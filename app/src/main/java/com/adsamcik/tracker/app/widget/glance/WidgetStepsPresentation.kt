package com.adsamcik.tracker.app.widget.glance

import android.content.Context
import com.adsamcik.tracker.R
import com.adsamcik.tracker.shared.base.di.QualifiedStepCount
import com.adsamcik.tracker.shared.base.di.QualifiedStepCountUnavailableReason
import com.adsamcik.tracker.stats.api.repository.HistoryAvailability
import com.adsamcik.tracker.stats.api.repository.HistoryProductState
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.SessionHistoryQuery
import com.adsamcik.tracker.stats.api.repository.StepsHistoryCause

/** App-widget presentation state that never turns absent or incomplete Steps into zero. */
internal sealed interface WidgetStepsPresentation {
    data class Ready(val count: Long) : WidgetStepsPresentation {
        init {
            require(count >= 0L) { "Widget Steps count cannot be negative" }
        }
    }

    /** A correction-safe lower bound, when the retained product can expose one. */
    data class Partial(val lowerBound: Long? = null) : WidgetStepsPresentation {
        init {
            require(lowerBound == null || lowerBound >= 0L) {
                "Widget Steps lower bound cannot be negative"
            }
        }
    }

    data object Materializing : WidgetStepsPresentation
    data object NotCaptured : WidgetStepsPresentation
    data object Disabled : WidgetStepsPresentation
    data object Unavailable : WidgetStepsPresentation
    data object StorageUnavailable : WidgetStepsPresentation
}

internal fun QualifiedStepCount.toWidgetStepsPresentation(): WidgetStepsPresentation = when (this) {
    is QualifiedStepCount.Ready -> WidgetStepsPresentation.Ready(value.toLong())
    is QualifiedStepCount.Unavailable -> when (reason) {
        QualifiedStepCountUnavailableReason.MATERIALIZING -> WidgetStepsPresentation.Materializing
        QualifiedStepCountUnavailableReason.NOT_CAPTURED -> WidgetStepsPresentation.NotCaptured
        QualifiedStepCountUnavailableReason.DISABLED -> WidgetStepsPresentation.Disabled
        QualifiedStepCountUnavailableReason.PARTIAL_CAPTURE -> WidgetStepsPresentation.Partial()
        QualifiedStepCountUnavailableReason.STORAGE_UNAVAILABLE ->
            WidgetStepsPresentation.StorageUnavailable
        QualifiedStepCountUnavailableReason.MISSING,
        QualifiedStepCountUnavailableReason.SOURCE_EVIDENCE_UNAVAILABLE,
        QualifiedStepCountUnavailableReason.CALENDAR_AUTHORITY_UNAVAILABLE,
        -> WidgetStepsPresentation.Unavailable
    }
}

/** Maps one exact selected segment without inferring policy or qualification from a stored count. */
internal fun SessionHistoryQuery.toWidgetStepsPresentation(
    expectedSegmentId: Long,
): WidgetStepsPresentation {
    val history = (this as? SessionHistoryQuery.Found)?.history
        ?.takeIf { it.segmentId == expectedSegmentId }
        ?: return WidgetStepsPresentation.Unavailable
    val steps = history.steps

    val completeCount = steps.count
    return when {
        steps.availability == HistoryAvailability.DISABLED -> WidgetStepsPresentation.Disabled
        steps.availability == HistoryAvailability.UNSUPPORTED ||
            steps.availability == HistoryAvailability.PERMISSION_REQUIRED ||
            steps.availability == HistoryAvailability.OS_LIMITED ||
            steps.availability == HistoryAvailability.UNAVAILABLE ->
            WidgetStepsPresentation.Unavailable
        HistorySource.STEPS in history.qualifiedSources &&
            steps.hasCompleteValue && completeCount != null ->
            WidgetStepsPresentation.Ready(completeCount)
        steps.isLowerBound -> WidgetStepsPresentation.Partial(steps.count)
        steps.productState == HistoryProductState.MATERIALIZING ->
            WidgetStepsPresentation.Materializing
        steps.productState == HistoryProductState.PARTIAL ||
            StepsHistoryCause.CAPTURE_PARTIAL in steps.causes ||
            StepsHistoryCause.ACQUISITION_INCOMPLETE in steps.causes ||
            StepsHistoryCause.PROVIDER_GAP in steps.causes ->
            WidgetStepsPresentation.Partial()
        StepsHistoryCause.SOURCE_NOT_CAPTURED in steps.causes ->
            WidgetStepsPresentation.NotCaptured
        else -> WidgetStepsPresentation.Unavailable
    }
}

/** Compact localized value text; nonnumeric states stay visibly nonnumeric. */
internal fun WidgetStepsPresentation.displayValue(context: Context): String = when (this) {
    is WidgetStepsPresentation.Ready -> WidgetFormatters.formatSteps(count)
    is WidgetStepsPresentation.Partial -> lowerBound?.let { "≥${WidgetFormatters.formatSteps(it)}" }
        ?: context.getString(R.string.widget_steps_partial)
    WidgetStepsPresentation.Materializing -> context.getString(R.string.widget_loading)
    WidgetStepsPresentation.NotCaptured -> context.getString(R.string.widget_steps_not_captured)
    WidgetStepsPresentation.Disabled -> context.getString(R.string.widget_steps_disabled)
    WidgetStepsPresentation.Unavailable -> context.getString(R.string.widget_steps_unavailable)
    WidgetStepsPresentation.StorageUnavailable -> context.getString(
        R.string.widget_steps_storage_unavailable,
    )
}
