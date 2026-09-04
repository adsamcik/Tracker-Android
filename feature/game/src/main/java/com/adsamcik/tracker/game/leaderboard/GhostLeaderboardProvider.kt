package com.adsamcik.tracker.game.leaderboard

import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.database.dao.DailySummaryDao
import com.adsamcik.tracker.shared.base.database.data.DailySummaryEntity
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Feature-facing port for locally generated leaderboard presentation.
 */
interface LeaderboardProvider {
	suspend fun getLeaderboard(
		metric: LeaderboardMetric,
		now: Instant = Instant.now(),
		zone: ZoneId = ZoneId.systemDefault(),
	): LeaderboardState
}

/**
 * Provides ghost leaderboard data by aggregating DailySummary rows into ISO weeks.
 *
 * All computation is local-only. No network calls, no new tables.
 * Uses ISO-8601 weeks (Monday start).
 */
@Singleton
class GhostLeaderboardProvider @Inject constructor(
	private val dailySummaryDao: DailySummaryDao,
	private val dispatchers: DispatchersProvider,
) : LeaderboardProvider {
	/**
	 * Compute the [LeaderboardState] for [metric] at the given [now] instant.
	 *
	 * @param metric Which metric to compare
	 * @param now The reference instant (default: current device time)
	 * @param zone The timezone used for week boundary calculations
	 */
	override suspend fun getLeaderboard(
		metric: LeaderboardMetric,
		now: Instant,
		zone: ZoneId,
	): LeaderboardState {
		require(metric.isSelectable) {
			"Steps leaderboard requires source-qualified historical composition"
		}
		return withContext(dispatchers.io) {
			val zonedNow = now.atZone(zone)
			val today = zonedNow.toLocalDate()
			val currentWeekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
			val currentWeekEnd = currentWeekStart.plusDays(6) // Sunday

			// Current week value
			val currentWeekDays = dailySummaryDao.getBetween(
				currentWeekStart.toEpochDay(),
				currentWeekEnd.toEpochDay(),
			)
			val currentWeekValue = aggregateMetric(currentWeekDays, metric)

			// Fetch all historical data before this week
			val allHistorical = if (currentWeekStart.toEpochDay() > 0) {
				dailySummaryDao.getAllBefore(currentWeekStart.toEpochDay())
			} else {
				emptyList()
			}

			// Group historical data by ISO week
			val weeklyTotals = groupByIsoWeek(allHistorical, metric)

			// Build ghost competitors
			val ghosts = buildGhosts(weeklyTotals, today, metric)

			// Build current user entry
			val currentUserEntry = GhostCompetitor(
				id = CURRENT_USER_ID,
				type = GhostType.CURRENT_USER,
				nameRes = R.string.leaderboard_you,
				value = currentWeekValue,
				period = zone.id,
			)

			// Merge and sort all competitors descending by value
			val allCompetitors = (ghosts + currentUserEntry).sortedByDescending { it.value }

			// Rank: 1-based position of current user in the sorted list
			val currentRank = allCompetitors.indexOfFirst { it.isCurrentUser } + 1

			// Week progress: timezone-aware, 0.0 at Monday 00:00, approaching 1.0 at end of Sunday
			val weekStartInstant = currentWeekStart.atStartOfDay(zone).toInstant()
			val weekEndInstant = currentWeekStart.plusDays(7).atStartOfDay(zone).toInstant()
			val totalMs = Duration.between(weekStartInstant, weekEndInstant).toMillis()
			val elapsedMs = Duration.between(weekStartInstant, now).toMillis()
			val weekProgressFraction = if (totalMs > 0) {
				(elapsedMs.toDouble() / totalMs.toDouble()).coerceIn(0.0, 1.0).toFloat()
			} else {
				0f
			}

			LeaderboardState(
				metric = metric,
				currentWeekValue = currentWeekValue,
				competitors = allCompetitors,
				currentRank = currentRank.coerceAtLeast(1),
				weekProgressFraction = weekProgressFraction,
			)
		}
	}

	internal fun aggregateMetric(
		days: List<DailySummaryEntity>,
		metric: LeaderboardMetric,
	): Double = when (metric) {
		LeaderboardMetric.DISTANCE -> days.sumOf { it.totalDistanceM.toDouble() } / 1000.0
		LeaderboardMetric.STEPS -> error(
			"Steps leaderboard requires source-qualified historical composition",
		)
		LeaderboardMetric.ACTIVE_TIME -> days.sumOf { it.totalDurationMs.toDouble() } / 3_600_000.0
		LeaderboardMetric.SESSIONS -> days.sumOf { it.tripCount.toDouble() }
	}

	/**
	 * Groups daily summaries into ISO week buckets and sums the metric per week.
	 * Returns a map of (year, isoWeek) → total metric value.
	 */
	internal fun groupByIsoWeek(
		days: List<DailySummaryEntity>,
		metric: LeaderboardMetric,
	): Map<Pair<Int, Int>, Double> {
		return days.groupBy { entity ->
			val date = LocalDate.ofEpochDay(entity.dateEpochDay)
			val isoYear = date.get(IsoFields.WEEK_BASED_YEAR)
			val isoWeek = date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
			isoYear to isoWeek
		}.mapValues { (_, entities) -> aggregateMetric(entities, metric) }
	}

	internal fun buildGhosts(
		weeklyTotals: Map<Pair<Int, Int>, Double>,
		now: LocalDate,
		metric: LeaderboardMetric,
	): List<GhostCompetitor> {
		if (weeklyTotals.isEmpty()) return emptyList()

		val ghosts = mutableListOf<GhostCompetitor>()

		// Best week
		val bestEntry = weeklyTotals.maxByOrNull { it.value }
		if (bestEntry != null && bestEntry.value > 0.0) {
			ghosts.add(
				GhostCompetitor(
					id = "best_week",
					type = GhostType.BEST_WEEK,
					nameRes = GhostType.BEST_WEEK.labelRes,
					value = bestEntry.value,
					period = "W${bestEntry.key.second} ${bestEntry.key.first}",
				),
			)
		}

		// Last 4 completed weeks average
		val currentIsoYear = now.get(IsoFields.WEEK_BASED_YEAR)
		val currentIsoWeek = now.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
		val last4Weeks = findLastNWeeks(currentIsoYear, currentIsoWeek, 4)
			.mapNotNull { weeklyTotals[it] }
		if (last4Weeks.isNotEmpty()) {
			ghosts.add(
				GhostCompetitor(
					id = "last_4_weeks_avg",
					type = GhostType.LAST_4_WEEKS_AVG,
					nameRes = GhostType.LAST_4_WEEKS_AVG.labelRes,
					value = last4Weeks.average(),
				),
			)
		}

		// Same week last year
		val lastYearKey = findSameWeekLastYear(currentIsoYear, currentIsoWeek)
		val sameWeekLastYearValue = weeklyTotals[lastYearKey]
		if (sameWeekLastYearValue != null && sameWeekLastYearValue > 0.0) {
			ghosts.add(
				GhostCompetitor(
					id = "same_week_last_year",
					type = GhostType.SAME_WEEK_LAST_YEAR,
					nameRes = GhostType.SAME_WEEK_LAST_YEAR.labelRes,
					value = sameWeekLastYearValue,
					period = "W${lastYearKey.second} ${lastYearKey.first}",
				),
			)
		}

		// Average week (across all completed weeks)
		if (weeklyTotals.isNotEmpty()) {
			ghosts.add(
				GhostCompetitor(
					id = "average_week",
					type = GhostType.AVERAGE_WEEK,
					nameRes = GhostType.AVERAGE_WEEK.labelRes,
					value = weeklyTotals.values.average(),
				),
			)
		}

		return ghosts
	}

	/**
	 * Returns the ISO week keys for the [count] weeks immediately before
	 * the week identified by [isoYear]/[isoWeek].
	 */
	internal fun findLastNWeeks(
		isoYear: Int,
		isoWeek: Int,
		count: Int,
	): List<Pair<Int, Int>> {
		val result = mutableListOf<Pair<Int, Int>>()
		var date = LocalDate.now()
			.with(IsoFields.WEEK_BASED_YEAR, isoYear.toLong())
			.with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, isoWeek.toLong())
			.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

		repeat(count) {
			date = date.minusWeeks(1)
			val y = date.get(IsoFields.WEEK_BASED_YEAR)
			val w = date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
			result.add(y to w)
		}
		return result
	}

	internal fun findSameWeekLastYear(
		isoYear: Int,
		isoWeek: Int,
	): Pair<Int, Int> = (isoYear - 1) to isoWeek

	companion object {
		const val CURRENT_USER_ID = "current_user"
	}
}
