package com.adsamcik.tracker.shared.preferences.observer

import androidx.annotation.MainThread
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer

/**
 * Preference listener for generic data type.
 */
@MainThread
class PreferenceListenerType<T> {
	private val map = mutableMapOf<String, MutableLiveData<T>>()

	/**
	 * Invokes all observers.
	 */
	fun invoke(key: String, value: T) {
		map[key]?.postValue(value)
	}

	/**
	 * Registers observer forever.
	 */
	fun observe(key: String, observer: Observer<in T>) {
		getListenerGroup(key).observeForever(observer)
	}

	/**
	 * Registers observer with lifecycle owner.
	 */
	fun observe(key: String, observer: Observer<in T>, owner: LifecycleOwner) {
		getListenerGroup(key).observe(owner, observer)
	}

	/**
	 * Removes observer.
	 */
	fun removeObserver(key: String, observer: Observer<in T>) {
		val listenerGroup = getListenerGroup(key)
		listenerGroup.removeObserver(observer)

		if (listenerGroup.hasObservers()) map.remove(key)
	}

	private fun getListenerGroup(key: String): MutableLiveData<T> =
			map[key] ?: MutableLiveData<T>().also { map[key] = it }
}

