package com.adsamcik.signalcollector.map

import com.adsamcik.signalcollector.utility.CoordinateBounds
import com.adsamcik.signalcollector.utility.Int2
import com.google.android.gms.maps.model.Tile
import com.google.android.gms.maps.model.TileProvider


class LocationTileProvider : TileProvider {
	var colorProvider: MapTileColorProvider? = null

	private val map = mutableMapOf<Int2, HeatmapTile>()

	private var lastZoom = 0

	fun recalculateHeat() {
		val maxHeat = map.maxBy { it.value.heatmap.maxHeat }!!.value.heatmap.maxHeat
		map.forEach {
			it.value.heatmap.maxHeat = maxHeat
			it.value.heatmap.dynamicHeat = false
		}
	}

	override fun getTile(x: Int, y: Int, z: Int): Tile {
		if (lastZoom != z) {
			map.clear()
			lastZoom = z
		}

		val colorProvider = colorProvider!!


		val leftX = MapFunctions.toLon(x.toDouble(), z)
		val topY = MapFunctions.toLat(y.toDouble(), z)

		val rightX = MapFunctions.toLon((x + 1).toDouble(), z)
		val bottomY = MapFunctions.toLat((y + 1).toDouble(), z)

		val area = CoordinateBounds(topY, rightX, bottomY, leftX)

		val key = Int2(x, y)
		val heatmap: HeatmapTile
		if (map.containsKey(key)) {
			heatmap = map[key]!!
		} else {
			heatmap = colorProvider.getHeatmap(x, y, z, area)
			map[key] = heatmap
		}
		return Tile(IMAGE_SIZE, IMAGE_SIZE, heatmap.toByteArray(IMAGE_SIZE))
	}

	companion object {
		const val IMAGE_SIZE: Int = 256
	}
}