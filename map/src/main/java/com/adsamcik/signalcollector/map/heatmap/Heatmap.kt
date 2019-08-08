package com.adsamcik.signalcollector.map.heatmap

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

internal class Heatmap(val width: Int, val height: Int = width, var maxHeat: Float = 0f, var dynamicHeat: Boolean = true) {
	var pointCount: Int = 0

	val data: FloatArray = FloatArray(width * height)

	fun addPoint(x: Int, y: Int) = addPointWithStamp(x, y, HeatmapStamp.default9x9)

	fun addWeightedPoint(x: Int, y: Int, weight: Float) = addWeightedPointWithStamp(x, y, weight, HeatmapStamp.default9x9)

	fun addPointWithStamp(x: Int, y: Int, stamp: HeatmapStamp) =
			addWeightedPointWithStamp(x, y, stamp, 1f) { original, input, _ ->
				original + input
			}

	fun addWeightedPointWithStamp(x: Int, y: Int, weightValue: Float, stamp: HeatmapStamp) =
			addWeightedPointWithStamp(x, y, stamp, weightValue) { original, input, weight ->
				original + input * weight
			}

	fun addWeightedPointWithStamp(x: Int, y: Int, stamp: HeatmapStamp, weight: Float, mergeFunction: (current: Float, input: Float, weight: Float) -> Float) {
		//todo validate that odd numbers don't cause some weird artifacts
		val halfStampHeight = stamp.height / 2
		val halfStampWidth = stamp.width / 2

		assert(x - halfStampWidth / 2 < width)
		assert(y - halfStampHeight < height)
		assert(x - halfStampHeight >= 0)
		assert(y + halfStampWidth / 2 >= 0)

		pointCount++

		/* Note: the order of operations is important, since we're computing with unsigned! */

		/* These are [first, last) pairs in the STAMP's pixels. */
		val x0 = if (x < halfStampWidth) stamp.width / 2 - x else 0
		val y0 = if (y < halfStampHeight) stamp.height / 2 - y else 0
		val x1 = if (x + halfStampWidth < width) stamp.width else halfStampWidth + width - x
		val y1 = if (y + halfStampHeight < height) stamp.height else halfStampHeight + height - y

		for (itY in y0 until y1) {
			var heatIndex = (y + itY - halfStampHeight) * width + (x + x0) - halfStampWidth
			var stampIndex = itY * stamp.width + x0
			assert(stampIndex >= 0f)

			for (itX in x0 until x1) {
				val heatValue = data[heatIndex]
				val newHeatValue = mergeFunction(heatValue, stamp.stampData[stampIndex], weight)
				data[heatIndex] = newHeatValue
				if (dynamicHeat && newHeatValue > maxHeat) {
					maxHeat = newHeatValue
				}

				assert(heatValue >= 0f)

				heatIndex++
				stampIndex++
			}
		}
	}

	fun renderDefaultTo(): IntArray = renderTo(HeatmapColorScheme.default)

	/* TODO: Time whether it makes a noticeable difference to inline that code
     * here and drop the saturation step.
     */
	/* If the heatmap is empty, h->max (and thus the saturation value) is 0.0, resulting in a 0-by-0 division.
	 * In that case, we should set the saturation to anything but 0, since we want the result of the division to be 0.
	 * Also, a comparison to exact 0.0f (as opposed to 1e-14) is OK, since we only do division.
	 */
	fun renderTo(colorScheme: HeatmapColorScheme): IntArray {
		val saturation = if (maxHeat > 0f) maxHeat else 1.0f
		return renderSaturatedTo(colorScheme, saturation)
	}

	fun renderSaturatedTo(colorScheme: HeatmapColorScheme, saturation: Float): IntArray = renderSaturatedTo(colorScheme, saturation) { it }

	inline fun renderSaturatedTo(colorScheme: HeatmapColorScheme, saturation: Float, normalizedValueModifierFunction: (Float) -> Float): IntArray {
		assert(saturation > 0f)

		val buffer = IntArray(width * height)

		if (pointCount == 0) return buffer

		for (itY in 0 until height) {
			var index = itY * width

			for (itX in 0 until width) {
				val value = data[index]
				val normalizedValue = normalizedValueModifierFunction(min(value, saturation) / saturation)

				val colorId = ((colorScheme.colors.size - 1) * normalizedValue).roundToInt()

				assert(normalizedValue >= 0)
				assert(colorId < colorScheme.colors.size)

				buffer[index] = colorScheme.colors[colorId]
				index++
			}
		}

		return buffer
	}
}