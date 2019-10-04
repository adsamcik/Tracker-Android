package com.adsamcik.tracker.common.extension

import com.adsamcik.tracker.common.constant.GeometryConstants.HALF_CIRCLE_IN_DEGREES
import kotlin.math.roundToInt


/// <summary>
/// Converts degrees to radians
/// </summary>
/// <param name="deg">Degree to convert</param>
/// <returns>Degree in radians</returns>
fun Double.toRadians(): Double = (this * kotlin.math.PI / HALF_CIRCLE_IN_DEGREES)

/// <summary>
/// Converts radians to degrees
/// </summary>
/// <param name="rad">Radians to convert</param>
/// <returns>Radians as degrees</returns>
fun Double.toDegrees(): Double = (this / kotlin.math.PI * HALF_CIRCLE_IN_DEGREES)

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
fun Double.rescale(
		originalRange: ClosedFloatingPointRange<Double>,
		newRange: ClosedFloatingPointRange<Double>
): Double {
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

fun LongRange.coerceIn(range: ClosedRange<Long>): LongRange {
	return start.coerceIn(range)..endInclusive.coerceIn(range)
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
}

