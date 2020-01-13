package com.adsamcik.tracker.map.heatmap.creators

import android.content.Context
import com.adsamcik.tracker.R
import com.adsamcik.tracker.shared.base.database.AppDatabase

import com.adsamcik.tracker.shared.map.MapLayerData
import com.adsamcik.tracker.map.MapController
import com.adsamcik.tracker.map.heatmap.HeatmapColorScheme
import com.adsamcik.tracker.map.heatmap.HeatmapStamp
import com.adsamcik.tracker.shared.preferences.Preferences
import kotlin.math.ceil
import kotlin.math.pow

@Suppress("MagicNumber")
internal class LocationHeatmapTileCreator(context: Context, val layerData: MapLayerData) :
		HeatmapTileCreator {
	override fun createHeatmapConfig(heatmapSize: Int, maxHeat: Float): HeatmapConfig {
		val colorList = layerData.colorList
		val colorListSize = colorList.size.toDouble()
		val heatmapColors = layerData.colorList.mapIndexed { index, color ->
			index / colorListSize to color
		}
		return HeatmapConfig(
				HeatmapColorScheme.fromArray(heatmapColors, 100),
				maxHeat,
				false,
				{ current, _, stampValue, weight ->
					current + stampValue * weight
				}) { current, stampValue, weight ->
			((current.toFloat() + stampValue * weight) / 2f).toInt().toUByte()
		}
	}

	override fun generateStamp(heatmapSize: Int, zoom: Int, pixelInMeters: Float): HeatmapStamp {
		val baseMeterSize = BASE_HEAT_SIZE_IN_METERS * HEATMAP_ZOOM_SCALE.pow(MapController.MAX_ZOOM - zoom)
		return HeatmapStamp.generateNonlinear(ceil(baseMeterSize / pixelInMeters).toInt()) {
			it.pow(2f)
		}
	}

	private val dao = AppDatabase.database(context).locationDao()

	override val availableRange: LongRange
		get() {
			val range = dao.range()
			return LongRange(range.start, range.endInclusive)
		}

	override val weightNormalizationValue: Double = Preferences
			.getPref(context)
			.getIntRes(
					R.string.settings_tracking_required_accuracy_key,
					R.integer.settings_tracking_required_accuracy_default
			)
			.toDouble()

	override val getAllInsideAndBetween = dao::getAllInsideAndBetween

	override val getAllInside = dao::getAllInside

	companion object {
		private const val BASE_HEAT_SIZE_IN_METERS = 20f
		private const val HEATMAP_ZOOM_SCALE = 1.4f
	}
}

