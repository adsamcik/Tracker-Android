package com.adsamcik.signalcollector.extensions

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