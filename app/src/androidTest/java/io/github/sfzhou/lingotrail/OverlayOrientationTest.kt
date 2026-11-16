package io.github.sfzhou.lingotrail

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.sfzhou.lingotrail.data.HistoryItem
import io.github.sfzhou.lingotrail.data.ScanStatus
import io.github.sfzhou.lingotrail.data.TargetLanguage
import io.github.sfzhou.lingotrail.data.TranslationBlock
import io.github.sfzhou.lingotrail.ocr.PixelRect
import io.github.sfzhou.lingotrail.overlay.OverlayPreparer
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies that the overlay measures the text band instead of trusting the OCR box.
 * A rotated line must produce a rotated, tight patch; an upright line must stay
 * axis-aligned and cover its box. Runs on-device without any network calls.
 */
@RunWith(AndroidJUnit4::class)
class OverlayOrientationTest {

    private val width = 1200
    private val height = 900
    private val text = "MUSEUM CAFÉ"
    private val textSize = 80f

    private fun createPhoto(rotationDegrees: Float): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(38, 58, 48))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = this@OverlayOrientationTest.textSize
            typeface = Typeface.DEFAULT_BOLD
        }
        canvas.save()
        canvas.rotate(rotationDegrees, width / 2f, height / 2f)
        canvas.drawText(text, width / 2f - paint.measureText(text) / 2f, height / 2f, paint)
        canvas.restore()
        return bitmap
    }

    private fun ocrBox(rotationDegrees: Float): PixelRect {
        val radians = Math.toRadians(rotationDegrees.toDouble())
        val textWidth = 560f
        val textHeight = textSize * 1.1f
        val halfWidth = (textWidth * cos(radians) + textHeight * abs(sin(radians))) / 2f
        val halfHeight = (textWidth * abs(sin(radians)) + textHeight * cos(radians)) / 2f
        return PixelRect(
            (width / 2f - halfWidth).toInt(),
            (height / 2f - halfHeight).toInt(),
            (width / 2f + halfWidth).toInt(),
            (height / 2f + halfHeight).toInt(),
        )
    }

    private fun prepare(rotationDegrees: Float): List<io.github.sfzhou.lingotrail.overlay.PreparedBlock> {
        val bitmap = createPhoto(rotationDegrees)
        try {
            val item = HistoryItem(
                id = "test",
                createdAt = 0L,
                targetLanguage = TargetLanguage.CHINESE_SIMPLIFIED,
                status = ScanStatus.DONE,
                width = bitmap.width,
                height = bitmap.height,
                blocks = listOf(
                    TranslationBlock(
                        original = text,
                        translation = "博物馆咖啡馆",
                        label = "sub_title",
                        boxes = listOf(ocrBox(rotationDegrees)),
                    ),
                ),
            )
            return OverlayPreparer.prepare(bitmap, item).blocks
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun rotatedTextGetsRotatedTightPatch() {
        val blocks = prepare(20f)
        assertEquals(1, blocks.size)
        val block = blocks.first()
        assertEquals(20f, block.rotationDegrees, 4f)
        // A rotated row of 80 px glyphs must not turn into a several-hundred-pixel
        // tall axis-aligned patch: its local height stays close to the glyph height.
        assertTrue(
            "patch height ${block.backgroundRect.height()} should stay near the glyph height",
            block.backgroundRect.height() < 180f,
        )
        assertTrue(
            "patch width ${block.backgroundRect.width()} should follow the text length",
            block.backgroundRect.width() > 400f,
        )
    }

    @Test
    fun uprightTextStaysAxisAlignedAndCoversItsBox() {
        val blocks = prepare(0f)
        assertEquals(1, blocks.size)
        val block = blocks.first()
        assertEquals(0f, block.rotationDegrees, 0.01f)
        val box = ocrBox(0f)
        val localWidth = block.backgroundRect.width()
        val localHeight = block.backgroundRect.height()
        assertTrue("height $localHeight should cover the box", localHeight >= box.height * 0.9f)
        assertTrue("width $localWidth should cover the box", localWidth >= box.width * 0.9f)
        assertTrue("height $localHeight should not be wildly larger", localHeight < box.height * 2f)
    }
}
