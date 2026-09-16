package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.PortableActivityDigest
import com.adsamcik.tracker.shared.base.database.PortableActivityOpaqueIdentity
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryEntry
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryEntryKey
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryOrigin
import com.adsamcik.tracker.stats.api.repository.CellHistoryEntry
import com.adsamcik.tracker.stats.api.repository.CellHistoryOrigin
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.ImportedCellHistorySelection
import com.adsamcik.tracker.stats.api.repository.ImportedPressureHistoryIdentity
import com.adsamcik.tracker.stats.api.repository.PortablePressureDigest
import com.adsamcik.tracker.stats.api.repository.PressureHistoryOrigin
import com.adsamcik.tracker.stats.api.repository.PressureOnlyHistoryEntry
import com.adsamcik.tracker.stats.api.repository.SourceAwareHistoryPageUnavailableReason
import com.adsamcik.tracker.stats.api.repository.WifiHistoryEntry
import com.adsamcik.tracker.stats.api.repository.WifiHistoryOrigin
import com.adsamcik.tracker.stats.api.repository.WifiImportedHistorySelection

/**
 * Opaque, source-authenticated identity of the newest imported physical member.
 *
 * Comparison is lexical only. The shared compositor never parses it as a native row id.
 */
@JvmInline
internal value class ImportedHistoryRecencyTieIdentity(
	private val encodedValue: String,
) : Comparable<ImportedHistoryRecencyTieIdentity> {
	init {
		require(encodedValue.isNotBlank())
	}

	override fun compareTo(other: ImportedHistoryRecencyTieIdentity): Int =
		encodedValue.compareTo(other.encodedValue)

	override fun toString(): String = "ImportedHistoryRecencyTieIdentity"
}

/**
 * Authenticated newest-member recency for one imported source product.
 *
 * [newestMemberStartTimeMs] and [newestMemberTieIdentity] must come from the same retained member.
 * Entry-envelope start/end and import receipt time are not recency authority.
 */
internal data class ImportedHistoryRecency(
	val source: HistorySource,
	val newestMemberStartTimeMs: Long,
	val newestMemberTieIdentity: ImportedHistoryRecencyTieIdentity,
) {
	init {
		require(
			source in setOf(
				HistorySource.WIFI,
				HistorySource.CELL,
				HistorySource.ACTIVITY,
				HistorySource.PRESSURE,
			),
		)
		require(newestMemberStartTimeMs >= 0L)
	}
}

/** Deterministic imported ordering after source-owned eligibility and duplicate resolution. */
internal val importedHistoryRecencyOrder: Comparator<ImportedHistoryRecency> =
	compareByDescending<ImportedHistoryRecency>(
		ImportedHistoryRecency::newestMemberStartTimeMs,
	).thenBy { it.stableSourceOrder }
		.thenByDescending(ImportedHistoryRecency::newestMemberTieIdentity)

private val ImportedHistoryRecency.stableSourceOrder: Int
	get() = when (source) {
		HistorySource.WIFI -> 0
		HistorySource.CELL -> 1
		HistorySource.ACTIVITY -> 2
		HistorySource.PRESSURE -> 3
		HistorySource.LOCATION,
		HistorySource.STEPS,
		-> error("Imported history recency supports only the four current producer bridges")
	}

/**
 * Accepted imported-only page. Implementations apply the requested limit after authentication and exact
 * duplicate/conflict resolution. Exhausted dependency or candidate budgets return [Unavailable].
 */
internal sealed interface ImportedHistoryEligiblePage<out T> {
	data class Available<T>(
		val entries: List<T>,
	) : ImportedHistoryEligiblePage<T>

	data class Unavailable(
		val reason: SourceAwareHistoryPageUnavailableReason,
	) : ImportedHistoryEligiblePage<Nothing> {
		init {
			require(
				reason in setOf(
					SourceAwareHistoryPageUnavailableReason.SOURCE_READ_BUDGET_EXCEEDED,
					SourceAwareHistoryPageUnavailableReason.SOURCE_INTEGRITY_FAILURE,
					SourceAwareHistoryPageUnavailableReason
						.SOURCE_RECENCY_AUTHORITY_UNAVAILABLE,
				),
			)
		}
	}
}

internal data class WifiImportedHistoryEligibleEntry(
	val entry: WifiHistoryEntry,
	val selection: WifiImportedHistorySelection,
	val recency: ImportedHistoryRecency,
) {
	init {
		require(entry.origin == WifiHistoryOrigin.IMPORTED)
		require(entry.importedSelection == selection)
		require(recency.source == HistorySource.WIFI)
		recency.requireWithin(entry.startTime.raw, entry.endTime.raw)
	}
}

internal data class CellImportedHistoryEligibleEntry(
	val entry: CellHistoryEntry,
	val selection: ImportedCellHistorySelection,
	val recency: ImportedHistoryRecency,
) {
	init {
		require(entry.origin == CellHistoryOrigin.Imported(selection))
		require(entry.selection == selection)
		require(recency.source == HistorySource.CELL)
		recency.requireWithin(entry.startTime.raw, entry.endTime.raw)
	}
}

internal data class ActivityImportedHistoryEligibleEntry(
	val entry: ActivityHistoryEntry,
	val selector: ActivityHistoryEntryKey,
	val identity: PortableActivityOpaqueIdentity,
	val importRevision: Long,
	val contentChecksum: PortableActivityDigest,
	val recency: ImportedHistoryRecency,
) {
	init {
		require(entry.origin == ActivityHistoryOrigin.IMPORTED)
		require(!entry.capturesOnlyActivity)
		require(entry.key == selector)
		require(importRevision > 0L)
		require(recency.source == HistorySource.ACTIVITY)
		recency.requireWithin(entry.startTime.raw, entry.endTime.raw)
	}
}

internal data class PressureImportedHistoryEligibleEntry(
	val entry: PressureOnlyHistoryEntry,
	val identity: ImportedPressureHistoryIdentity,
	val importRevision: Long,
	val contentChecksum: PortablePressureDigest,
	val recency: ImportedHistoryRecency,
) {
	init {
		require(entry.origin == PressureHistoryOrigin.Imported(identity))
		require(importRevision > 0L)
		require(recency.source == HistorySource.PRESSURE)
		recency.requireWithin(entry.startTime.raw, entry.endTime.raw)
	}
}

private fun ImportedHistoryRecency.requireWithin(
	entryStartTimeMs: Long,
	entryEndTimeMs: Long,
) {
	require(newestMemberStartTimeMs in entryStartTimeMs..entryEndTimeMs) {
		"Imported recency must belong to the authenticated entry envelope"
	}
}

internal interface WifiImportedHistoryEligibleReader {
	suspend fun recentImportedEligibleForSharedHistoryInTransaction(
		limit: Int,
	): ImportedHistoryEligiblePage<WifiImportedHistoryEligibleEntry>
}

internal interface CellImportedHistoryEligibleReader {
	suspend fun recentImportedEligibleForSharedHistoryInTransaction(
		limit: Int,
	): ImportedHistoryEligiblePage<CellImportedHistoryEligibleEntry>
}

internal interface ActivityImportedHistoryEligibleReader {
	suspend fun recentImportedEligibleForSharedHistoryInTransaction(
		limit: Int,
	): ImportedHistoryEligiblePage<ActivityImportedHistoryEligibleEntry>
}

internal interface PressureImportedHistoryEligibleReader {
	suspend fun recentImportedEligibleForSharedHistoryInTransaction(
		limit: Int,
	): ImportedHistoryEligiblePage<PressureImportedHistoryEligibleEntry>
}
