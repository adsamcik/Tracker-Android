package com.adsamcik.signalcollector.extensions

import android.arch.lifecycle.LifecycleOwner
import android.arch.lifecycle.LiveData
import android.arch.lifecycle.Observer

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