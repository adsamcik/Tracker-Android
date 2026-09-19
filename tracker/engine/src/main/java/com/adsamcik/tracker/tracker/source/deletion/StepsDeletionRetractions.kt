package com.adsamcik.tracker.tracker.source.deletion

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.StepsCountDomainStore
import com.adsamcik.tracker.shared.base.database.StepsCountDomainMaintenanceResult
import com.adsamcik.tracker.shared.base.database.StepsCountDomainOwnerLookupKey
import com.adsamcik.tracker.shared.base.database.StepsCountDomainWriteResult
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionIntegrity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainOwnerRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainReceiptIntegrity

/**
 * Installs one payload-free latest-state retraction for an authenticated Steps fact.
 *
 * Both native and portable selected deletion use the same representation so restore, correction,
 * and product readers see one monotonic redaction contract. False means the caller must roll back
 * because the requested semantic revision was exhausted or another mutation won the exact key.
 */
internal suspend fun AppDatabase.insertStepsDeletionRetractionOrVerify(
	fact: StepFactRevisionEntity,
	fence: SourceDeletionFenceEntity,
	countDomainOwners: MutableCollection<StepsCountDomainOwnerLookupKey>,
): Boolean {
	val nextRevision = try {
		Math.addExact(fact.semanticRevision, 1L)
	} catch (_: ArithmeticException) {
		return false
	}
	val mutationId = StepFactRevisionIntegrity.localDeleteMutationId(
		scopeIdentityDigest = fence.scopeIdentityDigest,
		logicalFactId = fact.logicalFactId,
		semanticRevision = nextRevision,
		scopeDeletionGeneration = fence.fenceGeneration,
	)
	val retraction = buildStepsDeletionRetraction(fact, fence, nextRevision, mutationId)
	val stored = if (stepFactRevisionDao().insert(retraction) != INSERT_IGNORED) {
		true
	} else {
		stepFactRevisionDao().revision(
			retraction.writerProjectionId,
			retraction.writerProjectionVersion,
			retraction.logicalFactId,
			retraction.semanticRevision,
		) == retraction
	}
	if (!stored) return false
	if (fact.originKind != StepFactRevisionEntity.ORIGIN_LIVE_WAL) return true
	val store = StepsCountDomainStore(this)
	when (store.recordSessionFact(fact)) {
		StepsCountDomainWriteResult.SCHEMA_UNAVAILABLE,
		StepsCountDomainWriteResult.UNPROVEN,
		-> return true
		StepsCountDomainWriteResult.INSERTED,
		StepsCountDomainWriteResult.EXACT_REPLAY,
		-> Unit
		StepsCountDomainWriteResult.NOT_APPLICABLE,
		StepsCountDomainWriteResult.AUTHORITY_PENDING,
		StepsCountDomainWriteResult.STORED_EVIDENCE_UNVERIFIABLE,
		StepsCountDomainWriteResult.IDENTITY_CONFLICT,
		StepsCountDomainWriteResult.REVISION_GAP,
		StepsCountDomainWriteResult.TERMINAL_OWNER,
		-> return false
	}
	val retractionResult = store.recordSessionFactRetraction(
		retraction = retraction,
		logicalTrackingId = requireNotNull(fact.logicalTrackingId),
		serviceRunId = requireNotNull(fact.serviceRunId),
	)
	return when (retractionResult) {
		StepsCountDomainWriteResult.INSERTED,
		StepsCountDomainWriteResult.EXACT_REPLAY,
		StepsCountDomainWriteResult.SCHEMA_UNAVAILABLE,
		StepsCountDomainWriteResult.NOT_APPLICABLE,
		-> {
			if (retractionResult != StepsCountDomainWriteResult.SCHEMA_UNAVAILABLE) {
				countDomainOwners +=
					StepsCountDomainOwnerLookupKey(
						ownerKind =
							StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_FACT,
						ownerIdentity =
							StepsCountDomainReceiptIntegrity.sessionFactOwnerIdentity(
								fact.writerProjectionId,
								fact.writerProjectionVersion,
								fact.logicalFactId,
							),
						ownerRevision = fact.semanticRevision,
					)
			}
			true
		}
		StepsCountDomainWriteResult.UNPROVEN,
		StepsCountDomainWriteResult.AUTHORITY_PENDING,
		StepsCountDomainWriteResult.STORED_EVIDENCE_UNVERIFIABLE,
		StepsCountDomainWriteResult.IDENTITY_CONFLICT,
		StepsCountDomainWriteResult.REVISION_GAP,
		StepsCountDomainWriteResult.TERMINAL_OWNER,
		-> false
	}
}

internal suspend fun AppDatabase.removeStepsDeletionCountDomainOwners(
	keys: Collection<StepsCountDomainOwnerLookupKey>,
) {
	if (keys.isEmpty()) return
	StepsCountDomainStore(this).withOwnerMaintenance { maintenance ->
		keys.distinct().chunked(STEPS_DELETION_COUNT_DOMAIN_BATCH_SIZE).forEach { batch ->
			check(
				maintenance.removeOwners(batch).let { result ->
					result is StepsCountDomainMaintenanceResult.Applied ||
						result == StepsCountDomainMaintenanceResult.SchemaUnavailable
				},
			) { "Steps deletion count-domain evidence could not be removed" }
		}
	}
}

@Suppress("LongMethod") // Explicit redaction keeps every cleared payload field reviewable.
private fun buildStepsDeletionRetraction(
	fact: StepFactRevisionEntity,
	fence: SourceDeletionFenceEntity,
	nextRevision: Long,
	mutationId: String,
): StepFactRevisionEntity {
	val unsigned = StepFactRevisionEntity(
		logicalFactId = fact.logicalFactId,
		semanticRevision = nextRevision,
		mutationId = mutationId,
		stepIntervalId = null,
		sourceEventId = null,
		sourceAdmissionOrdinal = null,
		originKind = StepFactRevisionEntity.ORIGIN_LOCAL_DELETE,
		originIdentity = fence.scopeIdentityDigest,
		writerProjectionId = fact.writerProjectionId,
		writerProjectionVersion = fact.writerProjectionVersion,
		writerBindingGeneration = fact.writerBindingGeneration,
		operation = StepFactRevisionEntity.OPERATION_RETRACT,
		intervalStartTimeMs = null,
		intervalEndTimeMs = null,
		intervalStartElapsedRealtimeNanos = null,
		intervalEndElapsedRealtimeNanos = null,
		clockDomainId = null,
		bootClockDomainId = null,
		cumulativeStepCountStart = null,
		cumulativeStepCountEnd = null,
		wallTimeUncertaintyMs = null,
		coverageKind = null,
		effectiveStepCount = null,
		logicalTrackingId = null,
		serviceRunId = null,
		purpose = StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
		manifestRevision = null,
		sourcePolicyRevision = null,
		captureConsentEpoch = null,
		collectedDataEpoch = fence.collectedDataEpoch,
		scopeDeletionGeneration = fence.fenceGeneration,
		effectChecksum = "pending-local-delete-effect",
		appliedAtMs = fence.deletedAtMs,
	)
	return unsigned.copy(
		effectChecksum = StepFactRevisionIntegrity.localDeleteEffectChecksum(unsigned),
	)
}

private const val STEPS_DELETION_COUNT_DOMAIN_BATCH_SIZE = 400

private const val INSERT_IGNORED = -1L
