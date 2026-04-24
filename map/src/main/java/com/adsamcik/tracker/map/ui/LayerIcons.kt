package com.adsamcik.tracker.map.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LayersClear
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Resolve the icon used across the map chrome for a given layer id.
 * Centralised so chips, popovers, and legend pills all render the same glyph.
 */
internal fun getLayerIcon(layerId: String): ImageVector = when (layerId) {
	"location_heatmap" -> Icons.Filled.LocationOn
	"cell_heatmap" -> Icons.Filled.CellTower
	"wifi_heatmap" -> Icons.Filled.Wifi
	"wifi_count_heatmap" -> Icons.Filled.Wifi
	"speed_heatmap" -> Icons.AutoMirrored.Filled.DirectionsRun
	"location_polyline" -> Icons.Filled.Timeline
	"none" -> Icons.Filled.LayersClear
	else -> Icons.Filled.Layers
}
