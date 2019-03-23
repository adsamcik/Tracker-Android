package com.adsamcik.signalcollector.map

import android.renderscript.Double2
import kotlin.math.pow

object MapFunctions {
	fun getTileCount(zoom: Int): Int {
		return 2.0.pow(zoom).toInt()
	}

	fun toLon(x: Double, zoom: Int): Double {
		return x / getTileCount(zoom) * 360.0 - 180.0
	}

	fun toLat(y: Double, zoom: Int): Double {
		val n = kotlin.math.PI - 2.0 * kotlin.math.PI * y / getTileCount(zoom)
		return 180.0 / kotlin.math.PI * kotlin.math.atan(0.5 * (kotlin.math.exp(n) - Math.exp(-n)))
	}

	fun getTileSize(zoom: Int): Double2 {
		val tileCount = getTileCount(zoom)
		val oneTile = 1.0 / tileCount.toDouble()
		return Double2(oneTile * 360.0, oneTile * 180.0)
	}
}