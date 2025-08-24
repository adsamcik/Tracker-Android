package com.adsamcik.tracker.app.onboarding.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.app.onboarding.data.*

/**
 * Basic tracking preferences screen allowing users to choose their tracking profile.
 * Focuses on simple, understandable options rather than complex technical settings.
 */
@Composable
fun BasicPreferencesScreen(
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
        
        // Header
        Icon(
            imageVector = Icons.Default.Settings,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = "Tracking Preferences",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.Bold
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = "Choose your tracking style. You can always change these settings later.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // Tracking profile selection
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .selectableGroup()
            ) {
                Text(
                    text = "Tracking Profile",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Precise tracking option
                TrackingProfileOption(
                    icon = Icons.Default.Speed,
                    title = "Precise Tracking",
                    description = "Best accuracy for detailed route analysis and statistics",
                    batteryImpact = "Higher battery usage",
                    isSelected = preferences.trackingProfile == TrackingProfile.PRECISE,
                    onClick = {
                        onPreferencesUpdate(
                            preferences.copy(trackingProfile = TrackingProfile.PRECISE)
                        )
                    }
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // Balanced tracking option
                TrackingProfileOption(
                    icon = Icons.Default.BatteryFull,
                    title = "Balanced Tracking",
                    description = "Good accuracy with reasonable battery consumption",
                    batteryImpact = "Moderate battery usage",
                    isSelected = preferences.trackingProfile == TrackingProfile.BALANCED,
                    onClick = {
                        onPreferencesUpdate(
                            preferences.copy(trackingProfile = TrackingProfile.BALANCED)
                        )
                    }
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // Eco tracking option
                TrackingProfileOption(
                    icon = Icons.Default.Eco,
                    title = "Eco Tracking",
                    description = "Basic tracking optimized for maximum battery life",
                    batteryImpact = "Minimal battery usage",
                    isSelected = preferences.trackingProfile == TrackingProfile.ECO,
                    onClick = {
                        onPreferencesUpdate(
                            preferences.copy(trackingProfile = TrackingProfile.ECO)
                        )
                    }
                )
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Additional preferences
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier.padding(20.dp)
            ) {
                Text(
                    text = "Additional Settings",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Auto-start tracking
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Auto-start tracking",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Start tracking automatically when you move",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = preferences.autoStartTracking,
                        onCheckedChange = { enabled ->
                            onPreferencesUpdate(
                                preferences.copy(autoStartTracking = enabled)
                            )
                        }
                    )
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Smart pause
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Smart pause",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Pause tracking when stationary for extended periods",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = preferences.enableSmartPause,
                        onCheckedChange = { enabled ->
                            onPreferencesUpdate(
                                preferences.copy(enableSmartPause = enabled)
                            )
                        }
                    )
                }
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
    }
}

@Composable
private fun TrackingProfileOption(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    batteryImpact: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .selectable(
                selected = isSelected,
                onClick = onClick,
                role = Role.RadioButton
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
            contentColor = if (isSelected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            }
        ),
        border = if (isSelected) {
            CardDefaults.outlinedCardBorder().copy(
                brush = androidx.compose.foundation.BorderStroke(
                    2.dp,
                    MaterialTheme.colorScheme.primary
                ).brush
            )
        } else null
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = batteryImpact,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            
            RadioButton(
                selected = isSelected,
                onClick = null // Handled by card's selectable
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun BasicPreferencesScreenPreview() {
    MaterialTheme {
        BasicPreferencesScreen(
            preferences = UserPreferences(
                trackingProfile = TrackingProfile.BALANCED,
                autoStartTracking = true,
                enableSmartPause = true
            ),
            onPreferencesUpdate = {},
            onContinue = {},
            onBack = {}
        )
    }
}
