package com.adsamcik.tracker.stats.api.repository

import com.adsamcik.tracker.stats.api.value.EpochMs

/** Read-only, identity-free access to captured Cell history. */
interface CellHistoryRepository {
	/** Resolves one repository-issued opaque selection without exposing its backing identity. */
	suspend fun detail(selection: CellHistoryEntrySelection): CellHistoryQuery =
		if (selection is ImportedCellHistorySelection) {
			imported(selection)
		} else {
			CellHistoryQuery.NotFound
		}

	/** Resolves the complete logical replacement group containing one physical presentation row. */
	suspend fun session(segmentId: Long): CellHistoryQuery

	/** Resolves one exact imported-origin selection without treating it as a local session. */
	suspend fun imported(selection: ImportedCellHistorySelection): CellHistoryQuery =
		CellHistoryQuery.NotFound

	/** Discovers recent logical entries from qualified Cell facts, never presentation counters. */
	suspend fun recent(limit: Int): CellHistoryPage

	/** Complete bounded source-local range discovery; never implemented by filtering [recent]. */
	suspend fun range(request: CellHistoryRangeRequest): CellHistoryRangePage =
		CellHistoryRangePage.Unavailable(CellHistoryRangeUnavailableReason.UNSUPPORTED_BY_IMPLEMENTATION)
}

/**
 * Exact Cell lookup outcome, distinguishing an existing saved product from no matching entry.
 */
sealed interface CellHistoryQuery {
	/**
	 * No matching saved source entry or selection remains.
	 */
	data object NotFound : CellHistoryQuery
	/**
	 * Contains the selected saved source product; its payload preserves current qualification and
	 * coverage.
	 */
	data class Found(val entry: CellHistoryEntry) : CellHistoryQuery
}

/**
 * Recent Cell entries or a page-wide integrity failure.
 */
sealed interface CellHistoryPage {
	/**
	 * Saved source entries whose individual states retain coverage and qualification.
	 */
	data class Available(val entries: List<CellHistoryEntry>) : CellHistoryPage
	/**
	 * The stored source or scope failed integrity qualification; this outcome does not expose a
	 * measured count.
	 */
	data class Failed(val cause: CellHistoryCause) : CellHistoryPage {
		init {
			require(cause.isIntegrityFailure)
		}
	}
}

/**
 * Selects Cell history by half-open UTC wall time or inclusive saved structural calendar days.
 */
sealed interface CellHistoryRangeScope {
	/**
	 * Nonempty half-open UTC millisecond selection bounded to the supported history interval.
	 */
	data class WallTime(
		val fromInclusive: EpochMs,
		val toExclusive: EpochMs,
	) : CellHistoryRangeScope {
		init {
			require(toExclusive > fromInclusive)
			require(toExclusive.raw - fromInclusive.raw <=
				MAX_CELL_RANGE_DAY_COUNT * MILLIS_PER_DAY)
		}
	}

	/**
	 * Inclusive bounded local calendar epoch-day range interpreted using retained saved zones.
	 */
	data class StructuralDays(
		val firstEpochDay: Long,
		val lastEpochDayInclusive: Long,
	) : CellHistoryRangeScope {
		init {
			require(firstEpochDay in MIN_SUPPORTED_CELL_EPOCH_DAY..MAX_SUPPORTED_CELL_EPOCH_DAY)
			require(lastEpochDayInclusive in firstEpochDay..MAX_SUPPORTED_CELL_EPOCH_DAY)
			require(lastEpochDayInclusive - firstEpochDay < MAX_CELL_RANGE_DAY_COUNT)
		}
	}
}

/** Opaque immutable snapshot continuation. Only the repository that issued it may interpret it. */
interface CellHistoryRangeContinuation

/**
 * Bounded Cell page request with an optional continuation issued for the same source range.
 */
data class CellHistoryRangeRequest(
	val scope: CellHistoryRangeScope,
	val limit: Int,
	val continuation: CellHistoryRangeContinuation? = null,
) {
	init {
		require(limit in 1..MAX_CELL_RANGE_PAGE_SIZE)
	}

	/**
	 * Maximum number of logical Cell entries returned by one range page.
	 */
	companion object {
		const val MAX_CELL_RANGE_PAGE_SIZE = 100
	}
}

/**
 * Local calendar epoch day and saved zone used to allocate the Cell entry.
 */
data class CellHistoryStructuralDay(
	val epochDay: Long,
	val storedZoneId: String,
) {
	init {
		require(storedZoneId.isNotBlank())
	}
}

/**
 * Whether saved-day membership is exact, ambiguous, partial or unavailable.
 */
enum class CellHistoryStructuralDayCompleteness {
	EXACT,
	AMBIGUOUS,
	PARTIAL,
	UNAVAILABLE,
}

/**
 * One Cell entry with its saved-day membership and allocation completeness.
 */
data class CellHistoryRangeEntry(
	val entry: CellHistoryEntry,
	val structuralDays: Set<CellHistoryStructuralDay>,
	val structuralDayCompleteness: CellHistoryStructuralDayCompleteness,
) {
	init {
		require(structuralDays.none { it.storedZoneId.isBlank() })
		when (structuralDayCompleteness) {
			CellHistoryStructuralDayCompleteness.EXACT -> require(structuralDays.isNotEmpty())
			CellHistoryStructuralDayCompleteness.AMBIGUOUS -> require(structuralDays.size > 1)
			CellHistoryStructuralDayCompleteness.PARTIAL -> require(structuralDays.isNotEmpty())
			CellHistoryStructuralDayCompleteness.UNAVAILABLE -> require(structuralDays.isEmpty())
		}
	}
}

/**
 * Bounded Cell range entries and continuation, or an explicit unavailable/integrity result.
 */
sealed interface CellHistoryRangePage {
	/**
	 * Bounded saved entries and an optional source-issued continuation; null ends the range.
	 */
	data class Available(
		val entries: List<CellHistoryRangeEntry>,
		val continuation: CellHistoryRangeContinuation?,
	) : CellHistoryRangePage {
		init {
			require(entries.size <= CellHistoryRangeRequest.MAX_CELL_RANGE_PAGE_SIZE)
		}
	}

	/**
	 * The requested data or authority is unavailable; any named reason preserves the original failure
	 * cause.
	 */
	data class Unavailable(
		val reason: CellHistoryRangeUnavailableReason,
	) : CellHistoryRangePage

	/**
	 * The stored source or scope failed integrity qualification; this outcome does not expose a
	 * measured count.
	 */
	data class Failed(val cause: CellHistoryCause) : CellHistoryRangePage {
		init {
			require(cause.isIntegrityFailure)
		}
	}
}

/**
 * Unsupported range capability or invalid continuation preventing a Cell history page.
 */
enum class CellHistoryRangeUnavailableReason {
	UNSUPPORTED_BY_IMPLEMENTATION,
	INVALID_CONTINUATION,
}

/**
 * Exact source selection, UTC millisecond action time and optional expected data epoch for
 * deleting Cell history.
 */
data class DeleteCellHistoryRequest(
	val selection: CellHistoryEntrySelection,
	val requestedAtMs: Long,
	val expectedCollectedDataEpoch: Long? = null,
) {
	init {
		require(requestedAtMs >= 0L)
		require(expectedCollectedDataEpoch == null || expectedCollectedDataEpoch >= 0L)
	}
}

/**
 * Deletes the local or imported Cell scope selected by the saved-data reader.
 */
interface DeleteCellHistory {
	/**
	 * Deletes the exact local/imported selection after rechecking its source scope, current epoch and
	 * live/retention boundaries.
	 */
	suspend fun delete(request: DeleteCellHistoryRequest): DeleteCellHistoryResult
}

/**
 * Selected Cell deletion outcome, separating removed counts, prior deletion, missing data and
 * failures.
 */
sealed interface DeleteCellHistoryResult {
	/**
	 * The exact selected source scope was removed; any counts report the original scope that was
	 * fenced.
	 */
	data class Deleted(
		val deletedLogicalEntryCount: Int,
		val deletedPhysicalRunCount: Int,
		val deletedFactOrObservationCount: Int,
	) : DeleteCellHistoryResult {
		init {
			require(deletedLogicalEntryCount == 1)
			require(deletedPhysicalRunCount > 0 && deletedFactOrObservationCount >= 0)
		}
	}

	/**
	 * The exact selected source scope was previously deleted and remains fenced against replay.
	 */
	data object AlreadyDeleted : DeleteCellHistoryResult
	/**
	 * No matching saved source entry or selection remains.
	 */
	data object NotFound : DeleteCellHistoryResult
	/**
	 * Current capture, selection or privacy state prevents the requested source mutation.
	 */
	data class Blocked(val reason: CellHistoryDeletionBlockedReason) : DeleteCellHistoryResult
	/**
	 * The source cannot verify the complete selected scope or stored lineage needed by this
	 * operation.
	 */
	data class Unverifiable(val reason: CellHistoryDeletionUnverifiableReason) :
		DeleteCellHistoryResult
	/**
	 * A transient storage or concurrent-state failure prevents completion and permits retry.
	 */
	data class RetryableFailure(val reason: CellHistoryDeletionRetryableReason) :
		DeleteCellHistoryResult
}

/**
 * Active capture, stale selection/epoch, retention or incomplete capture/deletion scope that
 * prevents selected erasure.
 */
enum class CellHistoryDeletionBlockedReason {
	ACTIVE_CAPTURE,
	STALE_SELECTION,
	STALE_REQUEST,
	RETENTION_BOUNDARY,
	MIXED_OR_INCOMPLETE_CAPTURE_SET,
	PARTIAL_DELETION_STATE,
}

/**
 * Unverifiable selection, hierarchy, writer/fact evidence or day repair preventing a complete Cell
 * deletion.
 */
enum class CellHistoryDeletionUnverifiableReason {
	SOURCE_EVIDENCE_STATE_MISSING,
	SELECTION_LOOKUP_BUDGET_EXCEEDED,
	REPLACEMENT_SCOPE_INVALID,
	MANIFEST_INTEGRITY_FAILED,
	WRITER_OR_FACT_AUTHORITY_INVALID,
	OPAQUE_IDENTITY_CONFLICT,
	STORED_EVIDENCE_UNVERIFIABLE,
	DAY_REPAIR_UNAVAILABLE,
}

/**
 * Concurrent-state or storage failures that allow retrying selected Cell deletion.
 */
enum class CellHistoryDeletionRetryableReason {
	CONCURRENT_STATE_CHANGE,
	STORAGE_UNAVAILABLE,
}

/** Opaque logical identity. Physical run, segment, subscription, and tower identities stay private. */
@JvmInline
value class CellHistoryEntryKey(private val opaqueValue: String) {
	init {
		require(opaqueValue.isNotBlank())
	}

	override fun toString(): String = "CellHistoryEntryKey"
}

/**
 * Repository-issued exact source-local selection. Consumers may retain and return it, but cannot
 * derive local physical ownership from the contract.
 */
interface CellHistoryEntrySelection

/** Repository-issued opaque identity for one exact local logical Cell entry. */
@JvmInline
value class LocalCellHistoryIdentity(val value: String) {
	init {
		require(OPAQUE_CELL_IDENTITY.matches(value))
	}

	override fun toString(): String = "LocalCellHistoryIdentity"
}

/**
 * Opaque local Cell history identity; imported selections use separately verified revision and
 * checksum values.
 */
data class LocalCellHistorySelection(
	val identity: LocalCellHistoryIdentity,
) : CellHistoryEntrySelection

/** Proven presentation origin. Neither form grants mutation or live capture authority. */
sealed interface CellHistoryOrigin {
	/**
	 * Selects or labels native saved data without borrowing imported revision authority.
	 */
	data object Local : CellHistoryOrigin
	/**
	 * Carries the source-issued imported identity/revision selection separately from native recording
	 * authority.
	 */
	data class Imported(val selection: ImportedCellHistorySelection) : CellHistoryOrigin
}

/** Stable source-supplied identity for selecting one imported Cell lineage. */
@JvmInline
value class ImportedCellHistoryIdentity(val value: String) {
	init {
		require(OPAQUE_CELL_IDENTITY.matches(value))
	}

	override fun toString(): String = "ImportedCellHistoryIdentity"
}

/** Exact latest imported revision selected by the product reader. */
data class ImportedCellHistorySelection(
	val identity: ImportedCellHistoryIdentity,
	val importRevision: Long,
	val contentChecksum: ImportedCellHistoryDigest,
) : CellHistoryEntrySelection {
	init {
		require(importRevision > 0L)
	}
}

/**
 * Lowercase SHA-256 content digest for the imported Cell selection; it grants no recording
 * permission.
 */
@JvmInline
value class ImportedCellHistoryDigest(val value: String) {
	init {
		require(OPAQUE_CELL_IDENTITY.matches(value))
	}

	override fun toString(): String = "ImportedCellHistoryDigest"
}

/**
 * Cell presentation state retaining partial data and distinguishing missing/deleted/unverifiable
 * data from a failed read.
 */
enum class CellHistoryProductState {
	MATERIALIZING,
	PARTIAL,
	READY,
	UNAVAILABLE,
	MISSING,
	DELETED,
	UNVERIFIABLE,
	FAILED,
}
/**
 * Amount of selected Cell scope represented by retained observations, including unknown coverage.
 */
enum class CellHistoryCoverage { NONE, PARTIAL, COMPLETE, UNKNOWN }
/**
 * Availability discriminator attached to a retained Cell observation.
 */
enum class CellHistoryAvailability { AVAILABLE }
/**
 * Explicitly unknown subscription grouping; tower readings do not identify the user's SIM.
 */
enum class CellHistorySubscriptionGrouping { UNKNOWN }
/**
 * Whether all represented Cell children qualified or some were rejected.
 */
enum class CellHistoryChildCompleteness { COMPLETE, PARTIAL }
/**
 * Provider-reported tower radio technology, including legacy and current cellular standards.
 */
enum class CellHistoryTechnology { GSM, CDMA, WCDMA, TDSCDMA, LTE, NR }

/**
 * Concrete provider, coverage, retention and integrity causes for Cell presentation; failures are
 * flagged separately.
 */
enum class CellHistoryCause(val isIntegrityFailure: Boolean = false) {
	SESSION_ACTIVE,
	MATERIALIZATION_BEHIND,
	SOURCE_NOT_CAPTURED,
	NO_QUALIFIED_FACTS,
	PROVIDER_UNAVAILABLE,
	ACQUISITION_INCOMPLETE,
	CHILDREN_PARTIAL,
	SUBSCRIPTION_GROUPING_UNKNOWN,
	RETENTION_LIMIT,
	PRIVACY_EPOCH_MISMATCH,
	DELETED,
	IMPORTED_PRIVACY_EPOCH_MISMATCH(isIntegrityFailure = true),
	IMPORTED_EVIDENCE_UNVERIFIABLE(isIntegrityFailure = true),
	IMPORTED_SELECTION_STALE(isIntegrityFailure = true),
	ORIGIN_IDENTITY_CONFLICT(isIntegrityFailure = true),
	READ_BUDGET_EXCEEDED(isIntegrityFailure = true),
	PHYSICAL_MEMBERSHIP_INVALID(isIntegrityFailure = true),
	MANIFEST_INTEGRITY_FAILED(isIntegrityFailure = true),
	PLAN_INTEGRITY_FAILED(isIntegrityFailure = true),
	WRITER_PROVENANCE_INVALID(isIntegrityFailure = true),
	FACT_INTEGRITY_FAILED(isIntegrityFailure = true),
	STORED_ZONE_INVALID(isIntegrityFailure = true),
}

/** One logical Cell entry composed without exposing physical replacement-run identities. */
data class CellHistoryEntry(
	val key: CellHistoryEntryKey,
	val startTime: EpochMs,
	val endTime: EpochMs,
	val storedZoneIds: Set<String>,
	val state: CellHistoryProductState,
	val coverage: CellHistoryCoverage,
	val observations: List<CellHistoryObservation>,
	val causes: Set<CellHistoryCause> = emptySet(),
	val origin: CellHistoryOrigin = CellHistoryOrigin.Local,
	val selection: CellHistoryEntrySelection? =
		(origin as? CellHistoryOrigin.Imported)?.selection,
) {
	init {
		require(origin is CellHistoryOrigin.Local || observations.all { it.localSourceEventId == null })
		require(endTime >= startTime)
		require(storedZoneIds.none(String::isBlank))
		when (origin) {
			CellHistoryOrigin.Local -> require(selection !is ImportedCellHistorySelection)
			is CellHistoryOrigin.Imported -> require(selection == origin.selection)
		}
		when (state) {
			CellHistoryProductState.READY -> {
				require(observations.isNotEmpty())
				require(causes.none { it.isIntegrityFailure })
			}
			CellHistoryProductState.PARTIAL -> {
				require(observations.isNotEmpty() && causes.isNotEmpty())
			}
			CellHistoryProductState.MATERIALIZING,
			CellHistoryProductState.UNAVAILABLE,
			CellHistoryProductState.MISSING,
			CellHistoryProductState.DELETED,
			CellHistoryProductState.UNVERIFIABLE,
			-> {
				require(observations.isEmpty() && coverage == CellHistoryCoverage.NONE)
				require(causes.isNotEmpty())
				if (state == CellHistoryProductState.DELETED) {
					require(CellHistoryCause.DELETED in causes)
				}
				if (state == CellHistoryProductState.UNVERIFIABLE) {
					require(causes.any { it.isIntegrityFailure })
				}
			}
			CellHistoryProductState.FAILED -> {
				require(observations.isEmpty() && coverage == CellHistoryCoverage.NONE)
				require(causes.any { it.isIntegrityFailure })
			}
		}
	}
}

/** One provider-confirmed delivery with its available, locally retained tower readings. */
data class CellHistoryObservation(
	val intervalStartTime: EpochMs,
	val observedTime: EpochMs,
	val wallTimeUncertaintyMs: Long,
	val availability: CellHistoryAvailability,
	val subscriptionGrouping: CellHistorySubscriptionGrouping,
	val childCompleteness: CellHistoryChildCompleteness,
	val submittedChildCount: Int,
	val acceptedChildCount: Int,
	val rejectedChildCount: Int,
	val registeredObservationCount: Int,
	val technologyMix: Map<CellHistoryTechnology, Int>,
	val signalQuality: CellHistorySignalQuality,
	val weakObservationCount: Int,
	val allKnownQualityIsWeak: Boolean,
	val sourceQualityFlags: Long,
	val sourceQualityConfidence: Float?,
	val storedZoneId: String,
	/** Opaque native provider event used only to join independently retained optional context. */
	val localSourceEventId: String? = null,
	val towers: List<CellObservedTower> = emptyList(),
) {
	init {
		require(observedTime >= intervalStartTime)
		require(wallTimeUncertaintyMs >= 0L)
		require(submittedChildCount > 0 && acceptedChildCount > 0)
		require(rejectedChildCount >= 0 && acceptedChildCount + rejectedChildCount == submittedChildCount)
		require(registeredObservationCount in 0..acceptedChildCount)
		require(technologyMix.values.all { it > 0 } && technologyMix.values.sum() == acceptedChildCount)
		require(signalQuality.totalCount == acceptedChildCount)
		require(weakObservationCount == signalQuality.noneOrUnknownCount + signalQuality.poorCount)
		require(allKnownQualityIsWeak ==
			(signalQuality.knownCount > 0 && weakObservationCount == signalQuality.knownCount))
		require(sourceQualityFlags >= 0L)
		require(sourceQualityConfidence == null || sourceQualityConfidence in 0f..1f)
		require(storedZoneId.isNotBlank())
		require(localSourceEventId == null || localSourceEventId.isNotBlank())
		require(towers.size <= acceptedChildCount)
	}
}

/** The phone observed this Cell identity; it does not establish a tower position. */
data class CellObservedTower(
	val observedTimeMs: Long,
	val wallTimeUncertaintyMs: Long,
	val identity: String?,
	val technology: CellHistoryTechnology,
	val registered: Boolean,
	val signalDbm: Int?,
) {
	init {
		require(observedTimeMs >= 0L && wallTimeUncertaintyMs >= 0L)
		require(identity == null || identity.isNotBlank())
	}

	override fun toString(): String = "CellObservedTower"
}

/**
 * Counts of original Android signal-quality bins; these are not inferred dBm measurements.
 */
data class CellHistorySignalQuality(
	val unknownCount: Int,
	val noneOrUnknownCount: Int,
	val poorCount: Int,
	val moderateCount: Int,
	val goodCount: Int,
	val greatCount: Int,
) {
	init {
		require(listOf(unknownCount, noneOrUnknownCount, poorCount, moderateCount,
			goodCount, greatCount).all { it >= 0 })
	}

	val totalCount: Int
		get() = listOf(unknownCount, noneOrUnknownCount, poorCount, moderateCount,
			goodCount, greatCount).sum()
	val knownCount: Int get() = totalCount - unknownCount
}

private val OPAQUE_CELL_IDENTITY = Regex("[0-9a-f]{64}")
private const val MILLIS_PER_DAY = 86_400_000L
private const val MAX_CELL_RANGE_DAY_COUNT = 370L
private const val MIN_SUPPORTED_CELL_EPOCH_DAY = 0L
private const val MAX_SUPPORTED_CELL_EPOCH_DAY =
	Long.MAX_VALUE / MILLIS_PER_DAY - 2L
