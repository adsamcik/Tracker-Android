package com.adsamcik.tracker.dashboard.data

import com.adsamcik.tracker.shared.base.concurrency.TestDispatchersProvider
import com.adsamcik.tracker.shared.base.database.dao.TripDao
import com.adsamcik.tracker.stats.api.repository.HistoryAvailability
import com.adsamcik.tracker.stats.api.repository.HistoryEvidence
import com.adsamcik.tracker.stats.api.repository.HistoryProductState
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.ImportedPressureHistoryIdentity
import com.adsamcik.tracker.stats.api.repository.PressureHistory
import com.adsamcik.tracker.stats.api.repository.PressureHistoryCause
import com.adsamcik.tracker.stats.api.repository.PressureHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.PressureHistoryOrigin
import com.adsamcik.tracker.stats.api.repository.PressureOnlyHistoryEntry
import com.adsamcik.tracker.stats.api.repository.SourceAwareHistoryPageEntry
import com.adsamcik.tracker.stats.api.repository.SourceAwareHistoryPageQuery
import com.adsamcik.tracker.stats.api.repository.StepsOnlyHistoryEntry
import com.adsamcik.tracker.stats.api.repository.StepsOnlyHistoryListState
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryActionTarget
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryEntryKey
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryReadSnapshot
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryRepository
import com.adsamcik.tracker.stats.api.value.EpochMs
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardHistorySourceDetailTest {
	private val tripDao = mockk<TripDao>()
	private val trackingHistoryRepository = mockk<TrackingHistoryRepository> {
		every { observeSessionRewardChanges() } returns flowOf(Unit)
	}

	@Test
	fun `Steps and native Pressure retain exact source members and their common snapshot`() = runTest {
		val steps = stepsEntry("steps-action", 30_000L).copy(segmentIds = listOf(17L, 19L))
		val pressure = pressureEntry("pressure-action", 20_000L).copy(segmentIds = listOf(23L, 29L))
		val entries = listOf(SourceAwareHistoryPageEntry.StepsOnly(steps),
			SourceAwareHistoryPageEntry.PressureOnly(pressure))
		val snapshot = TrackingHistoryReadSnapshot(7L, 11L)
		every { tripDao.getRecentTripsFlow(PHYSICAL_CANDIDATE_PROBE) } returns flowOf(emptyList())
		every { trackingHistoryRepository.observeRecentSourceAwarePage(emptyList(), RECENT_HISTORY_LIMIT) } returns
			flowOf(SourceAwareHistoryPageQuery.Content(entries, readSnapshot = snapshot))

		val page = repository(testScheduler).observeRecentHistory().first()
		val stepsSelection = requireNotNull((page[0] as DashboardRecentHistoryEntry.StepsOnly).detailSelection)
		val pressureSelection = requireNotNull((page[1] as DashboardRecentHistoryEntry.PressureOnly).detailSelection)
		stepsSelection.entry shouldBe entries[0]
		pressureSelection.entry shouldBe entries[1]
		stepsSelection.readSnapshot shouldBe snapshot
		pressureSelection.readSnapshot shouldBe snapshot
		stepsSelection.actionTarget shouldBe TrackingHistoryActionTarget.CapturedSource(
			HistorySource.STEPS, listOf(17L, 19L))
		pressureSelection.actionTarget shouldBe TrackingHistoryActionTarget.CapturedSource(
			HistorySource.PRESSURE, listOf(23L, 29L))
	}

	@Test
	fun `imported Pressure retains its own identity and revision without a physical candidate`() = runTest {
		val identity = ImportedPressureHistoryIdentity("sha256:" + "e".repeat(64))
		val pressure = pressureEntry("pressure-import", 20_000L).copy(
			origin = PressureHistoryOrigin.Imported(identity), importRevision = 3L)
		val entry = SourceAwareHistoryPageEntry.PressureOnly(pressure)
		val snapshot = TrackingHistoryReadSnapshot(7L, 12L)
		every { tripDao.getRecentTripsFlow(PHYSICAL_CANDIDATE_PROBE) } returns flowOf(emptyList())
		every { trackingHistoryRepository.observeRecentSourceAwarePage(emptyList(), RECENT_HISTORY_LIMIT) } returns
			flowOf(SourceAwareHistoryPageQuery.Content(listOf(entry), readSnapshot = snapshot))

		val mapped = repository(testScheduler).observeRecentHistory().first().single() as
			DashboardRecentHistoryEntry.PressureOnly
		mapped.history shouldBe pressure
		val selection = requireNotNull(mapped.detailSelection)
		selection.entry shouldBe entry
		selection.readSnapshot shouldBe snapshot
		selection.actionTarget shouldBe TrackingHistoryActionTarget.ImportedPressure(identity, 3L)
	}

	private fun repository(testScheduler: TestCoroutineScheduler) =
		RoomDashboardHistoryRepository(
			tripDao = tripDao,
			trackingHistoryRepository = trackingHistoryRepository,
			explorationCellDao = mockk(),
			explorationStreakDao = mockk(),
			dailySummaryDao = mockk(),
			achievementProgressDao = mockk(),
			dispatchers = TestDispatchersProvider(StandardTestDispatcher(testScheduler)),
		)

	private companion object {
		const val PHYSICAL_CANDIDATE_PROBE = 65
		const val RECENT_HISTORY_LIMIT = 5
	}
}

internal fun stepsEntry(key: String, startTimeMs: Long) = StepsOnlyHistoryEntry(
	key = TrackingHistoryEntryKey(key),
	startTime = EpochMs(startTimeMs),
	endTime = EpochMs(startTimeMs + 500L),
	state = StepsOnlyHistoryListState.AVAILABLE,
)

internal fun pressureEntry(key: String, startTimeMs: Long) = PressureOnlyHistoryEntry(
	key = TrackingHistoryEntryKey(key),
	origin = PressureHistoryOrigin.Local,
	startTime = EpochMs(startTimeMs),
	endTime = EpochMs(startTimeMs + 500L),
	pressure = PressureHistory(
		availability = HistoryAvailability.UNAVAILABLE,
		evidence = HistoryEvidence.NONE,
		productState = HistoryProductState.DEGRADED,
		coverage = PressureHistoryCoverage.UNKNOWN,
		windows = emptyList(),
		causes = setOf(PressureHistoryCause.RETENTION_TRUNCATED),
	),
)

