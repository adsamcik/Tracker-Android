package com.adsamcik.tracker.app.onboarding.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.app.onboarding.data.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.adsamcik.tracker.R

/**
 * Placeholder screens for the remaining onboarding steps
 * These will be implemented in subsequent stages
 */

@Composable
fun SuccessScreen(
    preferences: UserPreferences,
    grantedPermissions: Set<Permission>,
    onComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState())
            .testTag("onboarding_success_root"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Celebration emoji
        Text(
            text = "🎉",
            style = MaterialTheme.typography.displayLarge
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text(
            text = stringResource(R.string.onboarding_success_title),
            style = MaterialTheme.typography.headlineLarge,
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.Bold
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        Text(
            text = stringResource(R.string.onboarding_success_message),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // Summary of enabled features
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            val locationFeature = stringResource(R.string.onboarding_success_feature_location)
            val activityFeature = stringResource(R.string.onboarding_success_feature_activity)
            val backgroundFeature = stringResource(R.string.onboarding_success_feature_background)
            val wifiFeature = stringResource(R.string.onboarding_success_feature_wifi)
            val notificationsFeature = stringResource(R.string.onboarding_success_feature_notifications)
            val privacyFeature = stringResource(R.string.onboarding_success_feature_privacy)

            Column(
                modifier = Modifier.padding(20.dp)
            ) {
                Text(
                    text = stringResource(R.string.onboarding_success_features_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                val enabledFeatures = buildList {
                    if (Permission.LOCATION_FOREGROUND in grantedPermissions) {
                        add(locationFeature)
                    }
                    if (Permission.ACTIVITY_RECOGNITION in grantedPermissions) {
                        add(activityFeature)
                    }
                    if (Permission.LOCATION_BACKGROUND in grantedPermissions) {
                        add(backgroundFeature)
                    }
                    if (preferences.enableWifiTracking && Permission.NEARBY_WIFI_DEVICES in grantedPermissions) {
                        add(wifiFeature)
                    }
                    if (preferences.enableNotifications && Permission.NOTIFICATIONS in grantedPermissions) {
                        add(notificationsFeature)
                    }
                    add(privacyFeature)
                }
                
                enabledFeatures.forEach { feature ->
                    Text(
                        text = feature,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // Next steps information
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            border = CardDefaults.outlinedCardBorder()
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.onboarding_success_next_steps_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = stringResource(R.string.onboarding_success_next_steps_message),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Button(
            onClick = onComplete,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("onboarding_cta_done")
        ) {
            Text(stringResource(R.string.onboarding_start_tracking_button))
        }
    }
}

@Composable
private fun PlaceholderScreen(
    title: String,
    description: String,
    onContinue: () -> Unit,
    onBack: () -> Unit,
    showSkip: Boolean = false,
    onSkip: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(32.dp))
        
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = description,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.onboarding_placeholder_coming_soon),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        
        Spacer(modifier = Modifier.weight(1f))
        
        // Navigation buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.onboarding_button_back))
            }
            
            Button(
                onClick = onContinue,
                modifier = Modifier.weight(2f)
            ) {
                Text(stringResource(R.string.onboarding_button_continue))
            }
        }
        
        if (showSkip && onSkip != null) {
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(
                onClick = onSkip,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.button_skip))
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun SuccessScreenPreview() {
    MaterialTheme {
        SuccessScreen(
            preferences = UserPreferences(),
            grantedPermissions = emptySet(),
            onComplete = {}
        )
    }
}
