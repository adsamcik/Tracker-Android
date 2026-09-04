package com.adsamcik.tracker.stats.api.repository

/**
 * Deletes one selected, exactly attributed Steps-only presentation session.
 *
 * Implementations must fail closed when released legacy data cannot be tied to an immutable
 * logical-session/service-run scope. A successful result is also a durable no-resurrection fence,
 * not merely removal of the current presentation row.
 */
interface StepsSessionDeletion {
	/** Applies a permanent Steps deletion for the exact selected presentation row. */
	suspend fun deleteSelectedSession(sessionSegmentId: Long): StepsSessionDeletionResult
}

/** Typed outcome for a user-selected Steps session deletion request. */
sealed interface StepsSessionDeletionResult {
	/** The exact session was deleted and its durable source scope was fenced. */
	data object Deleted : StepsSessionDeletionResult

	/** The presentation row is already absent. This is an accepted idempotent outcome. */
	data object NotFound : StepsSessionDeletionResult

	/** Exact lifecycle evidence still permits an owner to mutate the selected run. */
	data object BlockedActive : StepsSessionDeletionResult

	/** Released legacy attribution cannot prove the selected source/run scope. */
	data object LegacyUnverifiable : StepsSessionDeletionResult

	/** The row is attributed, but is outside the deliberately narrow Steps-only contract. */
	data class UnsupportedScope(
		val reason: StepsSessionDeletionUnsupportedReason,
	) : StepsSessionDeletionResult

	/** A transient storage failure left the transaction unapplied and can be retried safely. */
	data class RetryableFailure(
		val reason: StepsSessionDeletionRetryableReason,
	) : StepsSessionDeletionResult
}

/** Stable fail-closed reasons that require a different product or migration path. */
enum class StepsSessionDeletionUnsupportedReason {
	INVALID_SEGMENT_ID,
	INCOMPLETE_ATTRIBUTION,
	SERVICE_RUN_MISSING,
	SERVICE_RUN_BINDING_MISMATCH,
	LOGICAL_SESSION_MISSING,
	MANIFEST_MISSING,
	MANIFEST_MEMBERSHIP_MISMATCH,
	MANIFEST_INTEGRITY_FAILED,
	MIXED_OR_INCOMPLETE_CAPTURE_SET,
	LEGACY_WRITER,
	CANDIDATE_WRITER_BINDING_INVALID,
	SOURCE_EVIDENCE_STATE_MISSING,
	/** Selected source facts disagree with their exact immutable manifest policy/consent authority. */
	FACT_ATTRIBUTION_MISMATCH,
	STALE_COLLECTED_DATA_EPOCH,
	/** Retention discarded part of this run, so its complete affected-day set is unknowable. */
	RETENTION_TRUNCATED_HISTORY,
	DELETION_GENERATION_EXHAUSTED,
	AFFECTED_DAY_RANGE_TOO_LARGE,
	/** A surviving day contribution could not be recomposed without inventing source evidence. */
	DAY_REPAIR_UNVERIFIABLE,
}

/** Stable transient categories suitable for a user-visible retry action. */
enum class StepsSessionDeletionRetryableReason {
	DATABASE_UNAVAILABLE,
	CONCURRENT_STATE_CHANGE,
	/** A surviving candidate writer has not durably settled its facts and completeness yet. */
	DAY_REPAIR_MATERIALIZING,
}
