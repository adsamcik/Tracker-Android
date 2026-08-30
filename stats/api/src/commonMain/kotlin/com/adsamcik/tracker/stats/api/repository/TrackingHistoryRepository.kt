package com.adsamcik.tracker.stats.api.repository

import kotlinx.coroutines.flow.Flow

/**
 * Read-only history facade for source-qualified tracking products.
 *
 * The facade reports durable product state. Observing history never starts a provider, changes a
 * writer, repairs a projection, or writes a derived summary.
 */
interface TrackingHistoryRepository {
	/**
	 * Observe the durable history attached to one local session-segment row.
	 *
	 * This narrow query is intended for one selected session. It is not a portable session identity
	 * or a list-row API; logical-session and day composition remain separate contracts.
	 */
	fun observeSession(segmentId: Long): Flow<SessionHistoryQuery>
}

/** Result of resolving one local session-segment row identity. */
sealed interface SessionHistoryQuery {
	/** No session segment exists with the requested local row identity. */
	data object NotFound : SessionHistoryQuery

	/** A coherent durable snapshot for the requested session segment. */
	data class Found(
		val history: SessionHistory,
	) : SessionHistoryQuery
}

/** Source-qualified history attached to one session segment. */
data class SessionHistory(
	val segmentId: Long,
	val steps: StepsHistory,
)

/** Policy/capability availability, independent of acquisition and product progress. */
enum class HistoryAvailability {
	DISABLED,
	UNSUPPORTED,
	PERMISSION_REQUIRED,
	OS_LIMITED,
	AVAILABLE,
	/** Retained evidence cannot safely distinguish the policy/capability state. */
	UNAVAILABLE,
}

/** Acquisition evidence, independent of product materialization. */
enum class HistoryEvidence {
	NONE,
	STARTING,
	ACTIVE,
	RECORDED,
}

/** Product readiness, independent of availability and acquisition evidence. */
enum class HistoryProductState {
	MATERIALIZING,
	PARTIAL,
	READY,
	DEGRADED,
	FAILED,
}

/** How much of the requested session interval the Steps value represents. */
enum class StepsHistoryCoverage {
	NONE,
	COMPLETE,
	PARTIAL,
	UNKNOWN,
}

/** Stable, product-facing causes that explain incomplete or degraded Steps history. */
enum class StepsHistoryCause {
	SOURCE_NOT_CAPTURED,
	AVAILABILITY_UNAVAILABLE,
	CAPTURE_PARTIAL,
	SESSION_STILL_ACTIVE,
	BASELINE_ONLY,
	NO_OBSERVATION,
	HISTORY_MEMBERSHIP_UNAVAILABLE,
	HISTORY_INTEGRITY_FAILED,
	WRITER_PROVENANCE_INVALID,
	LEGACY_UNVERIFIED,
	MATERIALIZATION_BEHIND,
	MATERIALIZATION_UNAVAILABLE,
	ACQUISITION_INCOMPLETE,
	PROVIDER_GAP,
	FACTS_MISSING,
	DELETED,
	RETENTION_LIMIT,
	EVIDENCE_STATE_UNAVAILABLE,
	PRIVACY_EPOCH_MISMATCH,
	VALUE_OVERFLOW,
}

/**
 * Steps value and the independent evidence required to interpret it honestly.
 *
 * A partial count is a lower bound, never a complete total. Numeric zero exists only after a
 * covered interval; baseline-only and missing evidence remain null.
 */
data class StepsHistory(
	val count: Long?,
	val availability: HistoryAvailability,
	val evidence: HistoryEvidence,
	val productState: HistoryProductState,
	val coverage: StepsHistoryCoverage,
	val causes: Set<StepsHistoryCause> = emptySet(),
) {
	init {
		require(count == null || count >= 0L) { "Steps count cannot be negative" }
		when {
			count == null -> Unit
			count == 0L -> {
				require(evidence == HistoryEvidence.ACTIVE) {
					"Verified zero requires covered evidence without recording onset"
				}
				require(coverage == StepsHistoryCoverage.COMPLETE || coverage == StepsHistoryCoverage.PARTIAL) {
					"Verified zero requires a covered interval"
				}
			}
			else -> require(evidence == HistoryEvidence.RECORDED) {
				"A positive Steps count requires recorded evidence"
			}
		}
		if (evidence == HistoryEvidence.NONE || evidence == HistoryEvidence.STARTING) {
			require(count == null) { "Missing or starting evidence cannot expose a Steps value" }
		}
		if (productState != HistoryProductState.READY) {
			require(causes.isNotEmpty()) { "Incomplete Steps product state requires a named cause" }
		}
	}

	/** True only when [count] is safe for complete totals, awards, and progress decisions. */
	val hasCompleteValue: Boolean
		get() = count != null &&
			productState == HistoryProductState.READY &&
			coverage == StepsHistoryCoverage.COMPLETE

	/** A correction-safe retained count that covers only part of the requested session. */
	val isLowerBound: Boolean
		get() = count != null && coverage == StepsHistoryCoverage.PARTIAL
}
