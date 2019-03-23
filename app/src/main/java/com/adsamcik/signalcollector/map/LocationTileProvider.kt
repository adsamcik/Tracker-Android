package com.adsamcik.signalcollector.map

import android.graphics.Color
import androidx.core.graphics.blue
import androidx.core.graphics.green
import androidx.core.graphics.red
import com.adsamcik.signalcollector.extensions.MathExtensions
import com.adsamcik.signalcollector.map.HeatmapTile.Companion.HEATMAP_SIZE
import com.adsamcik.signalcollector.utility.CoordinateBounds
import com.adsamcik.signalcollector.utility.Int2
import com.google.android.gms.maps.model.Tile
import com.google.android.gms.maps.model.TileProvider
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min


class LocationTileProvider : TileProvider {
	var colorProvider: MapTileColorProvider? = null

	private val map = mutableMapOf<Int2, HeatmapTile>()


	fun iterate(tileX: Int, tileY: Int, distance: Int): IntArray {
		val data = map[Int2(tileX, tileY)]
				?: return IntArray(HeatmapTile.HEATMAP_SIZE * HeatmapTile.HEATMAP_SIZE)

		if (data.grid.all { it == 0 })
			return IntArray(HeatmapTile.HEATMAP_SIZE * HeatmapTile.HEATMAP_SIZE)

		val heatmap = data.grid.copyOf()

		heatmap.forEachIndexed { index, item ->
			if (item > 0) {
				val itemOffset = 5
				val itemScale = (item - itemOffset) * 10
				heatmap[index] = Color.argb(min(item * 25, 200), min(255, itemScale), max(0, 255 - itemScale), 0)
			}
		}

		var maxDelta = Int.MAX_VALUE

		//while (maxDelta > 5) {
		val backlog = heatmap.copyOf()
		maxDelta = 0
		for (y in 0 until HEATMAP_SIZE) {
			for (x in 0 until HEATMAP_SIZE) {
				if (data.grid[x + y * HEATMAP_SIZE] == 0) {
					var sumRed = 0
					var sumBlue = 0
					var sumGreen = 0

					val fromY = max(0, y - 1)
					val toY = min(HEATMAP_SIZE - 1, y + 1)

					val fromX = max(0, x - 1)
					val toX = min(HEATMAP_SIZE - 1, x + 1)

					for (yy in fromY..toY) {
						for (xx in fromX..toX) {
							val heat = backlog[xx + yy * HEATMAP_SIZE]
							sumRed += heat.red
							sumBlue += heat.blue
							sumGreen += heat.green
						}
					}
					val value = heatmap[x + y * HEATMAP_SIZE]

					val count = (toY - fromY + 1) * (toX - fromX + 1)
					val newValue = MathExtensions.lerpArgb(0.5, value, Color.argb(255, sumRed / count, sumGreen / count, sumBlue / count))

					val delta = max(abs(newValue.red - value.red), max(abs(newValue.green - value.green), abs(newValue.blue - value.blue)))
					heatmap[x + y * HEATMAP_SIZE] = newValue

					maxDelta = max(maxDelta, delta)
				}

			}
		}
		//}

		return heatmap
	}


	override fun getTile(x: Int, y: Int, z: Int): Tile {
		val colorProvider = colorProvider!!


		val leftX = MapFunctions.toLon(x.toDouble(), z)
		val leftY = MapFunctions.toLat(y.toDouble(), z)

		val rightX = MapFunctions.toLon((x + 1).toDouble(), z)
		val rightY = MapFunctions.toLat((y + 1).toDouble(), z)

		val area = CoordinateBounds(leftY, rightX, rightY, leftX)

		val heatmap = colorProvider.getHeatmap(x, y, z, area)
		map[Int2(x, y)] = heatmap
		assert(Int2(x, y) == Int2(x, y))
		assert(map[Int2(x, y)] != null)
		val result = iterate(x, y)

		result.copyInto(heatmap.heatmap)

		return Tile(IMAGE_SIZE, IMAGE_SIZE, heatmap.toByteArray(IMAGE_SIZE))
	}

	companion object {
		const val IMAGE_SIZE: Int = 256
	}
}