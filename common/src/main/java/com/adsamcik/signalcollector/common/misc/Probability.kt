package com.adsamcik.signalcollector.common.misc

import com.adsamcik.signalcollector.common.Time
import com.adsamcik.signalcollector.common.extension.toIntArray
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

@Suppress("Unused", "Private")
object Probability {
	private val random: Random = Random(Time.nowMillis)

	/**
	 * Returns random number with uniform distribution
	 */
	fun uniform(from: Int, until: Int): Int = random.nextInt(from, until)

	/**
	 * Returns random number with uniform distribution
	 */
	fun uniform(from: Double, until: Double): Double = random.nextDouble(from, until)

	/**
	 * Returns random value between 0 (inclusive) and 1 (exclusive)
	 */
	@Suppress("Private")
	fun uniform(): Double = random.nextDouble()

	/**
	 * Returns two random numbers with normal distribution with mean [mean] and standard deviation [standardDeviation]
	 *
	 * Two numbers are returned because they are calculated using Box–Muller method
	 * https://en.wikipedia.org/wiki/Box%E2%80%93Muller_transform
	 * which has very little overhead in calculating 2nd value
	 */
	@Suppress("MagicNumber")
	fun normal(mean: Double = 0.5, standardDeviation: Double = 0.22): DoubleArray {
		val u = uniform()
		val v = uniform()

		val lnSqrt = sqrt(-2 * ln(u))
		val twoPiV = 2 * PI * v
		val x = lnSqrt * cos(twoPiV)
		val y = lnSqrt * sin(twoPiV)

		return doubleArrayOf(mean + standardDeviation * x, mean + standardDeviation * y)
	}

	/**
	 * @see normal
	 */
	@Suppress("Private")
	fun normal(mean: Int, standardDeviation: Int): IntArray {
		val normalDouble = normal(mean.toDouble(), standardDeviation.toDouble())
		return normalDouble.toIntArray()
	}

	/**
	 * Returns number with exponential distribution between 0 and 1
	 */
	fun exponential(lambda: Double = 1.0): Double {
		return -ln(1 - (1 - exp(-lambda)) * uniform()) / lambda
	}
}
