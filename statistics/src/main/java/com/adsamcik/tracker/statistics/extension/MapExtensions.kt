package com.adsamcik.tracker.statistics.extension

import com.adsamcik.tracker.shared.base.extension.require
import com.adsamcik.tracker.statistics.data.source.StatDataMap
import com.adsamcik.tracker.statistics.data.source.abstraction.StatDataProducer
import kotlin.reflect.KClass

/**
 * Required nullable typed value from array.
 */
@Suppress("UNCHECKED_CAST")
fun <K, V, T : V> Map<K, V>.getTyped(key: K): T? {
	return get(key) as T?
}

/**
 * Require non-null typed value from map.
 */
@Suppress("UNCHECKED_CAST")
fun <K, V : Any, T : V> Map<K, V>.requireTyped(key: K): T {
	return this.require(key) as T
}

/**
 * Get typed data from [StatDataMap].
 */
@Suppress("UNCHECKED_CAST")
fun <T : Any> StatDataMap.requireData(key: KClass<out StatDataProducer>): T {
	val data = requireNotNull(this.require(key).data) { "Data for key $key was null" }
	return data as T
}
