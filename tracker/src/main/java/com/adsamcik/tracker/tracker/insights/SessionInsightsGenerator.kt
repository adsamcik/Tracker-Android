package com.adsamcik.tracker.tracker.insights

import android.content.Context
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.di.DailySummary
import com.adsamcik.tracker.shared.base.di.GoalProgress
import com.adsamcik.tracker.shared.base.extension.formatReadable
import com.adsamcik.tracker.tracker.R

/**
 * Lightweight generator that produces [SessionInsight] items from
 * already-available post-session data. Avoids expensive raw-location queries.
 */
object SessionInsightsGenerator {

    private const val QUICK_SESSION_MIN = 5L
    private const val SOLID_SESSION_MIN = 20L
    private const val EXTENDED_SESSION_MIN = 60L

    /**
     * Generate insights from the completed session and optional daily/goal context.
     *
     * @param context Android context for string resources
     * @param session The session that just ended
     * @param dailySummary Today's aggregate (null if unavailable)
     * @param goalProgress Current goal state (null if gamification disabled)
     */
    fun generate(
        context: Context,
        session: TrackerSession,
        dailySummary: DailySummary?,
        goalProgress: GoalProgress?,
    ): List<SessionInsight> = buildList {
        addDurationInsight(context, session)
        addDistanceInsight(context, session)
        addStepsInsight(context, session)
        addMultiSessionInsight(context, dailySummary)
        addGoalInsight(context, goalProgress)
    }

    private fun MutableList<SessionInsight>.addDurationInsight(
        context: Context,
        session: TrackerSession,
    ) {
        val durationMin = (session.end - session.start) / 60_000L
        val (titleRes, descRes) = when {
            durationMin >= EXTENDED_SESSION_MIN -> R.string.insight_extended_session_title to R.string.insight_extended_session_desc
            durationMin >= SOLID_SESSION_MIN -> R.string.insight_solid_session_title to R.string.insight_solid_session_desc
            durationMin >= QUICK_SESSION_MIN -> R.string.insight_quick_session_title to R.string.insight_quick_session_desc
            else -> return // Too short for insight
        }
        add(
            SessionInsight(
                category = InsightCategory.DURATION,
                title = context.getString(titleRes),
                description = context.getString(descRes, durationMin.toInt()),
                iconRes = com.adsamcik.tracker.shared.base.R.drawable.ic_outline_access_time_24px,
            ),
        )
    }

    private fun MutableList<SessionInsight>.addDistanceInsight(
        context: Context,
        session: TrackerSession,
    ) {
        val distM = session.distanceInM
        if (distM <= 0f) return
        val formatted = when {
            distM >= 1_000f -> String.format("%.1f km", distM / 1_000f)
            else -> String.format("%.0f m", distM)
        }
        add(
            SessionInsight(
                category = InsightCategory.DISTANCE,
                title = context.getString(R.string.insight_distance_title),
                description = context.getString(R.string.insight_distance_desc, formatted),
                iconRes = com.adsamcik.tracker.shared.base.R.drawable.ic_ruler,
            ),
        )
    }

    private fun MutableList<SessionInsight>.addStepsInsight(
        context: Context,
        session: TrackerSession,
    ) {
        if (session.steps <= 0) return
        add(
            SessionInsight(
                category = InsightCategory.STEPS,
                title = context.getString(R.string.insight_steps_title),
                description = context.getString(
                    R.string.insight_steps_desc,
                    session.steps.formatReadable(),
                ),
                iconRes = com.adsamcik.tracker.shared.base.R.drawable.ic_shoe_print,
            ),
        )
    }

    private fun MutableList<SessionInsight>.addMultiSessionInsight(
        context: Context,
        dailySummary: DailySummary?,
    ) {
        val count = dailySummary?.sessionCount ?: return
        if (count < 2) return
        add(
            SessionInsight(
                category = InsightCategory.ACTIVITY,
                title = context.getString(R.string.insight_multi_session_title),
                description = context.getString(R.string.insight_multi_session_desc, count),
                iconRes = com.adsamcik.tracker.shared.base.R.drawable.ic_baseline_commute,
            ),
        )
    }

    private fun MutableList<SessionInsight>.addGoalInsight(
        context: Context,
        goalProgress: GoalProgress?,
    ) {
        if (goalProgress == null || !goalProgress.gamificationEnabled) return
        if (goalProgress.goalSteps <= 0) return

        val pct = (goalProgress.progress * 100).toInt()
        when {
            pct >= 100 -> add(
                SessionInsight(
                    category = InsightCategory.GOAL,
                    title = context.getString(R.string.insight_goal_reached_title),
                    description = context.getString(R.string.insight_goal_reached_desc),
                    iconRes = com.adsamcik.tracker.shared.base.R.drawable.ic_outline_games_24dp,
                ),
            )
            pct >= 50 -> add(
                SessionInsight(
                    category = InsightCategory.GOAL,
                    title = context.getString(R.string.insight_goal_close_title),
                    description = context.getString(R.string.insight_goal_close_desc, pct),
                    iconRes = com.adsamcik.tracker.shared.base.R.drawable.ic_outline_games_24dp,
                ),
            )
        }
    }
}
