package io.github.sfzhou.lingotrail.overlay

import android.graphics.Bitmap
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where a recognised block of text actually sits, in virtual image space.
 *
 * Unlike the axis-aligned boxes the OCR service reports, this describes the text
 * band's own direction: [rotationDegrees] is the angle of the text baseline,
 * [halfLength] runs along the text and [halfHeight] across it. A photo taken at an
 * angle therefore gets a tight rotated patch instead of a huge diagonal box.
 */
data class InkPlacement(
    val centerX: Float,
    val centerY: Float,
    val rotationDegrees: Float,
    val halfLength: Float,
    val halfHeight: Float,
    /** Estimated height of one original line of glyphs, in virtual pixels. */
    val glyphHeight: Float,
    /** Axis-aligned ink bounds, used for colour sampling. */
    val axisBounds: RectF,
    /**
     * Area the background patch must cover, in the block's rotated local frame.
     * For axis-aligned text this also covers the original OCR box, so old lines can
     * never peek out from under the patch.
     */
    val coverage: RectF,
    /**
     * False when the ink does not follow a straight baseline (logos printed around a
     * circle, arched text, ...). Such blocks are left untouched instead of being
     * straightened into a garbled overlay.
     */
    val isLinear: Boolean = true,
)

/**
 * Pure geometry maths for [InkAnalyzer]; free of Android classes so it is directly
 * unit-testable.
 */
object InkMath {

    data class PrincipalAxis(val angleDegrees: Float, val elongation: Float)

    /** Principal direction of a point cloud (PCA), normalised to (-90°, 90°]. */
    fun principalAxis(xs: FloatArray, ys: FloatArray): PrincipalAxis {
        require(xs.size == ys.size)
        if (xs.size < 3) return PrincipalAxis(0f, 1f)
        var meanX = 0.0
        var meanY = 0.0
        for (index in xs.indices) {
            meanX += xs[index]
            meanY += ys[index]
        }
        meanX /= xs.size
        meanY /= ys.size

        var covarianceXX = 0.0
        var covarianceYY = 0.0
        var covarianceXY = 0.0
        for (index in xs.indices) {
            val dx = xs[index] - meanX
            val dy = ys[index] - meanY
            covarianceXX += dx * dx
            covarianceYY += dy * dy
            covarianceXY += dx * dy
        }
        covarianceXX /= xs.size
        covarianceYY /= xs.size
        covarianceXY /= xs.size

        val halfDifference = (covarianceXX - covarianceYY) / 2.0
        val root = sqrt(halfDifference * halfDifference + covarianceXY * covarianceXY)
        val lambdaMax = (covarianceXX + covarianceYY) / 2.0 + root
        val lambdaMin = (covarianceXX + covarianceYY) / 2.0 - root
        if (lambdaMax <= 1e-6) return PrincipalAxis(0f, 1f)
        val elongation = if (lambdaMin <= 1e-6) 1_000f else (lambdaMax / lambdaMin).toFloat()

        var angle = Math.toDegrees(0.5 * atan2(2.0 * covarianceXY, covarianceXX - covarianceYY))
        angle = normalizeDegrees(angle)
        return PrincipalAxis(angle.toFloat(), elongation)
    }

    fun normalizeDegrees(angle: Double): Double {
        var result = angle % 180.0
        if (result > 90.0) result -= 180.0
        if (result <= -90.0) result += 180.0
        return result
    }

    /** Projects points onto the text axis (u) and the cross axis (v). */
    fun project(
        xs: FloatArray,
        ys: FloatArray,
        angleDegrees: Float,
    ): Pair<FloatArray, FloatArray> {
        val radians = Math.toRadians(angleDegrees.toDouble())
        val cosine = cos(radians).toFloat()
        val sine = sin(radians).toFloat()
        val us = FloatArray(xs.size)
        val vs = FloatArray(xs.size)
        for (index in xs.indices) {
            us[index] = xs[index] * cosine + ys[index] * sine
            vs[index] = -xs[index] * sine + ys[index] * cosine
        }
        return us to vs
    }

    /**
     * How sharply the ink piles up in the cross-axis projection. Text lines create
     * tall narrow peaks; the wrong angle smears them out. The bin width must be
     * larger than the ink-sampling stride, otherwise grid quantisation creates fake
     * peaks and biases the result towards horizontal.
     */
    fun projectionSharpness(vs: FloatArray, weights: FloatArray, binWidth: Float): Float {
        if (vs.isEmpty()) return 0f
        var minV = Float.MAX_VALUE
        var maxV = -Float.MAX_VALUE
        for (value in vs) {
            if (value < minV) minV = value
            if (value > maxV) maxV = value
        }
        val span = maxV - minV
        if (span <= 0f) return 0f
        val binCount = (span / binWidth).toInt().coerceIn(2, 4_000)
        val bins = FloatArray(binCount)
        val scale = (binCount - 1) / span
        for (index in vs.indices) {
            val bin = ((vs[index] - minV) * scale).toInt().coerceIn(0, binCount - 1)
            bins[bin] += weights[index]
        }
        var sharpness = 0f
        for (value in bins) sharpness += value * value
        return sharpness
    }

    /**
     * Searches text-baseline angles for the projection with the sharpest rows.
     * Tolerates extra ink around the line (neighbouring rows, a card border) because
     * such ink does not join the line's sharp band.
     */
    fun bestProjectionAngle(
        xs: FloatArray,
        ys: FloatArray,
        weights: FloatArray,
        binWidth: Float,
        minAngleDegrees: Float = -45f,
        maxAngleDegrees: Float = 45f,
        stepDegrees: Float = 1.5f,
    ): Float {
        if (xs.size < 3) return 0f
        val zeroScore = projectionSharpness(project(xs, ys, 0f).second, weights, binWidth)
        var bestAngle = 0f
        var bestScore = zeroScore
        var angle = minAngleDegrees
        while (angle <= maxAngleDegrees + 0.001f) {
            if (kotlin.math.abs(angle) > 0.01f) {
                val score = projectionSharpness(project(xs, ys, angle).second, weights, binWidth)
                if (score > bestScore) {
                    bestScore = score
                    bestAngle = angle
                }
            }
            angle += stepDegrees
        }
        // Only rotate when the angled projection is clearly sharper than horizontal.
        if (bestAngle != 0f && bestScore <= zeroScore * 1.25f) return 0f
        // Refine the winner at a finer step.
        var refined = bestAngle
        var refinedScore = bestScore
        var fine = bestAngle - stepDegrees
        while (fine <= bestAngle + stepDegrees + 0.001f) {
            val score = projectionSharpness(project(xs, ys, fine).second, weights, binWidth)
            if (score > refinedScore) {
                refinedScore = score
                refined = fine
            }
            fine += 0.4f
        }
        // Tilts below ~4° are not worth rotating: the patch is already tight enough
        // and a spurious few-degree rotation would misalign the text ends.
        return if (kotlin.math.abs(refined) < 4f) 0f else refined
    }

    fun sortedCopy(values: FloatArray): FloatArray = values.copyOf().also { it.sort() }

    /** Value at [fraction] (0..1) of a sorted array. */
    fun percentile(sorted: FloatArray, fraction: Float): Float {
        if (sorted.isEmpty()) return 0f
        val index = ((sorted.size - 1) * fraction).toInt().coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }

    /**
     * Estimated glyph height from a block's cross-axis extent and its line count.
     *
     * Line spacing in real text is roughly 1.35× the glyph height, and a block of
     * `n` lines spans `(n - 1)` spacings plus one glyph, hence:
     * `extent ≈ glyph × (1.35n − 0.35)`.
     */
    fun glyphHeightFromLineCount(crossExtent: Float, lineCount: Int): Float {
        if (lineCount <= 1) return crossExtent
        return crossExtent / (1.35f * lineCount - 0.35f)
    }

    /**
     * Target font size for the translation.
     *
     * A font's ink height is roughly 0.75–0.95 of its em size depending on the
     * script, so a font slightly larger than the original ink height produces
     * glyphs that visually match the original text instead of filling the box.
     */
    fun targetFontSize(glyphHeight: Float): Float = glyphHeight * 1.15f
}

/**
 * Finds the actual text band inside an OCR box: estimates the text direction with
 * PCA, measures glyph height from the ink itself, and returns a tight rotated
 * placement. Returns null when the ink cannot be measured reliably, in which case
 * the caller falls back to the axis-aligned OCR box.
 */
object InkAnalyzer {

    private const val MIN_INK_DISTANCE = 42f
    private const val MAX_INK_DISTANCE = 150f
    private const val MAX_SAMPLES = 48_000
    private const val MIN_INK_POINTS = 24
    private const val MIN_ELONGATION = 2.2f
    private const val MIN_ROTATION_DEGREES = 2.5f
    private const val MAX_ROTATION_DEGREES = 60f

    fun analyze(
        bitmap: Bitmap,
        ocrRectVirtual: RectF,
        lineCount: Int,
    ): InkPlacement? {
        if (bitmap.width < 8 || bitmap.height < 8) return null
        val pixelsPerVirtual = bitmap.width / PreparedOverlay.VIRTUAL_WIDTH
        if (pixelsPerVirtual <= 0f) return null

        val base = RectF(
            ocrRectVirtual.left * pixelsPerVirtual,
            ocrRectVirtual.top * pixelsPerVirtual,
            ocrRectVirtual.right * pixelsPerVirtual,
            ocrRectVirtual.bottom * pixelsPerVirtual,
        )
        val centerX = base.centerX()
        val centerY = base.centerY()
        val halfBoxWidth = base.width() / 2f
        val halfBoxHeight = base.height() / 2f
        if (halfBoxWidth < 2f || halfBoxHeight < 2f) return null

        // Stage A: a tight window around the OCR box gives a clean angle estimate.
        // A local-contrast filter drops smooth areas (walls, shadows, gradients) so
        // only glyph strokes and thin borders take part in the projection.
        val angleWindow = sampleWindow(
            bitmap = bitmap,
            centerX = centerX,
            centerY = centerY,
            halfWidth = max(10f, halfBoxWidth * 1.24f),
            halfHeight = max(10f, halfBoxHeight * 1.24f),
        ) ?: return null
        val angleInk = angleWindow.inkPoints(minContrast = CONTRAST_THRESHOLD)
        if (angleInk.size < MIN_INK_POINTS) return null

        val angleSigma = max(8f, 0.5f * max(angleWindow.halfWidth, angleWindow.halfHeight))
        val angleWeights = angleWindow.weights(angleInk, angleSigma)
        val binWidth = max(4f, min(48f, bitmap.width * 0.005f))
        val angleXs = FloatArray(angleInk.size) { angleInk[it].x }
        val angleYs = FloatArray(angleInk.size) { angleInk[it].y }
        var rotation = InkMath.bestProjectionAngle(
            angleXs,
            angleYs,
            angleWeights,
            binWidth = binWidth,
        )

        // Stage B: a wider window, filtered to the band perpendicular to the
        // estimated baseline, collects the whole line/paragraph for measuring. A
        // tilted line drops far below its axis-aligned box, so the window height
        // grows with the measured angle - otherwise both ends of the line fall
        // outside the window and the patch would be too short.
        val radiansForWindow = Math.toRadians(rotation.toDouble())
        val sineForWindow = sin(radiansForWindow).toFloat()
        val cosineForWindow = cos(radiansForWindow).toFloat()
        val measureWindow = sampleWindow(
            bitmap = bitmap,
            centerX = centerX,
            centerY = centerY,
            halfWidth = max(10f, halfBoxWidth * 1.6f + halfBoxHeight * 0.8f + 16f),
            halfHeight = max(
                10f,
                halfBoxWidth * abs(sineForWindow) + halfBoxHeight * 0.95f + 8f,
            ),
        ) ?: return null
        val measureInk = measureWindow.inkPoints(minContrast = CONTRAST_THRESHOLD)
        if (measureInk.size < MIN_INK_POINTS) return null

        val radians = Math.toRadians(rotation.toDouble())
        val cosine = cos(radians).toFloat()
        val sine = sin(radians).toFloat()
        val ocrVCenter = -centerX * sine + centerY * cosine
        val projectedHalfHeight = halfBoxWidth * abs(sine) + halfBoxHeight * abs(cosine)
        val safeLineCountForWindow = lineCount.coerceAtLeast(1)
        val vLimit = if (safeLineCountForWindow > 1) {
            max(projectedHalfHeight * 1.8f, base.height() * 1.3f)
        } else {
            projectedHalfHeight * 1.25f + measureWindow.halfHeight * 0.15f
        }

        val filtered = measureInk.filter { point ->
            val v = -point.x * sine + point.y * cosine
            abs(v - ocrVCenter) <= vLimit
        }
        val usable = if (filtered.size >= MIN_INK_POINTS) filtered else measureInk
        val filterSigma = max(8f, 0.55f * max(measureWindow.halfWidth, measureWindow.halfHeight))
        val weights = measureWindow.weights(usable, filterSigma)
        val xs = FloatArray(usable.size) { usable[it].x }
        val ys = FloatArray(usable.size) { usable[it].y }
        val (us, vs) = InkMath.project(xs, ys, rotation)
        val sortedVs = InkMath.sortedCopy(vs)

        val bands = detectBands(sortedVs, measureWindow.stride)
        if (bands.isEmpty()) return null
        val safeLineCount = lineCount.coerceAtLeast(1)

        // Score each band by how much of the OCR box it covers: OCR boxes for
        // rotated text are not line-aligned, but they still sit closest to their own
        // line, which is more reliable than comparing band centres.
        data class BandCandidate(
            val vLow: Float,
            val vHigh: Float,
            val uLow: Float,
            val uHigh: Float,
            val overlap: Float,
            val centerDistance: Float,
        )

        val baseArea = (base.width() * base.height()).coerceAtLeast(1f)
        val candidates = bands.mapNotNull { band ->
            val bandUs = ArrayList<Float>()
            for (point in 0 until usable.size) {
                if (vs[point] >= band.start - 1f && vs[point] <= band.endInclusive + 1f) {
                    bandUs += us[point]
                }
            }
            if (bandUs.size < MIN_INK_POINTS) return@mapNotNull null
            val bandSortedUs = bandUs.sorted()
            val bandULow = InkMath.percentile(bandSortedUs.toFloatArray(), 0.01f)
            val bandUHigh = InkMath.percentile(bandSortedUs.toFloatArray(), 0.99f)
            if (bandUHigh - bandULow < 4f) return@mapNotNull null
            val bbox = bandAxisBounds(bandULow, bandUHigh, band.start, band.endInclusive, cosine, sine)
            val overlap = rectOverlap(bbox, base)
            val bandCenterV = (band.start + band.endInclusive) / 2f
            BandCandidate(
                vLow = band.start,
                vHigh = band.endInclusive,
                uLow = bandULow,
                uHigh = bandUHigh,
                overlap = overlap,
                centerDistance = abs(bandCenterV - ocrVCenter),
            )
        }
        if (candidates.isEmpty()) return null

        val chosenBand: BandCandidate?
        val selectedCandidates: List<BandCandidate>
        if (safeLineCount <= 1) {
            chosenBand = candidates.maxWithOrNull(
                compareBy<BandCandidate> { it.overlap }
                    .thenByDescending { -it.centerDistance },
            ) ?: candidates.first()
            selectedCandidates = listOf(chosenBand)
        } else {
            chosenBand = null
            val sortedByV = candidates.sortedBy { it.vLow }
            val centers = sortedByV.map { (it.vLow + it.vHigh) / 2f }
            val gaps = centers.zipWithNext { first, second -> second - first }
                .filter { it > 0f }
                .sorted()
            val medianGap = if (gaps.isEmpty()) 0f else gaps[gaps.size / 2]
            val seeds = sortedByV
                .mapIndexedNotNull { index, candidate ->
                    index.takeIf { candidate.overlap >= baseArea * 0.02f }
                }
                .ifEmpty { sortedByV.indices.toList() }
            // Paragraphs often have an OCR box that covers only the first lines;
            // grow the selection across neighbouring rows while the line spacing
            // stays paragraph-like.
            val maxGap = if (medianGap > 0f) medianGap * 1.35f else Float.MAX_VALUE
            var lowIndex = seeds.minOf { it }
            var highIndex = seeds.maxOf { it }
            while (lowIndex > 0 && centers[lowIndex] - centers[lowIndex - 1] <= maxGap) {
                lowIndex--
            }
            while (highIndex < sortedByV.lastIndex &&
                centers[highIndex + 1] - centers[highIndex] <= maxGap
            ) {
                highIndex++
            }
            selectedCandidates = sortedByV.subList(lowIndex, highIndex + 1)
        }

        val vLow = selectedCandidates.minOf { it.vLow }
        val vHigh = selectedCandidates.maxOf { it.vHigh }
        val glyphHeightPx: Float
        if (chosenBand != null) {
            glyphHeightPx = max(2f, chosenBand.vHigh - chosenBand.vLow)
        } else {
            val crossExtent = (vHigh - vLow).coerceAtLeast(2f)
            val formula = InkMath.glyphHeightFromLineCount(crossExtent, safeLineCount)
            val bandHeights = selectedCandidates
                .map { it.vHigh - it.vLow }
                .sorted()
            val medianBandHeight = bandHeights[bandHeights.size / 2]
            glyphHeightPx = if (selectedCandidates.size >= 2) {
                max(2f, min(medianBandHeight, formula * 1.15f))
            } else {
                max(2f, formula)
            }
        }

        val usedPoints = ArrayList<Int>(usable.size)
        var uLow = Float.MAX_VALUE
        var uHigh = -Float.MAX_VALUE
        var axisLeft = Float.MAX_VALUE
        var axisTop = Float.MAX_VALUE
        var axisRight = -Float.MAX_VALUE
        var axisBottom = -Float.MAX_VALUE
        for (point in 0 until usable.size) {
            if (vs[point] < vLow - 1f || vs[point] > vHigh + 1f) continue
            usedPoints += point
            uLow = min(uLow, us[point])
            uHigh = max(uHigh, us[point])
            axisLeft = min(axisLeft, xs[point])
            axisTop = min(axisTop, ys[point])
            axisRight = max(axisRight, xs[point])
            axisBottom = max(axisBottom, ys[point])
        }
        if (usedPoints.size < MIN_INK_POINTS || uHigh - uLow < 4f) return null

        val usedUs = FloatArray(usedPoints.size) { us[usedPoints[it]] }
        val sortedUs = InkMath.sortedCopy(usedUs)
        val trimmedULow = if (chosenBand != null) {
            chosenBand.uLow
        } else {
            InkMath.percentile(sortedUs, 0.002f)
        }
        val trimmedUHigh = if (chosenBand != null) {
            chosenBand.uHigh
        } else {
            InkMath.percentile(sortedUs, 0.998f)
        }
        if (trimmedUHigh - trimmedULow < 4f) return null

        val halfLength = (trimmedUHigh - trimmedULow) / 2f
        val halfHeight = (vHigh - vLow) / 2f
        val uCenter = (trimmedUHigh + trimmedULow) / 2f
        val vCenter = (vHigh + vLow) / 2f
        var placementCenterX = uCenter * cosine - vCenter * sine
        var placementCenterY = uCenter * sine + vCenter * cosine

        // Keep the rotated patch inside the photo.
        val axisHalfWidth = abs(cosine) * halfLength + abs(sine) * halfHeight
        val axisHalfHeight = abs(sine) * halfLength + abs(cosine) * halfHeight
        placementCenterX = if (axisHalfWidth * 2f >= bitmap.width) {
            bitmap.width / 2f
        } else {
            placementCenterX.coerceIn(axisHalfWidth, bitmap.width - axisHalfWidth)
        }
        placementCenterY = if (axisHalfHeight * 2f >= bitmap.height) {
            bitmap.height / 2f
        } else {
            placementCenterY.coerceIn(axisHalfHeight, bitmap.height - axisHalfHeight)
        }

        val glyphHeight = (glyphHeightPx / pixelsPerVirtual).coerceAtLeast(3f)
        val linearityLimit = max(4f, glyphHeightPx * 0.7f)
        val isLinear = if (safeLineCountForWindow <= 1) {
            val residual = baselineResidual(us, vs, vLow, vHigh)
            residual == null || residual <= linearityLimit
        } else {
            // Stacked text separates into one ink band per line inside a wide box.
            // Circular logo lettering sits in a nearly square box, merges into one
            // dense ring or bends away from a straight baseline.
            val boxAspect = base.width() / base.height().coerceAtLeast(1f)
            val squareBox = boxAspect in 0.75f..1.45f
            !squareBox && bands.size >= 2 && selectedCandidates.none { band ->
                val residual = baselineResidual(us, vs, band.vLow, band.vHigh)
                residual != null && residual > linearityLimit
            }
        }
        val padding = max(2f, glyphHeight * 0.28f)
        val coverage: RectF
        if (abs(rotation) < 0.01f) {
            // Axis-aligned: cover the union of the measured band and the OCR box,
            // which is the behaviour that keeps tight boxes fully erased.
            val unionLeft = min(-halfLength, base.left - placementCenterX)
            val unionTop = min(-halfHeight, base.top - placementCenterY)
            val unionRight = max(halfLength, base.right - placementCenterX)
            val unionBottom = max(halfHeight, base.bottom - placementCenterY)
            coverage = RectF(
                unionLeft / pixelsPerVirtual - padding,
                unionTop / pixelsPerVirtual - padding,
                unionRight / pixelsPerVirtual + padding,
                unionBottom / pixelsPerVirtual + padding,
            )
        } else {
            coverage = RectF(
                -halfLength / pixelsPerVirtual - padding,
                -halfHeight / pixelsPerVirtual - padding,
                halfLength / pixelsPerVirtual + padding,
                halfHeight / pixelsPerVirtual + padding,
            )
        }
        return InkPlacement(
            centerX = placementCenterX / pixelsPerVirtual,
            centerY = placementCenterY / pixelsPerVirtual,
            rotationDegrees = rotation,
            halfLength = (halfLength / pixelsPerVirtual).coerceAtLeast(1.5f),
            halfHeight = (halfHeight / pixelsPerVirtual).coerceAtLeast(1f),
            glyphHeight = glyphHeight,
            axisBounds = RectF(
                axisLeft / pixelsPerVirtual,
                axisTop / pixelsPerVirtual,
                axisRight / pixelsPerVirtual,
                axisBottom / pixelsPerVirtual,
            ),
            coverage = coverage,
            isLinear = isLinear,
        )
    }

    private class SampledWindow(
        val stride: Int,
        val halfWidth: Float,
        val halfHeight: Float,
        val pixels: IntArray,
        val xs: IntArray,
        val ys: IntArray,
        val columns: Int,
        val rows: Int,
        val background: Int,
        val inkThreshold: Float,
    ) {
        /** Ink points (bitmap px) whose neighbourhood has enough local contrast. */
        fun inkPoints(minContrast: Int): List<InkPoint> {
            val points = ArrayList<InkPoint>()
            for (index in pixels.indices) {
                if (ColorAnalysis.colorDistance(pixels[index], background) <= inkThreshold) continue
                if (localContrast(index) < minContrast) continue
                points += InkPoint(xs[index].toFloat(), ys[index].toFloat())
            }
            return points
        }

        fun weights(points: List<InkPoint>, sigma: Float): FloatArray {
            val centerX = (xs.first() + xs.last()) / 2f
            val centerY = (ys.first() + ys.last()) / 2f
            return FloatArray(points.size) {
                val dx = points[it].x - centerX
                val dy = points[it].y - centerY
                kotlin.math.exp(-(dx * dx + dy * dy) / (2f * sigma * sigma))
            }
        }

        private fun localContrast(index: Int): Int {
            val row = index / columns
            val column = index % columns
            val center = pixels[index]
            var best = 0
            for (offset in CONTRAST_OFFSETS) {
                val otherRow = row + offset[1]
                val otherColumn = column + offset[0]
                if (otherRow !in 0 until rows || otherColumn !in 0 until columns) continue
                val other = pixels[otherRow * columns + otherColumn]
                val difference = maxOf(
                    abs(((center shr 16) and 0xFF) - ((other shr 16) and 0xFF)),
                    abs(((center shr 8) and 0xFF) - ((other shr 8) and 0xFF)),
                    abs((center and 0xFF) - (other and 0xFF)),
                )
                if (difference > best) best = difference
            }
            return best
        }
    }

    private data class InkPoint(val x: Float, val y: Float)

    /**
     * Fits a straight baseline to the per-column median of one text band and returns
     * the RMS residual in pixels, or null when there is not enough ink. Straight text
     * gives a few pixels; circular or arched lettering bends away from the line.
     */
    private fun baselineResidual(
        us: FloatArray,
        vs: FloatArray,
        vLow: Float,
        vHigh: Float,
    ): Float? {
        val indices = us.indices.filter { vs[it] >= vLow - 1f && vs[it] <= vHigh + 1f }
        if (indices.size < 40) return null
        var minU = Float.MAX_VALUE
        var maxU = -Float.MAX_VALUE
        for (index in indices) {
            if (us[index] < minU) minU = us[index]
            if (us[index] > maxU) maxU = us[index]
        }
        if (maxU - minU < (vHigh - vLow) * 1.2f) return null
        val binCount = 12
        val binWidth = (maxU - minU) / binCount
        if (binWidth <= 0f) return null
        val count = IntArray(binCount)
        val sumU = FloatArray(binCount)
        val sumV = FloatArray(binCount)
        for (index in indices) {
            val bin = ((us[index] - minU) / binWidth).toInt().coerceIn(0, binCount - 1)
            count[bin]++
            sumU[bin] += us[index]
            sumV[bin] += vs[index]
        }
        var n = 0
        var sumX = 0f
        var sumY = 0f
        var sumXX = 0f
        var sumXY = 0f
        for (bin in 0 until binCount) {
            if (count[bin] < 3) continue
            val x = sumU[bin] / count[bin]
            val y = sumV[bin] / count[bin]
            n++
            sumX += x
            sumY += y
            sumXX += x * x
            sumXY += x * y
        }
        if (n < 3) return null
        val denominator = n * sumXX - sumX * sumX
        if (kotlin.math.abs(denominator) < 1e-3f) return null
        val slope = (n * sumXY - sumX * sumY) / denominator
        val intercept = (sumY - slope * sumX) / n
        var residual = 0.0
        var weight = 0
        for (bin in 0 until binCount) {
            if (count[bin] < 3) continue
            val x = sumU[bin] / count[bin]
            val y = sumV[bin] / count[bin]
            val diff = (y - (slope * x + intercept)).toDouble()
            residual += diff * diff * count[bin]
            weight += count[bin]
        }
        if (weight == 0) return null
        return kotlin.math.sqrt(residual / weight).toFloat()
    }

    private fun bandAxisBounds(
        uLow: Float,
        uHigh: Float,
        vLow: Float,
        vHigh: Float,
        cosine: Float,
        sine: Float,
    ): RectF {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (u in floatArrayOf(uLow, uHigh)) {
            for (v in floatArrayOf(vLow, vHigh)) {
                val x = u * cosine - v * sine
                val y = u * sine + v * cosine
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
        return RectF(minX, minY, maxX, maxY)
    }

    private fun rectOverlap(first: RectF, second: RectF): Float {
        val width = min(first.right, second.right) - max(first.left, second.left)
        val height = min(first.bottom, second.bottom) - max(first.top, second.top)
        return if (width > 0f && height > 0f) width * height else 0f
    }

    private fun sampleWindow(
        bitmap: Bitmap,
        centerX: Float,
        centerY: Float,
        halfWidth: Float,
        halfHeight: Float,
    ): SampledWindow? {
        val left = (centerX - halfWidth).toInt().coerceIn(0, bitmap.width - 1)
        val top = (centerY - halfHeight).toInt().coerceIn(0, bitmap.height - 1)
        val right = (centerX + halfWidth).toInt().coerceIn(left + 1, bitmap.width)
        val bottom = (centerY + halfHeight).toInt().coerceIn(top + 1, bitmap.height)
        val windowWidth = right - left
        val windowHeight = bottom - top
        if (windowWidth < 4 || windowHeight < 4) return null

        val stride = max(1, ceil(sqrt(windowWidth.toFloat() * windowHeight / MAX_SAMPLES)).toInt())
        val columns = ceil(windowWidth.toFloat() / stride).toInt().coerceAtLeast(1)
        val rows = ceil(windowHeight.toFloat() / stride).toInt().coerceAtLeast(1)
        if (columns * rows < 16) return null

        val pixels = IntArray(columns * rows)
        val xs = IntArray(columns * rows)
        val ys = IntArray(columns * rows)
        var index = 0
        for (row in 0 until rows) {
            val y = (top + row * stride).coerceAtMost(bottom - 1)
            for (column in 0 until columns) {
                val x = (left + column * stride).coerceAtMost(right - 1)
                pixels[index] = bitmap.getPixel(x, y)
                xs[index] = x
                ys[index] = y
                index++
            }
        }

        val background = ColorAnalysis.dominantColor(pixels)
        val distances = FloatArray(pixels.size) { ColorAnalysis.colorDistance(pixels[it], background) }
        val sortedDistances = InkMath.sortedCopy(distances)
        val ninetyPercent = InkMath.percentile(sortedDistances, 0.9f)
        val inkThreshold = ninetyPercent.coerceIn(MIN_INK_DISTANCE, MAX_INK_DISTANCE)
        return SampledWindow(
            stride = stride,
            halfWidth = windowWidth / 2f,
            halfHeight = windowHeight / 2f,
            pixels = pixels,
            xs = xs,
            ys = ys,
            columns = columns,
            rows = rows,
            background = background,
            inkThreshold = inkThreshold,
        )
    }

    private val CONTRAST_OFFSETS = arrayOf(
        intArrayOf(1, 0),
        intArrayOf(-1, 0),
        intArrayOf(0, 1),
        intArrayOf(0, -1),
        intArrayOf(1, 1),
        intArrayOf(-1, -1),
        intArrayOf(1, -1),
        intArrayOf(-1, 1),
        intArrayOf(2, 0),
        intArrayOf(-2, 0),
        intArrayOf(0, 2),
        intArrayOf(0, -2),
    )

    private const val CONTRAST_THRESHOLD = 34

    /** Groups ink rows (along v) into text-line bands. */
    private fun detectBands(sortedVs: FloatArray, stride: Int): List<ClosedFloatingPointRange<Float>> {
        if (sortedVs.size < 2) return emptyList()
        val vMin = sortedVs.first()
        val vMax = sortedVs.last()
        val span = vMax - vMin
        if (span < 1f) return emptyList()
        // The ink samples sit on a grid with `stride` spacing, so a 1 px histogram
        // would split one line into many fake bands. One bin per stride keeps a
        // line contiguous at any text angle.
        val binSize = max(1f, stride.toFloat())
        val binCount = (span / binSize).toInt().coerceIn(2, 4_000)
        val bins = IntArray(binCount)
        val scale = (binCount - 1) / span
        for (value in sortedVs) {
            bins[((value - vMin) * scale).toInt().coerceIn(0, binCount - 1)]++
        }
        val smoothed = IntArray(binCount) { index ->
            var sum = bins[index]
            if (index > 0) sum += bins[index - 1]
            if (index < binCount - 1) sum += bins[index + 1]
            sum
        }
        val peak = smoothed.maxOrNull() ?: return emptyList()
        if (peak <= 0) return emptyList()
        val threshold = max(2, (peak * 0.10f).toInt())
        val bands = mutableListOf<IntRange>()
        var bandStart = -1
        var lastAbove = -1
        for (index in smoothed.indices) {
            if (smoothed[index] >= threshold) {
                if (bandStart < 0) {
                    bandStart = index
                } else if (index - lastAbove > 2) {
                    bands += bandStart until lastAbove + 1
                    bandStart = index
                }
                lastAbove = index
            }
        }
        if (bandStart >= 0) bands += bandStart until lastAbove + 1
        val minBins = max(1, (0.6f / 1f).toInt())
        val usable = bands.filter { it.last - it.first + 1 >= minBins }.ifEmpty { bands }
        return usable.map { band ->
            val start = vMin + (band.first - 1).coerceAtLeast(0) * binSize
            val end = vMin + (band.last + 1).coerceAtMost(binCount - 1) * binSize
            start..end
        }.sortedBy { it.start }
    }

}
