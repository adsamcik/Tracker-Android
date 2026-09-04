package com.adsamcik.tracker.app.widget.glance

import android.content.Context
import androidx.annotation.StringRes
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
import com.adsamcik.tracker.shared.base.data.DetectedActivity
import com.adsamcik.tracker.shared.model.Location
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.SessionHistoryQuery
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryRepository
import com.adsamcik.tracker.tracker.data.collection.TrackerActivityType
import com.adsamcik.tracker.tracker.data.collection.TrackerCollectionSnapshot
import com.adsamcik.tracker.tracker.data.session.TrackerSessionSnapshot
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

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
        val policyTier: PolicyTier
        val collectionSnapshot: TrackerCollectionSnapshot?
        val pathPoints: List<Location>
        val qualifiedSteps: Long?

        try {
            val entryPoint = EntryPointAccessors.fromApplication(
                context.applicationContext,
                TrackerWidgetEntryPoint::class.java,
            )
            val controller = entryPoint.trackerStateReader()
            isRunning = controller.isServiceRunning
            session = controller.sessionFlow.value
            policyTier = controller.policyTierFlow.value
            collectionSnapshot = controller.collectionDataFlow.value
            pathPoints = controller.pathPointsFlow.value?.second.orEmpty()
            qualifiedSteps = if (isRunning) {
                readQualifiedWidgetSteps(entryPoint.trackingHistoryRepository(), session)
            } else {
                null
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
                        qualifiedSteps = null,
                        context = context,
                    )
                }
            }
            return
        }

        val snapshot = buildSnapshot(context, collectionSnapshot, pathPoints)
        provideContent {
            GlanceTheme {
                ActiveSessionContent(
                    isRunning = isRunning,
                    session = session,
                    policyTier = policyTier,
                    snapshot = snapshot,
                    qualifiedSteps = qualifiedSteps,
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
    qualifiedSteps: Long?,
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
                qualifiedSteps = qualifiedSteps,
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
    session: TrackerSessionSnapshot,
    policyTier: PolicyTier,
    snapshot: ActiveSessionSnapshot,
    qualifiedSteps: Long?,
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

    val stats = buildActiveSessionStats(
        context = context,
        session = session,
        qualifiedSteps = qualifiedSteps,
        nowMillis = System.currentTimeMillis(),
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

internal data class ActiveSessionStat(
    @StringRes val labelRes: Int,
    val value: String,
)

/** Raw live-session Steps are never read; only exact-segment complete history may add the cell. */
internal fun buildActiveSessionStats(
    context: Context,
    session: TrackerSessionSnapshot,
    qualifiedSteps: Long?,
    nowMillis: Long,
): List<ActiveSessionStat> = buildList {
    add(
        ActiveSessionStat(
            R.string.widget_session_distance,
            WidgetFormatters.formatDistance(context, session.distanceInM),
        ),
    )
    add(
        ActiveSessionStat(
            R.string.widget_session_duration,
            WidgetFormatters.formatDuration(nowMillis - session.start),
        ),
    )
    qualifiedSteps?.let { steps ->
        add(
            ActiveSessionStat(
                R.string.widget_session_steps,
                WidgetFormatters.formatSteps(steps),
            ),
        )
    }
    add(
        ActiveSessionStat(
            R.string.widget_collections,
            session.collections.toString(),
        ),
    )
}

internal suspend fun readQualifiedWidgetSteps(
    repository: TrackingHistoryRepository,
    session: TrackerSessionSnapshot?,
): Long? {
    val segmentId = session?.id?.takeIf { it > 0L } ?: return null
    return try {
        repository.observeSession(segmentId).first().completeStepsForSegment(segmentId)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        null
    }
}

/** Fences the value to the exact active physical segment and complete qualified coverage. */
internal fun SessionHistoryQuery.completeStepsForSegment(expectedSegmentId: Long): Long? =
    (this as? SessionHistoryQuery.Found)
        ?.history
        ?.takeIf { history ->
            history.segmentId == expectedSegmentId &&
                HistorySource.STEPS in history.qualifiedSources
        }
        ?.steps
        ?.takeIf { steps -> steps.hasCompleteValue }
        ?.count

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
): ActiveSessionSnapshot {
    val activity = collectionSnapshot?.activity?.type?.toLegacyDetectedActivity()
    return ActiveSessionSnapshot(
        speedText = WidgetFormatters.formatSpeed(context, collectionSnapshot?.location?.speed),
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
