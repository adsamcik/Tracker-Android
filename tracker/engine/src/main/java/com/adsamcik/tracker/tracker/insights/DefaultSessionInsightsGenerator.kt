package com.adsamcik.tracker.tracker.insights

import android.content.Context
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.database.dao.DailySummaryDao
import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.data.session.TrackerSessionSnapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Provider
import kotlin.math.max
import kotlin.math.roundToInt

class DefaultSessionInsightsGenerator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dailySummaryDaoProvider: Provider<DailySummaryDao>,
    private val dispatchers: DispatchersProvider,
) : SessionInsightsGenerator {

    override suspend fun generate(
        session: TrackerSessionSnapshot,
    ): List<SessionInsight> = withContext(dispatchers.default) {
        val insights = mutableListOf<SessionInsight>()
        insights.addAchievementInsight(session)
        insights.addFunFactInsight(session)
        insights.addComparisonInsight(session)
        insights.take(MAX_INSIGHTS)
    }

    private suspend fun MutableList<SessionInsight>.addAchievementInsight(
        session: TrackerSessionSnapshot,
    ) {
        // TrackerSessionSnapshot.steps is a legacy live projection without retained source proof.
        // Keep independent distance insights, but never turn that projection into a completed
        // post-session Steps achievement.
        if (session.distanceInM >= LONG_DISTANCE_THRESHOLD_M) {
            add(
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

    private fun MutableList<SessionInsight>.addFunFactInsight(session: TrackerSessionSnapshot) {
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

    private suspend fun MutableList<SessionInsight>.addComparisonInsight(
        session: TrackerSessionSnapshot,
    ) {
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
        private const val LOOKBACK_DAYS = 7L
        private const val MAX_INSIGHTS = 4
        private const val LONG_DISTANCE_THRESHOLD_M = 4_000f
        private const val ABOVE_AVERAGE_MULTIPLIER = 1.2
    }
}
