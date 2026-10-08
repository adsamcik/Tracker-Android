package com.adsamcik.tracker.app.widget.glance

import android.content.Context
import com.adsamcik.tracker.R
import java.text.NumberFormat
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
    fun formatDistance(context: Context, meters: Float): String = formatDistance(context, meters.toDouble())

    /** Formats the shared saved distance without narrowing its accumulated value to Float. */
    fun formatDistance(context: Context, meters: Double): String {
        return if (meters >= 1000.0) {
            val km = meters / 1000.0
            context.getString(R.string.widget_format_km, km)
        } else {
            context.getString(R.string.widget_format_meters, meters.toInt())
        }
    }

    /**
     * Formats duration in milliseconds to HH:mm:ss or mm:ss.
     */
    fun formatDuration(durationMs: Long, locale: Locale = Locale.getDefault()): String {
        val totalSeconds = durationMs.coerceAtLeast(0L) / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(locale, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(locale, "%d:%02d", minutes, seconds)
        }
    }

    /**
     * Formats step count with thousands separator using user locale.
     */
    fun formatSteps(steps: Int, locale: Locale = Locale.getDefault()): String {
        return String.format(locale, "%,d", steps)
    }

    /** Formats a durable Steps count without narrowing or overflowing it. */
    fun formatSteps(steps: Long, locale: Locale = Locale.getDefault()): String {
        return String.format(locale, "%,d", steps)
    }

    /**
     * Formats goal progress as percentage string.
     */
    fun formatGoalProgress(progress: Float, locale: Locale = Locale.getDefault()): String {
        val percent = (progress * 100).toInt().coerceIn(0, 100)
        return NumberFormat.getPercentInstance(locale).apply {
            maximumFractionDigits = 0
        }.format(percent / 100.0)
    }
}
