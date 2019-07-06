package com.adsamcik.signalcollector.common.extension

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer

/**
 * LiveData observe that allows Higher order function instead of observer
 * More at [LiveData.observe]
 *
 * @param owner Lifecycle owner
 * @param body Higher order function that is executed when live data changes
 */
fun <T> LiveData<T>.observe(owner: LifecycleOwner, body: (T?) -> Unit) {
	observe(owner, Observer<T> { t -> body(t) })
}

/**
 * Returns the current value or throws exception if value is null. Note that calling this method on a background thread does not guarantee that the latest value set will be received
 */
val <T> LiveData<T>.requireValue
	get() = value ?: throw NullPointerException("Value cannot be null.")