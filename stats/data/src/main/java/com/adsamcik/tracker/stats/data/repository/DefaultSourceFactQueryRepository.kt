package com.adsamcik.tracker.stats.data.repository

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.CellPurposeItems
import com.adsamcik.tracker.shared.base.database.PressureFactLineageProof
import com.adsamcik.tracker.shared.base.database.PressureFactProofAuthenticator
import com.adsamcik.tracker.shared.base.database.PressureFactProofExpectation
import com.adsamcik.tracker.shared.base.database.PressureFactProofFailure
import com.adsamcik.tracker.shared.base.database.PressureFactRetentionResolution
import com.adsamcik.tracker.shared.base.database.StepsCountDomainOwnerLookupKey
import com.adsamcik.tracker.shared.base.database.StepsCountDomainOwnerRead
import com.adsamcik.tracker.shared.base.database.StepsCountDomainStore
import com.adsamcik.tracker.shared.base.database.StepsCountDomainStoredOwner
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedEvidenceEntity
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedFactIntegrity
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedFragmentEntity
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedWindowCursorEntity
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedWindowRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.ActivityWalCarrierAuthenticator
import com.adsamcik.tracker.shared.base.database.data.CELL_WAL_PAYLOAD_VERSION
import com.adsamcik.tracker.shared.base.database.data.CanonicalRetentionBounds
import com.adsamcik.tracker.shared.base.database.data.CellAggregateReuseAuthority
import com.adsamcik.tracker.shared.base.database.data.CellCapturedFactCursorEntity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedFactRevisionIntegrity
import com.adsamcik.tracker.shared.base.database.data.CellWalCarrierAuthentication
import com.adsamcik.tracker.shared.base.database.data.CellWalCarrierAuthenticator
import com.adsamcik.tracker.shared.base.database.data.LocationObservation
import com.adsamcik.tracker.shared.base.database.data.LocationObservationDecision
import com.adsamcik.tracker.shared.base.database.data.LocationSample
import com.adsamcik.tracker.shared.base.database.data.MAX_ACTIVITY_WAL_PAYLOAD_BYTES
import com.adsamcik.tracker.shared.base.database.data.MAX_PRESSURE_WAL_PAYLOAD_BYTES
import com.adsamcik.tracker.shared.base.database.data.MAX_STEPS_WAL_PAYLOAD_BYTES
import com.adsamcik.tracker.shared.base.database.data.MAX_WIFI_WAL_PAYLOAD_BYTES
import com.adsamcik.tracker.shared.base.database.data.PressureFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionJoinStateEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionIntegrity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainOwnerRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainReceiptIntegrity
import com.adsamcik.tracker.shared.base.database.data.StepsWalCarrierAuthentication
import com.adsamcik.tracker.shared.base.database.data.StepsWalCarrierAuthenticator
import com.adsamcik.tracker.shared.base.database.data.WifiCapturedFactCursorEntity
import com.adsamcik.tracker.shared.base.database.data.WifiCapturedFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.WifiCapturedFactRevisionIntegrity
import com.adsamcik.tracker.shared.base.database.data.WifiWalCarrierAuthentication
import com.adsamcik.tracker.shared.base.database.data.WifiWalCarrierAuthenticator
import com.adsamcik.tracker.shared.base.database.data.aggregateReuseAuthority
import com.adsamcik.tracker.shared.base.database.data.authenticatedProtectedLocationCanonicalCurationState
import com.adsamcik.tracker.shared.base.database.data.hasValidCanonicalHistoryShape
import com.adsamcik.tracker.shared.base.database.data.protectedLocationCanonicalProductIdentity
import com.adsamcik.tracker.shared.base.database.data.protectedLocationCanonicalReceiptStateKey
import com.adsamcik.tracker.shared.base.database.loadPressureFactProofSnapshot
import com.adsamcik.tracker.shared.base.database.qualifyCellCapturePurposeItems
import com.adsamcik.tracker.shared.base.database.qualifyWifiPurposeItems
import com.adsamcik.tracker.shared.base.database.resolvePressureRetentionByFact
import com.adsamcik.tracker.shared.base.di.IoDispatcher
import com.adsamcik.tracker.shared.base.time.BootClockDomainProvider
import com.adsamcik.tracker.shared.model.tracking.TrackingPurpose
import com.adsamcik.tracker.shared.model.tracking.TrackingSource
import com.adsamcik.tracker.stats.api.repository.MaterializedProofState
import com.adsamcik.tracker.stats.api.repository.QueryableProofState
import com.adsamcik.tracker.stats.api.repository.SourceFactLookupRequest
import com.adsamcik.tracker.stats.api.repository.SourceFactLookupResult
import com.adsamcik.tracker.stats.api.repository.SourceFactQueryRepository
import com.adsamcik.tracker.stats.api.repository.SourceProofCompleteness
import com.adsamcik.tracker.stats.api.repository.SourceProofCoverage
import com.adsamcik.tracker.stats.api.repository.SourceProofReason
import com.adsamcik.tracker.stats.api.repository.SourceProofScope
import com.adsamcik.tracker.stats.api.repository.SourceProofSelectedEvidenceLineage
import com.adsamcik.tracker.stats.api.repository.SourceProofWriterIdentity
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryReadSnapshot
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Internal lifecycle adapter that cannot bypass the production history qualification contract. */
@Singleton
internal class DefaultSourceFactQueryRepository @Inject constructor(
	private val trackingHistoryRepository: TrackingHistoryRepository,
) : SourceFactQueryRepository {
	override suspend fun lookup(request: SourceFactLookupRequest): SourceFactLookupResult =
		trackingHistoryRepository.lookupSourceFact(request)
}

/**
 * Source-local exact lookup used only behind [TrackingHistoryRepository].
 *
 * Keeping this reader separate prevents lifecycle callers from bypassing the production history
 * membership, collision, and presentation qualification performed by that repository. It never
 * discovers a fact from timestamps, counts, coordinates, or trip membership.
 */
// One exact-identity Room reader keeps six-source lookup and presentation correspondence under the same snapshot.
@Suppress("LargeClass", "TooManyFunctions")
internal class RoomSourceFactLookupReader(
	private val database: AppDatabase,
	@IoDispatcher private val ioDispatcher: CoroutineDispatcher,
	private val bootClockDomainProvider: BootClockDomainProvider? = null,
) {
	suspend fun lookup(request: SourceFactLookupRequest): SourceFactLookupResult =
		withContext(ioDispatcher) {
			runCatching {
				database.withTransaction {
					lookupInTransaction(request)
				}
			}.getOrElse { failure ->
				if (failure is CancellationException) {
					throw failure
				}
				SourceFactLookupResult.Failed(SourceProofReason.REPOSITORY_FAILURE)
			}
		}

	internal suspend fun lookupInTransaction(
		request: SourceFactLookupRequest,
	): SourceFactLookupResult {
		lookupScopeRejection(request)?.let { return it }
		val state = database.sourceEvidenceStateDao().get()
			?: return SourceFactLookupResult.Unavailable(
				SourceProofReason.IMMUTABLE_AUTHORITY_MISSING,
			)
		lookupEvidenceRejection(request, state)?.let { return it }
		val result = lookupHistoricalSourceFact(request, state.retainedFromMs)
		return if (result is SourceFactLookupResult.Found) {
			result.copy(
				state = result.state.copy(
					readSnapshot = TrackingHistoryReadSnapshot(
						collectedDataEpoch = state.collectedDataEpoch,
						sourceEvidenceRevision = state.revision,
					),
				),
			)
		} else {
			result
		}
	}

	/** Reject unsupported purpose/scope before reading any source authority. */
	private fun lookupScopeRejection(request: SourceFactLookupRequest): SourceFactLookupResult? {
		if (request.key.purpose != TrackingPurpose.SESSION_CAPTURE) {
			return SourceFactLookupResult.Unavailable(
				if (request.key.purpose == TrackingPurpose.CONTROL) {
					SourceProofReason.CONTROL_IS_NOT_CAPTURE_EVIDENCE
				} else {
					SourceProofReason.AMBIENT_PROOF_NOT_IMPLEMENTED
				},
			)
		}
		if (request.key.scope !is SourceProofScope.Run) {
			return SourceFactLookupResult.Failed(SourceProofReason.SCOPE_MISMATCH)
		}
		return null
	}

	/** Deletion wins over missing writers and presentation; reuse the caller's exact evidence snapshot. */
	private suspend fun lookupEvidenceRejection(
		request: SourceFactLookupRequest,
		state: SourceEvidenceState,
	): SourceFactLookupResult? {
		if (state.collectedDataEpoch != request.key.collectedDataEpoch) {
			return if (state.collectedDataEpoch > request.key.collectedDataEpoch) {
				SourceFactLookupResult.Deleted(SourceProofReason.DELETED)
			} else {
				SourceFactLookupResult.Unavailable(SourceProofReason.DATA_EPOCH_MISMATCH)
			}
		}
		if (request.materialized.lineage.sourceAdmissionOrdinal <=
			state.deletedSourceEventHighWaterOrdinal ||
			isRunDeleted(request)
		) {
			return SourceFactLookupResult.Deleted(SourceProofReason.DELETED)
		}
		if (state.revision < request.materialized.lineage.sourceEvidenceRevision) {
			return SourceFactLookupResult.Unavailable(
				SourceProofReason.IMMUTABLE_AUTHORITY_MISSING,
			)
		}
		return null
	}

	/** Qualify the historical lane before touching source payloads; current destination ownership is irrelevant. */
	private suspend fun lookupHistoricalSourceFact(
		request: SourceFactLookupRequest,
		retainedFromMs: Long?,
	): SourceFactLookupResult {
		val writer = request.materialized.lineage.writer
			?: return SourceFactLookupResult.Failed(SourceProofReason.WRITER_MISMATCH)
		writer.writerMismatch(request.key.source)?.let { reason ->
			return SourceFactLookupResult.Failed(reason)
		}
		val historicalLane = database.sourceProjectionStateDao().productLane(
			request.key.source.stableCode(),
			writer.bindingGeneration,
			writer.projectionId,
			writer.projectionVersion,
		) ?: return SourceFactLookupResult.Unavailable(
			SourceProofReason.IMMUTABLE_AUTHORITY_MISSING,
		)
		return if (!historicalLane.hasValidCanonicalHistoryShape() ||
			request.materialized.lineage.sourceAdmissionOrdinal < historicalLane.activationOrdinal ||
			historicalLane.contiguousAdmissionOrdinal <
			request.materialized.lineage.sourceAdmissionOrdinal ||
			historicalLane.captureAdmissionCutoffOrdinal?.let { cutoff ->
				request.materialized.lineage.sourceAdmissionOrdinal > cutoff
			} == true
		) {
			SourceFactLookupResult.Failed(SourceProofReason.INTEGRITY_FAILURE)
		} else {
			lookupSourceProduct(request, retainedFromMs, historicalLane)
		}
	}

	private suspend fun lookupSourceProduct(
		request: SourceFactLookupRequest,
		retainedFromMs: Long?,
		historicalLane: SourceProductProjectionLaneEntity,
	): SourceFactLookupResult =
		when (request.key.source) {
			TrackingSource.LOCATION -> lookupLocation(request, retainedFromMs)
			TrackingSource.ACTIVITY -> lookupActivity(request, retainedFromMs)
			TrackingSource.STEPS -> lookupSteps(request, retainedFromMs)
			TrackingSource.PRESSURE -> lookupPressure(
				request,
				retainedFromMs,
				historicalLane,
			)
			TrackingSource.WIFI -> lookupWifi(request, retainedFromMs)
			TrackingSource.CELL -> lookupCell(request, retainedFromMs)
		}

	/** Only a retained, qualified Location WAL reaches the canonical receipt/observation/decision/sample read. */
	// One bounded receipt/observation/decision/sample correspondence keeps missing and writer checks in order.
	@Suppress("CyclomaticComplexMethod", "ReturnCount")
	private suspend fun lookupCanonicalLocationRows(
		request: SourceFactLookupRequest,
		writer: SourceProofWriterIdentity,
		eventId: String,
		wal: SourceEventWalEntity,
	): SourceFactLookupResult {
		val receipt = database.sourceProjectionStateDao().joinState(
			writer.projectionId,
			writer.projectionVersion,
			protectedLocationCanonicalReceiptStateKey(eventId),
		) ?: return SourceFactLookupResult.Unavailable(SourceProofReason.RECEIPT_MISSING)
		val observation = database.locationObservationDao().getBySourceEventId(eventId)
			?: return SourceFactLookupResult.Unavailable(
				SourceProofReason.IMMUTABLE_AUTHORITY_MISSING,
			)
		val decision = database.locationObservationDecisionDao().getBySourceEventId(eventId)
			?: return SourceFactLookupResult.Unavailable(SourceProofReason.RECEIPT_MISSING)
		if (decision.decision != LocationObservationDecision.ACCEPTED) {
			return SourceFactLookupResult.Failed(SourceProofReason.INTEGRITY_FAILURE)
		}
		val samples = database.locationSampleDao().getBySourceEventIdBounded(eventId, 2)
		if (samples.isEmpty()) {
			return SourceFactLookupResult.Unavailable(SourceProofReason.RECEIPT_MISSING)
		}
		if (samples.size != 1) {
			return SourceFactLookupResult.Failed(SourceProofReason.INTEGRITY_FAILURE)
		}
		val sample = samples.single()
		if (decision.writerProjectionId != writer.projectionId ||
			decision.writerProjectionVersion != writer.projectionVersion
		) {
			return SourceFactLookupResult.Failed(SourceProofReason.WRITER_MISMATCH)
		}
		if (decision.writerBindingGeneration != writer.bindingGeneration ||
			decision.writerOwnerGeneration != writer.ownerGeneration
		) {
			return SourceFactLookupResult.Failed(SourceProofReason.WRITER_GENERATION_MISMATCH)
		}
		if (!canonicalLocationReceiptMatches(request, wal, receipt, observation, decision, sample)) {
			return SourceFactLookupResult.Failed(SourceProofReason.INTEGRITY_FAILURE)
		}
		return found(request)
	}

	/** Authenticate the exact immutable rows only after their ordered availability and writer checks. */
	// Keep the complete immutable Location receipt, decision and canonical sample identity together.
	@Suppress("CyclomaticComplexMethod")
	private fun canonicalLocationReceiptMatches(
		request: SourceFactLookupRequest,
		wal: SourceEventWalEntity,
		receipt: SourceProjectionJoinStateEntity,
		observation: LocationObservation,
		decision: LocationObservationDecision,
		sample: LocationSample,
	): Boolean {
		val lineage = request.materialized.lineage
		val eventId = request.materialized.requireFactIdentity()
		val scope = request.key.scope as SourceProofScope.Run
		val canonicalSignalId = protectedLocationCanonicalProductIdentity(eventId)
		return !(decision.reason != null ||
			decision.decisionVersion != LocationObservationDecision.CURRENT_DECISION_VERSION ||
			lineage.semanticRevision != decision.decisionVersion.toLong() ||
			lineage.cursorRevision !in wal.admissionOrdinal..Long.MAX_VALUE ||
			receipt.logicalTrackingId != scope.logicalTrackingId ||
			decision.observationSourceEventId != eventId ||
			decision.acceptedSampleSourceSignalId != canonicalSignalId ||
			decision.sourceSignalId != canonicalSignalId ||
			sample.sourceSignalId != canonicalSignalId ||
			sample.sourceEventId != observation.sourceEventId ||
			observation.sourceEventId != eventId ||
			request.materialized.completeness != SourceProofCompleteness.COMPLETE ||
			request.materialized.coverage !=
			SourceProofCoverage.ProviderPoint(sample.elapsedRealtimeNanos) ||
			receipt.authenticatedProtectedLocationCanonicalCurationState(
				sourceEventId = eventId,
				sourceAdmissionOrdinal = wal.admissionOrdinal,
				walIntegrityIdentity = wal.integrityIdentity,
				observation = observation,
				decision = decision,
				sample = sample,
			) == null
		)
	}

	private suspend fun lookupLocation(
		request: SourceFactLookupRequest,
		retainedFromMs: Long?,
	): SourceFactLookupResult {
		val writer = requireNotNull(request.materialized.lineage.writer)
		val eventId = request.materialized.requireFactIdentity()
		if (eventId != request.key.evidenceIdentity.value) {
			return identityMismatch()
		}
		val scope = request.key.scope as SourceProofScope.Run
		val wal = database.sourceEventWalDao().getByEventId(eventId)
			?: return SourceFactLookupResult.Unavailable(SourceProofReason.IMMUTABLE_AUTHORITY_MISSING)
		if (!wal.matchesLocationRequest(request, scope)) {
			return SourceFactLookupResult.Failed(SourceProofReason.INTEGRITY_FAILURE)
		}
		return retainedLowerBoundRejection(retainedFromMs) {
			wal.wallTimeMs?.let { wallTimeMs ->
				wal.wallTimeUncertaintyMs?.let { uncertaintyMs ->
					CanonicalRetentionBounds.location(wallTimeMs, uncertaintyMs)
				}
			}
		} ?: lookupCanonicalLocationRows(request, writer, eventId, wal)
	}

	private fun SourceEventWalEntity.matchesLocationRequest(
		request: SourceFactLookupRequest,
		scope: SourceProofScope.Run,
	): Boolean {
		if (!hasQualifiedIntegrity() || admissionOrdinal != request.materialized.lineage.sourceAdmissionOrdinal ||
			sourceKind != SourceDestinationOwnerEntity.SOURCE_LOCATION || logicalTrackingId != scope.logicalTrackingId
		) {
			return false
		}
		return serviceRunId == scope.serviceRunId && sourcePolicyRevision == request.key.sourcePolicyRevision &&
			captureConsentEpoch == request.key.captureConsentEpoch &&
			capturedCollectedDataEpoch == request.key.collectedDataEpoch
	}

	private fun retainedLowerBoundRejection(
		retainedFromMs: Long?,
		lowerBound: () -> Long?,
	): SourceFactLookupResult? {
		if (retainedFromMs == null) {
			return null
		}
		val earliest = lowerBound() ?: return SourceFactLookupResult.Failed(SourceProofReason.INTEGRITY_FAILURE)
		return SourceFactLookupResult.Deleted(SourceProofReason.RETENTION_EXPIRED).takeIf { earliest < retainedFromMs }
	}

	private suspend fun lookupActivity(
		request: SourceFactLookupRequest,
		retainedFromMs: Long?,
	): SourceFactLookupResult {
		val lineage = request.materialized.lineage
		val writer = requireNotNull(lineage.writer)
		val factId = request.materialized.requireFactIdentity()
		val semanticRevision = requireNotNull(lineage.semanticRevision)
		val revision = database.activityCapturedFactDao().revision(
			writer.projectionId,
			writer.projectionVersion,
			factId,
			semanticRevision,
		) ?: return SourceFactLookupResult.NotFound()
		val revisionLineage = database.activityCapturedFactDao().revisionsForWindow(
			writerProjectionId = writer.projectionId,
			writerProjectionVersion = writer.projectionVersion,
			logicalWindowId = factId,
			limit = MAX_ACTIVITY_REVISIONS + 1,
		)
		if (revisionLineage.size > MAX_ACTIVITY_REVISIONS) {
			return SourceFactLookupResult.Unavailable(SourceProofReason.PARTIAL_COMPLETENESS)
		}
		if (!ActivityCapturedFactIntegrity.hasCompleteRevisionLineage(revisionLineage) ||
			revisionLineage.lastOrNull() != revision
		) {
			return SourceFactLookupResult.Failed(SourceProofReason.INTEGRITY_FAILURE)
		}
		return lookupActivityCursor(request, retainedFromMs, revision, revisionLineage)
	}

	private suspend fun lookupActivityCursor(
		request: SourceFactLookupRequest,
		retainedFromMs: Long?,
		revision: ActivityCapturedWindowRevisionEntity,
		revisionLineage: List<ActivityCapturedWindowRevisionEntity>,
	): SourceFactLookupResult {
		val lineage = request.materialized.lineage
		val writer = requireNotNull(lineage.writer)
		val factId = request.materialized.requireFactIdentity()
		val cursor = database.activityCapturedFactDao().cursor(
			writer.projectionId,
			writer.projectionVersion,
			factId,
		) ?: return projectionFailureOrNotFound(
			writer,
			lineage.sourceAdmissionOrdinal,
			SourceProofReason.CURSOR_BEHIND,
		)
		val fragmentsByRevision = linkedMapOf<Long, List<
			com.adsamcik.tracker.shared.base.database.data.ActivityCapturedFragmentEntity>>()
		val evidenceByRevision = linkedMapOf<Long, List<
			com.adsamcik.tracker.shared.base.database.data.ActivityCapturedEvidenceEntity>>()
		return loadActivityChildRevisions(writer, factId, revisionLineage, fragmentsByRevision, evidenceByRevision)
			?: revision.qualifyActivityRevision(request, retainedFromMs, cursor,
				revisionLineage, fragmentsByRevision, evidenceByRevision)
	}

	private suspend fun loadActivityChildRevisions(
		writer: SourceProofWriterIdentity,
		factId: String,
		revisionLineage: List<ActivityCapturedWindowRevisionEntity>,
		fragmentsByRevision: MutableMap<Long, List<ActivityCapturedFragmentEntity>>,
		evidenceByRevision: MutableMap<Long, List<ActivityCapturedEvidenceEntity>>,
	): SourceFactLookupResult? {
		for (lineageRevision in revisionLineage) {
			val fragments = database.activityCapturedFactDao().maintenanceFragments(
				writer.projectionId,
				writer.projectionVersion,
				factId,
				lineageRevision.semanticRevision,
				MAX_ACTIVITY_CHILD_ROWS + 1,
			)
			val evidence = database.activityCapturedFactDao().maintenanceEvidence(
				writer.projectionId,
				writer.projectionVersion,
				factId,
				lineageRevision.semanticRevision,
				MAX_ACTIVITY_CHILD_ROWS + 1,
			)
			if (fragments.size > MAX_ACTIVITY_CHILD_ROWS ||
				evidence.size > MAX_ACTIVITY_CHILD_ROWS
			) {
				return SourceFactLookupResult.Unavailable(SourceProofReason.PARTIAL_COMPLETENESS)
			}
			if (!ActivityCapturedFactIntegrity.verify(lineageRevision, fragments, evidence)) {
				return SourceFactLookupResult.Failed(SourceProofReason.INTEGRITY_FAILURE)
			}
			fragmentsByRevision[lineageRevision.semanticRevision] = fragments
			evidenceByRevision[lineageRevision.semanticRevision] = evidence
		}
		return null
	}

	private suspend fun ActivityCapturedWindowRevisionEntity.qualifyActivityRevision(
		request: SourceFactLookupRequest,
		retainedFromMs: Long?,
		cursor: ActivityCapturedWindowCursorEntity,
		revisionLineage: List<ActivityCapturedWindowRevisionEntity>,
		fragmentsByRevision: Map<Long, List<ActivityCapturedFragmentEntity>>,
		evidenceByRevision: Map<Long, List<ActivityCapturedEvidenceEntity>>,
	): SourceFactLookupResult {
		val revision = this
		if (!revision.matchesActivityCursor(request, cursor)) {
			return SourceFactLookupResult.Failed(SourceProofReason.INTEGRITY_FAILURE)
		}
		val lineageEvidence = revisionLineage.flatMap { lineageRevision ->
			evidenceByRevision.getValue(lineageRevision.semanticRevision).map { evidence ->
				lineageRevision to evidence
			}
		}
		val selectedEvidenceRows = lineageEvidence.filter { (_, evidence) ->
			evidence.sourceEventId == request.key.evidenceIdentity.value
		}
		val selectedEvidence = selectedEvidenceRows.firstOrNull()?.second
			?: return SourceFactLookupResult.Failed(SourceProofReason.INTEGRITY_FAILURE)
		if (selectedEvidenceRows.any { (_, evidence) ->
				!ActivityCapturedFactIntegrity.hasSameRecordedObservation(selectedEvidence, evidence)
			}) {
			return SourceFactLookupResult.Failed(SourceProofReason.INTEGRITY_FAILURE)
		}
		return revision.lookupActivityEvidence(
			request, retainedFromMs, selectedEvidence, lineageEvidence, fragmentsByRevision,
		)
	}

	private fun ActivityCapturedWindowRevisionEntity.matchesActivityCursor(
		request: SourceFactLookupRequest,
		cursor: ActivityCapturedWindowCursorEntity,
	): Boolean {
		val writer = requireNotNull(request.materialized.lineage.writer)
		if (writerBindingGeneration != writer.bindingGeneration || writerOwnerGeneration != writer.ownerGeneration) {
			return false
		}
		return cursor.matchesActivityOwner(this) && cursor.matchesActivityRevision(this, request) && matches(request)
	}

	private fun ActivityCapturedWindowCursorEntity.matchesActivityOwner(
		revision: ActivityCapturedWindowRevisionEntity,
	): Boolean = logicalTrackingId == revision.logicalTrackingId && serviceRunId == revision.serviceRunId &&
		sessionSegmentId == revision.sessionSegmentId && writerOwnerGeneration == revision.writerOwnerGeneration

	private fun ActivityCapturedWindowCursorEntity.matchesActivityRevision(
		revision: ActivityCapturedWindowRevisionEntity,
		request: SourceFactLookupRequest,
	): Boolean {
		if (latestSemanticRevision != revision.semanticRevision || latestMutationId != revision.mutationId ||
			latestEffectChecksum != revision.effectChecksum
		) {
			return false
		}
		return cursorRevision == request.materialized.lineage.cursorRevision &&
			collectedDataEpoch == revision.collectedDataEpoch && updatedAtMs == revision.appliedAtMs
	}

	private suspend fun ActivityCapturedWindowRevisionEntity.lookupActivityEvidence(
		request: SourceFactLookupRequest,
		retainedFromMs: Long?,
		selectedEvidence: ActivityCapturedEvidenceEntity,
		lineageEvidence: List<Pair<ActivityCapturedWindowRevisionEntity, ActivityCapturedEvidenceEntity>>,
		fragmentsByRevision: Map<Long, List<ActivityCapturedFragmentEntity>>,
	): SourceFactLookupResult {
		val walByEventId = loadActivityWalCarriers(lineageEvidence.map { it.second })
		if (!hasValidActivityWalLineage(lineageEvidence, walByEventId)) {
			return SourceFactLookupResult.Failed(SourceProofReason.INTEGRITY_FAILURE)
		}
		val expectedCoverage = selectedEvidence.coverageEndExclusiveElapsedRealtimeNanos?.let { end ->
			SourceProofCoverage.ProviderInterval(
				selectedEvidence.providerElapsedRealtimeNanos,
				end,
			)
		} ?: SourceProofCoverage.ProviderPoint(
			selectedEvidence.providerElapsedRealtimeNanos,
		)
		if (request.materialized.coverage != expectedCoverage ||
			request.materialized.completeness != coverage.toCompleteness()
		) {
			return SourceFactLookupResult.Failed(SourceProofReason.FACT_IDENTITY_MISMATCH)
		}
		if (retainedFromMs != null) {
			if (CanonicalRetentionBounds.crossesFloor(
					CanonicalRetentionBounds.activityLineage(
						fragmentsByRevision.values.flatten(),
					),
					retainedFromMs,
				)
			) {
				return SourceFactLookupResult.Deleted(SourceProofReason.RETENTION_EXPIRED)
			}
		}
		return found(request)
	}

	private fun hasValidActivityWalLineage(
		lineageEvidence: List<Pair<ActivityCapturedWindowRevisionEntity, ActivityCapturedEvidenceEntity>>,
		walByEventId: Map<String, SourceEventWalEntity>,
	): Boolean = walByEventId.size == lineageEvidence.map { it.second.sourceEventId }.distinct().size &&
		lineageEvidence.all { (revision, row) ->
			walByEventId[row.sourceEventId]?.let { wal ->
				ActivityWalCarrierAuthenticator.authenticates(revision, row, wal)
			} == true
		}

	private suspend fun lookupSteps(
		request: SourceFactLookupRequest,
		retainedFromMs: Long?,
	): SourceFactLookupResult {
		val writer = requireNotNull(request.materialized.lineage.writer)
		val fact = database.stepFactRevisionDao().latest(
			writer.projectionId, writer.projectionVersion, request.materialized.requireFactIdentity(),
		) ?: return SourceFactLookupResult.NotFound()
		if (!fact.matchesStepsRequest(request)) {
			return SourceFactLookupResult.Failed(SourceProofReason.INTEGRITY_FAILURE)
		}
		return fact.lookupStepsOwners(request, retainedFromMs)
	}

	private fun StepFactRevisionEntity.matchesStepsRequest(request: SourceFactLookupRequest): Boolean {
		val lineage = request.materialized.lineage
		val writer = requireNotNull(lineage.writer)
		if (semanticRevision != lineage.semanticRevision || sourceEventId != request.key.evidenceIdentity.value ||
			sourceAdmissionOrdinal != lineage.sourceAdmissionOrdinal || writerBindingGeneration != writer.bindingGeneration
		) {
			return false
		}
		return StepFactRevisionIntegrity.hasValidCanonicalLiveWalFact(this) && matches(request)
	}

	private suspend fun StepFactRevisionEntity.lookupStepsOwners(
		request: SourceFactLookupRequest,
		retainedFromMs: Long?,
	): SourceFactLookupResult {
		val wal = database.sourceEventWalDao().boundedPayloadByEventId(
			request.key.evidenceIdentity.value, MAX_STEPS_WAL_PAYLOAD_BYTES,
		) ?: return SourceFactLookupResult.Unavailable(SourceProofReason.IMMUTABLE_AUTHORITY_MISSING)
		val ownerKeys = listOf(wal.stepsWalOwnerKey(), stepsFactOwnerKey())
		return when (val read = StepsCountDomainStore(database).readOwners(ownerKeys)) {
			is StepsCountDomainOwnerRead.Ready -> qualifyStepsCarrier(
				request, retainedFromMs, wal, read.owners[ownerKeys[0]], read.owners[ownerKeys[1]],
			)
			StepsCountDomainOwnerRead.SchemaUnavailable ->
				SourceFactLookupResult.Unavailable(SourceProofReason.IMMUTABLE_AUTHORITY_MISSING)
			StepsCountDomainOwnerRead.Overflow,
			StepsCountDomainOwnerRead.Unverifiable,
			-> SourceFactLookupResult.Failed(SourceProofReason.INTEGRITY_FAILURE)
		}
	}

	private suspend fun StepFactRevisionEntity.qualifyStepsCarrier(
		request: SourceFactLookupRequest,
		retainedFromMs: Long?,
		wal: SourceEventWalEntity,
		walOwner: StepsCountDomainStoredOwner?,
		factOwner: StepsCountDomainStoredOwner?,
	): SourceFactLookupResult {
		val carrier = StepsWalCarrierAuthenticator.authenticate(this, wal, walOwner, factOwner)
		if (carrier !is StepsWalCarrierAuthentication.Authenticated ||
			!carrier.carrier.countDomainProven || !carrier.carrier.isPositiveCoveredWindow
		) {
			return SourceFactLookupResult.Failed(SourceProofReason.INTEGRITY_FAILURE)
		}
		val binding = database.sourceSessionDao().manifestSources(
			requireNotNull(logicalTrackingId), requireNotNull(manifestRevision),
		).singleOrNull { source ->
			source.sourceKind == SourceDestinationOwnerEntity.SOURCE_STEPS &&
				source.purpose == SessionManifestPurposeCode.SESSION_CAPTURE
		}
		val writer = requireNotNull(request.materialized.lineage.writer)
		if (binding == null || !binding.matchesLookupWriter(captureConsentEpoch, writer,
				SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS,
				SourceDestinationOwnerEntity.OWNER_STEPS_SESSION_FACTS)
		) {
			return SourceFactLookupResult.Unavailable(SourceProofReason.IMMUTABLE_AUTHORITY_MISSING)
		}
		return completeStepsLookup(request, retainedFromMs)
	}

	private fun SessionManifestSourceEntity.matchesLookupWriter(
		expectedConsentEpoch: Long?,
		writer: SourceProofWriterIdentity,
		expectedDestination: String,
		expectedOwner: String,
	): Boolean {
		if (!persistenceEligible || consentEpoch != expectedConsentEpoch || outputDestination != expectedDestination ||
			writerOwner != expectedOwner
		) {
			return false
		}
		return writerOwnerGeneration == writer.ownerGeneration && writerProjectionId == writer.projectionId &&
			writerProjectionVersion == writer.projectionVersion && writerBindingGeneration == writer.bindingGeneration
	}

	private fun StepFactRevisionEntity.completeStepsLookup(
		request: SourceFactLookupRequest,
		retainedFromMs: Long?,
	): SourceFactLookupResult {
		if (request.materialized.coverage != providerCoverage(
				requireNotNull(intervalStartElapsedRealtimeNanos), requireNotNull(intervalEndElapsedRealtimeNanos),
			) || request.materialized.completeness != SourceProofCompleteness.COMPLETE
		) {
			return SourceFactLookupResult.Failed(SourceProofReason.FACT_IDENTITY_MISMATCH)
		}
		return if (retainedFromMs != null &&
			CanonicalRetentionBounds.stepsWindowCrossesFloor(intervalEndTimeMs, retainedFromMs)
		) {
			SourceFactLookupResult.Deleted(SourceProofReason.RETENTION_EXPIRED)
		} else {
			found(request)
		}
	}

	private suspend fun lookupPressure(
		request: SourceFactLookupRequest,
		retainedFromMs: Long?,
		historicalLane: SourceProductProjectionLaneEntity,
	): SourceFactLookupResult {
		val lineage = request.materialized.lineage
		val writer = requireNotNull(lineage.writer)
		val logicalFactId = request.materialized.requireFactIdentity()
		val selectedSemanticRevision =
			lineage.selectedEvidenceLineage?.semanticRevision ?: lineage.semanticRevision
		val fact = database.pressureFactRevisionDao().latest(
			writer.projectionId,
			writer.projectionVersion,
			logicalFactId,
		)
		val scope = request.key.scope as SourceProofScope.Run
		val retentionResolution = database.resolvePressureRetentionByFact(
			writerProjectionId = writer.projectionId,
			writerProjectionVersion = writer.projectionVersion,
			logicalFactId = logicalFactId,
			semanticRevision = selectedSemanticRevision,
			sourceEventId = request.key.evidenceIdentity.value,
			sourceAdmissionOrdinal = lineage.sourceAdmissionOrdinal,
			sourcePolicyRevision = request.key.sourcePolicyRevision,
			captureConsentEpoch = request.key.captureConsentEpoch,
			collectedDataEpoch = request.key.collectedDataEpoch,
			logicalTrackingId = scope.logicalTrackingId,
			serviceRunId = scope.serviceRunId,
			retainedFromMs = retainedFromMs,
		)
		return retentionResolution.lookupPressureRetention(request, historicalLane, fact) ?: if (fact == null) {
			SourceFactLookupResult.NotFound()
		} else {
			fact.lookupPressureLineage(request, retainedFromMs, historicalLane, selectedSemanticRevision)
		}
	}

	private fun PressureFactRetentionResolution.lookupPressureRetention(
		request: SourceFactLookupRequest,
		historicalLane: SourceProductProjectionLaneEntity,
		fact: PressureFactRevisionEntity?,
	): SourceFactLookupResult? = when (this) {
		is PressureFactRetentionResolution.Authenticated -> when {
			!authenticatesRetainedRequest(request, historicalLane) ->
				SourceFactLookupResult.Failed(SourceProofReason.INTEGRITY_FAILURE)
			fact != null -> SourceFactLookupResult.Failed(SourceProofReason.INTEGRITY_FAILURE)
			else -> SourceFactLookupResult.Deleted(SourceProofReason.RETENTION_EXPIRED)
		}
		PressureFactRetentionResolution.Invalid -> SourceFactLookupResult.Failed(SourceProofReason.INTEGRITY_FAILURE)
		PressureFactRetentionResolution.Absent -> null
	}

	private suspend fun PressureFactRevisionEntity.lookupPressureLineage(
		request: SourceFactLookupRequest,
		retainedFromMs: Long?,
		historicalLane: SourceProductProjectionLaneEntity,
		selectedSemanticRevision: Long?,
	): SourceFactLookupResult {
		val fact = this
		val lineage = request.materialized.lineage
		val writer = requireNotNull(lineage.writer)
		val scope = request.key.scope as SourceProofScope.Run
		val revisions = database.pressureFactRevisionDao().revisionsForFact(
			writer.projectionId,
			writer.projectionVersion,
			fact.logicalFactId,
			MAX_PRESSURE_PROOF_REVISIONS + 1,
		)
		if (revisions.size > MAX_PRESSURE_PROOF_REVISIONS) {
			return SourceFactLookupResult.Failed(SourceProofReason.INTEGRITY_FAILURE)
		}
		val binding = database.sourceSessionDao().manifestSources(
			fact.logicalTrackingId,
			fact.manifestRevision,
		).singleOrNull { source ->
			source.sourceKind == SourceDestinationOwnerEntity.SOURCE_PRESSURE &&
				source.purpose == SessionManifestPurposeCode.SESSION_CAPTURE
		} ?: return SourceFactLookupResult.Failed(SourceProofReason.INTEGRITY_FAILURE)
		val walByEventId = loadPressureWalCarriers(revisions)
		val proofSnapshot = database.loadPressureFactProofSnapshot(
			facts = revisions,
			walByEventId = walByEventId,
			currentClockDomainId = bootClockDomainProvider?.current(),
		)
		val proof = PressureFactProofAuthenticator.authenticateLineage(
			revisions = revisions,
			snapshot = proofSnapshot,
			expectation = PressureFactProofExpectation(
				evidenceIdentity = request.key.evidenceIdentity.value,
				sourcePolicyRevision = request.key.sourcePolicyRevision,
				captureConsentEpoch = request.key.captureConsentEpoch,
				collectedDataEpoch = request.key.collectedDataEpoch,
				logicalTrackingId = scope.logicalTrackingId,
				serviceRunId = scope.serviceRunId,
				logicalFactId = request.materialized.requireFactIdentity(),
				semanticRevision = selectedSemanticRevision,
				sourceAdmissionOrdinal = lineage.sourceAdmissionOrdinal,
				writerProjectionId = writer.projectionId,
				writerProjectionVersion = writer.projectionVersion,
				writerBindingGeneration = writer.bindingGeneration,
				writerOwnerGeneration = writer.ownerGeneration,
			),
		)
		return when (proof) {
			is PressureFactLineageProof.Authenticated ->
				proof.qualifyPressureLookup(request, retainedFromMs, historicalLane, fact, binding)
			is PressureFactLineageProof.Rejected -> proof.lookupFailure()
		}
	}

	private fun PressureFactLineageProof.Rejected.lookupFailure(): SourceFactLookupResult =
		if (reason == PressureFactProofFailure.IMMUTABLE_AUTHORITY_MISSING) {
			SourceFactLookupResult.Unavailable(SourceProofReason.IMMUTABLE_AUTHORITY_MISSING)
		} else {
			SourceFactLookupResult.Failed(if (reason == PressureFactProofFailure.CURRENT_FACT_MISMATCH) {
				SourceProofReason.FACT_IDENTITY_MISMATCH
			} else {
				SourceProofReason.INTEGRITY_FAILURE
			})
		}

	private fun PressureFactLineageProof.Authenticated.qualifyPressureLookup(
		request: SourceFactLookupRequest,
		retainedFromMs: Long?,
		historicalLane: SourceProductProjectionLaneEntity,
		fact: PressureFactRevisionEntity,
		binding: SessionManifestSourceEntity,
	): SourceFactLookupResult {
		val authenticated = this
		val lineage = request.materialized.lineage
		val writer = requireNotNull(lineage.writer)
		val authenticatedFact = authenticated.current
		val selectedFact = authenticated.selected
		val selectedLineage = SourceProofSelectedEvidenceLineage(
			semanticRevision = selectedFact.semanticRevision,
			supersededByCurrentSemanticRevision = if (
				selectedFact.semanticRevision < authenticatedFact.semanticRevision
			) {
				authenticatedFact.semanticRevision
			} else {
				null
			},
		)
		if (!fact.matchesSelectedPressureProof(request, this, selectedLineage) ||
			!withinPressureLookupLane(request, historicalLane) ||
			!binding.matchesLookupWriter(fact.captureConsentEpoch, writer,
				SourceDestinationOwnerEntity.DESTINATION_SESSION_PRESSURE,
				SourceDestinationOwnerEntity.OWNER_PRESSURE_SESSION_FACTS) || !fact.matches(request)
		) {
			return SourceFactLookupResult.Failed(SourceProofReason.INTEGRITY_FAILURE)
		}
		if (request.materialized.coverage != selectedPressureProofCoverage() ||
			request.materialized.completeness != selectedPressureProofCompleteness()
		) {
			return SourceFactLookupResult.Failed(SourceProofReason.FACT_IDENTITY_MISMATCH)
		}
		return retainedLowerBoundRejection(retainedFromMs) {
			CanonicalRetentionBounds.pressureLineage(revisions)
		} ?: found(request)
	}

	private fun PressureFactRevisionEntity.matchesSelectedPressureProof(
		request: SourceFactLookupRequest,
		proof: PressureFactLineageProof.Authenticated,
		selectedLineage: SourceProofSelectedEvidenceLineage,
	): Boolean {
		if (semanticRevision != request.materialized.lineage.semanticRevision || this != proof.current ||
			proof.selected.sourceEventId != request.key.evidenceIdentity.value
		) {
			return false
		}
		return proof.selected.sourceAdmissionOrdinal == request.materialized.lineage.sourceAdmissionOrdinal &&
			selectedLineage == request.materialized.lineage.selectedEvidenceLineage &&
			writerBindingGeneration == requireNotNull(request.materialized.lineage.writer).bindingGeneration
	}

	private fun PressureFactLineageProof.Authenticated.withinPressureLookupLane(
		request: SourceFactLookupRequest,
		lane: SourceProductProjectionLaneEntity,
	): Boolean {
		if (selected.sourceAdmissionOrdinal < lane.activationOrdinal ||
			current.sourceAdmissionOrdinal < lane.activationOrdinal ||
			current.sourceAdmissionOrdinal > requireNotNull(request.materialized.lineage.cursorRevision) ||
			current.sourceAdmissionOrdinal > lane.contiguousAdmissionOrdinal
		) {
			return false
		}
		return lane.captureAdmissionCutoffOrdinal?.let { current.sourceAdmissionOrdinal <= it } != false
	}

	private suspend fun loadPressureWalCarriers(
		revisions: List<PressureFactRevisionEntity>,
	): Map<String, com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity> {
		val rows = mutableListOf<
			com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity>()
		for (eventIds in revisions.map(PressureFactRevisionEntity::sourceEventId).distinct()
			.chunked(PRESSURE_WAL_ID_BATCH_SIZE)
		) {
			rows += database.sourceEventWalDao().boundedPayloadsByEventIds(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
				eventIds = eventIds,
				maximumPayloadBytes = MAX_PRESSURE_WAL_PAYLOAD_BYTES,
				limit = eventIds.size,
			)
		}
		return rows.associateBy { it.eventId }
	}

	private suspend fun lookupWifi(
		request: SourceFactLookupRequest,
		retainedFromMs: Long?,
	): SourceFactLookupResult = capturedRadioRetentionLookup(request, retainedFromMs)
		?: lookupRetainedWifi(request, retainedFromMs)

	private suspend fun lookupRetainedWifi(
		request: SourceFactLookupRequest,
		retainedFromMs: Long?,
	): SourceFactLookupResult {
		val lineage = request.materialized.lineage
		val writer = requireNotNull(lineage.writer)
		val fact = database.wifiCapturedFactDao().revision(
			writer.projectionId, writer.projectionVersion, request.materialized.requireFactIdentity(),
			requireNotNull(lineage.semanticRevision),
		) ?: return SourceFactLookupResult.NotFound()
		val wal = database.sourceEventWalDao().boundedPayloadByEventId(
			request.key.evidenceIdentity.value, MAX_WIFI_WAL_PAYLOAD_BYTES,
		) ?: return SourceFactLookupResult.Unavailable(SourceProofReason.IMMUTABLE_AUTHORITY_MISSING)
		val walAuthentication = authenticateWifiFactCarrier(fact, wal)
			?: return SourceFactLookupResult.Failed(SourceProofReason.INTEGRITY_FAILURE)
		return when (fact.factKind) {
			WifiCapturedFactRevisionEntity.FACT_KIND_AGGREGATE ->
				fact.lookupWifiCursor(request, retainedFromMs, walAuthentication, fact, null)
			WifiCapturedFactRevisionEntity.FACT_KIND_COVERAGE_ONLY ->
				fact.lookupWifiAggregate(request, retainedFromMs, walAuthentication)
			else -> SourceFactLookupResult.Failed(SourceProofReason.INTEGRITY_FAILURE)
		}
	}

	private suspend fun WifiCapturedFactRevisionEntity.lookupWifiAggregate(
		request: SourceFactLookupRequest,
		retainedFromMs: Long?,
		walAuthentication: WifiWalCarrierAuthentication.Authenticated,
	): SourceFactLookupResult {
		val ownerId = aggregateOwnerLogicalFactId
			?: return SourceFactLookupResult.Failed(SourceProofReason.INTEGRITY_FAILURE)
		val ownerRevision = aggregateOwnerSemanticRevision
			?: return SourceFactLookupResult.Failed(SourceProofReason.INTEGRITY_FAILURE)
		val owner = database.wifiCapturedFactDao().revision(
			writerProjectionId, writerProjectionVersion, ownerId, ownerRevision,
		) ?: return SourceFactLookupResult.Failed(SourceProofReason.INTEGRITY_FAILURE)
		return lookupWifiOwnerCarrier(request, retainedFromMs, walAuthentication, ownerId, owner)
	}

	private suspend fun WifiCapturedFactRevisionEntity.lookupWifiOwnerCarrier(
		request: SourceFactLookupRequest,
		retainedFromMs: Long?,
		walAuthentication: WifiWalCarrierAuthentication.Authenticated,
		ownerId: String,
		owner: WifiCapturedFactRevisionEntity,
	): SourceFactLookupResult {
		val ownerCursor = database.wifiCapturedFactDao().cursor(writerProjectionId, writerProjectionVersion, ownerId)
			?: return SourceFactLookupResult.Unavailable(SourceProofReason.IMMUTABLE_AUTHORITY_MISSING)
		val ownerWal = database.sourceEventWalDao().getByEventId(owner.sourceEventId)
			?: return SourceFactLookupResult.Unavailable(SourceProofReason.IMMUTABLE_AUTHORITY_MISSING)
		authenticateWifiFactCarrier(owner, ownerWal)
			?: return SourceFactLookupResult.Failed(SourceProofReason.INTEGRITY_FAILURE)
		return lookupWifiCursor(request, retainedFromMs, walAuthentication, owner, ownerCursor)
	}

	private suspend fun WifiCapturedFactRevisionEntity.lookupWifiCursor(
		request: SourceFactLookupRequest,
		retainedFromMs: Long?,
		walAuthentication: WifiWalCarrierAuthentication.Authenticated,
		aggregate: WifiCapturedFactRevisionEntity,
		ownerCursor: WifiCapturedFactCursorEntity?,
	): SourceFactLookupResult {
		val lineage = request.materialized.lineage
		val writer = requireNotNull(lineage.writer)
		val cursor = database.wifiCapturedFactDao().cursor(writer.projectionId, writer.projectionVersion, logicalFactId)
			?: return projectionFailureOrNotFound(writer, lineage.sourceAdmissionOrdinal, SourceProofReason.CURSOR_BEHIND)
		if (factKind == WifiCapturedFactRevisionEntity.FACT_KIND_COVERAGE_ONLY &&
			ownerCursor?.let { WifiCapturedFactRevisionIntegrity.coverageOnlyOwnerCursorAuthenticates(this, aggregate, it) }
				!= true
		) {
			return SourceFactLookupResult.NotFound(SourceProofReason.STALE)
		}
		if (!matchesWifiLookup(request, cursor, aggregate, walAuthentication)) {
			return SourceFactLookupResult.Failed(SourceProofReason.INTEGRITY_FAILURE)
		}
		return completeWifiLookup(request, retainedFromMs, aggregate)
	}

	private fun WifiCapturedFactRevisionEntity.matchesWifiLookup(
		request: SourceFactLookupRequest,
		cursor: WifiCapturedFactCursorEntity,
		aggregate: WifiCapturedFactRevisionEntity,
		walAuthentication: WifiWalCarrierAuthentication.Authenticated,
	): Boolean {
		val writer = requireNotNull(request.materialized.lineage.writer)
		if (writerBindingGeneration != writer.bindingGeneration || writerOwnerGeneration != writer.ownerGeneration ||
			!WifiCapturedFactRevisionIntegrity.cursorAuthenticatesCurrent(cursor, this)
		) {
			return false
		}
		return WifiWalCarrierAuthenticator.authenticatesAggregate(aggregate, walAuthentication.carrier, this) &&
			cursor.cursorRevision == request.materialized.lineage.cursorRevision && matches(request)
	}

	private fun WifiCapturedFactRevisionEntity.completeWifiLookup(
		request: SourceFactLookupRequest,
		retainedFromMs: Long?,
		aggregate: WifiCapturedFactRevisionEntity,
	): SourceFactLookupResult {
		if (request.materialized.coverage != providerCoverage(coverageIntervalStartNanos, coverageIntervalEndNanos) ||
			request.materialized.completeness != coverageCompleteness.toCompleteness()
		) {
			return SourceFactLookupResult.Failed(SourceProofReason.FACT_IDENTITY_MISMATCH)
		}
		return retainedRadioBoundsRejection(retainedFromMs) {
			listOf(this, aggregate).map { candidate -> CanonicalRetentionBounds.wifiCoverage(
				coverageEndWallTimeMs = candidate.observedWallTimeMs,
				wallTimeUncertaintyMs = candidate.wallTimeUncertaintyMs,
				coverageStartElapsedRealtimeNanos = candidate.coverageIntervalStartNanos,
				coverageEndElapsedRealtimeNanos = candidate.coverageIntervalEndNanos,
			) }
		} ?: found(request)
	}

	private fun retainedRadioBoundsRejection(
		retainedFromMs: Long?,
		lowerBounds: () -> List<Long?>,
	): SourceFactLookupResult? {
		if (retainedFromMs == null) {
			return null
		}
		val bounds = lowerBounds()
		if (bounds.any { it == null }) {
			return SourceFactLookupResult.Failed(SourceProofReason.INTEGRITY_FAILURE)
		}
		return SourceFactLookupResult.Deleted(SourceProofReason.RETENTION_EXPIRED)
			.takeIf { bounds.any { requireNotNull(it) < retainedFromMs } }
	}

	private suspend fun lookupCell(
		request: SourceFactLookupRequest,
		retainedFromMs: Long?,
	): SourceFactLookupResult = capturedRadioRetentionLookup(request, retainedFromMs)
		?: lookupRetainedCell(request, retainedFromMs)

	private suspend fun lookupRetainedCell(
		request: SourceFactLookupRequest,
		retainedFromMs: Long?,
	): SourceFactLookupResult {
		val lineage = request.materialized.lineage
		val writer = requireNotNull(lineage.writer)
		val fact = database.cellCapturedFactDao().revision(
			writer.projectionId, writer.projectionVersion, request.materialized.requireFactIdentity(),
			requireNotNull(lineage.semanticRevision),
		) ?: return SourceFactLookupResult.NotFound()
		val wal = database.sourceEventWalDao().getByEventId(request.key.evidenceIdentity.value)
			?: return SourceFactLookupResult.Unavailable(SourceProofReason.IMMUTABLE_AUTHORITY_MISSING)
		val cursor = database.cellCapturedFactDao().cursor(writer.projectionId, writer.projectionVersion, fact.logicalFactId)
			?: return projectionFailureOrNotFound(writer, lineage.sourceAdmissionOrdinal, SourceProofReason.CURSOR_BEHIND)
		return fact.lookupCellCarrier(request, retainedFromMs, wal, cursor)
	}

	private suspend fun CellCapturedFactRevisionEntity.lookupCellCarrier(
		request: SourceFactLookupRequest,
		retainedFromMs: Long?,
		wal: SourceEventWalEntity,
		cursor: CellCapturedFactCursorEntity,
	): SourceFactLookupResult {
		val walAuthentication = authenticateCellFactCarrier(this, wal)
			?: return SourceFactLookupResult.Failed(SourceProofReason.INTEGRITY_FAILURE)
		return when (factKind) {
			CellCapturedFactRevisionEntity.FACT_KIND_AGGREGATE ->
				qualifyCellLookup(request, retainedFromMs, walAuthentication, cursor, this, walAuthentication)
			CellCapturedFactRevisionEntity.FACT_KIND_COVERAGE_ONLY ->
				lookupCellAggregate(request, retainedFromMs, walAuthentication, cursor)
			else -> SourceFactLookupResult.Failed(SourceProofReason.INTEGRITY_FAILURE)
		}
	}

	private suspend fun CellCapturedFactRevisionEntity.lookupCellAggregate(
		request: SourceFactLookupRequest,
		retainedFromMs: Long?,
		walAuthentication: CellWalCarrierAuthentication.Authenticated,
		cursor: CellCapturedFactCursorEntity,
	): SourceFactLookupResult {
		val ownerId = aggregateOwnerLogicalFactId
			?: return SourceFactLookupResult.Failed(SourceProofReason.INTEGRITY_FAILURE)
		val ownerRevision = aggregateOwnerSemanticRevision
			?: return SourceFactLookupResult.Failed(SourceProofReason.INTEGRITY_FAILURE)
		val owner = database.cellCapturedFactDao().revision(
			writerProjectionId, writerProjectionVersion, ownerId, ownerRevision,
		) ?: return SourceFactLookupResult.Failed(SourceProofReason.INTEGRITY_FAILURE)
		return lookupCellOwnerCursor(request, retainedFromMs, walAuthentication, cursor, ownerId, owner)
	}

	private suspend fun CellCapturedFactRevisionEntity.lookupCellOwnerCursor(
		request: SourceFactLookupRequest,
		retainedFromMs: Long?,
		walAuthentication: CellWalCarrierAuthentication.Authenticated,
		cursor: CellCapturedFactCursorEntity,
		ownerId: String,
		owner: CellCapturedFactRevisionEntity,
	): SourceFactLookupResult {
		val ownerCursor = database.cellCapturedFactDao().cursor(writerProjectionId, writerProjectionVersion, ownerId)
			?: return SourceFactLookupResult.Unavailable(SourceProofReason.IMMUTABLE_AUTHORITY_MISSING)
		if (!CellCapturedFactRevisionIntegrity.coverageOnlyOwnerCursorAuthenticates(this, owner, ownerCursor)) {
			return SourceFactLookupResult.NotFound(SourceProofReason.STALE)
		}
		val ownerWal = database.sourceEventWalDao().getByEventId(owner.sourceEventId)
			?: return SourceFactLookupResult.Unavailable(SourceProofReason.IMMUTABLE_AUTHORITY_MISSING)
		return lookupCellOwnerCarrier(request, retainedFromMs, walAuthentication, cursor, owner, ownerWal)
	}

	private suspend fun CellCapturedFactRevisionEntity.lookupCellOwnerCarrier(
		request: SourceFactLookupRequest,
		retainedFromMs: Long?,
		walAuthentication: CellWalCarrierAuthentication.Authenticated,
		cursor: CellCapturedFactCursorEntity,
		owner: CellCapturedFactRevisionEntity,
		ownerWal: SourceEventWalEntity,
	): SourceFactLookupResult {
		val ownerAuthentication = authenticateCellFactCarrier(owner, ownerWal)
			?: return SourceFactLookupResult.Failed(SourceProofReason.INTEGRITY_FAILURE)
		return qualifyCellLookup(request, retainedFromMs, walAuthentication, cursor, owner, ownerAuthentication)
	}

	private suspend fun CellCapturedFactRevisionEntity.qualifyCellLookup(
		request: SourceFactLookupRequest,
		retainedFromMs: Long?,
		walAuthentication: CellWalCarrierAuthentication.Authenticated,
		cursor: CellCapturedFactCursorEntity,
		aggregate: CellCapturedFactRevisionEntity,
		aggregateAuthentication: CellWalCarrierAuthentication.Authenticated,
	): SourceFactLookupResult {
		if (!matchesCellLookupWriter(request) ||
			!CellCapturedFactRevisionIntegrity.cursorAuthenticatesCurrent(cursor, this)
		) {
			return SourceFactLookupResult.Failed(SourceProofReason.INTEGRITY_FAILURE)
		}
		if (!aggregate.matchesCellLookupWriter(request) || !authenticatesCellAggregate(aggregate, walAuthentication)) {
			return SourceFactLookupResult.Failed(SourceProofReason.INTEGRITY_FAILURE)
		}
		return qualifyCellReuse(request, retainedFromMs, walAuthentication, cursor, aggregate, aggregateAuthentication)
	}

	private fun CellCapturedFactRevisionEntity.matchesCellLookupWriter(request: SourceFactLookupRequest): Boolean {
		val writer = requireNotNull(request.materialized.lineage.writer)
		return CellCapturedFactRevisionIntegrity.hasValidEffectChecksum(this) &&
			writerBindingGeneration == writer.bindingGeneration && writerOwnerGeneration == writer.ownerGeneration
	}

	private fun CellCapturedFactRevisionEntity.authenticatesCellAggregate(
		aggregate: CellCapturedFactRevisionEntity,
		walAuthentication: CellWalCarrierAuthentication.Authenticated,
	): Boolean = if (factKind == CellCapturedFactRevisionEntity.FACT_KIND_COVERAGE_ONLY) {
		CellWalCarrierAuthenticator.authenticatesAggregate(aggregate, walAuthentication.carrier, this)
	} else {
		CellWalCarrierAuthenticator.authenticatesAggregate(aggregate, walAuthentication.carrier)
	}

	private suspend fun CellCapturedFactRevisionEntity.qualifyCellReuse(
		request: SourceFactLookupRequest,
		retainedFromMs: Long?,
		walAuthentication: CellWalCarrierAuthentication.Authenticated,
		cursor: CellCapturedFactCursorEntity,
		aggregate: CellCapturedFactRevisionEntity,
		aggregateAuthentication: CellWalCarrierAuthentication.Authenticated,
	): SourceFactLookupResult {
		if (factKind == CellCapturedFactRevisionEntity.FACT_KIND_COVERAGE_ONLY &&
			!cellAggregateReuseCompatible(this, aggregate)
		) {
			return SourceFactLookupResult.Failed(SourceProofReason.INTEGRITY_FAILURE)
		}
		if (cursor.cursorRevision != request.materialized.lineage.cursorRevision || !matches(request)) {
			return SourceFactLookupResult.Failed(SourceProofReason.INTEGRITY_FAILURE)
		}
		return completeCellLookup(request, retainedFromMs, walAuthentication, aggregate, aggregateAuthentication)
	}

	private fun CellCapturedFactRevisionEntity.completeCellLookup(
		request: SourceFactLookupRequest,
		retainedFromMs: Long?,
		walAuthentication: CellWalCarrierAuthentication.Authenticated,
		aggregate: CellCapturedFactRevisionEntity,
		aggregateAuthentication: CellWalCarrierAuthentication.Authenticated,
	): SourceFactLookupResult {
		if (request.materialized.coverage != providerCoverage(coverageIntervalStartNanos, coverageIntervalEndNanos) ||
			request.materialized.completeness != childCompleteness.toCompleteness()
		) {
			return SourceFactLookupResult.Failed(SourceProofReason.FACT_IDENTITY_MISMATCH)
		}
		return retainedRadioBoundsRejection(retainedFromMs) {
			listOf(CellWalCarrierAuthenticator.retentionLowerBound(this, walAuthentication.carrier),
				CellWalCarrierAuthenticator.retentionLowerBound(aggregate, aggregateAuthentication.carrier))
		} ?: found(request)
	}

	private suspend fun cellAggregateReuseCompatible(
		dependent: CellCapturedFactRevisionEntity,
		owner: CellCapturedFactRevisionEntity,
	): Boolean {
		val dependentAuthority = cellAggregateReuseAuthority(dependent) ?: return false
		val ownerAuthority = cellAggregateReuseAuthority(owner) ?: return false
		return dependentAuthority.canReuseAggregateFrom(ownerAuthority)
	}

	private suspend fun cellAggregateReuseAuthority(
		fact: CellCapturedFactRevisionEntity,
	): CellAggregateReuseAuthority? {
		val manifest = database.sourceSessionDao().manifest(
			fact.logicalTrackingId,
			fact.manifestRevision,
		) ?: return null
		val sources = database.sourceSessionDao().manifestSources(
			fact.logicalTrackingId,
			fact.manifestRevision,
		)
		if (manifest.serviceRunId != fact.serviceRunId ||
			!SessionManifestIntegrity.verify(manifest, sources)
		) {
			return null
		}
		val capturedSources = sources.filter { source ->
			source.purpose == SessionManifestPurposeCode.SESSION_CAPTURE &&
				source.persistenceEligible
		}.mapTo(linkedSetOf()) { it.sourceKind }
		val controlSources = sources.filter { source ->
			source.purpose == SessionManifestPurposeCode.CONTROL
		}.mapTo(linkedSetOf()) { it.sourceKind }
		return runCatching {
			fact.aggregateReuseAuthority(capturedSources, controlSources)
		}.getOrNull()
	}


	// Ordered carrier, legacy-format, purpose, exact-member and child-index exits preserve precise qualification.
	@Suppress("ReturnCount")
	private suspend fun authenticateWifiFactCarrier(
		fact: WifiCapturedFactRevisionEntity,
		wal: com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity,
	): WifiWalCarrierAuthentication.Authenticated? {
		val children = database.radioObservationChildDao().wifiSessionChildren(
			fact.writerProjectionId, fact.writerProjectionVersion,
			fact.logicalFactId, fact.semanticRevision, 65,
		)
		val stored = WifiWalCarrierAuthenticator.authenticate(fact, wal, children)
			as? WifiWalCarrierAuthentication.Authenticated ?: return null
		if (wal.payloadVersion != com.adsamcik.tracker.shared.base.database.data.WIFI_WAL_PAYLOAD_VERSION) {
			return stored
		}
		val items = database.qualifyWifiPurposeItems(wal, stored.carrier.payload,
			fact.providerAcceptanceStartNanos, fact.providerAcceptanceEndNanos) ?: return null
		val member = items.authorizationTimeline.flatMap { it.authorizedMembers }.singleOrNull {
			it.matchesWifiCaptureFact(fact)
		} ?: return null
		val indexes = items.forDemand(requireNotNull(member.demandId)).toSet()
		if (indexes.isEmpty() || children.any { it.itemIndex !in indexes }) {
			return null
		}
		return stored
	}

	// One immutable historical member must match the complete saved Wi-Fi fact identity.
	@Suppress("CyclomaticComplexMethod")
	private fun SourceAuthorizationEntity.matchesWifiCaptureFact(fact: WifiCapturedFactRevisionEntity): Boolean =
		this.purpose == SourceBrokerPurpose.SESSION_CAPTURE && this.persistenceEligible &&
			this.authorizationRevision == fact.authorizationRevision &&
			this.authorizationFingerprint == fact.authorizationFingerprint &&
			this.purposeEligibilityMask == fact.purposeEligibilityMask &&
			this.logicalTrackingId == fact.logicalTrackingId && this.serviceRunId == fact.serviceRunId &&
			this.manifestRevision == fact.manifestRevision &&
			this.lifecycleLeaseGeneration == fact.lifecycleLeaseGeneration &&
			this.sourcePolicyRevision == fact.sourcePolicyRevision && this.consentEpoch == fact.captureConsentEpoch

	// Keep legacy, raw carrier, child, purpose and exact child-index qualification exits in order.
	@Suppress("ReturnCount")
	private suspend fun authenticateCellFactCarrier(
		fact: CellCapturedFactRevisionEntity,
		wal: com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity,
	): com.adsamcik.tracker.shared.base.database.data.CellWalCarrierAuthentication.Authenticated? {
		if (wal.payloadVersion != CELL_WAL_PAYLOAD_VERSION) {
			return CellWalCarrierAuthenticator.authenticate(fact, wal)
				as? com.adsamcik.tracker.shared.base.database.data.CellWalCarrierAuthentication.Authenticated
		}
		val raw = CellWalCarrierAuthenticator.authenticate(wal)
			as? com.adsamcik.tracker.shared.base.database.data.CellWalCarrierAuthentication.Authenticated ?: return null
		val children = database.radioObservationChildDao().cellSessionChildren(
			fact.writerProjectionId, fact.writerProjectionVersion,
			fact.logicalFactId, fact.semanticRevision, raw.carrier.payload.observations.size + 1,
		)
		val stored = CellWalCarrierAuthenticator.authenticate(fact, wal, children)
			as? com.adsamcik.tracker.shared.base.database.data.CellWalCarrierAuthentication.Authenticated ?: return null
		val purpose = database.qualifyCellCapturePurposeItems(wal, raw.carrier.payload, fact)
			as? CellPurposeItems.Qualified ?: return null
		val exact = CellWalCarrierAuthenticator.authenticate(fact, wal, purpose.indexes.toSet())
			as? com.adsamcik.tracker.shared.base.database.data.CellWalCarrierAuthentication.Authenticated ?: return null
		return stored.takeIf { it.carrier.childQualification == exact.carrier.childQualification }
	}

	private suspend fun capturedRadioRetentionLookup(
		request: SourceFactLookupRequest,
		retainedFromMs: Long?,
	): SourceFactLookupResult? {
		if (request.key.source !in setOf(TrackingSource.WIFI, TrackingSource.CELL)) {
			return null
		}
		val lineage = request.materialized.lineage
		val writer = requireNotNull(lineage.writer)
		val logicalFactId = request.materialized.requireFactIdentity()
		val dao = database.capturedRadioFactRetentionProofDao()
		val byFact = dao.byFact(
			request.key.source.stableCode(),
			writer.projectionId,
			writer.projectionVersion,
			logicalFactId,
		) ?: return null
		if (dao.hasConflictingEventIdentity(
			request.key.source.stableCode(), request.key.evidenceIdentity.value,
			lineage.sourceAdmissionOrdinal, request.key.collectedDataEpoch,
			byFact.walIntegrityIdentity,
		)) {
			return SourceFactLookupResult.Failed(SourceProofReason.INTEGRITY_FAILURE)
		}
		val scope = request.key.scope as SourceProofScope.Run
		return if (byFact.authenticatesRequest(
				expectedSourceKind = request.key.source.stableCode(),
				expectedWriterProjectionId = writer.projectionId,
				expectedWriterProjectionVersion = writer.projectionVersion,
				expectedWriterBindingGeneration = writer.bindingGeneration,
				expectedWriterOwnerGeneration = writer.ownerGeneration,
				expectedLogicalFactId = logicalFactId,
				expectedSemanticRevision = requireNotNull(lineage.semanticRevision),
				expectedCursorRevision = requireNotNull(lineage.cursorRevision),
				expectedSourceEventId = request.key.evidenceIdentity.value,
				expectedSourceAdmissionOrdinal = lineage.sourceAdmissionOrdinal,
				expectedSourcePolicyRevision = request.key.sourcePolicyRevision,
				expectedCaptureConsentEpoch = request.key.captureConsentEpoch,
				expectedCollectedDataEpoch = request.key.collectedDataEpoch,
				expectedScopeDeletionGeneration = 0L,
				expectedLogicalRunScopeDigest =
					SourceDeletionFenceEntity.logicalServiceRunIdentity(
						request.key.source.stableCode(),
						SourceBrokerPurpose.SESSION_CAPTURE,
						scope.logicalTrackingId,
						scope.serviceRunId,
					),
				currentRetainedFromMs = retainedFromMs,
			)
		) {
			SourceFactLookupResult.Deleted(SourceProofReason.RETENTION_EXPIRED)
		} else {
			SourceFactLookupResult.Failed(SourceProofReason.INTEGRITY_FAILURE)
		}
	}

	private fun found(request: SourceFactLookupRequest): SourceFactLookupResult.Found =
		SourceFactLookupResult.Found(
			QueryableProofState(
				lineage = request.materialized.lineage,
				coverage = request.materialized.coverage,
				completeness = request.materialized.completeness,
				readSnapshot = TrackingHistoryReadSnapshot(
					request.key.collectedDataEpoch,
					request.materialized.lineage.sourceEvidenceRevision,
				),
			),
		)

	private fun identityMismatch() =
		SourceFactLookupResult.Failed(SourceProofReason.FACT_IDENTITY_MISMATCH)

	private suspend fun projectionFailureOrNotFound(
		writer: SourceProofWriterIdentity,
		admissionOrdinal: Long,
		fallbackReason: SourceProofReason,
	): SourceFactLookupResult {
		val failure = database.sourceProjectionStateDao().failure(
			writer.projectionId,
			writer.projectionVersion,
			admissionOrdinal,
		)
		return when {
			failure == null -> SourceFactLookupResult.NotFound(fallbackReason)
			failure.terminal -> SourceFactLookupResult.Failed(
				SourceProofReason.PROJECTION_TERMINAL_FAILURE,
			)
			else -> SourceFactLookupResult.NotFound(
				SourceProofReason.PROJECTION_RETRYABLE_FAILURE,
			)
		}
	}

	private fun MaterializedProofState.requireFactIdentity(): String =
		requireNotNull(lineage.factIdentity).value

	private fun PressureFactRetentionResolution.Authenticated.authenticatesRetainedRequest(
		request: SourceFactLookupRequest,
		historicalLane: SourceProductProjectionLaneEntity,
	): Boolean {
		val lineage = request.materialized.lineage
		val expectedSelectedLineage = SourceProofSelectedEvidenceLineage(
			semanticRevision = revision.semanticRevision,
			supersededByCurrentSemanticRevision = if (
				revision.semanticRevision < this.lineage.currentSemanticRevision
			) {
				this.lineage.currentSemanticRevision
			} else {
				null
			},
		)
		val retainedCurrentAdmission = this.lineage.currentSourceAdmissionOrdinal
		return lineage.semanticRevision == this.lineage.currentSemanticRevision &&
			lineage.selectedEvidenceLineage == expectedSelectedLineage &&
			retainedCurrentAdmission >= historicalLane.activationOrdinal &&
			retainedCurrentAdmission <= requireNotNull(lineage.cursorRevision) &&
			retainedCurrentAdmission <= historicalLane.contiguousAdmissionOrdinal &&
			historicalLane.captureAdmissionCutoffOrdinal?.let { cutoff ->
				retainedCurrentAdmission <= cutoff
			} != false
	}

	private suspend fun loadActivityWalCarriers(
		evidence: List<
			com.adsamcik.tracker.shared.base.database.data.ActivityCapturedEvidenceEntity>,
	): Map<String, com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity> {
		val rows = mutableListOf<
			com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity>()
		for (eventIds in evidence.map { it.sourceEventId }.distinct()
			.chunked(ACTIVITY_WAL_ID_BATCH_SIZE)
		) {
			rows += database.sourceEventWalDao().boundedPayloadsByEventIds(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_ACTIVITY,
				eventIds = eventIds,
				maximumPayloadBytes = MAX_ACTIVITY_WAL_PAYLOAD_BYTES,
				limit = eventIds.size,
			)
		}
		return rows.associateBy { it.eventId }
	}

	private fun com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
		.stepsWalOwnerKey() = StepsCountDomainOwnerLookupKey(
			ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_WAL,
			ownerIdentity = StepsCountDomainReceiptIntegrity.sessionWalOwnerIdentity(
				admissionOrdinal,
				eventId,
			),
			ownerRevision = 1L,
		)

	private fun StepFactRevisionEntity.stepsFactOwnerKey() = StepsCountDomainOwnerLookupKey(
		ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_FACT,
		ownerIdentity = StepsCountDomainReceiptIntegrity.sessionFactOwnerIdentity(
			writerProjectionId,
			writerProjectionVersion,
			logicalFactId,
		),
		ownerRevision = semanticRevision,
	)

	private fun com.adsamcik.tracker.shared.base.database.data.ActivityCapturedWindowRevisionEntity.matches(
		request: SourceFactLookupRequest,
	): Boolean = sourcePolicyRevision == request.key.sourcePolicyRevision &&
		captureConsentEpoch == request.key.captureConsentEpoch &&
		collectedDataEpoch == request.key.collectedDataEpoch &&
		runScopeMatches(request, logicalTrackingId, serviceRunId)

	private fun StepFactRevisionEntity.matches(request: SourceFactLookupRequest): Boolean =
		sourcePolicyRevision == request.key.sourcePolicyRevision &&
			captureConsentEpoch == request.key.captureConsentEpoch &&
			collectedDataEpoch == request.key.collectedDataEpoch &&
			runScopeMatches(request, logicalTrackingId, serviceRunId)

	private fun PressureFactRevisionEntity.matches(request: SourceFactLookupRequest): Boolean =
		sourcePolicyRevision == request.key.sourcePolicyRevision &&
			captureConsentEpoch == request.key.captureConsentEpoch &&
			collectedDataEpoch == request.key.collectedDataEpoch &&
			runScopeMatches(request, logicalTrackingId, serviceRunId)

	private fun WifiCapturedFactRevisionEntity.matches(request: SourceFactLookupRequest): Boolean =
		sourceEventId == request.key.evidenceIdentity.value &&
			sourcePolicyRevision == request.key.sourcePolicyRevision &&
			captureConsentEpoch == request.key.captureConsentEpoch &&
			collectedDataEpoch == request.key.collectedDataEpoch &&
			runScopeMatches(request, logicalTrackingId, serviceRunId)

	private fun CellCapturedFactRevisionEntity.matches(request: SourceFactLookupRequest): Boolean =
		sourceEventId == request.key.evidenceIdentity.value &&
			sourcePolicyRevision == request.key.sourcePolicyRevision &&
			captureConsentEpoch == request.key.captureConsentEpoch &&
			collectedDataEpoch == request.key.collectedDataEpoch &&
			runScopeMatches(request, logicalTrackingId, serviceRunId)

	private fun runScopeMatches(
		request: SourceFactLookupRequest,
		logicalTrackingId: String?,
		serviceRunId: String?,
	): Boolean {
		val scope = request.key.scope as? SourceProofScope.Run ?: return false
		return logicalTrackingId == scope.logicalTrackingId && serviceRunId == scope.serviceRunId
	}

	private suspend fun isRunDeleted(request: SourceFactLookupRequest): Boolean {
		val scope = request.key.scope as? SourceProofScope.Run ?: return false
		return database.sourceDeletionFenceDao().contains(
			sourceKind = request.key.source.stableCode(),
			purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
			scopeKind = SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
			scopeIdentityDigest = SourceDeletionFenceEntity.logicalServiceRunIdentity(
				sourceKind = request.key.source.stableCode(),
				purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
				logicalTrackingId = scope.logicalTrackingId,
				serviceRunId = scope.serviceRunId,
			),
		)
	}

	private fun TrackingSource.stableCode(): Int = when (this) {
		TrackingSource.LOCATION -> SourceDestinationOwnerEntity.SOURCE_LOCATION
		TrackingSource.ACTIVITY -> SourceDestinationOwnerEntity.SOURCE_ACTIVITY
		TrackingSource.STEPS -> SourceDestinationOwnerEntity.SOURCE_STEPS
		TrackingSource.PRESSURE -> SourceDestinationOwnerEntity.SOURCE_PRESSURE
		TrackingSource.WIFI -> SourceDestinationOwnerEntity.SOURCE_WIFI
		TrackingSource.CELL -> SourceDestinationOwnerEntity.SOURCE_CELL
	}

	private fun String.toCompleteness(): SourceProofCompleteness = when (this) {
		"COMPLETE" -> SourceProofCompleteness.COMPLETE
		"PARTIAL", "NONE" -> SourceProofCompleteness.PARTIAL
		else -> SourceProofCompleteness.UNKNOWN
	}

	private fun providerCoverage(
		startNanos: Long,
		endNanos: Long,
	): SourceProofCoverage = if (endNanos > startNanos) {
		SourceProofCoverage.ProviderInterval(startNanos, endNanos)
	} else {
		SourceProofCoverage.ProviderPoint(startNanos)
	}

	private fun SourceProofWriterIdentity.writerMismatch(
		source: TrackingSource,
	): SourceProofReason? {
		val expectedProjection = when (source) {
			TrackingSource.LOCATION ->
				SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_PROJECTION_ID to
					SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_PROJECTION_VERSION
			TrackingSource.ACTIVITY ->
				SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_ID to
					SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_VERSION
			TrackingSource.STEPS ->
				SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID to
					SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_VERSION
			TrackingSource.PRESSURE ->
				SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID to
					SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_VERSION
			TrackingSource.WIFI ->
				SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_ID to
					SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_VERSION
			TrackingSource.CELL ->
				SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID to
					SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION
		}
		if (projectionId != expectedProjection.first ||
			projectionVersion != expectedProjection.second
		) {
			return SourceProofReason.WRITER_MISMATCH
		}
		return null
	}

	private companion object {
		const val MAX_WIFI_CHILDREN = 64
		const val MAX_ACTIVITY_REVISIONS = 4_096
		const val MAX_ACTIVITY_CHILD_ROWS = 4_096
		const val ACTIVITY_WAL_ID_BATCH_SIZE = 400
		const val PRESSURE_WAL_ID_BATCH_SIZE = 400
		const val MAX_PRESSURE_PROOF_REVISIONS = 8_192
	}
}

internal fun PressureFactLineageProof.Authenticated.selectedPressureProofCoverage():
	SourceProofCoverage = if (
	selected.windowEndElapsedRealtimeNanos > selected.windowStartElapsedRealtimeNanos
) {
	SourceProofCoverage.ProviderInterval(
		selected.windowStartElapsedRealtimeNanos,
		selected.windowEndElapsedRealtimeNanos,
	)
} else {
	SourceProofCoverage.ProviderPoint(selected.windowStartElapsedRealtimeNanos)
}

internal fun PressureFactLineageProof.Authenticated.selectedPressureProofCompleteness():
	SourceProofCompleteness = when (selected.qualification) {
		PressureFactRevisionEntity.QUALIFICATION_COMPLETE -> SourceProofCompleteness.COMPLETE
		PressureFactRevisionEntity.QUALIFICATION_PARTIAL -> SourceProofCompleteness.PARTIAL
		else -> SourceProofCompleteness.UNKNOWN
	}
