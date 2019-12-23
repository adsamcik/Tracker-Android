package com.adsamcik.tracker.shared.utils.style.utility

import androidx.core.graphics.ColorUtils
import androidx.core.graphics.blue
import androidx.core.graphics.green
import androidx.core.graphics.red
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sqrt

@Suppress("MagicNumber")
class ColorDistanceCalculator {
	private val simulateCache = mutableMapOf<String, DoubleArray>()

	private val confusionLines = mapOf(
			DistanceType.CBProtanope to ConfusionData(
					x = 0.7465,
					y = 0.2535,
					m = 1.273463,
					yint = -0.073894
			),
			DistanceType.CBDeuteranope to ConfusionData(
					x = 1.4,
					y = -0.4,
					m = 0.968437,
					yint = 0.003331
			),
			DistanceType.CBTritanope to ConfusionData(
					x = 0.1748,
					y = 0.0,
					m = 0.062921,
					yint = 0.292119
			)
	)

	fun getColorDistance(
			lab1: DoubleArray,
			lab2: DoubleArray,
			type: DistanceType = DistanceType.Default
	): Double = when (type) {
		DistanceType.Default -> euclideanDistance(lab1, lab2)
		DistanceType.Euclidean -> euclideanDistance(lab1, lab2)
		DistanceType.CMC -> cmcDistance(lab1, lab2, 2.0, 1.0)
		DistanceType.Compromise -> compromiseDistance(lab1, lab2)
		else -> distanceColorblind(lab1, lab2, type)
	}

	// http://www.brucelindbloom.com/index.html?Eqn_DeltaE_CMC.html
	private fun cmcDistance(lab1: DoubleArray, lab2: DoubleArray, l: Double, c: Double): Double {
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

	private fun distanceColorblind(
			lab1: DoubleArray,
			lab2: DoubleArray,
			type: DistanceType
	): Double {
		val lab1Cb = simulate(lab1, type)
		val lab2Cb = simulate(lab2, type)
		return cmcDistance(lab1Cb, lab2Cb, 2.0, 1.0)
	}

	private fun compromiseDistance(lab1: DoubleArray, lab2: DoubleArray): Double {
		val distances = mutableListOf<Double>()
		val coefficients = mutableListOf<Int>()
		distances.add(cmcDistance(lab1, lab2, 2.0, 1.0))
		coefficients.add(1000)
		val types = arrayOf(
				DistanceType.CBProtanope,
				DistanceType.CBDeuteranope,
				DistanceType.CBTritanope
		)
		types.forEach { type ->
			val lab1Cb = simulate(lab1, type)
			val lab2Cb = simulate(lab2, type)
			if (!(lab1Cb.any { java.lang.Double.isNaN(it) } || lab2Cb.any {
						java.lang.Double.isNaN(
								it
						)
					})) {

				val c = when (type) {
					DistanceType.CBProtanope -> 100
					DistanceType.CBDeuteranope -> 500
					DistanceType.CBTritanope -> 1
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

	private fun euclideanDistance(lab1: DoubleArray, lab2: DoubleArray): Double {
		return sqrt(
				(lab1[0] - lab2[0]).pow(2) +
						(lab1[1] - lab2[1]).pow(2) +
						(lab1[2] - lab2[2]).pow(2)
		)
	}

	private fun simulate(lab: DoubleArray, type: DistanceType, amount: Int = 1): DoubleArray {
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
		val color = ColorUtils.LABToColor(lab[0], lab[1], lab[2])
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

	private data class ConfusionData(val x: Double, val y: Double, val m: Double, val yint: Double)

	enum class DistanceType {
		Default,
		Euclidean,
		CMC,
		Compromise,
		CBProtanope,
		CBDeuteranope,
		CBTritanope
	}
}
