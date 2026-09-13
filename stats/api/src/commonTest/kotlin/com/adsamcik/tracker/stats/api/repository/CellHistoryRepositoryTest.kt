package com.adsamcik.tracker.stats.api.repository

import com.adsamcik.tracker.stats.api.value.EpochMs
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class CellHistoryRepositoryTest {
	@Test
	fun `missing state cannot fabricate a zero observation`() {
		val entry = CellHistoryEntry(
			CellHistoryEntryKey("opaque"), EpochMs(1L), EpochMs(2L), setOf("Europe/Prague"),
			CellHistoryProductState.MISSING, CellHistoryCoverage.NONE, emptyList(),
			setOf(CellHistoryCause.NO_QUALIFIED_FACTS),
		)

		entry.observations shouldBe emptyList()
	}

	@Test
	fun `v1 observation exposes unknown subscription grouping and identity-free counts`() {
		val observation = observation()

		observation.subscriptionGrouping shouldBe CellHistorySubscriptionGrouping.UNKNOWN
		observation.technologyMix shouldBe mapOf(CellHistoryTechnology.LTE to 1)
	}

	@Test
	fun `partial delivery cannot be published as ready without a named cause`() {
		shouldThrow<IllegalArgumentException> {
			CellHistoryEntry(
				CellHistoryEntryKey("opaque"), EpochMs(1L), EpochMs(2L), setOf("UTC"),
				CellHistoryProductState.PARTIAL, CellHistoryCoverage.PARTIAL,
				listOf(observation()), emptySet(),
			)
		}
	}

	private fun observation() = CellHistoryObservation(
		intervalStartTime = EpochMs(1L),
		observedTime = EpochMs(2L),
		wallTimeUncertaintyMs = 0L,
		availability = CellHistoryAvailability.AVAILABLE,
		subscriptionGrouping = CellHistorySubscriptionGrouping.UNKNOWN,
		childCompleteness = CellHistoryChildCompleteness.COMPLETE,
		submittedChildCount = 1,
		acceptedChildCount = 1,
		rejectedChildCount = 0,
		registeredObservationCount = 1,
		technologyMix = mapOf(CellHistoryTechnology.LTE to 1),
		signalQuality = CellHistorySignalQuality(0, 0, 0, 0, 1, 0),
		weakObservationCount = 0,
		allKnownQualityIsWeak = false,
		sourceQualityFlags = 0L,
		sourceQualityConfidence = 1f,
		storedZoneId = "UTC",
	)
}
