package com.adsamcik.tracker.app.onboarding.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.R
import com.adsamcik.tracker.app.onboarding.data.UserPreferences
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext

/**
 * Auto-tracking configuration screen that explains the app's background tracking capabilities
 */
@Composable
fun AutoTrackingSetupScreen(
    preferences: UserPreferences,
    onPreferencesUpdate: (UserPreferences) -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val autoTrackingTitles = remember { context.resources.getStringArray(R.array.auto_tracking_options_values) }
    val distanceOptions = remember { context.resources.getIntArray(R.array.settings_tracking_min_distance_values).toList() }
    val timeOptions = remember { context.resources.getIntArray(R.array.settings_tracking_min_time_values).toList() }

    // Resolve current values or sensible defaults from resources
    val defaultMode = remember { context.resources.getString(R.string.settings_tracking_activity_default).toInt() }
    val selectedMode = preferences.autoTrackingModeIndex.takeIf { it in 0..2 } ?: defaultMode
    val defaultDistance = remember { context.resources.getInteger(R.integer.settings_tracking_min_distance_default) }
    val defaultTime = remember { context.resources.getInteger(R.integer.settings_tracking_min_time_default) }
    val selectedDistance = preferences.trackingMinDistanceMeters ?: defaultDistance
    val selectedTime = preferences.trackingMinTimeSeconds ?: defaultTime

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        Icon(
            imageVector = Icons.Default.AutoAwesome,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.onboarding_auto_tracking_title),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.onboarding_auto_tracking_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

    // Automatic tracking mode selector (mirrors Settings)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            border = CardDefaults.outlinedCardBorder()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.auto_tracking_options_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.auto_tracking_options_summary, autoTrackingTitles[selectedMode]),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))
                Column(modifier = Modifier.selectableGroup()) {
                    autoTrackingTitles.forEachIndexed { idx, title ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = selectedMode == idx,
                                    onClick = {
                                        onPreferencesUpdate(
                                            preferences.copy(
                                                autoTrackingModeIndex = idx,
                                                // Derive enableAutomaticTracking for flow logic elsewhere
                                                enableAutomaticTracking = idx > 0
                                            )
                                        )
                                    },
                                    role = Role.RadioButton
                                )
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = selectedMode == idx, onClick = null)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = title,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        
        Spacer(modifier = Modifier.height(16.dp))

        // Sliders mirroring Settings (min distance and min delay)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            border = CardDefaults.outlinedCardBorder()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.settings_tracking_min_distance_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${selectedDistance} m",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                val distIndex = distanceOptions.indexOfFirst { it == selectedDistance }.coerceAtLeast(0)
                Slider(
                    value = distIndex.toFloat(),
                    onValueChange = { newVal ->
                        val newIdx = newVal.toInt().coerceIn(0, distanceOptions.lastIndex)
                        onPreferencesUpdate(preferences.copy(trackingMinDistanceMeters = distanceOptions[newIdx]))
                    },
                    valueRange = 0f..distanceOptions.lastIndex.toFloat(),
                    steps = (distanceOptions.size - 2).coerceAtLeast(0)
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = stringResource(R.string.settings_tracking_min_time_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(4.dp))
                val timeLabel = remember(selectedTime) {
                    when {
                        selectedTime >= 60 && selectedTime % 60 == 0 -> "${selectedTime / 60} min"
                        else -> "${selectedTime} s"
                    }
                }
                Text(
                    text = timeLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                val timeIndex = timeOptions.indexOfFirst { it == selectedTime }.coerceAtLeast(0)
                Slider(
                    value = timeIndex.toFloat(),
                    onValueChange = { newVal ->
                        val newIdx = newVal.toInt().coerceIn(0, timeOptions.lastIndex)
                        onPreferencesUpdate(preferences.copy(trackingMinTimeSeconds = timeOptions[newIdx]))
                    },
                    valueRange = 0f..timeOptions.lastIndex.toFloat(),
                    steps = (timeOptions.size - 2).coerceAtLeast(0)
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))
        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f).testTag("onboarding_cta_back")) {
                Text(stringResource(R.string.generic_back))
            }
            Button(onClick = onContinue, modifier = Modifier.weight(2f).testTag("onboarding_cta_primary")) {
                Text(stringResource(R.string.generic_continue))
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun AutoTrackingSetupScreenPreview() {
    MaterialTheme {
        AutoTrackingSetupScreen(
            preferences = UserPreferences(
                enableAutomaticTracking = true,
                autoTrackingModeIndex = 1,
                trackingMinDistanceMeters = 10,
                trackingMinTimeSeconds = 2
            ),
            onPreferencesUpdate = {},
            onContinue = {},
            onBack = {}
        )
    }
}
