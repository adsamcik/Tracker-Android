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

	fun createTitle(context: Context, date: Calendar, activity: SessionActivity): String {
		val activityName = activity.name
		val hour = date[Calendar.HOUR_OF_DAY]
		val day = SimpleDateFormat("EEEE", Locale.getDefault()).format(date.time).capitalize()
		return if (hour < 6 || hour > 22) {
			context.getString(R.string.stats_night, day, activityName)
		} else if (hour < 12) {
			context.getString(R.string.stats_morning, day, activityName)
		} else if (hour < 17) {
			context.getString(R.string.stats_afternoon, day, activityName)
		} else {
			context.getString(R.string.stats_evening, day, activityName)
		}
	}

	private fun SimpleDateFormat.noYear(): SimpleDateFormat {
		applyPattern(toPattern().replace("[^\\p{Alpha}]*y+[^\\p{Alpha}]*".toRegex(), ""))
		return this
	}
}

