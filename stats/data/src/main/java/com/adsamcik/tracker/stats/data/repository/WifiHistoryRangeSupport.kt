package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.stats.api.repository.WifiHistoryDayAllocation
import com.adsamcik.tracker.stats.api.repository.WifiHistoryEntry
import com.adsamcik.tracker.stats.api.repository.WifiHistoryProductState
import com.adsamcik.tracker.stats.api.repository.WifiHistoryRangeContinuation
import com.adsamcik.tracker.stats.api.repository.WifiHistoryStructuralDay
import com.adsamcik.tracker.stats.api.repository.WifiHistoryStructuralDayMembership
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.util.Base64

internal data class WifiHistoryRangeCursor(
	val fromInclusiveMs: Long,
	val toExclusiveMs: Long,
	val localStartTimeMs: Long?,
	val localSegmentId: Long?,
	val importedStartTimeMs: Long?,
	val importedIdentity: String?,
) {
	init {
		require(fromInclusiveMs >= 0L && toExclusiveMs > fromInclusiveMs)
		require((localStartTimeMs == null) == (localSegmentId == null))
		require((importedStartTimeMs == null) == (importedIdentity == null))
	}
}

internal object WifiHistoryRangeCursorCodec {
	fun encode(cursor: WifiHistoryRangeCursor): WifiHistoryRangeContinuation {
		val body = listOf(
			VERSION,
			cursor.fromInclusiveMs.toString(),
			cursor.toExclusiveMs.toString(),
			cursor.localStartTimeMs?.toString() ?: NULL,
			cursor.localSegmentId?.toString() ?: NULL,
			cursor.importedStartTimeMs?.toString() ?: NULL,
			cursor.importedIdentity?.let(::encodeText) ?: NULL,
		).joinToString(SEPARATOR)
		return WifiHistoryRangeContinuation("$body$SEPARATOR${sha256(body)}")
	}

	fun decode(
		continuation: WifiHistoryRangeContinuation?,
		fromInclusiveMs: Long,
		toExclusiveMs: Long,
	): WifiHistoryRangeCursor? {
		if (continuation == null) {
			return WifiHistoryRangeCursor(
				fromInclusiveMs,
				toExclusiveMs,
				null,
				null,
				null,
				null,
			)
		}
		val parts = continuation.value.split(SEPARATOR)
		if (parts.size != PART_COUNT) return null
		val body = parts.dropLast(1).joinToString(SEPARATOR)
		if (parts.last() != sha256(body) || parts[0] != VERSION) return null
		return runCatching {
			WifiHistoryRangeCursor(
				fromInclusiveMs = parts[1].toLong(),
				toExclusiveMs = parts[2].toLong(),
				localStartTimeMs = parts[3].takeUnless { it == NULL }?.toLong(),
				localSegmentId = parts[4].takeUnless { it == NULL }?.toLong(),
				importedStartTimeMs = parts[5].takeUnless { it == NULL }?.toLong(),
				importedIdentity = parts[6].takeUnless { it == NULL }?.let(::decodeText),
			)
		}.getOrNull()?.takeIf {
			it.fromInclusiveMs == fromInclusiveMs && it.toExclusiveMs == toExclusiveMs
		}
	}

	private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
		.digest(value.toByteArray(Charsets.UTF_8))
		.joinToString("") { "%02x".format(it) }

	private fun encodeText(value: String): String = Base64.getUrlEncoder().withoutPadding()
		.encodeToString(value.toByteArray(Charsets.UTF_8))

	private fun decodeText(value: String): String = Base64.getUrlDecoder().decode(value)
		.toString(Charsets.UTF_8)

	private const val VERSION = "wifi-range-v1"
	private const val NULL = "~"
	private const val SEPARATOR = "|"
	private const val PART_COUNT = 8
}

internal object WifiHistoryStructuralDayComposer {
	fun compose(
		entries: List<WifiHistoryEntry>,
		fromInclusiveMs: Long,
		toExclusiveMs: Long,
	): List<WifiHistoryStructuralDay> {
		require(fromInclusiveMs >= 0L && toExclusiveMs > fromInclusiveMs)
		val memberships = linkedMapOf<Pair<Long, String>, MutableMap<WifiHistoryEntry, WifiHistoryDayAllocation>>()
		entries.forEach { entry ->
			if (entry.observations.isEmpty()) {
				entry.storedZoneIds.forEach zoneLoop@ { zoneId ->
					val start = maxOf(entry.startTime.raw, fromInclusiveMs)
					val endExclusive = minOf(
						if (entry.endTime.raw > entry.startTime.raw) entry.endTime.raw else
							Math.addExact(entry.endTime.raw, 1L),
						toExclusiveMs,
					)
					if (endExclusive <= start) return@zoneLoop
					addRange(
						entry,
						zoneId,
						start,
						endExclusive - 1L,
						WifiHistoryDayAllocation.UNAVAILABLE,
						memberships,
					)
				}
			} else {
				entry.observations.forEach observationLoop@ { observation ->
					val earliest = Math.subtractExact(
						observation.intervalStartTime.raw,
						observation.wallTimeUncertaintyMs,
					).coerceAtLeast(0L)
					val latest = Math.addExact(
						observation.observedTime.raw,
						observation.wallTimeUncertaintyMs,
					)
					if (latest < fromInclusiveMs || earliest >= toExclusiveMs) return@observationLoop
					val zone = ZoneId.of(observation.storedZoneId)
					val firstDay = Instant.ofEpochMilli(earliest).atZone(zone).toLocalDate().toEpochDay()
					val lastDay = Instant.ofEpochMilli(latest).atZone(zone).toLocalDate().toEpochDay()
					val clippedFirstDay = Instant.ofEpochMilli(maxOf(earliest, fromInclusiveMs))
						.atZone(zone).toLocalDate().toEpochDay()
					val clippedLastDay = Instant.ofEpochMilli(
						minOf(latest, toExclusiveMs - 1L),
					).atZone(zone).toLocalDate().toEpochDay()
					val allocation = if (
						firstDay == lastDay &&
						entry.state == WifiHistoryProductState.READY
					) {
						WifiHistoryDayAllocation.EXACT
					} else {
						WifiHistoryDayAllocation.PARTIAL
					}
					addDays(
						entry,
						observation.storedZoneId,
						clippedFirstDay,
						clippedLastDay,
						allocation,
						memberships,
					)
				}
			}
		}
		return memberships.map { (key, byEntry) ->
			WifiHistoryStructuralDay(
				epochDay = key.first,
				storedZoneId = key.second,
				memberships = byEntry.map { (entry, allocation) ->
					WifiHistoryStructuralDayMembership(entry, allocation)
				},
			)
		}.sortedWith(
			compareByDescending<WifiHistoryStructuralDay> { it.epochDay }
				.thenBy(WifiHistoryStructuralDay::storedZoneId),
		)
	}

	private fun addRange(
		entry: WifiHistoryEntry,
		zoneId: String,
		startTimeMs: Long,
		endTimeMs: Long,
		allocation: WifiHistoryDayAllocation,
		memberships: MutableMap<Pair<Long, String>, MutableMap<WifiHistoryEntry, WifiHistoryDayAllocation>>,
	) {
		val zone = ZoneId.of(zoneId)
		val firstDay = Instant.ofEpochMilli(startTimeMs).atZone(zone).toLocalDate().toEpochDay()
		val lastDay = Instant.ofEpochMilli(endTimeMs).atZone(zone).toLocalDate().toEpochDay()
		addDays(entry, zoneId, firstDay, lastDay, allocation, memberships)
	}

	private fun addDays(
		entry: WifiHistoryEntry,
		zoneId: String,
		firstDay: Long,
		lastDay: Long,
		allocation: WifiHistoryDayAllocation,
		memberships: MutableMap<Pair<Long, String>, MutableMap<WifiHistoryEntry, WifiHistoryDayAllocation>>,
	) {
		val span = Math.addExact(Math.subtractExact(lastDay, firstDay), 1L)
		if (span !in 1L..MAX_STRUCTURAL_DAYS_PER_ENTRY) throw WifiHistoryRangeLimitExceeded()
		for (epochDay in firstDay..lastDay) {
			val byEntry = memberships.getOrPut(epochDay to zoneId) { linkedMapOf() }
			val prior = byEntry[entry]
			byEntry[entry] = when {
				prior == null -> allocation
				prior == WifiHistoryDayAllocation.PARTIAL ||
					allocation == WifiHistoryDayAllocation.PARTIAL -> WifiHistoryDayAllocation.PARTIAL
				prior == WifiHistoryDayAllocation.UNAVAILABLE ||
					allocation == WifiHistoryDayAllocation.UNAVAILABLE -> WifiHistoryDayAllocation.UNAVAILABLE
				else -> WifiHistoryDayAllocation.EXACT
			}
		}
	}

	private const val MAX_STRUCTURAL_DAYS_PER_ENTRY = 400L
}

internal class WifiHistoryRangeLimitExceeded : RuntimeException(null, null, false, false)
