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
 * preserves pre-P5 behavior with SCHEMA_UNAVAILABLE. Any partial, markerless, legacy, or corrupt
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
			ownerIdentity,
		)
		if (latestOwner?.operation == StepsCountDomainOwnerRevisionEntity.OPERATION_UNPROVEN &&
			counterDomainToken != null
		) {
			return StepsCountDomainWriteResult.UNPROVEN
		}
		if (counterDomainToken == null) {
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

	suspend fun recordSessionFact(fact: StepFactRevisionEntity): StepsCountDomainWriteResult {
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
	): StepsCountDomainWriteResult {
		if (row.sourceKind != SourceDestinationOwnerEntity.SOURCE_STEPS) {
			return StepsCountDomainWriteResult.NOT_APPLICABLE
		}
		writeSchemaFailure()?.let { return it }
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
		val timeline = database.trackingHistoryReadDao().sourceCompleteness(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
			serviceRunIds = listOf(row.serviceRunId),
			limit = MAX_COMPLETENESS_TIMELINE + 1,
		).filter { it.logicalTrackingId == row.logicalTrackingId }
		val timelineChecksum =
			StepsCountDomainReceiptIntegrity.registrationTimelineChecksum(timeline)
		val timelineWithinBound = timeline.size <= MAX_COMPLETENESS_TIMELINE
		val priorTimelineIsComplete = timelineWithinBound && timeline.filter { it != row }.all { prior ->
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
			)
			priorOwner?.isAuthentic() == true
		}
		val exactComplete = row.hasExactCompleteRetirement(retirementEvidence) &&
			timelineWithinBound &&
			StepsSessionCompletenessIntegrity.hasValidRegisteredTimeline(
				timeline,
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
		) ?: return appendTerminalUnproven(
			ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS,
			scopeIdentity = scopeIdentity,
			ownerIdentity = ownerIdentity,
			ownerRevision = ownerRevision,
			ownerEffectChecksum = ownerEffectChecksum,
			linkedAtMs = row.updatedAtMs,
			completenessMarker = marker.asUnproven(),
		)
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

	suspend fun recordAmbientFact(
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
			StepsCountDomainSchemaState.Absent ->
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
			if (ownersByKey.size != owners.size) return StepsCountDomainOwnerRead.Unverifiable
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
	): StepsCountDomainMaintenanceResult {
		val distinct = keys.distinct()
		if (distinct.size > MAX_MAINTENANCE_OWNER_BATCH) {
			return StepsCountDomainMaintenanceResult.Overflow
		}
		val sqlite = database.openHelper.writableDatabase
		when (StepsCountDomainSchema.inspect(sqlite)) {
			StepsCountDomainSchemaState.Absent ->
				return StepsCountDomainMaintenanceResult.SchemaUnavailable
			StepsCountDomainSchemaState.Incompatible ->
				return StepsCountDomainMaintenanceResult.StoredEvidenceUnverifiable
			StepsCountDomainSchemaState.ValidV2 -> Unit
		}
		if (distinct.isEmpty()) return StepsCountDomainMaintenanceResult.Applied(0L, 0L)
		val owners = distinct.chunked(OWNER_QUERY_CHUNK).flatMap(sqlite::queryOwnerChunk)
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
	): StepsCountDomainMaintenanceResult {
		val sqlite = database.openHelper.writableDatabase
		when (StepsCountDomainSchema.inspect(sqlite)) {
			StepsCountDomainSchemaState.Absent ->
				return StepsCountDomainMaintenanceResult.SchemaUnavailable
			StepsCountDomainSchemaState.Incompatible ->
				return StepsCountDomainMaintenanceResult.StoredEvidenceUnverifiable
			StepsCountDomainSchemaState.ValidV2 -> Unit
		}
		val ownerCount = sqlite.longForQuery(
			if (mode == StepsCountDomainFullClearMode.REMOVE_ALL) {
				"SELECT COUNT(*) FROM steps_count_domain_owner_revision"
			} else {
				"SELECT COUNT(*) FROM steps_count_domain_owner_revision WHERE operation = 'BIND'"
			},
		)
		if (mode == StepsCountDomainFullClearMode.REMOVE_ALL) {
			sqlite.execSQL("DELETE FROM steps_count_domain_completeness_marker")
			sqlite.execSQL("DELETE FROM steps_count_domain_owner_revision")
		} else {
			sqlite.execSQL(
				"DELETE FROM steps_count_domain_owner_revision WHERE operation = 'BIND'",
			)
		}
		val receiptCount =
			sqlite.longForQuery("SELECT COUNT(*) FROM steps_count_domain_receipt")
		sqlite.execSQL("DELETE FROM steps_count_domain_receipt")
		return StepsCountDomainMaintenanceResult.Applied(ownerCount, receiptCount)
	}

	fun compactTerminalOwners(
		maximumRetainedTerminalOwners: Int,
		batchSize: Int,
		sourceFenceAuthenticated: Boolean,
	): StepsCountDomainMaintenanceResult {
		require(sourceFenceAuthenticated)
		require(maximumRetainedTerminalOwners >= 0)
		require(batchSize in 1..MAX_MAINTENANCE_OWNER_BATCH)
		val sqlite = database.openHelper.writableDatabase
		when (StepsCountDomainSchema.inspect(sqlite)) {
			StepsCountDomainSchemaState.Absent ->
				return StepsCountDomainMaintenanceResult.SchemaUnavailable
			StepsCountDomainSchemaState.Incompatible ->
				return StepsCountDomainMaintenanceResult.StoredEvidenceUnverifiable
			StepsCountDomainSchemaState.ValidV2 -> Unit
		}
		val candidates = sqlite.queryTerminalCompactionCandidates(
			maximumRetainedTerminalOwners,
			batchSize,
		)
		return removeOwners(candidates)
	}

	fun removeSessionWalOwnersForPrune(
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
			StepsCountDomainSchemaState.Absent ->
				return StepsCountDomainMaintenanceResult.SchemaUnavailable
			StepsCountDomainSchemaState.Incompatible ->
				return StepsCountDomainMaintenanceResult.StoredEvidenceUnverifiable
			StepsCountDomainSchemaState.ValidV2 -> Unit
		}
		val keys = sqlite.querySessionWalPruneOwners(
			safeOrdinal,
			createdBeforeMs,
			limit,
		)
		var removedOwners = 0L
		var removedReceipts = 0L
		keys.chunked(MAX_MAINTENANCE_OWNER_BATCH).forEach { chunk ->
			when (val result = removeOwners(chunk)) {
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
		val latest = sqlite.queryLatestOwner(owner.ownerKind, owner.ownerIdentity)
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
			owner.ownerIdentity,
			owner.ownerRevision,
		)
		if (latest?.operation == StepsCountDomainOwnerRevisionEntity.OPERATION_RETRACT &&
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
	): StepsCountDomainStoredOwner? {
		val sqlite = database.openHelper.writableDatabase
		if (StepsCountDomainSchema.inspect(sqlite) != StepsCountDomainSchemaState.ValidV2) {
			return null
		}
		val owner = sqlite.queryOwner(
			key.ownerKind,
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
			StepsCountDomainSchemaState.Absent -> StepsCountDomainWriteResult.SCHEMA_UNAVAILABLE
			StepsCountDomainSchemaState.ValidV2 -> null
			StepsCountDomainSchemaState.Incompatible ->
				StepsCountDomainWriteResult.STORED_EVIDENCE_UNVERIFIABLE
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
			"INSERT OR IGNORE INTO steps_count_domain_receipt (" +
					"receipt_identity, domain_identity, owner_kind, scope_identity, " +
					"owner_identity, owner_revision, " +
				"registration_generation, collected_data_epoch, authority_revision, " +
				"authority_fingerprint, coverage_kind, coverage_version, count_domain_version, " +
					"effect_checksum, completion_evidence_checksum) " +
					"VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
		const val INSERT_OWNER_SQL =
			"INSERT OR IGNORE INTO steps_count_domain_owner_revision (" +
				"owner_kind, scope_identity, owner_identity, owner_revision, operation, " +
				"receipt_identity, owner_effect_checksum, linked_at_ms) " +
				"VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
		const val INSERT_COMPLETENESS_MARKER_SQL =
			"INSERT OR IGNORE INTO steps_count_domain_completeness_marker (" +
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

fun SourceSessionCompletenessEntity.withMonotonicStepsCountDomainRevision(
	existing: SourceSessionCompletenessEntity?,
): SourceSessionCompletenessEntity {
	if (existing == null) return this
	require(
		logicalTrackingId == existing.logicalTrackingId &&
			serviceRunId == existing.serviceRunId &&
			sourceKind == existing.sourceKind &&
			sourceInstanceId == existing.sourceInstanceId &&
			registrationGeneration == existing.registrationGeneration,
	)
	if (copy(updatedAtMs = existing.updatedAtMs) == existing) return existing
	return copy(
		updatedAtMs = maxOf(
			updatedAtMs,
			Math.addExact(existing.updatedAtMs, 1L),
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
	ownerIdentity: String,
	ownerRevision: Long,
): StepsCountDomainOwnerRevisionEntity? =
	query(
		"SELECT * FROM steps_count_domain_owner_revision WHERE owner_kind = ? " +
			"AND owner_identity = ? AND owner_revision = ? LIMIT 1",
		arrayOf(ownerKind, ownerIdentity, ownerRevision),
	).use { cursor ->
		if (cursor.moveToFirst()) cursor.toStepsCountDomainOwner() else null
	}

private fun SupportSQLiteDatabase.queryLatestOwner(
	ownerKind: String,
	ownerIdentity: String,
): StepsCountDomainOwnerRevisionEntity? =
	query(
		"SELECT * FROM steps_count_domain_owner_revision WHERE owner_kind = ? " +
			"AND owner_identity = ? ORDER BY owner_revision DESC LIMIT 1",
		arrayOf(ownerKind, ownerIdentity),
	).use { cursor ->
		if (cursor.moveToFirst()) cursor.toStepsCountDomainOwner() else null
	}

private fun SupportSQLiteDatabase.queryReceipt(
	receiptIdentity: String,
): StepsCountDomainReceiptEntity? =
	query(
		"SELECT * FROM steps_count_domain_receipt WHERE receipt_identity = ? LIMIT 1",
		arrayOf(receiptIdentity),
	).use { cursor ->
		if (cursor.moveToFirst()) cursor.toStepsCountDomainReceipt() else null
	}

private fun SupportSQLiteDatabase.queryCompletenessMarker(
	ownerIdentity: String,
	ownerRevision: Long,
): StepsCountDomainCompletenessMarkerEntity? =
	query(
		"SELECT * FROM steps_count_domain_completeness_marker " +
			"WHERE owner_identity = ? AND owner_revision = ? LIMIT 1",
		arrayOf(ownerIdentity, ownerRevision),
	).use { cursor ->
		if (cursor.moveToFirst()) cursor.toStepsCountDomainCompletenessMarker() else null
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
		"SELECT * FROM steps_count_domain_owner_revision WHERE $predicate " +
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
	val placeholders = List(identities.size) { "?" }.joinToString()
	return query(
		"SELECT * FROM steps_count_domain_receipt WHERE receipt_identity IN ($placeholders) " +
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
		val predicate = owners.joinToString(" OR ") {
			"(owner_identity = ? AND owner_revision = ?)"
		}
		val arguments = owners.flatMap { listOf(it.ownerIdentity, it.ownerRevision) }
		return query(
			"SELECT * FROM steps_count_domain_completeness_marker WHERE $predicate " +
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
			"FROM steps_count_domain_owner_revision WHERE $predicate " +
			"GROUP BY owner_kind, owner_identity LIMIT ${keys.size + 1}",
		arguments.toTypedArray(),
	).use { cursor ->
		buildList {
			while (cursor.moveToNext()) {
				add(
					StepsCountDomainOwnerLineageKey(
						cursor.string("owner_kind"),
						cursor.string("owner_identity"),
					) to cursor.long("latest_revision"),
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
		"DELETE FROM steps_count_domain_owner_revision WHERE $predicate",
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
		"SELECT COUNT(*) FROM steps_count_domain_receipt WHERE receipt_identity IN ($placeholders) " +
			"AND NOT EXISTS (SELECT 1 FROM steps_count_domain_owner_revision AS owner " +
			"WHERE owner.receipt_identity = steps_count_domain_receipt.receipt_identity)",
		receiptIdentities.toTypedArray(),
	)
	execSQL(
		"DELETE FROM steps_count_domain_receipt WHERE receipt_identity IN ($placeholders) " +
			"AND NOT EXISTS (SELECT 1 FROM steps_count_domain_owner_revision AS owner " +
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
			"FROM steps_count_domain_owner_revision " +
			"WHERE operation IN ('RETRACT', 'UNPROVEN') " +
			"ORDER BY linked_at_ms DESC, owner_kind, owner_identity, owner_revision DESC " +
			"LIMIT ? OFFSET ?",
		arrayOf(limit, maximumRetainedTerminalOwners),
	).use { cursor ->
		buildList {
			while (cursor.moveToNext()) {
				add(
					StepsCountDomainOwnerLookupKey(
						cursor.string("owner_kind"),
						cursor.string("owner_identity"),
						cursor.long("owner_revision"),
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
		"SELECT admission_ordinal, event_id FROM source_event_wal " +
			"WHERE source_kind = ? AND created_at_ms < ? AND admission_ordinal <= ? " +
			"ORDER BY created_at_ms, admission_ordinal LIMIT ?",
		arrayOf(
			SourceDestinationOwnerEntity.SOURCE_STEPS,
			createdBeforeMs,
			safeOrdinal,
			limit,
		),
	).use { cursor ->
		buildList {
			while (cursor.moveToNext()) {
				val admissionOrdinal = cursor.getLong(0)
				val eventId = cursor.getString(1)
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

private fun SupportSQLiteDatabase.longForQuery(
	sql: String,
	args: Array<out Any?> = emptyArray(),
): Long = query(sql, args).use { cursor ->
	check(cursor.moveToFirst())
	cursor.getLong(0)
}

private fun android.database.Cursor.toStepsCountDomainOwner() =
	StepsCountDomainOwnerRevisionEntity(
		ownerKind = string("owner_kind"),
		scopeIdentity = string("scope_identity"),
		ownerIdentity = string("owner_identity"),
		ownerRevision = long("owner_revision"),
		operation = string("operation"),
		receiptIdentity = nullableString("receipt_identity"),
		ownerEffectChecksum = string("owner_effect_checksum"),
		linkedAtMs = long("linked_at_ms"),
	)

private fun android.database.Cursor.toStepsCountDomainReceipt() =
	StepsCountDomainReceiptEntity(
		receiptIdentity = string("receipt_identity"),
		domainIdentity = string("domain_identity"),
		ownerKind = string("owner_kind"),
		scopeIdentity = string("scope_identity"),
		ownerIdentity = string("owner_identity"),
		ownerRevision = long("owner_revision"),
		registrationGeneration = long("registration_generation"),
		collectedDataEpoch = long("collected_data_epoch"),
		authorityRevision = long("authority_revision"),
		authorityFingerprint = string("authority_fingerprint"),
		coverageKind = string("coverage_kind"),
		coverageVersion = long("coverage_version").toInt(),
		countDomainVersion = long("count_domain_version").toInt(),
		effectChecksum = string("effect_checksum"),
		completionEvidenceChecksum = nullableString("completion_evidence_checksum"),
	)

private fun android.database.Cursor.toStepsCountDomainCompletenessMarker() =
	StepsCountDomainCompletenessMarkerEntity(
		ownerKind = string("owner_kind"),
		ownerIdentity = string("owner_identity"),
		ownerRevision = long("owner_revision"),
		terminalState = string("terminal_state"),
		lastAdmissionOrdinal = nullableLong("last_admission_ordinal"),
		lastSourceSequence = nullableLong("last_source_sequence"),
		providerFlushOutcome = string("provider_flush_outcome"),
		registrationRemovalOutcome = string("registration_removal_outcome"),
		registrationTimelineChecksum = string("registration_timeline_checksum"),
		evidenceChecksum = string("evidence_checksum"),
	)

private fun android.database.Cursor.string(column: String): String =
	getString(getColumnIndexOrThrow(column))

private fun android.database.Cursor.nullableString(column: String): String? {
	val index = getColumnIndexOrThrow(column)
	return if (isNull(index)) null else getString(index)
}

private fun android.database.Cursor.long(column: String): Long =
	getLong(getColumnIndexOrThrow(column))

private fun android.database.Cursor.nullableLong(column: String): Long? {
	val index = getColumnIndexOrThrow(column)
	return if (isNull(index)) null else getLong(index)
}
