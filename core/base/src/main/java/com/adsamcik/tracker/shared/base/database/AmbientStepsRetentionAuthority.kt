package com.adsamcik.tracker.shared.base.database

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsRetentionAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsRetentionAuthorityIntegrity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.hasExactEligibleAmbientConsentReference
import com.adsamcik.tracker.shared.base.database.data.isEffectiveAtOrBefore

/**
 * Explicit native/imported Steps retention grant or revocation with original epoch, approval and
 * effective clock bounds.
 */
sealed interface AmbientStepsRetentionDecision {
	val expectedCollectedDataEpoch: Long
	val expectedRetainedFromMs: Long?
	val effectiveBootId: String
	val effectiveElapsedRealtimeNanos: Long
	val effectiveWallTimeMs: Long

	/**
	 * Grants native all-day Steps retention under exact original policy/consent and data epoch at the
	 * effective clocks.
	 */
	data class GrantLiveAmbient(
		val opaquePolicyId: String,
		override val expectedCollectedDataEpoch: Long,
		val expectedSourcePolicyRevision: Long,
		val expectedAmbientConsentEpoch: Long,
		override val effectiveBootId: String,
		override val effectiveElapsedRealtimeNanos: Long,
		override val effectiveWallTimeMs: Long,
		val expectedPreviousApprovalRevision: Long? = null,
		override val expectedRetainedFromMs: Long? = null,
	) : AmbientStepsRetentionDecision

	/**
	 * Grants imported Steps retention for the exact epoch and floor without authorizing native
	 * acquisition.
	 */
	data class GrantPortableImport(
		val opaquePolicyId: String,
		override val expectedCollectedDataEpoch: Long,
		override val effectiveBootId: String,
		override val effectiveElapsedRealtimeNanos: Long,
		override val effectiveWallTimeMs: Long,
		val expectedPreviousApprovalRevision: Long? = null,
		override val expectedRetainedFromMs: Long? = null,
	) : AmbientStepsRetentionDecision

	/**
	 * Revokes only the named Steps retention scope at the exact previous approval revision and
	 * effective clocks.
	 */
	data class Revoke(
		val scope: String,
		override val expectedCollectedDataEpoch: Long,
		override val effectiveBootId: String,
		override val effectiveElapsedRealtimeNanos: Long,
		override val effectiveWallTimeMs: Long,
		val expectedPreviousApprovalRevision: Long,
		override val expectedRetainedFromMs: Long? = null,
	) : AmbientStepsRetentionDecision
}

/**
 * Saved Steps retention scope/approval revision or a named unavailable authority result.
 */
sealed interface AmbientStepsRetentionAuthorityResult {
	/**
	 * The explicit retention decision was saved for the reported scope and approval revision.
	 */
	data class Applied(val scope: String, val approvalRevision: Long) :
		AmbientStepsRetentionAuthorityResult

	/**
	 * The requested original scope or retention authority is unavailable for the stated reason.
	 */
	data class Unavailable(val reason: AmbientStepsRetentionAuthorityUnavailableReason) :
		AmbientStepsRetentionAuthorityResult
}

/**
 * Changed epoch, floor, policy, approval or invalid integrity/effective time preventing Steps
 * retention authority.
 */
enum class AmbientStepsRetentionAuthorityUnavailableReason {
	COLLECTED_DATA_EPOCH_CHANGED,
	RETAINED_FROM_CHANGED,
	LIVE_POLICY_AUTHORITY_MISMATCH,
	NO_EXISTING_AUTHORITY,
	STALE_APPROVAL_REVISION,
	APPROVAL_REVISION_EXHAUSTED,
	INTEGRITY_MISMATCH,
	EFFECTIVE_TIME_INVALID,
}

/**
 * Applies the explicit Steps retention decision atomically after rechecking data epoch, floor,
 * policy and approval revision.
 */
suspend fun AppDatabase.applyAmbientStepsRetentionDecision(
	decision: AmbientStepsRetentionDecision,
): AmbientStepsRetentionAuthorityResult = try {
	withTransaction {
		decision.requireValid()
		val dao = ambientStepsFactRevisionDao()
		val scope = decision.scope()
		val evidence = sourceEvidenceStateDao().get()
			?: return@withTransaction unavailableStepsEpoch()
		if (evidence.collectedDataEpoch != decision.expectedCollectedDataEpoch) {
			return@withTransaction unavailableStepsEpoch()
		}
		if (evidence.retainedFromMs != decision.expectedRetainedFromMs) {
			return@withTransaction unavailableStepsRetainedFrom()
		}
		val current = dao.latestRetentionAuthority(scope)
		decision.currentAuthorityUnavailable(current)?.let { return@withTransaction it }
		if (!hasValidReferencedPolicyTime(decision)) {
			return@withTransaction unavailableStepsEffectiveTime()
		}
		val binding = retentionBinding(decision)
			?: return@withTransaction unavailableStepsLivePolicy()
		val revision = dao.maximumRetentionApprovalRevision(scope)
			.takeUnless { it == Long.MAX_VALUE }?.plus(1L)
			?: return@withTransaction unavailableStepsRevision()
		val value = decision.createStepsAuthority(scope, revision, current, binding)
		dao.insertRetentionAuthority(value)
		if (
			sourceEvidenceStateDao().incrementRevisionForExactLifecycle(
				expectedRevision = evidence.revision,
				expectedCollectedDataEpoch = decision.expectedCollectedDataEpoch,
				expectedRetainedFromMs = decision.expectedRetainedFromMs,
				updatedAtMs = decision.effectiveWallTimeMs,
			) != 1
		) {
			throw AmbientStepsRetentionLifecycleChangedDuringApply
		}
		AmbientStepsRetentionAuthorityResult.Applied(scope, revision)
	}
} catch (_: AmbientStepsRetentionLifecycleChangedDuringApply) {
	unavailableStepsEpoch()
}

private fun AmbientStepsRetentionDecision.currentAuthorityUnavailable(
	current: AmbientStepsRetentionAuthorityEntity?,
): AmbientStepsRetentionAuthorityResult.Unavailable? = when {
	current != null && !AmbientStepsRetentionAuthorityIntegrity.isAuthentic(current) -> unavailableStepsIntegrity()
	this is AmbientStepsRetentionDecision.Revoke && current == null -> unavailableStepsMissing()
	current?.approvalRevision != expectedPreviousApprovalRevision() -> unavailableStepsStaleRevision()
	!isAfterRetainedAuthority(current) -> unavailableStepsEffectiveTime()
	else -> null
}

private fun AmbientStepsRetentionDecision.createStepsAuthority(
	scope: String,
	revision: Long,
	current: AmbientStepsRetentionAuthorityEntity?,
	binding: Pair<Long?, Long?>,
): AmbientStepsRetentionAuthorityEntity {
	return AmbientStepsRetentionAuthorityIntegrity.create(
		scope = scope,
		approvalRevision = revision,
		state = if (this is AmbientStepsRetentionDecision.Revoke) {
			AmbientStepsRetentionAuthorityEntity.STATE_REVOKED
		} else {
			AmbientStepsRetentionAuthorityEntity.STATE_ACTIVE
		},
		opaquePolicyId = when (this) {
			is AmbientStepsRetentionDecision.GrantLiveAmbient -> opaquePolicyId
			is AmbientStepsRetentionDecision.GrantPortableImport -> opaquePolicyId
			is AmbientStepsRetentionDecision.Revoke -> requireNotNull(current).opaquePolicyId
		},
		sourcePolicyRevision = binding.first,
		ambientConsentEpoch = binding.second,
		collectedDataEpoch = expectedCollectedDataEpoch,
		retainedFromMs = expectedRetainedFromMs,
		effectiveBootId = effectiveBootId,
		effectiveElapsedRealtimeNanos = effectiveElapsedRealtimeNanos,
		effectiveWallTimeMs = effectiveWallTimeMs,
	)
}

private suspend fun AppDatabase.retentionBinding(
	decision: AmbientStepsRetentionDecision,
): Pair<Long?, Long?>? = when (decision) {
	is AmbientStepsRetentionDecision.GrantPortableImport -> null to null
	is AmbientStepsRetentionDecision.GrantLiveAmbient -> liveRetentionBinding(decision)
	is AmbientStepsRetentionDecision.Revoke -> when (decision.scope) {
		AmbientStepsRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT -> {
			val current = ambientStepsFactRevisionDao().latestRetentionAuthority(decision.scope)
			current?.sourcePolicyRevision to current?.ambientConsentEpoch
		}
		AmbientStepsRetentionAuthorityEntity.SCOPE_PORTABLE_IMPORT -> null to null
		else -> null
	}
}

private suspend fun AppDatabase.liveRetentionBinding(
	decision: AmbientStepsRetentionDecision.GrantLiveAmbient,
): Pair<Long?, Long?>? {
	val authority = sourcePolicyDao().authority()
	val policy = authority?.takeIf {
		it.bootstrapState == SourcePolicyAuthorityEntity.STATE_ACTIVE &&
			it.currentPolicyRevision == decision.expectedSourcePolicyRevision
	}?.let {
		sourcePolicyDao().policyAtRevision(
			decision.expectedSourcePolicyRevision,
			SourceDestinationOwnerEntity.SOURCE_STEPS,
		)
	}
	val consent = sourcePolicyDao().latestConsentEpoch(
		SourceDestinationOwnerEntity.SOURCE_STEPS,
		SourceBrokerPurpose.AMBIENT_PRODUCT,
	)
	return if (policy == null ||
		policy.ambientConsentEpoch != decision.expectedAmbientConsentEpoch ||
		!policy.hasExactEligibleAmbientConsentReference(consent)
	) {
		null
	} else {
		decision.expectedSourcePolicyRevision to decision.expectedAmbientConsentEpoch
	}
}

private suspend fun AppDatabase.hasValidReferencedPolicyTime(
	decision: AmbientStepsRetentionDecision,
): Boolean {
	if (decision !is AmbientStepsRetentionDecision.GrantLiveAmbient) {
		return true
	}
	val policy = sourcePolicyDao().policyAtRevision(
		decision.expectedSourcePolicyRevision,
		SourceDestinationOwnerEntity.SOURCE_STEPS,
	) ?: return true
	val consent = sourcePolicyDao().latestConsentEpoch(
		SourceDestinationOwnerEntity.SOURCE_STEPS,
		SourceBrokerPurpose.AMBIENT_PRODUCT,
	) ?: return true
	return if (!policy.hasExactEligibleAmbientConsentReference(consent)) {
		true
	} else {
		policy.isEffectiveAtOrBefore(
			decision.effectiveBootId,
			decision.effectiveElapsedRealtimeNanos,
			decision.effectiveWallTimeMs,
		) && consent.isEffectiveAtOrBefore(
			decision.effectiveBootId,
			decision.effectiveElapsedRealtimeNanos,
			decision.effectiveWallTimeMs,
		)
	}
}

private fun AmbientStepsRetentionDecision.scope(): String = when (this) {
	is AmbientStepsRetentionDecision.GrantLiveAmbient ->
		AmbientStepsRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT
	is AmbientStepsRetentionDecision.GrantPortableImport ->
		AmbientStepsRetentionAuthorityEntity.SCOPE_PORTABLE_IMPORT
	is AmbientStepsRetentionDecision.Revoke -> scope
}.also { scope ->
	require(
		scope == AmbientStepsRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT ||
			scope == AmbientStepsRetentionAuthorityEntity.SCOPE_PORTABLE_IMPORT,
	)
}

@Suppress("CyclomaticComplexMethod") // Keep this fixed field or enum projection together.
private fun AmbientStepsRetentionDecision.requireValid() {
	require(expectedCollectedDataEpoch >= 0L)
	val retainedFromMs = expectedRetainedFromMs
	require(retainedFromMs == null || retainedFromMs >= 0L)
	require(effectiveBootId.isNotBlank())
	require(effectiveElapsedRealtimeNanos >= 0L && effectiveWallTimeMs >= 0L)
	when (this) {
		is AmbientStepsRetentionDecision.GrantLiveAmbient -> {
			require(opaquePolicyId.isNotBlank() && opaquePolicyId.length <= MAX_POLICY_ID_LENGTH)
			require(expectedSourcePolicyRevision > 0L && expectedAmbientConsentEpoch >= 0L)
			require(expectedPreviousApprovalRevision?.let { it > 0L } != false)
		}
		is AmbientStepsRetentionDecision.GrantPortableImport -> {
			require(opaquePolicyId.isNotBlank() && opaquePolicyId.length <= MAX_POLICY_ID_LENGTH)
			require(expectedPreviousApprovalRevision?.let { it > 0L } != false)
		}
		is AmbientStepsRetentionDecision.Revoke -> {
			require(scope.isNotBlank())
			require(expectedPreviousApprovalRevision > 0L)
		}
	}
}

private fun AmbientStepsRetentionDecision.expectedPreviousApprovalRevision(): Long? = when (this) {
	is AmbientStepsRetentionDecision.GrantLiveAmbient -> expectedPreviousApprovalRevision
	is AmbientStepsRetentionDecision.GrantPortableImport -> expectedPreviousApprovalRevision
	is AmbientStepsRetentionDecision.Revoke -> expectedPreviousApprovalRevision
}

private fun unavailableStepsEpoch() = AmbientStepsRetentionAuthorityResult.Unavailable(
	AmbientStepsRetentionAuthorityUnavailableReason.COLLECTED_DATA_EPOCH_CHANGED,
)

private fun unavailableStepsRetainedFrom() = AmbientStepsRetentionAuthorityResult.Unavailable(
	AmbientStepsRetentionAuthorityUnavailableReason.RETAINED_FROM_CHANGED,
)

private fun unavailableStepsLivePolicy() = AmbientStepsRetentionAuthorityResult.Unavailable(
	AmbientStepsRetentionAuthorityUnavailableReason.LIVE_POLICY_AUTHORITY_MISMATCH,
)

private fun unavailableStepsMissing() = AmbientStepsRetentionAuthorityResult.Unavailable(
	AmbientStepsRetentionAuthorityUnavailableReason.NO_EXISTING_AUTHORITY,
)

private fun unavailableStepsStaleRevision() = AmbientStepsRetentionAuthorityResult.Unavailable(
	AmbientStepsRetentionAuthorityUnavailableReason.STALE_APPROVAL_REVISION,
)

private fun unavailableStepsRevision() = AmbientStepsRetentionAuthorityResult.Unavailable(
	AmbientStepsRetentionAuthorityUnavailableReason.APPROVAL_REVISION_EXHAUSTED,
)

private fun unavailableStepsIntegrity() = AmbientStepsRetentionAuthorityResult.Unavailable(
	AmbientStepsRetentionAuthorityUnavailableReason.INTEGRITY_MISMATCH,
)

private fun unavailableStepsEffectiveTime() = AmbientStepsRetentionAuthorityResult.Unavailable(
	AmbientStepsRetentionAuthorityUnavailableReason.EFFECTIVE_TIME_INVALID,
)

private object AmbientStepsRetentionLifecycleChangedDuringApply : IllegalStateException()

private const val MAX_POLICY_ID_LENGTH = 256

private fun AmbientStepsRetentionDecision.isAfterRetainedAuthority(
	current: AmbientStepsRetentionAuthorityEntity?,
): Boolean = current == null ||
	effectiveWallTimeMs >= current.effectiveWallTimeMs &&
		(effectiveBootId != current.effectiveBootId ||
			effectiveElapsedRealtimeNanos > current.effectiveElapsedRealtimeNanos)
