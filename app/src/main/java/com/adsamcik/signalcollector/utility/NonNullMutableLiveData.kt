package com.adsamcik.signalcollector.utility

import android.arch.lifecycle.LifecycleOwner
import android.arch.lifecycle.MutableLiveData
import android.arch.lifecycle.Observer

class NonNullLiveMutableData<T>(private val defaultValue: T) : MutableLiveData<T>() {
    override fun getValue(): T {
        return super.getValue() ?: defaultValue
    }

    fun observe(owner: LifecycleOwner, body: (T) -> Unit) {
        observe(owner, Observer<T> { t -> body(t ?: defaultValue) })
    }
}