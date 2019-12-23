package com.adsamcik.tracker.shared.base.extension

import java.lang.ref.WeakReference

fun <T> WeakReference<T>.require(): T = get() ?: throw NullPointerException("Value cannot be null")
