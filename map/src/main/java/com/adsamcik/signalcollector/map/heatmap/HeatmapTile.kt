package com.adsamcik.signalcollector.map.heatmap

import android.graphics.Bitmap
import androidx.core.graphics.scale
import com.adsamcik.signalcollector.common.database.data.Database2DLocationWeightedMinimal
import com.adsamcik.signalcollector.common.extension.toByteArray
import com.adsamcik.signalcollector.map.MapFunctions
import kotlin.math.roundToInt

class HeatmapTile(
		val heatmapSize: Int,
		val stamp: HeatmapStamp,
		val colorScheme: HeatmapColorScheme,
		val x: Int,
		val y: Int,
		zoom: Int,
		maxHeat: Float = 0f,
		dynamicHeat: Boolean = maxHeat <= 0f) {
	val heatmap: Heatmap = Heatmap(heatmapSize, heatmapSize, maxHeat, dynamicHeat)

	val tileCount: Int = MapFunctions.getTileCount(zoom)

	val maxHeat: Float get() = heatmap.maxHeat

	fun addAll(list: List<Database2DLocationWeightedMinimal>) {
		list.forEach { add(it) }
	}

	fun add(location: Database2DLocationWeightedMinimal) {
		val tx = MapFunctions.toTileX(location.longitude, tileCount)
		val ty = MapFunctions.toTileY(location.latitude, tileCount)
		val x = ((tx - x) * heatmapSize).roundToInt()
		val y = ((ty - y) * heatmapSize).roundToInt()
		heatmap.addWeightedPointWithStamp(x, y, location.normalizedWeight.toFloat(), stamp)
	}


	fun toByteArray(bitmapSize: Int): ByteArray {
		val array = heatmap.renderSaturatedTo(colorScheme, heatmap.maxHeat) { it.coerceAtLeast(0.1f) }
		val bitmap = Bitmap.createBitmap(array, heatmapSize, heatmapSize, Bitmap.Config.ARGB_8888)

		return if (heatmapSize != bitmapSize) {
			bitmap.scale(bitmapSize, bitmapSize, false).toByteArray()
		} else
			bitmap.toByteArray()
	}

	companion object {
		const val BASE_HEATMAP_SIZE: Int = 128
	}
}