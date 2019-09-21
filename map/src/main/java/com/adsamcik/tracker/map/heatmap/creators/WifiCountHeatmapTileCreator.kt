package com.adsamcik.tracker.map.heatmap.creators

import android.content.Context
import com.adsamcik.tracker.common.database.AppDatabase
import com.adsamcik.tracker.commonmap.MapLayerData
import com.adsamcik.tracker.map.heatmap.HeatmapColorScheme
import com.adsamcik.tracker.map.heatmap.HeatmapStamp
import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

@Suppress("MagicNumber")
internal class WifiCountHeatmapTileCreator(context: Context, val layerData: MapLayerData) :
		HeatmapTileCreator {
	override fun createHeatmapConfig(heatmapSize: Int, maxHeat: Float): HeatmapConfig {
		val colorList = layerData.colorList
		val colorListSize = colorList.size.toDouble()
		val heatmapColors = layerData.colorList.mapIndexed { index, color ->
			index / colorListSize to color
		}

		return HeatmapConfig(
				HeatmapColorScheme.fromArray(heatmapColors, 100),
				60f,
				false,
				{ current, _, stampValue, weight ->
					current + stampValue * weight
				}) { current, stampValue, weight ->
			((current.toFloat() + stampValue * weight) / 2f).toInt().toUByte()
		}
	}

	override fun generateStamp(heatmapSize: Int, zoom: Int, pixelInMeters: Float): HeatmapStamp {
		return HeatmapStamp.generateNonlinear(
				ceil(APPROXIMATE_DISTANCE_IN_METERS / (pixelInMeters / VISUAL_SCALE)).toInt()
		) {
			// very very simplified formula for signal loss, but it looks nice
			(10 * LOSS_EXPONENT * log10(max(it * APPROXIMATE_DISTANCE_IN_METERS, 1f))) / NORMALIZER
		}
	}

	private val dao = AppDatabase.database(context).wifiLocationCountDao()

	override val availableRange: LongRange
		get() {
			val range = dao.range()
			return LongRange(range.start, range.endInclusive)
		}

	override val weightNormalizationValue: Double = 0.0

	override val getAllInsideAndBetween get() = dao::getAllInsideAndBetween
	override val getAllInside get() = dao::getAllInside

	companion object {
		private const val NORMALIZER = 58.6273f
		private const val LOSS_EXPONENT = 3f
		private const val APPROXIMATE_DISTANCE_IN_METERS = 90f
		private const val VISUAL_SCALE = 2.5f
	}
}
