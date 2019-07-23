package com.adsamcik.signalcollector.tracker.data

import com.adsamcik.signalcollector.common.data.ActivityInfo

class CollectionTempData(val distance: Float,
                         val elapsedRealtimeNanos: Long,
                         val activity: ActivityInfo) {
	private val map: MutableMap<String, InternalData> = mutableMapOf()

	inline fun <reified T> set(key: String, value: T) {
		set(key, InternalData(T::class.java, value as Any))
	}

	fun set(key: String, value: InternalData) {
		map[key] = value
	}

	fun <T> tryGet(key: String): T? {
		@Suppress("UNCHECKED_CAST")
		return map[key]?.value as? T
	}


	data class InternalData(val type: Class<*>, val value: Any)
}