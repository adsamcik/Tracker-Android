package com.adsamcik.signalcollector.common.misc

import com.adsamcik.signalcollector.common.Time
import kotlin.math.*
import kotlin.random.Random

object Probability {
	val random: Random = Random(Time.nowMillis)

	/**
	 * Returns random number with uniform distribution
	 */
	@Suppress("UNUSED")
	fun uniform(from: Int, until: Int): Int = random.nextInt(from, until)

	/**
	 * Returns random number with uniform distribution
	 */
	@Suppress("UNUSED")
	fun uniform(from: Double, until: Double): Double = random.nextDouble(from, until)

	/**
	 * Returns random value between 0 (inclusive) and 1 (exclusive)
	 */
	@Suppress("PRIVATE")
	fun uniform(): Double = random.nextDouble()

	/**
	 * Returns two random numbers with normal distribution with mean [mean] and standard deviation [standardDeviation]
	 *
	 * Two numbers are returned because they are calculated using Box–Muller method https://en.wikipedia.org/wiki/Box%E2%80%93Muller_transform which has very little overhead in calculating 2nd value
	 */
	fun normal(mean: Double = 0.5, standardDeviation: Double = 0.22): Array<Double> {
		val u = uniform()
		val v = uniform()

		val lnSqrt = sqrt(-2 * ln(u))
		val twoPiV = 2 * PI * v
		val x = lnSqrt * cos(twoPiV)
		val y = lnSqrt * sin(twoPiV)

		return arrayOf(mean + standardDeviation * x, mean + standardDeviation * y)
	}


	/**
	 * @see normal
	 */
	@Suppress("UNUSED")
	fun normal(mean: Int, standardDeviation: Int): Array<Int> {
		val normalDouble = normal(mean.toDouble(), standardDeviation.toDouble())
		return normalDouble.map { it.toInt() }.toTypedArray()
	}

	/**
	 * Returns number with exponential distribution between 0 and 1
	 */
	fun exponential(lambda: Double = 1.0): Double {
		return -ln(1 - (1 - exp(-lambda)) * uniform()) / lambda
	}
}