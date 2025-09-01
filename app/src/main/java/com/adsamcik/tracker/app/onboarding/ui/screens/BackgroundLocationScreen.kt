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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.adsamcik.tracker.R

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
                stringResource(R.string.onboarding_bg_enabled_title)
            } else {
                stringResource(R.string.onboarding_bg_title)
            },
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.Bold
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = if (hasBackgroundPermission) {
                stringResource(R.string.onboarding_bg_enabled_subtitle)
            } else if (!hasLocationPermission) {
                stringResource(R.string.onboarding_bg_needs_location_subtitle)
            } else {
                stringResource(R.string.onboarding_bg_subtitle)
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
                        text = stringResource(R.string.onboarding_bg_grant_location_first_message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = { onPermissionGranted(Permission.LOCATION_FOREGROUND) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("onboarding_cta_primary")
            ) {
                Text(stringResource(R.string.onboarding_bg_grant_location_first_cta))
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
                        text = stringResource(R.string.onboarding_bg_benefits_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    BackgroundBenefitItem(
                        icon = Icons.Default.Schedule,
                        title = stringResource(R.string.onboarding_bg_benefit_automatic_title),
                        description = stringResource(R.string.onboarding_bg_benefit_automatic_desc)
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    BackgroundBenefitItem(
                        icon = Icons.Default.GpsFixed,
                        title = stringResource(R.string.onboarding_bg_benefit_complete_title),
                        description = stringResource(R.string.onboarding_bg_benefit_complete_desc)
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    BackgroundBenefitItem(
                        icon = Icons.Default.Schedule,
                        title = stringResource(R.string.onboarding_bg_benefit_insights_title),
                        description = stringResource(R.string.onboarding_bg_benefit_insights_desc)
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
                                text = stringResource(R.string.onboarding_bg_battery_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = stringResource(R.string.onboarding_bg_battery_desc),
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
                                text = stringResource(R.string.onboarding_bg_privacy_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = stringResource(R.string.onboarding_bg_privacy_desc),
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
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("onboarding_cta_primary")
            ) {
                Text(stringResource(R.string.onboarding_bg_allow_cta))
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            OutlinedButton(
                onClick = onSkip,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("onboarding_cta_skip")
            ) {
                Text(stringResource(R.string.onboarding_bg_skip))
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
                        text = stringResource(R.string.onboarding_bg_ready_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    
                    Text(
                        text = stringResource(R.string.onboarding_bg_ready_desc),
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
                modifier = Modifier
                    .weight(1f)
                    .testTag("onboarding_cta_back")
            ) {
                Text(stringResource(com.adsamcik.tracker.shared.base.R.string.generic_back))
            }
            
            if (!hasBackgroundPermission && hasLocationPermission) {
                Button(
                    onClick = onContinue,
                    enabled = false,
                    modifier = Modifier
                        .weight(2f)
                        .testTag("onboarding_cta_primary")
                ) {
                    Text(stringResource(com.adsamcik.tracker.shared.base.R.string.generic_continue))
                }
            } else if (hasLocationPermission) {
                Button(
                    onClick = onContinue,
                    modifier = Modifier
                        .weight(2f)
                        .testTag("onboarding_cta_primary")
                ) {
                    Text(stringResource(com.adsamcik.tracker.shared.base.R.string.generic_continue))
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
