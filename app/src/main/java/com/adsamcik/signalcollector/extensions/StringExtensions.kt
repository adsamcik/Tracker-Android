package com.adsamcik.signalcollector.extensions

fun Double.format(digits: Int): String = java.lang.String.format("%.${digits}f", this)