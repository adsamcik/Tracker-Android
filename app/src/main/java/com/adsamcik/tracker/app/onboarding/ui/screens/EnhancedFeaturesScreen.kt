package com.adsamcik.tracker.app.onboarding.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationCity
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.TipsAndUpdates
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.app.onboarding.data.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.adsamcik.tracker.R

/**
 * Enhanced features screen for optional improvements to tracking accuracy.
 * Includes WiFi tracking and notifications.
 */
@Composable
fun EnhancedFeaturesScreen(
    preferences: UserPreferences,
    grantedPermissions: Set<Permission>,
    onPreferencesUpdate: (UserPreferences) -> Unit,
    onPermissionGranted: (Permission) -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hasWifiPermission = Permission.NEARBY_WIFI_DEVICES in grantedPermissions
    val hasNotificationPermission = Permission.NOTIFICATIONS in grantedPermissions
    
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(16.dp))
        
        // Header
        Icon(
            imageVector = Icons.Default.TipsAndUpdates,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = stringResource(R.string.onboarding_enhanced_features_title),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.Bold
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = stringResource(R.string.onboarding_enhanced_features_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // Inline hint when Wi‑Fi is enabled but permission missing
        if (preferences.enableWifiTracking && !hasWifiPermission) {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.onboarding_wifi_permission_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.onboarding_wifi_permission_message),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(onClick = { onPermissionGranted(Permission.NEARBY_WIFI_DEVICES) }) {
                        Text(stringResource(R.string.onboarding_wifi_grant_button))
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // WiFi tracking section
        EnhancedFeatureCard(
            icon = Icons.Default.Wifi,
            title = stringResource(R.string.onboarding_enhanced_wifi_card_title),
            description = stringResource(R.string.onboarding_enhanced_wifi_card_description),
            benefits = listOf(
                stringResource(R.string.onboarding_enhanced_wifi_benefit_indoor_detection),
                stringResource(R.string.onboarding_enhanced_wifi_benefit_battery),
                stringResource(R.string.onboarding_enhanced_wifi_benefit_accuracy)
            ),
            isEnabled = preferences.enableWifiTracking,
            hasPermission = hasWifiPermission,
            onToggle = { enabled ->
                if (enabled && !hasWifiPermission) {
                    onPermissionGranted(Permission.NEARBY_WIFI_DEVICES)
                }
                onPreferencesUpdate(preferences.copy(enableWifiTracking = enabled))
            },
            onRequestPermission = { onPermissionGranted(Permission.NEARBY_WIFI_DEVICES) }
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Notifications section
        EnhancedFeatureCard(
            icon = Icons.Default.Notifications,
            title = stringResource(R.string.onboarding_enhanced_notifications_card_title),
            description = stringResource(R.string.onboarding_enhanced_notifications_card_description),
            benefits = listOf(
                stringResource(R.string.onboarding_enhanced_notifications_benefit_daily_summary),
                stringResource(R.string.onboarding_enhanced_notifications_benefit_achievements),
                stringResource(R.string.onboarding_enhanced_notifications_benefit_status_updates)
            ),
            isEnabled = preferences.enableNotifications,
            hasPermission = hasNotificationPermission,
            onToggle = { enabled ->
                if (enabled && !hasNotificationPermission) {
                    onPermissionGranted(Permission.NOTIFICATIONS)
                }
                onPreferencesUpdate(preferences.copy(enableNotifications = enabled))
            },
            onRequestPermission = { onPermissionGranted(Permission.NOTIFICATIONS) }
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // Information card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    imageVector = Icons.Default.TipsAndUpdates,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.secondary
                )
                
                Spacer(modifier = Modifier.width(12.dp))
                
                Text(
                    text = stringResource(R.string.onboarding_enhanced_features_privacy_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
        
    Spacer(modifier = Modifier.height(32.dp))
        
        // Navigation buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier
                    .weight(1f)
                    .testTag("onboarding_cta_back")
            ) {
                Text(stringResource(R.string.onboarding_button_back))
            }
            
            Button(
                onClick = onContinue,
                modifier = Modifier
                    .weight(2f)
                    .testTag("onboarding_cta_primary")
            ) {
                Text(stringResource(R.string.onboarding_button_continue))
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        TextButton(
            onClick = onSkip,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("onboarding_cta_skip")
        ) {
            Text(stringResource(R.string.onboarding_skip_enhanced_features))
        }
    }
}

@Composable
private fun EnhancedFeatureCard(
    icon: ImageVector,
    title: String,
    description: String,
    benefits: List<String>,
    isEnabled: Boolean,
    hasPermission: Boolean,
    onToggle: (Boolean) -> Unit,
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isEnabled) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        border = if (isEnabled) {
            CardDefaults.outlinedCardBorder().copy(
                brush = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.primary
                ).brush
            )
        } else {
            CardDefaults.outlinedCardBorder()
        }
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = if (isEnabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    
                    Spacer(modifier = Modifier.width(16.dp))
                    
                    Column {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isEnabled) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                        
                        Spacer(modifier = Modifier.height(4.dp))
                        
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isEnabled) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
                
                Switch(
                    checked = isEnabled && hasPermission,
                    onCheckedChange = onToggle
                )
            }
            
            if (isEnabled && !hasPermission) {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onRequestPermission,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.onboarding_grant_permission_button))
                }
            }
            
            if (isEnabled || benefits.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                
                benefits.forEach { benefit ->
                    Row(
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = "• ",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isEnabled) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                        Text(
                            text = benefit,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isEnabled) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun EnhancedFeaturesScreenPreview() {
    MaterialTheme {
        EnhancedFeaturesScreen(
            preferences = UserPreferences(
                enableWifiTracking = true,
                enableNotifications = false
            ),
            grantedPermissions = setOf(Permission.NEARBY_WIFI_DEVICES),
            onPreferencesUpdate = {},
            onPermissionGranted = {},
            onContinue = {},
            onBack = {},
            onSkip = {}
        )
    }
}
