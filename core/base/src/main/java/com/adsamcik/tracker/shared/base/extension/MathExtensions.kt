package com.adsamcik.tracker.shared.base.extension

import com.adsamcik.tracker.shared.base.constant.GeometryConstants.HALF_CIRCLE_IN_DEGREES
import kotlin.math.roundToInt


/**
 * Converts degrees to radians.
 *
 * @return Original degree value in radians.
 */
fun Double.toRadians(): Double = (this * kotlin.math.PI / HALF_CIRCLE_IN_DEGREES)

/**
 * Converts radians to degrees.
 *
 * @return Original radian value in degrees.
 */
fun Double.toDegrees(): Double = (this / kotlin.math.PI * HALF_CIRCLE_IN_DEGREES)

/**
 * Round double to a specified number of decimals.
 *
 * @param decimals Number of decimals
 */
@Suppress("MagicNumber")
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

/**
 * Rescale integer from original range to new range.
 */
fun Int.rescale(originalRange: ClosedRange<Int>, newRange: ClosedRange<Int>): Int {
	val thisDouble = this.toDouble()
	val originalRangeDouble = originalRange.start.toDouble()..originalRange.endInclusive.toDouble()
	val newRangeDouble = newRange.start.toDouble()..newRange.endInclusive.toDouble()
	val normalized = thisDouble.rescale(originalRangeDouble, newRangeDouble)
	return normalized.roundToInt()
}

/**
 * Coerce LongRange within a ClosedRange<Long>.
 *
 * @return New LongRange instance.
 */
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

/**
 * Checks if integer is a power of two
 *
 * @return True if integer is power of two
 */
fun Int.isPowerOfTwo(): Boolean = (this != 0) && ((this and (this - 1)) == 0)

/**
 * Math extensions
 */
object MathExtensions {
	/**
	 * Lerp (blend) values [from] to [to] based on fraction.
	 * Result is unconstrained and can be outside of bounds.
	 */
	fun lerp(fraction: Double, from: Double, to: Double): Double {
		val diff = to - from
		return from + diff * fraction
	}
}

