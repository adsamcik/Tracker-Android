package com.adsamcik.tracker.tracker.source.cell

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.CellCapturedFactCursorEntity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedFactRevisionIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.tracker.source.model.SourceEventId
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
}

/**
 * Dormant canonical Cell writer. Qualification and mutation share one Room transaction, so an
 * adapter result can never outlive the provider, authorization, consent, deletion, or epoch state
 * that created it. No production component invokes this class until a later source-local cutover.
 */
internal class CellCapturedFactWriter @Inject constructor(
	private val database: AppDatabase,
	private val adapter: CellWalQualificationAdapter,
) {
	suspend fun write(eventId: SourceEventId): CellCapturedWriteResult = try {
		database.withTransaction {
			when (val result = adapter.qualify(eventId)) {
				is CellWalAdapterResult.Rejected -> CellCapturedWriteResult.AdapterRejected(result.reason)
				is CellWalAdapterResult.Evaluated -> writeClassification(result.classification)
			}
		}
	} catch (rejected: CellCapturedWriteRejectedException) {
		CellCapturedWriteResult.Rejected(rejected.reason)
	}

	private suspend fun writeClassification(
		classification: CellCapturedFactClassification,
	): CellCapturedWriteResult = when (classification) {
		is CellCapturedFactClassification.FreshChanged -> writeFact(classification.fact)
		is CellCapturedFactClassification.FreshUnchanged -> writeFact(classification.fact)
		is CellCapturedFactClassification.Replay -> writeReplay(classification)
		else -> CellCapturedWriteResult.NoEvidence(classification)
	}

	@Suppress("LongMethod", "CyclomaticComplexMethod")
	private suspend fun writeFact(fact: CellCapturedFact): CellCapturedWriteResult {
		val authority = fact.authority
		val logicalTrackingId = authority.logicalTrackingId.value
		val serviceRunId = authority.serviceRunId.value
		val owner = database.sourceDestinationOwnerDao().get(SOURCE_KIND, DESTINATION)
		if (owner?.owner != OWNER || owner.ownerGeneration != OWNER_GENERATION) {
			reject(CellCapturedWriteRejection.DESTINATION_OWNER_CHANGED)
		}
		val binding = database.sourceSessionDao().manifestSource(
			logicalTrackingId,
			authority.sessionManifestRevision,
			SOURCE_KIND,
			PURPOSE,
		)
		if (binding == null || !binding.isExactCellWriter(authority.captureConsentEpoch)) {
			reject(CellCapturedWriteRejection.MANIFEST_WRITER_AUTHORITY_MISMATCH)
		}
		val state = database.sourceEvidenceStateDao().get()
			?: reject(CellCapturedWriteRejection.SOURCE_EVIDENCE_AUTHORITY_CHANGED)
		if (state.collectedDataEpoch != authority.collectedDataEpoch ||
			fact.evidenceBinding.sourceAdmissionOrdinal <= state.deletedSourceEventHighWaterOrdinal
		) reject(CellCapturedWriteRejection.SOURCE_EVIDENCE_AUTHORITY_CHANGED)
		if (state.retainedFromMs?.let { retainedFrom ->
			val earliest = if (fact.observedWallTimeMs <= fact.wallTimeUncertaintyMs) {
				0L
			} else {
				fact.observedWallTimeMs - fact.wallTimeUncertaintyMs
			}
			earliest < retainedFrom
		} == true) reject(CellCapturedWriteRejection.RETAINED_DATA)
		requireUndeletedScope(logicalTrackingId, serviceRunId, authority.scopeDeletionGeneration)

		val mapped = CellCapturedPersistence.map(fact, owner.ownerGeneration)
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
			if (!cursor.matchesScope(mapped) || cursor.latestSemanticRevision < mapped.semanticRevision ||
				(cursor.latestSemanticRevision == mapped.semanticRevision &&
					(cursor.latestMutationId != mapped.mutationId ||
						cursor.latestEffectChecksum != mapped.effectChecksum ||
						cursor.latestSourceAdmissionOrdinal != mapped.sourceAdmissionOrdinal))
			) reject(CellCapturedWriteRejection.REVISION_CHAIN_MISMATCH)
			return CellCapturedWriteResult.Unchanged(
				mapped.logicalFactId,
				mapped.semanticRevision,
				cursor.cursorRevision,
			)
		}

		validateAggregateOwner(mapped)
		val current = dao.cursor(WRITER_ID, WRITER_VERSION, mapped.logicalFactId)
		if (current == null) {
			if (mapped.semanticRevision != 1L || mapped.supersedesSemanticRevision != null) {
				reject(CellCapturedWriteRejection.REVISION_CHAIN_MISMATCH)
			}
		} else if (!current.matchesScope(mapped) ||
			current.latestSemanticRevision != mapped.supersedesSemanticRevision ||
			current.latestSemanticRevision == Long.MAX_VALUE ||
			current.latestSemanticRevision + 1L != mapped.semanticRevision ||
			mapped.sourceAdmissionOrdinal <= current.latestSourceAdmissionOrdinal
		) {
			reject(CellCapturedWriteRejection.REVISION_CHAIN_MISMATCH)
		}

		if (dao.insertRevision(mapped) == INSERT_IGNORED) {
			reject(CellCapturedWriteRejection.IDENTITY_COLLISION)
		}
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
		check(database.sourceEvidenceStateDao().incrementRevision(mapped.appliedAtMs) == 1) {
			"Unable to publish captured Cell fact revision"
		}
		return CellCapturedWriteResult.Applied(
			mapped.logicalFactId,
			mapped.semanticRevision,
			nextCursorRevision,
		)
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
		val cursor = database.cellCapturedFactDao().cursor(WRITER_ID, WRITER_VERSION, logicalFactId)
			?: reject(CellCapturedWriteRejection.REVISION_CHAIN_MISMATCH)
		if (!CellCapturedFactRevisionIntegrity.hasValidEffectChecksum(revision) ||
			!cursor.matchesScope(revision) || cursor.latestSemanticRevision < revision.semanticRevision ||
			(cursor.latestSemanticRevision == revision.semanticRevision &&
				(cursor.latestMutationId != revision.mutationId ||
					cursor.latestEffectChecksum != revision.effectChecksum))
		) reject(CellCapturedWriteRejection.REVISION_CHAIN_MISMATCH)
		return CellCapturedWriteResult.Unchanged(
			logicalFactId,
			revision.semanticRevision,
			cursor.cursorRevision,
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
		) reject(CellCapturedWriteRejection.DELETED_SCOPE)
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

	private suspend fun validateAggregateOwner(fact: CellCapturedFactRevisionEntity) {
		if (fact.factKind != CellCapturedFactRevisionEntity.FACT_KIND_COVERAGE_ONLY) return
		val ownerLogicalId = requireNotNull(fact.aggregateOwnerLogicalFactId)
		val ownerRevision = requireNotNull(fact.aggregateOwnerSemanticRevision)
		val owner = database.cellCapturedFactDao().revision(
			WRITER_ID,
			WRITER_VERSION,
			ownerLogicalId,
			ownerRevision,
		) ?: reject(CellCapturedWriteRejection.AGGREGATE_OWNER_MISMATCH)
		if (owner.factKind != CellCapturedFactRevisionEntity.FACT_KIND_AGGREGATE ||
			!CellCapturedFactRevisionIntegrity.hasValidEffectChecksum(owner) ||
			owner.logicalTrackingId != fact.logicalTrackingId || owner.serviceRunId != fact.serviceRunId ||
			owner.sessionSegmentId != fact.sessionSegmentId ||
			owner.captureConsentEpoch != fact.captureConsentEpoch ||
			owner.collectedDataEpoch != fact.collectedDataEpoch ||
			owner.scopeDeletionGeneration != fact.scopeDeletionGeneration ||
			owner.storedZoneId != fact.storedZoneId || owner.structuralEpochDay != fact.structuralEpochDay
		) reject(CellCapturedWriteRejection.AGGREGATE_OWNER_MISMATCH)
	}

	private fun reject(reason: CellCapturedWriteRejection): Nothing =
		throw CellCapturedWriteRejectedException(reason)

	private companion object {
		const val SOURCE_KIND = SourceDestinationOwnerEntity.SOURCE_CELL
		const val DESTINATION = SourceDestinationOwnerEntity.DESTINATION_SESSION_CELL
		const val OWNER = SourceDestinationOwnerEntity.OWNER_CELL_SESSION_FACTS
		const val OWNER_GENERATION = SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION
		const val PURPOSE = SourceBrokerPurpose.SESSION_CAPTURE
		const val WRITER_ID = SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID
		const val WRITER_VERSION = SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION
		const val INSERT_IGNORED = -1L
	}
}

private fun SessionManifestSourceEntity.isExactCellWriter(consentEpoch: Long): Boolean =
	sourceKind == SourceDestinationOwnerEntity.SOURCE_CELL &&
		purpose == SourceBrokerPurpose.SESSION_CAPTURE && persistenceEligible &&
		this.consentEpoch == consentEpoch &&
		outputDestination == SourceDestinationOwnerEntity.DESTINATION_SESSION_CELL &&
		writerOwner == SourceDestinationOwnerEntity.OWNER_CELL_SESSION_FACTS &&
		writerOwnerGeneration == SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION &&
		writerProjectionId == SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID &&
		writerProjectionVersion == SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION &&
		writerBindingGeneration == SourceDestinationOwnerEntity.CELL_FACT_BINDING_GENERATION

private fun CellCapturedFactCursorEntity.matchesScope(fact: CellCapturedFactRevisionEntity): Boolean =
	logicalFactId == fact.logicalFactId && logicalTrackingId == fact.logicalTrackingId &&
		serviceRunId == fact.serviceRunId && sessionSegmentId == fact.sessionSegmentId &&
		writerOwnerGeneration == fact.writerOwnerGeneration &&
		collectedDataEpoch == fact.collectedDataEpoch &&
		scopeDeletionGeneration == fact.scopeDeletionGeneration

private object CellCapturedPersistence {
	@Suppress("LongMethod")
	fun map(fact: CellCapturedFact, ownerGeneration: Long): CellCapturedFactRevisionEntity {
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
			writerBindingGeneration = SourceDestinationOwnerEntity.CELL_FACT_BINDING_GENERATION,
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

private class CellCapturedWriteRejectedException(
	val reason: CellCapturedWriteRejection,
) : IllegalStateException(reason.name)
