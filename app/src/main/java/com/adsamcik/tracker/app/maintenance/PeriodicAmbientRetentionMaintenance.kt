package com.adsamcik.tracker.app.maintenance

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AmbientCellRetentionCommand
import com.adsamcik.tracker.shared.base.database.AmbientCellRetentionResult
import com.adsamcik.tracker.shared.base.database.AmbientWifiRetentionCommand
import com.adsamcik.tracker.shared.base.database.AmbientWifiRetentionResult
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.pruneAmbientCell
import com.adsamcik.tracker.shared.base.database.pruneAmbientWifi
import com.adsamcik.tracker.shared.base.database.pruneAuthenticatedAmbientStepsFactsAffectedByRetentionFloor
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleSnapshot
import com.adsamcik.tracker.stats.api.repository.TruncateImportedAmbientStepsRetention
import com.adsamcik.tracker.stats.api.repository.TruncateImportedAmbientStepsRetentionRequest
import com.adsamcik.tracker.stats.api.repository.TruncateImportedAmbientStepsRetentionResult
import javax.inject.Inject
import javax.inject.Provider
import kotlin.coroutines.cancellation.CancellationException

class PeriodicAmbientRetentionMaintenance internal constructor(
	private val localSteps: suspend (
		AppDatabase,
		Long,
		Long,
		Long,
		suspend () -> Unit,
	) -> LocalAmbientStepsRetentionResult,
	private val importedSteps: suspend (
		AppDatabase,
		TruncateImportedAmbientStepsRetentionRequest,
		suspend () -> Unit,
	) -> TruncateImportedAmbientStepsRetentionResult,
	private val wifi: suspend (
		AppDatabase,
		AmbientWifiRetentionCommand,
		suspend () -> Unit,
	) -> AmbientWifiRetentionResult,
	private val cell: suspend (
		AppDatabase,
		AmbientCellRetentionCommand,
		suspend () -> Unit,
	) -> AmbientCellRetentionResult,
) {
	@Inject
	constructor(
		importedStepsRetention: Provider<TruncateImportedAmbientStepsRetention>,
	) : this(
		localSteps = { database, floor, epoch, appliedAtMs, verifyExecutionContinuation ->
			database.withTransaction {
				verifyExecutionContinuation()
				val deleted = database.pruneAuthenticatedAmbientStepsFactsAffectedByRetentionFloor(
					beforeMs = floor,
					collectedDataEpoch = epoch,
					markedAtMs = appliedAtMs,
				)
				verifyExecutionContinuation()
				if (deleted == 0) {
					LocalAmbientStepsRetentionResult.NoChange
				} else {
					LocalAmbientStepsRetentionResult.Pruned(deleted)
				}
			}
		},
		importedSteps = { database, request, verifyExecutionContinuation ->
			database.withTransaction {
				verifyExecutionContinuation()
				val result = importedStepsRetention.get().truncateNext(request)
				verifyExecutionContinuation()
				result
			}
		},
		wifi = { database, command, verifyExecutionContinuation ->
			database.withTransaction {
				verifyExecutionContinuation()
				val result = database.pruneAmbientWifi(command)
				verifyExecutionContinuation()
				result
			}
		},
		cell = { database, command, verifyExecutionContinuation ->
			database.withTransaction {
				verifyExecutionContinuation()
				val result = database.pruneAmbientCell(command)
				verifyExecutionContinuation()
				result
			}
		},
	)

	suspend fun run(
		database: AppDatabase,
		lifecycle: CollectedDataLifecycleSnapshot,
		appliedAtMs: Long,
		verifyExecutionContinuation: suspend () -> Unit,
	): PeriodicAmbientRetentionResult {
		val floor = requireNotNull(lifecycle.retainedFromMs) {
			"Ambient retention requires a settled retained-from floor"
		}
		require(appliedAtMs >= floor)
		val failures = mutableListOf<PeriodicAmbientRetentionFailure>()

		captureFailure(PeriodicAmbientRetentionSource.LOCAL_STEPS, failures) {
			verifyExecutionContinuation()
			when (
				val result = localSteps(
					database,
					floor,
					lifecycle.epoch,
					appliedAtMs,
					verifyExecutionContinuation,
				)
			) {
				LocalAmbientStepsRetentionResult.NoChange,
				is LocalAmbientStepsRetentionResult.Pruned,
				-> null
				is LocalAmbientStepsRetentionResult.Unavailable -> result.reason
			}
		}
		captureFailure(PeriodicAmbientRetentionSource.IMPORTED_STEPS, failures) {
			verifyExecutionContinuation()
			when (importedSteps(
				database,
				TruncateImportedAmbientStepsRetentionRequest(
					retainedFromMs = floor,
					expectedCollectedDataEpoch = lifecycle.epoch,
					retainedAtMs = appliedAtMs,
				),
				verifyExecutionContinuation,
			)) {
				TruncateImportedAmbientStepsRetentionResult.Complete -> null
				is TruncateImportedAmbientStepsRetentionResult.Retained ->
					PeriodicAmbientRetentionFailureReason.INCOMPLETE
				is TruncateImportedAmbientStepsRetentionResult.Blocked ->
					PeriodicAmbientRetentionFailureReason.BLOCKED
				is TruncateImportedAmbientStepsRetentionResult.Unverifiable ->
					PeriodicAmbientRetentionFailureReason.UNVERIFIABLE
				is TruncateImportedAmbientStepsRetentionResult.RetryableFailure ->
					PeriodicAmbientRetentionFailureReason.RETRYABLE
			}
		}
		captureFailure(PeriodicAmbientRetentionSource.WIFI, failures) {
			verifyExecutionContinuation()
			when (wifi(
				database,
				AmbientWifiRetentionCommand(floor, lifecycle.epoch, appliedAtMs),
				verifyExecutionContinuation,
			)) {
				AmbientWifiRetentionResult.NoChange,
				is AmbientWifiRetentionResult.Pruned -> null
				is AmbientWifiRetentionResult.Unavailable ->
					PeriodicAmbientRetentionFailureReason.UNVERIFIABLE
			}
		}
		captureFailure(PeriodicAmbientRetentionSource.CELL, failures) {
			verifyExecutionContinuation()
			when (cell(
				database,
				AmbientCellRetentionCommand(floor, lifecycle.epoch, appliedAtMs),
				verifyExecutionContinuation,
			)) {
				AmbientCellRetentionResult.NoChange,
				is AmbientCellRetentionResult.Pruned -> null
				is AmbientCellRetentionResult.Unavailable ->
					PeriodicAmbientRetentionFailureReason.UNVERIFIABLE
			}
		}

		return if (failures.isEmpty()) {
			PeriodicAmbientRetentionResult.Complete
		} else {
			PeriodicAmbientRetentionResult.Retryable(failures)
		}
	}

	private suspend fun captureFailure(
		source: PeriodicAmbientRetentionSource,
		failures: MutableList<PeriodicAmbientRetentionFailure>,
		operation: suspend () -> PeriodicAmbientRetentionFailureReason?,
	) {
		val reason = try {
			operation()
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (stopped: RetentionExecutionStoppedException) {
			throw stopped
		} catch (deferred: RetentionExecutionDeferredException) {
			throw deferred
		} catch (_: Exception) {
			PeriodicAmbientRetentionFailureReason.STORAGE_UNAVAILABLE
		}
		if (reason != null) {
			failures += PeriodicAmbientRetentionFailure(source, reason)
		}
	}
}

internal class RetentionExecutionStoppedException : RuntimeException()

internal class RetentionExecutionDeferredException : RuntimeException()

sealed interface LocalAmbientStepsRetentionResult {
	data class Pruned(val deletedRevisionCount: Int) : LocalAmbientStepsRetentionResult {
		init {
			require(deletedRevisionCount > 0)
		}
	}

	data object NoChange : LocalAmbientStepsRetentionResult

	data class Unavailable(
		val reason: PeriodicAmbientRetentionFailureReason,
	) : LocalAmbientStepsRetentionResult
}

sealed interface PeriodicAmbientRetentionResult {
	data object Complete : PeriodicAmbientRetentionResult

	data class Retryable(
		val failures: List<PeriodicAmbientRetentionFailure>,
	) : PeriodicAmbientRetentionResult {
		init {
			require(failures.isNotEmpty())
			require(failures.distinctBy(PeriodicAmbientRetentionFailure::source).size == failures.size)
		}
	}
}

data class PeriodicAmbientRetentionFailure(
	val source: PeriodicAmbientRetentionSource,
	val reason: PeriodicAmbientRetentionFailureReason,
)

enum class PeriodicAmbientRetentionSource {
	LOCAL_STEPS,
	IMPORTED_STEPS,
	WIFI,
	CELL,
}

enum class PeriodicAmbientRetentionFailureReason {
	BLOCKED,
	UNVERIFIABLE,
	INCOMPLETE,
	RETRYABLE,
	STORAGE_UNAVAILABLE,
}
