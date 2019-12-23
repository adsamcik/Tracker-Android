package com.adsamcik.tracker.shared.utils.style.utility.palette

import androidx.core.graphics.ColorUtils
import com.adsamcik.tracker.shared.utils.style.utility.ColorDistanceCalculator
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
	enum class Mode {
		KMeans,
		Force
	}

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

	fun diffSort(
			inputColors: List<Int>,
			distanceType: ColorDistanceCalculator.DistanceType
	): IntArray {
		// Sort
		val distanceCalculator = ColorDistanceCalculator()
		val colorsToSort = inputColors.toMutableList()
		val diffColors = mutableListOf(colorsToSort.removeAt(0))
		while (colorsToSort.isNotEmpty()) {
			var index = -1
			var maxDistance = -1.0
			for (candidate_index in 0 until colorsToSort.size) {
				var d = Double.POSITIVE_INFINITY
				for (i in diffColors.size - 1 downTo 0) {
					val colorA = DoubleArray(3)
					ColorUtils.colorToLAB(colorsToSort[candidate_index], colorA)

					val colorB = DoubleArray(3)
					ColorUtils.colorToLAB(colorsToSort[i], colorA)
					//I seriously have no idea what was this supposed to do
					//it was written like going through the array but throwing away everything but
					//last iteration
					d = distanceCalculator.getColorDistance(colorA, colorB, distanceType)
					if (d < Double.POSITIVE_INFINITY) break
				}
				if (d > maxDistance) {
					maxDistance = d
					index = candidate_index
				}
			}
			val color = colorsToSort[index]
			diffColors.add(color)

			colorsToSort.removeAt(index)
			//colorsToSort = colorsToSort.filter(fun(c, i) { return i != index; });
		}
		return diffColors.toIntArray()
	}
}

data class LabColor(var l: Double, var a: Double, var b: Double) {

	constructor(labColor: DoubleArray) : this(labColor[0], labColor[1], labColor[2]) {
		@Suppress("MagicNumber")
		require(labColor.size == 3)
	}

	fun toRgb(): Int = ColorUtils.LABToColor(l, a, b)
}

object LabConstants {
	// Corresponds roughly to RGB brighter/darker
	const val Kn = 18

	// D65 standard referent
	const val Xn = 0.950470
	const val Yn = 1.0
	const val Zn = 1.088830

	const val t0 = 0.137931034  // 4 / 29
	const val t1 = 0.206896552  // 6 / 29
	const val t2 = 0.12841855   // 3 * t1 * t1
	const val t3 = 0.008856452   // t1 * t1 * t1
}
