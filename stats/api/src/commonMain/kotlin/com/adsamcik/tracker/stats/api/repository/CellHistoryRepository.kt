package com.adsamcik.tracker.stats.api.repository

import com.adsamcik.tracker.stats.api.value.EpochMs

/** Read-only, identity-free access to captured Cell history. */
interface CellHistoryRepository {
	/** Resolves one repository-issued opaque selection without exposing its backing identity. */
	suspend fun detail(selection: CellHistoryEntrySelection): CellHistoryQuery =
		if (selection is ImportedCellHistorySelection) imported(selection) else CellHistoryQuery.NotFound

	/** Resolves the complete logical replacement group containing one physical presentation row. */
	suspend fun session(segmentId: Long): CellHistoryQuery

	/** Resolves one exact imported-origin selection without treating it as a local session. */
	suspend fun imported(selection: ImportedCellHistorySelection): CellHistoryQuery =
		CellHistoryQuery.NotFound

	/** Discovers recent logical entries from qualified Cell facts, never presentation counters. */
	suspend fun recent(limit: Int): CellHistoryPage
}

sealed interface CellHistoryQuery {
	data object NotFound : CellHistoryQuery
	data class Found(val entry: CellHistoryEntry) : CellHistoryQuery
}

sealed interface CellHistoryPage {
	data class Available(val entries: List<CellHistoryEntry>) : CellHistoryPage
	data class Failed(val cause: CellHistoryCause) : CellHistoryPage {
		init {
			require(cause.isIntegrityFailure)
		}
	}
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

data class LocalCellHistorySelection(
	val identity: LocalCellHistoryIdentity,
) : CellHistoryEntrySelection

/** Proven presentation origin. Neither form grants mutation or live capture authority. */
sealed interface CellHistoryOrigin {
	data object Local : CellHistoryOrigin
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

@JvmInline
value class ImportedCellHistoryDigest(val value: String) {
	init {
		require(OPAQUE_CELL_IDENTITY.matches(value))
	}

	override fun toString(): String = "ImportedCellHistoryDigest"
}

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
enum class CellHistoryCoverage { NONE, PARTIAL, COMPLETE, UNKNOWN }
enum class CellHistoryAvailability { AVAILABLE }
enum class CellHistorySubscriptionGrouping { UNKNOWN }
enum class CellHistoryChildCompleteness { COMPLETE, PARTIAL }
enum class CellHistoryTechnology { GSM, CDMA, WCDMA, TDSCDMA, LTE, NR }

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

/** One provider-confirmed delivery, minimized to product-safe counts and quality bands. */
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
	}
}

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
