package com.adsamcik.tracker.app.onboarding.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.DriveEta
import androidx.compose.material.icons.filled.SelfImprovement
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
 * Activity recognition setup screen explaining activity detection benefits.
 * Requests activity recognition permission with clear value proposition.
 */
@Composable
fun ActivitySetupScreen(
    grantedPermissions: Set<Permission>,
    onPermissionGranted: (Permission) -> Unit,
    onPermissionDenied: (Permission, String?) -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hasActivityPermission = Permission.ACTIVITY_RECOGNITION in grantedPermissions
    
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
            imageVector = Icons.AutoMirrored.Filled.DirectionsRun,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = stringResource(
                if (hasActivityPermission) {
                    R.string.onboarding_activity_enabled_title
                } else {
                    R.string.onboarding_activity_title
                }
            ),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.Bold
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = stringResource(
                if (hasActivityPermission) {
                    R.string.onboarding_activity_enabled_subtitle
                } else {
                    R.string.onboarding_activity_subtitle
                }
            ),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        if (!hasActivityPermission) {
            // Activity detection examples
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
                        text = stringResource(R.string.onboarding_activity_detects_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    ActivityTypeItem(
                        icon = Icons.AutoMirrored.Filled.DirectionsWalk,
                        activity = stringResource(R.string.onboarding_activity_type_walking_title),
                        description = stringResource(R.string.onboarding_activity_type_walking_description)
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    ActivityTypeItem(
                        icon = Icons.AutoMirrored.Filled.DirectionsRun,
                        activity = stringResource(R.string.onboarding_activity_type_running_title),
                        description = stringResource(R.string.onboarding_activity_type_running_description)
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    ActivityTypeItem(
                        icon = Icons.Default.DriveEta,
                        activity = stringResource(R.string.onboarding_activity_type_driving_title),
                        description = stringResource(R.string.onboarding_activity_type_driving_description)
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    ActivityTypeItem(
                        icon = Icons.Default.SelfImprovement,
                        activity = stringResource(R.string.onboarding_activity_type_stationary_title),
                        description = stringResource(R.string.onboarding_activity_type_stationary_description)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Benefits card
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
                        text = stringResource(R.string.onboarding_activity_why_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Text(
                        text = stringResource(R.string.onboarding_activity_benefits),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // Permission request button
            Button(
                onClick = { 
                    onPermissionGranted(Permission.ACTIVITY_RECOGNITION)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("onboarding_cta_primary")
            ) {
                Text(stringResource(R.string.onboarding_activity_enable_button))
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
                        imageVector = Icons.AutoMirrored.Filled.DirectionsRun,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Text(
                        text = stringResource(R.string.onboarding_activity_ready_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    
                    Text(
                        text = stringResource(R.string.onboarding_activity_ready_message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        textAlign = TextAlign.Center
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Button(
                onClick = onContinue,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("onboarding_cta_primary")
            ) {
                Text(stringResource(R.string.onboarding_continue_setup_button))
            }
        }
        
    Spacer(modifier = Modifier.height(32.dp))
        
        // Navigation buttons
        if (!hasActivityPermission) {
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
                    enabled = false,
                    modifier = Modifier
                        .weight(2f)
                        .testTag("onboarding_cta_primary")
                ) {
                    Text(stringResource(R.string.onboarding_button_continue))
                }
            }
        } else {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("onboarding_cta_back")
            ) {
                Text(stringResource(R.string.onboarding_button_back))
            }
        }
    }
}

@Composable
private fun ActivityTypeItem(
    icon: ImageVector,
    activity: String,
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
                text = activity,
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
fun ActivitySetupScreenPreview() {
    MaterialTheme {
        ActivitySetupScreen(
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
fun ActivitySetupScreenGrantedPreview() {
    MaterialTheme {
        ActivitySetupScreen(
            grantedPermissions = setOf(Permission.ACTIVITY_RECOGNITION),
            onPermissionGranted = {},
            onPermissionDenied = { _, _ -> },
            onContinue = {},
            onBack = {},
            onSkip = {}
        )
    }
}
