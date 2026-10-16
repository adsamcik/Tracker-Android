package com.adsamcik.tracker.shared.preferences.retention

import com.adsamcik.tracker.shared.base.database.AmbientRadioRetentionAuthorityResult
import com.adsamcik.tracker.shared.base.database.AmbientRadioRetentionAuthorityUnavailableReason
import com.adsamcik.tracker.shared.base.database.AmbientRadioRetentionDecision
import com.adsamcik.tracker.shared.base.database.AmbientStepsRetentionAuthorityResult
import com.adsamcik.tracker.shared.base.database.AmbientStepsRetentionAuthorityUnavailableReason
import com.adsamcik.tracker.shared.base.database.AmbientStepsRetentionDecision
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.SourceEvidenceRetentionBootstrapResult
import com.adsamcik.tracker.shared.base.database.applyAmbientCellRetentionDecision
import com.adsamcik.tracker.shared.base.database.applyAmbientStepsRetentionDecision
import com.adsamcik.tracker.shared.base.database.applyAmbientLocationRetentionDecision
import com.adsamcik.tracker.shared.base.database.data.AmbientLocationRetentionAuthorityIntegrity
import com.adsamcik.tracker.shared.base.database.applyAmbientWifiRetentionDecision
import com.adsamcik.tracker.shared.base.database.bootstrapSourceEvidenceForRetention
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

/** Separates live all-day collection from user-requested portable imports. */
enum class RetentionAuthorityScope {
	LIVE_AMBIENT,
	PORTABLE_IMPORT,
}

/** Whether this source and purpose currently have active retention approval. */
enum class RetentionAuthorityState {
	ACTIVE,
	REVOKED,
}

/** Payload-free authority, lifecycle, integrity or storage failures requiring recovery. */
enum class RetentionAuthorityUnavailableReason {
	RETENTION_POLICY_UNAVAILABLE,
	PURPOSE_AUTHORITY_UNAVAILABLE,
	RETENTION_AUTHORITY_UNAVAILABLE,
	COLLECTED_DATA_EPOCH_CHANGED,
	RETAINED_FROM_CHANGED,
	STALE_APPROVAL_REVISION,
	APPROVAL_REVISION_EXHAUSTED,
	INTEGRITY_MISMATCH,
	STALE_CONFIGURATION_GENERATION,
	EFFECTIVE_TIME_INVALID,
	SOURCE_AUTHORITY_UNAVAILABLE,
	STORAGE_UNAVAILABLE,
	COMMIT_ACKNOWLEDGEMENT_UNKNOWN,
	RETENTION_FLOOR_SETTLEMENT_PENDING,
}

/** Preparation or publication result for one exact staged retention configuration. */
sealed interface RetentionConfigurationApprovalResult {
	/** The exact retention policy has completed preparation before publication. */
	data class Prepared(val policy: ApprovedRetentionPolicy) :
		RetentionConfigurationApprovalResult

	/** Retention approval for the exact configuration, including its stable revision and effective time. */
	data class Approved(val policy: ApprovedRetentionPolicy) :
		RetentionConfigurationApprovalResult

	/** The same staged retention policy already completed approval. */
	data class AlreadyApproved(val policy: ApprovedRetentionPolicy) :
		RetentionConfigurationApprovalResult

	/** The requested authority could not be established; no recording or import permission is implied. */
	data class Unavailable(val reason: RetentionAuthorityUnavailableReason) :
		RetentionConfigurationApprovalResult
}

/** Per-source and per-purpose result of retention reconciliation. */
sealed interface RetentionAuthorityResult {
	val source: TrackingSourceComponent
	val scope: RetentionAuthorityScope

	/** A new active or revoked state was persisted at the returned approval revision. */
	data class Applied(
		override val source: TrackingSourceComponent,
		override val scope: RetentionAuthorityScope,
		val state: RetentionAuthorityState,
		val approvalRevision: Long,
	) : RetentionAuthorityResult

	/** Existing approval state is unchanged; a missing revision does not invent approval. */
	data class Unchanged(
		override val source: TrackingSourceComponent,
		override val scope: RetentionAuthorityScope,
		val state: RetentionAuthorityState,
		val approvalRevision: Long?,
	) : RetentionAuthorityResult

	/** The requested authority could not be established; no recording or import permission is implied. */
	data class Unavailable(
		override val source: TrackingSourceComponent,
		override val scope: RetentionAuthorityScope,
		val reason: RetentionAuthorityUnavailableReason,
	) : RetentionAuthorityResult
}

/** Per-source and per-purpose result of retention reconciliation. */
/** True for persisted active approvals; unavailable and revoked outcomes never authorize collection. */
fun RetentionAuthorityResult.isActiveApproval(): Boolean = when (this) {
	is RetentionAuthorityResult.Applied -> state == RetentionAuthorityState.ACTIVE
	is RetentionAuthorityResult.Unchanged -> state == RetentionAuthorityState.ACTIVE
	is RetentionAuthorityResult.Unavailable -> false
}

/** Read-only exact authority used when admitting new all-day data. */
sealed interface CurrentRetentionAuthority {
	/** Retention approval for the exact configuration, including its stable revision and effective time. */
	data class Approved(
		val opaquePolicyId: String,
		val approvalRevision: Long,
		val effectiveBootId: String,
		val effectiveElapsedRealtimeNanos: Long,
		val effectiveWallTimeMs: Long,
		val collectedDataEpoch: Long = 0L,
		val retainedFromMs: Long? = null,
	) : CurrentRetentionAuthority {
		init {
			require(opaquePolicyId.isNotBlank())
			require(approvalRevision > 0L)
			require(effectiveBootId.isNotBlank())
			require(effectiveElapsedRealtimeNanos >= 0L)
			require(effectiveWallTimeMs >= 0L)
			require(collectedDataEpoch >= 0L)
			require(retainedFromMs == null || retainedFromMs >= 0L)
		}
	}

	/** The requested authority could not be established; no recording or import permission is implied. */
	data class Unavailable(val reason: RetentionAuthorityUnavailableReason) :
		CurrentRetentionAuthority
}

/** Reads exact all-day retention authority without registering providers or changing source choices. */
interface RetentionAuthorityReader {
	/** Reads authority only for the supplied source policy, consent epoch, data epoch and retention floor. */
	suspend fun currentLiveAmbient(
		source: TrackingSourceComponent,
		expectedSourcePolicyRevision: Long,
		expectedAmbientConsentEpoch: Long,
		expectedCollectedDataEpoch: Long,
		expectedRetainedFromMs: Long? = null,
	): CurrentRetentionAuthority

	/** Allows only the named durable floor settlement to read authority while its journal is open. */
	suspend fun currentLiveAmbientForSettlement(
		source: TrackingSourceComponent,
		expectedSourcePolicyRevision: Long,
		expectedAmbientConsentEpoch: Long,
		expectedCollectedDataEpoch: Long,
		expectedRetainedFromMs: Long?,
		settlementOperationId: String,
	): CurrentRetentionAuthority {
		require(settlementOperationId.isNotBlank())
		return currentLiveAmbient(
			source,
			expectedSourcePolicyRevision,
			expectedAmbientConsentEpoch,
			expectedCollectedDataEpoch,
			expectedRetainedFromMs,
		)
	}

	/** Fail-closed broker seam for an exact persisted retention identity in the current boot. */
	suspend fun isCurrentLiveAmbientAt(
		source: TrackingSourceComponent,
		expectedSourcePolicyRevision: Long,
		expectedAmbientConsentEpoch: Long,
		expectedCollectedDataEpoch: Long,
		expectedRetainedFromMs: Long? = null,
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
			expectedRetainedFromMs,
		)
		return authority is CurrentRetentionAuthority.Approved &&
			authority.opaquePolicyId == expectedOpaquePolicyId &&
			authority.approvalRevision == expectedApprovalRevision &&
			authority.collectedDataEpoch == expectedCollectedDataEpoch &&
			authority.retainedFromMs == expectedRetainedFromMs &&
			authority.effectiveBootId == currentBootId &&
			authority.effectiveElapsedRealtimeNanos <= currentElapsedRealtimeNanos &&
			authority.effectiveWallTimeMs <= currentWallTimeMs
	}

	/** Exact-current predicate for the named durable retention-floor settlement. */
	suspend fun isCurrentLiveAmbientForSettlementAt(
		source: TrackingSourceComponent,
		expectedSourcePolicyRevision: Long,
		expectedAmbientConsentEpoch: Long,
		expectedCollectedDataEpoch: Long,
		expectedRetainedFromMs: Long?,
		expectedOpaquePolicyId: String,
		expectedApprovalRevision: Long,
		currentBootId: String,
		currentElapsedRealtimeNanos: Long,
		currentWallTimeMs: Long,
		settlementOperationId: String,
	): Boolean {
		require(settlementOperationId.isNotBlank())
		val authority = currentLiveAmbientForSettlement(
			source,
			expectedSourcePolicyRevision,
			expectedAmbientConsentEpoch,
			expectedCollectedDataEpoch,
			expectedRetainedFromMs,
			settlementOperationId,
		)
		return authority is CurrentRetentionAuthority.Approved &&
			authority.opaquePolicyId == expectedOpaquePolicyId &&
			authority.approvalRevision == expectedApprovalRevision &&
			authority.collectedDataEpoch == expectedCollectedDataEpoch &&
			authority.retainedFromMs == expectedRetainedFromMs &&
			authority.effectiveBootId == currentBootId &&
			authority.effectiveElapsedRealtimeNanos <= currentElapsedRealtimeNanos &&
			authority.effectiveWallTimeMs <= currentWallTimeMs
	}
}

/** Reconciles passive Location retention through the existing shared authority producer. */
fun interface LocationPassiveRetentionAuthority {
	/** Default-unavailable seam for the future passive Location source owner. */
	suspend fun reconcilePassiveLocationRetention(): RetentionAuthorityResult
}

/** Serializes retention approval and revocation across independently owned sources and purposes. */
interface RetentionAuthorityProducer :
	RetentionAuthorityReader,
	LocationPassiveRetentionAuthority {
	/** Reconciles every supported source and purpose against current settings, retaining individual outcomes. */
	suspend fun reconcileCurrentSettings(): List<RetentionAuthorityResult>
	/** Reconciles every supported source and purpose against current settings, retaining individual outcomes. */
	suspend fun reconcileCurrentSettings(
		permit: RetentionAuthorityOperationPermit,
	): List<RetentionAuthorityResult> {
		permit.requireActive()
		return reconcileCurrentSettings()
	}
	/** Prepares the exact staged configuration generation before publication. */
	suspend fun preparePendingConfiguration(
		expectedConfigurationGeneration: Long,
	): RetentionConfigurationApprovalResult
	/** Approves the matching staged configuration after required lifecycle preparation. */
	suspend fun reconcilePendingConfiguration(
		expectedConfigurationGeneration: Long? = null,
	): RetentionConfigurationApprovalResult
	/** Reconciles all-day retention for one selected source without granting another purpose. */
	suspend fun reconcileLiveAmbient(source: TrackingSourceComponent): RetentionAuthorityResult
	/** Allows only the named durable floor settlement to reissue provider authority. */
	suspend fun reconcileLiveAmbientForSettlement(
		source: TrackingSourceComponent,
		settlementOperationId: String,
	): RetentionAuthorityResult {
		require(settlementOperationId.isNotBlank())
		return reconcileLiveAmbient(source)
	}
	/** Establishes retention for an explicit user import of this source. */
	suspend fun approvePortableImport(source: TrackingSourceComponent): RetentionAuthorityResult
	/** Revokes future portable imports for this source without granting or changing live collection. */
	suspend fun revokePortableImport(source: TrackingSourceComponent): RetentionAuthorityResult
}

/** Serializes retention approval and revocation across independently owned sources and purposes. */
/** Reconciles settings using the existing permit, optionally for the named pending floor settlement. */
suspend fun RetentionAuthorityProducer.reconcileCurrentSettingsWithPermit(
	permit: RetentionAuthorityOperationPermit,
	settlementOperationId: String? = null,
): List<RetentionAuthorityResult> {
	permit.requireActive()
	return if (this is DefaultRetentionAuthorityProducer) {
		reconcileCurrentSettingsForSettlement(permit, settlementOperationId)
	} else {
		reconcileCurrentSettings()
	}
}

/** Default unavailable implementation that never manufactures retention approval. */
object UnavailableRetentionAuthorityProducer : RetentionAuthorityProducer {
	/** Reconciles every supported source and purpose against current settings, retaining individual outcomes. */
	override suspend fun reconcileCurrentSettings(): List<RetentionAuthorityResult> = listOf(
		TrackingSourceComponent.STEPS,
		TrackingSourceComponent.LOCATION,
		TrackingSourceComponent.WIFI,
		TrackingSourceComponent.CELL,
	).map { source ->
		unavailable(
			source,
			RetentionAuthorityScope.LIVE_AMBIENT,
			RetentionAuthorityUnavailableReason.RETENTION_POLICY_UNAVAILABLE,
		)
	}

	/** Prepares the exact staged configuration generation before publication. */
	override suspend fun preparePendingConfiguration(
		expectedConfigurationGeneration: Long,
	): RetentionConfigurationApprovalResult = RetentionConfigurationApprovalResult.Unavailable(
		RetentionAuthorityUnavailableReason.RETENTION_POLICY_UNAVAILABLE,
	)

	/** Approves the matching staged configuration after required lifecycle preparation. */
	override suspend fun reconcilePendingConfiguration(
		expectedConfigurationGeneration: Long?,
	): RetentionConfigurationApprovalResult = RetentionConfigurationApprovalResult.Unavailable(
		RetentionAuthorityUnavailableReason.RETENTION_POLICY_UNAVAILABLE,
	)

	/** Reconciles all-day retention for one selected source without granting another purpose. */
	override suspend fun reconcileLiveAmbient(
		source: TrackingSourceComponent,
	): RetentionAuthorityResult = unavailable(
		source,
		RetentionAuthorityScope.LIVE_AMBIENT,
		RetentionAuthorityUnavailableReason.RETENTION_POLICY_UNAVAILABLE,
	)

	/** Establishes retention for an explicit user import of this source. */
	override suspend fun approvePortableImport(
		source: TrackingSourceComponent,
	): RetentionAuthorityResult = unavailable(
		source,
		RetentionAuthorityScope.PORTABLE_IMPORT,
		RetentionAuthorityUnavailableReason.RETENTION_POLICY_UNAVAILABLE,
	)

	/** Revokes future portable imports for this source without granting or changing live collection. */
	override suspend fun revokePortableImport(
		source: TrackingSourceComponent,
	): RetentionAuthorityResult = unavailable(
		source,
		RetentionAuthorityScope.PORTABLE_IMPORT,
		RetentionAuthorityUnavailableReason.RETENTION_POLICY_UNAVAILABLE,
	)

	override suspend fun reconcilePassiveLocationRetention(): RetentionAuthorityResult =
		reconcileLiveAmbient(TrackingSourceComponent.LOCATION)

	/** Reads authority only for the supplied source policy, consent epoch, data epoch and retention floor. */
	override suspend fun currentLiveAmbient(
		source: TrackingSourceComponent,
		expectedSourcePolicyRevision: Long,
		expectedAmbientConsentEpoch: Long,
		expectedCollectedDataEpoch: Long,
		expectedRetainedFromMs: Long?,
	): CurrentRetentionAuthority = CurrentRetentionAuthority.Unavailable(
		RetentionAuthorityUnavailableReason.RETENTION_POLICY_UNAVAILABLE,
	)
}

/** Coordinates existing Room authority and DataStore policy under one retention operation owner. */
@Suppress("LargeClass", "TooManyFunctions") // One mutex/permit owns its complete source and configuration phases.
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
	private val operationLease: RetentionAuthorityOperationLease =
		RetentionAuthorityOperationLease(),
) : RetentionAuthorityProducer {
	constructor(
		database: AppDatabase,
		sourcePolicyRepository: SourcePolicyRepository,
		retentionConfigStore: RetentionConfigStore,
		collectedDataLifecycleStore: CollectedDataLifecycleStore,
		effectiveTimeProvider: SourcePolicyEffectiveTimeProvider,
		operationLease: RetentionAuthorityOperationLease = RetentionAuthorityOperationLease(),
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
		operationLease = operationLease,
	)

	private val mutex = Mutex()

	private suspend fun <T> withSerializedOperation(
		onCommitUnknown: (RetentionAuthorityDataStoreCommitUnknownException) -> T,
		operation: suspend (RetentionAuthorityOperationPermit) -> T,
	): T = try {
		operationLease.withPermit { permit ->
			mutex.withLock {
				permit.validate()
				operation(permit).also { permit.validate() }
			}
		}
	} catch (unknown: RetentionAuthorityDataStoreCommitUnknownException) {
		onCommitUnknown(unknown)
	}

	/** Reconciles every supported source and purpose against current settings, retaining individual outcomes. */
	override suspend fun reconcileCurrentSettings(): List<RetentionAuthorityResult> =
		withSerializedOperation(
			onCommitUnknown = {
				unavailableReconciliationResults(
					RetentionAuthorityUnavailableReason.COMMIT_ACKNOWLEDGEMENT_UNKNOWN,
				)
			},
			operation = { permit -> reconcileCurrentSettingsLocked(permit) },
		)

	/** Reconciles every supported source and purpose against current settings, retaining individual outcomes. */
	override suspend fun reconcileCurrentSettings(
		permit: RetentionAuthorityOperationPermit,
	): List<RetentionAuthorityResult> =
		reconcileCurrentSettingsForSettlement(permit, settlementOperationId = null)

	internal suspend fun reconcileCurrentSettingsForSettlement(
		permit: RetentionAuthorityOperationPermit,
		settlementOperationId: String?,
	): List<RetentionAuthorityResult> {
		operationLease.requireOwned(permit)
		return mutex.withLock {
			operationLease.requireOwned(permit)
			reconcileCurrentSettingsLocked(permit, settlementOperationId).also {
				operationLease.requireOwned(permit)
			}
		}
	}

	private suspend fun reconcileCurrentSettingsLocked(
		permit: RetentionAuthorityOperationPermit,
		settlementOperationId: String? = null,
	): List<RetentionAuthorityResult> {
		exactSourceEvidenceBootstrapFailure(permit, settlementOperationId)?.let { reason ->
			return unavailableReconciliationResults(reason)
		}
		val pending = readPendingPolicyCandidate() as? RetentionPolicyCandidateRead.Available
		if (pending != null) {
			val prepared = preparePendingConfigurationLocked(
				pending.policy.configurationGeneration,
				permit,
			)
			if (prepared is RetentionConfigurationApprovalResult.Unavailable) {
				return unavailableReconciliationResults(prepared.reason)
			}
			val approved = reconcilePendingConfigurationLocked(
				pending.policy.configurationGeneration,
				permit,
			)
			if (approved is RetentionConfigurationApprovalResult.Unavailable) {
				return unavailableReconciliationResults(approved.reason)
			}
		}
		val results = mutableListOf<RetentionAuthorityResult>()
		for (source in LIVE_AMBIENT_SOURCES) {
			results += safely(source, RetentionAuthorityScope.LIVE_AMBIENT) {
				reconcileLiveAmbientLocked(source, permit)
			}
		}
		for (source in PORTABLE_IMPORT_SOURCES) {
			results += safely(source, RetentionAuthorityScope.PORTABLE_IMPORT) {
				reconcileExistingPortableImportLocked(source, permit)
			}
		}
		return results
	}

	private suspend fun exactSourceEvidenceBootstrapFailure(
		operationPermit: RetentionAuthorityOperationPermit,
		settlementOperationId: String? = null,
	): RetentionAuthorityUnavailableReason? = try {
		operationPermit.validate()
		val pendingSettlement = database.collectedDataDeletionOperationDao()
			.activeRetentionFloorSettlement()
		if (
			pendingSettlement != null &&
			pendingSettlement.operationId != settlementOperationId
		) {
			return RetentionAuthorityUnavailableReason.RETENTION_FLOOR_SETTLEMENT_PENDING
		}
		val lifecycle = readLifecycle()
		operationPermit.validate()
		val updatedAtMs = effectiveTimeProvider.now().wallTimeMs
		operationPermit.validate()
		when (
			operationPermit.commitRoomMutation {
				database.bootstrapSourceEvidenceForRetention(
					expectedCollectedDataEpoch = lifecycle.epoch,
					expectedRetainedFromMs = lifecycle.retainedFromMs,
					updatedAtMs = updatedAtMs,
				)
			}
		) {
			is SourceEvidenceRetentionBootstrapResult.Ready -> null
			SourceEvidenceRetentionBootstrapResult.CollectedDataEpochChanged ->
				RetentionAuthorityUnavailableReason.COLLECTED_DATA_EPOCH_CHANGED
			SourceEvidenceRetentionBootstrapResult.RetainedFromChanged ->
				RetentionAuthorityUnavailableReason.RETAINED_FROM_CHANGED
			SourceEvidenceRetentionBootstrapResult.ConflictingEvidence,
			SourceEvidenceRetentionBootstrapResult.InvalidState,
			-> RetentionAuthorityUnavailableReason.INTEGRITY_MISMATCH
		}.also { operationPermit.validate() }
	} catch (cancelled: CancellationException) {
		throw cancelled
	} catch (_: Exception) {
		RetentionAuthorityUnavailableReason.STORAGE_UNAVAILABLE
	}

	private fun unavailableReconciliationResults(
		reason: RetentionAuthorityUnavailableReason,
	): List<RetentionAuthorityResult> =
		LIVE_AMBIENT_SOURCES.map { source ->
			unavailable(source, RetentionAuthorityScope.LIVE_AMBIENT, reason)
		} + PORTABLE_IMPORT_SOURCES.map { source ->
			unavailable(source, RetentionAuthorityScope.PORTABLE_IMPORT, reason)
		}

	/** Prepares the exact staged configuration generation before publication. */
	override suspend fun preparePendingConfiguration(
		expectedConfigurationGeneration: Long,
	): RetentionConfigurationApprovalResult = withSerializedOperation(
		onCommitUnknown = {
			RetentionConfigurationApprovalResult.Unavailable(
				RetentionAuthorityUnavailableReason.COMMIT_ACKNOWLEDGEMENT_UNKNOWN,
			)
		},
	) { permit ->
			try {
				exactSourceEvidenceBootstrapFailure(permit)?.let { reason ->
					return@withSerializedOperation RetentionConfigurationApprovalResult.Unavailable(reason)
				}
				preparePendingConfigurationLocked(expectedConfigurationGeneration, permit)
			} catch (cancelled: CancellationException) {
				throw cancelled
			} catch (_: Exception) {
				RetentionConfigurationApprovalResult.Unavailable(
					RetentionAuthorityUnavailableReason.STORAGE_UNAVAILABLE,
				)
			}
		}

	/** Approves the matching staged configuration after required lifecycle preparation. */
	override suspend fun reconcilePendingConfiguration(
		expectedConfigurationGeneration: Long?,
	): RetentionConfigurationApprovalResult = withSerializedOperation(
		onCommitUnknown = {
			RetentionConfigurationApprovalResult.Unavailable(
				RetentionAuthorityUnavailableReason.COMMIT_ACKNOWLEDGEMENT_UNKNOWN,
			)
		},
	) { permit ->
			try {
				exactSourceEvidenceBootstrapFailure(permit)?.let { reason ->
					return@withSerializedOperation RetentionConfigurationApprovalResult.Unavailable(reason)
				}
				reconcilePendingConfigurationLocked(expectedConfigurationGeneration, permit)
			} catch (cancelled: CancellationException) {
				throw cancelled
			} catch (_: Exception) {
				RetentionConfigurationApprovalResult.Unavailable(
					RetentionAuthorityUnavailableReason.STORAGE_UNAVAILABLE,
				)
			}
		}

	/** Reconciles all-day retention for one selected source without granting another purpose. */
	override suspend fun reconcileLiveAmbient(
		source: TrackingSourceComponent,
	): RetentionAuthorityResult = reconcileLiveAmbientWithSettlement(
		source = source,
		settlementOperationId = null,
	)

	override suspend fun reconcileLiveAmbientForSettlement(
		source: TrackingSourceComponent,
		settlementOperationId: String,
	): RetentionAuthorityResult {
		require(settlementOperationId.isNotBlank())
		return reconcileLiveAmbientWithSettlement(source, settlementOperationId)
	}

	private suspend fun reconcileLiveAmbientWithSettlement(
		source: TrackingSourceComponent,
		settlementOperationId: String?,
	): RetentionAuthorityResult = withSerializedOperation(
		onCommitUnknown = {
			unavailable(
				source,
				RetentionAuthorityScope.LIVE_AMBIENT,
				RetentionAuthorityUnavailableReason.COMMIT_ACKNOWLEDGEMENT_UNKNOWN,
			)
		},
	) { permit ->
			safely(source, RetentionAuthorityScope.LIVE_AMBIENT) {
				exactSourceEvidenceBootstrapFailure(permit, settlementOperationId)?.let { reason ->
					return@safely unavailable(source, RetentionAuthorityScope.LIVE_AMBIENT, reason)
				}
				reconcileLiveAmbientLocked(source, permit)
			}
		}

	/** Establishes retention for an explicit user import of this source. */
	override suspend fun approvePortableImport(
		source: TrackingSourceComponent,
	): RetentionAuthorityResult = withSerializedOperation(
		onCommitUnknown = {
			unavailable(
				source,
				RetentionAuthorityScope.PORTABLE_IMPORT,
				RetentionAuthorityUnavailableReason.COMMIT_ACKNOWLEDGEMENT_UNKNOWN,
			)
		},
	) { permit ->
			safely(source, RetentionAuthorityScope.PORTABLE_IMPORT) {
				exactSourceEvidenceBootstrapFailure(permit)?.let { reason ->
					return@safely unavailable(source, RetentionAuthorityScope.PORTABLE_IMPORT, reason)
				}
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
					operationPermit = permit,
				)
			}
		}

	/** Revokes future portable imports for this source without granting or changing live collection. */
	override suspend fun revokePortableImport(
		source: TrackingSourceComponent,
	): RetentionAuthorityResult = withSerializedOperation(
		onCommitUnknown = {
			unavailable(
				source,
				RetentionAuthorityScope.PORTABLE_IMPORT,
				RetentionAuthorityUnavailableReason.COMMIT_ACKNOWLEDGEMENT_UNKNOWN,
			)
		},
	) { permit ->
			safely(source, RetentionAuthorityScope.PORTABLE_IMPORT) {
				exactSourceEvidenceBootstrapFailure(permit)?.let { reason ->
					return@safely unavailable(source, RetentionAuthorityScope.PORTABLE_IMPORT, reason)
				}
				revoke(
					source,
					RetentionAuthorityScope.PORTABLE_IMPORT,
					readLifecycle(),
					permit,
				)
			}
		}

	override suspend fun reconcilePassiveLocationRetention(): RetentionAuthorityResult =
		reconcileLiveAmbient(TrackingSourceComponent.LOCATION)

	/** Reads authority only for the supplied source policy, consent epoch, data epoch and retention floor. */
	override suspend fun currentLiveAmbient(
		source: TrackingSourceComponent,
		expectedSourcePolicyRevision: Long,
		expectedAmbientConsentEpoch: Long,
		expectedCollectedDataEpoch: Long,
		expectedRetainedFromMs: Long?,
	): CurrentRetentionAuthority = currentLiveAmbientWithSettlement(
		source = source,
		expectedSourcePolicyRevision = expectedSourcePolicyRevision,
		expectedAmbientConsentEpoch = expectedAmbientConsentEpoch,
		expectedCollectedDataEpoch = expectedCollectedDataEpoch,
		expectedRetainedFromMs = expectedRetainedFromMs,
		settlementOperationId = null,
	)

	override suspend fun currentLiveAmbientForSettlement(
		source: TrackingSourceComponent,
		expectedSourcePolicyRevision: Long,
		expectedAmbientConsentEpoch: Long,
		expectedCollectedDataEpoch: Long,
		expectedRetainedFromMs: Long?,
		settlementOperationId: String,
	): CurrentRetentionAuthority {
		require(settlementOperationId.isNotBlank())
		return currentLiveAmbientWithSettlement(
			source,
			expectedSourcePolicyRevision,
			expectedAmbientConsentEpoch,
			expectedCollectedDataEpoch,
			expectedRetainedFromMs,
			settlementOperationId,
		)
	}

	private suspend fun currentLiveAmbientWithSettlement(
		source: TrackingSourceComponent,
		expectedSourcePolicyRevision: Long,
		expectedAmbientConsentEpoch: Long,
		expectedCollectedDataEpoch: Long,
		expectedRetainedFromMs: Long?,
		settlementOperationId: String?,
	): CurrentRetentionAuthority = withSerializedOperation(
		onCommitUnknown = {
			CurrentRetentionAuthority.Unavailable(
				RetentionAuthorityUnavailableReason.COMMIT_ACKNOWLEDGEMENT_UNKNOWN,
			)
		},
	) { permit ->
		try {
			exactSourceEvidenceBootstrapFailure(permit, settlementOperationId)?.let { reason ->
				return@withSerializedOperation CurrentRetentionAuthority.Unavailable(reason)
			}
			permit.validate()
			if (source !in DURABLE_LIVE_AMBIENT_SOURCES) {
				return@withSerializedOperation CurrentRetentionAuthority.Unavailable(
					RetentionAuthorityUnavailableReason.SOURCE_AUTHORITY_UNAVAILABLE,
				)
			}
			val approval = approvedPolicyOrNull()
				?: return@withSerializedOperation CurrentRetentionAuthority.Unavailable(
					RetentionAuthorityUnavailableReason.RETENTION_POLICY_UNAVAILABLE,
				)
			permit.validate()
			readApprovedLiveAmbient(
				source, approval, expectedSourcePolicyRevision, expectedAmbientConsentEpoch,
				expectedCollectedDataEpoch, expectedRetainedFromMs, permit,
			)
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: Exception) {
			CurrentRetentionAuthority.Unavailable(
				RetentionAuthorityUnavailableReason.STORAGE_UNAVAILABLE,
			)
		}
	}

	// Use the already captured lifecycle/approval inputs instead of adding a separate authority carrier.
	@Suppress("LongParameterList")
	private suspend fun readApprovedLiveAmbient(
		source: TrackingSourceComponent,
		approval: ApprovedRetentionPolicy,
		expectedSourcePolicyRevision: Long,
		expectedAmbientConsentEpoch: Long,
		expectedCollectedDataEpoch: Long,
		expectedRetainedFromMs: Long?,
		operationPermit: RetentionAuthorityOperationPermit,
	): CurrentRetentionAuthority {
		liveLifecycleFailure(expectedCollectedDataEpoch, expectedRetainedFromMs, operationPermit)?.let { reason ->
			return CurrentRetentionAuthority.Unavailable(reason)
		}
		val authority = sourcePolicyRepository.currentState()
		val snapshot = (authority as? SourcePolicyAuthorityState.Active)?.snapshot
			?: return CurrentRetentionAuthority.Unavailable(
				RetentionAuthorityUnavailableReason.PURPOSE_AUTHORITY_UNAVAILABLE,
			)
		val policy = snapshot[source]
		if (
			snapshot.revision != expectedSourcePolicyRevision ||
			policy.ambientConsentEpoch != expectedAmbientConsentEpoch ||
			!policy.ambientPersistenceEligible
		) {
			return CurrentRetentionAuthority.Unavailable(
				RetentionAuthorityUnavailableReason.PURPOSE_AUTHORITY_UNAVAILABLE,
			)
		}
		return storedLiveAmbientAuthority(
			source, approval, expectedSourcePolicyRevision, expectedAmbientConsentEpoch,
			expectedCollectedDataEpoch, expectedRetainedFromMs,
		)
	}

	@Suppress("ReturnCount") // Preserve ordered lifecycle and Room epoch/floor mismatch outcomes.
	private suspend fun liveLifecycleFailure(
		expectedCollectedDataEpoch: Long,
		expectedRetainedFromMs: Long?,
		operationPermit: RetentionAuthorityOperationPermit,
	): RetentionAuthorityUnavailableReason? {
		val lifecycle = readLifecycle()
		operationPermit.validate()
		if (lifecycle.epoch != expectedCollectedDataEpoch) {
			return RetentionAuthorityUnavailableReason.COLLECTED_DATA_EPOCH_CHANGED
		}
		if (lifecycle.retainedFromMs != expectedRetainedFromMs) {
			return RetentionAuthorityUnavailableReason.RETAINED_FROM_CHANGED
		}
		val evidence = database.sourceEvidenceStateDao().get()
		if (evidence?.collectedDataEpoch != expectedCollectedDataEpoch) {
			return RetentionAuthorityUnavailableReason.COLLECTED_DATA_EPOCH_CHANGED
		}
		if (evidence.retainedFromMs != expectedRetainedFromMs) {
			return RetentionAuthorityUnavailableReason.RETAINED_FROM_CHANGED
		}
		return null
	}

	@Suppress("ReturnCount") // Check stored identity before sampling and validating its effective clock.
	private suspend fun storedLiveAmbientAuthority(
		source: TrackingSourceComponent,
		approval: ApprovedRetentionPolicy,
		expectedSourcePolicyRevision: Long,
		expectedAmbientConsentEpoch: Long,
		expectedCollectedDataEpoch: Long,
		expectedRetainedFromMs: Long?,
	): CurrentRetentionAuthority {
		val stored = storedAuthority(source, RetentionAuthorityScope.LIVE_AMBIENT)
			?: return CurrentRetentionAuthority.Unavailable(
				RetentionAuthorityUnavailableReason.RETENTION_AUTHORITY_UNAVAILABLE,
			)
		if (!stored.authentic) {
			return CurrentRetentionAuthority.Unavailable(
				RetentionAuthorityUnavailableReason.INTEGRITY_MISMATCH,
			)
		}
		if (!stored.matchesApproval(
			approval.opaquePolicyId, expectedSourcePolicyRevision, expectedAmbientConsentEpoch,
			expectedCollectedDataEpoch, expectedRetainedFromMs,
		)) {
			return CurrentRetentionAuthority.Unavailable(
				RetentionAuthorityUnavailableReason.RETENTION_AUTHORITY_UNAVAILABLE,
			)
		}
		val now = effectiveTimeProvider.now()
		if (
			stored.effectiveBootId != now.bootId ||
			stored.effectiveElapsedRealtimeNanos > now.elapsedRealtimeNanos ||
			stored.effectiveWallTimeMs > now.wallTimeMs
		) {
			return CurrentRetentionAuthority.Unavailable(
				RetentionAuthorityUnavailableReason.EFFECTIVE_TIME_INVALID,
			)
		}
		return CurrentRetentionAuthority.Approved(
			stored.opaquePolicyId,
			stored.approvalRevision,
			stored.effectiveBootId,
			stored.effectiveElapsedRealtimeNanos,
			stored.effectiveWallTimeMs,
			stored.collectedDataEpoch,
			stored.retainedFromMs,
		)
	}

	@Suppress("ReturnCount") // Candidate rejection precedes ordered revocation and one prepared result.
	private suspend fun preparePendingConfigurationLocked(
		expectedConfigurationGeneration: Long,
		operationPermit: RetentionAuthorityOperationPermit,
	): RetentionConfigurationApprovalResult {
		operationPermit.validate()
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
		if (candidate.status != RetentionPolicyApprovalStatus.PENDING ||
			!candidate.policy.hasAuthenticChecksum(RetentionPolicyApprovalStatus.PENDING)
		) {
			return RetentionConfigurationApprovalResult.Unavailable(
				RetentionAuthorityUnavailableReason.INTEGRITY_MISMATCH,
			)
		}
		operationPermit.validate()
		val lifecycle = readLifecycle()
		operationPermit.validate()
		revokeExistingAuthorities(lifecycle, operationPermit)?.let { reason ->
			return RetentionConfigurationApprovalResult.Unavailable(reason)
		}
		return RetentionConfigurationApprovalResult.Prepared(candidate.policy)
	}

	private suspend fun revokeExistingAuthorities(
		lifecycle: CollectedDataLifecycleSnapshot,
		operationPermit: RetentionAuthorityOperationPermit,
	): RetentionAuthorityUnavailableReason? {
		for (source in DURABLE_LIVE_AMBIENT_SOURCES) {
			val revoked = revoke(
				source,
				RetentionAuthorityScope.LIVE_AMBIENT,
				lifecycle,
				operationPermit,
			)
			if (revoked is RetentionAuthorityResult.Unavailable) {
				return revoked.reason
			}
		}
		for (source in PORTABLE_IMPORT_SOURCES) {
			val revoked = revoke(
				source,
				RetentionAuthorityScope.PORTABLE_IMPORT,
				lifecycle,
				operationPermit,
			)
			if (revoked is RetentionAuthorityResult.Unavailable) {
				return revoked.reason
			}
		}
		return null
	}

	private suspend fun reconcilePendingConfigurationLocked(
		expectedConfigurationGeneration: Long? = null,
		operationPermit: RetentionAuthorityOperationPermit,
	): RetentionConfigurationApprovalResult {
		operationPermit.validate()
		val candidate = when (val read = readPolicyCandidate()) {
			is RetentionPolicyCandidateRead.Available -> read
			is RetentionPolicyCandidateRead.Unavailable ->
				return RetentionConfigurationApprovalResult.Unavailable(
					RetentionAuthorityUnavailableReason.RETENTION_POLICY_UNAVAILABLE,
				)
		}
		pendingCandidateOutcome(candidate, expectedConfigurationGeneration)?.let { return it }
		return approvePendingCandidate(candidate.policy, operationPermit)
	}

	private fun pendingCandidateOutcome(
		candidate: RetentionPolicyCandidateRead.Available,
		expectedConfigurationGeneration: Long?,
	): RetentionConfigurationApprovalResult? = when {
		expectedConfigurationGeneration != null &&
			candidate.policy.configurationGeneration != expectedConfigurationGeneration ->
			RetentionConfigurationApprovalResult.Unavailable(
				RetentionAuthorityUnavailableReason.STALE_CONFIGURATION_GENERATION,
			)
		candidate.status == RetentionPolicyApprovalStatus.APPROVED ->
			if (candidate.policy.hasAuthenticChecksum(RetentionPolicyApprovalStatus.APPROVED)) {
				RetentionConfigurationApprovalResult.AlreadyApproved(candidate.policy)
			} else {
				RetentionConfigurationApprovalResult.Unavailable(RetentionAuthorityUnavailableReason.INTEGRITY_MISMATCH)
			}
		candidate.status != RetentionPolicyApprovalStatus.PENDING ||
			!candidate.policy.hasAuthenticChecksum(RetentionPolicyApprovalStatus.PENDING) ->
			RetentionConfigurationApprovalResult.Unavailable(RetentionAuthorityUnavailableReason.INTEGRITY_MISMATCH)
		else -> null
	}

	private suspend fun approvePendingCandidate(
		policy: ApprovedRetentionPolicy,
		operationPermit: RetentionAuthorityOperationPermit,
	): RetentionConfigurationApprovalResult {
		operationPermit.validate()
		val lifecycle = readLifecycle()
		operationPermit.validate()
		revokeExistingAuthorities(lifecycle, operationPermit)?.let { reason ->
			return RetentionConfigurationApprovalResult.Unavailable(reason)
		}
		grantChosenLiveAuthorities(policy, lifecycle, operationPermit)?.let { reason ->
			return RetentionConfigurationApprovalResult.Unavailable(reason)
		}
		return publishApprovedConfiguration(policy, operationPermit)
	}

	private suspend fun grantChosenLiveAuthorities(
		policy: ApprovedRetentionPolicy,
		lifecycle: CollectedDataLifecycleSnapshot,
		operationPermit: RetentionAuthorityOperationPermit,
	): RetentionAuthorityUnavailableReason? {
		val policyState = sourcePolicyRepository.currentState()
		val snapshot = (policyState as? SourcePolicyAuthorityState.Active)?.snapshot
		if (snapshot != null) {
			for (source in DURABLE_LIVE_AMBIENT_SOURCES) {
				val sourcePolicy = snapshot[source]
				val consentEpoch = sourcePolicy.ambientConsentEpoch
				if (consentEpoch != null && sourcePolicy.ambientPersistenceEligible) {
					val granted = grant(
						source = source,
						scope = RetentionAuthorityScope.LIVE_AMBIENT,
						approval = policy,
						lifecycle = lifecycle,
						sourcePolicyRevision = snapshot.revision,
						ambientConsentEpoch = consentEpoch,
						operationPermit = operationPermit,
					)
					if (granted is RetentionAuthorityResult.Unavailable) {
						return granted.reason
					}
				}
			}
		}
		return null
	}

	private suspend fun publishApprovedConfiguration(
		policy: ApprovedRetentionPolicy,
		operationPermit: RetentionAuthorityOperationPermit,
	): RetentionConfigurationApprovalResult {
		operationPermit.validate()
		val approved = operationPermit.commitDataStoreMutation(
			policy.approvalMutationIdentity(),
		) {
			markPolicyApproved(policy)
		}
		operationPermit.validate()
		if (approved == null) {
			return RetentionConfigurationApprovalResult.Unavailable(
				RetentionAuthorityUnavailableReason.STALE_CONFIGURATION_GENERATION,
			)
		}
		if (!approved.hasAuthenticChecksum(RetentionPolicyApprovalStatus.APPROVED)) {
			return RetentionConfigurationApprovalResult.Unavailable(
				RetentionAuthorityUnavailableReason.INTEGRITY_MISMATCH,
			)
		}
		if (!approved.hasSameStableIdentity(policy)) {
			return RetentionConfigurationApprovalResult.Unavailable(
				RetentionAuthorityUnavailableReason.STALE_CONFIGURATION_GENERATION,
			)
		}
		return RetentionConfigurationApprovalResult.Approved(approved)
	}

	@Suppress("ReturnCount") // Ordered source, policy, consent and approval exits keep their distinct outcomes.
	private suspend fun reconcileLiveAmbientLocked(
		source: TrackingSourceComponent,
		operationPermit: RetentionAuthorityOperationPermit,
	): RetentionAuthorityResult {
		if (source !in DURABLE_LIVE_AMBIENT_SOURCES) {
			return unavailable(
				source,
				RetentionAuthorityScope.LIVE_AMBIENT,
				RetentionAuthorityUnavailableReason.SOURCE_AUTHORITY_UNAVAILABLE,
			)
		}
		operationPermit.validate()
		val lifecycle = readLifecycle()
		operationPermit.validate()
		val authority = sourcePolicyRepository.currentState()
		operationPermit.validate()
		val snapshot = (authority as? SourcePolicyAuthorityState.Active)?.snapshot
		if (snapshot == null) {
			return revokeBeforeUnavailable(
				source, RetentionAuthorityScope.LIVE_AMBIENT, lifecycle, operationPermit,
				RetentionAuthorityUnavailableReason.PURPOSE_AUTHORITY_UNAVAILABLE,
			)
		}
		val policy = snapshot[source]
		if (policy.ambientConsentEpoch == null || !policy.ambientPersistenceEligible) {
			return revoke(
				source,
				RetentionAuthorityScope.LIVE_AMBIENT,
				lifecycle,
				operationPermit,
			)
		}
		val approval = approvedPolicyOrNull()
		if (approval == null) {
			return revokeBeforeUnavailable(
				source, RetentionAuthorityScope.LIVE_AMBIENT, lifecycle, operationPermit,
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
			operationPermit = operationPermit,
		)
	}

	private suspend fun reconcileExistingPortableImportLocked(
		source: TrackingSourceComponent,
		operationPermit: RetentionAuthorityOperationPermit,
	): RetentionAuthorityResult {
		val scope = RetentionAuthorityScope.PORTABLE_IMPORT
		val current = storedAuthority(source, scope)
		inactiveAuthorityResult(source, scope, current)?.let { return it }
		operationPermit.validate()
		val lifecycle = readLifecycle()
		operationPermit.validate()
		val approval = approvedPolicyOrNull()
		operationPermit.validate()
		if (approval == null) {
			return revokeBeforeUnavailable(
				source, scope, lifecycle, operationPermit,
				RetentionAuthorityUnavailableReason.RETENTION_POLICY_UNAVAILABLE,
			)
		}
		return grant(
			source = source,
			scope = scope,
			approval = approval,
			lifecycle = lifecycle,
			sourcePolicyRevision = null,
			ambientConsentEpoch = null,
			operationPermit = operationPermit,
		)
	}

	private suspend fun revokeBeforeUnavailable(
		source: TrackingSourceComponent,
		scope: RetentionAuthorityScope,
		lifecycle: CollectedDataLifecycleSnapshot,
		operationPermit: RetentionAuthorityOperationPermit,
		reason: RetentionAuthorityUnavailableReason,
	): RetentionAuthorityResult {
		val revoked = revoke(source, scope, lifecycle, operationPermit)
		return if (revoked is RetentionAuthorityResult.Unavailable) {
			revoked
		} else {
			unavailable(source, scope, reason)
		}
	}

	private fun inactiveAuthorityResult(
		source: TrackingSourceComponent,
		scope: RetentionAuthorityScope,
		current: StoredRetentionAuthority?,
	): RetentionAuthorityResult? = when {
		current == null -> RetentionAuthorityResult.Unchanged(source, scope, RetentionAuthorityState.REVOKED, null)
		!current.authentic -> unavailable(source, scope, RetentionAuthorityUnavailableReason.INTEGRITY_MISMATCH)
		current.state == RetentionAuthorityState.REVOKED ->
			RetentionAuthorityResult.Unchanged(source, scope, RetentionAuthorityState.REVOKED, current.approvalRevision)
		else -> null
	}

	private suspend fun grant(
		source: TrackingSourceComponent,
		scope: RetentionAuthorityScope,
		approval: ApprovedRetentionPolicy,
		lifecycle: CollectedDataLifecycleSnapshot,
		sourcePolicyRevision: Long?,
		ambientConsentEpoch: Long?,
		operationPermit: RetentionAuthorityOperationPermit,
	): RetentionAuthorityResult {
		val current = storedAuthority(source, scope)
		if (current?.authentic == false) {
			return unavailable(
				source,
				scope,
				RetentionAuthorityUnavailableReason.INTEGRITY_MISMATCH,
			)
		}
		if (current != null && current.matchesApproval(
			approval.opaquePolicyId, sourcePolicyRevision, ambientConsentEpoch, lifecycle.epoch, lifecycle.retainedFromMs,
		)) {
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
		operationPermit.validate()
		val time = nextEffectiveTime(current) ?: return unavailable(
			source,
			scope,
			RetentionAuthorityUnavailableReason.EFFECTIVE_TIME_INVALID,
		)
		operationPermit.validate()
		return operationPermit.commitRoomMutation {
			applyGrant(
				source,
				scope,
				approval.opaquePolicyId,
				lifecycle.epoch,
				lifecycle.retainedFromMs,
				sourcePolicyRevision,
				ambientConsentEpoch,
				current?.approvalRevision,
				time,
			)
		}.also { operationPermit.validate() }
	}

	private suspend fun revoke(
		source: TrackingSourceComponent,
		scope: RetentionAuthorityScope,
		lifecycle: CollectedDataLifecycleSnapshot,
		operationPermit: RetentionAuthorityOperationPermit,
	): RetentionAuthorityResult {
		val current = storedAuthority(source, scope)
		inactiveAuthorityResult(source, scope, current)?.let { return it }
		requireNotNull(current)
		beforeDecisionApply(source, scope)
		operationPermit.validate()
		val time = nextEffectiveTime(current) ?: return unavailable(
			source,
			scope,
			RetentionAuthorityUnavailableReason.EFFECTIVE_TIME_INVALID,
		)
		operationPermit.validate()
		return operationPermit.commitRoomMutation {
			applyRevoke(
				source,
				scope,
				lifecycle.epoch,
				lifecycle.retainedFromMs,
				current.approvalRevision,
				time,
			)
		}.also { operationPermit.validate() }
	}

	// One stored approval identity; effective clocks and mutation rules stay in their calling phases.
	@Suppress("ComplexCondition")
	private fun StoredRetentionAuthority.matchesApproval(
		opaquePolicyId: String,
		sourcePolicyRevision: Long?,
		ambientConsentEpoch: Long?,
		collectedDataEpoch: Long,
		retainedFromMs: Long?,
	): Boolean = state == RetentionAuthorityState.ACTIVE &&
		this.opaquePolicyId == opaquePolicyId &&
		this.sourcePolicyRevision == sourcePolicyRevision &&
		this.ambientConsentEpoch == ambientConsentEpoch &&
		this.collectedDataEpoch == collectedDataEpoch &&
		this.retainedFromMs == retainedFromMs

	@Suppress("ReturnCount") // Keep ordered missing-reference and rejection exits together.
	private fun nextEffectiveTime(
		current: StoredRetentionAuthority?,
	): SourcePolicyEffectiveTime? {
		val now = effectiveTimeProvider.now()
		if (current == null) {
			return now
		}
		if (now.wallTimeMs < current.effectiveWallTimeMs) {
			return null
		}
		if (now.bootId != current.effectiveBootId) {
			return now
		}
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
		retainedFromMs: Long?,
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
					retainedFromMs,
				)
			} else {
				AmbientStepsRetentionDecision.GrantPortableImport(
					opaquePolicyId,
					collectedDataEpoch,
					time.bootId,
					time.elapsedRealtimeNanos,
					time.wallTimeMs,
					expectedPreviousApprovalRevision,
					retainedFromMs,
				)
			},
		).toResult(source, scope, RetentionAuthorityState.ACTIVE)
		TrackingSourceComponent.LOCATION -> database.applyAmbientLocationRetentionDecision(
			radioGrantDecision(
				scope, opaquePolicyId, collectedDataEpoch, retainedFromMs, sourcePolicyRevision,
				ambientConsentEpoch, expectedPreviousApprovalRevision, time,
			),
		).toResult(source, scope, RetentionAuthorityState.ACTIVE)
		TrackingSourceComponent.WIFI -> database.applyAmbientWifiRetentionDecision(
			radioGrantDecision(
				scope, opaquePolicyId, collectedDataEpoch, retainedFromMs, sourcePolicyRevision,
				ambientConsentEpoch, expectedPreviousApprovalRevision, time,
			),
		).toResult(source, scope, RetentionAuthorityState.ACTIVE)
		TrackingSourceComponent.CELL -> database.applyAmbientCellRetentionDecision(
			radioGrantDecision(
				scope, opaquePolicyId, collectedDataEpoch, retainedFromMs, sourcePolicyRevision,
				ambientConsentEpoch, expectedPreviousApprovalRevision, time,
			),
		).toResult(source, scope, RetentionAuthorityState.ACTIVE)
		else -> unavailable(
			source,
			scope,
			RetentionAuthorityUnavailableReason.SOURCE_AUTHORITY_UNAVAILABLE,
		)
	}

	// These are the existing immutable grant fields, kept explicit without another carrier type.
	@Suppress("LongParameterList")
	private fun radioGrantDecision(
		scope: RetentionAuthorityScope,
		opaquePolicyId: String,
		collectedDataEpoch: Long,
		retainedFromMs: Long?,
		sourcePolicyRevision: Long?,
		ambientConsentEpoch: Long?,
		expectedPreviousApprovalRevision: Long?,
		time: SourcePolicyEffectiveTime,
	): AmbientRadioRetentionDecision = if (scope == RetentionAuthorityScope.LIVE_AMBIENT) {
		AmbientRadioRetentionDecision.GrantLiveAmbient(
			opaquePolicyId,
			collectedDataEpoch,
			requireNotNull(sourcePolicyRevision),
			requireNotNull(ambientConsentEpoch),
			time.bootId,
			time.elapsedRealtimeNanos,
			time.wallTimeMs,
			expectedPreviousApprovalRevision,
			retainedFromMs,
		)
	} else {
		AmbientRadioRetentionDecision.GrantPortableImport(
			opaquePolicyId,
			collectedDataEpoch,
			time.bootId,
			time.elapsedRealtimeNanos,
			time.wallTimeMs,
			expectedPreviousApprovalRevision,
			retainedFromMs,
		)
	}

	private suspend fun applyRevoke(
		source: TrackingSourceComponent,
		scope: RetentionAuthorityScope,
		collectedDataEpoch: Long,
		retainedFromMs: Long?,
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
				retainedFromMs,
			),
		).toResult(source, scope, RetentionAuthorityState.REVOKED)
		TrackingSourceComponent.LOCATION -> database.applyAmbientLocationRetentionDecision(
			AmbientRadioRetentionDecision.Revoke(
				scope.name,
				collectedDataEpoch,
				time.bootId,
				time.elapsedRealtimeNanos,
				time.wallTimeMs,
				expectedPreviousApprovalRevision,
				retainedFromMs,
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
				retainedFromMs,
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
				retainedFromMs,
			),
		).toResult(source, scope, RetentionAuthorityState.REVOKED)
		else -> unavailable(
			source,
			scope,
			RetentionAuthorityUnavailableReason.SOURCE_AUTHORITY_UNAVAILABLE,
		)
	}

	// Four source-specific latest-row projections; lookup and integrity helpers retain their own rules.
	@Suppress("CyclomaticComplexMethod", "LongMethod")
	private suspend fun storedAuthority(
		source: TrackingSourceComponent,
		scope: RetentionAuthorityScope,
	): StoredRetentionAuthority? = when (source) {
		TrackingSourceComponent.STEPS ->
			database.ambientStepsFactRevisionDao().latestRetentionAuthority(scope.name)?.let {
				StoredRetentionAuthority(
					it.approvalRevision,
					if (it.isActive) {
						RetentionAuthorityState.ACTIVE
					} else {
						RetentionAuthorityState.REVOKED
					},
					it.opaquePolicyId,
					it.sourcePolicyRevision,
					it.ambientConsentEpoch,
					it.collectedDataEpoch,
					it.retainedFromMs,
					it.effectiveBootId,
					it.effectiveElapsedRealtimeNanos,
					it.effectiveWallTimeMs,
					AmbientStepsRetentionAuthorityIntegrity.isAuthentic(it),
				)
			}
		TrackingSourceComponent.LOCATION ->
			database.locationPurposeDao().latestRetentionAuthority(scope.name)?.let {
				StoredRetentionAuthority(
					it.approvalRevision,
					if (it.isActive) {
						RetentionAuthorityState.ACTIVE
					} else {
						RetentionAuthorityState.REVOKED
					},
					it.opaquePolicyId,
					it.sourcePolicyRevision,
					it.ambientConsentEpoch,
					it.collectedDataEpoch,
					it.retainedFromMs,
					it.effectiveBootId,
					it.effectiveElapsedRealtimeNanos,
					it.effectiveWallTimeMs,
					AmbientLocationRetentionAuthorityIntegrity.isAuthentic(it),
				)
			}
		TrackingSourceComponent.WIFI ->
			database.ambientWifiFactDao().latestRetentionAuthority(scope.name)?.let {
				StoredRetentionAuthority(
					it.approvalRevision,
					if (it.isActive) {
						RetentionAuthorityState.ACTIVE
					} else {
						RetentionAuthorityState.REVOKED
					},
					it.opaquePolicyId,
					it.sourcePolicyRevision,
					it.ambientConsentEpoch,
					it.collectedDataEpoch,
					it.retainedFromMs,
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
					if (it.isActive) {
						RetentionAuthorityState.ACTIVE
					} else {
						RetentionAuthorityState.REVOKED
					},
					it.opaquePolicyId,
					it.sourcePolicyRevision,
					it.ambientConsentEpoch,
					it.collectedDataEpoch,
					it.retainedFromMs,
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
		val retainedFromMs: Long?,
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
			TrackingSourceComponent.LOCATION,
			TrackingSourceComponent.STEPS,
			TrackingSourceComponent.WIFI,
			TrackingSourceComponent.CELL,
		)
		val PORTABLE_IMPORT_SOURCES = DURABLE_LIVE_AMBIENT_SOURCES - TrackingSourceComponent.LOCATION
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
	AmbientStepsRetentionAuthorityUnavailableReason.RETAINED_FROM_CHANGED ->
		RetentionAuthorityUnavailableReason.RETAINED_FROM_CHANGED
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
	AmbientRadioRetentionAuthorityUnavailableReason.RETAINED_FROM_CHANGED ->
		RetentionAuthorityUnavailableReason.RETAINED_FROM_CHANGED
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
