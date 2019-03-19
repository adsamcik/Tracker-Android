package com.adsamcik.signalcollector.extensions

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