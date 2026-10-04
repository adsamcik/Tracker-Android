package com.adsamcik.tracker.stats.data.repository

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.CellPurposeItems
import com.adsamcik.tracker.shared.base.database.qualifyCellCapturePurposeItems
import com.adsamcik.tracker.shared.base.database.data.CELL_WAL_PAYLOAD_VERSION
import com.adsamcik.tracker.shared.base.database.PressureFactLineageProof
import com.adsamcik.tracker.shared.base.database.PressureFactProofAuthenticator
import com.adsamcik.tracker.shared.base.database.PressureFactProofExpectation
import com.adsamcik.tracker.shared.base.database.PressureFactProofFailure
import com.adsamcik.tracker.shared.base.database.PressureFactRetentionResolution
import com.adsamcik.tracker.shared.base.database.StepsCountDomainOwnerLookupKey
import com.adsamcik.tracker.shared.base.database.StepsCountDomainOwnerRead
import com.adsamcik.tracker.shared.base.database.StepsCountDomainStore
import com.adsamcik.tracker.shared.base.database.loadPressureFactProofSnapshot
import com.adsamcik.tracker.shared.base.database.resolvePressureRetentionByFact
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedFactIntegrity
import com.adsamcik.tracker.shared.base.database.data.ActivityWalCarrierAuthenticator
import com.adsamcik.tracker.shared.base.database.data.CanonicalRetentionBounds
import com.adsamcik.tracker.shared.base.database.data.CellAggregateReuseAuthority
import com.adsamcik.tracker.shared.base.database.data.CellCapturedFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedFactRevisionIntegrity
import com.adsamcik.tracker.shared.base.database.data.CellWalCarrierAuthenticator
import com.adsamcik.tracker.shared.base.database.data.MAX_WIFI_WAL_PAYLOAD_BYTES
import com.adsamcik.tracker.shared.base.database.data.LocationObservationDecision
import com.adsamcik.tracker.shared.base.database.data.MAX_ACTIVITY_WAL_PAYLOAD_BYTES
import com.adsamcik.tracker.shared.base.database.data.MAX_PRESSURE_WAL_PAYLOAD_BYTES
import com.adsamcik.tracker.shared.base.database.data.MAX_STEPS_WAL_PAYLOAD_BYTES
import com.adsamcik.tracker.shared.base.database.data.PressureFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
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
import com.adsamcik.tracker.shared.base.database.data.authenticatedProtectedLocationCanonicalCurationState
import com.adsamcik.tracker.shared.base.database.data.hasValidCanonicalHistoryShape
import com.adsamcik.tracker.shared.base.database.data.protectedLocationCanonicalProductIdentity
import com.adsamcik.tracker.shared.base.database.data.protectedLocationCanonicalReceiptStateKey
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
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryRepository
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryReadSnapshot
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
@Suppress("TooManyFunctions")
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
				if (failure is CancellationException) throw failure
				SourceFactLookupResult.Failed(SourceProofReason.REPOSITORY_FAILURE)
			}
		}

	internal suspend fun lookupInTransaction(
		request: SourceFactLookupRequest,
	): SourceFactLookupResult {
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
		val state = database.sourceEvidenceStateDao().get()
			?: return SourceFactLookupResult.Unavailable(
				SourceProofReason.IMMUTABLE_AUTHORITY_MISSING,
			)
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
		if (!historicalLane.hasValidCanonicalHistoryShape() ||
			request.materialized.lineage.sourceAdmissionOrdinal < historicalLane.activationOrdinal ||
			historicalLane.contiguousAdmissionOrdinal <
			request.materialized.lineage.sourceAdmissionOrdinal ||
			historicalLane.captureAdmissionCutoffOrdinal?.let { cutoff ->
				request.materialized.lineage.sourceAdmissionOrdinal > cutoff
			} == true
		) {
			return SourceFactLookupResult.Failed(SourceProofReason.INTEGRITY_FAILURE)
		}
		val result = when (request.key.source) {
			TrackingSource.LOCATION -> lookupLocation(request, state.retainedFromMs)
			TrackingSource.ACTIVITY -> lookupActivity(request, state.retainedFromMs)
			TrackingSource.STEPS -> lookupSteps(request, state.retainedFromMs)
			TrackingSource.PRESSURE -> lookupPressure(
				request,
				state.retainedFromMs,
				historicalLane,
			)
			TrackingSource.WIFI -> lookupWifi(request, state.retainedFromMs)
			TrackingSource.CELL -> lookupCell(request, state.retainedFromMs)
		}
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

	private suspend fun lookupLocation(
		request: SourceFactLookupRequest,
		retainedFromMs: Long?,
	): SourceFactLookupResult {
		val lineage = request.materialized.lineage
		val writer = requireNotNull(request.materialized.lineage.writer)
		val eventId = request.materialized.requireFactIdentity()
		if (eventId != request.key.evidenceIdentity.value) return identityMismatch()
		val scope = request.key.scope as SourceProofScope.Run
		val wal = database.sourceEventWalDao().getByEventId(eventId)
			?: return SourceFactLookupResult.Unavailable(
				SourceProofReason.IMMUTABLE_AUTHORITY_MISSING,
			)
		if (!wal.hasQualifiedIntegrity() ||
			wal.admissionOrdinal != lineage.sourceAdmissionOrdinal ||
			wal.sourceKind != SourceDestinationOwnerEntity.SOURCE_LOCATION ||
			wal.logicalTrackingId != scope.logicalTrackingId ||
			wal.serviceRunId != scope.serviceRunId ||
			wal.sourcePolicyRevision != request.key.sourcePolicyRevision ||
			wal.captureConsentEpoch != request.key.captureConsentEpoch ||
			wal.capturedCollectedDataEpoch != request.key.collectedDataEpoch
		) {
			return SourceFactLookupResult.Failed(SourceProofReason.INTEGRITY_FAILURE)
		}
		if (retainedFromMs != null) {
			val lowerBound = wal.wallTimeMs?.let { wallTimeMs ->
				wal.wallTimeUncertaintyMs?.let { uncertaintyMs ->
					CanonicalRetentionBounds.location(wallTimeMs, uncertaintyMs)
				}
			} ?: return SourceFactLookupResult.Failed(SourceProofReason.INTEGRITY_FAILURE)
			if (lowerBound < retainedFromMs) {
				return SourceFactLookupResult.Deleted(SourceProofReason.RETENTION_EXPIRED)
			}
		}
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
		val canonicalSignalId = protectedLocationCanonicalProductIdentity(eventId)
		if (decision.reason != null ||
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
		) {
			return SourceFactLookupResult.Failed(SourceProofReason.INTEGRITY_FAILURE)
		}
		return found(request)
	}

	@Suppress("ComplexCondition", "LongMethod")
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
		if (revision.writerBindingGeneration != writer.bindingGeneration ||
			revision.writerOwnerGeneration != writer.ownerGeneration ||
			cursor.logicalTrackingId != revision.logicalTrackingId ||
			cursor.serviceRunId != revision.serviceRunId ||
			cursor.sessionSegmentId != revision.sessionSegmentId ||
			cursor.writerOwnerGeneration != revision.writerOwnerGeneration ||
			cursor.latestSemanticRevision != revision.semanticRevision ||
			cursor.latestMutationId != revision.mutationId ||
			cursor.latestEffectChecksum != revision.effectChecksum ||
			cursor.cursorRevision != lineage.cursorRevision ||
			cursor.collectedDataEpoch != revision.collectedDataEpoch ||
			cursor.updatedAtMs != revision.appliedAtMs ||
			!revision.matches(request)
		) {
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
		val walByEventId = loadActivityWalCarriers(lineageEvidence.map { it.second })
		if (walByEventId.size != lineageEvidence.map { it.second.sourceEventId }.distinct().size ||
			lineageEvidence.any { (lineageRevision, row) ->
				walByEventId[row.sourceEventId]?.let { wal ->
					ActivityWalCarrierAuthenticator.authenticates(lineageRevision, row, wal)
				} != true
			}
		) {
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
			request.materialized.completeness != revision.coverage.toCompleteness()
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

	private suspend fun lookupSteps(
		request: SourceFactLookupRequest,
		retainedFromMs: Long?,
	): SourceFactLookupResult {
		val lineage = request.materialized.lineage
		val writer = requireNotNull(lineage.writer)
		val fact = database.stepFactRevisionDao().latest(
			writer.projectionId,
			writer.projectionVersion,
			request.materialized.requireFactIdentity(),
		) ?: return SourceFactLookupResult.NotFound()
		if (fact.semanticRevision != lineage.semanticRevision ||
			fact.sourceEventId != request.key.evidenceIdentity.value ||
			fact.sourceAdmissionOrdinal != lineage.sourceAdmissionOrdinal ||
			fact.writerBindingGeneration != writer.bindingGeneration ||
			!StepFactRevisionIntegrity.hasValidCanonicalLiveWalFact(fact) ||
			!fact.matches(request)
		) {
			return SourceFactLookupResult.Failed(SourceProofReason.INTEGRITY_FAILURE)
		}
		val wal = database.sourceEventWalDao().boundedPayloadByEventId(
			request.key.evidenceIdentity.value,
			MAX_STEPS_WAL_PAYLOAD_BYTES,
		) ?: return SourceFactLookupResult.Unavailable(
			SourceProofReason.IMMUTABLE_AUTHORITY_MISSING,
		)
		val ownerKeys = listOf(wal.stepsWalOwnerKey(), fact.stepsFactOwnerKey())
		val owners = when (val read = StepsCountDomainStore(database).readOwners(ownerKeys)) {
			is StepsCountDomainOwnerRead.Ready -> read.owners
			StepsCountDomainOwnerRead.SchemaUnavailable -> return SourceFactLookupResult.Unavailable(
				SourceProofReason.IMMUTABLE_AUTHORITY_MISSING,
			)
			StepsCountDomainOwnerRead.Overflow,
			StepsCountDomainOwnerRead.Unverifiable,
			-> return SourceFactLookupResult.Failed(SourceProofReason.INTEGRITY_FAILURE)
		}
		val carrier = StepsWalCarrierAuthenticator.authenticate(
			fact,
			wal,
			owners[ownerKeys[0]],
			owners[ownerKeys[1]],
		)
		if (carrier !is StepsWalCarrierAuthentication.Authenticated ||
			!carrier.carrier.countDomainProven ||
			!carrier.carrier.isPositiveCoveredWindow
		) {
			return SourceFactLookupResult.Failed(SourceProofReason.INTEGRITY_FAILURE)
		}
		val binding = database.sourceSessionDao().manifestSources(
			requireNotNull(fact.logicalTrackingId),
			requireNotNull(fact.manifestRevision),
		).singleOrNull { source ->
			source.sourceKind == SourceDestinationOwnerEntity.SOURCE_STEPS &&
				source.purpose == SessionManifestPurposeCode.SESSION_CAPTURE
		}
		if (binding == null ||
			!binding.persistenceEligible ||
			binding.consentEpoch != fact.captureConsentEpoch ||
			binding.outputDestination != SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS ||
			binding.writerOwner != SourceDestinationOwnerEntity.OWNER_STEPS_SESSION_FACTS ||
			binding.writerOwnerGeneration != writer.ownerGeneration ||
			binding.writerProjectionId != writer.projectionId ||
			binding.writerProjectionVersion != writer.projectionVersion ||
			binding.writerBindingGeneration != writer.bindingGeneration
		) {
			return SourceFactLookupResult.Unavailable(
				SourceProofReason.IMMUTABLE_AUTHORITY_MISSING,
			)
		}
		if (request.materialized.coverage != providerCoverage(
				requireNotNull(fact.intervalStartElapsedRealtimeNanos),
				requireNotNull(fact.intervalEndElapsedRealtimeNanos),
			) ||
			request.materialized.completeness != SourceProofCompleteness.COMPLETE
		) {
			return SourceFactLookupResult.Failed(SourceProofReason.FACT_IDENTITY_MISMATCH)
		}
		if (retainedFromMs != null &&
			CanonicalRetentionBounds.stepsWindowCrossesFloor(
				fact.intervalEndTimeMs,
				retainedFromMs,
			)
		) {
			return SourceFactLookupResult.Deleted(SourceProofReason.RETENTION_EXPIRED)
		}
		return found(request)
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
		when (val retentionResolution = database.resolvePressureRetentionByFact(
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
		)) {
			is PressureFactRetentionResolution.Authenticated -> {
				if (!retentionResolution.authenticatesRetainedRequest(request, historicalLane)) {
					return SourceFactLookupResult.Failed(SourceProofReason.INTEGRITY_FAILURE)
				}
				if (fact != null) {
					return SourceFactLookupResult.Failed(SourceProofReason.INTEGRITY_FAILURE)
				}
				return SourceFactLookupResult.Deleted(SourceProofReason.RETENTION_EXPIRED)
			}
			PressureFactRetentionResolution.Invalid ->
				return SourceFactLookupResult.Failed(SourceProofReason.INTEGRITY_FAILURE)
			PressureFactRetentionResolution.Absent -> Unit
		}
		if (fact == null) return SourceFactLookupResult.NotFound()
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
		val authenticated = when (proof) {
			is PressureFactLineageProof.Authenticated -> proof
			is PressureFactLineageProof.Rejected -> return if (
				proof.reason == PressureFactProofFailure.IMMUTABLE_AUTHORITY_MISSING
			) {
				SourceFactLookupResult.Unavailable(SourceProofReason.IMMUTABLE_AUTHORITY_MISSING)
			} else {
				SourceFactLookupResult.Failed(
					if (proof.reason == PressureFactProofFailure.CURRENT_FACT_MISMATCH) {
						SourceProofReason.FACT_IDENTITY_MISMATCH
					} else {
						SourceProofReason.INTEGRITY_FAILURE
					},
				)
			}
		}
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
		if (fact.semanticRevision != lineage.semanticRevision ||
			fact != authenticatedFact ||
			selectedFact.sourceEventId != request.key.evidenceIdentity.value ||
			selectedFact.sourceAdmissionOrdinal != lineage.sourceAdmissionOrdinal ||
			selectedLineage != lineage.selectedEvidenceLineage ||
			fact.writerBindingGeneration != writer.bindingGeneration ||
			selectedFact.sourceAdmissionOrdinal < historicalLane.activationOrdinal ||
			fact.sourceAdmissionOrdinal < historicalLane.activationOrdinal ||
			fact.sourceAdmissionOrdinal > requireNotNull(lineage.cursorRevision) ||
			fact.sourceAdmissionOrdinal > historicalLane.contiguousAdmissionOrdinal ||
			historicalLane.captureAdmissionCutoffOrdinal?.let { cutoff ->
				fact.sourceAdmissionOrdinal > cutoff
			} == true ||
			!binding.persistenceEligible ||
			binding.consentEpoch != fact.captureConsentEpoch ||
			binding.outputDestination !=
			SourceDestinationOwnerEntity.DESTINATION_SESSION_PRESSURE ||
			binding.writerOwner != SourceDestinationOwnerEntity.OWNER_PRESSURE_SESSION_FACTS ||
			binding.writerOwnerGeneration != writer.ownerGeneration ||
			binding.writerProjectionId != writer.projectionId ||
			binding.writerProjectionVersion != writer.projectionVersion ||
			binding.writerBindingGeneration != writer.bindingGeneration ||
			!fact.matches(request)
		) {
			return SourceFactLookupResult.Failed(SourceProofReason.INTEGRITY_FAILURE)
		}
		if (request.materialized.coverage != authenticated.selectedPressureProofCoverage() ||
			request.materialized.completeness != authenticated.selectedPressureProofCompleteness()
		) {
			return SourceFactLookupResult.Failed(SourceProofReason.FACT_IDENTITY_MISMATCH)
		}
		if (retainedFromMs != null) {
			val lowerBound = CanonicalRetentionBounds.pressureLineage(authenticated.revisions)
				?: return SourceFactLookupResult.Failed(SourceProofReason.INTEGRITY_FAILURE)
			if (lowerBound < retainedFromMs) {
				return SourceFactLookupResult.Deleted(SourceProofReason.RETENTION_EXPIRED)
			}
		}
		return found(request)
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
	): SourceFactLookupResult {
		capturedRadioRetentionLookup(request, retainedFromMs)?.let { return it }
		val lineage = request.materialized.lineage
		val writer = requireNotNull(lineage.writer)
		val fact = database.wifiCapturedFactDao().revision(
			writer.projectionId,
			writer.projectionVersion,
			request.materialized.requireFactIdentity(),
			requireNotNull(lineage.semanticRevision),
		) ?: return SourceFactLookupResult.NotFound()
		val wal = database.sourceEventWalDao().boundedPayloadByEventId(
			request.key.evidenceIdentity.value,
			MAX_WIFI_WAL_PAYLOAD_BYTES,
		) ?: return SourceFactLookupResult.Unavailable(
			SourceProofReason.IMMUTABLE_AUTHORITY_MISSING,
		)
		val children = database.radioObservationChildDao().wifiSessionChildren(
			fact.writerProjectionId, fact.writerProjectionVersion,
			fact.logicalFactId, fact.semanticRevision, MAX_WIFI_CHILDREN + 1,
		)
		val walAuthentication = WifiWalCarrierAuthenticator.authenticate(fact, wal, children)
			as? WifiWalCarrierAuthentication.Authenticated
			?: return SourceFactLookupResult.Failed(SourceProofReason.INTEGRITY_FAILURE)
		var aggregateOwnerCursor: WifiCapturedFactCursorEntity? = null
		val aggregate = when (fact.factKind) {
			WifiCapturedFactRevisionEntity.FACT_KIND_AGGREGATE -> fact
			WifiCapturedFactRevisionEntity.FACT_KIND_COVERAGE_ONLY -> {
				val ownerId = fact.aggregateOwnerLogicalFactId
					?: return SourceFactLookupResult.Failed(SourceProofReason.INTEGRITY_FAILURE)
				val ownerRevision = fact.aggregateOwnerSemanticRevision
					?: return SourceFactLookupResult.Failed(SourceProofReason.INTEGRITY_FAILURE)
				val owner = database.wifiCapturedFactDao().revision(
					fact.writerProjectionId,
					fact.writerProjectionVersion,
					ownerId,
					ownerRevision,
				) ?: return SourceFactLookupResult.Failed(SourceProofReason.INTEGRITY_FAILURE)
				aggregateOwnerCursor = database.wifiCapturedFactDao().cursor(
					fact.writerProjectionId,
					fact.writerProjectionVersion,
					ownerId,
				) ?: return SourceFactLookupResult.Unavailable(
					SourceProofReason.IMMUTABLE_AUTHORITY_MISSING,
				)
				owner
			}
			else -> return SourceFactLookupResult.Failed(SourceProofReason.INTEGRITY_FAILURE)
		}
		val cursor = database.wifiCapturedFactDao().cursor(
			writer.projectionId,
			writer.projectionVersion,
			fact.logicalFactId,
		) ?: return projectionFailureOrNotFound(
			writer,
			lineage.sourceAdmissionOrdinal,
			SourceProofReason.CURSOR_BEHIND,
		)
		if (fact.factKind == WifiCapturedFactRevisionEntity.FACT_KIND_COVERAGE_ONLY &&
			aggregateOwnerCursor?.let { ownerCursor ->
				WifiCapturedFactRevisionIntegrity.coverageOnlyOwnerCursorAuthenticates(
					fact,
					aggregate,
					ownerCursor,
				)
			} != true
		) {
			return SourceFactLookupResult.NotFound(SourceProofReason.STALE)
		}
		if (!WifiCapturedFactRevisionIntegrity.hasValidEffectChecksum(fact) ||
			fact.writerBindingGeneration != writer.bindingGeneration ||
			fact.writerOwnerGeneration != writer.ownerGeneration ||
			!WifiCapturedFactRevisionIntegrity.cursorAuthenticatesCurrent(cursor, fact) ||
			!WifiCapturedFactRevisionIntegrity.hasValidEffectChecksum(aggregate) ||
			!WifiWalCarrierAuthenticator.authenticatesAggregate(
				aggregate,
				walAuthentication.carrier,
				fact,
			) ||
			cursor.cursorRevision != lineage.cursorRevision ||
			!fact.matches(request)
		) {
			return SourceFactLookupResult.Failed(SourceProofReason.INTEGRITY_FAILURE)
		}
		if (request.materialized.coverage != providerCoverage(
				fact.coverageIntervalStartNanos,
				fact.coverageIntervalEndNanos,
			) ||
			request.materialized.completeness != fact.coverageCompleteness.toCompleteness()
		) {
			return SourceFactLookupResult.Failed(SourceProofReason.FACT_IDENTITY_MISMATCH)
		}
		if (retainedFromMs != null) {
			val lowerBounds = listOf(fact, aggregate).map { candidate ->
				CanonicalRetentionBounds.wifiCoverage(
					coverageEndWallTimeMs = candidate.observedWallTimeMs,
					wallTimeUncertaintyMs = candidate.wallTimeUncertaintyMs,
					coverageStartElapsedRealtimeNanos = candidate.coverageIntervalStartNanos,
					coverageEndElapsedRealtimeNanos = candidate.coverageIntervalEndNanos,
				)
			}
			if (lowerBounds.any { it == null }) {
				return SourceFactLookupResult.Failed(SourceProofReason.INTEGRITY_FAILURE)
			}
			if (lowerBounds.any { requireNotNull(it) < retainedFromMs }) {
				return SourceFactLookupResult.Deleted(SourceProofReason.RETENTION_EXPIRED)
			}
		}
		return found(request)
	}

	private suspend fun lookupCell(
		request: SourceFactLookupRequest,
		retainedFromMs: Long?,
	): SourceFactLookupResult {
		capturedRadioRetentionLookup(request, retainedFromMs)?.let { return it }
		val lineage = request.materialized.lineage
		val writer = requireNotNull(lineage.writer)
		val fact = database.cellCapturedFactDao().revision(
			writer.projectionId,
			writer.projectionVersion,
			request.materialized.requireFactIdentity(),
			requireNotNull(lineage.semanticRevision),
		) ?: return SourceFactLookupResult.NotFound()
		val wal = database.sourceEventWalDao().getByEventId(request.key.evidenceIdentity.value)
			?: return SourceFactLookupResult.Unavailable(
				SourceProofReason.IMMUTABLE_AUTHORITY_MISSING,
			)
		val cursor = database.cellCapturedFactDao().cursor(
			writer.projectionId,
			writer.projectionVersion,
			fact.logicalFactId,
		) ?: return projectionFailureOrNotFound(
			writer,
			lineage.sourceAdmissionOrdinal,
			SourceProofReason.CURSOR_BEHIND,
		)
		val walAuthentication = authenticateCellFactCarrier(fact, wal)
			?: return SourceFactLookupResult.Failed(SourceProofReason.INTEGRITY_FAILURE)
		var aggregateOwnerCursor:
			com.adsamcik.tracker.shared.base.database.data.CellCapturedFactCursorEntity? = null
		var aggregateWalAuthentication = walAuthentication
		val aggregate = when (fact.factKind) {
			CellCapturedFactRevisionEntity.FACT_KIND_AGGREGATE -> fact
			CellCapturedFactRevisionEntity.FACT_KIND_COVERAGE_ONLY -> {
				val ownerId = fact.aggregateOwnerLogicalFactId
					?: return SourceFactLookupResult.Failed(SourceProofReason.INTEGRITY_FAILURE)
				val ownerRevision = fact.aggregateOwnerSemanticRevision
					?: return SourceFactLookupResult.Failed(SourceProofReason.INTEGRITY_FAILURE)
				val owner = database.cellCapturedFactDao().revision(
					fact.writerProjectionId,
					fact.writerProjectionVersion,
					ownerId,
					ownerRevision,
				) ?: return SourceFactLookupResult.Failed(SourceProofReason.INTEGRITY_FAILURE)
				val ownerCursor = database.cellCapturedFactDao().cursor(
					fact.writerProjectionId,
					fact.writerProjectionVersion,
					ownerId,
				) ?: return SourceFactLookupResult.Unavailable(
					SourceProofReason.IMMUTABLE_AUTHORITY_MISSING,
				)
				if (!CellCapturedFactRevisionIntegrity.coverageOnlyOwnerCursorAuthenticates(
						fact,
						owner,
						ownerCursor,
					)
				) return SourceFactLookupResult.NotFound(SourceProofReason.STALE)
				val ownerWal = database.sourceEventWalDao().getByEventId(owner.sourceEventId)
					?: return SourceFactLookupResult.Unavailable(
						SourceProofReason.IMMUTABLE_AUTHORITY_MISSING,
					)
				aggregateWalAuthentication = authenticateCellFactCarrier(owner, ownerWal)
					?: return SourceFactLookupResult.Failed(SourceProofReason.INTEGRITY_FAILURE)
				aggregateOwnerCursor = ownerCursor
				owner
			}
			else -> return SourceFactLookupResult.Failed(SourceProofReason.INTEGRITY_FAILURE)
		}
		if (!CellCapturedFactRevisionIntegrity.hasValidEffectChecksum(fact) ||
			fact.writerBindingGeneration != writer.bindingGeneration ||
			fact.writerOwnerGeneration != writer.ownerGeneration ||
			!CellCapturedFactRevisionIntegrity.cursorAuthenticatesCurrent(cursor, fact) ||
			!CellCapturedFactRevisionIntegrity.hasValidEffectChecksum(aggregate) ||
			aggregate.writerBindingGeneration != writer.bindingGeneration ||
			aggregate.writerOwnerGeneration != writer.ownerGeneration ||
			!(if (fact.factKind == CellCapturedFactRevisionEntity.FACT_KIND_COVERAGE_ONLY) {
				CellWalCarrierAuthenticator.authenticatesAggregate(
					aggregate,
					walAuthentication.carrier,
					fact,
				)
			} else {
				CellWalCarrierAuthenticator.authenticatesAggregate(
					aggregate,
					walAuthentication.carrier,
				)
			}) ||
			aggregateOwnerCursor?.let { ownerCursor ->
				CellCapturedFactRevisionIntegrity.coverageOnlyOwnerCursorAuthenticates(
					fact,
					aggregate,
					ownerCursor,
				)
			} == false ||
			fact.factKind == CellCapturedFactRevisionEntity.FACT_KIND_COVERAGE_ONLY &&
			!cellAggregateReuseCompatible(fact, aggregate) ||
			cursor.cursorRevision != lineage.cursorRevision ||
			!fact.matches(request)
		) {
			return SourceFactLookupResult.Failed(SourceProofReason.INTEGRITY_FAILURE)
		}
		if (request.materialized.coverage != providerCoverage(
				fact.coverageIntervalStartNanos,
				fact.coverageIntervalEndNanos,
			) ||
			request.materialized.completeness != fact.childCompleteness.toCompleteness()
		) {
			return SourceFactLookupResult.Failed(SourceProofReason.FACT_IDENTITY_MISMATCH)
		}
		if (retainedFromMs != null) {
			val lowerBounds = listOf(
				CellWalCarrierAuthenticator.retentionLowerBound(fact, walAuthentication.carrier),
				CellWalCarrierAuthenticator.retentionLowerBound(
					aggregate,
					aggregateWalAuthentication.carrier,
				),
			)
			if (lowerBounds.any { it == null }) {
				return SourceFactLookupResult.Failed(SourceProofReason.INTEGRITY_FAILURE)
			}
			if (lowerBounds.any { requireNotNull(it) < retainedFromMs }) {
				return SourceFactLookupResult.Deleted(SourceProofReason.RETENTION_EXPIRED)
			}
		}
		return found(request)
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
		) return null
		val capturedSources = sources.filter { source ->
			source.purpose == SessionManifestPurposeCode.SESSION_CAPTURE &&
				source.persistenceEligible
		}.mapTo(linkedSetOf()) { it.sourceKind }
		val controlSources = sources.filter { source ->
			source.purpose == SessionManifestPurposeCode.CONTROL
		}.mapTo(linkedSetOf()) { it.sourceKind }
		return runCatching {
			CellAggregateReuseAuthority(
				logicalTrackingId = fact.logicalTrackingId,
				serviceRunId = fact.serviceRunId,
				sessionSegmentId = fact.sessionSegmentId,
				capturedSources = capturedSources,
				controlSources = controlSources,
				purposeEligibilityMask = fact.purposeEligibilityMask,
				captureConsentEpoch = fact.captureConsentEpoch,
				collectedDataEpoch = fact.collectedDataEpoch,
				scopeDeletionGeneration = fact.scopeDeletionGeneration,
				storedZoneId = fact.storedZoneId,
				structuralEpochDay = fact.structuralEpochDay,
			)
		}.getOrNull()
	}

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
		if (request.key.source !in setOf(TrackingSource.WIFI, TrackingSource.CELL)) return null
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
		) return SourceProofReason.WRITER_MISMATCH
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
