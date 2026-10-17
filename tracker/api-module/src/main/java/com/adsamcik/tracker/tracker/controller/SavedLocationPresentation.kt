package com.adsamcik.tracker.tracker.controller

import android.content.Context
import com.adsamcik.tracker.stats.api.repository.SavedLocationSummaryRead
import com.adsamcik.tracker.stats.api.repository.SavedLocationSummarySelection
import com.adsamcik.tracker.tracker.R

/** Read-only live presentation gate; source-owned saved readers retain qualification and epoch authority. */
fun SavedLocationSummaryRead.forLiveSegment(
    segmentId: Long?,
    participating: Boolean,
): SavedLocationSummaryRead = when {
    !participating || segmentId == null || segmentId <= 0L -> SavedLocationSummaryRead.NotFound
    this is SavedLocationSummaryRead.Snapshot &&
        selection != SavedLocationSummarySelection.Session(segmentId) -> SavedLocationSummaryRead.NotFound
    else -> this
}

/** A missing measurement is never a measured zero; available partial distance stays useful. */
fun SavedLocationSummaryRead.formatSavedDistance(
    context: Context,
    format: (Double) -> String,
): String {
    val snapshot = this as? SavedLocationSummaryRead.Snapshot
    return formatSavedValue(context, snapshot?.summary?.distanceMeters, format)
}

/**
 * Formats saved moving average speed in metres per second through the supplied formatter; missing data
 * keeps its status.
 */
fun SavedLocationSummaryRead.formatSavedAverageSpeed(
    context: Context,
    format: (Double) -> String,
): String = formatSavedValue(context,
    (this as? SavedLocationSummaryRead.Snapshot)?.summary?.movingAverageSpeedMetersPerSecond, format)

private fun SavedLocationSummaryRead.formatSavedValue(
    context: Context,
    value: Double?,
    format: (Double) -> String,
): String {
    val snapshot = this as? SavedLocationSummaryRead.Snapshot
    val formatted = value?.let(format)
    return when {
        formatted != null && snapshot?.summary?.partial == true ->
            "$formatted · ${context.getString(R.string.tracker_measurement_partial)}"
        formatted != null -> formatted
        snapshot?.summary?.partial == true -> context.getString(R.string.tracker_measurement_partial)
        this is SavedLocationSummaryRead.Unavailable ->
            context.getString(R.string.tracker_steps_value_unavailable)
        else -> context.getString(R.string.tracker_steps_today_waiting)
    }
}
