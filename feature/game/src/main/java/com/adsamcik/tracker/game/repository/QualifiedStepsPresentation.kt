package com.adsamcik.tracker.game.repository

import com.adsamcik.tracker.shared.base.di.GoalProgress
import com.adsamcik.tracker.shared.base.di.QualifiedStepCount
import com.adsamcik.tracker.shared.base.di.QualifiedStepCountUnavailableReason
import com.adsamcik.tracker.stats.api.repository.StepsNumericSummary
import com.adsamcik.tracker.stats.api.repository.StepsNumericUnverifiableReason

/** Maps durable source truth into the only numeric Steps shape product consumers may display. */
internal fun StepsNumericSummary.toQualifiedStepCount(): QualifiedStepCount = when (this) {
	is StepsNumericSummary.Ready -> QualifiedStepCount.Ready(
		value = totalSteps.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
	)
	StepsNumericSummary.Materializing -> QualifiedStepCount.Unavailable(
		QualifiedStepCountUnavailableReason.MATERIALIZING,
	)
	is StepsNumericSummary.Unverifiable -> QualifiedStepCount.Unavailable(
		when (reason) {
			StepsNumericUnverifiableReason.NOT_CAPTURED ->
				QualifiedStepCountUnavailableReason.NOT_CAPTURED
			StepsNumericUnverifiableReason.PARTIAL_CAPTURE ->
				QualifiedStepCountUnavailableReason.PARTIAL_CAPTURE
			StepsNumericUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE ->
				QualifiedStepCountUnavailableReason.SOURCE_EVIDENCE_UNAVAILABLE
			StepsNumericUnverifiableReason.CALENDAR_AUTHORITY_UNAVAILABLE ->
				QualifiedStepCountUnavailableReason.CALENDAR_AUTHORITY_UNAVAILABLE
			StepsNumericUnverifiableReason.STORAGE_UNAVAILABLE ->
				QualifiedStepCountUnavailableReason.STORAGE_UNAVAILABLE
		},
	)
}

/** A missing repository emission is explicit and cannot become a zero-step day. */
internal fun StepsSummaryData?.toGoalProgress(): GoalProgress = GoalProgress(
	stepsToday = this?.stepsToday ?: QualifiedStepCount.Unavailable(
		QualifiedStepCountUnavailableReason.MISSING,
	),
	goalSteps = this?.goalDay ?: 0,
	gamificationEnabled = true,
)
