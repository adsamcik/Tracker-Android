package com.adsamcik.tracker.app.onboarding.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.LocationCity
import androidx.compose.material.icons.filled.TipsAndUpdates
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
            text = "Enhanced Features",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.Bold
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = "Optional features to improve tracking accuracy and user experience.",
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
                        text = "Wi‑Fi permission needed",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Grant Wi‑Fi permission to improve indoor accuracy.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(onClick = { onPermissionGranted(Permission.NEARBY_WIFI_DEVICES) }) {
                        Text("Grant Wi‑Fi permission")
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // WiFi tracking section
        EnhancedFeatureCard(
            icon = Icons.Default.Wifi,
            title = "WiFi-Based Indoor Tracking",
            description = "Improve location accuracy when GPS is weak indoors",
            benefits = listOf(
                "Better indoor position detection",
                "Reduced battery usage indoors",
                "More accurate building-level tracking"
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
            title = "Smart Notifications",
            description = "Get helpful insights about your movement patterns",
            benefits = listOf(
                "Daily movement summaries",
                "Achievement notifications",
                "Tracking status updates"
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
                    text = "All these features are optional and can be enabled or disabled anytime in settings. Your privacy remains protected.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
        
        Spacer(modifier = Modifier.weight(1f))
        Spacer(modifier = Modifier.height(16.dp))
        
        // Navigation buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.weight(1f)
            ) {
                Text("Back")
            }
            
            Button(
                onClick = onContinue,
                modifier = Modifier.weight(2f)
            ) {
                Text("Continue")
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        TextButton(
            onClick = onSkip,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Skip enhanced features")
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
                    Text("Grant Permission")
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
