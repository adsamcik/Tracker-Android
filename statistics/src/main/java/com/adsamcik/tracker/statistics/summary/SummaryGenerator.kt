package com.adsamcik.tracker.statistics.summary

import android.content.Context
import androidx.annotation.WorkerThread
import com.adsamcik.tracker.statistics.data.Stat
import com.adsamcik.tracker.statistics.repository.SessionStatsAdapter

/**
 * Legacy summary API kept for compatibility while call sites migrate to SessionStatsAdapter.
 */
@Deprecated(
	message = "Use SessionStatsAdapter via SessionRepository or presenter view models.",
	replaceWith = ReplaceWith(
		"SessionStatsAdapter.buildSummary(context)",
		"com.adsamcik.tracker.statistics.repository.SessionStatsAdapter",
	),
)
object SummaryGenerator {
	@WorkerThread
	fun buildSummary(context: Context): List<Stat> = SessionStatsAdapter.buildSummary(context)

	@WorkerThread
	fun buildSevenDaySummary(context: Context): List<Stat> = SessionStatsAdapter.buildSevenDaySummary(context)
}
