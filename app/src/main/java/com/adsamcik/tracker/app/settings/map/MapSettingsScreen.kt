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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.adsamcik.tracker.app.settings.MapSettingsViewModel
import com.adsamcik.tracker.app.settings.components.ExpandableSection
import com.adsamcik.tracker.app.settings.components.SettingsItem
import com.adsamcik.tracker.app.settings.components.SliderSettingsItemWithHelp

@Composable
fun MapSettingsScreen(
    viewModel: MapSettingsViewModel = hiltViewModel()
) {
    val basemapPath by viewModel.basemapPath.collectAsState()
    val skiInfraLoaded by viewModel.skiInfraLoaded.collectAsState()
    val quality by viewModel.quality.collectAsState()
    val maxHeat by viewModel.maxHeat.collectAsState()
    val visitThreshold by viewModel.visitThreshold.collectAsState()

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
            val importLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument()
            ) { uri ->
                if (uri != null) {
                    viewModel.importBasemap(uri)
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
                        viewModel.clearBasemap()
                    } else {
                        importLauncher.launch(arrayOf("application/octet-stream", "*/*"))
                    }
                }
            )
        }

        // Ski infrastructure import/reset
        item {
            val importLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument()
            ) { uri ->
                if (uri != null) {
                    viewModel.importSkiInfrastructure(uri)
                }
            }

            val subtitle = if (skiInfraLoaded) {
                val infraManager = viewModel.skiInfrastructureManager
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
                    if (skiInfraLoaded) {
                        viewModel.clearSkiInfrastructure()
                    } else {
                        importLauncher.launch(arrayOf("application/octet-stream", "application/x-sqlite3", "*/*"))
                    }
                }
            )

            if (skiInfraLoaded) {
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
                val qualityValues = MapSettingOptions.quality
                val qualityIndex = sliderIndexForValue(quality, qualityValues)

                SliderSettingsItemWithHelp(
                    title = stringResource(com.adsamcik.tracker.map.R.string.settings_map_quality_title),
                    value = qualityIndex,
                    valueRange = 0f..qualityValues.lastIndex.toFloat(),
                    steps = qualityValues.size - 2,
                    valueLabel = { "%.1fx".format(valueForSliderIndex(it, qualityValues)) },
                    onValueChange = { viewModel.setQuality(valueForSliderIndex(it, qualityValues)) },
                    helpTextRes = com.adsamcik.tracker.map.R.string.help_map_quality
                )

                val heatValues = MapSettingOptions.maxHeat
                val heatIndex = sliderIndexForValue(maxHeat, heatValues)

                SliderSettingsItemWithHelp(
                    title = stringResource(com.adsamcik.tracker.map.R.string.settings_map_max_heat_title),
                    value = heatIndex,
                    valueRange = 0f..heatValues.lastIndex.toFloat(),
                    steps = heatValues.size - 2,
                    valueLabel = { "%d".format(valueForSliderIndex(it, heatValues)) },
                    onValueChange = { viewModel.setMaxHeat(valueForSliderIndex(it, heatValues)) },
                    helpTextRes = com.adsamcik.tracker.map.R.string.help_max_heat_points
                )

                val visitValues = MapSettingOptions.visitThresholdSeconds
                val visitIndex = sliderIndexForValue(visitThreshold, visitValues)

                SliderSettingsItemWithHelp(
                    title = stringResource(com.adsamcik.tracker.map.R.string.settings_map_visit_threshold_title),
                    value = visitIndex,
                    valueRange = 0f..visitValues.lastIndex.toFloat(),
                    steps = visitValues.size - 2,
                    valueLabel = { formatVisitThreshold(valueForSliderIndex(it, visitValues)) },
                    onValueChange = { viewModel.setVisitThreshold(valueForSliderIndex(it, visitValues)) },
                    helpTextRes = com.adsamcik.tracker.map.R.string.help_visit_threshold
                )
            }
        }
    }
}
