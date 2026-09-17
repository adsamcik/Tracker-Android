package com.adsamcik.tracker.shared.base.database

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsRetentionAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsRetentionAuthorityIntegrity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.hasExactEligibleAmbientConsentReference
import com.adsamcik.tracker.shared.base.database.data.isEffectiveAtOrBefore

sealed interface AmbientStepsRetentionDecision {
	val expectedCollectedDataEpoch: Long
	val effectiveBootId: String
	val effectiveElapsedRealtimeNanos: Long
	val effectiveWallTimeMs: Long

	data class GrantLiveAmbient(
		val opaquePolicyId: String,
		override val expectedCollectedDataEpoch: Long,
		val expectedSourcePolicyRevision: Long,
		val expectedAmbientConsentEpoch: Long,
		override val effectiveBootId: String,
		override val effectiveElapsedRealtimeNanos: Long,
		override val effectiveWallTimeMs: Long,
		val expectedPreviousApprovalRevision: Long? = null,
	) : AmbientStepsRetentionDecision

	data class GrantPortableImport(
		val opaquePolicyId: String,
		override val expectedCollectedDataEpoch: Long,
		override val effectiveBootId: String,
		override val effectiveElapsedRealtimeNanos: Long,
		override val effectiveWallTimeMs: Long,
		val expectedPreviousApprovalRevision: Long? = null,
	) : AmbientStepsRetentionDecision

	data class Revoke(
		val scope: String,
		override val expectedCollectedDataEpoch: Long,
		override val effectiveBootId: String,
		override val effectiveElapsedRealtimeNanos: Long,
		override val effectiveWallTimeMs: Long,
		val expectedPreviousApprovalRevision: Long,
	) : AmbientStepsRetentionDecision
}

sealed interface AmbientStepsRetentionAuthorityResult {
	data class Applied(val scope: String, val approvalRevision: Long) :
		AmbientStepsRetentionAuthorityResult

	data class Unavailable(val reason: AmbientStepsRetentionAuthorityUnavailableReason) :
		AmbientStepsRetentionAuthorityResult
}

enum class AmbientStepsRetentionAuthorityUnavailableReason {
	COLLECTED_DATA_EPOCH_CHANGED,
	LIVE_POLICY_AUTHORITY_MISMATCH,
	NO_EXISTING_AUTHORITY,
	STALE_APPROVAL_REVISION,
	APPROVAL_REVISION_EXHAUSTED,
	INTEGRITY_MISMATCH,
	EFFECTIVE_TIME_INVALID,
}

suspend fun AppDatabase.applyAmbientStepsRetentionDecision(
	decision: AmbientStepsRetentionDecision,
): AmbientStepsRetentionAuthorityResult = withTransaction {
	decision.requireValid()
	val dao = ambientStepsFactRevisionDao()
	val scope = decision.scope()
	if (sourceEvidenceStateDao().get()?.collectedDataEpoch != decision.expectedCollectedDataEpoch) {
		return@withTransaction unavailableStepsEpoch()
	}
	val current = dao.latestRetentionAuthority(scope)
	if (current != null && !AmbientStepsRetentionAuthorityIntegrity.isAuthentic(current)) {
		return@withTransaction unavailableStepsIntegrity()
	}
	if (decision is AmbientStepsRetentionDecision.Revoke && current == null) {
		return@withTransaction unavailableStepsMissing()
	}
	if (current?.approvalRevision != decision.expectedPreviousApprovalRevision()) {
		return@withTransaction unavailableStepsStaleRevision()
	}
	if (!decision.hasValidEffectiveTime(current)) {
		return@withTransaction unavailableStepsEffectiveTime()
	}
	if (!hasValidReferencedPolicyTime(decision)) {
		return@withTransaction unavailableStepsEffectiveTime()
	}
	val binding = retentionBinding(decision) ?: return@withTransaction unavailableStepsLivePolicy()
	val revision = dao.maximumRetentionApprovalRevision(scope)
		.takeUnless { it == Long.MAX_VALUE }?.plus(1L)
		?: return@withTransaction unavailableStepsRevision()
	val value = AmbientStepsRetentionAuthorityIntegrity.create(
		scope = scope,
		approvalRevision = revision,
		state = if (decision is AmbientStepsRetentionDecision.Revoke) {
			AmbientStepsRetentionAuthorityEntity.STATE_REVOKED
		} else {
			AmbientStepsRetentionAuthorityEntity.STATE_ACTIVE
		},
		opaquePolicyId = when (decision) {
			is AmbientStepsRetentionDecision.GrantLiveAmbient -> decision.opaquePolicyId
			is AmbientStepsRetentionDecision.GrantPortableImport -> decision.opaquePolicyId
			is AmbientStepsRetentionDecision.Revoke -> requireNotNull(current).opaquePolicyId
		},
		sourcePolicyRevision = binding.first,
		ambientConsentEpoch = binding.second,
		collectedDataEpoch = decision.expectedCollectedDataEpoch,
		effectiveBootId = decision.effectiveBootId,
		effectiveElapsedRealtimeNanos = decision.effectiveElapsedRealtimeNanos,
		effectiveWallTimeMs = decision.effectiveWallTimeMs,
	)
	dao.insertRetentionAuthority(value)
	check(sourceEvidenceStateDao().incrementRevision(decision.effectiveWallTimeMs) == 1)
	AmbientStepsRetentionAuthorityResult.Applied(scope, revision)
}

private suspend fun AppDatabase.retentionBinding(
	decision: AmbientStepsRetentionDecision,
): Pair<Long?, Long?>? = when (decision) {
	is AmbientStepsRetentionDecision.GrantPortableImport -> null to null
	is AmbientStepsRetentionDecision.GrantLiveAmbient -> {
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
		if (policy == null ||
			policy.ambientConsentEpoch != decision.expectedAmbientConsentEpoch ||
			!policy.hasExactEligibleAmbientConsentReference(consent)
		) null else decision.expectedSourcePolicyRevision to decision.expectedAmbientConsentEpoch
	}
	is AmbientStepsRetentionDecision.Revoke -> when (decision.scope) {
		AmbientStepsRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT -> {
			val current = ambientStepsFactRevisionDao().latestRetentionAuthority(decision.scope)
			current?.sourcePolicyRevision to current?.ambientConsentEpoch
		}
		AmbientStepsRetentionAuthorityEntity.SCOPE_PORTABLE_IMPORT -> null to null
		else -> null
	}
}

private suspend fun AppDatabase.hasValidReferencedPolicyTime(
	decision: AmbientStepsRetentionDecision,
): Boolean {
	if (decision !is AmbientStepsRetentionDecision.GrantLiveAmbient) return true
	val policy = sourcePolicyDao().policyAtRevision(
		decision.expectedSourcePolicyRevision,
		SourceDestinationOwnerEntity.SOURCE_STEPS,
	) ?: return true
	val consent = sourcePolicyDao().latestConsentEpoch(
		SourceDestinationOwnerEntity.SOURCE_STEPS,
		SourceBrokerPurpose.AMBIENT_PRODUCT,
	) ?: return true
	if (!policy.hasExactEligibleAmbientConsentReference(consent)) return true
	return policy.isEffectiveAtOrBefore(
		decision.effectiveBootId,
		decision.effectiveElapsedRealtimeNanos,
		decision.effectiveWallTimeMs,
	) && consent.isEffectiveAtOrBefore(
		decision.effectiveBootId,
		decision.effectiveElapsedRealtimeNanos,
		decision.effectiveWallTimeMs,
	)
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

private fun AmbientStepsRetentionDecision.requireValid() {
	require(expectedCollectedDataEpoch >= 0L)
	require(effectiveBootId.isNotBlank())
	require(effectiveElapsedRealtimeNanos >= 0L && effectiveWallTimeMs >= 0L)
	when (this) {
		is AmbientStepsRetentionDecision.GrantLiveAmbient -> {
			require(opaquePolicyId.isNotBlank() && opaquePolicyId.length <= 256)
			require(expectedSourcePolicyRevision > 0L && expectedAmbientConsentEpoch >= 0L)
			require(expectedPreviousApprovalRevision?.let { it > 0L } != false)
		}
		is AmbientStepsRetentionDecision.GrantPortableImport -> {
			require(opaquePolicyId.isNotBlank() && opaquePolicyId.length <= 256)
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

private fun AmbientStepsRetentionDecision.hasValidEffectiveTime(
	current: AmbientStepsRetentionAuthorityEntity?,
): Boolean = current == null ||
	effectiveWallTimeMs >= current.effectiveWallTimeMs &&
		(effectiveBootId != current.effectiveBootId ||
			effectiveElapsedRealtimeNanos > current.effectiveElapsedRealtimeNanos)

private fun unavailableStepsEpoch() = AmbientStepsRetentionAuthorityResult.Unavailable(
	AmbientStepsRetentionAuthorityUnavailableReason.COLLECTED_DATA_EPOCH_CHANGED,
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
