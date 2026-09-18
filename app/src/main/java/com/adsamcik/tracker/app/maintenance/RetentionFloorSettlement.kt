package com.adsamcik.tracker.app.maintenance

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.synchronizeLifecycle
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleSnapshot
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.shared.preferences.lifecycle.advanceRetainedFromWithPermit
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityDataStoreCommitUnknownException
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityOperationLease
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityProducer
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityResult
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityScope
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityState
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityUnavailableReason
import com.adsamcik.tracker.shared.preferences.retention.reconcileCurrentSettingsWithPermit
import com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent
import com.adsamcik.tracker.tracker.api.AmbientTrackingSource
import com.adsamcik.tracker.tracker.api.TrackingRetentionFloorReconciler
import com.adsamcik.tracker.tracker.api.TrackingRetentionFloorReconciliationDebt
import com.adsamcik.tracker.tracker.api.TrackingRetentionFloorReconciliationFailure
import com.adsamcik.tracker.tracker.api.TrackingRetentionFloorReconciliationFailureReason
import com.adsamcik.tracker.tracker.api.TrackingRetentionFloorReconciliationResult
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

@Singleton
class RetentionFloorSettlement @Inject constructor(
	private val operationLease: RetentionAuthorityOperationLease,
	private val retentionAuthorityProducer: RetentionAuthorityProducer,
	private val purposeReconciler: TrackingRetentionFloorReconciler,
) {
	suspend fun settle(
		database: AppDatabase,
		lifecycleStore: CollectedDataLifecycleStore,
		startupGate: TrackingStartupGate,
		expectedStartupGeneration: Long,
		requestedRetainedFromMs: Long,
		operationId: String = "retention-floor:$requestedRetainedFromMs",
		updatedAtMs: Long,
		verifyApprovedOperation: () -> Unit,
	): RetentionFloorSettlementResult {
		require(requestedRetainedFromMs >= 0L)
		require(operationId.isNotBlank())
		require(updatedAtMs >= 0L)
		val commit = try {
			startupGate.withReadyGenerationOperation(expectedStartupGeneration) {
			operationLease.withPermit(cancellationShielded = true) { permit ->
				var phase = RetentionFloorSettlementPhase.LIFECYCLE_FLOOR
				try {
					verifyApprovedOperation()
					val lifecycle = lifecycleStore.advanceRetainedFromWithPermit(
						requestedRetainedFromMs,
						permit,
						operationId,
					)
					permit.validate()
					verifyApprovedOperation()
					phase = RetentionFloorSettlementPhase.ROOM_GUARD
					permit.commitRoomMutation {
						verifyApprovedOperation()
						database.withTransaction {
							verifyApprovedOperation()
							try {
								val sourceEvidenceStateDao = database.sourceEvidenceStateDao()
								val lifecycleChanged = sourceEvidenceStateDao.synchronizeLifecycle(
									epoch = lifecycle.epoch,
									retainedFromMs = lifecycle.retainedFromMs,
									updatedAtMs = updatedAtMs,
								)
								if (!lifecycleChanged) {
									val evidence = requireNotNull(sourceEvidenceStateDao.get())
									check(
										sourceEvidenceStateDao.incrementRevisionForExactLifecycle(
											expectedRevision = evidence.revision,
											expectedCollectedDataEpoch = lifecycle.epoch,
											expectedRetainedFromMs = lifecycle.retainedFromMs,
											updatedAtMs = updatedAtMs,
										) == 1,
									) {
										"Unable to advance source-evidence revision for retention floor"
									}
								}
							} finally {
								verifyApprovedOperation()
							}
						}
						verifyApprovedOperation()
					}
					permit.validate()
					phase = RetentionFloorSettlementPhase.AUTHORITY_REISSUE
					val retentionResults = try {
						retentionAuthorityProducer.reconcileCurrentSettingsWithPermit(permit)
					} catch (unknown: RetentionAuthorityDataStoreCommitUnknownException) {
						throw unknown
					} catch (_: Exception) {
						RETENTION_PROVIDER_SOURCES.keys.map { source ->
							RetentionAuthorityResult.Unavailable(
								source = source,
								scope = RetentionAuthorityScope.LIVE_AMBIENT,
								reason = RetentionAuthorityUnavailableReason.STORAGE_UNAVAILABLE,
							)
						}
					}
					RetentionFloorCommitResult.Applied(lifecycle, retentionResults)
				} catch (unknown: RetentionAuthorityDataStoreCommitUnknownException) {
					throw unknown
				} catch (_: Exception) {
					RetentionFloorCommitResult.Retryable(
						RetentionFloorSettlementFailure.CommitBoundary(phase),
					)
				}
			}
		}
		} catch (unknown: RetentionAuthorityDataStoreCommitUnknownException) {
			return RetentionFloorSettlementResult.Retryable(
				RetentionFloorSettlementDebt(
					requestedRetainedFromMs,
					listOf(
						RetentionFloorSettlementFailure.DataStoreCommitUnknown(
							unknown.operationIdentity,
						),
					),
				),
			)
		} ?: return RetentionFloorSettlementResult.StartupGenerationChanged

		if (commit is RetentionFloorCommitResult.Retryable) {
			return RetentionFloorSettlementResult.Retryable(
				RetentionFloorSettlementDebt(
					requestedRetainedFromMs,
					listOf(commit.failure),
				),
			)
		}
		commit as RetentionFloorCommitResult.Applied
		val retainedFromMs = requireNotNull(commit.lifecycle.retainedFromMs) {
			"Retention settlement must establish a durable retained-from floor"
		}
		val failures = mutableListOf<RetentionFloorSettlementFailure>()
		val approvedSources = buildSet {
			RETENTION_PROVIDER_SOURCES.forEach { (component, source) ->
				val results = commit.retentionResults.filter {
					it.source == component && it.scope == RetentionAuthorityScope.LIVE_AMBIENT
				}
				if (results.size != 1) {
					failures += RetentionFloorSettlementFailure.ResultSetInvalid(component)
					return@forEach
				}
				when (val result = results.single()) {
					is RetentionAuthorityResult.Unavailable ->
						failures += RetentionFloorSettlementFailure.AuthorityUnavailable(result)
					is RetentionAuthorityResult.Applied ->
						if (result.state == RetentionAuthorityState.ACTIVE) add(source)
					is RetentionAuthorityResult.Unchanged ->
						if (result.state == RetentionAuthorityState.ACTIVE) add(source)
				}
			}
		}
		val purposeResult = try {
			verifyApprovedOperation()
			purposeReconciler.reconcile(
				expectedStartupGeneration,
				retainedFromMs,
				approvedSources,
			).also { verifyApprovedOperation() }
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: Exception) {
			TrackingRetentionFloorReconciliationResult.Retryable(
				TrackingRetentionFloorReconciliationDebt(
					retainedFromMs,
					listOf(
						TrackingRetentionFloorReconciliationFailure(
							source = null,
							reason = TrackingRetentionFloorReconciliationFailureReason
								.OWNER_RECONCILIATION_FAILED,
						),
					),
				),
			)
		}
		if (purposeResult is TrackingRetentionFloorReconciliationResult.Retryable) {
			failures += RetentionFloorSettlementFailure.ProviderLifecycle(purposeResult.debt)
		} else if (
			purposeResult is TrackingRetentionFloorReconciliationResult.Complete &&
			purposeResult.reconciledSources != approvedSources
		) {
			failures += RetentionFloorSettlementFailure.ProviderLifecycle(
				TrackingRetentionFloorReconciliationDebt(
					retainedFromMs,
					listOf(
						TrackingRetentionFloorReconciliationFailure(
							source = null,
							reason = TrackingRetentionFloorReconciliationFailureReason
								.PUBLICATION_REJECTED,
						),
					),
				),
			)
		}
		return if (failures.isEmpty()) {
			startupGate.withReadyGeneration(expectedStartupGeneration) {
				RetentionFloorSettlementResult.Settled(commit.lifecycle, approvedSources)
			} ?: RetentionFloorSettlementResult.Retryable(
				RetentionFloorSettlementDebt(
					retainedFromMs,
					listOf(
						RetentionFloorSettlementFailure.ProviderLifecycle(
							TrackingRetentionFloorReconciliationDebt(
								retainedFromMs,
								listOf(
									TrackingRetentionFloorReconciliationFailure(
										source = null,
										reason =
											TrackingRetentionFloorReconciliationFailureReason
												.STARTUP_GENERATION_CHANGED,
									),
								),
							),
						),
					),
				),
			)
		} else {
			RetentionFloorSettlementResult.Retryable(
				RetentionFloorSettlementDebt(retainedFromMs, failures),
			)
		}
	}

	private companion object {
		val RETENTION_PROVIDER_SOURCES = linkedMapOf(
			TrackingSourceComponent.STEPS to AmbientTrackingSource.STEPS,
			TrackingSourceComponent.WIFI to AmbientTrackingSource.WIFI,
			TrackingSourceComponent.CELL to AmbientTrackingSource.CELL,
		)
	}
}

sealed interface RetentionFloorSettlementResult {
	data class Settled(
		val lifecycle: CollectedDataLifecycleSnapshot,
		val reconciledSources: Set<AmbientTrackingSource>,
	) : RetentionFloorSettlementResult

	data object StartupGenerationChanged : RetentionFloorSettlementResult

	data class Retryable(
		val debt: RetentionFloorSettlementDebt,
	) : RetentionFloorSettlementResult
}

data class RetentionFloorSettlementDebt(
	val retainedFromMs: Long,
	val failures: List<RetentionFloorSettlementFailure>,
) {
	init {
		require(retainedFromMs >= 0L)
		require(failures.isNotEmpty())
	}
}

sealed interface RetentionFloorSettlementFailure {
	data class ResultSetInvalid(
		val source: TrackingSourceComponent,
	) : RetentionFloorSettlementFailure

	data class AuthorityUnavailable(
		val result: RetentionAuthorityResult.Unavailable,
	) : RetentionFloorSettlementFailure

	data class ProviderLifecycle(
		val debt: TrackingRetentionFloorReconciliationDebt,
	) : RetentionFloorSettlementFailure

	data class CommitBoundary(
		val phase: RetentionFloorSettlementPhase,
	) : RetentionFloorSettlementFailure

	data class DataStoreCommitUnknown(
		val operationId: String,
	) : RetentionFloorSettlementFailure {
		init {
			require(operationId.isNotBlank())
		}
	}
}

enum class RetentionFloorSettlementPhase {
	LIFECYCLE_FLOOR,
	ROOM_GUARD,
	AUTHORITY_REISSUE,
}

private sealed interface RetentionFloorCommitResult {
	data class Applied(
		val lifecycle: CollectedDataLifecycleSnapshot,
		val retentionResults: List<RetentionAuthorityResult>,
	) : RetentionFloorCommitResult

	data class Retryable(
		val failure: RetentionFloorSettlementFailure.CommitBoundary,
	) : RetentionFloorCommitResult
}
