package com.adsamcik.tracker.map.layers.registry

import android.graphics.Color
import com.adsamcik.tracker.map.R
import com.adsamcik.tracker.map.data.GeoRepository
import com.adsamcik.tracker.map.data.GeoRepositoryImpl
import com.adsamcik.tracker.map.layers.base.BaseMapLayer
import com.adsamcik.tracker.map.layers.impl.CellHeatmapLayer
import com.adsamcik.tracker.map.layers.impl.HeatmapColorRamps
import com.adsamcik.tracker.map.layers.impl.LegacyHeatmapLayer
import com.adsamcik.tracker.map.layers.impl.LocationHeatmapLayer
import com.adsamcik.tracker.map.layers.impl.LocationPathLayer
import com.adsamcik.tracker.map.layers.impl.SpeedHeatmapLayer
import com.adsamcik.tracker.map.layers.impl.VehicleComplianceLayer
import com.adsamcik.tracker.map.layers.impl.WifiCountHeatmapLayer
import com.adsamcik.tracker.map.layers.impl.WifiHeatmapLayer
import com.adsamcik.tracker.map.perf.PerformanceManager
import com.adsamcik.tracker.map.presentation.bridge.MapLibreLayerConfig
import com.adsamcik.tracker.map.presentation.udf.LatLngModel
import com.adsamcik.tracker.map.ui.LayerEntry
import com.adsamcik.tracker.shared.base.data.SessionActivityIds
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.UnifiedGeoDao
import com.adsamcik.tracker.stats.api.roadmatch.RoadObservation
import com.adsamcik.tracker.map.shared.MapLayerData
import com.adsamcik.tracker.map.shared.MapLayerInfo
import com.adsamcik.tracker.map.shared.MapLegend
import com.adsamcik.tracker.map.shared.MapLegendValue
import com.adsamcik.tracker.map.shared.layers.LayerCapabilities
import com.adsamcik.tracker.map.shared.layers.LayerDescriptor
import com.adsamcik.tracker.map.shared.layers.LayerFactory
import com.adsamcik.tracker.map.shared.layers.LayerRecipe
import com.adsamcik.tracker.shared.base.concurrency.DefaultDispatchersProvider
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import kotlinx.coroutines.withContext

/** Registry for map layers: registers layers directly. */
class DefaultLayerRegistry(
    private val dispatchers: DispatchersProvider = DefaultDispatchersProvider,
) : LayerRegistry {

    private companion object {
        const val LOCATION_PATH_CHUNK_SIZE = 2_000
        const val LOCATION_PATH_MAX_PRE_POINTS = 30_000
        const val DEFAULT_LOCATION_RANGE_MS = 30L * 24 * 60 * 60 * 1_000
        const val VEHICLE_CHUNK_SIZE = 2_000
        const val VEHICLE_MAX_PRE_SAMPLES = 30_000

        /** Conversion factor from km/h to m/s for matched road speed limits. */
        const val VEHICLE_MPS_PER_KMH = 1000f / 3600f

        /**
         * Emission sigma (GPS noise) handed to the road matcher. The driving
         * sample projection doesn't carry per-sample accuracy, so we use a
         * single sensible default that the matcher clamps into its valid range.
         */
        const val VEHICLE_DEFAULT_ACCURACY_M = 8f
    }

    /**
     * No-op layer placeholder.
     */
    private class NoMapLayer : BaseMapLayer<Unit, Unit>() {
        override suspend fun loadData(context: android.content.Context, bounds: com.adsamcik.tracker.map.data.Bounds?) = Unit
        override fun processData(input: Unit, budgets: PerformanceManager.PerformanceBudgets) = Unit
        override fun produceConfig(processed: Unit): MapLibreLayerConfig? = null
    }

    private fun locationDensityLegendValues(): List<MapLegendValue> {
        val colors = HeatmapColorRamps.LocationDensity.drop(1).map { it.second }
        return listOf(
            MapLegendValue(R.string.map_layer_location_heatmap_low, colors[0]),
            MapLegendValue(R.string.map_layer_location_heatmap_low_medium, colors[1]),
            MapLegendValue(R.string.map_layer_location_heatmap_medium, colors[2]),
            MapLegendValue(R.string.map_layer_location_heatmap_high, colors[3]),
            MapLegendValue(R.string.map_layer_location_heatmap_peak, colors[4]),
        )
    }

    private fun cellSignalLegendValues(): List<MapLegendValue> {
        val colors = HeatmapColorRamps.CellSignal.map { it.second }
        return listOf(
            MapLegendValue(R.string.map_layer_cell_signal_weak, colors[0]),
            MapLegendValue(R.string.map_layer_cell_signal_fair, colors[1]),
            MapLegendValue(R.string.map_layer_cell_signal_good, colors[2]),
            MapLegendValue(R.string.map_layer_cell_signal_strong, colors[3]),
            MapLegendValue(R.string.map_layer_cell_signal_excellent, colors[4]),
        )
    }

    private fun buildLayers(): List<LayerDescriptor> = buildList {
        // No layer (legend only; acts as a placeholder)
        add(
            LayerDescriptor(
                id = "none",
                titleRes = R.string.map_layer_none_title,
                iconRes = null,
                capabilities = LayerCapabilities(),
                recipe = LayerRecipe(factory = LayerFactory {
                    LayerEntry(
                        build = { _ -> NoMapLayer() },
                        legend = MapLayerData(
                            info = MapLayerInfo("NoMapLayer", R.string.map_layer_none_title),
                            colorList = emptyList(),
                            legend = MapLegend()
                        )
                    )
                })
            )
        )

        // Location Heatmap
        add(
            LayerDescriptor(
                id = "location_heatmap",
                titleRes = R.string.map_layer_location_heatmap_title,
                chipLabelRes = R.string.map_layer_location_heatmap_chip,
                iconRes = null,
                capabilities = LayerCapabilities(isHeatmap = true),
                recipe = LayerRecipe(factory = LayerFactory {
                    LayerEntry(
                        build = { ctx ->
                            val dao: UnifiedGeoDao = AppDatabase.database(ctx).unifiedGeoDao()
                            val repo: GeoRepository = GeoRepositoryImpl(dao)
                            LocationHeatmapLayer(repo, PerformanceManager())
                        },
                        legend = MapLayerData(
                            info = MapLayerInfo("LocationHeatmapLayer", R.string.map_layer_location_heatmap_title),
                            colorList = HeatmapColorRamps.LocationDensity.drop(1).map { it.second },
                            legend = MapLegend(
                                description = R.string.map_layer_location_heatmap_description,
                                valueList = locationDensityLegendValues()
                            )
                        )
                    )
                })
            )
        )

        // Cell Heatmap
        add(
            LayerDescriptor(
                id = "cell_heatmap",
                titleRes = R.string.map_layer_cell_heatmap_title,
                iconRes = null,
                capabilities = LayerCapabilities(isHeatmap = true),
                recipe = LayerRecipe(factory = LayerFactory {
                    LayerEntry(
                        build = { ctx ->
                            val dao: UnifiedGeoDao = AppDatabase.database(ctx).unifiedGeoDao()
                            val repo: GeoRepository = GeoRepositoryImpl(dao)
                            CellHeatmapLayer(repo, PerformanceManager())
                        },
                        legend = MapLayerData(
                            info = MapLayerInfo("CellHeatmapLayer", R.string.map_layer_cell_heatmap_title),
                            colorList = HeatmapColorRamps.CellSignal.map { it.second },
                            legend = MapLegend(
                                description = R.string.map_layer_cell_heatmap_description,
                                valueList = cellSignalLegendValues()
                            )
                        )
                    )
                })
            )
        )

        // Wifi Heatmap
        add(
            LayerDescriptor(
                id = "wifi_heatmap",
                titleRes = R.string.map_layer_wifi_heatmap_title,
                iconRes = null,
                capabilities = LayerCapabilities(isHeatmap = true),
                recipe = LayerRecipe(factory = LayerFactory {
                    LayerEntry(
                        build = { ctx ->
                            val dao: UnifiedGeoDao = AppDatabase.database(ctx).unifiedGeoDao()
                            val repo: GeoRepository = GeoRepositoryImpl(dao)
                            WifiHeatmapLayer(repo, PerformanceManager())
                        },
                        legend = MapLayerData(
                            info = MapLayerInfo("WifiHeatmapLayer", R.string.map_layer_wifi_heatmap_title),
                            colorList = listOf(
                                Color.rgb(255, 183, 77),
                                Color.rgb(255, 152, 0),
                                Color.rgb(255, 112, 67),
                                Color.rgb(213, 0, 0)
                            ),
                            legend = MapLegend(
                                description = R.string.map_layer_wifi_heatmap_description,
                                valueList = listOf(
                                    MapLegendValue(R.string.map_layer_wifi_heatmap_low, Color.rgb(255, 183, 77)),
                                    MapLegendValue(R.string.map_layer_wifi_heatmap_medium, Color.rgb(255, 152, 0)),
                                    MapLegendValue(R.string.map_layer_wifi_heatmap_high, Color.rgb(213, 0, 0))
                                )
                            )
                        )
                    )
                })
            )
        )

        // Wifi Count Heatmap
        add(
            LayerDescriptor(
                id = "wifi_count_heatmap",
                titleRes = R.string.map_layer_wifi_count_heatmap_title,
                chipLabelRes = R.string.map_layer_wifi_count_heatmap_chip,
                iconRes = null,
                capabilities = LayerCapabilities(isHeatmap = true),
                recipe = LayerRecipe(factory = LayerFactory {
                    LayerEntry(
                        build = { ctx ->
                            val dao: UnifiedGeoDao = AppDatabase.database(ctx).unifiedGeoDao()
                            val repo: GeoRepository = GeoRepositoryImpl(dao)
                            WifiCountHeatmapLayer(repo, PerformanceManager())
                        },
                        legend = MapLayerData(
                            info = MapLayerInfo("WifiCountHeatmapLayer", R.string.map_layer_wifi_count_heatmap_title),
                            colorList = listOf(
                                Color.rgb(31, 200, 255),
                                Color.rgb(63, 81, 181),
                                Color.rgb(106, 27, 154)
                            ),
                            legend = MapLegend(
                                description = R.string.map_layer_wifi_count_heatmap_description,
                                valueList = listOf(
                                    MapLegendValue(R.string.map_layer_wifi_heatmap_low, Color.rgb(31, 200, 255)),
                                    MapLegendValue(R.string.map_layer_wifi_heatmap_medium, Color.rgb(63, 81, 181)),
                                    MapLegendValue(R.string.map_layer_wifi_heatmap_high, Color.rgb(106, 27, 154))
                                )
                            )
                        )
                    )
                })
            )
        )

        // Speed Heatmap
        add(
            LayerDescriptor(
                id = "speed_heatmap",
                titleRes = R.string.map_layer_speed_heatmap_title,
                iconRes = null,
                capabilities = LayerCapabilities(isHeatmap = true),
                recipe = LayerRecipe(factory = LayerFactory {
                    LayerEntry(
                        build = { ctx ->
                            val dao: UnifiedGeoDao = AppDatabase.database(ctx).unifiedGeoDao()
                            val repo: GeoRepository = GeoRepositoryImpl(dao)
                            SpeedHeatmapLayer(repo, PerformanceManager())
                        },
                        legend = MapLayerData(
                            info = MapLayerInfo("SpeedHeatmapLayer", R.string.map_layer_speed_heatmap_title),
                            colorList = HeatmapColorRamps.Speed.map { it.second },
                            legend = MapLegend(
                                description = R.string.map_layer_speed_heatmap_description,
                                valueList = listOf(
                                    MapLegendValue(R.string.map_layer_speed_very_slow, HeatmapColorRamps.Speed[0].second),
                                    MapLegendValue(R.string.map_layer_speed_walking, HeatmapColorRamps.Speed[1].second),
                                    MapLegendValue(R.string.map_layer_speed_running, HeatmapColorRamps.Speed[2].second),
                                    MapLegendValue(R.string.map_layer_speed_moderate, HeatmapColorRamps.Speed[3].second),
                                    MapLegendValue(R.string.map_layer_speed_fast, HeatmapColorRamps.Speed[4].second),
                                    MapLegendValue(R.string.map_layer_speed_very_fast, HeatmapColorRamps.Speed[5].second)
                                )
                            )
                        )
                    )
                })
            )
        )

        // Vehicle Speed Compliance
        add(
            LayerDescriptor(
                id = "vehicle_compliance",
                titleRes = R.string.map_layer_vehicle_compliance_title,
                chipLabelRes = R.string.map_layer_vehicle_compliance_chip,
                iconRes = null,
                capabilities = LayerCapabilities(isPolyline = true),
                recipe = LayerRecipe(factory = LayerFactory {
                    LayerEntry(
                        build = { ctx ->
                            val dao = AppDatabase.database(ctx).locationSampleDao()
                            val drivingActivityIds = SessionActivityIds.DRIVING.toList()
                            val roadMatcher = ctx.roadMatcher()
                            VehicleComplianceLayer(
                                edgeProvider = { range ->
                                    withContext(dispatchers.io) {
                                        val now = System.currentTimeMillis()
                                        val fromMs = if (!range.isEmpty()) {
                                            range.first
                                        } else {
                                            now - DEFAULT_LOCATION_RANGE_MS
                                        }
                                        val toMs = if (!range.isEmpty()) range.last else now
                                        val rows = mutableListOf<com.adsamcik.tracker.shared.base.database.dao.VehicleSpeedSampleRow>()
                                        var afterTimeMs: Long? = null
                                        var afterId: Long? = null

                                        while (true) {
                                            val chunk = dao.getDrivingChunkBetweenOrdered(
                                                fromMs = fromMs,
                                                toMs = toMs,
                                                drivingActivities = drivingActivityIds,
                                                afterTimeMs = afterTimeMs,
                                                afterId = afterId,
                                                limit = VEHICLE_CHUNK_SIZE,
                                            )
                                            if (chunk.isEmpty()) break

                                            rows.addAll(chunk)

                                            val lastRow = chunk.last()
                                            afterTimeMs = lastRow.timeMs
                                            afterId = lastRow.id
                                            if (chunk.size < VEHICLE_CHUNK_SIZE) break
                                        }
                                        if (rows.isEmpty()) {
                                            emptyList()
                                        } else {
                                            // Decimate to keep the matcher's work bounded, then map-match
                                            // the drive onto the OSM road graph. Each MatchedEdge already
                                            // follows the real road; we only classify it by speed-vs-limit.
                                            val step = (rows.size / VEHICLE_MAX_PRE_SAMPLES)
                                                .coerceAtLeast(1)
                                            val sampled = if (step == 1) {
                                                rows
                                            } else {
                                                rows.filterIndexed { index, _ -> index % step == 0 }
                                            }
                                            val observations = sampled.map { row ->
                                                RoadObservation(
                                                    latE7 = row.latE7,
                                                    lonE7 = row.lonE7,
                                                    accuracyM = VEHICLE_DEFAULT_ACCURACY_M,
                                                    timeMs = row.timeMs,
                                                )
                                            }
                                            val edges = roadMatcher.match(observations)
                                            val result = ArrayList<VehicleComplianceLayer.ComplianceEdge>(
                                                edges.size,
                                            )
                                            var prevToIndex = -1
                                            for (edge in edges) {
                                                // Classify the span by the speed recorded at the fix it
                                                // leads into (the more recent reading), matching the
                                                // original per-sample colouring and avoiding blended
                                                // "sliver" buckets at every speed transition.
                                                val speed = sampled[edge.toIndex].speedMps
                                                val limitMps = edge.maxspeedKmh * VEHICLE_MPS_PER_KMH
                                                val ratio = if (limitMps <= 0f) {
                                                    Float.NaN
                                                } else {
                                                    speed / limitMps
                                                }
                                                result.add(
                                                    VehicleComplianceLayer.ComplianceEdge(
                                                        ratio = ratio,
                                                        path = edge.path.map { p ->
                                                            LatLngModel(p.latE7 / 1e7, p.lonE7 / 1e7)
                                                        },
                                                        gapBefore = edge.fromIndex != prevToIndex,
                                                    ),
                                                )
                                                prevToIndex = edge.toIndex
                                            }
                                            result
                                        }
                                    }
                                },
                                perf = PerformanceManager(),
                            )
                        },
                        legend = MapLayerData(
                            info = MapLayerInfo(
                                "VehicleComplianceLayer",
                                R.string.map_layer_vehicle_compliance_title,
                            ),
                            colorList = HeatmapColorRamps.VehicleCompliance.map { it.second },
                            legend = MapLegend(
                                description = R.string.map_layer_vehicle_compliance_description,
                                valueList = listOf(
                                    MapLegendValue(
                                        R.string.map_layer_vehicle_compliance_way_under,
                                        HeatmapColorRamps.VehicleCompliance[0].second,
                                    ),
                                    MapLegendValue(
                                        R.string.map_layer_vehicle_compliance_slow,
                                        HeatmapColorRamps.VehicleCompliance[1].second,
                                    ),
                                    MapLegendValue(
                                        R.string.map_layer_vehicle_compliance_at_limit,
                                        HeatmapColorRamps.VehicleCompliance[2].second,
                                    ),
                                    MapLegendValue(
                                        R.string.map_layer_vehicle_compliance_slightly_over,
                                        HeatmapColorRamps.VehicleCompliance[3].second,
                                    ),
                                    MapLegendValue(
                                        R.string.map_layer_vehicle_compliance_speeding,
                                        HeatmapColorRamps.VehicleCompliance[4].second,
                                    ),
                                )
                            )
                        )
                    )
                })
            )
        )

        // Location Polyline
        add(
            LayerDescriptor(
                id = "location_polyline",
                titleRes = R.string.map_layer_location_polyline_title,
                chipLabelRes = R.string.map_layer_location_polyline_chip,
                iconRes = null,
                capabilities = LayerCapabilities(isPolyline = true),
                recipe = LayerRecipe(factory = LayerFactory {
                    LayerEntry(
                        build = { ctx ->
                            val dao = AppDatabase.database(ctx).locationSampleDao()
                            LocationPathLayer(
                                pointsProvider = { range ->
                                    withContext(dispatchers.io) {
                                        val now = System.currentTimeMillis()
                                        val fromMs = if (!range.isEmpty()) {
                                            range.first
                                        } else {
                                            now - DEFAULT_LOCATION_RANGE_MS
                                        }
                                        val toMs = if (!range.isEmpty()) range.last else now
                                        val rows = buildList {
                                            var afterTimeMs: Long? = null
                                            var afterId: Long? = null

                                            while (true) {
                                                val chunk = dao.getChunkBetweenOrdered(
                                                    fromMs = fromMs,
                                                    toMs = toMs,
                                                    afterTimeMs = afterTimeMs,
                                                    afterId = afterId,
                                                    limit = LOCATION_PATH_CHUNK_SIZE,
                                                )
                                                if (chunk.isEmpty()) break

                                                addAll(chunk)

                                                val lastRow = chunk.last()
                                                afterTimeMs = lastRow.timeMs
                                                afterId = lastRow.id
                                                if (chunk.size < LOCATION_PATH_CHUNK_SIZE) {
                                                    break
                                                }
                                            }
                                        }
                                        if (rows.isEmpty()) {
                                            emptyList()
                                        } else {
                                            val step = (rows.size / LOCATION_PATH_MAX_PRE_POINTS).coerceAtLeast(1)
                                            rows.asSequence()
                                                .filterIndexed { index, _ -> index % step == 0 }
                                                .mapNotNull { row ->
                                                    val latE7 = row.latE7
                                                    val lonE7 = row.lonE7
                                                    if (latE7 == null || lonE7 == null) {
                                                        null
                                                    } else {
                                                        LatLngModel(latE7 / 1e7, lonE7 / 1e7)
                                                    }
                                                }
                                                .toList()
                                        }
                                    }
                                },
                                perf = PerformanceManager()
                            )
                        },
                        legend = MapLayerData(
                            info = MapLayerInfo("LocationPathLayer", R.string.map_layer_location_polyline_title),
                            colorList = emptyList(),
                            legend = MapLegend(R.string.map_layer_location_polyline_description)
                        )
                    )
                })
            )
        )

        // Legacy grid-tile Heatmap (easter egg; only shown when enabled in Map settings).
        add(
            LayerDescriptor(
                id = "legacy_heatmap",
                titleRes = R.string.map_layer_legacy_heatmap_title,
                chipLabelRes = R.string.map_layer_legacy_heatmap_chip,
                iconRes = null,
                capabilities = LayerCapabilities(isHeatmap = true, supportsQuality = false),
                recipe = LayerRecipe(factory = LayerFactory {
                    LayerEntry(
                        build = { ctx ->
                            val dao: UnifiedGeoDao = AppDatabase.database(ctx).unifiedGeoDao()
                            val repo: GeoRepository = GeoRepositoryImpl(dao)
                            LegacyHeatmapLayer(repo, PerformanceManager())
                        },
                        legend = MapLayerData(
                            info = MapLayerInfo("LegacyHeatmapLayer", R.string.map_layer_legacy_heatmap_title),
                            colorList = HeatmapColorRamps.LegacyTiles.map { it.second },
                            legend = MapLegend(
                                description = R.string.map_layer_legacy_heatmap_description,
                                valueList = listOf(
                                    MapLegendValue(R.string.map_layer_location_heatmap_low, HeatmapColorRamps.LegacyTiles[0].second),
                                    MapLegendValue(R.string.map_layer_location_heatmap_low_medium, HeatmapColorRamps.LegacyTiles[1].second),
                                    MapLegendValue(R.string.map_layer_location_heatmap_medium, HeatmapColorRamps.LegacyTiles[2].second),
                                    MapLegendValue(R.string.map_layer_location_heatmap_high, HeatmapColorRamps.LegacyTiles[3].second),
                                    MapLegendValue(R.string.map_layer_location_heatmap_peak, HeatmapColorRamps.LegacyTiles[4].second),
                                )
                            )
                        )
                    )
                })
            )
        )
    }

    private val cachedLayers: List<LayerDescriptor> by lazy { buildLayers() }

    override fun getAllLayers(): List<LayerDescriptor> = cachedLayers
}
