package com.adsamcik.tracker.map.heatmap

import android.graphics.Bitmap
import androidx.core.graphics.scale
import com.adsamcik.tracker.map.MapFunctions
import com.adsamcik.tracker.map.heatmap.creators.HeatmapTileData
import com.adsamcik.tracker.map.heatmap.implementation.AgeWeightedHeatmap
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.database.data.location.TimeLocation2DWeighted
import com.adsamcik.tracker.shared.base.extension.toByteArray
import kotlin.math.roundToInt

@ExperimentalUnsignedTypes
internal class HeatmapTile(
		val data: HeatmapTileData
) {
	private val heatmap = AgeWeightedHeatmap(
			data.heatmapSize,
			data.heatmapSize,
			15 * Time.MINUTE_IN_SECONDS.toInt(),
			data.config.maxHeat,
			data.config.dynamicHeat
	)

	private val tileCount: Int = MapFunctions.getTileCount(data.zoom)

	var maxHeat: Float
		get() = heatmap.maxHeat
		set(value) {
			heatmap.maxHeat = value
		}

	fun addAll(list: List<TimeLocation2DWeighted>) {
		if (list.isEmpty()) return

		val minTime = requireNotNull(list.minBy { it.time }).time
		list.forEach { add(it, minTime) }
	}

	fun add(location: TimeLocation2DWeighted, minTime: Long) {
		val tx = MapFunctions.toTileX(location.longitude, tileCount)
		val ty = MapFunctions.toTileY(location.latitude, tileCount)
		val x = ((tx - data.x) * data.heatmapSize).roundToInt()
		val y = ((ty - data.y) * data.heatmapSize).roundToInt()

		val ageInSeconds = ((location.time - minTime) / Time.SECOND_IN_MILLISECONDS).toInt()

		heatmap.addPoint(
				x,
				y,
				ageInSeconds,
				location.normalizedWeight.toFloat(),
				data.stamp,
				data.config.weightMergeFunction,
				data.config.alphaMergeFunction
		)
	}


	fun toByteArray(bitmapSize: Int): ByteArray {
		val array = heatmap.renderSaturatedTo(data.config.colorScheme, heatmap.maxHeat) { it }
		val bitmap = Bitmap.createBitmap(
				array,
				data.heatmapSize,
				data.heatmapSize,
				Bitmap.Config.ARGB_8888
		)

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

