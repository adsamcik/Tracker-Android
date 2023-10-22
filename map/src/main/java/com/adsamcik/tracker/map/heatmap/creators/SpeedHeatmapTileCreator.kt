package com.adsamcik.tracker.map.heatmap.creators

import android.content.Context
import com.adsamcik.tracker.R
import com.adsamcik.tracker.map.MapController
import com.adsamcik.tracker.map.heatmap.HeatmapColorScheme
import com.adsamcik.tracker.map.heatmap.HeatmapStamp
import com.adsamcik.tracker.map.heatmap.UserHeatmapData
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.map.MapLayerData
import com.adsamcik.tracker.shared.preferences.Preferences
import kotlin.math.ceil
import kotlin.math.ln1p
import kotlin.math.max
import kotlin.math.pow

@Suppress("MagicNumber")
internal class SpeedHeatmapTileCreator(context: Context, val layerData: MapLayerData) :
    HeatmapTileCreator {
    override fun createHeatmapConfig(dataUser: UserHeatmapData): HeatmapConfig {
        val speedRanges = SpeedCategory.values().map { it.range }
        val heatmapColors = layerData.colorList.mapIndexed { index, color ->
            val speedRange = speedRanges[index]
            val normalizedSpeed =
                (speedRange.start + speedRange.endInclusive) / 2 / dataUser.maxHeat
            normalizedSpeed to color
        }
        return HeatmapConfig(
            HeatmapColorScheme.fromArray(heatmapColors, 100),
            dataUser.maxHeat,
            false,
            dataUser.ageThreshold,
            weightMergeFunction = { original, _, _, new ->
                // Using a logarithmic function to balance the influence of high and low weights
                (original + ln1p(new.toDouble()).toFloat()).coerceAtMost(dataUser.maxHeat)
            },
            alphaMergeFunction = { original, newAlpha, weight ->
                // Adjusting alpha based on weight, giving more prominence to higher weights
                val adjustedAlpha = (newAlpha * weight / dataUser.maxHeat).toInt()
                max(original, adjustedAlpha).coerceIn(0, 255)
            })
    }

    override fun generateStamp(heatmapSize: Int, zoom: Int, pixelInMeters: Float): HeatmapStamp {
        val baseMeterSize =
            BASE_HEAT_SIZE_IN_METERS * HEATMAP_ZOOM_SCALE.pow(MapController.MAX_ZOOM - zoom)
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

    override val weightNormalizationValue: Double = 0.0

    override val getAllInsideAndBetween = dao::getAllInsideAndBetweenSpeed

    override val getAllInside = dao::getAllInside


    private enum class SpeedCategory(val range: ClosedFloatingPointRange<Double>) {
        VERY_SLOW(0.0..0.5),
        WALKING(0.5..1.5),
        RUNNING(1.5..3.0),
        BIKE(3.0..7.0),
        PUBLIC_TRANSPORT(7.0..15.0),
        CAR(15.0..30.0),
        VERY_HIGH_SPEED(30.0..Double.MAX_VALUE)
    }

    companion object {
        private const val BASE_HEAT_SIZE_IN_METERS = 40f
        private const val HEATMAP_ZOOM_SCALE = 1.4f
    }
}

