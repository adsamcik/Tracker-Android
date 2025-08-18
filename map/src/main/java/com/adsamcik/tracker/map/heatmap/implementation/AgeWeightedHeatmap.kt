package com.adsamcik.tracker.map.heatmap.implementation

import com.adsamcik.tracker.logger.assertLess
import com.adsamcik.tracker.logger.assertMore
import com.adsamcik.tracker.logger.assertMoreOrEqual
import com.adsamcik.tracker.logger.assertWithin
import com.adsamcik.tracker.map.heatmap.HeatmapColorScheme
import com.adsamcik.tracker.map.heatmap.HeatmapStamp
import com.adsamcik.tracker.map.heatmap.creators.RevisitEasing
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.extension.withAlpha
import com.adsamcik.tracker.shared.utils.style.color.ColorConstants.EMPTY_COMPONENT
import com.adsamcik.tracker.shared.utils.style.color.ColorConstants.FULL_COMPONENT
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import java.util.Arrays

/* heatmap - High performance heatmap creation in C. (Rewritten to Kotlin)
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2013 Lucas Beyer
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 *
 * https://github.com/lucasb-eyer/heatmap/
 */

typealias AlphaMergeFunction = (current: Int, stampValue: Float, weight: Float) -> Int

typealias WeightMergeFunction = (current: Float, currentAlpha: Int, stampValue: Float, value: Float) -> Float

const val AGE_THRESHOLD_MINUTES: Int = 15

@Suppress("MemberVisibilityCanBePrivate")
internal class AgeWeightedHeatmap(
    val width: Int,
    val height: Int = width,
    val ageThreshold: Int = AGE_THRESHOLD_MINUTES * Time.MINUTE_IN_SECONDS.toInt(),
    var maxHeat: Float = 0f,
    var dynamicHeat: Boolean = true,
    var revisitIntervalSec: Int = 0,
    var revisitEasing: RevisitEasing = RevisitEasing.Smoothstep,
    var revisitEasingStrength: Float = 3f
) {
    // width * height * (1+4+4+4 = 13 bytes) total array size ~= 0.85MB for 256*256 tiles
    private val alphaArray: UByteArray = UByteArray(width * height)
    private val weightArray: FloatArray = FloatArray(width * height)
    private val ageArray: IntArray = IntArray(width * height) { -ageThreshold }
    private val lastValueArray: FloatArray = FloatArray(width * height)

    private var pointCount = 0

    @Suppress("unused_parameter")
    private fun mergeWeightDefault(
        current: Float,
        currentAlpha: Int,
        stampValue: Float,
        value: Float
    ): Float {
        return current + stampValue * value
    }

    @Suppress("unused_parameter")
    private fun mergeAlphaDefault(value: Int, stampValue: Float, weight: Float): Int {
        return max(value, (stampValue * UByte.MAX_VALUE.toFloat()).toInt())
    }

    // Suppressed because at the time of writing, this function is considered readable
    @Suppress("ComplexMethod", "LongParameterList", "NestedBlockDepth")
    fun addPoint(
        x: Int,
        y: Int,
        ageInSeconds: Int,
        weight: Float = 1f,
        stamp: HeatmapStamp = HeatmapStamp.default9x9,
        weightMergeFunction: WeightMergeFunction = this::mergeWeightDefault,
        alphaMergeFunction: AlphaMergeFunction = this::mergeAlphaDefault
    ) {
        //todo validate that odd numbers don't cause some weird artifacts
        val halfStampHeight = stamp.height / 2
        val halfStampWidth = stamp.width / 2

        // Assert point will have anything to draw
        // There are points that do not get drawn but it seems to be a little more complicated than some mistake.
        // This is not that big of an issue, since it causes no problems and performance impact is quite low.
        /*assertLess(x - halfStampWidth / 2, width + 1)
        assertLess(y - halfStampHeight, height + 1)
        assertMoreOrEqual(x - halfStampHeight, -1)
        assertMoreOrEqual(y + halfStampWidth / 2, -1)*/

        pointCount++

        /* Note: the order of operations is important, since we're computing with unsigned! */

        /* These are [first, last) pairs in the STAMP's pixels. */
        val x0 = if (x < halfStampWidth) halfStampWidth - x else 0
        val y0 = if (y < halfStampHeight) halfStampHeight - y else 0
        val x1 = if (x + halfStampWidth < width) stamp.width else halfStampWidth + width - x
        val y1 = if (y + halfStampHeight < height) stamp.height else halfStampHeight + height - y

        // Neighbor-aware density: compute a small local mean around the stamp center
        val densityRadius = max(2, min(halfStampWidth, halfStampHeight))
        var densitySum = 0f
        var densityCount = 0
        run {
            val offsets = intArrayOf(-densityRadius, 0, densityRadius)
            for (dy in offsets) {
                val sy = (y + dy).coerceIn(0, height - 1)
                for (dx in offsets) {
                    val sx = (x + dx).coerceIn(0, width - 1)
                    val idx = sy * width + sx
                    densitySum += weightArray[idx]
                    densityCount++
                }
            }
        }
        val localMean = if (densityCount > 0) densitySum / densityCount else 0f
    val denomBase = (maxHeat.takeIf { it > 0f } ?: 1f) * 0.12f
    var densityGain = if (localMean > 0f) localMean / (localMean + denomBase) else 0f
    // Ease curve and stronger floor to avoid vanishing in sparse areas
    densityGain = kotlin.math.sqrt(densityGain)
    val minGain = 0.45f
    densityGain = minGain + (1f - minGain) * densityGain

        for (itY in y0 until y1) {
            var heatIndex = (y + itY - halfStampHeight) * width + (x + x0) - halfStampWidth
            var stampIndex = itY * stamp.width + x0
            assertMoreOrEqual(stampIndex, 0)

            for (itX in x0 until x1) {
                var stampValue = stamp.stampData[stampIndex]
                // Light cap to prevent single-sample spikes; keeps blend smoother
                if (stampValue > 0.95f) stampValue = 0.95f

                if (stampValue > 0f) {
                    val alphaValue = alphaArray[heatIndex].toInt()
                    val weightValue = weightArray[heatIndex]
                    val valueAge = ageArray[heatIndex]
                    val alphaPercentage = alphaValue.toFloat() / 255f

                    assertWithin(alphaPercentage, 0f, 1f)

                    // Temporal decay handling with order-independence
                    val dt = ageInSeconds - valueAge
                    val tau = ageThreshold.toFloat().coerceAtLeast(1f)
                    if (dt >= 0) {
                        // Newer or same-time sample: decay existing to the new time, then add new
                        val decay = exp(-dt.toFloat() / tau)

                        // Soften previous alpha by decay and delegate new alpha computation to merge function
                        val decayedAlpha = alphaPercentage * decay
                        val decayedAlphaValue = (decayedAlpha * FULL_COMPONENT).toInt()
                        val newAlphaValue = alphaMergeFunction(decayedAlphaValue, stampValue, weight)

                        assertWithin(newAlphaValue, EMPTY_COMPONENT, FULL_COMPONENT)

                        // Decay the previous weight and then merge current contribution
                        val decayedWeight = weightValue * decay
                        // Hotspot softening: compress contributions as pixel approaches saturation
                        val saturation = (decayedWeight / (maxHeat.takeIf { it > 0f } ?: 1f)).coerceIn(0f, 1f)
                        val softness = 1f - saturation * saturation // quadratic softening for peaks
                        // Revisit gating: if a pixel is hit again sooner than revisitIntervalSec, reduce impact smoothly
                        val revisitFactor = if (revisitIntervalSec > 0) {
                            val u = (dt.toFloat() / revisitIntervalSec.toFloat()).coerceIn(0f, 1f)
                            when (revisitEasing) {
                                RevisitEasing.Smoothstep -> u * u * (3f - 2f * u)
                                RevisitEasing.Exponential -> 1f - exp(-revisitEasingStrength * u)
                                RevisitEasing.Power -> u.pow(revisitEasingStrength)
                            }
                        } else 1f
                        val merged = weightMergeFunction(decayedWeight, alphaValue, stampValue * softness * densityGain * revisitFactor, weight)
                        weightArray[heatIndex] = merged
                        lastValueArray[heatIndex] = merged - decayedWeight

                        alphaArray[heatIndex] = newAlphaValue.coerceIn(EMPTY_COMPONENT, FULL_COMPONENT).toUByte()
                        ageArray[heatIndex] = ageInSeconds
                    } else {
                        // Older sample arriving after a newer one: forward-decay the new contribution to stored time
                        val forwardDecay = exp(dt.toFloat() / tau) // dt < 0 -> factor in (0,1]

                        // Keep existing alpha; add new contribution scaled by forwardDecay
                        val newAlphaValue = alphaMergeFunction(alphaValue, stampValue * forwardDecay, weight)
                        assertWithin(newAlphaValue, EMPTY_COMPONENT, FULL_COMPONENT)

                        // Do not decay existing weight, only scale the incoming contribution to current timestamp
                        val decayedWeight = weightValue
                        val saturation = (decayedWeight / (maxHeat.takeIf { it > 0f } ?: 1f)).coerceIn(0f, 1f)
                        val softness = 1f - saturation * saturation
                        // Older sample relative to stored time: also apply revisit gating using |dt|
                        val revisitFactor = if (revisitIntervalSec > 0) {
                            val u = ((-dt).toFloat() / revisitIntervalSec.toFloat()).coerceIn(0f, 1f)
                            when (revisitEasing) {
                                RevisitEasing.Smoothstep -> u * u * (3f - 2f * u)
                                RevisitEasing.Exponential -> 1f - exp(-revisitEasingStrength * u)
                                RevisitEasing.Power -> u.pow(revisitEasingStrength)
                            }
                        } else 1f
                        val merged = weightMergeFunction(decayedWeight, alphaValue, stampValue * softness * densityGain * forwardDecay * revisitFactor, weight)
                        weightArray[heatIndex] = merged
                        lastValueArray[heatIndex] = merged - decayedWeight

                        alphaArray[heatIndex] = newAlphaValue.coerceIn(EMPTY_COMPONENT, FULL_COMPONENT).toUByte()
                        // Keep the more recent timestamp to maintain consistent reference time
                        ageArray[heatIndex] = valueAge
                    }
                }

                heatIndex++
                stampIndex++
            }
        }
    }

    private fun calculateRecencyFactor(ageInSeconds: Int, lastAgeInSeconds: Int, ageThreshold: Int): Float {
        val ageDifference = ageInSeconds - lastAgeInSeconds
        if (ageDifference < ageThreshold) {
            val a = ageThreshold.toFloat()
            val exponent = -(ageDifference / a).pow(2)
            return exp(exponent)
        }
        return 0.0f
    }

    fun renderDefaultTo(): IntArray = render(HeatmapColorScheme.default)

    /**
     * Estimate the p-th percentile of current heat values using down-sampling
     * to at most [maxSamples] items for speed. Returns 0f if no points.
     */
    fun estimatePercentile(p: Float, maxSamples: Int = 2048): Float {
        if (pointCount == 0) return 0f
        val total = width * height
        val step = (total / maxSamples).coerceAtLeast(1)
        val arr = FloatArray((total + step - 1) / step)
        var idx = 0
        var i = 0
        while (i < total) {
            val v = weightArray[i]
            if (v > 0f) {
                arr[idx++] = v
            }
            i += step
        }
        if (idx == 0) return 0f
        Arrays.sort(arr, 0, idx)
        val pos = ((idx - 1) * p.coerceIn(0f, 1f)).toInt()
        return arr[pos]
    }

    /** Fast histogram-based percentile on all pixels (optionally downsampled bins). */
    fun estimatePercentileHist(p: Float, bins: Int = 1024): Float {
        if (pointCount == 0) return 0f
        val total = width * height
        var minV = Float.POSITIVE_INFINITY
        var maxV = Float.NEGATIVE_INFINITY
        var i = 0
        while (i < total) { val v = weightArray[i]; if (v < minV) minV = v; if (v > maxV) maxV = v; i++ }
        if (!minV.isFinite() || !maxV.isFinite() || minV == maxV) return maxV
        val hist = IntArray(bins)
        val scale = (bins - 1) / (maxV - minV)
        i = 0
        while (i < total) { val v = weightArray[i]; val b = (((v - minV) * scale).toInt()).coerceIn(0, bins - 1); hist[b]++; i++ }
        val target = (p.coerceIn(0f,1f) * total).toInt()
        var cum = 0
        for (b in 0 until bins) { cum += hist[b]; if (cum >= target) return minV + b / scale }
        return maxV
    }

    /* If the heatmap is empty, h->max (and thus the saturation value) is 0.0, resulting in a 0-by-0 division.
     * In that case, we should set the saturation to anything but 0, since we want the result of the division to be 0.
     * Also, a comparison to exact 0.0f (as opposed to 1e-14) is OK, since we only do division.
     */
    /**
     * Render heatmap to IntArray with [colorScheme].
     *
     * @param colorScheme Color scheme
     */
    fun render(colorScheme: HeatmapColorScheme): IntArray {
        val saturation = if (maxHeat > 0f) maxHeat else 1.0f
        return renderSaturated(colorScheme, saturation)
    }

    /**
     * Render heatmap to IntArray with [colorScheme] and [saturation]
     */
    fun renderSaturated(
        colorScheme: HeatmapColorScheme,
        saturation: Float
    ): IntArray = renderSaturated(
        colorScheme,
        saturation
    ) { it }

    /**
     * Render heatmap to IntArray with [colorScheme], [saturation].
     * @param colorScheme Color scheme
     * @param saturation Saturation
     * @param normalizedValueModifierFunction Normalization function for values.
     */
    inline fun renderSaturated(
        colorScheme: HeatmapColorScheme,
        saturation: Float,
        normalizedValueModifierFunction: (Float) -> Float
    ): IntArray {
        assertMore(saturation, 0f)

        val buffer = IntArray(width * height)

        if (pointCount == 0) return buffer

        for (itY in 0 until height) {
            var index = itY * width

            for (itX in 0 until width) {
                val value = weightArray[index]
                val normalizedValue = normalizedValueModifierFunction(
                    min(value, saturation) / saturation
                )

                val colorId = ((colorScheme.colors.size - 1) * normalizedValue).roundToInt()

                assertMoreOrEqual(normalizedValue, 0f)
                assertLess(colorId, colorScheme.colors.size)

                val alpha = alphaArray[index].toInt().coerceIn(EMPTY_COMPONENT, FULL_COMPONENT)

                buffer[index] = colorScheme.colors[colorId].withAlpha(alpha)
                index++
            }
        }

        return buffer
    }

    /**
     * Build a normalized value buffer [0,1] from current weights using [saturation], applying [transform].
     * This mirrors the normalization in renderSaturated but returns floats for post-processing.
     */
    fun buildNormalizedBuffer(
        saturation: Float,
        transform: (Float) -> Float = { it }
    ): FloatArray {
        val out = FloatArray(width * height)
        if (pointCount == 0) return out
        val sat = if (saturation > 0f) saturation else 1f
        var index = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val v = weightArray[index]
                val n = (min(v, sat) / sat)
                out[index] = transform(n)
                index++
            }
        }
        return out
    }

    /**
     * Render a color buffer from a normalized [0,1] float buffer (same size), using internal alpha.
     */
    fun renderFromNormalized(
        colorScheme: HeatmapColorScheme,
        normalized: FloatArray,
        alphaFromNormalized: Boolean = false,
        opacity: Float = 1f
    ): IntArray {
        val buffer = IntArray(width * height)
        if (normalized.size != width * height) return buffer
        var index = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val n = normalized[index].coerceIn(0f, 1f)
                val colorId = ((colorScheme.colors.size - 1) * n).roundToInt()
                val alpha = if (alphaFromNormalized) {
                    (opacity * n * FULL_COMPONENT).roundToInt().coerceIn(EMPTY_COMPONENT, FULL_COMPONENT)
                } else {
                    alphaArray[index].toInt().coerceIn(EMPTY_COMPONENT, FULL_COMPONENT)
                }
                buffer[index] = colorScheme.colors[colorId].withAlpha(alpha)
                index++
            }
        }
        return buffer
    }

    /**
     * Fraction of pixels with non-zero weights; used to detect low-content tiles.
     */
    fun activeCoverage(epsilon: Float = 1e-6f): Float {
        if (pointCount == 0) return 0f
        val total = width * height
        var active = 0
        var i = 0
        while (i < total) {
            if (weightArray[i] > epsilon) active++
            i++
        }
        return active.toFloat() / total.toFloat()
    }
}

