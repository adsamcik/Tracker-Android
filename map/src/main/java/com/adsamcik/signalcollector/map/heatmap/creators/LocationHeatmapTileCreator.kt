package com.adsamcik.signalcollector.map.heatmap.creators

import android.content.Context
import android.graphics.Color
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.common.database.AppDatabase
import com.adsamcik.signalcollector.common.database.data.Database2DLocationWeightedMinimal
import com.adsamcik.signalcollector.common.preference.Preferences
import com.adsamcik.signalcollector.map.heatmap.HeatmapColorScheme
import com.adsamcik.signalcollector.map.heatmap.HeatmapStamp
import kotlin.math.pow

internal class LocationHeatmapTileCreator(context: Context) : HeatmapTileCreator {
	override fun createHeatmapConfig(heatmapSize: Int, maxHeat: Float): HeatmapConfig {
		return HeatmapConfig(generateStamp(heatmapSize),
				HeatmapColorScheme.fromArray(
						listOf(Pair(0.1, Color.TRANSPARENT), Pair(0.3, Color.BLUE), Pair(0.7, Color.YELLOW),
								Pair(1.0, Color.RED)), 100),
				maxHeat,
				false) { current, input, weight ->
			current + input * weight
		}
	}

	override fun generateStamp(heatmapSize: Int): HeatmapStamp {
		val radius = heatmapSize / 16 + 1
		return HeatmapStamp.generateNonlinear(radius) { it.pow(2f) }
	}

	private val dao = AppDatabase.getDatabase(context).locationDao()

	override val availableRange: LongRange
		get() {
			val range = dao.range()
			return LongRange(range.start, range.endInclusive)
		}

	override val weightNormalizationValue: Double = Preferences
			.getPref(context)
			.getIntRes(R.string.settings_tracking_required_accuracy_key,
					R.integer.settings_tracking_required_accuracy_default)
			.toDouble()

	override val getAllInsideAndBetween: (from: Long, to: Long, topLatitude: Double, rightLongitude: Double, bottomLatitude: Double, leftLongitude: Double) -> List<Database2DLocationWeightedMinimal> = dao::getAllInsideAndBetween

	override val getAllInside: (topLatitude: Double, rightLongitude: Double, bottomLatitude: Double, leftLongitude: Double) -> List<Database2DLocationWeightedMinimal> = dao::getAllInside
}
