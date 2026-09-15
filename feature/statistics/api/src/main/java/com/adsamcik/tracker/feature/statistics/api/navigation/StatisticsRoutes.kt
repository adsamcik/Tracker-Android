package com.adsamcik.tracker.feature.statistics.api.navigation

import com.adsamcik.tracker.shared.base.time.SystemClock
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
	private const val SELECTION_TTL_NANOS = 120_000_000_000L
	private val lock = Any()
	private val selections = LinkedHashMap<String, PendingSelection>()

	fun register(
		selection: SourceHistoryDetailSelection,
		nowElapsedRealtimeNanos: Long = SystemClock.elapsedRealtimeNanos(),
	): SourceHistoryDetail {
		require(nowElapsedRealtimeNanos >= 0L)
		val token = UUID.randomUUID().toString()
		synchronized(lock) {
			pruneExpired(nowElapsedRealtimeNanos)
			while (selections.size >= MAX_RETAINED_SELECTIONS) {
				selections.remove(selections.keys.first())
			}
			selections[token] = PendingSelection(
				selection = selection,
				expiresAtElapsedRealtimeNanos = saturatedAdd(
					nowElapsedRealtimeNanos,
					SELECTION_TTL_NANOS,
				),
			)
		}
		return SourceHistoryDetail(token)
	}

	/**
	 * Atomically transfers one selection to its destination owner.
	 *
	 * A token is single-use: the pending registry removes it before returning the selection.
	 */
	fun consume(
		selectionToken: String,
		nowElapsedRealtimeNanos: Long = SystemClock.elapsedRealtimeNanos(),
	): SourceHistoryDetailSelection? = synchronized(lock) {
		require(nowElapsedRealtimeNanos >= 0L)
		pruneExpired(nowElapsedRealtimeNanos)
		val pending = selections.remove(selectionToken) ?: return@synchronized null
		pending.selection.takeIf {
			pending.expiresAtElapsedRealtimeNanos > nowElapsedRealtimeNanos
		}
	}

	/** Releases an unresolved navigation token after failed navigation or abandoned ownership. */
	fun release(selectionToken: String) {
		synchronized(lock) {
			selections.remove(selectionToken)
		}
	}

	private fun pruneExpired(nowElapsedRealtimeNanos: Long) {
		val iterator = selections.entries.iterator()
		while (iterator.hasNext()) {
			if (iterator.next().value.expiresAtElapsedRealtimeNanos <= nowElapsedRealtimeNanos) {
				iterator.remove()
			}
		}
	}

	private fun saturatedAdd(left: Long, right: Long): Long =
		if (left <= Long.MAX_VALUE - right) left + right else Long.MAX_VALUE

	private data class PendingSelection(
		val selection: SourceHistoryDetailSelection,
		val expiresAtElapsedRealtimeNanos: Long,
	)
}

@Serializable
data object History
