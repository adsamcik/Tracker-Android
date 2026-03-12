package com.adsamcik.tracker.tracker.ui.compose

/**
 * TrackerDashboard
 *
 * First Compose iteration of the Tracker screen. Uses Material3 and is structured
 * to be progressively enhanced with the expressive design/animations detailed in the design docs.
 * Currently binds to Flow/StateFlow-backed dashboard state and routes actions back to the host.
 */

import android.content.Context
import android.text.format.DateUtils
import android.content.pm.PackageManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.LocationSearching
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Sailing
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FabPosition
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import com.adsamcik.tracker.tracker.data.store.trackingTogglesProtoDataStore
import kotlinx.coroutines.flow.map
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.material.icons.outlined.Star
import android.util.Log
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.assist.Assist
import com.adsamcik.tracker.shared.base.concurrency.DefaultDispatchersProvider
import com.adsamcik.tracker.shared.base.data.CollectionData
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.di.DailySummary
import com.adsamcik.tracker.shared.base.di.DailySummaryProvider
import com.adsamcik.tracker.shared.base.di.DailyPointsProvider
import com.adsamcik.tracker.shared.base.di.GoalProgressProvider
import com.adsamcik.tracker.shared.base.data.LengthUnit
import com.adsamcik.tracker.shared.base.extension.formatAsDuration
import com.adsamcik.tracker.shared.base.extension.formatReadable
import com.adsamcik.tracker.shared.base.extension.formatTrackedSteps
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.shared.preferences.tracking.TrackingPreset
import com.adsamcik.tracker.shared.preferences.settings.TrackerSettingsQuick
import com.adsamcik.tracker.shared.preferences.settings.TrackerSettingsState
import com.adsamcik.tracker.shared.utils.extension.formatDistance
import com.adsamcik.tracker.shared.utils.extension.formatSpeed
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.Trip
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.shared.preferences.R as PrefR

private val defaultDispatchers = DefaultDispatchersProvider

@Immutable
internal data class TrackerDashboardUiState(
    val isTracking: Boolean = false,
    val isLocked: Boolean = false,
    val sessionData: TrackerSession? = null,
    val collectionData: CollectionData? = null,
    val hasLocationPermission: Boolean = false,
    val pathPoints: List<com.adsamcik.tracker.shared.base.data.Location>? = null,
    val policyTier: PolicyTier = PolicyTier.OFF,
    val precisionModePreset: TrackingPreset = TrackingPreset.BALANCED
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
                pathPoints = state.pathPoints
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
private fun TrackerTopBar(
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
                LinearProgressIndicator(
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
    modifier: Modifier = Modifier
) {
    val configuration = LocalConfiguration.current
    val columns = when {
        configuration.screenWidthDp >= 1000 -> 3
        configuration.screenWidthDp >= 600 -> 2
        else -> 1
    }

    val context = LocalContext.current
    val locationEnabled = rememberPrefBoolean(PrefR.string.settings_location_enabled_key, PrefR.string.settings_location_enabled_default)
    val cellEnabled = rememberPrefBoolean(PrefR.string.settings_cell_enabled_key, PrefR.string.settings_cell_enabled_default)
    val wifiEnabled = run {
        val wifiCount = rememberPrefBoolean(PrefR.string.settings_wifi_location_count_enabled_key, PrefR.string.settings_wifi_location_count_enabled_default)
        val wifiNetwork = rememberPrefBoolean(PrefR.string.settings_wifi_network_enabled_key, PrefR.string.settings_wifi_network_enabled_default)
        wifiCount || wifiNetwork
    }
    val activityEnabled = rememberPrefBoolean(PrefR.string.settings_activity_enabled_key, PrefR.string.settings_activity_enabled_default)
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

            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(columns) }) { Spacer(Modifier.height(140.dp)) }
        }
    }
}

@Composable
private fun rememberPrefBoolean(keyRes: Int, defaultRes: Int): Boolean {
    val context = LocalContext.current
    val keyName = remember(context, keyRes) { context.getString(keyRes) }
    val default = remember(context, defaultRes) { context.resources.getString(defaultRes).toBoolean() }
    val ds = remember(context) { context.trackingTogglesProtoDataStore }
    val flow = remember(ds, keyName, default) {
        ds.data.map { proto -> proto.togglesMap[keyName] ?: default }
    }
    val value = flow.collectAsState(initial = default).value
    return value
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SessionOverviewCard(
    session: TrackerSession,
    isTracking: Boolean,
    pathPoints: List<com.adsamcik.tracker.shared.base.data.Location>?,
    onMapClick: () -> Unit,
    onDetailClick: ((Long) -> Unit)? = null,
    snackbarHostState: androidx.compose.material3.SnackbarHostState
) {
    val context = LocalContext.current
    val resources = context.resources
    val settings = TrackerSettingsQuick.snapshot(context)
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    val sessionEnd = when {
        session.end > session.start -> session.end
        isTracking -> Time.nowMillis
        else -> session.start
    }
    val durationMillis = (sessionEnd - session.start).coerceAtLeast(0L)
    val durationText = durationMillis.formatAsDuration(context)

    val distanceText = resources.formatDistance(
        session.distanceInM,
        digits = if (session.distanceInM >= 1000f) 1 else 2,
        unit = settings.lengthSystem
    )
    val stepCounterSupported = context.packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_STEP_COUNTER)
    val stepsText = session.steps.formatTrackedSteps(stepCounterSupported)
    val updatesText = context.getString(R.string.collection_count_value, session.collections)
    val sessionAge = DateUtils.getRelativeTimeSpanString(
        session.start,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE
    ).toString()

    val generateSummary = remember(session, settings) {
        {
            buildString {
                appendLine("Session Summary")
                appendLine("Duration: $durationText")
                appendLine("Distance: $distanceText (${session.distanceInM}m)")
                appendLine("Steps: $stepsText")
                appendLine("Updates: ${session.collections}")
                appendLine("Started: $sessionAge")
                if (session.distanceOnFootInM > 0) {
                    appendLine("On foot: ${resources.formatDistance(session.distanceOnFootInM, 1, settings.lengthSystem)}")
                }
                if (session.distanceInVehicleInM > 0) {
                    appendLine("In vehicle: ${resources.formatDistance(session.distanceInVehicleInM, 1, settings.lengthSystem)}")
                }
            }
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { onDetailClick?.invoke(session.id) },
                onLongClick = {
                    val sessionSummary = generateSummary()
                    copyToClipboard(context, haptics, "Session", sessionSummary)
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            message = context.getString(R.string.tracker_session_summary_copied),
                            duration = androidx.compose.material3.SnackbarDuration.Short
                        )
                    }
                }
            )
            .semantics {
                contentDescription = "Session overview: $durationText duration, $distanceText distance"
                onClick(label = context.getString(R.string.tracker_copy_session_summary)) {
                    true
                }
            },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.tracker_session_card_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                )
                Spacer(Modifier.weight(1f))
                
                IconButton(
                    onClick = {
                        val summary = generateSummary()
                        val sendIntent = Intent().apply {
                            action = Intent.ACTION_SEND
                            putExtra(Intent.EXTRA_TEXT, summary)
                            type = "text/plain"
                        }
                        val shareIntent = Intent.createChooser(sendIntent, null)
                        context.startActivity(shareIntent)
                    },
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = stringResource(R.string.tracker_share_session_summary),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(16.dp)
                    )
                }
                
                Spacer(Modifier.size(12.dp))

                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = stringResource(R.string.tracker_long_press_to_copy),
                    modifier = Modifier
                        .size(14.dp)
                        .alpha(0.5f),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
                if (isTracking) {
                    Badge(containerColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.15f)) {
                        Text(
                            text = stringResource(R.string.notification_tracking_active),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(
                text = durationText,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                text = stringResource(R.string.duration_title),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
            )

            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                SessionMetric(
                    label = stringResource(R.string.tracker_distance_title),
                    value = distanceText,
                    modifier = Modifier.weight(1f)
                )
                SessionMetric(
                    label = stringResource(R.string.tracker_steps_title),
                    value = stepsText,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                SessionMetric(
                    label = stringResource(R.string.tracker_collections_title),
                    value = updatesText,
                    modifier = Modifier.weight(1f)
                )
                SessionMetric(
                    label = stringResource(R.string.tracker_session_age),
                    value = sessionAge,
                    modifier = Modifier.weight(1f)
                )
            }
            
            if (pathPoints != null && pathPoints.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Box(modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .clickable { onMapClick() }
                ) {
                    SessionPathPreview(
                        points = pathPoints,
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.05f))
                    )
                    
                    // Overlay hint
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp)
                            .background(
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                                MaterialTheme.shapes.small
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.tracker_view_map),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}


@Composable
private fun SessionMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

@Composable
private fun StatusAndQuickStatsCard(
    isTracking: Boolean,
    sessionData: TrackerSession?,
    collectionData: CollectionData?,
    wallClockNowMillis: Long,
    onMapClick: () -> Unit,
    pathPoints: List<com.adsamcik.tracker.shared.base.data.Location>? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val resources = context.resources
    val settings = TrackerSettingsQuick.snapshot(context)
    var selectedTab by rememberSaveable { mutableStateOf(TrackingStatsTab.LIVE) }

    val currentSpeed = collectionData?.location?.speed
    val currentActivity = collectionData?.activity?.getGroupedActivityName(context)
    val sessionEnd = when {
        isTracking -> wallClockNowMillis
        sessionData != null && sessionData.end > sessionData.start -> sessionData.end
        else -> wallClockNowMillis
    }
    val durationMillis = if (sessionData != null) (sessionEnd - sessionData.start).coerceAtLeast(0L) else 0L
    val durationText = durationMillis.formatAsDuration(context)
    val isMoving = (currentSpeed ?: 0f) > 0.5f
    val derivedDistanceMeters = remember(pathPoints) {
        pathPoints.orEmpty().windowed(size = 2).sumOf { (start, end) ->
            start.distance(end, LengthUnit.Meter)
        }.toFloat()
    }
    val distanceMeters = maxOf(sessionData?.distanceInM ?: 0f, derivedDistanceMeters)
    val distanceText = resources.formatDistance(
        distanceMeters,
        digits = if (distanceMeters >= 1000f) 1 else 0,
        unit = settings.lengthSystem
    )
    val avgSpeed = remember(pathPoints) {
        calculateMovingAverageSpeed(pathPoints)
    }
    val avgSpeedText = resources.formatSpeed(context, avgSpeed, 1)
    val speedText = currentSpeed?.let { resources.formatSpeed(context, it.toDouble(), 1) } ?: "—"
    val altitudeText = collectionData?.location?.altitude?.let {
        resources.formatDistance(it.toFloat(), 0, settings.lengthSystem)
    } ?: "—"
    val accuracyText = collectionData?.location?.horizontalAccuracy?.let {
        "±${resources.formatDistance(it, 0, settings.lengthSystem)}"
    } ?: "—"
    val stepCounterSupported = context.packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_STEP_COUNTER)
    val stepsText = sessionData?.steps?.formatTrackedSteps(stepCounterSupported) ?: "—"
    val wifiText = collectionData?.wifi?.inRange?.size?.takeIf { it > 0 }?.toString() ?: "—"
    val cellText = collectionData?.cell?.totalCount?.takeIf { it > 0 }?.toString() ?: "—"
    val activityText = currentActivity ?: "—"

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        onClick = onMapClick
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header: Status + Map Arrow
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Pulsing recording dot
                    val infiniteTransition = rememberInfiniteTransition(label = "recording_dot")
                    val alpha by infiniteTransition.animateFloat(
                        initialValue = 1f,
                        targetValue = 0.2f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1000),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "alpha"
                    )
                    
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .alpha(alpha)
                            .clip(MaterialTheme.shapes.extraLarge)
                            .background(MaterialTheme.colorScheme.error)
                    )
                    Text(
                        text = stringResource(R.string.notification_tracking_active),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = stringResource(R.string.tracker_go_to_map),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(Modifier.height(12.dp))

            // Primary Metric Area with animated values
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom
            ) {
                if (isMoving && currentSpeed != null) {
                    // Moving: Speed is Primary
                    val speedText = resources.formatSpeed(context, currentSpeed.toDouble(), 1)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.speed_title),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                        )
                        PulseOnChange(key = speedText) {
                            AnimatedStatValue(
                                value = speedText,
                                style = MaterialTheme.typography.displayMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                } else {
                    // Stopped/Idle: Duration is Primary
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.duration_title),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                        )
                        AnimatedStatValue(
                            value = durationText,
                            style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            TabRow(selectedTabIndex = TrackingStatsTab.entries.indexOf(selectedTab)) {
                TrackingStatsTab.entries.forEach { tab ->
                    Tab(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        text = { Text(text = stringResource(tab.titleRes)) }
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            when (selectedTab) {
                TrackingStatsTab.LIVE -> {
                    TrackingStatRow(
                        first = stringResource(R.string.speed_title) to speedText,
                        second = stringResource(R.string.tracker_average_label) to avgSpeedText,
                        third = stringResource(R.string.tracker_accuracy_label) to accuracyText
                    )
                }

                TrackingStatsTab.ROUTE -> {
                    TrackingStatRow(
                        first = stringResource(R.string.tracker_distance_title) to distanceText,
                        second = stringResource(R.string.duration_title) to durationText,
                        third = stringResource(R.string.altitude_title) to altitudeText
                    )
                }

                TrackingStatsTab.ACTIVITY -> {
                    TrackingStatRow(
                        first = stringResource(R.string.tracker_activity_title) to activityText,
                        second = stringResource(R.string.tracker_steps_title) to stepsText,
                        third = stringResource(R.string.tracker_accuracy_label) to accuracyText
                    )
                    Spacer(Modifier.height(12.dp))
                    TrackingTechnicalRow(
                        wifiText = wifiText,
                        cellText = cellText,
                        coordinatesText = collectionData?.location?.let { location ->
                            "${Assist.coordinateToString(location.latitude)}, ${Assist.coordinateToString(location.longitude)}"
                        }
                    )
                }
            }
        }
    }
}

private enum class TrackingStatsTab(val titleRes: Int) {
    LIVE(R.string.tracker_live_tab),
    ROUTE(R.string.tracker_route_tab),
    ACTIVITY(R.string.tracker_activity_tab)
}

@Composable
private fun TrackingStatRow(
    first: Pair<String, String>,
    second: Pair<String, String>,
    third: Pair<String, String>
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        CompactStatItem(
            label = first.first,
            value = first.second,
            modifier = Modifier.weight(1f)
        )
        CompactStatItem(
            label = second.first,
            value = second.second,
            modifier = Modifier.weight(1f)
        )
        CompactStatItem(
            label = third.first,
            value = third.second,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun TrackingTechnicalRow(
    wifiText: String,
    cellText: String,
    coordinatesText: String?
) {
    if (wifiText == "—" && cellText == "—" && coordinatesText == null) return

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (wifiText != "—") {
            TechnicalStatItem(
                icon = Icons.Filled.Wifi,
                text = wifiText
            )
        }
        if (cellText != "—") {
            TechnicalStatItem(
                icon = Icons.Filled.SignalCellularAlt,
                text = cellText
            )
        }
        if (coordinatesText != null) {
            Text(
                text = coordinatesText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                modifier = Modifier.weight(1f),
                maxLines = 1
            )
        }
    }
}

@Composable
private fun ActiveStatItem(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

private fun calculateMovingAverageSpeed(
    pathPoints: List<com.adsamcik.tracker.shared.base.data.Location>?
): Double {
    if (pathPoints.isNullOrEmpty() || pathPoints.size < 2) return 0.0

    var movingDistanceMeters = 0.0
    var movingDurationSeconds = 0.0

    pathPoints.windowed(size = 2).forEach { (start, end) ->
        val deltaMillis = end.time - start.time
        if (deltaMillis <= 0L) return@forEach

        val segmentDistanceMeters = start.distance(end, LengthUnit.Meter)
        if (segmentDistanceMeters <= 0.0) return@forEach

        movingDistanceMeters += segmentDistanceMeters
        movingDurationSeconds += deltaMillis.toDouble() / 1000.0
    }

    return if (movingDurationSeconds > 0.0) {
        movingDistanceMeters / movingDurationSeconds
    } else {
        0.0
    }
}

@Composable
private fun CompactStatItem(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    animated: Boolean = true
) {
    Column(modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
        )
        if (animated) {
            AnimatedContent(
                targetState = value,
                label = "compact_stat",
                transitionSpec = {
                    (fadeIn(animationSpec = tween(200)) + scaleIn(initialScale = 0.95f))
                        .togetherWith(fadeOut(animationSpec = tween(100)))
                }
            ) { targetValue ->
                Text(
                    text = targetValue,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        } else {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
private fun TechnicalStatItem(
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .background(
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.08f),
                shape = MaterialTheme.shapes.small
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.9f)
        )
    }
}

/**
 * AnimatedStatValue - Displays a value with smooth counting animation when it changes.
 * Uses AnimatedContent with vertical slide for a slot-machine effect.
 */
@Composable
private fun AnimatedStatValue(
    value: String,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.displayMedium,
    fontWeight: FontWeight = FontWeight.Bold,
    color: Color = MaterialTheme.colorScheme.onPrimaryContainer
) {
    AnimatedContent(
        targetState = value,
        label = "stat_value",
        transitionSpec = {
            // Slide up with fade for counting effect
            (slideInVertically { height -> height / 4 } + fadeIn(animationSpec = tween(300)))
                .togetherWith(slideOutVertically { height -> -height / 4 } + fadeOut(animationSpec = tween(150)))
        },
        modifier = modifier
    ) { targetValue ->
        Text(
            text = targetValue,
            style = style,
            fontWeight = fontWeight,
            color = color
        )
    }
}

/**
 * PulseOnChange - Wraps content and adds a subtle scale pulse when value changes.
 */
@Composable
private fun PulseOnChange(
    key: Any,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val scale = remember { Animatable(1f) }
    
    LaunchedEffect(key) {
        // Quick pulse: scale up then back
        scale.animateTo(
            targetValue = 1.08f,
            animationSpec = tween(100, easing = FastOutSlowInEasing)
        )
        scale.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium
            )
        )
    }
    
    Box(
        modifier = modifier.graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
        }
    ) {
        content()
    }
}

@Composable
private fun SessionPathPreview(
    points: List<com.adsamcik.tracker.shared.base.data.Location>,
    modifier: Modifier = Modifier
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val startColor = MaterialTheme.colorScheme.tertiary
    val endColor = MaterialTheme.colorScheme.error
    val pathDescription = "Session route preview with ${points.size} points"
    
    // Animate path drawing progress from 0 to 1
    val pathProgress = remember { Animatable(0f) }
    
    LaunchedEffect(points) {
        pathProgress.snapTo(0f)
        pathProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = 800,
                easing = FastOutSlowInEasing
            )
        )
    }
    
    androidx.compose.foundation.Canvas(
        modifier = modifier.semantics { 
            contentDescription = pathDescription
        }
    ) {
        if (points.size < 2) return@Canvas

        // Calculate bounds
        var minLat = Double.MAX_VALUE
        var maxLat = Double.MIN_VALUE
        var minLon = Double.MAX_VALUE
        var maxLon = Double.MIN_VALUE

        points.forEach { p ->
            minLat = minOf(minLat, p.latitude)
            maxLat = maxOf(maxLat, p.latitude)
            minLon = minOf(minLon, p.longitude)
            maxLon = maxOf(maxLon, p.longitude)
        }

        val latRange = maxLat - minLat
        val lonRange = maxLon - minLon

        if (latRange == 0.0 && lonRange == 0.0) return@Canvas

        // Add padding to bounds (10%)
        val latPadding = if (latRange == 0.0) 0.001 else latRange * 0.1
        val lonPadding = if (lonRange == 0.0) 0.001 else lonRange * 0.1

        val drawMinLat = minLat - latPadding
        val drawMaxLat = maxLat + latPadding
        val drawMinLon = minLon - lonPadding
        val drawMaxLon = maxLon + lonPadding

        val drawLatRange = drawMaxLat - drawMinLat
        val drawLonRange = drawMaxLon - drawMinLon

        // Scale to canvas
        val width = size.width
        val height = size.height
        
        // Calculate point positions
        val screenPoints = points.map { p ->
            val x = ((p.longitude - drawMinLon) / drawLonRange).toFloat() * width
            val y = (1 - ((p.latitude - drawMinLat) / drawLatRange)).toFloat() * height
            Offset(x, y)
        }
        
        // Determine how many points to draw based on animation progress
        val pointsToDraw = (screenPoints.size * pathProgress.value).toInt().coerceAtLeast(2)
        
        // Draw the path up to current progress
        val path = androidx.compose.ui.graphics.Path()
        screenPoints.take(pointsToDraw).forEachIndexed { index, offset ->
            if (index == 0) {
                path.moveTo(offset.x, offset.y)
            } else {
                path.lineTo(offset.x, offset.y)
            }
        }

        drawPath(
            path = path,
            color = primaryColor,
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = 3.dp.toPx(),
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                join = androidx.compose.ui.graphics.StrokeJoin.Round
            ),
            alpha = 0.6f
        )
        
        // Draw start marker (green circle)
        val startPoint = screenPoints.firstOrNull()
        if (startPoint != null && pathProgress.value > 0.05f) {
            drawCircle(
                color = startColor,
                radius = 6.dp.toPx(),
                center = startPoint,
                alpha = pathProgress.value
            )
            drawCircle(
                color = Color.White,
                radius = 3.dp.toPx(),
                center = startPoint,
                alpha = pathProgress.value
            )
        }
        
        // Draw end marker (red circle) - only when animation is mostly complete
        val endPoint = screenPoints.lastOrNull()
        if (endPoint != null && pathProgress.value > 0.9f) {
            val endAlpha = ((pathProgress.value - 0.9f) / 0.1f).coerceIn(0f, 1f)
            drawCircle(
                color = endColor,
                radius = 6.dp.toPx(),
                center = endPoint,
                alpha = endAlpha
            )
            drawCircle(
                color = Color.White,
                radius = 3.dp.toPx(),
                center = endPoint,
                alpha = endAlpha
            )
        }
    }
}

@Composable
private fun EmptyStateCard() {
    val infiniteTransition = rememberInfiniteTransition(label = "empty_state")
    
    // Gentle floating animation for the icon
    val floatOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 8f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "float"
    )
    
    // Subtle pulse for the icon container
    val iconScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "icon_pulse"
    )
    
    // Background pattern animation
    val pathOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 50f,
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = androidx.compose.animation.core.LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "path_offset"
    )
    
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Animated Background Pattern
            Canvas(modifier = Modifier.fillMaxSize().alpha(0.06f)) {
                val width = size.width
                val height = size.height
                val path = androidx.compose.ui.graphics.Path()
                
                // Animated wavy lines suggesting a route
                val waveOffset = pathOffset
                path.moveTo(-50f + waveOffset, height * 0.3f)
                path.cubicTo(
                    width * 0.2f + waveOffset * 0.5f, height * 0.5f,
                    width * 0.4f + waveOffset * 0.3f, height * 0.2f,
                    width * 0.6f + waveOffset * 0.5f, height * 0.4f
                )
                path.cubicTo(
                    width * 0.8f + waveOffset * 0.3f, height * 0.6f,
                    width + waveOffset * 0.5f, height * 0.3f,
                    width + 50f, height * 0.5f
                )
                
                path.moveTo(-30f + waveOffset * 0.7f, height * 0.7f)
                path.cubicTo(
                    width * 0.3f + waveOffset * 0.4f, height * 0.8f,
                    width * 0.5f + waveOffset * 0.6f, height * 0.6f,
                    width * 0.7f + waveOffset * 0.4f, height * 0.85f
                )
                path.lineTo(width + 30f, height * 0.9f)
                
                drawPath(
                    path = path,
                    color = Color.Black,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = 3.dp.toPx(),
                        cap = androidx.compose.ui.graphics.StrokeCap.Round
                    )
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 56.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top
            ) {
                // Floating icon with pulse
                Box(
                    modifier = Modifier
                        .graphicsLayer {
                            translationY = -floatOffset
                            scaleX = iconScale
                            scaleY = iconScale
                        }
                        .size(72.dp)
                        .clip(MaterialTheme.shapes.extraLarge)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primaryContainer,
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f)
                                )
                            )
                        )
                        .padding(18.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.LocationSearching,
                        contentDescription = stringResource(R.string.tracker_empty_state_icon_desc),
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                
                Spacer(Modifier.height(20.dp))
                
                Text(
                    text = stringResource(R.string.settings_tracking_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                
                Spacer(Modifier.height(8.dp))
                
                Text(
                    text = stringResource(R.string.shortcut_start_tracking_long),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

private sealed class TrackingComponent(val key: String) {
    data object Location : TrackingComponent("location")
    data object Activity : TrackingComponent("activity")
    data object Wifi : TrackingComponent("wifi")
    data object Cell : TrackingComponent("cell")
}

@Immutable
private data class TrackingComponentModel(
    val component: TrackingComponent,
    val enabled: Boolean,
    val metrics: ComponentMetrics?
)

@Immutable
private data class ComponentMetrics(
    val primary: ComponentMetric?,
    val secondary: List<ComponentMetric> = emptyList(),
    val status: String? = null
)

@Immutable
private data class ComponentMetric(
    val label: String,
    val value: String,
    val copyableValue: String? = null, // Both formatted + raw for clipboard
    val onClick: (() -> Unit)? = null
)

@Composable
private fun ComponentCard(
    component: TrackingComponent,
    enabled: Boolean,
    metrics: ComponentMetrics?,
    onClick: (() -> Unit)?
) {
    // Use surfaceContainerLow for enabled (better visibility) and surfaceVariant for disabled
    val containerColor = if (enabled) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val contentAlpha = if (enabled) 1f else 0.6f
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current

    val title = when (component) {
        TrackingComponent.Location -> stringResource(R.string.settings_location_enabled_title)
        TrackingComponent.Activity -> stringResource(R.string.tracker_activity_title)
        TrackingComponent.Wifi -> stringResource(R.string.settings_wifi_enabled_title)
        TrackingComponent.Cell -> stringResource(R.string.settings_cell_enabled_title)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("component_card_${component.key}"),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = onClick != null) { onClick?.invoke() }
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .weight(1f)
                        .alpha(0.9f)
                )

                if (!enabled) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = stringResource(R.string.description_settings),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("component_settings_icon").size(16.dp)
                    )
                }
            }

            metrics?.let { details ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .alpha(contentAlpha),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val scope = rememberCoroutineScope()
                    details.primary?.let {
                        ComponentMetricText(
                            metric = it,
                            emphasize = true,
                            onCopy = if (enabled && it.copyableValue != null) {
                                {
                                    copyToClipboard(context, haptics, it.label, it.copyableValue)
                                }
                            } else null
                        )
                    }
                    
                    if (details.secondary.isNotEmpty()) {
                        // Use a FlowRow or simple Column for secondary metrics
                        details.secondary.forEach {
                            ComponentMetricText(
                                metric = it,
                                emphasize = false,
                                onCopy = if (enabled && it.copyableValue != null) {
                                    {
                                        copyToClipboard(context, haptics, it.label, it.copyableValue)
                                    }
                                } else null
                            )
                        }
                    }
                    
                    details.status?.let { status ->
                        Text(
                            text = status,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            } ?: Row(
                modifier = Modifier
                    .padding(top = 12.dp)
                    .alpha(contentAlpha),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (enabled) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(R.string.tracker_component_waiting_data),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = stringResource(R.string.tracker_disabled),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ComponentMetricText(
    metric: ComponentMetric,
    emphasize: Boolean,
    onCopy: (() -> Unit)? = null
) {
    var justCopied by remember { mutableStateOf(false) }
    LaunchedEffect(justCopied) {
        if (justCopied) {
            delay(2000)
            justCopied = false
        }
    }

    val interactionModifier = if (onCopy != null) {
        Modifier.combinedClickable(
            onClick = { metric.onClick?.invoke() },
            onLongClick = {
                onCopy()
                justCopied = true
            }
        )
    } else if (metric.onClick != null) {
        Modifier.clickable { metric.onClick.invoke() }
    } else {
        Modifier
    }

    val semanticsModifier = Modifier.semantics {
        contentDescription = "${metric.label}: ${metric.value}"
        if (onCopy != null) {
            onClick(label = "Copy ${metric.label}") {
                onCopy()
                justCopied = true
                true
            }
        } else if (metric.onClick != null) {
             onClick(label = "Toggle format") {
                 metric.onClick.invoke()
                 true
             }
        }
    }

    Column(modifier = interactionModifier.then(semanticsModifier)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = metric.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f)
            )
            if (onCopy != null) {
                AnimatedContent(targetState = justCopied, label = "copy_icon") { copied ->
                    if (copied) {
                         Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = stringResource(R.string.description_copied),
                            modifier = Modifier.size(10.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = stringResource(R.string.description_copy),
                            modifier = Modifier
                                .size(10.dp)
                                .alpha(0.4f),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(2.dp))
        
        AnimatedContent(
            targetState = metric.value,
            transitionSpec = {
                (slideInVertically { height -> height } + fadeIn()).togetherWith(slideOutVertically { height -> -height } + fadeOut())
            },
            label = "metric_value"
        ) { targetValue ->
            Text(
                text = targetValue,
                style = if (emphasize) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                fontWeight = if (emphasize) FontWeight.SemiBold else FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

private fun buildLocationMetrics(
    context: Context,
    collectionData: CollectionData?,
    settings: TrackerSettingsState,
    useDecimalDegrees: Boolean,
    onToggleFormat: () -> Unit
): ComponentMetrics? {
    val location = collectionData?.location ?: return null
    val resources = context.resources
    val primary = location.horizontalAccuracy?.takeIf { it > 0f }?.let { accuracy ->
        val formattedAccuracy = resources.formatDistance(
            distanceInMeters = accuracy,
            digits = if (accuracy >= 100f) 0 else 1,
            unit = settings.lengthSystem
        )
        ComponentMetric(
            label = context.getString(R.string.horizontal_accuracy_title),
            value = formattedAccuracy,
            copyableValue = "$formattedAccuracy\n${accuracy}m"
        )
    }

    val secondary = buildList {
        // Add coordinates first
        val latFormatted = if (useDecimalDegrees) location.latitude.toString() else Assist.coordinateToString(location.latitude)
        val lonFormatted = if (useDecimalDegrees) location.longitude.toString() else Assist.coordinateToString(location.longitude)
        add(
            ComponentMetric(
                label = context.getString(R.string.coordinates_title),
                value = "$latFormatted, $lonFormatted",
                copyableValue = "Latitude: $latFormatted (${location.latitude})\nLongitude: $lonFormatted (${location.longitude})",
                onClick = onToggleFormat
            )
        )
        
        location.speed?.takeIf { it > 0f }?.let { speed ->
            val formattedSpeed = resources.formatSpeed(context, speed.toDouble(), 1)
            add(
                ComponentMetric(
                    label = context.getString(R.string.speed_title),
                    value = formattedSpeed,
                    copyableValue = "$formattedSpeed\n${speed}m/s"
                )
            )
        }
        location.altitude?.let { altitude ->
            val formattedAltitude = resources.formatDistance(altitude.toFloat(), 0, settings.lengthSystem)
            add(
                ComponentMetric(
                    label = context.getString(R.string.altitude_title),
                    value = formattedAltitude,
                    copyableValue = "$formattedAltitude\n${altitude}m"
                )
            )
        }
    }

    val status = formatRelativeUpdate(context, location.time)
    if (primary == null && secondary.isEmpty() && status == null) return null
    return ComponentMetrics(primary = primary, secondary = secondary, status = status)
}

private fun buildActivityMetrics(context: Context, collectionData: CollectionData?): ComponentMetrics? {
    val activity = collectionData?.activity ?: return null
    val activityName = activity.getGroupedActivityName(context)
    val primary = ComponentMetric(
        label = context.getString(R.string.tracker_activity_title),
        value = activityName,
        copyableValue = "$activityName (${activity.confidence}% confidence)"
    )
    val secondary = listOf(
        ComponentMetric(
            label = context.getString(R.string.tracker_activity_confidence),
            value = "${activity.confidence}%",
            copyableValue = "Confidence: ${activity.confidence}%"
        )
    )
    val status = formatRelativeUpdate(context, collectionData.time)
    return ComponentMetrics(primary = primary, secondary = secondary, status = status)
}

private fun buildWifiMetrics(context: Context, collectionData: CollectionData?): ComponentMetrics? {
    val wifi = collectionData?.wifi ?: return null
    val count = wifi.inRange.size
    val wifiCountText = context.resources.getQuantityString(R.plurals.tracker_wifi_networks_value, count, count)
    val primary = ComponentMetric(
        label = context.getString(R.string.settings_wifi_enabled_title),
        value = wifiCountText,
        copyableValue = "$count WiFi networks in range"
    )
    val strongest = wifi.inRange.maxByOrNull { it.level }
    val secondary = strongest?.let {
        val signalText = context.getString(R.string.tracker_signal_strength_value, it.level)
        listOf(
            ComponentMetric(
                label = context.getString(R.string.tracker_signal_strength_title),
                value = signalText,
                copyableValue = "Strongest signal: ${it.level} dBm\nSSID: ${it.ssid ?: "Hidden"}"
            )
        )
    } ?: emptyList()
    val status = formatRelativeUpdate(context, wifi.time)
    return ComponentMetrics(primary = primary, secondary = secondary, status = status)
}

private fun buildCellMetrics(context: Context, collectionData: CollectionData?): ComponentMetrics? {
    val cell = collectionData?.cell ?: return null
    val cellCountText = context.getString(R.string.cell_count_value, cell.totalCount)
    val primary = ComponentMetric(
        label = context.getString(R.string.cell_count_title),
        value = cellCountText,
        copyableValue = "Total cells: ${cell.totalCount}"
    )
    val strongest = cell.registeredCells.maxByOrNull { it.dbm }
    val secondary = strongest?.dbm?.takeIf { it != 0 }?.let { dbm ->
        val operatorName = strongest.networkOperator.name?.takeIf { it.isNotBlank() }
            ?: "${strongest.networkOperator.mcc}-${strongest.networkOperator.mnc}"
        val cellType = context.getString(strongest.type.nameRes)
        val cellValueText = context.getString(R.string.cell_current_single_value, cellType, operatorName, dbm)
        listOf(
            ComponentMetric(
                label = context.getString(R.string.cell_current_title),
                value = cellValueText,
                copyableValue = "Type: $cellType\nOperator: $operatorName\nSignal: ${dbm}dBm"
            )
        )
    } ?: emptyList()
    val status = formatRelativeUpdate(context, collectionData.time)
    return ComponentMetrics(primary = primary, secondary = secondary, status = status)
}

private fun formatRelativeUpdate(context: Context, timestamp: Long?): String? {
    if (timestamp == null || timestamp <= 0L) return null
    val relative = DateUtils.getRelativeTimeSpanString(
        timestamp,
        System.currentTimeMillis(),
        DateUtils.SECOND_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE
    ).toString()
    return context.getString(R.string.last_update_value, relative)
}

/**
 * Expressive Tracking Button
 *
 * A redesigned tracking control with Material 3 Expressive styling:
 * - Morphing shape: Rounded square (idle) → Circle (tracking)
 * - Animated ring progress indicator when tracking
 * - Soft glow effect during active tracking
 * - Smooth icon transitions with scale animations
 * - Clear state distinction through color and shape
 */
@OptIn(ExperimentalAnimationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun TrackingFAB(
    isTracking: Boolean,
    hasPermission: Boolean,
    onToggleTracking: () -> Unit,
    onRequestPermission: () -> Unit,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "tracking_button")
    
    // Morphing corner radius: More rounded when tracking
    val cornerRadius by animateFloatAsState(
        targetValue = if (isTracking) 50f else 28f,
        animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing),
        label = "corner_radius"
    )
    
    // Subtle pulse when tracking
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isTracking) 1.04f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    
    // Glow ring animation - rotating gradient effect when tracking
    val glowRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = androidx.compose.animation.core.LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "glow_rotation"
    )
    
    // Alpha for glow effect
    val glowAlpha by animateFloatAsState(
        targetValue = if (isTracking) 0.6f else 0f,
        animationSpec = tween(durationMillis = 500),
        label = "glow_alpha"
    )
    
    // Icon scale for press feedback
    val iconScale by animateFloatAsState(
        targetValue = if (isTracking) 0.9f else 1f,
        animationSpec = tween(durationMillis = 300),
        label = "icon_scale"
    )
    
    val buttonSize = 96.dp
    val primaryColor = MaterialTheme.colorScheme.primary
    val errorColor = MaterialTheme.colorScheme.error
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    
    val containerColor = when {
        !hasPermission -> surfaceVariant
        isTracking -> errorColor
        else -> primaryColor
    }
    
    val contentColor = when {
        !hasPermission -> MaterialTheme.colorScheme.onSurfaceVariant
        isTracking -> MaterialTheme.colorScheme.onError
        else -> MaterialTheme.colorScheme.onPrimary
    }
    
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(buttonSize + 16.dp) // Extra space for glow
    ) {
        // Outer glow ring when tracking
        if (isTracking) {
            Canvas(
                modifier = Modifier
                    .size(buttonSize + 12.dp)
                    .alpha(glowAlpha)
                    .graphicsLayer { rotationZ = glowRotation }
            ) {
                val sweepGradient = Brush.sweepGradient(
                    0f to errorColor.copy(alpha = 0.8f),
                    0.25f to errorColor.copy(alpha = 0.2f),
                    0.5f to errorColor.copy(alpha = 0.8f),
                    0.75f to errorColor.copy(alpha = 0.2f),
                    1f to errorColor.copy(alpha = 0.8f)
                )
                
                drawCircle(
                    brush = sweepGradient,
                    radius = size.minDimension / 2,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4.dp.toPx())
                )
            }
        }
        
        // Main button with morphing shape
        Surface(
            onClick = {
                if (!hasPermission) onRequestPermission() else onToggleTracking()
            },
            shape = androidx.compose.foundation.shape.RoundedCornerShape(cornerRadius.dp),
            color = containerColor,
            shadowElevation = if (isTracking) 8.dp else 6.dp,
            tonalElevation = if (isTracking) 4.dp else 2.dp,
            modifier = Modifier
                .size(buttonSize)
                .graphicsLayer {
                    scaleX = pulseScale
                    scaleY = pulseScale
                }
                .testTag("tracking_fab")
                .semantics {
                    contentDescription = when {
                        !hasPermission -> "Start tracking - permission required"
                        isTracking -> "Stop tracking"
                        else -> "Start tracking"
                    }
                }
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                // Icon with animated transitions
                val icon: ImageVector = when {
                    !hasPermission -> Icons.Default.LocationOff
                    isTracking -> Icons.Default.Stop
                    else -> Icons.Default.PlayArrow
                }
                val description = when {
                    !hasPermission -> stringResource(R.string.description_tracking_start)
                    isTracking -> stringResource(R.string.description_tracking_stop)
                    else -> stringResource(R.string.description_tracking_start)
                }
                
                AnimatedContent(
                    targetState = icon,
                    label = "fab_icon",
                    transitionSpec = {
                        (fadeIn(animationSpec = tween(300)) + 
                            androidx.compose.animation.scaleIn(initialScale = 0.8f, animationSpec = tween(300)))
                            .togetherWith(
                                fadeOut(animationSpec = tween(200)) +
                                    androidx.compose.animation.scaleOut(targetScale = 0.8f, animationSpec = tween(200))
                            )
                    }
                ) { targetIcon ->
                    Icon(
                        imageVector = targetIcon,
                        contentDescription = description,
                        tint = contentColor,
                        modifier = Modifier
                            .size(40.dp)
                            .graphicsLayer {
                                scaleX = iconScale
                                scaleY = iconScale
                            }
                    )
                }
            }
        }
    }
}

@Composable
private fun LockBanner(onClick: () -> Unit) {
    val context = LocalContext.current
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.testTag("lock_banner")
    ) {
        Row(
            Modifier
                .clickable { onClick() }
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .semantics { contentDescription = context.getString(R.string.description_recharge_settings) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Outlined.Lock, contentDescription = stringResource(R.string.tracker_lock_icon_desc), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text = stringResource(R.string.settings_disabled_recharge_title), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(Icons.Outlined.Tune, contentDescription = stringResource(R.string.tracker_settings_icon_desc), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun copyToClipboard(
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

/**
 * Today's Progress Card
 * 
 * Displays aggregated daily metrics (distance, steps, duration) when not tracking.
 * Redesigned to use a circular progress indicator for the daily goal and a more
 * prominent display of the primary metric (distance).
 */
@Composable
private fun TodayProgressCard(
    isTracking: Boolean,
    settings: TrackerSettingsState,
    dailySummaryProvider: DailySummaryProvider,
    goalProgressProvider: GoalProgressProvider,
    modifier: Modifier = Modifier,
    onStartTrackingHint: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val resources = context.resources
    
    var todaySummary by remember { mutableStateOf<DailySummary?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    
    // Fetch on-demand when composition enters or when tracking state changes
    LaunchedEffect(isTracking) {
        isLoading = true
        try {
            todaySummary = dailySummaryProvider.fetchTodaySummary()
        } catch (e: Exception) {
            Log.e("TodayProgressCard", "Failed to fetch today summary", e)
            todaySummary = null
        }
        isLoading = false
    }
    
    // Don't show the card when actively tracking (StatusAndQuickStatsCard handles that)
    if (isTracking) return
    
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = MaterialTheme.shapes.large
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left side: Stats
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = stringResource(R.string.dashboard_today_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                if (isLoading) {
                    Box(modifier = Modifier.height(40.dp), contentAlignment = Alignment.CenterStart) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                } else if (todaySummary == null) {
                    Text(
                        text = stringResource(R.string.dashboard_today_empty_title),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                } else {
                    // todaySummary is guaranteed non-null in this branch
                    val summary = requireNotNull(todaySummary) { "todaySummary was null despite passing null check" }
                    
                    // Primary Metric: Distance
                    val distanceText = resources.formatDistance(
                        summary.totalDistanceM,
                        digits = if (summary.totalDistanceM >= 1000f) 1 else 0,
                        unit = settings.lengthSystem
                    )
                    
                    Text(
                        text = distanceText,
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    
                    Spacer(Modifier.height(8.dp))
                    
                    // Secondary Metrics Grid
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        // Duration
                        Column {
                            Text(
                                text = stringResource(R.string.dashboard_today_duration),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f)
                            )
                            Text(
                                text = summary.totalDurationMs.formatAsDuration(context),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        
                        // Steps
                        if (summary.totalSteps > 0) {
                            Column {
                                Text(
                                    text = stringResource(R.string.dashboard_today_steps),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f)
                                )
                                Text(
                                    text = summary.totalSteps.formatReadable(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }
            
            // Right side: Goal Progress Ring
            if (!isLoading) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.padding(start = 16.dp)
                ) {
                    GoalProgressRing(goalProgressProvider = goalProgressProvider)
                }
            }
        }
    }
}

/**
 * Goal Progress Ring
 * 
 * A circular progress indicator showing daily step goal progress.
 * Replaces the linear bar for a more modern look.
 */
@Composable
private fun GoalProgressRing(
    goalProgressProvider: GoalProgressProvider,
    modifier: Modifier = Modifier
) {
    val goalProgress by goalProgressProvider.goalProgressFlow.collectAsState()
    
    if (!goalProgress.gamificationEnabled || goalProgress.goalSteps <= 0) return
    
    val animatedProgress by animateFloatAsState(
        targetValue = goalProgress.progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 1000, easing = FastOutSlowInEasing),
        label = "goal_progress"
    )
    
    val primaryColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    
    Box(contentAlignment = Alignment.Center, modifier = modifier.size(72.dp)) {
        // Track
        CircularProgressIndicator(
            progress = { 1f },
            modifier = Modifier.fillMaxSize(),
            color = trackColor,
            strokeWidth = 6.dp,
            trackColor = Color.Transparent,
        )
        
        // Progress
        CircularProgressIndicator(
            progress = { animatedProgress },
            modifier = Modifier.fillMaxSize(),
            color = primaryColor,
            strokeWidth = 6.dp,
            trackColor = Color.Transparent,
            strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
        )
        
        // Icon or Percentage inside
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Outlined.Star,
                contentDescription = stringResource(R.string.tracker_points_icon_desc),
                tint = primaryColor,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = "${(goalProgress.progress * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

// region Phase 4a: PolicyTierChip

/**
 * Compact chip displaying and toggling the tracking precision mode in the top bar.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun PolicyTierChip(
    tier: PolicyTier,
    precisionModePreset: TrackingPreset,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isBatterySaver = precisionModePreset == TrackingPreset.POWER_SAVE
    val label = stringResource(
        if (isBatterySaver) {
            R.string.tracking_preset_battery_saver_title
        } else {
            R.string.tracking_preset_high_precision_title
        }
    )
    val nextLabel = stringResource(
        if (isBatterySaver) {
            R.string.tracking_preset_high_precision_title
        } else {
            R.string.tracking_preset_battery_saver_title
        }
    )
    val policyLabel = when (tier) {
        PolicyTier.AMBIENT -> stringResource(R.string.policy_tier_ambient)
        PolicyTier.ACTIVE -> stringResource(R.string.policy_tier_active)
        PolicyTier.PRECISION -> stringResource(R.string.policy_tier_precision)
        PolicyTier.OFF -> stringResource(R.string.policy_tier_ambient)
    }
    val hintText = stringResource(R.string.tracker_precision_chip_hint, nextLabel)
    val containerColor = when {
        isBatterySaver -> MaterialTheme.colorScheme.surfaceVariant
        tier == PolicyTier.PRECISION -> MaterialTheme.colorScheme.secondaryContainer
        tier == PolicyTier.ACTIVE -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.tertiaryContainer
    }
    val contentColor = when {
        isBatterySaver -> MaterialTheme.colorScheme.onSurfaceVariant
        tier == PolicyTier.PRECISION -> MaterialTheme.colorScheme.onSecondaryContainer
        tier == PolicyTier.ACTIVE -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onTertiaryContainer
    }
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        color = containerColor,
        modifier = modifier
            .padding(end = 4.dp)
            .semantics {
                contentDescription = "$label. ${context.getString(R.string.policy_tier_status_prefix)} $policyLabel. $hintText"
            }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = if (isBatterySaver) Icons.Default.LocationOff else Icons.Default.LocationSearching,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor
            )
        }
    }
}

// endregion

// region Phase 4d: RecentTripsCard

/**
 * Card showing up to 3 most recent trips from the session_segment table.
 * Only rendered when trips exist; hidden otherwise.
 */
@Composable
private fun RecentTripsCard(
    settings: TrackerSettingsState,
    onTripClick: ((Long) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val db = remember { AppDatabase.database(context) }
    var trips by remember { mutableStateOf<List<Trip>>(emptyList()) }

    LaunchedEffect(Unit) {
        try {
            trips = withContext(defaultDispatchers.io) {
                db.tripDao().getRecentTrips(3)
            }
        } catch (e: Exception) {
            Log.e("RecentTripsCard", "Failed to fetch recent trips", e)
            trips = emptyList()
        }
    }

    if (trips.isEmpty()) return

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.dashboard_recent_trips),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            trips.forEach { trip ->
                key(trip.id) {
                    RecentTripRow(
                        trip = trip,
                        settings = settings,
                        onClick = if (onTripClick != null) {
                            { onTripClick(trip.id) }
                        } else null
                    )
                }
            }
        }
    }
}

@Composable
private fun RecentTripRow(
    trip: Trip,
    settings: TrackerSettingsState,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val resources = context.resources
    val icon = getTripIcon(trip.primaryActivity)
    val distanceText = resources.formatDistance(trip.distanceM, 1, settings.lengthSystem)
    val durationText = trip.durationMs.formatAsDuration(context)
    val timeText = DateUtils.getRelativeTimeSpanString(
        trip.startTimeMs,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE
    ).toString()

    val rowModifier = if (onClick != null) {
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp)
    } else {
        modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp, horizontal = 4.dp)
    }

    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = distanceText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = durationText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = timeText,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (onClick != null) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

private fun getTripIcon(primaryActivity: Int?): ImageVector {
    return when (primaryActivity) {
        -2 -> Icons.AutoMirrored.Filled.DirectionsWalk
        -3 -> Icons.AutoMirrored.Filled.DirectionsRun
        -4 -> Icons.AutoMirrored.Filled.DirectionsBike
        -5, -34 -> Icons.Filled.DirectionsCar
        -26 -> Icons.Filled.Sailing
        -31 -> Icons.Filled.Flight
        else -> Icons.Filled.Route
    }
}

// endregion
