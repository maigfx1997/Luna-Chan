package com.example.ui.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.CustomSiteRepository
import com.example.data.local.LunaDatabase
import com.example.data.local.NovelHistoryRepository
import com.example.data.local.PreferencesRepository
import com.example.data.remote.MediaCacheManager
import com.example.data.remote.NovelScraper
import com.example.export.EbookExporter
import com.example.model.AppLanguage
import com.example.model.BatchDownloadProgress
import com.example.model.Chapter
import com.example.model.CustomNovelSite
import com.example.model.DiscoveredNovel
import com.example.model.EbookFormat
import com.example.model.ExportResult
import com.example.model.LunaLocalization
import com.example.model.MultiExportMode
import com.example.model.Novel
import com.example.model.ScrapeOutcome
import com.example.ui.components.IncomingShareRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

data class LunaUiState(
    val language: AppLanguage = AppLanguage.AR,
    val isDarkMode: Boolean = false,
    val includeChapterImages: Boolean = true,
    val preSelectedFormats: Set<EbookFormat> = setOf(EbookFormat.EPUB),
    val multiExportMode: MultiExportMode = MultiExportMode.SEPARATE_FILES,
    val shareReceiveEnabled: Boolean = true,
    val shareInstantFullConvert: Boolean = false,
    val isShareOptionsOpen: Boolean = false,
    val incomingShareRequest: IncomingShareRequest? = null,
    val customSites: List<CustomNovelSite> = emptyList(),
    val isCustomSitesOpen: Boolean = false,
    val urlInput: String = "",
    val isFetching: Boolean = false,
    val fetchProgress: Float = 0f,
    val fetchStageKey: String = "connecting",
    val currentNovel: Novel? = null,
    // Multi-chapter TOC Picker state
    val discoveredNovel: DiscoveredNovel? = null,
    val selectedChapterIds: Set<String> = emptySet(),
    val isBatchDownloading: Boolean = false,
    val batchProgress: BatchDownloadProgress = BatchDownloadProgress(),
    // Smart Browser WebView state
    val smartBrowserUrl: String? = null,
    val isBrowserExtracting: Boolean = false,
    // Modals & Export state
    val history: List<Novel> = emptyList(),
    val isHistoryOpen: Boolean = false,
    val isPreviewOpen: Boolean = false,
    val isExporting: Boolean = false,
    val exportingLabel: String? = null,
    val lastExportResult: ExportResult? = null,
    val confettiTrigger: Int = 0
)

class LunaViewModel(application: Application) : AndroidViewModel(application) {

    private val database = LunaDatabase.getInstance(application)
    private val prefsRepo = PreferencesRepository(application)
    private val historyRepo = NovelHistoryRepository(database.novelHistoryDao())
    private val customSiteRepo = CustomSiteRepository(database.customSiteDao())
    private val scraper = NovelScraper()
    private val mediaCache = MediaCacheManager(application, scraper.httpClient)
    private val exporter = EbookExporter(application, scraper.httpClient)

    private val _workingState = MutableStateFlow(LunaUiState())

    val uiState: StateFlow<LunaUiState> = combine(
        _workingState,
        prefsRepo.userPreferences,
        historyRepo.recentNovels,
        customSiteRepo.customSites
    ) { state, prefs, historyList, sites ->
        state.copy(
            language = prefs.language,
            isDarkMode = prefs.isDarkMode,
            includeChapterImages = prefs.includeChapterImages,
            preSelectedFormats = prefs.preSelectedFormats,
            multiExportMode = prefs.multiExportMode,
            shareReceiveEnabled = prefs.shareReceiveEnabled,
            shareInstantFullConvert = prefs.shareInstantFullConvert,
            history = historyList,
            customSites = sites
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = LunaUiState()
    )

    private val _snackbarEvents = MutableSharedFlow<String>(extraBufferCapacity = 6)
    val snackbarEvents = _snackbarEvents.asSharedFlow()

    private var batchDownloadJob: Job? = null
    @Volatile
    private var stopRequestedKeepDownloaded = false

    fun setLanguage(language: AppLanguage) {
        viewModelScope.launch { prefsRepo.setLanguage(language) }
    }

    fun toggleDarkMode() {
        val currentDark = uiState.value.isDarkMode
        viewModelScope.launch { prefsRepo.setDarkMode(!currentDark) }
    }

    fun setIncludeChapterImages(enabled: Boolean) {
        viewModelScope.launch { prefsRepo.setIncludeChapterImages(enabled) }
    }

    fun togglePreSelectedFormat(format: EbookFormat) {
        viewModelScope.launch {
            val current = prefsRepo.userPreferences.first().preSelectedFormats
            val updated = if (current.contains(format)) current - format else current + format
            prefsRepo.setPreSelectedFormats(updated)
        }
    }

    fun toggleAllPreSelectedFormats() {
        viewModelScope.launch {
            val current = prefsRepo.userPreferences.first().preSelectedFormats
            val updated = if (current.size == EbookFormat.entries.size) {
                emptySet()
            } else {
                EbookFormat.entries.toSet()
            }
            prefsRepo.setPreSelectedFormats(updated)
        }
    }

    fun setMultiExportMode(mode: MultiExportMode) {
        viewModelScope.launch { prefsRepo.setMultiExportMode(mode) }
    }

    fun setShareOptionsOpen(open: Boolean) {
        _workingState.update { it.copy(isShareOptionsOpen = open) }
    }

    /* =========================================================================
       CUSTOM NOVEL WEBSITES MANAGER (Add / Edit / Browse / Bookmark / Delete)
       ========================================================================= */
    fun setCustomSitesOpen(open: Boolean) {
        _workingState.update { it.copy(isCustomSitesOpen = open) }
    }

    fun saveCustomSite(
        editingId: String?,
        rawName: String,
        rawUrl: String,
        useSmartBrowser: Boolean,
        titleSel: String,
        authorSel: String,
        descSel: String,
        coverSel: String,
        chaptersSel: String,
        contentSel: String
    ): Boolean {
        val host = CustomSiteRepository.extractNormalizedHost(rawUrl)
        val strings = LunaLocalization.get(uiState.value.language)
        if (host == null) {
            _snackbarEvents.tryEmit(strings.customSitesInvalidUrlMsg)
            return false
        }
        val normalizedUrl = CustomSiteRepository.normalizeBaseUrl(rawUrl)
        val displayName = rawName.trim().ifBlank {
            host.substringBefore('.').replaceFirstChar { it.uppercase() }
        }
        val site = CustomNovelSite(
            id = editingId ?: "site_${System.currentTimeMillis()}_${host.hashCode()}",
            name = displayName,
            baseUrl = normalizedUrl,
            host = host,
            useSmartBrowserDefault = useSmartBrowser,
            titleSelector = titleSel.trim(),
            authorSelector = authorSel.trim(),
            descriptionSelector = descSel.trim(),
            coverSelector = coverSel.trim(),
            chapterListSelector = chaptersSel.trim(),
            chapterContentSelector = contentSel.trim(),
            addedAt = System.currentTimeMillis()
        )
        viewModelScope.launch {
            customSiteRepo.saveSite(site)
            val lang = prefsRepo.userPreferences.first().language
            _snackbarEvents.tryEmit(LunaLocalization.get(lang).customSitesSavedMsg)
        }
        return true
    }

    fun deleteCustomSite(id: String) {
        viewModelScope.launch {
            customSiteRepo.deleteSite(id)
            val lang = prefsRepo.userPreferences.first().language
            _snackbarEvents.tryEmit(LunaLocalization.get(lang).customSitesDeletedMsg)
        }
    }

    fun browseCustomSite(site: CustomNovelSite) {
        _workingState.update {
            it.copy(
                isCustomSitesOpen = false,
                urlInput = site.baseUrl
            )
        }
        openSmartBrowser(site.baseUrl)
    }

    fun saveSiteFromBrowser(pageUrl: String) {
        viewModelScope.launch {
            val lang = prefsRepo.userPreferences.first().language
            val strings = LunaLocalization.get(lang)
            val host = CustomSiteRepository.extractNormalizedHost(pageUrl)
            if (host == null) {
                _snackbarEvents.tryEmit(strings.customSitesInvalidUrlMsg)
                return@launch
            }
            val existing = customSiteRepo.findMatchingSite(pageUrl)
            val baseOrigin = runCatching {
                val u = Uri.parse(CustomSiteRepository.normalizeBaseUrl(pageUrl))
                "${u.scheme ?: "https"}://${u.host ?: host}"
            }.getOrDefault("https://$host")
            val site = existing?.copy(addedAt = System.currentTimeMillis()) ?: CustomNovelSite(
                id = "site_${System.currentTimeMillis()}_${host.hashCode()}",
                name = host.substringBefore('.').replaceFirstChar { it.uppercase() },
                baseUrl = baseOrigin,
                host = host,
                useSmartBrowserDefault = false,
                addedAt = System.currentTimeMillis()
            )
            customSiteRepo.saveSite(site)
            _snackbarEvents.tryEmit(strings.browserSiteSavedMsg)
        }
    }

    fun setShareReceiveEnabled(enabled: Boolean) {
        viewModelScope.launch {
            prefsRepo.setShareReceiveEnabled(enabled)
            applyShareReceiverComponentState(enabled)
            val lang = prefsRepo.userPreferences.first().language
            val strings = LunaLocalization.get(lang)
            _snackbarEvents.tryEmit(
                if (enabled) strings.shareAppsEnabledToast else strings.shareAppsDisabledToast
            )
        }
    }

    fun setShareInstantFullConvert(enabled: Boolean) {
        viewModelScope.launch { prefsRepo.setShareInstantFullConvert(enabled) }
    }

    private fun applyShareReceiverComponentState(enabled: Boolean) {
        runCatching {
            val app = getApplication<Application>()
            val component = ComponentName(app.packageName, "com.example.ShareToEbookReceiver")
            val newState = if (enabled) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            app.packageManager.setComponentEnabledSetting(
                component,
                newState,
                PackageManager.DONT_KILL_APP
            )
        }
    }

    fun updateUrlInput(newUrl: String) {
        _workingState.update { it.copy(urlInput = newUrl) }
    }

    /**
     * Handles incoming shared novels/fanfics from Wattpad, Novlar, Uranus Novel, browsers, or text selection.
     * Safe on both cold start (before UI subscribes) and warm start (onNewIntent).
     */
    fun handleIncomingSharedText(sharedText: String?, subjectHint: String? = null) {
        if (sharedText.isNullOrBlank()) return
        val extractedUrl = extractFirstUrl(sharedText) ?: return
        val titleHint = extractTitleHint(sharedText, extractedUrl, subjectHint)

        viewModelScope.launch {
            val prefs = prefsRepo.userPreferences.first()
            val strings = LunaLocalization.get(prefs.language)
            _workingState.update {
                it.copy(
                    urlInput = extractedUrl,
                    isShareOptionsOpen = false
                )
            }

            if (prefs.shareInstantFullConvert) {
                _snackbarEvents.tryEmit(strings.sharedLinkReceivedMsg)
                fetchNovelInternal(
                    explicitUrl = extractedUrl,
                    autoDownloadAllChapters = true
                )
            } else {
                _workingState.update {
                    it.copy(
                        incomingShareRequest = IncomingShareRequest(
                            url = extractedUrl,
                            sharedTitleHint = titleHint
                        )
                    )
                }
            }
        }
    }

    fun dismissIncomingShare() {
        _workingState.update { it.copy(incomingShareRequest = null) }
    }

    fun convertIncomingShareFullStory() {
        val req = _workingState.value.incomingShareRequest ?: return
        _workingState.update { it.copy(incomingShareRequest = null, urlInput = req.url) }
        viewModelScope.launch {
            val prefs = prefsRepo.userPreferences.first()
            // Ensure at least EPUB is selected if the user cleared all formats before tapping Convert Full Story
            if (prefs.preSelectedFormats.isEmpty()) {
                prefsRepo.setPreSelectedFormats(setOf(EbookFormat.EPUB))
            }
            fetchNovelInternal(
                explicitUrl = req.url,
                autoDownloadAllChapters = true
            )
        }
    }

    fun convertIncomingSharePickChapters() {
        val req = _workingState.value.incomingShareRequest ?: return
        _workingState.update { it.copy(incomingShareRequest = null, urlInput = req.url) }
        viewModelScope.launch {
            fetchNovelInternal(
                explicitUrl = req.url,
                autoDownloadAllChapters = false
            )
        }
    }

    fun convertClipboardShareText(clipText: String?) {
        viewModelScope.launch {
            val lang = prefsRepo.userPreferences.first().language
            val strings = LunaLocalization.get(lang)
            if (clipText.isNullOrBlank()) {
                _snackbarEvents.tryEmit(strings.clipboardEmptyMsg)
                return@launch
            }
            val extractedUrl = extractFirstUrl(clipText)
            if (extractedUrl == null) {
                _snackbarEvents.tryEmit(strings.shareAppsNoUrlMsg)
                return@launch
            }
            val titleHint = extractTitleHint(clipText, extractedUrl, null)
            _workingState.update {
                it.copy(
                    isShareOptionsOpen = false,
                    urlInput = extractedUrl,
                    incomingShareRequest = IncomingShareRequest(
                        url = extractedUrl,
                        sharedTitleHint = titleHint
                    )
                )
            }
        }
    }

    fun pasteFromClipboardText(clipText: String?) {
        viewModelScope.launch {
            val lang = prefsRepo.userPreferences.first().language
            val strings = LunaLocalization.get(lang)
            if (clipText.isNullOrBlank()) {
                _snackbarEvents.tryEmit(strings.clipboardEmptyMsg)
                return@launch
            }
            val extracted = extractFirstUrl(clipText) ?: clipText.trim()
            _workingState.update { it.copy(urlInput = extracted) }
            _snackbarEvents.tryEmit(strings.pastedUrlMsg)
        }
    }

    /* =========================================================================
       STAGE 1: SMART DISCOVERY & DUAL-ENGINE FETCH
       ========================================================================= */
    fun fetchNovelFromUrl() {
        val rawUrl = _workingState.value.urlInput.trim()
        viewModelScope.launch {
            fetchNovelInternal(explicitUrl = rawUrl, autoDownloadAllChapters = false)
        }
    }

    private suspend fun fetchNovelInternal(
        explicitUrl: String,
        autoDownloadAllChapters: Boolean
    ) {
        val prefs = prefsRepo.userPreferences.first()
        val strings = LunaLocalization.get(prefs.language)
        val url = explicitUrl.trim()
        if (url.isBlank()) {
            _snackbarEvents.tryEmit(strings.emptyUrlMsg)
            return
        }
        val working = _workingState.value
        if (working.isFetching || working.isBatchDownloading) return

        _workingState.update {
            it.copy(
                urlInput = url,
                isFetching = true,
                fetchProgress = 0.15f,
                fetchStageKey = "connecting",
                lastExportResult = null
            )
        }
        try {
            val matchedSite = customSiteRepo.findMatchingSite(url)
            val outcome = scraper.discoverNovel(
                rawUrl = url,
                includeChapterImages = prefs.includeChapterImages,
                mediaCache = mediaCache,
                customSite = matchedSite
            ) { progress, stage ->
                _workingState.update {
                    it.copy(fetchProgress = progress, fetchStageKey = stage)
                }
            }
            handleScrapeOutcome(
                outcome = outcome,
                openBrowserAutomatically = true,
                autoDownloadAllChapters = autoDownloadAllChapters
            )
        } catch (_: Exception) {
            _workingState.update { it.copy(isFetching = false) }
            _snackbarEvents.tryEmit(strings.protectedSiteMsg)
            openSmartBrowser(url)
        }
    }

    private suspend fun handleScrapeOutcome(
        outcome: ScrapeOutcome,
        openBrowserAutomatically: Boolean,
        autoDownloadAllChapters: Boolean = false
    ) {
        val prefs = prefsRepo.userPreferences.first()
        val strings = LunaLocalization.get(prefs.language)
        when (outcome) {
            is ScrapeOutcome.ReadyNovel -> {
                _workingState.update {
                    it.copy(
                        isFetching = false,
                        isBrowserExtracting = false,
                        smartBrowserUrl = null,
                        discoveredNovel = null,
                        currentNovel = outcome.novel,
                        lastExportResult = null
                    )
                }
                triggerAutoExportIfConfigured(outcome.novel)
            }
            is ScrapeOutcome.NeedsChapterSelection -> {
                val defaultSelected = outcome.discovered.chapters
                    .filterNot { it.isLocked }
                    .map { it.id }
                    .toSet()
                    .ifEmpty { outcome.discovered.chapters.map { it.id }.toSet() }
                _workingState.update {
                    it.copy(
                        isFetching = false,
                        isBrowserExtracting = false,
                        smartBrowserUrl = null,
                        discoveredNovel = outcome.discovered,
                        selectedChapterIds = defaultSelected,
                        lastExportResult = null
                    )
                }
                if (autoDownloadAllChapters && defaultSelected.isNotEmpty()) {
                    startBatchChapterDownload(
                        discoveredOverride = outcome.discovered,
                        selectedIdsOverride = defaultSelected
                    )
                }
            }
            is ScrapeOutcome.RequiresBrowser -> {
                _workingState.update {
                    it.copy(
                        isFetching = false,
                        isBrowserExtracting = false
                    )
                }
                if (openBrowserAutomatically) {
                    _snackbarEvents.tryEmit(strings.protectedSiteMsg)
                    openSmartBrowser(outcome.url)
                } else {
                    _snackbarEvents.tryEmit(strings.browserExtractEmptyMsg)
                }
            }
        }
    }

    /* =========================================================================
       SMART BROWSER (WEBVIEW) CONTROLS
       ========================================================================= */
    fun openSmartBrowser(initialUrl: String? = null) {
        val target = (initialUrl ?: _workingState.value.urlInput).trim().ifBlank { "https://www.google.com" }
        val normalized = if (target.startsWith("http://", true) || target.startsWith("https://", true)) {
            target
        } else {
            "https://$target"
        }
        _workingState.update {
            it.copy(
                smartBrowserUrl = normalized,
                isBrowserExtracting = false
            )
        }
    }

    fun closeSmartBrowser() {
        _workingState.update {
            it.copy(
                smartBrowserUrl = null,
                isBrowserExtracting = false
            )
        }
    }

    fun extractFromBrowserHtml(pageUrl: String, html: String) {
        viewModelScope.launch {
            val prefs = prefsRepo.userPreferences.first()
            val strings = LunaLocalization.get(prefs.language)
            if (html.isBlank()) {
                _snackbarEvents.tryEmit(strings.browserExtractEmptyMsg)
                return@launch
            }
            _workingState.update {
                it.copy(
                    isBrowserExtracting = true,
                    urlInput = pageUrl
                )
            }
            try {
                val matchedSite = customSiteRepo.findMatchingSite(pageUrl)
                val outcome = scraper.parseRenderedHtml(
                    pageUrl = pageUrl,
                    html = html,
                    includeChapterImages = prefs.includeChapterImages,
                    mediaCache = mediaCache,
                    fromBrowser = true,
                    customSite = matchedSite
                ) { _, _ -> }
                handleScrapeOutcome(outcome, openBrowserAutomatically = false)
            } catch (_: Exception) {
                _workingState.update { it.copy(isBrowserExtracting = false) }
                _snackbarEvents.tryEmit(strings.fetchFallbackMsg)
            }
        }
    }

    /* =========================================================================
       STAGE 2: INTERACTIVE CHAPTER PICKER & BATCH DOWNLOADER + AUTO-EXPORT
       ========================================================================= */
    fun dismissChapterPicker() {
        if (_workingState.value.isBatchDownloading) return
        _workingState.update {
            it.copy(
                discoveredNovel = null,
                selectedChapterIds = emptySet()
            )
        }
    }

    fun toggleChapterSelection(chapterId: String) {
        _workingState.update { state ->
            val updated = if (state.selectedChapterIds.contains(chapterId)) {
                state.selectedChapterIds - chapterId
            } else {
                state.selectedChapterIds + chapterId
            }
            state.copy(selectedChapterIds = updated)
        }
    }

    fun selectAllChapters() {
        val discovered = _workingState.value.discoveredNovel ?: return
        _workingState.update {
            it.copy(selectedChapterIds = discovered.chapters.map { ch -> ch.id }.toSet())
        }
    }

    fun deselectAllChapters() {
        _workingState.update { it.copy(selectedChapterIds = emptySet()) }
    }

    fun reverseDiscoveredOrder() {
        val discovered = _workingState.value.discoveredNovel ?: return
        _workingState.update {
            it.copy(discoveredNovel = discovered.copy(chapters = discovered.chapters.reversed()))
        }
    }

    fun applyChapterRange(fromOneBased: Int, toOneBased: Int) {
        val discovered = _workingState.value.discoveredNovel ?: return
        val total = discovered.chapters.size
        if (total == 0) return
        val start = fromOneBased.coerceIn(1, total)
        val end = toOneBased.coerceIn(1, total)
        val low = minOf(start, end) - 1
        val high = maxOf(start, end) - 1
        val ids = discovered.chapters.subList(low, high + 1).map { it.id }.toSet()
        _workingState.update { it.copy(selectedChapterIds = ids) }
    }

    fun startBatchChapterDownload(
        discoveredOverride: DiscoveredNovel? = null,
        selectedIdsOverride: Set<String>? = null
    ) {
        val working = _workingState.value
        val discovered = discoveredOverride ?: working.discoveredNovel ?: return
        val selectedIds = selectedIdsOverride ?: working.selectedChapterIds
        val selectedRefs = discovered.chapters.filter { selectedIds.contains(it.id) }
        if (working.isBatchDownloading) return

        batchDownloadJob = viewModelScope.launch {
            val prefs = prefsRepo.userPreferences.first()
            val strings = LunaLocalization.get(prefs.language)
            if (selectedRefs.isEmpty()) {
                _snackbarEvents.tryEmit(strings.selectAtLeastOneMsg)
                return@launch
            }

            stopRequestedKeepDownloaded = false
            val includeImages = prefs.includeChapterImages

            _workingState.update {
                it.copy(
                    isBatchDownloading = true,
                    batchProgress = BatchDownloadProgress(
                        currentIndex = 0,
                        totalCount = selectedRefs.size,
                        currentChapterTitle = selectedRefs.first().title
                    )
                )
            }

            val cachedCover = mediaCache.downloadImageToLocal(
                imageUrl = discovered.coverUrl,
                refererUrl = discovered.url,
                folderName = "covers"
            ) ?: discovered.coverUrl

            val matchedSite = customSiteRepo.findMatchingSite(discovered.url)
            val downloadedChapters = mutableListOf<Chapter>()
            var skipped = 0

            try {
                for ((idx, ref) in selectedRefs.withIndex()) {
                    if (!isActive || stopRequestedKeepDownloaded) break

                    _workingState.update {
                        it.copy(
                            batchProgress = BatchDownloadProgress(
                                currentIndex = idx + 1,
                                totalCount = selectedRefs.size,
                                currentChapterTitle = ref.title,
                                downloadedCount = downloadedChapters.size,
                                skippedCount = skipped
                            )
                        )
                    }

                    val fetched = runCatching {
                        scraper.fetchChapter(
                            ref = ref,
                            platform = discovered.platform,
                            refererUrl = discovered.url,
                            includeImages = includeImages,
                            mediaCache = mediaCache,
                            customSite = matchedSite
                        )
                    }.getOrNull()

                    if (fetched != null) {
                        downloadedChapters.add(fetched)
                    } else {
                        skipped++
                    }

                    if (idx < selectedRefs.lastIndex && !stopRequestedKeepDownloaded) {
                        delay(150)
                    }
                }
            } catch (_: CancellationException) {
                // Preserve already fetched chapters
            }

            if (downloadedChapters.isNotEmpty()) {
                val finalNovel = Novel(
                    id = System.currentTimeMillis().toString(),
                    url = discovered.url,
                    title = discovered.title,
                    author = discovered.author,
                    description = discovered.description,
                    coverUri = cachedCover,
                    chapters = downloadedChapters,
                    savedAt = System.currentTimeMillis()
                )
                _workingState.update {
                    it.copy(
                        isBatchDownloading = false,
                        discoveredNovel = null,
                        selectedChapterIds = emptySet(),
                        currentNovel = finalNovel,
                        lastExportResult = null
                    )
                }

                // Read latest persisted formats and auto-export immediately while editor is open!
                val latestPrefs = prefsRepo.userPreferences.first()
                val selectedFormats = latestPrefs.preSelectedFormats
                if (selectedFormats.isNotEmpty()) {
                    executeExport(
                        novel = finalNovel,
                        formats = EbookFormat.entries.filter { selectedFormats.contains(it) },
                        mode = latestPrefs.multiExportMode
                    )
                } else {
                    val baseMsg = String.format(strings.batchCompleteMsg, downloadedChapters.size)
                    val fullMsg = if (skipped > 0) {
                        "$baseMsg ${String.format(strings.batchSkippedSuffix, skipped)}"
                    } else {
                        baseMsg
                    }
                    _snackbarEvents.tryEmit(fullMsg)
                }
            } else {
                _workingState.update { it.copy(isBatchDownloading = false) }
                _snackbarEvents.tryEmit(strings.protectedSiteMsg)
                openSmartBrowser(selectedRefs.first().url)
            }
        }
    }

    fun stopBatchAndKeepDownloaded() {
        stopRequestedKeepDownloaded = true
    }

    private fun triggerAutoExportIfConfigured(novel: Novel) {
        viewModelScope.launch {
            val prefs = prefsRepo.userPreferences.first()
            val selected = prefs.preSelectedFormats
            if (selected.isEmpty()) return@launch
            val ordered = EbookFormat.entries.filter { selected.contains(it) }
            executeExport(novel = novel, formats = ordered, mode = prefs.multiExportMode)
        }
    }

    /* =========================================================================
       SAMPLE NOVEL, METADATA, CHAPTER EDITING & HISTORY
       ========================================================================= */
    fun loadSampleNovel(customUrl: String = "") {
        val strings = LunaLocalization.get(uiState.value.language)
        val now = System.currentTimeMillis()
        val sample = Novel(
            id = now.toString(),
            url = customUrl.ifBlank { "https://example.com/story/rose-kingdom" },
            title = strings.sampleTitle,
            author = strings.sampleAuthor,
            description = strings.sampleDescription,
            coverUri = null,
            chapters = listOf(
                Chapter(
                    id = "ch_${now}_1",
                    title = strings.sampleCh1Title,
                    content = strings.sampleCh1Content
                ),
                Chapter(
                    id = "ch_${now}_2",
                    title = strings.sampleCh2Title,
                    content = strings.sampleCh2Content
                )
            ),
            savedAt = now
        )
        _workingState.update {
            it.copy(
                currentNovel = sample,
                lastExportResult = null
            )
        }
    }

    fun updateTitle(title: String) = updateNovel { it.copy(title = title) }
    fun updateAuthor(author: String) = updateNovel { it.copy(author = author) }
    fun updateDescription(description: String) = updateNovel { it.copy(description = description) }
    fun clearDescription() = updateNovel { it.copy(description = "") }
    fun resetCoverToDefault() = updateNovel { it.copy(coverUri = null) }

    fun setCoverFromPickedUri(uri: Uri) {
        viewModelScope.launch {
            val savedPath = copyUriToInternalStorage(uri, "covers", "cover")
            if (savedPath != null) {
                updateNovel { it.copy(coverUri = savedPath) }
            }
        }
    }

    fun addChapter() {
        val strings = LunaLocalization.get(uiState.value.language)
        updateNovel { novel ->
            val nextNum = novel.chapters.size + 1
            val newChapter = Chapter(
                id = "ch_${System.currentTimeMillis()}",
                title = "${strings.defaultChapterPrefix} $nextNum",
                content = ""
            )
            novel.copy(chapters = novel.chapters + newChapter)
        }
    }

    fun updateChapterTitle(chapterId: String, newTitle: String) {
        updateNovel { novel ->
            novel.copy(chapters = novel.chapters.map { if (it.id == chapterId) it.copy(title = newTitle) else it })
        }
    }

    fun updateChapterContent(chapterId: String, newContent: String) {
        updateNovel { novel ->
            novel.copy(chapters = novel.chapters.map { if (it.id == chapterId) it.copy(content = newContent) else it })
        }
    }

    fun deleteChapter(chapterId: String) {
        updateNovel { novel ->
            novel.copy(chapters = novel.chapters.filterNot { it.id == chapterId })
        }
    }

    fun moveChapter(index: Int, delta: Int) {
        updateNovel { novel ->
            val target = index + delta
            if (index !in novel.chapters.indices || target !in novel.chapters.indices) return@updateNovel novel
            val mutable = novel.chapters.toMutableList()
            val moved = mutable.removeAt(index)
            mutable.add(target, moved)
            novel.copy(chapters = mutable)
        }
    }

    fun addImageToChapter(chapterId: String, uri: Uri) {
        viewModelScope.launch {
            val savedPath = copyUriToInternalStorage(uri, "chapter_images", "img")
            if (savedPath != null) {
                updateNovel { novel ->
                    novel.copy(
                        chapters = novel.chapters.map { ch ->
                            if (ch.id == chapterId) ch.copy(images = ch.images + savedPath) else ch
                        }
                    )
                }
            }
        }
    }

    fun removeImageFromChapter(chapterId: String, imageRef: String) {
        updateNovel { novel ->
            novel.copy(
                chapters = novel.chapters.map { ch ->
                    if (ch.id == chapterId) ch.copy(images = ch.images - imageRef) else ch
                }
            )
        }
    }

    fun setPreviewOpen(open: Boolean) {
        _workingState.update { it.copy(isPreviewOpen = open) }
    }

    fun setHistoryOpen(open: Boolean) {
        _workingState.update { it.copy(isHistoryOpen = open) }
    }

    fun loadFromHistory(novel: Novel) {
        _workingState.update {
            it.copy(
                currentNovel = novel,
                urlInput = novel.url,
                isHistoryOpen = false,
                lastExportResult = null
            )
        }
    }

    fun deleteHistoryItem(id: String) {
        viewModelScope.launch { historyRepo.deleteNovel(id) }
    }

    fun clearHistory() {
        viewModelScope.launch { historyRepo.clearAll() }
    }

    /* =========================================================================
       EXPORT ACTIONS (Single Format OR All 7 Formats Separate / ZIP)
       ========================================================================= */
    fun exportNovel(format: EbookFormat) {
        val novel = _workingState.value.currentNovel ?: return
        if (_workingState.value.isExporting) return
        viewModelScope.launch {
            executeExport(
                novel = novel,
                formats = listOf(format),
                mode = MultiExportMode.SEPARATE_FILES
            )
        }
    }

    fun exportAllFormats(mode: MultiExportMode) {
        val novel = _workingState.value.currentNovel ?: return
        if (_workingState.value.isExporting) return
        viewModelScope.launch {
            executeExport(
                novel = novel,
                formats = EbookFormat.entries,
                mode = mode
            )
        }
    }

    private suspend fun executeExport(
        novel: Novel,
        formats: List<EbookFormat>,
        mode: MultiExportMode
    ) {
        val prefs = prefsRepo.userPreferences.first()
        val strings = LunaLocalization.get(prefs.language)
        val label = when {
            formats.size == 1 -> formats.first().extension
            mode == MultiExportMode.ZIP_BUNDLE -> "all_zip"
            else -> "all_separate"
        }

        _workingState.update {
            it.copy(isExporting = true, exportingLabel = label)
        }
        try {
            historyRepo.saveNovel(novel)
            val result = if (formats.size == 1) {
                exporter.export(novel, formats.first(), prefs.language)
            } else {
                exporter.exportMultiple(novel, formats, mode, prefs.language)
            }
            _workingState.update {
                it.copy(
                    isExporting = false,
                    exportingLabel = null,
                    lastExportResult = result,
                    confettiTrigger = it.confettiTrigger + 1
                )
            }
            val message = when {
                result.isZipBundle -> String.format(strings.exportZipSuccessDownloads, result.exportedCount)
                result.exportedCount > 1 -> String.format(strings.exportBatchSuccessDownloads, result.exportedCount)
                result.savedToDownloads -> "${strings.exportSuccessDownloads} (${result.fileName})"
                else -> "${strings.exportSuccessReady} (${result.fileName})"
            }
            _snackbarEvents.tryEmit(message)
        } catch (_: Exception) {
            _workingState.update {
                it.copy(isExporting = false, exportingLabel = null)
            }
            _snackbarEvents.tryEmit(strings.exportErrorMsg)
        }
    }

    private fun updateNovel(transform: (Novel) -> Novel) {
        _workingState.update { state ->
            val current = state.currentNovel ?: return@update state
            state.copy(currentNovel = transform(current))
        }
    }

    private suspend fun copyUriToInternalStorage(
        uri: Uri,
        folderName: String,
        prefix: String
    ): String? = withContext(Dispatchers.IO) {
        runCatching {
            val app = getApplication<Application>()
            val dir = File(app.filesDir, folderName).apply { mkdirs() }
            val destFile = File(dir, "${prefix}_${System.currentTimeMillis()}.jpg")
            app.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output -> input.copyTo(output) }
            } ?: return@withContext null
            destFile.absolutePath
        }.getOrNull()
    }

    private fun extractFirstUrl(text: String): String? {
        val schemeRegex = Regex("(?:https?|wattpad)://[^\\s<>\"'\\]\\)]+", RegexOption.IGNORE_CASE)
        schemeRegex.find(text)?.value?.let { match ->
            return match.trimEnd('.', ',', ';', '!', '?', ')', ']', '"', '\'')
        }
        // Fallback for bare domain links shared without https:// (e.g. w.tt/abc, novlar.com/..., uranus-novel.com/...)
        val bareDomainRegex = Regex(
            "(?:www\\.|m\\.|my\\.)?(?:wattpad\\.com|w\\.tt|novlar\\.com|uranus-novel\\.com)/[^\\s<>\"'\\]\\)]*",
            RegexOption.IGNORE_CASE
        )
        bareDomainRegex.find(text)?.value?.let { match ->
            val cleaned = match.trimEnd('.', ',', ';', '!', '?', ')', ']', '"', '\'')
            return "https://$cleaned"
        }
        return null
    }

    private fun extractTitleHint(rawText: String, extractedUrl: String, subjectHint: String?): String? {
        val fromSubject = subjectHint?.trim()?.takeIf { it.isNotBlank() && !it.startsWith("http", true) }
        if (fromSubject != null) return fromSubject.take(120)

        val cleaned = rawText
            .replace(extractedUrl, "")
            .replace(Regex("https?://\\S+", RegexOption.IGNORE_CASE), "")
            .lines()
            .map { it.trim() }
            .firstOrNull { it.length >= 3 }
            ?.trim('"', '\'', '«', '»', ':', '-', '—')
        return cleaned?.takeIf { it.length >= 3 }?.take(120)
    }
}
