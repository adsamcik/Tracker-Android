package com.adsamcik.signalcollector.common.misc.extension

import android.graphics.Color
import androidx.annotation.ColorInt
import androidx.core.graphics.alpha
import androidx.core.graphics.blue
import androidx.core.graphics.green
import androidx.core.graphics.red
import kotlin.math.roundToInt


/// <summary>
/// Converts degrees to radians
/// </summary>
/// <param name="deg">Degree to convert</param>
/// <returns>Degree in radians</returns>
fun Double.deg2rad(): Double = (this * kotlin.math.PI / 180.0)

/// <summary>
/// Converts radians to degrees
/// </summary>
/// <param name="rad">Radians to convert</param>
/// <returns>Radians as degrees</returns>
fun Double.rad2deg(): Double = (this / kotlin.math.PI * 180.0)

fun Double.round(decimals: Int): Double {
	var multiplier = 1.0
	repeat(decimals) { multiplier *= 10 }
	return kotlin.math.round(this * multiplier) / multiplier
}

/**
 * Rescales value in [range] to a proportionally same value between 0.0 and 1.0
 */
fun Double.normalize(range: ClosedFloatingPointRange<Double>): Double {
	return (this - range.start) / (range.endInclusive - range.start)
}

/**
 * Rescales value in [originalRange] to a proportionally same value in [newRange]
 */
fun Double.rescale(originalRange: ClosedFloatingPointRange<Double>, newRange: ClosedFloatingPointRange<Double>): Double {
	return this.normalize(originalRange).rescale(newRange)
}

/**
 * Rescales value between 0.0 and 1.0 to a proportionally same value in [newRange]
 */
fun Double.rescale(newRange: ClosedFloatingPointRange<Double>): Double {
	return this * (newRange.endInclusive - newRange.start) + newRange.start
}

fun Int.rescale(originalRange: ClosedRange<Int>, newRange: ClosedRange<Int>): Int {
	val thisDouble = this.toDouble()
	val originalRangeDouble = originalRange.start.toDouble()..originalRange.endInclusive.toDouble()
	val newRangeDouble = newRange.start.toDouble()..newRange.endInclusive.toDouble()
	val normalized = thisDouble.rescale(originalRangeDouble, newRangeDouble)
	return normalized.roundToInt()
}

/**
 * Returns additive inverse of this value
 *
 * eg. 4 in 2 to 5 => 3
 * (3 has the same distance to 5 as 4 does to 2)
 */
fun Double.additiveInverse(range: ClosedRange<Double>): Double {
	return range.endInclusive - (this - range.start)
}

fun Int.isPowerOfTwo(): Boolean = (this != 0) && ((this and (this - 1)) == 0)

object MathExtensions {
	fun lerp(fraction: Double, from: Double, to: Double): Double {
		val diff = to - from
		return from + diff * fraction
	}

	@ColorInt
	fun lerpRgb(fraction: Double, @ColorInt from: Int, @ColorInt to: Int): Int {
		var red = from.red
		var green = from.green
		var blue = from.blue

		red += ((to.red - red) * fraction).roundToInt().coerceIn(0, 255)
		green += ((to.green - green) * fraction).roundToInt().coerceIn(0, 255)
		blue += ((to.blue - blue) * fraction).roundToInt().coerceIn(0, 255)
		return Color.rgb(red, green, blue)
	}

	@ColorInt
	fun lerpArgb(fraction: Double, @ColorInt from: Int, @ColorInt to: Int): Int {
		var alpha = from.alpha
		var red = from.red
		var green = from.green
		var blue = from.blue

		red += ((to.red - red) * fraction).roundToInt().coerceIn(0, 255)
		green += ((to.green - green) * fraction).roundToInt().coerceIn(0, 255)
		blue += ((to.blue - blue) * fraction).roundToInt().coerceIn(0, 255)
		alpha += ((to.alpha - alpha) * fraction).roundToInt().coerceIn(0, 255)
		return Color.rgb(red, green, blue)
	}
}