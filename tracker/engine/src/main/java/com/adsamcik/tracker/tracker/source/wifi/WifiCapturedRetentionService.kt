package com.adsamcik.tracker.tracker.source.wifi

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.tracker.source.coordinator.SourcePlanCodec
import com.adsamcik.tracker.tracker.source.ingress.SourcePayloadCodec
import javax.inject.Inject

/**
 * Narrow app-facing bridge into authenticated captured-Wi-Fi retention.
 *
 * The evidence read is only an optimistic snapshot. [WifiCapturedFactMaintenance] rechecks its
 * epoch, deleted-source high-water, and retained-from cutoff in the mutation transaction. This
 * bridge constructs no runtime, demand, provider, writer lane, or rollout state.
 */
class WifiCapturedRetentionService private constructor(
	private val operation: WifiCapturedRetentionOperation,
) {
	@Inject
	internal constructor(
		payloadCodec: SourcePayloadCodec,
		planCodec: SourcePlanCodec,
	) : this(
		WifiCapturedRetentionOperation { database, beforeMs, epoch, deletionHighWater, markedAtMs ->
			WifiCapturedFactMaintenance(database, payloadCodec, planCodec)
				.pruneAffectedByRetentionFloor(
					beforeMs = beforeMs,
					expectedCollectedDataEpoch = epoch,
					expectedDeletedSourceEventHighWaterOrdinal = deletionHighWater,
					markedAtMs = markedAtMs,
				)
		},
	)

	internal constructor(
		operation: suspend (AppDatabase, Long, Long, Long, Long) -> WifiCapturedRetentionResult,
	) : this(WifiCapturedRetentionOperation(operation))

	suspend fun prune(
		database: AppDatabase,
		beforeMs: Long,
		markedAtMs: Long,
	): WifiCapturedRetentionResult {
		require(beforeMs >= 0L)
		require(markedAtMs >= 0L)
		val evidenceState = database.sourceEvidenceStateDao().get()
			?: return WifiCapturedRetentionResult.Blocked(
				WifiCapturedRetentionBlockedReason.SOURCE_EVIDENCE_AUTHORITY_CHANGED,
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

private fun interface WifiCapturedRetentionOperation {
	suspend fun run(
		database: AppDatabase,
		beforeMs: Long,
		expectedCollectedDataEpoch: Long,
		expectedDeletedSourceEventHighWaterOrdinal: Long,
		markedAtMs: Long,
	): WifiCapturedRetentionResult
}
