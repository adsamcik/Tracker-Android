package com.adsamcik.tracker.stats.data.repository

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionEntity
import javax.inject.Inject

/**
 * Selects one session segment's Steps history from its immutable service-run writer provenance.
 *
 * This is intentionally Steps-specific. The permanent destination owner is write authority for
 * the present; it must never reinterpret an older segment after cutover or rollback. Acquisition
 * completeness and materializer progress also remain independent axes so a number cannot silently
 * turn missing, baseline-only, partial, or still-processing evidence into a verified zero.
 */
internal class StepsSegmentHistorySelector @Inject constructor(
	private val database: AppDatabase,
) {
	suspend fun select(segment: SessionSegment): StepsSegmentHistoryResult = database.withTransaction {
		val logicalTrackingId = segment.logicalTrackingId
		val serviceRunId = segment.serviceRunId
		if (logicalTrackingId == null && serviceRunId == null) {
			return@withTransaction unattributedLegacy(segment.steps)
		}
		if (logicalTrackingId.isNullOrBlank() || serviceRunId.isNullOrBlank()) {
			return@withTransaction unavailable(StepsHistoryReason.SEGMENT_MEMBERSHIP_INCOMPLETE)
		}

		val sessionDao = database.sourceSessionDao()
		val serviceRun = sessionDao.serviceRun(serviceRunId)
		if (serviceRun == null) {
			return@withTransaction unavailable(StepsHistoryReason.SERVICE_RUN_MISSING)
		}
		if (serviceRun.logicalTrackingId != logicalTrackingId) {
			return@withTransaction unavailable(StepsHistoryReason.SERVICE_RUN_MEMBERSHIP_MISMATCH)
		}

		val manifests = sessionDao.manifestsForServiceRun(serviceRunId)
		if (manifests.isEmpty()) {
			return@withTransaction unavailable(StepsHistoryReason.MANIFEST_MISSING)
		}
		val sourcesByRevision = linkedMapOf<Long, List<SessionManifestSourceEntity>>()
		for (manifest in manifests) {
			if (manifest.logicalTrackingId != logicalTrackingId || manifest.serviceRunId != serviceRunId) {
				return@withTransaction unavailable(StepsHistoryReason.MANIFEST_MEMBERSHIP_MISMATCH)
			}
			val sources = sessionDao.manifestSources(logicalTrackingId, manifest.manifestRevision)
			if (!SessionManifestIntegrity.verify(manifest, sources)) {
				return@withTransaction unavailable(StepsHistoryReason.MANIFEST_INTEGRITY_FAILED)
			}
			sourcesByRevision[manifest.manifestRevision] = sources
		}

		val stepsBindings = sourcesByRevision.mapValues { (_, sources) ->
			sources.singleOrNull { source ->
				source.sourceKind == SourceDestinationOwnerEntity.SOURCE_STEPS &&
					source.purpose == SourceBrokerPurpose.SESSION_CAPTURE &&
					source.persistenceEligible
			}
		}.filterValues { it != null }.mapValues { (_, source) -> requireNotNull(source) }
		if (stepsBindings.isEmpty()) {
			return@withTransaction StepsSegmentHistoryResult(
				count = null,
				availability = StepsHistoryAvailability.DISABLED,
				evidence = StepsHistoryEvidence.NO_OBSERVATION,
				materialization = StepsHistoryMaterialization.NOT_APPLICABLE,
				coverage = StepsHistoryCoverage.NONE,
				reasons = setOf(StepsHistoryReason.SOURCE_NOT_CAPTURED),
			)
		}

		val writers = stepsBindings.values.map(::writerBinding).distinct()
		if (writers.size != 1) {
			return@withTransaction failed(StepsHistoryReason.MIXED_WRITER_WITHIN_SERVICE_RUN)
		}
		val writer = writers.single()
		val captureCoveredWholeRun = stepsBindings.size == manifests.size
		return@withTransaction when (writer.owner) {
			SourceDestinationOwnerEntity.OWNER_LEGACY_STEP_INTERVAL -> legacy(
				steps = segment.steps,
				captureCoveredWholeRun = captureCoveredWholeRun,
			)
			SourceDestinationOwnerEntity.OWNER_STEPS_SESSION_FACTS -> candidate(
				segment = segment,
				logicalTrackingId = logicalTrackingId,
				serviceRunId = serviceRunId,
				serviceRunCompleted = serviceRun.completedAtMs != null &&
					serviceRun.state in TERMINAL_SERVICE_RUN_STATES,
				writer = writer,
				manifestRevisions = stepsBindings.keys.toList(),
				captureCoveredWholeRun = captureCoveredWholeRun,
			)
			else -> unavailable(StepsHistoryReason.UNKNOWN_WRITER)
		}
	}

	private suspend fun candidate(
		segment: SessionSegment,
		logicalTrackingId: String,
		serviceRunId: String,
		serviceRunCompleted: Boolean,
		writer: HistoricalStepsWriterBinding,
		manifestRevisions: List<Long>,
		captureCoveredWholeRun: Boolean,
	): StepsSegmentHistoryResult {
		val projectionId = writer.projectionId
		val projectionVersion = writer.projectionVersion
		val bindingGeneration = writer.bindingGeneration
		if (projectionId == null || projectionVersion == null || bindingGeneration == null) {
			return unavailable(StepsHistoryReason.CANDIDATE_PROVENANCE_INCOMPLETE)
		}
		val lane = database.sourceProjectionStateDao().productLane(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
			bindingGeneration = bindingGeneration,
			projectionId = projectionId,
			projectionVersion = projectionVersion,
		) ?: return failed(StepsHistoryReason.PRODUCT_LANE_MISSING)
		if (!isValidHistoricalLane(lane)) return failed(StepsHistoryReason.PRODUCT_LANE_INVALID)

		val evidenceState = database.sourceEvidenceStateDao().get()
			?: return failed(StepsHistoryReason.SOURCE_EVIDENCE_STATE_MISSING)
		if (evidenceState.retainedFromMs?.let { segment.endTimeMs < it } == true) {
			return unavailable(StepsHistoryReason.OUTSIDE_RETAINED_FLOOR)
		}
		val retentionCrossesSegment = evidenceState.retainedFromMs?.let { retainedFromMs ->
			segment.startTimeMs < retainedFromMs && segment.endTimeMs >= retainedFromMs
		} == true
		val factStates = database.stepFactRevisionDao().latestStatesForServiceRun(
			writerProjectionId = projectionId,
			writerProjectionVersion = projectionVersion,
			writerBindingGeneration = bindingGeneration,
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
			manifestRevisions = manifestRevisions,
		)
		val deletedFacts = factStates.filter {
			it.operation == StepFactRevisionEntity.OPERATION_RETRACT
		}
		val facts = factStates.filter {
			it.operation == StepFactRevisionEntity.OPERATION_UPSERT
		}.filter { fact ->
			evidenceState.retainedFromMs?.let { retainedFromMs ->
				requireNotNull(fact.intervalEndTimeMs) >= retainedFromMs
			} != false
		}
		if (factStates.any { it.collectedDataEpoch != evidenceState.collectedDataEpoch }) {
			return failed(StepsHistoryReason.STALE_COLLECTED_DATA_EPOCH)
		}

		val completeness = database.sourceSessionDao()
			.completenessForServiceRun(logicalTrackingId, serviceRunId)
			.filter { it.sourceKind == SourceDestinationOwnerEntity.SOURCE_STEPS }
		val targetOrdinal = completeness.mapNotNull { it.lastAdmissionOrdinal }.maxOrNull()
		val reasons = linkedSetOf<StepsHistoryReason>()
		if (!captureCoveredWholeRun) reasons += StepsHistoryReason.CAPTURE_NOT_ENABLED_FOR_WHOLE_RUN
		if (!serviceRunCompleted) reasons += StepsHistoryReason.SERVICE_RUN_ACTIVE
		if (retentionCrossesSegment) reasons += StepsHistoryReason.RETENTION_CROSSES_SEGMENT
		if (deletedFacts.isNotEmpty()) reasons += StepsHistoryReason.DELETED_FACTS
		if (completeness.isEmpty()) reasons += StepsHistoryReason.COMPLETENESS_MISSING
		if (completeness.any { !it.appDrainComplete }) reasons += StepsHistoryReason.APP_DRAIN_INCOMPLETE
		if (completeness.any { it.stopStatus != COMPLETE_STOP_STATUS }) {
			reasons += StepsHistoryReason.STOP_INCOMPLETE
		}
		if (completeness.any {
				(it.unresolvedSequenceStart == null) != (it.unresolvedSequenceEnd == null) ||
					it.unresolvedSequenceStart != null
			}
		) reasons += StepsHistoryReason.UNRESOLVED_PROVIDER_SEQUENCE
		if (completeness.any { it.providerCoverage != COMPLETE_PROVIDER_COVERAGE }) {
			reasons += StepsHistoryReason.PROVIDER_COMPLETENESS_UNOBSERVABLE
		}
		if (facts.any { it.coverageKind == StepFactRevisionEntity.COVERAGE_RESET_GAP }) {
			reasons += StepsHistoryReason.RESET_GAP
		}
		if (facts.any { it.coverageKind == StepFactRevisionEntity.COVERAGE_PARTIAL }) {
			reasons += StepsHistoryReason.PARTIAL_FACT
		}
		if (targetOrdinal != null && factStates.isEmpty() && !retentionCrossesSegment) {
			reasons += StepsHistoryReason.FACTS_MISSING_FOR_ADMITTED_RUN
		}
		val laneCutoffOrdinal = lane.captureAdmissionCutoffOrdinal

		val materialization = when {
			targetOrdinal != null && targetOrdinal < lane.activationOrdinal -> {
				reasons += StepsHistoryReason.TARGET_BEFORE_LANE_ACTIVATION
				StepsHistoryMaterialization.FAILED
			}
			targetOrdinal != null && database.sourceProjectionStateDao().firstTerminalFailureAfterThrough(
				projectionId = projectionId,
				projectionVersion = projectionVersion,
				afterOrdinal = lane.activationOrdinal - 1L,
				throughOrdinal = targetOrdinal,
			) != null -> {
				reasons += StepsHistoryReason.TERMINAL_PROJECTION_FAILURE
				StepsHistoryMaterialization.FAILED
			}
			targetOrdinal != null && laneCutoffOrdinal != null &&
				targetOrdinal > laneCutoffOrdinal -> {
				reasons += StepsHistoryReason.PRODUCT_LANE_CUTOFF_BEFORE_TARGET
				StepsHistoryMaterialization.FAILED
			}
			targetOrdinal != null &&
				lane.status == SourceProductProjectionLaneEntity.STATUS_RETIRED &&
				lane.contiguousAdmissionOrdinal < targetOrdinal -> {
				reasons += StepsHistoryReason.PRODUCT_LANE_RETIRED_BEFORE_TARGET
				StepsHistoryMaterialization.FAILED
			}
			targetOrdinal != null && lane.contiguousAdmissionOrdinal < targetOrdinal -> {
				reasons += StepsHistoryReason.PRODUCT_LANE_BEHIND
				StepsHistoryMaterialization.MATERIALIZING
			}
			!serviceRunCompleted -> StepsHistoryMaterialization.MATERIALIZING
			completeness.isEmpty() -> StepsHistoryMaterialization.FAILED
			StepsHistoryReason.FACTS_MISSING_FOR_ADMITTED_RUN in reasons ->
				StepsHistoryMaterialization.FAILED
			else -> StepsHistoryMaterialization.READY
		}

		val coveredFacts = facts.filter {
			it.coverageKind == StepFactRevisionEntity.COVERAGE_COVERED
		}
		val count = try {
			coveredFacts.fold(0L) { total, fact ->
				Math.addExact(total, requireNotNull(fact.effectiveStepCount))
			}.takeIf { coveredFacts.isNotEmpty() }
		} catch (_: ArithmeticException) {
			return failed(StepsHistoryReason.COUNT_OVERFLOW)
		}
		val evidence = when {
			count != null && count > 0L -> StepsHistoryEvidence.RECORDED
			count != null -> StepsHistoryEvidence.COVERED_ZERO
			facts.any { it.coverageKind == StepFactRevisionEntity.COVERAGE_BASELINE } ->
				StepsHistoryEvidence.BASELINE
			else -> StepsHistoryEvidence.NO_OBSERVATION
		}
		val acquisitionComplete = completeness.isNotEmpty() && completeness.all {
			it.appDrainComplete && it.stopStatus == COMPLETE_STOP_STATUS &&
				it.unresolvedSequenceStart == null && it.unresolvedSequenceEnd == null &&
				it.providerCoverage == COMPLETE_PROVIDER_COVERAGE
		}
		val coverage = when {
			coveredFacts.isEmpty() -> StepsHistoryCoverage.NONE
			acquisitionComplete && captureCoveredWholeRun &&
				StepsHistoryReason.DELETED_FACTS !in reasons &&
				StepsHistoryReason.RESET_GAP !in reasons &&
				StepsHistoryReason.PARTIAL_FACT !in reasons &&
				StepsHistoryReason.RETENTION_CROSSES_SEGMENT !in reasons &&
				StepsHistoryReason.SERVICE_RUN_ACTIVE !in reasons ->
				StepsHistoryCoverage.COMPLETE
			else -> StepsHistoryCoverage.PARTIAL
		}
		return StepsSegmentHistoryResult(
			count = count,
			availability = if (deletedFacts.isNotEmpty() && facts.isEmpty()) {
				StepsHistoryAvailability.DELETED
			} else {
				StepsHistoryAvailability.AVAILABLE
			},
			evidence = evidence,
			materialization = materialization,
			coverage = coverage,
			reasons = reasons,
		)
	}

	private fun writerBinding(source: SessionManifestSourceEntity) = HistoricalStepsWriterBinding(
		owner = requireNotNull(source.writerOwner),
		ownerGeneration = requireNotNull(source.writerOwnerGeneration),
		projectionId = source.writerProjectionId,
		projectionVersion = source.writerProjectionVersion,
		bindingGeneration = source.writerBindingGeneration,
	)

	private fun isValidHistoricalLane(lane: SourceProductProjectionLaneEntity): Boolean {
		if (lane.captureModeMask <= 0L ||
			lane.productStage !in setOf(
				SourceProductProjectionLaneEntity.STAGE_EVENT_SHADOW,
				SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL,
			) ||
			lane.activatedRolloutRevision <= 0L || lane.activationOrdinal <= 0L ||
			lane.installedAtMs < 0L || lane.updatedAtMs < lane.installedAtMs
		) return false

		val minimumCursor = lane.activationOrdinal - 1L
		val cutoff = lane.captureAdmissionCutoffOrdinal
		if (lane.contiguousAdmissionOrdinal < minimumCursor ||
			(cutoff != null && (cutoff < minimumCursor || lane.contiguousAdmissionOrdinal > cutoff))
		) return false
		val terminalDisposition = lane.terminalDisposition
		val terminalAtMs = lane.terminalAtMs
		if ((terminalDisposition == null) != (terminalAtMs == null)) return false

		return when (lane.status) {
			SourceProductProjectionLaneEntity.STATUS_ACTIVE ->
				lane.retentionRequired && terminalDisposition == null
			SourceProductProjectionLaneEntity.STATUS_RETIRED -> {
				if (lane.retentionRequired) return false
				if (terminalDisposition == null) return cutoff == null
				val terminalAt = terminalAtMs ?: return false
				terminalDisposition ==
					SourceProductProjectionLaneEntity.DISPOSITION_CONTAINED_AFTER_DRAIN &&
					cutoff != null && lane.contiguousAdmissionOrdinal == cutoff &&
					terminalAt >= lane.installedAtMs && lane.updatedAtMs >= terminalAt
			}
			else -> false
		}
	}

	private fun legacy(
		steps: Int?,
		captureCoveredWholeRun: Boolean,
	): StepsSegmentHistoryResult {
		val positive = steps?.takeIf { it > 0 }?.toLong()
		val reasons = linkedSetOf(StepsHistoryReason.LEGACY_REPLAY_UNVERIFIED)
		if (positive == null) reasons += StepsHistoryReason.LEGACY_ZERO_UNVERIFIED
		if (!captureCoveredWholeRun) reasons += StepsHistoryReason.CAPTURE_NOT_ENABLED_FOR_WHOLE_RUN
		return StepsSegmentHistoryResult(
			count = positive,
			availability = StepsHistoryAvailability.AVAILABLE,
			evidence = if (positive == null) {
				StepsHistoryEvidence.NO_OBSERVATION
			} else {
				StepsHistoryEvidence.LEGACY_RECORDED
			},
			materialization = StepsHistoryMaterialization.DEGRADED,
			coverage = StepsHistoryCoverage.UNKNOWN,
			reasons = reasons,
		)
	}

	private fun unattributedLegacy(steps: Int?): StepsSegmentHistoryResult =
		legacy(steps = steps, captureCoveredWholeRun = true).let { legacy ->
			legacy.copy(
				availability = StepsHistoryAvailability.UNAVAILABLE,
				reasons = legacy.reasons + StepsHistoryReason.LEGACY_UNATTRIBUTED,
			)
		}

	private fun unavailable(reason: StepsHistoryReason) = StepsSegmentHistoryResult(
		count = null,
		availability = StepsHistoryAvailability.UNAVAILABLE,
		evidence = StepsHistoryEvidence.NO_OBSERVATION,
		materialization = StepsHistoryMaterialization.FAILED,
		coverage = StepsHistoryCoverage.UNKNOWN,
		reasons = setOf(reason),
	)

	private fun failed(reason: StepsHistoryReason) = StepsSegmentHistoryResult(
		count = null,
		availability = StepsHistoryAvailability.AVAILABLE,
		evidence = StepsHistoryEvidence.NO_OBSERVATION,
		materialization = StepsHistoryMaterialization.FAILED,
		coverage = StepsHistoryCoverage.UNKNOWN,
		reasons = setOf(reason),
	)

	private data class HistoricalStepsWriterBinding(
		val owner: String,
		val ownerGeneration: Long,
		val projectionId: String?,
		val projectionVersion: Int?,
		val bindingGeneration: Long?,
	)

	private companion object {
		const val COMPLETE_STOP_STATUS = "COMPLETE"
		const val COMPLETE_PROVIDER_COVERAGE = "CALLBACKS_ENTERED_BEFORE_BARRIER"
		val TERMINAL_SERVICE_RUN_STATES = setOf("FINALIZED", "FAILED", "CLOSED")
	}
}

internal data class StepsSegmentHistoryResult(
	val count: Long?,
	val availability: StepsHistoryAvailability,
	val evidence: StepsHistoryEvidence,
	val materialization: StepsHistoryMaterialization,
	val coverage: StepsHistoryCoverage,
	val reasons: Set<StepsHistoryReason>,
) {
	init {
		require(count == null || count >= 0L)
		if (availability == StepsHistoryAvailability.DELETED) require(count == null)
		when (evidence) {
			StepsHistoryEvidence.COVERED_ZERO -> require(count == 0L)
			StepsHistoryEvidence.RECORDED,
			StepsHistoryEvidence.LEGACY_RECORDED -> require(count != null && count > 0L)
			StepsHistoryEvidence.NO_OBSERVATION,
			StepsHistoryEvidence.BASELINE -> require(count == null)
		}
	}
}

internal enum class StepsHistoryAvailability { DISABLED, AVAILABLE, DELETED, UNAVAILABLE }
internal enum class StepsHistoryEvidence {
	NO_OBSERVATION,
	BASELINE,
	COVERED_ZERO,
	RECORDED,
	LEGACY_RECORDED,
}
internal enum class StepsHistoryMaterialization { NOT_APPLICABLE, MATERIALIZING, READY, DEGRADED, FAILED }
internal enum class StepsHistoryCoverage { NONE, COMPLETE, PARTIAL, UNKNOWN }
internal enum class StepsHistoryReason {
	SOURCE_NOT_CAPTURED,
	SEGMENT_MEMBERSHIP_INCOMPLETE,
	SERVICE_RUN_MISSING,
	SERVICE_RUN_MEMBERSHIP_MISMATCH,
	MANIFEST_MISSING,
	MANIFEST_MEMBERSHIP_MISMATCH,
	MANIFEST_INTEGRITY_FAILED,
	MIXED_WRITER_WITHIN_SERVICE_RUN,
	UNKNOWN_WRITER,
	CANDIDATE_PROVENANCE_INCOMPLETE,
	CAPTURE_NOT_ENABLED_FOR_WHOLE_RUN,
	SERVICE_RUN_ACTIVE,
	LEGACY_UNATTRIBUTED,
	LEGACY_REPLAY_UNVERIFIED,
	LEGACY_ZERO_UNVERIFIED,
	PRODUCT_LANE_MISSING,
	PRODUCT_LANE_INVALID,
	PRODUCT_LANE_BEHIND,
	PRODUCT_LANE_CUTOFF_BEFORE_TARGET,
	PRODUCT_LANE_RETIRED_BEFORE_TARGET,
	TARGET_BEFORE_LANE_ACTIVATION,
	TERMINAL_PROJECTION_FAILURE,
	COMPLETENESS_MISSING,
	APP_DRAIN_INCOMPLETE,
	STOP_INCOMPLETE,
	UNRESOLVED_PROVIDER_SEQUENCE,
	PROVIDER_COMPLETENESS_UNOBSERVABLE,
	FACTS_MISSING_FOR_ADMITTED_RUN,
	DELETED_FACTS,
	RESET_GAP,
	PARTIAL_FACT,
	OUTSIDE_RETAINED_FLOOR,
	RETENTION_CROSSES_SEGMENT,
	SOURCE_EVIDENCE_STATE_MISSING,
	STALE_COLLECTED_DATA_EPOCH,
	COUNT_OVERFLOW,
}
