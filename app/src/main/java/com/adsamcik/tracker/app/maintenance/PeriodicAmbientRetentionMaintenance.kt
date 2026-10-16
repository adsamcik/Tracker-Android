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

/** Applies the settled retention floor independently to all-day Steps, Wi-Fi and Cell. */
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

	/** Prunes each source under continuation checks, retaining individual failures for retry. Times use UTC milliseconds. */
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
			localStepsFailureReason(database, floor, lifecycle.epoch, appliedAtMs, verifyExecutionContinuation)
		}
		captureFailure(PeriodicAmbientRetentionSource.IMPORTED_STEPS, failures) {
			verifyExecutionContinuation()
			importedStepsFailureReason(database, floor, lifecycle.epoch, appliedAtMs, verifyExecutionContinuation)
		}
		captureFailure(PeriodicAmbientRetentionSource.WIFI, failures) {
			verifyExecutionContinuation()
			wifiFailureReason(database, floor, lifecycle.epoch, appliedAtMs, verifyExecutionContinuation)
		}
		captureFailure(PeriodicAmbientRetentionSource.CELL, failures) {
			verifyExecutionContinuation()
			cellFailureReason(database, floor, lifecycle.epoch, appliedAtMs, verifyExecutionContinuation)
		}
		return if (failures.isEmpty()) {
			PeriodicAmbientRetentionResult.Complete
		} else {
			PeriodicAmbientRetentionResult.Retryable(failures)
		}
	}

	private suspend fun localStepsFailureReason(
		database: AppDatabase,
		floor: Long,
		epoch: Long,
		appliedAtMs: Long,
		verifyExecutionContinuation: suspend () -> Unit,
	): PeriodicAmbientRetentionFailureReason? =
		when (val result = localSteps(database, floor, epoch, appliedAtMs, verifyExecutionContinuation)) {
			LocalAmbientStepsRetentionResult.NoChange,
			is LocalAmbientStepsRetentionResult.Pruned -> null
			is LocalAmbientStepsRetentionResult.Unavailable -> result.reason
		}

	private suspend fun importedStepsFailureReason(
		database: AppDatabase,
		floor: Long,
		epoch: Long,
		appliedAtMs: Long,
		verifyExecutionContinuation: suspend () -> Unit,
	): PeriodicAmbientRetentionFailureReason? =
		when (importedSteps(database, TruncateImportedAmbientStepsRetentionRequest(
			retainedFromMs = floor, expectedCollectedDataEpoch = epoch, retainedAtMs = appliedAtMs,
		), verifyExecutionContinuation)) {
			TruncateImportedAmbientStepsRetentionResult.Complete -> null
			is TruncateImportedAmbientStepsRetentionResult.Retained -> PeriodicAmbientRetentionFailureReason.INCOMPLETE
			is TruncateImportedAmbientStepsRetentionResult.Blocked -> PeriodicAmbientRetentionFailureReason.BLOCKED
			is TruncateImportedAmbientStepsRetentionResult.Unverifiable ->
				PeriodicAmbientRetentionFailureReason.UNVERIFIABLE
			is TruncateImportedAmbientStepsRetentionResult.RetryableFailure ->
				PeriodicAmbientRetentionFailureReason.RETRYABLE
		}

	private suspend fun wifiFailureReason(
		database: AppDatabase,
		floor: Long,
		epoch: Long,
		appliedAtMs: Long,
		verifyExecutionContinuation: suspend () -> Unit,
	): PeriodicAmbientRetentionFailureReason? =
		when (wifi(database, AmbientWifiRetentionCommand(floor, epoch, appliedAtMs), verifyExecutionContinuation)) {
			AmbientWifiRetentionResult.NoChange,
			is AmbientWifiRetentionResult.Pruned -> null
			is AmbientWifiRetentionResult.Unavailable -> PeriodicAmbientRetentionFailureReason.UNVERIFIABLE
		}

	private suspend fun cellFailureReason(
		database: AppDatabase,
		floor: Long,
		epoch: Long,
		appliedAtMs: Long,
		verifyExecutionContinuation: suspend () -> Unit,
	): PeriodicAmbientRetentionFailureReason? =
		when (cell(database, AmbientCellRetentionCommand(floor, epoch, appliedAtMs), verifyExecutionContinuation)) {
			AmbientCellRetentionResult.NoChange,
			is AmbientCellRetentionResult.Pruned -> null
			is AmbientCellRetentionResult.Unavailable -> PeriodicAmbientRetentionFailureReason.UNVERIFIABLE
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
		} catch (aborted: RetentionExecutionAbortException) {
			throw aborted
		} catch (_: Exception) {
			PeriodicAmbientRetentionFailureReason.STORAGE_UNAVAILABLE
		}
		if (reason != null) {
			failures += PeriodicAmbientRetentionFailure(source, reason)
		}
	}
}

/** A retention attempt must exit immediately when its owned execution stops or is deferred. */
internal sealed class RetentionExecutionAbortException : RuntimeException()

internal class RetentionExecutionStoppedException : RetentionExecutionAbortException()

internal class RetentionExecutionDeferredException : RetentionExecutionAbortException()

/** Result of pruning native all-day step revisions in the current collected-data epoch. */
sealed interface LocalAmbientStepsRetentionResult {
	/** Positive count of removed revisions. */
	data class Pruned(val deletedRevisionCount: Int) : LocalAmbientStepsRetentionResult {
		init {
			require(deletedRevisionCount > 0)
		}
	}

	/** No eligible native step revisions needed removal. */
	data object NoChange : LocalAmbientStepsRetentionResult

	/** This source could not safely complete its pruning operation. */
	data class Unavailable(
		val reason: PeriodicAmbientRetentionFailureReason,
	) : LocalAmbientStepsRetentionResult
}

/** Combined maintenance result that preserves source-specific retry reasons. */
sealed interface PeriodicAmbientRetentionResult {
	/** Every selected maintenance operation reached its settled floor. */
	data object Complete : PeriodicAmbientRetentionResult

	/** One or more distinct sources need another bounded attempt. */
	data class Retryable(
		val failures: List<PeriodicAmbientRetentionFailure>,
	) : PeriodicAmbientRetentionResult {
		init {
			require(failures.isNotEmpty())
			require(failures.distinctBy(PeriodicAmbientRetentionFailure::source).size == failures.size)
		}
	}
}

/** Source and payload-free reason for a failed periodic pruning attempt. */
data class PeriodicAmbientRetentionFailure(
	val source: PeriodicAmbientRetentionSource,
	val reason: PeriodicAmbientRetentionFailureReason,
)

/** Separately maintained all-day data stores; imported Steps retain their own workflow. */
enum class PeriodicAmbientRetentionSource {
	LOCAL_STEPS,
	IMPORTED_STEPS,
	WIFI,
	CELL,
}

/** Distinguishes incomplete batches, authority problems and retryable storage failures. */
enum class PeriodicAmbientRetentionFailureReason {
	BLOCKED,
	UNVERIFIABLE,
	INCOMPLETE,
	RETRYABLE,
	STORAGE_UNAVAILABLE,
}
