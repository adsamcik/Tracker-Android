package com.adsamcik.signalcollector.extensions

inline fun<T> List<T>.contains(func: (T) -> Boolean): Boolean {
    forEach {
        if(func(it))
            return true
    }
    return false
}