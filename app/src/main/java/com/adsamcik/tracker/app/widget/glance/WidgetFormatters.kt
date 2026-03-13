package com.adsamcik.tracker.app.widget.glance

import android.content.Context
import com.adsamcik.tracker.R
import java.util.Locale

/**
 * Lightweight formatting utilities for widget display.
 * Keeps widget updates fast by avoiding full formatter infrastructure.
 */
object WidgetFormatters {

    /**
     * Formats distance in meters to a human-readable string.
     * Uses km for >= 1000m, otherwise meters.
     */
    fun formatDistance(context: Context, meters: Float): String {
        return if (meters >= 1000f) {
            val km = meters / 1000f
            context.getString(R.string.widget_format_km, km)
        } else {
            context.getString(R.string.widget_format_meters, meters.toInt())
        }
    }

    /**
     * Formats duration in milliseconds to HH:mm:ss or mm:ss.
     */
    fun formatDuration(durationMs: Long): String {
        if (durationMs < 0) return "0:00"
        val totalSeconds = durationMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    /**
     * Formats step count with thousands separator using user locale.
     */
    fun formatSteps(steps: Int): String {
        return String.format(Locale.getDefault(), "%,d", steps)
    }

    /**
     * Formats goal progress as percentage string.
     */
    fun formatGoalProgress(progress: Float): String {
        return String.format(Locale.US, "%d%%", (progress * 100).toInt().coerceIn(0, 100))
    }
}
