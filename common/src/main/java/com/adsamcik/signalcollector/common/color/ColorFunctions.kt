package com.adsamcik.signalcollector.common.color

import android.graphics.Color
import androidx.core.graphics.alpha
import androidx.core.graphics.blue
import androidx.core.graphics.green
import androidx.core.graphics.red

object ColorFunctions {
	fun averageRgb(first: Int, second: Int): Int {
		val red = (first.red + second.red) / 2
		val green = (first.green + second.green) / 2
		val blue = (first.blue + second.blue) / 2
		return Color.rgb(red, green, blue)
	}

	fun averageRgba(first: Int, second: Int): Int {
		val red = (first.red + second.red) / 2
		val green = (first.green + second.green) / 2
		val blue = (first.blue + second.blue) / 2
		val alpha = (first.alpha + second.alpha) / 2
		return Color.argb(alpha, red, green, blue)
	}
}