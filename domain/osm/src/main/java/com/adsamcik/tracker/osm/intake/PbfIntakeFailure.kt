package com.adsamcik.tracker.osm.intake

import java.io.IOException

/**
 * Stable, non-retryable outcomes for the safe offline-PBF intake boundary.
 *
 * These failures deliberately never retain a source URI, file name, PBF entity
 * id, parser exception message, or other user-controlled text. A future worker
 * can map [code] to a user-facing message and redact diagnostics without
 * depending on the exception's implementation details.
 *
 * This type is only the foundation for a future safe pipeline. It does not
 * validate PBF framing or enable the release-gated importer.
 */
sealed class PbfIntakeFailure(
	val code: PbfIntakeFailureCode,
	val isPermanent: Boolean = true,
	message: String,
) : IOException(message) {

	/** The supplied directory cannot be used as app-private snapshot storage. */
	object InvalidPrivateSnapshotDirectory : PbfIntakeFailure(
		code = PbfIntakeFailureCode.INVALID_PRIVATE_SNAPSHOT_DIRECTORY,
		message = "Private PBF snapshot storage is unavailable",
	)

	/** The selected source could not be opened for the one permitted copy pass. */
	object SourceUnavailable : PbfIntakeFailure(
		code = PbfIntakeFailureCode.SOURCE_UNAVAILABLE,
		message = "PBF source is unavailable",
	)

	/** A checked resource reservation would exceed its declared bound. */
	class ResourceLimitExceeded(
		val resource: PbfResource,
		val limit: Long,
		val requested: Long,
	) : PbfIntakeFailure(
		code = PbfIntakeFailureCode.RESOURCE_LIMIT_EXCEEDED,
		message = "PBF intake resource limit exceeded",
	)

	/** Copying the source to private storage did not complete successfully. */
	object SnapshotIoFailed : PbfIntakeFailure(
		code = PbfIntakeFailureCode.SNAPSHOT_IO_FAILED,
		message = "PBF private snapshot copy failed",
	)

	/** A fully written temporary snapshot could not be made visible atomically. */
	object SnapshotFinalizationFailed : PbfIntakeFailure(
		code = PbfIntakeFailureCode.SNAPSHOT_FINALIZATION_FAILED,
		message = "PBF private snapshot finalization failed",
	)

	/** A previously completed private snapshot no longer has its expected bytes. */
	object SnapshotIntegrityFailed : PbfIntakeFailure(
		code = PbfIntakeFailureCode.SNAPSHOT_INTEGRITY_FAILED,
		message = "PBF private snapshot is unavailable",
	)

	/** A completed private snapshot could not be removed during cleanup. */
	object SnapshotDeleteFailed : PbfIntakeFailure(
		code = PbfIntakeFailureCode.SNAPSHOT_DELETE_FAILED,
		message = "PBF private snapshot cleanup failed",
	)
}

/** Wire-stable categories for [PbfIntakeFailure]. */
enum class PbfIntakeFailureCode {
	INVALID_PRIVATE_SNAPSHOT_DIRECTORY,
	SOURCE_UNAVAILABLE,
	RESOURCE_LIMIT_EXCEEDED,
	SNAPSHOT_IO_FAILED,
	SNAPSHOT_FINALIZATION_FAILED,
	SNAPSHOT_INTEGRITY_FAILED,
	SNAPSHOT_DELETE_FAILED,
	STRICT_PBF_REJECTED,
}
