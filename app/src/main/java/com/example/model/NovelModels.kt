package com.example.model

import com.squareup.moshi.JsonClass

enum class AppLanguage(val code: String, val label: String, val isRtl: Boolean) {
    AR("ar", "عربي", true),
    EN("en", "EN", false),
    FR("fr", "FR", false);

    companion object {
        fun fromCode(code: String?): AppLanguage =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: AR
    }
}

enum class EbookFormat(
    val extension: String,
    val label: String,
    val mimeType: String,
    val isPrimary: Boolean = false
) {
    EPUB("epub", "EPUB", "application/epub+zip", isPrimary = true),
    PDF("pdf", "PDF", "application/pdf"),
    FB2("fb2", "FB2", "application/x-fictionbook+xml"),
    HTML("html", "HTML", "text/html"),
    MD("md", "MD", "text/markdown"),
    RTF("rtf", "RTF", "application/rtf"),
    TXT("txt", "TXT", "text/plain");

    companion object {
        fun fromExtension(ext: String): EbookFormat? =
            entries.firstOrNull { it.extension.equals(ext, ignoreCase = true) }
    }
}

enum class MultiExportMode {
    SEPARATE_FILES,
    ZIP_BUNDLE;

    companion object {
        fun fromName(name: String?): MultiExportMode =
            entries.firstOrNull { it.name == name } ?: SEPARATE_FILES
    }
}

enum class SourcePlatform(val displayName: String) {
    WATTPAD("Wattpad"),
    NOVLAR("Novlar"),
    URANUS("Uranus Novel"),
    GENERIC("Web")
}

@JsonClass(generateAdapter = true)
data class Chapter(
    val id: String,
    val title: String,
    val content: String,
    val images: List<String> = emptyList()
)

data class Novel(
    val id: String,
    val url: String = "",
    val title: String = "",
    val author: String = "",
    val description: String = "",
    val coverUri: String? = null,
    val chapters: List<Chapter> = emptyList(),
    val savedAt: Long = System.currentTimeMillis()
)

data class ChapterRef(
    val id: String,
    val title: String,
    val url: String,
    val wattpadPartId: String? = null,
    val isLocked: Boolean = false
)

data class DiscoveredNovel(
    val url: String,
    val title: String,
    val author: String,
    val description: String,
    val coverUrl: String?,
    val platform: SourcePlatform,
    val chapters: List<ChapterRef>
)

sealed interface ScrapeOutcome {
    data class ReadyNovel(val novel: Novel) : ScrapeOutcome
    data class NeedsChapterSelection(val discovered: DiscoveredNovel) : ScrapeOutcome
    data class RequiresBrowser(val url: String, val reason: String) : ScrapeOutcome
}

data class BatchDownloadProgress(
    val currentIndex: Int = 0,
    val totalCount: Int = 0,
    val currentChapterTitle: String = "",
    val downloadedCount: Int = 0,
    val skippedCount: Int = 0
)

data class ExportResult(
    val fileName: String,
    val mimeType: String,
    val cacheFilePath: String,
    val savedToDownloads: Boolean,
    val exportedCount: Int = 1,
    val isZipBundle: Boolean = false,
    val exportedFileNames: List<String> = listOf(fileName)
)

data class CustomNovelSite(
    val id: String,
    val name: String,
    val baseUrl: String,
    val host: String,
    val useSmartBrowserDefault: Boolean = false,
    val titleSelector: String = "",
    val authorSelector: String = "",
    val descriptionSelector: String = "",
    val coverSelector: String = "",
    val chapterListSelector: String = "",
    val chapterContentSelector: String = "",
    val addedAt: Long = System.currentTimeMillis()
) {
    val hasCustomSelectors: Boolean
        get() = titleSelector.isNotBlank() ||
            authorSelector.isNotBlank() ||
            descriptionSelector.isNotBlank() ||
            coverSelector.isNotBlank() ||
            chapterListSelector.isNotBlank() ||
            chapterContentSelector.isNotBlank()
}

