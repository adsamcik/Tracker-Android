package com.adsamcik.tracker.app.maintenance

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
import com.adsamcik.tracker.tracker.api.AmbientTrackingSource
import javax.inject.Inject
import javax.inject.Provider
import kotlin.coroutines.cancellation.CancellationException

class PeriodicAmbientRetentionMaintenance internal constructor(
	private val localSteps: suspend (AppDatabase, Long, Long, Long) -> Unit,
	private val importedSteps: suspend (
		TruncateImportedAmbientStepsRetentionRequest,
	) -> TruncateImportedAmbientStepsRetentionResult,
	private val wifi: suspend (
		AppDatabase,
		AmbientWifiRetentionCommand,
	) -> AmbientWifiRetentionResult,
	private val cell: suspend (
		AppDatabase,
		AmbientCellRetentionCommand,
	) -> AmbientCellRetentionResult,
) {
	@Inject
	constructor(
		importedStepsRetention: Provider<TruncateImportedAmbientStepsRetention>,
	) : this(
		localSteps = { database, floor, epoch, appliedAtMs ->
			database.pruneAuthenticatedAmbientStepsFactsAffectedByRetentionFloor(
				beforeMs = floor,
				collectedDataEpoch = epoch,
				markedAtMs = appliedAtMs,
			)
		},
		importedSteps = { request ->
			importedStepsRetention.get().truncateNext(request)
		},
		wifi = AppDatabase::pruneAmbientWifi,
		cell = AppDatabase::pruneAmbientCell,
	)

	suspend fun run(
		database: AppDatabase,
		lifecycle: CollectedDataLifecycleSnapshot,
		activeLocalSources: Set<AmbientTrackingSource>,
		appliedAtMs: Long,
	): PeriodicAmbientRetentionResult {
		val floor = requireNotNull(lifecycle.retainedFromMs) {
			"Ambient retention requires a settled retained-from floor"
		}
		require(appliedAtMs >= floor)
		val failures = mutableListOf<PeriodicAmbientRetentionFailure>()

		if (AmbientTrackingSource.STEPS in activeLocalSources) {
			captureFailure(PeriodicAmbientRetentionSource.LOCAL_STEPS, failures) {
				localSteps(database, floor, lifecycle.epoch, appliedAtMs)
				null
			}
		}
		captureFailure(PeriodicAmbientRetentionSource.IMPORTED_STEPS, failures) {
			when (importedSteps(
				TruncateImportedAmbientStepsRetentionRequest(
					retainedFromMs = floor,
					expectedCollectedDataEpoch = lifecycle.epoch,
					retainedAtMs = appliedAtMs,
				),
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
			when (wifi(
				database,
				AmbientWifiRetentionCommand(floor, lifecycle.epoch, appliedAtMs),
			)) {
				AmbientWifiRetentionResult.NoChange,
				is AmbientWifiRetentionResult.Pruned -> null
				is AmbientWifiRetentionResult.Unavailable ->
					PeriodicAmbientRetentionFailureReason.UNVERIFIABLE
			}
		}
		captureFailure(PeriodicAmbientRetentionSource.CELL, failures) {
			when (cell(
				database,
				AmbientCellRetentionCommand(floor, lifecycle.epoch, appliedAtMs),
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
		} catch (_: Exception) {
			PeriodicAmbientRetentionFailureReason.STORAGE_UNAVAILABLE
		}
		if (reason != null) {
			failures += PeriodicAmbientRetentionFailure(source, reason)
		}
	}
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
