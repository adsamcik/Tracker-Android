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

	@Test
	fun `imported origin carries an opaque exact selection without changing native defaults`() {
		val identity = ImportedCellHistoryIdentity("a".repeat(64))
		val selection = ImportedCellHistorySelection(
			identity = identity,
			importRevision = 2L,
			contentChecksum = ImportedCellHistoryDigest("b".repeat(64)),
		)
		val imported = CellHistoryEntry(
			CellHistoryEntryKey("opaque"),
			EpochMs(1L),
			EpochMs(2L),
			setOf("UTC"),
			CellHistoryProductState.PARTIAL,
			CellHistoryCoverage.PARTIAL,
			listOf(observation()),
			setOf(CellHistoryCause.SUBSCRIPTION_GROUPING_UNKNOWN),
			CellHistoryOrigin.Imported(selection),
		)
		val local = imported.copy(origin = CellHistoryOrigin.Local)

		(imported.origin as CellHistoryOrigin.Imported).selection shouldBe selection
		local.origin shouldBe CellHistoryOrigin.Local
		identity.toString() shouldBe "ImportedCellHistoryIdentity"
		selection.contentChecksum.toString() shouldBe "ImportedCellHistoryDigest"
	}

	@Test
	fun `deleted and unverifiable states remain value free and explicitly caused`() {
		CellHistoryEntry(
			CellHistoryEntryKey("deleted"),
			EpochMs(1L),
			EpochMs(2L),
			emptySet(),
			CellHistoryProductState.DELETED,
			CellHistoryCoverage.NONE,
			emptyList(),
			setOf(CellHistoryCause.DELETED),
		).state shouldBe CellHistoryProductState.DELETED
		CellHistoryEntry(
			CellHistoryEntryKey("unverifiable"),
			EpochMs(1L),
			EpochMs(2L),
			emptySet(),
			CellHistoryProductState.UNVERIFIABLE,
			CellHistoryCoverage.NONE,
			emptyList(),
			setOf(CellHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE),
		).state shouldBe CellHistoryProductState.UNVERIFIABLE
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
