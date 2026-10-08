package com.adsamcik.tracker.app.widget.glance

import com.adsamcik.tracker.stats.api.repository.SavedLocationSummaryRead
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.tracker.controller.forLiveSegment
import com.adsamcik.tracker.tracker.controller.formatSavedDistance
import com.adsamcik.tracker.tracker.controller.hasLiveCapture
import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsRepository
import com.adsamcik.tracker.tracker.controller.TrackerStateReader
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.adsamcik.tracker.R
import com.adsamcik.tracker.shared.base.data.DetectedActivity
import com.adsamcik.tracker.shared.model.Location
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryRepository
import com.adsamcik.tracker.tracker.data.collection.TrackerActivityType
import com.adsamcik.tracker.tracker.data.collection.TrackerCollectionSnapshot
import com.adsamcik.tracker.tracker.data.session.TrackerSessionSnapshot
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Active Session widget (4x3): live-ish session data during tracking.
 * Shows distance, duration, activity tier, and collection count.
 * When not tracking, shows a "tap to start" idle state.
 *
 * Limitation: True real-time updates (e.g. every 5s) are not feasible with Glance
 * due to Android background restrictions. Widget updates via WorkManager periodic task
 * (~15 min minimum) plus event-driven updates when tracking starts/stops.
 */
class ActiveSessionWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val isRunning: Boolean
        val session: TrackerSessionSnapshot?
        val stepsPresentation: WidgetStepsPresentation
        val trackingParams: TrackingParamsState
        val controller: TrackerStateReader
        val paramsRepository: TrackingParamsRepository

        try {
            val entryPoint = EntryPointAccessors.fromApplication(
                context.applicationContext,
                TrackerWidgetEntryPoint::class.java,
            )
            controller = entryPoint.trackerStateReader()
            paramsRepository = entryPoint.trackingParamsRepository()
            isRunning = controller.hasLiveCapture
            trackingParams = withTimeoutOrNull(WIDGET_STEPS_SETTLEMENT_TIMEOUT_MILLIS) {
                paramsRepository.data.first()
            } ?: TrackingParamsState(locationEnabled = false, stepsEnabled = false, activityEnabled = false,
                wifiEnabled = false, cellEnabled = false, barometerEnabled = false)
            session = controller.sessionFlow.value
            stepsPresentation = if (isRunning && trackingParams.stepsEnabled) {
                readWidgetSteps(entryPoint.trackingHistoryRepository(), session)
            } else {
                WidgetStepsPresentation.NotCaptured
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // Hilt not initialized - show idle state.
            provideContent {
                GlanceTheme {
                    ActiveSessionContent(
                        isRunning = false,
                        session = null,
                        policyTier = PolicyTier.OFF,
                        snapshot = ActiveSessionSnapshot.empty(context),
                        stepsPresentation = WidgetStepsPresentation.StorageUnavailable,
                        context = context,
                    )
                }
            }
            return
        }

        provideContent {
            // Glance owns these collectors while its existing composition is alive. No worker/wake is added.
            val hosted by controller.isServiceRunningFlow.collectAsState()
            val currentInfo by controller.sessionInfoFlow.collectAsState()
            val currentSession by controller.sessionFlow.collectAsState()
            val currentPolicy by controller.policyTierFlow.collectAsState()
            val currentCollection by controller.collectionDataFlow.collectAsState()
            val currentPath by controller.pathPointsFlow.collectAsState()
            val savedRead by controller.savedLocationSummaryFlow.collectAsState()
            val currentChoices by paramsRepository.data.collectAsState(initial = trackingParams)
            val active = hosted && currentInfo != null && (currentSession?.id ?: 0L) > 0L
            val savedLocation = savedRead.forLiveSegment(currentSession?.id, active && currentChoices.locationEnabled)
            val preview = currentPath?.takeIf {
                currentChoices.locationEnabled && it.first == currentSession?.id }?.second.orEmpty()
            val snapshot = buildSnapshot(context, currentCollection, preview, savedLocation,
                currentChoices.locationEnabled, currentChoices.activityEnabled)
            GlanceTheme {
                ActiveSessionContent(
                    isRunning = active,
                    session = currentSession,
                    policyTier = currentPolicy,
                    snapshot = snapshot,
                    stepsPresentation = if (currentSession?.id == session?.id) stepsPresentation
                        else WidgetStepsPresentation.Materializing,
                    savedLocation = savedLocation,
                    trackingParams = currentChoices,
                    context = context,
                )
            }
        }
    }
}

@Composable
private fun ActiveSessionContent(
    isRunning: Boolean,
    session: TrackerSessionSnapshot?,
    policyTier: PolicyTier,
    snapshot: ActiveSessionSnapshot,
    stepsPresentation: WidgetStepsPresentation,
    savedLocation: SavedLocationSummaryRead = SavedLocationSummaryRead.NotFound,
    trackingParams: TrackingParamsState = TrackingParamsState(),
    context: Context,
) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.surfaceVariant)
            .cornerRadius(16.dp)
            .padding(16.dp),
    ) {
        if (!isRunning || session == null) {
            IdleContent(context)
        } else {
            TrackingContent(
                session = session,
                policyTier = policyTier,
                snapshot = snapshot,
                stepsPresentation = stepsPresentation,
                savedLocation = savedLocation,
                trackingParams = trackingParams,
                context = context,
            )
        }
    }
}

@Composable
private fun IdleContent(context: Context) {
    Row(
        modifier = GlanceModifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = GlanceModifier.defaultWeight()
                .clickable(actionRunCallback<ToggleTrackingAction>()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                provider = ImageProvider(R.drawable.ic_widget_play),
                contentDescription = context.getString(R.string.widget_start_tracking),
                modifier = GlanceModifier.size(48.dp),
                colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
            )
            Spacer(modifier = GlanceModifier.height(8.dp))
            Text(
                text = context.getString(R.string.widget_no_active_session),
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                ),
            )
            Spacer(modifier = GlanceModifier.height(4.dp))
            Text(
                text = context.getString(R.string.widget_tap_to_start),
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 12.sp,
                ),
            )
        }
        Column(
            modifier = GlanceModifier.width(72.dp)
                .clickable(actionRunCallback<TurnOffTrackingAction>()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                provider = ImageProvider(R.drawable.ic_widget_stop),
                contentDescription = context.getString(R.string.widget_turn_off_tracking),
                modifier = GlanceModifier.size(32.dp),
                colorFilter = ColorFilter.tint(GlanceTheme.colors.error),
            )
            Text(
                text = context.getString(R.string.widget_turn_off_short),
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp),
            )
        }
    }
}

@Composable
private fun TrackingContent(
    session: TrackerSessionSnapshot,
    policyTier: PolicyTier,
    snapshot: ActiveSessionSnapshot,
    stepsPresentation: WidgetStepsPresentation,
    savedLocation: SavedLocationSummaryRead = SavedLocationSummaryRead.NotFound,
    trackingParams: TrackingParamsState = TrackingParamsState(),
    context: Context,
) {
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                text = context.getString(R.string.widget_tracking_active),
                style = TextStyle(
                    color = GlanceTheme.colors.primary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
            Text(
                text = if (snapshot.activityEnabled)
                    "${snapshot.activityIcon} ${snapshot.activityLabel} • ${formatPolicyTier(policyTier, context)}"
                    else formatPolicyTier(policyTier, context),
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 11.sp,
                ),
            )
        }
        // Keep End distinct from the all-purpose Turn off control.
        Column(
            modifier = GlanceModifier
                .size(48.dp)
                .clickable(actionRunCallback<ToggleTrackingAction>()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                provider = ImageProvider(R.drawable.ic_widget_stop),
                contentDescription = context.getString(R.string.widget_end_session),
                modifier = GlanceModifier.size(24.dp),
                colorFilter = ColorFilter.tint(GlanceTheme.colors.error),
            )
            Text(
                text = context.getString(R.string.widget_end_short),
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 10.sp),
            )
        }
        Column(
            modifier = GlanceModifier.width(64.dp)
                .height(48.dp)
                .clickable(actionRunCallback<TurnOffTrackingAction>()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                provider = ImageProvider(R.drawable.ic_widget_stop),
                contentDescription = context.getString(R.string.widget_turn_off_tracking),
                modifier = GlanceModifier.size(24.dp),
                colorFilter = ColorFilter.tint(GlanceTheme.colors.error),
            )
            Text(
                text = context.getString(R.string.widget_turn_off_short),
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 10.sp),
            )
        }
    }

    Spacer(modifier = GlanceModifier.height(12.dp))

    if (snapshot.locationEnabled) Text(
        text = snapshot.speedText,
        style = TextStyle(
            color = GlanceTheme.colors.primary,
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
        ),
    )
    if (snapshot.locationEnabled) Text(
        text = context.getString(R.string.widget_current_speed),
        style = TextStyle(
            color = GlanceTheme.colors.onSurfaceVariant,
            fontSize = 11.sp,
        ),
    )

    Spacer(modifier = GlanceModifier.height(12.dp))

    val stats = buildActiveSessionStats(
        context = context,
        session = session,
        stepsPresentation = stepsPresentation,
        nowMillis = System.currentTimeMillis(),
        savedLocation = savedLocation,
        locationEnabled = trackingParams.locationEnabled,
        stepsEnabled = trackingParams.stepsEnabled,
    )
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
    ) {
        stats.take(2).forEach { stat ->
            SessionStatItem(
                label = context.getString(stat.labelRes),
                value = stat.value,
                modifier = GlanceModifier.defaultWeight(),
            )
        }
    }

    Spacer(modifier = GlanceModifier.height(8.dp))

    Row(
        modifier = GlanceModifier.fillMaxWidth(),
    ) {
        stats.drop(2).forEach { stat ->
            SessionStatItem(
                label = context.getString(stat.labelRes),
                value = stat.value,
                modifier = GlanceModifier.defaultWeight(),
            )
        }
    }

    Spacer(modifier = GlanceModifier.height(8.dp))

    if (snapshot.locationEnabled) Text(
        text = context.getString(R.string.widget_path_preview),
        style = TextStyle(
            color = GlanceTheme.colors.onSurfaceVariant,
            fontSize = 10.sp,
        ),
    )
    if (snapshot.locationEnabled) Text(
        text = snapshot.pathPreview,
        style = TextStyle(
            color = GlanceTheme.colors.onSurface,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
        ),
    )
}

internal data class ActiveSessionStat(
    @StringRes val labelRes: Int,
    val value: String,
)

/** Raw live-session Steps are never read; exact-segment history supplies a value or typed status. */
internal fun buildActiveSessionStats(
    context: Context,
    session: TrackerSessionSnapshot,
    stepsPresentation: WidgetStepsPresentation,
    nowMillis: Long,
    savedLocation: SavedLocationSummaryRead = SavedLocationSummaryRead.NotFound,
    locationEnabled: Boolean = true,
    stepsEnabled: Boolean = true,
): List<ActiveSessionStat> = buildList {
    if (locationEnabled) add(
        ActiveSessionStat(
            R.string.widget_session_distance,
            savedLocation.forLiveSegment(session.id, locationEnabled).formatSavedDistance(context) {
                WidgetFormatters.formatDistance(context, it)
            },
        ),
    )
    add(
        ActiveSessionStat(
            R.string.widget_session_duration,
            WidgetFormatters.formatDuration(
                nowMillis - session.start, context.resources.configuration.locales[0],
            ),
        ),
    )
    if (stepsEnabled) add(
        ActiveSessionStat(
            R.string.widget_session_steps,
            stepsPresentation.displayValue(context),
        ),
    )
    add(
        ActiveSessionStat(
            R.string.widget_collections,
            String.format(context.resources.configuration.locales[0], "%,d", session.collections),
        ),
    )
}

internal const val WIDGET_STEPS_SETTLEMENT_TIMEOUT_MILLIS = 5_000L

internal suspend fun readWidgetSteps(
    repository: TrackingHistoryRepository,
    session: TrackerSessionSnapshot?,
    timeoutMillis: Long = WIDGET_STEPS_SETTLEMENT_TIMEOUT_MILLIS,
): WidgetStepsPresentation {
    require(timeoutMillis > 0L) { "Widget Steps timeout must be positive" }
    val segmentId = session?.id?.takeIf { it > 0L }
        ?: return WidgetStepsPresentation.Unavailable
    return try {
        withTimeoutOrNull(timeoutMillis) {
            repository.observeSession(segmentId)
                .map { query -> query.toWidgetStepsPresentation(segmentId) }
                .first { presentation -> presentation != WidgetStepsPresentation.Materializing }
        } ?: WidgetStepsPresentation.Materializing
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        WidgetStepsPresentation.StorageUnavailable
    }
}

@Composable
private fun SessionStatItem(
    label: String,
    value: String,
    modifier: GlanceModifier = GlanceModifier,
) {
    Column(modifier = modifier) {
        Text(
            text = value,
            style = TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
        Text(
            text = label,
            style = TextStyle(
                color = GlanceTheme.colors.onSurfaceVariant,
                fontSize = 10.sp,
            ),
        )
    }
}

private fun formatPolicyTier(tier: PolicyTier, context: Context): String {
    return when (tier) {
        PolicyTier.OFF -> context.getString(R.string.widget_tier_off)
        PolicyTier.AMBIENT -> context.getString(R.string.widget_tier_ambient)
        PolicyTier.ACTIVE -> context.getString(R.string.widget_tier_active)
        PolicyTier.PRECISION -> context.getString(R.string.widget_tier_precision)
    }
}

private fun buildSnapshot(
    context: Context,
    collectionSnapshot: TrackerCollectionSnapshot?,
    pathPoints: List<Location>,
    savedLocation: SavedLocationSummaryRead,
    locationEnabled: Boolean,
    activityEnabled: Boolean,
): ActiveSessionSnapshot {
    val activity = collectionSnapshot?.activity?.takeIf { activityEnabled }?.type?.toLegacyDetectedActivity()
    return ActiveSessionSnapshot(
        speedText = WidgetFormatters.formatSpeed(context,
            (savedLocation as? SavedLocationSummaryRead.Snapshot)?.summary?.latestFix?.speedMetersPerSecond),
        locationEnabled = locationEnabled,
        activityEnabled = activityEnabled,
        activityIcon = activity.activityIcon(),
        activityLabel = activity?.let { context.getString(it.nameRes) }
            ?: context.getString(R.string.widget_activity_unknown),
        pathPreview = WidgetFormatters.formatPathPreview(pathPoints),
    )
}

private fun TrackerActivityType.toLegacyDetectedActivity(): DetectedActivity = when (this) {
    TrackerActivityType.STILL -> DetectedActivity.STILL
    TrackerActivityType.RUNNING -> DetectedActivity.RUNNING
    TrackerActivityType.ON_FOOT -> DetectedActivity.ON_FOOT
    TrackerActivityType.ON_BICYCLE -> DetectedActivity.ON_BICYCLE
    TrackerActivityType.IN_VEHICLE -> DetectedActivity.IN_VEHICLE
    TrackerActivityType.TILTING -> DetectedActivity.TILTING
    TrackerActivityType.UNKNOWN -> DetectedActivity.UNKNOWN
    TrackerActivityType.WALKING -> DetectedActivity.WALKING
}

private fun DetectedActivity?.activityIcon(): String = when (this) {
    DetectedActivity.WALKING, DetectedActivity.ON_FOOT -> "👣"
    DetectedActivity.RUNNING -> "🏃"
    DetectedActivity.ON_BICYCLE -> "🚴"
    DetectedActivity.IN_VEHICLE -> "🚗"
    else -> "◎"
}

private data class ActiveSessionSnapshot(
    val speedText: String,
    val locationEnabled: Boolean,
    val activityEnabled: Boolean,
    val activityIcon: String,
    val activityLabel: String,
    val pathPreview: String,
) {
    companion object {
        fun empty(context: Context) = ActiveSessionSnapshot(
            speedText = context.getString(R.string.widget_speed_unknown),
            locationEnabled = false,
            activityEnabled = false,
            activityIcon = "◎",
            activityLabel = context.getString(R.string.widget_activity_unknown),
            pathPreview = "•",
        )
    }
}

/**
 * BroadcastReceiver for the Active Session widget.
 */
class ActiveSessionWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ActiveSessionWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetUpdateScheduler.schedulePeriodicUpdates(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WidgetUpdateScheduler.cancelPeriodicUpdates(context)
    }
}
