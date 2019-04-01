package com.adsamcik.signalcollector.misc.extension

import android.os.Build
import java.util.*

fun createCalendarWithDate(year: Int, monthOfYear: Int, dayOfMonth: Int): Calendar {
	return if (Build.VERSION.SDK_INT >= 26)
		Calendar.Builder().setDate(year, monthOfYear, dayOfMonth).build()
	else {
		val cal = Calendar.getInstance()
		cal.set(year, monthOfYear, dayOfMonth, 0, 0, 0)
		cal
	}
}

fun Date.toCalendar(): Calendar {
	val calendar = Calendar.getInstance()
	calendar.time = this
	return calendar
}