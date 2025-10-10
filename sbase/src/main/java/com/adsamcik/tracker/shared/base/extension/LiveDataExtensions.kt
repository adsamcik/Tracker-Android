package com.adsamcik.tracker.shared.base.extension

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData

/**
 * LiveData observe that allows Higher order function instead of observer (deprecated).
 * More at [LiveData.observe]
 *
 * @param owner Lifecycle owner
 * @param body Higher order function that is executed when live data changes
 * @deprecated Migrate to Flow.collect for reactive observation. LiveData support will be removed in a future release.
 */
@Deprecated(
	message = "Migrate to Flow.collect for reactive observation",
	level = DeprecationLevel.WARNING
)
fun <T> LiveData<T>.observe(owner: LifecycleOwner, body: (T?) -> Unit) {
	observe(owner) { t -> body(t) }
}

/**
 * Returns the current value or throws exception if value is null (deprecated).
 * Note that calling this method on a background thread does not guarantee that the latest value set will be received.
 * 
 * @deprecated Use StateFlow.value for synchronous value access. LiveData support will be removed in a future release.
 */
@Deprecated(
	message = "Use StateFlow.value for synchronous value access",
	level = DeprecationLevel.WARNING
)
val <T> LiveData<T?>.requireValue: T
	get() = value ?: throw NullPointerException("Value cannot be null.")

