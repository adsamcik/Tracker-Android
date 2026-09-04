package com.adsamcik.tracker.shared.base.database.data

import java.security.MessageDigest

/** Verifies the complete retained representation of a canonical LIVE_WAL Steps fact. */
object StepFactRevisionIntegrity {
	/** Verifies immutable policy and append-only consent authority for one Steps capture binding. */
	@Suppress("ComplexCondition", "CyclomaticComplexMethod")
	fun hasValidStepsCaptureAuthority(
		policy: SourcePolicyEntity?,
		consent: SourceConsentEpochEntity?,
		manifestPolicyRevision: Long,
		binding: SessionManifestSourceEntity,
	): Boolean = policy != null &&
		policy.policyRevision == manifestPolicyRevision &&
		binding.sourceKind == SourceDestinationOwnerEntity.SOURCE_STEPS &&
		binding.purpose == SessionManifestPurposeCode.SESSION_CAPTURE &&
		binding.persistenceEligible &&
		policy.sourceKind == SourceDestinationOwnerEntity.SOURCE_STEPS &&
		policy.enabled && policy.capturePersistenceEligible &&
		policy.captureConsentEpoch == binding.consentEpoch && policy.qosCode == binding.qosCode &&
		binding.qosCode in MIN_CAPTURE_QOS_CODE..MAX_CAPTURE_QOS_CODE && consent != null &&
		consent.sourceKind == SourceDestinationOwnerEntity.SOURCE_STEPS &&
		consent.purpose == SessionManifestPurposeCode.SESSION_CAPTURE &&
		consent.epoch == binding.consentEpoch && consent.eligible && consent.persistenceEligible &&
		consent.policyRevision <= manifestPolicyRevision

	/** Computes the versioned digest over every retained LIVE_WAL fact field except the digest itself. */
	fun liveWalEffectChecksum(fact: StepFactRevisionEntity): String {
		require(fact.originKind == StepFactRevisionEntity.ORIGIN_LIVE_WAL)
		require(fact.operation == StepFactRevisionEntity.OPERATION_UPSERT)
		return digest(
			LIVE_WAL_RETAINED_EFFECT_VERSION,
			fact.logicalFactId,
			fact.semanticRevision,
			fact.mutationId,
			fact.stepIntervalId,
			fact.sourceEventId,
			fact.sourceAdmissionOrdinal,
			fact.originKind,
			fact.originIdentity,
			fact.writerProjectionId,
			fact.writerProjectionVersion,
			fact.writerBindingGeneration,
			fact.operation,
			fact.intervalStartTimeMs,
			fact.intervalEndTimeMs,
			fact.intervalStartElapsedRealtimeNanos,
			fact.intervalEndElapsedRealtimeNanos,
			fact.clockDomainId,
			fact.bootClockDomainId,
			fact.cumulativeStepCountStart,
			fact.cumulativeStepCountEnd,
			fact.wallTimeUncertaintyMs,
			fact.coverageKind,
			fact.effectiveStepCount,
			fact.logicalTrackingId,
			fact.serviceRunId,
			fact.purpose,
			fact.manifestRevision,
			fact.sourcePolicyRevision,
			fact.captureConsentEpoch,
			fact.collectedDataEpoch,
			fact.scopeDeletionGeneration,
			fact.appliedAtMs,
		)
	}

	/** Verifies retained LIVE_WAL UPSERT bytes; source-writer semantics require the stricter check. */
	fun hasValidLiveWalEffectChecksum(fact: StepFactRevisionEntity): Boolean {
		if (fact.originKind != StepFactRevisionEntity.ORIGIN_LIVE_WAL ||
			fact.operation != StepFactRevisionEntity.OPERATION_UPSERT
		) {
			return false
		}
		return fact.effectChecksum == liveWalEffectChecksum(fact)
	}

	/**
	 * Verifies the complete source-local shape emitted by the sole canonical LIVE_WAL Steps writer.
	 *
	 * Run, manifest, consent, lane, deletion-fence, and retained-epoch authority remain contextual
	 * reader checks. This method authenticates only the intrinsic fact semantics shared by every
	 * reader so a checksum-valid row cannot invent a writer shape the producer never emitted.
	 */
	@Suppress("ComplexCondition", "CyclomaticComplexMethod", "ReturnCount")
	fun hasValidCanonicalLiveWalFact(fact: StepFactRevisionEntity): Boolean {
		if (!hasValidLiveWalEffectChecksum(fact)) {
			return false
		}
		val sourceEventId = fact.sourceEventId ?: return false
		val startMs = fact.intervalStartTimeMs ?: return false
		val endMs = fact.intervalEndTimeMs ?: return false
		val startElapsed = fact.intervalStartElapsedRealtimeNanos ?: return false
		val endElapsed = fact.intervalEndElapsedRealtimeNanos ?: return false
		val cumulativeStart = fact.cumulativeStepCountStart ?: return false
		val cumulativeEnd = fact.cumulativeStepCountEnd ?: return false
		val effective = fact.effectiveStepCount ?: return false
		val expectedLogicalFactId =
			"${SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID}:$sourceEventId"
		val expectedMutationId =
			"$expectedLogicalFactId:$LIVE_WAL_SEMANTIC_REVISION:" +
				StepFactRevisionEntity.OPERATION_UPSERT
		if (fact.semanticRevision != LIVE_WAL_SEMANTIC_REVISION ||
			fact.writerProjectionId != SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID ||
			fact.writerProjectionVersion != SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_VERSION ||
			fact.logicalFactId != expectedLogicalFactId || fact.mutationId != expectedMutationId ||
			fact.stepIntervalId != null || fact.originIdentity != sourceEventId ||
			fact.clockDomainId != fact.bootClockDomainId || startMs > endMs ||
			startElapsed > endElapsed || fact.appliedAtMs != endMs
		) {
			return false
		}
		val durationMs = (endElapsed - startElapsed) / NANOS_PER_MILLISECOND
		val expectedStartMs = if (durationMs > endMs) {
			0L
		} else {
			endMs - durationMs
		}
		if (startMs != expectedStartMs) {
			return false
		}
		return when (fact.coverageKind) {
			StepFactRevisionEntity.COVERAGE_BASELINE ->
				effective == 0L && cumulativeStart == cumulativeEnd && startElapsed == endElapsed
			StepFactRevisionEntity.COVERAGE_COVERED ->
				startElapsed < endElapsed && cumulativeEnd >= cumulativeStart &&
					effective == cumulativeEnd - cumulativeStart
			StepFactRevisionEntity.COVERAGE_RESET_GAP ->
				startElapsed < endElapsed && effective == 0L && cumulativeEnd < cumulativeStart
			StepFactRevisionEntity.COVERAGE_PARTIAL -> effective == 0L
			else -> false
		}
	}

	/**
	 * Verifies the elapsed-time and counter chain emitted by one canonical writer in one service run.
	 *
	 * Admission order is the source-local authority. Wall time can legitimately move backwards and is
	 * deliberately not used here. A BASELINE can reopen the chain after an authorization gap, and the
	 * first retained fact remains valid after prefix retention. Every other retained successor must
	 * start exactly at the prior elapsed endpoint and continue its cumulative counter.
	 */
	@Suppress("ComplexCondition", "CyclomaticComplexMethod", "ReturnCount")
	fun hasValidCanonicalLiveWalRunTimeline(
		facts: Collection<StepFactRevisionEntity>,
		logicalTrackingId: String,
		serviceRunId: String,
	): Boolean {
		if (logicalTrackingId.isBlank() || serviceRunId.isBlank()) {
			return false
		}
		val ordered = facts.sortedBy { fact -> fact.sourceAdmissionOrdinal }
		if (ordered.any { fact ->
				!hasValidCanonicalLiveWalFact(fact) ||
					fact.logicalTrackingId != logicalTrackingId ||
					fact.serviceRunId != serviceRunId ||
					fact.purpose != SessionManifestPurposeCode.SESSION_CAPTURE
			} || ordered.mapNotNull(StepFactRevisionEntity::sourceAdmissionOrdinal).distinct().size !=
			ordered.size || ordered.map(StepFactRevisionEntity::writerBindingGeneration).distinct().size > 1
		) {
			return false
		}
		return ordered.zipWithNext().all { (previous, current) ->
			val previousEnd = requireNotNull(previous.intervalEndElapsedRealtimeNanos)
			val currentStart = requireNotNull(current.intervalStartElapsedRealtimeNanos)
			if (current.coverageKind == StepFactRevisionEntity.COVERAGE_BASELINE) {
				currentStart >= previousEnd
			} else {
				currentStart == previousEnd &&
					current.cumulativeStepCountStart == previous.cumulativeStepCountEnd
			}
		}
	}

	/** Deterministic mutation identity shared by the local-delete writer and every verifier. */
	fun localDeleteMutationId(
		scopeIdentityDigest: String,
		logicalFactId: String,
		semanticRevision: Long,
		scopeDeletionGeneration: Long,
	): String = "local-delete:${localDeleteMutationDigest(
		scopeIdentityDigest = scopeIdentityDigest,
		logicalFactId = logicalFactId,
		semanticRevision = semanticRevision,
		scopeDeletionGeneration = scopeDeletionGeneration,
	)}"

	/** Computes the production local-delete effect digest without trusting its stored mutation id. */
	fun localDeleteEffectChecksum(retraction: StepFactRevisionEntity): String {
		require(retraction.operation == StepFactRevisionEntity.OPERATION_RETRACT)
		require(retraction.originKind == StepFactRevisionEntity.ORIGIN_LOCAL_DELETE)
		val mutationDigest = localDeleteMutationDigest(
			scopeIdentityDigest = retraction.originIdentity,
			logicalFactId = retraction.logicalFactId,
			semanticRevision = retraction.semanticRevision,
			scopeDeletionGeneration = retraction.scopeDeletionGeneration,
		)
		return digest(
			LOCAL_DELETE_EFFECT_VERSION,
			retraction.writerProjectionId,
			retraction.writerProjectionVersion,
			retraction.logicalFactId,
			retraction.semanticRevision,
			mutationDigest,
			retraction.originIdentity,
			retraction.scopeDeletionGeneration,
			retraction.collectedDataEpoch,
			retraction.appliedAtMs,
		)
	}

	/** Verifies deterministic deletion authorship and its exact expected source/run scope linkage. */
	fun hasValidLocalDeleteEffectChecksum(
		retraction: StepFactRevisionEntity,
		expectedScopeIdentityDigest: String,
	): Boolean {
		if (retraction.operation != StepFactRevisionEntity.OPERATION_RETRACT ||
			retraction.originKind != StepFactRevisionEntity.ORIGIN_LOCAL_DELETE ||
			retraction.originIdentity != expectedScopeIdentityDigest
		) {
			return false
		}
		val expectedMutationId = localDeleteMutationId(
			scopeIdentityDigest = expectedScopeIdentityDigest,
			logicalFactId = retraction.logicalFactId,
			semanticRevision = retraction.semanticRevision,
			scopeDeletionGeneration = retraction.scopeDeletionGeneration,
		)
		return retraction.mutationId == expectedMutationId &&
			retraction.effectChecksum == localDeleteEffectChecksum(retraction)
	}

	private fun localDeleteMutationDigest(
		scopeIdentityDigest: String,
		logicalFactId: String,
		semanticRevision: Long,
		scopeDeletionGeneration: Long,
	): String = digest(
		LOCAL_DELETE_MUTATION_VERSION,
		scopeIdentityDigest,
		logicalFactId,
		semanticRevision,
		scopeDeletionGeneration,
	)

	private fun digest(vararg values: Any?): String {
		val canonical = values.joinToString(separator = "") { value ->
			val text = value?.toString()
			when (text) {
				null -> "-1:"
				else -> "${text.length}:$text"
			}
		}
		return MessageDigest.getInstance("SHA-256")
			.digest(canonical.toByteArray(Charsets.UTF_8))
			.joinToString(separator = "") { byte -> "%02x".format(byte) }
	}

	private const val LIVE_WAL_RETAINED_EFFECT_VERSION = "steps-live-wal-retained-effect-v1"
	private const val MIN_CAPTURE_QOS_CODE = 1
	private const val MAX_CAPTURE_QOS_CODE = 3
	private const val LIVE_WAL_SEMANTIC_REVISION = 1L
	private const val NANOS_PER_MILLISECOND = 1_000_000L
	private const val LOCAL_DELETE_MUTATION_VERSION = "steps-local-delete-mutation-v1"
	private const val LOCAL_DELETE_EFFECT_VERSION = "steps-local-delete-effect-v1"
}
