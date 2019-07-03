package com.adsamcik.signalcollector.statistics

import android.content.Context
import com.adsamcik.signalcollector.common.Time
import com.adsamcik.signalcollector.common.data.SessionActivity
import com.adsamcik.signalcollector.common.extension.toDate
import java.text.SimpleDateFormat
import java.util.*

object StatsFormat {
	//todo improve localization support
	fun formatRange(start: Calendar, end: Calendar): String {
		val today = Calendar.getInstance().toDate()
		val startDate = start.time
		val endDate = end.time

		val timePattern = "hh:mm"

		val locale = Locale.getDefault()

		return if ((startDate.time / Time.DAY_IN_MILLISECONDS) == (endDate.time / Time.DAY_IN_MILLISECONDS)) {
			val dateFormat = SimpleDateFormat("d MMMM", locale)
			val timeFormat = SimpleDateFormat(timePattern, locale)
			"${dateFormat.format(startDate)}, ${timeFormat.format(startDate)} - ${timeFormat.format(endDate)}"
		} else {
			val datePattern = if (start.get(Calendar.YEAR) == today.get(Calendar.YEAR)) "d MMMM"
			else "d MMMM yyyy"

			val format = SimpleDateFormat("$datePattern $timePattern", locale)
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
}