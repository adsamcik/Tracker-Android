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
	SOURCE_AUTHORITY_UNAVAILABLE,
	STORAGE_UNAVAILABLE,
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
	) : CurrentRetentionAuthority

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
}

fun interface LocationPassiveRetentionAuthority {
	/** Default-unavailable seam for the future passive Location source owner. */
	suspend fun reconcilePassiveLocationRetention(): RetentionAuthorityResult
}

interface RetentionAuthorityProducer :
	RetentionAuthorityReader,
	LocationPassiveRetentionAuthority {
	suspend fun reconcileCurrentSettings(): List<RetentionAuthorityResult>
	suspend fun reconcileLiveAmbient(source: TrackingSourceComponent): RetentionAuthorityResult
	suspend fun approvePortableImport(source: TrackingSourceComponent): RetentionAuthorityResult
	suspend fun revokePortableImport(source: TrackingSourceComponent): RetentionAuthorityResult
}

object UnavailableRetentionAuthorityProducer : RetentionAuthorityProducer {
	override suspend fun reconcileCurrentSettings(): List<RetentionAuthorityResult> = emptyList()

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
	)

	private val mutex = Mutex()

	override suspend fun reconcileCurrentSettings(): List<RetentionAuthorityResult> =
		mutex.withLock {
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
			CurrentRetentionAuthority.Approved(
				stored.opaquePolicyId,
				stored.approvalRevision,
			)
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: Exception) {
			CurrentRetentionAuthority.Unavailable(
				RetentionAuthorityUnavailableReason.STORAGE_UNAVAILABLE,
			)
		}
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
			return RetentionAuthorityResult.Unchanged(
				source,
				scope,
				RetentionAuthorityState.ACTIVE,
				current.approvalRevision,
			)
		}
		beforeDecisionApply(source, scope)
		val time = effectiveTimeProvider.now()
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
		val time = effectiveTimeProvider.now()
		return applyRevoke(source, scope, lifecycle.epoch, current.approvalRevision, time)
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
}

private fun unavailable(
	source: TrackingSourceComponent,
	scope: RetentionAuthorityScope,
	reason: RetentionAuthorityUnavailableReason,
) = RetentionAuthorityResult.Unavailable(source, scope, reason)
