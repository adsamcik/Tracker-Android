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
import com.adsamcik.tracker.app.onboarding.data.TrackingProfile

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

        // Auto tracking toggle
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            border = CardDefaults.outlinedCardBorder()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.onboarding_enable_auto_tracking),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.onboarding_auto_tracking_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Switch(
                    checked = preferences.enableAutomaticTracking,
                    onCheckedChange = { checked ->
                        onPreferencesUpdate(preferences.copy(enableAutomaticTracking = checked))
                    }
                )
            }
        }

        if (preferences.enableAutomaticTracking) {
            Spacer(modifier = Modifier.height(16.dp))

            // Tracking profile selection
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                border = CardDefaults.outlinedCardBorder()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.onboarding_tracking_profile_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.onboarding_tracking_profile_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Column(modifier = Modifier.selectableGroup()) {
                        TrackingProfileOption(
                            profile = TrackingProfile.ECO,
                            title = stringResource(R.string.tracking_profile_eco),
                            subtitle = stringResource(R.string.tracking_profile_eco_description),
                            selected = preferences.trackingProfile == TrackingProfile.ECO,
                            onSelect = { 
                                onPreferencesUpdate(preferences.copy(trackingProfile = TrackingProfile.ECO))
                            }
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        TrackingProfileOption(
                            profile = TrackingProfile.BALANCED,
                            title = stringResource(R.string.tracking_profile_balanced),
                            subtitle = stringResource(R.string.tracking_profile_balanced_description),
                            selected = preferences.trackingProfile == TrackingProfile.BALANCED,
                            onSelect = { 
                                onPreferencesUpdate(preferences.copy(trackingProfile = TrackingProfile.BALANCED))
                            }
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        TrackingProfileOption(
                            profile = TrackingProfile.PRECISE,
                            title = stringResource(R.string.tracking_profile_precise),
                            subtitle = stringResource(R.string.tracking_profile_precise_description),
                            selected = preferences.trackingProfile == TrackingProfile.PRECISE,
                            onSelect = { 
                                onPreferencesUpdate(preferences.copy(trackingProfile = TrackingProfile.PRECISE))
                            }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))
        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.generic_back))
            }
            Button(onClick = onContinue, modifier = Modifier.weight(2f)) {
                Text(stringResource(R.string.generic_continue))
            }
        }
    }
}

@Composable
private fun TrackingProfileOption(
    profile: TrackingProfile,
    title: String,
    subtitle: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                onClick = onSelect,
                role = Role.RadioButton
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = null
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
                trackingProfile = TrackingProfile.BALANCED
            ),
            onPreferencesUpdate = {},
            onContinue = {},
            onBack = {}
        )
    }
}
