package com.adsamcik.tracker.app.onboarding.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.R
import com.adsamcik.tracker.app.onboarding.data.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource

/**
 * Location setup screen explaining location benefits and requesting permission.
 * Intelligently requests both foreground and background permissions when needed.
 */
@Composable
fun LocationSetupScreen(
    grantedPermissions: Set<Permission>,
    userPreferences: UserPreferences,
    onPermissionGranted: (Permission) -> Unit,
    onPermissionDenied: (Permission, String?) -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hasLocationPermission = Permission.LOCATION_FOREGROUND in grantedPermissions
    val hasBackgroundPermission = Permission.LOCATION_BACKGROUND in grantedPermissions
    
    // Determine if background tracking is enabled
    val needsBackgroundTracking = userPreferences.autoTrackingModeIndex > 0 || userPreferences.enableAutomaticTracking
    
    // Determine what we need to show
    val needsForegroundPermission = !hasLocationPermission
    val needsBackgroundPermission = needsBackgroundTracking && !hasBackgroundPermission && hasLocationPermission
    val allPermissionsGranted = hasLocationPermission && (!needsBackgroundTracking || hasBackgroundPermission)
    
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(16.dp))
        
        // Header icon
        Icon(
            imageVector = Icons.Default.LocationOn,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = if (allPermissionsGranted) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = when {
                allPermissionsGranted -> "Location Enabled!"
                needsBackgroundPermission -> "Enable Background Location"
                else -> "Enable Location Tracking"
            },
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.Bold
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = when {
                allPermissionsGranted -> {
                    if (needsBackgroundTracking) {
                        "Location and background tracking are enabled. Automatic tracking will work seamlessly!"
                    } else {
                        "Location tracking is enabled. You can start discovering your movement patterns!"
                    }
                }
                needsBackgroundPermission -> {
                    "For automatic tracking to work properly, the app needs background location access. This allows tracking even when the app is closed."
                }
                needsBackgroundTracking -> {
                    "Since you've enabled automatic tracking, this app needs both location access and background location permission to track your movements automatically."
                }
                else -> {
                    "Enable location access to track your routes and discover your daily patterns."
                }
            },
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        if (needsForegroundPermission) {
            // Benefits of location tracking
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(20.dp)
                ) {
                    Text(
                        text = stringResource(R.string.onboarding_location_benefits_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    LocationBenefitItem(
                        icon = Icons.Default.Map,
                        title = "Route Visualization",
                        description = "See your paths on an interactive map"
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    LocationBenefitItem(
                        icon = Icons.Default.Timeline,
                        title = "Movement Insights",
                        description = "Discover patterns in your daily travel"
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    LocationBenefitItem(
                        icon = Icons.Default.Route,
                        title = "Distance & Speed",
                        description = "Track how far and fast you move"
                    )
                    
                    // Add background tracking benefits if needed
                    if (needsBackgroundTracking) {
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        LocationBenefitItem(
                            icon = Icons.Default.Schedule,
                            title = "Automatic Tracking",
                            description = "Track movements even when app is closed"
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Privacy assurance
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                border = CardDefaults.outlinedCardBorder()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.LocationOn,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    
                    Spacer(modifier = Modifier.width(12.dp))
                    
                    Column {
                        Text(
                            text = stringResource(R.string.onboarding_privacy_protected_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = stringResource(R.string.onboarding_privacy_message),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            
            // Show background location explanation if needed
            if (needsBackgroundTracking) {
                Spacer(modifier = Modifier.height(16.dp))
                
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                imageVector = Icons.Default.Schedule,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            
                            Spacer(modifier = Modifier.width(12.dp))
                            
                            Column {
                                Text(
                                    text = stringResource(R.string.onboarding_background_location_required_title),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = stringResource(R.string.onboarding_background_location_required_message),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // Permission request button
            Button(
                onClick = { 
                    // Request location permission (foreground first)
                    onPermissionGranted(Permission.LOCATION_FOREGROUND)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("onboarding_cta_primary")
            ) {
                Text(stringResource(R.string.onboarding_location_enable_button))
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            OutlinedButton(
                onClick = onSkip,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("onboarding_cta_skip")
            ) {
                Text(stringResource(R.string.button_skip))
            }
            
        } else if (needsBackgroundPermission) {
            // Show background permission explanation and request
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(20.dp)
                ) {
                    Text(
                        text = stringResource(R.string.onboarding_background_benefits_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    LocationBenefitItem(
                        icon = Icons.Default.Schedule,
                        title = "Automatic Tracking",
                        description = "Seamlessly track activities without manual start/stop"
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    LocationBenefitItem(
                        icon = Icons.Default.Timeline,
                        title = "Complete Journey",
                        description = "Capture your entire route, not just when app is open"
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    LocationBenefitItem(
                        icon = Icons.Default.Route,
                        title = "Better Insights",
                        description = "Get accurate daily movement patterns"
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Important considerations for background location
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
                    Row(
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Default.BatteryAlert,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        
                        Spacer(modifier = Modifier.width(12.dp))
                        
                        Column {
                            Text(
                                text = stringResource(R.string.onboarding_battery_usage_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = stringResource(R.string.onboarding_battery_usage_message),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Row(
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        
                        Spacer(modifier = Modifier.width(12.dp))
                        
                        Column {
                            Text(
                                text = stringResource(R.string.onboarding_privacy_protected_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = stringResource(R.string.onboarding_privacy_message),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // Background permission request button
            Button(
                onClick = { 
                    onPermissionGranted(Permission.LOCATION_BACKGROUND)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("onboarding_cta_primary")
            ) {
                Text(stringResource(R.string.onboarding_background_location_enable_button))
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            OutlinedButton(
                onClick = onContinue,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("onboarding_cta_skip")
            ) {
                Text(stringResource(R.string.onboarding_continue_without_background_button))
            }
            
        } else {
            // All permissions granted - show confirmation
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.LocationOn,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Text(
                        text = if (needsBackgroundTracking) "Location & Background Tracking Ready!" else "Location Ready!",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    
                    Text(
                        text = if (needsBackgroundTracking) {
                            "Automatic tracking is now fully enabled and ready to work seamlessly."
                        } else {
                            "Location tracking is enabled. You can now track your movements."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        textAlign = TextAlign.Center
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            Button(
                onClick = onContinue,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("onboarding_cta_primary")
            ) {
                Text(stringResource(R.string.button_continue))
            }
        }
    }
}

@Composable
private fun LocationBenefitItem(
    icon: ImageVector,
    title: String,
    description: String
) {
    Row(
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        
        Spacer(modifier = Modifier.width(12.dp))
        
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun LocationSetupScreenPreview() {
    MaterialTheme {
        LocationSetupScreen(
            grantedPermissions = emptySet(),
            userPreferences = UserPreferences(
                enableLocationTracking = true,
                autoTrackingModeIndex = 1 // Enable background tracking
            ),
            onPermissionGranted = {},
            onPermissionDenied = { _, _ -> },
            onContinue = {},
            onBack = {},
            onSkip = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
fun LocationSetupScreenGrantedPreview() {
    MaterialTheme {
        LocationSetupScreen(
            grantedPermissions = setOf(Permission.LOCATION_FOREGROUND),
            userPreferences = UserPreferences(
                enableLocationTracking = true,
                autoTrackingModeIndex = 0 // No background tracking
            ),
            onPermissionGranted = {},
            onPermissionDenied = { _, _ -> },
            onContinue = {},
            onBack = {},
            onSkip = {}
        )
    }
}
