package com.adsamcik.tracker.feature.statistics.api.navigation

import com.adsamcik.tracker.stats.api.repository.SourceAwareHistoryPageEntry
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryActionTarget
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryReadSnapshot
import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable
data object Stats

@Serializable
data object StatsSummary

@Serializable
data object StatsWifi

@Serializable
data object StatsSignalReport

@Serializable
data class TripDetail(val tripId: Long)

/** Navigation token for one exact source-issued history selection retained in-process. */
@Serializable
data class SourceHistoryDetail(val selectionToken: String) {
	init {
		require(selectionToken.isNotBlank())
	}
}

/**
 * Exact source detail handoff. The entry and action target remain opaque source-owned objects;
 * navigation serializes only a short-lived random token and never fabricates a physical id.
 */
data class SourceHistoryDetailSelection(
	val entry: SourceAwareHistoryPageEntry,
	val readSnapshot: TrackingHistoryReadSnapshot?,
) {
	val actionTarget: TrackingHistoryActionTarget = entry.actionTarget

	init {
		require(
			entry is SourceAwareHistoryPageEntry.ActivityOnly ||
				entry is SourceAwareHistoryPageEntry.WifiOnly ||
				entry is SourceAwareHistoryPageEntry.CellOnly,
		) { "Only source-issued actionable detail entries may cross this navigation boundary" }
		require(
			actionTarget is TrackingHistoryActionTarget.Activity ||
				actionTarget is TrackingHistoryActionTarget.Wifi ||
				actionTarget is TrackingHistoryActionTarget.Cell,
		) { "Source detail navigation requires an authenticated action target" }
	}
}

/**
 * Bounded in-process handoff for non-serializable opaque selections.
 *
 * Process recreation deliberately resolves as unavailable instead of reconstructing ownership
 * from timestamps, display values, or local Trip identities.
 */
object SourceHistoryDetailHandoff {
	private const val MAX_RETAINED_SELECTIONS = 32
	private val lock = Any()
	private val selections = object : LinkedHashMap<String, SourceHistoryDetailSelection>(
		MAX_RETAINED_SELECTIONS,
		0.75f,
		true,
	) {
		override fun removeEldestEntry(
			eldest: MutableMap.MutableEntry<String, SourceHistoryDetailSelection>?,
		): Boolean = size > MAX_RETAINED_SELECTIONS
	}

	fun register(selection: SourceHistoryDetailSelection): SourceHistoryDetail {
		val token = UUID.randomUUID().toString()
		synchronized(lock) {
			selections[token] = selection
		}
		return SourceHistoryDetail(token)
	}

	fun resolve(selectionToken: String): SourceHistoryDetailSelection? = synchronized(lock) {
		selections[selectionToken]
	}

	fun release(selectionToken: String) {
		synchronized(lock) {
			selections.remove(selectionToken)
		}
	}
}

@Serializable
data object History
