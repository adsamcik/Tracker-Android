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
import kotlin.math.max
import kotlin.math.pow

@Suppress("MagicNumber")
internal class SpeedHeatmapTileCreator(context: Context, val layerData: MapLayerData) :
    HeatmapTileCreator {
    override fun createHeatmapConfig(dataUser: UserHeatmapData): HeatmapConfig {
        val colorList = layerData.colorList
        val colorListSize = colorList.size.toDouble()
        val heatmapColors = layerData.colorList.mapIndexed { index, color ->
            index / colorListSize to color
        }
        return HeatmapConfig(
            HeatmapColorScheme.fromArray(heatmapColors, 100),
            dataUser.maxHeat,
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

    companion object {
        private const val BASE_HEAT_SIZE_IN_METERS = 40f
        private const val HEATMAP_ZOOM_SCALE = 1.4f
    }
}

