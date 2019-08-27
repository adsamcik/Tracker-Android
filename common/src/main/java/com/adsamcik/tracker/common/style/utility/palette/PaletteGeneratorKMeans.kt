package com.adsamcik.tracker.common.style.utility.palette

import androidx.core.graphics.ColorUtils
import com.adsamcik.tracker.common.extension.remove
import com.adsamcik.tracker.common.style.utility.ColorFunctions.validateLab
import kotlin.random.Random

class PaletteGeneratorKMeans {

	@Suppress("ComplexMethod", "LongMethod", "NestedBlockDepth")
	fun generate(
			colorsCount: Int,
			checkColor: (DoubleArray) -> Boolean,
			quality: Int,
			ultraPrecision: Boolean,
			random: Random
	): List<DoubleArray> {

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
		val samplesClosest = mutableListOf<Int?>()
		val tmpLabArray = DoubleArray(3)
		for (l in 0..100 step stepL) {
			for (a in -100..100 step stepA) {
				for (b in -100..100 step stepB) {
					tmpLabArray[0] = l.toDouble()
					tmpLabArray[1] = a.toDouble()
					tmpLabArray[2] = b.toDouble()
					if (checkColor2(tmpLabArray)) {
						colorSamples.add(tmpLabArray.copyOf())
						samplesClosest.add(null)
					}
				}
			}
		}

		// Steps
		repeat(quality) {
			// kMeans -> Samples Closest
			for (i in 0 until colorSamples.size) {
				val lab = colorSamples[i]
				var minDistance = Double.POSITIVE_INFINITY
				for (j in kMeans.indices) {
					val kMean = kMeans[j]
					//ns.getColorDistance(lab, kMean, distanceType) replaced with ColorUtils euclidean
					val distance = ColorUtils.distanceEuclidean(lab, kMean)
					if (distance < minDistance) {
						minDistance = distance
						samplesClosest[i] = j
					}
				}
			}

			// Samples -> kMeans
			val freeColorSamples = colorSamples.toMutableList()

			for (j in 0 until kMeans.size) {
				var count = 0
				val candidateKMean = doubleArrayOf(0.0, 0.0, 0.0)
				for (i in 0 until colorSamples.size) {
					if (samplesClosest[i] == j) {
						count++
						candidateKMean[0] += colorSamples[i][0]
						candidateKMean[1] += colorSamples[i][1]
						candidateKMean[2] += colorSamples[i][2]
					}
				}

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
							val distance = ColorUtils.distanceEuclidean(
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
							val distance = ColorUtils.distanceEuclidean(
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
}
