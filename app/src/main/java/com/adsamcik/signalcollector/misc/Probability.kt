package com.adsamcik.signalcollector.misc

import com.adsamcik.signalcollector.misc.extension.rescale
import kotlin.math.*
import kotlin.random.Random

object Probability {
	/**
	 * Returns random number with uniform distribution
	 */
	fun uniform(from: Int, until: Int): Int = Random.nextInt(from, until)

	/**
	 * Returns random number with uniform distribution
	 */
	fun uniform(from: Double, until: Double): Double = Random.nextDouble(from, until)

	/**
	 * Returns random value between 0 (inclusive) and 1 (exclusive)
	 */
	fun uniform(): Double = Random.nextDouble()

	/**
	 * Returns two random numbers with normal distribution between [from] (inclusive) and [until] (exclusive)
	 *
	 * Two numbers are returned because they are calculated using Box–Muller method https://en.wikipedia.org/wiki/Box%E2%80%93Muller_transform which has very little overhead in calculating 2nd value
	 */
	fun normal(from: Double, until: Double): Pair<Double, Double> {
		val u = uniform()
		val v = uniform()

		val lnSqrt = sqrt(-2 * ln(u))
		val twoPiV = 2 * PI * v
		val x = lnSqrt * cos(twoPiV)
		val y = lnSqrt * sin(twoPiV)

		return Pair(x.rescale(from..until), y.rescale(from..until))
	}


	/**
	 * @see normal
	 */
	fun normal(from: Int, until: Int): Pair<Int, Int> {
		val normalDouble = normal(from.toDouble(), until.toDouble())
		return Pair(normalDouble.first.toInt(), normalDouble.second.toInt())
	}
}