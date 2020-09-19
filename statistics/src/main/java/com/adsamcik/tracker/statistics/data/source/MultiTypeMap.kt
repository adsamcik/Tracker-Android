package com.adsamcik.tracker.statistics.data.source

/**
 * Immutable multi-type map. Enables typed access to variables of different types.
 */
abstract class MultiTypeMap<K : Any, V : Any> : Map<K, V> {
	protected abstract val map: Map<K, V>

	override val size: Int
		get() = map.size

	override fun containsKey(key: K): Boolean {
		return map.containsKey(key)
	}

	override fun containsValue(value: V): Boolean {
		return map.containsValue(value)
	}

	override fun get(key: K): V {
		return requireNotNull(map[key]) { "Value with key $key not found" }
	}

	/**
	 *  Returns the value corresponding to the given key, or null if such a key is not present in the map.
	 */
	inline fun <reified T : V> requiredTyped(key: K): T {
		val value = get(key)

		val typedValue = get(key) as? T
		requireNotNull(typedValue) {
			"Value is not of type ${T::class.java.simpleName} but of type ${value::class.java.simpleName}"
		}

		return typedValue
	}

	override fun isEmpty(): Boolean {
		return map.isEmpty()
	}

	override val entries: Set<Map.Entry<K, V>>
		get() = map.entries

	override val keys: Set<K>
		get() = map.keys

	override val values: Collection<V>
		get() = map.values
}
