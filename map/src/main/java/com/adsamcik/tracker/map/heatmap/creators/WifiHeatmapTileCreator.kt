package com.adsamcik.tracker.map.heatmap.creators

import android.content.Context
import com.adsamcik.tracker.common.database.AppDatabase
import com.adsamcik.tracker.common.database.data.Database2DLocationWeightedMinimal
import com.adsamcik.tracker.common.style.ColorConstants
import com.adsamcik.tracker.map.heatmap.HeatmapColorScheme
import com.adsamcik.tracker.map.heatmap.HeatmapStamp
import kotlin.math.pow

internal class WifiHeatmapTileCreator(context: Context) : HeatmapTileCreator {
	override fun createHeatmapConfig(heatmapSize: Int, maxHeat: Float): HeatmapConfig {
		return HeatmapConfig(generateStamp(heatmapSize),
				HeatmapColorScheme.fromArray(listOf(Pair(0.05, ColorConstants.TRANSPARENT),
						Pair(0.2, ColorConstants.GREEN),
						Pair(0.8, ColorConstants.ORANGE),
						Pair(1.0, ColorConstants.RED)), 100),
				20f,
				false) { current, input, weight ->
			current + input * weight
		}
	}

	override fun generateStamp(heatmapSize: Int): HeatmapStamp {
		val radius = heatmapSize / 16 + 1
		return HeatmapStamp.generateNonlinear(radius) { it.pow(2f) }
	}

	private val dao = AppDatabase.getDatabase(context).wifiDao()

	override val availableRange: LongRange
		get() {
			val range = dao.range()
			return LongRange(range.start, range.endInclusive)
		}

	override val weightNormalizationValue: Double = 0.0

	override val getAllInsideAndBetween: (from: Long, to: Long, topLatitude: Double, rightLongitude: Double, bottomLatitude: Double, leftLongitude: Double) -> List<Database2DLocationWeightedMinimal>
		get() = dao::getAllInsideAndBetween
	override val getAllInside: (topLatitude: Double, rightLongitude: Double, bottomLatitude: Double, leftLongitude: Double) -> List<Database2DLocationWeightedMinimal>
		get() = dao::getAllInside
}
