package io.github.sfzhou.lingotrail.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import io.github.sfzhou.lingotrail.data.HistoryItem
import io.github.sfzhou.lingotrail.overlay.OverlayPreparer
import io.github.sfzhou.lingotrail.overlay.PreparedOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * In-memory caches for decoded bitmaps and prepared overlays.
 *
 * Decoded photo copies are capped at [MAX_PHOTO_DIM] — the viewer zooms up to 4×,
 * which still leaves plenty of detail on a 1080 px wide screen, while keeping the
 * cache within a few tens of megabytes.
 */
class MediaCache {

    private val photoCache = object : LruCache<String, Bitmap>(cacheSizeKb(2)) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    private val thumbnailCache = object : LruCache<String, Bitmap>(cacheSizeKb(6)) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    private val overlayCache = object : LruCache<String, PreparedOverlay>(24) {
        override fun sizeOf(key: String, value: PreparedOverlay): Int = 1
    }

    suspend fun photo(item: HistoryItem, file: File): Bitmap? = withContext(Dispatchers.IO) {
        photoCache.get(file.absolutePath)?.let { return@withContext it }
        val bitmap = decodeScaled(file, MAX_PHOTO_DIM) ?: return@withContext null
        photoCache.put(file.absolutePath, bitmap)
        bitmap
    }

    suspend fun thumbnail(file: File): Bitmap? = withContext(Dispatchers.IO) {
        thumbnailCache.get(file.absolutePath)?.let { return@withContext it }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return@withContext null
        thumbnailCache.put(file.absolutePath, bitmap)
        bitmap
    }

    /** Builds (or reuses) the overlay geometry for a finished scan. */
    suspend fun preparedOverlay(item: HistoryItem, photoFile: File): PreparedOverlay? =
        withContext(Dispatchers.Default) {
            if (item.blocks.isEmpty()) return@withContext null
            val key = overlayKey(item)
            overlayCache.get(key)?.let { return@withContext it }
            val bitmap = photo(item, photoFile) ?: return@withContext null
            val overlay = OverlayPreparer.prepare(bitmap, item)
            overlayCache.put(key, overlay)
            overlay
        }

    /** Full-resolution decode for the share export. Caller owns the bitmap. */
    suspend fun fullPhoto(file: File): Bitmap? = withContext(Dispatchers.IO) {
        BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        })
    }

    fun evictOverlay(item: HistoryItem) {
        overlayCache.remove(overlayKey(item))
    }

    private fun overlayKey(item: HistoryItem): String {
        val blocksKey = item.blocks.fold(17) { hash, block ->
            hash * 31 + block.translation.hashCode() + block.boxes.size
        }
        return "${item.id}:${item.width}x${item.height}:$blocksKey"
    }

    private fun decodeScaled(file: File, maxDimension: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxDimension * 2) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(file.absolutePath, options)
    }

    private fun cacheSizeKb(fractionDenominator: Int): Int {
        val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        return maxMemoryKb / fractionDenominator
    }

    private companion object {
        const val MAX_PHOTO_DIM = 1600
    }
}
