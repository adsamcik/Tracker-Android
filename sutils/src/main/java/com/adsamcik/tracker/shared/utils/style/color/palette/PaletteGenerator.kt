package com.adsamcik.tracker.shared.utils.style.color.palette

import androidx.core.graphics.ColorUtils
import com.adsamcik.tracker.shared.utils.style.color.ColorDistanceCalculator
import kotlin.random.Random

/**
chroma.palette-gen.js - a palette generator for data scientists
based on Chroma.js HCL color space
Copyright (C) 2016  Mathieu Jacomy
Copyright (C) 2019 Kotlin rewrite, Adsamcik

The JavaScript code in this page is free software: you can
redistribute it and/or modify it under the terms of the GNU
General Public License (GNU GPL) as published by the Free Software
Foundation, either version 3 of the License, or (at your option)
any later version.  The code is distributed WITHOUT ANY WARRANTY;
without even the implied warranty of MERCHANTABILITY or FITNESS
FOR A PARTICULAR PURPOSE.  See the GNU GPL for more details.

As additional permission under GNU GPL version 3 section 7, you
may distribute non-source (e.g., minimized or compacted) forms of
that code without the copy of the GNU GPL normally required by
section 4, provided you include this license notice and a URL
through which recipients can access the Corresponding Source.
 */
class PaletteGenerator {
	/**
	 * Palette generator mode.
	 */
	enum class Mode {
		KMeans,
		Force
	}

	/**
	 * Generate color palette.
	 *
	 * @param colorsCount Number of colors to generate
	 * @param checkColor Color validation function
	 * @param mode Generator mode
	 * @param quality Quality
	 * @param ultraPrecision Increased precision at the cost of more computation
	 * @param distanceType How will the distance be calculated
	 * @param seed Random generator seed
	 */
	@Suppress("LongParameterList")
	fun generate(
			colorsCount: Int = 8,
			checkColor: (DoubleArray) -> Boolean = { true },
			mode: Mode = Mode.KMeans,
			quality: Int = 50,
			ultraPrecision: Boolean = false,
			distanceType: ColorDistanceCalculator.DistanceType = ColorDistanceCalculator.DistanceType.Default,
			seed: Long = Random.nextLong()
	): List<LabColor> {
		val random = Random(seed)

		return when (mode) {
			Mode.KMeans -> PaletteGeneratorKMeans().generate(
					colorsCount,
					checkColor,
					quality,
					ultraPrecision,
					random
			)
			Mode.Force -> PaletteGeneratorForce().generate(
					colorsCount,
					checkColor,
					quality,
					distanceType,
					random
			)
		}.map { LabColor(it) }
	}
}

/**
 * Data about LAB color
 */
data class LabColor(val l: Double, val a: Double, val b: Double) {

	constructor(labColor: DoubleArray) : this(labColor[0], labColor[1], labColor[2]) {
		@Suppress("MagicNumber")
		require(labColor.size == 3)
	}

	/**
	 * Convert LAB color to RGB
	 */
	fun toRgb(): Int = ColorUtils.LABToColor(l, a, b)
}
