package com.adsamcik.signalcollector.preference.listener

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class PreferenceListenerType<T> {
	private val lock = ReentrantLock()
	private val map = mutableMapOf<String, MutableLiveData<T>>()

	fun invoke(key: String, value: T) {
		lock.withLock { map[key]?.postValue(value) }
	}

	fun observe(key: String, observer: Observer<in T>) {
		lock.withLock {
			getListenerGroup(key).observeForever(observer)
		}
	}

	fun observe(key: String, observer: Observer<in T>, owner: LifecycleOwner) {
		lock.withLock {
			getListenerGroup(key).observe(owner, observer)
		}
	}

	fun removeObserver(key: String, observer: Observer<in T>) {
		lock.withLock {
			val listenerGroup = getListenerGroup(key)
			listenerGroup.removeObserver(observer)

			if (listenerGroup.hasObservers())
				map.remove(key)
		}
	}

	private fun getListenerGroup(key: String): MutableLiveData<T> = map[key]
			?: MutableLiveData<T>().also { map[key] = it }

}