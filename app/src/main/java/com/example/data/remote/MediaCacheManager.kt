package com.example.data.remote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

class MediaCacheManager(
    private val context: Context,
    private val httpClient: OkHttpClient
) {

    suspend fun downloadImageToLocal(
        imageUrl: String?,
        refererUrl: String?,
        folderName: String
    ): String? = withContext(Dispatchers.IO) {
        if (imageUrl.isNullOrBlank()) return@withContext null
        if (!imageUrl.startsWith("http://", ignoreCase = true) &&
            !imageUrl.startsWith("https://", ignoreCase = true)
        ) {
            return@withContext imageUrl
        }

        val dir = File(context.filesDir, folderName).apply { mkdirs() }
        val hashName = sha256Short(imageUrl) + ".jpg"
        val destFile = File(dir, hashName)
        if (destFile.exists() && destFile.length() > 512) {
            return@withContext destFile.absolutePath
        }

        runCatching {
            val reqBuilder = Request.Builder()
                .url(imageUrl)
                .header(
                    "User-Agent",
                    NovelScraper.MOBILE_USER_AGENT
                )
                .header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")

            if (!refererUrl.isNullOrBlank()) {
                reqBuilder.header("Referer", refererUrl)
            }

            val cookies = runCatching {
                CookieManager.getInstance().getCookie(imageUrl)
                    ?: refererUrl?.let { CookieManager.getInstance().getCookie(it) }
            }.getOrNull()
            if (!cookies.isNullOrBlank()) {
                reqBuilder.header("Cookie", cookies)
            }

            httpClient.newCall(reqBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val bytes = response.body?.bytes() ?: return@withContext null
                if (bytes.size < 256 || bytes.size > 12 * 1024 * 1024) return@withContext null

                // Decode & re-encode cleanly to JPEG (normalizes WebP/PNG/AVIF for EPUB & PDF!)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                    // Fallback: write raw bytes if format isn't bitmap-decodable
                    destFile.writeBytes(bytes)
                    return@withContext destFile.absolutePath
                }

                var sample = 1
                while (bounds.outWidth / sample > 1400 || bounds.outHeight / sample > 2000) {
                    sample *= 2
                }
                val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
                    ?: return@withContext null
                try {
                    FileOutputStream(destFile).use { fos ->
                        bmp.compress(Bitmap.CompressFormat.JPEG, 88, fos)
                    }
                    destFile.absolutePath
                } finally {
                    bmp.recycle()
                }
            }
        }.getOrNull()
    }

    private fun sha256Short(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.take(12).joinToString("") { "%02x".format(it) }
    }
}
