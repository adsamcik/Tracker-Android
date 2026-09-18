package com.adsamcik.tracker.app.maintenance

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.synchronizeLifecycle
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleSnapshot
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.shared.preferences.lifecycle.advanceRetainedFromWithPermit
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityOperationLease
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityProducer
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityResult
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityScope
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityState
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityUnavailableReason
import com.adsamcik.tracker.shared.preferences.retention.reconcileCurrentSettingsWithPermit
import com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent
import com.adsamcik.tracker.tracker.api.AmbientTrackingSource
import com.adsamcik.tracker.tracker.api.AmbientStepsProviderLifecycle
import com.adsamcik.tracker.tracker.api.AmbientStepsSettingsReconciliationFailure
import com.adsamcik.tracker.tracker.api.AmbientStepsSettingsReconciliationResult
import com.adsamcik.tracker.tracker.api.NoOpAmbientStepsProviderLifecycle
import com.adsamcik.tracker.tracker.api.TrackingRetentionFloorReconciler
import com.adsamcik.tracker.tracker.api.TrackingRetentionFloorReconciliationDebt
import com.adsamcik.tracker.tracker.api.TrackingRetentionFloorReconciliationFailure
import com.adsamcik.tracker.tracker.api.TrackingRetentionFloorReconciliationFailureReason
import com.adsamcik.tracker.tracker.api.TrackingRetentionFloorReconciliationResult
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

@Singleton
class RetentionFloorSettlement @Inject constructor(
	private val operationLease: RetentionAuthorityOperationLease,
	private val retentionAuthorityProducer: RetentionAuthorityProducer,
	private val purposeReconciler: TrackingRetentionFloorReconciler,
	private val ambientStepsProviderLifecycle: Provider<AmbientStepsProviderLifecycle> =
		Provider { NoOpAmbientStepsProviderLifecycle },
) {
	suspend fun settle(
		database: AppDatabase,
		lifecycleStore: CollectedDataLifecycleStore,
		startupGate: TrackingStartupGate,
		expectedStartupGeneration: Long,
		requestedRetainedFromMs: Long,
		updatedAtMs: Long,
		verifyApprovedOperation: () -> Unit,
	): RetentionFloorSettlementResult {
		require(requestedRetainedFromMs >= 0L)
		require(updatedAtMs >= 0L)
		val commit = startupGate.withReadyGenerationOperation(expectedStartupGeneration) {
			withContext(NonCancellable) {
				operationLease.withPermit { permit ->
					verifyApprovedOperation()
					val lifecycle = lifecycleStore.advanceRetainedFromWithPermit(
						requestedRetainedFromMs,
						permit,
					)
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
								check(sourceEvidenceStateDao.incrementRevision(updatedAtMs) == 1) {
									"Unable to advance source-evidence revision for retention floor"
								}
							}
						} finally {
							verifyApprovedOperation()
						}
					}
					val retentionResults = try {
						retentionAuthorityProducer.reconcileCurrentSettingsWithPermit(permit)
					} catch (cancelled: CancellationException) {
						throw cancelled
					} catch (_: Exception) {
						RETENTION_PROVIDER_SOURCES.keys.map { source ->
							RetentionAuthorityResult.Unavailable(
								source = source,
								scope = RetentionAuthorityScope.LIVE_AMBIENT,
								reason = RetentionAuthorityUnavailableReason.STORAGE_UNAVAILABLE,
							)
						}
					}
					RetentionFloorCommit(lifecycle, retentionResults)
				}
			}
		} ?: return RetentionFloorSettlementResult.StartupGenerationChanged

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
			withContext(NonCancellable) {
				purposeReconciler.reconcile(
					expectedStartupGeneration,
					retainedFromMs,
					approvedSources,
				)
			}
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
		if (AmbientTrackingSource.STEPS in approvedSources) {
			val stepsResult = try {
				withContext(NonCancellable) {
					startupGate.withReadyGenerationOperation(expectedStartupGeneration) {
						ambientStepsProviderLifecycle.get().reconcileAfterSettingsChange()
					}
				} ?: AmbientStepsSettingsReconciliationResult(
					complete = false,
					operational = false,
					failure =
						AmbientStepsSettingsReconciliationFailure
							.AUTHORITY_CHANGED_DURING_ACTIVATION,
					retryable = true,
				)
			} catch (cancelled: CancellationException) {
				throw cancelled
			} catch (_: Exception) {
				AmbientStepsSettingsReconciliationResult(
					complete = false,
					operational = false,
					failure =
						AmbientStepsSettingsReconciliationFailure.PROVIDER_ACTIVATION_FAILED,
					retryable = true,
				)
			}
			val exactStepsResult = if (
				stepsResult.complete &&
				!startupGate.isReadyGeneration(expectedStartupGeneration)
			) {
				AmbientStepsSettingsReconciliationResult(
					complete = false,
					operational = false,
					failure =
						AmbientStepsSettingsReconciliationFailure
							.AUTHORITY_CHANGED_DURING_ACTIVATION,
					retryable = true,
				)
			} else {
				stepsResult
			}
			if (!exactStepsResult.complete) {
				failures +=
					RetentionFloorSettlementFailure.AmbientStepsLifecycle(exactStepsResult)
			}
		}
		return if (failures.isEmpty()) {
			RetentionFloorSettlementResult.Settled(commit.lifecycle, approvedSources)
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

	data class AmbientStepsLifecycle(
		val result: AmbientStepsSettingsReconciliationResult,
	) : RetentionFloorSettlementFailure {
		init {
			require(!result.complete)
		}
	}
}

private data class RetentionFloorCommit(
	val lifecycle: CollectedDataLifecycleSnapshot,
	val retentionResults: List<RetentionAuthorityResult>,
)
