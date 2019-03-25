package com.adsamcik.signalcollector.map

import android.graphics.Bitmap
import androidx.core.graphics.scale
import com.adsamcik.signalcollector.database.data.DatabaseLocation
import com.adsamcik.signalcollector.extensions.toByteArray
import kotlin.math.roundToInt

class HeatmapTile(val x: Int, val y: Int, zoom: Int, maxHeat: Float = 0f, dynamicHeat: Boolean = maxHeat <= 0f) {
	val heatmap = Heatmap(HEATMAP_SIZE, HEATMAP_SIZE, maxHeat, dynamicHeat)

	val tileCount = MapFunctions.getTileCount(zoom)

	val maxHeat get() = heatmap.maxHeat

	fun addAll(list: List<DatabaseLocation>) {
		list.forEach { add(it) }
	}

	fun add(location: DatabaseLocation) {
		val tx = MapFunctions.toTileX(location.longitude, tileCount)
		val ty = MapFunctions.toTileY(location.latitude, tileCount)
		val x = ((tx - x) * HEATMAP_SIZE_AS_DOUBLE).roundToInt()
		val y = ((ty - y) * HEATMAP_SIZE_AS_DOUBLE).roundToInt()
		heatmap.addPoint(x, y)
	}


	fun toByteArray(size: Int): ByteArray {
		val array = heatmap.renderDefaultTo()
		val bitmap = Bitmap.createBitmap(array, HEATMAP_SIZE, HEATMAP_SIZE, Bitmap.Config.ARGB_8888)
		val scaled = bitmap.scale(size, size, false)
		return scaled.toByteArray()
	}

	companion object {
		const val HEATMAP_SIZE = 64
		const val HEATMAP_SIZE_AS_DOUBLE = HEATMAP_SIZE.toDouble()
	}
}