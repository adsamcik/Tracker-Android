package com.adsamcik.tracker.shared.utils.style.color.palette

import com.adsamcik.tracker.shared.utils.style.color.ColorDistanceCalculator
import com.adsamcik.tracker.shared.utils.style.color.ColorFunctions
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Palette generator using force
 */
@Suppress("MagicNumber")
internal class PaletteGeneratorForce {
	private val distanceCalculator = ColorDistanceCalculator()

	/**
	 * Generate color palette.
	 *
	 * @param colorsCount Number of colors to generate
	 * @param checkColor Color validation function
	 * @param quality Quality
	 * @param distanceType How will the distance be calculated
	 * @param random Random instance
	 */
	fun generate(
			colorsCount: Int = 8,
			checkColor: (DoubleArray) -> Boolean,
			quality: Int,
			distanceType: ColorDistanceCalculator.DistanceType,
			random: Random
	): List<DoubleArray> {
		val colors = mutableListOf<DoubleArray>()

		// It will be necessary to check if a Lab color exists in the rgb space.
		fun checkLab(lab: DoubleArray): Boolean {
			return ColorFunctions.validateLab(lab) && checkColor(lab)
		}

		// Init
		for (i in 0 until colorsCount) {
			// Find a valid Lab color
			val color = doubleArrayOf(
					100 * random.nextDouble(),
					100 * (2 * random.nextDouble() - 1),
					100 * (2 * random.nextDouble() - 1)
			)
			while (!checkLab(color)) {
				color[0] = 100.0 * random.nextDouble()
				color[1] = 100.0 * (2.0 * random.nextDouble() - 1.0)
				color[2] = 100.0 * (2.0 * random.nextDouble() - 1.0)
			}
			colors.add(color)
		}

		// Force vector: repulsion
		val repulsion = 100
		val speed = 100
		var steps = quality * 20
		while (steps-- > 0) {
			// Init
			val vectors = Array(colors.size) { DoubleArray(3) }

			// Compute Force
			for (i in 0 until colors.size) {
				val colorA = colors[i]
				for (j in 0 until i) {
					val colorB = colors[j]

					// repulsion force
					val dl = colorA[0] - colorB[0]
					val da = colorA[1] - colorB[1]
					val db = colorA[2] - colorB[2]
					val d = distanceCalculator.getColorDistance(colorA, colorB, distanceType)
					if (d > 0) {
						val force = repulsion / d.pow(2)

						vectors[i][0] += dl * force / d
						vectors[i][1] += da * force / d
						vectors[i][2] += db * force / d

						vectors[j][0] -= dl * force / d
						vectors[j][1] -= da * force / d
						vectors[j][2] -= db * force / d
					} else {
						// Jitter
						vectors[j][0] += 2 - 4 * random.nextDouble()
						vectors[j][1] += 2 - 4 * random.nextDouble()
						vectors[j][2] += 2 - 4 * random.nextDouble()
					}
				}
			}
			// Apply Force
			for (i in 0 until colors.size) {
				val color = colors[i]
				val displacement =
						speed * sqrt(
								vectors[i][0].pow(2) +
										vectors[i][1].pow(2) +
										vectors[i][2].pow(2)
						)
				if (displacement > 0) {
					val ratio = speed * min(0.1, displacement) / displacement
					val candidateLab = doubleArrayOf(
							color[0] + vectors[i][0] * ratio,
							color[1] + vectors[i][1] * ratio,
							color[2] + vectors[i][2] * ratio
					)
					if (checkLab(candidateLab)) {
						colors[i] = candidateLab
					}
				}
			}
		}
		return colors
	}
}
