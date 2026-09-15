package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.stats.api.repository.WifiHistoryAvailability
import com.adsamcik.tracker.stats.api.repository.WifiHistoryBand
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
			listOf(entry),
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
	fun `uncertainty crossing stored-zone midnight creates partial nonadditive memberships`() {
		val zone = ZoneId.of("Europe/Prague")
		val observed = ZonedDateTime.of(2026, 10, 25, 0, 0, 0, 0, zone)
			.toInstant().toEpochMilli()
		val entry = entry(
			"midnight",
			observation(
				intervalStartMs = observed,
				observedMs = observed,
				uncertaintyMs = 60_000L,
				zoneId = zone.id,
			),
		)

		val days = WifiHistoryStructuralDayComposer.compose(
			listOf(entry),
			observed - 60_000L,
			observed + 60_001L,
		)

		days.size shouldBe 2
		days.flatMap { it.memberships }.all {
			it.allocation == WifiHistoryDayAllocation.PARTIAL
		} shouldBe true
		days.flatMap { it.memberships }.all { it.entry.observations.size == 1 } shouldBe true
	}

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
