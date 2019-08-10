package com.adsamcik.signalcollector.common.style

import android.graphics.Color
import androidx.annotation.ColorInt
import androidx.annotation.IntRange
import androidx.core.graphics.alpha
import androidx.core.graphics.blue
import androidx.core.graphics.green
import androidx.core.graphics.red
import kotlin.math.floor

/**
 * Brightens given color component with given value and ensures it is not larger than 255
 */
fun brightenComponent(component: Int, @IntRange(from = 0, to = 255) value: Int): Int = (component + value).coerceIn(0, 255)

/**
 * Brightens color by components with given value.
 */
fun brightenColor(@ColorInt color: Int, @IntRange(from = 0, to = 255) value: Int): Int {
	val r = brightenComponent(Color.red(color), value)
	val g = brightenComponent(Color.green(color), value)
	val b = brightenComponent(Color.blue(color), value)
	return Color.rgb(r, g, b)
}

/**
 * Converts RGB red (0-255) to % blue (0.0 - 1.0)
 */
fun relRed(@ColorInt color: Int): Double = Color.red(color) / 255.0

/**
 * Converts RGB green (0-255) to % blue (0.0 - 1.0)
 */
fun relGreen(@ColorInt color: Int): Double = Color.green(color) / 255.0

/**
 * Converts RGB blue (0-255) to % blue (0.0 - 1.0)
 */
fun relBlue(@ColorInt color: Int): Double = Color.blue(color) / 255.0

/**
 * Returns perceived relative luminance using an algorithm taken from formula for converting
 * RGB to YIQ
 * @see <a href="https://www.w3.org/TR/AERT/#color-contrast">https://www.w3.org/TR/AERT/#color-contrast</a>
 *
 * @param color Packed ARGB color
 * @return Double value from 0 to 1 based on perceived luminance
 */
fun perceivedLuminance(@ColorInt color: Int): Double = 0.299 * relRed(color) + 0.587 * relGreen(color) + 0.114 * relBlue(color)

/**
 * Returns perceived relative luminance using an algorithm taken from formula for converting
 * RGB to YIQ
 * @see <a href="https://www.w3.org/TR/AERT/#color-contrast">https://www.w3.org/TR/AERT/#color-contrast</a>
 *
 * @param color packed ARGB color
 * @return Value from [Byte.MIN_VALUE] to [Byte.MAX_VALUE]
 */
fun perceivedRelLuminance(@ColorInt color: Int): Int = floor((perceivedLuminance(color) - 0.5) * 255).toInt()

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
	fun getBackgroundLayerColor(@ColorInt backgroundColor: Int, @IntRange(from = -127, to = 127) luminance: Int, layerDelta: Int): Int {
		return if (layerDelta == 0) {
			backgroundColor
		} else {
			val brightenMultiplier = if (luminance <= 0) {
				LIGHTNESS_PER_LEVEL
			} else {
				-LIGHTNESS_PER_LEVEL
			}

			brightenColor(backgroundColor, brightenMultiplier * layerDelta)
		}
	}
}