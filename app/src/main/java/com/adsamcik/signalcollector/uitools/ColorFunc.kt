package com.adsamcik.signalcollector.uitools

import android.graphics.Color
import android.support.annotation.ColorInt
import android.support.v4.graphics.ColorUtils

fun brightenComponent(component: Int, value: Int) = Math.min(component + value, 255)

fun brightenColor(@ColorInt color: Int, value: Int): Int {
    val r = brightenComponent(Color.red(color), value)
    val g = brightenComponent(Color.green(color), value)
    val b = brightenComponent(Color.blue(color), value)
    return Color.rgb(r, g, b)
}

fun relRed(@ColorInt color: Int) = Color.red(color) / 255.0
fun relGreen(@ColorInt color: Int) = Color.green(color) / 255.0
fun relBlue(@ColorInt color: Int) = Color.blue(color) / 255.0

fun perceivedLuminance(@ColorInt color: Int) = 0.299 * relRed(color) + 0.587 * relGreen(color) + 0.114 * relBlue(color)

fun perceivedRelLuminance(@ColorInt color: Int) = Math.signum(perceivedLuminance(color) - 0.5).toByte()
fun relLuminance(@ColorInt color: Int) = Math.signum(ColorUtils.calculateLuminance(color) - 0.5).toByte()