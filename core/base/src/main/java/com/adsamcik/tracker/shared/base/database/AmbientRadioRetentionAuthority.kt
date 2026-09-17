package com.adsamcik.tracker.shared.base.database

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.data.AmbientCellRetentionAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellRetentionAuthorityIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiRetentionAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiRetentionAuthorityIntegrity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.hasExactEligibleAmbientConsentReference
import com.adsamcik.tracker.shared.base.database.data.isEffectiveAtOrBefore

sealed interface AmbientRadioRetentionDecision {
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
	) : AmbientRadioRetentionDecision

	data class GrantPortableImport(
		val opaquePolicyId: String,
		override val expectedCollectedDataEpoch: Long,
		override val effectiveBootId: String,
		override val effectiveElapsedRealtimeNanos: Long,
		override val effectiveWallTimeMs: Long,
		val expectedPreviousApprovalRevision: Long? = null,
	) : AmbientRadioRetentionDecision

	data class Revoke(
		val scope: String,
		override val expectedCollectedDataEpoch: Long,
		override val effectiveBootId: String,
		override val effectiveElapsedRealtimeNanos: Long,
		override val effectiveWallTimeMs: Long,
		val expectedPreviousApprovalRevision: Long,
	) : AmbientRadioRetentionDecision
}

sealed interface AmbientRadioRetentionAuthorityResult {
	data class Applied(val scope: String, val approvalRevision: Long) :
		AmbientRadioRetentionAuthorityResult

	data class Unavailable(val reason: AmbientRadioRetentionAuthorityUnavailableReason) :
		AmbientRadioRetentionAuthorityResult
}

enum class AmbientRadioRetentionAuthorityUnavailableReason {
	COLLECTED_DATA_EPOCH_CHANGED,
	LIVE_POLICY_AUTHORITY_MISMATCH,
	NO_EXISTING_AUTHORITY,
	STALE_APPROVAL_REVISION,
	APPROVAL_REVISION_EXHAUSTED,
	INTEGRITY_MISMATCH,
	EFFECTIVE_TIME_INVALID,
}

suspend fun AppDatabase.applyAmbientWifiRetentionDecision(
	decision: AmbientRadioRetentionDecision,
): AmbientRadioRetentionAuthorityResult = withTransaction {
	decision.requireValid()
	val scope = decision.wifiScope()
	val dao = ambientWifiFactDao()
	val epochMismatch = sourceEvidenceStateDao().get()?.collectedDataEpoch !=
		decision.expectedCollectedDataEpoch
	if (epochMismatch) return@withTransaction unavailableEpoch()
	val current = dao.latestRetentionAuthority(scope)
	if (current != null && !AmbientWifiRetentionAuthorityIntegrity.isAuthentic(current)) {
		return@withTransaction unavailableIntegrity()
	}
	if (decision is AmbientRadioRetentionDecision.Revoke && current == null) {
		return@withTransaction unavailableMissing()
	}
	if (current?.approvalRevision != decision.expectedPreviousApprovalRevision()) {
		return@withTransaction unavailableStaleRevision()
	}
	if (!decision.hasValidEffectiveTime(current)) {
		return@withTransaction unavailableEffectiveTime()
	}
	if (!hasValidReferencedPolicyTime(decision, SourceDestinationOwnerEntity.SOURCE_WIFI)) {
		return@withTransaction unavailableEffectiveTime()
	}
	val binding = retentionBinding(decision, SourceDestinationOwnerEntity.SOURCE_WIFI)
		?: return@withTransaction unavailableLivePolicy()
	val revision = dao.maximumRetentionApprovalRevision(scope)
		.takeUnless { it == Long.MAX_VALUE }?.plus(1L)
		?: return@withTransaction unavailableRevision()
	val value = AmbientWifiRetentionAuthorityIntegrity.create(
		scope,
		revision,
		if (decision is AmbientRadioRetentionDecision.Revoke) {
			AmbientWifiRetentionAuthorityEntity.STATE_REVOKED
		} else {
			AmbientWifiRetentionAuthorityEntity.STATE_ACTIVE
		},
		decision.opaquePolicyId(current),
		binding.first,
		binding.second,
		decision.expectedCollectedDataEpoch,
		decision.effectiveBootId,
		decision.effectiveElapsedRealtimeNanos,
		decision.effectiveWallTimeMs,
	)
	dao.insertRetentionAuthority(value)
	check(sourceEvidenceStateDao().incrementRevision(decision.effectiveWallTimeMs) == 1)
	AmbientRadioRetentionAuthorityResult.Applied(scope, revision)
}

suspend fun AppDatabase.applyAmbientCellRetentionDecision(
	decision: AmbientRadioRetentionDecision,
): AmbientRadioRetentionAuthorityResult = withTransaction {
	decision.requireValid()
	val scope = decision.cellScope()
	val dao = ambientCellFactDao()
	val epochMismatch = sourceEvidenceStateDao().get()?.collectedDataEpoch !=
		decision.expectedCollectedDataEpoch
	if (epochMismatch) return@withTransaction unavailableEpoch()
	val current = dao.latestRetentionAuthority(scope)
	if (current != null && !AmbientCellRetentionAuthorityIntegrity.isAuthentic(current)) {
		return@withTransaction unavailableIntegrity()
	}
	if (decision is AmbientRadioRetentionDecision.Revoke && current == null) {
		return@withTransaction unavailableMissing()
	}
	if (current?.approvalRevision != decision.expectedPreviousApprovalRevision()) {
		return@withTransaction unavailableStaleRevision()
	}
	if (!decision.hasValidEffectiveTime(current)) {
		return@withTransaction unavailableEffectiveTime()
	}
	if (!hasValidReferencedPolicyTime(decision, SourceDestinationOwnerEntity.SOURCE_CELL)) {
		return@withTransaction unavailableEffectiveTime()
	}
	val binding = retentionBinding(decision, SourceDestinationOwnerEntity.SOURCE_CELL)
		?: return@withTransaction unavailableLivePolicy()
	val revision = dao.maximumRetentionApprovalRevision(scope)
		.takeUnless { it == Long.MAX_VALUE }?.plus(1L)
		?: return@withTransaction unavailableRevision()
	val value = AmbientCellRetentionAuthorityIntegrity.create(
		scope,
		revision,
		if (decision is AmbientRadioRetentionDecision.Revoke) {
			AmbientCellRetentionAuthorityEntity.STATE_REVOKED
		} else {
			AmbientCellRetentionAuthorityEntity.STATE_ACTIVE
		},
		decision.opaquePolicyId(current),
		binding.first,
		binding.second,
		decision.expectedCollectedDataEpoch,
		decision.effectiveBootId,
		decision.effectiveElapsedRealtimeNanos,
		decision.effectiveWallTimeMs,
	)
	dao.insertRetentionAuthority(value)
	check(sourceEvidenceStateDao().incrementRevision(decision.effectiveWallTimeMs) == 1)
	AmbientRadioRetentionAuthorityResult.Applied(scope, revision)
}

private suspend fun AppDatabase.retentionBinding(
	decision: AmbientRadioRetentionDecision,
	sourceKind: Int,
): Pair<Long?, Long?>? = when (decision) {
	is AmbientRadioRetentionDecision.GrantPortableImport -> null to null
	is AmbientRadioRetentionDecision.GrantLiveAmbient -> {
		val authority = sourcePolicyDao().authority()
		val policy = authority?.takeIf {
			it.bootstrapState == SourcePolicyAuthorityEntity.STATE_ACTIVE &&
				it.currentPolicyRevision == decision.expectedSourcePolicyRevision
		}?.let {
			sourcePolicyDao().policyAtRevision(decision.expectedSourcePolicyRevision, sourceKind)
		}
		val consent = sourcePolicyDao().latestConsentEpoch(
			sourceKind,
			SourceBrokerPurpose.AMBIENT_PRODUCT,
		)
		if (policy == null ||
			policy.ambientConsentEpoch != decision.expectedAmbientConsentEpoch ||
			!policy.hasExactEligibleAmbientConsentReference(consent)
		) null else decision.expectedSourcePolicyRevision to decision.expectedAmbientConsentEpoch
	}
	is AmbientRadioRetentionDecision.Revoke -> when (decision.scope) {
		AmbientWifiRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT -> {
			val current = if (sourceKind == SourceDestinationOwnerEntity.SOURCE_WIFI) {
				ambientWifiFactDao().latestRetentionAuthority(decision.scope)
					?.let { it.sourcePolicyRevision to it.ambientConsentEpoch }
			} else {
				ambientCellFactDao().latestRetentionAuthority(decision.scope)
					?.let { it.sourcePolicyRevision to it.ambientConsentEpoch }
			}
			current
		}
		AmbientWifiRetentionAuthorityEntity.SCOPE_PORTABLE_IMPORT -> null to null
		else -> null
	}
}

private suspend fun AppDatabase.hasValidReferencedPolicyTime(
	decision: AmbientRadioRetentionDecision,
	sourceKind: Int,
): Boolean {
	if (decision !is AmbientRadioRetentionDecision.GrantLiveAmbient) return true
	val policy = sourcePolicyDao().policyAtRevision(
		decision.expectedSourcePolicyRevision,
		sourceKind,
	) ?: return true
	val consent = sourcePolicyDao().latestConsentEpoch(
		sourceKind,
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

private fun AmbientRadioRetentionDecision.wifiScope(): String = when (this) {
	is AmbientRadioRetentionDecision.GrantLiveAmbient ->
		AmbientWifiRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT
	is AmbientRadioRetentionDecision.GrantPortableImport ->
		AmbientWifiRetentionAuthorityEntity.SCOPE_PORTABLE_IMPORT
	is AmbientRadioRetentionDecision.Revoke -> scope
}.also { require(it in setOf(
	AmbientWifiRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
	AmbientWifiRetentionAuthorityEntity.SCOPE_PORTABLE_IMPORT,
)) }

private fun AmbientRadioRetentionDecision.cellScope(): String = when (this) {
	is AmbientRadioRetentionDecision.GrantLiveAmbient ->
		AmbientCellRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT
	is AmbientRadioRetentionDecision.GrantPortableImport ->
		AmbientCellRetentionAuthorityEntity.SCOPE_PORTABLE_IMPORT
	is AmbientRadioRetentionDecision.Revoke -> scope
}.also { require(it in setOf(
	AmbientCellRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
	AmbientCellRetentionAuthorityEntity.SCOPE_PORTABLE_IMPORT,
)) }

private fun AmbientRadioRetentionDecision.opaquePolicyId(
	current: AmbientWifiRetentionAuthorityEntity?,
): String = when (this) {
	is AmbientRadioRetentionDecision.GrantLiveAmbient -> opaquePolicyId
	is AmbientRadioRetentionDecision.GrantPortableImport -> opaquePolicyId
	is AmbientRadioRetentionDecision.Revoke -> requireNotNull(current).opaquePolicyId
}

private fun AmbientRadioRetentionDecision.opaquePolicyId(
	current: AmbientCellRetentionAuthorityEntity?,
): String = when (this) {
	is AmbientRadioRetentionDecision.GrantLiveAmbient -> opaquePolicyId
	is AmbientRadioRetentionDecision.GrantPortableImport -> opaquePolicyId
	is AmbientRadioRetentionDecision.Revoke -> requireNotNull(current).opaquePolicyId
}

private fun AmbientRadioRetentionDecision.requireValid() {
	require(expectedCollectedDataEpoch >= 0L)
	require(effectiveBootId.isNotBlank())
	require(effectiveElapsedRealtimeNanos >= 0L && effectiveWallTimeMs >= 0L)
	when (this) {
		is AmbientRadioRetentionDecision.GrantLiveAmbient -> {
			require(opaquePolicyId.isNotBlank() && opaquePolicyId.length <= 256)
			require(expectedSourcePolicyRevision > 0L && expectedAmbientConsentEpoch >= 0L)
			require(expectedPreviousApprovalRevision?.let { it > 0L } != false)
		}
		is AmbientRadioRetentionDecision.GrantPortableImport -> {
			require(opaquePolicyId.isNotBlank() && opaquePolicyId.length <= 256)
			require(expectedPreviousApprovalRevision?.let { it > 0L } != false)
		}
		is AmbientRadioRetentionDecision.Revoke -> {
			require(scope.isNotBlank())
			require(expectedPreviousApprovalRevision > 0L)
		}
	}
}

private fun AmbientRadioRetentionDecision.expectedPreviousApprovalRevision(): Long? = when (this) {
	is AmbientRadioRetentionDecision.GrantLiveAmbient -> expectedPreviousApprovalRevision
	is AmbientRadioRetentionDecision.GrantPortableImport -> expectedPreviousApprovalRevision
	is AmbientRadioRetentionDecision.Revoke -> expectedPreviousApprovalRevision
}

private fun AmbientRadioRetentionDecision.hasValidEffectiveTime(
	current: AmbientWifiRetentionAuthorityEntity?,
): Boolean = current == null || effectiveTimeFollows(
	current.effectiveBootId,
	current.effectiveElapsedRealtimeNanos,
	current.effectiveWallTimeMs,
)

private fun AmbientRadioRetentionDecision.hasValidEffectiveTime(
	current: AmbientCellRetentionAuthorityEntity?,
): Boolean = current == null || effectiveTimeFollows(
	current.effectiveBootId,
	current.effectiveElapsedRealtimeNanos,
	current.effectiveWallTimeMs,
)

private fun AmbientRadioRetentionDecision.effectiveTimeFollows(
	previousBootId: String,
	previousElapsedRealtimeNanos: Long,
	previousWallTimeMs: Long,
): Boolean =
	effectiveWallTimeMs >= previousWallTimeMs &&
		(effectiveBootId != previousBootId ||
			effectiveElapsedRealtimeNanos > previousElapsedRealtimeNanos)

private fun unavailableEpoch() = AmbientRadioRetentionAuthorityResult.Unavailable(
	AmbientRadioRetentionAuthorityUnavailableReason.COLLECTED_DATA_EPOCH_CHANGED,
)

private fun unavailableLivePolicy() = AmbientRadioRetentionAuthorityResult.Unavailable(
	AmbientRadioRetentionAuthorityUnavailableReason.LIVE_POLICY_AUTHORITY_MISMATCH,
)

private fun unavailableMissing() = AmbientRadioRetentionAuthorityResult.Unavailable(
	AmbientRadioRetentionAuthorityUnavailableReason.NO_EXISTING_AUTHORITY,
)

private fun unavailableStaleRevision() = AmbientRadioRetentionAuthorityResult.Unavailable(
	AmbientRadioRetentionAuthorityUnavailableReason.STALE_APPROVAL_REVISION,
)

private fun unavailableRevision() = AmbientRadioRetentionAuthorityResult.Unavailable(
	AmbientRadioRetentionAuthorityUnavailableReason.APPROVAL_REVISION_EXHAUSTED,
)

private fun unavailableIntegrity() = AmbientRadioRetentionAuthorityResult.Unavailable(
	AmbientRadioRetentionAuthorityUnavailableReason.INTEGRITY_MISMATCH,
)

private fun unavailableEffectiveTime() = AmbientRadioRetentionAuthorityResult.Unavailable(
	AmbientRadioRetentionAuthorityUnavailableReason.EFFECTIVE_TIME_INVALID,
)
