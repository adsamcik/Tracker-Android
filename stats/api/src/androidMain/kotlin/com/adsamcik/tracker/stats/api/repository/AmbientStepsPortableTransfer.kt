package com.adsamcik.tracker.stats.api.repository

import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableDigest
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableFormatV1
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableOpaqueIdentity
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

/** Imported-origin-only re-export. Native/imported union remains a separate product assembly. */
interface ReexportImportedAmbientSteps : ExportPortableAmbientSteps

/** Immutable file/import provenance copied into Ambient Steps portable-origin storage. */
data class PortableAmbientStepsImportReceipt(
	val jobId: String,
	val archiveKey: String,
	val sourceName: String,
	val receivedAtMs: Long,
) {
	init {
		listOf(jobId, archiveKey, sourceName).forEach { value ->
			require(value.isNotBlank())
			require(value.length <= AmbientStepsPortableFormatV1.MAX_IMPORT_RECEIPT_FIELD_LENGTH)
		}
		require(receivedAtMs >= 0L)
	}
}

/** Decoder-owned preflight metadata; no archive is admitted from an unbounded byte source. */
data class PortableAmbientStepsImportMetadata(
	val encodedByteCount: Long,
	val archiveContentChecksum: AmbientStepsPortableDigest,
	val dayCount: Int,
	val factCount: Int,
	val gapCount: Int,
) {
	init {
		require(encodedByteCount in 1L..AmbientStepsPortableFormatV1.MAX_FILE_BYTES)
		require(dayCount in 1..AmbientStepsPortableFormatV1.MAX_DAYS)
		require(factCount in 1..AmbientStepsPortableFormatV1.MAX_FACTS)
		require(gapCount in 0..AmbientStepsPortableFormatV1.MAX_GAPS)
	}
}

data class ImportPortableAmbientStepsRequest(
	val archive: PortableAmbientStepsArchiveV1,
	val receipt: PortableAmbientStepsImportReceipt,
	val metadata: PortableAmbientStepsImportMetadata,
	val expectedCollectedDataEpoch: Long,
) {
	init {
		require(expectedCollectedDataEpoch >= 0L)
	}
}

/** Portable-origin admission never creates provider, session, consent, WAL, or writer authority. */
interface ImportPortableAmbientSteps {
	suspend fun importArchive(
		request: ImportPortableAmbientStepsRequest,
	): ImportPortableAmbientStepsResult
}

sealed interface ImportPortableAmbientStepsResult {
	data class Applied(
		val archiveIdentity: AmbientStepsPortableOpaqueIdentity,
		val appendedDayRevisionCount: Int,
		val dayCount: Int,
		val factCount: Int,
		val gapCount: Int,
	) : ImportPortableAmbientStepsResult {
		init {
			require(appendedDayRevisionCount in 0..dayCount)
			require(dayCount > 0)
			require(factCount > 0)
			require(gapCount >= 0)
		}
	}

	data class Duplicate(
		val archiveIdentity: AmbientStepsPortableOpaqueIdentity,
		val dayCount: Int,
	) : ImportPortableAmbientStepsResult {
		init {
			require(dayCount > 0)
		}
	}

	data class Blocked(
		val reason: PortableAmbientStepsImportBlockedReason,
	) : ImportPortableAmbientStepsResult

	data class Unverifiable(
		val reason: PortableAmbientStepsImportUnverifiableReason,
	) : ImportPortableAmbientStepsResult

	data class RetryableFailure(
		val reason: PortableAmbientStepsTransferRetryableReason,
	) : ImportPortableAmbientStepsResult
}

enum class PortableAmbientStepsImportBlockedReason {
	COLLECTED_DATA_EPOCH_CHANGED,
	RETENTION_POLICY_UNAVAILABLE,
	RETENTION_BOUNDARY,
	DELETED_DAY,
	RETAINED_DAY,
	SOURCE_DELETED,
	RECEIPT_CONFLICT,
	OPAQUE_IDENTITY_CONFLICT,
	LOCAL_ORIGIN_OVERLAP,
	CORRECTION_CONFLICT,
}

enum class PortableAmbientStepsImportUnverifiableReason {
	ARCHIVE_INVALID,
	METADATA_MISMATCH,
	SOURCE_EVIDENCE_STATE_MISSING,
	STORED_EVIDENCE_UNVERIFIABLE,
	DEPENDENCY_OVERFLOW,
	REVISION_OVERFLOW,
}

data class DeleteImportedAmbientStepsDayRequest(
	val dayIdentity: AmbientStepsPortableOpaqueIdentity,
	val expectedCollectedDataEpoch: Long,
	val deletedAtMs: Long,
) {
	init {
		require(expectedCollectedDataEpoch >= 0L)
		require(deletedAtMs >= 0L)
	}
}

interface DeleteImportedAmbientStepsDay {
	suspend fun deleteDay(
		request: DeleteImportedAmbientStepsDayRequest,
	): DeleteImportedAmbientStepsDayResult
}

sealed interface DeleteImportedAmbientStepsDayResult {
	data class Deleted(val removedRevisionCount: Int) : DeleteImportedAmbientStepsDayResult {
		init {
			require(removedRevisionCount > 0)
		}
	}

	data object AlreadyDeleted : DeleteImportedAmbientStepsDayResult
	data object Retained : DeleteImportedAmbientStepsDayResult
	data object NotFound : DeleteImportedAmbientStepsDayResult

	data class Blocked(
		val reason: ImportedAmbientStepsMutationBlockedReason,
	) : DeleteImportedAmbientStepsDayResult

	data class Unverifiable(
		val reason: ImportedAmbientStepsMutationUnverifiableReason,
	) : DeleteImportedAmbientStepsDayResult

	data class RetryableFailure(
		val reason: PortableAmbientStepsTransferRetryableReason,
	) : DeleteImportedAmbientStepsDayResult
}

data class TruncateImportedAmbientStepsRetentionRequest(
	val retainedFromMs: Long,
	val expectedCollectedDataEpoch: Long,
	val retainedAtMs: Long,
) {
	init {
		require(retainedFromMs >= 0L)
		require(expectedCollectedDataEpoch >= 0L)
		require(retainedAtMs >= retainedFromMs)
	}
}

interface TruncateImportedAmbientStepsRetention {
	/** Removes at most one complete correction lineage per call. */
	suspend fun truncateNext(
		request: TruncateImportedAmbientStepsRetentionRequest,
	): TruncateImportedAmbientStepsRetentionResult
}

sealed interface TruncateImportedAmbientStepsRetentionResult {
	data class Retained(val removedRevisionCount: Int) :
		TruncateImportedAmbientStepsRetentionResult {
		init {
			require(removedRevisionCount > 0)
		}
	}

	data object Complete : TruncateImportedAmbientStepsRetentionResult

	data class Blocked(
		val reason: ImportedAmbientStepsMutationBlockedReason,
	) : TruncateImportedAmbientStepsRetentionResult

	data class Unverifiable(
		val reason: ImportedAmbientStepsMutationUnverifiableReason,
	) : TruncateImportedAmbientStepsRetentionResult

	data class RetryableFailure(
		val reason: PortableAmbientStepsTransferRetryableReason,
	) : TruncateImportedAmbientStepsRetentionResult
}

enum class ImportedAmbientStepsMutationBlockedReason {
	COLLECTED_DATA_EPOCH_CHANGED,
	RETENTION_FLOOR_CHANGED,
	CONSENT_NOT_REVOKED,
	SOURCE_DELETION_PENDING,
}

enum class ImportedAmbientStepsMutationUnverifiableReason {
	SOURCE_EVIDENCE_STATE_MISSING,
	STORED_EVIDENCE_UNVERIFIABLE,
	DEPENDENCY_OVERFLOW,
}

data class DeleteImportedAmbientStepsAfterConsentResetRequest(
	val expectedCollectedDataEpoch: Long,
	val expectedRevokedConsentEpoch: Long,
	val deletedAtMs: Long,
) {
	init {
		require(expectedCollectedDataEpoch >= 0L)
		require(expectedRevokedConsentEpoch >= 0L)
		require(deletedAtMs >= 0L)
	}
}

interface DeleteImportedAmbientStepsAfterConsentReset {
	/** Fences and removes at most one imported correction lineage per call. */
	suspend fun deleteNext(
		request: DeleteImportedAmbientStepsAfterConsentResetRequest,
	): DeleteImportedAmbientStepsAfterConsentResetResult
}

sealed interface DeleteImportedAmbientStepsAfterConsentResetResult {
	data class Deleted(val removedRevisionCount: Int) :
		DeleteImportedAmbientStepsAfterConsentResetResult {
		init {
			require(removedRevisionCount > 0)
		}
	}

	data object Complete : DeleteImportedAmbientStepsAfterConsentResetResult

	data class Blocked(
		val reason: ImportedAmbientStepsMutationBlockedReason,
	) : DeleteImportedAmbientStepsAfterConsentResetResult

	data class Unverifiable(
		val reason: ImportedAmbientStepsMutationUnverifiableReason,
	) : DeleteImportedAmbientStepsAfterConsentResetResult

	data class RetryableFailure(
		val reason: PortableAmbientStepsTransferRetryableReason,
	) : DeleteImportedAmbientStepsAfterConsentResetResult
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
	IMPORTED_DAY_DELETED,
	IMPORTED_DAY_RETAINED,
}

enum class PortableAmbientStepsExportRetryableReason { STORAGE_UNAVAILABLE }

enum class PortableAmbientStepsTransferRetryableReason {
	CONCURRENT_STATE_CHANGE,
	STORAGE_UNAVAILABLE,
}
