package io.github.sfzhou.lingotrail.util

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import io.github.sfzhou.lingotrail.data.HistoryItem
import io.github.sfzhou.lingotrail.overlay.PhotoOverlayRenderer
import io.github.sfzhou.lingotrail.overlay.PreparedOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Renders photo + translation overlay into a JPEG in the cache directory and
 * returns a chooser-ready share intent.
 */
object ShareUtils {

    suspend fun buildShareIntent(
        context: Context,
        item: HistoryItem,
        overlay: PreparedOverlay?,
        photoFile: File,
    ): Intent? = withContext(Dispatchers.IO) {
        val photo = BitmapFactory.decodeFile(
            photoFile.absolutePath,
            BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 },
        ) ?: return@withContext null
        var composite: Bitmap? = null
        try {
            composite = if (overlay != null) {
                PhotoOverlayRenderer.renderComposite(photo, overlay)
            } else {
                photo
            }
            val sharedDir = File(context.cacheDir, "shared").apply { mkdirs() }
            pruneOldShares(sharedDir)
            val target = File(sharedDir, "LingoTrail-${item.id.take(8)}.jpg")
            FileOutputStream(target).use { stream ->
                composite.compress(Bitmap.CompressFormat.JPEG, 92, stream)
            }
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                target,
            )
            Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (_: Exception) {
            null
        } finally {
            if (composite != null && composite !== photo) composite.recycle()
            photo.recycle()
        }
    }

    private fun pruneOldShares(directory: File) {
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        directory.listFiles()?.forEach { file ->
            if (file.lastModified() < cutoff) file.delete()
        }
    }
}
