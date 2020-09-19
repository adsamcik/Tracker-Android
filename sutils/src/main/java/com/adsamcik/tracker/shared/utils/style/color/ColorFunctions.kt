package com.adsamcik.tracker.shared.utils.style.color

import android.graphics.Color
import androidx.annotation.ColorInt
import androidx.annotation.IntRange
import androidx.core.graphics.alpha
import androidx.core.graphics.blue
import androidx.core.graphics.green
import androidx.core.graphics.red
import com.adsamcik.tracker.shared.base.constant.LabConstants
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.roundToInt

/**
 * Brightens given color component with given value and ensures it is not larger than 255
 */
fun brightenComponent(
		component: Int,
		@IntRange(
				from = MIN_COLOR_COMPONENT_VALUE.toLong(),
				to = MAX_COLOR_COMPONENT_VALUE.toLong()
		) value: Int
): Int = (component + value).coerceIn(
		COMPONENT_MIN_COERCE,
		COMPONENT_MAX_COERCE
)

/**
 * Brightens color by components with given value.
 */
fun brightenColor(
		@ColorInt color: Int,
		@IntRange(
				from = MIN_COLOR_COMPONENT_VALUE.toLong(),
				to = MAX_COLOR_COMPONENT_VALUE.toLong()
		) value: Int
): Int {
	val r = brightenComponent(Color.red(color), value)
	val g = brightenComponent(Color.green(color), value)
	val b = brightenComponent(Color.blue(color), value)
	return Color.rgb(r, g, b)
}

/**
 * Converts RGB red (0-255) to % blue (0.0 - 1.0)
 */
fun relRed(@ColorInt color: Int): Double = Color.red(color) / MAX_COLOR_COMPONENT_VALUE

/**
 * Converts RGB green (0-255) to % blue (0.0 - 1.0)
 */
fun relGreen(@ColorInt color: Int): Double = Color.green(color) / MAX_COLOR_COMPONENT_VALUE

/**
 * Converts RGB blue (0-255) to % blue (0.0 - 1.0)
 */
fun relBlue(@ColorInt color: Int): Double = Color.blue(color) / MAX_COLOR_COMPONENT_VALUE

private const val MAX_COLOR_COMPONENT_VALUE = 255.0
private const val MIN_COLOR_COMPONENT_VALUE = 0.0

private const val COMPONENT_MAX_COERCE = MAX_COLOR_COMPONENT_VALUE.toInt()
private const val COMPONENT_MIN_COERCE = MIN_COLOR_COMPONENT_VALUE.toInt()

/**
 * Returns perceived relative luminance using an algorithm taken from formula for converting
 * RGB to YIQ
 * @see <a href="https://www.w3.org/TR/AERT/#color-contrast">https://www.w3.org/TR/AERT/#color-contrast</a>
 *
 * @param color Packed ARGB color
 * @return Double value from 0 to 1 based on perceived luminance
 */
@Suppress("MagicNumber")
fun perceivedLuminance(@ColorInt color: Int): Double = 0.299 * relRed(
		color
) + 0.587 * relGreen(
		color
) + 0.114 * relBlue(color)

/**
 * Returns perceived relative luminance using an algorithm taken from formula for converting
 * RGB to YIQ
 * @see <a href="https://www.w3.org/TR/AERT/#color-contrast">https://www.w3.org/TR/AERT/#color-contrast</a>
 *
 * @param color packed ARGB color
 * @return Value from [Byte.MIN_VALUE] to [Byte.MAX_VALUE]
 */
@Suppress("MagicNumber")
fun perceivedRelLuminance(@ColorInt color: Int): Int =
		floor((perceivedLuminance(color) - 0.5) * MAX_COLOR_COMPONENT_VALUE).toInt()

object ColorFunctions {
	const val LIGHTNESS_PER_LEVEL: Int = 17

	fun averageRgb(first: Int, second: Int): Int {
		val red = (first.red + second.red) / 2
		val green = (first.green + second.green) / 2
		val blue = (first.blue + second.blue) / 2
		return Color.rgb(red, green, blue)
	}

	fun averageRgba(first: Int, second: Int): Int {
		val red = (first.red + second.red) / 2
		val green = (first.green + second.green) / 2
		val blue = (first.blue + second.blue) / 2
		val alpha = (first.alpha + second.alpha) / 2
		return Color.argb(alpha, red, green, blue)
	}

	@ColorInt
	fun getBackgroundLayerColor(
			@ColorInt backgroundColor: Int, @IntRange(from = -127, to = 127) luminance: Int,
			layerDelta: Int
	): Int {
		return if (layerDelta == 0) {
			backgroundColor
		} else {
			val brightenMultiplier = if (luminance <= 0) {
				LIGHTNESS_PER_LEVEL
			} else {
				-LIGHTNESS_PER_LEVEL
			}

			brightenColor(
					backgroundColor,
					brightenMultiplier * layerDelta
			)
		}
	}

	@ColorInt
	fun lerpRgb(fraction: Double, @ColorInt from: Int, @ColorInt to: Int): Int {
		var red = from.red
		var green = from.green
		var blue = from.blue

		red += ((to.red - red) * fraction).roundToInt().coerceIn(
				COMPONENT_MIN_COERCE,
				COMPONENT_MAX_COERCE
		)
		green += ((to.green - green) * fraction).roundToInt().coerceIn(
				COMPONENT_MIN_COERCE,
				COMPONENT_MAX_COERCE
		)
		blue += ((to.blue - blue) * fraction).roundToInt().coerceIn(
				COMPONENT_MIN_COERCE,
				COMPONENT_MAX_COERCE
		)
		return Color.rgb(red, green, blue)
	}

	@ColorInt
	fun lerpArgb(fraction: Double, @ColorInt from: Int, @ColorInt to: Int): Int {
		var alpha = from.alpha
		var red = from.red
		var green = from.green
		var blue = from.blue

		red += ((to.red - red) * fraction).roundToInt().coerceIn(
				COMPONENT_MIN_COERCE,
				COMPONENT_MAX_COERCE
		)
		green += ((to.green - green) * fraction).roundToInt().coerceIn(
				COMPONENT_MIN_COERCE,
				COMPONENT_MAX_COERCE
		)
		blue += ((to.blue - blue) * fraction).roundToInt().coerceIn(
				COMPONENT_MIN_COERCE,
				COMPONENT_MAX_COERCE
		)
		alpha += ((to.alpha - alpha) * fraction).roundToInt().coerceIn(
				COMPONENT_MIN_COERCE,
				COMPONENT_MAX_COERCE
		)
		return Color.rgb(red, green, blue)
	}

	@Suppress("MagicNumber")
	fun validateLab(lab: DoubleArray): Boolean {
		// Code from Chroma.js 2016

		val l = lab[0]
		val a = lab[1]
		val b = lab[2]

		var y = (l + 16) / 116
		var x = if (a.isNaN()) y else (y + a / 500.0)
		var z = if (b.isNaN()) y else (y - b / 200.0)

		y = LabConstants.Yn * labToXyz(y)
		x = LabConstants.Xn * labToXyz(x)
		z = LabConstants.Zn * labToXyz(z)

		val red = xyzToRgb(3.2404542 * x - 1.5371385 * y - 0.4985314 * z)  // D65 -> sRGB
		val green = xyzToRgb(-0.9692660 * x + 1.8760108 * y + 0.0415560 * z)
		val blue = xyzToRgb(0.0556434 * x - 0.2040259 * y + 1.0572252 * z)

		val range = 0.0..255.0
		return range.contains(red) && range.contains(green) && range.contains(blue)
	}

	@Suppress("MagicNumber")
	private fun xyzToRgb(r: Double): Double {
		val value = if (r <= 0.00304) {
			12.92 * r
		} else {
			1.055 * r.pow(1.0 / 2.4) - 0.055
		}
		return round(255.0 * value)
	}

	@Suppress("MagicNumber")
	private fun labToXyz(t: Double): Double {
		return if (t > LabConstants.t1) t.pow(3) else (LabConstants.t2 * (t - LabConstants.t0))
	}

	fun distance(@ColorInt colorA: Int, @ColorInt colorB: Int): Int {
		val redDist = abs(colorB.red - colorA.red)
		val blueDist = abs(colorB.blue - colorA.blue)
		val greenDist = abs(colorB.green - colorA.green)

		return redDist + blueDist + greenDist
	}
}

