package com.adsamcik.tracker.common.style.utility

import android.util.Log
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.blue
import androidx.core.graphics.green
import androidx.core.graphics.red
import java.lang.Double.isNaN
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sqrt
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
@Suppress("ComplexMethod", "LongMethod", "MagicNumber", "NestedBlockDepth", "LongParameterList")
class PalleteGenerator {
	data class LabColor(var l: Double, var a: Double, var b: Double) {
		fun toRgb(): Int = ColorUtils.LABToColor(l, a, b)
	}

	enum class DistanceType {
		Default,
		Euclidean,
		CMC,
		Compromise,
		CB_Protanope,
		CB_Deuteranope,
		CB_Tritanope
	}

	fun generate(
			colorsCount: Int = 8,
			checkColor: (DoubleArray) -> Boolean = { true },
			forceMode: Boolean = false,
			quality: Int = 50,
			ultraPrecision: Boolean = false,
			distanceType: DistanceType = DistanceType.Default
	): List<LabColor> {
		Log.d(
				"test",
				"Generate palettes for $colorsCount colors using color distance $distanceType"
		)

		if (forceMode) {
			// Force Vector Mode

			val colors = mutableListOf<DoubleArray>()

			// It will be necessary to check if a Lab color exists in the rgb space.
			fun checkLab(lab: DoubleArray): Boolean {
				return validateLab(lab) && checkColor(lab);
			}

			// Init
			for (i in 0 until colorsCount) {
				// Find a valid Lab color
				val color = doubleArrayOf(
						100 * Random.nextDouble(),
						100 * (2 * Random.nextDouble() - 1),
						100 * (2 * Random.nextDouble() - 1)
				)
				/*while (!checkLab(color)) {
					color = [100 * Random.nextDouble(), 100 * (2 * Random.nextDouble() - 1), 100 * (2 * Random.nextDouble() - 1)];
				}*/
				colors.add(color)
			}

			// Force vector: repulsion
			val repulsion = 100
			val speed = 100
			var steps = quality * 20
			while (steps-- > 0) {
				// Init
				val vectors = Array(colors.size) { LabColor(0.0, 0.0, 0.0) }

				// Compute Force
				for (i in 0 until colors.size) {
					val colorA = colors[i]
					for (j in 0 until i) {
						val colorB = colors[j]

						// repulsion force
						val dl = colorA[0] - colorB[0]
						val da = colorA[1] - colorB[1]
						val db = colorA[2] - colorB[2]
						val d = getColorDistance(colorA, colorB, distanceType)
						if (d > 0) {
							val force = repulsion / d.pow(2)

							vectors[i].l += dl * force / d
							vectors[i].a += da * force / d
							vectors[i].b += db * force / d

							vectors[j].l -= dl * force / d
							vectors[j].a -= da * force / d
							vectors[j].b -= db * force / d
						} else {
							// Jitter
							vectors[j].l += 2 - 4 * Random.nextDouble()
							vectors[j].a += 2 - 4 * Random.nextDouble()
							vectors[j].b += 2 - 4 * Random.nextDouble()
						}
					}
				}
				// Apply Force
				for (i in 0 until colors.size) {
					val color = colors[i]
					val displacement = speed * sqrt(
							vectors[i].l.pow(2) + vectors[i].a.pow(2) + vectors[i].b.pow(
									2
							)
					)
					if (displacement > 0) {
						val ratio = speed * min(0.1, displacement) / displacement
						val candidateLab = doubleArrayOf(
								color[0] + vectors[i].l * ratio,
								color[1] + vectors[i].a * ratio,
								color[2] + vectors[i].b * ratio
						)
						//if (checkLab(candidateLab)) {
						colors[i] = candidateLab
						//}
					}
				}
			}
			return colors.map { lab -> LabColor(lab[0], lab[1], lab[2]) }

		} else {

			// K-Means Mode
			fun checkColor2(lab: DoubleArray): Boolean {
				// Check that a color is valid: it must verify our checkColor condition, but also be in the color space
				return validateLab(lab) && checkColor(lab)
			}

			val kMeans = mutableListOf<DoubleArray>()
			for (i in 0 until colorsCount) {
				var lab = doubleArrayOf(
						100 * Random.nextDouble(),
						100 * (2 * Random.nextDouble() - 1),
						100 * (2 * Random.nextDouble() - 1)
				)
				var iterationIndex = 0
				while (!checkColor2(lab) && iterationIndex++ < 10) {
					lab = doubleArrayOf(
							100 * Random.nextDouble(),
							100 * (2 * Random.nextDouble() - 1),
							100 * (2 * Random.nextDouble() - 1)
					)
				}
				kMeans.add(lab)
			}


			val colorSamples = mutableListOf<DoubleArray>()
			val samplesClosest = mutableListOf<Int?>()

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

			for (l in 0..100 step stepL) {
				for (a in -100..100 step stepA) {
					for (b in -100..100 step stepB) {
						//if (checkColor2([l, a, b])) {
						colorSamples.add(doubleArrayOf(l.toDouble(), a.toDouble(), b.toDouble()))
						samplesClosest.add(null)
						//}
					}
				}
			}

			// Steps
			var steps = quality
			while (steps-- > 0) {
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
				var freeColorSamples = colorSamples.toList()
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
						// The candidate kMean is out of the boundaries of the color space, or unfound.
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
					freeColorSamples = freeColorSamples.filter { color ->
						color[0] != kMeans[j][0] || color[1] != kMeans[j][1] || color[2] != kMeans[j][2]
					}
				}
			}
			return kMeans.map { lab -> LabColor(lab[0], lab[1], lab[2]) }
		}
	}

	fun diffSort(inputColors: List<Int>, distanceType: DistanceType): IntArray {
		// Sort
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
					d = getColorDistance(colorA, colorB, distanceType)
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

	fun getColorDistance(
			lab1: DoubleArray,
			lab2: DoubleArray,
			type: DistanceType = DistanceType.Default
	): Double {

		// http://www.brucelindbloom.com/index.html?Eqn_DeltaE_CMC.html
		fun cmcDistance(lab1: DoubleArray, lab2: DoubleArray, l: Double, c: Double): Double {
			val l1 = lab1[0]
			val l2 = lab2[0]
			val a1 = lab1[1]
			val a2 = lab2[1]
			val b1 = lab1[2]
			val b2 = lab2[2]
			val c1 = sqrt(a1.pow(2) + b1.pow(2))
			val c2 = sqrt(a2.pow(2) + b2.pow(2))
			val deltaC = c1 - c2
			val deltaL = l1 - l2
			val deltaA = a1 - a2
			val deltaB = b1 - b2
			val deltaH = sqrt(deltaA.pow(2) + deltaB.pow(2) + deltaC.pow(2))
			var h1 = atan2(b1, a1) * (180 / PI)
			while (h1 < 0) {
				h1 += 360
			}
			val f = sqrt(c1.pow(4) / (c1.pow(4) + 1900))
			val t = if (h1 in 164.0..345.0) {
				(0.56 + abs(0.2 * cos(h1 + 168)))
			} else {
				(0.36 + abs(0.4 * cos(h1 + 35)))
			}
			val sL = if (lab1[0] < 16) {
				0.511
			} else {
				0.040975 * l1 / (1 + 0.01765 * l1)
			}
			val sC = (0.0638 * c1 / (1 + 0.0131 * c1)) + 0.638
			val sH = sC * (f * t + 1 - f)
			return sqrt(
					(deltaL / (l * sL)).pow(2.0) +
							(deltaC / (c * sC)).pow(2.0) +
							(deltaH / sH).pow(2.0)
			)
		}

		fun distanceColorblind(lab1: DoubleArray, lab2: DoubleArray, type: DistanceType): Double {
			val lab1Cb = simulate(lab1, type)
			val lab2Cb = simulate(lab2, type)
			return cmcDistance(lab1Cb, lab2Cb, 2.0, 1.0)
		}

		fun compromiseDistance(lab1: DoubleArray, lab2: DoubleArray): Double {
			val distances = mutableListOf<Double>()
			val coefficients = mutableListOf<Int>()
			distances.add(cmcDistance(lab1, lab2, 2.0, 1.0))
			coefficients.add(1000)
			val types = arrayOf(
					DistanceType.CB_Protanope,
					DistanceType.CB_Deuteranope,
					DistanceType.CB_Tritanope
			)
			types.forEach { type ->
				val lab1Cb = simulate(lab1, type)
				val lab2Cb = simulate(lab2, type)
				if (!(lab1Cb.any { isNaN(it) } || lab2Cb.any { isNaN(it) })) {

					val c = when (type) {
						DistanceType.CB_Protanope -> 100
						DistanceType.CB_Deuteranope -> 500
						DistanceType.CB_Tritanope -> 1
						else -> throw IllegalArgumentException(type.toString())
					}
					distances.add(cmcDistance(lab1Cb, lab2Cb, 2.0, 1.0))
					coefficients.add(c)
				}
			}
			var total = 0.0
			var count = 0
			distances.forEachIndexed { index, d ->
				total += coefficients[index] * d
				count += coefficients[index]
			}
			return total / count
		}

		fun euclideanDistance(lab1: DoubleArray, lab2: DoubleArray): Double {
			return sqrt(
					(lab1[0] - lab2[0]).pow(2) +
							(lab1[1] - lab2[1]).pow(2) +
							(lab1[2] - lab2[2]).pow(2)
			)
		}

		return when (type) {
			DistanceType.Default -> euclideanDistance(lab1, lab2)
			DistanceType.Euclidean -> euclideanDistance(lab1, lab2)
			DistanceType.CMC -> cmcDistance(lab1, lab2, 2.0, 1.0)
			DistanceType.Compromise -> compromiseDistance(lab1, lab2)
			else -> distanceColorblind(lab1, lab2, type)
		}
	}

	data class ConfusionData(val x: Double, val y: Double, val m: Double, val yint: Double)

	private val confusionLines = mapOf(
			DistanceType.CB_Protanope to ConfusionData(
					x = 0.7465,
					y = 0.2535,
					m = 1.273463,
					yint = -0.073894
			),
			DistanceType.CB_Deuteranope to ConfusionData(
					x = 1.4,
					y = -0.4,
					m = 0.968437,
					yint = 0.003331
			),
			DistanceType.CB_Tritanope to ConfusionData(
					x = 0.1748,
					y = 0.0,
					m = 0.062921,
					yint = 0.292119
			)
	)

	private val simulateCache = mutableMapOf<String, DoubleArray>()

	fun simulate(lab: DoubleArray, type: DistanceType, amount: Int = 1): DoubleArray {
		// WARNING: may return [NaN, NaN, NaN]

		// Cache
		val key = lab.joinToString("-") + "-" + type + "-" + amount
		val cache = simulateCache[key]
		if (cache != null) return cache

		// Get data from type
		val confuseX = confusionLines.getValue(type).x
		val confuseY = confusionLines.getValue(type).y
		val confuseM = confusionLines.getValue(type).m
		val confuseYint = confusionLines.getValue(type).yint

		// Code adapted from http://galacticmilk.com/labs/Color-Vision/Javascript/Color.Vision.Simulate.js
		val color = LabColor(lab[0], lab[1], lab[2]).toRgb()
		val sr = color.red.toDouble()
		val sg = color.green.toDouble()
		val sb = color.blue.toDouble()
		// Convert source color into XYZ color space
		val powR = sr.pow(2.2)
		val powG = sg.pow(2.2)
		val powB = sb.pow(2.2)
		val x = powR * 0.412424 + powG * 0.357579 + powB * 0.180464 // RGB->XYZ (sRGB:D65)
		val y = powR * 0.212656 + powG * 0.715158 + powB * 0.0721856
		val z = powR * 0.0193324 + powG * 0.119193 + powB * 0.950444
		// Convert XYZ into xyY Chromacity Coordinates (xy) and Luminance (Y)
		val chromaX = x / (x + y + z)
		val chromaY = y / (x + y + z)
		// Generate the "Confusion Line" between the source color and the Confusion Point
		val m = (chromaY - confuseY) / (chromaX - confuseX) // slope of Confusion Line
		val yint = chromaY - chromaX * m // y-intercept of confusion line (x-intercept = 0.0)
		// How far the xy coords deviate from the simulation
		val deviateX = (confuseYint - yint) / (m - confuseM)
		val deviateY = (m * deviateX) + yint
		// Compute the simulated color"s XYZ coords
		val dX = deviateX * y / deviateY
		val dZ = (1.0 - (deviateX + deviateY)) * y / deviateY
		// Neutral grey calculated from luminance (in D65)
		val neutralX = 0.312713 * y / 0.329016
		val neutralZ = 0.358271 * y / 0.329016
		// Difference between simulated color and neutral grey
		val diffX = neutralX - dX
		val diffZ = neutralZ - dZ
		val diffR = diffX * 3.24071 + diffZ * -0.498571 // XYZ->RGB (sRGB:D65)
		val diffG = diffX * -0.969258 + diffZ * 0.0415557
		val diffB = diffX * 0.0556352 + diffZ * 1.05707
		// Convert to RGB color space
		var dr = dX * 3.24071 + y * -1.53726 + dZ * -0.498571 // XYZ->RGB (sRGB:D65)
		var dg = dX * -0.969258 + y * 1.87599 + dZ * 0.0415557
		var db = dX * 0.0556352 + y * -0.203996 + dZ * 1.05707

		fun isPositive(value: Double) = if (value >= 0) 1.0 else 0.0

		fun keepOnlyIfInside(range: ClosedFloatingPointRange<Double>, value: Double) =
				if (value in range) value else 0.0

		// Compensate simulated color towards a neutral fit in RGB space
		val fitR = (isPositive(dr) - dr) / diffR
		val fitG = (isPositive(dg) - dg) / diffG
		val fitB = (isPositive(db) - db) / diffB
		val range = 0.0..1.0
		val adjust = maxOf( // highest value
				keepOnlyIfInside(range, fitR),
				keepOnlyIfInside(range, fitG),
				keepOnlyIfInside(range, fitB)
		)
		// Shift proportional to the greatest shift
		dr += (adjust * diffR)
		dg += (adjust * diffG)
		db += (adjust * diffB)
		// Apply gamma correction
		dr = dr.pow(1.0 / 2.2)
		dg = dg.pow(1.0 / 2.2)
		db = db.pow(1.0 / 2.2)
		// Anomylize colors
		dr = sr * (1.0 - amount) + dr * amount
		dg = sg * (1.0 - amount) + dg * amount
		db = sb * (1.0 - amount) + db * amount
		val result = DoubleArray(3)
		ColorUtils.RGBToLAB((dr * 255).toInt(), (dg * 255).toInt(), (db * 255).toInt(), result)
		simulateCache[key] = result
		return result
	}

	private fun xyzToRgb(r: Double): Double {
		val value = if (r <= 0.00304) {
			12.92 * r
		} else {
			1.055 * r.pow(1.0 / 2.4) - 0.055
		}
		return round(255.0 * value)
	}

	private fun labToXyz(t: Double): Double {
		return if (t > LabConstants.t1) t.pow(3) else (LabConstants.t2 * (t - LabConstants.t0))
	}

	fun validateLab(lab: DoubleArray): Boolean {
		// Code from Chroma.js 2016

		val l = lab[0]
		val a = lab[1]
		val b = lab[2]

		var y = (l + 16) / 116
		var x = if (isNaN(a)) y else (y + a / 500)
		var z = if (isNaN(b)) y else (y - b / 200)

		y = LabConstants.Yn * labToXyz(y)
		x = LabConstants.Xn * labToXyz(x)
		z = LabConstants.Zn * labToXyz(z)

		val red = xyzToRgb(3.2404542 * x - 1.5371385 * y - 0.4985314 * z)  // D65 -> sRGB
		val green = xyzToRgb(-0.9692660 * x + 1.8760108 * y + 0.0415560 * z)
		val blue = xyzToRgb(0.0556434 * x - 0.2040259 * y + 1.0572252 * z)

		val range = 0.0..255.0
		return range.contains(red) && range.contains(green) && range.contains(blue)
	}
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
