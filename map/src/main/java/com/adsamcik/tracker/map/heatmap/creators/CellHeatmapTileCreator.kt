package com.adsamcik.tracker.map.heatmap.creators

import android.content.Context
import com.adsamcik.tracker.map.heatmap.HeatmapColorScheme
import com.adsamcik.tracker.map.heatmap.HeatmapStamp
import com.adsamcik.tracker.map.heatmap.UserHeatmapData
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.map.MapLayerData
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.pow

internal class CellHeatmapTileCreator(
		context: Context,
		val layerData: MapLayerData
) : HeatmapTileCreator {
	private val dao = AppDatabase.database(context).cellLocationDao()

	override val weightNormalizationValue: Double = 0.0

	override val availableRange: LongRange
		get() {
			val range = dao.range()
			return LongRange(range.start, range.endInclusive)
		}

	override val getAllInsideAndBetween get() = dao::getAllInsideAndBetween
	override val getAllInside get() = dao::getAllInside

	override fun createHeatmapConfig(dataUser: UserHeatmapData): HeatmapConfig {
		val colorMap = layerData.colorList
		return HeatmapConfig(
				HeatmapColorScheme.fromArray(colorMap, 0),
				(colorMap.size - 1).toFloat(),
				false,
				dataUser.ageThreshold,
				{ original, _, _, weight ->
					max(weight, original)
				}) { original, stampValue, _ ->
			val newAlpha = (stampValue * UByte.MAX_VALUE.toFloat()).toInt()
			max(original, newAlpha)
		}
	}

	override fun generateStamp(heatmapSize: Int, zoom: Int, pixelInMeters: Float): HeatmapStamp {
		val radius = ceil(APPROXIMATE_SIZE_IN_METERS / pixelInMeters).toInt()
		return HeatmapStamp.generateNonlinear(radius) { it.pow(FALLOFF_EXPONENT) }
	}

	companion object {
		private const val APPROXIMATE_SIZE_IN_METERS = 50f
		private const val FALLOFF_EXPONENT = 4
	}
}
