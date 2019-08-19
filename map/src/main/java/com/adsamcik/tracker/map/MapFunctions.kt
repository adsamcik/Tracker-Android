package com.adsamcik.tracker.map

import android.renderscript.Double2
import com.adsamcik.tracker.common.extension.LocationExtensions
import com.adsamcik.tracker.common.extension.deg2rad
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.tan

internal object MapFunctions {
	fun getTileCount(zoom: Int): Int {
		return 2.0.pow(zoom).toInt()
	}

	fun toLon(x: Double, zoom: Int): Double {
		return x / getTileCount(zoom) * 360.0 - 180.0
	}

	fun toLat(y: Double, zoom: Int): Double {
		val n = PI - 2.0 * PI * y / getTileCount(zoom)
		return 180.0 / PI * atan(0.5 * (exp(n) - exp(-n)))
	}

	fun toTileX(lon: Double, tileCount: Int): Double {
		return tileCount.toDouble() * ((lon + 180.0) / 360.0)
	}

	fun toTileY(lat: Double, tileCount: Int): Double {
		val latRadians = PI / 180 * lat
		return tileCount.toDouble() * (1.0 - ln(tan(latRadians) + 1 / cos(latRadians)) / PI) / 2
	}

	fun countPixelSize(latitude: Double, zoom: Int): Double {
		return LocationExtensions.EARTH_CIRCUMFERENCE * cos(latitude.deg2rad()) / 2.0.pow(zoom + 8)
	}

	fun getTileSize(zoom: Int): Double2 {
		val tileCount = getTileCount(zoom)
		val oneTile = 1.0 / tileCount.toDouble()
		return Double2(oneTile * 360.0, oneTile * 180.0)
	}
}

