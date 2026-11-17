package io.github.sfzhou.lingotrail

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.sfzhou.lingotrail.overlay.InkAnalyzer
import io.github.sfzhou.lingotrail.overlay.InkPlacement
import io.github.sfzhou.lingotrail.overlay.PatchBuilder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PatchBuilderTest {

    @Test
    fun inpaintedPatchRemovesOriginalGlyphs() {
        val width = 900
        val height = 300
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(150, 25, 35))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(235, 210, 130)
            textSize = 110f
        }
        canvas.drawText("DAILY NUTS", 80f, 190f, paint)

        // 900 px wide bitmap -> 0.9 px per virtual unit.
        val placement = InkPlacement(
            centerX = 430f,
            centerY = 165f,
            rotationDegrees = 0f,
            halfLength = 380f,
            halfHeight = 62f,
            glyphHeight = 95f,
            axisBounds = RectF(89f, 111f, 778f, 222f),
            coverage = RectF(-380f, -62f, 380f, 62f),
            isLinear = true,
        )
        val patch = PatchBuilder.build(bitmap, placement)
        assertNotNull("patch should be built", patch)
        val patchBitmap = patch!!.bitmap
        val pixels = IntArray(patchBitmap.width * patchBitmap.height)
        patchBitmap.getPixels(pixels, 0, patchBitmap.width, 0, 0, patchBitmap.width, patchBitmap.height)
        var brightInk = 0
        var opaque = 0
        for (pixel in pixels) {
            if ((pixel ushr 24) != 0) opaque++
            val red = (pixel shr 16) and 0xFF
            val green = (pixel shr 8) and 0xFF
            val blue = pixel and 0xFF
            if (red > 200 && green > 170 && blue > 80) brightInk++
        }
        val total = pixels.size
        assertTrue("patch should be opaque (was $opaque/$total)", opaque > total * 0.98f)
        assertTrue(
            "original glyph pixels should be almost gone (left $brightInk/$total)",
            brightInk < total * 0.002f,
        )
        bitmap.recycle()
        patchBitmap.recycle()
    }

    @Test
    fun straightTextIsLinearAndCircularLogoIsNot() {
        val straight = Bitmap.createBitmap(900, 260, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(straight)
        canvas.drawColor(Color.rgb(40, 60, 50))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 80f
        }
        canvas.drawText("OPEN DAILY 10:00", 90f, 160f, paint)
        val straightBox = RectF(80f, 60f, 820f, 200f)
        val straightPlacement = InkAnalyzer.analyze(straight, straightBox, 1)
        assertNotNull("straight text should be analysed", straightPlacement)
        assertTrue("straight text should be treated as linear", straightPlacement!!.isLinear)
        straight.recycle()

        // Circular logo lettering: characters laid out around a circle.
        val circular = Bitmap.createBitmap(700, 700, Bitmap.Config.ARGB_8888)
        val logoCanvas = Canvas(circular)
        logoCanvas.drawColor(Color.rgb(150, 25, 35))
        val letter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(235, 210, 130)
            textSize = 60f
        }
        val text = "IMPORTED INGREDIENTS"
        val radius = 210f
        for (index in text.indices) {
            val angle = Math.toRadians(-150.0 + index * (300.0 / text.length))
            val x = 350f + (radius * cos(angle)).toFloat()
            val y = 350f + (radius * sin(angle)).toFloat()
            logoCanvas.save()
            logoCanvas.rotate(angle.toFloat() * 180f / Math.PI.toFloat() + 90f, x, y)
            logoCanvas.drawText(text[index].toString(), x, y, letter)
            logoCanvas.restore()
        }
        // The circular bitmap is 700 px wide -> 0.7 px per virtual unit, so the
        // circle (px 80..620) spans virtual 114..886.
        val logoBox = RectF(114f, 114f, 886f, 886f)
        val logoPlacement = InkAnalyzer.analyze(circular, logoBox, 2)
        val description = logoPlacement?.let {
            "linear=${it.isLinear} angle=${it.rotationDegrees} halfL=${it.halfLength} " +
                "halfH=${it.halfHeight} glyph=${it.glyphHeight} center=(${it.centerX},${it.centerY})"
        } ?: "null"
        assertFalse(
            "circular lettering must not be straightened into a rotated label ($description)",
            logoPlacement == null || logoPlacement.isLinear,
        )
        circular.recycle()
    }
}
