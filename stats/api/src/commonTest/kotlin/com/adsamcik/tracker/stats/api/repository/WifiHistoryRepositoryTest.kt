package com.adsamcik.tracker.stats.api.repository

import com.adsamcik.tracker.stats.api.value.EpochMs
import kotlin.test.Test
import kotlin.test.assertFailsWith

class WifiHistoryRepositoryTest {
	@Test
	fun `unavailable history cannot fabricate an empty observation`() {
		assertFailsWith<IllegalArgumentException> {
			WifiHistoryEntry(
				key = WifiHistoryEntryKey("wifi-entry"),
				startTime = EpochMs(1L),
				endTime = EpochMs(2L),
				storedZoneIds = setOf("UTC"),
				state = WifiHistoryProductState.UNAVAILABLE,
				coverage = WifiHistoryCoverage.NONE,
				observations = listOf(observation()),
				causes = setOf(WifiHistoryCause.PROVIDER_UNAVAILABLE),
			)
		}
	}

	@Test
	fun `identity-free observation requires exact accepted count band and quality totals`() {
		assertFailsWith<IllegalArgumentException> {
			observation().copy(bandMix = mapOf(WifiHistoryBand.FIVE_GHZ to 1))
		}
		assertFailsWith<IllegalArgumentException> {
			observation().copy(signalQuality = observation().signalQuality.copy(sampleCount = 1))
		}
	}

	private fun observation() = WifiHistoryObservation(
		intervalStartTime = EpochMs(1L),
		observedTime = EpochMs(2L),
		wallTimeUncertaintyMs = 1L,
		availability = WifiHistoryAvailability.AVAILABLE,
		resultCompleteness = WifiHistoryResultCompleteness.COMPLETE,
		submittedResultCount = 2,
		acceptedResultCount = 2,
		rejectedResultCount = 0,
		observationCount = 2,
		bandMix = mapOf(WifiHistoryBand.TWO_POINT_FOUR_GHZ to 1, WifiHistoryBand.FIVE_GHZ to 1),
		signalQuality = WifiHistorySignalQuality(-50, -70, -60.0, 2),
		sourceQualityFlags = 0L,
		sourceQualityConfidence = 1f,
		storedZoneId = "UTC",
	)
}
