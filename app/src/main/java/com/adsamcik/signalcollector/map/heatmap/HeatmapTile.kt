package com.adsamcik.signalcollector.map.heatmap

import android.graphics.Bitmap
import androidx.core.graphics.scale
import com.adsamcik.signalcollector.database.data.Database2DLocationWeightedMinimal
import com.adsamcik.signalcollector.extensions.toByteArray
import com.adsamcik.signalcollector.map.MapFunctions
import kotlin.math.roundToInt

class HeatmapTile(
		val heatmapSize: Int,
		val stamp: HeatmapStamp,
		val x: Int,
		val y: Int,
		zoom: Int,
		maxHeat: Float = 0f,
		dynamicHeat: Boolean = maxHeat <= 0f) {
	val heatmap = Heatmap(BASE_HEATMAP_SIZE, BASE_HEATMAP_SIZE, maxHeat, dynamicHeat)

	val tileCount = MapFunctions.getTileCount(zoom)

	val maxHeat get() = heatmap.maxHeat

	fun addAll(list: List<Database2DLocationWeightedMinimal>) {
		list.forEach { add(it) }
	}

	fun add(location: Database2DLocationWeightedMinimal) {
		val tx = MapFunctions.toTileX(location.longitude, tileCount)
		val ty = MapFunctions.toTileY(location.latitude, tileCount)
		val x = ((tx - x) * HEATMAP_SIZE_AS_DOUBLE).roundToInt()
		val y = ((ty - y) * HEATMAP_SIZE_AS_DOUBLE).roundToInt()
		heatmap.addWeightedPointWithStamp(x, y, location.weight.toFloat(), stamp)
	}


	fun toByteArray(size: Int): ByteArray {
		val array = heatmap.renderDefaultTo()
		val bitmap = Bitmap.createBitmap(array, heatmapSize, heatmapSize, Bitmap.Config.ARGB_8888)
		val scaled = bitmap.scale(size, size, false)
		return scaled.toByteArray()
	}

	companion object {
		const val BASE_HEATMAP_SIZE = 64
		const val HEATMAP_STAMP_RADIUS = BASE_HEATMAP_SIZE / 16 + 1

		const val HEATMAP_STAMP_SIZE_AS_DOUBLE = HEATMAP_STAMP_RADIUS.toDouble()
		const val HEATMAP_SIZE_AS_DOUBLE = BASE_HEATMAP_SIZE.toDouble()
	}
}