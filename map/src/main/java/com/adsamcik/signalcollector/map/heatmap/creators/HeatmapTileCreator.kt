package com.adsamcik.signalcollector.map.heatmap.creators

import com.adsamcik.signalcollector.database.data.Database2DLocationWeightedMinimal
import com.adsamcik.signalcollector.map.CoordinateBounds
import com.adsamcik.signalcollector.map.heatmap.HeatmapColorScheme
import com.adsamcik.signalcollector.map.heatmap.HeatmapStamp
import com.adsamcik.signalcollector.map.heatmap.HeatmapTile
import com.adsamcik.signalcollector.common.misc.extension.isPowerOfTwo

interface HeatmapTileCreator {
	val getAllInsideAndBetween: (from: Long, to: Long, topLatitude: Double, rightLongitude: Double, bottomLatitude: Double, leftLongitude: Double) -> List<Database2DLocationWeightedMinimal>

	val getAllInside: (topLatitude: Double, rightLongitude: Double, bottomLatitude: Double, leftLongitude: Double) -> List<Database2DLocationWeightedMinimal>

	val weightNormalizationValue: Double

	fun getHeatmap(heatmapSize: Int, stamp: HeatmapStamp, colorScheme: HeatmapColorScheme, from: Long, to: Long, x: Int, y: Int, z: Int, area: CoordinateBounds, maxHeat: Float): HeatmapTile {
		return createHeatmap(heatmapSize, stamp, colorScheme, x, y, z, area, maxHeat) { topLatitude, rightLongitude, bottomLatitude, leftLongitude ->
			getAllInsideAndBetween(from, to, topLatitude, rightLongitude, bottomLatitude, leftLongitude)
		}
	}

	fun getHeatmap(heatmapSize: Int, stamp: HeatmapStamp, colorScheme: HeatmapColorScheme, x: Int, y: Int, z: Int, area: CoordinateBounds, maxHeat: Float): HeatmapTile {
		return createHeatmap(heatmapSize, stamp, colorScheme, x, y, z, area, maxHeat) { topLatitude, rightLongitude, bottomLatitude, leftLongitude ->
			getAllInside(topLatitude, rightLongitude, bottomLatitude, leftLongitude)
		}
	}

	private fun createHeatmap(heatmapSize: Int,
	                          stamp: HeatmapStamp,
	                          colorScheme: HeatmapColorScheme,
	                          x: Int,
	                          y: Int,
	                          z: Int,
	                          area: CoordinateBounds,
	                          maxHeat: Float,
	                          getLocations: (topLatitude: Double, rightLongitude: Double, bottomLatitude: Double, leftLongitude: Double) -> List<Database2DLocationWeightedMinimal>): HeatmapTile {
		assert(heatmapSize.isPowerOfTwo())
		assert(area.left < area.right)
		assert(area.bottom < area.top)

		val extendLatitude = area.height * (stamp.height.toDouble() / heatmapSize.toDouble())
		val extendLongitude = area.width * (stamp.width.toDouble() / heatmapSize.toDouble())

		assert(extendLatitude > 0)
		assert(extendLongitude > 0)

		val allInside = getLocations.invoke(area.top + extendLatitude, area.right + extendLongitude, area.bottom - extendLatitude, area.left - extendLongitude)
		//val allInside = dao.getAllInside(area.top, area.right, area.bottom, area.left)

		if (weightNormalizationValue != 0.0) {
			val weightNormalizationValue = weightNormalizationValue
			allInside.forEach {
				it.normalize(weightNormalizationValue)
			}
		}

		val heatmap = HeatmapTile(heatmapSize, stamp, colorScheme, x, y, z, maxHeat, true)
		heatmap.addAll(allInside.sortedWith(compareBy({ it.longitude }, { it.latitude })))
		return heatmap
	}
}