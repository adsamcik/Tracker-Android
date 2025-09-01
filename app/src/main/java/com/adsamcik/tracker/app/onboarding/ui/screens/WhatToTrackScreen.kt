package com.adsamcik.tracker.app.onboarding.ui.screens

import android.content.pm.PackageManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.ui.res.stringResource
import com.adsamcik.tracker.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.app.onboarding.data.UserPreferences
import androidx.compose.ui.platform.testTag

@Composable
fun WhatToTrackScreen(
    preferences: UserPreferences,
    onPreferencesUpdate: (UserPreferences) -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val pm = context.packageManager
    val stepSensorAvailable = pm.hasSystemFeature(PackageManager.FEATURE_SENSOR_STEP_COUNTER)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.onboarding_what_to_track_title),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.onboarding_what_to_track_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Toggles card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            border = CardDefaults.outlinedCardBorder()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                ToggleRow(
                    title = stringResource(R.string.tracking_option_location_title),
                    subtitle = stringResource(R.string.tracking_option_location_description),
                    checked = preferences.enableLocationTracking,
                    onCheckedChange = { checked ->
                        onPreferencesUpdate(preferences.copy(enableLocationTracking = checked))
                    }
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                ToggleRow(
                    title = stringResource(R.string.tracking_option_activity_title),
                    subtitle = stringResource(R.string.tracking_option_activity_description),
                    checked = preferences.enableActivityTracking,
                    onCheckedChange = { checked ->
                        onPreferencesUpdate(preferences.copy(enableActivityTracking = checked))
                    }
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                ToggleRow(
                    title = stringResource(R.string.tracking_option_wifi_title),
                    subtitle = stringResource(R.string.tracking_option_wifi_description),
                    checked = preferences.enableWifiTracking,
                    onCheckedChange = { checked ->
                        onPreferencesUpdate(preferences.copy(enableWifiTracking = checked))
                    }
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                ToggleRow(
                    title = stringResource(R.string.tracking_option_cell_title),
                    subtitle = stringResource(R.string.tracking_option_cell_description),
                    checked = preferences.enableCellTracking,
                    onCheckedChange = { checked ->
                        onPreferencesUpdate(preferences.copy(enableCellTracking = checked))
                    }
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                ToggleRow(
                    title = stringResource(R.string.tracking_option_steps_title),
                    subtitle = if (stepSensorAvailable) {
                        stringResource(R.string.tracking_option_steps_description)
                    } else {
                        stringResource(R.string.onboarding_steps_not_available)
                    },
                    checked = preferences.enableStepsTracking && stepSensorAvailable,
                    onCheckedChange = { checked ->
                        onPreferencesUpdate(preferences.copy(enableStepsTracking = checked && stepSensorAvailable))
                    },
                    enabled = stepSensorAvailable
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
                Text(stringResource(com.adsamcik.tracker.shared.base.R.string.generic_back))
            }
            Button(onClick = onContinue, modifier = Modifier.weight(2f).testTag("onboarding_cta_primary")) {
                Text(stringResource(com.adsamcik.tracker.shared.base.R.string.generic_continue))
            }
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Preview(showBackground = true)
@Composable
private fun WhatToTrackScreenPreview() {
    MaterialTheme {
        WhatToTrackScreen(
            preferences = UserPreferences(
                enableLocationTracking = true,
                enableActivityTracking = false,
                enableWifiTracking = true,
                enableCellTracking = false,
                enableStepsTracking = true
            ),
            onPreferencesUpdate = {},
            onContinue = {},
            onBack = {}
        )
    }
}
