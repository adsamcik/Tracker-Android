package com.adsamcik.tracker.map.heatmap.creators

import android.content.Context
import com.adsamcik.tracker.common.data.CellType
import com.adsamcik.tracker.common.database.AppDatabase
import com.adsamcik.tracker.common.style.ColorConstants
import com.adsamcik.tracker.common.style.ColorGenerator
import com.adsamcik.tracker.map.heatmap.HeatmapColorScheme
import com.adsamcik.tracker.map.heatmap.HeatmapStamp
import kotlin.math.max

@Suppress("MagicNumber")
internal class CellHeatmapTileCreator(context: Context) : HeatmapTileCreator {
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
		val cellTypeCount = CellType.values().size
		val colorMap = mutableListOf(ColorConstants.TRANSPARENT)
		colorMap.addAll(ColorGenerator.generateWithGolden(1.0, cellTypeCount))
		return HeatmapConfig(
				HeatmapColorScheme.fromArray(colorMap, 0),
				(cellTypeCount - 1).toFloat(),
				false)
		{ original, _, weight ->
			max(original, weight)
		}
	}

	override fun generateStamp(heatmapSize: Int): HeatmapStamp {
		val radius = heatmapSize / 16 + 1
		return HeatmapStamp.generateNonlinear(radius) { 1f }
	}
}
