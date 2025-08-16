package com.adsamcik.tracker.map.layers.registry

import android.graphics.Color
import com.adsamcik.tracker.map.R
import com.adsamcik.tracker.map.layers.impl.CellHeatmapLayer
import com.adsamcik.tracker.map.layers.impl.LocationHeatmapLayer
import com.adsamcik.tracker.map.layers.impl.LocationPathLayer
import com.adsamcik.tracker.map.layers.impl.SpeedHeatmapLayer
import com.adsamcik.tracker.map.layers.impl.WifiCountHeatmapLayer
import com.adsamcik.tracker.map.layers.impl.WifiHeatmapLayer
import com.adsamcik.tracker.map.perf.PerformanceManager
import com.adsamcik.tracker.map.ui.LayerEntry
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.UnifiedGeoDao
import com.adsamcik.tracker.map.graphics.BitmapPool
import com.adsamcik.tracker.map.data.GeoRepository
import com.adsamcik.tracker.map.data.GeoRepositoryImpl
import com.adsamcik.tracker.shared.base.data.CellType
import com.adsamcik.tracker.shared.map.MapLayerData
import com.adsamcik.tracker.shared.map.MapLayerInfo
import com.adsamcik.tracker.shared.map.MapLayerLogic
import com.adsamcik.tracker.shared.map.MapLegend
import com.adsamcik.tracker.shared.map.MapLegendValue
import com.adsamcik.tracker.shared.map.v2.layers.LayerCapabilities
import com.adsamcik.tracker.shared.map.v2.layers.LayerDescriptor
import com.adsamcik.tracker.shared.map.v2.layers.LayerFactory
import com.adsamcik.tracker.shared.map.v2.layers.LayerRecipe
import com.adsamcik.tracker.shared.utils.style.color.ColorConstants
import com.adsamcik.tracker.shared.utils.style.color.ColorGenerator
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** v2-only registry: registers v2 layers directly (cutover complete). */
class DefaultLayerRegistry : LayerRegistry {

    private val layers: List<LayerDescriptor> = buildList {
        // No layer (legend only; acts as a placeholder)
        add(
            LayerDescriptor(
                id = "none",
                titleRes = R.string.map_layer_none_title,
                iconRes = null,
                capabilities = LayerCapabilities(),
                recipe = LayerRecipe(factory = LayerFactory {
                    LayerEntry(
                        build = { _ -> object : com.adsamcik.tracker.map.layers.base.BaseMapLayer<Unit, Unit>() {
                            override fun loadData(context: android.content.Context) = Unit
                            override fun processData(input: Unit, budgets: PerformanceManager.PerformanceBudgets) = Unit
                            override fun render(map: com.google.android.gms.maps.GoogleMap, processed: Unit) {}
                        } },
                        legend = MapLayerData(
                            info = MapLayerInfo(MapLayerLogic::class.java, R.string.map_layer_none_title),
                            colorList = emptyList(),
                            legend = MapLegend()
                        )
                    )
                })
            )
        )

        // Location Heatmap (v2)
        add(
            LayerDescriptor(
                id = "location_heatmap",
                titleRes = R.string.map_layer_location_heatmap_title,
                iconRes = null,
                capabilities = LayerCapabilities(isHeatmap = true),
                recipe = LayerRecipe(factory = LayerFactory {
                    LayerEntry(
                        build = { ctx ->
                            val dao: UnifiedGeoDao = AppDatabase.database(ctx).unifiedGeoDao()
                            val repo: GeoRepository = GeoRepositoryImpl(dao)
                            LocationHeatmapLayer(repo, BitmapPool(), PerformanceManager())
                        },
                        legend = MapLayerData(
                            info = MapLayerInfo(MapLayerLogic::class.java, R.string.map_layer_location_heatmap_title),
                            colorList = listOf(Color.BLUE, Color.YELLOW, Color.RED),
                            legend = MapLegend(
                                description = R.string.map_layer_location_heatmap_description,
                                valueList = listOf(
                                    MapLegendValue(R.string.map_layer_location_heatmap_low, Color.BLUE),
                                    MapLegendValue(R.string.map_layer_location_heatmap_medium, Color.YELLOW),
                                    MapLegendValue(R.string.map_layer_location_heatmap_high, Color.RED)
                                )
                            )
                        )
                    )
                })
            )
        )

        // Cell Heatmap (v2)
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
                            CellHeatmapLayer(repo, BitmapPool(), PerformanceManager())
                        },
                        legend = MapLayerData(
                            info = MapLayerInfo(MapLayerLogic::class.java, R.string.map_layer_cell_heatmap_title),
                            colorList = run {
                                val count = CellType.values().size
                                val startHue = 0.230 // formerly CellHeatmapLogic.COLOR_START_HUE
                                ColorGenerator.generateWithGolden(startHue, count)
                            },
                            legend = MapLegend(
                                description = R.string.map_layer_cell_heatmap_description,
                                valueList = run {
                                    val colors = run {
                                        val count = CellType.values().size
                                        val startHue = 0.230
                                        ColorGenerator.generateWithGolden(startHue, count)
                                    }
                                    CellType.values().mapIndexed { index, type ->
                                        MapLegendValue(type.nameRes, colors[index])
                                    }
                                }
                            )
                        )
                    )
                })
            )
        )

        // Wifi Heatmap (v2)
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
                            WifiHeatmapLayer(repo, BitmapPool(), PerformanceManager())
                        },
                        legend = MapLayerData(
                            info = MapLayerInfo(MapLayerLogic::class.java, R.string.map_layer_wifi_heatmap_title),
                            colorList = listOf(ColorConstants.GREEN, ColorConstants.ORANGE, ColorConstants.RED),
                            legend = MapLegend(
                                description = R.string.map_layer_wifi_heatmap_description,
                                valueList = listOf(
                                    MapLegendValue(R.string.map_layer_wifi_heatmap_low, ColorConstants.GREEN),
                                    MapLegendValue(R.string.map_layer_wifi_heatmap_medium, ColorConstants.ORANGE),
                                    MapLegendValue(R.string.map_layer_wifi_heatmap_high, ColorConstants.RED)
                                )
                            )
                        )
                    )
                })
            )
        )

        // Wifi Count Heatmap (v2)
        add(
            LayerDescriptor(
                id = "wifi_count_heatmap",
                titleRes = R.string.map_layer_wifi_count_heatmap_title,
                iconRes = null,
                capabilities = LayerCapabilities(isHeatmap = true),
                recipe = LayerRecipe(factory = LayerFactory {
                    LayerEntry(
                        build = { ctx ->
                            val dao: UnifiedGeoDao = AppDatabase.database(ctx).unifiedGeoDao()
                            val repo: GeoRepository = GeoRepositoryImpl(dao)
                            WifiCountHeatmapLayer(repo, BitmapPool(), PerformanceManager())
                        },
                        legend = MapLayerData(
                            info = MapLayerInfo(MapLayerLogic::class.java, R.string.map_layer_wifi_count_heatmap_title),
                            colorList = listOf(ColorConstants.GREEN, ColorConstants.ORANGE, ColorConstants.RED),
                            legend = MapLegend(
                                description = R.string.map_layer_wifi_heatmap_description,
                                valueList = listOf(
                                    MapLegendValue(R.string.map_layer_wifi_heatmap_low, ColorConstants.GREEN),
                                    MapLegendValue(R.string.map_layer_wifi_heatmap_medium, ColorConstants.ORANGE),
                                    MapLegendValue(R.string.map_layer_wifi_heatmap_high, ColorConstants.RED)
                                )
                            )
                        )
                    )
                })
            )
        )

        // Speed Heatmap (v2)
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
                            SpeedHeatmapLayer(repo, BitmapPool(), PerformanceManager())
                        },
                        legend = MapLayerData(
                            info = MapLayerInfo(MapLayerLogic::class.java, R.string.map_layer_speed_heatmap_title),
                            colorList = listOf(
                                Color.rgb(153, 102, 255), // Very Slow: Purple
                                Color.rgb(102, 204, 255), // Walking: Light Blue
                                Color.rgb(102, 255, 102), // Running: Light Green
                                Color.rgb(255, 255, 102), // Bike: Yellow
                                Color.rgb(255, 128, 0),   // Public Transport: Orange
                                Color.rgb(255, 51, 51),   // Car: Red
                                Color.rgb(255, 0, 0)      // Very High Speed: Dark Red
                            ),
                            legend = MapLegend(
                                description = R.string.map_layer_speed_heatmap_description,
                                valueList = listOf(
                                    MapLegendValue(R.string.map_layer_speed_very_slow, Color.rgb(153, 102, 255)),
                                    MapLegendValue(R.string.map_layer_speed_walking, Color.rgb(102, 204, 255)),
                                    MapLegendValue(R.string.map_layer_speed_running, Color.rgb(102, 255, 102)),
                                    MapLegendValue(R.string.map_layer_speed_moderate, Color.rgb(255, 255, 102)),
                                    MapLegendValue(R.string.map_layer_speed_fast, Color.rgb(255, 128, 0)),
                                    MapLegendValue(R.string.map_layer_speed_very_fast, Color.rgb(255, 51, 51)),
                                    MapLegendValue(R.string.map_layer_speed_extreme, Color.rgb(255, 0, 0))
                                )
                            )
                        )
                    )
                })
            )
        )

        // Location Polyline (v2)
        add(
            LayerDescriptor(
                id = "location_polyline",
                titleRes = R.string.map_layer_location_polyline_title,
                iconRes = null,
                capabilities = LayerCapabilities(isPolyline = true),
                recipe = LayerRecipe(factory = LayerFactory {
                    LayerEntry(
                        build = { ctx ->
                            val dao = AppDatabase.database(ctx).locationDao()
                            LocationPathLayer(
                                pointsProvider = { range ->
                                    withContext(Dispatchers.IO) {
                                        val from = range.start
                                        val to = range.endInclusive
                                        // Prefer ordered query within range; fallback to all if empty range
                                        val rows = if (!range.isEmpty()) dao.getAllBetweenOrdered(from, to) else dao.getAll()
                                        if (rows.isEmpty()) emptyList() else {
                                            // Light pre-sampling to cap the size before decimation
                                            val maxPrePoints = 30_000
                                            val step = (rows.size / maxPrePoints).coerceAtLeast(1)
                                            rows.asSequence()
                                                .filterIndexed { index, _ -> index % step == 0 }
                                                .map { LatLng(it.latitude, it.longitude) }
                                                .toList()
                                        }
                                    }
                                },
                                perf = PerformanceManager()
                            )
                        },
                        legend = MapLayerData(
                            info = MapLayerInfo(MapLayerLogic::class.java, R.string.map_layer_location_polyline_title),
                            colorList = emptyList(),
                            legend = MapLegend(R.string.map_layer_location_heatmap_description)
                        )
                    )
                })
            )
        )
    }

    override fun getAllLayers(): List<LayerDescriptor> = layers
}
