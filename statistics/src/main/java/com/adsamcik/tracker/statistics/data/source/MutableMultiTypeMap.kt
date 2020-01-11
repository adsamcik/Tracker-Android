package com.adsamcik.tracker.statistics.data.source

/**
 * Mutable multi-type map. Enables typed access to variables of different types.
 */
class MutableMultiTypeMap<K : Any, V : Any> : MutableMap<K, V> {
	private val map: MutableMap<K, V> = mutableMapOf()

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
