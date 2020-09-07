package com.adsamcik.tracker.map.heatmap.creators

import android.content.Context
import com.adsamcik.tracker.map.heatmap.HeatmapColorScheme
import com.adsamcik.tracker.map.heatmap.HeatmapStamp
import com.adsamcik.tracker.map.heatmap.UserHeatmapData
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.map.MapLayerData
import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.max

@Suppress("MagicNumber")
internal class WifiCountHeatmapTileCreator(context: Context, val layerData: MapLayerData) :
		HeatmapTileCreator {
	override fun createHeatmapConfig(dataUser: UserHeatmapData): HeatmapConfig {
		val colorList = layerData.colorList
		val colorListSize = colorList.size.toDouble()
		val heatmapColors = layerData.colorList.mapIndexed { index, color ->
			index / colorListSize to color
		}

		return HeatmapConfig(
				HeatmapColorScheme.fromArray(heatmapColors, 100),
				MAX_WIFI_HEAT,
				false,
				dataUser.ageThreshold,
				{ current, _, stampValue, weight ->
					current + stampValue * weight
				}) { current, stampValue, weight ->
			((current.toFloat() + stampValue * weight) / 2f).toInt()
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

}
