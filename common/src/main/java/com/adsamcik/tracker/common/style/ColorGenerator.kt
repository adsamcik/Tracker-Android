package com.adsamcik.tracker.common.style

import android.graphics.Color
import androidx.annotation.FloatRange
import kotlin.random.Random


object ColorGenerator {
	@Suppress()
	const val GOLDEN_RATIO: Double = 1.61803398875
	private const val GOLDEN_RATIO_CONJUGATE: Double = 1.0 / GOLDEN_RATIO
	private const val CIRCLE_DEGREES = 360.0
	private const val GOLDEN_RATIO_DEGREES: Double = GOLDEN_RATIO_CONJUGATE * CIRCLE_DEGREES
	/**
	 * Generates distinct colors
	 * Based on https://martin.ankerl.com/2009/12/09/how-to-create-random-colors-programmatically/
	 */
	fun generateWithGolden(count: Int): List<Int> {
		val hue = Random.nextDouble()
		return generateWithGolden(hue, count)
	}

	/**
	 * Generates distinct colors
	 * Based on https://martin.ankerl.com/2009/12/09/how-to-create-random-colors-programmatically/
	 */
	fun generateWithGolden(
			@FloatRange(from = 0.0, to = 1.0) startHue: Double,
			count: Int
	): List<Int> {
		// FloatRange cannot check dynamic variable values
		require((startHue < 1.0) and (startHue > 0.0))

		var hue = startHue * CIRCLE_DEGREES
		@Suppress("MagicNumber")
		val hsv = floatArrayOf(0.0f, 0.984f, 0.769f)
		val colorList = ArrayList<Int>(count)
		for (i in 0 until count) {
			hsv[0] = hue.toFloat()
			colorList.add(Color.HSVToColor(hsv))
			hue = (hue + GOLDEN_RATIO_DEGREES).rem(CIRCLE_DEGREES)
		}

		return colorList
	}
}
