package io.github.sfzhou.lingotrail.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Thrown when an imported image cannot be decoded or encoded. */
class ImageProcessingException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Turns an arbitrary picked/captured image into the two JPEGs the app stores:
 * a display copy capped at [MAX_STORED_DIM] and a grid thumbnail.
 *
 * Stored copies are always upright (EXIF rotation is baked in) and always JPEG,
 * so the OCR upload, the viewer and the share export all agree on one format.
 */
object ImageProcessor {

    const val MAX_STORED_DIM = 2560
    const val THUMB_MAX_DIM = 512

    private const val STORED_QUALITY = 90
    private const val THUMB_QUALITY = 82
    private const val DECODE_HARD_LIMIT = 4096

    data class ProcessedImage(
        val jpeg: ByteArray,
        val thumbnailJpeg: ByteArray,
        val width: Int,
        val height: Int,
    )

    fun process(input: InputStream): ProcessedImage = try {
        val bytes = input.use { it.readBytes() }
        if (bytes.isEmpty()) throw ImageProcessingException("Empty image")

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw ImageProcessingException("Image could not be decoded")
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
            ?: throw ImageProcessingException("Image could not be decoded")

        val oriented = applyExifOrientation(decoded, readOrientation(bytes))
        val scaled = scaleDown(oriented, MAX_STORED_DIM)
        val width = scaled.width
        val height = scaled.height

        val storedJpeg = scaled.toJpeg(STORED_QUALITY)
        val thumbnail = scaleDown(scaled, THUMB_MAX_DIM)
        val thumbnailJpeg = thumbnail.toJpeg(THUMB_QUALITY)
        if (thumbnail !== scaled) thumbnail.recycle()
        scaled.recycle()

        ProcessedImage(
            jpeg = storedJpeg,
            thumbnailJpeg = thumbnailJpeg,
            width = width,
            height = height,
        )
    } catch (e: ImageProcessingException) {
        throw e
    } catch (e: Exception) {
        throw ImageProcessingException("Image could not be processed", e)
    }

    private fun calculateSampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (maxOf(width / sample, height / sample) > DECODE_HARD_LIMIT) {
            sample *= 2
        }
        return sample
    }

    private fun readOrientation(bytes: ByteArray): Int = runCatching {
        ExifInterface(ByteArrayInputStream(bytes))
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return bitmap
        }
        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                .also { rotated -> if (rotated !== bitmap) bitmap.recycle() }
        }.getOrElse { bitmap }
    }

    private fun scaleDown(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxDimension) return bitmap
        val ratio = maxDimension.toFloat() / longest
        val targetWidth = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val targetHeight = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    private fun Bitmap.toJpeg(quality: Int): ByteArray {
        val output = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.JPEG, quality, output)
        return output.toByteArray()
    }
}
