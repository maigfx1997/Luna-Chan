package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Book
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LibraryBooks
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Subject
import androidx.compose.material.icons.rounded.TextSnippet
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.model.EbookFormat
import com.example.model.ExportResult
import com.example.model.LunaStrings
import com.example.model.MultiExportMode
import com.example.ui.theme.Pinky100
import com.example.ui.theme.Pinky500
import com.example.ui.theme.Pinky600
import com.example.ui.theme.Pinky700
import com.example.ui.theme.Pinky900

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExportBannerCard(
    strings: LunaStrings,
    isExporting: Boolean,
    exportingLabel: String?,
    lastExportResult: ExportResult?,
    onExportClick: (EbookFormat) -> Unit,
    onExportAllClick: (MultiExportMode) -> Unit,
    onOpenExportedFile: (ExportResult) -> Unit,
    onShareExportedFile: (ExportResult) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(
                Brush.linearGradient(listOf(Pinky500, Pinky600, Pinky700))
            )
            .padding(20.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = strings.exportTitle,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
                    color = Color.White
                )
                Text(
                    text = strings.exportDesc,
                    style = MaterialTheme.typography.bodySmall,
                    color = Pinky100
                )
            }

            // All 7 Individual E-Book Export Format Buttons
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                EbookFormat.entries.forEach { format ->
                    val loadingThis = isExporting && exportingLabel == format.extension
                    val isPrimary = format.isPrimary
                    Surface(
                        onClick = { onExportClick(format) },
                        enabled = !isExporting,
                        shape = RoundedCornerShape(16.dp),
                        color = if (isPrimary) Color.White else Pinky900.copy(alpha = 0.38f),
                        border = if (isPrimary) null else BorderStroke(1.dp, Color.White.copy(alpha = 0.32f)),
                        shadowElevation = if (isPrimary) 4.dp else 0.dp,
                        modifier = Modifier.testTag("export_${format.extension}_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (loadingThis) {
                                CircularProgressIndicator(
                                    color = if (isPrimary) Pinky600 else Color.White,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(16.dp)
                                )
                            } else {
                                Icon(
                                    imageVector = formatIcon(format),
                                    contentDescription = null,
                                    tint = if (isPrimary) Pinky600 else Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Text(
                                text = format.label,
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold),
                                color = if (isPrimary) Pinky600 else Color.White
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.25f))

            // Download ALL 7 Formats Buttons (Both Separate Files AND Single ZIP Bundle)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val loadingAllSep = isExporting && exportingLabel == "all_separate"
                Surface(
                    onClick = { onExportAllClick(MultiExportMode.SEPARATE_FILES) },
                    enabled = !isExporting,
                    shape = RoundedCornerShape(16.dp),
                    color = Color.White,
                    shadowElevation = 3.dp,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("export_all_separate_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        if (loadingAllSep) {
                            CircularProgressIndicator(
                                color = Pinky600,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(16.dp)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.LibraryBooks,
                                contentDescription = null,
                                tint = Pinky600,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = strings.exportAllSeparateBtn,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.ExtraBold),
                            color = Pinky600,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                val loadingAllZip = isExporting && exportingLabel == "all_zip"
                Surface(
                    onClick = { onExportAllClick(MultiExportMode.ZIP_BUNDLE) },
                    enabled = !isExporting,
                    shape = RoundedCornerShape(16.dp),
                    color = Pinky900.copy(alpha = 0.45f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.45f)),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("export_all_zip_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        if (loadingAllZip) {
                            CircularProgressIndicator(
                                color = Color.White,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(16.dp)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.FolderZip,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = strings.exportAllZipBtn,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.ExtraBold),
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Post-Export Quick Action Pill (Open / Share the freshly exported book or ZIP bundle)
            AnimatedVisibility(visible = lastExportResult != null && !isExporting) {
                lastExportResult?.let { result ->
                    val summaryLabel = when {
                        result.isZipBundle -> "${result.fileName} (${result.exportedCount})"
                        result.exportedCount > 1 -> "${result.exportedCount} • ${result.fileName}"
                        else -> result.fileName
                    }
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        color = Color.White.copy(alpha = 0.18f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.35f))
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.CheckCircle,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = summaryLabel,
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Surface(
                                    onClick = { onOpenExportedFile(result) },
                                    shape = RoundedCornerShape(12.dp),
                                    color = Color.White,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("open_exported_file_button")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(vertical = 9.dp),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                            contentDescription = null,
                                            tint = Pinky600,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = strings.openBookBtn,
                                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                            color = Pinky600
                                        )
                                    }
                                }

                                Surface(
                                    onClick = { onShareExportedFile(result) },
                                    shape = RoundedCornerShape(12.dp),
                                    color = Pinky900.copy(alpha = 0.45f),
                                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.4f)),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("share_exported_file_button")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(vertical = 9.dp),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Share,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = strings.shareBookBtn,
                                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                            color = Color.White
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatIcon(format: EbookFormat): ImageVector = when (format) {
    EbookFormat.EPUB -> Icons.Rounded.Book
    EbookFormat.PDF -> Icons.Rounded.PictureAsPdf
    EbookFormat.FB2 -> Icons.Rounded.Code
    EbookFormat.HTML -> Icons.Rounded.Language
    EbookFormat.MD -> Icons.Rounded.Subject
    EbookFormat.RTF -> Icons.Rounded.Description
    EbookFormat.TXT -> Icons.Rounded.TextSnippet
}
