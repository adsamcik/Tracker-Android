package com.adsamcik.signals.utilities

import android.content.Context


typealias Callback = () -> Unit
typealias ValueCallback<T> = (T) -> Unit
typealias ContextValueCallback<T> = (Context, T) -> Unit