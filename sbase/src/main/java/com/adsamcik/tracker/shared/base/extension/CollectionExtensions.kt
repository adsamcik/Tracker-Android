@file:Suppress("TooManyFunctions")

package com.adsamcik.tracker.shared.base.extension

import androidx.annotation.FloatRange
import com.adsamcik.tracker.shared.base.graph.Vertex
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Find if collection contains any item satisfying [func]
 *
 * @param func Higher order function that determines whether item satisfies the contains condition
 * @return True if any item returns true from [func]
 */
inline fun <T> Collection<T>.contains(func: (T) -> Boolean): Boolean {
	forEach {
		if (func(it)) {
			return true
		}
	}
	return false
}

/**
 * Calculates average from [transform] function applied to items in collection.
 *
 * @param transform Transform function
 * @return Average of transformed items
 */
inline fun <T> Collection<T>.averageDouble(transform: (T) -> Double): Double =
		averageIfDouble({ true }, transform)

/**
 * Calculates average from [transform] function applied to items in collection.
 *
 * @param transform Transform function
 * @return Average of transformed items
 */
inline fun <T> Collection<T>.averageFloat(transform: (T) -> Float): Float =
		averageIfFloat({ true }, transform)

/**
 * Calculates average from [transform] function applied to items in collection that
 * pass the condition function..
 *
 * @param transform Transform function
 * @return Average of transformed items
 */
inline fun <T> Collection<T>.averageIfFloat(
		condition: (T) -> Boolean,
		transform: (T) -> Float
): Float {
	var sum = 0.0f
	var count = 0
	forEach {
		if (condition(it)) {
			sum += transform(it)
			count++
		}
	}
	return sum / count.toFloat()
}

/**
 * Calculates average from [transform] function applied to items in collection that
 * pass the condition function..
 *
 * @param transform Transform function
 * @return Average of transformed items
 */
inline fun <T> Collection<T>.averageIfDouble(
		condition: (T) -> Boolean,
		transform: (T) -> Double
): Double {
	var sum = 0.0
	var count = 0
	forEach {
		if (condition(it)) {
			sum += transform(it)
			count++
		}
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


/**
 * Removes item from mutable list based on condition.
 *
 * @param condition Condition which returns true if item should be removed.
 *
 * @return True if item was removed.
 */
inline fun <T> MutableList<T>.remove(condition: (T) -> Boolean): Boolean {
	for (i in 0 until size) {
		if (condition(get(i))) {
			removeAt(i)
			return true
		}
	}
	return false
}

/**
 * Removes all items at indexes specified in [indexCollection] collection.
 * Indexes are converted to set to prevent duplicate values before removal.
 *
 * @param indexCollection Index collection
 */
fun <T> MutableList<T>.removeAllByIndexes(indexCollection: Collection<Int>) {
	indexCollection.sortedDescending().toSet().forEach { removeAt(it) }
}

/**
 * Converts double array to int array. [Double.toInt] is used for the conversion.
 */
fun DoubleArray.toIntArray(): IntArray {
	val intArray = IntArray(size)
	for (i in indices) {
		intArray[i] = this[i].toInt()
	}
	return intArray
}

/**
 * Rounds [DoubleArray] to [IntArray] using [Double.roundToInt].
 */
fun DoubleArray.roundToIntArray(): IntArray {
	val intArray = IntArray(size)
	for (i in indices) {
		intArray[i] = this[i].roundToInt()
	}
	return intArray
}

/**
 * Sorts an array using an array of the same size that contains order (indexes)
 * by which the array is sorted.
 * Each index refers to an item and its position determines the position of that item
 * in the final sorted array.
 *
 * @param indexList List of indexes used for sorting
 * @return Sorted array by [indexList]
 */
fun <T> List<T>.sortByIndexes(indexList: Collection<Int>): List<T> {
	require(indexList.size == size)

	val sortedList = ArrayList<T>(size)
	indexList.forEach { sortedList.add(get(it)) }
	return sortedList
}

/**
 * Sorts an array using an array of the same size that contains order (vertexes)
 * by which the array is sorted.
 * Each vertex refers to an item and its position determines the position of that item
 * in the final sorted array.
 *
 * @param vertexList List of indexes used for sorting
 * @return Sorted array by [vertexList]
 */
fun <T> List<T>.sortByVertexes(vertexList: Collection<Vertex>): List<T> {
	return sortByIndexes(vertexList.map { it.value })
}

/**
 * Filters all consecutive values whose difference is smaller than [similarity] value.
 *
 */
fun List<Float>.filterConsecutive(@FloatRange(from = 0.0) similarity: Float): List<Float> {
	return filterConsecutive { lastValue, value -> abs(lastValue - value) > similarity }
}

/**
 * Filters all equal consecutive values.
 *
 * Eg. [1,2,2,3,3,4] => [1,2,3,4]
 */
inline fun <T> List<T>.filterConsecutive(similarityFunc: (lastValue: T, value: T) -> Boolean): List<T> {
	return filterConsecutive({ it }, similarityFunc)
}

/**
 * Goes through the list and eliminates all consecutive values that are similar.
 *
 * @param similarityTransform Transforms data to desired similarity format.
 * @param similarityFunc Similarity calculation function that returns true if values are similar.
 *
 * @return New list without consecutive similar values.
 */
inline fun <T, R> List<T>.filterConsecutive(
		similarityTransform: (value: T) -> R,
		similarityFunc: (lastValue: R, value: R) -> Boolean
): List<T> {
	if (size <= 1) return toList()

	var lastKeptValue = similarityTransform(get(0))

	val toKeepList = mutableListOf(get(0))

	for (i in 1 until size) {
		val value = get(i)
		val transformedValue = similarityTransform(value)
		if (similarityFunc.invoke(lastKeptValue, transformedValue)) {
			toKeepList.add(value)
			lastKeptValue = transformedValue
		}
	}
	return toKeepList
}

/**
 * Processes collection in parallel.
 *
 * @param func Function called in parallel to process collection.
 */
suspend inline fun <Data, Result> Collection<Data>.forEachParallel(
		crossinline func: suspend (Data) -> Result
): List<Deferred<Result>> = coroutineScope { map { async { func(it) } } }

/**
 * Processes collection in parallel.
 *
 * @param func Function called in parallel to process collection.
 */
fun <Data, Result> Collection<Data>.forEachParallelBlocking(
		func: suspend (Data) -> Result
): List<Result> = runBlocking { forEachParallel(func).awaitAll() }

/**
 * Throws an [IllegalArgumentException] if the value is null.
 * Otherwise returns the not null value.
 *
 * @param key Key
 *
 * @return Non-null value
 */
fun <K, V : Any> Map<K, V>.require(key: K): V {
	return requireNotNull(get(key)) { "No value with key $key found" }
}
