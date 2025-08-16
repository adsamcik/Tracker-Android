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
			data.config.ageThreshold,
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

		val sortedList = list.sortedBy { it.time }
		val minTime = sortedList.first().time
		sortedList.forEach { add(it, minTime) }
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


	fun toByteArray(bitmapSize: Int, bitmapPool: com.adsamcik.tracker.map.graphics.BitmapPool? = null): ByteArray {
		val array = heatmap.renderSaturated(data.config.colorScheme, heatmap.maxHeat) { it }
		val base = bitmapPool?.acquire(data.heatmapSize, data.heatmapSize) ?: Bitmap.createBitmap(
				array,
				data.heatmapSize,
				data.heatmapSize,
				Bitmap.Config.ARGB_8888
		)
		if (base !== null && base.isMutable && base.width == data.heatmapSize && base.height == data.heatmapSize) {
			// If acquired empty bitmap, copy pixels in; else we already created with pixels
			if (base.getPixel(0,0) == 0) {
				base.setPixels(array, 0, data.heatmapSize, 0, 0, data.heatmapSize, data.heatmapSize)
			}
		}
		val resultBytes = if (data.heatmapSize != bitmapSize) {
			base.scale(bitmapSize, bitmapSize, false).toByteArray()
		} else {
			base.toByteArray()
		}
		bitmapPool?.release(base)
		return resultBytes
	}

	companion object {
		const val BASE_HEATMAP_SIZE: Int = 128
	}
}

