package com.adsamcik.tracker.app.onboarding.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.R
import com.adsamcik.tracker.app.onboarding.data.*

/**
 * Location setup screen explaining location benefits and requesting permission.
 * Uses just-in-time permission request with clear value proposition.
 */
@Composable
fun LocationSetupScreen(
    grantedPermissions: Set<Permission>,
    onPermissionGranted: (Permission) -> Unit,
    onPermissionDenied: (Permission, String?) -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hasLocationPermission = Permission.LOCATION_FOREGROUND in grantedPermissions
    
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
            tint = MaterialTheme.colorScheme.primary
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = if (hasLocationPermission) "Location Enabled!" else "Enable Location Tracking",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.Bold
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = if (hasLocationPermission) {
                "Location tracking is now enabled. You can start discovering your movement patterns!"
            } else {
                "Enable location access to track your routes and discover your daily patterns."
            },
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        if (!hasLocationPermission) {
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
                        text = "What you'll get:",
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
                            text = "Your privacy is protected",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "All location data stays on your device. We never upload or share your routes.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // Permission request button
            Button(
                onClick = { 
                    // Request location permission
                    onPermissionGranted(Permission.LOCATION_FOREGROUND)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Enable Location Access")
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            OutlinedButton(
                onClick = onSkip,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Skip for now")
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
                        imageVector = Icons.Default.LocationOn,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Text(
                        text = "Location tracking is ready!",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    
                    Text(
                        text = "You can now track your routes and movement patterns.",
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
                Text("Continue Setup")
            }
        }
        
        Spacer(modifier = Modifier.weight(1f))
        Spacer(modifier = Modifier.height(16.dp))
        
        // Navigation buttons (only back button when permission not granted)
        if (!hasLocationPermission) {
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
                
                // Continue button is disabled until permission is granted
                Button(
                    onClick = onContinue,
                    enabled = false,
                    modifier = Modifier.weight(2f)
                ) {
                    Text("Continue")
                }
            }
        } else {
            // Show back button when permission is granted
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Back")
            }
        }
    }
}

@Composable
private fun LocationBenefitItem(
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
fun LocationSetupScreenPreview() {
    MaterialTheme {
        LocationSetupScreen(
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
fun LocationSetupScreenGrantedPreview() {
    MaterialTheme {
        LocationSetupScreen(
            grantedPermissions = setOf(Permission.LOCATION_FOREGROUND),
            onPermissionGranted = {},
            onPermissionDenied = { _, _ -> },
            onContinue = {},
            onBack = {},
            onSkip = {}
        )
    }
}
