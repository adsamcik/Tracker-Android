package com.adsamcik.tracker.app.widget.glance

import android.content.Context
import android.content.Intent
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import com.adsamcik.tracker.tracker.api.ManualTrackingStartRepairNavigation
import com.adsamcik.tracker.tracker.api.ManualTrackingStartResult
import com.adsamcik.tracker.tracker.api.TrackerServiceApi
import com.adsamcik.tracker.tracker.api.TrackingStopQuiescenceResult
import com.adsamcik.tracker.tracker.api.TrackingTurnOffResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Starts or ends a session without changing all-day tracking choices. */
class ToggleTrackingAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        try {
            val isRunning = TrackerServiceApi.hasLiveCapture(context)
            if (isRunning) {
                if (withTimeoutOrNull(STOP_ACTION_TIMEOUT_MS) {
                    TrackerServiceApi.stopServiceAndAwaitQuiescence(context)
                } !=
                    TrackingStopQuiescenceResult.HANDLED
                ) openDashboard(context)
            } else {
                val result = TrackerServiceApi.requestManualTrackingStart(context)
                if (result != ManualTrackingStartResult.ENQUEUED) {
                    openDashboard(context, when (result) {
                        is ManualTrackingStartResult.RepairRequired -> result.continuationGeneration
                        is ManualTrackingStartResult.SetupPending -> result.continuationGeneration
                        else -> null
                    })
                }
            }
            // Allow state to propagate before refreshing widgets.
            delay(STATE_PROPAGATION_DELAY_MS)
            WidgetUpdateScheduler.updateAllWidgets(context)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Bring the user to the foreground repair surface when background prerequisites fail.
            openDashboard(context)
        }
    }

    private companion object {
        const val STATE_PROPAGATION_DELAY_MS = 500L
        const val STOP_ACTION_TIMEOUT_MS = 8_000L
    }
}

/** Stops all collection, including all-day sources and automatic starts. */
class TurnOffTrackingAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        try {
            when (TrackerServiceApi.turnOffTrackingAndAwait(context)) {
                TrackingTurnOffResult.UNAVAILABLE,
                TrackingTurnOffResult.SUPERSEDED_BY_NEW_START ->
                    openDashboard(context)
                TrackingTurnOffResult.COMPLETE,
                TrackingTurnOffResult.DURABLY_OFF_CLEANUP_PENDING -> Unit
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            openDashboard(context)
        } finally {
            WidgetUpdateScheduler.updateAllWidgets(context)
        }
    }
}

private fun openDashboard(context: Context, continuationGeneration: Long? = null) {
    ManualTrackingStartRepairNavigation.createDashboardIntent(
        context,
        continuationGeneration = continuationGeneration,
    )?.let { context.startActivity(it) }
}

/**
 * Glance ActionCallback that opens the main app activity.
 */
class OpenAppAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        context.startActivity(intent)
    }
}
