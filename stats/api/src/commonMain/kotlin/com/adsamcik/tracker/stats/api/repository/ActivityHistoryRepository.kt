package com.adsamcik.tracker.stats.api.repository

import com.adsamcik.tracker.stats.api.value.EpochMs

/** Read-only source-local access to captured Activity history. */
interface ActivityHistoryRepository {
	/** Resolves the complete logical replacement group containing one physical presentation row. */
	suspend fun session(segmentId: Long): ActivityHistoryQuery

	/** Discovers recent logical entries from qualified Activity facts, never presentation counters. */
	suspend fun recent(limit: Int): ActivityHistoryPage
}

sealed interface ActivityHistoryQuery {
	data object NotFound : ActivityHistoryQuery

	data class Found(val entry: ActivityHistoryEntry) : ActivityHistoryQuery
}

sealed interface ActivityHistoryPage {
	data class Available(val entries: List<ActivityHistoryEntry>) : ActivityHistoryPage

	data class Failed(val cause: ActivityHistoryCause) : ActivityHistoryPage {
		init {
			require(cause.isIntegrityFailure) { "A failed page requires an integrity cause" }
		}
	}
}

/** Opaque logical identity. It deliberately grants no physical-row or deletion authority. */
@JvmInline
value class ActivityHistoryEntryKey(private val opaqueValue: String) {
	init {
		require(opaqueValue.isNotBlank())
	}

	override fun toString(): String = "ActivityHistoryEntryKey"
}

enum class ActivityHistoryProductState {
	MATERIALIZING,
	PARTIAL,
	READY,
	UNAVAILABLE,
	FAILED,
}

/** Product provenance only; it carries no local row or portable identity. */
enum class ActivityHistoryOrigin {
	LOCAL,
	IMPORTED,
}

enum class ActivityHistoryCoverage {
	NONE,
	PARTIAL,
	COMPLETE,
}

enum class ActivityHistoryCause(val isIntegrityFailure: Boolean = false) {
	SESSION_ACTIVE,
	MATERIALIZATION_BEHIND,
	SOURCE_NOT_CAPTURED,
	NO_QUALIFIED_FACTS,
	ACQUISITION_INCOMPLETE,
	PROVIDER_GAP,
	PROVIDER_UNAVAILABLE,
	RETENTION_LIMIT,
	DELETED,
	PRIVACY_EPOCH_MISMATCH,
	LEGACY_UNVERIFIABLE,
	IMPORTED_EVIDENCE_UNVERIFIABLE(isIntegrityFailure = true),
	ORIGIN_IDENTITY_CONFLICT(isIntegrityFailure = true),
	READ_BUDGET_EXCEEDED(isIntegrityFailure = true),
	PHYSICAL_MEMBERSHIP_INVALID(isIntegrityFailure = true),
	MANIFEST_INTEGRITY_FAILED(isIntegrityFailure = true),
	WRITER_PROVENANCE_INVALID(isIntegrityFailure = true),
	FACT_INTEGRITY_FAILED(isIntegrityFailure = true),
	STORED_ZONE_INVALID(isIntegrityFailure = true),
	VALUE_OVERFLOW(isIntegrityFailure = true),
}

/** One logical Activity entry composed without exposing its physical replacement-run identities. */
data class ActivityHistoryEntry(
	val key: ActivityHistoryEntryKey,
	val startTime: EpochMs,
	val endTime: EpochMs,
	val storedZoneIds: Set<String>,
	val state: ActivityHistoryProductState,
	val coverage: ActivityHistoryCoverage,
	val activeTime: ActivityActiveTime?,
	val fragments: List<ActivityHistoryFragment>,
	val causes: Set<ActivityHistoryCause> = emptySet(),
	val origin: ActivityHistoryOrigin = ActivityHistoryOrigin.LOCAL,
) {
	init {
		require(endTime >= startTime)
		require(storedZoneIds.none(String::isBlank))
		when (state) {
			ActivityHistoryProductState.READY -> {
				require(activeTime != null && fragments.isNotEmpty())
				require(coverage != ActivityHistoryCoverage.NONE)
				require(causes.none { it.isIntegrityFailure })
			}
			ActivityHistoryProductState.PARTIAL,
			ActivityHistoryProductState.MATERIALIZING -> require(causes.isNotEmpty())
			ActivityHistoryProductState.UNAVAILABLE -> {
				require(activeTime == null && fragments.isEmpty())
				require(coverage == ActivityHistoryCoverage.NONE && causes.isNotEmpty())
			}
			ActivityHistoryProductState.FAILED -> {
				require(activeTime == null && fragments.isEmpty())
				require(coverage == ActivityHistoryCoverage.NONE)
				require(causes.any { it.isIntegrityFailure })
			}
		}
	}
}

data class ActivityActiveTime(
	val knownActiveDurationNanos: Long,
	val knownInactiveDurationNanos: Long,
	val unknownActivityDurationNanos: Long,
	val unobservedDurationNanos: Long,
) {
	init {
		require(knownActiveDurationNanos >= 0L)
		require(knownInactiveDurationNanos >= 0L)
		require(unknownActivityDurationNanos >= 0L)
		require(unobservedDurationNanos >= 0L)
	}
}

sealed interface ActivityHistoryFragment {
	val storedZoneId: String
	val durationNanos: Long

	data class Band(
		override val storedZoneId: String,
		val startTime: EpochMs,
		val endTime: EpochMs,
		val startUncertaintyMs: Long,
		val endUncertaintyMs: Long,
		val activity: ActivityHistoryType,
		val mechanism: ActivityHistoryMechanism,
		val refinedTransitionActivity: ActivityHistoryType?,
		val confidence: ActivityHistoryConfidence,
		val wallTimeContinuity: ActivityHistoryWallTimeContinuity,
		override val durationNanos: Long,
	) : ActivityHistoryFragment {
		init {
			require(storedZoneId.isNotBlank())
			require(
				endTime >= startTime ||
					wallTimeContinuity == ActivityHistoryWallTimeContinuity.DISCONTINUITY_DETECTED,
			)
			require(startUncertaintyMs >= 0L && endUncertaintyMs >= 0L)
			require(durationNanos > 0L)
		}

	}

	/** A known missing interval; elapsed order is retained by list position without a fake wall time. */
	data class Gap(
		override val storedZoneId: String,
		val reason: ActivityHistoryGapReason,
		override val durationNanos: Long,
	) : ActivityHistoryFragment {
		init {
			require(storedZoneId.isNotBlank() && durationNanos > 0L)
		}
	}
}

enum class ActivityHistoryType {
	STILL,
	WALKING,
	RUNNING,
	ON_BICYCLE,
	IN_VEHICLE,
	ON_FOOT,
	TILTING,
	UNKNOWN,
}

enum class ActivityHistoryMechanism {
	TRANSITION,
	SAMPLED_REFINEMENT,
	SAMPLED_CLASSIFICATION,
}

sealed interface ActivityHistoryConfidence {
	data object TransitionSignal : ActivityHistoryConfidence

	data class Sampled(
		val minimumPercent: Int,
		val maximumPercent: Int,
		val observationCount: Int,
	) : ActivityHistoryConfidence {
		init {
			require(minimumPercent in 0..100)
			require(maximumPercent in minimumPercent..100)
			require(observationCount > 0)
		}
	}
}

enum class ActivityHistoryWallTimeContinuity {
	SAME_ANCHOR,
	CONSISTENT_WITHIN_UNCERTAINTY,
	DISCONTINUITY_DETECTED,
}

enum class ActivityHistoryGapReason {
	NO_QUALIFIED_EVIDENCE,
	PROVIDER_DISCONTINUITY,
	AUTHORIZATION_DISCONTINUITY,
	PROCESS_OR_REBOOT_DISCONTINUITY,
	SOURCE_REJECTED_EVIDENCE,
}
