package com.adsamcik.tracker.tracker.ui.compose

/**
 * TrackerDashboard
 *
 * First Compose iteration of the Tracker screen. Uses Material3 and is structured
 * to be progressively enhanced with the expressive design/animations detailed in the design docs.
 * Currently binds to existing LiveData and routes actions back to the hosting Fragment.
 */

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FabPosition
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.shared.base.data.CollectionData
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.ui.TrackerViewModel
import androidx.compose.runtime.livedata.observeAsState
import com.google.android.gms.location.DetectedActivity
import com.adsamcik.tracker.tracker.ui.receiver.SessionUpdateReceiver
import com.adsamcik.tracker.tracker.locker.TrackerLocker
import com.adsamcik.tracker.shared.base.extension.hasLocationPermission
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.shared.preferences.R as PrefR

@Composable
internal fun TrackerDashboard(
    viewModel: TrackerViewModel,
    onSettingsClick: () -> Unit,
    onRequestPermission: (String) -> Unit,
    onToggleTracking: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    // Bridge existing LiveData to Compose
    val isTracking = com.adsamcik.tracker.tracker.service.TrackerService.isServiceRunning.observeAsState(false).value
    val isLocked = TrackerLocker.isLocked.observeAsState(false).value
    val sessionData: TrackerSession? = SessionUpdateReceiver.sessionData.observeAsState().value
    val collectionData: CollectionData? = SessionUpdateReceiver.collectionData.observeAsState().value
    val hasLocationPermission = LocalContext.current.hasLocationPermission

    val haptics = LocalHapticFeedback.current

    Scaffold(
        modifier = modifier,
        topBar = {
            TrackerTopBar(
                isTracking = isTracking,
                onSettingsClick = onSettingsClick
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
                    onRequestPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
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
                onSettingsClick = onSettingsClick
            )
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun TrackerTopBar(
    isTracking: Boolean,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surface) {
        Column {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        BadgedBox(badge = {
                            val locked = TrackerLocker.isLocked.observeAsState(false).value
                            if (locked) { Badge { Text("🔒") } }
                        }) {
                            Text(
                                text = if (isTracking) stringResource(R.string.notification_tracking_active) else stringResource(R.string.settings_tracking_title),
                                style = MaterialTheme.typography.titleLarge
                            )
                        }
                    }
                },
                actions = {
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
private fun TrackingContent(
    sessionData: TrackerSession?,
    collectionData: CollectionData?,
    isTracking: Boolean,
    isLocked: Boolean,
    onSettingsClick: () -> Unit,
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

    val expandDetails = remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current

    Column(modifier = modifier.fillMaxSize()) {
        // Progressive disclosure toggle
        Row(modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            AnimatedVisibility(visible = isLocked) {
                LockBanner(onClick = onSettingsClick)
            }

            IconButton(
                onClick = {
                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    expandDetails.value = !expandDetails.value
                },
                modifier = Modifier.testTag("expand_details_button")
            ) {
                val rotation by animateFloatAsState(if (expandDetails.value) 180f else 0f, label = "rot")
                Icon(Icons.Outlined.ExpandMore, contentDescription = "Expand details", modifier = Modifier.rotate(rotation))
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            sessionData?.let { session ->
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(columns) }, key = "session") {
                    SessionOverviewCard(session = session)
                }
            }

            // Determine which components to show
            val enabledComponents = buildList<TrackingComponent> {
                if (collectionData?.location != null || expandDetails.value) add(TrackingComponent.Location)
                if (collectionData?.activity != null || expandDetails.value) add(TrackingComponent.Activity)
                if (collectionData?.wifi != null || expandDetails.value) add(TrackingComponent.Wifi)
                if (collectionData?.cell != null || expandDetails.value) add(TrackingComponent.Cell)
            }

            items(enabledComponents, key = { it.key }) { component ->
                val enabled = when (component) {
                    TrackingComponent.Location -> locationEnabled
                    TrackingComponent.Activity -> activityEnabled
                    TrackingComponent.Wifi -> wifiEnabled
                    TrackingComponent.Cell -> cellEnabled
                }
                ComponentCard(
                    component = component,
                    enabled = enabled,
                    onClick = if (enabled) null else onSettingsClick
                )
            }

            if (!isTracking && sessionData == null && collectionData == null) {
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(columns) }, key = "empty") {
                    EmptyStateCard()
                }
            }

            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(columns) }) { Spacer(Modifier.height(80.dp)) }
        }
    }
}

private val android.content.Context.trackingTogglesDataStore by preferencesDataStore(name = "tracking_toggles")

@Composable
private fun rememberPrefBoolean(keyRes: Int, defaultRes: Int): Boolean {
    val context = LocalContext.current
    val keyName = remember(context, keyRes) { context.getString(keyRes) }
    val default = remember(context, defaultRes) { context.resources.getString(defaultRes).toBoolean() }
    val ds = remember(context) { context.trackingTogglesDataStore }
    val flow = remember(ds, keyName, default) {
        val prefKey = booleanPreferencesKey(keyName)
        ds.data.map { it[prefKey] ?: default }
    }
    val value = flow.collectAsState(initial = default).value
    return value
}

@Composable
private fun SessionOverviewCard(session: TrackerSession) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = stringResource(R.string.tracker_session_card_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "--:--",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
private fun EmptyStateCard() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.settings_tracking_title),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.shortcut_start_tracking_long),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

private sealed class TrackingComponent(val key: String) {
    data object Location : TrackingComponent("location")
    data object Activity : TrackingComponent("activity")
    data object Wifi : TrackingComponent("wifi")
    data object Cell : TrackingComponent("cell")
}

@Composable
private fun ComponentCard(component: TrackingComponent, enabled: Boolean, onClick: (() -> Unit)?) {
    val containerColor = if (enabled) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant
    val contentAlpha = if (enabled) 1f else 0.6f

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
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = onClick != null) { onClick?.invoke() }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.alpha(contentAlpha))

            if (!enabled) {
                Spacer(Modifier.weight(1f))
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = stringResource(R.string.description_settings),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("component_settings_icon")
                )
            }
        }
    }
}

@OptIn(ExperimentalAnimationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun TrackingFAB(
    isTracking: Boolean,
    hasPermission: Boolean,
    onToggleTracking: () -> Unit,
    onRequestPermission: () -> Unit,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "fab")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isTracking) 1.08f else 1f,
        animationSpec = infiniteRepeatable<Float>(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    LargeFloatingActionButton(
        onClick = {
            if (!hasPermission) onRequestPermission() else onToggleTracking()
        },
        containerColor = when {
            !hasPermission -> MaterialTheme.colorScheme.surfaceVariant
            isTracking -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.primary
        },
        contentColor = when {
            !hasPermission -> MaterialTheme.colorScheme.onSurfaceVariant
            isTracking -> MaterialTheme.colorScheme.onError
            else -> MaterialTheme.colorScheme.onPrimary
        },
        modifier = Modifier
            .size(88.dp)
            .testTag("tracking_fab")
    ) {
        val icon: ImageVector = when {
            !hasPermission -> Icons.Default.LocationOff
            isTracking -> Icons.Default.Stop
            else -> Icons.Default.PlayArrow
        }
        AnimatedContent(targetState = icon, label = "fab_icon") { target ->
            Icon(target, contentDescription = null, modifier = Modifier.size(36.dp))
        }
    }
}

@Composable
private fun LockBanner(onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.testTag("lock_banner")
    ) {
        Row(
            Modifier
                .clickable { onClick() }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Outlined.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text = stringResource(R.string.settings_disabled_recharge_title), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(Icons.Outlined.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
