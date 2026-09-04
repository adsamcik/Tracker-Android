package com.adsamcik.tracker.game.leaderboard

import com.adsamcik.tracker.shared.base.concurrency.TestDispatchersProvider
import com.adsamcik.tracker.shared.base.database.dao.DailySummaryDao
import com.adsamcik.tracker.shared.base.database.data.DailySummaryEntity
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAdjusters

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("GhostLeaderboardProvider")
class GhostLeaderboardProviderTest {

	private val dao: DailySummaryDao = mockk()
	private val testDispatcher = UnconfinedTestDispatcher()
	private val dispatchers = TestDispatchersProvider(testDispatcher)
	private lateinit var provider: GhostLeaderboardProvider

	private val utc: ZoneId = ZoneOffset.UTC

	@BeforeEach
	fun setup() {
		provider = GhostLeaderboardProvider(dao, dispatchers)
	}

	private fun makeSummary(
		epochDay: Long,
		distanceM: Float = 0f,
		steps: Int = 0,
		durationMs: Long = 0,
		tripCount: Int = 0,
		activeMs: Long = 0,
	) = DailySummaryEntity(
		dateEpochDay = epochDay,
		totalDistanceM = distanceM,
		totalSteps = steps,
		totalDurationMs = durationMs,
		tripCount = tripCount,
		activeTrackingMs = activeMs,
		lastUpdatedMs = System.currentTimeMillis(),
		createdAt = System.currentTimeMillis(),
	)

	/** Convert a LocalDate at start of day UTC to Instant. */
	private fun LocalDate.toInstantAtStartOfDay(): Instant =
		atStartOfDay(utc).toInstant()

	/** Convert a LocalDate at a specific hour UTC to Instant. */
	private fun LocalDate.toInstantAtHour(hour: Int): Instant =
		atTime(hour, 0).toInstant(ZoneOffset.UTC)

	@Nested
	@DisplayName("No data scenarios")
	inner class NoData {

		@Test
		fun `returns valid state with user entry when no data exists`() = runTest {
			coEvery { dao.getBetween(any(), any()) } returns emptyList()
			coEvery { dao.getAllBefore(any()) } returns emptyList()

			val state = provider.getLeaderboard(LeaderboardMetric.DISTANCE, zone = utc)

			state.currentWeekValue shouldBe 0.0
			// Should have exactly 1 competitor: the current user
			state.competitors shouldHaveSize 1
			state.competitors[0].isCurrentUser shouldBe true
			state.competitors[0].value shouldBe 0.0
			state.ghosts shouldHaveSize 0
			state.currentRank shouldBe 1
			state.metric shouldBe LeaderboardMetric.DISTANCE
		}

		@Test
		fun `returns valid state with user entry for all metrics when no data`() = runTest {
			coEvery { dao.getBetween(any(), any()) } returns emptyList()
			coEvery { dao.getAllBefore(any()) } returns emptyList()

			LeaderboardMetric.selectableEntries.forEach { metric ->
				val state = provider.getLeaderboard(metric, zone = utc)
				state.competitors shouldHaveSize 1
				state.competitors[0].isCurrentUser shouldBe true
				state.currentWeekValue shouldBe 0.0
			}
		}
	}

	@Nested
	@DisplayName("Single week data")
	inner class SingleWeek {

		@Test
		fun `current week only produces user-only competitor list`() = runTest {
			val today = LocalDate.of(2024, 6, 12) // Wednesday
			val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
			val now = today.toInstantAtStartOfDay()

			val currentWeekData = listOf(
				makeSummary(monday.toEpochDay(), tripCount = 5000),
				makeSummary(monday.plusDays(1).toEpochDay(), tripCount = 3000),
			)

			coEvery { dao.getBetween(monday.toEpochDay(), monday.plusDays(6).toEpochDay()) } returns currentWeekData
			coEvery { dao.getAllBefore(monday.toEpochDay()) } returns emptyList()

			val state = provider.getLeaderboard(LeaderboardMetric.SESSIONS, now = now, zone = utc)

			state.currentWeekValue shouldBe 8000.0
			state.ghosts shouldHaveSize 0
			// User entry still present
			state.competitors shouldHaveSize 1
			state.competitors[0].isCurrentUser shouldBe true
			state.competitors[0].value shouldBe 8000.0
			state.currentRank shouldBe 1
		}

		@Test
		fun `one completed week produces ghosts and user entry`() = runTest {
			val today = LocalDate.of(2024, 6, 12) // Wednesday in week 24
			val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
			val prevMonday = monday.minusWeeks(1)
			val now = today.toInstantAtStartOfDay()

			val currentWeekData = listOf(
				makeSummary(monday.toEpochDay(), tripCount = 5000),
			)
			val historicalData = listOf(
				makeSummary(prevMonday.toEpochDay(), tripCount = 10000),
				makeSummary(prevMonday.plusDays(1).toEpochDay(), tripCount = 8000),
			)

			coEvery { dao.getBetween(monday.toEpochDay(), monday.plusDays(6).toEpochDay()) } returns currentWeekData
			coEvery { dao.getAllBefore(monday.toEpochDay()) } returns historicalData

			val state = provider.getLeaderboard(LeaderboardMetric.SESSIONS, now = now, zone = utc)

			state.currentWeekValue shouldBe 5000.0
			state.ghosts shouldHaveSize 3 // best, last 4 avg, average
			state.ghosts.any { it.type == GhostType.BEST_WEEK } shouldBe true
			state.ghosts.first { it.type == GhostType.BEST_WEEK }.value shouldBe 18000.0

			// User entry is included in competitors
			val userEntry = state.competitors.first { it.isCurrentUser }
			userEntry.value shouldBe 5000.0

			// Total competitors = ghosts + user
			state.competitors shouldHaveSize 4
		}
	}

	@Nested
	@DisplayName("Multiple weeks - ranking and averages")
	inner class MultipleWeeks {

		private val today = LocalDate.of(2024, 7, 10) // Wednesday
		private val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
		private val now = today.toInstantAtStartOfDay()

		private fun setupMultiWeekData() {
			// Current week: 3000 units
			coEvery {
				dao.getBetween(monday.toEpochDay(), monday.plusDays(6).toEpochDay())
			} returns listOf(
				makeSummary(monday.toEpochDay(), tripCount = 3000),
			)

			// Historical: 4 previous weeks with varying values
			val week1Start = monday.minusWeeks(1)
			val week2Start = monday.minusWeeks(2)
			val week3Start = monday.minusWeeks(3)
			val week4Start = monday.minusWeeks(4)

			val historicalData = listOf(
				// Week -1: 20000 units
				makeSummary(week1Start.toEpochDay(), tripCount = 10000),
				makeSummary(week1Start.plusDays(1).toEpochDay(), tripCount = 10000),
				// Week -2: 15000 units
				makeSummary(week2Start.toEpochDay(), tripCount = 8000),
				makeSummary(week2Start.plusDays(1).toEpochDay(), tripCount = 7000),
				// Week -3: 5000 units
				makeSummary(week3Start.toEpochDay(), tripCount = 5000),
				// Week -4: 10000 units
				makeSummary(week4Start.toEpochDay(), tripCount = 10000),
			)

			coEvery { dao.getAllBefore(monday.toEpochDay()) } returns historicalData
		}

		@Test
		fun `best week is the maximum across all completed weeks`() = runTest {
			setupMultiWeekData()

			val state = provider.getLeaderboard(LeaderboardMetric.SESSIONS, now = now, zone = utc)

			val bestGhost = state.ghosts.first { it.type == GhostType.BEST_WEEK }
			bestGhost.value shouldBe 20000.0
			bestGhost.id shouldBe "best_week"
			bestGhost.nameRes shouldBe GhostType.BEST_WEEK.labelRes
		}

		@Test
		fun `last 4 weeks average is calculated correctly`() = runTest {
			setupMultiWeekData()

			val state = provider.getLeaderboard(LeaderboardMetric.SESSIONS, now = now, zone = utc)

			val avgGhost = state.ghosts.first { it.type == GhostType.LAST_4_WEEKS_AVG }
			// (20000 + 15000 + 5000 + 10000) / 4 = 12500
			avgGhost.value shouldBe 12500.0
			avgGhost.id shouldBe "last_4_weeks_avg"
		}

		@Test
		fun `average week is calculated over all completed weeks`() = runTest {
			setupMultiWeekData()

			val state = provider.getLeaderboard(LeaderboardMetric.SESSIONS, now = now, zone = utc)

			val avgGhost = state.ghosts.first { it.type == GhostType.AVERAGE_WEEK }
			// (20000 + 15000 + 5000 + 10000) / 4 = 12500
			avgGhost.value shouldBe 12500.0
		}

		@Test
		fun `competitors are sorted descending by value`() = runTest {
			setupMultiWeekData()

			val state = provider.getLeaderboard(LeaderboardMetric.SESSIONS, now = now, zone = utc)

			val values = state.competitors.map { it.value }
			values shouldBe values.sortedDescending()
		}

		@Test
		fun `rank is derived from position in sorted competitors list`() = runTest {
			setupMultiWeekData()

			val state = provider.getLeaderboard(LeaderboardMetric.SESSIONS, now = now, zone = utc)

			// Current = 3000, all ghosts are > 3000 so user should be last
			val userIndex = state.competitors.indexOfFirst { it.isCurrentUser }
			state.currentRank shouldBe userIndex + 1
			state.currentRank shouldBeGreaterThanOrEqual 1
			state.currentRank shouldBeLessThanOrEqual state.competitors.size
		}

		@Test
		fun `user entry is always included in competitors list`() = runTest {
			setupMultiWeekData()

			val state = provider.getLeaderboard(LeaderboardMetric.SESSIONS, now = now, zone = utc)

			state.competitors.any { it.isCurrentUser } shouldBe true
			state.competitors.first { it.isCurrentUser }.id shouldBe GhostLeaderboardProvider.CURRENT_USER_ID
		}
	}

	@Nested
	@DisplayName("All metrics work correctly")
	inner class AllMetrics {

		private val today = LocalDate.of(2024, 7, 10)
		private val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
		private val now = today.toInstantAtStartOfDay()

		@Test
		fun `distance metric aggregates in km`() = runTest {
			val prevMonday = monday.minusWeeks(1)
			coEvery {
				dao.getBetween(monday.toEpochDay(), monday.plusDays(6).toEpochDay())
			} returns listOf(
				makeSummary(monday.toEpochDay(), distanceM = 5000f),
			)
			coEvery { dao.getAllBefore(monday.toEpochDay()) } returns listOf(
				makeSummary(prevMonday.toEpochDay(), distanceM = 10000f),
			)

			val state = provider.getLeaderboard(LeaderboardMetric.DISTANCE, now = now, zone = utc)

			state.currentWeekValue shouldBe 5.0 // 5000m = 5km
			state.ghosts.first { it.type == GhostType.BEST_WEEK }.value shouldBe 10.0
		}

		@Test
		fun `active time metric uses totalDurationMs not activeTrackingMs`() = runTest {
			val prevMonday = monday.minusWeeks(1)
			coEvery {
				dao.getBetween(monday.toEpochDay(), monday.plusDays(6).toEpochDay())
			} returns listOf(
				// totalDurationMs = 1h, activeTrackingMs = 2h (different values to verify correct field)
				makeSummary(monday.toEpochDay(), durationMs = 3_600_000L, activeMs = 7_200_000L),
			)
			coEvery { dao.getAllBefore(monday.toEpochDay()) } returns listOf(
				makeSummary(prevMonday.toEpochDay(), durationMs = 7_200_000L, activeMs = 3_600_000L),
			)

			val state = provider.getLeaderboard(LeaderboardMetric.ACTIVE_TIME, now = now, zone = utc)

			// Should use totalDurationMs (1h), NOT activeTrackingMs (2h)
			state.currentWeekValue shouldBe 1.0
			state.ghosts.first { it.type == GhostType.BEST_WEEK }.value shouldBe 2.0
		}

		@Test
		fun `sessions metric aggregates trip count`() = runTest {
			val prevMonday = monday.minusWeeks(1)
			coEvery {
				dao.getBetween(monday.toEpochDay(), monday.plusDays(6).toEpochDay())
			} returns listOf(
				makeSummary(monday.toEpochDay(), tripCount = 2),
				makeSummary(monday.plusDays(1).toEpochDay(), tripCount = 3),
			)
			coEvery { dao.getAllBefore(monday.toEpochDay()) } returns listOf(
				makeSummary(prevMonday.toEpochDay(), tripCount = 5),
				makeSummary(prevMonday.plusDays(1).toEpochDay(), tripCount = 4),
			)

			val state = provider.getLeaderboard(LeaderboardMetric.SESSIONS, now = now, zone = utc)

			state.currentWeekValue shouldBe 5.0
			state.ghosts.first { it.type == GhostType.BEST_WEEK }.value shouldBe 9.0
		}

		@Test
		fun `steps metric is rejected before reading unqualified summaries`() = runTest {
			shouldThrow<IllegalArgumentException> {
				provider.getLeaderboard(LeaderboardMetric.STEPS, now = now, zone = utc)
			}

			coVerify(exactly = 0) { dao.getBetween(any(), any()) }
			coVerify(exactly = 0) { dao.getAllBefore(any()) }
		}
	}

	@Nested
	@DisplayName("Same week last year")
	inner class SameWeekLastYear {

		@Test
		fun `same week last year ghost is included when data exists`() = runTest {
			val today = LocalDate.of(2024, 7, 10)
			val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
			val now = today.toInstantAtStartOfDay()
			val currentIsoYear = today.get(IsoFields.WEEK_BASED_YEAR)
			val currentIsoWeek = today.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)

			// Find a date in the same ISO week last year
			val lastYearDate = LocalDate.now()
				.with(IsoFields.WEEK_BASED_YEAR, (currentIsoYear - 1).toLong())
				.with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, currentIsoWeek.toLong())
				.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

			coEvery {
				dao.getBetween(monday.toEpochDay(), monday.plusDays(6).toEpochDay())
			} returns listOf(
				makeSummary(monday.toEpochDay(), tripCount = 5000),
			)
			coEvery { dao.getAllBefore(monday.toEpochDay()) } returns listOf(
				makeSummary(lastYearDate.toEpochDay(), tripCount = 7000),
				makeSummary(lastYearDate.plusDays(1).toEpochDay(), tripCount = 3000),
			)

			val state = provider.getLeaderboard(LeaderboardMetric.SESSIONS, now = now, zone = utc)

			val sameWeekGhost = state.ghosts.find { it.type == GhostType.SAME_WEEK_LAST_YEAR }
			sameWeekGhost shouldNotBe null
			sameWeekGhost!!.value shouldBe 10000.0
			sameWeekGhost.id shouldBe "same_week_last_year"
		}

		@Test
		fun `same week last year ghost is omitted when no data`() = runTest {
			val today = LocalDate.of(2024, 7, 10)
			val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
			val prevMonday = monday.minusWeeks(1)
			val now = today.toInstantAtStartOfDay()

			coEvery {
				dao.getBetween(monday.toEpochDay(), monday.plusDays(6).toEpochDay())
			} returns emptyList()
			coEvery { dao.getAllBefore(monday.toEpochDay()) } returns listOf(
				makeSummary(prevMonday.toEpochDay(), tripCount = 5000),
			)

			val state = provider.getLeaderboard(LeaderboardMetric.SESSIONS, now = now, zone = utc)

			state.ghosts.none { it.type == GhostType.SAME_WEEK_LAST_YEAR } shouldBe true
		}
	}

	@Nested
	@DisplayName("Week progress - timezone-aware")
	inner class WeekProgress {

		@Test
		fun `monday 00 00 gives 0 progress`() = runTest {
			coEvery { dao.getBetween(any(), any()) } returns emptyList()
			coEvery { dao.getAllBefore(any()) } returns emptyList()

			val monday = LocalDate.of(2024, 7, 8) // Monday
			val now = monday.atStartOfDay(utc).toInstant()
			val state = provider.getLeaderboard(LeaderboardMetric.DISTANCE, now = now, zone = utc)

			state.weekProgressFraction shouldBe 0f
		}

		@Test
		fun `sunday end-of-day approaches 1`() = runTest {
			coEvery { dao.getBetween(any(), any()) } returns emptyList()
			coEvery { dao.getAllBefore(any()) } returns emptyList()

			val sunday = LocalDate.of(2024, 7, 14) // Sunday
			val now = sunday.atTime(23, 59).toInstant(ZoneOffset.UTC)
			val state = provider.getLeaderboard(LeaderboardMetric.DISTANCE, now = now, zone = utc)

			state.weekProgressFraction shouldBeGreaterThan 0.99f
			state.weekProgressFraction shouldBeLessThan 1.0f
		}

		@Test
		fun `wednesday noon gives roughly 0 point 375`() = runTest {
			coEvery { dao.getBetween(any(), any()) } returns emptyList()
			coEvery { dao.getAllBefore(any()) } returns emptyList()

			val wednesday = LocalDate.of(2024, 7, 10) // Wednesday
			// Wednesday noon = 2.5 days into week; 2.5/7 ≈ 0.357
			val now = wednesday.atTime(12, 0).toInstant(ZoneOffset.UTC)
			val state = provider.getLeaderboard(LeaderboardMetric.DISTANCE, now = now, zone = utc)

			// 2.5 days / 7 days ≈ 0.357
			state.weekProgressFraction shouldBeGreaterThan 0.3f
			state.weekProgressFraction shouldBeLessThan 0.4f
		}

		@Test
		fun `friday at start gives 4 over 7 progress`() = runTest {
			coEvery { dao.getBetween(any(), any()) } returns emptyList()
			coEvery { dao.getAllBefore(any()) } returns emptyList()

			val friday = LocalDate.of(2024, 7, 12) // Friday
			val now = friday.atStartOfDay(utc).toInstant()
			val state = provider.getLeaderboard(LeaderboardMetric.DISTANCE, now = now, zone = utc)

			// 4 days / 7 ≈ 0.571
			val expected = 4f / 7f
			val tolerance = 0.01f
			state.weekProgressFraction shouldBeGreaterThan (expected - tolerance)
			state.weekProgressFraction shouldBeLessThan (expected + tolerance)
		}
	}

	@Nested
	@DisplayName("LeaderboardState computed properties")
	inner class StateProperties {

		@Test
		fun `progressAgainstTop is 0 when no ghosts`() {
			val state = LeaderboardState(
				metric = LeaderboardMetric.DISTANCE,
				currentWeekValue = 5000.0,
				competitors = listOf(
					GhostCompetitor(
						id = "current_user",
						type = GhostType.CURRENT_USER,
						nameRes = 0,
						value = 5000.0,
					),
				),
				currentRank = 1,
				weekProgressFraction = 0.5f,
			)

			state.progressAgainstTop shouldBe 0f
			state.topGhostValue shouldBe 0.0
			state.ghosts shouldHaveSize 0
		}

		@Test
		fun `progressAgainstTop caps at 1 when exceeding top ghost`() {
			val state = LeaderboardState(
				metric = LeaderboardMetric.DISTANCE,
				currentWeekValue = 20000.0,
				competitors = listOf(
					GhostCompetitor(
						id = "current_user",
						type = GhostType.CURRENT_USER,
						nameRes = 0,
						value = 20000.0,
					),
					GhostCompetitor(
						id = "best_week",
						type = GhostType.BEST_WEEK,
						nameRes = 0,
						value = 10000.0,
					),
				),
				currentRank = 1,
				weekProgressFraction = 0.5f,
			)

			state.progressAgainstTop shouldBe 1f
		}

		@Test
		fun `progressAgainstTop computes fraction correctly`() {
			val state = LeaderboardState(
				metric = LeaderboardMetric.DISTANCE,
				currentWeekValue = 5.0,
				competitors = listOf(
					GhostCompetitor(
						id = "best_week",
						type = GhostType.BEST_WEEK,
						nameRes = 0,
						value = 20.0,
					),
					GhostCompetitor(
						id = "current_user",
						type = GhostType.CURRENT_USER,
						nameRes = 0,
						value = 5.0,
					),
				),
				currentRank = 2,
				weekProgressFraction = 0.5f,
			)

			state.progressAgainstTop shouldBe 0.25f
		}

		@Test
		fun `ghosts property filters out current user`() {
			val state = LeaderboardState(
				metric = LeaderboardMetric.DISTANCE,
				currentWeekValue = 5000.0,
				competitors = listOf(
					GhostCompetitor(id = "best_week", type = GhostType.BEST_WEEK, nameRes = 0, value = 20000.0),
					GhostCompetitor(id = "avg_week", type = GhostType.AVERAGE_WEEK, nameRes = 0, value = 10000.0),
					GhostCompetitor(id = "current_user", type = GhostType.CURRENT_USER, nameRes = 0, value = 5000.0),
				),
				currentRank = 3,
				weekProgressFraction = 0.5f,
			)

			state.ghosts shouldHaveSize 2
			state.ghosts.none { it.isCurrentUser } shouldBe true
		}
	}

	@Nested
	@DisplayName("GhostCompetitor identity")
	inner class CompetitorIdentity {

		@Test
		fun `ghost competitors have stable ids`() = runTest {
			val today = LocalDate.of(2024, 7, 10)
			val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
			val prevMonday = monday.minusWeeks(1)
			val now = today.toInstantAtStartOfDay()

			coEvery {
				dao.getBetween(monday.toEpochDay(), monday.plusDays(6).toEpochDay())
			} returns listOf(makeSummary(monday.toEpochDay(), tripCount = 1000))
			coEvery { dao.getAllBefore(monday.toEpochDay()) } returns listOf(
				makeSummary(prevMonday.toEpochDay(), tripCount = 5000),
			)

			val state = provider.getLeaderboard(LeaderboardMetric.SESSIONS, now = now, zone = utc)

			state.competitors.first { it.type == GhostType.BEST_WEEK }.id shouldBe "best_week"
			state.competitors.first { it.type == GhostType.AVERAGE_WEEK }.id shouldBe "average_week"
			state.competitors.first { it.isCurrentUser }.id shouldBe "current_user"
		}

		@Test
		fun `ghost competitors carry nameRes matching their type`() = runTest {
			val today = LocalDate.of(2024, 7, 10)
			val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
			val prevMonday = monday.minusWeeks(1)
			val now = today.toInstantAtStartOfDay()

			coEvery {
				dao.getBetween(monday.toEpochDay(), monday.plusDays(6).toEpochDay())
			} returns listOf(makeSummary(monday.toEpochDay(), tripCount = 1000))
			coEvery { dao.getAllBefore(monday.toEpochDay()) } returns listOf(
				makeSummary(prevMonday.toEpochDay(), tripCount = 5000),
			)

			val state = provider.getLeaderboard(LeaderboardMetric.SESSIONS, now = now, zone = utc)

			state.ghosts.forEach { ghost ->
				ghost.nameRes shouldBe ghost.type.labelRes
			}
		}

		@Test
		fun `best week ghost carries period label`() = runTest {
			val today = LocalDate.of(2024, 7, 10)
			val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
			val prevMonday = monday.minusWeeks(1)
			val now = today.toInstantAtStartOfDay()

			coEvery {
				dao.getBetween(monday.toEpochDay(), monday.plusDays(6).toEpochDay())
			} returns listOf(makeSummary(monday.toEpochDay(), tripCount = 1000))
			coEvery { dao.getAllBefore(monday.toEpochDay()) } returns listOf(
				makeSummary(prevMonday.toEpochDay(), tripCount = 5000),
			)

			val state = provider.getLeaderboard(LeaderboardMetric.SESSIONS, now = now, zone = utc)

			val bestGhost = state.ghosts.first { it.type == GhostType.BEST_WEEK }
			bestGhost.period shouldNotBe null
		}
	}

	@Nested
	@DisplayName("Internal helper methods")
	inner class Helpers {

		@Test
		fun `findLastNWeeks returns correct week keys`() {
			// ISO week 28, 2024
			val result = provider.findLastNWeeks(2024, 28, 4)
			result shouldHaveSize 4
			// Should be weeks 27, 26, 25, 24
			result[0] shouldBe (2024 to 27)
			result[1] shouldBe (2024 to 26)
			result[2] shouldBe (2024 to 25)
			result[3] shouldBe (2024 to 24)
		}

		@Test
		fun `findLastNWeeks handles year boundary`() {
			// ISO week 2, 2024
			val result = provider.findLastNWeeks(2024, 2, 3)
			result shouldHaveSize 3
			result[0] shouldBe (2024 to 1)
			// Weeks 52/53 of 2023
			result[1].first shouldBe 2023
			result[2].first shouldBe 2023
		}

		@Test
		fun `findSameWeekLastYear returns previous year same week`() {
			provider.findSameWeekLastYear(2024, 28) shouldBe (2023 to 28)
		}

		@Test
		fun `aggregateMetric rejects raw steps`() {
			val days = listOf(
				makeSummary(0, steps = 100),
				makeSummary(1, steps = 200),
			)
			shouldThrow<IllegalStateException> {
				provider.aggregateMetric(days, LeaderboardMetric.STEPS)
			}
		}

		@Test
		fun `aggregateMetric converts distance to km`() {
			val days = listOf(
				makeSummary(0, distanceM = 1500f),
				makeSummary(1, distanceM = 2500f),
			)
			provider.aggregateMetric(days, LeaderboardMetric.DISTANCE) shouldBe 4.0
		}

		@Test
		fun `aggregateMetric uses totalDurationMs for active time`() {
			val days = listOf(
				makeSummary(0, durationMs = 1_800_000L, activeMs = 999_999L),
				makeSummary(1, durationMs = 1_800_000L, activeMs = 999_999L),
			)
			// Should use totalDurationMs (2x 0.5h = 1.0h), NOT activeTrackingMs
			provider.aggregateMetric(days, LeaderboardMetric.ACTIVE_TIME) shouldBe 1.0
		}

		@Test
		fun `aggregateMetric sums sessions`() {
			val days = listOf(
				makeSummary(0, tripCount = 3),
				makeSummary(1, tripCount = 2),
			)
			provider.aggregateMetric(days, LeaderboardMetric.SESSIONS) shouldBe 5.0
		}

		@Test
		fun `groupByIsoWeek groups correctly`() {
			val monday1 = LocalDate.of(2024, 7, 1) // Week 27
			val monday2 = LocalDate.of(2024, 7, 8) // Week 28
			val days = listOf(
				makeSummary(monday1.toEpochDay(), tripCount = 100),
				makeSummary(monday1.plusDays(1).toEpochDay(), tripCount = 200),
				makeSummary(monday2.toEpochDay(), tripCount = 300),
			)

			val result = provider.groupByIsoWeek(days, LeaderboardMetric.SESSIONS)
			result.size shouldBe 2
			result[2024 to 27] shouldBe 300.0
			result[2024 to 28] shouldBe 300.0
		}

		@Test
		fun `buildGhosts returns empty for empty weekly totals`() {
			val result = provider.buildGhosts(
				emptyMap(),
				LocalDate.of(2024, 7, 10),
				LeaderboardMetric.DISTANCE,
			)
			result shouldHaveSize 0
		}
	}
}
