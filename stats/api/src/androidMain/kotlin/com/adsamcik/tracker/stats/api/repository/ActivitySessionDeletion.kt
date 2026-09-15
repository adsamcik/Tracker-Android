package com.adsamcik.tracker.stats.api.repository

/**
 * Deletes one selected, exactly attributed Activity-only logical tracking session.
 *
 * A logical session may own several physical replacement runs. Implementations must authenticate
 * and fence the complete immutable run set before removing any captured Activity payload or
 * presentation row. Released legacy or shared-source rows fail closed.
 */
interface ActivitySessionDeletion {
	/** Applies a permanent Activity deletion for the logical entry owning [sessionSegmentId]. */
	suspend fun deleteSelectedSession(sessionSegmentId: Long): ActivitySessionDeletionResult
}

/** Typed outcome for one selected Activity-only logical-session deletion. */
sealed interface ActivitySessionDeletionResult {
	/** The complete exact logical entry was fenced and removed from product presentation. */
	data object Deleted : ActivitySessionDeletionResult

	/** The selected presentation row is already absent. */
	data object NotFound : ActivitySessionDeletionResult

	/** Current lifecycle or provider authority can still mutate the selected scope. */
	data object BlockedActive : ActivitySessionDeletionResult

	/** Released rows do not retain enough immutable ownership evidence for exact deletion. */
	data object LegacyUnverifiable : ActivitySessionDeletionResult

	/** The selected row is outside the deliberately narrow exact Activity-only contract. */
	data class UnsupportedScope(
		val reason: ActivitySessionDeletionUnsupportedReason,
	) : ActivitySessionDeletionResult

	/** A transient storage or concurrent-state failure left the transaction unapplied. */
	data class RetryableFailure(
		val reason: ActivitySessionDeletionRetryableReason,
	) : ActivitySessionDeletionResult
}

/** Stable fail-closed reasons requiring migration, repair, or a different product path. */
enum class ActivitySessionDeletionUnsupportedReason {
	INVALID_SEGMENT_ID,
	INCOMPLETE_ATTRIBUTION,
	SERVICE_RUN_MISSING,
	SERVICE_RUN_BINDING_MISMATCH,
	LOGICAL_SESSION_MISSING,
	REPLACEMENT_SCOPE_OVERFLOW,
	REPLACEMENT_SCOPE_MISMATCH,
	MANIFEST_MISSING,
	MANIFEST_MEMBERSHIP_MISMATCH,
	MANIFEST_INTEGRITY_FAILED,
	MIXED_OR_INCOMPLETE_CAPTURE_SET,
	LEGACY_WRITER,
	CANDIDATE_WRITER_BINDING_INVALID,
	SOURCE_EVIDENCE_STATE_MISSING,
	PROVIDER_AUTHORITY_UNVERIFIABLE,
	MAINTENANCE_BOUND_EXCEEDED,
	FACT_INTEGRITY_FAILED,
	STALE_COLLECTED_DATA_EPOCH,
	STALE_REQUEST,
	DELETION_FENCE_CONFLICT,
	AFFECTED_DAY_RANGE_TOO_LARGE,
	DAY_REPAIR_UNVERIFIABLE,
}

/** Stable transient categories suitable for an explicit retry. */
enum class ActivitySessionDeletionRetryableReason {
	DATABASE_UNAVAILABLE,
	CONCURRENT_STATE_CHANGE,
	DAY_REPAIR_MATERIALIZING,
}
