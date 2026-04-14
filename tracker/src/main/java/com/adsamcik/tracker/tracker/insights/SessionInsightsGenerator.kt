package com.adsamcik.tracker.tracker.insights

import android.content.Context
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.database.dao.DailySummaryDao
import com.adsamcik.tracker.shared.base.database.dao.ExplorationCellDao
import com.adsamcik.tracker.tracker.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Provider
import kotlin.math.max
import kotlin.math.roundToInt

class SessionInsightsGenerator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dailySummaryDaoProvider: Provider<DailySummaryDao>,
    private val explorationCellDaoProvider: Provider<ExplorationCellDao>,
    private val dispatchers: DispatchersProvider,
) {

    suspend fun generate(session: TrackerSession): List<SessionInsight> = withContext(dispatchers.default) {
        val insights = mutableListOf<SessionInsight>()
        insights.addAchievementInsight(session)
        insights.addFunFactInsight(session)
        insights.addExplorationInsight(session)
        insights.addComparisonInsight(session)
        insights.take(MAX_INSIGHTS)
    }

    private suspend fun MutableList<SessionInsight>.addAchievementInsight(session: TrackerSession) {
        when {
            session.steps >= BIG_STEP_SESSION_THRESHOLD -> add(
                SessionInsight(
                    iconRes = com.adsamcik.tracker.shared.base.R.drawable.ic_shoe_print,
                    title = context.getString(R.string.insight_steps_title),
                    description = context.getString(R.string.insight_steps_desc, session.steps.toString()),
                    category = InsightCategory.ACHIEVEMENT,
                ),
            )
            session.distanceInM >= LONG_DISTANCE_THRESHOLD_M -> add(
                SessionInsight(
                    iconRes = com.adsamcik.tracker.shared.base.R.drawable.ic_ruler,
                    title = context.getString(R.string.insight_distance_title),
                    description = context.getString(
                        R.string.insight_distance_desc,
                        context.getString(R.string.insight_distance_value_km, session.distanceInM / 1_000f),
                    ),
                    category = InsightCategory.ACHIEVEMENT,
                ),
            )
        }
    }

    private fun MutableList<SessionInsight>.addFunFactInsight(session: TrackerSession) {
        val durationMinutes = ((session.end - session.start).coerceAtLeast(0L) / 60_000L).toInt()
        if (durationMinutes <= 0) return
        add(
            SessionInsight(
                iconRes = com.adsamcik.tracker.shared.base.R.drawable.ic_outline_access_time_24px,
                title = context.getString(R.string.insight_duration_title),
                description = context.resources.getQuantityString(R.plurals.insight_duration_desc, durationMinutes, durationMinutes),
                category = InsightCategory.FUN_FACT,
            ),
        )
    }

    private suspend fun MutableList<SessionInsight>.addExplorationInsight(session: TrackerSession) {
        val newCells = withContext(dispatchers.io) {
            explorationCellDaoProvider.get().countDiscoveredSince(session.start, EXPLORATION_LEVEL)
        }
        if (newCells <= 0) return
        add(
            SessionInsight(
                iconRes = com.adsamcik.tracker.shared.base.R.drawable.ic_baseline_commute,
                title = context.getString(R.string.insight_exploration_title),
                description = context.resources.getQuantityString(R.plurals.insight_exploration_desc, newCells, newCells),
                category = InsightCategory.EXPLORATION,
            ),
        )
    }

    private suspend fun MutableList<SessionInsight>.addComparisonInsight(session: TrackerSession) {
        val sessionDay = Instant.ofEpochMilli(session.end)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .toEpochDay()
        val fromDay = max(0L, sessionDay - LOOKBACK_DAYS)
        val dailySummaries = withContext(dispatchers.io) {
            dailySummaryDaoProvider.get().getBetween(fromDay, sessionDay - 1)
        }
        val totalTrips = dailySummaries.sumOf { it.tripCount }
        if (totalTrips <= 0) return
        val averageDistanceM = dailySummaries.sumOf { it.totalDistanceM.toDouble() } / totalTrips
        if (averageDistanceM <= 0.0 || session.distanceInM < averageDistanceM * ABOVE_AVERAGE_MULTIPLIER) return

        val percentLonger = ((session.distanceInM / averageDistanceM) - 1.0) * 100.0
        add(
            SessionInsight(
                iconRes = com.adsamcik.tracker.shared.base.R.drawable.ic_ruler,
                title = context.getString(R.string.insight_comparison_title),
                description = context.getString(
                    R.string.insight_comparison_desc,
                    percentLonger.roundToInt(),
                ),
                category = InsightCategory.COMPARISON,
            ),
        )
    }

    private companion object {
        private const val EXPLORATION_LEVEL = 14
        private const val LOOKBACK_DAYS = 7L
        private const val MAX_INSIGHTS = 4
        private const val BIG_STEP_SESSION_THRESHOLD = 4_000
        private const val LONG_DISTANCE_THRESHOLD_M = 4_000f
        private const val ABOVE_AVERAGE_MULTIPLIER = 1.2
    }
}
