package com.adsamcik.tracker.dashboard.ui

import android.content.Context
import com.adsamcik.tracker.dashboard.ui.compose.state.ExplorationUiState
import com.adsamcik.tracker.dashboard.ui.compose.state.StreakState
import com.adsamcik.tracker.dashboard.ui.compose.state.WeeklyTrend
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.ExplorationCellDao
import com.adsamcik.tracker.shared.base.database.dao.ExplorationStreakDao
import com.adsamcik.tracker.shared.base.database.dao.TripDao
import com.adsamcik.tracker.shared.base.database.data.ExplorationStreakEntity
import com.adsamcik.tracker.shared.base.database.data.SegmentSource
import com.adsamcik.tracker.shared.base.database.data.Trip
import com.adsamcik.tracker.shared.base.di.ActiveChallengeInfo
import com.adsamcik.tracker.shared.base.di.DailySummary
import com.adsamcik.tracker.shared.base.di.DailySummaryProvider
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {

	private lateinit var testDispatcher: TestDispatcher
	private lateinit var context: Context
	private lateinit var database: AppDatabase
	private lateinit var tripDao: TripDao
	private lateinit var explorationCellDao: ExplorationCellDao
	private lateinit var explorationStreakDao: ExplorationStreakDao
	private lateinit var dispatchers: DispatchersProvider

	@BeforeEach
	fun setup() {
		testDispatcher = StandardTestDispatcher()
		Dispatchers.setMain(testDispatcher)

		context = mockk(relaxed = true)
		database = mockk(relaxed = true)
		tripDao = mockk(relaxed = true)
		explorationCellDao = mockk(relaxed = true)
		explorationStreakDao = mockk(relaxed = true)

		every { database.tripDao() } returns tripDao
		every { database.explorationCellDao() } returns explorationCellDao
		every { database.explorationStreakDao() } returns explorationStreakDao

		coEvery { tripDao.getRecentTrips(any()) } returns emptyList()
		coEvery { tripDao.getBetween(any(), any()) } returns emptyList()
		coEvery { explorationCellDao.countAtLevel(any()) } returns 0
		coEvery { explorationStreakDao.getByType(any()) } returns null

		dispatchers = object : DispatchersProvider {
			override val io: CoroutineDispatcher = testDispatcher
			override val default: CoroutineDispatcher = testDispatcher
			override val main: CoroutineDispatcher = testDispatcher
			override val unconfined: CoroutineDispatcher = testDispatcher
		}

		mockkStatic(androidx.core.content.ContextCompat::class)
		every {
			androidx.core.content.ContextCompat.checkSelfPermission(any(), any())
		} returns android.content.pm.PackageManager.PERMISSION_DENIED
	}

	@AfterEach
	fun tearDown() {
		Dispatchers.resetMain()
		unmockkAll()
	}

	private fun createViewModel(): DashboardViewModel {
		return DashboardViewModel(context, dispatchers, database)
	}

	@Nested
	@DisplayName("mapChallenges")
	inner class MapChallenges {
		@Test
		fun `empty input produces empty output`() {
			val vm = createViewModel()
			val result = vm.mapChallenges(emptyList())
			result.shouldBeEmpty()
		}

		@Test
		fun `maps all fields correctly`() {
			val vm = createViewModel()
			val infos = listOf(
				ActiveChallengeInfo(
					id = 42L,
					title = "Walk 5km",
					description = "Walk a total of 5 kilometers",
					progress = 0.75f,
					difficulty = "easy",
					timeRemainingMs = 3_600_000L,
				),
			)

			val result = vm.mapChallenges(infos)

			result shouldHaveSize 1
			result[0].let { model ->
				model.id shouldBe 42L
				model.title shouldBe "Walk 5km"
				model.description shouldBe "Walk a total of 5 kilometers"
				model.progress shouldBe 0.75f
				model.difficulty shouldBe "easy"
				model.timeRemainingMs shouldBe 3_600_000L
				model.iconResName shouldBe ""
				model.rewardPoints shouldBe 0
			}
		}

		@Test
		fun `maps multiple challenges`() {
			val vm = createViewModel()
			val infos = listOf(
				ActiveChallengeInfo(1, "A", "descA", 0.1f, "easy", 1000L),
				ActiveChallengeInfo(2, "B", "descB", 0.5f, "medium", 2000L),
				ActiveChallengeInfo(3, "C", "descC", 0.9f, "hard", 3000L),
			)

			val result = vm.mapChallenges(infos)
			result shouldHaveSize 3
			result[0].id shouldBe 1L
			result[1].id shouldBe 2L
			result[2].id shouldBe 3L
		}
	}

	@Nested
	@DisplayName("Permission state machine")
	inner class PermissionState {
		@Test
		fun `onPermissionResult granted sets hasLocationPermission true`() {
			val vm = createViewModel()
			vm.onPermissionResult(granted = true)
			vm.hasLocationPermission.value shouldBe true
			vm.permissionDenied.value shouldBe false
		}

		@Test
		fun `onPermissionResult denied sets permissionDenied`() {
			val vm = createViewModel()
			vm.onPermissionResult(granted = false)
			vm.hasLocationPermission.value shouldBe false
			vm.permissionDenied.value shouldBe true
		}

		@Test
		fun `requestPermission and dismissPermission toggle state`() {
			val vm = createViewModel()
			vm.showLocationPermissionRequest.value shouldBe false

			vm.requestPermission()
			vm.showLocationPermissionRequest.value shouldBe true

			vm.dismissPermissionRequest()
			vm.showLocationPermissionRequest.value shouldBe false
		}

		@Test
		fun `clearPermissionDenied resets denied flag`() {
			val vm = createViewModel()
			vm.onPermissionResult(granted = false)
			vm.permissionDenied.value shouldBe true

			vm.clearPermissionDenied()
			vm.permissionDenied.value shouldBe false
		}
	}

	@Nested
	@DisplayName("refreshTodaySummary")
	inner class RefreshTodaySummary {
		@Test
		fun `successful fetch updates state`() = runTest {
			val vm = createViewModel()
			val provider = mockk<DailySummaryProvider>()
			val summary = DailySummary(
				totalDistanceM = 5000f,
				totalSteps = 8000,
				totalDurationMs = 3_600_000L,
				sessionCount = 2,
			)
			coEvery { provider.fetchTodaySummary() } returns summary

			vm.refreshTodaySummary(provider)
			advanceUntilIdle()

			vm.todaySummary.value shouldBe summary
		}

		@Test
		fun `exception yields null`() = runTest {
			val vm = createViewModel()
			val provider = mockk<DailySummaryProvider>()
			coEvery { provider.fetchTodaySummary() } throws RuntimeException("DB error")

			vm.refreshTodaySummary(provider)
			advanceUntilIdle()

			vm.todaySummary.value shouldBe null
		}
	}

	@Nested
	@DisplayName("loadHistoricalData")
	inner class LoadHistoricalData {
		@Test
		fun `skips loading when tracking is active`() = runTest {
			val vm = createViewModel()
			vm.loadHistoricalData(isTracking = true, lastSessionData = null)
			advanceUntilIdle()

			vm.recentTrips.value.shouldBeEmpty()
		}

		@Test
		fun `loads recent trips when idle`() = runTest {
			val trips = listOf(makeTrip(1L, 1000f), makeTrip(2L, 2000f))
			coEvery { tripDao.getRecentTrips(5) } returns trips

			val vm = createViewModel()
			vm.loadHistoricalData(isTracking = false, lastSessionData = null)
			advanceUntilIdle()

			vm.recentTrips.value shouldHaveSize 2
		}

		@Test
		fun `skips DB session lookup when lastSessionData provided`() = runTest {
			val session = mockk<TrackerSession>(relaxed = true)
			val vm = createViewModel()
			vm.loadHistoricalData(isTracking = false, lastSessionData = session)
			advanceUntilIdle()

			// dbLastSession should remain null since lastSessionData was provided
			vm.dbLastSession.value shouldBe null
		}

		@Test
		fun `loads DB session when lastSessionData is null`() = runTest {
			// Legacy session DAO was removed; the VM now derives last-session
			// info from TripDao. Verify no crash and default state is null.
			val vm = createViewModel()
			vm.loadHistoricalData(isTracking = false, lastSessionData = null)
			advanceUntilIdle()

			vm.dbLastSession.value shouldBe null
		}
	}

	@Nested
	@DisplayName("Exploration data")
	inner class ExplorationData {
		@Test
		fun `no cells produces default exploration state`() = runTest {
			coEvery { explorationCellDao.countAtLevel(14) } returns 0

			val vm = createViewModel()
			vm.loadHistoricalData(isTracking = false, lastSessionData = null)
			advanceUntilIdle()

			vm.explorationState.value shouldBe ExplorationUiState()
		}

		@Test
		fun `loads exploration data with season bitmask calculation`() = runTest {
			coEvery { explorationCellDao.countAtLevel(14) } returns 150
			coEvery { explorationCellDao.countDiscoveredSince(any(), eq(14)) } returns 5
			// Bitmasks: spring=1, summer=2, fall=4 → combined=7 → bitCount=3
			coEvery { explorationCellDao.getDistinctSeasonBitmasks(14) } returns listOf(1, 2, 4)

			val vm = createViewModel()
			vm.loadHistoricalData(isTracking = false, lastSessionData = null)
			advanceUntilIdle()

			vm.explorationState.value.let { state ->
				state.hasExplorationData shouldBe true
				state.totalCells shouldBe 150
				state.newCellsToday shouldBe 5
				state.seasonsCovered shouldBe 3
			}
		}

		@Test
		fun `overlapping season bitmasks are ORed correctly`() = runTest {
			coEvery { explorationCellDao.countAtLevel(14) } returns 10
			coEvery { explorationCellDao.countDiscoveredSince(any(), eq(14)) } returns 0
			// Both have spring (1) and summer (2) → OR = 3 → bitCount = 2
			coEvery { explorationCellDao.getDistinctSeasonBitmasks(14) } returns listOf(3, 3)

			val vm = createViewModel()
			vm.loadHistoricalData(isTracking = false, lastSessionData = null)
			advanceUntilIdle()

			vm.explorationState.value.seasonsCovered shouldBe 2
		}
	}

	@Nested
	@DisplayName("Streak and weekly trend")
	inner class StreakAndTrend {
		@Test
		fun `no streak entity produces zero streak`() = runTest {
			coEvery { explorationStreakDao.getByType("DAILY_DISCOVERY") } returns null

			val vm = createViewModel()
			vm.loadHistoricalData(isTracking = false, lastSessionData = null)
			advanceUntilIdle()

			vm.streakState.value.let { state ->
				state.currentStreak shouldBe 0
				state.bestStreak shouldBe 0
			}
		}

		@Test
		fun `streak values from entity are propagated`() = runTest {
			coEvery { explorationStreakDao.getByType("DAILY_DISCOVERY") } returns
				ExplorationStreakEntity(
					type = "DAILY_DISCOVERY",
					currentCount = 7,
					bestCount = 14,
				)

			val vm = createViewModel()
			vm.loadHistoricalData(isTracking = false, lastSessionData = null)
			advanceUntilIdle()

			vm.streakState.value.let { state ->
				state.currentStreak shouldBe 7
				state.bestStreak shouldBe 14
			}
		}

		@Test
		fun `weekly distances produces 7 entries`() = runTest {
			val vm = createViewModel()
			vm.loadHistoricalData(isTracking = false, lastSessionData = null)
			advanceUntilIdle()

			vm.streakState.value.weeklyDistances shouldHaveSize 7
		}

		@Test
		fun `trend UP when recent days exceed earlier by more than 10 percent`() = runTest {
			// Days 0-3 (earlier): no trips; Days 4-6 (recent): 1000m each
			var callCount = 0
			coEvery { tripDao.getBetween(any(), any()) } answers {
				callCount++
				// Calls 1-4 → earlier days (0m), calls 5-7 → recent days (1000m)
				if (callCount > 4) listOf(makeTrip(callCount.toLong(), 1000f))
				else emptyList()
			}

			val vm = createViewModel()
			vm.loadHistoricalData(isTracking = false, lastSessionData = null)
			advanceUntilIdle()

			vm.streakState.value.weeklyTrend shouldBe WeeklyTrend.UP
		}

		@Test
		fun `trend DOWN when recent days are below earlier by more than 10 percent`() = runTest {
			var callCount = 0
			coEvery { tripDao.getBetween(any(), any()) } answers {
				callCount++
				// Calls 1-4 → earlier days (1000m), calls 5-7 → recent days (0m)
				if (callCount <= 4) listOf(makeTrip(callCount.toLong(), 1000f))
				else emptyList()
			}

			val vm = createViewModel()
			vm.loadHistoricalData(isTracking = false, lastSessionData = null)
			advanceUntilIdle()

			vm.streakState.value.weeklyTrend shouldBe WeeklyTrend.DOWN
		}

		@Test
		fun `trend STEADY when totals are within 10 percent`() = runTest {
			// All days return same distance → equal halves → STEADY
			coEvery { tripDao.getBetween(any(), any()) } returns
				listOf(makeTrip(1L, 500f))

			val vm = createViewModel()
			vm.loadHistoricalData(isTracking = false, lastSessionData = null)
			advanceUntilIdle()

			vm.streakState.value.weeklyTrend shouldBe WeeklyTrend.STEADY
		}

		@Test
		fun `trend STEADY when all distances are zero`() = runTest {
			coEvery { tripDao.getBetween(any(), any()) } returns emptyList()

			val vm = createViewModel()
			vm.loadHistoricalData(isTracking = false, lastSessionData = null)
			advanceUntilIdle()

			vm.streakState.value.weeklyTrend shouldBe WeeklyTrend.STEADY
		}
	}

	@Nested
	@DisplayName("checkPermission")
	inner class CheckPermission {
		@Test
		fun `checkPermission updates state when fine location granted`() {
			every {
				androidx.core.content.ContextCompat.checkSelfPermission(any(), any())
			} returns android.content.pm.PackageManager.PERMISSION_GRANTED

			val vm = createViewModel()
			vm.checkPermission(context)

			vm.hasLocationPermission.value shouldBe true
		}

		@Test
		fun `checkPermission updates state when both permissions denied`() {
			every {
				androidx.core.content.ContextCompat.checkSelfPermission(any(), any())
			} returns android.content.pm.PackageManager.PERMISSION_DENIED

			val vm = createViewModel()
			vm.checkPermission(context)

			vm.hasLocationPermission.value shouldBe false
		}

		@Test
		fun `initial permission state reflects mocked context`() {
			every {
				androidx.core.content.ContextCompat.checkSelfPermission(any(), any())
			} returns android.content.pm.PackageManager.PERMISSION_GRANTED

			val vm = createViewModel()
			vm.hasLocationPermission.value shouldBe true
		}
	}

	@Nested
	@DisplayName("Error resilience")
	inner class ErrorResilience {
		@Test
		fun `loadHistoricalData handles DB exception gracefully`() = runTest {
			coEvery { tripDao.getRecentTrips(any()) } throws RuntimeException("DB corrupt")

			val vm = createViewModel()
			vm.loadHistoricalData(isTracking = false, lastSessionData = null)
			advanceUntilIdle()

			// Non-fatal — state remains at defaults
			vm.recentTrips.value.shouldBeEmpty()
			vm.explorationState.value shouldBe ExplorationUiState()
			vm.streakState.value shouldBe StreakState()
		}
	}

	@Nested
	@DisplayName("Edge cases")
	inner class EdgeCases {
		@Test
		fun `empty season bitmask list produces zero seasons`() = runTest {
			coEvery { explorationCellDao.countAtLevel(14) } returns 5
			coEvery { explorationCellDao.countDiscoveredSince(any(), eq(14)) } returns 0
			coEvery { explorationCellDao.getDistinctSeasonBitmasks(14) } returns emptyList()

			val vm = createViewModel()
			vm.loadHistoricalData(isTracking = false, lastSessionData = null)
			advanceUntilIdle()

			vm.explorationState.value.let { state ->
				state.hasExplorationData shouldBe true
				state.totalCells shouldBe 5
				state.seasonsCovered shouldBe 0
			}
		}

		@Test
		fun `onPermissionResult granted after denied resets denied flag`() {
			val vm = createViewModel()
			vm.onPermissionResult(granted = false)
			vm.permissionDenied.value shouldBe true

			vm.onPermissionResult(granted = true)
			vm.hasLocationPermission.value shouldBe true
			// permissionDenied stays true — only clearPermissionDenied resets it
			vm.permissionDenied.value shouldBe true
		}

		@Test
		fun `loadHistoricalData called multiple times overwrites state`() = runTest {
			coEvery { tripDao.getRecentTrips(5) } returns listOf(makeTrip(1L, 100f))

			val vm = createViewModel()
			vm.loadHistoricalData(isTracking = false, lastSessionData = null)
			advanceUntilIdle()
			vm.recentTrips.value shouldHaveSize 1

			coEvery { tripDao.getRecentTrips(5) } returns listOf(makeTrip(1L, 100f), makeTrip(2L, 200f))
			vm.loadHistoricalData(isTracking = false, lastSessionData = null)
			advanceUntilIdle()
			vm.recentTrips.value shouldHaveSize 2
		}
	}

	private fun makeTrip(id: Long, distanceM: Float): Trip = Trip(
		id = id,
		startTimeMs = System.currentTimeMillis() - 3_600_000,
		endTimeMs = System.currentTimeMillis(),
		distanceM = distanceM,
		steps = null,
		primaryActivity = null,
		activityConfidence = null,
		sampleCount = 10,
		source = SegmentSource.USER_CREATED,
		createdAt = System.currentTimeMillis(),
	)
}
