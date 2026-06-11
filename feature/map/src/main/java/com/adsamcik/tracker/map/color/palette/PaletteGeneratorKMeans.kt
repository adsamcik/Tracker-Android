package com.adsamcik.tracker.map.color.palette

import com.adsamcik.tracker.shared.base.extension.remove
import com.adsamcik.tracker.map.color.ColorFunctions.validateLab
import kotlin.random.Random

/**
 * Palette generator that uses K-Means.
 */
internal class PaletteGeneratorKMeans {

	/**
	 * Generate color palette.
	 *
	 * @param colorsCount Number of colors to generate
	 * @param checkColor Color validation function
	 * @param quality Quality
	 * @param ultraPrecision Increased precision at the cost of more computation
	 * @param random Random instance
	 */
	@Suppress("MagicNumber")
	fun generate(
			colorsCount: Int,
			checkColor: (DoubleArray) -> Boolean,
			quality: Int,
			ultraPrecision: Boolean,
			random: Random
	): List<DoubleArray> {

		if (colorsCount <= 0) return emptyList()

		// K-Means Mode
		fun checkColor2(lab: DoubleArray): Boolean {
			// Check that a color is valid: it must verify our checkColor condition, but also be in the color space
			return validateLab(lab) && checkColor(lab)
		}

		val kMeans = mutableListOf<DoubleArray>()

		repeat(colorsCount) {
			var lab = doubleArrayOf(
					100 * random.nextDouble(),
					100 * (2 * random.nextDouble() - 1),
					100 * (2 * random.nextDouble() - 1)
			)
			var iterationIndex = 0
			while (!checkColor2(lab) && iterationIndex++ < 10) {
				lab = doubleArrayOf(
						100 * random.nextDouble(),
						100 * (2 * random.nextDouble() - 1),
						100 * (2 * random.nextDouble() - 1)
				)
			}
			kMeans.add(lab)
		}

		val stepL: Int
		val stepA: Int
		val stepB: Int

		if (ultraPrecision) {
			stepL = 1
			stepA = 5
			stepB = 5
		} else {
			stepL = 5
			stepA = 10
			stepB = 10
		}


		val colorSamples = mutableListOf<DoubleArray>()
		val tmpLabArray = DoubleArray(3)
		for (l in 0..100 step stepL) {
			for (a in -100..100 step stepA) {
				for (b in -100..100 step stepB) {
					tmpLabArray[0] = l.toDouble()
					tmpLabArray[1] = a.toDouble()
					tmpLabArray[2] = b.toDouble()
					if (checkColor2(tmpLabArray)) {
						colorSamples.add(tmpLabArray.copyOf())
					}
				}
			}
		}

		val centroidL = DoubleArray(colorsCount)
		val centroidA = DoubleArray(colorsCount)
		val centroidB = DoubleArray(colorsCount)
		val centroidCounts = IntArray(colorsCount)

		// Steps
		repeat(quality) {
			centroidL.fill(0.0)
			centroidA.fill(0.0)
			centroidB.fill(0.0)
			centroidCounts.fill(0)

			// Assign samples and accumulate centroids in one pass.
			for (i in 0 until colorSamples.size) {
				val lab = colorSamples[i]
				var minDistance = Double.POSITIVE_INFINITY
				var closest = 0
				for (j in kMeans.indices) {
					val kMean = kMeans[j]
					val distance = distanceEuclideanSquared(lab, kMean)
					if (distance < minDistance) {
						minDistance = distance
						closest = j
					}
				}
				centroidL[closest] += lab[0]
				centroidA[closest] += lab[1]
				centroidB[closest] += lab[2]
				centroidCounts[closest]++
			}

			// Samples -> kMeans
			val freeColorSamples = colorSamples.toMutableList()

			for (j in 0 until kMeans.size) {
				val count = centroidCounts[j]
				val candidateKMean = doubleArrayOf(centroidL[j], centroidA[j], centroidB[j])
				if (count != 0) {
					//for some reason /= doesn't work
					candidateKMean[0] = candidateKMean[0] / count
					candidateKMean[1] = candidateKMean[1] / count
					candidateKMean[2] = candidateKMean[2] / count
				}
//&& checkColor2([candidateKMean[0], candidateKMean[1], candidateKMean[2]]) && candidateKMean
				if (count != 0) {
					kMeans[j] = candidateKMean
				} else {
					// The candidate kMean is out of the boundaries of the color space, or not found.
					if (freeColorSamples.isNotEmpty()) {
						// We just search for the closest FREE color of the candidate kMean
						var minDistance = Double.POSITIVE_INFINITY
						var closest = -1
						for (i in freeColorSamples.indices) {

							//ns.getColorDistance(freeColorSamples[i],candidateKMean,distanceType );
							val distance = distanceEuclideanSquared(
									freeColorSamples[i],
									candidateKMean
							)
							if (distance < minDistance) {
								minDistance = distance
								closest = i
							}
						}
						if (closest >= 0) {
							kMeans[j] = colorSamples[closest]
						}

					} else {
						// Then we just search for the closest color of the candidate kMean
						var minDistance = Double.POSITIVE_INFINITY
						var closest = -1
						for (i in 0 until colorSamples.size) {
							//ns.getColorDistance(colorSamples[i],candidateKMean,distanceType)
							val distance = distanceEuclideanSquared(
									colorSamples[i],
									candidateKMean
							)
							if (distance < minDistance) {
								minDistance = distance
								closest = i
							}
						}
						if (closest >= 0) {
							kMeans[j] = colorSamples[closest]
						}
					}
				}

				val lab = kMeans[j]

				freeColorSamples.remove { it.contentEquals(lab) }
			}
		}
		return kMeans
	}

	private fun distanceEuclideanSquared(first: DoubleArray, second: DoubleArray): Double {
		val l = first[0] - second[0]
		val a = first[1] - second[1]
		val b = first[2] - second[2]
		return l * l + a * a + b * b
	}
}
