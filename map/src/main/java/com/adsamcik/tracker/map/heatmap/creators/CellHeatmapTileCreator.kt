package com.adsamcik.tracker.map.heatmap.creators

import android.content.Context
import com.adsamcik.tracker.common.data.CellType
import com.adsamcik.tracker.common.database.AppDatabase
import com.adsamcik.tracker.common.style.ColorGenerator
import com.adsamcik.tracker.map.heatmap.HeatmapColorScheme
import com.adsamcik.tracker.map.heatmap.HeatmapStamp
import kotlin.math.max
import kotlin.math.pow

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

	//todo this should not be created anew on each tile
	override fun createHeatmapConfig(heatmapSize: Int, maxHeat: Float): HeatmapConfig {
		val cellTypeCount = CellType.values().size
		val colorMap = ColorGenerator.generateWithGolden(1.0, cellTypeCount)
		return HeatmapConfig(
				HeatmapColorScheme.fromArray(colorMap, 0),
				(cellTypeCount - 1).toFloat(),
				false,
				{ original, alphaValue, stampValue, weight ->
					if (alphaValue > stampValue) {
						max(original, weight)
					} else {
						original
					}
				}) { original, stampValue, _ ->
			val newAlpha = (stampValue * UByte.MAX_VALUE.toFloat()).toInt()
			max(original, newAlpha).toUByte()
		}
	}

	override fun generateStamp(heatmapSize: Int, zoom: Int, pixelInMeters: Float): HeatmapStamp {
		val radius = heatmapSize / 12 + 1
		return HeatmapStamp.generateNonlinear(radius) { it.pow(10) }
	}
}
