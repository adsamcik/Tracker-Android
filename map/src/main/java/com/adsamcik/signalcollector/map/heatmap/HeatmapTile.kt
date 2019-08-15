package com.adsamcik.signalcollector.map.heatmap

import android.graphics.Bitmap
import androidx.core.graphics.scale
import com.adsamcik.signalcollector.common.database.data.Database2DLocationWeightedMinimal
import com.adsamcik.signalcollector.common.extension.toByteArray
import com.adsamcik.signalcollector.map.MapFunctions
import com.adsamcik.signalcollector.map.heatmap.creators.HeatmapData
import kotlin.math.roundToInt

internal class HeatmapTile(
		val data: HeatmapData
) {
	val heatmap: Heatmap = Heatmap(data.heatmapSize, data.heatmapSize, data.config.maxHeat, data.config.dynamicHeat)

	private val tileCount: Int = MapFunctions.getTileCount(data.zoom)

	val maxHeat: Float get() = heatmap.maxHeat

	fun addAll(list: List<Database2DLocationWeightedMinimal>) {
		list.forEach { add(it) }
	}

	fun add(location: Database2DLocationWeightedMinimal) {
		val tx = MapFunctions.toTileX(location.longitude, tileCount)
		val ty = MapFunctions.toTileY(location.latitude, tileCount)
		val x = ((tx - data.x) * data.heatmapSize).roundToInt()
		val y = ((ty - data.y) * data.heatmapSize).roundToInt()
		heatmap.addWeightedPointWithStamp(x, y, data.config.stamp, location.normalizedWeight.toFloat(),
				data.config.mergeFunction)
	}


	fun toByteArray(bitmapSize: Int): ByteArray {
		val array = heatmap.renderSaturatedTo(data.config.colorScheme, heatmap.maxHeat) { it }
		val bitmap = Bitmap.createBitmap(array, data.heatmapSize, data.heatmapSize, Bitmap.Config.ARGB_8888)

		return if (data.heatmapSize != bitmapSize) {
			bitmap.scale(bitmapSize, bitmapSize, false).toByteArray()
		} else
			bitmap.toByteArray()
	}

	companion object {
		const val BASE_HEATMAP_SIZE: Int = 128
	}
}
