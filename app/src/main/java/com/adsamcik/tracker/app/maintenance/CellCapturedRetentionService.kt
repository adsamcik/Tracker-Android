package com.adsamcik.tracker.app.maintenance

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.CellCapturedRetentionBlockedReason
import com.adsamcik.tracker.shared.base.database.CellCapturedRetentionResult
import com.adsamcik.tracker.shared.base.database.pruneCapturedCellFactsAffectedByRetentionFloor
import javax.inject.Inject

/**
 * Bounded bridge from the app retention policy to authenticated captured-Cell maintenance.
 *
 * The source-evidence snapshot only supplies optimistic authority inputs. The maintenance
 * transaction rechecks the epoch, deletion high-water, and exact retained-from cutoff before any
 * mutation. This service neither changes that authority nor interacts with acquisition runtime.
 */
class CellCapturedRetentionService private constructor(
	private val operation: CellCapturedRetentionOperation,
) {
	@Inject
	constructor() : this(
		CellCapturedRetentionOperation { database, beforeMs, epoch, deletionHighWater, markedAtMs ->
			database.pruneCapturedCellFactsAffectedByRetentionFloor(
				beforeMs = beforeMs,
				expectedCollectedDataEpoch = epoch,
				expectedDeletedSourceEventHighWaterOrdinal = deletionHighWater,
				markedAtMs = markedAtMs,
			)
		},
	)

	internal constructor(
		operation: suspend (AppDatabase, Long, Long, Long, Long) -> CellCapturedRetentionResult,
	) : this(CellCapturedRetentionOperation(operation))

	suspend fun prune(
		database: AppDatabase,
		beforeMs: Long,
		markedAtMs: Long,
	): CellCapturedRetentionResult {
		require(beforeMs >= 0L)
		require(markedAtMs >= 0L)
		val evidenceState = database.sourceEvidenceStateDao().get()
			?: return CellCapturedRetentionResult.Blocked(
				CellCapturedRetentionBlockedReason.SOURCE_EVIDENCE_AUTHORITY_CHANGED,
			)
		return operation.run(
			database,
			beforeMs,
			evidenceState.collectedDataEpoch,
			evidenceState.deletedSourceEventHighWaterOrdinal,
			markedAtMs,
		)
	}
}

private fun interface CellCapturedRetentionOperation {
	suspend fun run(
		database: AppDatabase,
		beforeMs: Long,
		expectedCollectedDataEpoch: Long,
		expectedDeletedSourceEventHighWaterOrdinal: Long,
		markedAtMs: Long,
	): CellCapturedRetentionResult
}
