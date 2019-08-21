package com.adsamcik.tracker.map.heatmap.creators

import android.content.Context
import com.adsamcik.tracker.common.data.CellType
import com.adsamcik.tracker.common.database.AppDatabase
import com.adsamcik.tracker.common.style.ColorGenerator
import com.adsamcik.tracker.commonmap.MapLayerData
import com.adsamcik.tracker.map.heatmap.HeatmapColorScheme
import com.adsamcik.tracker.map.heatmap.HeatmapStamp
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.pow

internal class CellHeatmapTileCreator(context: Context, val data: MapLayerData) : HeatmapTileCreator {
	private val dao = AppDatabase.getDatabase(context).cellLocationDao()

	override val weightNormalizationValue: Double = 0.0

	override val availableRange: LongRange
		get() {
			val range = dao.range()
			return LongRange(range.start, range.endInclusive)
		}

	override val getAllInsideAndBetween get() = dao::getAllInsideAndBetween
	override val getAllInside get() = dao::getAllInside

	override fun createHeatmapConfig(heatmapSize: Int, maxHeat: Float): HeatmapConfig {
		val colorMap = data.colorList
		return HeatmapConfig(
				HeatmapColorScheme.fromArray(colorMap, 0),
				(colorMap.size - 1).toFloat(),
				false,
				{ original, _, _, weight ->
					max(weight, original)
				}) { original, stampValue, _ ->
			val newAlpha = (stampValue * UByte.MAX_VALUE.toFloat()).toInt()
			max(original, newAlpha).toUByte()
		}
	}

	override fun generateStamp(heatmapSize: Int, zoom: Int, pixelInMeters: Float): HeatmapStamp {
		val radius = ceil(APPROXIMATE_DISTANCE_IN_METERS / pixelInMeters).toInt()
		return HeatmapStamp.generateNonlinear(radius) { it.pow(FALLOFF_EXPONENT) }
	}

	companion object {
		private const val APPROXIMATE_DISTANCE_IN_METERS = 90f
		private const val FALLOFF_EXPONENT = 6
	}
}
