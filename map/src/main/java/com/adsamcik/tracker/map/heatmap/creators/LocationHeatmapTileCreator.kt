package com.adsamcik.tracker.map.heatmap.creators

import android.content.Context
import com.adsamcik.tracker.R
import com.adsamcik.tracker.map.MapConstants
import com.adsamcik.tracker.map.heatmap.HeatmapColorScheme
import com.adsamcik.tracker.map.heatmap.HeatmapStamp
import com.adsamcik.tracker.map.heatmap.UserHeatmapData
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.map.v2.DevFlags
import com.adsamcik.tracker.map.v2.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import com.adsamcik.tracker.shared.map.MapLayerData
import com.adsamcik.tracker.shared.preferences.Preferences
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.pow

@Suppress("MagicNumber")
internal class LocationHeatmapTileCreator(context: Context, val layerData: MapLayerData) :
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
            { current, _, stampValue, weight ->
                current + stampValue * weight
            }) { current, stampValue, _ ->
            max(current, (stampValue * 255f).toInt())
        }
    }

    override fun generateStamp(heatmapSize: Int, zoom: Int, pixelInMeters: Float): HeatmapStamp {
        val baseMeterSize =
            BASE_HEAT_SIZE_IN_METERS * HEATMAP_ZOOM_SCALE.pow(MapConstants.MAX_ZOOM - zoom)
        return HeatmapStamp.generateNonlinear(ceil(baseMeterSize / pixelInMeters).toInt()) {
            it.pow(2f)
        }
    }

    private val dao = AppDatabase.database(context).locationDao()
    private val unifiedDao = AppDatabase.database(context).unifiedGeoDao()
    private val geoRepository: GeoRepository by lazy { GeoRepositoryImpl(unifiedDao) }

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

    override val getAllInsideAndBetween = if (DevFlags.USE_REPO_LOCATION_HEATMAP) { top@{ from: Long, to: Long, topLat: Double, rightLon: Double, bottomLat: Double, leftLon: Double ->
        // Use repository synchronous collection inside runBlocking (legacy interface requires List)
        runBlocking(Dispatchers.IO) {
            geoRepository.queryWeighted(
                GeoQuery(
                    source = GeoSource.LOCATION,
                    bounds = Bounds(topLat, rightLon, bottomLat, leftLon),
                    timeFrom = from,
                    timeTo = to,
                    weight = "hor_acc" // default weighting analogous to existing weight selection
                ),
                weightColumn = "hor_acc"
            ).first().map { w -> com.adsamcik.tracker.shared.base.database.data.location.TimeLocation2DWeighted(w.time, w.lon, w.lat, w.weight) }
        }
    } } else dao::getAllInsideAndBetween

    override val getAllInside = if (DevFlags.USE_REPO_LOCATION_HEATMAP) { top@{ topLat: Double, rightLon: Double, bottomLat: Double, leftLon: Double ->
        runBlocking(Dispatchers.IO) {
            geoRepository.queryWeighted(
                GeoQuery(
                    source = GeoSource.LOCATION,
                    bounds = Bounds(topLat, rightLon, bottomLat, leftLon),
                    weight = "hor_acc"
                ),
                weightColumn = "hor_acc"
            ).first().map { w -> com.adsamcik.tracker.shared.base.database.data.location.TimeLocation2DWeighted(w.time, w.lon, w.lat, w.weight) }
        }
    } } else dao::getAllInside

    companion object {
        private const val BASE_HEAT_SIZE_IN_METERS = 40f
        private const val HEATMAP_ZOOM_SCALE = 1.4f
    }
}

