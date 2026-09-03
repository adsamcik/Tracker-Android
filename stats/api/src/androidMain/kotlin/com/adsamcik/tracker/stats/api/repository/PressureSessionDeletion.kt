package com.adsamcik.tracker.stats.api.repository

/**
 * Deletes one selected, exactly attributed Pressure-only presentation session.
 *
 * Implementations must fail closed when released legacy data, immutable writer authority, or a
 * bounded correction scope cannot be proven. A successful result permanently fences the exact
 * logical-session/service-run scope before removing its current Pressure facts.
 */
interface PressureSessionDeletion {
	/** Applies a permanent Pressure deletion for the exact selected presentation row. */
	suspend fun deleteSelectedSession(sessionSegmentId: Long): PressureSessionDeletionResult
}

/** Typed outcome for a user-selected Pressure session deletion request. */
sealed interface PressureSessionDeletionResult {
	/** The exact session was deleted and its durable source scope was fenced. */
	data object Deleted : PressureSessionDeletionResult

	/** The presentation row is already absent. This is an accepted idempotent outcome. */
	data object NotFound : PressureSessionDeletionResult

	/** Exact lifecycle evidence still permits an owner to mutate the selected run. */
	data object BlockedActive : PressureSessionDeletionResult

	/** Released legacy attribution cannot prove the selected source/run scope. */
	data object LegacyUnverifiable : PressureSessionDeletionResult

	/** The row is attributed, but is outside the deliberately narrow Pressure-only contract. */
	data class UnsupportedScope(
		val reason: PressureSessionDeletionUnsupportedReason,
	) : PressureSessionDeletionResult

	/** A transient storage failure left the transaction unapplied and can be retried safely. */
	data class RetryableFailure(
		val reason: PressureSessionDeletionRetryableReason,
	) : PressureSessionDeletionResult
}

/** Stable fail-closed reasons that require a different product or migration path. */
enum class PressureSessionDeletionUnsupportedReason {
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
	SOURCE_POLICY_ATTRIBUTION_MISMATCH,
	SOURCE_EVIDENCE_STATE_MISSING,
	FACT_ATTRIBUTION_MISMATCH,
	FACT_INTEGRITY_FAILED,
	/** A correction revision cannot be bounded to this exact logical session and service run. */
	FACT_CORRECTION_SCOPE_UNVERIFIABLE,
	STALE_COLLECTED_DATA_EPOCH,
	AFFECTED_DAY_RANGE_TOO_LARGE,
	/** A surviving day contribution could not be recomposed without inventing source evidence. */
	DAY_REPAIR_UNVERIFIABLE,
}

/** Stable transient categories suitable for a user-visible retry action. */
enum class PressureSessionDeletionRetryableReason {
	DATABASE_UNAVAILABLE,
	CONCURRENT_STATE_CHANGE,
	/** A surviving candidate writer has not durably settled its facts and completeness yet. */
	DAY_REPAIR_MATERIALIZING,
}
