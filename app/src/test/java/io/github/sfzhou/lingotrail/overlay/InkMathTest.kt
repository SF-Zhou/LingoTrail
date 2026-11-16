package io.github.sfzhou.lingotrail.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class InkMathTest {

    private fun linePoints(angleDeg: Float, length: Float = 300f, thickness: Float = 20f): Pair<FloatArray, FloatArray> {
        val radians = Math.toRadians(angleDeg.toDouble())
        val ux = cos(radians).toFloat()
        val uy = sin(radians).toFloat()
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        var u = -length / 2
        while (u <= length / 2) {
            var v = -thickness / 2
            while (v <= thickness / 2) {
                xs += 500f + u * ux - v * uy
                ys += 500f + u * uy + v * ux
                v += 2f
            }
            u += 2f
        }
        return xs.toFloatArray() to ys.toFloatArray()
    }

    @Test
    fun principalAxisFindsRotatedLine() {
        val (xs, ys) = linePoints(-18f)
        val axis = InkMath.principalAxis(xs, ys)
        assertEquals(-18f, axis.angleDegrees, 2f)
        assertTrue(axis.elongation > 20f)
    }

    @Test
    fun bestProjectionAngleFindsRotatedLine() {
        val (xs, ys) = linePoints(-18f)
        val weights = FloatArray(xs.size) { 1f }
        assertEquals(-18f, InkMath.bestProjectionAngle(xs, ys, weights, binWidth = 12f), 2f)
    }

    @Test
    fun bestProjectionAngleFindsRotatedLineWithNeighbourAndOutliers() {
        val (xs1, ys1) = linePoints(-18f)
        val (xs2raw, ys2raw) = linePoints(-18f)
        // Neighbour row 40px above, plus a horizontal card border below.
        val xs2 = FloatArray(xs2raw.size) { xs2raw[it] }
        val ys2 = FloatArray(ys2raw.size) { ys2raw[it] - 40f }
        val borderXs = FloatArray(400) { 100f + it }
        val borderYs = FloatArray(400) { 760f }
        val xs = xs1 + xs2 + borderXs
        val ys = ys1 + ys2 + borderYs
        val weights = FloatArray(xs.size) { 1f }
        assertEquals(-18f, InkMath.bestProjectionAngle(xs, ys, weights, binWidth = 12f), 2f)
    }

    @Test
    fun horizontalTextStaysAtZero() {
        val (xs, ys) = linePoints(0f)
        val weights = FloatArray(xs.size) { 1f }
        assertEquals(0f, InkMath.bestProjectionAngle(xs, ys, weights, binWidth = 12f), 0.01f)
    }

    @Test
    fun glyphHeightEstimateHandlesMultipleLines() {
        assertEquals(40f, InkMath.glyphHeightFromLineCount(40f, 1), 0.1f)
        assertEquals(40f, InkMath.glyphHeightFromLineCount(40f + 1.35f * 40f * 4f, 5), 1f)
    }
}
