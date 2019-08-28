package com.adsamcik.tracker.statistics

import android.content.Context
import com.adsamcik.tracker.common.data.SessionActivity
import com.adsamcik.tracker.common.extension.dayOfYear
import com.adsamcik.tracker.common.extension.toDate
import com.adsamcik.tracker.common.extension.year
import java.text.SimpleDateFormat
import java.util.*


object StatsFormat {
	//todo improve separator localization
	fun formatRange(start: Calendar, end: Calendar): String {
		val today = Calendar.getInstance().toDate()
		val startDate = start.time
		val endDate = end.time

		val locale = Locale.getDefault()

		var dateFormat = SimpleDateFormat.getDateInstance(
				SimpleDateFormat.MEDIUM,
				locale
		) as SimpleDateFormat

		if (start.year == today.year) {
			dateFormat = dateFormat.noYear()
		}

		return if (start.dayOfYear == end.dayOfYear && start.year == end.year) {
			val timeFormat = SimpleDateFormat.getTimeInstance(SimpleDateFormat.SHORT, locale)
			"${dateFormat.format(startDate)}, ${timeFormat.format(startDate)} - ${timeFormat.format(
					endDate
			)}"
		} else {
			val timeFormat = SimpleDateFormat.getDateTimeInstance(
					SimpleDateFormat.SHORT, SimpleDateFormat.SHORT,
					locale
			) as SimpleDateFormat
			val format = SimpleDateFormat(
					"${dateFormat.toPattern()} ${timeFormat.toPattern()}",
					locale
			)
			"${format.format(startDate)} - ${format.format(endDate)}"
		}
	}

	@Suppress("ComplexMethod", "MagicNumber")
	fun createTitle(
			context: Context,
			start: Calendar,
			end: Calendar,
			activity: SessionActivity
	): String {
		val activityName = if (activity.name.isBlank()) {
			context.getString(R.string.stats_format_unknown_activity)
		} else {
			activity.name
		}

		val startHour = start[Calendar.HOUR_OF_DAY]
		val endHour = end[Calendar.HOUR_OF_DAY]

		val day = SimpleDateFormat("EEEE", Locale.getDefault()).format(start.time).capitalize()
		val timeOfDayStringRes = if (startHour >= 22 && endHour <= 2) {
			R.string.stats_midnight
		} else if ((startHour in 22..24 || startHour in 0..6) && (endHour in 22..24 || endHour in 0..6)) {
			R.string.stats_night
		} else if (startHour >= 6 && endHour <= 12) {
			R.string.stats_morning
		} else if (startHour >= 11 && endHour <= 14) {
			R.string.stats_lunch
		} else if (startHour >= 12 && endHour <= 17) {
			R.string.stats_afternoon
		} else if (startHour >= 17 && endHour <= 23) {
			R.string.stats_evening
		} else {
			0
		}

		return if (timeOfDayStringRes == 0) {
			context.getString(R.string.stats_generic_title_text, day, activityName)
		} else {
			val dayPart = context.getString(timeOfDayStringRes)
			context.getString(R.string.stats_title_text, day, dayPart, activityName)
		}
	}

	private fun SimpleDateFormat.noYear(): SimpleDateFormat {
		applyPattern(toPattern().replace("[^\\p{Alpha}]*y+[^\\p{Alpha}]*".toRegex(), ""))
		return this
	}
}

