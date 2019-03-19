package com.adsamcik.signalcollector.map

import android.renderscript.Double2
import com.adsamcik.signalcollector.utility.CoordinateBounds
import com.google.android.gms.maps.model.Tile
import com.google.android.gms.maps.model.TileProvider
import kotlin.math.pow


class LocationTileProvider : TileProvider {
	var colorProvider: MapTileColorProvider? = null


	private fun getTileCount(zoom: Int): Int {
		return 2.0.pow(zoom).toInt()
	}

	private fun toLon(x: Double, zoom: Int): Double {
		return x / getTileCount(zoom) * 360.0 - 180.0
	}

	private fun toLat(y: Double, zoom: Int): Double {
		val n = kotlin.math.PI - 2.0 * kotlin.math.PI * y / getTileCount(zoom)
		return 180.0 / kotlin.math.PI * kotlin.math.atan(0.5 * (kotlin.math.exp(n) - Math.exp(-n)))
	}

	private fun getTileSize(zoom: Int): Double2 {
		val tileCount = getTileCount(zoom)
		val oneTile = 1.0 / tileCount.toDouble()
		return Double2(oneTile * 360.0, oneTile * 180.0)
	}

	override fun getTile(x: Int, y: Int, z: Int): Tile {
		val colorProvider = colorProvider!!


		val leftX = toLon(x.toDouble(), z)
		val leftY = toLat(y.toDouble(), z)

		val rightX = toLon((x + 1).toDouble(), z)
		val rightY = toLon((y + 1).toDouble(), z)

		val area = CoordinateBounds(leftY, rightX, rightY, leftX)

		return Tile(IMAGE_SIZE, IMAGE_SIZE, colorProvider.getColor(x, y, z, area))
	}

	companion object {
		const val IMAGE_SIZE: Int = 256
	}
}