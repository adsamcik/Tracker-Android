package com.adsamcik.tracker.tracker.ui.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.adsamcik.tracker.shared.base.data.CollectionData
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.extension.formatAsDuration
import com.adsamcik.tracker.shared.utils.extension.formatDistance
import com.adsamcik.tracker.shared.utils.style.compose.AppColors
import com.adsamcik.tracker.shared.utils.style.compose.PrimaryActionButton
import com.adsamcik.tracker.tracker.R
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.adsamcik.tracker.shared.utils.style.compose.GlassCard
import com.adsamcik.tracker.shared.preferences.settings.TrackerSettingsQuick
import com.adsamcik.tracker.tracker.ui.viewmodel.TrackerDashboardViewModel
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

data class TrackerFocusState(
    val isTracking: Boolean = false,
    val isLocked: Boolean = false,
    val sessionData: TrackerSession? = null,
    val collectionData: CollectionData? = null,
    val hasLocationPermission: Boolean = false
)

@Composable
fun TrackerDashboardFocus(
    state: TrackerFocusState,
    onSettingsClick: () -> Unit,
    onToggleTracking: (Boolean) -> Unit,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    contentPadding: androidx.compose.foundation.layout.PaddingValues = androidx.compose.foundation.layout.PaddingValues(0.dp),
    viewModel: TrackerDashboardViewModel = hiltViewModel()
) {
    val weeklyStats by viewModel.weeklyStats.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Main Content
        // We use a Column that fills size. Inner views handle scrolling if needed.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AnimatedVisibility(
                visible = !state.isTracking,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                FocusIdleView(
                    lastSession = state.sessionData,
                    weeklyStats = weeklyStats
                )
            }

            AnimatedVisibility(
                visible = state.isTracking,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                FocusTrackingView(
                    sessionData = state.sessionData,
                    collectionData = state.collectionData,
                    onStop = { onToggleTracking(false) }
                )
            }
        }
        
        // Floating Controls (Only visible when NOT tracking)
        // Positioned at the bottom, above navigation/padding
        AnimatedVisibility(
            visible = !state.isTracking,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(contentPadding) // Respect system logging
                .padding(horizontal = 16.dp, vertical = 16.dp)
        ) {
            DashboardControlBar(
                onStartClick = { onToggleTracking(true) },
                onSettingsClick = onSettingsClick
            )
        }
        
        // Snackbar Host
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 100.dp)
        )
    }
}

@Composable
private fun FocusIdleView(
    lastSession: TrackerSession?,
    weeklyStats: List<com.adsamcik.tracker.statistics.data.Stat>
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(top = 48.dp, bottom = 120.dp), // Extra bottom padding for floating bar
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // Header
        Column {
            Text(
                text = "Tracker",
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
            )
            Text(
                text = "Overview",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Last Session Card
        if (lastSession != null) {
            LastSessionCard(session = lastSession)
        } else {
             // Empty state placeholder if no session exists
             GlassCard(modifier = Modifier.fillMaxWidth()) {
                 Box(
                     modifier = Modifier.padding(24.dp).fillMaxWidth(),
                     contentAlignment = Alignment.Center
                 ) {
                     Column(horizontalAlignment = Alignment.CenterHorizontally) {
                         Icon(
                             painter = painterResource(id = com.adsamcik.tracker.shared.base.R.drawable.ic_outline_directions_run_24px),
                             contentDescription = null,
                             modifier = Modifier.size(48.dp),
                             tint = MaterialTheme.colorScheme.primary
                         )
                         Spacer(modifier = Modifier.height(16.dp))
                         Text(
                             text = "Ready to start your first session?",
                             style = MaterialTheme.typography.bodyLarge,
                             color = MaterialTheme.colorScheme.onSurfaceVariant
                         )
                     }
                 }
             }
        }

        // Weekly Progress
        if (weeklyStats.isNotEmpty()) {
            WeeklyProgressRow(stats = weeklyStats)
        }
    }
}

@Composable
private fun FocusTrackingView(
    sessionData: TrackerSession?,
    collectionData: CollectionData?,
    onStop: () -> Unit
) {
    val context = LocalContext.current
    val settings = remember { TrackerSettingsQuick.snapshot(context) }
    
    val durationText = remember(sessionData) {
        val duration = if (sessionData != null) {
            System.currentTimeMillis() - sessionData.start
        } else 0L
        duration.formatAsDuration(context)
    }

    val distanceText = remember(sessionData) {
        if (sessionData != null) {
            context.resources.formatDistance(sessionData.distanceInM, 1, settings.lengthSystem)
        } else "0.0 km"
    }

    val wifiCount = collectionData?.wifi?.inRange?.size ?: 0
    val cellCount = collectionData?.cell?.totalCount ?: 0

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(48.dp)
        ) {
            FocusMetricsDisplay(
                durationText = durationText,
                distanceText = distanceText
            )
            
            FocusSignalDisplay(
                wifiCount = wifiCount,
                cellCount = cellCount
            )
            
            // Stop Button (Placeholder for gesture)
            val stopDescription = stringResource(id = R.string.description_tracking_stop)
            PrimaryActionButton(
                text = "STOP TRACKING",
                onClick = onStop,
                modifier = Modifier
                    .padding(horizontal = 32.dp)
                    .testTag("tracking_fab")
                    .semantics { contentDescription = stopDescription }
            )
        }
    }
}
