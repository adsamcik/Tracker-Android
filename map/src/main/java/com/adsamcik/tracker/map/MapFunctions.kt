package com.adsamcik.tracker.map

import com.adsamcik.tracker.shared.base.constant.GeometryConstants.CIRCLE_IN_DEGREES
import com.adsamcik.tracker.shared.base.constant.GeometryConstants.CIRCLE_IN_RADIANS
import com.adsamcik.tracker.shared.base.constant.GeometryConstants.HALF_CIRCLE_IN_DEGREES
import com.adsamcik.tracker.shared.base.constant.GeometryConstants.HALF_CIRCLE_IN_RADIANS
import com.adsamcik.tracker.shared.base.extension.LocationExtensions.EARTH_CIRCUMFERENCE
import com.adsamcik.tracker.shared.base.extension.toRadians
import com.adsamcik.tracker.shared.base.misc.Double2
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
		return x / getTileCount(zoom) * CIRCLE_IN_DEGREES - HALF_CIRCLE_IN_DEGREES
	}

	fun toLat(y: Double, zoom: Int): Double {
		val n = HALF_CIRCLE_IN_RADIANS - CIRCLE_IN_RADIANS * y / getTileCount(zoom)
		return HALF_CIRCLE_IN_DEGREES / PI * atan(0.5 * (exp(n) - exp(-n)))
	}

	fun toTileX(lon: Double, tileCount: Int): Double {
		return tileCount.toDouble() * ((lon + HALF_CIRCLE_IN_DEGREES) / CIRCLE_IN_DEGREES)
	}

	fun toTileY(lat: Double, tileCount: Int): Double {
		val latRadians = PI / HALF_CIRCLE_IN_DEGREES * lat
		return tileCount.toDouble() * (1.0 - ln(tan(latRadians) + 1 / cos(latRadians)) / PI) / 2
	}

	fun countPixelSize(latitude: Double, zoom: Int): Double {
		return EARTH_CIRCUMFERENCE * cos(latitude.toRadians()) / 2.0.pow(zoom + 8)
	}

	fun getTileSize(zoom: Int): Double2 {
		val tileCount = getTileCount(zoom)
		val oneTile = 1.0 / tileCount.toDouble()
		return Double2(oneTile * CIRCLE_IN_DEGREES, oneTile * HALF_CIRCLE_IN_DEGREES)
	}
}

