package com.adsamcik.tracker.statistics.summary

import android.content.Context
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.TrackerSessionSummary
import com.adsamcik.tracker.shared.base.extension.formatAsDuration
import com.adsamcik.tracker.shared.base.extension.formatReadable
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.shared.utils.extension.formatDistance
import com.adsamcik.tracker.statistics.R
import com.adsamcik.tracker.statistics.data.Stat
import com.adsamcik.tracker.statistics.detail.recycler.StatisticDisplayType
import java.util.*

/**
 * Provides methods to generate summaries
 */
object SummaryGenerator {
	private const val SUMMARY_DECIMAL_PLACES = 1

	private fun getSessionSummaryStats(
			context: Context,
			sessionSummary: TrackerSessionSummary
	): List<Stat> {
		val resources = context.resources
		return listOf(
				Stat(
						R.string.stats_time,
						com.adsamcik.tracker.shared.base.R.drawable.seed_outline,
						displayType = StatisticDisplayType.Information,
						sessionSummary.duration.formatAsDuration(context)
				),
				Stat(
						R.string.stats_distance_total,
						com.adsamcik.tracker.shared.base.R.drawable.seed_outline,
						displayType = StatisticDisplayType.Information,
						resources.formatDistance(
								sessionSummary.distanceInM,
								SUMMARY_DECIMAL_PLACES,
								Preferences.getLengthSystem(context)
						)
				),
				Stat(
						R.string.stats_distance_on_foot,
						com.adsamcik.tracker.shared.base.R.drawable.seed_outline,
						displayType = StatisticDisplayType.Information,
						resources.formatDistance(
								sessionSummary.distanceOnFootInM,
								SUMMARY_DECIMAL_PLACES,
								Preferences.getLengthSystem(context)
						)
				),

				Stat(
						R.string.stats_distance_in_vehicle,
						com.adsamcik.tracker.shared.base.R.drawable.seed_outline,
						displayType = StatisticDisplayType.Information,
						resources.formatDistance(
								sessionSummary.distanceInVehicleInM,
								SUMMARY_DECIMAL_PLACES,
								Preferences.getLengthSystem(context)
						)
				),
				Stat(
						R.string.stats_collections,
						com.adsamcik.tracker.shared.base.R.drawable.seed_outline,
						displayType = StatisticDisplayType.Information,
						sessionSummary.collections.formatReadable()
				),
				Stat(
						R.string.stats_steps,
						com.adsamcik.tracker.shared.base.R.drawable.seed_outline,
						displayType = StatisticDisplayType.Information,
						sessionSummary.steps.formatReadable()
				),
		)
	}

	fun buildSummary(context: Context): List<Stat> {
		val database = AppDatabase.database(context)
		val wifiDao = database.wifiDao()
		val cellDao = database.cellLocationDao()
		val locationDao = database.locationDao()
		val sessionDao = database.sessionDao()
		val sumSessionData = sessionDao.getSummary()

		val sessionSummaryStats = getSessionSummaryStats(context, sumSessionData)

		val countList = listOf(
				Stat(
						R.string.stats_location_count,
						com.adsamcik.tracker.shared.base.R.drawable.seed_outline,
						displayType = StatisticDisplayType.Information,
						locationDao.count().formatReadable()
				),
				Stat(
						R.string.stats_wifi_count,
						com.adsamcik.tracker.shared.base.R.drawable.seed_outline,
						displayType = StatisticDisplayType.Information,
						wifiDao.count().formatReadable()
				),
				Stat(
						R.string.stats_cell_count,
						com.adsamcik.tracker.shared.base.R.drawable.seed_outline,
						displayType = StatisticDisplayType.Information,
						cellDao.uniqueCount().formatReadable()
				),
				Stat(
						R.string.stats_session_count,
						com.adsamcik.tracker.shared.base.R.drawable.seed_outline,
						displayType = StatisticDisplayType.Information,
						sessionDao.count().formatReadable()
				),
		)

		val result = mutableListOf<Stat>()

		result.addAll(sessionSummaryStats)
		result.addAll(countList)

		return result
	}

	fun buildSevenDaySummary(context: Context): List<Stat> {
		val now = Time.nowMillis
		val weekAgo = Calendar.getInstance(Locale.getDefault()).apply {
			add(Calendar.WEEK_OF_MONTH, -1)
		}.timeInMillis

		val database = AppDatabase.database(context)
		val sessionDao = database.sessionDao()
		val lastWeekSummary = sessionDao.getSummary(weekAgo, now)
		val wifiDao = database.wifiDao()
		val cellDao = database.cellLocationDao()
		val locationDao = database.locationDao()

		val sessionStatData = getSessionSummaryStats(context, lastWeekSummary)
		val countList = listOf(
				Stat(
						R.string.stats_session_count,
						com.adsamcik.tracker.shared.base.R.drawable.seed_outline,
						displayType = StatisticDisplayType.Information,
						sessionDao.count(weekAgo, now).formatReadable()
				),
				Stat(
						R.string.stats_location_count,
						com.adsamcik.tracker.shared.base.R.drawable.seed_outline,
						displayType = StatisticDisplayType.Information,
						locationDao.count(weekAgo, now).formatReadable()
				),
				Stat(
						R.string.stats_wifi_count,
						com.adsamcik.tracker.shared.base.R.drawable.seed_outline,
						displayType = StatisticDisplayType.Information,
						wifiDao.count(weekAgo, now).formatReadable()
				),
				Stat(
						R.string.stats_cell_count,
						com.adsamcik.tracker.shared.base.R.drawable.seed_outline,
						displayType = StatisticDisplayType.Information,
						cellDao.uniqueCount(weekAgo, now).formatReadable()
				)
		)

		val result = mutableListOf<Stat>()

		result.addAll(sessionStatData)
		result.addAll(countList)
		return result
	}
}
