package com.adsamcik.tracker.statistics.repository

import android.content.Context
import androidx.annotation.WorkerThread
import androidx.sqlite.db.SimpleSQLiteQuery
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.TrackerSessionSummary
import com.adsamcik.tracker.shared.base.extension.formatAsDuration
import com.adsamcik.tracker.shared.base.extension.formatReadable
import com.adsamcik.tracker.shared.preferences.settings.TrackerSettingsQuick
import com.adsamcik.tracker.shared.utils.extension.formatDistance
import com.adsamcik.tracker.statistics.R
import com.adsamcik.tracker.statistics.data.Stat
import com.adsamcik.tracker.statistics.detail.StatisticDisplayType
import java.util.Calendar
import java.util.Locale

/**
 * Adapter that materializes legacy summary dialog [Stat] rows from Room-backed repositories.
 * This is the summary API used by repository and UI call sites.
 */
object SessionStatsAdapter {
	private const val SUMMARY_DECIMAL_PLACES = 1

	@WorkerThread
	fun buildSummary(context: Context): List<Stat> {
		val database = AppDatabase.database(context)
		val tripDao = database.tripDao()
		val wifiObservationDao = database.wifiObservationDao()
		val cellSampleDao = database.cellSampleDao()
		val summary = querySegmentSummary(database, null, null)
		val unifiedSessionCount = tripDao.countAllTrips()
		val wifiCount = wifiObservationDao.countDistinctBssid()
		val cellCount = cellSampleDao.uniqueCount()
		val locationCount = queryLocationCount(database, null, null)

		return buildList {
			addAll(sessionSummaryStats(context, summary))
			add(
				Stat(
					R.string.stats_location_count,
					com.adsamcik.tracker.shared.base.R.drawable.ic_outline_location_on_24px,
					StatisticDisplayType.INFORMATION,
					locationCount.formatReadable(),
				),
			)
			add(
				Stat(
					R.string.stats_wifi_count,
					com.adsamcik.tracker.shared.base.R.drawable.ic_outline_network_wifi_24px,
					StatisticDisplayType.INFORMATION,
					wifiCount.formatReadable(),
				),
			)
			add(
				Stat(
					R.string.stats_cell_count,
					com.adsamcik.tracker.shared.base.R.drawable.ic_outline_network_cell_24px,
					StatisticDisplayType.INFORMATION,
					cellCount.formatReadable(),
				),
			)
			add(
				Stat(
					R.string.stats_session_count,
					com.adsamcik.tracker.shared.base.R.drawable.ic_outline_map_24dp,
					StatisticDisplayType.INFORMATION,
					unifiedSessionCount.formatReadable(),
				),
			)
		}
	}

	@WorkerThread
	fun buildSevenDaySummary(context: Context): List<Stat> {
		val now = Time.nowMillis
		val weekAgo = Calendar.getInstance(Locale.getDefault()).apply {
			add(Calendar.WEEK_OF_MONTH, -1)
		}.timeInMillis

		val database = AppDatabase.database(context)
		val tripDao = database.tripDao()
		val wifiObservationDao = database.wifiObservationDao()
		val cellSampleDao = database.cellSampleDao()
		val summary = querySegmentSummary(database, weekAgo, now)
		val unifiedSessionCount = tripDao.countTripsBetween(weekAgo, now)
		val wifiCount = wifiObservationDao.countDistinctBssid(weekAgo, now)
		val cellCount = cellSampleDao.uniqueCount(weekAgo, now)
		val locationCount = queryLocationCount(database, weekAgo, now)

		return buildList {
			addAll(sessionSummaryStats(context, summary))
			add(
				Stat(
					R.string.stats_session_count,
					com.adsamcik.tracker.shared.base.R.drawable.ic_outline_map_24dp,
					StatisticDisplayType.INFORMATION,
					unifiedSessionCount.formatReadable(),
				),
			)
			add(
				Stat(
					R.string.stats_location_count,
					com.adsamcik.tracker.shared.base.R.drawable.ic_outline_location_on_24px,
					StatisticDisplayType.INFORMATION,
					locationCount.formatReadable(),
				),
			)
			add(
				Stat(
					R.string.stats_wifi_count,
					com.adsamcik.tracker.shared.base.R.drawable.ic_outline_network_wifi_24px,
					StatisticDisplayType.INFORMATION,
					wifiCount.formatReadable(),
				),
			)
			add(
				Stat(
					R.string.stats_cell_count,
					com.adsamcik.tracker.shared.base.R.drawable.ic_outline_network_cell_24px,
					StatisticDisplayType.INFORMATION,
					cellCount.formatReadable(),
				),
			)
		}
	}

	/**
	 * Queries aggregated summary from session_segment table using raw SQL.
	 * When [fromMs] and [toMs] are null, aggregates all segments.
	 */
	private fun querySegmentSummary(
		database: AppDatabase,
		fromMs: Long?,
		toMs: Long?,
	): TrackerSessionSummary {
		val query = if (fromMs != null && toMs != null) {
			SimpleSQLiteQuery(
				"""
				SELECT
					IFNULL(SUM(end_time_ms - start_time_ms), 0),
					IFNULL(SUM(sample_count), 0),
					IFNULL(SUM(distance_m), 0),
					IFNULL(SUM(steps), 0)
				FROM session_segment
				WHERE sample_count > 0 AND start_time_ms >= ? AND end_time_ms <= ?
				""",
				arrayOf<Any>(fromMs, toMs),
			)
		} else {
			SimpleSQLiteQuery(
				"""
				SELECT
					IFNULL(SUM(end_time_ms - start_time_ms), 0),
					IFNULL(SUM(sample_count), 0),
					IFNULL(SUM(distance_m), 0),
					IFNULL(SUM(steps), 0)
				FROM session_segment
				WHERE sample_count > 0
				""",
			)
		}
		val cursor = database.query(query)
		cursor.moveToFirst()
		val duration = cursor.getLong(0)
		val collections = cursor.getInt(1)
		val distance = cursor.getFloat(2)
		val steps = cursor.getInt(3)
		cursor.close()
		return TrackerSessionSummary(
			duration = duration,
			collections = collections,
			distanceInM = distance,
			distanceOnFootInM = 0f,
			distanceInVehicleInM = 0f,
			steps = steps,
		)
	}

	/**
	 * Queries location sample count using raw SQL.
	 * When [fromMs] and [toMs] are null, counts all samples.
	 */
	private fun queryLocationCount(
		database: AppDatabase,
		fromMs: Long?,
		toMs: Long?,
	): Long {
		val query = if (fromMs != null && toMs != null) {
			SimpleSQLiteQuery(
				"SELECT COUNT(*) FROM location_sample WHERE time_ms >= ? AND time_ms <= ?",
				arrayOf<Any>(fromMs, toMs),
			)
		} else {
			SimpleSQLiteQuery("SELECT COUNT(*) FROM location_sample")
		}
		val cursor = database.query(query)
		cursor.moveToFirst()
		val count = cursor.getLong(0)
		cursor.close()
		return count
	}

	private fun sessionSummaryStats(
		context: Context,
		summary: TrackerSessionSummary,
	): List<Stat> {
		val resources = context.resources
		val lengthSystem = TrackerSettingsQuick.lengthSystem(context)
		return listOf(
			Stat(
				R.string.stats_time,
				com.adsamcik.tracker.shared.base.R.drawable.ic_outline_access_time_24px,
				StatisticDisplayType.INFORMATION,
				summary.duration.formatAsDuration(context),
			),
			Stat(
				R.string.stats_distance_total,
				com.adsamcik.tracker.shared.base.R.drawable.ic_ruler,
				StatisticDisplayType.INFORMATION,
				resources.formatDistance(summary.distanceInM, SUMMARY_DECIMAL_PLACES, lengthSystem),
			),
			Stat(
				R.string.stats_distance_on_foot,
				com.adsamcik.tracker.shared.base.R.drawable.ic_shoe_print,
				StatisticDisplayType.INFORMATION,
				resources.formatDistance(summary.distanceOnFootInM, SUMMARY_DECIMAL_PLACES, lengthSystem),
			),
			Stat(
				R.string.stats_distance_in_vehicle,
				com.adsamcik.tracker.shared.base.R.drawable.ic_directions_car_white_24dp,
				StatisticDisplayType.INFORMATION,
				resources.formatDistance(summary.distanceInVehicleInM, SUMMARY_DECIMAL_PLACES, lengthSystem),
			),
			Stat(
				R.string.stats_collections,
				com.adsamcik.tracker.shared.base.R.drawable.ic_outline_layers_24dp,
				StatisticDisplayType.INFORMATION,
				summary.collections.formatReadable(),
			),
			Stat(
				R.string.stats_steps,
				com.adsamcik.tracker.shared.base.R.drawable.ic_outline_directions_run_24px,
				StatisticDisplayType.INFORMATION,
				summary.steps.formatReadable(),
			),
		)
	}
}
