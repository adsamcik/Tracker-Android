package com.adsamcik.tracker.app.widget.glance

import android.content.Context
import androidx.compose.runtime.Composable
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
import com.adsamcik.tracker.shared.base.data.CollectionData
import com.adsamcik.tracker.shared.base.data.DetectedActivity
import com.adsamcik.tracker.shared.base.data.Location
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.stats.api.PolicyTier
import dagger.hilt.android.EntryPointAccessors

/**
 * Active Session widget (4x3): live-ish session data during tracking.
 * Shows distance, steps, duration, activity tier, and collection count.
 * When not tracking, shows a "tap to start" idle state.
 *
 * Limitation: True real-time updates (e.g. every 5s) are not feasible with Glance
 * due to Android background restrictions. Widget updates via WorkManager periodic task
 * (~15 min minimum) plus event-driven updates when tracking starts/stops.
 */
class ActiveSessionWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val isRunning: Boolean
        val session: TrackerSession?
        val policyTier: PolicyTier
        val collectionData: CollectionData?
        val pathPoints: List<Location>

        try {
            val entryPoint = EntryPointAccessors.fromApplication(
                context.applicationContext,
                TrackerWidgetEntryPoint::class.java,
            )
            val controller = entryPoint.trackerServiceController()
            isRunning = controller.isServiceRunning
            session = controller.sessionFlow.value
            policyTier = controller.policyTierFlow.value
            collectionData = controller.collectionDataFlow.value
            pathPoints = controller.pathPointsFlow.value?.second.orEmpty()
        } catch (_: Exception) {
            // Hilt not initialized - show idle state.
            provideContent {
                GlanceTheme {
                    ActiveSessionContent(
                        isRunning = false,
                        session = null,
                        policyTier = PolicyTier.OFF,
                        snapshot = ActiveSessionSnapshot.empty(context),
                        context = context,
                    )
                }
            }
            return
        }

        val snapshot = buildSnapshot(context, collectionData, pathPoints)
        provideContent {
            GlanceTheme {
                ActiveSessionContent(
                    isRunning = isRunning,
                    session = session,
                    policyTier = policyTier,
                    snapshot = snapshot,
                    context = context,
                )
            }
        }
    }
}

@Composable
private fun ActiveSessionContent(
    isRunning: Boolean,
    session: TrackerSession?,
    policyTier: PolicyTier,
    snapshot: ActiveSessionSnapshot,
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
                context = context,
            )
        }
    }
}

@Composable
private fun IdleContent(context: Context) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .clickable(actionRunCallback<ToggleTrackingAction>()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically,
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
}

@Composable
private fun TrackingContent(
    session: TrackerSession,
    policyTier: PolicyTier,
    snapshot: ActiveSessionSnapshot,
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
                text = "${snapshot.activityIcon} ${snapshot.activityLabel} • ${formatPolicyTier(policyTier, context)}",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 11.sp,
                ),
            )
        }
        // Stop button - 48dp touch target
        Column(
            modifier = GlanceModifier
                .size(48.dp)
                .clickable(actionRunCallback<ToggleTrackingAction>()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                provider = ImageProvider(R.drawable.ic_widget_stop),
                contentDescription = context.getString(R.string.widget_stop_tracking),
                modifier = GlanceModifier.size(32.dp),
                colorFilter = ColorFilter.tint(GlanceTheme.colors.error),
            )
        }
    }

    Spacer(modifier = GlanceModifier.height(12.dp))

    Text(
        text = snapshot.speedText,
        style = TextStyle(
            color = GlanceTheme.colors.primary,
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
        ),
    )
    Text(
        text = context.getString(R.string.widget_current_speed),
        style = TextStyle(
            color = GlanceTheme.colors.onSurfaceVariant,
            fontSize = 11.sp,
        ),
    )

    Spacer(modifier = GlanceModifier.height(12.dp))

    // Stats grid
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
    ) {
        SessionStatItem(
            label = context.getString(R.string.widget_session_distance),
            value = WidgetFormatters.formatDistance(context, session.distanceInM),
            modifier = GlanceModifier.defaultWeight(),
        )
        SessionStatItem(
            label = context.getString(R.string.widget_session_duration),
            value = WidgetFormatters.formatDuration(System.currentTimeMillis() - session.start),
            modifier = GlanceModifier.defaultWeight(),
        )
    }

    Spacer(modifier = GlanceModifier.height(8.dp))

    Row(
        modifier = GlanceModifier.fillMaxWidth(),
    ) {
        SessionStatItem(
            label = context.getString(R.string.widget_session_steps),
            value = WidgetFormatters.formatSteps(session.steps),
            modifier = GlanceModifier.defaultWeight(),
        )
        SessionStatItem(
            label = context.getString(R.string.widget_collections),
            value = session.collections.toString(),
            modifier = GlanceModifier.defaultWeight(),
        )
    }

    Spacer(modifier = GlanceModifier.height(8.dp))

    Text(
        text = context.getString(R.string.widget_path_preview),
        style = TextStyle(
            color = GlanceTheme.colors.onSurfaceVariant,
            fontSize = 10.sp,
        ),
    )
    Text(
        text = snapshot.pathPreview,
        style = TextStyle(
            color = GlanceTheme.colors.onSurface,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
        ),
    )
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
    collectionData: CollectionData?,
    pathPoints: List<Location>,
): ActiveSessionSnapshot {
    val activity = collectionData?.activity?.activity
    return ActiveSessionSnapshot(
        speedText = WidgetFormatters.formatSpeed(context, collectionData?.location?.speed),
        activityIcon = activity.activityIcon(),
        activityLabel = activity?.let { context.getString(it.nameRes) }
            ?: context.getString(R.string.widget_activity_unknown),
        pathPreview = WidgetFormatters.formatPathPreview(pathPoints),
    )
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
    val activityIcon: String,
    val activityLabel: String,
    val pathPreview: String,
) {
    companion object {
        fun empty(context: Context) = ActiveSessionSnapshot(
            speedText = context.getString(R.string.widget_speed_unknown),
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
