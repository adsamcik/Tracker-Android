package com.adsamcik.tracker.stats.api.repository

import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableDigest
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableFormatV1
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableOpaqueIdentity
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsArchiveV1

/**
 * Half-open UTC millisecond range for all-day Steps export, or a bounded exact set of imported day
 * identities.
 */
data class ExportPortableAmbientStepsRequest(
	val fromInclusiveMs: Long,
	val toExclusiveMs: Long,
	/** Exact imported-file membership; null selects measured dates. */
	val selectedDayIdentities: Set<String>? = null,
) {
	init {
		require(fromInclusiveMs >= 0L)
		require(toExclusiveMs > fromInclusiveMs)
		require(selectedDayIdentities == null || (selectedDayIdentities.isNotEmpty() &&
			selectedDayIdentities.size <= AmbientStepsPortableFormatV1.MAX_DAYS &&
			selectedDayIdentities.all { it.matches(Regex("sha256:[0-9a-f]{64}")) }))
	}
}

/**
 * Receives a complete all-day Steps archive; emitting does not acquire a sensor or recording
 * permission.
 */
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

/**
 * Decoded all-day Steps archive, bounded decoder metadata and file receipt tied to the expected
 * collected-data epoch.
 */
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
	/**
	 * Admits the complete decoded Steps archive and file provenance under its original data epoch
	 * without creating native recording permission.
	 */
	suspend fun importArchive(
		request: ImportPortableAmbientStepsRequest,
	): ImportPortableAmbientStepsResult
}

/**
 * Admission outcome for an imported all-day Steps archive, with counts separate from rejection and
 * retry states.
 */
sealed interface ImportPortableAmbientStepsResult {
	/**
	 * Reports the imported archive identity and saved day revisions, facts and gaps.
	 */
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

	/**
	 * The same archive is already represented by the reported imported days.
	 */
	data class Duplicate(
		val archiveIdentity: AmbientStepsPortableOpaqueIdentity,
		val dayCount: Int,
	) : ImportPortableAmbientStepsResult {
		init {
			require(dayCount > 0)
		}
	}

	/**
	 * The stated lifecycle, selection or privacy boundary prevents this operation from continuing.
	 */
	data class Blocked(
		val reason: PortableAmbientStepsImportBlockedReason,
	) : ImportPortableAmbientStepsResult

	/**
	 * The required stored scope or source lineage could not be verified for this operation.
	 */
	data class Unverifiable(
		val reason: PortableAmbientStepsImportUnverifiableReason,
	) : ImportPortableAmbientStepsResult

	/**
	 * A temporary storage or concurrent-state failure prevents completion and allows a retry.
	 */
	data class RetryableFailure(
		val reason: PortableAmbientStepsTransferRetryableReason,
	) : ImportPortableAmbientStepsResult
}

/**
 * Existing deletion, retention, identity, correction or data-epoch boundaries that prohibit
 * archive admission.
 */
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

/**
 * Invalid archive/metadata or insufficient stored evidence and bounds to verify the imported Steps
 * lineage.
 */
enum class PortableAmbientStepsImportUnverifiableReason {
	ARCHIVE_INVALID,
	METADATA_MISMATCH,
	SOURCE_EVIDENCE_STATE_MISSING,
	STORED_EVIDENCE_UNVERIFIABLE,
	DEPENDENCY_OVERFLOW,
	REVISION_OVERFLOW,
}

/**
 * Exact imported day identity and expected data epoch, with a nonnegative UTC millisecond deletion
 * time.
 */
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

/**
 * Removes the saved imported correction lineage for one selected Steps day.
 */
interface DeleteImportedAmbientStepsDay {
	/**
	 * Removes and replay-fences the selected imported day lineage after rechecking the expected data
	 * epoch.
	 */
	suspend fun deleteDay(
		request: DeleteImportedAmbientStepsDayRequest,
	): DeleteImportedAmbientStepsDayResult
}

/**
 * Outcome of deleting one imported Steps day, distinguishing prior deletion, retention and missing
 * data.
 */
sealed interface DeleteImportedAmbientStepsDayResult {
	/**
	 * The selected imported day correction lineage was removed; reports its revision count.
	 */
	data class Deleted(val removedRevisionCount: Int) : DeleteImportedAmbientStepsDayResult {
		init {
			require(removedRevisionCount > 0)
		}
	}

	/**
	 * The selected imported day is already deleted and remains replay-fenced.
	 */
	data object AlreadyDeleted : DeleteImportedAmbientStepsDayResult
	/**
	 * Retention already removed the selected imported day rather than an explicit user deletion.
	 */
	data object Retained : DeleteImportedAmbientStepsDayResult
	/**
	 * No saved imported day or corresponding deletion/retention outcome matches the selection.
	 */
	data object NotFound : DeleteImportedAmbientStepsDayResult

	/**
	 * The stated lifecycle, selection or privacy boundary prevents this operation from continuing.
	 */
	data class Blocked(
		val reason: ImportedAmbientStepsMutationBlockedReason,
	) : DeleteImportedAmbientStepsDayResult

	/**
	 * The required stored scope or source lineage could not be verified for this operation.
	 */
	data class Unverifiable(
		val reason: ImportedAmbientStepsMutationUnverifiableReason,
	) : DeleteImportedAmbientStepsDayResult

	/**
	 * A temporary storage or concurrent-state failure prevents completion and allows a retry.
	 */
	data class RetryableFailure(
		val reason: PortableAmbientStepsTransferRetryableReason,
	) : DeleteImportedAmbientStepsDayResult
}

/**
 * Published UTC millisecond retention floor and expected data epoch; maintenance time cannot
 * precede the floor.
 */
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

/**
 * Removes imported Steps correction lineages affected by the published retention floor in bounded
 * calls.
 */
interface TruncateImportedAmbientStepsRetention {
	/** Removes at most one complete correction lineage per call. */
	suspend fun truncateNext(
		request: TruncateImportedAmbientStepsRetentionRequest,
	): TruncateImportedAmbientStepsRetentionResult
}

/**
 * Outcome of one retention batch; Complete means no further affected imported Steps lineage
 * remains.
 */
sealed interface TruncateImportedAmbientStepsRetentionResult {
	/**
	 * One affected imported correction lineage was removed; more work may remain.
	 */
	data class Retained(val removedRevisionCount: Int) :
		TruncateImportedAmbientStepsRetentionResult {
		init {
			require(removedRevisionCount > 0)
		}
	}

	/**
	 * No further imported Steps correction lineage is affected by the requested retention floor.
	 */
	data object Complete : TruncateImportedAmbientStepsRetentionResult

	/**
	 * The stated lifecycle, selection or privacy boundary prevents this operation from continuing.
	 */
	data class Blocked(
		val reason: ImportedAmbientStepsMutationBlockedReason,
	) : TruncateImportedAmbientStepsRetentionResult

	/**
	 * The required stored scope or source lineage could not be verified for this operation.
	 */
	data class Unverifiable(
		val reason: ImportedAmbientStepsMutationUnverifiableReason,
	) : TruncateImportedAmbientStepsRetentionResult

	/**
	 * A temporary storage or concurrent-state failure prevents completion and allows a retry.
	 */
	data class RetryableFailure(
		val reason: PortableAmbientStepsTransferRetryableReason,
	) : TruncateImportedAmbientStepsRetentionResult
}

/**
 * Data-epoch, retention, consent or pending source-deletion changes that prevent this imported
 * Steps mutation.
 */
enum class ImportedAmbientStepsMutationBlockedReason {
	COLLECTED_DATA_EPOCH_CHANGED,
	RETENTION_FLOOR_CHANGED,
	CONSENT_NOT_REVOKED,
	SOURCE_DELETION_PENDING,
}

/**
 * Missing source state, invalid stored lineage or exceeded dependencies that prevent a complete
 * imported mutation.
 */
enum class ImportedAmbientStepsMutationUnverifiableReason {
	SOURCE_EVIDENCE_STATE_MISSING,
	STORED_EVIDENCE_UNVERIFIABLE,
	DEPENDENCY_OVERFLOW,
}

/**
 * Expected data and revoked-consent epochs, plus the UTC millisecond time for the imported source
 * erase.
 */
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

/**
 * Fences and removes imported Steps correction lineages after the specified source consent reset.
 */
interface DeleteImportedAmbientStepsAfterConsentReset {
	/** Fences and removes at most one imported correction lineage per call. */
	suspend fun deleteNext(
		request: DeleteImportedAmbientStepsAfterConsentResetRequest,
	): DeleteImportedAmbientStepsAfterConsentResetResult
}

/**
 * Outcome of one consent-reset erase batch, distinguishing progress from complete, blocked and
 * retry states.
 */
sealed interface DeleteImportedAmbientStepsAfterConsentResetResult {
	/**
	 * One imported correction lineage was fenced and removed; reports the removed revision count.
	 */
	data class Deleted(val removedRevisionCount: Int) :
		DeleteImportedAmbientStepsAfterConsentResetResult {
		init {
			require(removedRevisionCount > 0)
		}
	}

	/**
	 * No further imported Steps correction lineage remains to erase for the consent reset.
	 */
	data object Complete : DeleteImportedAmbientStepsAfterConsentResetResult

	/**
	 * The stated lifecycle, selection or privacy boundary prevents this operation from continuing.
	 */
	data class Blocked(
		val reason: ImportedAmbientStepsMutationBlockedReason,
	) : DeleteImportedAmbientStepsAfterConsentResetResult

	/**
	 * The required stored scope or source lineage could not be verified for this operation.
	 */
	data class Unverifiable(
		val reason: ImportedAmbientStepsMutationUnverifiableReason,
	) : DeleteImportedAmbientStepsAfterConsentResetResult

	/**
	 * A temporary storage or concurrent-state failure prevents completion and allows a retry.
	 */
	data class RetryableFailure(
		val reason: PortableAmbientStepsTransferRetryableReason,
	) : DeleteImportedAmbientStepsAfterConsentResetResult
}

/**
 * Complete archive export counts, empty selection or an explicit qualification/storage failure.
 */
sealed interface ExportPortableAmbientStepsResult {
	/**
	 * A complete all-day Steps archive was emitted with the reported day, fact and gap counts.
	 */
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

	/**
	 * The requested all-day Steps selection contains no exportable archive data.
	 */
	data object NoData : ExportPortableAmbientStepsResult

	/**
	 * The required stored scope or source lineage could not be verified for this operation.
	 */
	data class Unverifiable(
		val reason: PortableAmbientStepsExportUnverifiableReason,
	) : ExportPortableAmbientStepsResult

	/**
	 * A temporary storage or concurrent-state failure prevents completion and allows a retry.
	 */
	data class RetryableFailure(
		val reason: PortableAmbientStepsExportRetryableReason,
	) : ExportPortableAmbientStepsResult
}

/**
 * Unavailable count-domain or source evidence, maintenance, deletion or size bounds that prevent a
 * complete archive.
 */
enum class PortableAmbientStepsExportUnverifiableReason {
	COUNT_DOMAIN_GRAPH_UNAVAILABLE,
	SOURCE_AUTHORITY_UNAVAILABLE,
	DELETION_PENDING,
	CORRUPT_RETAINED_STATE,
	RETENTION_CROSSES_FACT,
	MATERIALIZING,
	DEPENDENCY_OVERFLOW,
	IMPORTED_DAY_DELETED,
	IMPORTED_DAY_RETAINED,
}

/**
 * Storage failure that prevents preparing the all-day Steps archive and may be retried.
 */
enum class PortableAmbientStepsExportRetryableReason { STORAGE_UNAVAILABLE }

/**
 * Concurrent-state or storage failure that permits retrying the all-day Steps transfer or
 * mutation.
 */
enum class PortableAmbientStepsTransferRetryableReason {
	CONCURRENT_STATE_CHANGE,
	STORAGE_UNAVAILABLE,
}
