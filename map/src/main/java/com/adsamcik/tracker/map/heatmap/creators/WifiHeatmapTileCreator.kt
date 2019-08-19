package com.adsamcik.tracker.map.heatmap.creators

import android.content.Context
import com.adsamcik.tracker.common.database.AppDatabase
import com.adsamcik.tracker.common.style.ColorConstants
import com.adsamcik.tracker.map.heatmap.HeatmapColorScheme
import com.adsamcik.tracker.map.heatmap.HeatmapStamp
import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.max

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
				{ current, _, stampValue, weight ->
					current + stampValue * weight
				}) { current, stampValue, weight ->
			((current.toFloat() + stampValue * weight) / 2f).toInt().toUByte()
		}
	}

	override fun generateStamp(heatmapSize: Int, zoom: Int, pixelInMeters: Float): HeatmapStamp {
		return HeatmapStamp.generateNonlinear(ceil(APPROXIMATE_DISTANCE_IN_METERS / pixelInMeters).toInt()) {
			// very very simplified formula for signal loss
			(10 * LOSS_EXPONENT * log10(max(it * APPROXIMATE_DISTANCE_IN_METERS, 1f))) / 58.6273f
		}
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

	companion object {
		// , path loss can be represented by the path loss exponent, whose value is normally in the range of 2 to 4
		// (where 2 is for propagation in free space, 4 is for relatively lossy environments and for the case of
		// full specular reflection from the earth surface—the so-called flat earth model).
		private const val LOSS_EXPONENT = 3f

		private const val APPROXIMATE_DISTANCE_IN_METERS = 90f
	}
}
