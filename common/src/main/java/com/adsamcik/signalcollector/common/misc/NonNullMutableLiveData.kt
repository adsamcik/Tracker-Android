package com.adsamcik.signalcollector.common.misc

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer

abstract class NonNullLiveData<T>(defaultValue: T) : LiveData<T>(defaultValue) {
	override fun getValue(): T {
		return super.getValue()
		       ?: throw NullPointerException("Value was null. This should NEVER happen!")
	}

	fun observe(owner: LifecycleOwner, body: (T) -> Unit) {
		observe(owner, Observer<T> { t: T? ->
			body(t ?: throw NullPointerException("Value was null. This should NEVER happen!"))
		})
	}

	fun observeGetCurrent(owner: LifecycleOwner, body: (T) -> Unit) {
		body(value)
		observe(owner, body)
	}
}

/**
 * Wrapper class for MutableLiveData to provider non-null type handling for Kotlin types
 */
class NonNullLiveMutableData<T>(defaultValue: T) : NonNullLiveData<T>(defaultValue) {
	public override fun postValue(value: T) {
		super.postValue(value)
	}

	public override fun setValue(value: T) {
		super.setValue(value)
	}
}
