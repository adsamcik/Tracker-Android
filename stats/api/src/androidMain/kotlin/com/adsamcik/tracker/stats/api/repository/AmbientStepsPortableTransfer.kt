package com.adsamcik.tracker.stats.api.repository

import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsArchiveV1

data class ExportPortableAmbientStepsRequest(
	val fromInclusiveMs: Long,
	val toExclusiveMs: Long,
) {
	init {
		require(fromInclusiveMs >= 0L)
		require(toExclusiveMs > fromInclusiveMs)
	}
}

fun interface PortableAmbientStepsArchiveSink {
	/** Receives one complete archive only after its bounded Room snapshot has closed. */
	suspend fun emit(archive: PortableAmbientStepsArchiveV1)
}

/** Dormant source-local export contract. It performs no acquisition or provider registration. */
interface ExportPortableAmbientSteps {
	/**
	 * Emits exactly one complete archive or nothing. Corrupt, materializing, deleted, overflowing,
	 * and cancelled snapshots must never expose a partial artifact.
	 */
	suspend fun export(
		request: ExportPortableAmbientStepsRequest,
		sink: PortableAmbientStepsArchiveSink,
	): ExportPortableAmbientStepsResult
}

sealed interface ExportPortableAmbientStepsResult {
	data class Exported(
		val dayCount: Int,
		val factCount: Int,
		val gapCount: Int,
	) : ExportPortableAmbientStepsResult {
		init {
			require(dayCount > 0)
			require(factCount > 0)
			require(gapCount >= 0)
		}
	}

	data object NoData : ExportPortableAmbientStepsResult

	data class Unverifiable(
		val reason: PortableAmbientStepsExportUnverifiableReason,
	) : ExportPortableAmbientStepsResult

	data class RetryableFailure(
		val reason: PortableAmbientStepsExportRetryableReason,
	) : ExportPortableAmbientStepsResult
}

enum class PortableAmbientStepsExportUnverifiableReason {
	SOURCE_AUTHORITY_UNAVAILABLE,
	DELETION_PENDING,
	CORRUPT_RETAINED_STATE,
	RETENTION_CROSSES_FACT,
	MATERIALIZING,
	DEPENDENCY_OVERFLOW,
}

enum class PortableAmbientStepsExportRetryableReason { STORAGE_UNAVAILABLE }
