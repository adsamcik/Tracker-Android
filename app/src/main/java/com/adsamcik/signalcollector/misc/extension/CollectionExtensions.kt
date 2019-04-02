package com.adsamcik.signalcollector.misc.extension

/**
 * Find if collection contains any item satisfying [func]
 *
 * @param func Higher order function that determines whether item satisfies the contains condition
 * @return True if any item returns true from [func]
 */
inline fun <T> Collection<T>.contains(func: (T) -> Boolean): Boolean {
	forEach {
		if (func(it))
			return true
	}
	return false
}

inline fun <T> Collection<T>.average(func: (T) -> Double): Double {
	var sum = 0.0
	var count = 0
	forEach {
		sum += func(it)
		count++
	}
	return sum / count.toDouble()
}

/**
 * @param distance Function that returns positive decimal value that represents distance
 * @return Returns null if collection is empty or all elements have distance of [Double.MAX_VALUE]
 */
inline fun <T> Collection<T>.nearestDouble(distance: (T) -> Double): T? {
	var nearest = Double.MAX_VALUE
	var nearestItem: T? = null

	forEach {
		val thisDistance = distance(it)
		if (thisDistance < nearest) {
			nearest = thisDistance
			nearestItem = it
		}
	}

	return nearestItem
}

/**
 * @param distance Function that returns positive integer value that represents distance
 * @return Returns null if collection is empty or all elements have distance of [Long.MAX_VALUE]
 */
inline fun <T> Collection<T>.nearestLong(distance: (T) -> Long): T? {
	var nearest = Long.MAX_VALUE
	var nearestItem: T? = null

	forEach {
		val thisDistance = distance(it)
		if (thisDistance < nearest) {
			nearest = thisDistance
			nearestItem = it
		}
	}

	return nearestItem
}