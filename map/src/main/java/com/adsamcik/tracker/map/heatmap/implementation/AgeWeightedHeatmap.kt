package com.adsamcik.tracker.map.heatmap.implementation

import androidx.core.graphics.ColorUtils
import com.adsamcik.tracker.map.heatmap.HeatmapColorScheme
import com.adsamcik.tracker.map.heatmap.HeatmapStamp
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.utils.debug.assertLess
import com.adsamcik.tracker.shared.utils.debug.assertLessOrEqual
import com.adsamcik.tracker.shared.utils.debug.assertMore
import com.adsamcik.tracker.shared.utils.debug.assertMoreOrEqual
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

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

internal class AgeWeightedHeatmap(
		val width: Int,
		val height: Int = width,
		val ageThreshold: Int = Time.MINUTE_IN_SECONDS.toInt(),
		var maxHeat: Float = 0f,
		var dynamicHeat: Boolean = true
) {
	private val alphaArray: UByteArray = UByteArray(width * height)
	private val weightArray: FloatArray = FloatArray(width * height)
	private val ageArray: IntArray = IntArray(width * height) { -ageThreshold }

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
		val x0 = if (x < halfStampWidth) stamp.width / 2 - x else 0
		val y0 = if (y < halfStampHeight) stamp.height / 2 - y else 0
		val x1 = if (x + halfStampWidth < width) stamp.width else halfStampWidth + width - x
		val y1 = if (y + halfStampHeight < height) stamp.height else halfStampHeight + height - y

		val ageThresholdFloat = ageThreshold.toFloat()

		for (itY in y0 until y1) {
			var heatIndex = (y + itY - halfStampHeight) * width + (x + x0) - halfStampWidth
			var stampIndex = itY * stamp.width + x0
			assertMoreOrEqual(stampIndex, 0)

			for (itX in x0 until x1) {
				val stampValue = stamp.stampData[stampIndex]

				if (stampValue == 0f) continue

				val alphaValue = alphaArray[heatIndex].toInt()
				val weightValue = weightArray[heatIndex]
				val valueAge = ageArray[heatIndex]
				val agePercentage =
						(ageInSeconds - valueAge).toFloat()
								.coerceAtMost(ageThresholdFloat) / ageThresholdFloat
				val agedStamp = stampValue * agePercentage

				val newWeightValue = weightValue + agedStamp
				assertMoreOrEqual(newWeightValue, 0f)
				assertMoreOrEqual(agePercentage, 0f)

				weightArray[heatIndex] = newWeightValue

				if (dynamicHeat && newWeightValue > maxHeat) {
					maxHeat = newWeightValue
				}
				/*if (ageInSeconds - ageThreshold > ageArray[heatIndex]) {
					/*newWeightValue = weightMergeFunction(
							weightArray[heatIndex],
							alphaValue,
							stampValue,
							weight
					)*/


				} else {
					newWeightValue = weightArray[heatIndex]
				}*/

				val newAlphaValue = max(alphaValue, (agedStamp * 255f).toInt())

				assertMoreOrEqual(newAlphaValue, 0)
				assertLessOrEqual(newAlphaValue, 255)

				alphaArray[heatIndex] = newAlphaValue.toUByte()
				ageArray[heatIndex] = ageInSeconds

				heatIndex++
				stampIndex++
			}
		}
	}

	fun renderDefaultTo(): IntArray = renderTo(HeatmapColorScheme.default)

	/* If the heatmap is empty, h->max (and thus the saturation value) is 0.0, resulting in a 0-by-0 division.
	 * In that case, we should set the saturation to anything but 0, since we want the result of the division to be 0.
	 * Also, a comparison to exact 0.0f (as opposed to 1e-14) is OK, since we only do division.
	 */
	fun renderTo(colorScheme: HeatmapColorScheme): IntArray {
		val saturation = if (maxHeat > 0f) maxHeat else 1.0f
		return renderSaturatedTo(colorScheme, saturation)
	}

	fun renderSaturatedTo(
			colorScheme: HeatmapColorScheme,
			saturation: Float
	): IntArray = renderSaturatedTo(
			colorScheme,
			saturation
	) { it }

	inline fun renderSaturatedTo(
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

				buffer[index] = ColorUtils.setAlphaComponent(
						colorScheme.colors[colorId],
						alphaArray[index].toInt()
				)
				index++
			}
		}

		return buffer
	}
}

