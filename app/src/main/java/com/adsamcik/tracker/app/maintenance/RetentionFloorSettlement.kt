package com.adsamcik.tracker.app.maintenance

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.RetentionFloorDestructivePlan
import com.adsamcik.tracker.shared.base.database.RetentionFloorSettlementOperation
import com.adsamcik.tracker.shared.base.database.RetentionFloorSettlementDisposition
import com.adsamcik.tracker.shared.base.database.RetentionFloorOperationLookupResult
import com.adsamcik.tracker.shared.base.database.RetentionWorkExecutionReceipt
import com.adsamcik.tracker.shared.base.database.activeRetentionFloorSettlement
import com.adsamcik.tracker.shared.base.database.advanceRetentionFloorSettlementPhase
import com.adsamcik.tracker.shared.base.database.commitRetentionFloorAuthorityReissued
import com.adsamcik.tracker.shared.base.database.commitRetentionFloorRoomGuard
import com.adsamcik.tracker.shared.base.database.data.CollectedDataDeletionOperationEntity
import com.adsamcik.tracker.shared.base.database.prepareOrResumeRetentionFloorSettlement
import com.adsamcik.tracker.shared.base.database.retentionFloorSettlement
import com.adsamcik.tracker.shared.base.database.retentionFloorSettlementDisposition
import com.adsamcik.tracker.shared.base.database.retentionFloorSettlementForExecution
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleSnapshot
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.shared.preferences.lifecycle.advanceRetainedFromWithPermit
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityDataStoreCommitUnknownException
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityOperationPermit
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
/** Resumes the durable retention journal across policy, DataStore, Room and provider reconciliation. */
class RetentionFloorSettlement @Inject constructor(
	private val operationLease: RetentionAuthorityOperationLease,
	private val retentionAuthorityProducer: RetentionAuthorityProducer,
	private val purposeReconciler: TrackingRetentionFloorReconciler,
) {
	/** Reads the pending floor operation, optionally qualifying it against the exact worker execution. */
	suspend fun pendingOperation(database: AppDatabase): RetentionFloorSettlementOperation? =
		database.activeRetentionFloorSettlement()

	/** Reads the pending floor operation, optionally qualifying it against the exact worker execution. */
	suspend fun pendingOperation(
		database: AppDatabase,
		execution: RetentionWorkExecutionReceipt,
		expectedOperationId: String? = null,
	): RetentionFloorOperationLookupResult =
		database.retentionFloorSettlementForExecution(execution, expectedOperationId)

	/** Checks whether the named operation is current, completed or superseded by data deletion. */
	suspend fun disposition(
		database: AppDatabase,
		operationId: String,
		expectedCollectedDataEpoch: Long,
	): RetentionFloorSettlementDisposition =
		database.retentionFloorSettlementDisposition(operationId, expectedCollectedDataEpoch)

	@Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
	/** Advances a UTC millisecond retention floor under the existing operation lease and startup generation. */
	suspend fun settle(
		database: AppDatabase,
		lifecycleStore: CollectedDataLifecycleStore,
		startupGate: TrackingStartupGate,
		expectedStartupGeneration: Long,
		requestedRetainedFromMs: Long,
		operationId: String = "retention-floor:$requestedRetainedFromMs",
		updatedAtMs: Long,
		workExecutionId: String = operationId,
		destructivePlan: RetentionFloorDestructivePlan =
			RetentionFloorDestructivePlan.legacy(
				updatedAtMs,
				requestedRetainedFromMs,
			),
		verifyApprovedOperation: suspend () -> Unit,
	): RetentionFloorSettlementResult {
		require(requestedRetainedFromMs >= 0L)
		require(operationId.isNotBlank())
		require(updatedAtMs >= 0L)
		require(workExecutionId.isNotBlank())
		require(destructivePlan.requestedAtMs == updatedAtMs)
		require(destructivePlan.requestedRetainedFromMs == requestedRetainedFromMs)
		var activeOperation: RetentionFloorSettlementOperation? = null
		var phase = RetentionFloorSettlementPhase.REQUESTED_PREPARED
		val result = try {
			startupGate.withReadyGenerationOperation(expectedStartupGeneration) {
				val authorityPreparation = operationLease.withPermit(
					cancellationShielded = true,
				) { permit ->
					verifyApprovedOperation()
					val initialLifecycle = lifecycleStore.snapshot()
					permit.validate()
					var operation = permit.commitRoomMutation {
						database.prepareOrResumeRetentionFloorSettlement(
							operationId = operationId,
							requestedRetainedFromMs = requestedRetainedFromMs,
							collectedDataEpoch = initialLifecycle.epoch,
							requestedAtMs = updatedAtMs,
							workExecutionId = workExecutionId,
							destructivePlan = destructivePlan,
						)
					}
					activeOperation = operation
					val transitionAtMs = maxOf(updatedAtMs, operation.requestedAtMs)
					var lifecycle = initialLifecycle
					if (
						!operation.hasReached(
							CollectedDataDeletionOperationEntity
								.PHASE_RETENTION_DATASTORE_ACKNOWLEDGED,
						)
					) {
						phase = RetentionFloorSettlementPhase.DATASTORE_FLOOR_ACKNOWLEDGED
						verifyApprovedOperation()
						lifecycle = lifecycleStore.advanceRetainedFromWithPermit(
							operation.requestedRetainedFromMs,
							permit,
							operation.operationId,
						)
						check(lifecycle.epoch == operation.collectedDataEpoch) {
							"Retention-floor lifecycle epoch changed before acknowledgement"
						}
						check(
							requireNotNull(lifecycle.retainedFromMs) >=
								operation.requestedRetainedFromMs,
						) { "Retention-floor DataStore acknowledgement is below its request" }
						operation = permit.commitRoomMutation {
							database.advanceRetentionFloorSettlementPhase(
								operation = operation,
								expectedPhase =
									CollectedDataDeletionOperationEntity.PHASE_RETENTION_PREPARED,
								newPhase = CollectedDataDeletionOperationEntity
									.PHASE_RETENTION_DATASTORE_ACKNOWLEDGED,
								updatedAtMs = transitionAtMs,
							)
						}
						activeOperation = operation
					} else {
						lifecycle = lifecycleStore.snapshot()
						check(lifecycle.epoch == operation.collectedDataEpoch)
						check(
							requireNotNull(lifecycle.retainedFromMs) >=
								operation.requestedRetainedFromMs,
						)
					}
					val settledRetainedFromMs = requireNotNull(lifecycle.retainedFromMs)
					phase = RetentionFloorSettlementPhase.ROOM_GUARD_COMMITTED
					verifyApprovedOperation()
					operation = permit.commitRoomMutation {
						database.commitRetentionFloorRoomGuard(
							operation = operation,
							settledRetainedFromMs = settledRetainedFromMs,
							updatedAtMs = transitionAtMs,
						)
					}
					activeOperation = operation
					permit.validate()
					if (
						operation.hasReached(
							CollectedDataDeletionOperationEntity
								.PHASE_RETENTION_PROVIDER_RECONCILED,
						)
					) {
						return@withPermit RetentionFloorLeaseResult.Prepared(
							lifecycle = lifecycle,
							activeLocalSources = operation.activeSourceKeys.mapTo(
								linkedSetOf(),
							) { source -> AmbientTrackingSource.valueOf(source) },
							operation = operation,
						)
					}
					if (
						operation.hasReached(
							CollectedDataDeletionOperationEntity
								.PHASE_RETENTION_AUTHORITY_REISSUED,
						)
					) {
						return@withPermit RetentionFloorLeaseResult.Prepared(
							lifecycle = lifecycle,
							activeLocalSources = operation.activeSourceKeys.mapTo(
								linkedSetOf(),
							) { source -> AmbientTrackingSource.valueOf(source) },
							operation = operation,
						)
					}
					phase = RetentionFloorSettlementPhase.AUTHORITY_REISSUED
					val retentionResults = try {
						retentionAuthorityProducer.reconcileCurrentSettingsWithPermit(
							permit = permit,
							settlementOperationId = operation.operationId,
						)
					} catch (unknown: RetentionAuthorityDataStoreCommitUnknownException) {
						throw unknown
					} catch (_: Exception) {
						requiredAuthorityKeys().map { (source, scope) ->
							RetentionAuthorityResult.Unavailable(
								source = source,
								scope = scope,
								reason = RetentionAuthorityUnavailableReason.STORAGE_UNAVAILABLE,
							)
						}
					}
					val authority = evaluateAuthorityResults(retentionResults)
					if (authority.failures.isNotEmpty()) {
						return@withPermit RetentionFloorLeaseResult.Retryable(
							RetentionFloorSettlementResult.Retryable(
								RetentionFloorSettlementDebt(
									operation.requestedRetainedFromMs,
									authority.failures,
								),
							),
						)
					}
					if (
						!operation.hasReached(
							CollectedDataDeletionOperationEntity
								.PHASE_RETENTION_AUTHORITY_REISSUED,
						)
					) {
						operation = permit.commitRoomMutation {
							database.commitRetentionFloorAuthorityReissued(
								operation,
								authority.activeLocalSources.mapTo(linkedSetOf()) { it.name },
							)
						}
						activeOperation = operation
					}
					RetentionFloorLeaseResult.Prepared(
						lifecycle = lifecycle,
						activeLocalSources = authority.activeLocalSources,
						operation = operation,
					)
				}
				if (authorityPreparation is RetentionFloorLeaseResult.Retryable) {
					return@withReadyGenerationOperation authorityPreparation.result
				}
				authorityPreparation as RetentionFloorLeaseResult.Prepared
				phase = RetentionFloorSettlementPhase.SOURCE_RECONCILIATION_COMPLETED
				if (
					!authorityPreparation.operation.hasReached(
						CollectedDataDeletionOperationEntity
							.PHASE_RETENTION_PROVIDER_RECONCILED,
					)
				) {
					val purposeResult = reconcilePurpose(
						expectedStartupGeneration,
						requireNotNull(authorityPreparation.lifecycle.retainedFromMs),
						authorityPreparation.activeLocalSources,
						authorityPreparation.operation.operationId,
						verifyApprovedOperation,
					)
					if (purposeResult is TrackingRetentionFloorReconciliationResult.Retryable) {
						return@withReadyGenerationOperation RetentionFloorSettlementResult.Retryable(
							RetentionFloorSettlementDebt(
								authorityPreparation.operation.requestedRetainedFromMs,
								listOf(
									RetentionFloorSettlementFailure.ProviderLifecycle(
										purposeResult.debt,
									),
								),
							),
						)
					}
					purposeResult as TrackingRetentionFloorReconciliationResult.Complete
					if (purposeResult.reconciledSources != authorityPreparation.activeLocalSources) {
						return@withReadyGenerationOperation RetentionFloorSettlementResult.Retryable(
							RetentionFloorSettlementDebt(
								authorityPreparation.operation.requestedRetainedFromMs,
								listOf(
									publicationRejected(
										requireNotNull(
											authorityPreparation.lifecycle.retainedFromMs,
										),
									),
								),
							),
						)
					}
				}
				if (!startupGate.isReadyGeneration(expectedStartupGeneration)) {
					return@withReadyGenerationOperation RetentionFloorSettlementResult.Retryable(
						RetentionFloorSettlementDebt(
							authorityPreparation.operation.requestedRetainedFromMs,
							listOf(
								startupGenerationChanged(
									requireNotNull(
										authorityPreparation.lifecycle.retainedFromMs,
									),
								),
							),
						),
					)
				}
				val reconciledOperation = operationLease.withPermit(
					cancellationShielded = true,
				) { permit ->
					verifyApprovedOperation()
					if (
						authorityPreparation.operation.hasReached(
							CollectedDataDeletionOperationEntity
								.PHASE_RETENTION_PROVIDER_RECONCILED,
						)
					) {
						authorityPreparation.operation
					} else {
						permit.commitRoomMutation {
							database.advanceRetentionFloorSettlementPhase(
								operation = authorityPreparation.operation,
								expectedPhase = CollectedDataDeletionOperationEntity
									.PHASE_RETENTION_AUTHORITY_REISSUED,
								newPhase = CollectedDataDeletionOperationEntity
									.PHASE_RETENTION_PROVIDER_RECONCILED,
								updatedAtMs = maxOf(
									updatedAtMs,
									authorityPreparation.operation.requestedAtMs,
									requireNotNull(
										authorityPreparation.operation.sourceMaintenanceAtMs,
									),
								),
							)
						}
					}
				}
				activeOperation = reconciledOperation
				RetentionFloorSettlementResult.Settled(
					lifecycle = authorityPreparation.lifecycle,
					reconciledSources = authorityPreparation.activeLocalSources,
					operationId = reconciledOperation.operationId,
					requestedRetainedFromMs = reconciledOperation.requestedRetainedFromMs,
					requestedAtMs = reconciledOperation.requestedAtMs,
					workExecutionId = reconciledOperation.workExecutionId,
					destructivePlan = reconciledOperation.destructivePlan,
					sourceMaintenanceAtMs = requireNotNull(
						reconciledOperation.sourceMaintenanceAtMs,
					),
					sourceMaintenanceCompleted = reconciledOperation.hasReached(
						CollectedDataDeletionOperationEntity
							.PHASE_RETENTION_SOURCE_MAINTENANCE_COMPLETED,
					),
				)
			}
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (unknown: RetentionAuthorityDataStoreCommitUnknownException) {
			val persisted = try {
				database.collectedDataDeletionOperationDao().get(unknown.operationIdentity)
			} catch (_: Exception) {
				null
			}
			return RetentionFloorSettlementResult.Retryable(
				RetentionFloorSettlementDebt(
					persisted?.retainedFromMs ?: requestedRetainedFromMs,
					listOf(
						RetentionFloorSettlementFailure.DataStoreCommitUnknown(
							unknown.operationIdentity,
						),
					),
				),
			)
		} catch (_: Exception) {
			return RetentionFloorSettlementResult.Retryable(
				RetentionFloorSettlementDebt(
					activeOperation?.requestedRetainedFromMs ?: requestedRetainedFromMs,
					listOf(RetentionFloorSettlementFailure.CommitBoundary(phase)),
				),
			)
		}
		if (result != null) {
			return result
		}
		val pending = activeOperation ?: return RetentionFloorSettlementResult.StartupGenerationChanged
		return RetentionFloorSettlementResult.Retryable(
			RetentionFloorSettlementDebt(
				pending.requestedRetainedFromMs,
				listOf(startupGenerationChanged(pending.requestedRetainedFromMs)),
			),
		)
	}

	/** Closes the exact reconciled floor operation, preserving retry or full-deletion supersession outcomes. */
	suspend fun complete(
		database: AppDatabase,
		startupGate: TrackingStartupGate,
		expectedStartupGeneration: Long,
		settlement: RetentionFloorSettlementResult.Settled,
		completedAtMs: Long,
		verifyApprovedOperation: suspend () -> Unit,
	): RetentionFloorSettlementCompletionResult {
		require(completedAtMs >= settlement.requestedAtMs)
		require(completedAtMs >= settlement.sourceMaintenanceAtMs)
		val result = try {
			startupGate.withReadyGenerationOperation(expectedStartupGeneration) {
				operationLease.withPermit(cancellationShielded = true) { permit ->
					verifyApprovedOperation()
					completeAdmittedSettlement(
						database, startupGate, expectedStartupGeneration, settlement, completedAtMs, permit,
					)
				}
			}
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: Exception) {
			return RetentionFloorSettlementCompletionResult.Retryable
		}
		if (result != null) {
			return result
		}
		return recoverCompletion(database, settlement)
	}

	private suspend fun completeAdmittedSettlement(
		database: AppDatabase,
		startupGate: TrackingStartupGate,
		expectedStartupGeneration: Long,
		settlement: RetentionFloorSettlementResult.Settled,
		completedAtMs: Long,
		permit: RetentionAuthorityOperationPermit,
	): RetentionFloorSettlementCompletionResult {
		val operation = database.retentionFloorSettlement(settlement.operationId)
		if (operation == null) {
			val deletion = database.collectedDataDeletionOperationDao()
				.completedFullDeletionAfter(settlement.lifecycle.epoch)
			return if (deletion != null) {
				RetentionFloorSettlementCompletionResult.SupersededByFullDeletion(
					deletion.targetCollectedDataEpoch,
				)
			} else {
				RetentionFloorSettlementCompletionResult.Retryable
			}
		}
		verifySettlementIdentity(operation, settlement)
		val durable = operation
		if (
			!durable.hasReached(
				CollectedDataDeletionOperationEntity
					.PHASE_RETENTION_PROVIDER_RECONCILED,
			)
		) {
			return RetentionFloorSettlementCompletionResult.Retryable
		}
		return finishRetentionPhases(
			database, startupGate, expectedStartupGeneration, durable, completedAtMs, permit,
		)
	}

	private fun verifySettlementIdentity(
		operation: RetentionFloorSettlementOperation,
		settlement: RetentionFloorSettlementResult.Settled,
	) {
		check(operation.operationId == settlement.operationId)
		check(operation.requestedRetainedFromMs == settlement.requestedRetainedFromMs)
		check(operation.collectedDataEpoch == settlement.lifecycle.epoch)
		check(operation.requestedAtMs == settlement.requestedAtMs)
		check(operation.workExecutionId == settlement.workExecutionId)
		check(operation.destructivePlan == settlement.destructivePlan)
		check(operation.sourceMaintenanceAtMs == settlement.sourceMaintenanceAtMs)
		check(
			operation.settledRetainedFromMs ==
				requireNotNull(settlement.lifecycle.retainedFromMs),
		)
	}

	private suspend fun finishRetentionPhases(
		database: AppDatabase,
		startupGate: TrackingStartupGate,
		expectedStartupGeneration: Long,
		operation: RetentionFloorSettlementOperation,
		completedAtMs: Long,
		permit: RetentionAuthorityOperationPermit,
	): RetentionFloorSettlementCompletionResult {
		var durable = operation
		if (
			!durable.hasReached(
				CollectedDataDeletionOperationEntity
					.PHASE_RETENTION_SOURCE_MAINTENANCE_COMPLETED,
			)
		) {
			if (!startupGate.isReadyGeneration(expectedStartupGeneration)) {
				return RetentionFloorSettlementCompletionResult
					.StartupGenerationChanged
			}
			val currentDurable = durable
			durable = permit.commitRoomMutation {
				database.advanceRetentionFloorSettlementPhase(
					operation = currentDurable,
					expectedPhase = CollectedDataDeletionOperationEntity
						.PHASE_RETENTION_PROVIDER_RECONCILED,
					newPhase = CollectedDataDeletionOperationEntity
						.PHASE_RETENTION_SOURCE_MAINTENANCE_COMPLETED,
					updatedAtMs = completedAtMs,
				)
			}
		}
		if (
			!durable.hasReached(
				CollectedDataDeletionOperationEntity.PHASE_RETENTION_FINAL,
			)
		) {
			if (!startupGate.isReadyGeneration(expectedStartupGeneration)) {
				return RetentionFloorSettlementCompletionResult
					.StartupGenerationChanged
			}
			permit.commitRoomMutation {
				database.advanceRetentionFloorSettlementPhase(
					operation = durable,
					expectedPhase = CollectedDataDeletionOperationEntity
						.PHASE_RETENTION_SOURCE_MAINTENANCE_COMPLETED,
					newPhase =
						CollectedDataDeletionOperationEntity.PHASE_RETENTION_FINAL,
					updatedAtMs = completedAtMs,
				)
			}
		}
		return RetentionFloorSettlementCompletionResult.Completed
	}

	private suspend fun recoverCompletion(
		database: AppDatabase,
		settlement: RetentionFloorSettlementResult.Settled,
	): RetentionFloorSettlementCompletionResult {
		val durable = try {
			database.retentionFloorSettlement(settlement.operationId)
		} catch (_: Exception) {
			null
		}
		if (
			durable?.hasReached(
				CollectedDataDeletionOperationEntity.PHASE_RETENTION_FINAL,
			) == true
		) {
			return RetentionFloorSettlementCompletionResult.Completed
		}
		val deletion = try {
			database.collectedDataDeletionOperationDao()
				.completedFullDeletionAfter(settlement.lifecycle.epoch)
		} catch (_: Exception) {
			null
		}
		return if (deletion != null) {
			RetentionFloorSettlementCompletionResult.SupersededByFullDeletion(
				deletion.targetCollectedDataEpoch,
			)
		} else {
			RetentionFloorSettlementCompletionResult.StartupGenerationChanged
		}
	}

	private suspend fun reconcilePurpose(
		expectedStartupGeneration: Long,
		retainedFromMs: Long,
		activeSources: Set<AmbientTrackingSource>,
		settlementOperationId: String,
		verifyApprovedOperation: suspend () -> Unit,
	): TrackingRetentionFloorReconciliationResult = try {
		verifyApprovedOperation()
		val result = purposeReconciler.reconcile(
			expectedStartupGeneration,
			retainedFromMs,
			activeSources,
			settlementOperationId,
		)
		verifyApprovedOperation()
		result
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

	private fun evaluateAuthorityResults(
		results: List<RetentionAuthorityResult>,
	): RetentionAuthorityEvaluation {
		val failures = mutableListOf<RetentionFloorSettlementFailure>()
		val activeSources = buildSet {
			requiredAuthorityKeys().forEach { (component, scope) ->
				val matching = results.filter { it.source == component && it.scope == scope }
				if (matching.size != 1) {
					failures += RetentionFloorSettlementFailure.ResultSetInvalid(component, scope)
					return@forEach
				}
				val state = when (val result = matching.single()) {
					is RetentionAuthorityResult.Unavailable -> {
						failures += RetentionFloorSettlementFailure.AuthorityUnavailable(result)
						null
					}
					is RetentionAuthorityResult.Applied -> result.state
					is RetentionAuthorityResult.Unchanged -> result.state
				}
				if (scope == RetentionAuthorityScope.LIVE_AMBIENT && state == RetentionAuthorityState.ACTIVE) {
					add(RETENTION_PROVIDER_SOURCES.getValue(component))
				}
			}
		}
		return RetentionAuthorityEvaluation(activeSources, failures)
	}

	private fun publicationRejected(retainedFromMs: Long) =
		RetentionFloorSettlementFailure.ProviderLifecycle(
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

	private fun startupGenerationChanged(retainedFromMs: Long) =
		RetentionFloorSettlementFailure.ProviderLifecycle(
			TrackingRetentionFloorReconciliationDebt(
				retainedFromMs,
				listOf(
					TrackingRetentionFloorReconciliationFailure(
						source = null,
						reason = TrackingRetentionFloorReconciliationFailureReason
							.STARTUP_GENERATION_CHANGED,
					),
				),
			),
		)

	private companion object {
		val RETENTION_PROVIDER_SOURCES = linkedMapOf(
			TrackingSourceComponent.STEPS to AmbientTrackingSource.STEPS,
			TrackingSourceComponent.WIFI to AmbientTrackingSource.WIFI,
			TrackingSourceComponent.CELL to AmbientTrackingSource.CELL,
		)

		fun requiredAuthorityKeys(): List<Pair<TrackingSourceComponent, RetentionAuthorityScope>> =
			RETENTION_PROVIDER_SOURCES.keys.flatMap { source ->
				listOf(
					source to RetentionAuthorityScope.LIVE_AMBIENT,
					source to RetentionAuthorityScope.PORTABLE_IMPORT,
				)
			}
	}
}

/** Outcome of bringing the requested floor and all required source authorities into agreement. */
sealed interface RetentionFloorSettlementResult {
	/** The floor, lifecycle and active local source set are settled for this exact operation. */
	data class Settled(
		val lifecycle: CollectedDataLifecycleSnapshot,
		val reconciledSources: Set<AmbientTrackingSource>,
		val operationId: String,
		val requestedRetainedFromMs: Long,
		val requestedAtMs: Long,
		val workExecutionId: String = operationId,
		val destructivePlan: RetentionFloorDestructivePlan =
			RetentionFloorDestructivePlan.legacy(
				requestedAtMs,
				requestedRetainedFromMs,
			),
		val sourceMaintenanceAtMs: Long = requestedAtMs,
		val sourceMaintenanceCompleted: Boolean = false,
	) : RetentionFloorSettlementResult

	/** Startup or deletion changed while settlement was being admitted. */
	data object StartupGenerationChanged : RetentionFloorSettlementResult

	/** Settlement remains incomplete and needs another bounded attempt. */
	data class Retryable(
		val debt: RetentionFloorSettlementDebt,
	) : RetentionFloorSettlementResult
}

/** Outcome of closing a settled journal operation. */
sealed interface RetentionFloorSettlementCompletionResult {
	/** The exact settlement journal operation reached durable completion. */
	data object Completed : RetentionFloorSettlementCompletionResult
	/** Full deletion replaced this settlement at the reported collected-data epoch. */
	data class SupersededByFullDeletion(
		val collectedDataEpoch: Long,
	) : RetentionFloorSettlementCompletionResult
	/** Startup or deletion changed while settlement was being admitted. */
	data object StartupGenerationChanged : RetentionFloorSettlementCompletionResult
	/** Settlement remains incomplete and needs another bounded attempt. */
	data object Retryable : RetentionFloorSettlementCompletionResult
}

/** Requested UTC millisecond floor and concrete incomplete reconciliation obligations. */
data class RetentionFloorSettlementDebt(
	val retainedFromMs: Long,
	val failures: List<RetentionFloorSettlementFailure>,
) {
	init {
		require(retainedFromMs >= 0L)
		require(failures.isNotEmpty())
	}
}

/** Typed failure at an authority, provider or durable commit boundary. */
sealed interface RetentionFloorSettlementFailure {
	/** A required source and retention scope was missing or duplicated in reconciliation results. */
	data class ResultSetInvalid(
		val source: TrackingSourceComponent,
		val scope: RetentionAuthorityScope,
	) : RetentionFloorSettlementFailure

	/** The exact source retention authority could not be established. */
	data class AuthorityUnavailable(
		val result: RetentionAuthorityResult.Unavailable,
	) : RetentionFloorSettlementFailure

	/** Provider reconciliation retains cleanup or replacement obligations. */
	data class ProviderLifecycle(
		val debt: TrackingRetentionFloorReconciliationDebt,
	) : RetentionFloorSettlementFailure

	/** The named durable settlement phase did not complete. */
	data class CommitBoundary(
		val phase: RetentionFloorSettlementPhase,
	) : RetentionFloorSettlementFailure

	/** The DataStore mutation may still be running; its operation identity is retained for recovery. */
	data class DataStoreCommitUnknown(
		val operationId: String,
	) : RetentionFloorSettlementFailure {
		init {
			require(operationId.isNotBlank())
		}
	}
}

/** Durable milestones used to resume an interrupted retention-floor change. */
enum class RetentionFloorSettlementPhase {
	REQUESTED_PREPARED,
	DATASTORE_FLOOR_ACKNOWLEDGED,
	ROOM_GUARD_COMMITTED,
	AUTHORITY_REISSUED,
	SOURCE_RECONCILIATION_COMPLETED,
}

private data class RetentionAuthorityEvaluation(
	val activeLocalSources: Set<AmbientTrackingSource>,
	val failures: List<RetentionFloorSettlementFailure>,
)


private sealed interface RetentionFloorLeaseResult {
	data class Prepared(
		val lifecycle: CollectedDataLifecycleSnapshot,
		val activeLocalSources: Set<AmbientTrackingSource>,
		val operation: RetentionFloorSettlementOperation,
	) : RetentionFloorLeaseResult

	/** Settlement remains incomplete and needs another bounded attempt. */
	data class Retryable(
		val result: RetentionFloorSettlementResult.Retryable,
	) : RetentionFloorLeaseResult
}
