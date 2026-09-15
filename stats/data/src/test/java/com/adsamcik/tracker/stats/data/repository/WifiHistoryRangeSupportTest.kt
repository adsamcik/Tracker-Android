package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.stats.api.repository.WifiHistoryAvailability
import com.adsamcik.tracker.stats.api.repository.WifiHistoryBand
import com.adsamcik.tracker.stats.api.repository.WifiHistoryCause
import com.adsamcik.tracker.stats.api.repository.WifiHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.WifiHistoryDayAllocation
import com.adsamcik.tracker.stats.api.repository.WifiHistoryEntry
import com.adsamcik.tracker.stats.api.repository.WifiHistoryEntryKey
import com.adsamcik.tracker.stats.api.repository.WifiHistoryObservation
import com.adsamcik.tracker.stats.api.repository.WifiHistoryProductState
import com.adsamcik.tracker.stats.api.repository.WifiHistoryResultCompleteness
import com.adsamcik.tracker.stats.api.repository.WifiHistorySignalQuality
import com.adsamcik.tracker.stats.api.value.EpochMs
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Test

class WifiHistoryRangeSupportTest {
	@Test
	fun `stored Prague zone keeps a DST transition observation on its structural day`() {
		val zone = ZoneId.of("Europe/Prague")
		val beforeJump = ZonedDateTime.of(2026, 3, 29, 1, 30, 0, 0, zone).toInstant().toEpochMilli()
		val afterJump = Instant.parse("2026-03-29T01:30:00Z").toEpochMilli()
		val entry = entry(
			"dst",
			observation(beforeJump, afterJump, 1_000L, zone.id),
		)

		val days = WifiHistoryStructuralDayComposer.compose(
			listOf(projection(entry)),
			beforeJump - 60_000L,
			afterJump + 60_001L,
		)

		days.single().epochDay shouldBe ZonedDateTime.of(
			2026,
			3,
			29,
			12,
			0,
			0,
			0,
			zone,
		).toLocalDate().toEpochDay()
		days.single().storedZoneId shouldBe zone.id
		days.single().memberships.single().allocation shouldBe WifiHistoryDayAllocation.EXACT
	}

	@Test
	fun `already expanded observation bounds are not expanded by uncertainty twice`() {
		val zone = ZoneId.of("Europe/Prague")
		val earliest = ZonedDateTime.of(2026, 10, 25, 0, 0, 30, 0, zone)
			.toInstant().toEpochMilli()
		val observed = earliest + 60_000L
		val latest = observed + 60_000L
		val entry = entry(
			"midnight",
			observation(
				intervalStartMs = earliest,
				observedMs = observed,
				uncertaintyMs = 60_000L,
				zoneId = zone.id,
			),
		)

		val days = WifiHistoryStructuralDayComposer.compose(
			listOf(projection(entry, earliest, latest)),
			earliest,
			latest + 1L,
		)

		days.size shouldBe 1
		days.single().memberships.single().allocation shouldBe WifiHistoryDayAllocation.EXACT
	}

	@Test
	fun `empty runs keep disjoint stored zones within their own envelopes`() {
		val utc = ZoneId.of("UTC")
		val prague = ZoneId.of("Europe/Prague")
		val firstStart = ZonedDateTime.of(2026, 1, 10, 12, 0, 0, 0, utc)
			.toInstant().toEpochMilli()
		val secondStart = ZonedDateTime.of(2026, 1, 12, 12, 0, 0, 0, prague)
			.toInstant().toEpochMilli()
		val entry = emptyEntry("empty-runs", firstStart, secondStart + 60_000L, setOf(utc.id, prague.id))

		val days = WifiHistoryStructuralDayComposer.compose(
			listOf(
				WifiHistoryStructuralEntryProjection(
					entry,
					listOf(
						emptyRun(firstStart, firstStart + 60_000L, utc.id),
						emptyRun(secondStart, secondStart + 60_000L, prague.id),
					),
				),
			),
			firstStart,
			secondStart + 60_001L,
		)

		days.map { it.storedZoneId } shouldBe listOf(prague.id, utc.id)
		days.single { it.storedZoneId == utc.id }.epochDay shouldBe
			Instant.ofEpochMilli(firstStart).atZone(utc).toLocalDate().toEpochDay()
		days.single { it.storedZoneId == prague.id }.epochDay shouldBe
			Instant.ofEpochMilli(secondStart).atZone(prague).toLocalDate().toEpochDay()
	}

	@Test
	fun `fact run and empty sibling both retain run-owned day membership`() {
		val utc = ZoneId.of("UTC")
		val prague = ZoneId.of("Europe/Prague")
		val observed = ZonedDateTime.of(2026, 2, 1, 12, 0, 0, 0, utc)
			.toInstant().toEpochMilli()
		val emptyStart = ZonedDateTime.of(2026, 2, 3, 12, 0, 0, 0, prague)
			.toInstant().toEpochMilli()
		val publicObservation = observation(observed - 1L, observed, 1L, utc.id)
		val entry = entry("mixed-runs", publicObservation).copy(
			startTime = EpochMs(observed - 1_000L),
			endTime = EpochMs(emptyStart + 60_000L),
			storedZoneIds = setOf(utc.id, prague.id),
			state = WifiHistoryProductState.PARTIAL,
			coverage = WifiHistoryCoverage.PARTIAL,
			causes = setOf(WifiHistoryCause.NO_QUALIFIED_FACTS),
		)

		val days = WifiHistoryStructuralDayComposer.compose(
			listOf(
				WifiHistoryStructuralEntryProjection(
					entry,
					listOf(
						WifiHistoryStructuralRunProjection(
							observed - 1_000L,
							observed + 1_000L,
							setOf(utc.id),
							listOf(
								WifiHistoryStructuralObservationProjection(
									observed - 1L,
									observed + 1L,
									utc.id,
								),
							),
						),
						emptyRun(emptyStart, emptyStart + 60_000L, prague.id),
					),
				),
			),
			observed - 1_000L,
			emptyStart + 60_001L,
		)

		days.single { it.storedZoneId == utc.id }
			.memberships.single().allocation shouldBe WifiHistoryDayAllocation.PARTIAL
		days.single { it.storedZoneId == prague.id }
			.memberships.single().allocation shouldBe WifiHistoryDayAllocation.UNAVAILABLE
	}

	private fun projection(
		entry: WifiHistoryEntry,
		earliest: Long = entry.observations.single().intervalStartTime.raw,
		latest: Long = Math.addExact(
			entry.observations.single().observedTime.raw,
			entry.observations.single().wallTimeUncertaintyMs,
		),
	) = WifiHistoryStructuralEntryProjection(
		entry,
		listOf(
			WifiHistoryStructuralRunProjection(
				entry.startTime.raw,
				entry.endTime.raw,
				entry.storedZoneIds,
				listOf(
					WifiHistoryStructuralObservationProjection(
						earliest,
						latest,
						entry.observations.single().storedZoneId,
					),
				),
			),
		),
	)

	private fun emptyRun(startTimeMs: Long, endTimeMs: Long, zoneId: String) =
		WifiHistoryStructuralRunProjection(
			startTimeMs,
			endTimeMs,
			setOf(zoneId),
			emptyList(),
		)

	private fun emptyEntry(
		seed: String,
		startTimeMs: Long,
		endTimeMs: Long,
		zones: Set<String>,
	) = WifiHistoryEntry(
		key = WifiHistoryEntryKey("range-$seed"),
		startTime = EpochMs(startTimeMs),
		endTime = EpochMs(endTimeMs),
		storedZoneIds = zones,
		state = WifiHistoryProductState.UNAVAILABLE,
		coverage = WifiHistoryCoverage.NONE,
		observations = emptyList(),
		causes = setOf(WifiHistoryCause.PROVIDER_UNAVAILABLE),
	)

	private fun entry(seed: String, observation: WifiHistoryObservation) = WifiHistoryEntry(
		key = WifiHistoryEntryKey("range-$seed"),
		startTime = observation.intervalStartTime,
		endTime = observation.observedTime,
		storedZoneIds = setOf(observation.storedZoneId),
		state = WifiHistoryProductState.READY,
		coverage = WifiHistoryCoverage.COMPLETE,
		observations = listOf(observation),
	)

	private fun observation(
		intervalStartMs: Long,
		observedMs: Long,
		uncertaintyMs: Long,
		zoneId: String,
	) = WifiHistoryObservation(
		intervalStartTime = EpochMs(intervalStartMs),
		observedTime = EpochMs(observedMs),
		wallTimeUncertaintyMs = uncertaintyMs,
		availability = WifiHistoryAvailability.AVAILABLE,
		resultCompleteness = WifiHistoryResultCompleteness.COMPLETE,
		submittedResultCount = 1,
		acceptedResultCount = 1,
		rejectedResultCount = 0,
		observationCount = 1,
		bandMix = mapOf(WifiHistoryBand.FIVE_GHZ to 1),
		signalQuality = WifiHistorySignalQuality(-60, -60, -60.0, 1),
		sourceQualityFlags = 0L,
		sourceQualityConfidence = 1f,
		storedZoneId = zoneId,
	)
}
