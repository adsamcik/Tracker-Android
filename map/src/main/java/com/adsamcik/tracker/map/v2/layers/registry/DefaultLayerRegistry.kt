package com.adsamcik.tracker.map.v2.layers.registry

import com.adsamcik.tracker.map.R
import com.adsamcik.tracker.map.layer.logic.CellHeatmapLogic
import com.adsamcik.tracker.map.layer.logic.LocationHeatmapLogic
import com.adsamcik.tracker.map.layer.logic.LocationPolylineLogic
import com.adsamcik.tracker.map.layer.logic.NoMapLayerLogic
import com.adsamcik.tracker.map.layer.logic.SpeedHeatmapLogic
import com.adsamcik.tracker.map.layer.logic.WifiCountHeatmapLogic
import com.adsamcik.tracker.map.layer.logic.WifiHeatmapLogic
import com.adsamcik.tracker.shared.map.MapLayerLogic
import com.adsamcik.tracker.shared.map.v2.layers.LayerCapabilities
import com.adsamcik.tracker.shared.map.v2.layers.LayerDescriptor
import com.adsamcik.tracker.shared.map.v2.layers.LayerFactory
import com.adsamcik.tracker.shared.map.v2.layers.LayerRecipe

/** Simple adapter to expose existing v1 MapLayerLogic implementations through v2 descriptors. */
class DefaultLayerRegistry : LayerRegistry {

    private val layers: List<LayerDescriptor> = listOf(
    adapt("none", R.string.map_layer_none_title, null) { NoMapLayerLogic() },
        adapt("location_heatmap", R.string.map_layer_location_heatmap_title, null, heatmap = true) { LocationHeatmapLogic() },
        adapt("cell_heatmap", R.string.map_layer_cell_heatmap_title, null, heatmap = true) { CellHeatmapLogic() },
        adapt("wifi_heatmap", R.string.map_layer_wifi_heatmap_title, null, heatmap = true) { WifiHeatmapLogic() },
        adapt("wifi_count_heatmap", R.string.map_layer_wifi_count_heatmap_title, null, heatmap = true) { WifiCountHeatmapLogic() },
    adapt("location_polyline", R.string.map_layer_location_polyline_title, null, polyline = true) { LocationPolylineLogic() },
        adapt("speed_heatmap", R.string.map_layer_speed_heatmap_title, null, heatmap = true) { SpeedHeatmapLogic() }
    )

    private fun adapt(
        id: String,
        titleRes: Int,
        iconRes: Int?,
        heatmap: Boolean = false,
        polyline: Boolean = false,
        factory: () -> MapLayerLogic
    ): LayerDescriptor = LayerDescriptor(
        id = id,
        titleRes = titleRes,
        iconRes = iconRes,
        capabilities = LayerCapabilities(
            supportsDateRange = true,
            supportsQuality = true,
            isHeatmap = heatmap,
            isPolyline = polyline
        ),
        recipe = LayerRecipe(factory = LayerFactory { factory() })
    )

    override fun getAllLayers(): List<LayerDescriptor> = layers
}
