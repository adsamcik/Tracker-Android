package com.adsamcik.tracker.app.settings.map

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.activity.ski.SkiInfrastructureManager
import com.adsamcik.tracker.app.settings.components.ExpandableSection
import com.adsamcik.tracker.app.settings.components.SettingsItem
import com.adsamcik.tracker.app.settings.components.SliderSettingsItemWithHelp
import com.adsamcik.tracker.map.basemap.BasemapManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun MapSettingsScreen() {
    val context = LocalContext.current
    val prefs = remember { com.adsamcik.tracker.shared.preferences.Preferences.getPref(context) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 88.dp)
    ) {
        // Info card explaining map settings purpose
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        stringResource(com.adsamcik.tracker.R.string.settings_map_info_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }

        // Basemap import/reset
        item {
            val scope = rememberCoroutineScope()
            val basemapManager = remember { BasemapManager(context) }
            val basemapPathKey = remember {
                context.getString(com.adsamcik.tracker.map.R.string.settings_map_basemap_path_key)
            }
            var basemapPath by remember { mutableStateOf(basemapManager.customBasemapPath()) }

            val importLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument()
            ) { uri ->
                if (uri != null) {
                    scope.launch(Dispatchers.IO) {
                        val path = basemapManager.importBasemap(uri)
                        prefs.edit { setString(basemapPathKey, path) }
                        basemapPath = path
                    }
                }
            }

            SettingsItem(
                title = stringResource(com.adsamcik.tracker.map.R.string.settings_map_basemap_title),
                subtitle = if (basemapPath != null) {
                    stringResource(com.adsamcik.tracker.map.R.string.settings_map_basemap_custom)
                } else {
                    stringResource(com.adsamcik.tracker.map.R.string.settings_map_basemap_default)
                },
                icon = Icons.Default.Map,
                onClick = {
                    if (basemapPath != null) {
                        basemapManager.clearCustomBasemap()
                        prefs.edit { setString(basemapPathKey, "") }
                        basemapPath = null
                    } else {
                        importLauncher.launch(arrayOf("application/octet-stream", "*/*"))
                    }
                }
            )
        }

        // Ski infrastructure import/reset
        item {
            val scope = rememberCoroutineScope()
            val infraManager = remember { SkiInfrastructureManager(context) }
            var isLoaded by remember { mutableStateOf(infraManager.isAvailable()) }

            val importLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument()
            ) { uri ->
                if (uri != null) {
                    scope.launch(Dispatchers.IO) {
                        try {
                            infraManager.importDatabase(uri)
                            isLoaded = true
                        } catch (_: Exception) {
                            // Import failed — already cleaned up by manager
                        }
                    }
                }
            }

            val subtitle = if (isLoaded) {
                val liftCount = infraManager.getMetadata("lift_count")
                val generatedAt = infraManager.getMetadata("generated_at")?.take(10)
                if (liftCount != null && generatedAt != null) {
                    stringResource(
                        com.adsamcik.tracker.activity.R.string.settings_ski_infrastructure_subtitle_info,
                        liftCount.toIntOrNull() ?: 0,
                        generatedAt
                    )
                } else {
                    stringResource(com.adsamcik.tracker.activity.R.string.settings_ski_infrastructure_subtitle_loaded)
                }
            } else {
                stringResource(com.adsamcik.tracker.activity.R.string.settings_ski_infrastructure_subtitle_none)
            }

            SettingsItem(
                title = stringResource(com.adsamcik.tracker.activity.R.string.settings_ski_infrastructure_title),
                subtitle = subtitle,
                icon = Icons.Default.Terrain,
                onClick = {
                    if (isLoaded) {
                        infraManager.clearDatabase()
                        isLoaded = false
                    } else {
                        importLauncher.launch(arrayOf("application/octet-stream", "application/x-sqlite3", "*/*"))
                    }
                }
            )

            if (isLoaded) {
                Text(
                    text = stringResource(com.adsamcik.tracker.activity.R.string.settings_ski_infrastructure_attribution),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 56.dp, end = 16.dp, bottom = 8.dp)
                )
            }
        }

        // All map settings are advanced - wrap in expandable section
        item {
            ExpandableSection(
                title = stringResource(com.adsamcik.tracker.map.R.string.settings_map_advanced_section_title),
                initiallyExpanded = false
            ) {
                // Map quality slider
                val qualityValues = context.resources.getStringArray(com.adsamcik.tracker.map.R.array.settings_map_quality_values).map { it.toFloat() }
                val qualityKey = context.getString(com.adsamcik.tracker.map.R.string.settings_map_quality_key)
                val qualityDefault = context.getString(com.adsamcik.tracker.map.R.string.settings_map_quality_default).toFloat()
                val quality by prefs.observeFloat(qualityKey, qualityDefault).collectAsState(initial = qualityDefault)

                SliderSettingsItemWithHelp(
                    title = stringResource(com.adsamcik.tracker.map.R.string.settings_map_quality_title),
                    value = quality,
                    valueRange = qualityValues.first()..qualityValues.last(),
                    steps = qualityValues.size - 2,
                    valueLabel = { "%.1fx".format(it) },
                    onValueChange = {
                        prefs.edit { setFloat(qualityKey, it) }
                    },
                    helpTextRes = com.adsamcik.tracker.map.R.string.help_map_quality
                )

                // Max heat points slider
                val heatValues = context.resources.getIntArray(com.adsamcik.tracker.map.R.array.settings_map_max_heat_values)
                val heatKey = context.getString(com.adsamcik.tracker.map.R.string.settings_map_max_heat_key)
                val heatDefault = context.getString(com.adsamcik.tracker.map.R.string.settings_map_max_heat_default).toInt()
                val maxHeat by prefs.observeInt(heatKey, heatDefault).collectAsState(initial = heatDefault)

                SliderSettingsItemWithHelp(
                    title = stringResource(com.adsamcik.tracker.map.R.string.settings_map_max_heat_title),
                    value = maxHeat.toFloat(),
                    valueRange = heatValues.first().toFloat()..heatValues.last().toFloat(),
                    steps = heatValues.size - 2,
                    valueLabel = { "%d".format(it.toInt()) },
                    onValueChange = {
                        prefs.edit { setInt(heatKey, it.toInt()) }
                    },
                    helpTextRes = com.adsamcik.tracker.map.R.string.help_max_heat_points
                )

                // Visit threshold slider (duration in minutes)
                val visitValues = context.resources.getIntArray(com.adsamcik.tracker.map.R.array.settings_map_visit_threshold_values)
                val visitKey = context.getString(com.adsamcik.tracker.map.R.string.settings_map_visit_threshold_key)
                val visitDefault = context.getString(com.adsamcik.tracker.map.R.string.settings_map_visit_threshold_default).toInt()
                val visitThreshold by prefs.observeInt(visitKey, visitDefault).collectAsState(initial = visitDefault)

                SliderSettingsItemWithHelp(
                    title = stringResource(com.adsamcik.tracker.map.R.string.settings_map_visit_threshold_title),
                    value = visitThreshold.toFloat(),
                    valueRange = visitValues.first().toFloat()..visitValues.last().toFloat(),
                    steps = visitValues.size - 2,
                    valueLabel = {
                        val minutes = it.toInt() / 60
                        if (minutes < 60) "$minutes min" else "${minutes / 60}h ${minutes % 60}min"
                    },
                    onValueChange = {
                        prefs.edit { setInt(visitKey, it.toInt()) }
                    },
                    helpTextRes = com.adsamcik.tracker.map.R.string.help_visit_threshold
                )
            }
        }
    }
}
