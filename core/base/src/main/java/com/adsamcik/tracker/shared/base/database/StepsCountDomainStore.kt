package com.adsamcik.tracker.shared.base.database

import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsFactIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionIntegrity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainCompletenessMarkerEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainOwnerRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainReceiptIntegrity
import com.adsamcik.tracker.shared.base.database.data.StepsSessionCompletenessIntegrity
import com.adsamcik.tracker.shared.model.steps.StepsCounterDomainToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

enum class StepsCountDomainWriteResult {
	INSERTED,
	EXACT_REPLAY,
	SCHEMA_UNAVAILABLE,
	STORED_EVIDENCE_UNVERIFIABLE,
	NOT_APPLICABLE,
	AUTHORITY_PENDING,
	UNPROVEN,
	IDENTITY_CONFLICT,
	REVISION_GAP,
	TERMINAL_OWNER,
}

data class StepsCountDomainOwnerLookupKey(
	val ownerKind: String,
	val ownerIdentity: String,
	val ownerRevision: Long,
) {
	init {
		require(ownerKind in StepsCountDomainOwnerRevisionEntity.BINDABLE_OWNER_KINDS)
		require(StepsCountDomainReceiptIntegrity.isOpaque(ownerIdentity))
		require(ownerRevision > 0L)
	}
}

data class StepsCountDomainOwnerLineageKey(
	val ownerKind: String,
	val ownerIdentity: String,
)

private data class CountDomainOwnerScopeKey(
	val ownerKind: String,
	val scopeIdentity: String,
	val ownerIdentity: String,
)

data class StepsCountDomainStoredOwner(
	val owner: StepsCountDomainOwnerRevisionEntity,
	val receipt: StepsCountDomainReceiptEntity?,
	val completenessMarker: StepsCountDomainCompletenessMarkerEntity? = null,
)

data class StepsCountDomainRetirementEvidence(
	val providerFlushOutcome: String,
	val registrationRemovalOutcome: String,
) {
	init {
		require(providerFlushOutcome.isNotBlank())
		require(registrationRemovalOutcome.isNotBlank())
	}
}

object StepsRetirementTerminality {
	@Suppress("LongParameterList")
	fun isTerminal(
		stopStatus: String,
		registrationRemovalOutcome: String,
		providerFlushOutcome: String,
		providerCoverage: String,
		appDrainComplete: Boolean,
		lastAdmissionOrdinal: Long?,
		unresolvedSequenceStart: Long?,
		unresolvedSequenceEnd: Long?,
	): Boolean {
		if (registrationRemovalOutcome !in setOf("REMOVED", "NOT_REGISTERED") ||
			providerFlushOutcome !in setOf("COMPLETE", "NOT_SUPPORTED", "NOT_REQUESTED")
		) {
			return false
		}
		return when (stopStatus) {
			"COMPLETE" -> appDrainComplete
			"PROCESS_RESTARTED" ->
				!appDrainComplete && providerCoverage == "PROVIDER_COMPLETENESS_UNOBSERVABLE"
			"PARTIAL_UNOBSERVABLE" ->
				(appDrainComplete && lastAdmissionOrdinal != null) ||
					(!appDrainComplete &&
						unresolvedSequenceStart != null &&
						unresolvedSequenceEnd != null &&
						unresolvedSequenceStart <= unresolvedSequenceEnd)
			else -> false
		}
	}
}

sealed interface StepsTerminalCompletenessAuthentication {
	data class Authenticated(
		val completeness: SourceSessionCompletenessEntity,
		val retirementEvidence: StepsCountDomainRetirementEvidence,
		val countDomainCollectedDataEpoch: Long?,
	) : StepsTerminalCompletenessAuthentication

	data object Absent : StepsTerminalCompletenessAuthentication
	data object Unverifiable : StepsTerminalCompletenessAuthentication
}

sealed interface StepsTerminalProductAuthentication {
	data object Materializable : StepsTerminalProductAuthentication
	data object TerminalUnavailable : StepsTerminalProductAuthentication
	data object SchemaUnavailable : StepsTerminalProductAuthentication
	data object Unverifiable : StepsTerminalProductAuthentication
}

sealed interface StepsCountDomainOwnerRead {
	data class Ready(
		val owners: Map<StepsCountDomainOwnerLookupKey, StepsCountDomainStoredOwner>,
		val latestRevisions: Map<StepsCountDomainOwnerLineageKey, Long>,
	) : StepsCountDomainOwnerRead

	data object SchemaUnavailable : StepsCountDomainOwnerRead
	data object Overflow : StepsCountDomainOwnerRead
	data object Unverifiable : StepsCountDomainOwnerRead
}

sealed interface StepsCountDomainMaintenanceResult {
	data object SchemaUnavailable : StepsCountDomainMaintenanceResult
	data object StoredEvidenceUnverifiable : StepsCountDomainMaintenanceResult
	data object Overflow : StepsCountDomainMaintenanceResult
	data class Applied(
		val removedOwners: Long,
		val removedReceipts: Long,
	) : StepsCountDomainMaintenanceResult
}

enum class StepsCountDomainFullClearMode {
	REMOVE_ALL,
	PRESERVE_TERMINAL,
}

/**
 * Source-owned bridge used until the serialized AppDatabase owner registers the additive entities.
 *
 * Calls must occur inside the producer's existing Room transaction. A completely absent namespace
 * or exact empty Room scaffold preserves pre-P5 behavior with SCHEMA_UNAVAILABLE until the schema
 * callback installs its triggers and sentinel. Any other partial, markerless, legacy, or corrupt
 * namespace is STORED_EVIDENCE_UNVERIFIABLE and never activates.
 */
@Suppress("TooManyFunctions")
class StepsCountDomainStore(
	private val database: AppDatabase,
) {
	fun isInstalled(): Boolean =
		StepsCountDomainSchema.inspect(database.openHelper.writableDatabase) ==
			StepsCountDomainSchemaState.ValidV2

	suspend fun recordSessionWal(
		row: SourceEventWalEntity,
		counterDomainToken: StepsCounterDomainToken?,
	): StepsCountDomainWriteResult = authenticateCountDomainWrite {
		recordSessionWalAuthenticated(row, counterDomainToken)
	}

	private suspend fun recordSessionWalAuthenticated(
		row: SourceEventWalEntity,
		counterDomainToken: StepsCounterDomainToken?,
	): StepsCountDomainWriteResult {
		if (row.sourceKind != SourceDestinationOwnerEntity.SOURCE_STEPS ||
			row.authorizationPurposeEligibilityMask and
			SourceBrokerPurpose.MASK_SESSION_CAPTURE == 0L
		) {
			return StepsCountDomainWriteResult.NOT_APPLICABLE
		}
		writeSchemaFailure()?.let { return it }
		val logicalTrackingId = row.logicalTrackingId
			?: return StepsCountDomainWriteResult.UNPROVEN
		val serviceRunId = row.serviceRunId
			?: return StepsCountDomainWriteResult.UNPROVEN
		val authorityRevision = row.authorizationRevision
			?: return StepsCountDomainWriteResult.UNPROVEN
		val authorityFingerprint = row.authorizationFingerprint
			?: return StepsCountDomainWriteResult.UNPROVEN
		if (!row.hasQualifiedIntegrity()) return StepsCountDomainWriteResult.IDENTITY_CONFLICT
		if ((counterDomainToken != null &&
				row.payloadVersion < StepsCounterDomainToken.MINIMUM_DURABLE_PAYLOAD_VERSION) ||
			(counterDomainToken == null &&
				row.payloadVersion == StepsCounterDomainToken.MINIMUM_DURABLE_PAYLOAD_VERSION)
		) {
			return StepsCountDomainWriteResult.IDENTITY_CONFLICT
		}

		val ownerIdentity = StepsCountDomainReceiptIntegrity.sessionWalOwnerIdentity(
			row.admissionOrdinal,
			row.eventId,
		)
		val scopeIdentity = StepsCountDomainReceiptIntegrity.sessionRunScopeIdentity(
			logicalTrackingId,
			serviceRunId,
		)
		val latestOwner = database.openHelper.writableDatabase.queryLatestOwner(
			StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_WAL,
			scopeIdentity,
			ownerIdentity,
		)
		val hasGenerationSafeToken =
			row.payloadVersion >= StepsCounterDomainToken.COUNTER_EPOCH_GENERATION_PAYLOAD_VERSION &&
				counterDomainToken != null
		if (latestOwner?.operation == StepsCountDomainOwnerRevisionEntity.OPERATION_UNPROVEN &&
			hasGenerationSafeToken
		) {
			return StepsCountDomainWriteResult.UNPROVEN
		}
		// Payload v6 carried one token through COUNTER_RESET and therefore cannot prove that any
		// retained window stayed within one physical counter epoch. Generation-safe authority starts
		// at v7; all older WAL remains decodable for fact recovery but is permanently UNPROVEN here.
		if (!hasGenerationSafeToken) {
			return appendTerminalUnproven(
				ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_WAL,
				scopeIdentity = scopeIdentity,
				ownerIdentity = ownerIdentity,
				ownerRevision = 1L,
				ownerEffectChecksum = row.integrityIdentity,
				linkedAtMs = row.createdAtMs,
			)
		}
		return appendBoundOwner(
			receipt = nativeReceipt(
				ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_WAL,
				scopeIdentity = scopeIdentity,
				ownerIdentity = ownerIdentity,
				ownerRevision = 1L,
				counterDomainToken = counterDomainToken,
				registrationGeneration = row.registrationGeneration,
				collectedDataEpoch = row.capturedCollectedDataEpoch,
				authorityRevision = authorityRevision,
				authorityFingerprint = authorityFingerprint,
				coverageKind = StepsCountDomainReceiptEntity.COVERAGE_ADMITTED_WINDOW,
				coverageVersion = row.payloadVersion,
				effectChecksum = row.integrityIdentity,
				completionEvidenceChecksum = null,
			),
			scopeIdentity = scopeIdentity,
			ownerEffectChecksum = row.integrityIdentity,
			linkedAtMs = row.createdAtMs,
		)
	}

	suspend fun recordSessionFact(
		fact: StepFactRevisionEntity,
	): StepsCountDomainWriteResult = authenticateCountDomainWrite {
		recordSessionFactAuthenticated(fact)
	}

	private suspend fun recordSessionFactAuthenticated(
		fact: StepFactRevisionEntity,
	): StepsCountDomainWriteResult {
		if (fact.originKind != StepFactRevisionEntity.ORIGIN_LIVE_WAL ||
			fact.operation != StepFactRevisionEntity.OPERATION_UPSERT
		) {
			return StepsCountDomainWriteResult.NOT_APPLICABLE
		}
		writeSchemaFailure()?.let { return it }
		if (!StepFactRevisionIntegrity.hasValidCanonicalLiveWalFact(fact)) {
			return StepsCountDomainWriteResult.IDENTITY_CONFLICT
		}
		val admissionOrdinal = requireNotNull(fact.sourceAdmissionOrdinal)
		val eventId = requireNotNull(fact.sourceEventId)
		val scopeIdentity = StepsCountDomainReceiptIntegrity.sessionRunScopeIdentity(
			requireNotNull(fact.logicalTrackingId),
			requireNotNull(fact.serviceRunId),
		)
		val ownerIdentity = StepsCountDomainReceiptIntegrity.sessionFactOwnerIdentity(
			fact.writerProjectionId,
			fact.writerProjectionVersion,
			fact.logicalFactId,
		)
		val walOwner = loadExactOwner(
			StepsCountDomainOwnerLookupKey(
				StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_WAL,
				StepsCountDomainReceiptIntegrity.sessionWalOwnerIdentity(admissionOrdinal, eventId),
				1L,
			),
			scopeIdentity,
		) ?: return appendTerminalUnproven(
			ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_FACT,
			scopeIdentity = scopeIdentity,
			ownerIdentity = ownerIdentity,
			ownerRevision = fact.semanticRevision,
			ownerEffectChecksum = fact.effectChecksum,
			linkedAtMs = fact.appliedAtMs,
		)
		val walReceipt = walOwner.receipt
		if (walOwner.owner.operation == StepsCountDomainOwnerRevisionEntity.OPERATION_UNPROVEN) {
			return appendTerminalUnproven(
				ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_FACT,
				scopeIdentity = scopeIdentity,
				ownerIdentity = ownerIdentity,
				ownerRevision = fact.semanticRevision,
				ownerEffectChecksum = fact.effectChecksum,
				linkedAtMs = fact.appliedAtMs,
			)
		}
		if (walReceipt == null || !walOwner.isAuthentic() ||
			walOwner.owner.scopeIdentity != scopeIdentity ||
			walReceipt.collectedDataEpoch != fact.collectedDataEpoch
		) {
			return StepsCountDomainWriteResult.IDENTITY_CONFLICT
		}
		val coverageKind = when (fact.coverageKind) {
			StepFactRevisionEntity.COVERAGE_BASELINE ->
				StepsCountDomainReceiptEntity.COVERAGE_BASELINE
			StepFactRevisionEntity.COVERAGE_COVERED ->
				StepsCountDomainReceiptEntity.COVERAGE_COVERED
			StepFactRevisionEntity.COVERAGE_RESET_GAP ->
				StepsCountDomainReceiptEntity.COVERAGE_RESET_GAP
			StepFactRevisionEntity.COVERAGE_PARTIAL ->
				StepsCountDomainReceiptEntity.COVERAGE_PARTIAL
			else -> return StepsCountDomainWriteResult.IDENTITY_CONFLICT
		}
		val receipt = nativeReceiptFrom(
			source = walReceipt,
			ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_FACT,
			scopeIdentity = scopeIdentity,
			ownerIdentity = ownerIdentity,
			ownerRevision = fact.semanticRevision,
			coverageKind = coverageKind,
			coverageVersion = fact.writerProjectionVersion,
			effectChecksum = fact.effectChecksum,
			completionEvidenceChecksum = null,
		)
		return appendBoundOwner(
			receipt = receipt,
			scopeIdentity = scopeIdentity,
			ownerEffectChecksum = fact.effectChecksum,
			linkedAtMs = fact.appliedAtMs,
		)
	}

	@Suppress("ComplexCondition", "CyclomaticComplexMethod", "LongMethod", "ReturnCount")
	suspend fun recordSessionCompleteness(
		row: SourceSessionCompletenessEntity,
		retirementEvidence: StepsCountDomainRetirementEvidence,
	): StepsCountDomainWriteResult = authenticateCountDomainWrite {
		recordSessionCompletenessAuthenticated(row, retirementEvidence)
	}

	@Suppress("ComplexCondition", "CyclomaticComplexMethod", "LongMethod", "ReturnCount")
	private suspend fun recordSessionCompletenessAuthenticated(
		row: SourceSessionCompletenessEntity,
		retirementEvidence: StepsCountDomainRetirementEvidence,
	): StepsCountDomainWriteResult {
		if (row.sourceKind != SourceDestinationOwnerEntity.SOURCE_STEPS) {
			return StepsCountDomainWriteResult.NOT_APPLICABLE
		}
		writeSchemaFailure()?.let { return it }
		if (!row.hasTerminalRetirement(retirementEvidence)) {
			return StepsCountDomainWriteResult.AUTHORITY_PENDING
		}
		val scopeIdentity = StepsCountDomainReceiptIntegrity.sessionRunScopeIdentity(
			row.logicalTrackingId,
			row.serviceRunId,
		)
		val ownerEffectChecksum =
			StepsCountDomainReceiptIntegrity.completenessEffectChecksum(row)
		val ownerIdentity = StepsCountDomainReceiptIntegrity.sessionCompletenessOwnerIdentity(
			row.logicalTrackingId,
			row.serviceRunId,
			row.sourceInstanceId,
			row.registrationGeneration,
		)
		val ownerRevision = StepsCountDomainReceiptIntegrity.completenessOwnerRevision(row)
		val timeline = authenticatedStepsCompletenessTimeline(
			row.logicalTrackingId,
			row.serviceRunId,
		) ?: return StepsCountDomainWriteResult.STORED_EVIDENCE_UNVERIFIABLE
		val orderedTimeline =
			timeline.sortedBy(SourceSessionCompletenessEntity::registrationGeneration)
		val rowIndex = orderedTimeline.indexOfFirst { candidate ->
			candidate.sourceInstanceId == row.sourceInstanceId &&
				candidate.registrationGeneration == row.registrationGeneration
		}
		if (rowIndex < 0 || orderedTimeline[rowIndex] != row) {
			return StepsCountDomainWriteResult.STORED_EVIDENCE_UNVERIFIABLE
		}
		val timelinePrefix = orderedTimeline.take(rowIndex + 1)
		val timelineChecksum =
			StepsCountDomainReceiptIntegrity.registrationTimelineChecksum(timelinePrefix)
		val timelineWithinBound = orderedTimeline.size <= MAX_COMPLETENESS_TIMELINE
		val priorTimelineIsComplete = timelineWithinBound && timelinePrefix.dropLast(1).all { prior ->
			val priorOwnerIdentity =
				StepsCountDomainReceiptIntegrity.sessionCompletenessOwnerIdentity(
					prior.logicalTrackingId,
					prior.serviceRunId,
					prior.sourceInstanceId,
					prior.registrationGeneration,
				)
			val priorOwner = loadExactOwner(
				StepsCountDomainOwnerLookupKey(
					StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS,
					priorOwnerIdentity,
					StepsCountDomainReceiptIntegrity.completenessOwnerRevision(prior),
				),
				scopeIdentity,
			)
			priorOwner?.isAuthentic() == true
		}
		val exactComplete = row.hasExactCompleteRetirement(retirementEvidence) &&
			timelineWithinBound &&
			StepsSessionCompletenessIntegrity.hasValidRegisteredTimeline(
				timelinePrefix,
				row.logicalTrackingId,
				row.serviceRunId,
			) && priorTimelineIsComplete
		val marker = completenessMarker(
			row = row,
			ownerIdentity = ownerIdentity,
			ownerRevision = ownerRevision,
			retirementEvidence = retirementEvidence,
			timelineChecksum = timelineChecksum,
			exactComplete = exactComplete,
		)
		val admissionOrdinal = row.lastAdmissionOrdinal
		if (!exactComplete || admissionOrdinal == null) {
			return appendTerminalUnproven(
				ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS,
				scopeIdentity = scopeIdentity,
				ownerIdentity = ownerIdentity,
				ownerRevision = ownerRevision,
				ownerEffectChecksum = ownerEffectChecksum,
				linkedAtMs = row.updatedAtMs,
				completenessMarker = marker,
			)
		}
		val wal = database.sourceEventWalDao().getByAdmissionOrdinal(admissionOrdinal)
			?: return appendTerminalUnproven(
				ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS,
				scopeIdentity = scopeIdentity,
				ownerIdentity = ownerIdentity,
				ownerRevision = ownerRevision,
				ownerEffectChecksum = ownerEffectChecksum,
				linkedAtMs = row.updatedAtMs,
				completenessMarker = marker.asUnproven(),
			)
		if (wal.logicalTrackingId != row.logicalTrackingId ||
			wal.serviceRunId != row.serviceRunId ||
			wal.sourceInstanceId != row.sourceInstanceId ||
			wal.registrationGeneration != row.registrationGeneration ||
			wal.sourceSequence != row.lastSourceSequence
		) {
			return StepsCountDomainWriteResult.IDENTITY_CONFLICT
		}
		val walOwner = loadExactOwner(
			StepsCountDomainOwnerLookupKey(
				StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_WAL,
				StepsCountDomainReceiptIntegrity.sessionWalOwnerIdentity(
					admissionOrdinal,
					wal.eventId,
				),
				1L,
			),
			scopeIdentity,
		) ?: return if (canonicalProjectionCommittedThrough(admissionOrdinal)) {
			appendTerminalUnproven(
				ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS,
				scopeIdentity = scopeIdentity,
				ownerIdentity = ownerIdentity,
				ownerRevision = ownerRevision,
				ownerEffectChecksum = ownerEffectChecksum,
				linkedAtMs = row.updatedAtMs,
				completenessMarker = marker.asUnproven(),
			)
		} else {
			StepsCountDomainWriteResult.AUTHORITY_PENDING
		}
		if (walOwner.owner.operation == StepsCountDomainOwnerRevisionEntity.OPERATION_UNPROVEN) {
			return appendTerminalUnproven(
				ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS,
				scopeIdentity = scopeIdentity,
				ownerIdentity = ownerIdentity,
				ownerRevision = ownerRevision,
				ownerEffectChecksum = ownerEffectChecksum,
				linkedAtMs = row.updatedAtMs,
				completenessMarker = marker.asUnproven(),
			)
		}
		val walReceipt = walOwner.receipt ?: return StepsCountDomainWriteResult.IDENTITY_CONFLICT
		if (!walOwner.isAuthentic() || walOwner.owner.scopeIdentity != scopeIdentity) {
			return StepsCountDomainWriteResult.IDENTITY_CONFLICT
		}
		val receipt = nativeReceiptFrom(
			source = walReceipt,
			ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS,
			scopeIdentity = scopeIdentity,
			ownerIdentity = ownerIdentity,
			ownerRevision = ownerRevision,
			coverageKind = StepsCountDomainReceiptEntity.COVERAGE_COMPLETE_RUN,
			coverageVersion = COMPLETENESS_COVERAGE_VERSION,
			effectChecksum = ownerEffectChecksum,
			completionEvidenceChecksum = marker.evidenceChecksum,
		)
		return appendBoundOwner(
			receipt,
			scopeIdentity,
			ownerEffectChecksum,
			row.updatedAtMs,
			marker,
		)
	}

	@Suppress("ComplexCondition", "LongMethod", "ReturnCount")
	suspend fun authenticateTerminalSessionCompleteness(
		logicalTrackingId: String,
		serviceRunId: String,
		sourceInstanceId: String,
		registrationGeneration: Long,
	): StepsTerminalCompletenessAuthentication {
		if (logicalTrackingId.isBlank() || serviceRunId.isBlank() || sourceInstanceId.isBlank() ||
			registrationGeneration <= 0L
		) {
			return StepsTerminalCompletenessAuthentication.Unverifiable
		}
		val timeline = authenticatedStepsCompletenessTimeline(logicalTrackingId, serviceRunId)
			?: return StepsTerminalCompletenessAuthentication.Unverifiable
		if (
			!StepsSessionCompletenessIntegrity.hasValidRegisteredTimeline(
				timeline,
				logicalTrackingId,
				serviceRunId,
			)
		) {
			return StepsTerminalCompletenessAuthentication.Unverifiable
		}
		val ownerIdentity = StepsCountDomainReceiptIntegrity.sessionCompletenessOwnerIdentity(
			logicalTrackingId,
			serviceRunId,
			sourceInstanceId,
			registrationGeneration,
		)
		val completeness = timeline.singleOrNull { row ->
			row.sourceInstanceId == sourceInstanceId &&
				row.registrationGeneration == registrationGeneration
		} ?: return terminalCompletenessAbsence(
			ownerIdentity,
			StepsCountDomainReceiptIntegrity.sessionRunScopeIdentity(
				logicalTrackingId,
				serviceRunId,
			),
		)
		val orderedTimeline =
			timeline.sortedBy(SourceSessionCompletenessEntity::registrationGeneration)
		val completenessIndex = orderedTimeline.indexOf(completeness)
		if (completenessIndex < 0 || timeline != orderedTimeline) {
			return StepsTerminalCompletenessAuthentication.Unverifiable
		}
		val timelinePrefix = orderedTimeline.take(completenessIndex + 1)
		val ownerRevision = StepsCountDomainReceiptIntegrity.completenessOwnerRevision(completeness)
		val key = StepsCountDomainOwnerLookupKey(
			StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS,
			ownerIdentity,
			ownerRevision,
		)
		val ready = readOwners(listOf(key), limit = 1) as? StepsCountDomainOwnerRead.Ready
			?: return StepsTerminalCompletenessAuthentication.Unverifiable
		val stored = ready.owners[key]
			?: return StepsTerminalCompletenessAuthentication.Unverifiable
		if (ready.latestRevisions[
				StepsCountDomainOwnerLineageKey(key.ownerKind, key.ownerIdentity)
			] != ownerRevision
		) {
			return StepsTerminalCompletenessAuthentication.Unverifiable
		}
		val marker = stored.completenessMarker
			?: return StepsTerminalCompletenessAuthentication.Unverifiable
		val expectedScope = StepsCountDomainReceiptIntegrity.sessionRunScopeIdentity(
			logicalTrackingId,
			serviceRunId,
		)
		val expectedEffect = StepsCountDomainReceiptIntegrity.completenessEffectChecksum(completeness)
		if (!marker.matches(stored.owner) ||
			stored.owner.scopeIdentity != expectedScope ||
			stored.owner.ownerEffectChecksum != expectedEffect ||
			stored.owner.linkedAtMs != completeness.updatedAtMs ||
			marker.lastAdmissionOrdinal != completeness.lastAdmissionOrdinal ||
			marker.lastSourceSequence != completeness.lastSourceSequence ||
			marker.registrationTimelineChecksum !=
				StepsCountDomainReceiptIntegrity.registrationTimelineChecksum(timelinePrefix)
		) {
			return StepsTerminalCompletenessAuthentication.Unverifiable
		}
		val retirementEvidence = StepsCountDomainRetirementEvidence(
			providerFlushOutcome = marker.providerFlushOutcome,
			registrationRemovalOutcome = marker.registrationRemovalOutcome,
		)
		if (!retirementEvidence.hasFinalRegistrationRemoval()) {
			return StepsTerminalCompletenessAuthentication.Unverifiable
		}
		val receipt = stored.receipt
		val authentic = when (stored.owner.operation) {
			StepsCountDomainOwnerRevisionEntity.OPERATION_BIND ->
				stored.isAuthentic() &&
					receipt != null &&
					receipt.registrationGeneration == registrationGeneration &&
					receipt.coverageKind ==
					StepsCountDomainReceiptEntity.COVERAGE_COMPLETE_RUN &&
					completeness.hasExactCompleteRetirement(retirementEvidence)
			StepsCountDomainOwnerRevisionEntity.OPERATION_UNPROVEN ->
				receipt == null && stored.owner.receiptIdentity == null &&
					marker.terminalState ==
					StepsCountDomainCompletenessMarkerEntity.STATE_UNPROVEN
			else -> false
		}
		return if (authentic) {
			StepsTerminalCompletenessAuthentication.Authenticated(
				completeness,
				retirementEvidence,
				receipt?.collectedDataEpoch,
			)
		} else {
			StepsTerminalCompletenessAuthentication.Unverifiable
		}
	}

	@Suppress("ComplexCondition", "LongMethod", "ReturnCount")
	suspend fun authenticateTerminalProductDisposition(
		logicalTrackingId: String,
		serviceRunId: String,
		timeline: List<SourceSessionCompletenessEntity>,
	): StepsTerminalProductAuthentication {
		if (
			logicalTrackingId.isBlank() ||
			serviceRunId.isBlank() ||
			timeline.isEmpty() ||
			timeline.size > MAX_COMPLETENESS_TIMELINE ||
			!StepsSessionCompletenessIntegrity.hasValidTimeline(
				timeline,
				logicalTrackingId,
				serviceRunId,
			)
		) {
			return StepsTerminalProductAuthentication.Unverifiable
		}
		val orderedTimeline =
			timeline.sortedBy(SourceSessionCompletenessEntity::registrationGeneration)
		if (timeline != orderedTimeline) {
			return StepsTerminalProductAuthentication.Unverifiable
		}
		val keys = orderedTimeline.map { row ->
			StepsCountDomainOwnerLookupKey(
				StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS,
				StepsCountDomainReceiptIntegrity.sessionCompletenessOwnerIdentity(
					row.logicalTrackingId,
					row.serviceRunId,
					row.sourceInstanceId,
					row.registrationGeneration,
				),
				StepsCountDomainReceiptIntegrity.completenessOwnerRevision(row),
			)
		}
		val ready = when (val read = readOwners(keys, MAX_COMPLETENESS_TIMELINE)) {
			is StepsCountDomainOwnerRead.Ready -> read
			StepsCountDomainOwnerRead.SchemaUnavailable ->
				return StepsTerminalProductAuthentication.SchemaUnavailable
			StepsCountDomainOwnerRead.Overflow,
			StepsCountDomainOwnerRead.Unverifiable,
			-> return StepsTerminalProductAuthentication.Unverifiable
		}
		var unavailable = false
		for ((index, pair) in orderedTimeline.zip(keys).withIndex()) {
			val (row, key) = pair
			val stored = ready.owners[key]
				?: return StepsTerminalProductAuthentication.Unverifiable
			val marker = stored.completenessMarker
				?: return StepsTerminalProductAuthentication.Unverifiable
			val expectedScope = StepsCountDomainReceiptIntegrity.sessionRunScopeIdentity(
				row.logicalTrackingId,
				row.serviceRunId,
			)
			val expectedEffect = StepsCountDomainReceiptIntegrity.completenessEffectChecksum(row)
			val retirementEvidence = StepsCountDomainRetirementEvidence(
				providerFlushOutcome = marker.providerFlushOutcome,
				registrationRemovalOutcome = marker.registrationRemovalOutcome,
			)
			if (
				ready.latestRevisions[
					StepsCountDomainOwnerLineageKey(key.ownerKind, key.ownerIdentity)
				] != key.ownerRevision ||
				!marker.matches(stored.owner) ||
				stored.owner.scopeIdentity != expectedScope ||
				stored.owner.ownerEffectChecksum != expectedEffect ||
				stored.owner.linkedAtMs != row.updatedAtMs ||
				marker.lastAdmissionOrdinal != row.lastAdmissionOrdinal ||
				marker.lastSourceSequence != row.lastSourceSequence ||
				marker.registrationTimelineChecksum !=
				StepsCountDomainReceiptIntegrity.registrationTimelineChecksum(
					orderedTimeline.take(index + 1),
				) ||
				!retirementEvidence.hasFinalRegistrationRemoval()
			) {
				return StepsTerminalProductAuthentication.Unverifiable
			}
			when (stored.owner.operation) {
				StepsCountDomainOwnerRevisionEntity.OPERATION_BIND -> {
					if (
						!stored.isAuthentic() ||
						!row.hasExactCompleteRetirement(retirementEvidence)
					) {
						return StepsTerminalProductAuthentication.Unverifiable
					}
				}
				StepsCountDomainOwnerRevisionEntity.OPERATION_UNPROVEN -> {
					if (
						stored.receipt != null ||
						stored.owner.receiptIdentity != null ||
						marker.terminalState !=
						StepsCountDomainCompletenessMarkerEntity.STATE_UNPROVEN ||
						!row.hasTerminalRetirement(retirementEvidence)
					) {
						return StepsTerminalProductAuthentication.Unverifiable
					}
					unavailable = true
				}
				else -> return StepsTerminalProductAuthentication.Unverifiable
			}
		}
		return if (unavailable) {
			StepsTerminalProductAuthentication.TerminalUnavailable
		} else {
			StepsTerminalProductAuthentication.Materializable
		}
	}

	private suspend fun authenticatedStepsCompletenessTimeline(
		logicalTrackingId: String,
		serviceRunId: String,
	): List<SourceSessionCompletenessEntity>? {
		val raw = database.sourceSessionDao().rawSourceCompletenessForServiceRun(
			serviceRunId = serviceRunId,
			sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
			limit = MAX_COMPLETENESS_TIMELINE + 1,
		)
		if (raw.size > MAX_COMPLETENESS_TIMELINE) return null
		val rows = ArrayList<SourceSessionCompletenessEntity>(raw.size)
		for (candidate in raw) {
			val row = candidate.validatedOrNull() ?: return null
			if (
				row.logicalTrackingId != logicalTrackingId ||
				row.serviceRunId != serviceRunId ||
				row.sourceKind != SourceDestinationOwnerEntity.SOURCE_STEPS
			) {
				return null
			}
			rows += row
		}
		return rows
	}

	private fun terminalCompletenessAbsence(
		ownerIdentity: String,
		scopeIdentity: String,
	): StepsTerminalCompletenessAuthentication {
		val sqlite = database.openHelper.readableDatabase
		return try {
			when (StepsCountDomainSchema.inspect(sqlite)) {
				StepsCountDomainSchemaState.Absent,
				StepsCountDomainSchemaState.FreshRoomScaffold,
				-> StepsTerminalCompletenessAuthentication.Absent
				StepsCountDomainSchemaState.Incompatible ->
					StepsTerminalCompletenessAuthentication.Unverifiable
				StepsCountDomainSchemaState.ValidV2 -> {
					if (sqlite.queryLatestOwner(
							StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS,
							scopeIdentity,
							ownerIdentity,
						) == null
					) {
						StepsTerminalCompletenessAuthentication.Absent
					} else {
						StepsTerminalCompletenessAuthentication.Unverifiable
					}
				}
			}
		} catch (_: SQLiteException) {
			StepsTerminalCompletenessAuthentication.Unverifiable
		} catch (_: IllegalArgumentException) {
			StepsTerminalCompletenessAuthentication.Unverifiable
		} catch (_: IllegalStateException) {
			StepsTerminalCompletenessAuthentication.Unverifiable
		}
	}

	suspend fun recordAmbientFact(
		fact: AmbientStepsFactRevisionEntity,
		counterDomainToken: StepsCounterDomainToken?,
	): StepsCountDomainWriteResult = authenticateCountDomainWrite {
		recordAmbientFactAuthenticated(fact, counterDomainToken)
	}

	private suspend fun recordAmbientFactAuthenticated(
		fact: AmbientStepsFactRevisionEntity,
		counterDomainToken: StepsCounterDomainToken?,
	): StepsCountDomainWriteResult {
		if (fact.operation != AmbientStepsFactRevisionEntity.OPERATION_UPSERT ||
			fact.originKind != AmbientStepsFactRevisionEntity.ORIGIN_PROVIDER_AGGREGATE
		) {
			return StepsCountDomainWriteResult.NOT_APPLICABLE
		}
		writeSchemaFailure()?.let { return it }
		if (!AmbientStepsFactIntegrity.hasValidEffectChecksum(fact)) {
			return StepsCountDomainWriteResult.IDENTITY_CONFLICT
		}
		val ownerIdentity = StepsCountDomainReceiptIntegrity.ambientFactOwnerIdentity(
			fact.writerId,
			fact.writerVersion,
			fact.logicalFactId,
		)
		val scopeIdentity =
			StepsCountDomainReceiptIntegrity.ambientFactScopeIdentity(fact.logicalFactId)
		val latestOwner = database.openHelper.writableDatabase.queryLatestOwner(
			StepsCountDomainOwnerRevisionEntity.OWNER_AMBIENT_FACT,
			scopeIdentity,
			ownerIdentity,
		)
		if (latestOwner?.operation == StepsCountDomainOwnerRevisionEntity.OPERATION_UNPROVEN) {
			return appendTerminalUnproven(
				ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_AMBIENT_FACT,
				scopeIdentity = scopeIdentity,
				ownerIdentity = ownerIdentity,
				ownerRevision = fact.semanticRevision,
				ownerEffectChecksum = fact.effectChecksum,
				linkedAtMs = fact.appliedAtMs,
			)
		}
		if (counterDomainToken == null) {
			return appendTerminalUnproven(
				ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_AMBIENT_FACT,
				scopeIdentity = scopeIdentity,
				ownerIdentity = ownerIdentity,
				ownerRevision = fact.semanticRevision,
				ownerEffectChecksum = fact.effectChecksum,
				linkedAtMs = fact.appliedAtMs,
			)
		}
		if (fact.semanticRevision > 1L &&
			latestOwner == null
		) {
			return appendTerminalUnproven(
				ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_AMBIENT_FACT,
				scopeIdentity = scopeIdentity,
				ownerIdentity = ownerIdentity,
				ownerRevision = fact.semanticRevision,
				ownerEffectChecksum = fact.effectChecksum,
				linkedAtMs = fact.appliedAtMs,
			)
		}
		val receipt = nativeReceipt(
			ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_AMBIENT_FACT,
			scopeIdentity = scopeIdentity,
			ownerIdentity = ownerIdentity,
			ownerRevision = fact.semanticRevision,
			counterDomainToken = counterDomainToken,
			registrationGeneration = requireNotNull(fact.registrationGeneration),
			collectedDataEpoch = fact.collectedDataEpoch,
			authorityRevision = requireNotNull(fact.authorizationRevision),
			authorityFingerprint = requireNotNull(fact.authorizationFingerprint),
			coverageKind = StepsCountDomainReceiptEntity.COVERAGE_AMBIENT_AGGREGATE,
			coverageVersion = fact.writerVersion,
			effectChecksum = fact.effectChecksum,
			completionEvidenceChecksum = null,
		)
		return appendBoundOwner(
			receipt = receipt,
			scopeIdentity = scopeIdentity,
			ownerEffectChecksum = fact.effectChecksum,
			linkedAtMs = fact.appliedAtMs,
		)
	}

	suspend fun recordSessionFactRetraction(
		retraction: StepFactRevisionEntity,
		logicalTrackingId: String,
		serviceRunId: String,
	): StepsCountDomainWriteResult = authenticateCountDomainWrite {
		recordSessionFactRetractionAuthenticated(
			retraction,
			logicalTrackingId,
			serviceRunId,
		)
	}

	private suspend fun recordSessionFactRetractionAuthenticated(
		retraction: StepFactRevisionEntity,
		logicalTrackingId: String,
		serviceRunId: String,
	): StepsCountDomainWriteResult {
		val expectedScopeIdentity = SourceDeletionFenceEntity.logicalServiceRunIdentity(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
			purpose = StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
		)
		writeSchemaFailure()?.let { return it }
		if (retraction.operation != StepFactRevisionEntity.OPERATION_RETRACT ||
			!StepFactRevisionIntegrity.hasValidLocalDeleteEffectChecksum(
				retraction,
				expectedScopeIdentity,
			)
		) {
			return StepsCountDomainWriteResult.IDENTITY_CONFLICT
		}
		return appendOwner(
			null,
			StepsCountDomainOwnerRevisionEntity(
				ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_FACT,
				scopeIdentity = StepsCountDomainReceiptIntegrity.sessionRunScopeIdentity(
					logicalTrackingId,
					serviceRunId,
				),
				ownerIdentity = StepsCountDomainReceiptIntegrity.sessionFactOwnerIdentity(
					retraction.writerProjectionId,
					retraction.writerProjectionVersion,
					retraction.logicalFactId,
				),
				ownerRevision = retraction.semanticRevision,
				operation = StepsCountDomainOwnerRevisionEntity.OPERATION_RETRACT,
				receiptIdentity = null,
				ownerEffectChecksum = retraction.effectChecksum,
				linkedAtMs = retraction.appliedAtMs,
			),
		)
	}

	suspend fun recordAmbientFactRetraction(
		retraction: AmbientStepsFactRevisionEntity,
	): StepsCountDomainWriteResult = authenticateCountDomainWrite {
		recordAmbientFactRetractionAuthenticated(retraction)
	}

	private suspend fun recordAmbientFactRetractionAuthenticated(
		retraction: AmbientStepsFactRevisionEntity,
	): StepsCountDomainWriteResult {
		writeSchemaFailure()?.let { return it }
		if (retraction.operation != AmbientStepsFactRevisionEntity.OPERATION_RETRACT ||
			!AmbientStepsFactIntegrity.hasValidEffectChecksum(retraction)
		) {
			return StepsCountDomainWriteResult.IDENTITY_CONFLICT
		}
		return appendOwner(
			null,
			StepsCountDomainOwnerRevisionEntity(
				ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_AMBIENT_FACT,
				scopeIdentity = StepsCountDomainReceiptIntegrity.ambientFactScopeIdentity(
					retraction.logicalFactId,
				),
				ownerIdentity = StepsCountDomainReceiptIntegrity.ambientFactOwnerIdentity(
					retraction.writerId,
					retraction.writerVersion,
					retraction.logicalFactId,
				),
				ownerRevision = retraction.semanticRevision,
				operation = StepsCountDomainOwnerRevisionEntity.OPERATION_RETRACT,
				receiptIdentity = null,
				ownerEffectChecksum = retraction.effectChecksum,
				linkedAtMs = retraction.appliedAtMs,
			),
		)
	}

	@Suppress("LongMethod", "ReturnCount")
	suspend fun readOwners(
		keys: List<StepsCountDomainOwnerLookupKey>,
		limit: Int = MAX_OWNER_LOOKUP,
	): StepsCountDomainOwnerRead {
		val distinctKeys = keys.distinct()
		if (limit !in 1..MAX_OWNER_LOOKUP || distinctKeys.size > limit) {
			return StepsCountDomainOwnerRead.Overflow
		}
		val sqlite = database.openHelper.readableDatabase
		when (StepsCountDomainSchema.inspect(sqlite)) {
			StepsCountDomainSchemaState.Absent,
			StepsCountDomainSchemaState.FreshRoomScaffold,
			->
				return StepsCountDomainOwnerRead.SchemaUnavailable
			StepsCountDomainSchemaState.Incompatible ->
				return StepsCountDomainOwnerRead.Unverifiable
			StepsCountDomainSchemaState.ValidV2 -> Unit
		}
		return try {
			val owners = mutableListOf<StepsCountDomainOwnerRevisionEntity>()
			for (chunk in distinctKeys.chunked(OWNER_QUERY_CHUNK)) {
				currentCoroutineContext().ensureActive()
				owners += sqlite.queryOwnerChunk(chunk)
			}

			if (owners.size > limit) return StepsCountDomainOwnerRead.Overflow
			val ownersByKey = owners.associateBy {
				StepsCountDomainOwnerLookupKey(
					it.ownerKind,
					it.ownerIdentity,
					it.ownerRevision,
				)
			}
			if (ownersByKey.size != owners.size) {
				return StepsCountDomainOwnerRead.Unverifiable
			}
			sqlite.requireAuthenticMissingCountDomainOwnerKeys(
				distinctKeys.filterNot(ownersByKey::containsKey),
			)
			sqlite.requireAuthenticCountDomainOwnerScopes(
				owners.map { owner ->
					CountDomainOwnerScopeKey(
						owner.ownerKind,
						owner.scopeIdentity,
						owner.ownerIdentity,
					)
				},
			)
			val latestRows = mutableListOf<Pair<StepsCountDomainOwnerLineageKey, Long>>()
			for (chunk in distinctKeys
				.map { StepsCountDomainOwnerLineageKey(it.ownerKind, it.ownerIdentity) }
				.distinct()
				.chunked(LATEST_QUERY_CHUNK)) {
				currentCoroutineContext().ensureActive()
				latestRows += sqlite.queryLatestOwnerChunk(chunk)
			}
			val latestRevisions = latestRows.toMap()
			if (owners.any { owner ->
				StepsCountDomainOwnerLineageKey(owner.ownerKind, owner.ownerIdentity) !in
					latestRevisions
			}) {
				return StepsCountDomainOwnerRead.Unverifiable
			}
			val receiptIds = owners.mapNotNull(StepsCountDomainOwnerRevisionEntity::receiptIdentity)
				.distinct()
			if (receiptIds.size > limit) return StepsCountDomainOwnerRead.Overflow
			val receipts = mutableListOf<StepsCountDomainReceiptEntity>()
			for (chunk in receiptIds.chunked(RECEIPT_QUERY_CHUNK)) {
				currentCoroutineContext().ensureActive()
				receipts += sqlite.queryReceiptChunk(chunk)
			}
			if (receipts.size != receiptIds.size) return StepsCountDomainOwnerRead.Unverifiable
			val receiptsById = receipts.associateBy(StepsCountDomainReceiptEntity::receiptIdentity)
			val completenessOwners = owners.filter {
				it.ownerKind == StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS
			}
			val markers = mutableListOf<StepsCountDomainCompletenessMarkerEntity>()
			for (chunk in completenessOwners.chunked(MARKER_QUERY_CHUNK)) {
				currentCoroutineContext().ensureActive()
				markers += sqlite.queryCompletenessMarkerChunk(chunk)
			}
			if (markers.size != completenessOwners.size) {
				return StepsCountDomainOwnerRead.Unverifiable
			}
			val markersByKey = markers.associateBy {
				StepsCountDomainOwnerLookupKey(
					it.ownerKind,
					it.ownerIdentity,
					it.ownerRevision,
				)
			}
			StepsCountDomainOwnerRead.Ready(
				distinctKeys.mapNotNull { key ->
					ownersByKey[key]?.let { owner ->
						key to StepsCountDomainStoredOwner(
							owner,
							owner.receiptIdentity?.let(receiptsById::get),
							markersByKey[key],
						)
					}
				}.toMap(),
				latestRevisions,
			)
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: SQLiteException) {
			StepsCountDomainOwnerRead.Unverifiable
		} catch (_: IllegalArgumentException) {
			StepsCountDomainOwnerRead.Unverifiable
		} catch (_: IllegalStateException) {
			StepsCountDomainOwnerRead.Unverifiable
		}
	}

	fun removeOwners(
		keys: List<StepsCountDomainOwnerLookupKey>,
	): StepsCountDomainMaintenanceResult = authenticateCountDomainMaintenance {
		removeOwnersAuthenticated(keys)
	}

	private fun removeOwnersAuthenticated(
		keys: List<StepsCountDomainOwnerLookupKey>,
		auditMaintenanceDomain: Boolean = true,
		requireEveryOwner: Boolean = false,
	): StepsCountDomainMaintenanceResult {
		val distinct = keys.distinct()
		if (distinct.size > MAX_MAINTENANCE_OWNER_BATCH) {
			return StepsCountDomainMaintenanceResult.Overflow
		}
		val sqlite = database.openHelper.writableDatabase
		when (StepsCountDomainSchema.inspect(sqlite)) {
			StepsCountDomainSchemaState.Absent,
			StepsCountDomainSchemaState.FreshRoomScaffold,
			->
				return StepsCountDomainMaintenanceResult.SchemaUnavailable
			StepsCountDomainSchemaState.Incompatible ->
				return StepsCountDomainMaintenanceResult.StoredEvidenceUnverifiable
			StepsCountDomainSchemaState.ValidV2 -> Unit
		}
		if (auditMaintenanceDomain) {
			sqlite.requireAuthenticCountDomainMaintenanceRows()
		}
		if (distinct.isEmpty()) return StepsCountDomainMaintenanceResult.Applied(0L, 0L)
		val owners = distinct.chunked(OWNER_QUERY_CHUNK).flatMap(sqlite::queryOwnerChunk)
		if (requireEveryOwner && owners.size != distinct.size) {
			return StepsCountDomainMaintenanceResult.StoredEvidenceUnverifiable
		}
		val receiptIds = owners.mapNotNull(StepsCountDomainOwnerRevisionEntity::receiptIdentity)
			.distinct()
		var removedOwnerRows = 0
		distinct.chunked(OWNER_QUERY_CHUNK).forEach { chunk ->
			removedOwnerRows += sqlite.deleteOwnerChunk(chunk)
		}
		check(removedOwnerRows == owners.size) {
			"Steps count-domain owner changed during removal"
		}
		val removedReceipts = sqlite.deleteUnreferencedReceipts(receiptIds)
		return StepsCountDomainMaintenanceResult.Applied(owners.size.toLong(), removedReceipts)
	}

	fun clear(
		mode: StepsCountDomainFullClearMode,
	): StepsCountDomainMaintenanceResult = authenticateCountDomainMaintenance {
		clearAuthenticated(mode)
	}

	private fun clearAuthenticated(
		mode: StepsCountDomainFullClearMode,
	): StepsCountDomainMaintenanceResult {
		val sqlite = database.openHelper.writableDatabase
		when (StepsCountDomainSchema.inspect(sqlite)) {
			StepsCountDomainSchemaState.Absent,
			StepsCountDomainSchemaState.FreshRoomScaffold,
			->
				return StepsCountDomainMaintenanceResult.SchemaUnavailable
			StepsCountDomainSchemaState.Incompatible ->
				return StepsCountDomainMaintenanceResult.StoredEvidenceUnverifiable
			StepsCountDomainSchemaState.ValidV2 -> Unit
		}
		sqlite.requireAuthenticCountDomainMaintenanceRows()
		val ownerCount = sqlite.longForQuery(
			if (mode == StepsCountDomainFullClearMode.REMOVE_ALL) {
				"SELECT COUNT(*) FROM main.steps_count_domain_owner_revision"
			} else {
				"SELECT COUNT(*) FROM main.steps_count_domain_owner_revision " +
					"WHERE operation = 'BIND'"
			},
		)
		if (mode == StepsCountDomainFullClearMode.REMOVE_ALL) {
			sqlite.execSQL("DELETE FROM main.steps_count_domain_completeness_marker")
			sqlite.execSQL("DELETE FROM main.steps_count_domain_owner_revision")
		} else {
			sqlite.execSQL(
				"DELETE FROM main.steps_count_domain_owner_revision WHERE operation = 'BIND'",
			)
		}
		val receiptCount =
			sqlite.longForQuery("SELECT COUNT(*) FROM main.steps_count_domain_receipt")
		sqlite.execSQL("DELETE FROM main.steps_count_domain_receipt")
		return StepsCountDomainMaintenanceResult.Applied(ownerCount, receiptCount)
	}

	fun compactTerminalOwners(
		maximumRetainedTerminalOwners: Int,
		batchSize: Int,
		sourceFenceAuthenticated: Boolean,
	): StepsCountDomainMaintenanceResult = authenticateCountDomainMaintenance {
		compactTerminalOwnersAuthenticated(
			maximumRetainedTerminalOwners,
			batchSize,
			sourceFenceAuthenticated,
		)
	}

	private fun compactTerminalOwnersAuthenticated(
		maximumRetainedTerminalOwners: Int,
		batchSize: Int,
		sourceFenceAuthenticated: Boolean,
	): StepsCountDomainMaintenanceResult {
		require(sourceFenceAuthenticated)
		require(maximumRetainedTerminalOwners >= 0)
		require(batchSize in 1..MAX_MAINTENANCE_OWNER_BATCH)
		val sqlite = database.openHelper.writableDatabase
		when (StepsCountDomainSchema.inspect(sqlite)) {
			StepsCountDomainSchemaState.Absent,
			StepsCountDomainSchemaState.FreshRoomScaffold,
			->
				return StepsCountDomainMaintenanceResult.SchemaUnavailable
			StepsCountDomainSchemaState.Incompatible ->
				return StepsCountDomainMaintenanceResult.StoredEvidenceUnverifiable
			StepsCountDomainSchemaState.ValidV2 -> Unit
		}
		sqlite.requireAuthenticCountDomainMaintenanceRows()
		val candidates = sqlite.queryTerminalCompactionCandidates(
			maximumRetainedTerminalOwners,
			batchSize,
		)
		return removeOwnersAuthenticated(candidates, auditMaintenanceDomain = false)
	}

	fun removeSessionWalOwnersForPrune(
		safeOrdinal: Long,
		createdBeforeMs: Long,
		limit: Int,
	): StepsCountDomainMaintenanceResult = authenticateCountDomainMaintenance {
		removeSessionWalOwnersForPruneAuthenticated(safeOrdinal, createdBeforeMs, limit)
	}

	private fun removeSessionWalOwnersForPruneAuthenticated(
		safeOrdinal: Long,
		createdBeforeMs: Long,
		limit: Int,
	): StepsCountDomainMaintenanceResult {
		require(safeOrdinal >= 0L)
		require(createdBeforeMs >= 0L)
		if (limit !in 1..MAX_WAL_PRUNE_BATCH) {
			return StepsCountDomainMaintenanceResult.Overflow
		}
		val sqlite = database.openHelper.writableDatabase
		when (StepsCountDomainSchema.inspect(sqlite)) {
			StepsCountDomainSchemaState.Absent,
			StepsCountDomainSchemaState.FreshRoomScaffold,
			->
				return StepsCountDomainMaintenanceResult.SchemaUnavailable
			StepsCountDomainSchemaState.Incompatible ->
				return StepsCountDomainMaintenanceResult.StoredEvidenceUnverifiable
			StepsCountDomainSchemaState.ValidV2 -> Unit
		}
		sqlite.requireAuthenticCountDomainMaintenanceRows()
		sqlite.requireAuthenticStepsWalMaintenanceDomain()
		val keys = sqlite.querySessionWalPruneOwners(
			safeOrdinal,
			createdBeforeMs,
			limit,
		)
		var removedOwners = 0L
		var removedReceipts = 0L
		keys.chunked(MAX_MAINTENANCE_OWNER_BATCH).forEach { chunk ->
			when (
				val result = removeOwnersAuthenticated(
					chunk,
					auditMaintenanceDomain = false,
					requireEveryOwner = true,
				)
			) {
				is StepsCountDomainMaintenanceResult.Applied -> {
					removedOwners += result.removedOwners
					removedReceipts += result.removedReceipts
				}
				StepsCountDomainMaintenanceResult.SchemaUnavailable ->
					return StepsCountDomainMaintenanceResult.SchemaUnavailable
				StepsCountDomainMaintenanceResult.StoredEvidenceUnverifiable ->
					return StepsCountDomainMaintenanceResult.StoredEvidenceUnverifiable
				StepsCountDomainMaintenanceResult.Overflow ->
					return StepsCountDomainMaintenanceResult.Overflow
			}
		}
		return StepsCountDomainMaintenanceResult.Applied(removedOwners, removedReceipts)
	}

	private suspend fun authenticateCountDomainWrite(
		block: suspend () -> StepsCountDomainWriteResult,
	): StepsCountDomainWriteResult = try {
		block()
	} catch (_: CountDomainStoredEvidenceException) {
		StepsCountDomainWriteResult.STORED_EVIDENCE_UNVERIFIABLE
	}

	private fun authenticateCountDomainMaintenance(
		block: () -> StepsCountDomainMaintenanceResult,
	): StepsCountDomainMaintenanceResult = try {
		block()
	} catch (_: CountDomainStoredEvidenceException) {
		StepsCountDomainMaintenanceResult.StoredEvidenceUnverifiable
	} catch (_: SQLiteException) {
		StepsCountDomainMaintenanceResult.StoredEvidenceUnverifiable
	} catch (_: IllegalArgumentException) {
		StepsCountDomainMaintenanceResult.StoredEvidenceUnverifiable
	} catch (_: IllegalStateException) {
		StepsCountDomainMaintenanceResult.StoredEvidenceUnverifiable
	} catch (_: ArithmeticException) {
		StepsCountDomainMaintenanceResult.StoredEvidenceUnverifiable
	}

	private fun appendBoundOwner(
		receipt: StepsCountDomainReceiptEntity,
		scopeIdentity: String,
		ownerEffectChecksum: String,
		linkedAtMs: Long,
		completenessMarker: StepsCountDomainCompletenessMarkerEntity? = null,
	): StepsCountDomainWriteResult {
		if (receipt.scopeIdentity != scopeIdentity) {
			return StepsCountDomainWriteResult.IDENTITY_CONFLICT
		}
		return appendOwner(
			receipt,
			StepsCountDomainOwnerRevisionEntity(
				ownerKind = receipt.ownerKind,
				scopeIdentity = scopeIdentity,
				ownerIdentity = receipt.ownerIdentity,
				ownerRevision = receipt.ownerRevision,
				operation = StepsCountDomainOwnerRevisionEntity.OPERATION_BIND,
				receiptIdentity = receipt.receiptIdentity,
				ownerEffectChecksum = ownerEffectChecksum,
				linkedAtMs = linkedAtMs,
			),
			completenessMarker,
		)
	}

	private fun appendTerminalUnproven(
		ownerKind: String,
		scopeIdentity: String,
		ownerIdentity: String,
		ownerRevision: Long,
		ownerEffectChecksum: String,
		linkedAtMs: Long,
		completenessMarker: StepsCountDomainCompletenessMarkerEntity? = null,
	): StepsCountDomainWriteResult = appendOwner(
		receipt = null,
		owner = StepsCountDomainOwnerRevisionEntity(
			ownerKind = ownerKind,
			scopeIdentity = scopeIdentity,
			ownerIdentity = ownerIdentity,
			ownerRevision = ownerRevision,
			operation = StepsCountDomainOwnerRevisionEntity.OPERATION_UNPROVEN,
			receiptIdentity = null,
			ownerEffectChecksum = ownerEffectChecksum,
			linkedAtMs = linkedAtMs,
		),
		completenessMarker = completenessMarker,
	)

	@Suppress("ComplexCondition", "CyclomaticComplexMethod", "LongMethod", "ReturnCount")
	private fun appendOwner(
		receipt: StepsCountDomainReceiptEntity?,
		owner: StepsCountDomainOwnerRevisionEntity,
		completenessMarker: StepsCountDomainCompletenessMarkerEntity? = null,
	): StepsCountDomainWriteResult {
		val sqlite = database.openHelper.writableDatabase
		writeSchemaFailure()?.let { return it }
		val latest = sqlite.queryLatestOwner(
			owner.ownerKind,
			owner.scopeIdentity,
			owner.ownerIdentity,
		)
		if (receipt != null && receipt.effectChecksum != owner.ownerEffectChecksum) {
			return StepsCountDomainWriteResult.IDENTITY_CONFLICT
		}
		if ((owner.ownerKind == StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS) !=
			(completenessMarker != null)
		) {
			return StepsCountDomainWriteResult.IDENTITY_CONFLICT
		}
		if (completenessMarker != null && !completenessMarker.matches(owner)) {
			return StepsCountDomainWriteResult.IDENTITY_CONFLICT
		}
		if (completenessMarker?.terminalState ==
			StepsCountDomainCompletenessMarkerEntity.STATE_COMPLETE &&
			receipt?.completionEvidenceChecksum != completenessMarker.evidenceChecksum
		) {
			return StepsCountDomainWriteResult.IDENTITY_CONFLICT
		}
		val exact = sqlite.queryOwner(
			owner.ownerKind,
			owner.scopeIdentity,
			owner.ownerIdentity,
			owner.ownerRevision,
		)
		if (latest?.operation == StepsCountDomainOwnerRevisionEntity.OPERATION_RETRACT &&
			exact != latest
		) {
			return StepsCountDomainWriteResult.TERMINAL_OWNER
		}
		if (latest?.ownerKind ==
			StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS &&
			latest.operation == StepsCountDomainOwnerRevisionEntity.OPERATION_BIND &&
			exact != latest
		) {
			return StepsCountDomainWriteResult.TERMINAL_OWNER
		}
		if (latest?.operation == StepsCountDomainOwnerRevisionEntity.OPERATION_UNPROVEN &&
			exact != latest &&
			owner.operation != StepsCountDomainOwnerRevisionEntity.OPERATION_RETRACT &&
			!(
				owner.ownerKind == StepsCountDomainOwnerRevisionEntity.OWNER_AMBIENT_FACT &&
					owner.operation == StepsCountDomainOwnerRevisionEntity.OPERATION_UNPROVEN
				)
		) {
			return StepsCountDomainWriteResult.TERMINAL_OWNER
		}
		if (exact != null) {
			val storedReceipt = exact.receiptIdentity?.let(sqlite::queryReceipt)
			val storedMarker = if (
				exact.ownerKind ==
				StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS
			) {
				sqlite.queryCompletenessMarker(exact.ownerIdentity, exact.ownerRevision)
			} else {
				null
			}
			return if (exact == owner && storedReceipt == receipt &&
				storedMarker == completenessMarker
			) {
				StepsCountDomainWriteResult.EXACT_REPLAY
			} else {
				StepsCountDomainWriteResult.IDENTITY_CONFLICT
			}
		}
		if (latest != null && latest.scopeIdentity != owner.scopeIdentity) {
			return StepsCountDomainWriteResult.IDENTITY_CONFLICT
		}
		val revisionIsValid = when {
			latest == null && owner.operation ==
				StepsCountDomainOwnerRevisionEntity.OPERATION_UNPROVEN -> true
			owner.ownerKind ==
				StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS -> {
				latest == null || owner.ownerRevision > latest.ownerRevision
			}
			else -> {
				owner.ownerRevision == (latest?.ownerRevision ?: 0L) + 1L
			}
		}
		if (!revisionIsValid) {
			return StepsCountDomainWriteResult.REVISION_GAP
		}
		if (receipt != null && latest != null &&
			owner.ownerKind in FACT_OWNER_KINDS
		) {
			val previousReceipt = latest.receiptIdentity?.let(sqlite::queryReceipt)
				?: return StepsCountDomainWriteResult.IDENTITY_CONFLICT
			if (!receipt.hasSameImmutableDomain(previousReceipt)) {
				return StepsCountDomainWriteResult.IDENTITY_CONFLICT
			}
		}
		if (receipt != null) {
			sqlite.execSQL(INSERT_RECEIPT_SQL, receipt.bindArguments())
			if (sqlite.queryReceipt(receipt.receiptIdentity) != receipt) {
				return StepsCountDomainWriteResult.IDENTITY_CONFLICT
			}
		}
		sqlite.execSQL(INSERT_OWNER_SQL, owner.bindArguments())
		if (sqlite.queryOwner(
				owner.ownerKind,
				owner.scopeIdentity,
				owner.ownerIdentity,
				owner.ownerRevision,
			) != owner
		) {
			return StepsCountDomainWriteResult.IDENTITY_CONFLICT
		}
		if (completenessMarker != null) {
			sqlite.execSQL(INSERT_COMPLETENESS_MARKER_SQL, completenessMarker.bindArguments())
			if (sqlite.queryCompletenessMarker(
					completenessMarker.ownerIdentity,
					completenessMarker.ownerRevision,
				) != completenessMarker
			) {
				return StepsCountDomainWriteResult.IDENTITY_CONFLICT
			}
		}
		return StepsCountDomainWriteResult.INSERTED
	}

	private fun loadExactOwner(
		key: StepsCountDomainOwnerLookupKey,
		scopeIdentity: String,
	): StepsCountDomainStoredOwner? {
		val sqlite = database.openHelper.writableDatabase
		if (StepsCountDomainSchema.inspect(sqlite) != StepsCountDomainSchemaState.ValidV2) {
			return null
		}
		val owner = sqlite.queryOwner(
			key.ownerKind,
			scopeIdentity,
			key.ownerIdentity,
			key.ownerRevision,
		) ?: return null
		return StepsCountDomainStoredOwner(
			owner,
			owner.receiptIdentity?.let(sqlite::queryReceipt),
			if (owner.ownerKind ==
				StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS
			) {
				sqlite.queryCompletenessMarker(owner.ownerIdentity, owner.ownerRevision)
			} else {
				null
			},
		)
	}

	private fun writeSchemaFailure(): StepsCountDomainWriteResult? =
		when (StepsCountDomainSchema.inspect(database.openHelper.writableDatabase)) {
			StepsCountDomainSchemaState.Absent,
			StepsCountDomainSchemaState.FreshRoomScaffold,
			-> StepsCountDomainWriteResult.SCHEMA_UNAVAILABLE
			StepsCountDomainSchemaState.ValidV2 -> null
			StepsCountDomainSchemaState.Incompatible ->
				StepsCountDomainWriteResult.STORED_EVIDENCE_UNVERIFIABLE
		}

	private suspend fun canonicalProjectionCommittedThrough(admissionOrdinal: Long): Boolean =
		database.sourceProjectionStateDao().productLanesByProjection(
			SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID,
			SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_VERSION,
		).any { lane ->
			lane.productStage == SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL &&
				lane.activationOrdinal <= admissionOrdinal &&
				lane.contiguousAdmissionOrdinal >= admissionOrdinal &&
				lane.captureAdmissionCutoffOrdinal?.let { it >= admissionOrdinal } != false
		}

	@Suppress("LongParameterList")
	private fun nativeReceipt(
		ownerKind: String,
		scopeIdentity: String,
		ownerIdentity: String,
		ownerRevision: Long,
		counterDomainToken: StepsCounterDomainToken,
		registrationGeneration: Long,
		collectedDataEpoch: Long,
		authorityRevision: Long,
		authorityFingerprint: String,
		coverageKind: String,
		coverageVersion: Int,
		effectChecksum: String,
		completionEvidenceChecksum: String?,
	): StepsCountDomainReceiptEntity {
		val domainIdentity =
			StepsCountDomainReceiptIntegrity.counterDomainIdentity(counterDomainToken)
		val countDomainVersion = StepsCountDomainReceiptEntity.CURRENT_COUNT_DOMAIN_VERSION
		val receiptIdentity = StepsCountDomainReceiptIntegrity.receiptIdentity(
			domainIdentity = domainIdentity,
			ownerKind = ownerKind,
			scopeIdentity = scopeIdentity,
			ownerIdentity = ownerIdentity,
			ownerRevision = ownerRevision,
			registrationGeneration = registrationGeneration,
			collectedDataEpoch = collectedDataEpoch,
			authorityRevision = authorityRevision,
			authorityFingerprint = authorityFingerprint,
			coverageKind = coverageKind,
			coverageVersion = coverageVersion,
			countDomainVersion = countDomainVersion,
			effectChecksum = effectChecksum,
			completionEvidenceChecksum = completionEvidenceChecksum,
		)
		return StepsCountDomainReceiptEntity(
			receiptIdentity = receiptIdentity,
			domainIdentity = domainIdentity,
			ownerKind = ownerKind,
			scopeIdentity = scopeIdentity,
			ownerIdentity = ownerIdentity,
			ownerRevision = ownerRevision,
			registrationGeneration = registrationGeneration,
			collectedDataEpoch = collectedDataEpoch,
			authorityRevision = authorityRevision,
			authorityFingerprint = authorityFingerprint,
			coverageKind = coverageKind,
			coverageVersion = coverageVersion,
			countDomainVersion = countDomainVersion,
			effectChecksum = effectChecksum,
			completionEvidenceChecksum = completionEvidenceChecksum,
		)
	}

	private fun nativeReceiptFrom(
		source: StepsCountDomainReceiptEntity,
		ownerKind: String,
		scopeIdentity: String,
		ownerIdentity: String,
		ownerRevision: Long,
		coverageKind: String,
		coverageVersion: Int,
		effectChecksum: String,
		completionEvidenceChecksum: String?,
	): StepsCountDomainReceiptEntity {
		val receiptIdentity = StepsCountDomainReceiptIntegrity.receiptIdentity(
			domainIdentity = source.domainIdentity,
			ownerKind = ownerKind,
			scopeIdentity = scopeIdentity,
			ownerIdentity = ownerIdentity,
			ownerRevision = ownerRevision,
			registrationGeneration = source.registrationGeneration,
			collectedDataEpoch = source.collectedDataEpoch,
			authorityRevision = source.authorityRevision,
			authorityFingerprint = source.authorityFingerprint,
			coverageKind = coverageKind,
			coverageVersion = coverageVersion,
			countDomainVersion = source.countDomainVersion,
			effectChecksum = effectChecksum,
			completionEvidenceChecksum = completionEvidenceChecksum,
		)
		return StepsCountDomainReceiptEntity(
			receiptIdentity = receiptIdentity,
			domainIdentity = source.domainIdentity,
			ownerKind = ownerKind,
			scopeIdentity = scopeIdentity,
			ownerIdentity = ownerIdentity,
			ownerRevision = ownerRevision,
			registrationGeneration = source.registrationGeneration,
			collectedDataEpoch = source.collectedDataEpoch,
			authorityRevision = source.authorityRevision,
			authorityFingerprint = source.authorityFingerprint,
			coverageKind = coverageKind,
			coverageVersion = coverageVersion,
			countDomainVersion = source.countDomainVersion,
			effectChecksum = effectChecksum,
			completionEvidenceChecksum = completionEvidenceChecksum,
		)
	}

	private fun completenessMarker(
		row: SourceSessionCompletenessEntity,
		ownerIdentity: String,
		ownerRevision: Long,
		retirementEvidence: StepsCountDomainRetirementEvidence,
		timelineChecksum: String,
		exactComplete: Boolean,
	): StepsCountDomainCompletenessMarkerEntity {
		val terminalState = if (exactComplete) {
			StepsCountDomainCompletenessMarkerEntity.STATE_COMPLETE
		} else {
			StepsCountDomainCompletenessMarkerEntity.STATE_UNPROVEN
		}
		val checksum = StepsCountDomainReceiptIntegrity.completenessMarkerChecksum(
			ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS,
			ownerIdentity = ownerIdentity,
			ownerRevision = ownerRevision,
			terminalState = terminalState,
			lastAdmissionOrdinal = row.lastAdmissionOrdinal,
			lastSourceSequence = row.lastSourceSequence,
			providerFlushOutcome = retirementEvidence.providerFlushOutcome,
			registrationRemovalOutcome = retirementEvidence.registrationRemovalOutcome,
			registrationTimelineChecksum = timelineChecksum,
		)
		return StepsCountDomainCompletenessMarkerEntity(
			ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS,
			ownerIdentity = ownerIdentity,
			ownerRevision = ownerRevision,
			terminalState = terminalState,
			lastAdmissionOrdinal = row.lastAdmissionOrdinal,
			lastSourceSequence = row.lastSourceSequence,
			providerFlushOutcome = retirementEvidence.providerFlushOutcome,
			registrationRemovalOutcome = retirementEvidence.registrationRemovalOutcome,
			registrationTimelineChecksum = timelineChecksum,
			evidenceChecksum = checksum,
		)
	}

	private fun StepsCountDomainStoredOwner.isAuthentic(): Boolean {
		val receipt = receipt ?: return false
		return owner.operation == StepsCountDomainOwnerRevisionEntity.OPERATION_BIND &&
			owner.receiptIdentity == receipt.receiptIdentity &&
			owner.ownerKind == receipt.ownerKind &&
			owner.scopeIdentity == receipt.scopeIdentity &&
			owner.ownerIdentity == receipt.ownerIdentity &&
			owner.ownerRevision == receipt.ownerRevision &&
			owner.ownerEffectChecksum == receipt.effectChecksum &&
			StepsCountDomainReceiptIntegrity.hasValidReceipt(receipt) &&
			if (owner.ownerKind ==
				StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS
			) {
				completenessMarker?.let {
					it.terminalState == StepsCountDomainCompletenessMarkerEntity.STATE_COMPLETE &&
						it.evidenceChecksum == receipt.completionEvidenceChecksum
				} == true
			} else {
				completenessMarker == null && receipt.completionEvidenceChecksum == null
			}
	}

	private companion object {
		const val COMPLETENESS_COVERAGE_VERSION = 1
		const val MAX_OWNER_LOOKUP = 512
		const val OWNER_QUERY_CHUNK = 100
		const val LATEST_QUERY_CHUNK = 200
		const val RECEIPT_QUERY_CHUNK = 400
		const val MARKER_QUERY_CHUNK = 200
		const val MAX_MAINTENANCE_OWNER_BATCH = 400
		const val MAX_WAL_PRUNE_BATCH = 2_048
		const val MAX_COMPLETENESS_TIMELINE = 64
		val FACT_OWNER_KINDS = setOf(
			StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_FACT,
			StepsCountDomainOwnerRevisionEntity.OWNER_AMBIENT_FACT,
		)
		const val INSERT_RECEIPT_SQL =
			"INSERT OR IGNORE INTO main.steps_count_domain_receipt (" +
					"receipt_identity, domain_identity, owner_kind, scope_identity, " +
					"owner_identity, owner_revision, " +
				"registration_generation, collected_data_epoch, authority_revision, " +
				"authority_fingerprint, coverage_kind, coverage_version, count_domain_version, " +
					"effect_checksum, completion_evidence_checksum) " +
					"VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
		const val INSERT_OWNER_SQL =
			"INSERT OR IGNORE INTO main.steps_count_domain_owner_revision (" +
				"owner_kind, scope_identity, owner_identity, owner_revision, operation, " +
				"receipt_identity, owner_effect_checksum, linked_at_ms) " +
				"VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
		const val INSERT_COMPLETENESS_MARKER_SQL =
			"INSERT OR IGNORE INTO main.steps_count_domain_completeness_marker (" +
				"owner_kind, owner_identity, owner_revision, terminal_state, " +
				"last_admission_ordinal, last_source_sequence, provider_flush_outcome, " +
				"registration_removal_outcome, registration_timeline_checksum, evidence_checksum) " +
				"VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
	}
}

/**
 * Transaction-owned serialized AppDatabase hook. The caller must already be inside the existing
 * collected-data clear transaction, after source payload fences are installed and before commit.
 */
fun clearStepsCountDomainEvidenceInCurrentTransaction(
	database: AppDatabase,
	mode: StepsCountDomainFullClearMode,
): StepsCountDomainMaintenanceResult {
	check(database.inTransaction()) {
		"Steps count-domain clear requires the caller's existing AppDatabase transaction"
	}
	return StepsCountDomainStore(database).clear(mode)
}

/** Standalone wrapper for callers that do not already own a Room transaction. */
suspend fun AppDatabase.clearStepsCountDomainEvidenceInTransaction(
	mode: StepsCountDomainFullClearMode,
): StepsCountDomainMaintenanceResult = withTransaction {
	clearStepsCountDomainEvidenceInCurrentTransaction(this, mode)
}

/**
 * Publishes a Steps evidence revision on the wall-clock axis while preserving monotonic storage.
 *
 * Runtime checkpoint causal order remains in its dedicated elapsed-realtime field.
 */
suspend fun publishStepsCountDomainEvidenceRevisionAtWallTime(
	database: AppDatabase,
	wallTimeMs: Long,
) {
	check(database.inTransaction()) {
		"Steps evidence publication requires the caller's existing AppDatabase transaction"
	}
	require(wallTimeMs >= 0L)
	val dao = database.sourceEvidenceStateDao()
	dao.ensure()
	val current = requireNotNull(dao.get()) {
		"Source-evidence state disappeared inside the Steps transaction"
	}
	check(dao.incrementRevision(maxOf(current.updatedAtMs, wallTimeMs)) == 1) {
		"Unable to publish terminal Steps count-domain completeness"
	}
}

fun SourceSessionCompletenessEntity.withMonotonicStepsCountDomainRevision(
	existing: SourceSessionCompletenessEntity?,
): SourceSessionCompletenessEntity {
	require(updatedAtMs in 0L until Long.MAX_VALUE)
	if (existing == null) return this
	require(
		logicalTrackingId == existing.logicalTrackingId &&
			serviceRunId == existing.serviceRunId &&
			sourceKind == existing.sourceKind &&
			sourceInstanceId == existing.sourceInstanceId &&
			registrationGeneration == existing.registrationGeneration &&
			existing.updatedAtMs in 0L until Long.MAX_VALUE,
	)
	if (copy(updatedAtMs = existing.updatedAtMs) == existing) return existing
	val nextRevisionWallTime = existing.updatedAtMs + 1L
	require(nextRevisionWallTime < Long.MAX_VALUE)
	return copy(
		updatedAtMs = maxOf(
			updatedAtMs,
			nextRevisionWallTime,
		),
	)
}

private fun SourceSessionCompletenessEntity.hasExactCompleteRetirement(
	evidence: StepsCountDomainRetirementEvidence,
): Boolean = registrationGeneration > 0L &&
	appDrainComplete &&
	providerCoverage == "CALLBACKS_ENTERED_BEFORE_BARRIER" &&
	stopStatus == "COMPLETE" &&
	unresolvedSequenceStart == null &&
	unresolvedSequenceEnd == null &&
	lastAdmissionOrdinal != null &&
	lastSourceSequence != null &&
	evidence.providerFlushOutcome in
		setOf("COMPLETE", "NOT_SUPPORTED", "NOT_REQUESTED") &&
	evidence.registrationRemovalOutcome in setOf("REMOVED", "NOT_REGISTERED")

private fun SourceSessionCompletenessEntity.hasTerminalRetirement(
	evidence: StepsCountDomainRetirementEvidence,
): Boolean = StepsRetirementTerminality.isTerminal(
	stopStatus = stopStatus,
	registrationRemovalOutcome = evidence.registrationRemovalOutcome,
	providerFlushOutcome = evidence.providerFlushOutcome,
	providerCoverage = providerCoverage,
	appDrainComplete = appDrainComplete,
	lastAdmissionOrdinal = lastAdmissionOrdinal,
	unresolvedSequenceStart = unresolvedSequenceStart,
	unresolvedSequenceEnd = unresolvedSequenceEnd,
)

private fun StepsCountDomainRetirementEvidence.hasFinalRegistrationRemoval(): Boolean =
	registrationRemovalOutcome in setOf("REMOVED", "NOT_REGISTERED")

private fun StepsCountDomainCompletenessMarkerEntity.asUnproven():
	StepsCountDomainCompletenessMarkerEntity {
	if (terminalState == StepsCountDomainCompletenessMarkerEntity.STATE_UNPROVEN) return this
	val checksum = StepsCountDomainReceiptIntegrity.completenessMarkerChecksum(
		ownerKind = ownerKind,
		ownerIdentity = ownerIdentity,
		ownerRevision = ownerRevision,
		terminalState = StepsCountDomainCompletenessMarkerEntity.STATE_UNPROVEN,
		lastAdmissionOrdinal = lastAdmissionOrdinal,
		lastSourceSequence = lastSourceSequence,
		providerFlushOutcome = providerFlushOutcome,
		registrationRemovalOutcome = registrationRemovalOutcome,
		registrationTimelineChecksum = registrationTimelineChecksum,
	)
	return copy(
		terminalState = StepsCountDomainCompletenessMarkerEntity.STATE_UNPROVEN,
		evidenceChecksum = checksum,
	)
}

private fun StepsCountDomainCompletenessMarkerEntity.matches(
	owner: StepsCountDomainOwnerRevisionEntity,
): Boolean = ownerKind == owner.ownerKind &&
	ownerIdentity == owner.ownerIdentity &&
	ownerRevision == owner.ownerRevision &&
	when (owner.operation) {
		StepsCountDomainOwnerRevisionEntity.OPERATION_BIND ->
			terminalState == StepsCountDomainCompletenessMarkerEntity.STATE_COMPLETE
		StepsCountDomainOwnerRevisionEntity.OPERATION_UNPROVEN ->
			terminalState == StepsCountDomainCompletenessMarkerEntity.STATE_UNPROVEN
		else -> false
	}

private fun StepsCountDomainReceiptEntity.bindArguments(): Array<Any?> = arrayOf(
	receiptIdentity,
	domainIdentity,
	ownerKind,
	scopeIdentity,
	ownerIdentity,
	ownerRevision,
	registrationGeneration,
	collectedDataEpoch,
	authorityRevision,
	authorityFingerprint,
	coverageKind,
	coverageVersion,
	countDomainVersion,
	effectChecksum,
	completionEvidenceChecksum,
)

private fun StepsCountDomainOwnerRevisionEntity.bindArguments(): Array<Any?> = arrayOf(
	ownerKind,
	scopeIdentity,
	ownerIdentity,
	ownerRevision,
	operation,
	receiptIdentity,
	ownerEffectChecksum,
	linkedAtMs,
)

private fun StepsCountDomainCompletenessMarkerEntity.bindArguments(): Array<Any?> = arrayOf(
	ownerKind,
	ownerIdentity,
	ownerRevision,
	terminalState,
	lastAdmissionOrdinal,
	lastSourceSequence,
	providerFlushOutcome,
	registrationRemovalOutcome,
	registrationTimelineChecksum,
	evidenceChecksum,
)

private fun StepsCountDomainReceiptEntity.hasSameImmutableDomain(
	other: StepsCountDomainReceiptEntity,
): Boolean = domainIdentity == other.domainIdentity &&
	collectedDataEpoch == other.collectedDataEpoch &&
	countDomainVersion == other.countDomainVersion

private fun SupportSQLiteDatabase.queryOwner(
	ownerKind: String,
	scopeIdentity: String,
	ownerIdentity: String,
	ownerRevision: Long,
): StepsCountDomainOwnerRevisionEntity? {
	requireAuthenticCountDomainOwnerScopes(
		listOf(CountDomainOwnerScopeKey(ownerKind, scopeIdentity, ownerIdentity)),
	)
	return query(
		"SELECT * FROM main.steps_count_domain_owner_revision WHERE " +
			"owner_kind = ? AND scope_identity = ? AND owner_identity = ? " +
			"AND owner_revision = ? LIMIT 2",
		arrayOf(ownerKind, scopeIdentity, ownerIdentity, ownerRevision),
	).use { cursor ->
		if (!cursor.moveToFirst()) return@use null
		val owner = cursor.toStepsCountDomainOwner()
		if (cursor.moveToNext()) throw CountDomainStoredEvidenceException()
		owner
	}
}

private fun SupportSQLiteDatabase.queryLatestOwner(
	ownerKind: String,
	scopeIdentity: String,
	ownerIdentity: String,
): StepsCountDomainOwnerRevisionEntity? {
	requireAuthenticCountDomainOwnerScopes(
		listOf(CountDomainOwnerScopeKey(ownerKind, scopeIdentity, ownerIdentity)),
	)
	return query(
		"SELECT * FROM main.steps_count_domain_owner_revision WHERE " +
			"owner_kind = ? AND scope_identity = ? AND owner_identity = ? " +
			"ORDER BY owner_revision DESC LIMIT 2",
		arrayOf(ownerKind, scopeIdentity, ownerIdentity),
	).use { cursor ->
		if (!cursor.moveToFirst()) return@use null
		val owner = cursor.toStepsCountDomainOwner()
		if (cursor.moveToNext()) {
			val olderOrMalformed = cursor.toStepsCountDomainOwner()
			if (olderOrMalformed.ownerKind != ownerKind ||
				olderOrMalformed.scopeIdentity != scopeIdentity ||
				olderOrMalformed.ownerIdentity != ownerIdentity
			) {
				throw CountDomainStoredEvidenceException()
			}
		}
		owner
	}
}

private fun SupportSQLiteDatabase.queryReceipt(
	receiptIdentity: String,
): StepsCountDomainReceiptEntity? {
	requireAuthenticCountDomainReceiptKeys(listOf(receiptIdentity))
	return query(
		"SELECT * FROM main.steps_count_domain_receipt WHERE receipt_identity = ? LIMIT 2",
		arrayOf(receiptIdentity),
	).use { cursor ->
		if (!cursor.moveToFirst()) return@use null
		val receipt = cursor.toStepsCountDomainReceipt()
		if (cursor.moveToNext()) throw CountDomainStoredEvidenceException()
		receipt
	}
}

private fun SupportSQLiteDatabase.queryCompletenessMarker(
	ownerIdentity: String,
	ownerRevision: Long,
): StepsCountDomainCompletenessMarkerEntity? {
	requireAuthenticCountDomainMarkerKeys(listOf(ownerIdentity to ownerRevision))
	return query(
		"SELECT * FROM main.steps_count_domain_completeness_marker " +
			"WHERE owner_identity = ? AND owner_revision = ? LIMIT 2",
		arrayOf(ownerIdentity, ownerRevision),
	).use { cursor ->
		if (!cursor.moveToFirst()) return@use null
		val marker = cursor.toStepsCountDomainCompletenessMarker()
		if (cursor.moveToNext()) throw CountDomainStoredEvidenceException()
		marker
	}
}

private fun SupportSQLiteDatabase.queryOwnerChunk(
	keys: List<StepsCountDomainOwnerLookupKey>,
): List<StepsCountDomainOwnerRevisionEntity> {
	if (keys.isEmpty()) return emptyList()
	val predicate = keys.joinToString(" OR ") {
		"(owner_kind = ? AND owner_identity = ? AND owner_revision = ?)"
	}
	val arguments = keys.flatMap { listOf(it.ownerKind, it.ownerIdentity, it.ownerRevision) }
	return query(
		"SELECT * FROM main.steps_count_domain_owner_revision WHERE $predicate " +
			"ORDER BY owner_kind, owner_identity, owner_revision LIMIT ${keys.size + 1}",
		arguments.toTypedArray(),
	).use { cursor ->
		buildList {
			while (cursor.moveToNext()) add(cursor.toStepsCountDomainOwner())
		}
	}
}

private fun SupportSQLiteDatabase.queryReceiptChunk(
	identities: List<String>,
): List<StepsCountDomainReceiptEntity> {
	if (identities.isEmpty()) return emptyList()
	requireAuthenticCountDomainReceiptKeys(identities)
	val placeholders = List(identities.size) { "?" }.joinToString()
	return query(
		"SELECT * FROM main.steps_count_domain_receipt WHERE receipt_identity IN ($placeholders) " +
			"ORDER BY receipt_identity LIMIT ${identities.size + 1}",
		identities.toTypedArray(),
	).use { cursor ->
		buildList {
			while (cursor.moveToNext()) add(cursor.toStepsCountDomainReceipt())
		}
	}
}

private fun SupportSQLiteDatabase.queryCompletenessMarkerChunk(
		owners: List<StepsCountDomainOwnerRevisionEntity>,
	): List<StepsCountDomainCompletenessMarkerEntity> {
		if (owners.isEmpty()) return emptyList()
		requireAuthenticCountDomainMarkerKeys(
			owners.map { owner -> owner.ownerIdentity to owner.ownerRevision },
		)
		val predicate = owners.joinToString(" OR ") {
			"(owner_identity = ? AND owner_revision = ?)"
		}
		val arguments = owners.flatMap { listOf(it.ownerIdentity, it.ownerRevision) }
		return query(
			"SELECT * FROM main.steps_count_domain_completeness_marker WHERE $predicate " +
				"ORDER BY owner_identity, owner_revision LIMIT ${owners.size + 1}",
			arguments.toTypedArray(),
		).use { cursor ->
			buildList {
				while (cursor.moveToNext()) add(cursor.toStepsCountDomainCompletenessMarker())
			}
		}
	}

private fun SupportSQLiteDatabase.queryLatestOwnerChunk(
	keys: List<StepsCountDomainOwnerLineageKey>,
): List<Pair<StepsCountDomainOwnerLineageKey, Long>> {
	if (keys.isEmpty()) return emptyList()
	val predicate = keys.joinToString(" OR ") {
		"(owner_kind = ? AND owner_identity = ?)"
	}
	val arguments = keys.flatMap { listOf(it.ownerKind, it.ownerIdentity) }
	return query(
		"SELECT owner_kind, owner_identity, MAX(owner_revision) AS latest_revision " +
			"FROM main.steps_count_domain_owner_revision WHERE $predicate " +
			"GROUP BY owner_kind, owner_identity LIMIT ${keys.size + 1}",
		arguments.toTypedArray(),
	).use { cursor ->
		buildList {
			while (cursor.moveToNext()) {
				add(
					StepsCountDomainOwnerLineageKey(
						cursor.requiredText("owner_kind"),
						cursor.requiredText("owner_identity"),
					) to cursor.requiredLong("latest_revision"),
				)
			}
		}
	}
}

private fun SupportSQLiteDatabase.deleteOwnerChunk(
	keys: List<StepsCountDomainOwnerLookupKey>,
): Int {
	if (keys.isEmpty()) return 0
	val predicate = keys.joinToString(" OR ") {
		"(owner_kind = ? AND owner_identity = ? AND owner_revision = ?)"
	}
	val arguments = keys.flatMap { listOf(it.ownerKind, it.ownerIdentity, it.ownerRevision) }
	return compileStatement(
		"DELETE FROM main.steps_count_domain_owner_revision WHERE $predicate",
	).use { statement ->
		arguments.forEachIndexed { index, value ->
			when (value) {
				is String -> statement.bindString(index + 1, value)
				is Long -> statement.bindLong(index + 1, value)
				else -> error("Unsupported count-domain owner key value")
			}
		}
		statement.executeUpdateDelete()
	}
}

private fun SupportSQLiteDatabase.deleteUnreferencedReceipts(
	receiptIdentities: List<String>,
): Long {
	if (receiptIdentities.isEmpty()) return 0L
	val placeholders = List(receiptIdentities.size) { "?" }.joinToString()
	val before = longForQuery(
		"SELECT COUNT(*) FROM main.steps_count_domain_receipt AS receipt " +
			"WHERE receipt_identity IN ($placeholders) " +
			"AND NOT EXISTS (SELECT 1 FROM main.steps_count_domain_owner_revision AS owner " +
			"WHERE owner.receipt_identity = receipt.receipt_identity)",
		receiptIdentities.toTypedArray(),
	)
	execSQL(
		"DELETE FROM main.steps_count_domain_receipt WHERE receipt_identity IN ($placeholders) " +
			"AND NOT EXISTS (SELECT 1 FROM main.steps_count_domain_owner_revision AS owner " +
			"WHERE owner.receipt_identity = steps_count_domain_receipt.receipt_identity)",
		receiptIdentities.toTypedArray(),
	)
	return before
}

private fun SupportSQLiteDatabase.queryTerminalCompactionCandidates(
	maximumRetainedTerminalOwners: Int,
	limit: Int,
): List<StepsCountDomainOwnerLookupKey> =
	query(
		"SELECT owner_kind, owner_identity, owner_revision " +
			"FROM main.steps_count_domain_owner_revision " +
			"WHERE operation IN ('RETRACT', 'UNPROVEN') " +
			"ORDER BY linked_at_ms DESC, owner_kind, owner_identity, owner_revision DESC " +
			"LIMIT ? OFFSET ?",
		arrayOf(limit, maximumRetainedTerminalOwners),
	).use { cursor ->
		buildList {
			while (cursor.moveToNext()) {
				add(
					StepsCountDomainOwnerLookupKey(
						cursor.requiredText("owner_kind"),
						cursor.requiredText("owner_identity"),
						cursor.requiredLong("owner_revision"),
					),
				)
			}
		}
	}

private fun SupportSQLiteDatabase.querySessionWalPruneOwners(
	safeOrdinal: Long,
	createdBeforeMs: Long,
	limit: Int,
): List<StepsCountDomainOwnerLookupKey> =
	query(
		"SELECT wal.admission_ordinal, wal.event_id FROM source_event_wal AS wal " +
			"WHERE wal.source_kind = ? AND wal.created_at_ms < ? AND wal.admission_ordinal <= ? " +
			"AND NOT (" +
			"wal.logical_tracking_id IS NOT NULL AND wal.service_run_id IS NOT NULL AND " +
			"wal.admission_ordinal = (" +
			"SELECT MAX(candidate.admission_ordinal) FROM source_event_wal AS candidate " +
			"WHERE candidate.source_kind = wal.source_kind " +
			"AND candidate.source_instance_id = wal.source_instance_id " +
			"AND candidate.registration_generation = wal.registration_generation " +
			"AND candidate.logical_tracking_id = wal.logical_tracking_id " +
			"AND candidate.service_run_id = wal.service_run_id " +
			"AND (candidate.authorization_purpose_eligibility_mask & ?) != 0" +
			") AND (" +
			"EXISTS (SELECT 1 FROM provider_registration_generation AS registration " +
			"WHERE registration.source_kind = wal.source_kind " +
			"AND registration.source_instance_id = wal.source_instance_id " +
			"AND registration.registration_generation = wal.registration_generation " +
			"AND registration.status IN ('RESERVED', 'ACTIVE', 'RETIRING')) " +
			"OR EXISTS (SELECT 1 FROM source_service_run AS run " +
			"WHERE run.service_run_id = wal.service_run_id " +
			"AND run.logical_tracking_id = wal.logical_tracking_id " +
			"AND (run.completed_at_ms IS NULL OR run.state NOT IN ('FINALIZED', 'CLOSED', 'FAILED')))" +
			")) ORDER BY wal.created_at_ms, wal.admission_ordinal LIMIT ?",
		arrayOf(
			SourceDestinationOwnerEntity.SOURCE_STEPS,
			createdBeforeMs,
			safeOrdinal,
			SourceBrokerPurpose.MASK_SESSION_CAPTURE,
			limit,
		),
	).use { cursor ->
		buildList {
			while (cursor.moveToNext()) {
				val admissionOrdinal = cursor.requiredLong(0)
				val eventId = cursor.requiredText(1)
				if (admissionOrdinal <= 0L || eventId.isBlank()) {
					throw CountDomainStoredEvidenceException()
				}
				add(
					StepsCountDomainOwnerLookupKey(
						ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_WAL,
						ownerIdentity =
							StepsCountDomainReceiptIntegrity.sessionWalOwnerIdentity(
								admissionOrdinal,
								eventId,
							),
						ownerRevision = 1L,
					),
				)
			}
		}
	}

private fun SupportSQLiteDatabase.requireAuthenticMissingCountDomainOwnerKeys(
	keys: List<StepsCountDomainOwnerLookupKey>,
) {
	for (chunk in keys.distinct().chunked(OWNER_QUERY_CHUNK)) {
		if (chunk.isEmpty()) continue
		val predicate = chunk.joinToString(" OR ") {
			"(CAST(owner_kind AS TEXT) = ? AND CAST(owner_identity AS TEXT) = ? " +
				"AND CAST(owner_revision AS INTEGER) = ?) OR " +
				"(owner_kind = ? AND owner_revision = ? AND typeof(owner_identity) != 'text') OR " +
				"(owner_identity = ? AND owner_revision = ? AND typeof(owner_kind) != 'text') OR " +
				"(owner_kind = ? AND owner_identity = ? AND typeof(owner_revision) != 'integer')"
		}
		val arguments = chunk.flatMap { key ->
			listOf(
				key.ownerKind,
				key.ownerIdentity,
				key.ownerRevision,
				key.ownerKind,
				key.ownerRevision,
				key.ownerIdentity,
				key.ownerRevision,
				key.ownerKind,
				key.ownerIdentity,
			)
		}
		val hidden = query(
			"SELECT 1 FROM main.steps_count_domain_owner_revision WHERE ($predicate) LIMIT 1",
			arguments.toTypedArray(),
		).use { cursor -> cursor.moveToFirst() }
		if (hidden) throw CountDomainStoredEvidenceException()
	}
}

private fun SupportSQLiteDatabase.requireAuthenticCountDomainOwnerScopes(
	keys: List<CountDomainOwnerScopeKey>,
) {
	for (chunk in keys.distinct().chunked(LATEST_QUERY_CHUNK)) {
		if (chunk.isEmpty()) continue
		val invalidPredicate = chunk.joinToString(" OR ") {
			"((((owner_kind = ? AND owner_identity = ?) OR " +
				"((typeof(owner_kind) != 'text' OR typeof(owner_identity) != 'text') " +
				"AND CAST(owner_kind AS TEXT) = ? AND CAST(owner_identity AS TEXT) = ?) OR " +
				"(owner_kind = ? AND scope_identity = ? AND typeof(owner_identity) != 'text') OR " +
				"(scope_identity = ? AND owner_identity = ? AND typeof(owner_kind) != 'text') OR " +
				"(owner_kind = ? AND owner_identity = ? AND typeof(scope_identity) != 'text'))) AND (" +
				"typeof(owner_kind) != 'text' OR " +
				"owner_kind NOT IN " +
				"('SESSION_WAL', 'SESSION_FACT', 'SESSION_COMPLETENESS', 'AMBIENT_FACT') OR " +
				"typeof(scope_identity) != 'text' OR CAST(scope_identity AS TEXT) != ? OR " +
				"length(scope_identity) != 71 OR substr(scope_identity, 1, 7) != 'sha256:' OR " +
				"substr(scope_identity, 8) GLOB '*[^0-9a-f]*' OR " +
				"typeof(owner_identity) != 'text' OR length(owner_identity) != 71 OR " +
				"substr(owner_identity, 1, 7) != 'sha256:' OR " +
				"substr(owner_identity, 8) GLOB '*[^0-9a-f]*' OR " +
				"typeof(owner_revision) != 'integer' OR owner_revision <= 0))"
		}
		val arguments = chunk.flatMap { key ->
			listOf(
				key.ownerKind,
				key.ownerIdentity,
				key.ownerKind,
				key.ownerIdentity,
				key.ownerKind,
				key.scopeIdentity,
				key.scopeIdentity,
				key.ownerIdentity,
				key.ownerKind,
				key.ownerIdentity,
				key.scopeIdentity,
			)
		}
		val invalid = query(
			"SELECT 1 FROM main.steps_count_domain_owner_revision " +
				"WHERE $invalidPredicate LIMIT 1",
			arguments.toTypedArray(),
		).use { cursor -> cursor.moveToFirst() }
		if (invalid) throw CountDomainStoredEvidenceException()
	}
}

private fun SupportSQLiteDatabase.requireAuthenticCountDomainReceiptKeys(
	identities: List<String>,
) {
	for (chunk in identities.distinct().chunked(RECEIPT_QUERY_CHUNK)) {
		if (chunk.isEmpty()) continue
		val predicate = chunk.joinToString(" OR ") {
			"(receipt_identity = ? OR (typeof(receipt_identity) != 'text' " +
				"AND CAST(receipt_identity AS TEXT) = ?))"
		}
		val arguments = chunk.flatMap { identity -> listOf(identity, identity) }
		val invalid = query(
			"SELECT 1 FROM main.steps_count_domain_receipt WHERE ($predicate) AND (" +
				"typeof(receipt_identity) != 'text' OR length(receipt_identity) != 71 OR " +
				"substr(receipt_identity, 1, 7) != 'sha256:' OR " +
				"substr(receipt_identity, 8) GLOB '*[^0-9a-f]*') LIMIT 1",
			arguments.toTypedArray(),
		).use { cursor -> cursor.moveToFirst() }
		if (invalid) throw CountDomainStoredEvidenceException()
	}
}

private fun SupportSQLiteDatabase.requireAuthenticCountDomainMarkerKeys(
	keys: List<Pair<String, Long>>,
) {
	for (chunk in keys.distinct().chunked(MARKER_QUERY_CHUNK)) {
		if (chunk.isEmpty()) continue
		val predicate = chunk.joinToString(" OR ") {
			"((owner_identity = ? AND owner_revision = ?) OR " +
				"((typeof(owner_identity) != 'text' OR typeof(owner_revision) != 'integer') " +
				"AND CAST(owner_identity AS TEXT) = ? AND CAST(owner_revision AS INTEGER) = ?))"
		}
		val arguments = chunk.flatMap { (identity, revision) ->
			listOf(identity, revision, identity, revision)
		}
		val invalid = query(
			"SELECT 1 FROM main.steps_count_domain_completeness_marker WHERE ($predicate) AND (" +
				"typeof(owner_kind) != 'text' OR owner_kind != 'SESSION_COMPLETENESS' OR " +
				"typeof(owner_identity) != 'text' OR length(owner_identity) != 71 OR " +
				"substr(owner_identity, 1, 7) != 'sha256:' OR " +
				"substr(owner_identity, 8) GLOB '*[^0-9a-f]*' OR " +
				"typeof(owner_revision) != 'integer' OR owner_revision <= 0) LIMIT 1",
			arguments.toTypedArray(),
		).use { cursor -> cursor.moveToFirst() }
		if (invalid) throw CountDomainStoredEvidenceException()
	}
}

private fun SupportSQLiteDatabase.requireAuthenticCountDomainMaintenanceRows() {
	query("SELECT * FROM main.steps_count_domain_owner_revision").use { cursor ->
		while (cursor.moveToNext()) cursor.toStepsCountDomainOwner()
	}
	query("SELECT * FROM main.steps_count_domain_receipt").use { cursor ->
		while (cursor.moveToNext()) cursor.toStepsCountDomainReceipt()
	}
	query("SELECT * FROM main.steps_count_domain_completeness_marker").use { cursor ->
		while (cursor.moveToNext()) cursor.toStepsCountDomainCompletenessMarker()
	}
}

private fun SupportSQLiteDatabase.requireAuthenticStepsWalMaintenanceDomain() {
	val invalidWal = query(
		"SELECT 1 FROM source_event_wal WHERE " +
			"(source_kind = ? OR (typeof(source_kind) != 'integer' " +
			"AND CAST(source_kind AS INTEGER) = ?) OR " +
			"(typeof(source_kind) = 'integer' AND source_kind NOT BETWEEN 1 AND 6)) AND (" +
			"typeof(event_id) != 'text' OR trim(event_id) = '' OR " +
			"typeof(admission_ordinal) != 'integer' OR admission_ordinal <= 0 OR " +
			"typeof(created_at_ms) != 'integer' OR created_at_ms < 0 OR " +
			"typeof(source_kind) != 'integer' OR source_kind != ? OR " +
			"typeof(source_instance_id) != 'text' OR trim(source_instance_id) = '' OR " +
			"typeof(registration_generation) != 'integer' OR registration_generation <= 0 OR " +
			"typeof(authorization_purpose_eligibility_mask) != 'integer' OR " +
			"authorization_purpose_eligibility_mask < 0 OR " +
			"(authorization_purpose_eligibility_mask & ?) != " +
			"authorization_purpose_eligibility_mask OR " +
			"typeof(logical_tracking_id) NOT IN ('text', 'null') OR " +
			"typeof(service_run_id) NOT IN ('text', 'null') OR " +
			"(logical_tracking_id IS NULL) != (service_run_id IS NULL) OR " +
			"(logical_tracking_id IS NOT NULL AND trim(logical_tracking_id) = '') OR " +
			"(service_run_id IS NOT NULL AND trim(service_run_id) = '') OR " +
			"((authorization_purpose_eligibility_mask & ?) != 0 AND " +
			"(logical_tracking_id IS NULL OR service_run_id IS NULL))) LIMIT 1",
		arrayOf(
			SourceDestinationOwnerEntity.SOURCE_STEPS,
			SourceDestinationOwnerEntity.SOURCE_STEPS,
			SourceDestinationOwnerEntity.SOURCE_STEPS,
			SourceBrokerPurpose.ALL_MASK,
			SourceBrokerPurpose.MASK_SESSION_CAPTURE,
		),
	).use { cursor -> cursor.moveToFirst() }
	if (invalidWal) throw CountDomainStoredEvidenceException()

	val invalidRegistration = query(
		"SELECT 1 FROM provider_registration_generation AS registration " +
			"JOIN source_event_wal AS wal ON " +
			"CAST(registration.source_instance_id AS TEXT) = CAST(wal.source_instance_id AS TEXT) " +
			"AND (registration.registration_generation = wal.registration_generation OR " +
			"typeof(registration.registration_generation) != 'integer') " +
			"WHERE " +
			"(wal.source_kind = ? OR (typeof(wal.source_kind) != 'integer' " +
			"AND CAST(wal.source_kind AS INTEGER) = ?)) " +
			"AND (" +
			"typeof(registration.source_kind) != 'integer' OR registration.source_kind != ? OR " +
			"typeof(registration.source_instance_id) != 'text' OR " +
			"trim(registration.source_instance_id) = '' OR " +
			"typeof(registration.registration_generation) != 'integer' OR " +
			"registration.registration_generation <= 0 OR " +
			"typeof(registration.status) != 'text' OR registration.status NOT IN " +
			"('RESERVED', 'ACTIVE', 'RETIRING', 'RETIRED', 'FAILED')) LIMIT 1",
		arrayOf(
			SourceDestinationOwnerEntity.SOURCE_STEPS,
			SourceDestinationOwnerEntity.SOURCE_STEPS,
			SourceDestinationOwnerEntity.SOURCE_STEPS,
		),
	).use { cursor -> cursor.moveToFirst() }
	if (invalidRegistration) throw CountDomainStoredEvidenceException()

	val invalidRun = query(
		"SELECT 1 FROM source_service_run AS run WHERE EXISTS (" +
			"SELECT 1 FROM source_event_wal AS wal WHERE " +
			"(wal.source_kind = ? OR (typeof(wal.source_kind) != 'integer' " +
			"AND CAST(wal.source_kind AS INTEGER) = ?)) " +
			"AND wal.logical_tracking_id IS NOT NULL AND wal.service_run_id IS NOT NULL " +
			"AND CAST(run.logical_tracking_id AS TEXT) = CAST(wal.logical_tracking_id AS TEXT) " +
			"AND CAST(run.service_run_id AS TEXT) = CAST(wal.service_run_id AS TEXT)) AND (" +
			"typeof(run.logical_tracking_id) != 'text' OR trim(run.logical_tracking_id) = '' OR " +
			"typeof(run.service_run_id) != 'text' OR trim(run.service_run_id) = '' OR " +
			"typeof(run.state) != 'text' OR run.state NOT IN " +
			"('IDLE', 'STARTING', 'ACTIVE', 'RECONFIGURING', 'STOPPING', " +
			"'FINALIZED', 'CLOSED', 'FAILED') OR " +
			"typeof(run.completed_at_ms) NOT IN ('integer', 'null') OR " +
			"(typeof(run.completed_at_ms) = 'integer' AND run.completed_at_ms < 0) OR " +
			"(run.state IN ('FINALIZED', 'CLOSED', 'FAILED') AND run.completed_at_ms IS NULL) OR " +
			"(run.state NOT IN ('FINALIZED', 'CLOSED', 'FAILED') " +
			"AND run.completed_at_ms IS NOT NULL)) LIMIT 1",
		arrayOf(
			SourceDestinationOwnerEntity.SOURCE_STEPS,
			SourceDestinationOwnerEntity.SOURCE_STEPS,
		),
	).use { cursor -> cursor.moveToFirst() }
	if (invalidRun) throw CountDomainStoredEvidenceException()
}

private fun SupportSQLiteDatabase.longForQuery(
	sql: String,
	args: Array<out Any?> = emptyArray(),
): Long = query(sql, args).use { cursor ->
	if (!cursor.moveToFirst()) throw CountDomainStoredEvidenceException()
	val value = cursor.requiredLong(0)
	if (cursor.moveToNext()) throw CountDomainStoredEvidenceException()
	value
}

private fun android.database.Cursor.toStepsCountDomainOwner() =
	authenticatedCountDomainRow {
		StepsCountDomainOwnerRevisionEntity(
			ownerKind = requiredText("owner_kind"),
			scopeIdentity = requiredText("scope_identity"),
			ownerIdentity = requiredText("owner_identity"),
			ownerRevision = requiredLong("owner_revision"),
			operation = requiredText("operation"),
			receiptIdentity = nullableText("receipt_identity"),
			ownerEffectChecksum = requiredText("owner_effect_checksum"),
			linkedAtMs = requiredLong("linked_at_ms"),
		)
	}

private fun android.database.Cursor.toStepsCountDomainReceipt() =
	authenticatedCountDomainRow {
		StepsCountDomainReceiptEntity(
			receiptIdentity = requiredText("receipt_identity"),
			domainIdentity = requiredText("domain_identity"),
			ownerKind = requiredText("owner_kind"),
			scopeIdentity = requiredText("scope_identity"),
			ownerIdentity = requiredText("owner_identity"),
			ownerRevision = requiredLong("owner_revision"),
			registrationGeneration = requiredLong("registration_generation"),
			collectedDataEpoch = requiredLong("collected_data_epoch"),
			authorityRevision = requiredLong("authority_revision"),
			authorityFingerprint = requiredText("authority_fingerprint"),
			coverageKind = requiredText("coverage_kind"),
			coverageVersion = requiredInt("coverage_version"),
			countDomainVersion = requiredInt("count_domain_version"),
			effectChecksum = requiredText("effect_checksum"),
			completionEvidenceChecksum = nullableText("completion_evidence_checksum"),
		)
	}

private fun android.database.Cursor.toStepsCountDomainCompletenessMarker() =
	authenticatedCountDomainRow {
		StepsCountDomainCompletenessMarkerEntity(
			ownerKind = requiredText("owner_kind"),
			ownerIdentity = requiredText("owner_identity"),
			ownerRevision = requiredLong("owner_revision"),
			terminalState = requiredText("terminal_state"),
			lastAdmissionOrdinal = nullableLong("last_admission_ordinal"),
			lastSourceSequence = nullableLong("last_source_sequence"),
			providerFlushOutcome = requiredText("provider_flush_outcome"),
			registrationRemovalOutcome = requiredText("registration_removal_outcome"),
			registrationTimelineChecksum = requiredText("registration_timeline_checksum"),
			evidenceChecksum = requiredText("evidence_checksum"),
		)
	}

private inline fun <T> authenticatedCountDomainRow(block: () -> T): T =
	try {
		block()
	} catch (error: CountDomainStoredEvidenceException) {
		throw error
	} catch (error: IllegalArgumentException) {
		throw CountDomainStoredEvidenceException(error)
	} catch (error: IllegalStateException) {
		throw CountDomainStoredEvidenceException(error)
	}

private fun android.database.Cursor.requiredText(column: String): String =
	requiredText(getColumnIndexOrThrow(column))

private fun android.database.Cursor.requiredText(index: Int): String {
	if (getType(index) != android.database.Cursor.FIELD_TYPE_STRING) {
		throw CountDomainStoredEvidenceException()
	}
	return getString(index) ?: throw CountDomainStoredEvidenceException()
}

private fun android.database.Cursor.nullableText(column: String): String? {
	val index = getColumnIndexOrThrow(column)
	return when (getType(index)) {
		android.database.Cursor.FIELD_TYPE_NULL -> null
		android.database.Cursor.FIELD_TYPE_STRING ->
			getString(index) ?: throw CountDomainStoredEvidenceException()
		else -> throw CountDomainStoredEvidenceException()
	}
}

private fun android.database.Cursor.requiredLong(column: String): Long =
	requiredLong(getColumnIndexOrThrow(column))

private fun android.database.Cursor.requiredLong(index: Int): Long {
	if (getType(index) != android.database.Cursor.FIELD_TYPE_INTEGER) {
		throw CountDomainStoredEvidenceException()
	}
	return getLong(index)
}

private fun android.database.Cursor.requiredInt(column: String): Int {
	val value = requiredLong(column)
	if (value !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
		throw CountDomainStoredEvidenceException()
	}
	return value.toInt()
}

private fun android.database.Cursor.nullableLong(column: String): Long? {
	val index = getColumnIndexOrThrow(column)
	return when (getType(index)) {
		android.database.Cursor.FIELD_TYPE_NULL -> null
		android.database.Cursor.FIELD_TYPE_INTEGER -> getLong(index)
		else -> throw CountDomainStoredEvidenceException()
	}
}

private class CountDomainStoredEvidenceException(
	cause: Throwable? = null,
) : IllegalStateException("Stored Steps count-domain evidence is unverifiable", cause)
