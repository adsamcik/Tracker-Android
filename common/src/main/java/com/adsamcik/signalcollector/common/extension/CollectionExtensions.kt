package com.adsamcik.signalcollector.common.extension

import kotlin.math.roundToInt

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


inline fun <T> MutableList<T>.remove(condition: (T) -> Boolean): Boolean {
	for (i in 0 until size) {
		if (condition(get(i))) {
			removeAt(i)
			return true
		}
	}
	return false
}

fun <T> MutableList<T>.removeAllByIndexes(indexList: Collection<Int>) {
	indexList.sortedDescending().toSet().forEach { removeAt(it) }
}

inline fun <T> Iterable<T>.forEachIf(condition: (T) -> Boolean, action: (T) -> Unit) {
	forEach {
		if (condition(it)) action(it)
	}
}

inline fun <T, R> Iterable<T>.mapIf(condition: (T) -> Boolean, action: (T) -> R): List<R> {
	val size = if (this is Collection<*>) this.size else 10
	val collection = ArrayList<R>(size)
	forEachIf(condition) {
		collection.add(action(it))
	}
	return collection
}

fun DoubleArray.toIntArray(): IntArray {
	val intArray = IntArray(size)
	for (i in 0 until size) {
		intArray[i] = this[i].toInt()
	}
	return intArray
}

fun DoubleArray.roundToIntArray(): IntArray {
	val intArray = IntArray(size)
	for (i in 0 until size) {
		intArray[i] = this[i].roundToInt()
	}
	return intArray
}
