package com.adsamcik.signalcollector.map.heatmap.providers

import com.adsamcik.signalcollector.database.data.Database2DLocationWeightedMinimal
import com.adsamcik.signalcollector.extensions.isPowerOfTwo
import com.adsamcik.signalcollector.map.heatmap.HeatmapStamp
import com.adsamcik.signalcollector.map.heatmap.HeatmapTile
import com.adsamcik.signalcollector.utility.CoordinateBounds

interface MapTileHeatmapProvider {
	val getAllInsideAndBetween: (from: Long, to: Long, topLatitude: Double, rightLongitude: Double, bottomLatitude: Double, leftLongitude: Double) -> List<Database2DLocationWeightedMinimal>

	val getAllInside: (topLatitude: Double, rightLongitude: Double, bottomLatitude: Double, leftLongitude: Double) -> List<Database2DLocationWeightedMinimal>

	fun getHeatmap(heatmapSize: Int, stamp: HeatmapStamp, from: Long, to: Long, x: Int, y: Int, z: Int, area: CoordinateBounds, maxHeat: Float): HeatmapTile {
		return createHeatmap(heatmapSize, stamp, x, y, z, area, maxHeat) { topLatitude, rightLongitude, bottomLatitude, leftLongitude ->
			getAllInsideAndBetween(from, to, topLatitude, rightLongitude, bottomLatitude, leftLongitude)
		}
	}

	fun getHeatmap(heatmapSize: Int, stamp: HeatmapStamp, x: Int, y: Int, z: Int, area: CoordinateBounds, maxHeat: Float): HeatmapTile {
		return createHeatmap(heatmapSize, stamp, x, y, z, area, maxHeat) { topLatitude, rightLongitude, bottomLatitude, leftLongitude ->
			getAllInside(topLatitude, rightLongitude, bottomLatitude, leftLongitude)
		}
	}

	private fun createHeatmap(heatmapSize: Int, stamp: HeatmapStamp, x: Int, y: Int, z: Int, area: CoordinateBounds, maxHeat: Float, getLocations: (topLatitude: Double, rightLongitude: Double, bottomLatitude: Double, leftLongitude: Double) -> List<Database2DLocationWeightedMinimal>): HeatmapTile {
		assert(heatmapSize.isPowerOfTwo())

		val extendLatitude = area.height * (stamp.height.toDouble() / heatmapSize.toDouble())
		val extendLongitude = area.width * (stamp.width.toDouble() / heatmapSize.toDouble())

		val allInside = getLocations.invoke(area.top + extendLatitude, area.right + extendLongitude, area.bottom - extendLatitude, area.left - extendLongitude)
		//val allInside = dao.getAllInside(area.top, area.right, area.bottom, area.left)

		val heatmap = HeatmapTile(heatmapSize, stamp, x, y, z, maxHeat, true)
		heatmap.addAll(allInside.sortedWith(compareBy({ it.longitude }, { it.latitude })))
		return heatmap
	}
}