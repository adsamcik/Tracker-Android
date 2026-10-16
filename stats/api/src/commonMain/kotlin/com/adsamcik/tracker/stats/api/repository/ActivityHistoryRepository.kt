package com.adsamcik.tracker.stats.api.repository

import com.adsamcik.tracker.stats.api.value.EpochMs

/** Read-only source-local access to captured Activity history. */
interface ActivityHistoryRepository {
	/** Resolves the complete logical replacement group containing one physical presentation row. */
	suspend fun session(segmentId: Long): ActivityHistoryQuery

	/** Discovers recent logical entries from captured Activity intent and retained product facts. */
	suspend fun recent(limit: Int): ActivityHistoryPage

	/** Reads one bounded page from a wall-time or stored-structural-day Activity scope. */
	suspend fun range(request: ActivityHistoryRangeRequest): ActivityHistoryRangePage
}

/**
 * Exact Activity lookup outcome, distinguishing an existing product entry from no matching entry.
 */
sealed interface ActivityHistoryQuery {
	/**
	 * No matching saved source entry or selection remains.
	 */
	data object NotFound : ActivityHistoryQuery

	/**
	 * Contains the selected saved source product; its payload preserves current qualification and
	 * coverage.
	 */
	data class Found(val entry: ActivityHistoryEntry) : ActivityHistoryQuery
}

/**
 * Recent Activity entries or an integrity failure that prevents presenting the page.
 */
sealed interface ActivityHistoryPage {
	/**
	 * Saved source entries whose individual states retain coverage and qualification.
	 */
	data class Available(val entries: List<ActivityHistoryEntry>) : ActivityHistoryPage

	/**
	 * The stored source or scope failed integrity qualification; this outcome does not expose a
	 * measured count.
	 */
	data class Failed(val cause: ActivityHistoryCause) : ActivityHistoryPage {
		init {
			require(cause.isIntegrityFailure) { "A failed page requires an integrity cause" }
		}
	}
}

/** Opaque logical identity. The key alone grants no physical-row or imported deletion authority. */
@JvmInline
value class ActivityHistoryEntryKey(private val opaqueValue: String) {
	init {
		require(opaqueValue.isNotBlank())
	}

	override fun toString(): String = "ActivityHistoryEntryKey"
}

/** Opaque imported Activity identity issued only by an authenticated source read. */
@JvmInline
value class ActivityImportedHistoryIdentity(val value: String) {
	init {
		require(ACTIVITY_IMPORTED_SHA_256.matches(value))
	}

	override fun toString(): String = "ActivityImportedHistoryIdentity"
}

/** Opaque imported Activity content digest issued only by an authenticated source read. */
@JvmInline
value class ActivityImportedHistoryDigest(val value: String) {
	init {
		require(ACTIVITY_IMPORTED_SHA_256.matches(value))
	}

	override fun toString(): String = "ActivityImportedHistoryDigest"
}

/** Opaque Activity source-deletion scope retained only for exact selected deletion. */
@JvmInline
value class ActivityImportedHistoryDeletionScopeDigest(val value: String) {
	init {
		require(ACTIVITY_IMPORTED_SHA_256.matches(value))
	}

	override fun toString(): String = "ActivityImportedHistoryDeletionScopeDigest"
}

/** Exact physical-run deletion authority retained without exposing a native row identity. */
data class ActivityImportedHistoryRunDeletionScope(
	val runIdentity: ActivityImportedHistoryIdentity,
	val deletionScopeDigest: ActivityImportedHistoryDeletionScopeDigest,
)

/** Source-evidence snapshot that authenticated one imported Activity selection. */
data class ActivityImportedHistoryReadSnapshot(
	val collectedDataEpoch: Long,
	val sourceEvidenceRevision: Long,
) {
	init {
		require(collectedDataEpoch >= 0L)
		require(sourceEvidenceRevision >= 0L)
	}
}

/**
 * Exact imported Activity selection issued with one authenticated product read.
 *
 * The stable presentation [key] is bound for consistency but is never decoded into [identity].
 * Physical identities remain opaque and exist only so deletion replay can authenticate the exact
 * hierarchy after its payload has been removed.
 */
@Suppress("LongParameterList")
data class ActivityImportedHistorySelection(
	val key: ActivityHistoryEntryKey,
	val identity: ActivityImportedHistoryIdentity,
	val importRevision: Long,
	val contentChecksum: ActivityImportedHistoryDigest,
	val runDeletionScopes: List<ActivityImportedHistoryRunDeletionScope>,
	val windowIdentities: List<ActivityImportedHistoryIdentity>,
	val readSnapshot: ActivityImportedHistoryReadSnapshot,
) {
	init {
		require(importRevision > 0L)
		require(runDeletionScopes.isNotEmpty())
		require(runDeletionScopes.size <= MAX_ACTIVITY_IMPORTED_RUNS)
		require(runDeletionScopes.map { it.runIdentity }.distinct().size == runDeletionScopes.size)
		require(
			runDeletionScopes.map { it.deletionScopeDigest }.distinct().size ==
				runDeletionScopes.size,
		)
		require(windowIdentities.isNotEmpty())
		require(windowIdentities.size <= MAX_ACTIVITY_IMPORTED_WINDOWS)
		require(windowIdentities.distinct().size == windowIdentities.size)
		val protectedIdentities = buildSet {
			add(identity.value)
			runDeletionScopes.forEach { add(it.runIdentity.value) }
			windowIdentities.forEach { add(it.value) }
		}
		require(
			protectedIdentities.size == runDeletionScopes.size + windowIdentities.size + 1,
		)
		require(runDeletionScopes.none { it.deletionScopeDigest.value in protectedIdentities })
	}

	override fun toString(): String = "ActivityImportedHistorySelection"
}

/** Exact source-local Activity selection. Local rows never fabricate imported authority. */
sealed interface ActivityHistorySelection {
	val key: ActivityHistoryEntryKey
	val origin: ActivityHistoryOrigin

	/**
	 * Selects or labels native saved data without borrowing imported revision authority.
	 */
	data class Local(
		override val key: ActivityHistoryEntryKey,
	) : ActivityHistorySelection {
		override val origin: ActivityHistoryOrigin = ActivityHistoryOrigin.LOCAL
	}

	/**
	 * Carries the source-issued imported identity/revision selection separately from native recording
	 * authority.
	 */
	data class Imported(
		val selected: ActivityImportedHistorySelection,
	) : ActivityHistorySelection {
		override val key: ActivityHistoryEntryKey = selected.key
		override val origin: ActivityHistoryOrigin = ActivityHistoryOrigin.IMPORTED
	}
}

/**
 * Presentation state separating in-progress materialization, usable partial data, ready data and
 * unavailable or failed reads.
 */
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

/**
 * How much of the selected Activity scope has retained qualified observations.
 */
enum class ActivityHistoryCoverage {
	NONE,
	PARTIAL,
	COMPLETE,
}

/**
 * Concrete capture, provider, retention and integrity causes; isIntegrityFailure marks failures
 * that cannot expose a qualified number.
 */
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

/** One logical Activity entry; selected-deletion authority, when present, remains opaque. */
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
	/** Every authenticated capture manifest revision contains Activity and no other captured source. */
	val capturesOnlyActivity: Boolean = false,
	/** Present only when an imported producer authenticated exact selected-deletion authority. */
	val importedSelection: ActivityImportedHistorySelection? = null,
) {
	val selection: ActivityHistorySelection?
		get() = when (origin) {
			ActivityHistoryOrigin.LOCAL -> ActivityHistorySelection.Local(key)
			ActivityHistoryOrigin.IMPORTED ->
				importedSelection?.let(ActivityHistorySelection::Imported)
		}

	init {
		require(endTime >= startTime)
		require(storedZoneIds.none(String::isBlank))
		require(origin == ActivityHistoryOrigin.LOCAL || !capturesOnlyActivity) {
			"Imported Activity product cannot claim native Activity-only capture intent"
		}
		require(importedSelection == null || origin == ActivityHistoryOrigin.IMPORTED)
		require(importedSelection == null || importedSelection.key == key)
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

/**
 * Nonnegative elapsed durations in nanoseconds, separating known active/inactive behavior, unknown
 * activity and unobserved time.
 */
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

/**
 * Ordered Activity evidence carrying a saved zone and elapsed nanosecond duration, as a band or a
 * genuine gap.
 */
sealed interface ActivityHistoryFragment {
	val storedZoneId: String
	val durationNanos: Long

	/**
	 * Observed Activity band retaining original wall-time uncertainty, elapsed nanosecond duration,
	 * classification and confidence.
	 */
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

/**
 * Recognized activity classifications retained from the provider, including an explicit unknown
 * classification.
 */
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

/**
 * Whether the classification came from a transition, sampled refinement or sampled classification.
 */
enum class ActivityHistoryMechanism {
	TRANSITION,
	SAMPLED_REFINEMENT,
	SAMPLED_CLASSIFICATION,
}

/**
 * Transition evidence or the observed percent-confidence range of sampled classifications.
 */
sealed interface ActivityHistoryConfidence {
	/**
	 * Transition evidence has no sampled numeric confidence percentage.
	 */
	data object TransitionSignal : ActivityHistoryConfidence

	/**
	 * Minimum/maximum confidence percentages from 0 to 100 with the positive number of represented
	 * sampled observations.
	 */
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

/**
 * Relationship between wall-time anchors; a discontinuity does not invalidate the separately
 * retained elapsed duration.
 */
enum class ActivityHistoryWallTimeContinuity {
	SAME_ANCHOR,
	CONSISTENT_WITHIN_UNCERTAINTY,
	DISCONTINUITY_DETECTED,
}

/**
 * Why a real interval lacks qualified Activity evidence, including provider, authorization and
 * process discontinuities.
 */
enum class ActivityHistoryGapReason {
	NO_QUALIFIED_EVIDENCE,
	PROVIDER_DISCONTINUITY,
	AUTHORIZATION_DISCONTINUITY,
	PROCESS_OR_REBOOT_DISCONTINUITY,
	SOURCE_REJECTED_EVIDENCE,
}

private val ACTIVITY_IMPORTED_SHA_256 = Regex("[0-9a-f]{64}")
private const val MAX_ACTIVITY_IMPORTED_RUNS = 64
private const val MAX_ACTIVITY_IMPORTED_WINDOWS = 16_384
