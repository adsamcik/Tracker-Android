package com.adsamcik.tracker.app.widget.glance

import android.content.Context
import android.content.Intent
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import com.adsamcik.tracker.tracker.api.TrackerServiceApi
import kotlinx.coroutines.delay

/**
 * Glance ActionCallback that toggles tracking on/off.
 * Uses TrackerServiceApi static methods which internally use Hilt EntryPoint.
 */
class ToggleTrackingAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        try {
            val isRunning = TrackerServiceApi.isActive(context)
            if (isRunning) {
                TrackerServiceApi.stopService(context)
            } else {
                TrackerServiceApi.startService(context, isUserInitiated = true)
            }
            // Allow state to propagate before refreshing widgets.
            delay(STATE_PROPAGATION_DELAY_MS)
            WidgetUpdateScheduler.updateAllWidgets(context)
        } catch (_: Exception) {
            // Service not available (e.g. Hilt not initialized after OOM kill).
            // Fail silently - user can retry.
        }
    }

    private companion object {
        const val STATE_PROPAGATION_DELAY_MS = 500L
    }
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
