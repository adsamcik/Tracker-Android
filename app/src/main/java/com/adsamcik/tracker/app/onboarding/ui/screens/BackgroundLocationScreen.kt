package com.adsamcik.tracker.app.onboarding.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
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
 * Background location permission screen with careful explanation and user control.
 * Only requests this sensitive permission after establishing clear value and necessity.
 */
@Composable
fun BackgroundLocationScreen(
    grantedPermissions: Set<Permission>,
    onPermissionGranted: (Permission) -> Unit,
    onPermissionDenied: (Permission, String?) -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hasLocationPermission = Permission.LOCATION_FOREGROUND in grantedPermissions
    val hasBackgroundPermission = Permission.LOCATION_BACKGROUND in grantedPermissions
    
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
            imageVector = Icons.Default.GpsFixed,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = if (hasBackgroundPermission) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = if (hasBackgroundPermission) {
                "Background Tracking Enabled!"
            } else {
                "Background Location Tracking"
            },
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.Bold
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = if (hasBackgroundPermission) {
                "Automatic tracking is now enabled. The app can track your movements even when minimized."
            } else if (!hasLocationPermission) {
                "Location permission is required before enabling background tracking."
            } else {
                "Enable automatic tracking when the app is in the background for seamless movement logging."
            },
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        if (!hasLocationPermission) {
            // Show that foreground location is required first
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    
                    Spacer(modifier = Modifier.width(16.dp))
                    
                    Text(
                        text = "Location access is required before enabling background tracking. Please go back and enable location first.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
            
        } else if (!hasBackgroundPermission) {
            // Benefits of background tracking
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
                        text = "Why enable background tracking?",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    BackgroundBenefitItem(
                        icon = Icons.Default.Schedule,
                        title = "Automatic Tracking",
                        description = "No need to manually start/stop tracking sessions"
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    BackgroundBenefitItem(
                        icon = Icons.Default.GpsFixed,
                        title = "Complete Journey Capture",
                        description = "Track entire trips even when switching between apps"
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    BackgroundBenefitItem(
                        icon = Icons.Default.Schedule,
                        title = "Better Insights",
                        description = "More complete data for better movement patterns"
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Important considerations
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
                                text = "Battery Usage",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Background tracking uses more battery. The app is optimized to minimize impact, but you may notice increased battery usage.",
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
                                text = "Privacy Protected",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "All location data remains on your device. We never upload or share your location information.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // Permission request button
            Button(
                onClick = { 
                    onPermissionGranted(Permission.LOCATION_BACKGROUND)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Enable Background Tracking")
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            OutlinedButton(
                onClick = onSkip,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Skip - Track manually")
            }
            
        } else {
            // Permission already granted - show confirmation
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
                        imageVector = Icons.Default.GpsFixed,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Text(
                        text = "Background tracking is ready!",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    
                    Text(
                        text = "The app will now automatically track your movements even when running in the background.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        textAlign = TextAlign.Center
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Button(
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Complete Setup")
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
            
            if (!hasBackgroundPermission && hasLocationPermission) {
                Button(
                    onClick = onContinue,
                    enabled = false,
                    modifier = Modifier.weight(2f)
                ) {
                    Text("Continue")
                }
            } else if (hasLocationPermission) {
                Button(
                    onClick = onContinue,
                    modifier = Modifier.weight(2f)
                ) {
                    Text("Continue")
                }
            }
        }
    }
}

@Composable
private fun BackgroundBenefitItem(
    icon: ImageVector,
    title: String,
    description: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
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
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
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
fun BackgroundLocationScreenPreview() {
    MaterialTheme {
        BackgroundLocationScreen(
            grantedPermissions = setOf(Permission.LOCATION_FOREGROUND),
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
fun BackgroundLocationScreenNoLocationPreview() {
    MaterialTheme {
        BackgroundLocationScreen(
            grantedPermissions = emptySet(),
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
fun BackgroundLocationScreenGrantedPreview() {
    MaterialTheme {
        BackgroundLocationScreen(
            grantedPermissions = setOf(
                Permission.LOCATION_FOREGROUND,
                Permission.LOCATION_BACKGROUND
            ),
            onPermissionGranted = {},
            onPermissionDenied = { _, _ -> },
            onContinue = {},
            onBack = {},
            onSkip = {}
        )
    }
}
