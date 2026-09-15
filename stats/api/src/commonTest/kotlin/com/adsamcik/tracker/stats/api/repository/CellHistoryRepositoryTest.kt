package com.adsamcik.tracker.stats.api.repository

import com.adsamcik.tracker.stats.api.value.EpochMs
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlinx.coroutines.test.runTest

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
		val local = imported.copy(origin = CellHistoryOrigin.Local, selection = null)

		(imported.origin as CellHistoryOrigin.Imported).selection shouldBe selection
		imported.selection shouldBe selection
		local.origin shouldBe CellHistoryOrigin.Local
		local.selection shouldBe null
		identity.toString() shouldBe "ImportedCellHistoryIdentity"
		selection.contentChecksum.toString() shouldBe "ImportedCellHistoryDigest"
		LocalCellHistorySelection(
			LocalCellHistoryIdentity("c".repeat(64)),
		).toString() shouldBe "LocalCellHistorySelection(identity=LocalCellHistoryIdentity)"
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

	@Test
	fun `legacy repository implementors retain honest unsupported imported defaults`() = runTest {
		val selection = ImportedCellHistorySelection(
			ImportedCellHistoryIdentity("a".repeat(64)),
			1L,
			ImportedCellHistoryDigest("b".repeat(64)),
		)
		val legacy = object : CellHistoryRepository {
			override suspend fun session(segmentId: Long): CellHistoryQuery = CellHistoryQuery.NotFound
			override suspend fun recent(limit: Int): CellHistoryPage =
				CellHistoryPage.Available(emptyList())
		}

		legacy.imported(selection) shouldBe CellHistoryQuery.NotFound
		legacy.detail(selection) shouldBe CellHistoryQuery.NotFound
		legacy.range(
			CellHistoryRangeRequest(
				CellHistoryRangeScope.WallTime(EpochMs(1L), EpochMs(2L)),
				1,
			),
		) shouldBe CellHistoryRangePage.Unavailable(
			CellHistoryRangeUnavailableReason.UNSUPPORTED_BY_IMPLEMENTATION,
		)
	}

	@Test
	fun `range contracts bound wall time structural days page size and membership truth`() {
		shouldThrow<IllegalArgumentException> {
			CellHistoryRangeScope.WallTime(EpochMs(2L), EpochMs(2L))
		}
		shouldThrow<IllegalArgumentException> {
			CellHistoryRangeScope.StructuralDays(0L, 370L)
		}
		shouldThrow<IllegalArgumentException> {
			CellHistoryRangeRequest(
				CellHistoryRangeScope.StructuralDays(0L, 0L),
				101,
			)
		}
		CellHistoryRangeEntry(
			entry = CellHistoryEntry(
				CellHistoryEntryKey("range"),
				EpochMs(1L),
				EpochMs(2L),
				emptySet(),
				CellHistoryProductState.UNAVAILABLE,
				CellHistoryCoverage.NONE,
				emptyList(),
				setOf(CellHistoryCause.NO_QUALIFIED_FACTS),
			),
			structuralDays = emptySet(),
			structuralDayCompleteness = CellHistoryStructuralDayCompleteness.UNAVAILABLE,
		).structuralDays shouldBe emptySet()
	}

	@Test
	fun `selected deletion request accepts only explicit source-local selection and nonnegative time`() {
		val imported = ImportedCellHistorySelection(
			ImportedCellHistoryIdentity("a".repeat(64)),
			1L,
			ImportedCellHistoryDigest("b".repeat(64)),
		)

		DeleteCellHistoryRequest(imported, 0L).selection shouldBe imported
		DeleteCellHistoryResult.Deleted(1, 1, 0).deletedFactOrObservationCount shouldBe 0
		shouldThrow<IllegalArgumentException> {
			DeleteCellHistoryRequest(imported, -1L)
		}
		shouldThrow<IllegalArgumentException> {
			DeleteCellHistoryResult.Deleted(2, 1, 1)
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
