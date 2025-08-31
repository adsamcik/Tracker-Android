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
            text = "What can Tracker do for you?",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = "A quick peek at the insights Tracker can surface for you",
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
                title = "Your movement, visualized",
                description = "See your daily paths, time at places, and how your days flow",
                previewContent = {
                    LocationPreview()
                }
            )
            
            FeaturePreviewCard(
                icon = Icons.AutoMirrored.Filled.DirectionsRun,
                title = "Automatic activity detection",
                description = "Walk, drive, stay still — Tracker classifies it for you",
                previewContent = {
                    ActivityPreview()
                }
            )
            
            FeaturePreviewCard(
                icon = Icons.Default.Analytics,
                title = "Meaningful insights",
                description = "Daily and weekly summaries help you spot trends and changes",
                previewContent = {
                    InsightsPreview()
                }
            )
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // Call to action
        Text(
            text = "Ready to set up tracking your way?",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground
        )
        
        Spacer(modifier = Modifier.weight(1f))
        
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
                Text("Back")
            }
            
            Button(
                onClick = onContinue,
                modifier = Modifier
                    .weight(2f)
                    .testTag("onboarding_cta_primary")
            ) {
                Text("Continue")
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
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
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
                text = "Interactive Map Preview",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun ActivityPreview() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
    ActivityCard("Walking", "2.3 hours", Icons.AutoMirrored.Filled.DirectionsWalk, Modifier.weight(1f))
        ActivityCard("Driving", "1.1 hours", Icons.Default.DirectionsCar, Modifier.weight(1f))
        ActivityCard("Still", "20.6 hours", Icons.Default.Hotel, Modifier.weight(1f))
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
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        InsightRow("Distance traveled today", "12.4 km")
        InsightRow("Most visited location", "Home (8.2 hours)")
        InsightRow("Active time", "3.4 hours")
        InsightRow("Places discovered", "3 new locations")
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
