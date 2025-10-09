package com.adsamcik.tracker.app.onboarding.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.adsamcik.tracker.R

/**
 * Value demonstration screen - Stage 2 of onboarding
 * Shows app value through interactive preview and mock data
 */
@Composable
fun ValueDemoScreen(
    onContinue: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Title
        Text(
            text = stringResource(R.string.onboarding_value_demo_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = stringResource(R.string.onboarding_value_demo_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // Feature preview cards
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            FeaturePreviewCard(
                icon = Icons.Default.LocationOn,
                title = stringResource(R.string.onboarding_value_demo_card_movement_title),
                description = stringResource(R.string.onboarding_value_demo_card_movement_description),
                previewContent = {
                    LocationPreview()
                }
            )
            
            FeaturePreviewCard(
                icon = Icons.AutoMirrored.Filled.DirectionsRun,
                title = stringResource(R.string.onboarding_value_demo_card_activity_title),
                description = stringResource(R.string.onboarding_value_demo_card_activity_description),
                previewContent = {
                    ActivityPreview()
                }
            )
            
            FeaturePreviewCard(
                icon = Icons.Default.Analytics,
                title = stringResource(R.string.onboarding_value_demo_card_insights_title),
                description = stringResource(R.string.onboarding_value_demo_card_insights_description),
                previewContent = {
                    InsightsPreview()
                }
            )
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // Call to action
        Text(
            text = stringResource(R.string.onboarding_value_demo_ready_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground
        )
        
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
        
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
fun FeaturePreviewCard(
    icon: ImageVector,
    title: String,
    description: String,
    previewContent: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(false) }
    
    Card(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = tween(300)),
        onClick = { isExpanded = !isExpanded }
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
                
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                Icon(
                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = stringResource(
                        if (isExpanded) {
                            R.string.onboarding_value_demo_collapse
                        } else {
                            R.string.onboarding_value_demo_expand
                        }
                    ),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            if (isExpanded) {
                Spacer(modifier = Modifier.height(16.dp))
                previewContent()
            }
        }
    }
}

@Composable
fun LocationPreview() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Map,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp)
            )
            Text(
                text = stringResource(R.string.onboarding_value_demo_card_location_preview),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun ActivityPreview() {
    val walking = stringResource(R.string.onboarding_value_demo_activity_walking)
    val walkingDuration = stringResource(R.string.onboarding_value_demo_activity_walking_duration)
    val driving = stringResource(R.string.onboarding_value_demo_activity_driving)
    val drivingDuration = stringResource(R.string.onboarding_value_demo_activity_driving_duration)
    val still = stringResource(R.string.onboarding_value_demo_activity_still)
    val stillDuration = stringResource(R.string.onboarding_value_demo_activity_still_duration)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ActivityCard(walking, walkingDuration, Icons.AutoMirrored.Filled.DirectionsWalk, Modifier.weight(1f))
        ActivityCard(driving, drivingDuration, Icons.Default.DirectionsCar, Modifier.weight(1f))
        ActivityCard(still, stillDuration, Icons.Default.Hotel, Modifier.weight(1f))
    }
}

@Composable
fun ActivityCard(
    activity: String,
    duration: String,
    icon: ImageVector,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = activity,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = activity,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = duration,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun InsightsPreview() {
    val distanceLabel = stringResource(R.string.onboarding_value_demo_insight_distance_label)
    val distanceValue = stringResource(R.string.onboarding_value_demo_insight_distance_value)
    val locationLabel = stringResource(R.string.onboarding_value_demo_insight_location_label)
    val locationValue = stringResource(R.string.onboarding_value_demo_insight_location_value)
    val activeTimeLabel = stringResource(R.string.onboarding_value_demo_insight_active_time_label)
    val activeTimeValue = stringResource(R.string.onboarding_value_demo_insight_active_time_value)
    val placesLabel = stringResource(R.string.onboarding_value_demo_insight_places_label)
    val placesValue = stringResource(R.string.onboarding_value_demo_insight_places_value)

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        InsightRow(distanceLabel, distanceValue)
        InsightRow(locationLabel, locationValue)
        InsightRow(activeTimeLabel, activeTimeValue)
        InsightRow(placesLabel, placesValue)
    }
}

@Composable
fun InsightRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

@Preview(showBackground = true)
@Composable
fun ValueDemoScreenPreview() {
    MaterialTheme {
        ValueDemoScreen(
            onContinue = {},
            onBack = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
fun FeaturePreviewCardPreview() {
    MaterialTheme {
        FeaturePreviewCard(
            icon = Icons.Default.LocationOn,
            title = "See where you go",
            description = "Visualize your daily journeys and discover patterns",
            previewContent = { LocationPreview() }
        )
    }
}
