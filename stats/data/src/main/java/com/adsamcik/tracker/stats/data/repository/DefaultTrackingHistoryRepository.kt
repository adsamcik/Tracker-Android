package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.di.IoDispatcher
import com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent
import com.adsamcik.tracker.stats.api.repository.HistoryCapture
import com.adsamcik.tracker.stats.api.repository.HistoryCaptureRevision
import com.adsamcik.tracker.stats.api.repository.HistoryAvailability
import com.adsamcik.tracker.stats.api.repository.HistoryEvidence
import com.adsamcik.tracker.stats.api.repository.HistoryProductState
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.SessionHistory
import com.adsamcik.tracker.stats.api.repository.SessionHistoryQuery
import com.adsamcik.tracker.stats.api.repository.StepsHistory
import com.adsamcik.tracker.stats.api.repository.StepsHistoryCause
import com.adsamcik.tracker.stats.api.repository.StepsAwareHistoryPageEntry
import com.adsamcik.tracker.stats.api.repository.StepsOnlyHistoryEntry
import com.adsamcik.tracker.stats.api.repository.StepsOnlyHistoryListState
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryEntryKey
import com.adsamcik.tracker.stats.api.repository.StepsHistoryCoverage as ApiStepsHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryRepository
import com.adsamcik.tracker.stats.api.value.EpochMs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import javax.inject.Inject

/** Room-backed, read-only history facade. */
internal class DefaultTrackingHistoryRepository @Inject constructor(
	private val database: AppDatabase,
	private val stepsSelector: StepsSegmentHistorySelector,
	private val logicalHistoryReader: LogicalTrackingHistoryReader,
	@IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : TrackingHistoryRepository {
	@OptIn(ExperimentalCoroutinesApi::class)
	override fun observeSession(segmentId: Long): Flow<SessionHistoryQuery> =
		historyInvalidations().mapLatest {
			stepsSelector.selectEvidenceBySegmentIds(listOf(segmentId))[segmentId]?.let { selected ->
				SessionHistoryQuery.Found(selected.toPublicSessionHistory())
			} ?: SessionHistoryQuery.NotFound
		}.distinctUntilChanged()
			.flowOn(ioDispatcher)

	@OptIn(ExperimentalCoroutinesApi::class)
	override fun observeRecentStepsOnlyEntries(
		limit: Int,
	): Flow<List<StepsOnlyHistoryEntry>> {
		require(limit in 1..MAX_RECENT_ENTRY_COUNT) {
			"Recent Steps-only history limit must be between 1 and $MAX_RECENT_ENTRY_COUNT"
		}
		return historyInvalidations().mapLatest {
			logicalHistoryReader.selectRecentStepsOnlyEntries(limit).mapToPublicStepsOnlyEntries()
		}.distinctUntilChanged()
			.flowOn(ioDispatcher)
	}

	@OptIn(ExperimentalCoroutinesApi::class)
	override fun observeRecentStepsAwarePage(
		candidateSegmentIds: List<Long>,
		limit: Int,
	): Flow<List<StepsAwareHistoryPageEntry>> {
		validateStepsAwarePageRequest(candidateSegmentIds, limit)
		val stableCandidateSegmentIds = candidateSegmentIds.toList()
		return historyInvalidations().mapLatest {
			logicalHistoryReader.selectRecentStepsAwarePage(
				candidateSegmentIds = stableCandidateSegmentIds,
				limit = limit,
			).map(HistoricalStepsAwarePageEntry::toPublicPageEntry)
		}.distinctUntilChanged()
			.flowOn(ioDispatcher)
	}

	private fun validateStepsAwarePageRequest(
		candidateSegmentIds: List<Long>,
		limit: Int,
	) {
		require(candidateSegmentIds.size <= MAX_RECENT_ENTRY_COUNT) {
			"Physical history candidate count cannot exceed $MAX_RECENT_ENTRY_COUNT"
		}
		require(candidateSegmentIds.all { it > 0L }) {
			"Physical history candidate ids must be positive"
		}
		require(candidateSegmentIds.distinct().size == candidateSegmentIds.size) {
			"Physical history candidate ids must be distinct"
		}
		require(limit in 1..MAX_RECENT_ENTRY_COUNT) {
			"Recent Steps-aware history limit must be between 1 and $MAX_RECENT_ENTRY_COUNT"
		}
	}

	private fun historyInvalidations() = database.invalidationTracker.createFlow(
		SESSION_SEGMENT_TABLE,
		SERVICE_RUN_TABLE,
		MANIFEST_TABLE,
		MANIFEST_SOURCE_TABLE,
		SOURCE_POLICY_TABLE,
		SOURCE_CONSENT_EPOCH_TABLE,
		PRODUCT_LANE_TABLE,
		PROJECTION_FAILURE_TABLE,
		SOURCE_EVIDENCE_TABLE,
		SOURCE_DELETION_FENCE_TABLE,
		STEP_FACT_TABLE,
		SESSION_COMPLETENESS_TABLE,
		emitInitialState = true,
	)

	private companion object {
		const val MAX_RECENT_ENTRY_COUNT = 100
		const val SESSION_SEGMENT_TABLE = "session_segment"
		const val SERVICE_RUN_TABLE = "source_service_run"
		const val MANIFEST_TABLE = "session_manifest_version"
		const val MANIFEST_SOURCE_TABLE = "session_manifest_source"
		const val SOURCE_POLICY_TABLE = "source_policy"
		const val SOURCE_CONSENT_EPOCH_TABLE = "source_consent_epoch"
		const val PRODUCT_LANE_TABLE = "source_product_projection_lane"
		const val PROJECTION_FAILURE_TABLE = "source_projection_failure"
		const val SOURCE_EVIDENCE_TABLE = "source_evidence_state"
		const val SOURCE_DELETION_FENCE_TABLE = "source_deletion_fence"
		const val STEP_FACT_TABLE = "step_fact_revision"
		const val SESSION_COMPLETENESS_TABLE = "source_session_completeness"
	}
}

private fun HistoricalSegmentEvidence.toPublicSessionHistory() = SessionHistory(
	segmentId = segment.id,
	capture = captureAuthority.toPublicCapture(),
	qualifiedSources = qualifiedSources.mapTo(linkedSetOf(), TrackingSourceComponent::toPublicSource),
	steps = steps.toPublicHistory(),
)

private fun HistoricalCaptureAuthority.toPublicCapture(): HistoryCapture = when (this) {
	is HistoricalCaptureAuthority.Exact -> HistoryCapture.Exact(
		revisions.map { revision ->
			HistoryCaptureRevision(
				revision = revision.manifestRevision,
				effectiveAt = EpochMs(revision.effectiveWallTimeMs),
				capturedSources = revision.capturedSources.mapTo(
					linkedSetOf(),
					TrackingSourceComponent::toPublicSource,
				),
				controlSources = revision.controlSources.mapTo(
					linkedSetOf(),
					TrackingSourceComponent::toPublicSource,
				),
			)
		},
	)
	is HistoricalCaptureAuthority.Unverifiable -> HistoryCapture.Unverifiable
}

private fun TrackingSourceComponent.toPublicSource(): HistorySource = when (this) {
	TrackingSourceComponent.LOCATION -> HistorySource.LOCATION
	TrackingSourceComponent.WIFI -> HistorySource.WIFI
	TrackingSourceComponent.CELL -> HistorySource.CELL
	TrackingSourceComponent.ACTIVITY -> HistorySource.ACTIVITY
	TrackingSourceComponent.STEPS -> HistorySource.STEPS
	TrackingSourceComponent.PRESSURE -> HistorySource.PRESSURE
}

private fun List<HistoricalTrackingEntryEvidence>.mapToPublicStepsOnlyEntries() =
	map(HistoricalTrackingEntryEvidence::toPublicStepsOnlyEntry)

private fun HistoricalTrackingEntryEvidence.toPublicStepsOnlyEntry(): StepsOnlyHistoryEntry {
	check(isExactStepsOnlyCapture) { "Steps-only reader returned a non-Steps-only entry" }
	val logicalIdentity = identity as? HistoricalEntryIdentity.Logical
		?: error("Exact Steps-only entry requires logical identity")
	return StepsOnlyHistoryEntry(
		key = TrackingHistoryEntryKey("logical:${logicalIdentity.logicalTrackingId}"),
		startTime = EpochMs(physicalMembers.minOf { it.segment.startTimeMs }),
		endTime = EpochMs(physicalMembers.maxOf { it.segment.endTimeMs }),
		state = physicalMembers.map { it.steps.toPublicHistory() }.toStepsOnlyListState(),
	)
}

private fun HistoricalStepsAwarePageEntry.toPublicPageEntry(): StepsAwareHistoryPageEntry =
	when (this) {
		is HistoricalStepsAwarePageEntry.Physical ->
			StepsAwareHistoryPageEntry.Physical(segment.id)
		is HistoricalStepsAwarePageEntry.StepsOnly ->
			StepsAwareHistoryPageEntry.StepsOnly(history.toPublicStepsOnlyEntry())
	}

internal fun List<StepsHistory>.toStepsOnlyListState(): StepsOnlyHistoryListState {
	require(isNotEmpty()) { "Steps-only logical entry requires a physical member" }
	return when {
		any { it.productState == HistoryProductState.MATERIALIZING } ->
			StepsOnlyHistoryListState.MATERIALIZING
		all(StepsHistory::hasCompleteValue) -> StepsOnlyHistoryListState.AVAILABLE
		else -> StepsOnlyHistoryListState.PARTIAL
	}
}

internal fun StepsSegmentHistoryResult.toPublicHistory(): StepsHistory {
	val publicAvailability = toPublicAvailability()
	val publicEvidence = toPublicEvidence()
	val publicProductState = toPublicProductState()
	val publicCauses = reasons.mapTo(linkedSetOf(), StepsHistoryReason::toPublicCause)
	if (publicAvailability == HistoryAvailability.UNAVAILABLE) {
		publicCauses += StepsHistoryCause.AVAILABILITY_UNAVAILABLE
	}
	when (evidence) {
		StepsHistoryEvidence.BASELINE -> publicCauses += StepsHistoryCause.BASELINE_ONLY
		StepsHistoryEvidence.NO_OBSERVATION -> if (
			publicProductState != HistoryProductState.READY && publicCauses.isEmpty()
		) {
			publicCauses += StepsHistoryCause.NO_OBSERVATION
		}
		else -> Unit
	}
	return StepsHistory(
		count = count,
		availability = publicAvailability,
		evidence = publicEvidence,
		productState = publicProductState,
		coverage = coverage.toPublicCoverage(),
		causes = publicCauses,
	)
}

private fun StepsSegmentHistoryResult.toPublicAvailability(): HistoryAvailability =
	when (availability) {
		StepsHistoryAvailability.DISABLED -> HistoryAvailability.DISABLED
		StepsHistoryAvailability.AVAILABLE,
		StepsHistoryAvailability.DELETED -> HistoryAvailability.AVAILABLE
		StepsHistoryAvailability.UNAVAILABLE -> HistoryAvailability.UNAVAILABLE
	}

private fun StepsSegmentHistoryResult.toPublicEvidence(): HistoryEvidence = when (evidence) {
	StepsHistoryEvidence.NO_OBSERVATION -> if (StepsHistoryReason.SERVICE_RUN_ACTIVE in reasons) {
		HistoryEvidence.STARTING
	} else {
		HistoryEvidence.NONE
	}
	StepsHistoryEvidence.BASELINE -> HistoryEvidence.NONE
	StepsHistoryEvidence.COVERED_ZERO -> HistoryEvidence.ACTIVE
	StepsHistoryEvidence.RECORDED,
	StepsHistoryEvidence.LEGACY_RECORDED -> HistoryEvidence.RECORDED
}

private fun StepsSegmentHistoryResult.toPublicProductState(): HistoryProductState =
	when (materialization) {
		StepsHistoryMaterialization.NOT_APPLICABLE -> HistoryProductState.READY
		StepsHistoryMaterialization.MATERIALIZING -> HistoryProductState.MATERIALIZING
		StepsHistoryMaterialization.DEGRADED -> HistoryProductState.DEGRADED
		StepsHistoryMaterialization.FAILED -> if (
			StepsHistoryReason.OUTSIDE_RETAINED_FLOOR in reasons
		) {
			HistoryProductState.PARTIAL
		} else {
			HistoryProductState.FAILED
		}
		StepsHistoryMaterialization.READY -> if (
			coverage == StepsHistoryCoverage.COMPLETE &&
			availability != StepsHistoryAvailability.DELETED
		) {
			HistoryProductState.READY
		} else {
			HistoryProductState.PARTIAL
		}
	}

private fun StepsHistoryCoverage.toPublicCoverage(): ApiStepsHistoryCoverage =
	when (this) {
		StepsHistoryCoverage.NONE -> ApiStepsHistoryCoverage.NONE
		StepsHistoryCoverage.COMPLETE -> ApiStepsHistoryCoverage.COMPLETE
		StepsHistoryCoverage.PARTIAL -> ApiStepsHistoryCoverage.PARTIAL
		StepsHistoryCoverage.UNKNOWN -> ApiStepsHistoryCoverage.UNKNOWN
	}

// Exhaustiveness is intentional: adding an internal selector failure cannot silently erase the
// stable product-facing explanation required by the history contract.
@Suppress("CyclomaticComplexMethod")
internal fun StepsHistoryReason.toPublicCause(): StepsHistoryCause = when (this) {
	StepsHistoryReason.SOURCE_NOT_CAPTURED -> StepsHistoryCause.SOURCE_NOT_CAPTURED
	StepsHistoryReason.CAPTURE_NOT_ENABLED_FOR_WHOLE_RUN -> StepsHistoryCause.CAPTURE_PARTIAL
	StepsHistoryReason.SERVICE_RUN_ACTIVE -> StepsHistoryCause.SESSION_STILL_ACTIVE
	StepsHistoryReason.SEGMENT_MEMBERSHIP_INCOMPLETE,
	StepsHistoryReason.SERVICE_RUN_MISSING,
	StepsHistoryReason.SERVICE_RUN_MEMBERSHIP_MISMATCH,
	StepsHistoryReason.SERVICE_RUN_SEGMENT_BINDING_MISMATCH,
	StepsHistoryReason.MANIFEST_MISSING,
	StepsHistoryReason.MANIFEST_MEMBERSHIP_MISMATCH -> StepsHistoryCause.HISTORY_MEMBERSHIP_UNAVAILABLE
	StepsHistoryReason.MANIFEST_INTEGRITY_FAILED,
	StepsHistoryReason.SOURCE_POLICY_ATTRIBUTION_INVALID,
	StepsHistoryReason.STEP_FACT_INTEGRITY_FAILED,
	StepsHistoryReason.COMPLETENESS_INVALID -> StepsHistoryCause.HISTORY_INTEGRITY_FAILED
	StepsHistoryReason.MIXED_WRITER_WITHIN_SERVICE_RUN,
	StepsHistoryReason.UNKNOWN_WRITER,
	StepsHistoryReason.CANDIDATE_PROVENANCE_INCOMPLETE,
	StepsHistoryReason.PRODUCT_LANE_MISSING,
	StepsHistoryReason.PRODUCT_LANE_INVALID,
	StepsHistoryReason.TARGET_BEFORE_LANE_ACTIVATION -> StepsHistoryCause.WRITER_PROVENANCE_INVALID
	StepsHistoryReason.LEGACY_UNATTRIBUTED,
	StepsHistoryReason.LEGACY_REPLAY_UNVERIFIED,
	StepsHistoryReason.LEGACY_ZERO_UNVERIFIED,
	StepsHistoryReason.SERVICE_RUN_SEGMENT_BINDING_UNVERIFIABLE ->
		StepsHistoryCause.LEGACY_UNVERIFIED
	StepsHistoryReason.PRODUCT_LANE_BEHIND -> StepsHistoryCause.MATERIALIZATION_BEHIND
	StepsHistoryReason.PRODUCT_LANE_CUTOFF_BEFORE_TARGET,
	StepsHistoryReason.PRODUCT_LANE_RETIRED_BEFORE_TARGET,
	StepsHistoryReason.TERMINAL_PROJECTION_FAILURE,
	StepsHistoryReason.BATCH_DEPENDENCY_OVERFLOW -> StepsHistoryCause.MATERIALIZATION_UNAVAILABLE
	StepsHistoryReason.COMPLETENESS_MISSING,
	StepsHistoryReason.APP_DRAIN_INCOMPLETE,
	StepsHistoryReason.STOP_INCOMPLETE -> StepsHistoryCause.ACQUISITION_INCOMPLETE
	StepsHistoryReason.UNRESOLVED_PROVIDER_SEQUENCE,
	StepsHistoryReason.PROVIDER_COMPLETENESS_UNOBSERVABLE,
	StepsHistoryReason.RESET_GAP,
	StepsHistoryReason.PARTIAL_FACT -> StepsHistoryCause.PROVIDER_GAP
	StepsHistoryReason.FACTS_MISSING_FOR_ADMITTED_RUN -> StepsHistoryCause.FACTS_MISSING
	StepsHistoryReason.DELETED_FACTS -> StepsHistoryCause.DELETED
	StepsHistoryReason.OUTSIDE_RETAINED_FLOOR,
	StepsHistoryReason.RETENTION_CROSSES_SEGMENT -> StepsHistoryCause.RETENTION_LIMIT
	StepsHistoryReason.SOURCE_EVIDENCE_STATE_MISSING -> StepsHistoryCause.EVIDENCE_STATE_UNAVAILABLE
	StepsHistoryReason.STALE_COLLECTED_DATA_EPOCH -> StepsHistoryCause.PRIVACY_EPOCH_MISMATCH
	StepsHistoryReason.COUNT_OVERFLOW -> StepsHistoryCause.VALUE_OVERFLOW
}
