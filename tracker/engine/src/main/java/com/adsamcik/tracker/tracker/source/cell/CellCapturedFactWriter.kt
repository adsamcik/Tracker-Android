package com.adsamcik.tracker.tracker.source.cell

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.savedSessionCutoff
import com.adsamcik.tracker.shared.base.database.CellPurposeItems
import com.adsamcik.tracker.shared.base.database.qualifyCellPurposeItems
import com.adsamcik.tracker.shared.base.database.qualifyCellCapturePurposeItems
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationSnapshot
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.CanonicalRetentionBounds
import com.adsamcik.tracker.shared.base.database.data.CellCapturedFactCursorEntity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedFactRevisionIntegrity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedChildEntity
import com.adsamcik.tracker.shared.base.database.data.CellChildChecksumInput
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.CellWalQualifiedChild
import com.adsamcik.tracker.shared.base.database.data.CellWalChildDisposition
import com.adsamcik.tracker.shared.base.database.data.CELL_WAL_PAYLOAD_VERSION
import com.adsamcik.tracker.shared.base.database.data.AuthenticatedCellWalCarrier
import com.adsamcik.tracker.shared.base.database.data.CellWalCarrierAuthentication
import com.adsamcik.tracker.shared.base.database.data.CellWalCarrierAuthenticator
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.RadioChildOwner
import com.adsamcik.tracker.shared.base.database.data.RadioChildPurpose
import com.adsamcik.tracker.shared.base.database.data.RadioObservationChildIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceWriterGenerationContract
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull
import com.adsamcik.tracker.tracker.source.model.LogicalTrackingId
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.ServiceRunId
import com.adsamcik.tracker.tracker.source.model.SourceDeliveryIdentity
import com.adsamcik.tracker.tracker.source.model.SourceEventId
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject

internal enum class CellCapturedWriteRejection {
	DESTINATION_OWNER_CHANGED,
	MANIFEST_WRITER_AUTHORITY_MISMATCH,
	SOURCE_EVIDENCE_AUTHORITY_CHANGED,
	DELETED_SCOPE,
	SCOPE_DELETION_AUTHORITY_CHANGED,
	RETAINED_DATA,
	REVISION_CHAIN_MISMATCH,
	AGGREGATE_OWNER_MISMATCH,
	IDENTITY_COLLISION,
	CURSOR_CHANGED,
}

internal enum class CellCapturedWriteCheckpoint {
	QUALIFIED,
	REVISION_INSERTED,
	CURSOR_ADVANCED,
}

internal sealed interface CellCapturedWriteResult {
	data class Applied(
		val logicalFactId: String,
		val semanticRevision: Long,
		val cursorRevision: Long,
	) : CellCapturedWriteResult

	data class Unchanged(
		val logicalFactId: String,
		val semanticRevision: Long,
		val cursorRevision: Long,
	) : CellCapturedWriteResult

	data class NoEvidence(val classification: CellCapturedFactClassification) : CellCapturedWriteResult
	data class AdapterRejected(val reason: CellWalAdapterRejection) : CellCapturedWriteResult
	data class Rejected(val reason: CellCapturedWriteRejection) : CellCapturedWriteResult
	data class Groups(val results: List<CellCapturedWriteResult>) : CellCapturedWriteResult
}

/**
 * Dormant canonical Cell writer. Qualification and mutation share one Room transaction, so an
 * adapter result can never outlive the provider, authorization, consent, deletion, or epoch state
 * that created it. Its only production caller is the exact source-local Cell lane; executable code
 * alone neither installs that lane nor authorizes canonical rollout.
 */
// One source writer owns the Room transaction, exact replay/correction checks and publication rollback.
@Suppress("LargeClass", "TooManyFunctions")
internal class CellCapturedFactWriter @Inject constructor(
	private val database: AppDatabase,
	private val adapter: CellWalQualificationAdapter,
) {
	suspend fun write(eventId: SourceEventId): CellCapturedWriteResult = write(eventId) { }

	internal suspend fun write(
		eventId: SourceEventId,
		checkpoint: suspend (CellCapturedWriteCheckpoint) -> Unit,
	): CellCapturedWriteResult = try {
		database.withTransaction {
			writeInCurrentTransaction(eventId, checkpoint)
		}
	} catch (rejected: CellCapturedWriteRejectedException) {
		CellCapturedWriteResult.Rejected(rejected.reason)
	}

	/**
	 * Source-lane seam. The caller owns the Room transaction and must allow
	 * [CellCapturedWriteRejectedException] to escape so a late cursor rejection rolls back every
	 * preceding fact mutation before it records a durable projection failure.
	 */
	internal suspend fun writeInCurrentTransaction(
		eventId: SourceEventId,
		checkpoint: suspend (CellCapturedWriteCheckpoint) -> Unit = {},
	): CellCapturedWriteResult = writeQualified(
		adapter.qualify(eventId, ::classifyWithStoredState), checkpoint,
	)

	private suspend fun writeQualified(
		result: CellWalAdapterResult,
		checkpoint: suspend (CellCapturedWriteCheckpoint) -> Unit,
	): CellCapturedWriteResult = when (result) {
		is CellWalAdapterResult.Groups -> CellCapturedWriteResult.Groups(
			result.results.map { writeQualified(it, checkpoint) },
		)
		is CellWalAdapterResult.Rejected -> CellCapturedWriteResult.AdapterRejected(result.reason)
		is CellWalAdapterResult.Evaluated -> {
			checkpoint(CellCapturedWriteCheckpoint.QUALIFIED)
			writeClassification(result.classification, checkpoint)
		}
	}

	private suspend fun classifyWithStoredState(
		input: CellObservationInput,
		authority: CellCaptureAuthority,
	): CellCapturedFactClassification {
		val evidence = input.walEvidence
			?: return CellCapturedFactClassifier.classify(input = input, authority = authority)
		val deliveryIdentity = evidence.sourceDeliveryIdentity
			?: return CellCapturedFactClassifier.classify(input = input, authority = authority)
		val logicalFactId = CellCapturedFactRevisionIntegrity.logicalFactId(
			deliveryIdentity.value,
			authority.logicalTrackingId.value,
			authority.serviceRunId.value,
			authority.sessionSegmentId,
			authority.sessionManifestRevision,
			authority.collectedDataEpoch,
			authority.scopeDeletionGeneration,
		)
		val dao = database.cellCapturedFactDao()
		val exactCursor = dao.cursor(WRITER_ID, WRITER_VERSION, logicalFactId)
		if (exactCursor != null) {
			val current = dao.revision(
				WRITER_ID,
				WRITER_VERSION,
				logicalFactId,
				exactCursor.latestSemanticRevision,
			) ?: reject(CellCapturedWriteRejection.REVISION_CHAIN_MISMATCH)
			return classifyStoredRevision(input, authority, current, exactCursor)
		}

		val priorRow = dao.latestEffectiveBefore(
			writerProjectionId = WRITER_ID,
			writerProjectionVersion = WRITER_VERSION,
			logicalTrackingId = authority.logicalTrackingId.value,
			serviceRunId = authority.serviceRunId.value,
			sessionSegmentId = authority.sessionSegmentId,
			collectedDataEpoch = authority.collectedDataEpoch,
			scopeDeletionGeneration = authority.scopeDeletionGeneration,
			beforeSourceAdmissionOrdinal = evidence.sourceAdmissionOrdinal,
		)
		val prior = priorRow?.let { row ->
			val cursor = dao.cursor(WRITER_ID, WRITER_VERSION, row.logicalFactId)
				?: reject(CellCapturedWriteRejection.REVISION_CHAIN_MISMATCH)
			loadPriorAggregateOwner(row, cursor)
		}
		return CellCapturedFactClassifier.classify(
			input = input,
			authority = authority,
			priorFact = prior,
		)
	}

	private suspend fun loadPriorAggregateOwner(
		row: CellCapturedFactRevisionEntity,
		cursor: CellCapturedFactCursorEntity,
	): CellReusableFact.DirectAggregateOwner? = try {
		when (val reusable = loadReusableFact(row, cursor)) {
			is CellReusableFact.DirectAggregateOwner -> reusable
			is CellReusableFact.CoverageOnly -> reusable.directAggregateOwner
		}
	} catch (rejected: CellCapturedWriteRejectedException) {
		// A fresh retained delivery remains useful after its former aggregate owner falls
		// below retention. Materialize it directly instead of creating a dangling reuse.
		if (rejected.reason == CellCapturedWriteRejection.RETAINED_DATA) {
			null
		} else {
			throw rejected
		}
	}

	private suspend fun classifyStoredRevision(
		input: CellObservationInput,
		authority: CellCaptureAuthority,
		current: CellCapturedFactRevisionEntity,
		exactCursor: CellCapturedFactCursorEntity,
	): CellCapturedFactClassification {
		val correctionBase = loadReusableFact(current, exactCursor)
		if (exactCursor.latestSemanticRevision == Long.MAX_VALUE) {
			reject(CellCapturedWriteRejection.REVISION_CHAIN_MISMATCH)
		}
		val classification = CellCapturedFactClassifier.classify(
			input = input,
			authority = authority,
			semanticRevision = exactCursor.latestSemanticRevision + 1L,
			supersedesSemanticRevision = exactCursor.latestSemanticRevision,
			priorFact = correctionBase,
			correctionBase = correctionBase,
		)
		if (classification !is CellCapturedFactClassification.Replay &&
			exactCursor.latestSemanticRevision >= MAX_FACT_REVISIONS
		) {
			reject(CellCapturedWriteRejection.REVISION_CHAIN_MISMATCH)
		}
		return classification
	}

	private suspend fun writeClassification(
		classification: CellCapturedFactClassification,
		checkpoint: suspend (CellCapturedWriteCheckpoint) -> Unit,
	): CellCapturedWriteResult = when (classification) {
		is CellCapturedFactClassification.FreshChanged -> writeFact(classification.fact, checkpoint)
		is CellCapturedFactClassification.FreshUnchanged -> writeFact(classification.fact, checkpoint)
		is CellCapturedFactClassification.Replay -> writeReplay(classification)
		else -> CellCapturedWriteResult.NoEvidence(classification)
	}

	@Suppress("LongMethod", "CyclomaticComplexMethod")
	private suspend fun writeFact(
		fact: CellCapturedFact,
		checkpoint: suspend (CellCapturedWriteCheckpoint) -> Unit,
	): CellCapturedWriteResult {
		val authority = fact.authority
		val logicalTrackingId = authority.logicalTrackingId.value
		val serviceRunId = authority.serviceRunId.value
		val owner = database.sourceDestinationOwnerDao().get(SOURCE_KIND, DESTINATION)
		if (owner?.owner != OWNER || SourceWriterGenerationContract.bindingGenerationForCanonicalOwner(owner.ownerGeneration)
				?.let(SourceWriterGenerationContract::captureModeMaskForKnownBinding) == null) {
			reject(CellCapturedWriteRejection.DESTINATION_OWNER_CHANGED)
		}
		val sessionDao = database.sourceSessionDao()
		val manifest = sessionDao.manifest(logicalTrackingId, authority.sessionManifestRevision)
		val session = sessionDao.session(logicalTrackingId)
		val binding = sessionDao.manifestSource(
			logicalTrackingId,
			authority.sessionManifestRevision,
			SOURCE_KIND,
			PURPOSE,
		)
		// Match the complete acknowledged manifest/physical-writer tuple before writing this fact.
		@Suppress("ComplexCondition")
		if (binding == null || manifest == null || session == null ||
			manifest.sessionMode != session.sessionMode ||
			!binding.isExactCellWriter(authority.captureConsentEpoch, manifest.sessionMode) ||
			binding.writerOwnerGeneration != owner.ownerGeneration) {
			reject(CellCapturedWriteRejection.MANIFEST_WRITER_AUTHORITY_MISMATCH)
		}
		val state = database.sourceEvidenceStateDao().get()
			?: reject(CellCapturedWriteRejection.SOURCE_EVIDENCE_AUTHORITY_CHANGED)
		if (state.collectedDataEpoch != authority.collectedDataEpoch ||
			fact.evidenceBinding.sourceAdmissionOrdinal <= state.deletedSourceEventHighWaterOrdinal
		) {
			reject(CellCapturedWriteRejection.SOURCE_EVIDENCE_AUTHORITY_CHANGED)
		}
		val mapped = CellCapturedPersistence.map(fact, owner.ownerGeneration, requireNotNull(binding.writerBindingGeneration))
		requireNoRetainedFactProof(mapped, state.retainedFromMs)
		if (state.retainedFromMs?.let { retainedFrom ->
			CanonicalRetentionBounds.cellCoverage(
				walObservedWallTimeMs = fact.evidenceBinding.observedWallTimeMs,
				wallTimeUncertaintyMs = fact.evidenceBinding.wallTimeUncertaintyMs,
				walObservedElapsedRealtimeNanos =
					fact.evidenceBinding.observedElapsedRealtimeNanos,
				coverageStartElapsedRealtimeNanos =
					fact.coverage.providerIntervalStartElapsedRealtimeNanos,
			)?.let { earliest -> earliest < retainedFrom } ?: true
		} == true) {
			reject(CellCapturedWriteRejection.RETAINED_DATA)
		}
		requireUndeletedScope(logicalTrackingId, serviceRunId, authority.scopeDeletionGeneration)

		val dao = database.cellCapturedFactDao()
		val existing = dao.revision(
			WRITER_ID,
			WRITER_VERSION,
			mapped.logicalFactId,
			mapped.semanticRevision,
		)
		if (existing != null) {
			if (existing != mapped || !CellCapturedFactRevisionIntegrity.hasValidEffectChecksum(existing)) {
				reject(CellCapturedWriteRejection.IDENTITY_COLLISION)
			}
			val cursor = dao.cursor(WRITER_ID, WRITER_VERSION, mapped.logicalFactId)
				?: reject(CellCapturedWriteRejection.REVISION_CHAIN_MISMATCH)
			// Existing mapped data must correspond to its complete saved cursor head or a later revision.
			@Suppress("ComplexCondition")
			if (!cursor.matchesScope(mapped) || cursor.latestSemanticRevision < mapped.semanticRevision ||
				(cursor.latestSemanticRevision == mapped.semanticRevision &&
					(cursor.latestMutationId != mapped.mutationId ||
						cursor.latestEffectChecksum != mapped.effectChecksum ||
						cursor.latestSourceAdmissionOrdinal != mapped.sourceAdmissionOrdinal))
			) {
				reject(CellCapturedWriteRejection.REVISION_CHAIN_MISMATCH)
			}
			return CellCapturedWriteResult.Unchanged(
				mapped.logicalFactId,
				mapped.semanticRevision,
				cursor.cursorRevision,
			)
		}

		validateAggregateOwner(mapped, authority)
		val current = dao.cursor(WRITER_ID, WRITER_VERSION, mapped.logicalFactId)
		requireNextRevision(mapped, current)

		if (dao.insertRevision(mapped) == INSERT_IGNORED) {
			reject(CellCapturedWriteRejection.IDENTITY_COLLISION)
		}
		persistAcceptedChildren(mapped)
		checkpoint(CellCapturedWriteCheckpoint.REVISION_INSERTED)
		val nextCursorRevision = (current?.cursorRevision ?: 0L) + 1L
		val nextCursor = mapped.toCursor(nextCursorRevision)
		if (current == null) {
			if (dao.insertCursor(nextCursor) == INSERT_IGNORED) {
				reject(CellCapturedWriteRejection.CURSOR_CHANGED)
			}
		} else if (dao.advanceCursorExact(
				writerProjectionId = WRITER_ID,
				writerProjectionVersion = WRITER_VERSION,
				logicalFactId = mapped.logicalFactId,
				logicalTrackingId = mapped.logicalTrackingId,
				serviceRunId = mapped.serviceRunId,
				sessionSegmentId = mapped.sessionSegmentId,
				writerOwnerGeneration = mapped.writerOwnerGeneration,
				collectedDataEpoch = mapped.collectedDataEpoch,
				scopeDeletionGeneration = mapped.scopeDeletionGeneration,
				expectedSemanticRevision = current.latestSemanticRevision,
				expectedMutationId = current.latestMutationId,
				expectedEffectChecksum = current.latestEffectChecksum,
				expectedSourceAdmissionOrdinal = current.latestSourceAdmissionOrdinal,
				expectedCursorRevision = current.cursorRevision,
				newSemanticRevision = mapped.semanticRevision,
				newMutationId = mapped.mutationId,
				newEffectChecksum = mapped.effectChecksum,
				newSourceAdmissionOrdinal = mapped.sourceAdmissionOrdinal,
				newCursorRevision = nextCursorRevision,
				updatedAtMs = mapped.appliedAtMs,
			) != 1
		) {
			reject(CellCapturedWriteRejection.CURSOR_CHANGED)
		}
		checkpoint(CellCapturedWriteCheckpoint.CURSOR_ADVANCED)
		check(database.sourceEvidenceStateDao().incrementRevision(mapped.appliedAtMs) == 1) {
			"Unable to publish captured Cell fact revision"
		}
		return CellCapturedWriteResult.Applied(
			mapped.logicalFactId,
			mapped.semanticRevision,
			nextCursorRevision,
		)
	}

	// This one complete nullable cursor/predecessor contract keeps its exact field and overflow order.
	@Suppress("ComplexCondition")
	private fun requireNextRevision(
		mapped: CellCapturedFactRevisionEntity,
		current: CellCapturedFactCursorEntity?,
	) {
		if (current == null) {
			if (mapped.semanticRevision != 1L || mapped.supersedesSemanticRevision != null) {
				reject(CellCapturedWriteRejection.REVISION_CHAIN_MISMATCH)
			}
		} else if (!current.matchesScope(mapped) ||
			current.latestSemanticRevision != mapped.supersedesSemanticRevision ||
			current.latestSemanticRevision == Long.MAX_VALUE ||
			current.latestSemanticRevision + 1L != mapped.semanticRevision ||
			mapped.sourceAdmissionOrdinal < current.latestSourceAdmissionOrdinal
		) {
			reject(CellCapturedWriteRejection.REVISION_CHAIN_MISMATCH)
		}
	}

	private suspend fun persistAcceptedChildren(fact: CellCapturedFactRevisionEntity) {
		if (fact.payloadVersion != CELL_WAL_PAYLOAD_VERSION) {
			return
		}
		val wal = database.sourceEventWalDao().getByEventId(fact.sourceEventId)
			?: reject(CellCapturedWriteRejection.REVISION_CHAIN_MISMATCH)
		val raw = CellWalCarrierAuthenticator.authenticate(wal).requireCarrier()
		val purpose = database.qualifyCellCapturePurposeItems(wal, raw.payload, fact) as? CellPurposeItems.Qualified
			?: reject(CellCapturedWriteRejection.REVISION_CHAIN_MISMATCH)
		val carrier = CellWalCarrierAuthenticator.authenticate(fact, wal, purpose.indexes.toSet()).requireCarrier()
		val accepted = requireNotNull(carrier.childQualification).children.withIndex()
			.filter { it.value.disposition == CellWalChildDisposition.ACCEPTED }
		if (accepted.isEmpty() || accepted.any { it.index !in purpose.indexes } ||
			fact.authorizationEffectStartNanos != purpose.continuousFromNanos ||
			fact.maximumObservationAgeNanos > purpose.demandMaximumAgeNanos
		) {
			reject(CellCapturedWriteRejection.REVISION_CHAIN_MISMATCH)
		}
		val children = projectAcceptedChildren(fact, wal, accepted)
		database.radioObservationChildDao().insertCellSession(children)
	}

	private fun CellWalCarrierAuthentication.requireCarrier(): AuthenticatedCellWalCarrier = when (this) {
		is CellWalCarrierAuthentication.Authenticated -> carrier
		is CellWalCarrierAuthentication.Rejected -> reject(CellCapturedWriteRejection.REVISION_CHAIN_MISMATCH)
	}

	private fun projectAcceptedChildren(
		fact: CellCapturedFactRevisionEntity,
		wal: SourceEventWalEntity,
		accepted: List<IndexedValue<CellWalQualifiedChild>>,
	): List<CellCapturedChildEntity> {
		return accepted.map { (index, item) ->
			val observation = item.observation
			val providerTime = requireNotNull(observation.providerTimestampNanos)
			val ageNanos = wal.observedElapsedNanos - providerTime
			val ageMs = ageNanos / 1_000_000L
			val itemWallTime = wal.wallTimeMs?.takeIf { it >= ageMs }?.minus(ageMs)
				?: reject(CellCapturedWriteRejection.REVISION_CHAIN_MISMATCH)
			val itemUncertainty = Math.addExact(
				requireNotNull(wal.wallTimeUncertaintyMs),
				if (ageNanos % 1_000_000L == 0L) {
					0L
				} else {
					1L
				},
			)
			val owner = RadioChildOwner(
				RadioChildPurpose.SESSION, fact.writerProjectionId, fact.writerProjectionVersion,
				fact.logicalFactId, fact.semanticRevision, index,
			)
			val input = CellChildChecksumInput(
				owner, providerTime, itemWallTime, itemUncertainty,
				observation.identifierToken.ifEmpty { null }, observation.radioType,
				observation.registered, observation.signalLevelDbm,
			)
			CellCapturedChildEntity(
				fact.writerProjectionId, fact.writerProjectionVersion, fact.logicalFactId,
				fact.semanticRevision, index, providerTime, itemWallTime,
				itemUncertainty, input.towerIdentity, observation.radioType,
				observation.registered, observation.signalLevelDbm,
				RadioObservationChildIntegrity.cell(input),
			)
		}
	}

	private suspend fun writeReplay(
		replay: CellCapturedFactClassification.Replay,
	): CellCapturedWriteResult {
		val identity = replay.reference.identity
		val logicalFactId = CellCapturedFactRevisionIntegrity.logicalFactId(
			identity.sourceDeliveryIdentity.value,
			identity.logicalTrackingId.value,
			identity.serviceRunId.value,
			identity.sessionSegmentId,
			identity.sessionManifestRevision,
			identity.collectedDataEpoch,
			identity.scopeDeletionGeneration,
		)
		val revision = database.cellCapturedFactDao().revision(
			WRITER_ID,
			WRITER_VERSION,
			logicalFactId,
			replay.reference.semanticRevision,
		) ?: reject(CellCapturedWriteRejection.REVISION_CHAIN_MISMATCH)
		requireCurrentWriteAuthority(revision)
		// Recheck the saved operation pair after qualification, before acknowledging replay.
		persistedAuthority(revision)
		val cursor = database.cellCapturedFactDao().cursor(WRITER_ID, WRITER_VERSION, logicalFactId)
			?: reject(CellCapturedWriteRejection.REVISION_CHAIN_MISMATCH)
		// A replay acknowledges the same checksum-covered fact and complete saved cursor tuple.
		@Suppress("ComplexCondition")
		if (!CellCapturedFactRevisionIntegrity.hasValidEffectChecksum(revision) ||
			!cursor.matchesScope(revision) || cursor.latestSemanticRevision < revision.semanticRevision ||
			(cursor.latestSemanticRevision == revision.semanticRevision &&
				(cursor.latestMutationId != revision.mutationId ||
					cursor.latestEffectChecksum != revision.effectChecksum))
		) {
			reject(CellCapturedWriteRejection.REVISION_CHAIN_MISMATCH)
		}
		return CellCapturedWriteResult.Unchanged(
			logicalFactId,
			revision.semanticRevision,
			cursor.cursorRevision,
		)
	}

	private suspend fun requireCurrentManifestWriter(fact: CellCapturedFactRevisionEntity) {
		val owner = database.sourceDestinationOwnerDao().get(SOURCE_KIND, DESTINATION)
		if (owner?.owner != OWNER || owner.ownerGeneration != fact.writerOwnerGeneration) {
			reject(CellCapturedWriteRejection.DESTINATION_OWNER_CHANGED)
		}
		val sessionDao = database.sourceSessionDao()
		val manifest = sessionDao.manifest(fact.logicalTrackingId, fact.manifestRevision)
		val session = sessionDao.session(fact.logicalTrackingId)
		val binding = sessionDao.manifestSource(
			fact.logicalTrackingId,
			fact.manifestRevision,
			SOURCE_KIND,
			PURPOSE,
		)
		// The saved manifest and binding must match the complete current physical writer tuple.
		@Suppress("ComplexCondition")
		if (binding == null || manifest == null || session == null ||
			manifest.sessionMode != session.sessionMode ||
			!binding.isExactCellWriter(fact.captureConsentEpoch, manifest.sessionMode) ||
			binding.writerBindingGeneration != fact.writerBindingGeneration ||
			binding.writerOwnerGeneration != fact.writerOwnerGeneration) {
			reject(CellCapturedWriteRejection.MANIFEST_WRITER_AUTHORITY_MISMATCH)
		}
	}

	private suspend fun requireCurrentWriteAuthority(fact: CellCapturedFactRevisionEntity) {
		requireCurrentManifestWriter(fact)
		val state = database.sourceEvidenceStateDao().get()
			?: reject(CellCapturedWriteRejection.SOURCE_EVIDENCE_AUTHORITY_CHANGED)
		if (state.collectedDataEpoch != fact.collectedDataEpoch ||
			fact.sourceAdmissionOrdinal <= state.deletedSourceEventHighWaterOrdinal
		) {
			reject(CellCapturedWriteRejection.SOURCE_EVIDENCE_AUTHORITY_CHANGED)
		}
		if (state.retainedFromMs?.let { retainedFrom ->
			canonicalRetentionLowerBound(fact)?.let { earliest -> earliest < retainedFrom } ?: true
		} == true) {
			reject(CellCapturedWriteRejection.RETAINED_DATA)
		}
		requireUndeletedScope(
			fact.logicalTrackingId,
			fact.serviceRunId,
			fact.scopeDeletionGeneration,
		)
	}

	private suspend fun loadReusableFact(
		row: CellCapturedFactRevisionEntity,
		cursor: CellCapturedFactCursorEntity,
	): CellReusableFact {
		requireCurrentWriteAuthority(row)
		requireExactRevisionLineage(row, cursor)
		return try {
			val authority = persistedAuthority(row)
			val rawUncertainty = requireNotNull(database.sourceEventWalDao()
				.getByEventId(row.sourceEventId)?.wallTimeUncertaintyMs)
			val evidence = row.persistedEvidence(authority, rawUncertainty)
			val coverage = row.persistedCoverage()
			val mutation = row.persistedMutation()
			when (row.factKind) {
				CellCapturedFactRevisionEntity.FACT_KIND_AGGREGATE ->
					CellReusableFact.DirectAggregateOwner.from(
						CellCapturedFact.Aggregate(
							mutation = mutation,
							authority = authority,
							evidenceBinding = evidence,
							observedWallTimeMs = row.observedWallTimeMs,
							wallTimeUncertaintyMs = row.wallTimeUncertaintyMs,
							availability = CellAvailability.valueOf(row.availability),
							coverage = coverage,
							aggregate = row.persistedAggregate(),
						),
					)
				CellCapturedFactRevisionEntity.FACT_KIND_COVERAGE_ONLY -> {
					val owner = loadDirectAggregateOwner(
						requireNotNull(row.aggregateOwnerLogicalFactId),
						requireNotNull(row.aggregateOwnerSemanticRevision),
					)
					CellReusableFact.CoverageOnly(
						reference = mutation.reference,
						authority = authority,
						productEffect = CellCapturedProductEffect(
							evidenceBinding = evidence,
							observedWallTimeMs = row.observedWallTimeMs,
							wallTimeUncertaintyMs = row.wallTimeUncertaintyMs,
							availability = CellAvailability.valueOf(row.availability),
							coverage = coverage,
							aggregate = owner.productEffect.aggregate,
						),
						directAggregateOwner = owner,
					)
				}
				else -> reject(CellCapturedWriteRejection.REVISION_CHAIN_MISMATCH)
			}
		} catch (_: IllegalArgumentException) {
			reject(CellCapturedWriteRejection.REVISION_CHAIN_MISMATCH)
		}
	}

	private suspend fun requireExactRevisionLineage(
		current: CellCapturedFactRevisionEntity,
		cursor: CellCapturedFactCursorEntity,
	): List<CellCapturedFactRevisionEntity> {
		if (!cursor.matchesCurrentRevision(current) || current.semanticRevision > MAX_FACT_REVISIONS) {
			reject(CellCapturedWriteRejection.REVISION_CHAIN_MISMATCH)
		}
		val revisions = database.cellCapturedFactDao().revisionsForFact(
			WRITER_ID,
			WRITER_VERSION,
			current.logicalFactId,
			MAX_FACT_REVISIONS + 1,
		)
		if (revisions.size.toLong() != current.semanticRevision || revisions.firstOrNull() != current) {
			reject(CellCapturedWriteRejection.REVISION_CHAIN_MISMATCH)
		}
		revisions.forEachIndexed { index, revision ->
			val expectedSemanticRevision = current.semanticRevision - index
			val expectedSuperseded = (expectedSemanticRevision - 1L).takeIf { it > 0L }
			val nextOlder = revisions.getOrNull(index + 1)
			// One saved revision matches its exact expected predecessor, integrity and stable lineage.
			@Suppress("ComplexCondition")
			if (revision.semanticRevision != expectedSemanticRevision ||
				revision.supersedesSemanticRevision != expectedSuperseded ||
				!CellCapturedFactRevisionIntegrity.hasValidEffectChecksum(revision) ||
				(nextOlder != null && !revision.isAuthenticatedSuccessorOf(nextOlder))
			) {
				reject(CellCapturedWriteRejection.REVISION_CHAIN_MISMATCH)
			}
		}
		return revisions
	}

	private suspend fun loadDirectAggregateOwner(
		logicalFactId: String,
		semanticRevision: Long,
	): CellReusableFact.DirectAggregateOwner {
		val dao = database.cellCapturedFactDao()
		val row = dao.revision(
			WRITER_ID,
			WRITER_VERSION,
			logicalFactId,
			semanticRevision,
		) ?: reject(CellCapturedWriteRejection.AGGREGATE_OWNER_MISMATCH)
		val cursor = dao.cursor(WRITER_ID, WRITER_VERSION, logicalFactId)
			?: reject(CellCapturedWriteRejection.AGGREGATE_OWNER_MISMATCH)
		val current = dao.revision(
			WRITER_ID,
			WRITER_VERSION,
			logicalFactId,
			cursor.latestSemanticRevision,
		) ?: reject(CellCapturedWriteRejection.AGGREGATE_OWNER_MISMATCH)
		if (row.factKind != CellCapturedFactRevisionEntity.FACT_KIND_AGGREGATE ||
			!CellCapturedFactRevisionIntegrity.hasValidEffectChecksum(row) ||
			!cursor.matchesCurrentRevision(current)
		) {
			reject(CellCapturedWriteRejection.AGGREGATE_OWNER_MISMATCH)
		}
		val lineage = requireExactRevisionLineage(current, cursor)
		if (row !in lineage || lineage.any { revision ->
				revision.factKind != CellCapturedFactRevisionEntity.FACT_KIND_AGGREGATE
			}
		) {
			reject(CellCapturedWriteRejection.AGGREGATE_OWNER_MISMATCH)
		}
		requireCurrentWriteAuthority(row)
		return try {
			val authority = persistedAuthority(row)
			val rawUncertainty = requireNotNull(database.sourceEventWalDao()
				.getByEventId(row.sourceEventId)?.wallTimeUncertaintyMs)
			CellReusableFact.DirectAggregateOwner.from(
				CellCapturedFact.Aggregate(
					mutation = row.persistedMutation(),
					authority = authority,
					evidenceBinding = row.persistedEvidence(authority, rawUncertainty),
					observedWallTimeMs = row.observedWallTimeMs,
					wallTimeUncertaintyMs = row.wallTimeUncertaintyMs,
					availability = CellAvailability.valueOf(row.availability),
					coverage = row.persistedCoverage(),
					aggregate = row.persistedAggregate(),
				),
			)
		} catch (_: IllegalArgumentException) {
			reject(CellCapturedWriteRejection.AGGREGATE_OWNER_MISMATCH)
		}
	}

	@Suppress("LongMethod", "CyclomaticComplexMethod")
	private suspend fun persistedAuthority(
		row: CellCapturedFactRevisionEntity,
	): CellCaptureAuthority {
		val sessionDao = database.sourceSessionDao()
		val run = sessionDao.serviceRun(row.serviceRunId)
		val session = sessionDao.session(row.logicalTrackingId)
		if (run == null || session == null || hasConflictingSavedRunOwner(row, run, session) ||
			hasInvalidSavedSessionCutoff(session)
		) {
			reject(CellCapturedWriteRejection.REVISION_CHAIN_MISMATCH)
		}
		val manifests = sessionDao.manifestsForServiceRun(row.serviceRunId, MAX_MANIFESTS_PER_RUN + 1)
		val allSources = database.trackingHistoryReadDao().manifestSources(
			listOf(row.serviceRunId),
			MAX_MANIFEST_SOURCES_PER_RUN + 1,
		)
		requireRetainedManifestTimeline(run, manifests, allSources)
		val manifestIndex = manifests.indexOfFirst { manifest ->
			manifest.logicalTrackingId == row.logicalTrackingId &&
				manifest.manifestRevision == row.manifestRevision
		}
		if (manifestIndex < 0) {
			reject(CellCapturedWriteRejection.REVISION_CHAIN_MISMATCH)
		}
		val manifest = manifests[manifestIndex]
		val sources = allSources.filter { source ->
			source.logicalTrackingId == row.logicalTrackingId &&
				source.manifestRevision == row.manifestRevision
		}
		if (sources.size > MAX_MANIFEST_SOURCE_ROWS) {
			reject(CellCapturedWriteRejection.REVISION_CHAIN_MISMATCH)
		}
		val binding = sources.singleOrNull { source ->
			source.sourceKind == SOURCE_KIND && source.purpose == PURPOSE
		} ?: reject(CellCapturedWriteRejection.REVISION_CHAIN_MISMATCH)
		if (hasConflictingSavedManifestBinding(row, session, manifest, binding) ||
			manifest.effectiveElapsedRealtimeNanos > row.coverageIntervalStartNanos
		) {
			reject(CellCapturedWriteRejection.REVISION_CHAIN_MISMATCH)
		}

		val brokerDao = database.sourceBrokerDao()
		val registration = brokerDao.registration(SOURCE_KIND, row.registrationGeneration)
			?: reject(CellCapturedWriteRejection.REVISION_CHAIN_MISMATCH)
		val registrationStart = registration.acceptedElapsedRealtimeNanos
		val registrationEnd = registration.retiredElapsedRealtimeNanos ?: Long.MAX_VALUE
		if (registrationStart == null || registration.hasConflictingSavedCellRegistration(row) ||
			row.coverageIntervalStartNanos < registrationStart ||
			row.coverageIntervalEndNanos >= registrationEnd
		) {
			reject(CellCapturedWriteRejection.REVISION_CHAIN_MISMATCH)
		}
		val authorizationRows = brokerDao.authorizationRevision(
			SOURCE_KIND, row.registrationGeneration, row.authorizationRevision,
		)
		val authorization = runCatching {
			authorizationRows.toAuthorizationSnapshotOrNull()
		}.getOrNull()
			?: reject(CellCapturedWriteRejection.REVISION_CHAIN_MISMATCH)
		val startAuthorization = runCatching {
			brokerDao.authorizationAt(
				SOURCE_KIND,
				row.registrationGeneration,
				row.clockDomainId,
				row.coverageIntervalStartNanos,
			).toAuthorizationSnapshotOrNull()
		}.getOrNull()
		val endAuthorization = runCatching {
			brokerDao.authorizationAt(
				SOURCE_KIND,
				row.registrationGeneration,
				row.clockDomainId,
				row.coverageIntervalEndNanos,
			).toAuthorizationSnapshotOrNull()
		}.getOrNull()
		val authorizationMembers = authorization.authorizedMembers
		val captureMember = authorizationMembers.singleOrNull { member ->
			member.purpose == PURPOSE && member.persistenceEligible &&
				member.logicalTrackingId == row.logicalTrackingId &&
				member.serviceRunId == row.serviceRunId &&
				member.manifestRevision == row.manifestRevision &&
				member.lifecycleLeaseGeneration == row.lifecycleLeaseGeneration &&
				member.sourcePolicyRevision == row.sourcePolicyRevision &&
				member.consentEpoch == row.captureConsentEpoch
		}
		val demandIds = authorizationMembers.mapNotNull { member -> member.demandId }
		val demands = brokerDao.demandsByIds(demandIds)
		val recomputedAuthorization = runCatching {
			SourceBrokerAuthorization.rows(
				sourceKind = SOURCE_KIND,
				registrationGeneration = row.registrationGeneration,
				authorizationRevision = row.authorizationRevision,
				demands = demands,
				effectiveBootId = authorization.effectiveBootId,
				effectiveElapsedRealtimeNanos = authorization.effectiveElapsedRealtimeNanos,
				effectiveWallTimeMs = authorization.members.first().effectiveWallTimeMs,
			)
		}.getOrNull()
		val purpose = if (row.payloadVersion == CELL_WAL_PAYLOAD_VERSION) {
			val wal = database.sourceEventWalDao().getByEventId(row.sourceEventId)
				?: reject(CellCapturedWriteRejection.REVISION_CHAIN_MISMATCH)
			val carrier = CellWalCarrierAuthenticator.authenticate(wal).requireCarrier()
			val demandId = captureMember?.demandId
				?: reject(CellCapturedWriteRejection.REVISION_CHAIN_MISMATCH)
			val qualified = database.qualifyCellPurposeItems(
				wal, carrier.payload, demandId, registrationStart, registrationEnd,
			) as? CellPurposeItems.Qualified
				?: reject(CellCapturedWriteRejection.REVISION_CHAIN_MISMATCH)
			val selected = CellWalCarrierAuthenticator.authenticate(row, wal, qualified.indexes.toSet())
				as? CellWalCarrierAuthentication.Authenticated
				?: reject(CellCapturedWriteRejection.REVISION_CHAIN_MISMATCH)
			val accepted = selected.carrier.childQualification?.children?.withIndex()
				?.filter { it.value.disposition == CellWalChildDisposition.ACCEPTED }
				?.map { it.index }.orEmpty()
			if (qualified.indexes.isEmpty() || accepted.any { it !in qualified.indexes } ||
				row.maximumObservationAgeNanos > qualified.demandMaximumAgeNanos
			) {
				reject(CellCapturedWriteRejection.REVISION_CHAIN_MISMATCH)
			}
			qualified
		} else {
			null
		}
		if (authorization.isDenied || captureMember == null) {
			reject(CellCapturedWriteRejection.REVISION_CHAIN_MISMATCH)
		}
		if (purpose == null && (startAuthorization != authorization || endAuthorization != authorization)) {
			reject(CellCapturedWriteRejection.REVISION_CHAIN_MISMATCH)
		}
		if (hasConflictingReconstructedCellAuthorization(
			row, authorization, demandIds, demands, recomputedAuthorization,
		) ||
			(purpose?.continuousFromNanos ?: authorization.effectiveElapsedRealtimeNanos) >
				row.coverageIntervalStartNanos
		) {
			reject(CellCapturedWriteRejection.REVISION_CHAIN_MISMATCH)
		}
		val nextAuthorizationRows = brokerDao.nextAuthorizationRevision(
			SOURCE_KIND,
			row.registrationGeneration,
			row.authorizationRevision,
		)
		val nextAuthorization = if (nextAuthorizationRows.isEmpty()) {
			null
		} else {
			runCatching { nextAuthorizationRows.toAuthorizationSnapshotOrNull() }.getOrNull()
				?: reject(CellCapturedWriteRejection.REVISION_CHAIN_MISMATCH)
		}
		if (nextAuthorization != null &&
			(nextAuthorization.effectiveBootId != row.clockDomainId ||
				nextAuthorization.effectiveElapsedRealtimeNanos < authorization.effectiveElapsedRealtimeNanos)
		) {
			reject(CellCapturedWriteRejection.REVISION_CHAIN_MISMATCH)
		}
		val authorizationEnd = purpose?.continuousUntilNanos ?: minOf(
			registrationEnd,
			nextAuthorization?.effectiveElapsedRealtimeNanos ?: Long.MAX_VALUE,
		)
		if (row.coverageIntervalEndNanos >= authorizationEnd) {
			reject(CellCapturedWriteRejection.REVISION_CHAIN_MISMATCH)
		}

		val policy = database.sourcePolicyDao().policyAtRevision(row.sourcePolicyRevision, SOURCE_KIND)
			?: reject(CellCapturedWriteRejection.REVISION_CHAIN_MISMATCH)
		val consent = database.sourcePolicyDao().consentEpoch(
			SOURCE_KIND,
			PURPOSE,
			row.captureConsentEpoch,
		) ?: reject(CellCapturedWriteRejection.REVISION_CHAIN_MISMATCH)
		if (hasInvalidOriginalCapturePolicy(row, binding, policy) || hasInvalidOriginalCaptureConsent(row, consent)
		) {
			reject(CellCapturedWriteRejection.REVISION_CHAIN_MISMATCH)
		}

		val sessionEnd = database.savedSessionCutoff(session)
			.retainedEnd(row.clockDomainId, row.deletionEffectEndNanos)
		val manifestEnd = manifests.getOrNull(manifestIndex + 1)?.effectiveElapsedRealtimeNanos
			?: minOf(sessionEnd, registrationEnd, authorizationEnd)
		if (run.startedElapsedNanos > row.coverageIntervalStartNanos ||
			row.coverageIntervalEndNanos >= sessionEnd || row.coverageIntervalEndNanos >= manifestEnd
		) {
			reject(CellCapturedWriteRejection.REVISION_CHAIN_MISMATCH)
		}
		val zone = runCatching { ZoneId.of(row.storedZoneId) }.getOrNull()
			?: reject(CellCapturedWriteRejection.REVISION_CHAIN_MISMATCH)
		val earliestWall = canonicalRetentionLowerBound(row)
			?: reject(CellCapturedWriteRejection.REVISION_CHAIN_MISMATCH)
		val latestWall = runCatching {
			Math.addExact(row.observedWallTimeMs, row.wallTimeUncertaintyMs)
		}.getOrNull() ?: reject(CellCapturedWriteRejection.REVISION_CHAIN_MISMATCH)
		val firstDay = Instant.ofEpochMilli(earliestWall).atZone(zone).toLocalDate().toEpochDay()
		val lastDay = Instant.ofEpochMilli(latestWall).atZone(zone).toLocalDate().toEpochDay()
		val measuredDay = Instant.ofEpochMilli(row.observedWallTimeMs).atZone(zone).toLocalDate().toEpochDay()
		if (if (row.payloadVersion == CELL_WAL_PAYLOAD_VERSION) {
			measuredDay != row.structuralEpochDay
		} else
			{
			firstDay != lastDay || firstDay != row.structuralEpochDay
		}
		) {
			reject(CellCapturedWriteRejection.REVISION_CHAIN_MISMATCH)
		}
		val capturedSources = sources.sourceKinds(SessionManifestPurposeCode.SESSION_CAPTURE) {
			it.persistenceEligible
		}
		val controlSources = sources.sourceKinds(SessionManifestPurposeCode.CONTROL) { true }
		val persisted = row.persistedAuthority(capturedSources, controlSources)
		val authoritative = persisted.copy(
			temporalAuthority = CellCaptureTemporalAuthority(
				providerAcceptance = CellProviderTimeInterval(registrationStart, registrationEnd),
				authorizationEffect = CellProviderTimeInterval(
					purpose?.continuousFromNanos ?: authorization.effectiveElapsedRealtimeNanos,
					authorizationEnd,
				),
				consentEffect = CellProviderTimeInterval(
					if (consent.effectiveBootId == row.clockDomainId) {
						consent.effectiveElapsedRealtimeNanos
					} else {
						0L
					},
					authorizationEnd,
				),
				sessionRunEffect = CellProviderTimeInterval(
					manifest.effectiveElapsedRealtimeNanos,
					manifestEnd,
				),
				deletionEffect = CellProviderTimeInterval(run.startedElapsedNanos, sessionEnd),
			),
		)
		if (!authoritative.isExactSettlementOf(persisted)) {
			reject(CellCapturedWriteRejection.REVISION_CHAIN_MISMATCH)
		}
		return persisted
	}

	private fun requireRetainedManifestTimeline(
		run: SourceServiceRunEntity,
		manifests: List<SessionManifestVersionEntity>,
		allSources: List<SessionManifestSourceEntity>,
	) {
		if (manifests.size > MAX_MANIFESTS_PER_RUN ||
			allSources.size > MAX_MANIFEST_SOURCES_PER_RUN
		) {
			reject(CellCapturedWriteRejection.REVISION_CHAIN_MISMATCH)
		}
		if (!SessionManifestIntegrity.hasValidRetainedServiceRunTimeline(run, manifests)) {
			reject(CellCapturedWriteRejection.REVISION_CHAIN_MISMATCH)
		}
		if (manifests.any { manifest ->
				!SessionManifestIntegrity.verify(
					manifest,
					allSources.filter { source ->
						source.logicalTrackingId == manifest.logicalTrackingId &&
							source.manifestRevision == manifest.manifestRevision
					},
				)
			}
		) {
			reject(CellCapturedWriteRejection.REVISION_CHAIN_MISMATCH)
		}
	}

	private fun hasConflictingSavedRunOwner(
		row: CellCapturedFactRevisionEntity,
		run: SourceServiceRunEntity,
		session: LogicalTrackingSessionEntity,
	): Boolean = run.logicalTrackingId != row.logicalTrackingId ||
		!SessionManifestIntegrity.hasRetainedLeaseGeneration(run, row.lifecycleLeaseGeneration) ||
		run.sessionSegmentId != row.sessionSegmentId ||
		session.logicalTrackingId != row.logicalTrackingId ||
		session.lifecycleLeaseGeneration < row.lifecycleLeaseGeneration ||
		(session.currentServiceRunId == row.serviceRunId &&
			(run.leaseGeneration != session.lifecycleLeaseGeneration || run.bootId != session.lifecycleBootId))

	private fun hasInvalidSavedSessionCutoff(session: LogicalTrackingSessionEntity): Boolean =
		(session.cutoffAtMs == null) != (session.cutoffElapsedNanos == null) ||
		(session.completedAtMs != null && session.cutoffElapsedNanos == null)

	private fun hasConflictingSavedManifestBinding(
		row: CellCapturedFactRevisionEntity,
		session: LogicalTrackingSessionEntity,
		manifest: SessionManifestVersionEntity,
		binding: SessionManifestSourceEntity,
	): Boolean = manifest.sessionMode != session.sessionMode ||
		!binding.isExactCellWriter(row.captureConsentEpoch, manifest.sessionMode) ||
		binding.writerBindingGeneration != row.writerBindingGeneration ||
		binding.writerOwnerGeneration != row.writerOwnerGeneration ||
		manifest.serviceRunId != row.serviceRunId ||
		manifest.sourcePolicyRevision != row.sourcePolicyRevision ||
		manifest.acquisitionPlanRevision != row.configurationRevision ||
		manifest.effectiveBootId != row.clockDomainId || manifest.zoneId != row.storedZoneId

	private fun ProviderRegistrationGenerationEntity.hasConflictingSavedCellRegistration(
		row: CellCapturedFactRevisionEntity,
	): Boolean = this.sourceInstanceId != row.sourceInstanceId ||
		this.ownerScope != EXPECTED_OWNER_SCOPE ||
		this.providerResidency != ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND ||
		this.physicalConfigurationFingerprint != row.physicalConfigurationFingerprint ||
		this.collectedDataEpoch != row.collectedDataEpoch ||
		this.clockDomainId != row.clockDomainId ||
		this.status !in ACCEPTED_REGISTRATION_STATES ||
		(this.retiredAtMs == null) !=
			(this.retiredElapsedRealtimeNanos == null)

	private fun hasConflictingReconstructedCellAuthorization(
		row: CellCapturedFactRevisionEntity,
		authorization: SourceAuthorizationSnapshot,
		demandIds: List<String>,
		demands: List<SourceDemandEntity>,
		recomputedAuthorization: List<SourceAuthorizationEntity>?,
	): Boolean = demandIds.distinct().size != authorization.authorizedMembers.size ||
		demands.size != authorization.authorizedMembers.size ||
		recomputedAuthorization?.sortedBy { it.memberId } != authorization.members.sortedBy { it.memberId } ||
		authorization.authorizationFingerprint != row.authorizationFingerprint ||
		authorization.purposeEligibilityMask != row.purposeEligibilityMask ||
		authorization.effectiveBootId != row.clockDomainId

	private fun hasInvalidOriginalCapturePolicy(
		row: CellCapturedFactRevisionEntity,
		binding: SessionManifestSourceEntity,
		policy: SourcePolicyEntity,
	): Boolean = !policy.enabled || !policy.capturePersistenceEligible ||
		policy.captureConsentEpoch != row.captureConsentEpoch ||
		binding.qosCode != policy.qosCode ||
		(policy.effectiveBootId == row.clockDomainId &&
			policy.effectiveElapsedRealtimeNanos > row.coverageIntervalStartNanos)

	private fun hasInvalidOriginalCaptureConsent(
		row: CellCapturedFactRevisionEntity,
		consent: SourceConsentEpochEntity,
	): Boolean = !consent.eligible || !consent.persistenceEligible ||
		consent.policyRevision > row.sourcePolicyRevision ||
		(consent.effectiveBootId == row.clockDomainId &&
			consent.effectiveElapsedRealtimeNanos > row.coverageIntervalStartNanos)

	private fun CellCapturedFactRevisionEntity.persistedAuthority(
		capturedSources: Set<SourceKind>,
		controlSources: Set<SourceKind>,
	): CellCaptureAuthority {
		val row = this
		return CellCaptureAuthority(
			logicalTrackingId = LogicalTrackingId(row.logicalTrackingId),
			serviceRunId = ServiceRunId(row.serviceRunId),
			sessionSegmentId = row.sessionSegmentId,
			capturedSources = capturedSources,
			controlSources = controlSources,
			sourceInstanceId = SourceInstanceId(row.sourceInstanceId),
			registrationGeneration = row.registrationGeneration,
			configurationRevision = row.configurationRevision,
			physicalConfigurationFingerprint = row.physicalConfigurationFingerprint,
			authorizationRevision = row.authorizationRevision,
			authorizationFingerprint = row.authorizationFingerprint,
			purposeEligibilityMask = row.purposeEligibilityMask,
			sourcePolicyRevision = row.sourcePolicyRevision,
			captureConsentEpoch = row.captureConsentEpoch,
			sessionManifestRevision = row.manifestRevision,
			lifecycleLeaseGeneration = row.lifecycleLeaseGeneration,
			collectedDataEpoch = row.collectedDataEpoch,
			scopeDeletionGeneration = row.scopeDeletionGeneration,
			clockDomainId = row.clockDomainId,
			zoneId = row.storedZoneId,
			structuralEpochDay = row.structuralEpochDay,
			temporalAuthority = CellCaptureTemporalAuthority(
				providerAcceptance = CellProviderTimeInterval(
					row.providerAcceptanceStartNanos,
					row.providerAcceptanceEndNanos,
				),
				authorizationEffect = CellProviderTimeInterval(
					row.authorizationEffectStartNanos,
					row.authorizationEffectEndNanos,
				),
				consentEffect = CellProviderTimeInterval(
					row.consentEffectStartNanos,
					row.consentEffectEndNanos,
				),
				sessionRunEffect = CellProviderTimeInterval(
					row.sessionRunEffectStartNanos,
					row.sessionRunEffectEndNanos,
				),
				deletionEffect = CellProviderTimeInterval(
					row.deletionEffectStartNanos,
					row.deletionEffectEndNanos,
				),
			),
			maximumObservationAgeNanos = row.maximumObservationAgeNanos,
		)
	}

	private suspend fun requireUndeletedScope(
		logicalTrackingId: String,
		serviceRunId: String,
		expectedGeneration: Long,
	) {
		val identity = SourceDeletionFenceEntity.logicalServiceRunIdentity(
			SOURCE_KIND,
			PURPOSE,
			logicalTrackingId,
			serviceRunId,
		)
		if (database.sourceDeletionFenceDao().contains(
				SOURCE_KIND,
				PURPOSE,
				SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
				identity,
			)
		) {
			reject(CellCapturedWriteRejection.DELETED_SCOPE)
		}
		val state = database.cellCapturedFactDao().deletionGeneration(logicalTrackingId, serviceRunId)
		val actualGeneration = when {
			state == null -> 0L
			state.collectedDataEpoch != database.sourceEvidenceStateDao().get()?.collectedDataEpoch ->
				reject(CellCapturedWriteRejection.SCOPE_DELETION_AUTHORITY_CHANGED)
			else -> state.generation
		}
		if (actualGeneration != expectedGeneration) {
			reject(CellCapturedWriteRejection.SCOPE_DELETION_AUTHORITY_CHANGED)
		}
	}

	private suspend fun validateAggregateOwner(
		fact: CellCapturedFactRevisionEntity,
		currentAuthority: CellCaptureAuthority,
	) {
		if (fact.factKind != CellCapturedFactRevisionEntity.FACT_KIND_COVERAGE_ONLY) {
			return
		}
		val ownerLogicalId = requireNotNull(fact.aggregateOwnerLogicalFactId)
		val ownerRevision = requireNotNull(fact.aggregateOwnerSemanticRevision)
		val owner = loadDirectAggregateOwner(ownerLogicalId, ownerRevision)
		if (owner.reference.identity.logicalTrackingId.value != fact.logicalTrackingId ||
			owner.reference.identity.serviceRunId.value != fact.serviceRunId ||
			owner.reference.identity.sessionSegmentId != fact.sessionSegmentId ||
			!currentAuthority.canReuseAggregateFrom(owner.authority)
		) {
			reject(CellCapturedWriteRejection.AGGREGATE_OWNER_MISMATCH)
		}
	}

	private suspend fun canonicalRetentionLowerBound(
		fact: CellCapturedFactRevisionEntity,
	): Long? {
		val wal = database.sourceEventWalDao().getByEventId(fact.sourceEventId) ?: return null
		val authenticated = CellWalCarrierAuthenticator.authenticate(fact, wal)
			as? CellWalCarrierAuthentication.Authenticated ?: return null
		return CellWalCarrierAuthenticator.retentionLowerBound(fact, authenticated.carrier)
	}

	private suspend fun requireNoRetainedFactProof(
		fact: CellCapturedFactRevisionEntity,
		currentRetainedFromMs: Long?,
	) {
		val dao = database.capturedRadioFactRetentionProofDao()
		val conflictingEvent = dao.hasConflictingEventIdentity(
			SOURCE_KIND, fact.sourceEventId, fact.sourceAdmissionOrdinal,
			fact.collectedDataEpoch, fact.walIntegrityIdentity,
		)
		val byFact = dao.byFact(
			SOURCE_KIND,
			fact.writerProjectionId,
			fact.writerProjectionVersion,
			fact.logicalFactId,
		)
		if (!conflictingEvent && byFact == null) {
			return
		}
		if (conflictingEvent || byFact == null ||
			!byFact.authenticatesRequest(
				expectedSourceKind = SOURCE_KIND,
				expectedWriterProjectionId = fact.writerProjectionId,
				expectedWriterProjectionVersion = fact.writerProjectionVersion,
				expectedWriterBindingGeneration = fact.writerBindingGeneration,
				expectedWriterOwnerGeneration = fact.writerOwnerGeneration,
				expectedLogicalFactId = fact.logicalFactId,
				expectedSemanticRevision = fact.semanticRevision,
				expectedCursorRevision = fact.semanticRevision,
				expectedSourceEventId = fact.sourceEventId,
				expectedSourceAdmissionOrdinal = fact.sourceAdmissionOrdinal,
				expectedSourcePolicyRevision = fact.sourcePolicyRevision,
				expectedCaptureConsentEpoch = fact.captureConsentEpoch,
				expectedCollectedDataEpoch = fact.collectedDataEpoch,
				expectedScopeDeletionGeneration = fact.scopeDeletionGeneration,
				expectedLogicalRunScopeDigest =
					SourceDeletionFenceEntity.logicalServiceRunIdentity(
						SOURCE_KIND,
						PURPOSE,
						fact.logicalTrackingId,
						fact.serviceRunId,
					),
				currentRetainedFromMs = currentRetainedFromMs,
			)
		) {
			reject(CellCapturedWriteRejection.IDENTITY_COLLISION)
		}
		reject(CellCapturedWriteRejection.RETAINED_DATA)
	}

	private fun reject(reason: CellCapturedWriteRejection): Nothing =
		throw CellCapturedWriteRejectedException(reason)

	private companion object {
		const val SOURCE_KIND = SourceDestinationOwnerEntity.SOURCE_CELL
		const val DESTINATION = SourceDestinationOwnerEntity.DESTINATION_SESSION_CELL
		const val OWNER = SourceDestinationOwnerEntity.OWNER_CELL_SESSION_FACTS
		const val PURPOSE = SourceBrokerPurpose.SESSION_CAPTURE
		const val WRITER_ID = SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID
		const val WRITER_VERSION = SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION
		const val INSERT_IGNORED = -1L
		const val MAX_MANIFEST_SOURCE_ROWS = 32
		const val MAX_MANIFESTS_PER_RUN = 256
		const val MAX_MANIFEST_SOURCES_PER_RUN = 4_096
		const val MAX_FACT_REVISIONS = 256
		val EXPECTED_OWNER_SCOPE = "source-broker:$SOURCE_KIND"
		val ACCEPTED_REGISTRATION_STATES = setOf(
			ProviderRegistrationGenerationEntity.STATUS_ACTIVE,
			ProviderRegistrationGenerationEntity.STATUS_RETIRING,
			ProviderRegistrationGenerationEntity.STATUS_RETIRED,
		)
	}
}

@Suppress("CyclomaticComplexMethod") // Keep this fixed field or enum projection together.
private fun SessionManifestSourceEntity.isExactCellWriter(consentEpoch: Long, sessionMode: String?): Boolean =
	sourceKind == SourceDestinationOwnerEntity.SOURCE_CELL &&
		purpose == SourceBrokerPurpose.SESSION_CAPTURE && persistenceEligible &&
		this.consentEpoch == consentEpoch &&
		outputDestination == SourceDestinationOwnerEntity.DESTINATION_SESSION_CELL &&
		writerOwner == SourceDestinationOwnerEntity.OWNER_CELL_SESSION_FACTS &&
		SourceWriterGenerationContract.captureModeMaskForKnownBinding(writerBindingGeneration ?: -1L) != null &&
		writerOwnerGeneration == writerBindingGeneration?.let(SourceWriterGenerationContract::canonicalOwnerGeneration) &&
		writerProjectionId == SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID &&
		writerProjectionVersion == SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION &&
		when (sessionMode) {
			"MANUAL" -> true
			"AUTOMATIC" -> writerBindingGeneration == SourceWriterGenerationContract.FINAL_CAPTURE_BINDING_GENERATION
			else -> false
		}

private fun CellCapturedFactCursorEntity.matchesScope(fact: CellCapturedFactRevisionEntity): Boolean =
	logicalFactId == fact.logicalFactId && logicalTrackingId == fact.logicalTrackingId &&
		serviceRunId == fact.serviceRunId && sessionSegmentId == fact.sessionSegmentId &&
		writerOwnerGeneration == fact.writerOwnerGeneration &&
		collectedDataEpoch == fact.collectedDataEpoch &&
		scopeDeletionGeneration == fact.scopeDeletionGeneration

private fun CellCapturedFactCursorEntity.matchesCurrentRevision(
	fact: CellCapturedFactRevisionEntity,
): Boolean = matchesScope(fact) && latestSemanticRevision == fact.semanticRevision &&
	latestMutationId == fact.mutationId && latestEffectChecksum == fact.effectChecksum &&
	latestSourceAdmissionOrdinal == fact.sourceAdmissionOrdinal

private fun CellCapturedFactRevisionEntity.isAuthenticatedSuccessorOf(
	previous: CellCapturedFactRevisionEntity,
): Boolean = fixedCorrectionLineage() == previous.fixedCorrectionLineage() &&
	providerAcceptanceEndNanos.isExactSettlementOf(previous.providerAcceptanceEndNanos) &&
	authorizationEffectEndNanos.isExactSettlementOf(previous.authorizationEffectEndNanos) &&
	consentEffectEndNanos.isExactSettlementOf(previous.consentEffectEndNanos) &&
	sessionRunEffectEndNanos.isExactSettlementOf(previous.sessionRunEffectEndNanos) &&
	deletionEffectEndNanos.isExactSettlementOf(previous.deletionEffectEndNanos)

@Suppress("LongMethod")
private fun CellCapturedFactRevisionEntity.fixedCorrectionLineage(): List<Any?> = listOf(
	writerProjectionId,
	writerProjectionVersion,
	writerBindingGeneration,
	writerOwnerGeneration,
	logicalFactId,
	logicalTrackingId,
	serviceRunId,
	sessionSegmentId,
	purpose,
	sourceDeliveryIdentity,
	sourceEventId,
	sourceAdmissionOrdinal,
	walIntegrityIdentity,
	payloadChecksum,
	deliveryUnitIndex,
	deliveryUnitCount,
	sourceSequence,
	planAttribution,
	payloadVersion,
	canonicalProviderSemanticsDigest,
	sourceInstanceId,
	registrationGeneration,
	configurationRevision,
	physicalConfigurationFingerprint,
	authorizationRevision,
	authorizationFingerprint,
	purposeEligibilityMask,
	sourcePolicyRevision,
	captureConsentEpoch,
	manifestRevision,
	lifecycleLeaseGeneration,
	collectedDataEpoch,
	scopeDeletionGeneration,
	clockDomainId,
	storedZoneId,
	structuralEpochDay,
	providerAcceptanceStartNanos,
	authorizationEffectStartNanos,
	consentEffectStartNanos,
	sessionRunEffectStartNanos,
	deletionEffectStartNanos,
	maximumObservationAgeNanos,
	observedIntervalStartNanos,
	observedElapsedNanos,
	receivedElapsedNanos,
	observedWallTimeMs,
	wallTimeUncertaintyMs,
	acquiredAtMs,
	createdAtMs,
	qualityFlags,
	qualityConfidence,
)

private fun Long.isExactSettlementOf(previous: Long): Boolean =
	this == previous || (previous == Long.MAX_VALUE && this < Long.MAX_VALUE)

private fun List<SessionManifestSourceEntity>.sourceKinds(
	purpose: String,
	extraPredicate: (SessionManifestSourceEntity) -> Boolean,
): Set<SourceKind> = filter { source -> source.purpose == purpose && extraPredicate(source) }
	.map { source ->
		SourceKind.entries.singleOrNull { it.stableCode == source.sourceKind }
			?: throw IllegalArgumentException("Unknown source kind ${source.sourceKind}")
	}
	.toSet()

private val CellCapturedFactMutation.reference: CellAggregateFactReference
	get() = CellAggregateFactReference(identity, semanticRevision)

private fun CellCapturedFactRevisionEntity.persistedMutation() = CellCapturedFactMutation(
	identity = CellCapturedFactIdentity(
		sourceDeliveryIdentity = SourceDeliveryIdentity(sourceDeliveryIdentity),
		logicalTrackingId = LogicalTrackingId(logicalTrackingId),
		serviceRunId = ServiceRunId(serviceRunId),
		sessionSegmentId = sessionSegmentId,
		sessionManifestRevision = manifestRevision,
		collectedDataEpoch = collectedDataEpoch,
		scopeDeletionGeneration = scopeDeletionGeneration,
	),
	semanticRevision = semanticRevision,
	supersedesSemanticRevision = supersedesSemanticRevision,
)

private fun CellCapturedFactRevisionEntity.persistedEvidence(
	authority: CellCaptureAuthority,
	rawWallTimeUncertaintyMs: Long,
) = CellCapturedEvidenceBinding(
	sourceEventId = SourceEventId(sourceEventId),
	sourceKind = SourceKind.CELL,
	sourceDeliveryIdentity = SourceDeliveryIdentity(sourceDeliveryIdentity),
	sourceAdmissionOrdinal = sourceAdmissionOrdinal,
	walIntegrityIdentity = walIntegrityIdentity,
	payloadChecksum = payloadChecksum,
	deliveryUnitIndex = deliveryUnitIndex,
	deliveryUnitCount = deliveryUnitCount,
	sourceSequence = sourceSequence,
	planAttribution = PlanAttribution.valueOf(planAttribution),
	payloadVersion = payloadVersion,
	canonicalProviderSemanticsDigest = canonicalProviderSemanticsDigest,
	capturedAuthority = authority,
	observedIntervalStartElapsedRealtimeNanos = observedIntervalStartNanos,
	observedElapsedRealtimeNanos = observedElapsedNanos,
	receivedElapsedRealtimeNanos = receivedElapsedNanos,
	observedWallTimeMs = acquiredAtMs,
	wallTimeUncertaintyMs = rawWallTimeUncertaintyMs,
	acquiredAtMs = acquiredAtMs,
	createdAtMs = createdAtMs,
	qualityFlags = qualityFlags,
	qualityConfidence = qualityConfidence,
)

private fun CellCapturedFactRevisionEntity.persistedCoverage() = CellCoverageEvidence(
	providerIntervalStartElapsedRealtimeNanos = coverageIntervalStartNanos,
	providerIntervalEndElapsedRealtimeNanos = coverageIntervalEndNanos,
	submittedChildCount = submittedChildCount,
	acceptedChildCount = acceptedChildCount,
	staleChildCount = staleChildCount,
	futureTimeChildCount = futureTimeChildCount,
	missingTimeChildCount = missingTimeChildCount,
	clockUnverifiableChildCount = clockUnverifiableChildCount,
	authorityMismatchChildCount = authorityMismatchChildCount,
	unsupportedTechnologyChildCount = unsupportedTechnologyChildCount,
	expectedSubscriptionCount = null,
	observedSubscriptionCount = null,
	subscriptionCompleteness = CellSubscriptionCompleteness.valueOf(subscriptionCompleteness),
	childCompleteness = CellChildCompleteness.valueOf(childCompleteness),
)

private fun CellCapturedFactRevisionEntity.persistedAggregate(): CellIdentityFreeAggregate {
	val technologies = buildMap {
		putIfPositive(CellRadioTechnology.GSM, gsmCount)
		putIfPositive(CellRadioTechnology.CDMA, cdmaCount)
		putIfPositive(CellRadioTechnology.WCDMA, wcdmaCount)
		putIfPositive(CellRadioTechnology.TDSCDMA, tdscdmaCount)
		putIfPositive(CellRadioTechnology.LTE, lteCount)
		putIfPositive(CellRadioTechnology.NR, nrCount)
	}
	val quality = CellSignalQualityDistribution(
		unknownCount = requireNotNull(qualityUnknownCount),
		noneOrUnknownCount = requireNotNull(qualityNoneOrUnknownCount),
		poorCount = requireNotNull(qualityPoorCount),
		moderateCount = requireNotNull(qualityModerateCount),
		goodCount = requireNotNull(qualityGoodCount),
		greatCount = requireNotNull(qualityGreatCount),
	)
	return CellIdentityFreeAggregate(
		observationCount = requireNotNull(observationCount),
		registeredObservationCount = requireNotNull(registeredObservationCount),
		technologyMix = CellTechnologyMix(technologies),
		signalQuality = quality,
		weakPeriod = CellWeakPeriodEvidence(
			weakObservationCount = requireNotNull(weakObservationCount),
			knownQualityObservationCount = requireNotNull(knownQualityObservationCount),
			allKnownQualityIsWeak = requireNotNull(allKnownQualityIsWeak),
		),
	)
}

private fun MutableMap<CellRadioTechnology, Int>.putIfPositive(
	technology: CellRadioTechnology,
	count: Int?,
) {
	val value = requireNotNull(count)
	if (value > 0) {
		put(technology, value)
	}
}

private object CellCapturedPersistence {
	// This fixed saved-row projection retains every Cell fact field and nullable aggregate default together.
	@Suppress("LongMethod", "CyclomaticComplexMethod")
	fun map(fact: CellCapturedFact, ownerGeneration: Long, writerBindingGeneration: Long): CellCapturedFactRevisionEntity {
		val mutation = fact.mutation
		val identity = mutation.identity
		val authority = fact.authority
		val evidence = fact.evidenceBinding
		val coverage = fact.coverage
		val aggregate = (fact as? CellCapturedFact.Aggregate)?.aggregate
		val aggregateOwner = (fact as? CellCapturedFact.CoverageOnly)?.reusesAggregate?.reference
		val logicalFactId = CellCapturedFactRevisionIntegrity.logicalFactId(
			identity.sourceDeliveryIdentity.value,
			identity.logicalTrackingId.value,
			identity.serviceRunId.value,
			identity.sessionSegmentId,
			identity.sessionManifestRevision,
			identity.collectedDataEpoch,
			identity.scopeDeletionGeneration,
		)
		val unsigned = CellCapturedFactRevisionEntity(
			writerProjectionId = SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID,
			writerProjectionVersion = SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION,
			writerBindingGeneration = writerBindingGeneration,
			writerOwnerGeneration = ownerGeneration,
			logicalFactId = logicalFactId,
			semanticRevision = mutation.semanticRevision,
			supersedesSemanticRevision = mutation.supersedesSemanticRevision,
			mutationId = CellCapturedFactRevisionIntegrity.mutationId(logicalFactId, mutation.semanticRevision),
			factKind = if (aggregate == null) {
				CellCapturedFactRevisionEntity.FACT_KIND_COVERAGE_ONLY
			} else {
				CellCapturedFactRevisionEntity.FACT_KIND_AGGREGATE
			},
			aggregateOwnerLogicalFactId = aggregateOwner?.let { reference ->
				CellCapturedFactRevisionIntegrity.logicalFactId(
					reference.identity.sourceDeliveryIdentity.value,
					reference.identity.logicalTrackingId.value,
					reference.identity.serviceRunId.value,
					reference.identity.sessionSegmentId,
					reference.identity.sessionManifestRevision,
					reference.identity.collectedDataEpoch,
					reference.identity.scopeDeletionGeneration,
				)
			},
			aggregateOwnerSemanticRevision = aggregateOwner?.semanticRevision,
			logicalTrackingId = authority.logicalTrackingId.value,
			serviceRunId = authority.serviceRunId.value,
			sessionSegmentId = authority.sessionSegmentId,
			purpose = SourceBrokerPurpose.SESSION_CAPTURE,
			sourceDeliveryIdentity = identity.sourceDeliveryIdentity.value,
			sourceEventId = evidence.sourceEventId.value,
			sourceAdmissionOrdinal = evidence.sourceAdmissionOrdinal,
			walIntegrityIdentity = evidence.walIntegrityIdentity,
			payloadChecksum = evidence.payloadChecksum,
			deliveryUnitIndex = evidence.deliveryUnitIndex,
			deliveryUnitCount = evidence.deliveryUnitCount,
			sourceSequence = evidence.sourceSequence,
			planAttribution = evidence.planAttribution.name,
			payloadVersion = evidence.payloadVersion,
			canonicalProviderSemanticsDigest = evidence.canonicalProviderSemanticsDigest,
			sourceInstanceId = authority.sourceInstanceId.value,
			registrationGeneration = authority.registrationGeneration,
			configurationRevision = authority.configurationRevision,
			physicalConfigurationFingerprint = authority.physicalConfigurationFingerprint,
			authorizationRevision = authority.authorizationRevision,
			authorizationFingerprint = authority.authorizationFingerprint,
			purposeEligibilityMask = authority.purposeEligibilityMask,
			sourcePolicyRevision = authority.sourcePolicyRevision,
			captureConsentEpoch = authority.captureConsentEpoch,
			manifestRevision = authority.sessionManifestRevision,
			lifecycleLeaseGeneration = authority.lifecycleLeaseGeneration,
			collectedDataEpoch = authority.collectedDataEpoch,
			scopeDeletionGeneration = authority.scopeDeletionGeneration,
			clockDomainId = authority.clockDomainId,
			storedZoneId = authority.zoneId,
			structuralEpochDay = authority.structuralEpochDay,
			providerAcceptanceStartNanos = authority.temporalAuthority.providerAcceptance.startInclusiveNanos,
			providerAcceptanceEndNanos = authority.temporalAuthority.providerAcceptance.endExclusiveNanos,
			authorizationEffectStartNanos = authority.temporalAuthority.authorizationEffect.startInclusiveNanos,
			authorizationEffectEndNanos = authority.temporalAuthority.authorizationEffect.endExclusiveNanos,
			consentEffectStartNanos = authority.temporalAuthority.consentEffect.startInclusiveNanos,
			consentEffectEndNanos = authority.temporalAuthority.consentEffect.endExclusiveNanos,
			sessionRunEffectStartNanos = authority.temporalAuthority.sessionRunEffect.startInclusiveNanos,
			sessionRunEffectEndNanos = authority.temporalAuthority.sessionRunEffect.endExclusiveNanos,
			deletionEffectStartNanos = authority.temporalAuthority.deletionEffect.startInclusiveNanos,
			deletionEffectEndNanos = authority.temporalAuthority.deletionEffect.endExclusiveNanos,
			maximumObservationAgeNanos = authority.maximumObservationAgeNanos,
			observedIntervalStartNanos = evidence.observedIntervalStartElapsedRealtimeNanos,
			observedElapsedNanos = evidence.observedElapsedRealtimeNanos,
			receivedElapsedNanos = evidence.receivedElapsedRealtimeNanos,
			coverageIntervalStartNanos = coverage.providerIntervalStartElapsedRealtimeNanos,
			coverageIntervalEndNanos = coverage.providerIntervalEndElapsedRealtimeNanos,
			observedWallTimeMs = fact.observedWallTimeMs,
			wallTimeUncertaintyMs = fact.wallTimeUncertaintyMs,
			acquiredAtMs = evidence.acquiredAtMs,
			createdAtMs = evidence.createdAtMs,
			qualityFlags = evidence.qualityFlags,
			qualityConfidence = evidence.qualityConfidence,
			availability = fact.availability.name,
			submittedChildCount = coverage.submittedChildCount,
			acceptedChildCount = coverage.acceptedChildCount,
			staleChildCount = coverage.staleChildCount,
			futureTimeChildCount = coverage.futureTimeChildCount,
			missingTimeChildCount = coverage.missingTimeChildCount,
			clockUnverifiableChildCount = coverage.clockUnverifiableChildCount,
			authorityMismatchChildCount = coverage.authorityMismatchChildCount,
			unsupportedTechnologyChildCount = coverage.unsupportedTechnologyChildCount,
			subscriptionCompleteness = coverage.subscriptionCompleteness.name,
			childCompleteness = coverage.childCompleteness.name,
			observationCount = aggregate?.observationCount,
			registeredObservationCount = aggregate?.registeredObservationCount,
			gsmCount = aggregate?.technologyMix?.counts?.get(CellRadioTechnology.GSM) ?: aggregate?.let { 0 },
			cdmaCount = aggregate?.technologyMix?.counts?.get(CellRadioTechnology.CDMA) ?: aggregate?.let { 0 },
			wcdmaCount = aggregate?.technologyMix?.counts?.get(CellRadioTechnology.WCDMA) ?: aggregate?.let { 0 },
			tdscdmaCount = aggregate?.technologyMix?.counts?.get(CellRadioTechnology.TDSCDMA) ?: aggregate?.let { 0 },
			lteCount = aggregate?.technologyMix?.counts?.get(CellRadioTechnology.LTE) ?: aggregate?.let { 0 },
			nrCount = aggregate?.technologyMix?.counts?.get(CellRadioTechnology.NR) ?: aggregate?.let { 0 },
			qualityUnknownCount = aggregate?.signalQuality?.unknownCount,
			qualityNoneOrUnknownCount = aggregate?.signalQuality?.noneOrUnknownCount,
			qualityPoorCount = aggregate?.signalQuality?.poorCount,
			qualityModerateCount = aggregate?.signalQuality?.moderateCount,
			qualityGoodCount = aggregate?.signalQuality?.goodCount,
			qualityGreatCount = aggregate?.signalQuality?.greatCount,
			weakObservationCount = aggregate?.weakPeriod?.weakObservationCount,
			knownQualityObservationCount = aggregate?.weakPeriod?.knownQualityObservationCount,
			allKnownQualityIsWeak = aggregate?.weakPeriod?.allKnownQualityIsWeak,
			effectChecksum = ZERO_CHECKSUM,
			appliedAtMs = fact.observedWallTimeMs,
		)
		return unsigned.copy(effectChecksum = CellCapturedFactRevisionIntegrity.effectChecksum(unsigned))
	}

	private const val ZERO_CHECKSUM =
		"0000000000000000000000000000000000000000000000000000000000000000"
}

private fun CellCapturedFactRevisionEntity.toCursor(cursorRevision: Long) =
	CellCapturedFactCursorEntity(
		writerProjectionId = writerProjectionId,
		writerProjectionVersion = writerProjectionVersion,
		logicalFactId = logicalFactId,
		logicalTrackingId = logicalTrackingId,
		serviceRunId = serviceRunId,
		sessionSegmentId = sessionSegmentId,
		writerOwnerGeneration = writerOwnerGeneration,
		collectedDataEpoch = collectedDataEpoch,
		scopeDeletionGeneration = scopeDeletionGeneration,
		latestSemanticRevision = semanticRevision,
		latestMutationId = mutationId,
		latestEffectChecksum = effectChecksum,
		latestSourceAdmissionOrdinal = sourceAdmissionOrdinal,
		cursorRevision = cursorRevision,
		updatedAtMs = appliedAtMs,
	)

internal class CellCapturedWriteRejectedException(
	val reason: CellCapturedWriteRejection,
) : IllegalStateException(reason.name)
