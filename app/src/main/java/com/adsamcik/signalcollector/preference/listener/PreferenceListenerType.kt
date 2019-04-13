package com.adsamcik.signalcollector.preference.listener

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class PreferenceListenerType<T> {
	private val lock = ReentrantLock()
	private val map = mutableMapOf<String, PreferenceListenerGroup<T>>()

	fun invoke(key: String, value: T) {
		lock.withLock { map[key]?.onChange(value) }
	}

	fun addListener(key: String, listener: OnPreferenceChanged<T>) {
		lock.withLock {
			getListenerGroup(key).addListener(listener)
		}
	}

	fun removeListener(key: String, listener: OnPreferenceChanged<T>) {
		lock.withLock {
			val listenerGroup = getListenerGroup(key)
			listenerGroup.removeListener(listener)

			if (listenerGroup.isEmpty)
				map.remove(key)
		}
	}

	private fun getListenerGroup(key: String): PreferenceListenerGroup<T> = map[key]
			?: PreferenceListenerGroup<T>().also { map[key] = it }

}