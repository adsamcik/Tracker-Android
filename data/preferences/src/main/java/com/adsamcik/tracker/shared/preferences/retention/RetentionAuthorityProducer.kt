package com.adsamcik.tracker.shared.preferences.retention

import com.adsamcik.tracker.shared.base.database.AmbientRadioRetentionAuthorityResult
import com.adsamcik.tracker.shared.base.database.AmbientRadioRetentionAuthorityUnavailableReason
import com.adsamcik.tracker.shared.base.database.AmbientRadioRetentionDecision
import com.adsamcik.tracker.shared.base.database.AmbientStepsRetentionAuthorityResult
import com.adsamcik.tracker.shared.base.database.AmbientStepsRetentionAuthorityUnavailableReason
import com.adsamcik.tracker.shared.base.database.AmbientStepsRetentionDecision
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.applyAmbientCellRetentionDecision
import com.adsamcik.tracker.shared.base.database.applyAmbientStepsRetentionDecision
import com.adsamcik.tracker.shared.base.database.applyAmbientWifiRetentionDecision
import com.adsamcik.tracker.shared.base.database.data.AmbientCellRetentionAuthorityIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsRetentionAuthorityIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiRetentionAuthorityIntegrity
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleSnapshot
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyAuthorityState
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyEffectiveTime
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyEffectiveTimeProvider
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyRepository
import com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class RetentionAuthorityScope {
	LIVE_AMBIENT,
	PORTABLE_IMPORT,
}

enum class RetentionAuthorityState {
	ACTIVE,
	REVOKED,
}

enum class RetentionAuthorityUnavailableReason {
	RETENTION_POLICY_UNAVAILABLE,
	PURPOSE_AUTHORITY_UNAVAILABLE,
	RETENTION_AUTHORITY_UNAVAILABLE,
	COLLECTED_DATA_EPOCH_CHANGED,
	STALE_APPROVAL_REVISION,
	APPROVAL_REVISION_EXHAUSTED,
	INTEGRITY_MISMATCH,
	STALE_CONFIGURATION_GENERATION,
	EFFECTIVE_TIME_INVALID,
	SOURCE_AUTHORITY_UNAVAILABLE,
	STORAGE_UNAVAILABLE,
}

sealed interface RetentionConfigurationApprovalResult {
	data class Prepared(val policy: ApprovedRetentionPolicy) :
		RetentionConfigurationApprovalResult

	data class Approved(val policy: ApprovedRetentionPolicy) :
		RetentionConfigurationApprovalResult

	data class AlreadyApproved(val policy: ApprovedRetentionPolicy) :
		RetentionConfigurationApprovalResult

	data class Unavailable(val reason: RetentionAuthorityUnavailableReason) :
		RetentionConfigurationApprovalResult
}

sealed interface RetentionAuthorityResult {
	val source: TrackingSourceComponent
	val scope: RetentionAuthorityScope

	data class Applied(
		override val source: TrackingSourceComponent,
		override val scope: RetentionAuthorityScope,
		val state: RetentionAuthorityState,
		val approvalRevision: Long,
	) : RetentionAuthorityResult

	data class Unchanged(
		override val source: TrackingSourceComponent,
		override val scope: RetentionAuthorityScope,
		val state: RetentionAuthorityState,
		val approvalRevision: Long?,
	) : RetentionAuthorityResult

	data class Unavailable(
		override val source: TrackingSourceComponent,
		override val scope: RetentionAuthorityScope,
		val reason: RetentionAuthorityUnavailableReason,
	) : RetentionAuthorityResult
}

fun RetentionAuthorityResult.isActiveApproval(): Boolean = when (this) {
	is RetentionAuthorityResult.Applied -> state == RetentionAuthorityState.ACTIVE
	is RetentionAuthorityResult.Unchanged -> state == RetentionAuthorityState.ACTIVE
	is RetentionAuthorityResult.Unavailable -> false
}

sealed interface CurrentRetentionAuthority {
	data class Approved(
		val opaquePolicyId: String,
		val approvalRevision: Long,
		val effectiveBootId: String,
		val effectiveElapsedRealtimeNanos: Long,
		val effectiveWallTimeMs: Long,
	) : CurrentRetentionAuthority {
		init {
			require(opaquePolicyId.isNotBlank())
			require(approvalRevision > 0L)
			require(effectiveBootId.isNotBlank())
			require(effectiveElapsedRealtimeNanos >= 0L)
			require(effectiveWallTimeMs >= 0L)
		}
	}

	data class Unavailable(val reason: RetentionAuthorityUnavailableReason) :
		CurrentRetentionAuthority
}

interface RetentionAuthorityReader {
	suspend fun currentLiveAmbient(
		source: TrackingSourceComponent,
		expectedSourcePolicyRevision: Long,
		expectedAmbientConsentEpoch: Long,
		expectedCollectedDataEpoch: Long,
	): CurrentRetentionAuthority

	/** Fail-closed broker seam for an exact persisted retention identity in the current boot. */
	suspend fun isCurrentLiveAmbientAt(
		source: TrackingSourceComponent,
		expectedSourcePolicyRevision: Long,
		expectedAmbientConsentEpoch: Long,
		expectedCollectedDataEpoch: Long,
		expectedOpaquePolicyId: String,
		expectedApprovalRevision: Long,
		currentBootId: String,
		currentElapsedRealtimeNanos: Long,
		currentWallTimeMs: Long,
	): Boolean {
		val authority = currentLiveAmbient(
			source,
			expectedSourcePolicyRevision,
			expectedAmbientConsentEpoch,
			expectedCollectedDataEpoch,
		)
		return authority is CurrentRetentionAuthority.Approved &&
			authority.opaquePolicyId == expectedOpaquePolicyId &&
			authority.approvalRevision == expectedApprovalRevision &&
			authority.effectiveBootId == currentBootId &&
			authority.effectiveElapsedRealtimeNanos <= currentElapsedRealtimeNanos &&
			authority.effectiveWallTimeMs <= currentWallTimeMs
	}
}

fun interface LocationPassiveRetentionAuthority {
	/** Default-unavailable seam for the future passive Location source owner. */
	suspend fun reconcilePassiveLocationRetention(): RetentionAuthorityResult
}

interface RetentionAuthorityProducer :
	RetentionAuthorityReader,
	LocationPassiveRetentionAuthority {
	suspend fun reconcileCurrentSettings(): List<RetentionAuthorityResult>
	suspend fun preparePendingConfiguration(
		expectedConfigurationGeneration: Long,
	): RetentionConfigurationApprovalResult
	suspend fun reconcilePendingConfiguration(
		expectedConfigurationGeneration: Long? = null,
	): RetentionConfigurationApprovalResult
	suspend fun reconcileLiveAmbient(source: TrackingSourceComponent): RetentionAuthorityResult
	suspend fun approvePortableImport(source: TrackingSourceComponent): RetentionAuthorityResult
	suspend fun revokePortableImport(source: TrackingSourceComponent): RetentionAuthorityResult
}

object UnavailableRetentionAuthorityProducer : RetentionAuthorityProducer {
	override suspend fun reconcileCurrentSettings(): List<RetentionAuthorityResult> = emptyList()

	override suspend fun preparePendingConfiguration(
		expectedConfigurationGeneration: Long,
	): RetentionConfigurationApprovalResult = RetentionConfigurationApprovalResult.Unavailable(
		RetentionAuthorityUnavailableReason.RETENTION_POLICY_UNAVAILABLE,
	)

	override suspend fun reconcilePendingConfiguration(
		expectedConfigurationGeneration: Long?,
	): RetentionConfigurationApprovalResult = RetentionConfigurationApprovalResult.Unavailable(
		RetentionAuthorityUnavailableReason.RETENTION_POLICY_UNAVAILABLE,
	)

	override suspend fun reconcileLiveAmbient(
		source: TrackingSourceComponent,
	): RetentionAuthorityResult = unavailable(
		source,
		RetentionAuthorityScope.LIVE_AMBIENT,
		RetentionAuthorityUnavailableReason.RETENTION_POLICY_UNAVAILABLE,
	)

	override suspend fun approvePortableImport(
		source: TrackingSourceComponent,
	): RetentionAuthorityResult = unavailable(
		source,
		RetentionAuthorityScope.PORTABLE_IMPORT,
		RetentionAuthorityUnavailableReason.RETENTION_POLICY_UNAVAILABLE,
	)

	override suspend fun revokePortableImport(
		source: TrackingSourceComponent,
	): RetentionAuthorityResult = unavailable(
		source,
		RetentionAuthorityScope.PORTABLE_IMPORT,
		RetentionAuthorityUnavailableReason.RETENTION_POLICY_UNAVAILABLE,
	)

	override suspend fun reconcilePassiveLocationRetention(): RetentionAuthorityResult =
		reconcileLiveAmbient(TrackingSourceComponent.LOCATION)

	override suspend fun currentLiveAmbient(
		source: TrackingSourceComponent,
		expectedSourcePolicyRevision: Long,
		expectedAmbientConsentEpoch: Long,
		expectedCollectedDataEpoch: Long,
	): CurrentRetentionAuthority = CurrentRetentionAuthority.Unavailable(
		RetentionAuthorityUnavailableReason.RETENTION_POLICY_UNAVAILABLE,
	)
}

class DefaultRetentionAuthorityProducer internal constructor(
	private val database: AppDatabase,
	private val sourcePolicyRepository: SourcePolicyRepository,
	private val readApprovedPolicy: suspend () -> ApprovedRetentionPolicyRead,
	private val readLifecycle: suspend () -> CollectedDataLifecycleSnapshot,
	private val effectiveTimeProvider: SourcePolicyEffectiveTimeProvider,
	private val beforeDecisionApply: suspend (
		TrackingSourceComponent,
		RetentionAuthorityScope,
	) -> Unit,
	private val readPolicyCandidate: suspend () -> RetentionPolicyCandidateRead = {
		when (val approved = readApprovedPolicy()) {
			is ApprovedRetentionPolicyRead.Available -> RetentionPolicyCandidateRead.Available(
				approved.policy,
				RetentionPolicyApprovalStatus.APPROVED,
			)
			is ApprovedRetentionPolicyRead.Unavailable ->
				RetentionPolicyCandidateRead.Unavailable(approved.reason)
		}
	},
	private val markPolicyApproved: suspend (ApprovedRetentionPolicy) -> ApprovedRetentionPolicy? = { it },
	private val readPendingPolicyCandidate: suspend () -> RetentionPolicyCandidateRead = {
		when (val candidate = readPolicyCandidate()) {
			is RetentionPolicyCandidateRead.Available ->
				if (candidate.status == RetentionPolicyApprovalStatus.PENDING) {
					candidate
				} else {
					RetentionPolicyCandidateRead.Unavailable(
						ApprovedRetentionPolicyUnavailableReason.NOT_APPROVED,
					)
				}
			is RetentionPolicyCandidateRead.Unavailable -> candidate
		}
	},
) : RetentionAuthorityProducer {
	constructor(
		database: AppDatabase,
		sourcePolicyRepository: SourcePolicyRepository,
		retentionConfigStore: RetentionConfigStore,
		collectedDataLifecycleStore: CollectedDataLifecycleStore,
		effectiveTimeProvider: SourcePolicyEffectiveTimeProvider,
	) : this(
		database = database,
		sourcePolicyRepository = sourcePolicyRepository,
		readApprovedPolicy = retentionConfigStore::currentApprovedPolicy,
		readLifecycle = collectedDataLifecycleStore::snapshot,
		effectiveTimeProvider = effectiveTimeProvider,
		beforeDecisionApply = { _, _ -> },
		readPolicyCandidate = retentionConfigStore::currentPolicyCandidate,
		markPolicyApproved = retentionConfigStore::markPolicyApproved,
		readPendingPolicyCandidate = retentionConfigStore::pendingPolicyCandidate,
	)

	private val mutex = Mutex()

	override suspend fun reconcileCurrentSettings(): List<RetentionAuthorityResult> =
		mutex.withLock {
			(readPendingPolicyCandidate() as? RetentionPolicyCandidateRead.Available)?.let {
				preparePendingConfigurationLocked(it.policy.configurationGeneration)
			}
			reconcilePendingConfigurationLocked()
			val results = mutableListOf<RetentionAuthorityResult>()
			for (source in LIVE_AMBIENT_SOURCES) {
				results += safely(source, RetentionAuthorityScope.LIVE_AMBIENT) {
					reconcileLiveAmbientLocked(source)
				}
			}
			for (source in PORTABLE_IMPORT_SOURCES) {
				results += safely(source, RetentionAuthorityScope.PORTABLE_IMPORT) {
					reconcileExistingPortableImportLocked(source)
				}
			}
			results
		}

	override suspend fun preparePendingConfiguration(
		expectedConfigurationGeneration: Long,
	): RetentionConfigurationApprovalResult = mutex.withLock {
		try {
			preparePendingConfigurationLocked(expectedConfigurationGeneration)
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: Exception) {
			RetentionConfigurationApprovalResult.Unavailable(
				RetentionAuthorityUnavailableReason.STORAGE_UNAVAILABLE,
			)
		}
	}

	override suspend fun reconcilePendingConfiguration(
		expectedConfigurationGeneration: Long?,
	): RetentionConfigurationApprovalResult = mutex.withLock {
		try {
			reconcilePendingConfigurationLocked(expectedConfigurationGeneration)
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: Exception) {
			RetentionConfigurationApprovalResult.Unavailable(
				RetentionAuthorityUnavailableReason.STORAGE_UNAVAILABLE,
			)
		}
	}

	override suspend fun reconcileLiveAmbient(
		source: TrackingSourceComponent,
	): RetentionAuthorityResult = mutex.withLock {
		safely(source, RetentionAuthorityScope.LIVE_AMBIENT) {
			reconcileLiveAmbientLocked(source)
		}
	}

	override suspend fun approvePortableImport(
		source: TrackingSourceComponent,
	): RetentionAuthorityResult = mutex.withLock {
		safely(source, RetentionAuthorityScope.PORTABLE_IMPORT) {
			if (source !in PORTABLE_IMPORT_SOURCES) {
				return@safely unavailable(
					source,
					RetentionAuthorityScope.PORTABLE_IMPORT,
					RetentionAuthorityUnavailableReason.SOURCE_AUTHORITY_UNAVAILABLE,
				)
			}
			val approval = approvedPolicyOrNull() ?: return@safely unavailable(
				source,
				RetentionAuthorityScope.PORTABLE_IMPORT,
				RetentionAuthorityUnavailableReason.RETENTION_POLICY_UNAVAILABLE,
			)
			val lifecycle = readLifecycle()
			grant(
				source = source,
				scope = RetentionAuthorityScope.PORTABLE_IMPORT,
				approval = approval,
				lifecycle = lifecycle,
				sourcePolicyRevision = null,
				ambientConsentEpoch = null,
			)
		}
	}

	override suspend fun revokePortableImport(
		source: TrackingSourceComponent,
	): RetentionAuthorityResult = mutex.withLock {
		safely(source, RetentionAuthorityScope.PORTABLE_IMPORT) {
			revoke(source, RetentionAuthorityScope.PORTABLE_IMPORT, readLifecycle())
		}
	}

	override suspend fun reconcilePassiveLocationRetention(): RetentionAuthorityResult =
		reconcileLiveAmbient(TrackingSourceComponent.LOCATION)

	override suspend fun currentLiveAmbient(
		source: TrackingSourceComponent,
		expectedSourcePolicyRevision: Long,
		expectedAmbientConsentEpoch: Long,
		expectedCollectedDataEpoch: Long,
	): CurrentRetentionAuthority = mutex.withLock {
		try {
			if (source !in DURABLE_LIVE_AMBIENT_SOURCES) {
				return@withLock CurrentRetentionAuthority.Unavailable(
					RetentionAuthorityUnavailableReason.SOURCE_AUTHORITY_UNAVAILABLE,
				)
			}
			val approval = approvedPolicyOrNull()
				?: return@withLock CurrentRetentionAuthority.Unavailable(
					RetentionAuthorityUnavailableReason.RETENTION_POLICY_UNAVAILABLE,
				)
			val lifecycle = readLifecycle()
			if (lifecycle.epoch != expectedCollectedDataEpoch) {
				return@withLock CurrentRetentionAuthority.Unavailable(
					RetentionAuthorityUnavailableReason.COLLECTED_DATA_EPOCH_CHANGED,
				)
			}
			if (
				database.sourceEvidenceStateDao().get()?.collectedDataEpoch !=
				expectedCollectedDataEpoch
			) {
				return@withLock CurrentRetentionAuthority.Unavailable(
					RetentionAuthorityUnavailableReason.COLLECTED_DATA_EPOCH_CHANGED,
				)
			}
			val authority = sourcePolicyRepository.currentState()
			val snapshot = (authority as? SourcePolicyAuthorityState.Active)?.snapshot
				?: return@withLock CurrentRetentionAuthority.Unavailable(
					RetentionAuthorityUnavailableReason.PURPOSE_AUTHORITY_UNAVAILABLE,
				)
			val policy = snapshot[source]
			if (
				snapshot.revision != expectedSourcePolicyRevision ||
				policy.ambientConsentEpoch != expectedAmbientConsentEpoch ||
				!policy.ambientPersistenceEligible
			) {
				return@withLock CurrentRetentionAuthority.Unavailable(
					RetentionAuthorityUnavailableReason.PURPOSE_AUTHORITY_UNAVAILABLE,
				)
			}
			val stored = storedAuthority(source, RetentionAuthorityScope.LIVE_AMBIENT)
				?: return@withLock CurrentRetentionAuthority.Unavailable(
					RetentionAuthorityUnavailableReason.RETENTION_AUTHORITY_UNAVAILABLE,
				)
			if (!stored.authentic) {
				return@withLock CurrentRetentionAuthority.Unavailable(
					RetentionAuthorityUnavailableReason.INTEGRITY_MISMATCH,
				)
			}
			if (
				stored.state != RetentionAuthorityState.ACTIVE ||
				stored.opaquePolicyId != approval.opaquePolicyId ||
				stored.sourcePolicyRevision != expectedSourcePolicyRevision ||
				stored.ambientConsentEpoch != expectedAmbientConsentEpoch ||
				stored.collectedDataEpoch != expectedCollectedDataEpoch
			) {
				return@withLock CurrentRetentionAuthority.Unavailable(
					RetentionAuthorityUnavailableReason.RETENTION_AUTHORITY_UNAVAILABLE,
				)
			}
			val now = effectiveTimeProvider.now()
			if (
				stored.effectiveBootId != now.bootId ||
				stored.effectiveElapsedRealtimeNanos > now.elapsedRealtimeNanos ||
				stored.effectiveWallTimeMs > now.wallTimeMs
			) {
				return@withLock CurrentRetentionAuthority.Unavailable(
					RetentionAuthorityUnavailableReason.EFFECTIVE_TIME_INVALID,
				)
			}
			CurrentRetentionAuthority.Approved(
				stored.opaquePolicyId,
				stored.approvalRevision,
				stored.effectiveBootId,
				stored.effectiveElapsedRealtimeNanos,
				stored.effectiveWallTimeMs,
			)
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: Exception) {
			CurrentRetentionAuthority.Unavailable(
				RetentionAuthorityUnavailableReason.STORAGE_UNAVAILABLE,
			)
		}
	}

	private suspend fun preparePendingConfigurationLocked(
		expectedConfigurationGeneration: Long,
	): RetentionConfigurationApprovalResult {
		val candidate = when (val read = readPendingPolicyCandidate()) {
			is RetentionPolicyCandidateRead.Available -> read
			is RetentionPolicyCandidateRead.Unavailable ->
				return RetentionConfigurationApprovalResult.Unavailable(
					RetentionAuthorityUnavailableReason.RETENTION_POLICY_UNAVAILABLE,
				)
		}
		if (candidate.policy.configurationGeneration != expectedConfigurationGeneration) {
			return RetentionConfigurationApprovalResult.Unavailable(
				RetentionAuthorityUnavailableReason.STALE_CONFIGURATION_GENERATION,
			)
		}
		if (candidate.status != RetentionPolicyApprovalStatus.PENDING) {
			return RetentionConfigurationApprovalResult.Unavailable(
				RetentionAuthorityUnavailableReason.STALE_CONFIGURATION_GENERATION,
			)
		}
		val lifecycle = readLifecycle()
		for (source in DURABLE_LIVE_AMBIENT_SOURCES) {
			val revoked = revoke(source, RetentionAuthorityScope.LIVE_AMBIENT, lifecycle)
			if (revoked is RetentionAuthorityResult.Unavailable) {
				return RetentionConfigurationApprovalResult.Unavailable(revoked.reason)
			}
		}
		for (source in PORTABLE_IMPORT_SOURCES) {
			val revoked = revoke(source, RetentionAuthorityScope.PORTABLE_IMPORT, lifecycle)
			if (revoked is RetentionAuthorityResult.Unavailable) {
				return RetentionConfigurationApprovalResult.Unavailable(revoked.reason)
			}
		}
		return RetentionConfigurationApprovalResult.Prepared(candidate.policy)
	}

	private suspend fun reconcilePendingConfigurationLocked(
		expectedConfigurationGeneration: Long? = null,
	): RetentionConfigurationApprovalResult {
		val candidate = when (val read = readPolicyCandidate()) {
			is RetentionPolicyCandidateRead.Available -> read
			is RetentionPolicyCandidateRead.Unavailable ->
				return RetentionConfigurationApprovalResult.Unavailable(
					RetentionAuthorityUnavailableReason.RETENTION_POLICY_UNAVAILABLE,
				)
		}
		if (
			expectedConfigurationGeneration != null &&
			candidate.policy.configurationGeneration != expectedConfigurationGeneration
		) {
			return RetentionConfigurationApprovalResult.Unavailable(
				RetentionAuthorityUnavailableReason.STALE_CONFIGURATION_GENERATION,
			)
		}
		if (candidate.status == RetentionPolicyApprovalStatus.APPROVED) {
			return RetentionConfigurationApprovalResult.AlreadyApproved(candidate.policy)
		}
		val lifecycle = readLifecycle()
		for (source in DURABLE_LIVE_AMBIENT_SOURCES) {
			val revoked = revoke(source, RetentionAuthorityScope.LIVE_AMBIENT, lifecycle)
			if (revoked is RetentionAuthorityResult.Unavailable) {
				return RetentionConfigurationApprovalResult.Unavailable(revoked.reason)
			}
		}
		for (source in PORTABLE_IMPORT_SOURCES) {
			val revoked = revoke(source, RetentionAuthorityScope.PORTABLE_IMPORT, lifecycle)
			if (revoked is RetentionAuthorityResult.Unavailable) {
				return RetentionConfigurationApprovalResult.Unavailable(revoked.reason)
			}
		}
		val policyState = sourcePolicyRepository.currentState()
		val snapshot = (policyState as? SourcePolicyAuthorityState.Active)?.snapshot
		if (snapshot != null) {
			for (source in DURABLE_LIVE_AMBIENT_SOURCES) {
				val policy = snapshot[source]
				val consentEpoch = policy.ambientConsentEpoch
				if (consentEpoch != null && policy.ambientPersistenceEligible) {
					val granted = grant(
						source = source,
						scope = RetentionAuthorityScope.LIVE_AMBIENT,
						approval = candidate.policy,
						lifecycle = lifecycle,
						sourcePolicyRevision = snapshot.revision,
						ambientConsentEpoch = consentEpoch,
					)
					if (granted is RetentionAuthorityResult.Unavailable) {
						return RetentionConfigurationApprovalResult.Unavailable(granted.reason)
					}
				}
			}
		}
		val approved = markPolicyApproved(candidate.policy)
		if (approved == null) {
			return RetentionConfigurationApprovalResult.Unavailable(
				RetentionAuthorityUnavailableReason.STALE_CONFIGURATION_GENERATION,
			)
		}
		return RetentionConfigurationApprovalResult.Approved(approved)
	}

	private suspend fun reconcileLiveAmbientLocked(
		source: TrackingSourceComponent,
	): RetentionAuthorityResult {
		if (source !in DURABLE_LIVE_AMBIENT_SOURCES) {
			return unavailable(
				source,
				RetentionAuthorityScope.LIVE_AMBIENT,
				RetentionAuthorityUnavailableReason.SOURCE_AUTHORITY_UNAVAILABLE,
			)
		}
		val lifecycle = readLifecycle()
		val authority = sourcePolicyRepository.currentState()
		val snapshot = (authority as? SourcePolicyAuthorityState.Active)?.snapshot
		val policy = snapshot?.get(source)
		if (
			snapshot == null ||
			policy?.ambientConsentEpoch == null ||
			!policy.ambientPersistenceEligible
		) {
			revoke(source, RetentionAuthorityScope.LIVE_AMBIENT, lifecycle)
			return unavailable(
				source,
				RetentionAuthorityScope.LIVE_AMBIENT,
				RetentionAuthorityUnavailableReason.PURPOSE_AUTHORITY_UNAVAILABLE,
			)
		}
		val approval = approvedPolicyOrNull()
		if (approval == null) {
			revoke(source, RetentionAuthorityScope.LIVE_AMBIENT, lifecycle)
			return unavailable(
				source,
				RetentionAuthorityScope.LIVE_AMBIENT,
				RetentionAuthorityUnavailableReason.RETENTION_POLICY_UNAVAILABLE,
			)
		}
		return grant(
			source = source,
			scope = RetentionAuthorityScope.LIVE_AMBIENT,
			approval = approval,
			lifecycle = lifecycle,
			sourcePolicyRevision = snapshot.revision,
			ambientConsentEpoch = policy.ambientConsentEpoch,
		)
	}

	private suspend fun reconcileExistingPortableImportLocked(
		source: TrackingSourceComponent,
	): RetentionAuthorityResult {
		val scope = RetentionAuthorityScope.PORTABLE_IMPORT
		val current = storedAuthority(source, scope)
			?: return RetentionAuthorityResult.Unchanged(
				source,
				scope,
				RetentionAuthorityState.REVOKED,
				null,
			)
		if (!current.authentic) {
			return unavailable(
				source,
				scope,
				RetentionAuthorityUnavailableReason.INTEGRITY_MISMATCH,
			)
		}
		if (current.state == RetentionAuthorityState.REVOKED) {
			return RetentionAuthorityResult.Unchanged(
				source,
				scope,
				RetentionAuthorityState.REVOKED,
				current.approvalRevision,
			)
		}
		val lifecycle = readLifecycle()
		val approval = approvedPolicyOrNull()
		if (
			approval == null ||
			current.opaquePolicyId != approval.opaquePolicyId ||
			current.collectedDataEpoch != lifecycle.epoch
		) {
			revoke(source, scope, lifecycle)
			return unavailable(
				source,
				scope,
				if (approval == null) {
					RetentionAuthorityUnavailableReason.RETENTION_POLICY_UNAVAILABLE
				} else {
					RetentionAuthorityUnavailableReason.RETENTION_AUTHORITY_UNAVAILABLE
				},
			)
		}
		return RetentionAuthorityResult.Unchanged(
			source,
			scope,
			RetentionAuthorityState.ACTIVE,
			current.approvalRevision,
		)
	}

	private suspend fun grant(
		source: TrackingSourceComponent,
		scope: RetentionAuthorityScope,
		approval: ApprovedRetentionPolicy,
		lifecycle: CollectedDataLifecycleSnapshot,
		sourcePolicyRevision: Long?,
		ambientConsentEpoch: Long?,
	): RetentionAuthorityResult {
		val current = storedAuthority(source, scope)
		if (current?.authentic == false) {
			return unavailable(
				source,
				scope,
				RetentionAuthorityUnavailableReason.INTEGRITY_MISMATCH,
			)
		}
		if (
			current?.state == RetentionAuthorityState.ACTIVE &&
			current.opaquePolicyId == approval.opaquePolicyId &&
			current.sourcePolicyRevision == sourcePolicyRevision &&
			current.ambientConsentEpoch == ambientConsentEpoch &&
			current.collectedDataEpoch == lifecycle.epoch
		) {
			val now = effectiveTimeProvider.now()
			if (
				current.effectiveBootId == now.bootId &&
				current.effectiveElapsedRealtimeNanos <= now.elapsedRealtimeNanos &&
				current.effectiveWallTimeMs <= now.wallTimeMs
			) {
				return RetentionAuthorityResult.Unchanged(
					source,
					scope,
					RetentionAuthorityState.ACTIVE,
					current.approvalRevision,
				)
			}
		}
		beforeDecisionApply(source, scope)
		val time = nextEffectiveTime(current) ?: return unavailable(
			source,
			scope,
			RetentionAuthorityUnavailableReason.EFFECTIVE_TIME_INVALID,
		)
		return applyGrant(
			source,
			scope,
			approval.opaquePolicyId,
			lifecycle.epoch,
			sourcePolicyRevision,
			ambientConsentEpoch,
			current?.approvalRevision,
			time,
		)
	}

	private suspend fun revoke(
		source: TrackingSourceComponent,
		scope: RetentionAuthorityScope,
		lifecycle: CollectedDataLifecycleSnapshot,
	): RetentionAuthorityResult {
		val current = storedAuthority(source, scope)
			?: return RetentionAuthorityResult.Unchanged(
				source,
				scope,
				RetentionAuthorityState.REVOKED,
				null,
			)
		if (!current.authentic) {
			return unavailable(
				source,
				scope,
				RetentionAuthorityUnavailableReason.INTEGRITY_MISMATCH,
			)
		}
		if (current.state == RetentionAuthorityState.REVOKED) {
			return RetentionAuthorityResult.Unchanged(
				source,
				scope,
				RetentionAuthorityState.REVOKED,
				current.approvalRevision,
			)
		}
		beforeDecisionApply(source, scope)
		val time = nextEffectiveTime(current) ?: return unavailable(
			source,
			scope,
			RetentionAuthorityUnavailableReason.EFFECTIVE_TIME_INVALID,
		)
		return applyRevoke(source, scope, lifecycle.epoch, current.approvalRevision, time)
	}

	private fun nextEffectiveTime(
		current: StoredRetentionAuthority?,
	): SourcePolicyEffectiveTime? {
		val now = effectiveTimeProvider.now()
		if (current == null) return now
		if (now.wallTimeMs < current.effectiveWallTimeMs) return null
		if (now.bootId != current.effectiveBootId) return now
		val elapsed = if (now.elapsedRealtimeNanos > current.effectiveElapsedRealtimeNanos) {
			now.elapsedRealtimeNanos
		} else {
			try {
				Math.addExact(current.effectiveElapsedRealtimeNanos, 1L)
			} catch (_: ArithmeticException) {
				return null
			}
		}
		return now.copy(
			elapsedRealtimeNanos = elapsed,
			wallTimeMs = maxOf(now.wallTimeMs, current.effectiveWallTimeMs),
		)
	}

	private suspend fun applyGrant(
		source: TrackingSourceComponent,
		scope: RetentionAuthorityScope,
		opaquePolicyId: String,
		collectedDataEpoch: Long,
		sourcePolicyRevision: Long?,
		ambientConsentEpoch: Long?,
		expectedPreviousApprovalRevision: Long?,
		time: SourcePolicyEffectiveTime,
	): RetentionAuthorityResult = when (source) {
		TrackingSourceComponent.STEPS -> database.applyAmbientStepsRetentionDecision(
			if (scope == RetentionAuthorityScope.LIVE_AMBIENT) {
				AmbientStepsRetentionDecision.GrantLiveAmbient(
					opaquePolicyId,
					collectedDataEpoch,
					requireNotNull(sourcePolicyRevision),
					requireNotNull(ambientConsentEpoch),
					time.bootId,
					time.elapsedRealtimeNanos,
					time.wallTimeMs,
					expectedPreviousApprovalRevision,
				)
			} else {
				AmbientStepsRetentionDecision.GrantPortableImport(
					opaquePolicyId,
					collectedDataEpoch,
					time.bootId,
					time.elapsedRealtimeNanos,
					time.wallTimeMs,
					expectedPreviousApprovalRevision,
				)
			},
		).toResult(source, scope, RetentionAuthorityState.ACTIVE)
		TrackingSourceComponent.WIFI -> database.applyAmbientWifiRetentionDecision(
			if (scope == RetentionAuthorityScope.LIVE_AMBIENT) {
				AmbientRadioRetentionDecision.GrantLiveAmbient(
					opaquePolicyId,
					collectedDataEpoch,
					requireNotNull(sourcePolicyRevision),
					requireNotNull(ambientConsentEpoch),
					time.bootId,
					time.elapsedRealtimeNanos,
					time.wallTimeMs,
					expectedPreviousApprovalRevision,
				)
			} else {
				AmbientRadioRetentionDecision.GrantPortableImport(
					opaquePolicyId,
					collectedDataEpoch,
					time.bootId,
					time.elapsedRealtimeNanos,
					time.wallTimeMs,
					expectedPreviousApprovalRevision,
				)
			},
		).toResult(source, scope, RetentionAuthorityState.ACTIVE)
		TrackingSourceComponent.CELL -> database.applyAmbientCellRetentionDecision(
			if (scope == RetentionAuthorityScope.LIVE_AMBIENT) {
				AmbientRadioRetentionDecision.GrantLiveAmbient(
					opaquePolicyId,
					collectedDataEpoch,
					requireNotNull(sourcePolicyRevision),
					requireNotNull(ambientConsentEpoch),
					time.bootId,
					time.elapsedRealtimeNanos,
					time.wallTimeMs,
					expectedPreviousApprovalRevision,
				)
			} else {
				AmbientRadioRetentionDecision.GrantPortableImport(
					opaquePolicyId,
					collectedDataEpoch,
					time.bootId,
					time.elapsedRealtimeNanos,
					time.wallTimeMs,
					expectedPreviousApprovalRevision,
				)
			},
		).toResult(source, scope, RetentionAuthorityState.ACTIVE)
		else -> unavailable(
			source,
			scope,
			RetentionAuthorityUnavailableReason.SOURCE_AUTHORITY_UNAVAILABLE,
		)
	}

	private suspend fun applyRevoke(
		source: TrackingSourceComponent,
		scope: RetentionAuthorityScope,
		collectedDataEpoch: Long,
		expectedPreviousApprovalRevision: Long,
		time: SourcePolicyEffectiveTime,
	): RetentionAuthorityResult = when (source) {
		TrackingSourceComponent.STEPS -> database.applyAmbientStepsRetentionDecision(
			AmbientStepsRetentionDecision.Revoke(
				scope.name,
				collectedDataEpoch,
				time.bootId,
				time.elapsedRealtimeNanos,
				time.wallTimeMs,
				expectedPreviousApprovalRevision,
			),
		).toResult(source, scope, RetentionAuthorityState.REVOKED)
		TrackingSourceComponent.WIFI -> database.applyAmbientWifiRetentionDecision(
			AmbientRadioRetentionDecision.Revoke(
				scope.name,
				collectedDataEpoch,
				time.bootId,
				time.elapsedRealtimeNanos,
				time.wallTimeMs,
				expectedPreviousApprovalRevision,
			),
		).toResult(source, scope, RetentionAuthorityState.REVOKED)
		TrackingSourceComponent.CELL -> database.applyAmbientCellRetentionDecision(
			AmbientRadioRetentionDecision.Revoke(
				scope.name,
				collectedDataEpoch,
				time.bootId,
				time.elapsedRealtimeNanos,
				time.wallTimeMs,
				expectedPreviousApprovalRevision,
			),
		).toResult(source, scope, RetentionAuthorityState.REVOKED)
		else -> unavailable(
			source,
			scope,
			RetentionAuthorityUnavailableReason.SOURCE_AUTHORITY_UNAVAILABLE,
		)
	}

	private suspend fun storedAuthority(
		source: TrackingSourceComponent,
		scope: RetentionAuthorityScope,
	): StoredRetentionAuthority? = when (source) {
		TrackingSourceComponent.STEPS ->
			database.ambientStepsFactRevisionDao().latestRetentionAuthority(scope.name)?.let {
				StoredRetentionAuthority(
					it.approvalRevision,
					if (it.isActive) RetentionAuthorityState.ACTIVE else RetentionAuthorityState.REVOKED,
					it.opaquePolicyId,
					it.sourcePolicyRevision,
					it.ambientConsentEpoch,
					it.collectedDataEpoch,
					it.effectiveBootId,
					it.effectiveElapsedRealtimeNanos,
					it.effectiveWallTimeMs,
					AmbientStepsRetentionAuthorityIntegrity.isAuthentic(it),
				)
			}
		TrackingSourceComponent.WIFI ->
			database.ambientWifiFactDao().latestRetentionAuthority(scope.name)?.let {
				StoredRetentionAuthority(
					it.approvalRevision,
					if (it.isActive) RetentionAuthorityState.ACTIVE else RetentionAuthorityState.REVOKED,
					it.opaquePolicyId,
					it.sourcePolicyRevision,
					it.ambientConsentEpoch,
					it.collectedDataEpoch,
					it.effectiveBootId,
					it.effectiveElapsedRealtimeNanos,
					it.effectiveWallTimeMs,
					AmbientWifiRetentionAuthorityIntegrity.isAuthentic(it),
				)
			}
		TrackingSourceComponent.CELL ->
			database.ambientCellFactDao().latestRetentionAuthority(scope.name)?.let {
				StoredRetentionAuthority(
					it.approvalRevision,
					if (it.isActive) RetentionAuthorityState.ACTIVE else RetentionAuthorityState.REVOKED,
					it.opaquePolicyId,
					it.sourcePolicyRevision,
					it.ambientConsentEpoch,
					it.collectedDataEpoch,
					it.effectiveBootId,
					it.effectiveElapsedRealtimeNanos,
					it.effectiveWallTimeMs,
					AmbientCellRetentionAuthorityIntegrity.isAuthentic(it),
				)
			}
		else -> null
	}

	private suspend fun approvedPolicyOrNull(): ApprovedRetentionPolicy? =
		(readApprovedPolicy() as? ApprovedRetentionPolicyRead.Available)?.policy

	private suspend fun safely(
		source: TrackingSourceComponent,
		scope: RetentionAuthorityScope,
		block: suspend () -> RetentionAuthorityResult,
	): RetentionAuthorityResult = try {
		block()
	} catch (cancelled: CancellationException) {
		throw cancelled
	} catch (_: Exception) {
		unavailable(source, scope, RetentionAuthorityUnavailableReason.STORAGE_UNAVAILABLE)
	}

	private data class StoredRetentionAuthority(
		val approvalRevision: Long,
		val state: RetentionAuthorityState,
		val opaquePolicyId: String,
		val sourcePolicyRevision: Long?,
		val ambientConsentEpoch: Long?,
		val collectedDataEpoch: Long,
		val effectiveBootId: String,
		val effectiveElapsedRealtimeNanos: Long,
		val effectiveWallTimeMs: Long,
		val authentic: Boolean,
	)

	private companion object {
		val LIVE_AMBIENT_SOURCES = listOf(
			TrackingSourceComponent.STEPS,
			TrackingSourceComponent.LOCATION,
			TrackingSourceComponent.WIFI,
			TrackingSourceComponent.CELL,
		)
		val DURABLE_LIVE_AMBIENT_SOURCES = setOf(
			TrackingSourceComponent.STEPS,
			TrackingSourceComponent.WIFI,
			TrackingSourceComponent.CELL,
		)
		val PORTABLE_IMPORT_SOURCES = DURABLE_LIVE_AMBIENT_SOURCES
	}
}

private fun AmbientStepsRetentionAuthorityResult.toResult(
	source: TrackingSourceComponent,
	scope: RetentionAuthorityScope,
	state: RetentionAuthorityState,
): RetentionAuthorityResult = when (this) {
	is AmbientStepsRetentionAuthorityResult.Applied ->
		RetentionAuthorityResult.Applied(source, scope, state, approvalRevision)
	is AmbientStepsRetentionAuthorityResult.Unavailable ->
		unavailable(source, scope, reason.toPublicReason())
}

private fun AmbientRadioRetentionAuthorityResult.toResult(
	source: TrackingSourceComponent,
	scope: RetentionAuthorityScope,
	state: RetentionAuthorityState,
): RetentionAuthorityResult = when (this) {
	is AmbientRadioRetentionAuthorityResult.Applied ->
		RetentionAuthorityResult.Applied(source, scope, state, approvalRevision)
	is AmbientRadioRetentionAuthorityResult.Unavailable ->
		unavailable(source, scope, reason.toPublicReason())
}

private fun AmbientStepsRetentionAuthorityUnavailableReason.toPublicReason():
	RetentionAuthorityUnavailableReason = when (this) {
	AmbientStepsRetentionAuthorityUnavailableReason.COLLECTED_DATA_EPOCH_CHANGED ->
		RetentionAuthorityUnavailableReason.COLLECTED_DATA_EPOCH_CHANGED
	AmbientStepsRetentionAuthorityUnavailableReason.LIVE_POLICY_AUTHORITY_MISMATCH ->
		RetentionAuthorityUnavailableReason.PURPOSE_AUTHORITY_UNAVAILABLE
	AmbientStepsRetentionAuthorityUnavailableReason.NO_EXISTING_AUTHORITY ->
		RetentionAuthorityUnavailableReason.RETENTION_AUTHORITY_UNAVAILABLE
	AmbientStepsRetentionAuthorityUnavailableReason.STALE_APPROVAL_REVISION ->
		RetentionAuthorityUnavailableReason.STALE_APPROVAL_REVISION
	AmbientStepsRetentionAuthorityUnavailableReason.APPROVAL_REVISION_EXHAUSTED ->
		RetentionAuthorityUnavailableReason.APPROVAL_REVISION_EXHAUSTED
	AmbientStepsRetentionAuthorityUnavailableReason.INTEGRITY_MISMATCH ->
		RetentionAuthorityUnavailableReason.INTEGRITY_MISMATCH
	AmbientStepsRetentionAuthorityUnavailableReason.EFFECTIVE_TIME_INVALID ->
		RetentionAuthorityUnavailableReason.EFFECTIVE_TIME_INVALID
}

private fun AmbientRadioRetentionAuthorityUnavailableReason.toPublicReason():
	RetentionAuthorityUnavailableReason = when (this) {
	AmbientRadioRetentionAuthorityUnavailableReason.COLLECTED_DATA_EPOCH_CHANGED ->
		RetentionAuthorityUnavailableReason.COLLECTED_DATA_EPOCH_CHANGED
	AmbientRadioRetentionAuthorityUnavailableReason.LIVE_POLICY_AUTHORITY_MISMATCH ->
		RetentionAuthorityUnavailableReason.PURPOSE_AUTHORITY_UNAVAILABLE
	AmbientRadioRetentionAuthorityUnavailableReason.NO_EXISTING_AUTHORITY ->
		RetentionAuthorityUnavailableReason.RETENTION_AUTHORITY_UNAVAILABLE
	AmbientRadioRetentionAuthorityUnavailableReason.STALE_APPROVAL_REVISION ->
		RetentionAuthorityUnavailableReason.STALE_APPROVAL_REVISION
	AmbientRadioRetentionAuthorityUnavailableReason.APPROVAL_REVISION_EXHAUSTED ->
		RetentionAuthorityUnavailableReason.APPROVAL_REVISION_EXHAUSTED
	AmbientRadioRetentionAuthorityUnavailableReason.INTEGRITY_MISMATCH ->
		RetentionAuthorityUnavailableReason.INTEGRITY_MISMATCH
	AmbientRadioRetentionAuthorityUnavailableReason.EFFECTIVE_TIME_INVALID ->
		RetentionAuthorityUnavailableReason.EFFECTIVE_TIME_INVALID
}

private fun unavailable(
	source: TrackingSourceComponent,
	scope: RetentionAuthorityScope,
	reason: RetentionAuthorityUnavailableReason,
) = RetentionAuthorityResult.Unavailable(source, scope, reason)
