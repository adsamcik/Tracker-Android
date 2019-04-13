package com.adsamcik.signalcollector.preference.listener

class PreferenceListenerGroup<T> {
	private val listeners = mutableListOf<OnPreferenceChanged<T>>()

	val isEmpty = listeners.isEmpty()

	fun addListener(listener: OnPreferenceChanged<T>) {
		listeners.add(listener)
	}

	fun removeListener(listener: OnPreferenceChanged<T>) {
		listeners.remove(listener)
	}

	internal fun onChange(value: T) {
		listeners.forEach { it.onChange(value) }
	}
}

interface OnPreferenceChanged<T> {
	fun onChange(value: T)
}