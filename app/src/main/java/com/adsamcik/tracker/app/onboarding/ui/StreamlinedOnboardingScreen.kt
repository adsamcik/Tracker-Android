package com.adsamcik.tracker.app.onboarding.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.R
import com.adsamcik.tracker.app.onboarding.ui.components.LocationPrecisionMode
import com.adsamcik.tracker.app.onboarding.ui.components.LocationPrecisionSelector
import com.adsamcik.tracker.shared.utils.style.compose.PrimaryActionButton
import androidx.compose.ui.platform.testTag

/**
 * Streamlined single-screen onboarding following Apple-style philosophy:
 * Redesigned with "Outdoor Modern" aesthetic.
 * - Single welcome screen with clear value proposition
 * - Location precision choice (APPROXIMATE vs PRECISE)
 * - Smart defaults applied immediately (no configuration required)
 * - Contextual permissions requested when needed (not upfront)
 * - Time-to-first-track: <30 seconds
 */
@Composable
fun StreamlinedOnboardingScreen(
    onComplete: (LocationPrecisionMode) -> Unit,
    modifier: Modifier = Modifier
) {
    var showPrecisionSelector by remember { mutableStateOf(false) }
    
    // Background container
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        if (showPrecisionSelector) {
            // Show location precision selection screen
            LocationPrecisionSelectorScreen(
                onModeSelected = { mode ->
                    onComplete(mode)
                },
                onBack = {
                    showPrecisionSelector = false
                }
            )
        } else {
            // Show welcome screen
            WelcomeScreen(
                onGetStarted = { showPrecisionSelector = true }
            )
        }
    }
}

/**
 * Welcome screen content
 */
@Composable
private fun WelcomeScreen(
    onGetStarted: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        containerColor = Color.Transparent, // Let DeepVoid show through
        contentWindowInsets = WindowInsets.safeDrawing
    ) { contentPadding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState())
                .imePadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(48.dp))
            
            // Animated app icon
            val scale = remember { Animatable(0f) }
            LaunchedEffect(Unit) {
                scale.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 600)
                )
            }
            
            Icon(
                imageVector = Icons.Default.MyLocation,
                contentDescription = null,
                modifier = Modifier
                    .size(120.dp)
                    .scale(scale.value),
                tint = MaterialTheme.colorScheme.primary
            )
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // Main value proposition
            Text(
                text = stringResource(R.string.onboarding_streamlined_title),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = stringResource(R.string.onboarding_streamlined_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.height(48.dp))
            
            // Three key benefits
            BenefitItem(
                icon = Icons.Default.Security,
                title = stringResource(R.string.onboarding_benefit_privacy_title),
                description = stringResource(R.string.onboarding_benefit_privacy_desc)
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            BenefitItem(
                icon = Icons.Default.Insights,
                title = stringResource(R.string.onboarding_benefit_insights_title),
                description = stringResource(R.string.onboarding_benefit_insights_desc)
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            BenefitItem(
                icon = Icons.Default.EmojiEvents,
                title = stringResource(R.string.onboarding_benefit_gamification_title),
                description = stringResource(R.string.onboarding_benefit_gamification_desc)
            )
            
            Spacer(modifier = Modifier.height(48.dp))
            
            // Primary CTA
            PrimaryActionButton(
                text = stringResource(R.string.onboarding_get_started),
                onClick = onGetStarted,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("onboarding_cta_primary")
            )
            
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

/**
 * Location precision selector screen
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocationPrecisionSelectorScreen(
    onModeSelected: (LocationPrecisionMode) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedMode by remember { mutableStateOf<LocationPrecisionMode?>(null) }
    
    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            CenterAlignedTopAppBar(
                title = { },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("onboarding_cta_back").size(48.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack, 
                            contentDescription = stringResource(R.string.action_navigate_back),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        }
    ) { contentPadding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(horizontal = 24.dp)
                .imePadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Scrollable content area
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(16.dp))
                
                // Use the LocationPrecisionSelector component
                LocationPrecisionSelector(
                    selectedMode = selectedMode,
                    onModeSelected = { mode ->
                        selectedMode = mode
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                
                Spacer(modifier = Modifier.height(24.dp))
            }
            
            // Continue button - pinned at bottom
            PrimaryActionButton(
                text = stringResource(R.string.button_continue),
                onClick = { selectedMode?.let { onModeSelected(it) } },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("onboarding_cta_primary"),
                enabled = selectedMode != null
            )
            
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun BenefitItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier
                .size(48.dp)
                .padding(top = 4.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        
        Spacer(modifier = Modifier.width(16.dp))
        
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            
            Spacer(modifier = Modifier.height(4.dp))
            
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun StreamlinedOnboardingScreenPreview() {
    MaterialTheme {
        StreamlinedOnboardingScreen(
            onComplete = {}
        )
    }
}
