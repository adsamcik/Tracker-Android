package com.adsamcik.tracker.shared.base.misc

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer

/**
 * Non-null LiveData wrapper (deprecated).
 * 
 * @deprecated Migrate to StateFlow for reactive state management. LiveData support will be removed in a future release.
 */
@Deprecated(
	message = "Migrate to StateFlow for reactive state management",
	level = DeprecationLevel.WARNING
)
abstract class NonNullLiveData<T>(defaultValue: T) : LiveData<T>(defaultValue) {
	override fun getValue(): T {
		return super.getValue()
				?: throw NullPointerException("Value was null. This should NEVER happen!")
	}

	fun observe(owner: LifecycleOwner, body: (T) -> Unit) {
		observe(owner, Observer { t: T? ->
			body(t ?: throw NullPointerException("Value was null. This should NEVER happen!"))
		})
	}

	fun observeGetCurrent(owner: LifecycleOwner, body: (T) -> Unit) {
		body(value)
		observe(owner, body)
	}
}

/**
 * Wrapper class for MutableLiveData to provider non-null type handling for Kotlin types (deprecated).
 * 
 * @deprecated Migrate to MutableStateFlow for reactive state management. LiveData support will be removed in a future release.
 */
@Deprecated(
	message = "Migrate to MutableStateFlow for reactive state management",
	level = DeprecationLevel.WARNING
)
class NonNullLiveMutableData<T>(defaultValue: T) : NonNullLiveData<T>(defaultValue) {
	public override fun postValue(value: T) {
		super.postValue(value)
	}

	public override fun setValue(value: T) {
		super.setValue(value)
	}
}

