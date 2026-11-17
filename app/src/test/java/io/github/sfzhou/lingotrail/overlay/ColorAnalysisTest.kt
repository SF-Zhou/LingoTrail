package io.github.sfzhou.lingotrail.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorAnalysisTest {

    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()
    private val midGray = 0xFF808080.toInt()

    @Test
    fun detectsLightPaperAndDarkInk() {
        val pixels = IntArray(1_000) { white } + IntArray(120) { black }
        val colors = ColorAnalysis.analyze(pixels.toMutableList().shuffled().toIntArray())

        assertEquals(255, (colors.background ushr 24) and 0xFF)
        assertTrue(ColorAnalysis.luminance(colors.background) > 0.9f)
        assertTrue(ColorAnalysis.luminance(colors.foreground) < 0.2f)
        assertTrue(ColorAnalysis.contrastRatio(colors.background, colors.foreground) >= 4.5f)
    }

    @Test
    fun detectsDarkBackgroundAndLightInk() {
        val background = 0xFF1B2622.toInt()
        val ink = 0xFFF2F2EE.toInt()
        val pixels = IntArray(1_000) { background } + IntArray(90) { ink }
        val colors = ColorAnalysis.analyze(pixels.toMutableList().shuffled().toIntArray())

        assertTrue(ColorAnalysis.luminance(colors.background) < 0.1f)
        assertTrue(ColorAnalysis.luminance(colors.foreground) > 0.7f)
        assertTrue(ColorAnalysis.contrastRatio(colors.background, colors.foreground) >= 4.5f)
    }

    @Test
    fun keepsColourfulInkWhenItContrastsEnough() {
        val paper = 0xFFFFF8E1.toInt()
        val redInk = 0xFFB3261E.toInt()
        val pixels = IntArray(800) { paper } + IntArray(80) { redInk }
        val colors = ColorAnalysis.analyze(pixels.toMutableList().shuffled().toIntArray())

        assertTrue("background should stay light", ColorAnalysis.luminance(colors.background) > 0.8f)
        assertTrue("background should stay warm", (colors.background and 0xFF) < (colors.background shr 16 and 0xFF))
        assertTrue(ColorAnalysis.contrastRatio(colors.background, colors.foreground) >= 3f)
    }

    @Test
    fun antialiasedInkStaysDark() {
        // A black glyph on white paper, mostly antialiased edges.
        val paper = 0xFFFFFFFF.toInt()
        val solidInk = 0xFF101010.toInt()
        val edgeInk = 0xFF9E9E9E.toInt()
        val pixels = IntArray(700) { paper } + IntArray(60) { solidInk } + IntArray(140) { edgeInk }
        val colors = ColorAnalysis.analyze(pixels.toMutableList().shuffled().toIntArray())

        assertTrue("background should stay light", ColorAnalysis.luminance(colors.background) > 0.85f)
        assertTrue(
            "ink should stay dark, was ${ColorAnalysis.luminance(colors.foreground)}",
            ColorAnalysis.luminance(colors.foreground) < 0.3f,
        )
        assertTrue(ColorAnalysis.contrastRatio(colors.background, colors.foreground) >= 7f)
    }

    @Test
    fun uniformRegionFallsBackToReadableContrast() {
        val colors = ColorAnalysis.analyze(IntArray(200) { midGray })
        assertTrue(ColorAnalysis.contrastRatio(colors.background, colors.foreground) >= 4.5f)
    }

    @Test
    fun ignoresTransparentPixelsAndHandlesEmptyInput() {
        val colors = ColorAnalysis.analyze(IntArray(50))
        assertTrue(ColorAnalysis.contrastRatio(colors.background, colors.foreground) >= 4.5f)

        val withTransparent = IntArray(100) { 0x00FFFFFF } + IntArray(100) { black } + IntArray(10) { white }
        val mixed = ColorAnalysis.analyze(withTransparent)
        assertEquals(255, (mixed.background ushr 24) and 0xFF)
    }

    @Test
    fun medianIsRobustAgainstMinorityPixels() {
        val pixels = IntArray(900) { white } + IntArray(100) { black }
        val median = ColorAnalysis.medianColor(pixels)
        assertTrue(ColorAnalysis.luminance(median) > 0.9f)
    }

    @Test
    fun dominantColourIgnoresTextAndCompressionRinging() {
        val paper = 0xFF2E3E4A.toInt()
        val pixels = IntArray(1_000) { paper } +
            IntArray(120) { 0xFFFFFFFF.toInt() } +
            IntArray(60) { 0xFF6E7E8A.toInt() }
        assertEquals(paper, ColorAnalysis.dominantColor(pixels.toMutableList().shuffled().toIntArray()))
    }

    @Test
    fun contrastColorPicksOppositePoles() {
        val onWhite = ColorAnalysis.contrastColor(white)
        val onBlack = ColorAnalysis.contrastColor(black)
        assertTrue(ColorAnalysis.luminance(onWhite) < 0.2f)
        assertTrue(ColorAnalysis.luminance(onBlack) > 0.7f)
    }
}
