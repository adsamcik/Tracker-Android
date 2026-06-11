package com.adsamcik.tracker.tracker.ui.compose

/**
 * TrackerDashboard
 *
 * First Compose iteration of the Tracker screen. Uses Material3 and is structured
 * to be progressively enhanced with the expressive design/animations detailed in the design docs.
 * Currently binds to Flow/StateFlow-backed dashboard state and routes actions back to the host.
 */

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FabPosition
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.concurrency.DefaultDispatchersProvider
import com.adsamcik.tracker.shared.base.data.CollectionData
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.di.DailyPointsProvider
import com.adsamcik.tracker.shared.base.di.DailySummaryProvider
import com.adsamcik.tracker.shared.base.di.GoalProgressProvider
import com.adsamcik.tracker.shared.base.extension.formatReadable
import com.adsamcik.tracker.shared.preferences.settings.TrackerSettingsQuick
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.shared.preferences.tracking.TrackingPreset
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.tracker.R
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

internal val defaultDispatchers = DefaultDispatchersProvider

@Immutable
internal data class TrackerDashboardUiState(
    val isTracking: Boolean = false,
    val isLocked: Boolean = false,
    val sessionData: TrackerSession? = null,
    val collectionData: CollectionData? = null,
    val hasLocationPermission: Boolean = false,
    val pathPoints: List<com.adsamcik.tracker.shared.base.data.Location>? = null,
    val policyTier: PolicyTier = PolicyTier.OFF,
    val precisionModePreset: TrackingPreset = TrackingPreset.BALANCED,
    val trackingParams: TrackingParamsState = TrackingParamsState(),
)

@Composable
internal fun TrackerDashboard(
    state: TrackerDashboardUiState,
    dailyPointsProvider: DailyPointsProvider,
    dailySummaryProvider: DailySummaryProvider,
    goalProgressProvider: GoalProgressProvider,
    onSettingsClick: () -> Unit,
    onMapClick: () -> Unit,
    onRequestPermission: () -> Unit,
    onToggleTracking: (Boolean) -> Unit,
    onGameClick: (() -> Unit)? = null,
    onPrecisionModeToggle: () -> Unit,
    onSessionDetailClick: ((Long) -> Unit)? = null,
    modifier: Modifier = Modifier,
    snackbarHostState: androidx.compose.material3.SnackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
) {
    val isTracking = state.isTracking
    val isLocked = state.isLocked
    val sessionData = state.sessionData
    val collectionData = state.collectionData
    val hasLocationPermission = state.hasLocationPermission
    val wallClockNowMillis = rememberWallClockMillis(isTracking)

    val haptics = LocalHapticFeedback.current
    
    // Milestone haptic feedback - trigger at distance/step milestones during tracking
    MilestoneHapticEffect(
        sessionData = sessionData,
        isTracking = isTracking,
        haptics = haptics
    )

    Scaffold(
        modifier = modifier,
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbarHostState) },
        topBar = {
            TrackerTopBar(
                isTracking = isTracking,
                isLocked = isLocked,
                policyTier = state.policyTier,
                precisionModePreset = state.precisionModePreset,
                dailyPointsProvider = dailyPointsProvider,
                onSettingsClick = onSettingsClick,
                onGameClick = onGameClick,
                onPrecisionModeToggle = onPrecisionModeToggle
            )
        },
        floatingActionButton = {
            TrackingFAB(
                isTracking = isTracking,
                hasPermission = hasLocationPermission,
                onToggleTracking = {
                    if (isTracking) haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.Reject)
                    else haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.Confirm)
                    onToggleTracking(!isTracking)
                },
                onRequestPermission = {
                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    onRequestPermission()
                }
            )
        },
        floatingActionButtonPosition = FabPosition.Center
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            TrackingContent(
                sessionData = sessionData,
                collectionData = collectionData,
                isTracking = isTracking,
                isLocked = isLocked,
                wallClockNowMillis = wallClockNowMillis,
                dailySummaryProvider = dailySummaryProvider,
                goalProgressProvider = goalProgressProvider,
                onSettingsClick = onSettingsClick,
                onMapClick = onMapClick,
                onSessionDetailClick = onSessionDetailClick,
                snackbarHostState = snackbarHostState,
                pathPoints = state.pathPoints,
                trackingParams = state.trackingParams,
            )
        }
    }
}

/**
 * MilestoneHapticEffect - Triggers haptic feedback when tracking milestones are reached.
 * 
 * Milestones:
 * - Every 1000 meters (1 km)
 * - Every 1000 steps
 * - Every 10 minutes of tracking
 */
@Composable
private fun MilestoneHapticEffect(
    sessionData: TrackerSession?,
    isTracking: Boolean,
    haptics: androidx.compose.ui.hapticfeedback.HapticFeedback
) {
    // Track previous milestone values to detect crossings
    var lastDistanceKm by remember { mutableStateOf(0) }
    var lastStepsThousand by remember { mutableStateOf(0) }
    var lastMinutesTen by remember { mutableStateOf(0) }
    
    LaunchedEffect(sessionData, isTracking) {
        if (!isTracking || sessionData == null) {
            // Reset on stop
            lastDistanceKm = 0
            lastStepsThousand = 0
            lastMinutesTen = 0
            return@LaunchedEffect
        }
        
        val currentDistanceKm = (sessionData.distanceInM / 1000f).toInt()
        val currentStepsThousand = sessionData.steps / 1000
        val durationMinutes = ((Time.nowMillis - sessionData.start) / 60000).toInt()
        val currentMinutesTen = durationMinutes / 10
        
        // Check for kilometer milestone
        if (currentDistanceKm > lastDistanceKm && lastDistanceKm > 0) {
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        }
        lastDistanceKm = currentDistanceKm
        
        // Check for steps milestone (every 1000 steps)
        if (currentStepsThousand > lastStepsThousand && lastStepsThousand > 0) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
        lastStepsThousand = currentStepsThousand
        
        // Check for time milestone (every 10 minutes)
        if (currentMinutesTen > lastMinutesTen && lastMinutesTen > 0) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
        lastMinutesTen = currentMinutesTen
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun TrackerTopBar(
    isTracking: Boolean,
    isLocked: Boolean,
    policyTier: PolicyTier = PolicyTier.OFF,
    precisionModePreset: TrackingPreset,
    dailyPointsProvider: DailyPointsProvider,
    onSettingsClick: () -> Unit,
    onGameClick: (() -> Unit)? = null,
    onPrecisionModeToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val pointsToday by dailyPointsProvider.pointsTodayFlow.collectAsState()
    
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surface) {
        Column {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        BadgedBox(badge = {
                            if (isLocked) { Badge { Text("🔒") } }
                        }) {
                            Text(
                                text = if (isTracking) stringResource(R.string.notification_tracking_active) else stringResource(R.string.settings_tracking_title),
                                style = MaterialTheme.typography.titleLarge
                            )
                        }
                    }
                },
                actions = {
                    PolicyTierChip(
                        tier = policyTier,
                        precisionModePreset = precisionModePreset,
                        onClick = onPrecisionModeToggle
                    )

                    // Points chip - show when gamification has points
                    if (pointsToday > 0 && onGameClick != null) {
                        Surface(
                            onClick = onGameClick,
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .semantics {
                                    contentDescription = context.getString(
                                        R.string.description_points_today,
                                        pointsToday.formatReadable()
                                    )
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    Icons.Outlined.Star,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = pointsToday.formatReadable(),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                    }
                    
                    IconButton(onClick = onSettingsClick) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = stringResource(R.string.description_settings)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )

            if (isTracking) {
                // Recording is an active state, not a loading state. A determinate full band with
                // the error color communicates "live recording" without abusing the M3 loading
                // indicator (which would announce "loading" to TalkBack).
                LinearProgressIndicator(
                    progress = { 1f },
                    color = MaterialTheme.colorScheme.error,
                    trackColor = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                )
            }
        }
    }
}

@Composable
private fun rememberWallClockMillis(isTracking: Boolean): Long {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val nowMillis by produceState(
        initialValue = Time.nowMillis,
        key1 = isTracking,
        key2 = lifecycle
    ) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            value = Time.nowMillis
            if (!isTracking) return@repeatOnLifecycle

            while (currentCoroutineContext().isActive) {
                delay(1000)
                value = Time.nowMillis
            }
        }
    }

    return nowMillis
}

@Composable
private fun TrackingContent(
    sessionData: TrackerSession?,
    collectionData: CollectionData?,
    isTracking: Boolean,
    isLocked: Boolean,
    wallClockNowMillis: Long,
    dailySummaryProvider: DailySummaryProvider,
    goalProgressProvider: GoalProgressProvider,
    onSettingsClick: () -> Unit,
    onMapClick: () -> Unit,
    onSessionDetailClick: ((Long) -> Unit)? = null,
    snackbarHostState: androidx.compose.material3.SnackbarHostState,
    pathPoints: List<com.adsamcik.tracker.shared.base.data.Location>? = null,
    trackingParams: TrackingParamsState,
    modifier: Modifier = Modifier
) {
    val configuration = LocalConfiguration.current
    val columns = when {
        configuration.screenWidthDp >= 1000 -> 3
        configuration.screenWidthDp >= 600 -> 2
        else -> 1
    }

    val context = LocalContext.current
    val locationEnabled = trackingParams.locationEnabled
    val cellEnabled = trackingParams.cellEnabled
    val wifiEnabled = trackingParams.wifiEnabled ||
        trackingParams.wifiLocationCountEnabled ||
        trackingParams.wifiNetworkEnabled
    val activityEnabled = trackingParams.activityEnabled
    val trackerSettings = TrackerSettingsQuick.snapshot(context)

    var useDecimalDegrees by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        // Lock banner - only show when locked (no separate row otherwise)
        AnimatedVisibility(visible = isLocked) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically
            ) {
                LockBanner(onClick = onSettingsClick)
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            // Status and quick stats card - only visible when tracking (shows live data)
            // Uses AnimatedVisibility for smooth entrance/exit
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(columns) }, key = "status") {
                AnimatedVisibility(
                    visible = isTracking,
                    enter = expandVertically(
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessLow
                        )
                    ) + fadeIn(animationSpec = tween(300)) + scaleIn(
                        initialScale = 0.95f,
                        animationSpec = tween(300)
                    ),
                    exit = shrinkVertically(
                        animationSpec = tween(200)
                    ) + fadeOut(animationSpec = tween(150))
                ) {
                    StatusAndQuickStatsCard(
                        isTracking = isTracking,
                        sessionData = sessionData,
                        collectionData = collectionData,
                        wallClockNowMillis = wallClockNowMillis,
                        onMapClick = onMapClick,
                        pathPoints = pathPoints
                    )
                }
            }
            
            // Today's progress card - shows aggregated daily stats when not tracking
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(columns) }, key = "today_progress") {
                AnimatedVisibility(
                    visible = !isTracking,
                    enter = expandVertically(
                        animationSpec = tween(300, delayMillis = 100)
                    ) + fadeIn(animationSpec = tween(300, delayMillis = 100)),
                    exit = shrinkVertically(animationSpec = tween(200)) + fadeOut(animationSpec = tween(150))
                ) {
                    TodayProgressCard(
                        isTracking = isTracking,
                        settings = trackerSettings,
                        dailySummaryProvider = dailySummaryProvider,
                        goalProgressProvider = goalProgressProvider
                    )
                }
            }

            // Recent trips card - shows last 3 trips when not tracking
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(columns) }, key = "recent_trips") {
                AnimatedVisibility(
                    visible = !isTracking,
                    enter = expandVertically(
                        animationSpec = tween(300, delayMillis = 200)
                    ) + fadeIn(animationSpec = tween(300, delayMillis = 200)),
                    exit = shrinkVertically(animationSpec = tween(200)) + fadeOut(animationSpec = tween(150))
                ) {
                    RecentTripsCard(
                        settings = trackerSettings,
                        onTripClick = onSessionDetailClick
                    )
                }
            }

            // Detailed session card - show when not tracking for historical data
            if (sessionData != null && !isTracking) {
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(columns) }, key = "session") {
                    AnimatedVisibility(
                        visible = true,
                        enter = expandVertically(
                            animationSpec = tween(300, delayMillis = 150)
                        ) + fadeIn(animationSpec = tween(300, delayMillis = 150)),
                        exit = shrinkVertically(animationSpec = tween(200)) + fadeOut(animationSpec = tween(150))
                    ) {
                        SessionOverviewCard(
                            session = sessionData,
                            isTracking = isTracking,
                            pathPoints = pathPoints,
                            onMapClick = onMapClick,
                            onDetailClick = onSessionDetailClick,
                            snackbarHostState = snackbarHostState
                        )
                    }
                }
            }

            val componentModels = buildList {
                val locationMetrics = buildLocationMetrics(
                    context = context,
                    collectionData = collectionData,
                    settings = trackerSettings,
                    useDecimalDegrees = useDecimalDegrees,
                    onToggleFormat = { useDecimalDegrees = !useDecimalDegrees }
                )
                if (locationMetrics != null) add(
                    TrackingComponentModel(
                        component = TrackingComponent.Location,
                        enabled = locationEnabled,
                        metrics = locationMetrics
                    )
                )

                val activityMetrics = buildActivityMetrics(context, collectionData)
                if (activityMetrics != null) add(
                    TrackingComponentModel(
                        component = TrackingComponent.Activity,
                        enabled = activityEnabled,
                        metrics = activityMetrics
                    )
                )

                val wifiMetrics = buildWifiMetrics(context, collectionData)
                if (wifiMetrics != null) add(
                    TrackingComponentModel(
                        component = TrackingComponent.Wifi,
                        enabled = wifiEnabled,
                        metrics = wifiMetrics
                    )
                )

                val cellMetrics = buildCellMetrics(context, collectionData)
                if (cellMetrics != null) add(
                    TrackingComponentModel(
                        component = TrackingComponent.Cell,
                        enabled = cellEnabled,
                        metrics = cellMetrics
                    )
                )
            }

            // Component details section - collapsible when tracking, shown when not tracking
            if (!isTracking) {
                // Show full component cards when not tracking
                items(componentModels, key = { it.component.key }) { model ->
                    ComponentCard(
                        component = model.component,
                        enabled = model.enabled,
                        metrics = model.metrics,
                        onClick = if (model.enabled) null else onSettingsClick
                    )
                }
            } else if (componentModels.isNotEmpty()) {
                // Show collapsible details section when tracking
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(columns) }, key = "details_toggle") {
                    var showDetails by remember { mutableStateOf(false) }
                    
                    Column {
                        // Toggle button
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                            ),
                            onClick = { showDetails = !showDetails }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (showDetails) stringResource(R.string.tracker_hide_details) else stringResource(R.string.tracker_show_sensor_details),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Icon(
                                    imageVector = if (showDetails) 
                                        Icons.Default.KeyboardArrowUp 
                                    else 
                                        Icons.Default.KeyboardArrowDown,
                                    contentDescription = if (showDetails) stringResource(R.string.tracker_collapse) else stringResource(R.string.tracker_expand),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        
                        // Expandable content
                        AnimatedVisibility(visible = showDetails) {
                            Column(
                                modifier = Modifier.padding(top = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                componentModels.forEach { model ->
                                    ComponentCard(
                                        component = model.component,
                                        enabled = model.enabled,
                                        metrics = model.metrics,
                                        onClick = if (model.enabled) null else onSettingsClick
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(columns) }) {
                val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                Spacer(Modifier.height(140.dp + navInset))
            }
        }
    }
}

internal fun copyToClipboard(
    context: Context,
    haptics: androidx.compose.ui.hapticfeedback.HapticFeedback,
    label: String,
    value: String
) {
    val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText(label, value)
    clipboardManager.setPrimaryClip(clip)
    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
}




// endregion
