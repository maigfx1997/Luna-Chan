package com.example.data.remote

import android.net.Uri
import android.webkit.CookieManager
import com.example.model.Chapter
import com.example.model.ChapterRef
import com.example.model.CustomNovelSite
import com.example.model.DiscoveredNovel
import com.example.model.Novel
import com.example.model.ScrapeOutcome
import com.example.model.SourcePlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.concurrent.TimeUnit

class NovelScraper(
    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(16, TimeUnit.SECONDS)
        .readTimeout(24, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
) {

    companion object {
        const val MOBILE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    }

    fun detectPlatform(url: String): SourcePlatform {
        val host = runCatching { Uri.parse(url).host?.lowercase().orEmpty() }.getOrDefault("")
        return when {
            host.contains("wattpad.com") -> SourcePlatform.WATTPAD
            host.contains("novlar.com") -> SourcePlatform.NOVLAR
            host.contains("uranus-novel.com") -> SourcePlatform.URANUS
            else -> SourcePlatform.GENERIC
        }
    }

    /**
     * Resolves short share links (such as Wattpad's w.tt / my.wattpad.com, bit.ly, etc.)
     * to their canonical story URL so the right site adapter handles them automatically.
     */
    suspend fun resolveRedirectUrl(rawUrl: String): String = withContext(Dispatchers.IO) {
        val normalized = normalizeUrl(rawUrl)
        val host = runCatching { Uri.parse(normalized).host?.lowercase().orEmpty() }.getOrDefault("")
        val isShortOrShareHost = host == "w.tt" ||
            host.endsWith(".w.tt") ||
            host == "my.wattpad.com" ||
            host == "bit.ly" ||
            host == "tinyurl.com" ||
            host == "t.co" ||
            host == "goo.gl" ||
            host == "rebrand.ly" ||
            host == "linkfly.to"
        if (!isShortOrShareHost) return@withContext normalized

        runCatching {
            val req = Request.Builder()
                .url(normalized)
                .header("User-Agent", MOBILE_USER_AGENT)
                .get()
                .build()
            httpClient.newCall(req).execute().use { response ->
                response.request.url.toString()
            }
        }.getOrDefault(normalized)
    }

    /**
     * Stage 1: Direct fast fetch via dedicated APIs / HTML adapters.
     * Returns ReadyNovel (single chapter), NeedsChapterSelection (TOC), or RequiresBrowser (JS/Cloudflare).
     */
    suspend fun discoverNovel(
        rawUrl: String,
        includeChapterImages: Boolean,
        mediaCache: MediaCacheManager?,
        customSite: CustomNovelSite? = null,
        onProgress: (Float, String) -> Unit
    ): ScrapeOutcome = withContext(Dispatchers.IO) {
        onProgress(0.15f, "connecting")
        val url = resolveRedirectUrl(rawUrl)
        val platform = detectPlatform(url)
        onProgress(0.25f, "connecting")

        // Respect user-configured "Always open via Smart Browser" rule for this custom site
        if (customSite?.useSmartBrowserDefault == true) {
            return@withContext ScrapeOutcome.RequiresBrowser(url, "Custom site browser mode")
        }

        // 1) Wattpad Dedicated JSON API Adapter (fastest & most reliable!)
        if (platform == SourcePlatform.WATTPAD) {
            val wattpadOutcome = runCatching {
                discoverWattpadViaApi(url, onProgress)
            }.getOrNull()
            if (wattpadOutcome != null) {
                return@withContext wattpadOutcome
            }
        }

        // 2) Direct HTTP HTML Fetch (using shared WebView cookies if available!)
        val fetchResult = runCatching { httpGetText(url, referer = url) }
        val html = fetchResult.getOrNull()
        if (html == null) {
            val reason = fetchResult.exceptionOrNull()?.message ?: "HTTP blocked"
            return@withContext ScrapeOutcome.RequiresBrowser(url, reason)
        }

        if (isChallengeOrEmptyShell(html)) {
            return@withContext ScrapeOutcome.RequiresBrowser(url, "Cloudflare/JS")
        }

        onProgress(0.62f, "parsing")
        parseRenderedHtml(
            pageUrl = url,
            html = html,
            includeChapterImages = includeChapterImages,
            mediaCache = mediaCache,
            fromBrowser = false,
            customSite = customSite,
            onProgress = onProgress
        )
    }

    /**
     * Parses HTML obtained either from direct HTTP OR from the Smart Browser WebView DOM snapshot!
     */
    suspend fun parseRenderedHtml(
        pageUrl: String,
        html: String,
        includeChapterImages: Boolean,
        mediaCache: MediaCacheManager?,
        fromBrowser: Boolean,
        customSite: CustomNovelSite? = null,
        onProgress: (Float, String) -> Unit
    ): ScrapeOutcome = withContext(Dispatchers.IO) {
        val normalizedUrl = normalizeUrl(pageUrl)
        val platform = detectPlatform(normalizedUrl)
        val doc = Jsoup.parse(html, normalizedUrl)

        // If the user opened a Wattpad story inside Smart Browser, still try Wattpad API first for full TOC!
        if (platform == SourcePlatform.WATTPAD) {
            val apiAttempt = runCatching { discoverWattpadViaApi(normalizedUrl, onProgress) }.getOrNull()
            if (apiAttempt != null) return@withContext apiAttempt
        }

        val title = extractTitle(doc, platform, customSite)
        val author = extractAuthor(doc, platform, customSite)
        val description = extractDescription(doc, platform, customSite)
        val coverUrl = extractCoverUrl(doc, platform, customSite)

        // Attempt to discover a Table of Contents (multiple chapters) on this page
        val discoveredChapters = discoverChaptersFromDocument(doc, normalizedUrl, platform, customSite)
        if (discoveredChapters.size >= 2 || (discoveredChapters.size == 1 && isLikelyIndexPage(normalizedUrl, doc, platform))) {
            onProgress(0.95f, "finishing")
            return@withContext ScrapeOutcome.NeedsChapterSelection(
                DiscoveredNovel(
                    url = normalizedUrl,
                    title = title,
                    author = author,
                    description = description,
                    coverUrl = coverUrl,
                    platform = platform,
                    chapters = discoveredChapters
                )
            )
        }

        // Otherwise treat as a single chapter / story reading page
        onProgress(0.8f, "parsing")
        val extractedChapter = extractSingleChapterFromDoc(doc, normalizedUrl, title, platform, customSite)

        // If a direct fetch on a details page yielded almost no body text and no chapters, it's likely JS-rendered!
        if (!fromBrowser && extractedChapter.content.length < 180 && extractedChapter.images.isEmpty()) {
            return@withContext ScrapeOutcome.RequiresBrowser(normalizedUrl, "Dynamic JS content")
        }

        onProgress(0.92f, "finishing")
        val cachedCover = mediaCache?.downloadImageToLocal(coverUrl, normalizedUrl, "covers") ?: coverUrl
        val finalImages = if (includeChapterImages) {
            extractedChapter.images.mapNotNull { img ->
                mediaCache?.downloadImageToLocal(img, normalizedUrl, "chapter_images") ?: img
            }
        } else {
            emptyList()
        }

        ScrapeOutcome.ReadyNovel(
            Novel(
                id = System.currentTimeMillis().toString(),
                url = normalizedUrl,
                title = title,
                author = author,
                description = description,
                coverUri = cachedCover,
                chapters = listOf(extractedChapter.copy(images = finalImages)),
                savedAt = System.currentTimeMillis()
            )
        )
    }

    /**
     * Stage 2: Fetch a single chapter's full text + illustrations (for batch downloading selected TOC chapters).
     */
    suspend fun fetchChapter(
        ref: ChapterRef,
        platform: SourcePlatform,
        refererUrl: String,
        includeImages: Boolean,
        mediaCache: MediaCacheManager?,
        customSite: CustomNovelSite? = null
    ): Chapter = withContext(Dispatchers.IO) {
        if (platform == SourcePlatform.WATTPAD && !ref.wattpadPartId.isNullOrBlank()) {
            return@withContext fetchWattpadPart(
                partId = ref.wattpadPartId,
                partTitle = ref.title,
                partUrl = ref.url,
                includeImages = includeImages,
                mediaCache = mediaCache
            )
        }

        val html = httpGetText(ref.url, referer = refererUrl)
        if (isChallengeOrEmptyShell(html)) {
            throw IllegalStateException("Protected chapter page")
        }
        val doc = Jsoup.parse(html, ref.url)
        val rawChapter = extractSingleChapterFromDoc(doc, ref.url, ref.title, platform, customSite)
        val finalImages = if (includeImages) {
            rawChapter.images.mapNotNull { imgUrl ->
                mediaCache?.downloadImageToLocal(imgUrl, ref.url, "chapter_images") ?: imgUrl
            }
        } else {
            emptyList()
        }
        rawChapter.copy(
            id = ref.id,
            title = ref.title.ifBlank { rawChapter.title },
            images = finalImages
        )
    }

    /* =========================================================================
       WATTPAD ADAPTER (API v3 metadata/TOC + API v2 multi-page storytext)
       ========================================================================= */
    private fun discoverWattpadViaApi(
        url: String,
        onProgress: (Float, String) -> Unit
    ): ScrapeOutcome? {
        var storyId = Regex("/story/(\\d+)").find(url)?.groupValues?.getOrNull(1)

        // Support Wattpad direct part URLs like https://www.wattpad.com/123456789-chapter-name
        if (storyId == null) {
            val partId = Regex("wattpad\\.com/(\\d+)").find(url)?.groupValues?.getOrNull(1)
            if (partId != null) {
                val partJsonStr = httpGetText(
                    "https://www.wattpad.com/api/v3/parts/$partId?fields=id,title,groupId",
                    referer = "https://www.wattpad.com/",
                    acceptJson = true
                )
                val partObj = JSONObject(partJsonStr)
                val groupId = partObj.optString("groupId")
                if (groupId.isNotBlank()) storyId = groupId
            }
        }

        if (storyId.isNullOrBlank()) return null

        onProgress(0.45f, "parsing")
        val apiUrl = "https://www.wattpad.com/api/v3/stories/$storyId" +
            "?fields=id,title,description,cover,user(name,fullname),parts(id,title,url,deleted,draft,isPaid)"
        val jsonStr = httpGetText(apiUrl, referer = "https://www.wattpad.com/", acceptJson = true)
        val obj = JSONObject(jsonStr)

        val title = obj.optString("title").trim().ifBlank { "Wattpad Story" }
        val description = obj.optString("description").trim()
        val cover = obj.optString("cover").trim().ifBlank { null }
        val userObj = obj.optJSONObject("user")
        val author = userObj?.optString("fullname")?.takeIf { it.isNotBlank() }
            ?: userObj?.optString("name")?.takeIf { it.isNotBlank() }
            ?: "Wattpad Author"

        val partsArr = obj.optJSONArray("parts") ?: return null
        val refs = mutableListOf<ChapterRef>()
        for (i in 0 until partsArr.length()) {
            val p = partsArr.optJSONObject(i) ?: continue
            if (p.optBoolean("deleted", false) || p.optBoolean("draft", false)) continue
            val pid = p.optString("id")
            if (pid.isBlank()) continue
            val pTitle = p.optString("title").trim().ifBlank { "Part ${i + 1}" }
            val pUrl = p.optString("url").ifBlank { "https://www.wattpad.com/$pid" }
            val isPaid = p.optBoolean("isPaid", false)
            refs.add(
                ChapterRef(
                    id = "wp_$pid",
                    title = pTitle,
                    url = pUrl,
                    wattpadPartId = pid,
                    isLocked = isPaid
                )
            )
        }

        if (refs.isEmpty()) return null
        onProgress(0.95f, "finishing")
        return ScrapeOutcome.NeedsChapterSelection(
            DiscoveredNovel(
                url = url,
                title = title,
                author = author,
                description = description,
                coverUrl = cover,
                platform = SourcePlatform.WATTPAD,
                chapters = refs
            )
        )
    }

    private suspend fun fetchWattpadPart(
        partId: String,
        partTitle: String,
        partUrl: String,
        includeImages: Boolean,
        mediaCache: MediaCacheManager?
    ): Chapter {
        val allParagraphs = mutableListOf<String>()
        val allImages = mutableListOf<String>()
        var page = 0
        var lastChunkSignature = ""

        while (page < 40) {
            val endpoint = "https://www.wattpad.com/apiv2/?m=storytext&id=$partId&page=$page"
            val chunk = runCatching {
                httpGetText(endpoint, referer = partUrl, acceptJson = false)
            }.getOrNull() ?: break

            val trimmed = chunk.trim()
            if (trimmed.isEmpty() || trimmed == lastChunkSignature) break
            lastChunkSignature = trimmed

            val fragment = Jsoup.parseBodyFragment(trimmed, "https://www.wattpad.com/")
            var addedSomething = false
            fragment.select("p, h1, h2, h3, blockquote, img").forEach { el ->
                if (el.tagName().equals("img", ignoreCase = true)) {
                    val src = el.absUrl("data-src").ifBlank { el.absUrl("src") }
                    if (src.isNotBlank() && !isNoiseImage(src) && !allImages.contains(src)) {
                        allImages.add(src)
                        addedSomething = true
                    }
                } else {
                    val txt = el.text().trim()
                    if (txt.isNotBlank()) {
                        allParagraphs.add(txt)
                        addedSomething = true
                    }
                }
            }
            if (!addedSomething) {
                val fallbackText = fragment.text().trim()
                if (fallbackText.isNotBlank()) {
                    allParagraphs.add(fallbackText)
                } else {
                    break
                }
            }
            // Wattpad apiv2 sometimes returns full chapter on page 0 or paginated pages; stop if short
            if (trimmed.length < 300) break
            page++
        }

        if (allParagraphs.isEmpty() && allImages.isEmpty()) {
            // Fallback to HTML scrape of partUrl if apiv2 returned empty
            val html = httpGetText(partUrl, referer = "https://www.wattpad.com/")
            val doc = Jsoup.parse(html, partUrl)
            val fallback = extractSingleChapterFromDoc(doc, partUrl, partTitle, SourcePlatform.WATTPAD)
            val cachedImgs = if (includeImages) {
                fallback.images.mapNotNull { mediaCache?.downloadImageToLocal(it, partUrl, "chapter_images") ?: it }
            } else emptyList()
            return fallback.copy(id = "wp_$partId", title = partTitle, images = cachedImgs)
        }

        val cachedImages = if (includeImages) {
            allImages.mapNotNull { img ->
                mediaCache?.downloadImageToLocal(img, partUrl, "chapter_images") ?: img
            }
        } else {
            emptyList()
        }

        return Chapter(
            id = "wp_$partId",
            title = partTitle,
            content = allParagraphs.joinToString("\n\n"),
            images = cachedImages
        )
    }

    /* =========================================================================
       METADATA EXTRACTORS (Novlar, Uranus Novel, & Universal Sites)
       ========================================================================= */
    private fun extractTitle(
        doc: Document,
        platform: SourcePlatform,
        customSite: CustomNovelSite? = null
    ): String {
        val customSel = customSite?.titleSelector?.trim().orEmpty()
        val baseCandidates = when (platform) {
            SourcePlatform.NOVLAR -> listOf(
                ".novel-title", ".book-title", ".story-title", "h1.title", "h1",
                "meta[property=og:title]"
            )
            SourcePlatform.URANUS -> listOf(
                "h1.novel-title", ".novel-header h1", "h1", "meta[property=og:title]"
            )
            else -> listOf(
                "meta[property=og:title]", "h1.entry-title", "h1.novel-title",
                ".post-title h1", ".book-title", "h1"
            )
        }
        val candidates = if (customSel.isNotBlank()) listOf(customSel) + baseCandidates else baseCandidates

        for (selector in candidates) {
            val value = runCatching {
                if (selector.startsWith("meta")) {
                    doc.selectFirst(selector)?.attr("content")?.trim()
                } else {
                    doc.selectFirst(selector)?.text()?.trim()
                }
            }.getOrNull()
            if (!value.isNullOrBlank() && value.length <= 160) {
                return cleanSiteSuffix(value)
            }
        }
        return cleanSiteSuffix(doc.title().trim()).ifBlank { "Untitled Story" }
    }

    private fun extractAuthor(
        doc: Document,
        platform: SourcePlatform,
        customSite: CustomNovelSite? = null
    ): String {
        val customSel = customSite?.authorSelector?.trim().orEmpty()
        val baseCandidates = when (platform) {
            SourcePlatform.NOVLAR -> listOf(
                ".author-name", ".novel-author", "a[href*=author]", "a[href*=profile]",
                "a[href*=user]", "[class*=author]", "meta[name=author]"
            )
            SourcePlatform.URANUS -> listOf(
                ".author-name", "a[href*=author]", "a[href*=writers]", "a[href*=users]",
                "[class*=author]", "meta[name=author]"
            )
            else -> listOf(
                "meta[name=author]", "[rel=author]", ".author-content a", ".author-name",
                ".author a", ".author", ".byline", "[class*=author]"
            )
        }
        val candidates = if (customSel.isNotBlank()) listOf(customSel) + baseCandidates else baseCandidates

        for (selector in candidates) {
            val value = runCatching {
                if (selector.startsWith("meta")) {
                    doc.selectFirst(selector)?.attr("content")?.trim()
                } else {
                    doc.selectFirst(selector)?.text()?.trim()
                }
            }.getOrNull()
            if (!value.isNullOrBlank() && value.length in 2..80) {
                return value
                    .removePrefix("بقلم:")
                    .removePrefix("الكاتب:")
                    .removePrefix("By ")
                    .removePrefix("by ")
                    .trim()
            }
        }
        return "Unknown Author"
    }

    private fun extractDescription(
        doc: Document,
        platform: SourcePlatform,
        customSite: CustomNovelSite? = null
    ): String {
        val customSel = customSite?.descriptionSelector?.trim().orEmpty()
        val baseCandidates = when (platform) {
            SourcePlatform.NOVLAR -> listOf(
                ".novel-description", ".story-description", ".synopsis", ".summary",
                ".description", "#description", "[class*=desc]", "meta[property=og:description]",
                "meta[name=description]"
            )
            SourcePlatform.URANUS -> listOf(
                ".novel-synopsis", ".novel-description", ".summary", ".synopsis",
                ".description", "[class*=summary]", "[class*=desc]",
                "meta[property=og:description]", "meta[name=description]"
            )
            else -> listOf(
                ".summary__content", ".description-summary", ".novel-synopsis", ".synopsis",
                ".summary", ".book-description", ".description",
                "meta[property=og:description]", "meta[name=description]"
            )
        }
        val candidates = if (customSel.isNotBlank()) listOf(customSel) + baseCandidates else baseCandidates

        for (selector in candidates) {
            val value = runCatching {
                if (selector.startsWith("meta")) {
                    doc.selectFirst(selector)?.attr("content")?.trim()
                } else {
                    val el = doc.selectFirst(selector)
                    el?.select("p")?.map { it.text().trim() }?.filter { it.isNotBlank() }
                        ?.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
                        ?: el?.text()?.trim()
                }
            }.getOrNull()
            if (!value.isNullOrBlank() && value.length >= 12) {
                return value
            }
        }
        return ""
    }

    private fun extractCoverUrl(
        doc: Document,
        platform: SourcePlatform,
        customSite: CustomNovelSite? = null
    ): String? {
        val customSel = customSite?.coverSelector?.trim().orEmpty()
        val baseSelectors = when (platform) {
            SourcePlatform.NOVLAR -> listOf(
                ".novel-cover img", ".book-cover img", ".cover img",
                "img[src*=cover]", "img[src*=upload]", "meta[property=og:image]",
                "[class*=cover] img"
            )
            SourcePlatform.URANUS -> listOf(
                ".novel-cover img", "img[src*=/storage/]", ".cover img",
                "meta[property=og:image]", "[class*=cover] img", "img[class*=thumb]"
            )
            else -> listOf(
                "meta[property=og:image]", ".summary_image img", ".novel-cover img",
                ".book-cover img", ".cover img", "[class*=cover] img", "article img"
            )
        }
        val selectors = if (customSel.isNotBlank()) listOf(customSel) + baseSelectors else baseSelectors

        for (selector in selectors) {
            val candidate = runCatching {
                if (selector.startsWith("meta")) {
                    doc.selectFirst(selector)?.absUrl("content")
                } else {
                    val el = doc.selectFirst(selector)
                    when {
                        el == null -> null
                        el.tagName().equals("img", ignoreCase = true) -> resolveImageSrc(el)
                        else -> el.selectFirst("img")?.let { resolveImageSrc(it) }
                    }
                }
            }.getOrNull()
            if (!candidate.isNullOrBlank() && !isNoiseImage(candidate)) {
                return candidate
            }
        }
        return null
    }

    /* =========================================================================
       CHAPTER LIST (TOC) DISCOVERY (Novlar, Uranus, Custom Sites, & Universal Clustering)
       ========================================================================= */
    private fun discoverChaptersFromDocument(
        doc: Document,
        pageUrl: String,
        platform: SourcePlatform,
        customSite: CustomNovelSite? = null
    ): List<ChapterRef> {
        val pageHost = runCatching { Uri.parse(pageUrl).host?.lowercase().orEmpty() }.getOrDefault("")
        val cleanPageUrl = pageUrl.substringBefore("#").trimEnd('/')

        val customChapSel = customSite?.chapterListSelector?.trim().orEmpty()
        // 1) Custom site + platform-specific TOC container selectors first
        val basePlatformSelectors = when (platform) {
            SourcePlatform.NOVLAR -> listOf(
                "a[href*=chapter]", "a[href*=read.php]", "a[href*=part]",
                "a[href*=episode]", ".chapters-list a", ".chapter-list a",
                "#chapters a", "[class*=chapter] a"
            )
            SourcePlatform.URANUS -> listOf(
                "a[href*=/chapters/]", "a[href*=/chapter/]", "a[href*=/read/]",
                ".chapters-list a", ".chapter-list a", "#chapters a", "[class*=chapter] a"
            )
            else -> listOf(
                ".wp-manga-chapter a", ".chapter-list a", ".chapters-list a",
                "#chapterlist a", ".eplister a", "ul.chapters a", ".toc a",
                "a[href*=/chapter]", "a[href*=/chapters/]", "a[href*=chapter_id]",
                "a[href*=chapterId]", "a[href*=read.php]"
            )
        }
        val platformSelectors = if (customChapSel.isNotBlank()) {
            listOf(customChapSel, "$customChapSel a") + basePlatformSelectors
        } else {
            basePlatformSelectors
        }

        val collected = LinkedHashMap<String, ChapterRef>()

        fun considerAnchor(a: Element) {
            val href = a.absUrl("href").substringBefore("#").trim()
            if (href.isBlank() || href.trimEnd('/') == cleanPageUrl) return
            if (!href.startsWith("http://") && !href.startsWith("https://")) return
            val linkHost = runCatching { Uri.parse(href).host?.lowercase().orEmpty() }.getOrDefault("")
            if (pageHost.isNotBlank() && linkHost != pageHost) return

            val lowerHref = href.lowercase()
            if (isNavigationOrNoiseLink(lowerHref)) return

            val label = a.text().trim().replace(Regex("\\s+"), " ")
            if (label.isBlank() || label.length > 140) return
            if (isNoiseLabel(label)) return

            if (!collected.containsKey(href)) {
                val isLocked = a.hasClass("locked") ||
                    a.selectFirst("[class*=lock], .fa-lock, .premium, .vip") != null ||
                    label.contains("🔒")
                collected[href] = ChapterRef(
                    id = "ch_${collected.size + 1}_${href.hashCode()}",
                    title = label,
                    url = href,
                    isLocked = isLocked
                )
            }
        }

        for (selector in platformSelectors) {
            runCatching {
                doc.select(selector).forEach { el ->
                    if (el.tagName().equals("a", ignoreCase = true)) {
                        considerAnchor(el)
                    } else {
                        el.select("a[href]").forEach { considerAnchor(it) }
                    }
                }
            }
            if (collected.size >= 3) break
        }

        // 2) Universal smart anchor heuristic fallback (catches Arabic/custom chapter URLs on ANY site)
        if (collected.size < 2) {
            val novelIdMatch = Regex("(?:novels?/|id=)(\\d+)").find(pageUrl)?.groupValues?.getOrNull(1)
            doc.select("a[href]").forEach { a ->
                val href = a.absUrl("href").lowercase()
                val text = a.text().trim()
                val matchesChapterText = Regex(
                    "(الفصل|فصل|البارت|بارت|الحلقة|جزء|chapter|ch\\.?\\s*\\d|part\\s*\\d|episode|chapitre)",
                    RegexOption.IGNORE_CASE
                ).containsMatchIn(text)
                val matchesChapterHref = href.contains("chapter") ||
                    href.contains("read") ||
                    href.contains("part") ||
                    href.contains("episode") ||
                    (novelIdMatch != null && href.contains(novelIdMatch) && href != cleanPageUrl.lowercase())
                if (matchesChapterText || matchesChapterHref) {
                    considerAnchor(a)
                }
            }
        }

        return collected.values.toList()
    }

    private fun isLikelyIndexPage(url: String, doc: Document, platform: SourcePlatform): Boolean {
        val lower = url.lowercase()
        if (platform == SourcePlatform.NOVLAR && lower.contains("details.php")) return true
        if (platform == SourcePlatform.URANUS && Regex("/novels/\\d+/?$").containsMatchIn(lower)) return true
        if (lower.contains("/novel/") || lower.contains("/book/") || lower.contains("/story/")) {
            val hasChapterContainer = doc.selectFirst(".chapter-list, .chapters-list, #chapters, .wp-manga-chapter") != null
            if (hasChapterContainer) return true
        }
        return false
    }

    /* =========================================================================
       SINGLE CHAPTER TEXT & ILLUSTRATION EXTRACTOR
       ========================================================================= */
    private fun extractSingleChapterFromDoc(
        doc: Document,
        baseUrl: String,
        fallbackTitle: String,
        platform: SourcePlatform,
        customSite: CustomNovelSite? = null
    ): Chapter {
        val workDoc = doc.clone()
        workDoc.select(
            "script, style, nav, header, footer, aside, iframe, form, button, " +
                ".ads, .advertisement, .comments, #comments, .comment-section, " +
                ".chapter-nav, .nav-links, .share-buttons, .social-share, noscript"
        ).remove()

        val customBodySel = customSite?.chapterContentSelector?.trim().orEmpty()
        val baseContentSelectors = when (platform) {
            SourcePlatform.NOVLAR -> listOf(
                "#chapter-content", ".chapter-content", ".reading-content",
                ".chapter-text", ".story-content", "article", "main"
            )
            SourcePlatform.URANUS -> listOf(
                ".chapter-content", "#chapter-content", ".reading-content",
                ".chapter-body", ".novel-content", "article", "main"
            )
            else -> listOf(
                ".reading-content", "#chapter-content", ".chapter-content",
                ".chapter-inner", ".chapter-c", ".text-left", ".entry-content",
                ".story-text", "[class*=chapter-content]", "[class*=chapter-body]",
                "article", "main"
            )
        }
        val contentSelectors = if (customBodySel.isNotBlank()) {
            listOf(customBodySel) + baseContentSelectors
        } else {
            baseContentSelectors
        }

        var bestContainer: Element? = null
        var bestScore = 0

        for (selector in contentSelectors) {
            runCatching {
                workDoc.select(selector).forEach { candidate ->
                    val textLen = candidate.text().trim().length
                    val pCount = candidate.select("p").size
                    val score = textLen + pCount * 40
                    if (score > bestScore) {
                        bestScore = score
                        bestContainer = candidate
                    }
                }
            }
            if (bestScore > 400 || (selector == customBodySel && bestScore > 60)) break
        }

        val target = bestContainer ?: findDensestTextContainer(workDoc.body()) ?: workDoc.body()
        val chHeading = target?.selectFirst("h1, h2, .chapter-title")?.text()?.trim()
            ?.takeIf { it.length in 2..120 }
            ?: fallbackTitle

        val (text, images) = if (target != null) {
            extractCleanTextAndImages(target)
        } else {
            "" to emptyList()
        }

        return Chapter(
            id = "ch_${System.currentTimeMillis()}",
            title = chHeading,
            content = text.ifBlank { "..." },
            images = images
        )
    }

    private fun findDensestTextContainer(body: Element?): Element? {
        if (body == null) return null
        var best: Element? = null
        var bestScore = 0
        body.select("div, section, article").forEach { el ->
            val directParagraphs = el.children().filter { it.tagName() == "p" }
            val score = directParagraphs.sumOf { it.text().trim().length }
            if (score > bestScore) {
                bestScore = score
                best = el
            }
        }
        return best
    }

    private fun extractCleanTextAndImages(container: Element): Pair<String, List<String>> {
        val paragraphs = mutableListOf<String>()
        val images = mutableListOf<String>()
        val seenLines = HashSet<String>()

        container.select("p, h2, h3, blockquote, img").forEach { el ->
            if (el.tagName().equals("img", ignoreCase = true)) {
                val src = resolveImageSrc(el)
                if (!src.isNullOrBlank() && !isNoiseImage(src) && !images.contains(src)) {
                    images.add(src)
                }
            } else {
                val txt = el.text().trim()
                if (txt.isNotBlank() && seenLines.add(txt)) {
                    paragraphs.add(txt)
                }
            }
        }

        // Handle sites that use <br> separated text inside a div without <p> tags
        val finalText = if (paragraphs.size <= 1 && container.select("br").size >= 3) {
            val brHtml = container.html()
                .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
                .replace(Regex("</(div|p|h[1-6])>", RegexOption.IGNORE_CASE), "\n\n")
            Jsoup.parse(brHtml).wholeText()
                .lines()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .joinToString("\n\n")
        } else if (paragraphs.isNotEmpty()) {
            paragraphs.joinToString("\n\n")
        } else {
            container.text().trim()
        }

        return finalText to images
    }

    /* =========================================================================
       HTTP & HELPER UTILITIES (Shared WebView Cookies + Challenge Detection)
       ========================================================================= */
    private fun httpGetText(
        url: String,
        referer: String? = null,
        acceptJson: Boolean = false
    ): String {
        val reqBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", MOBILE_USER_AGENT)
            .header(
                "Accept",
                if (acceptJson) "application/json, text/plain, */*"
                else "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )
            .header("Accept-Language", "ar,en-US;q=0.9,en;q=0.8,fr;q=0.7")

        if (!referer.isNullOrBlank()) {
            reqBuilder.header("Referer", referer)
        }

        val cookies = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
        if (!cookies.isNullOrBlank()) {
            reqBuilder.header("Cookie", cookies)
        }

        httpClient.newCall(reqBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}")
            }
            return response.body?.string() ?: throw IllegalStateException("Empty response")
        }
    }

    private fun isChallengeOrEmptyShell(html: String): Boolean {
        val lower = html.lowercase()
        val isCloudflare = lower.contains("cf-browser-verification") ||
            lower.contains("challenge-platform") ||
            lower.contains("just a moment...") ||
            lower.contains("enable javascript and cookies to continue") ||
            lower.contains("attention required! | cloudflare")
        if (isCloudflare) return true
        val strippedLength = Jsoup.parse(html).body()?.text()?.trim()?.length ?: 0
        return strippedLength < 80
    }

    private fun resolveImageSrc(img: Element): String? {
        val attrs = listOf("data-original", "data-lazy-src", "data-src", "src")
        for (attr in attrs) {
            val resolved = img.absUrl(attr)
            if (resolved.isNotBlank() && !resolved.startsWith("data:")) {
                return resolved
            }
        }
        return null
    }

    private fun normalizeUrl(raw: String): String {
        val trimmed = raw.trim()
        val withScheme = when {
            trimmed.startsWith("wattpad://", ignoreCase = true) -> {
                val path = trimmed.substringAfter("://").trimStart('/')
                "https://www.wattpad.com/$path"
            }
            trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            else -> "https://$trimmed"
        }
        return runCatching {
            val uri = Uri.parse(withScheme)
            if (uri.queryParameterNames.isEmpty()) return@runCatching withScheme
            val noisyKeys = setOf("utm_source", "utm_medium", "utm_campaign", "utm_content", "utm_term", "fbclid", "si")
            val kept = uri.queryParameterNames.filterNot { it.lowercase() in noisyKeys }
            if (kept.size == uri.queryParameterNames.size) return@runCatching withScheme
            val builder = uri.buildUpon().clearQuery()
            for (k in kept) {
                uri.getQueryParameters(k).forEach { v -> builder.appendQueryParameter(k, v) }
            }
            builder.build().toString()
        }.getOrDefault(withScheme)
    }

    private fun cleanSiteSuffix(title: String): String {
        return title
            .replace(Regex("\\s*[|\\-–—]\\s*(Novlar|Uranus Novel|Wattpad).*$", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    private fun isNoiseImage(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("avatar") ||
            lower.contains("icon") ||
            lower.contains("emoji") ||
            lower.contains("logo") ||
            lower.contains("spinner") ||
            lower.contains("loading") ||
            lower.contains("placeholder") ||
            lower.endsWith(".svg") ||
            lower.startsWith("data:image/svg")
    }

    private fun isNavigationOrNoiseLink(lowerHref: String): Boolean {
        return lowerHref.contains("login") ||
            lowerHref.contains("register") ||
            lowerHref.contains("signup") ||
            lowerHref.contains("privacy") ||
            lowerHref.contains("terms") ||
            lowerHref.contains("contact") ||
            lowerHref.contains("category") ||
            lowerHref.contains("genre") ||
            lowerHref.contains("/tag/") ||
            lowerHref.contains("javascript:") ||
            lowerHref.contains("mailto:") ||
            lowerHref.contains("facebook.com") ||
            lowerHref.contains("twitter.com") ||
            lowerHref.contains("whatsapp")
    }

    private fun isNoiseLabel(label: String): Boolean {
        val lower = label.lowercase()
        return lower in setOf(
            "home", "login", "register", "next", "prev", "previous",
            "الرئيسية", "تسجيل الدخول", "حساب جديد", "التالي", "السابق", "قراءة الآن", "ابدأ القراءة"
        )
    }
}
