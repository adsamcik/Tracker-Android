package com.adsamcik.tracker.map.layers.registry

import android.graphics.Color
import com.adsamcik.tracker.map.R
import com.adsamcik.tracker.map.data.GeoRepository
import com.adsamcik.tracker.map.data.GeoRepositoryImpl
import com.adsamcik.tracker.map.layers.base.BaseMapLayer
import com.adsamcik.tracker.map.layers.impl.LocationPathLayer
import com.adsamcik.tracker.map.layers.impl.HeatmapColorRamps
import com.adsamcik.tracker.map.perf.PerformanceManager
import com.adsamcik.tracker.map.presentation.bridge.MapLibreLayerConfig
import com.adsamcik.tracker.map.presentation.udf.LatLngModel
import com.adsamcik.tracker.map.ui.LayerEntry
import com.adsamcik.tracker.map.viz.catalog.cellSignalHeatmap
import com.adsamcik.tracker.map.viz.catalog.activityRibbon
import com.adsamcik.tracker.map.viz.catalog.ACTIVITY_RIBBON_RAMP
import com.adsamcik.tracker.map.viz.catalog.altitudeRibbon
import com.adsamcik.tracker.map.viz.catalog.ALTITUDE_RIBBON_RAMP
import com.adsamcik.tracker.map.viz.catalog.firstContact
import com.adsamcik.tracker.map.viz.catalog.FIRST_CONTACT_RAMP
import com.adsamcik.tracker.map.viz.catalog.fogOfWonder
import com.adsamcik.tracker.map.viz.catalog.FOG_RAMP
import com.adsamcik.tracker.map.viz.catalog.legacyTileHeatmap
import com.adsamcik.tracker.map.viz.catalog.lifeAsTerrain
import com.adsamcik.tracker.map.viz.catalog.locationDensityHeatmap
import com.adsamcik.tracker.map.viz.catalog.SEASONAL_RAMP
import com.adsamcik.tracker.map.viz.catalog.seasonalPalimpsest
import com.adsamcik.tracker.map.viz.catalog.signalCoverageHeatmap
import com.adsamcik.tracker.map.viz.catalog.signalAurora
import com.adsamcik.tracker.map.viz.catalog.AURORA_CORE_RAMP
import com.adsamcik.tracker.map.viz.catalog.AURORA_HALO_RAMP
import com.adsamcik.tracker.map.viz.catalog.skiXRay
import com.adsamcik.tracker.map.viz.catalog.speedHeatmap
import com.adsamcik.tracker.map.viz.catalog.speedRibbon
import com.adsamcik.tracker.map.viz.catalog.SPEED_RIBBON_RAMP
import com.adsamcik.tracker.map.viz.catalog.TERRAIN_RAMP
import com.adsamcik.tracker.map.viz.catalog.wifiCountHeatmap
import com.adsamcik.tracker.map.viz.catalog.wifiSignalHeatmap
import com.adsamcik.tracker.map.viz.CELL_RADIO_COLORS
import com.adsamcik.tracker.map.viz.RadioVisualGroup
import com.adsamcik.tracker.map.viz.WIFI_RADIO_COLORS
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.UnifiedGeoDao
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

/**
 * Read ordered DAO chunks without ever decoding more than [maxMaterializedRows] objects.
 *
 * Callers advance their keyset cursor inside [fetch]. Sampling happens after collection so the
 * selected first and last rows are retained by [downSampleEvenly].
 */
internal suspend fun <T> collectBoundedChunks(
    chunkSize: Int,
    maxMaterializedRows: Int,
    fetch: suspend (limit: Int) -> List<T>,
): List<T> {
    require(chunkSize > 0) { "chunkSize must be positive" }
    require(maxMaterializedRows > 0) { "maxMaterializedRows must be positive" }
    val rows = ArrayList<T>(maxMaterializedRows)
    while (rows.size < maxMaterializedRows) {
        val requested = minOf(chunkSize, maxMaterializedRows - rows.size)
        val chunk = fetch(requested)
        if (chunk.isEmpty()) break
        rows.addAll(chunk)
        if (chunk.size < requested) break
    }
    return rows
}

/** Evenly retain at most [maxPoints] rows, pinning both endpoints. */
internal fun <T> downSampleEvenly(rows: List<T>, maxPoints: Int): List<T> {
    require(maxPoints > 0) { "maxPoints must be positive" }
    if (rows.size <= maxPoints) return rows
    if (maxPoints == 1) return listOf(rows.first())
    return List(maxPoints) { index ->
        rows[((index.toLong() * rows.lastIndex) / (maxPoints - 1)).toInt()]
    }
}

/** Registry for map layers: registers layers directly. */
class DefaultLayerRegistry(
    private val dispatchers: DispatchersProvider = DefaultDispatchersProvider,
) : LayerRegistry {

    private companion object {
        const val LOCATION_PATH_CHUNK_SIZE = 2_000
        const val LOCATION_PATH_MAX_PRE_POINTS = 30_000
        const val LOCATION_PATH_MAX_MATERIALIZED_POINTS = LOCATION_PATH_MAX_PRE_POINTS * 2
        const val DEFAULT_LOCATION_RANGE_MS = 30L * 24 * 60 * 60 * 1_000
        const val E7 = 10_000_000.0
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

    private fun cellRadioLegendValues(): List<MapLegendValue> {
        return listOf(
            MapLegendValue(R.string.map_layer_cell_radio_gsm, CELL_RADIO_COLORS.getValue(RadioVisualGroup.CELL_GSM)),
            MapLegendValue(R.string.map_layer_cell_radio_wcdma, CELL_RADIO_COLORS.getValue(RadioVisualGroup.CELL_WCDMA)),
            MapLegendValue(R.string.map_layer_cell_radio_lte, CELL_RADIO_COLORS.getValue(RadioVisualGroup.CELL_LTE)),
            MapLegendValue(R.string.map_layer_cell_radio_nr, CELL_RADIO_COLORS.getValue(RadioVisualGroup.CELL_NR)),
        )
    }

    private fun wifiRadioLegendValues(): List<MapLegendValue> {
        return listOf(
            MapLegendValue(R.string.map_layer_wifi_radio_24, WIFI_RADIO_COLORS.getValue(RadioVisualGroup.WIFI_24)),
            MapLegendValue(R.string.map_layer_wifi_radio_5, WIFI_RADIO_COLORS.getValue(RadioVisualGroup.WIFI_5)),
            MapLegendValue(R.string.map_layer_wifi_radio_6, WIFI_RADIO_COLORS.getValue(RadioVisualGroup.WIFI_6)),
        )
    }

    private fun signalDeadZoneLegendValues(): List<MapLegendValue> {
        // Drop the transparent 0.0 stop: it represents "strong signal / no data" and is not a
        // meaningful legend swatch. The remaining stops run weak → dead zone.
        val colors = HeatmapColorRamps.SignalDeadZone.drop(1).map { it.second }
        return listOf(
            MapLegendValue(R.string.map_layer_signal_coverage_weak, colors[0]),
            MapLegendValue(R.string.map_layer_signal_coverage_poor, colors[1]),
            MapLegendValue(R.string.map_layer_signal_coverage_very_poor, colors[2]),
            MapLegendValue(R.string.map_layer_signal_coverage_dead, colors[3]),
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

		// Distinct accepted-fix density (keeps the historical location_heatmap ID).
        add(
            LayerDescriptor(
                id = "location_heatmap",
				titleRes = R.string.map_layer_distinct_visits_title,
				chipLabelRes = R.string.map_layer_distinct_visits_chip,
                iconRes = null,
                capabilities = LayerCapabilities(isHeatmap = true),
                recipe = LayerRecipe(factory = LayerFactory {
                    LayerEntry(
                        build = { ctx ->
                            val dao: UnifiedGeoDao = AppDatabase.database(ctx).unifiedGeoDao()
                            val repo: GeoRepository = GeoRepositoryImpl(dao)
                            locationDensityHeatmap(repo).toLayer()
                        },
                        legend = MapLayerData(
							info = MapLayerInfo("LocationHeatmapLayer", R.string.map_layer_distinct_visits_title),
                            colorList = HeatmapColorRamps.LocationDensity.drop(1).map { it.second },
                            legend = MapLegend(
								description = R.string.map_layer_distinct_visits_description,
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
                            cellSignalHeatmap(repo).toLayer()
                        },
                        legend = MapLayerData(
                            info = MapLayerInfo("CellHeatmapLayer", R.string.map_layer_cell_heatmap_title),
                            colorList = cellRadioLegendValues().map { it.color },
                            legend = MapLegend(
                                description = R.string.map_layer_cell_heatmap_description,
                                valueList = cellRadioLegendValues()
                            )
                        )
                    )
                })
            )
        )

        // Signal Coverage (dead zones) — inverse of Cell Heatmap: highlights weak/absent signal.
        add(
            LayerDescriptor(
                id = "signal_coverage",
                titleRes = R.string.map_layer_signal_coverage_title,
                chipLabelRes = R.string.map_layer_signal_coverage_chip,
                iconRes = null,
                capabilities = LayerCapabilities(isHeatmap = true),
                recipe = LayerRecipe(factory = LayerFactory {
                    LayerEntry(
                        build = { ctx ->
                            val dao: UnifiedGeoDao = AppDatabase.database(ctx).unifiedGeoDao()
                            val repo: GeoRepository = GeoRepositoryImpl(dao)
                            signalCoverageHeatmap(repo).toLayer()
                        },
                        legend = MapLayerData(
                            info = MapLayerInfo("SignalCoverageLayer", R.string.map_layer_signal_coverage_title),
                            colorList = HeatmapColorRamps.SignalDeadZone.drop(1).map { it.second },
                            legend = MapLegend(
                                description = R.string.map_layer_signal_coverage_description,
                                valueList = signalDeadZoneLegendValues()
                            )
                        )
                    )
                })
            )
        )

        // Signal Aurora — native halo/core composite with property-only pulse animation.
        add(
            LayerDescriptor(
                id = "signal_aurora",
                titleRes = R.string.map_layer_signal_aurora_title,
                chipLabelRes = R.string.map_layer_signal_aurora_chip,
                iconRes = null,
                capabilities = LayerCapabilities(isHeatmap = true),
                recipe = LayerRecipe(factory = LayerFactory {
                    LayerEntry(
                        build = { ctx ->
                            val dao: UnifiedGeoDao = AppDatabase.database(ctx).unifiedGeoDao()
                            signalAurora(GeoRepositoryImpl(dao)).toLayer()
                        },
                        legend = MapLayerData(
                            info = MapLayerInfo("SignalAurora", R.string.map_layer_signal_aurora_title),
                            colorList = AURORA_CORE_RAMP.drop(1).map { it.second },
                            legend = MapLegend(
                                description = R.string.map_layer_signal_aurora_description,
                                valueList = listOf(
                                    MapLegendValue(R.string.map_layer_signal_aurora_faint, AURORA_HALO_RAMP[1].second),
                                    MapLegendValue(R.string.map_layer_signal_aurora_strong, AURORA_CORE_RAMP.last().second),
                                ),
                            ),
                        ),
                    )
                }),
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
                            wifiSignalHeatmap(repo).toLayer()
                        },
                        legend = MapLayerData(
                            info = MapLayerInfo("WifiHeatmapLayer", R.string.map_layer_wifi_heatmap_title),
                            colorList = wifiRadioLegendValues().map { it.color },
                            legend = MapLegend(
                                description = R.string.map_layer_wifi_heatmap_description,
                                valueList = wifiRadioLegendValues()
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
                            wifiCountHeatmap(repo).toLayer()
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
                            speedHeatmap(repo).toLayer()
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
                                pointsProvider = { range, bounds ->
                                    withContext(dispatchers.io) {
                                        val now = System.currentTimeMillis()
                                        val fromMs = if (!range.isEmpty()) {
                                            range.first
                                        } else {
                                            now - DEFAULT_LOCATION_RANGE_MS
                                        }
                                        val toMs = if (!range.isEmpty()) range.last else now
                                        var afterTimeMs: Long? = null
                                        var afterId: Long? = null
                                        val initialRows = collectBoundedChunks(
                                            chunkSize = LOCATION_PATH_CHUNK_SIZE,
                                            maxMaterializedRows = LOCATION_PATH_MAX_MATERIALIZED_POINTS - 1,
                                        ) { limit ->
                                            val chunk = if (bounds == null) {
                                                dao.getChunkBetweenOrdered(
                                                    fromMs = fromMs,
                                                    toMs = toMs,
                                                    afterTimeMs = afterTimeMs,
                                                    afterId = afterId,
                                                    limit = limit,
                                                )
                                            } else {
                                                dao.getChunkBetweenOrderedInBounds(
                                                    fromMs = fromMs,
                                                    toMs = toMs,
                                                    northE7 = (bounds.north * E7).toInt(),
                                                    eastE7 = (bounds.east * E7).toInt(),
                                                    southE7 = (bounds.south * E7).toInt(),
                                                    westE7 = (bounds.west * E7).toInt(),
                                                    afterTimeMs = afterTimeMs,
                                                    afterId = afterId,
                                                    limit = limit,
                                                )
                                            }
                                            chunk.lastOrNull()?.let { lastRow ->
                                                afterTimeMs = lastRow.timeMs
                                                afterId = lastRow.id
                                            }
                                            chunk
                                        }
                                        val lastRow = if (bounds == null) {
                                            dao.getLatestBetween(fromMs, toMs)
                                        } else {
                                            dao.getLatestBetweenInBounds(
                                                fromMs = fromMs,
                                                toMs = toMs,
                                                northE7 = (bounds.north * E7).toInt(),
                                                eastE7 = (bounds.east * E7).toInt(),
                                                southE7 = (bounds.south * E7).toInt(),
                                                westE7 = (bounds.west * E7).toInt(),
                                            )
                                        }
                                        val rows = if (lastRow != null && initialRows.lastOrNull()?.id != lastRow.id) {
                                            initialRows + lastRow
                                        } else {
                                            initialRows
                                        }
                                        if (rows.isEmpty()) {
                                            emptyList()
                                        } else {
                                            downSampleEvenly(rows, LOCATION_PATH_MAX_PRE_POINTS).asSequence()
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
                            legacyTileHeatmap(repo).toLayer()
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

        // Seasonal explorer — first new-data visualization: explored cells tinted by primary season.
        add(
            LayerDescriptor(
                id = "seasonal_palimpsest",
                titleRes = R.string.map_layer_seasonal_title,
                chipLabelRes = R.string.map_layer_seasonal_chip,
                iconRes = null,
                capabilities = LayerCapabilities(isHeatmap = false, supportsQuality = false),
                recipe = LayerRecipe(factory = LayerFactory {
                    LayerEntry(
                        build = { ctx ->
                            val dao = AppDatabase.database(ctx).explorationCellDao()
                            seasonalPalimpsest(dao).toLayer()
                        },
                        legend = MapLayerData(
                            info = MapLayerInfo("SeasonalPalimpsest", R.string.map_layer_seasonal_title),
                            colorList = SEASONAL_RAMP.map { it.second },
                            legend = MapLegend(
                                description = R.string.map_layer_seasonal_description,
                                valueList = listOf(
                                    MapLegendValue(R.string.map_layer_season_spring, SEASONAL_RAMP[0].second),
                                    MapLegendValue(R.string.map_layer_season_summer, SEASONAL_RAMP[1].second),
                                    MapLegendValue(R.string.map_layer_season_autumn, SEASONAL_RAMP[2].second),
                                    MapLegendValue(R.string.map_layer_season_winter, SEASONAL_RAMP[3].second),
                                )
                            )
                        )
                    )
                })
            )
        )

        // Life as terrain — 3D extrusion: location density lifted into physical height.
        add(
            LayerDescriptor(
                id = "life_terrain",
                titleRes = R.string.map_layer_terrain_title,
                chipLabelRes = R.string.map_layer_terrain_chip,
                iconRes = null,
                capabilities = LayerCapabilities(isHeatmap = false, supportsQuality = false),
                recipe = LayerRecipe(factory = LayerFactory {
                    LayerEntry(
                        build = { ctx ->
                            val dao: UnifiedGeoDao = AppDatabase.database(ctx).unifiedGeoDao()
                            val repo: GeoRepository = GeoRepositoryImpl(dao)
                            lifeAsTerrain(repo).toLayer()
                        },
                        legend = MapLayerData(
                            info = MapLayerInfo("LifeAsTerrain", R.string.map_layer_terrain_title),
                            colorList = TERRAIN_RAMP.map { it.second },
                            legend = MapLegend(
                                description = R.string.map_layer_terrain_description,
                                valueList = listOf(
                                    MapLegendValue(R.string.map_layer_terrain_low, TERRAIN_RAMP[0].second),
                                    MapLegendValue(R.string.map_layer_terrain_mid, TERRAIN_RAMP[2].second),
                                    MapLegendValue(R.string.map_layer_terrain_high, TERRAIN_RAMP[4].second),
                                )
                            )
                        )
                    )
                })
            )
        )

        // Speed ribbon — the recorded route coloured continuously by speed (Segments -> GradientLine).
        add(
            LayerDescriptor(
                id = "speed_ribbon",
                titleRes = R.string.map_layer_speed_ribbon_title,
                chipLabelRes = R.string.map_layer_speed_ribbon_chip,
                iconRes = null,
                capabilities = LayerCapabilities(isHeatmap = false, supportsQuality = false),
                recipe = LayerRecipe(factory = LayerFactory {
                    LayerEntry(
                        build = { ctx ->
                            val dao: UnifiedGeoDao = AppDatabase.database(ctx).unifiedGeoDao()
                            val repo: GeoRepository = GeoRepositoryImpl(dao)
                            speedRibbon(repo).toLayer()
                        },
                        legend = MapLayerData(
                            info = MapLayerInfo("SpeedRibbon", R.string.map_layer_speed_ribbon_title),
                            colorList = SPEED_RIBBON_RAMP.map { it.second },
                            legend = MapLegend(
                                description = R.string.map_layer_speed_ribbon_description,
                                valueList = listOf(
                                    MapLegendValue(R.string.map_layer_speed_very_slow, SPEED_RIBBON_RAMP[0].second),
                                    MapLegendValue(R.string.map_layer_speed_walking, SPEED_RIBBON_RAMP[1].second),
                                    MapLegendValue(R.string.map_layer_speed_running, SPEED_RIBBON_RAMP[2].second),
                                    MapLegendValue(R.string.map_layer_speed_moderate, SPEED_RIBBON_RAMP[3].second),
                                    MapLegendValue(R.string.map_layer_speed_fast, SPEED_RIBBON_RAMP[4].second),
                                    MapLegendValue(R.string.map_layer_speed_very_fast, SPEED_RIBBON_RAMP[5].second),
                                )
                            )
                        )
                    )
                })
            )
        )

        // Altitude ribbon — fixed global elevation scale, split across long tracking gaps.
        add(
            LayerDescriptor(
                id = "altitude_ribbon",
                titleRes = R.string.map_layer_altitude_ribbon_title,
                chipLabelRes = R.string.map_layer_altitude_ribbon_chip,
                iconRes = null,
                capabilities = LayerCapabilities(isHeatmap = false, supportsQuality = false),
                recipe = LayerRecipe(factory = LayerFactory {
                    LayerEntry(
                        build = { ctx ->
                            val dao: UnifiedGeoDao = AppDatabase.database(ctx).unifiedGeoDao()
                            altitudeRibbon(GeoRepositoryImpl(dao)).toLayer()
                        },
                        legend = MapLayerData(
                            info = MapLayerInfo("AltitudeRibbon", R.string.map_layer_altitude_ribbon_title),
                            colorList = ALTITUDE_RIBBON_RAMP.map { it.second },
                            legend = MapLegend(
                                description = R.string.map_layer_altitude_ribbon_description,
                                valueList = listOf(
                                    MapLegendValue(R.string.map_layer_altitude_low, ALTITUDE_RIBBON_RAMP.first().second),
                                    MapLegendValue(R.string.map_layer_altitude_mid, ALTITUDE_RIBBON_RAMP[2].second),
                                    MapLegendValue(R.string.map_layer_altitude_high, ALTITUDE_RIBBON_RAMP.last().second),
                                ),
                            ),
                        ),
                    )
                }),
            )
        )

        // Activity ribbon — truthful persisted motion states, not ambiguous rich activity ordinals.
        add(
            LayerDescriptor(
                id = "activity_ribbon",
                titleRes = R.string.map_layer_activity_ribbon_title,
                chipLabelRes = R.string.map_layer_activity_ribbon_chip,
                iconRes = null,
                capabilities = LayerCapabilities(isHeatmap = false, supportsQuality = false),
                recipe = LayerRecipe(factory = LayerFactory {
                    LayerEntry(
                        build = { ctx ->
                            val dao: UnifiedGeoDao = AppDatabase.database(ctx).unifiedGeoDao()
                            activityRibbon(GeoRepositoryImpl(dao)).toLayer()
                        },
                        legend = MapLayerData(
                            info = MapLayerInfo("ActivityRibbon", R.string.map_layer_activity_ribbon_title),
                            colorList = listOf(
                                ACTIVITY_RIBBON_RAMP.first().second,
                                ACTIVITY_RIBBON_RAMP[2].second,
                                ACTIVITY_RIBBON_RAMP.last().second,
                            ),
                            legend = MapLegend(
                                description = R.string.map_layer_activity_ribbon_description,
                                valueList = listOf(
                                    MapLegendValue(R.string.map_layer_activity_unknown, ACTIVITY_RIBBON_RAMP.first().second),
                                    MapLegendValue(R.string.map_layer_activity_still, ACTIVITY_RIBBON_RAMP[2].second),
                                    MapLegendValue(R.string.map_layer_activity_moving, ACTIVITY_RIBBON_RAMP.last().second),
                                ),
                            ),
                        ),
                    )
                }),
            )
        )

        // Ski X-ray — segment midpoint annotations with native SDF symbols and labels.
        add(
            LayerDescriptor(
                id = "ski_xray",
                titleRes = R.string.map_layer_ski_xray_title,
                chipLabelRes = R.string.map_layer_ski_xray_chip,
                iconRes = R.drawable.ic_ski_downhill,
                capabilities = LayerCapabilities(isHeatmap = false, supportsQuality = false),
                recipe = LayerRecipe(factory = LayerFactory {
                    LayerEntry(
                        build = { ctx ->
                            val entryPoint = ctx.mapLayerEntryPoint()
                            skiXRay(
                                skiRepository = entryPoint.skiRunSegmentRepository(),
                                locationRepository = entryPoint.locationSampleRepository(),
                            ).toLayer()
                        },
                        legend = MapLayerData(
                            info = MapLayerInfo("SkiXRay", R.string.map_layer_ski_xray_title),
                            colorList = listOf(
                                0xFF29B6F6.toInt(),
                                0xFFFFB300.toInt(),
                                0xFFAB47BC.toInt(),
                            ),
                            legend = MapLegend(
                                description = R.string.map_layer_ski_xray_description,
                                valueList = listOf(
                                    MapLegendValue(R.string.map_layer_ski_downhill, 0xFF29B6F6.toInt()),
                                    MapLegendValue(R.string.map_layer_ski_lift, 0xFFFFB300.toInt()),
                                    MapLegendValue(R.string.map_layer_ski_walk, 0xFFAB47BC.toInt()),
                                ),
                            ),
                        ),
                    )
                }),
            )
        )

        // Explorer's fog — explored cells revealed by discovery quality (Fill shape, alpha ramp).
        add(
            LayerDescriptor(
                id = "fog_of_wonder",
                titleRes = R.string.map_layer_fog_title,
                chipLabelRes = R.string.map_layer_fog_chip,
                iconRes = null,
                capabilities = LayerCapabilities(isHeatmap = false, supportsQuality = false),
                recipe = LayerRecipe(factory = LayerFactory {
                    LayerEntry(
                        build = { ctx ->
                            val dao = AppDatabase.database(ctx).explorationCellDao()
                            fogOfWonder(dao).toLayer()
                        },
                        legend = MapLayerData(
                            info = MapLayerInfo("FogOfWonder", R.string.map_layer_fog_title),
                            colorList = FOG_RAMP.map { it.second },
                            legend = MapLegend(
                                description = R.string.map_layer_fog_description,
                                valueList = listOf(
                                    MapLegendValue(R.string.map_layer_fog_faint, FOG_RAMP.first().second),
                                    MapLegendValue(R.string.map_layer_fog_known, FOG_RAMP.last().second),
                                )
                            )
                        )
                    )
                })
            )
        )

        // First contact — explored cells coloured by how recently first discovered (Fill shape).
        add(
            LayerDescriptor(
                id = "first_contact",
                titleRes = R.string.map_layer_first_contact_title,
                chipLabelRes = R.string.map_layer_first_contact_chip,
                iconRes = null,
                capabilities = LayerCapabilities(isHeatmap = false, supportsQuality = false),
                recipe = LayerRecipe(factory = LayerFactory {
                    LayerEntry(
                        build = { ctx ->
                            val dao = AppDatabase.database(ctx).explorationCellDao()
                            firstContact(dao).toLayer()
                        },
                        legend = MapLayerData(
                            info = MapLayerInfo("FirstContact", R.string.map_layer_first_contact_title),
                            colorList = FIRST_CONTACT_RAMP.map { it.second },
                            legend = MapLegend(
                                description = R.string.map_layer_first_contact_description,
                                valueList = listOf(
                                    MapLegendValue(R.string.map_layer_first_contact_old, FIRST_CONTACT_RAMP.first().second),
                                    MapLegendValue(R.string.map_layer_first_contact_recent, FIRST_CONTACT_RAMP.last().second),
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
