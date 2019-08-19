package com.adsamcik.tracker.map.heatmap

import android.graphics.Bitmap
import androidx.core.graphics.scale
import com.adsamcik.tracker.common.database.data.Database2DLocationWeightedMinimal
import com.adsamcik.tracker.common.extension.toByteArray
import com.adsamcik.tracker.map.MapFunctions
import com.adsamcik.tracker.map.heatmap.creators.HeatmapTileData
import kotlin.math.roundToInt

@ExperimentalUnsignedTypes
internal class HeatmapTile(
		val data: HeatmapTileData
) {
	private val heatmap = WeightedHeatmap(data.heatmapSize, data.heatmapSize, data.config.maxHeat, data.config.dynamicHeat)

	private val tileCount: Int = MapFunctions.getTileCount(data.zoom)

	var maxHeat: Float
		get() = heatmap.maxHeat
		set(value) {
			heatmap.maxHeat = value
		}

	fun addAll(list: List<Database2DLocationWeightedMinimal>) {
		list.forEach { add(it) }
	}

	fun add(location: Database2DLocationWeightedMinimal) {
		val tx = MapFunctions.toTileX(location.longitude, tileCount)
		val ty = MapFunctions.toTileY(location.latitude, tileCount)
		val x = ((tx - data.x) * data.heatmapSize).roundToInt()
		val y = ((ty - data.y) * data.heatmapSize).roundToInt()
		heatmap.addPoint(x, y, data.stamp, location.normalizedWeight.toFloat(),
				data.config.weightMergeFunction)
	}


	fun toByteArray(bitmapSize: Int): ByteArray {
		val array = heatmap.renderSaturatedTo(data.config.colorScheme, heatmap.maxHeat) { it }
		val bitmap = Bitmap.createBitmap(array, data.heatmapSize, data.heatmapSize, Bitmap.Config.ARGB_8888)

		return if (data.heatmapSize != bitmapSize) {
			bitmap.scale(bitmapSize, bitmapSize, false).toByteArray()
		} else {
			bitmap.toByteArray()
		}
	}

	companion object {
		const val BASE_HEATMAP_SIZE: Int = 128
	}
}

