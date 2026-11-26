package com.example.export

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.util.Base64
import androidx.core.content.res.ResourcesCompat
import com.example.R
import com.example.model.AppLanguage
import com.example.model.EbookFormat
import com.example.model.ExportResult
import com.example.model.MultiExportMode
import com.example.model.Novel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.min

class EbookExporter(
    private val context: Context,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
) {

    suspend fun export(
        novel: Novel,
        format: EbookFormat,
        language: AppLanguage
    ): ExportResult = withContext(Dispatchers.IO) {
        val safeBase = sanitizeFilename(novel.title.ifBlank { "LunaChan_Book" })
        val fileName = "$safeBase.${format.extension}"

        val exportsDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val cacheFile = File(exportsDir, fileName)

        writeFormatFile(novel, format, language, cacheFile)
        val savedToDownloads = saveToPublicDownloads(cacheFile, fileName, format.mimeType, subFolder = null)

        ExportResult(
            fileName = fileName,
            mimeType = format.mimeType,
            cacheFilePath = cacheFile.absolutePath,
            savedToDownloads = savedToDownloads,
            exportedCount = 1,
            isZipBundle = false,
            exportedFileNames = listOf(fileName)
        )
    }

    suspend fun exportMultiple(
        novel: Novel,
        formats: List<EbookFormat>,
        mode: MultiExportMode,
        language: AppLanguage
    ): ExportResult = withContext(Dispatchers.IO) {
        val orderedFormats = formats.distinct()
        require(orderedFormats.isNotEmpty()) { "At least one format is required" }

        if (orderedFormats.size == 1 && mode == MultiExportMode.SEPARATE_FILES) {
            return@withContext export(novel, orderedFormats.first(), language)
        }

        val safeBase = sanitizeFilename(novel.title.ifBlank { "LunaChan_Book" })
        val exportsDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val generatedPairs = mutableListOf<Pair<EbookFormat, File>>()

        orderedFormats.forEach { fmt ->
            val file = File(exportsDir, "$safeBase.${fmt.extension}")
            writeFormatFile(novel, fmt, language, file)
            generatedPairs.add(fmt to file)
        }

        if (mode == MultiExportMode.ZIP_BUNDLE) {
            val zipSuffix = if (orderedFormats.size == EbookFormat.entries.size) "AllFormats" else "${orderedFormats.size}Formats"
            val zipFileName = "${safeBase}_$zipSuffix.zip"
            val zipFile = File(exportsDir, zipFileName)

            ZipOutputStream(FileOutputStream(zipFile)).use { zip ->
                generatedPairs.forEach { (_, file) ->
                    zip.putNextEntry(ZipEntry(file.name))
                    file.inputStream().use { input -> input.copyTo(zip) }
                    zip.closeEntry()
                }
            }

            val savedToDownloads = saveToPublicDownloads(
                sourceFile = zipFile,
                fileName = zipFileName,
                mimeType = "application/zip",
                subFolder = null
            )

            return@withContext ExportResult(
                fileName = zipFileName,
                mimeType = "application/zip",
                cacheFilePath = zipFile.absolutePath,
                savedToDownloads = savedToDownloads,
                exportedCount = orderedFormats.size,
                isZipBundle = true,
                exportedFileNames = generatedPairs.map { it.second.name }
            )
        } else {
            // Save each format as an independent file inside Downloads/LunaChan/<BookName>/
            var anySaved = false
            generatedPairs.forEach { (fmt, file) ->
                val ok = saveToPublicDownloads(
                    sourceFile = file,
                    fileName = file.name,
                    mimeType = fmt.mimeType,
                    subFolder = safeBase
                )
                if (ok) anySaved = true
            }
            val primary = generatedPairs.first()
            return@withContext ExportResult(
                fileName = primary.second.name,
                mimeType = primary.first.mimeType,
                cacheFilePath = primary.second.absolutePath,
                savedToDownloads = anySaved,
                exportedCount = orderedFormats.size,
                isZipBundle = false,
                exportedFileNames = generatedPairs.map { it.second.name }
            )
        }
    }

    private fun writeFormatFile(
        novel: Novel,
        format: EbookFormat,
        language: AppLanguage,
        outFile: File
    ) {
        when (format) {
            EbookFormat.EPUB -> buildEpub(novel, language, outFile)
            EbookFormat.PDF -> buildPdf(novel, language, outFile)
            EbookFormat.FB2 -> outFile.writeText(buildFb2(novel, language), Charsets.UTF_8)
            EbookFormat.HTML -> outFile.writeText(buildHtml(novel, language), Charsets.UTF_8)
            EbookFormat.MD -> outFile.writeText(buildMarkdown(novel), Charsets.UTF_8)
            EbookFormat.RTF -> outFile.writeText(buildRtf(novel, language), Charsets.US_ASCII)
            EbookFormat.TXT -> outFile.writeText(buildTxt(novel), Charsets.UTF_8)
        }
    }

    private fun saveToPublicDownloads(
        sourceFile: File,
        fileName: String,
        mimeType: String,
        subFolder: String?
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return runCatching {
            val relativePath = if (subFolder.isNullOrBlank()) {
                "${Environment.DIRECTORY_DOWNLOADS}/LunaChan"
            } else {
                "${Environment.DIRECTORY_DOWNLOADS}/LunaChan/$subFolder"
            }
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
            resolver.openOutputStream(uri)?.use { out ->
                sourceFile.inputStream().use { input -> input.copyTo(out) }
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        }.getOrDefault(false)
    }

    /* ============================================================================
       1. EPUB 2.0/3.0 Compliant Archive
       ============================================================================ */
    private fun buildEpub(novel: Novel, language: AppLanguage, outFile: File) {
        val dir = if (language.isRtl) "rtl" else "ltr"
        val bookId = "urn:uuid:${novel.id.ifBlank { System.currentTimeMillis().toString() }}"

        ZipOutputStream(FileOutputStream(outFile)).use { zip ->
            // 1) Uncompressed STORED mimetype MUST be the very first entry in EPUB
            val mimeBytes = "application/epub+zip".toByteArray(Charsets.US_ASCII)
            val crc = CRC32().apply { update(mimeBytes) }
            val mimeEntry = ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = mimeBytes.size.toLong()
                compressedSize = mimeBytes.size.toLong()
                this.crc = crc.value
            }
            zip.putNextEntry(mimeEntry)
            zip.write(mimeBytes)
            zip.closeEntry()

            // 2) META-INF/container.xml
            zip.putTextEntry(
                "META-INF/container.xml",
                """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>"""
            )

            val manifest = StringBuilder()
            val spine = StringBuilder()
            val navPoints = StringBuilder()

            // 3) Cover image (always embed real image or default Luna cover bitmap)
            val coverBytes = loadImageAsJpegBytes(novel.coverUri) ?: renderDefaultCoverJpeg(novel.title)
            zip.putBinaryEntry("OEBPS/images/cover.jpg", coverBytes)
            manifest.append("""    <item id="cover-img" href="images/cover.jpg" media-type="image/jpeg" properties="cover-image"/>""").append('\n')

            // 4) Intro / Cover XHTML
            val descHtml = if (novel.description.isNotBlank()) {
                val paras = novel.description.split(Regex("\n+"))
                    .filter { it.isNotBlank() }
                    .joinToString("") { "<p>${escapeXml(it.trim())}</p>" }
                """<div class="desc">$paras</div>"""
            } else ""

            val introXhtml = """<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" dir="$dir" lang="${language.code}">
<head>
  <title>${escapeXml(novel.title)}</title>
  <style>
    body { font-family: sans-serif; text-align: center; padding: 6%; color: #241A21; line-height: 1.8; }
    img { max-width: 72%; border-radius: 12px; margin-bottom: 16px; }
    h1 { color: #d6336c; margin-bottom: 6px; }
    h3 { color: #6B5A65; font-weight: normal; margin-top: 0; }
    .desc { text-align: start; background: #fff0f6; padding: 14px; border-radius: 12px; margin-top: 20px; }
  </style>
</head>
<body>
  <img src="images/cover.jpg" alt="Cover"/>
  <h1>${escapeXml(novel.title)}</h1>
  <h3>${escapeXml(novel.author)}</h3>
  $descHtml
</body>
</html>"""
            zip.putTextEntry("OEBPS/intro.xhtml", introXhtml)
            manifest.append("""    <item id="intro" href="intro.xhtml" media-type="application/xhtml+xml"/>""").append('\n')
            spine.append("""    <itemref idref="intro"/>""").append('\n')

            // 5) Chapters + Embedded Images
            var imageCounter = 1
            novel.chapters.forEachIndexed { index, chapter ->
                val chFile = "chapter_${index + 1}.xhtml"
                val bodyBuilder = StringBuilder()

                chapter.content.split(Regex("\n+"))
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .forEach { para ->
                        bodyBuilder.append("  <p>").append(escapeXml(para)).append("</p>\n")
                    }

                chapter.images.forEach { imgRef ->
                    val imgBytes = loadImageAsJpegBytes(imgRef)
                    if (imgBytes != null) {
                        val imgName = "img_${imageCounter++}.jpg"
                        zip.putBinaryEntry("OEBPS/images/$imgName", imgBytes)
                        manifest.append("""    <item id="$imgName" href="images/$imgName" media-type="image/jpeg"/>""").append('\n')
                        bodyBuilder.append("""  <p class="img-wrap"><img src="images/$imgName" alt="Illustration"/></p>""").append('\n')
                    }
                }

                val chapterXhtml = """<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" dir="$dir" lang="${language.code}">
<head>
  <title>${escapeXml(chapter.title)}</title>
  <style>
    body { font-family: sans-serif; line-height: 1.9; padding: 5%; color: #241A21; }
    h2 { color: #d6336c; border-bottom: 1px solid #fcc2d7; padding-bottom: 6px; }
    p { margin: 0 0 12px 0; }
    .img-wrap { text-align: center; margin: 16px 0; }
    img { max-width: 100%; height: auto; border-radius: 8px; }
  </style>
</head>
<body>
  <h2>${escapeXml(chapter.title)}</h2>
$bodyBuilder</body>
</html>"""
                zip.putTextEntry("OEBPS/$chFile", chapterXhtml)
                manifest.append("""    <item id="ch_${index + 1}" href="$chFile" media-type="application/xhtml+xml"/>""").append('\n')
                spine.append("""    <itemref idref="ch_${index + 1}"/>""").append('\n')
                navPoints.append(
                    """    <navPoint id="nav_${index + 1}" playOrder="${index + 1}">
      <navLabel><text>${escapeXml(chapter.title)}</text></navLabel>
      <content src="$chFile"/>
    </navPoint>
"""
                )
            }

            // 6) toc.ncx
            zip.putTextEntry(
                "OEBPS/toc.ncx",
                """<?xml version="1.0" encoding="UTF-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
  <head>
    <meta name="dtb:uid" content="$bookId"/>
  </head>
  <docTitle><text>${escapeXml(novel.title)}</text></docTitle>
  <navMap>
$navPoints  </navMap>
</ncx>"""
            )
            manifest.append("""    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>""").append('\n')

            // 7) content.opf
            zip.putTextEntry(
                "OEBPS/content.opf",
                """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" unique-identifier="BookId" version="2.0">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>${escapeXml(novel.title)}</dc:title>
    <dc:creator>${escapeXml(novel.author)}</dc:creator>
    <dc:language>${language.code}</dc:language>
    <dc:identifier id="BookId">$bookId</dc:identifier>
    <meta name="cover" content="cover-img"/>
  </metadata>
  <manifest>
$manifest  </manifest>
  <spine toc="ncx">
$spine  </spine>
</package>"""
            )
        }
    }

    /* ============================================================================
       2. Multi-Page A4 PDF with Full Arabic/RTL & Latin Shaping
       ============================================================================ */
    private fun buildPdf(novel: Novel, language: AppLanguage, outFile: File) {
        val pageWidth = 595
        val pageHeight = 842
        val margin = 46f
        val contentWidth = (pageWidth - margin * 2).toInt()
        val maxY = pageHeight - 54f

        val baseTypeface = runCatching {
            ResourcesCompat.getFont(context, R.font.cairo)
        }.getOrNull() ?: Typeface.DEFAULT

        val boldTypeface = Typeface.create(baseTypeface, Typeface.BOLD)
        val textDir = if (language.isRtl) TextDirectionHeuristics.RTL else TextDirectionHeuristics.LTR
        val bodyAlign = Layout.Alignment.ALIGN_NORMAL

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = boldTypeface
            textSize = 22f
            color = Color.parseColor("#D6336C")
        }
        val authorPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = baseTypeface
            textSize = 14f
            color = Color.parseColor("#6B5A65")
        }
        val chapterHeadingPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = boldTypeface
            textSize = 17f
            color = Color.parseColor("#D6336C")
        }
        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = baseTypeface
            textSize = 12.5f
            color = Color.parseColor("#241A21")
        }
        val pageNumPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = baseTypeface
            textSize = 10f
            color = Color.parseColor("#9C8594")
            textAlign = Paint.Align.CENTER
        }
        val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FCC2D7")
            strokeWidth = 1.5f
        }
        val cardFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFF0F6")
            style = Paint.Style.FILL
        }

        val pdf = PdfDocument()
        var pageNumber = 1

        // --- Cover Page ---
        run {
            val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber++).create()
            val page = pdf.startPage(pageInfo)
            val canvas = page.canvas
            var y = 64f

            val coverBmp = loadBitmap(novel.coverUri) ?: renderDefaultCoverBitmap(novel.title)
            val targetW = 200
            val targetH = 290
            val left = (pageWidth - targetW) / 2f
            val dstRect = RectF(left, y, left + targetW, y + targetH)
            canvas.drawBitmap(coverBmp, null, dstRect, Paint(Paint.FILTER_BITMAP_FLAG))
            y += targetH + 28f

            val titleLayout = buildStaticLayout(
                novel.title.ifBlank { "Luna-chan Book" },
                titlePaint,
                contentWidth,
                Layout.Alignment.ALIGN_CENTER,
                textDir
            )
            canvas.save()
            canvas.translate(margin, y)
            titleLayout.draw(canvas)
            canvas.restore()
            y += titleLayout.height + 10f

            if (novel.author.isNotBlank()) {
                val authorLayout = buildStaticLayout(
                    novel.author,
                    authorPaint,
                    contentWidth,
                    Layout.Alignment.ALIGN_CENTER,
                    textDir
                )
                canvas.save()
                canvas.translate(margin, y)
                authorLayout.draw(canvas)
                canvas.restore()
                y += authorLayout.height + 24f
            }

            if (novel.description.isNotBlank()) {
                val descLayout = buildStaticLayout(
                    novel.description,
                    bodyPaint,
                    contentWidth - 32,
                    bodyAlign,
                    textDir
                )
                val boxHeight = min(descLayout.height + 28f, maxY - y)
                if (boxHeight > 32f) {
                    canvas.drawRoundRect(
                        RectF(margin, y, pageWidth - margin, y + boxHeight),
                        14f,
                        14f,
                        cardFillPaint
                    )
                    canvas.save()
                    canvas.clipRect(margin + 16f, y + 14f, pageWidth - margin - 16f, y + boxHeight - 10f)
                    canvas.translate(margin + 16f, y + 14f)
                    descLayout.draw(canvas)
                    canvas.restore()
                }
            }

            pdf.finishPage(page)
        }

        // --- Chapter Pages ---
        novel.chapters.forEach { chapter ->
            var page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber++).create())
            var canvas = page.canvas
            var y = 54f

            fun newPage() {
                canvas.drawText("${pageNumber - 1}", pageWidth / 2f, pageHeight - 24f, pageNumPaint)
                pdf.finishPage(page)
                page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber++).create())
                canvas = page.canvas
                y = 54f
            }

            val headingLayout = buildStaticLayout(
                chapter.title,
                chapterHeadingPaint,
                contentWidth,
                bodyAlign,
                textDir
            )
            canvas.save()
            canvas.translate(margin, y)
            headingLayout.draw(canvas)
            canvas.restore()
            y += headingLayout.height + 8f
            canvas.drawLine(margin, y, pageWidth - margin, y, dividerPaint)
            y += 18f

            val paragraphs = chapter.content.split(Regex("\n+"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            paragraphs.forEach { para ->
                val paraLayout = buildStaticLayout(para, bodyPaint, contentWidth, bodyAlign, textDir)
                for (lineIndex in 0 until paraLayout.lineCount) {
                    val lineTop = paraLayout.getLineTop(lineIndex)
                    val lineBottom = paraLayout.getLineBottom(lineIndex)
                    val lineHeight = (lineBottom - lineTop).toFloat()
                    if (y + lineHeight > maxY) {
                        newPage()
                    }
                    canvas.save()
                    canvas.clipRect(margin, y, pageWidth - margin, y + lineHeight)
                    canvas.translate(margin, y - lineTop)
                    paraLayout.draw(canvas)
                    canvas.restore()
                    y += lineHeight
                }
                y += 10f
            }

            chapter.images.forEach { imgRef ->
                val bmp = loadBitmap(imgRef)
                if (bmp != null) {
                    val scale = min(contentWidth.toFloat() / bmp.width, 280f / bmp.height).coerceAtMost(1f)
                    val drawW = bmp.width * scale
                    val drawH = bmp.height * scale
                    if (y + drawH > maxY) {
                        newPage()
                    }
                    val imgLeft = (pageWidth - drawW) / 2f
                    canvas.drawBitmap(
                        bmp,
                        null,
                        RectF(imgLeft, y, imgLeft + drawW, y + drawH),
                        Paint(Paint.FILTER_BITMAP_FLAG)
                    )
                    y += drawH + 16f
                    bmp.recycle()
                }
            }

            canvas.drawText("${pageNumber - 1}", pageWidth / 2f, pageHeight - 24f, pageNumPaint)
            pdf.finishPage(page)
        }

        FileOutputStream(outFile).use { fos -> pdf.writeTo(fos) }
        pdf.close()
    }

    private fun buildStaticLayout(
        text: String,
        paint: TextPaint,
        width: Int,
        alignment: Layout.Alignment,
        textDir: android.text.TextDirectionHeuristic
    ): StaticLayout {
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width.coerceAtLeast(1))
            .setAlignment(alignment)
            .setTextDirection(textDir)
            .setLineSpacing(4f, 1.25f)
            .setIncludePad(false)
            .build()
    }

    /* ============================================================================
       3. FictionBook 2.0 (.fb2)
       ============================================================================ */
    private fun buildFb2(novel: Novel, language: AppLanguage): String {
        val parts = novel.author.trim().split(Regex("\\s+"))
        val firstName = parts.firstOrNull().orEmpty().ifBlank { "Author" }
        val lastName = if (parts.size > 1) parts.drop(1).joinToString(" ") else ""

        val coverBytes = loadImageAsJpegBytes(novel.coverUri) ?: renderDefaultCoverJpeg(novel.title)
        val coverBase64 = Base64.encodeToString(coverBytes, Base64.NO_WRAP)

        val sections = novel.chapters.joinToString("\n") { ch ->
            val paras = ch.content.split(Regex("\n+"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString("\n") { "      <p>${escapeXml(it)}</p>" }
            """    <section>
      <title><p>${escapeXml(ch.title)}</p></title>
$paras
    </section>"""
        }

        return """<?xml version="1.0" encoding="UTF-8"?>
<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0" xmlns:l="http://www.w3.org/1999/xlink">
  <description>
    <title-info>
      <book-title>${escapeXml(novel.title)}</book-title>
      <author><first-name>${escapeXml(firstName)}</first-name><last-name>${escapeXml(lastName)}</last-name></author>
      <annotation><p>${escapeXml(novel.description)}</p></annotation>
      <coverpage><image l:href="#cover.jpg"/></coverpage>
      <lang>${language.code}</lang>
    </title-info>
  </description>
  <body>
$sections
  </body>
  <binary id="cover.jpg" content-type="image/jpeg">$coverBase64</binary>
</FictionBook>"""
    }

    /* ============================================================================
       4. Self-Contained Styled HTML5 (.html)
       ============================================================================ */
    private fun buildHtml(novel: Novel, language: AppLanguage): String {
        val dir = if (language.isRtl) "rtl" else "ltr"
        val coverBytes = loadImageAsJpegBytes(novel.coverUri) ?: renderDefaultCoverJpeg(novel.title)
        val coverDataUri = "data:image/jpeg;base64,${Base64.encodeToString(coverBytes, Base64.NO_WRAP)}"

        val chaptersHtml = novel.chapters.joinToString("\n") { ch ->
            val parasHtml = ch.content.split(Regex("\n+"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString("\n") { "      <p>${escapeHtml(it)}</p>" }
            val imgsHtml = ch.images.mapNotNull { ref ->
                val bytes = loadImageAsJpegBytes(ref)
                if (bytes != null) {
                    val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    """      <p class="img-wrap"><img src="data:image/jpeg;base64,$b64" alt="Illustration"/></p>"""
                } else null
            }.joinToString("\n")

            """    <section class="chapter">
      <h2>${escapeHtml(ch.title)}</h2>
$parasHtml
$imgsHtml
    </section>"""
        }

        val descHtml = if (novel.description.isNotBlank()) {
            """  <div class="desc"><p>${escapeHtml(novel.description)}</p></div>"""
        } else ""

        return """<!DOCTYPE html>
<html lang="${language.code}" dir="$dir">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>${escapeHtml(novel.title)}</title>
<style>
  body { font-family: 'Cairo', system-ui, sans-serif; max-width: 780px; margin: 0 auto; padding: 2rem 1.25rem; background: #fff0f6; color: #241A21; line-height: 1.9; }
  .book-header { text-align: center; margin-bottom: 2.5rem; padding-bottom: 2rem; border-bottom: 2px solid #fcc2d7; }
  .book-header img { max-width: 220px; border-radius: 16px; box-shadow: 0 10px 24px rgba(240, 101, 149, 0.25); }
  h1 { color: #c2255c; margin: 1rem 0 0.25rem; }
  h3 { color: #6B5A65; margin: 0 0 1rem; font-weight: 600; }
  h2 { color: #d6336c; border-bottom: 1px solid #ffdeeb; padding-bottom: 0.4rem; }
  .desc { background: #fff; padding: 1rem 1.4rem; border-radius: 14px; text-align: start; border: 1px solid #fcc2d7; }
  .chapter { background: #fff; padding: 1.5rem 1.75rem; border-radius: 18px; margin-bottom: 1.75rem; box-shadow: 0 3px 12px rgba(240,101,149,0.08); }
  .img-wrap { text-align: center; }
  img { max-width: 100%; height: auto; border-radius: 12px; }
</style>
</head>
<body>
<header class="book-header">
  <img src="$coverDataUri" alt="Cover"/>
  <h1>${escapeHtml(novel.title)}</h1>
  <h3>${escapeHtml(novel.author)}</h3>
$descHtml
</header>
$chaptersHtml
</body>
</html>"""
    }

    /* ============================================================================
       5. Markdown (.md)
       ============================================================================ */
    private fun buildMarkdown(novel: Novel): String = buildString {
        append("# ").append(novel.title).append("\n\n")
        if (novel.author.isNotBlank()) append("**").append(novel.author).append("**\n\n")
        if (novel.description.isNotBlank()) {
            novel.description.split(Regex("\n+")).forEach { line ->
                if (line.isNotBlank()) append("> ").append(line.trim()).append("\n")
            }
            append("\n---\n\n")
        }
        novel.chapters.forEach { ch ->
            append("## ").append(ch.title).append("\n\n")
            append(ch.content.trim()).append("\n\n")
            ch.images.forEachIndexed { idx, img ->
                append("![Illustration ${idx + 1}]($img)\n\n")
            }
            append("---\n\n")
        }
    }

    /* ============================================================================
       6. Rich Text Format (.rtf) with Full Unicode / Arabic Support
       ============================================================================ */
    private fun buildRtf(novel: Novel, language: AppLanguage): String {
        val dirCmd = if (language.isRtl) "\\rtlpar" else "\\ltrpar"
        val body = buildString {
            append("{\\b\\fs36 ").append(toRtfUnicode(novel.title)).append("}\\par\n")
            if (novel.author.isNotBlank()) {
                append("{\\fs26 ").append(toRtfUnicode(novel.author)).append("}\\par\\par\n")
            }
            if (novel.description.isNotBlank()) {
                append("{\\i ").append(toRtfUnicode(novel.description)).append("}\\par\\par\n")
            }
            novel.chapters.forEach { ch ->
                append("{\\b\\fs28 ").append(toRtfUnicode(ch.title)).append("}\\par\n")
                append(toRtfUnicode(ch.content.trim())).append("\\par\\par\n")
            }
        }
        return "{\\rtf1\\ansi\\ansicpg1252\\deff0 $dirCmd\n$body}"
    }

    private fun toRtfUnicode(text: String): String = buildString {
        for (ch in text) {
            val code = ch.code
            when {
                ch == '\n' -> append("\\par\n")
                ch == '\r' -> Unit
                ch == '\\' || ch == '{' || ch == '}' -> append('\\').append(ch)
                code < 128 -> append(ch)
                else -> {
                    val signed = if (code > 32767) code - 65536 else code
                    append("\\u").append(signed).append('?')
                }
            }
        }
    }

    /* ============================================================================
       7. Plain Text (.txt)
       ============================================================================ */
    private fun buildTxt(novel: Novel): String = buildString {
        append(novel.title).append('\n')
        if (novel.author.isNotBlank()) append(novel.author).append('\n')
        if (novel.description.isNotBlank()) append('\n').append(novel.description).append('\n')
        append('\n').append("=".repeat(40)).append("\n\n")
        novel.chapters.forEach { ch ->
            append("### ").append(ch.title).append(" ###\n\n")
            append(ch.content.trim()).append("\n\n")
            append("-".repeat(30)).append("\n\n")
        }
    }

    /* ============================================================================
       Image Loading & Default Luna Cover Bitmap Renderer
       ============================================================================ */
    private fun loadImageAsJpegBytes(uriOrPath: String?): ByteArray? {
        val bmp = loadBitmap(uriOrPath) ?: return null
        return try {
            val baos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 88, baos)
            baos.toByteArray()
        } finally {
            bmp.recycle()
        }
    }

    private fun loadBitmap(uriOrPath: String?): Bitmap? {
        if (uriOrPath.isNullOrBlank()) return null
        return runCatching {
            val rawBytes: ByteArray? = when {
                uriOrPath.startsWith("http://", true) || uriOrPath.startsWith("https://", true) -> {
                    val req = Request.Builder()
                        .url(uriOrPath)
                        .header("User-Agent", "Mozilla/5.0 (Android 14) LunaChan/1.0")
                        .build()
                    httpClient.newCall(req).execute().use { res ->
                        if (res.isSuccessful) res.body?.bytes() else null
                    }
                }
                uriOrPath.startsWith("content://") -> {
                    context.contentResolver.openInputStream(Uri.parse(uriOrPath))?.use { it.readBytes() }
                }
                else -> {
                    val f = File(uriOrPath)
                    if (f.exists()) f.readBytes() else null
                }
            }
            if (rawBytes == null || rawBytes.isEmpty()) return null
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, opts)
            var sample = 1
            while (opts.outWidth / sample > 1200 || opts.outHeight / sample > 1600) {
                sample *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOpts)
        }.getOrNull()
    }

    private fun renderDefaultCoverJpeg(bookTitle: String): ByteArray {
        val bmp = renderDefaultCoverBitmap(bookTitle)
        return try {
            val baos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 92, baos)
            baos.toByteArray()
        } finally {
            bmp.recycle()
        }
    }

    private fun renderDefaultCoverBitmap(bookTitle: String): Bitmap {
        val width = 480
        val height = 720
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, width.toFloat(), height.toFloat(),
                intArrayOf(
                    Color.parseColor("#FFDEEB"),
                    Color.parseColor("#F783AC"),
                    Color.parseColor("#E64980")
                ),
                null,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // Subtle decorative frame
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(110, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        canvas.drawRoundRect(RectF(28f, 28f, width - 28f, height - 28f), 28f, 28f, borderPaint)

        // Soft decorative circle behind emblem
        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(45, 255, 255, 255)
        }
        canvas.drawCircle(width / 2f, height * 0.42f, 92f, glowPaint)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            textSize = 30f
        }
        val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(230, 255, 255, 255)
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = 20f
        }
        val displayTitle = bookTitle.ifBlank { "Luna-chan" }.take(24)
        canvas.drawText(displayTitle, width / 2f, height * 0.45f, textPaint)
        canvas.drawText("Luna-chan Edition", width / 2f, height * 0.54f, subtitlePaint)
        return bmp
    }

    private fun ZipOutputStream.putTextEntry(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun ZipOutputStream.putBinaryEntry(name: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(bytes)
        closeEntry()
    }

    private fun sanitizeFilename(raw: String): String {
        val cleaned = raw.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").trim('_')
        return cleaned.ifBlank { "LunaChan_Book" }.take(60)
    }

    private fun escapeHtml(str: String): String = str
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

    private fun escapeXml(str: String): String = str
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
