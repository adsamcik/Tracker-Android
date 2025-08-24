package com.adsamcik.tracker.app.onboarding.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.app.onboarding.data.*

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
            .padding(24.dp),
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
            text = "Setup Complete!",
            style = MaterialTheme.typography.headlineLarge,
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.Bold
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        Text(
            text = "Tracker is ready to help you discover your daily patterns and insights.",
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
            Column(
                modifier = Modifier.padding(20.dp)
            ) {
                Text(
                    text = "Features Enabled:",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                val enabledFeatures = buildList {
                    if (Permission.LOCATION_FOREGROUND in grantedPermissions) {
                        add("📍 Location tracking")
                    }
                    if (Permission.ACTIVITY_RECOGNITION in grantedPermissions) {
                        add("🏃 Activity detection") 
                    }
                    if (Permission.LOCATION_BACKGROUND in grantedPermissions) {
                        add("🔄 Background tracking")
                    }
                    if (preferences.enableWifiTracking && Permission.NEARBY_WIFI_DEVICES in grantedPermissions) {
                        add("📶 WiFi-based indoor tracking")
                    }
                    if (preferences.enableNotifications && Permission.NOTIFICATIONS in grantedPermissions) {
                        add("🔔 Smart notifications")
                    }
                    add("🛡️ Privacy-first data storage")
                    add("⚙️ ${preferences.trackingProfile.name.lowercase().replaceFirstChar { it.uppercase() }} tracking profile")
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
                    text = "What's Next?",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = "• Start your first tracking session\n" +
                           "• Explore the map view to see your routes\n" +
                           "• Check statistics for movement insights\n" +
                           "• Adjust settings anytime in preferences",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Button(
            onClick = onComplete,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Start Tracking!")
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
                    text = "Coming in Stage 2+",
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
                Text("Back")
            }
            
            Button(
                onClick = onContinue,
                modifier = Modifier.weight(2f)
            ) {
                Text("Continue")
            }
        }
        
        if (showSkip && onSkip != null) {
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(
                onClick = onSkip,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Skip for now")
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
