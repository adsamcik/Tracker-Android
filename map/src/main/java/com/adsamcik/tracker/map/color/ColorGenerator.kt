package com.adsamcik.tracker.map.color
import androidx.annotation.FloatRange
import com.adsamcik.tracker.map.color.palette.PaletteGenerator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Generates random colors with various methods.
 */
object ColorGenerator {
	private const val GOLDEN_RATIO: Double = 1.61803398875
	private const val THRESHOLD = 30.0
	private const val GOLDEN_RATIO_CONJUGATE: Double = 1.0 / GOLDEN_RATIO
	private const val CIRCLE_DEGREES = 360.0
	private const val GOLDEN_RATIO_DEGREES: Double = GOLDEN_RATIO_CONJUGATE * CIRCLE_DEGREES

	/**
	 * Generates distinct colors
	 * Based on https://martin.ankerl.com/2009/12/09/how-to-create-random-colors-programmatically/
	 */
	fun generateWithGolden(count: Int): List<Int> {
		val hue = Random.nextDouble()
		return generateWithGolden(
				hue,
				count
		)
	}

	/**
	 * Generates color palette.
	 */
	fun generatePalette(count: Int): List<Int> {
		return PaletteGenerator().generate(
				colorsCount = count,
				quality = 100,
				mode = PaletteGenerator.Mode.KMeans,
				distanceType = ColorDistanceCalculator.DistanceType.CMC
		).map { it.toRgb() }
	}

	/**
	 * Generates distinct colors
	 * Based on https://martin.ankerl.com/2009/12/09/how-to-create-random-colors-programmatically/
	 */
	fun generateWithGolden(
		@FloatRange(from = 0.0, to = 1.0) startHue: Double,
		count: Int,
		minSaturation: Float = 0.5f, // Minimum saturation value
		maxSaturation: Float = 1.0f, // Maximum saturation value
		saturationStep: Float = 0.1f // Step for varying the saturation
	): List<Int> {
		require((startHue < 1.0) and (startHue > 0.0))

		val baseHue = startHue * CIRCLE_DEGREES
		return List(count) { index ->
			val hue = ((baseHue + (index * GOLDEN_RATIO_DEGREES)).rem(CIRCLE_DEGREES)).toFloat()
			val saturation = (minSaturation + (index * saturationStep)).coerceIn(minSaturation, maxSaturation)
			hsvToColor(hue, saturation, 0.769f)
		}
	}

	private fun isTooSimilar(color1: Int, color2: Int, threshold: Int = 32): Boolean {
		val r1 = red(color1)
		val g1 = green(color1)
		val b1 = blue(color1)

		val r2 = red(color2)
		val g2 = green(color2)
		val b2 = blue(color2)

		val distance = sqrt(((r2 - r1).toFloat().pow(2) + (g2 - g1).toFloat().pow(2) + (b2 - b1).toFloat().pow(2)))
		return distance < threshold
	}

	fun generateDistinctColors(count: Int, startingHue: Float): List<Int> {
		if (count <= 0) return emptyList()

		val normalizedStart = ((startingHue % CIRCLE_DEGREES.toFloat()) + CIRCLE_DEGREES.toFloat()) % CIRCLE_DEGREES.toFloat()
		val phase = startingHue.toRawBits().ushr(1)

		return List(count) { index ->
			var hue = (normalizedStart + (index * GOLDEN_RATIO_DEGREES.toFloat())) % CIRCLE_DEGREES.toFloat()
			if (hue in 60.0f..160.0f) {
				hue = (hue + 120.0f) % CIRCLE_DEGREES.toFloat()
			}

			val saturation = 0.65f + (((phase + index) % 3) * 0.1f)
			val brightness = 0.8f + (((phase + index) % 2) * 0.1f)
			hsvToColor(
				hue,
				saturation.coerceAtMost(1.0f),
				brightness.coerceAtMost(1.0f)
			)
		}
	}

	private fun isTooSimilarCiede(color1: Int, color2: Int): Boolean {
		val lab1 = rgbToLab(color1)
		val lab2 = rgbToLab(color2)

		val deltaE = ciede2000(lab1, lab2)
		return deltaE < THRESHOLD
	}

	fun rgbToLab(color: Int): FloatArray {
		val r = pivotRgbChannel(red(color) / 255.0)
		val g = pivotRgbChannel(green(color) / 255.0)
		val b = pivotRgbChannel(blue(color) / 255.0)

		// Convert RGB to XYZ
		val x = (0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / 0.95047
		val y = (0.2126729 * r + 0.7151522 * g + 0.0721750 * b) / 1.00000
		val z = (0.0193339 * r + 0.1191920 * g + 0.9503041 * b) / 1.08883

		// Convert XYZ to LAB
		val xRoot = if (x > 0.008856) cbrt(x) else (903.3 * x + 16) / 116
		val yRoot = if (y > 0.008856) cbrt(y) else (903.3 * y + 16) / 116
		val zRoot = if (z > 0.008856) cbrt(z) else (903.3 * z + 16) / 116

		val l = (116 * yRoot) - 16
		val a = 500 * (xRoot - yRoot)
		val bLab = 200 * (yRoot - zRoot)

		return floatArrayOf(l.toFloat(), a.toFloat(), bLab.toFloat())
	}

	private fun pivotRgbChannel(channel: Double): Double {
		return if (channel <= 0.04045) {
			channel / 12.92
		} else {
			((channel + 0.055) / 1.055).pow(2.4)
		}
	}

	private fun hsvToColor(hue: Float, saturation: Float, value: Float): Int {
		val normalizedHue = ((hue % 360f) + 360f) % 360f
		val chroma = value * saturation
		val segment = normalizedHue / 60f
		val secondary = chroma * (1f - abs((segment % 2f) - 1f))
		val match = value - chroma

		val (rPrime, gPrime, bPrime) = when {
			segment < 1f -> Triple(chroma, secondary, 0f)
			segment < 2f -> Triple(secondary, chroma, 0f)
			segment < 3f -> Triple(0f, chroma, secondary)
			segment < 4f -> Triple(0f, secondary, chroma)
			segment < 5f -> Triple(secondary, 0f, chroma)
			else -> Triple(chroma, 0f, secondary)
		}

		return argb(
			alpha = 255,
			red = ((rPrime + match) * 255f).toInt().coerceIn(0, 255),
			green = ((gPrime + match) * 255f).toInt().coerceIn(0, 255),
			blue = ((bPrime + match) * 255f).toInt().coerceIn(0, 255)
		)
	}

	private fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int {
		return ((alpha and 0xFF) shl 24) or
			((red and 0xFF) shl 16) or
			((green and 0xFF) shl 8) or
			(blue and 0xFF)
	}

	private fun red(color: Int): Int = (color shr 16) and 0xFF

	private fun green(color: Int): Int = (color shr 8) and 0xFF

	private fun blue(color: Int): Int = color and 0xFF

	private fun ciede2000(lab1: FloatArray, lab2: FloatArray): Double {
		val L1 = lab1[0].toDouble()
		val a1 = lab1[1].toDouble()
		val b1 = lab1[2].toDouble()

		val L2 = lab2[0].toDouble()
		val a2 = lab2[1].toDouble()
		val b2 = lab2[2].toDouble()

		val C1 = sqrt(a1 * a1 + b1 * b1)
		val C2 = sqrt(a2 * a2 + b2 * b2)
		val barC = (C1 + C2) / 2.0
		val G = 0.5 * (1.0 - sqrt(barC.pow(7.0) / (barC.pow(7.0) + 25.0.pow(7.0))))

		val a1Prime = (1.0 + G) * a1
		val a2Prime = (1.0 + G) * a2

		val C1Prime = sqrt(a1Prime * a1Prime + b1 * b1)
		val C2Prime = sqrt(a2Prime * a2Prime + b2 * b2)

		val h1Prime = if (b1 == 0.0 && a1Prime == 0.0) 0.0 else atan2(b1, a1Prime).let { rad -> if (rad >= 0) rad else rad + 2 * PI }
		val h2Prime = if (b2 == 0.0 && a2Prime == 0.0) 0.0 else atan2(b2, a2Prime).let { rad -> if (rad >= 0) rad else rad + 2 * PI }

		val deltaLPrime = L2 - L1
		val deltaCPrime = C2Prime - C1Prime
		val deltahPrime = when {
			C1Prime * C2Prime == 0.0 -> 0.0
			abs(h2Prime - h1Prime) <= PI -> h2Prime - h1Prime
			h2Prime - h1Prime > PI -> h2Prime - h1Prime - 2 * PI
			else -> h2Prime - h1Prime + 2 * PI
		}

		val deltaHPrime = 2.0 * sqrt(C1Prime * C2Prime) * sin(deltahPrime / 2.0)
		val barL = (L1 + L2) / 2.0
		val barCPrime = (C1Prime + C2Prime) / 2.0
		val barhPrime = when {
			C1Prime * C2Prime == 0.0 -> h1Prime + h2Prime
			abs(h1Prime - h2Prime) <= PI -> (h1Prime + h2Prime) / 2.0
			h1Prime + h2Prime < 2 * PI -> (h1Prime + h2Prime + 2 * PI) / 2.0
			else -> (h1Prime + h2Prime - 2 * PI) / 2.0
		}

		val T = 1.0 - 0.17 * cos(barhPrime - PI / 6.0) + 0.24 * cos(2 * barhPrime) + 0.32 * cos(3 * barhPrime + PI / 30.0) - 0.20 * cos(4 * barhPrime - 63 * PI / 180.0)

		val SL = 1.0 + (0.015 * (barL - 50).pow(2.0)) / sqrt(20 + (barL - 50).pow(2.0))
		val SC = 1.0 + 0.045 * barCPrime
		val SH = 1.0 + 0.015 * barCPrime * T

		val RT = -2.0 * sqrt(barCPrime.pow(7.0) / (barCPrime.pow(7.0) + 25.0.pow(7.0))) * sin(60 * exp(-((barhPrime - 275) / 25).pow(2.0)))

		return sqrt(
			(deltaLPrime / (SL * 1)).pow(2.0) +
					(deltaCPrime / (SC * 1)).pow(2.0) +
					(deltaHPrime / (SH * 1)).pow(2.0) +
					RT * (deltaCPrime / (SC * 1)) * (deltaHPrime / (SH * 1))
		)
	}
}
