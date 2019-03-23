package com.adsamcik.signalcollector.map

import android.graphics.Bitmap
import androidx.core.graphics.scale
import ca.hss.heatmaplib.HeatMap
import com.adsamcik.signalcollector.database.data.DatabaseLocation
import com.adsamcik.signalcollector.extensions.toByteArray
import com.adsamcik.signalcollector.utility.CoordinateBounds
import kotlin.math.max
import kotlin.math.roundToInt

class HeatmapTile(val zoom: Int, val bounds: CoordinateBounds) {
	val grid: IntArray
	val heatmap: heatmap

	init {
		grid = IntArray(HEATMAP_SIZE * HEATMAP_SIZE)
	}

	fun initialize(list: List<DatabaseLocation>) {
		val width = bounds.width
		val height = bounds.height
		val lonRound = max(width / HEATMAP_SIZE, 5.0)
		val latRound = max(height / HEATMAP_SIZE, 5.0)

		list.groupBy { it.location.roundTo(lonRound, latRound) }.forEach {
			val x = ((it.key.longitude - bounds.left) / HEATMAP_SIZE).roundToInt()
			val y = ((it.key.latitude - bounds.bottom) / HEATMAP_SIZE).roundToInt()
			this[y, x] = it.value.size
		}

	}

	fun calculateSpreadMap() {

	}

	operator fun set(y: Int, x: Int, value: Int) {
		grid[x + y * HEATMAP_SIZE] = value
	}

	operator fun get(y: Int, x: Int) = grid[x + y * HEATMAP_SIZE]


	fun toByteArray(size: Int): ByteArray {
		val bitmap = Bitmap.createBitmap(heatmap, HEATMAP_SIZE, HEATMAP_SIZE, Bitmap.Config.ARGB_8888)
		val scaled = bitmap.scale(size, size, false)
		return scaled.toByteArray()
	}

	companion object {
		const val HEATMAP_SIZE = 16
	}
}