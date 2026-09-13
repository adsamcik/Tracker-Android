package com.adsamcik.tracker.tracker.source.wifi

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.WifiCapturedFactCursorEntity
import com.adsamcik.tracker.shared.base.database.data.WifiCapturedFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.WifiCapturedFactRevisionIntegrity
import com.adsamcik.tracker.tracker.source.model.LogicalTrackingId
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.ServiceRunId
import com.adsamcik.tracker.tracker.source.model.SourceDeliveryIdentity
import com.adsamcik.tracker.tracker.source.model.SourceEventId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import javax.inject.Inject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal enum class WifiCapturedWriteRejection {
	DESTINATION_OWNER_CHANGED,
	MANIFEST_WRITER_AUTHORITY_MISMATCH,
	SOURCE_EVIDENCE_AUTHORITY_CHANGED,
	DELETED_SCOPE,
	SCOPE_DELETION_AUTHORITY_CHANGED,
	RETAINED_DATA,
	REVISION_CHAIN_MISMATCH,
	AGGREGATE_OWNER_MISMATCH,
	AGGREGATE_OWNER_HAS_DEPENDENTS,
	IDENTITY_COLLISION,
	CURSOR_CHANGED,
}

internal enum class WifiCapturedWriteCheckpoint { QUALIFIED, REVISION_INSERTED, CURSOR_ADVANCED }

internal sealed interface WifiCapturedWriteResult {
	data class Applied(
		val logicalFactId: String,
		val semanticRevision: Long,
		val cursorRevision: Long,
	) : WifiCapturedWriteResult

	data class Unchanged(
		val logicalFactId: String,
		val semanticRevision: Long,
		val cursorRevision: Long,
	) : WifiCapturedWriteResult

	data class NoEvidence(val classification: WifiCapturedFactClassification) : WifiCapturedWriteResult
	data class AdapterRejected(val reason: WifiWalAdapterRejection) : WifiCapturedWriteResult
	data class Rejected(val reason: WifiCapturedWriteRejection) : WifiCapturedWriteResult
}

/**
 * Dormant canonical Wi-Fi writer. Qualification, revision append, cursor CAS, and publication share
 * one Room transaction. Nothing invokes this writer from a provider or production runtime yet.
 */
internal class WifiCapturedFactWriter @Inject constructor(
	private val database: AppDatabase,
	private val adapter: WifiWalQualificationAdapter,
) {
	suspend fun write(eventId: SourceEventId): WifiCapturedWriteResult = write(eventId) { }

	internal suspend fun write(
		eventId: SourceEventId,
		checkpoint: suspend (WifiCapturedWriteCheckpoint) -> Unit,
	): WifiCapturedWriteResult = try {
		database.withTransaction {
			currentCoroutineContext().ensureActive()
			when (val result = adapter.qualify(eventId, ::classifyWithStoredState)) {
				is WifiWalAdapterResult.Rejected -> WifiCapturedWriteResult.AdapterRejected(result.reason)
				is WifiWalAdapterResult.Evaluated -> {
					checkpoint(WifiCapturedWriteCheckpoint.QUALIFIED)
					writeClassification(result.classification, checkpoint)
				}
			}
		}
	} catch (rejected: WifiCapturedWriteRejectedException) {
		WifiCapturedWriteResult.Rejected(rejected.reason)
	}

	private suspend fun classifyWithStoredState(
		input: WifiObservationInput,
		authority: WifiCaptureAuthority,
		deletion: WifiDeletionAuthority,
	): WifiCapturedFactClassification {
		val evidence = input.walEvidence
			?: return WifiCapturedFactClassifier.classify(input, authority, deletion)
		val delivery = evidence.sourceDeliveryIdentity
			?: return WifiCapturedFactClassifier.classify(input, authority, deletion)
		val logicalFactId = WifiCapturedFactRevisionIntegrity.logicalFactId(
			delivery.value,
			authority.logicalTrackingId.value,
			authority.serviceRunId.value,
			authority.sessionSegmentId,
			authority.sessionManifestRevision,
			authority.collectedDataEpoch,
			authority.scopeDeletionGeneration,
		)
		val dao = database.wifiCapturedFactDao()
		val exactCursor = dao.cursor(WRITER_ID, WRITER_VERSION, logicalFactId)
		if (exactCursor != null) {
			val current = dao.revision(
				WRITER_ID,
				WRITER_VERSION,
				logicalFactId,
				exactCursor.latestSemanticRevision,
			) ?: reject(WifiCapturedWriteRejection.REVISION_CHAIN_MISMATCH)
			val base = loadReusableFact(current, exactCursor, authority)
			if (exactCursor.latestSemanticRevision == Long.MAX_VALUE) {
				reject(WifiCapturedWriteRejection.REVISION_CHAIN_MISMATCH)
			}
			val classified = WifiCapturedFactClassifier.classify(
				input = input,
				expectedAuthority = authority,
				currentDeletionAuthority = deletion,
				semanticRevision = exactCursor.latestSemanticRevision + 1L,
				supersedesSemanticRevision = exactCursor.latestSemanticRevision,
				priorFact = base,
				correctionBase = base,
			)
			if (classified !is WifiCapturedFactClassification.Replay &&
				exactCursor.latestSemanticRevision >= MAX_FACT_REVISIONS
			) reject(WifiCapturedWriteRejection.REVISION_CHAIN_MISMATCH)
			return classified
		}

		val priorRow = dao.latestEffectiveBefore(
			WRITER_ID,
			WRITER_VERSION,
			authority.logicalTrackingId.value,
			authority.serviceRunId.value,
			authority.sessionSegmentId,
			authority.collectedDataEpoch,
			authority.scopeDeletionGeneration,
			evidence.sourceAdmissionOrdinal,
		)
		val priorOwner = priorRow?.let { row ->
			val cursor = dao.cursor(WRITER_ID, WRITER_VERSION, row.logicalFactId)
				?: reject(WifiCapturedWriteRejection.REVISION_CHAIN_MISMATCH)
			try {
				when (val reusable = loadReusableFact(row, cursor, authority)) {
					is WifiReusableFact.DirectAggregateOwner -> reusable
					is WifiReusableFact.CoverageOnly -> reusable.directAggregateOwner
				}
			} catch (rejected: WifiCapturedWriteRejectedException) {
				if (rejected.reason == WifiCapturedWriteRejection.RETAINED_DATA) null else throw rejected
			}
		}
		return WifiCapturedFactClassifier.classify(
			input,
			authority,
			deletion,
			priorFact = priorOwner,
		)
	}

	private suspend fun writeClassification(
		classification: WifiCapturedFactClassification,
		checkpoint: suspend (WifiCapturedWriteCheckpoint) -> Unit,
	): WifiCapturedWriteResult = when (classification) {
		is WifiCapturedFactClassification.FreshChanged -> writeFact(classification.fact, checkpoint)
		is WifiCapturedFactClassification.FreshUnchanged -> writeFact(classification.fact, checkpoint)
		is WifiCapturedFactClassification.Replay -> writeReplay(classification)
		else -> WifiCapturedWriteResult.NoEvidence(classification)
	}

	@Suppress("LongMethod", "CyclomaticComplexMethod")
	private suspend fun writeFact(
		fact: WifiCapturedFact,
		checkpoint: suspend (WifiCapturedWriteCheckpoint) -> Unit,
	): WifiCapturedWriteResult {
		val authority = fact.authority
		val owner = database.sourceDestinationOwnerDao().get(SOURCE_KIND, DESTINATION)
		if (owner?.owner != OWNER || owner.ownerGeneration != OWNER_GENERATION) {
			reject(WifiCapturedWriteRejection.DESTINATION_OWNER_CHANGED)
		}
		val binding = database.sourceSessionDao().manifestSource(
			authority.logicalTrackingId.value,
			authority.sessionManifestRevision,
			SOURCE_KIND,
			PURPOSE,
		)
		if (binding == null || !binding.isExactWifiWriter(authority.captureConsentEpoch)) {
			reject(WifiCapturedWriteRejection.MANIFEST_WRITER_AUTHORITY_MISMATCH)
		}
		val state = database.sourceEvidenceStateDao().get()
			?: reject(WifiCapturedWriteRejection.SOURCE_EVIDENCE_AUTHORITY_CHANGED)
		if (state.collectedDataEpoch != authority.collectedDataEpoch ||
			fact.evidenceBinding.sourceAdmissionOrdinal <= state.deletedSourceEventHighWaterOrdinal
		) reject(WifiCapturedWriteRejection.SOURCE_EVIDENCE_AUTHORITY_CHANGED)
		requireUndeletedScope(authority)
		val ownerCursorRevision = (fact as? WifiCapturedFact.CoverageOnly)?.let { coverage ->
			val ownerId = coverage.reusesAggregate.reference.logicalFactId()
			val cursor = database.wifiCapturedFactDao().cursor(WRITER_ID, WRITER_VERSION, ownerId)
				?: reject(WifiCapturedWriteRejection.AGGREGATE_OWNER_MISMATCH)
			cursor.cursorRevision
		}
		val mapped = WifiCapturedPersistence.map(fact, owner.ownerGeneration, ownerCursorRevision)
		if (mapped.earliestCoveredWallTimeMs()?.let { earliest ->
			state.retainedFromMs?.let { earliest < it }
		} == true) reject(WifiCapturedWriteRejection.RETAINED_DATA)

		val dao = database.wifiCapturedFactDao()
		val existing = dao.revision(WRITER_ID, WRITER_VERSION, mapped.logicalFactId, mapped.semanticRevision)
		if (existing != null) {
			if (existing != mapped || !WifiCapturedFactRevisionIntegrity.hasValidEffectChecksum(existing)) {
				reject(WifiCapturedWriteRejection.IDENTITY_COLLISION)
			}
			val cursor = dao.cursor(WRITER_ID, WRITER_VERSION, mapped.logicalFactId)
				?: reject(WifiCapturedWriteRejection.REVISION_CHAIN_MISMATCH)
			if (!cursor.matchesCurrentRevision(existing)) {
				reject(WifiCapturedWriteRejection.REVISION_CHAIN_MISMATCH)
			}
			return WifiCapturedWriteResult.Unchanged(
				mapped.logicalFactId,
				mapped.semanticRevision,
				cursor.cursorRevision,
			)
		}

		validateAggregateOwner(mapped, authority)
		val current = dao.cursor(WRITER_ID, WRITER_VERSION, mapped.logicalFactId)
		if (current == null) {
			if (mapped.semanticRevision != 1L || mapped.supersedesSemanticRevision != null) {
				reject(WifiCapturedWriteRejection.REVISION_CHAIN_MISMATCH)
			}
		} else {
			if (!current.matchesScope(mapped) ||
				current.latestSemanticRevision != mapped.supersedesSemanticRevision ||
				current.latestSemanticRevision == Long.MAX_VALUE ||
				current.latestSemanticRevision + 1L != mapped.semanticRevision ||
				mapped.sourceAdmissionOrdinal != current.latestSourceAdmissionOrdinal
			) reject(WifiCapturedWriteRejection.REVISION_CHAIN_MISMATCH)
			val previous = dao.revision(
				WRITER_ID,
				WRITER_VERSION,
				mapped.logicalFactId,
				current.latestSemanticRevision,
			) ?: reject(WifiCapturedWriteRejection.REVISION_CHAIN_MISMATCH)
			if (!mapped.isAuthenticatedSuccessorOf(previous)) {
				reject(WifiCapturedWriteRejection.REVISION_CHAIN_MISMATCH)
			}
			if (previous.factKind == WifiCapturedFactRevisionEntity.FACT_KIND_AGGREGATE &&
				dao.dependentCount(previous.logicalFactId, previous.semanticRevision) > 0L
			) reject(WifiCapturedWriteRejection.AGGREGATE_OWNER_HAS_DEPENDENTS)
		}

		if (dao.insertRevision(mapped) == INSERT_IGNORED) {
			reject(WifiCapturedWriteRejection.IDENTITY_COLLISION)
		}
		checkpoint(WifiCapturedWriteCheckpoint.REVISION_INSERTED)
		currentCoroutineContext().ensureActive()
		val nextCursorRevision = Math.addExact(current?.cursorRevision ?: 0L, 1L)
		val nextCursor = mapped.toCursor(nextCursorRevision)
		if (current == null) {
			if (dao.insertCursor(nextCursor) == INSERT_IGNORED) {
				reject(WifiCapturedWriteRejection.CURSOR_CHANGED)
			}
		} else if (dao.advanceCursorExact(
			WRITER_ID, WRITER_VERSION, mapped.logicalFactId, mapped.logicalTrackingId,
			mapped.serviceRunId, mapped.sessionSegmentId, mapped.writerOwnerGeneration,
			mapped.collectedDataEpoch, mapped.scopeDeletionGeneration,
			current.latestSemanticRevision, current.latestMutationId, current.latestEffectChecksum,
			current.latestSourceAdmissionOrdinal, current.cursorRevision, mapped.semanticRevision,
			mapped.mutationId, mapped.effectChecksum, mapped.sourceAdmissionOrdinal,
			nextCursorRevision, mapped.appliedAtMs,
		) != 1) {
			reject(WifiCapturedWriteRejection.CURSOR_CHANGED)
		}
		checkpoint(WifiCapturedWriteCheckpoint.CURSOR_ADVANCED)
		check(database.sourceEvidenceStateDao().incrementRevision(mapped.appliedAtMs) == 1) {
			"Unable to publish captured Wi-Fi fact revision"
		}
		return WifiCapturedWriteResult.Applied(mapped.logicalFactId, mapped.semanticRevision, nextCursorRevision)
	}

	private suspend fun writeReplay(
		replay: WifiCapturedFactClassification.Replay,
	): WifiCapturedWriteResult {
		val logicalFactId = replay.reference.logicalFactId()
		val dao = database.wifiCapturedFactDao()
		val row = dao.revision(WRITER_ID, WRITER_VERSION, logicalFactId, replay.reference.semanticRevision)
			?: reject(WifiCapturedWriteRejection.REVISION_CHAIN_MISMATCH)
		val cursor = dao.cursor(WRITER_ID, WRITER_VERSION, logicalFactId)
			?: reject(WifiCapturedWriteRejection.REVISION_CHAIN_MISMATCH)
		if (!cursor.matchesCurrentRevision(row)) reject(WifiCapturedWriteRejection.REVISION_CHAIN_MISMATCH)
		requireCurrentWriteAuthority(row)
		return WifiCapturedWriteResult.Unchanged(logicalFactId, row.semanticRevision, cursor.cursorRevision)
	}

	private suspend fun loadReusableFact(
		row: WifiCapturedFactRevisionEntity,
		cursor: WifiCapturedFactCursorEntity,
		currentAuthority: WifiCaptureAuthority,
	): WifiReusableFact {
		requireCurrentWriteAuthority(row)
		requireExactRevisionLineage(row, cursor)
		return try {
			val authority = row.persistedAuthority(currentAuthority)
			val mutation = row.persistedMutation()
			val evidence = row.persistedEvidence()
			val coverage = row.persistedCoverage()
			when (row.factKind) {
				WifiCapturedFactRevisionEntity.FACT_KIND_AGGREGATE ->
					WifiReusableFact.DirectAggregateOwner.from(WifiCapturedFact.Aggregate(
						mutation, authority, evidence, row.observedWallTimeMs,
						row.wallTimeUncertaintyMs, WifiAvailability.valueOf(row.availability),
						coverage, row.persistedAggregate(),
					))
				WifiCapturedFactRevisionEntity.FACT_KIND_COVERAGE_ONLY -> {
					val owner = loadDirectAggregateOwner(
						requireNotNull(row.aggregateOwnerLogicalFactId),
						requireNotNull(row.aggregateOwnerSemanticRevision),
						requireNotNull(row.aggregateOwnerCursorRevision),
						currentAuthority,
					)
					WifiReusableFact.CoverageOnly(
						mutation.reference,
						authority,
						WifiCapturedProductEffect(
							evidence, row.observedWallTimeMs, row.wallTimeUncertaintyMs,
							WifiAvailability.valueOf(row.availability), coverage,
							owner.productEffect.aggregate,
						),
						owner,
					)
				}
				else -> reject(WifiCapturedWriteRejection.REVISION_CHAIN_MISMATCH)
			}
		} catch (_: IllegalArgumentException) {
			reject(WifiCapturedWriteRejection.REVISION_CHAIN_MISMATCH)
		}
	}

	private suspend fun loadDirectAggregateOwner(
		logicalFactId: String,
		semanticRevision: Long,
		cursorRevision: Long,
		currentAuthority: WifiCaptureAuthority,
	): WifiReusableFact.DirectAggregateOwner {
		val dao = database.wifiCapturedFactDao()
		val row = dao.revision(WRITER_ID, WRITER_VERSION, logicalFactId, semanticRevision)
			?: reject(WifiCapturedWriteRejection.AGGREGATE_OWNER_MISMATCH)
		val cursor = dao.cursor(WRITER_ID, WRITER_VERSION, logicalFactId)
			?: reject(WifiCapturedWriteRejection.AGGREGATE_OWNER_MISMATCH)
		if (row.factKind != WifiCapturedFactRevisionEntity.FACT_KIND_AGGREGATE ||
			cursor.cursorRevision != cursorRevision || !cursor.matchesCurrentRevision(row)
		) reject(WifiCapturedWriteRejection.AGGREGATE_OWNER_MISMATCH)
		requireExactRevisionLineage(row, cursor)
		requireCurrentWriteAuthority(row)
		return try {
			val authority = row.persistedAuthority(currentAuthority)
			WifiReusableFact.DirectAggregateOwner.from(WifiCapturedFact.Aggregate(
				row.persistedMutation(), authority, row.persistedEvidence(), row.observedWallTimeMs,
				row.wallTimeUncertaintyMs, WifiAvailability.valueOf(row.availability),
				row.persistedCoverage(), row.persistedAggregate(),
			))
		} catch (_: IllegalArgumentException) {
			reject(WifiCapturedWriteRejection.AGGREGATE_OWNER_MISMATCH)
		}
	}

	private suspend fun requireExactRevisionLineage(
		current: WifiCapturedFactRevisionEntity,
		cursor: WifiCapturedFactCursorEntity,
	) {
		if (!cursor.matchesCurrentRevision(current) || current.semanticRevision > MAX_FACT_REVISIONS) {
			reject(WifiCapturedWriteRejection.REVISION_CHAIN_MISMATCH)
		}
		val revisions = database.wifiCapturedFactDao().revisionsForFact(
			WRITER_ID,
			WRITER_VERSION,
			current.logicalFactId,
			MAX_FACT_REVISIONS + 1,
		)
		if (revisions.size.toLong() != current.semanticRevision || revisions.firstOrNull() != current) {
			reject(WifiCapturedWriteRejection.REVISION_CHAIN_MISMATCH)
		}
		revisions.forEachIndexed { index, revision ->
			currentCoroutineContext().ensureActive()
			val expected = current.semanticRevision - index
			val older = revisions.getOrNull(index + 1)
			if (revision.semanticRevision != expected ||
				revision.supersedesSemanticRevision != (expected - 1L).takeIf { it > 0L } ||
				!WifiCapturedFactRevisionIntegrity.hasValidEffectChecksum(revision) ||
				(older != null && !revision.isAuthenticatedSuccessorOf(older))
			) reject(WifiCapturedWriteRejection.REVISION_CHAIN_MISMATCH)
		}
	}

	private suspend fun validateAggregateOwner(
		fact: WifiCapturedFactRevisionEntity,
		currentAuthority: WifiCaptureAuthority,
	) {
		if (fact.factKind != WifiCapturedFactRevisionEntity.FACT_KIND_COVERAGE_ONLY) return
		val owner = loadDirectAggregateOwner(
			requireNotNull(fact.aggregateOwnerLogicalFactId),
			requireNotNull(fact.aggregateOwnerSemanticRevision),
			requireNotNull(fact.aggregateOwnerCursorRevision),
			currentAuthority,
		)
		if (owner.reference.identity.logicalTrackingId.value != fact.logicalTrackingId ||
			owner.reference.identity.serviceRunId.value != fact.serviceRunId ||
			owner.reference.identity.sessionSegmentId != fact.sessionSegmentId ||
			owner.authority != currentAuthority ||
			owner.productEffect.aggregate?.observationCount != fact.acceptedResultCount
		) reject(WifiCapturedWriteRejection.AGGREGATE_OWNER_MISMATCH)
	}

	private suspend fun requireCurrentWriteAuthority(row: WifiCapturedFactRevisionEntity) {
		val owner = database.sourceDestinationOwnerDao().get(SOURCE_KIND, DESTINATION)
		if (owner?.owner != OWNER || owner.ownerGeneration != row.writerOwnerGeneration) {
			reject(WifiCapturedWriteRejection.DESTINATION_OWNER_CHANGED)
		}
		val binding = database.sourceSessionDao().manifestSource(
			row.logicalTrackingId,
			row.manifestRevision,
			SOURCE_KIND,
			PURPOSE,
		)
		if (binding == null || !binding.isExactWifiWriter(row.captureConsentEpoch)) {
			reject(WifiCapturedWriteRejection.MANIFEST_WRITER_AUTHORITY_MISMATCH)
		}
		val state = database.sourceEvidenceStateDao().get()
			?: reject(WifiCapturedWriteRejection.SOURCE_EVIDENCE_AUTHORITY_CHANGED)
		if (state.collectedDataEpoch != row.collectedDataEpoch ||
			row.sourceAdmissionOrdinal <= state.deletedSourceEventHighWaterOrdinal
		) reject(WifiCapturedWriteRejection.SOURCE_EVIDENCE_AUTHORITY_CHANGED)
		if (row.earliestCoveredWallTimeMs()?.let { earliest ->
			state.retainedFromMs?.let { earliest < it }
		} == true) reject(WifiCapturedWriteRejection.RETAINED_DATA)
		requireUndeletedScope(row.logicalTrackingId, row.serviceRunId, row.scopeDeletionGeneration)
	}

	private suspend fun requireUndeletedScope(authority: WifiCaptureAuthority) = requireUndeletedScope(
		authority.logicalTrackingId.value,
		authority.serviceRunId.value,
		authority.scopeDeletionGeneration,
	)

	private suspend fun requireUndeletedScope(
		logicalTrackingId: String,
		serviceRunId: String,
		expectedGeneration: Long,
	) {
		val digest = SourceDeletionFenceEntity.logicalServiceRunIdentity(
			SOURCE_KIND,
			PURPOSE,
			logicalTrackingId,
			serviceRunId,
		)
		if (database.sourceDeletionFenceDao().contains(
			SOURCE_KIND,
			PURPOSE,
			SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
			digest,
		)) reject(WifiCapturedWriteRejection.DELETED_SCOPE)
		val sourceFence = database.wifiCapturedFactDao().deletionGeneration(logicalTrackingId, serviceRunId)
		val actual = sourceFence?.let { state ->
			if (state.collectedDataEpoch != database.sourceEvidenceStateDao().get()?.collectedDataEpoch) {
				reject(WifiCapturedWriteRejection.SCOPE_DELETION_AUTHORITY_CHANGED)
			}
			state.generation
		} ?: 0L
		if (actual != expectedGeneration) {
			reject(WifiCapturedWriteRejection.SCOPE_DELETION_AUTHORITY_CHANGED)
		}
	}

	private fun reject(reason: WifiCapturedWriteRejection): Nothing =
		throw WifiCapturedWriteRejectedException(reason)

	private companion object {
		const val SOURCE_KIND = SourceDestinationOwnerEntity.SOURCE_WIFI
		const val DESTINATION = SourceDestinationOwnerEntity.DESTINATION_SESSION_WIFI
		const val OWNER = SourceDestinationOwnerEntity.OWNER_WIFI_SESSION_FACTS
		const val OWNER_GENERATION = SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION
		const val PURPOSE = SourceBrokerPurpose.SESSION_CAPTURE
		const val WRITER_ID = SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_ID
		const val WRITER_VERSION = SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_VERSION
		const val MAX_FACT_REVISIONS = 256
		const val INSERT_IGNORED = -1L
	}
}

private fun SessionManifestSourceEntity.isExactWifiWriter(consentEpoch: Long): Boolean =
	sourceKind == SourceDestinationOwnerEntity.SOURCE_WIFI &&
		purpose == SourceBrokerPurpose.SESSION_CAPTURE && persistenceEligible &&
		this.consentEpoch == consentEpoch &&
		outputDestination == SourceDestinationOwnerEntity.DESTINATION_SESSION_WIFI &&
		writerOwner == SourceDestinationOwnerEntity.OWNER_WIFI_SESSION_FACTS &&
		writerOwnerGeneration == SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION &&
		writerProjectionId == SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_ID &&
		writerProjectionVersion == SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_VERSION &&
		writerBindingGeneration == SourceDestinationOwnerEntity.WIFI_FACT_BINDING_GENERATION

private fun WifiAggregateFactReference.logicalFactId(): String =
	WifiCapturedFactRevisionIntegrity.logicalFactId(
		identity.sourceDeliveryIdentity.value,
		identity.logicalTrackingId.value,
		identity.serviceRunId.value,
		identity.sessionSegmentId,
		identity.sessionManifestRevision,
		identity.collectedDataEpoch,
		identity.scopeDeletionGeneration,
	)

private val WifiCapturedFactMutation.reference: WifiAggregateFactReference
	get() = WifiAggregateFactReference(identity, semanticRevision)

private fun WifiCapturedFactCursorEntity.matchesScope(row: WifiCapturedFactRevisionEntity): Boolean =
	logicalFactId == row.logicalFactId && logicalTrackingId == row.logicalTrackingId &&
		serviceRunId == row.serviceRunId && sessionSegmentId == row.sessionSegmentId &&
		writerOwnerGeneration == row.writerOwnerGeneration &&
		collectedDataEpoch == row.collectedDataEpoch &&
		scopeDeletionGeneration == row.scopeDeletionGeneration

private fun WifiCapturedFactCursorEntity.matchesCurrentRevision(
	row: WifiCapturedFactRevisionEntity,
): Boolean = matchesScope(row) && latestSemanticRevision == row.semanticRevision &&
	latestMutationId == row.mutationId && latestEffectChecksum == row.effectChecksum &&
	latestSourceAdmissionOrdinal == row.sourceAdmissionOrdinal

private fun WifiCapturedFactRevisionEntity.isAuthenticatedSuccessorOf(
	previous: WifiCapturedFactRevisionEntity,
): Boolean = fixedCorrectionLineage() == previous.fixedCorrectionLineage() &&
	providerAcceptanceEndNanos.isExactSettlementOf(previous.providerAcceptanceEndNanos) &&
	authorizationEffectEndNanos.isExactSettlementOf(previous.authorizationEffectEndNanos) &&
	sessionRunEffectEndNanos.isExactSettlementOf(previous.sessionRunEffectEndNanos)

private fun WifiCapturedFactRevisionEntity.fixedCorrectionLineage(): List<Any?> = listOf(
	writerProjectionId, writerProjectionVersion, writerBindingGeneration, writerOwnerGeneration,
	logicalFactId, factKind, aggregateOwnerLogicalFactId, aggregateOwnerSemanticRevision,
	aggregateOwnerCursorRevision, logicalTrackingId, serviceRunId, sessionSegmentId, purpose,
	capturedSourceCodes, controlSourceCodes, sourceEventId, sourceAdmissionOrdinal,
	walIntegrityIdentity, payloadChecksum, sourceDeliveryIdentity, deliveryUnitIndex, deliveryUnitCount,
	sourceSequence, planAttribution, sourceInstanceId, registrationGeneration, configurationRevision,
	physicalConfigurationFingerprint, authorizationRevision, authorizationFingerprint,
	purposeEligibilityMask, sourcePolicyRevision, captureConsentEpoch, manifestRevision,
	lifecycleLeaseGeneration, collectedDataEpoch, scopeDeletionGeneration, clockDomainId, storedZoneId,
	planPayloadVersion, planPayloadChecksum, maximumObservationAgeNanos, resultContract,
	registrationAppliedAtNanos, providerAcceptanceStartNanos, authorizationEffectStartNanos,
	sessionRunEffectStartNanos, observedIntervalStartNanos, observedElapsedNanos, receivedElapsedNanos,
	coverageIntervalStartNanos, coverageIntervalEndNanos, observedWallTimeMs, wallTimeUncertaintyMs,
	acquiredAtMs, qualityFlags, qualityConfidence, availability, submittedResultCount,
	acceptedResultCount, staleResultCount, clockUnverifiableResultCount, malformedResultCount,
	coverageCompleteness, observationCount, twoPointFourGhzCount, fiveGhzCount, sixGhzCount,
	otherBandCount, strongestSignalDbm, weakestSignalDbm, signalSumDbm,
)

private fun Long.isExactSettlementOf(previous: Long): Boolean =
	this == previous || (previous == Long.MAX_VALUE && this < Long.MAX_VALUE)

private fun WifiCapturedFactRevisionEntity.persistedMutation() = WifiCapturedFactMutation(
	identity = WifiCapturedFactIdentity(
		SourceEventId(sourceEventId),
		sourceAdmissionOrdinal,
		walIntegrityIdentity,
		payloadChecksum,
		SourceDeliveryIdentity(sourceDeliveryIdentity),
		deliveryUnitIndex,
		LogicalTrackingId(logicalTrackingId),
		ServiceRunId(serviceRunId),
		sessionSegmentId,
		manifestRevision,
		collectedDataEpoch,
		scopeDeletionGeneration,
	),
	semanticRevision = semanticRevision,
	supersedesSemanticRevision = supersedesSemanticRevision,
)

private fun WifiCapturedFactRevisionEntity.persistedAuthority(
	current: WifiCaptureAuthority,
): WifiCaptureAuthority {
	val captured = capturedSourceCodes.toSourceKinds()
	val control = controlSourceCodes.toSourceKinds()
	require(current.capturedSources == captured && current.controlSources == control)
	val expectedStatic = current.copy(
		capturedSources = captured,
		controlSources = control,
		temporalAuthority = WifiCaptureTemporalAuthority(
			WifiProviderTimeInterval(providerAcceptanceStartNanos, providerAcceptanceEndNanos),
			WifiProviderTimeInterval(authorizationEffectStartNanos, authorizationEffectEndNanos),
			WifiProviderTimeInterval(sessionRunEffectStartNanos, sessionRunEffectEndNanos),
		),
	)
	require(expectedStatic.logicalTrackingId.value == logicalTrackingId)
	require(expectedStatic.serviceRunId.value == serviceRunId)
	require(expectedStatic.sessionSegmentId == sessionSegmentId)
	require(expectedStatic.sourceInstanceId.value == sourceInstanceId)
	require(expectedStatic.registrationGeneration == registrationGeneration)
	require(expectedStatic.configurationRevision == configurationRevision)
	require(expectedStatic.physicalConfigurationFingerprint == physicalConfigurationFingerprint)
	require(expectedStatic.authorizationRevision == authorizationRevision)
	require(expectedStatic.authorizationFingerprint == authorizationFingerprint)
	require(expectedStatic.purposeEligibilityMask == purposeEligibilityMask)
	require(expectedStatic.sourcePolicyRevision == sourcePolicyRevision)
	require(expectedStatic.captureConsentEpoch == captureConsentEpoch)
	require(expectedStatic.sessionManifestRevision == manifestRevision)
	require(expectedStatic.lifecycleLeaseGeneration == lifecycleLeaseGeneration)
	require(expectedStatic.collectedDataEpoch == collectedDataEpoch)
	require(expectedStatic.scopeDeletionGeneration == scopeDeletionGeneration)
	require(expectedStatic.clockDomainId == clockDomainId && expectedStatic.zoneId == storedZoneId)
	require(expectedStatic.acquisitionConfiguration.maximumObservationAgeNanos == maximumObservationAgeNanos)
	require(expectedStatic.acquisitionConfiguration.resultContract.name == resultContract)
	require(expectedStatic.serializedAcquisitionPlan.payloadVersion == planPayloadVersion)
	require(expectedStatic.serializedAcquisitionPlan.payloadChecksum == planPayloadChecksum)
	require(expectedStatic.appliedRegistration.appliedAtElapsedRealtimeNanos == registrationAppliedAtNanos)
	require(current.temporalAuthority.isExactSettlementOf(expectedStatic.temporalAuthority))
	return expectedStatic
}

private fun WifiCaptureTemporalAuthority.isExactSettlementOf(
	previous: WifiCaptureTemporalAuthority,
): Boolean = providerAcceptance.startInclusiveNanos == previous.providerAcceptance.startInclusiveNanos &&
	providerAcceptance.endExclusiveNanos.isExactSettlementOf(previous.providerAcceptance.endExclusiveNanos) &&
	authorizationEffect.startInclusiveNanos == previous.authorizationEffect.startInclusiveNanos &&
	authorizationEffect.endExclusiveNanos.isExactSettlementOf(previous.authorizationEffect.endExclusiveNanos) &&
	sessionRunEffect.startInclusiveNanos == previous.sessionRunEffect.startInclusiveNanos &&
	sessionRunEffect.endExclusiveNanos.isExactSettlementOf(previous.sessionRunEffect.endExclusiveNanos)

private fun String.toSourceKinds(): Set<SourceKind> = if (isEmpty()) emptySet() else
	split(',').map { code ->
		SourceKind.entries.single { it.stableCode == code.toInt() }
	}.toSet()

private fun WifiCapturedFactRevisionEntity.persistedEvidence() = WifiCapturedEvidenceBinding(
	SourceEventId(sourceEventId), sourceAdmissionOrdinal, walIntegrityIdentity, payloadChecksum,
	SourceDeliveryIdentity(sourceDeliveryIdentity), deliveryUnitIndex, deliveryUnitCount, null,
	sourceSequence, PlanAttribution.valueOf(planAttribution), configurationRevision,
	planPayloadChecksum, clockDomainId, observedIntervalStartNanos, observedElapsedNanos,
	receivedElapsedNanos, observedWallTimeMs, wallTimeUncertaintyMs, acquiredAtMs,
	qualityFlags, qualityConfidence,
)

private fun WifiCapturedFactRevisionEntity.persistedCoverage() = WifiCoverageEvidence(
	coverageIntervalStartNanos, coverageIntervalEndNanos, receivedElapsedNanos,
	submittedResultCount, acceptedResultCount, staleResultCount,
	clockUnverifiableResultCount, malformedResultCount,
	WifiCoverageCompleteness.valueOf(coverageCompleteness),
)

private fun WifiCapturedFactRevisionEntity.persistedAggregate() = WifiIdentityFreeAggregate(
	requireNotNull(observationCount),
	WifiBandMix(requireNotNull(twoPointFourGhzCount), requireNotNull(fiveGhzCount),
		requireNotNull(sixGhzCount), requireNotNull(otherBandCount)),
	WifiSignalQualitySummary(requireNotNull(observationCount), requireNotNull(strongestSignalDbm),
		requireNotNull(weakestSignalDbm), requireNotNull(signalSumDbm)),
)

private fun WifiCapturedFactRevisionEntity.earliestCoveredWallTimeMs(): Long? = runCatching {
	val spanMs = (observedElapsedNanos - coverageIntervalStartNanos) / 1_000_000L
	Math.subtractExact(Math.subtractExact(observedWallTimeMs, spanMs), wallTimeUncertaintyMs)
		.takeIf { it >= 0L }
}.getOrNull()

private object WifiCapturedPersistence {
	@Suppress("LongMethod")
	fun map(
		fact: WifiCapturedFact,
		ownerGeneration: Long,
		aggregateOwnerCursorRevision: Long?,
	): WifiCapturedFactRevisionEntity {
		val mutation = fact.mutation
		val identity = mutation.identity
		val authority = fact.authority
		val evidence = fact.evidenceBinding
		val coverage = fact.coverage
		val aggregate = (fact as? WifiCapturedFact.Aggregate)?.aggregate
		val aggregateOwner = (fact as? WifiCapturedFact.CoverageOnly)?.reusesAggregate?.reference
		val logicalId = WifiCapturedFactRevisionIntegrity.logicalFactId(
			identity.sourceDeliveryIdentity.value, identity.logicalTrackingId.value,
			identity.serviceRunId.value, identity.sessionSegmentId, identity.sessionManifestRevision,
			identity.collectedDataEpoch, identity.scopeDeletionGeneration,
		)
		val unsigned = WifiCapturedFactRevisionEntity(
			SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_ID,
			SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_VERSION,
			SourceDestinationOwnerEntity.WIFI_FACT_BINDING_GENERATION,
			ownerGeneration,
			logicalId,
			mutation.semanticRevision,
			mutation.supersedesSemanticRevision,
			WifiCapturedFactRevisionIntegrity.mutationId(logicalId, mutation.semanticRevision),
			if (aggregate == null) WifiCapturedFactRevisionEntity.FACT_KIND_COVERAGE_ONLY
			else WifiCapturedFactRevisionEntity.FACT_KIND_AGGREGATE,
			aggregateOwner?.logicalFactId(),
			aggregateOwner?.semanticRevision,
			aggregateOwnerCursorRevision,
			authority.logicalTrackingId.value,
			authority.serviceRunId.value,
			authority.sessionSegmentId,
			SourceBrokerPurpose.SESSION_CAPTURE,
			authority.capturedSources.toCodes(),
			authority.controlSources.toCodes(),
			evidence.sourceEventId.value,
			evidence.sourceAdmissionOrdinal,
			evidence.walIntegrityIdentity,
			evidence.payloadChecksum,
			evidence.sourceDeliveryIdentity.value,
			evidence.deliveryUnitIndex,
			evidence.deliveryUnitCount,
			evidence.sourceSequence,
			evidence.planAttribution.name,
			authority.sourceInstanceId.value,
			authority.registrationGeneration,
			authority.configurationRevision,
			authority.physicalConfigurationFingerprint,
			authority.authorizationRevision,
			authority.authorizationFingerprint,
			authority.purposeEligibilityMask,
			authority.sourcePolicyRevision,
			authority.captureConsentEpoch,
			authority.sessionManifestRevision,
			authority.lifecycleLeaseGeneration,
			authority.collectedDataEpoch,
			authority.scopeDeletionGeneration,
			authority.clockDomainId,
			authority.zoneId,
			authority.serializedAcquisitionPlan.payloadVersion,
			authority.serializedAcquisitionPlan.payloadChecksum,
			authority.acquisitionConfiguration.maximumObservationAgeNanos,
			authority.acquisitionConfiguration.resultContract.name,
			requireNotNull(authority.appliedRegistration.appliedAtElapsedRealtimeNanos),
			authority.temporalAuthority.providerAcceptance.startInclusiveNanos,
			authority.temporalAuthority.providerAcceptance.endExclusiveNanos,
			authority.temporalAuthority.authorizationEffect.startInclusiveNanos,
			authority.temporalAuthority.authorizationEffect.endExclusiveNanos,
			authority.temporalAuthority.sessionRunEffect.startInclusiveNanos,
			authority.temporalAuthority.sessionRunEffect.endExclusiveNanos,
			evidence.observedIntervalStartElapsedRealtimeNanos,
			evidence.observedElapsedRealtimeNanos,
			evidence.receivedElapsedRealtimeNanos,
			coverage.providerIntervalStartElapsedRealtimeNanos,
			coverage.providerIntervalEndElapsedRealtimeNanos,
			fact.observedWallTimeMs,
			fact.wallTimeUncertaintyMs,
			evidence.acquiredAtMs,
			evidence.qualityFlags,
			evidence.qualityConfidence,
			fact.availability.name,
			coverage.submittedResultCount,
			coverage.acceptedResultCount,
			coverage.staleResultCount,
			coverage.clockUnverifiableResultCount,
			coverage.malformedResultCount,
			coverage.completeness.name,
			aggregate?.observationCount,
			aggregate?.bandMix?.twoPointFourGhzCount,
			aggregate?.bandMix?.fiveGhzCount,
			aggregate?.bandMix?.sixGhzCount,
			aggregate?.bandMix?.otherCount,
			aggregate?.signalQuality?.strongestSignalLevelDbm,
			aggregate?.signalQuality?.weakestSignalLevelDbm,
			aggregate?.signalQuality?.signalLevelSumDbm,
			ZERO_CHECKSUM,
			fact.observedWallTimeMs,
		)
		return unsigned.copy(effectChecksum = WifiCapturedFactRevisionIntegrity.effectChecksum(unsigned))
	}

	private const val ZERO_CHECKSUM =
		"0000000000000000000000000000000000000000000000000000000000000000"
}

private fun Set<SourceKind>.toCodes(): String = map(SourceKind::stableCode).sorted().joinToString(",")

private fun WifiCapturedFactRevisionEntity.toCursor(revision: Long) = WifiCapturedFactCursorEntity(
	writerProjectionId, writerProjectionVersion, logicalFactId, logicalTrackingId, serviceRunId,
	sessionSegmentId, writerOwnerGeneration, collectedDataEpoch, scopeDeletionGeneration,
	semanticRevision, mutationId, effectChecksum, sourceAdmissionOrdinal, revision, appliedAtMs,
)

private class WifiCapturedWriteRejectedException(
	val reason: WifiCapturedWriteRejection,
) : IllegalStateException(reason.name)
