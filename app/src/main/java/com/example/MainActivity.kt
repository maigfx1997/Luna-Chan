package com.example

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.model.ExportResult
import com.example.model.LunaLocalization
import com.example.ui.components.BookMetadataCard
import com.example.ui.components.BookPreviewDialog
import com.example.ui.components.ChapterPickerDialog
import com.example.ui.components.ChaptersEditorCard
import com.example.ui.components.ConfettiOverlay
import com.example.ui.components.CustomSitesDialog
import com.example.ui.components.ExportBannerCard
import com.example.ui.components.HistorySheetDialog
import com.example.ui.components.IncomingShareDialog
import com.example.ui.components.LunaTopBar
import com.example.ui.components.ShareAppsOptionDialog
import com.example.ui.components.SmartBrowserDialog
import com.example.ui.components.UrlImportCard
import com.example.ui.theme.LunaChanTheme
import com.example.ui.viewmodel.LunaViewModel
import java.io.File

class MainActivity : ComponentActivity() {

    private val viewModel: LunaViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleShareIntent(intent)

        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()

            LaunchedEffect(state.isDarkMode) {
                enableEdgeToEdge(
                    statusBarStyle = if (state.isDarkMode) {
                        SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(
                            android.graphics.Color.TRANSPARENT,
                            android.graphics.Color.TRANSPARENT
                        )
                    },
                    navigationBarStyle = if (state.isDarkMode) {
                        SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(
                            android.graphics.Color.TRANSPARENT,
                            android.graphics.Color.TRANSPARENT
                        )
                    }
                )
            }

            LunaChanTheme(
                darkTheme = state.isDarkMode,
                isArabic = state.language.isRtl
            ) {
                val layoutDir = if (state.language.isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
                CompositionLocalProvider(LocalLayoutDirection provides layoutDir) {
                    LunaAppScreen(viewModel = viewModel)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    /**
     * Extracts shared novel/fanfic links from Wattpad, Novlar, Uranus Novel, and browsers
     * across ACTION_SEND, ACTION_SEND_MULTIPLE, ACTION_VIEW, EXTRA_TEXT, EXTRA_HTML_TEXT, and ClipData.
     */
    private fun handleShareIntent(intent: Intent?) {
        if (intent == null) return
        val candidates = mutableListOf<String>()
        val subjectHint = intent.getStringExtra(Intent.EXTRA_SUBJECT)
            ?: intent.getStringExtra(Intent.EXTRA_TITLE)

        when (intent.action) {
            Intent.ACTION_SEND -> {
                intent.getStringExtra(Intent.EXTRA_TEXT)?.let { candidates.add(it) }
                intent.getStringExtra(Intent.EXTRA_HTML_TEXT)?.let { candidates.add(it) }
                subjectHint?.let { candidates.add(it) }
                intent.clipData?.let { clip ->
                    for (i in 0 until clip.itemCount) {
                        val item = clip.getItemAt(i)
                        item.text?.toString()?.let { candidates.add(it) }
                        item.uri?.toString()?.let { candidates.add(it) }
                    }
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                intent.getStringArrayListExtra(Intent.EXTRA_TEXT)?.let { candidates.addAll(it) }
                intent.clipData?.let { clip ->
                    for (i in 0 until clip.itemCount) {
                        clip.getItemAt(i).text?.toString()?.let { candidates.add(it) }
                    }
                }
            }
            Intent.ACTION_VIEW -> {
                intent.dataString?.let { candidates.add(it) }
            }
            Intent.ACTION_PROCESS_TEXT -> {
                intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()?.let { candidates.add(it) }
            }
        }

        val combined = candidates.joinToString("\n").trim()
        if (combined.isNotBlank()) {
            viewModel.handleIncomingSharedText(combined, subjectHint)
        }
    }
}

@Composable
private fun LunaAppScreen(viewModel: LunaViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val strings = remember(state.language) { LunaLocalization.get(state.language) }
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.snackbarEvents.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    // Zero-permission Android Photo Picker for Cover Image
    val coverPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            viewModel.setCoverFromPickedUri(uri)
        }
    }

    // Zero-permission Android Photo Picker for Chapter Illustration
    var pendingImageChapterId by remember { mutableStateOf<String?>(null) }
    val chapterImagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        val targetId = pendingImageChapterId
        pendingImageChapterId = null
        if (uri != null && targetId != null) {
            viewModel.addImageToChapter(targetId, uri)
        }
    }

    fun launchFileAction(result: ExportResult, isShare: Boolean) {
        val file = File(result.cacheFilePath)
        if (!file.exists()) return
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        if (isShare) {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = result.mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, result.fileName)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(shareIntent, strings.shareBookBtn))
        } else {
            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, result.mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                context.startActivity(viewIntent)
            } catch (_: ActivityNotFoundException) {
                launchFileAction(result, isShare = true)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                LunaTopBar(
                    strings = strings,
                    currentLanguage = state.language,
                    isDarkMode = state.isDarkMode,
                    historyCount = state.history.size,
                    onHistoryClick = { viewModel.setHistoryOpen(true) },
                    onLanguageSelected = { viewModel.setLanguage(it) },
                    onToggleDarkMode = { viewModel.toggleDarkMode() }
                )
            },
            snackbarHost = {
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier.navigationBarsPadding()
                )
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .imePadding(),
                contentAlignment = Alignment.TopCenter
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = 720.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    // 1. Hero URL Import, Images Toggle, Pre-Selected Formats & Smart Browser Card
                    item(key = "url_import") {
                        UrlImportCard(
                            strings = strings,
                            urlInput = state.urlInput,
                            includeChapterImages = state.includeChapterImages,
                            preSelectedFormats = state.preSelectedFormats,
                            multiExportMode = state.multiExportMode,
                            shareReceiveEnabled = state.shareReceiveEnabled,
                            customSites = state.customSites,
                            isFetching = state.isFetching,
                            fetchProgress = state.fetchProgress,
                            fetchStageKey = state.fetchStageKey,
                            onUrlChange = viewModel::updateUrlInput,
                            onIncludeChapterImagesChange = viewModel::setIncludeChapterImages,
                            onTogglePreSelectedFormat = viewModel::togglePreSelectedFormat,
                            onToggleAllPreSelectedFormats = viewModel::toggleAllPreSelectedFormats,
                            onSelectMultiExportMode = viewModel::setMultiExportMode,
                            onAutoPaste = {
                                val clip = clipboardManager.getText()?.text
                                viewModel.pasteFromClipboardText(clip)
                            },
                            onFetchClick = viewModel::fetchNovelFromUrl,
                            onOpenSmartBrowserClick = { viewModel.openSmartBrowser() },
                            onOpenShareOptionsClick = { viewModel.setShareOptionsOpen(true) },
                            onOpenCustomSitesClick = { viewModel.setCustomSitesOpen(true) },
                            onQuickBrowseCustomSite = viewModel::browseCustomSite,
                            onLoadSampleClick = { viewModel.loadSampleNovel() }
                        )
                    }

                    // 2. Active Book Workspace (Metadata, Chapters, Single & All-7-Formats Export)
                    val activeNovel = state.currentNovel
                    if (activeNovel != null) {
                        item(key = "book_workspace") {
                            AnimatedVisibility(
                                visible = true,
                                enter = fadeIn() + expandVertically()
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                                    BookMetadataCard(
                                        strings = strings,
                                        novel = activeNovel,
                                        onTitleChange = viewModel::updateTitle,
                                        onAuthorChange = viewModel::updateAuthor,
                                        onDescriptionChange = viewModel::updateDescription,
                                        onClearDescription = viewModel::clearDescription,
                                        onPickCoverClick = {
                                            coverPickerLauncher.launch(
                                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                            )
                                        },
                                        onResetCoverClick = viewModel::resetCoverToDefault
                                    )

                                    ChaptersEditorCard(
                                        strings = strings,
                                        chapters = activeNovel.chapters,
                                        onAddChapter = viewModel::addChapter,
                                        onPreviewBook = { viewModel.setPreviewOpen(true) },
                                        onUpdateChapterTitle = viewModel::updateChapterTitle,
                                        onUpdateChapterContent = viewModel::updateChapterContent,
                                        onDeleteChapter = viewModel::deleteChapter,
                                        onMoveChapter = viewModel::moveChapter,
                                        onInsertImageClick = { chapterId ->
                                            pendingImageChapterId = chapterId
                                            chapterImagePickerLauncher.launch(
                                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                            )
                                        },
                                        onRemoveChapterImage = viewModel::removeImageFromChapter
                                    )

                                    ExportBannerCard(
                                        strings = strings,
                                        isExporting = state.isExporting,
                                        exportingLabel = state.exportingLabel,
                                        lastExportResult = state.lastExportResult,
                                        onExportClick = viewModel::exportNovel,
                                        onExportAllClick = viewModel::exportAllFormats,
                                        onOpenExportedFile = { launchFileAction(it, isShare = false) },
                                        onShareExportedFile = { launchFileAction(it, isShare = true) }
                                    )
                                }
                            }
                        }
                    }

                    item(key = "bottom_spacer") {
                        Spacer(
                            modifier = Modifier
                                .navigationBarsPadding()
                                .height(20.dp)
                        )
                    }
                }
            }
        }

        // Interactive Table of Contents & Multi-Chapter Picker Modal
        val discovered = state.discoveredNovel
        if (discovered != null) {
            ChapterPickerDialog(
                strings = strings,
                discovered = discovered,
                selectedIds = state.selectedChapterIds,
                includeChapterImages = state.includeChapterImages,
                preSelectedFormats = state.preSelectedFormats,
                multiExportMode = state.multiExportMode,
                isDownloading = state.isBatchDownloading,
                batchProgress = state.batchProgress,
                onToggleChapter = viewModel::toggleChapterSelection,
                onSelectAll = viewModel::selectAllChapters,
                onDeselectAll = viewModel::deselectAllChapters,
                onReverseOrder = viewModel::reverseDiscoveredOrder,
                onApplyRange = viewModel::applyChapterRange,
                onIncludeImagesChange = viewModel::setIncludeChapterImages,
                onTogglePreSelectedFormat = viewModel::togglePreSelectedFormat,
                onToggleAllPreSelectedFormats = viewModel::toggleAllPreSelectedFormats,
                onSelectMultiExportMode = viewModel::setMultiExportMode,
                onDownloadSelected = viewModel::startBatchChapterDownload,
                onStopAndKeep = viewModel::stopBatchAndKeepDownloaded,
                onOpenInSmartBrowser = {
                    viewModel.dismissChapterPicker()
                    viewModel.openSmartBrowser(discovered.url)
                },
                onDismiss = viewModel::dismissChapterPicker
            )
        }

        // Smart Built-In Browser WebView Modal (for Cloudflare / JS / Login sites)
        val browserUrl = state.smartBrowserUrl
        if (browserUrl != null) {
            SmartBrowserDialog(
                strings = strings,
                initialUrl = browserUrl,
                isExtracting = state.isBrowserExtracting,
                onExtractPage = viewModel::extractFromBrowserHtml,
                onSaveCurrentSite = viewModel::saveSiteFromBrowser,
                onDismiss = viewModel::closeSmartBrowser
            )
        }

        // Full Book Layout Preview Modal
        val previewNovel = state.currentNovel
        if (state.isPreviewOpen && previewNovel != null) {
            BookPreviewDialog(
                strings = strings,
                novel = previewNovel,
                onDismiss = { viewModel.setPreviewOpen(false) }
            )
        }

        // Local Conversion History Modal
        if (state.isHistoryOpen) {
            HistorySheetDialog(
                strings = strings,
                history = state.history,
                onLoadNovel = viewModel::loadFromHistory,
                onDeleteItem = viewModel::deleteHistoryItem,
                onClearHistory = viewModel::clearHistory,
                onDismiss = { viewModel.setHistoryOpen(false) }
            )
        }

        // Share-from-Reading-Apps Option & Guide Modal
        if (state.isShareOptionsOpen) {
            ShareAppsOptionDialog(
                strings = strings,
                shareReceiveEnabled = state.shareReceiveEnabled,
                shareInstantFullConvert = state.shareInstantFullConvert,
                preSelectedFormats = state.preSelectedFormats,
                multiExportMode = state.multiExportMode,
                onToggleShareReceive = viewModel::setShareReceiveEnabled,
                onToggleInstantFullConvert = viewModel::setShareInstantFullConvert,
                onToggleFormat = viewModel::togglePreSelectedFormat,
                onToggleAllFormats = viewModel::toggleAllPreSelectedFormats,
                onSelectMultiMode = viewModel::setMultiExportMode,
                onConvertClipboardShare = {
                    val clip = clipboardManager.getText()?.text
                    viewModel.convertClipboardShareText(clip)
                },
                onDismiss = { viewModel.setShareOptionsOpen(false) }
            )
        }

        // Incoming Shared Story Instant Multi-Format Conversion Modal
        val incomingShare = state.incomingShareRequest
        if (incomingShare != null) {
            IncomingShareDialog(
                strings = strings,
                request = incomingShare,
                includeChapterImages = state.includeChapterImages,
                selectedFormats = state.preSelectedFormats,
                multiExportMode = state.multiExportMode,
                onIncludeImagesChange = viewModel::setIncludeChapterImages,
                onToggleFormat = viewModel::togglePreSelectedFormat,
                onToggleAllFormats = viewModel::toggleAllPreSelectedFormats,
                onSelectMultiMode = viewModel::setMultiExportMode,
                onConvertFullStoryNow = viewModel::convertIncomingShareFullStory,
                onChooseChaptersFirst = viewModel::convertIncomingSharePickChapters,
                onDismiss = viewModel::dismissIncomingShare
            )
        }

        // Custom Novel Websites Manager Modal
        if (state.isCustomSitesOpen) {
            CustomSitesDialog(
                strings = strings,
                customSites = state.customSites,
                onSaveSite = viewModel::saveCustomSite,
                onBrowseSite = viewModel::browseCustomSite,
                onDeleteSite = viewModel::deleteCustomSite,
                onDismiss = { viewModel.setCustomSitesOpen(false) }
            )
        }

        // Celebratory Confetti Overlay on Export Success
        ConfettiOverlay(
            trigger = state.confettiTrigger,
            modifier = Modifier.fillMaxSize()
        )
    }
}
