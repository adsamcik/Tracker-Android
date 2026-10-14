package com.adsamcik.tracker.statistics.presenter

import android.content.Context
import com.adsamcik.tracker.shared.base.extension.formatAsDuration
import com.adsamcik.tracker.shared.base.extension.formatReadable
import com.adsamcik.tracker.shared.preferences.settings.TrackerSettingsQuick
import com.adsamcik.tracker.shared.preferences.extension.formatDistance
import com.adsamcik.tracker.statistics.R
import com.adsamcik.tracker.statistics.data.Stat
import com.adsamcik.tracker.statistics.detail.StatisticDisplayType
import com.adsamcik.tracker.stats.api.repository.SessionStatsSnapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class SessionStatsUiFormatter @Inject constructor(
	@ApplicationContext private val context: Context,
) {

	fun formatSummary(snapshot: SessionStatsSnapshot): List<Stat> {
		return buildList {
			addAll(sessionSummaryStats(snapshot))
			add(countStat(R.string.stats_location_count, com.adsamcik.tracker.shared.base.R.drawable.ic_outline_location_on_24px, snapshot.locationCount))
			add(countStat(R.string.stats_wifi_count, com.adsamcik.tracker.shared.base.R.drawable.ic_outline_network_wifi_24px, snapshot.wifiCount, snapshot.wifiCountIsPartial))
			add(countStat(R.string.stats_cell_count, com.adsamcik.tracker.shared.base.R.drawable.ic_outline_network_cell_24px, snapshot.cellCount, snapshot.cellCountIsPartial))
			add(countStat(R.string.stats_session_count, com.adsamcik.tracker.shared.base.R.drawable.ic_outline_map_24dp, snapshot.tripCount))
		}
	}

	fun formatWeekly(snapshot: SessionStatsSnapshot): List<Stat> {
		return buildList {
			addAll(sessionSummaryStats(snapshot))
			add(countStat(R.string.stats_session_count, com.adsamcik.tracker.shared.base.R.drawable.ic_outline_map_24dp, snapshot.tripCount))
			add(countStat(R.string.stats_location_count, com.adsamcik.tracker.shared.base.R.drawable.ic_outline_location_on_24px, snapshot.locationCount))
			add(countStat(R.string.stats_wifi_count, com.adsamcik.tracker.shared.base.R.drawable.ic_outline_network_wifi_24px, snapshot.wifiCount, snapshot.wifiCountIsPartial))
			add(countStat(R.string.stats_cell_count, com.adsamcik.tracker.shared.base.R.drawable.ic_outline_network_cell_24px, snapshot.cellCount, snapshot.cellCountIsPartial))
		}
	}

	private fun sessionSummaryStats(snapshot: SessionStatsSnapshot): List<Stat> {
		val resources = context.resources
		val lengthSystem = TrackerSettingsQuick.lengthSystem(context)
		return listOf(
			Stat(
				R.string.stats_time,
				com.adsamcik.tracker.shared.base.R.drawable.ic_outline_access_time_24px,
				StatisticDisplayType.INFORMATION,
				snapshot.duration.raw.formatAsDuration(context),
			),
			Stat(
				R.string.stats_distance_total,
				com.adsamcik.tracker.shared.base.R.drawable.ic_ruler,
				StatisticDisplayType.INFORMATION,
				resources.formatDistance(snapshot.totalDistance.raw, SUMMARY_DECIMAL_PLACES, lengthSystem),
			),
			Stat(
				R.string.stats_distance_on_foot,
				com.adsamcik.tracker.shared.base.R.drawable.ic_shoe_print,
				StatisticDisplayType.INFORMATION,
				resources.formatDistance(snapshot.onFootDistance.raw, SUMMARY_DECIMAL_PLACES, lengthSystem),
			),
			Stat(
				R.string.stats_distance_in_vehicle,
				com.adsamcik.tracker.shared.base.R.drawable.ic_directions_car_white_24dp,
				StatisticDisplayType.INFORMATION,
				resources.formatDistance(snapshot.inVehicleDistance.raw, SUMMARY_DECIMAL_PLACES, lengthSystem),
			),
			Stat(
				R.string.stats_collections,
				com.adsamcik.tracker.shared.base.R.drawable.ic_outline_layers_24dp,
				StatisticDisplayType.INFORMATION,
				snapshot.collections.formatReadable(),
			),
		).filter { stat -> when (stat.nameRes) {
			R.string.stats_distance_total -> snapshot.hasMeasuredDistance
			R.string.stats_distance_on_foot, R.string.stats_distance_in_vehicle ->
				snapshot.hasMeasuredDistance && snapshot.hasActivityDistances
			else -> true
		} }.map { stat ->
			if (snapshot.distanceIsPartial && stat.nameRes in setOf(R.string.stats_distance_total,
				R.string.stats_distance_on_foot, R.string.stats_distance_in_vehicle)) {
				stat.copy(data = resources.getString(R.string.stats_recorded_partial_value, stat.data.toString()))
			} else stat
		}
	}

	private fun countStat(nameRes: Int, iconRes: Int, value: Long?, partial: Boolean = false): Stat {
		val text = value?.formatReadable()?.let {
			if (partial) context.getString(R.string.stats_recorded_partial_value, it) else it
		} ?: context.getString(R.string.history_value_unavailable)
		return Stat(
			nameRes = nameRes,
			iconRes = iconRes,
			displayType = StatisticDisplayType.INFORMATION,
			data = text,
		)
	}

	private companion object {
		const val SUMMARY_DECIMAL_PLACES = 1
	}
}
