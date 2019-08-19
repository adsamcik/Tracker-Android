package com.adsamcik.tracker.map.heatmap.creators

import android.content.Context
import com.adsamcik.tracker.common.database.AppDatabase
import com.adsamcik.tracker.common.style.ColorConstants
import com.adsamcik.tracker.map.heatmap.HeatmapColorScheme
import com.adsamcik.tracker.map.heatmap.HeatmapStamp
import kotlin.math.pow

@Suppress("MagicNumber")
internal class WifiHeatmapTileCreator(context: Context) : HeatmapTileCreator {
	override fun createHeatmapConfig(heatmapSize: Int, maxHeat: Float): HeatmapConfig {
		return HeatmapConfig(
				HeatmapColorScheme.fromArray(listOf(
						Pair(0.0, ColorConstants.TRANSPARENT),
						Pair(0.2, ColorConstants.GREEN),
						Pair(0.8, ColorConstants.ORANGE),
						Pair(1.0, ColorConstants.RED)), 100),
				20f,
				false,
				{ current, stampValue, weight ->
					current + stampValue * weight
				}) { current, stampValue, weight ->
			((current.toFloat() + stampValue * weight) / 2f).toInt().toUByte()
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

	override val getAllInsideAndBetween get() = dao::getAllInsideAndBetween
	override val getAllInside get() = dao::getAllInside
}
