package com.adsamcik.tracker.map.layers.registry

import android.graphics.Color
import com.adsamcik.tracker.map.R
import com.adsamcik.tracker.map.data.GeoRepository
import com.adsamcik.tracker.map.data.GeoRepositoryImpl
import com.adsamcik.tracker.map.layers.base.BaseMapLayer
import com.adsamcik.tracker.map.layers.impl.CellHeatmapLayer
import com.adsamcik.tracker.map.layers.impl.LocationHeatmapLayer
import com.adsamcik.tracker.map.layers.impl.LocationPathLayer
import com.adsamcik.tracker.map.layers.impl.SpeedHeatmapLayer
import com.adsamcik.tracker.map.layers.impl.WifiCountHeatmapLayer
import com.adsamcik.tracker.map.layers.impl.WifiHeatmapLayer
import com.adsamcik.tracker.map.perf.PerformanceManager
import com.adsamcik.tracker.map.presentation.bridge.MapLibreLayerConfig
import com.adsamcik.tracker.map.presentation.udf.LatLngModel
import com.adsamcik.tracker.map.ui.LayerEntry
import com.adsamcik.tracker.shared.base.data.CellType
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.UnifiedGeoDao
import com.adsamcik.tracker.shared.map.MapLayerData
import com.adsamcik.tracker.shared.map.MapLayerInfo
import com.adsamcik.tracker.shared.map.MapLegend
import com.adsamcik.tracker.shared.map.MapLegendValue
import com.adsamcik.tracker.shared.map.layers.LayerCapabilities
import com.adsamcik.tracker.shared.map.layers.LayerDescriptor
import com.adsamcik.tracker.shared.map.layers.LayerFactory
import com.adsamcik.tracker.shared.map.layers.LayerRecipe
import com.adsamcik.tracker.shared.utils.style.color.ColorConstants
import com.adsamcik.tracker.shared.utils.style.color.ColorGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Registry for map layers: registers layers directly. */
class DefaultLayerRegistry : LayerRegistry {

    /**
     * No-op layer placeholder.
     */
    private class NoMapLayer : BaseMapLayer<Unit, Unit>() {
        override suspend fun loadData(context: android.content.Context) = Unit
        override fun processData(input: Unit, budgets: PerformanceManager.PerformanceBudgets) = Unit
        override fun produceConfig(processed: Unit): MapLibreLayerConfig? = null
    }

    private fun cellTypeColors(): List<Int> {
        val count = CellType.values().size
        val startHue = 0.230
        return ColorGenerator.generateWithGolden(startHue, count)
    }

    private fun cellTypeLegendValues(colors: List<Int>): List<MapLegendValue> =
        CellType.values().mapIndexed { index, type -> MapLegendValue(type.nameRes, colors[index]) }

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
                            colorList = cellTypeColors(),
                            legend = MapLegend(
                                description = R.string.map_layer_cell_heatmap_description,
                                valueList = cellTypeLegendValues(cellTypeColors())
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

        // Wifi Count Heatmap
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
                            WifiCountHeatmapLayer(repo, PerformanceManager())
                        },
                        legend = MapLayerData(
                            info = MapLayerInfo("WifiCountHeatmapLayer", R.string.map_layer_wifi_count_heatmap_title),
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
                            colorList = listOf(
                                Color.rgb(153, 102, 255),
                                Color.rgb(102, 204, 255),
                                Color.rgb(102, 255, 102),
                                Color.rgb(255, 255, 102),
                                Color.rgb(255, 128, 0),
                                Color.rgb(255, 51, 51),
                                Color.rgb(255, 0, 0)
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

        // Location Polyline
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
                                        // Resolve effective time bounds: use provided range or fall back to actual data range
                                        val effective = if (!range.isEmpty()) range else {
                                            val dr = dao.range() ?: return@withContext emptyList()
                                            LongRange(dr.start, dr.endInclusive)
                                        }
                                        val rows = if (!effective.isEmpty()) dao.getAllBetweenOrdered(effective.first, effective.last) else emptyList()
                                        if (rows.isEmpty()) emptyList() else {
                                            val maxPrePoints = 30_000
                                            val step = (rows.size / maxPrePoints).coerceAtLeast(1)
                                            rows.asSequence()
                                                .filterIndexed { index, _ -> index % step == 0 }
                                                .map { LatLngModel(it.latitude, it.longitude) }
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
    }

    private val cachedLayers: List<LayerDescriptor> by lazy { buildLayers() }

    override fun getAllLayers(): List<LayerDescriptor> = cachedLayers
}
