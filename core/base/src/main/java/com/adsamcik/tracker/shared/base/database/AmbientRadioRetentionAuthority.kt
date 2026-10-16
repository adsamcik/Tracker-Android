package com.adsamcik.tracker.shared.base.database

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.data.AmbientLocationRetentionAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientLocationRetentionAuthorityIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellRetentionAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellRetentionAuthorityIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiRetentionAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiRetentionAuthorityIntegrity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.hasExactEligibleAmbientConsentReference
import com.adsamcik.tracker.shared.base.database.data.isEffectiveAtOrBefore

/**
 * Explicit live/imported retention grant or revocation bound to the data epoch, previous approval
 * and effective clocks.
 */
sealed interface AmbientRadioRetentionDecision {
	val expectedCollectedDataEpoch: Long
	val expectedRetainedFromMs: Long?
	val effectiveBootId: String
	val effectiveElapsedRealtimeNanos: Long
	val effectiveWallTimeMs: Long

	/**
	 * Grants native all-day retention under exact original policy/consent and data epoch at the
	 * effective boot/elapsed/wall clocks.
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
	) : AmbientRadioRetentionDecision

	/**
	 * Grants imported-product retention for the exact data epoch and floor without authorizing native
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
	) : AmbientRadioRetentionDecision

	/**
	 * Revokes only the named retention scope at the exact previous approval revision and effective
	 * clocks.
	 */
	data class Revoke(
		val scope: String,
		override val expectedCollectedDataEpoch: Long,
		override val effectiveBootId: String,
		override val effectiveElapsedRealtimeNanos: Long,
		override val effectiveWallTimeMs: Long,
		val expectedPreviousApprovalRevision: Long,
		override val expectedRetainedFromMs: Long? = null,
	) : AmbientRadioRetentionDecision
}

/**
 * Saved scope/approval revision or a named stale/invalid retention authority boundary.
 */
sealed interface AmbientRadioRetentionAuthorityResult {
	/**
	 * The explicit retention decision was saved for the reported scope and approval revision.
	 */
	data class Applied(val scope: String, val approvalRevision: Long) :
		AmbientRadioRetentionAuthorityResult

	/**
	 * The requested original scope or retention authority is unavailable for the stated reason.
	 */
	data class Unavailable(val reason: AmbientRadioRetentionAuthorityUnavailableReason) :
		AmbientRadioRetentionAuthorityResult
}

/**
 * Epoch, floor, policy, approval, integrity or effective-time mismatch preventing a retention
 * decision.
 */
enum class AmbientRadioRetentionAuthorityUnavailableReason {
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
 * Applies the explicit Location retention decision atomically after rechecking data epoch, floor,
 * policy and approval revision.
 */
suspend fun AppDatabase.applyAmbientLocationRetentionDecision(
	decision: AmbientRadioRetentionDecision,
): AmbientRadioRetentionAuthorityResult = try {
	withTransaction {
		decision.requireValid()
		val scope = decision.wifiScope()
		if (scope != AmbientLocationRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT) {
			return@withTransaction unavailable(
				AmbientRadioRetentionAuthorityUnavailableReason.LIVE_POLICY_AUTHORITY_MISMATCH,
			)
		}
		val dao = locationPurposeDao()
		val evidence = sourceEvidenceStateDao().get()
			?: return@withTransaction unavailable(
				AmbientRadioRetentionAuthorityUnavailableReason.COLLECTED_DATA_EPOCH_CHANGED,
			)
		if (evidence.collectedDataEpoch != decision.expectedCollectedDataEpoch) {
			return@withTransaction unavailable(
				AmbientRadioRetentionAuthorityUnavailableReason.COLLECTED_DATA_EPOCH_CHANGED,
			)
		}
		if (evidence.retainedFromMs != decision.expectedRetainedFromMs) {
			return@withTransaction unavailable(
				AmbientRadioRetentionAuthorityUnavailableReason.RETAINED_FROM_CHANGED,
			)
		}
		val current = dao.latestRetentionAuthority(scope)
		decision.currentAuthorityUnavailable(current)?.let { return@withTransaction it }
		if (!hasValidReferencedPolicyTime(decision, SourceDestinationOwnerEntity.SOURCE_LOCATION)) {
			return@withTransaction unavailable(
				AmbientRadioRetentionAuthorityUnavailableReason.EFFECTIVE_TIME_INVALID,
			)
		}
		val binding = retentionBinding(decision, SourceDestinationOwnerEntity.SOURCE_LOCATION)
			?: return@withTransaction unavailable(
				AmbientRadioRetentionAuthorityUnavailableReason.LIVE_POLICY_AUTHORITY_MISMATCH,
			)
		val revision = dao.maximumRetentionApprovalRevision(scope)
			.takeUnless { it == Long.MAX_VALUE }?.plus(1L)
			?: return@withTransaction unavailable(
				AmbientRadioRetentionAuthorityUnavailableReason.APPROVAL_REVISION_EXHAUSTED,
			)
		val value = decision.createLocationAuthority(scope, revision, current, binding)
		dao.insertRetentionAuthority(value)
		pruneChangedLocationRetentionFloor(decision, current)
		advanceAmbientRadioRetentionRevision(evidence.revision, decision)
		AmbientRadioRetentionAuthorityResult.Applied(scope, revision)
	}
} catch (_: AmbientRadioRetentionLifecycleChangedDuringApply) {
	unavailable(
		AmbientRadioRetentionAuthorityUnavailableReason.COLLECTED_DATA_EPOCH_CHANGED,
	)
}

/**
 * Applies the explicit Wi-Fi retention decision atomically after rechecking data epoch, floor,
 * policy and approval revision.
 */
suspend fun AppDatabase.applyAmbientWifiRetentionDecision(
	decision: AmbientRadioRetentionDecision,
): AmbientRadioRetentionAuthorityResult = try {
	withTransaction {
		decision.requireValid()
		val scope = decision.wifiScope()
		val dao = ambientWifiFactDao()
		val evidence = sourceEvidenceStateDao().get()
			?: return@withTransaction unavailable(
				AmbientRadioRetentionAuthorityUnavailableReason.COLLECTED_DATA_EPOCH_CHANGED,
			)
		if (evidence.collectedDataEpoch != decision.expectedCollectedDataEpoch) {
			return@withTransaction unavailable(
				AmbientRadioRetentionAuthorityUnavailableReason.COLLECTED_DATA_EPOCH_CHANGED,
			)
		}
		if (evidence.retainedFromMs != decision.expectedRetainedFromMs) {
			return@withTransaction unavailable(
				AmbientRadioRetentionAuthorityUnavailableReason.RETAINED_FROM_CHANGED,
			)
		}
		val current = dao.latestRetentionAuthority(scope)
		decision.currentAuthorityUnavailable(current)?.let { return@withTransaction it }
		if (!hasValidReferencedPolicyTime(decision, SourceDestinationOwnerEntity.SOURCE_WIFI)) {
			return@withTransaction unavailable(
				AmbientRadioRetentionAuthorityUnavailableReason.EFFECTIVE_TIME_INVALID,
			)
		}
		val binding = retentionBinding(decision, SourceDestinationOwnerEntity.SOURCE_WIFI)
			?: return@withTransaction unavailable(
				AmbientRadioRetentionAuthorityUnavailableReason.LIVE_POLICY_AUTHORITY_MISMATCH,
			)
		val revision = dao.maximumRetentionApprovalRevision(scope)
			.takeUnless { it == Long.MAX_VALUE }?.plus(1L)
			?: return@withTransaction unavailable(
				AmbientRadioRetentionAuthorityUnavailableReason.APPROVAL_REVISION_EXHAUSTED,
			)
		val value = decision.createWifiAuthority(scope, revision, current, binding)
		dao.insertRetentionAuthority(value)
		advanceAmbientRadioRetentionRevision(evidence.revision, decision)
		AmbientRadioRetentionAuthorityResult.Applied(scope, revision)
	}
} catch (_: AmbientRadioRetentionLifecycleChangedDuringApply) {
	unavailable(
		AmbientRadioRetentionAuthorityUnavailableReason.COLLECTED_DATA_EPOCH_CHANGED,
	)
}

/**
 * Applies the explicit Cell retention decision atomically after rechecking data epoch, floor,
 * policy and approval revision.
 */
suspend fun AppDatabase.applyAmbientCellRetentionDecision(
	decision: AmbientRadioRetentionDecision,
): AmbientRadioRetentionAuthorityResult = try {
	withTransaction {
		decision.requireValid()
		val scope = decision.cellScope()
		val dao = ambientCellFactDao()
		val evidence = sourceEvidenceStateDao().get()
			?: return@withTransaction unavailable(
				AmbientRadioRetentionAuthorityUnavailableReason.COLLECTED_DATA_EPOCH_CHANGED,
			)
		if (evidence.collectedDataEpoch != decision.expectedCollectedDataEpoch) {
			return@withTransaction unavailable(
				AmbientRadioRetentionAuthorityUnavailableReason.COLLECTED_DATA_EPOCH_CHANGED,
			)
		}
		if (evidence.retainedFromMs != decision.expectedRetainedFromMs) {
			return@withTransaction unavailable(
				AmbientRadioRetentionAuthorityUnavailableReason.RETAINED_FROM_CHANGED,
			)
		}
		val current = dao.latestRetentionAuthority(scope)
		decision.currentAuthorityUnavailable(current)?.let { return@withTransaction it }
		if (!hasValidReferencedPolicyTime(decision, SourceDestinationOwnerEntity.SOURCE_CELL)) {
			return@withTransaction unavailable(
				AmbientRadioRetentionAuthorityUnavailableReason.EFFECTIVE_TIME_INVALID,
			)
		}
		val binding = retentionBinding(decision, SourceDestinationOwnerEntity.SOURCE_CELL)
			?: return@withTransaction unavailable(
				AmbientRadioRetentionAuthorityUnavailableReason.LIVE_POLICY_AUTHORITY_MISMATCH,
			)
		val revision = dao.maximumRetentionApprovalRevision(scope)
			.takeUnless { it == Long.MAX_VALUE }?.plus(1L)
			?: return@withTransaction unavailable(
				AmbientRadioRetentionAuthorityUnavailableReason.APPROVAL_REVISION_EXHAUSTED,
			)
		val value = decision.createCellAuthority(scope, revision, current, binding)
		dao.insertRetentionAuthority(value)
		advanceAmbientRadioRetentionRevision(evidence.revision, decision)
		AmbientRadioRetentionAuthorityResult.Applied(scope, revision)
	}
} catch (_: AmbientRadioRetentionLifecycleChangedDuringApply) {
	unavailable(
		AmbientRadioRetentionAuthorityUnavailableReason.COLLECTED_DATA_EPOCH_CHANGED,
	)
}

private fun AmbientRadioRetentionDecision.currentAuthorityUnavailable(
	current: AmbientLocationRetentionAuthorityEntity?,
): AmbientRadioRetentionAuthorityResult.Unavailable? = when {
	current != null && !AmbientLocationRetentionAuthorityIntegrity.isAuthentic(current) -> unavailable(
		AmbientRadioRetentionAuthorityUnavailableReason.INTEGRITY_MISMATCH,
	)
	this is AmbientRadioRetentionDecision.Revoke && current == null -> unavailable(
		AmbientRadioRetentionAuthorityUnavailableReason.NO_EXISTING_AUTHORITY,
	)
	current?.approvalRevision != expectedPreviousApprovalRevision() -> unavailable(
		AmbientRadioRetentionAuthorityUnavailableReason.STALE_APPROVAL_REVISION,
	)
	!(current == null || effectiveTimeFollows(
		current.effectiveBootId,
		current.effectiveElapsedRealtimeNanos,
		current.effectiveWallTimeMs,
	)) -> unavailable(
		AmbientRadioRetentionAuthorityUnavailableReason.EFFECTIVE_TIME_INVALID,
	)
	else -> null
}

private fun AmbientRadioRetentionDecision.createLocationAuthority(
	scope: String,
	revision: Long,
	current: AmbientLocationRetentionAuthorityEntity?,
	binding: Pair<Long?, Long?>,
): AmbientLocationRetentionAuthorityEntity {
	return AmbientLocationRetentionAuthorityIntegrity.create(
		scope,
		revision,
		if (this is AmbientRadioRetentionDecision.Revoke) {
			AmbientLocationRetentionAuthorityEntity.STATE_REVOKED
		} else {
			AmbientLocationRetentionAuthorityEntity.STATE_ACTIVE
		},
		when (this) {
			is AmbientRadioRetentionDecision.GrantLiveAmbient -> opaquePolicyId
			is AmbientRadioRetentionDecision.GrantPortableImport -> opaquePolicyId
			is AmbientRadioRetentionDecision.Revoke -> requireNotNull(current).opaquePolicyId
		},
		binding.first,
		binding.second,
		expectedCollectedDataEpoch,
		effectiveBootId,
		effectiveElapsedRealtimeNanos,
		effectiveWallTimeMs,
		expectedRetainedFromMs,
	)
}

private fun AmbientRadioRetentionDecision.currentAuthorityUnavailable(
	current: AmbientWifiRetentionAuthorityEntity?,
): AmbientRadioRetentionAuthorityResult.Unavailable? = when {
	current != null && !AmbientWifiRetentionAuthorityIntegrity.isAuthentic(current) -> unavailable(
		AmbientRadioRetentionAuthorityUnavailableReason.INTEGRITY_MISMATCH,
	)
	this is AmbientRadioRetentionDecision.Revoke && current == null -> unavailable(
		AmbientRadioRetentionAuthorityUnavailableReason.NO_EXISTING_AUTHORITY,
	)
	current?.approvalRevision != expectedPreviousApprovalRevision() -> unavailable(
		AmbientRadioRetentionAuthorityUnavailableReason.STALE_APPROVAL_REVISION,
	)
	!(current == null || effectiveTimeFollows(
		current.effectiveBootId,
		current.effectiveElapsedRealtimeNanos,
		current.effectiveWallTimeMs,
	)) -> unavailable(
		AmbientRadioRetentionAuthorityUnavailableReason.EFFECTIVE_TIME_INVALID,
	)
	else -> null
}

private fun AmbientRadioRetentionDecision.createWifiAuthority(
	scope: String,
	revision: Long,
	current: AmbientWifiRetentionAuthorityEntity?,
	binding: Pair<Long?, Long?>,
): AmbientWifiRetentionAuthorityEntity {
	return AmbientWifiRetentionAuthorityIntegrity.create(
		scope,
		revision,
		if (this is AmbientRadioRetentionDecision.Revoke) {
			AmbientWifiRetentionAuthorityEntity.STATE_REVOKED
		} else {
			AmbientWifiRetentionAuthorityEntity.STATE_ACTIVE
		},
		when (this) {
			is AmbientRadioRetentionDecision.GrantLiveAmbient -> opaquePolicyId
			is AmbientRadioRetentionDecision.GrantPortableImport -> opaquePolicyId
			is AmbientRadioRetentionDecision.Revoke -> requireNotNull(current).opaquePolicyId
		},
		binding.first,
		binding.second,
		expectedCollectedDataEpoch,
		effectiveBootId,
		effectiveElapsedRealtimeNanos,
		effectiveWallTimeMs,
		expectedRetainedFromMs,
	)
}

private fun AmbientRadioRetentionDecision.currentAuthorityUnavailable(
	current: AmbientCellRetentionAuthorityEntity?,
): AmbientRadioRetentionAuthorityResult.Unavailable? = when {
	current != null && !AmbientCellRetentionAuthorityIntegrity.isAuthentic(current) -> unavailable(
		AmbientRadioRetentionAuthorityUnavailableReason.INTEGRITY_MISMATCH,
	)
	this is AmbientRadioRetentionDecision.Revoke && current == null -> unavailable(
		AmbientRadioRetentionAuthorityUnavailableReason.NO_EXISTING_AUTHORITY,
	)
	current?.approvalRevision != expectedPreviousApprovalRevision() -> unavailable(
		AmbientRadioRetentionAuthorityUnavailableReason.STALE_APPROVAL_REVISION,
	)
	!(current == null || effectiveTimeFollows(
		current.effectiveBootId,
		current.effectiveElapsedRealtimeNanos,
		current.effectiveWallTimeMs,
	)) -> unavailable(
		AmbientRadioRetentionAuthorityUnavailableReason.EFFECTIVE_TIME_INVALID,
	)
	else -> null
}

private fun AmbientRadioRetentionDecision.createCellAuthority(
	scope: String,
	revision: Long,
	current: AmbientCellRetentionAuthorityEntity?,
	binding: Pair<Long?, Long?>,
): AmbientCellRetentionAuthorityEntity {
	return AmbientCellRetentionAuthorityIntegrity.create(
		scope,
		revision,
		if (this is AmbientRadioRetentionDecision.Revoke) {
			AmbientCellRetentionAuthorityEntity.STATE_REVOKED
		} else {
			AmbientCellRetentionAuthorityEntity.STATE_ACTIVE
		},
		when (this) {
			is AmbientRadioRetentionDecision.GrantLiveAmbient -> opaquePolicyId
			is AmbientRadioRetentionDecision.GrantPortableImport -> opaquePolicyId
			is AmbientRadioRetentionDecision.Revoke -> requireNotNull(current).opaquePolicyId
		},
		binding.first,
		binding.second,
		expectedCollectedDataEpoch,
		effectiveBootId,
		effectiveElapsedRealtimeNanos,
		effectiveWallTimeMs,
		expectedRetainedFromMs,
	)
}

private suspend fun AppDatabase.pruneChangedLocationRetentionFloor(
	decision: AmbientRadioRetentionDecision,
	current: AmbientLocationRetentionAuthorityEntity?,
) {
	// Revoke closes new collection; it does not retract previously authorized points.
	// The exact current retention floor still prunes expired background associations.
	if (decision.expectedRetainedFromMs != current?.retainedFromMs) {
		decision.expectedRetainedFromMs?.let { pruneLocationAmbientPurposeInCurrentTransaction(it) }
	}
}

private suspend fun AppDatabase.advanceAmbientRadioRetentionRevision(
	evidenceRevision: Long,
	decision: AmbientRadioRetentionDecision,
) {
	if (
		sourceEvidenceStateDao().incrementRevisionForExactLifecycle(
			expectedRevision = evidenceRevision,
			expectedCollectedDataEpoch = decision.expectedCollectedDataEpoch,
			expectedRetainedFromMs = decision.expectedRetainedFromMs,
			updatedAtMs = decision.effectiveWallTimeMs,
		) != 1
	) {
		throw AmbientRadioRetentionLifecycleChangedDuringApply
	}
}

private suspend fun AppDatabase.retentionBinding(
	decision: AmbientRadioRetentionDecision,
	sourceKind: Int,
): Pair<Long?, Long?>? = when (decision) {
	is AmbientRadioRetentionDecision.GrantPortableImport -> null to null
	is AmbientRadioRetentionDecision.GrantLiveAmbient -> liveRetentionBinding(decision, sourceKind)
	is AmbientRadioRetentionDecision.Revoke -> when (decision.scope) {
		AmbientWifiRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT -> {
			currentLiveRetentionBinding(decision.scope, sourceKind)
		}
		AmbientWifiRetentionAuthorityEntity.SCOPE_PORTABLE_IMPORT -> null to null
		else -> null
	}
}

private suspend fun AppDatabase.currentLiveRetentionBinding(
	scope: String,
	sourceKind: Int,
): Pair<Long?, Long?>? {
	val current = if (sourceKind == SourceDestinationOwnerEntity.SOURCE_LOCATION) {
		locationPurposeDao().latestRetentionAuthority(scope)
			?.let { it.sourcePolicyRevision to it.ambientConsentEpoch }
	} else if (sourceKind == SourceDestinationOwnerEntity.SOURCE_WIFI) {
		ambientWifiFactDao().latestRetentionAuthority(scope)
			?.let { it.sourcePolicyRevision to it.ambientConsentEpoch }
	} else {
		ambientCellFactDao().latestRetentionAuthority(scope)
			?.let { it.sourcePolicyRevision to it.ambientConsentEpoch }
	}
	return current
}

private suspend fun AppDatabase.liveRetentionBinding(
	decision: AmbientRadioRetentionDecision.GrantLiveAmbient,
	sourceKind: Int,
): Pair<Long?, Long?>? {
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
	decision: AmbientRadioRetentionDecision,
	sourceKind: Int,
): Boolean {
	if (decision !is AmbientRadioRetentionDecision.GrantLiveAmbient) {
		return true
	}
	val policy = sourcePolicyDao().policyAtRevision(
		decision.expectedSourcePolicyRevision,
		sourceKind,
	) ?: return true
	val consent = sourcePolicyDao().latestConsentEpoch(
		sourceKind,
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

@Suppress("CyclomaticComplexMethod") // Keep this fixed field or enum projection together.
private fun AmbientRadioRetentionDecision.requireValid() {
	require(expectedCollectedDataEpoch >= 0L)
	val retainedFromMs = expectedRetainedFromMs
	require(retainedFromMs == null || retainedFromMs >= 0L)
	require(effectiveBootId.isNotBlank())
	require(effectiveElapsedRealtimeNanos >= 0L && effectiveWallTimeMs >= 0L)
	when (this) {
		is AmbientRadioRetentionDecision.GrantLiveAmbient -> {
			require(opaquePolicyId.isNotBlank() && opaquePolicyId.length <= MAX_POLICY_ID_LENGTH)
			require(expectedSourcePolicyRevision > 0L && expectedAmbientConsentEpoch >= 0L)
			require(expectedPreviousApprovalRevision?.let { it > 0L } != false)
		}
		is AmbientRadioRetentionDecision.GrantPortableImport -> {
			require(opaquePolicyId.isNotBlank() && opaquePolicyId.length <= MAX_POLICY_ID_LENGTH)
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

private fun AmbientRadioRetentionDecision.effectiveTimeFollows(
	previousBootId: String,
	previousElapsedRealtimeNanos: Long,
	previousWallTimeMs: Long,
): Boolean =
	effectiveWallTimeMs >= previousWallTimeMs &&
		(effectiveBootId != previousBootId ||
			effectiveElapsedRealtimeNanos > previousElapsedRealtimeNanos)

private fun unavailable(
	reason: AmbientRadioRetentionAuthorityUnavailableReason,
) = AmbientRadioRetentionAuthorityResult.Unavailable(reason)

private object AmbientRadioRetentionLifecycleChangedDuringApply : IllegalStateException()

private const val MAX_POLICY_ID_LENGTH = 256
