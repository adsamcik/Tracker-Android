package com.adsamcik.tracker.statistics.data.source

/**
 * Mutable multi-type map. Enables typed access to variables of different types.
 */
class MutableMultiTypeMap<K : Any, V : Any> : MultiTypeMap<K, V>(), MutableMap<K, V> {
	override val map: MutableMap<K, V> = mutableMapOf()

	override val size: Int
		get() = map.size


	override val entries: MutableSet<MutableMap.MutableEntry<K, V>>
		get() = map.entries

	override val keys: MutableSet<K>
		get() = map.keys

	override val values: MutableCollection<V>
		get() = map.values

	override fun clear() {
		map.clear()
	}

	override fun put(key: K, value: V): V? {
		return map.put(key, value)
	}

	override fun putAll(from: Map<out K, V>) {
		map.putAll(from)
	}

	override fun remove(key: K): V? {
		return map.remove(key)
	}
}
