package com.adsamcik.signalcollector.common.style

import android.graphics.Color
import kotlin.random.Random


object ColorGenerator {
	@Suppress()
	const val GOLDEN_RATIO: Double = 1.61803398875
	private const val GOLDEN_RATIO_CONJUGATE: Double = 1.0 / GOLDEN_RATIO

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
	fun generateWithGolden(startHue: Double, count: Int): List<Int> {
		var hue = startHue.rem(1.0)
		val hsv = floatArrayOf(0.0f, 0.5f, 0.95f)
		val colorList = mutableListOf<Int>()
		for (i in 0..count) {
			hsv[0] = hue.toFloat()
			colorList.add(Color.HSVToColor(hsv))
			hue = (hue + GOLDEN_RATIO_CONJUGATE).rem(1.0)
		}

		return colorList
	}
}