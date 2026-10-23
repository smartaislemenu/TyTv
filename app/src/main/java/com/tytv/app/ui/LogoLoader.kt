package com.tytv.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Small logo/poster loader for channel rows and poster rails: memory
 * LRU plus a disk cache under cacheDir, downsampled to TV-row size so
 * a list of thousands of entries never holds full-size art in memory.
 * The view tag guards against recycled rows receiving a stale image.
 * No third-party dependency; a failed load simply leaves the row
 * without an image rather than breaking the list.
 */
object LogoLoader {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private const val TARGET_PX = 128

    private val memCache = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int = bitmap.byteCount / 1024
    }

    /** Loads [url] into [imageView], downsampled to [targetPx].
     *  Rows use the small default; poster cards pass a larger size.
     *  The view tag guards against recycled rows receiving a stale
     *  image, and the cache key includes the size so the two never
     *  share a file. */
    fun load(imageView: ImageView, url: String?, targetPx: Int = TARGET_PX) {
        val key = "$url|$targetPx"
        imageView.tag = key
        if (url.isNullOrEmpty()) {
            imageView.setImageDrawable(null)
            return
        }
        memCache.get(key)?.let {
            imageView.setImageBitmap(it)
            return
        }
        imageView.setImageDrawable(null)
        scope.launch {
            val bitmap = loadFromDisk(imageView, url, targetPx) ?: download(url, targetPx)?.also {
                saveToDisk(imageView, url, targetPx, it)
            }
            if (bitmap != null) {
                memCache.put(key, bitmap)
                withContext(Dispatchers.Main) {
                    if (imageView.tag == key) imageView.setImageBitmap(bitmap)
                }
            }
        }
    }

    private fun diskFile(imageView: ImageView, url: String, targetPx: Int): File {
        val dir = File(imageView.context.cacheDir, "logo-cache").apply { mkdirs() }
        val hash = url.hashCode().let { if (it < 0) -it.toLong() else it.toLong() }
        return File(dir, "$hash-$targetPx.png")
    }

    private fun loadFromDisk(imageView: ImageView, url: String, targetPx: Int): Bitmap? {
        return try {
            val file = diskFile(imageView, url, targetPx)
            if (!file.exists()) return null
            decodeSampled(file.readBytes(), targetPx)
        } catch (e: Exception) {
            null
        }
    }

    private fun saveToDisk(imageView: ImageView, url: String, targetPx: Int, bitmap: Bitmap) {
        try {
            FileOutputStream(diskFile(imageView, url, targetPx)).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
            }
        } catch (e: Exception) {
        }
    }

    private fun download(url: String, targetPx: Int): Bitmap? {
        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            try {
                if (connection.responseCode !in 200..299) return null
                val bytes = connection.inputStream.readBytes()
                decodeSampled(bytes, targetPx)
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun decodeSampled(bytes: ByteArray, targetPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > targetPx || bounds.outHeight / sample > targetPx) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }
}
