package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.ImportedActivityProductEvaluation
import com.adsamcik.tracker.shared.base.database.ImportedActivityProductFailure
import com.adsamcik.tracker.shared.base.database.PortableActivityCaptureCoverage
import com.adsamcik.tracker.shared.base.database.PortableActivityFragmentV1
import com.adsamcik.tracker.shared.base.database.PortableActivityWindowCoverage
import com.adsamcik.tracker.shared.base.database.PortableActivityWindowV1
import com.adsamcik.tracker.stats.api.repository.ActivityActiveTime
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryCause
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryConfidence
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryEntry
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryEntryKey
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryFragment
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryGapReason
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryMechanism
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryOrigin
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryProductState
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryType
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryWallTimeContinuity
import com.adsamcik.tracker.stats.api.value.EpochMs

internal fun ImportedActivityProductEvaluation.toPublicActivityEntry(
	originConflict: Boolean = false,
): ActivityHistoryEntry = when (this) {
	is ImportedActivityProductEvaluation.Retained -> if (originConflict) {
		failed(ActivityHistoryCause.ORIGIN_IDENTITY_CONFLICT)
	} else {
		unavailable(ActivityHistoryCause.RETENTION_LIMIT)
	}
	is ImportedActivityProductEvaluation.Unverifiable -> unavailableOrFailed(
		cause = if (originConflict) {
			ActivityHistoryCause.ORIGIN_IDENTITY_CONFLICT
		} else {
			reason.toPublicCause()
		},
	)
	is ImportedActivityProductEvaluation.Readable -> if (originConflict) {
		failed(ActivityHistoryCause.ORIGIN_IDENTITY_CONFLICT)
	} else {
		toReadablePublicEntry()
	}
}

private fun ImportedActivityProductEvaluation.Readable.toReadablePublicEntry(): ActivityHistoryEntry {
	if (entryDeleted) return unavailable(ActivityHistoryCause.DELETED)
	val visibleRuns = entry.runs.filterNot { it.identity.value in deletedRunIdentities }
	if (retentionLimited) {
		return unavailable(ActivityHistoryCause.RETENTION_LIMIT)
	}
	val capturedRuns = visibleRuns.filter {
		it.captureCoverage != PortableActivityCaptureCoverage.NOT_CAPTURED
	}
	val windows = capturedRuns.flatMap { it.windows }
	if (windows.isEmpty()) {
		return unavailable(
			if (deletedRunIdentities.isNotEmpty()) ActivityHistoryCause.DELETED else
				ActivityHistoryCause.NO_QUALIFIED_FACTS,
		)
	}
	val causes = linkedSetOf<ActivityHistoryCause>()
	if (deletedRunIdentities.isNotEmpty()) causes += ActivityHistoryCause.DELETED
	if (visibleRuns.any { it.captureCoverage == PortableActivityCaptureCoverage.NOT_CAPTURED }) {
		causes += ActivityHistoryCause.SOURCE_NOT_CAPTURED
	}
	if (visibleRuns.any { it.captureCoverage == PortableActivityCaptureCoverage.PARTIAL_RUN }) {
		causes += ActivityHistoryCause.ACQUISITION_INCOMPLETE
	}
	if (windows.any { it.coverage != PortableActivityWindowCoverage.COMPLETE }) {
		causes += ActivityHistoryCause.ACQUISITION_INCOMPLETE
	}
	if (windows.flatMap { it.fragments }.any { it is PortableActivityFragmentV1.Gap }) {
		causes += ActivityHistoryCause.PROVIDER_GAP
	}
	val activeTime = windows.fold(ActivityActiveTime(0L, 0L, 0L, 0L)) { total, window ->
		ActivityActiveTime(
			knownActiveDurationNanos = Math.addExact(
				total.knownActiveDurationNanos,
				window.knownActiveDurationNanos,
			),
			knownInactiveDurationNanos = Math.addExact(
				total.knownInactiveDurationNanos,
				window.knownInactiveDurationNanos,
			),
			unknownActivityDurationNanos = Math.addExact(
				total.unknownActivityDurationNanos,
				window.unknownActivityDurationNanos,
			),
			unobservedDurationNanos = Math.addExact(
				total.unobservedDurationNanos,
				window.unobservedDurationNanos,
			),
		)
	}
	val coverage = if (causes.isEmpty() && capturedRuns.size == visibleRuns.size &&
		visibleRuns.all { it.captureCoverage == PortableActivityCaptureCoverage.WHOLE_RUN } &&
		windows.all { it.coverage == PortableActivityWindowCoverage.COMPLETE }
	) ActivityHistoryCoverage.COMPLETE else ActivityHistoryCoverage.PARTIAL
	val state = if (coverage == ActivityHistoryCoverage.COMPLETE) {
		ActivityHistoryProductState.READY
	} else {
		ActivityHistoryProductState.PARTIAL
	}
	return ActivityHistoryEntry(
		key = importedKey(entry.identity.value),
		startTime = EpochMs(entry.startTimeMs),
		endTime = EpochMs(entry.endTimeMs),
		storedZoneIds = visibleRuns.flatMapTo(linkedSetOf()) { run ->
			run.zoneEpochs.map { it.zoneId }
		},
		state = state,
		coverage = coverage,
		activeTime = activeTime,
		fragments = windows.flatMap(PortableActivityWindowV1::toPublicFragments),
		causes = causes,
		origin = ActivityHistoryOrigin.IMPORTED,
	)
}

private fun PortableActivityWindowV1.toPublicFragments(): List<ActivityHistoryFragment> =
	fragments.map { fragment ->
		val duration = Math.subtractExact(fragment.endOffsetNanos, fragment.startOffsetNanos)
		when (fragment) {
			is PortableActivityFragmentV1.Gap -> ActivityHistoryFragment.Gap(
				storedZoneId = storedZoneId,
				reason = ActivityHistoryGapReason.valueOf(fragment.reason),
				durationNanos = duration,
			)
			is PortableActivityFragmentV1.Band -> ActivityHistoryFragment.Band(
				storedZoneId = storedZoneId,
				startTime = EpochMs(fragment.startWallTimeMs),
				endTime = EpochMs(fragment.endWallTimeMs),
				startUncertaintyMs = fragment.startWallTimeUncertaintyMs,
				endUncertaintyMs = fragment.endWallTimeUncertaintyMs,
				activity = ActivityHistoryType.valueOf(fragment.activity),
				mechanism = ActivityHistoryMechanism.valueOf(fragment.mechanism),
				refinedTransitionActivity = fragment.refinedTransitionActivity?.let(
					ActivityHistoryType::valueOf,
				),
				confidence = fragment.toPublicConfidence(),
				wallTimeContinuity = ActivityHistoryWallTimeContinuity.valueOf(
					fragment.wallTimeContinuity,
				),
				durationNanos = duration,
			)
		}
	}

private fun PortableActivityFragmentV1.Band.toPublicConfidence(): ActivityHistoryConfidence =
	when (confidenceKind) {
		"TRANSITION_SIGNAL" -> ActivityHistoryConfidence.TransitionSignal
		"SAMPLED" -> ActivityHistoryConfidence.Sampled(
			minimumPercent = requireNotNull(confidenceMinimumPercent),
			maximumPercent = requireNotNull(confidenceMaximumPercent),
			observationCount = requireNotNull(confidenceObservationCount),
		)
		else -> error("Unknown imported Activity confidence")
	}

private fun ImportedActivityProductEvaluation.unavailableOrFailed(
	cause: ActivityHistoryCause,
): ActivityHistoryEntry = if (cause.isIntegrityFailure) failed(cause) else unavailable(cause)

private fun ImportedActivityProductEvaluation.failed(cause: ActivityHistoryCause) =
	publicShell(ActivityHistoryProductState.FAILED, cause)

private fun ImportedActivityProductEvaluation.unavailable(cause: ActivityHistoryCause) =
	publicShell(ActivityHistoryProductState.UNAVAILABLE, cause)

private fun ImportedActivityProductEvaluation.publicShell(
	state: ActivityHistoryProductState,
	cause: ActivityHistoryCause,
) = ActivityHistoryEntry(
	key = importedKey(candidate.identity),
	startTime = EpochMs(candidate.startTimeMs),
	endTime = EpochMs(candidate.endTimeMs),
	storedZoneIds = emptySet(),
	state = state,
	coverage = ActivityHistoryCoverage.NONE,
	activeTime = null,
	fragments = emptyList(),
	causes = setOf(cause),
	origin = ActivityHistoryOrigin.IMPORTED,
)

private fun ImportedActivityProductFailure.toPublicCause(): ActivityHistoryCause = when (this) {
	ImportedActivityProductFailure.SOURCE_EVIDENCE_STATE_MISSING,
	ImportedActivityProductFailure.STORED_EVIDENCE_UNVERIFIABLE,
	-> ActivityHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE
	ImportedActivityProductFailure.STALE_COLLECTED_DATA_EPOCH ->
		ActivityHistoryCause.PRIVACY_EPOCH_MISMATCH
	ImportedActivityProductFailure.ORIGIN_IDENTITY_CONFLICT ->
		ActivityHistoryCause.ORIGIN_IDENTITY_CONFLICT
	ImportedActivityProductFailure.DEPENDENCY_OVERFLOW ->
		ActivityHistoryCause.READ_BUDGET_EXCEEDED
	ImportedActivityProductFailure.VALUE_OVERFLOW -> ActivityHistoryCause.VALUE_OVERFLOW
}

private fun importedKey(identity: String) = ActivityHistoryEntryKey("activity-imported:$identity")
