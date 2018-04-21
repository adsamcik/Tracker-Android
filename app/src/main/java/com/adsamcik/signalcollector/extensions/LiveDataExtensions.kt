package com.adsamcik.signalcollector.extensions

import android.arch.lifecycle.LifecycleOwner
import android.arch.lifecycle.LiveData
import android.arch.lifecycle.Observer

fun <T> LiveData<T>.observe(owner: LifecycleOwner, body: (T?) -> Unit) {
    observe(owner, Observer<T> { t -> body(t) })
}