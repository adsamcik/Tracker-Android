package com.adsamcik.signalcollector.utility

import com.adsamcik.signalcollector.extensions.date
import com.adsamcik.signalcollector.extensions.roundToDate
import com.luckycatlabs.sunrisesunset.SunriseSunsetCalculator
import com.luckycatlabs.sunrisesunset.dto.Location
import java.util.*

class SunSetRise {
    private var location: Location? = null
    private var lastUpdate: Calendar? = null

    fun nextSunset(): Calendar {
        val calendar = Calendar.getInstance()
        return if(location != null) {
            getSunset(location!!, calendar)
        } else {
            calendar.roundToDate()
            calendar.set(Calendar.HOUR_OF_DAY, 21)
            calendar
        }
    }

    fun nextSunrise(): Calendar {
        val calendar = Calendar.getInstance()
        return if(location != null) {
            getSunrise(location!!, calendar)
        } else {
            calendar.roundToDate()
            calendar.set(Calendar.HOUR_OF_DAY, 7)
            calendar
        }
    }


    @Synchronized fun updateLocation(loc: android.location.Location) {
        this.location = Location(loc.latitude, loc.longitude)
        lastUpdate = Calendar.getInstance()
    }

    private fun getCalculator(location: Location, calendar: Calendar) : SunriseSunsetCalculator {
        return SunriseSunsetCalculator(location, calendar.timeZone)
    }

    private fun getSunset(calculator: SunriseSunsetCalculator, calendar: Calendar) : Calendar {
        return calculator.getOfficialSunsetCalendarForDate(calendar)
    }

    private fun getSunset(location: Location, calendar: Calendar) : Calendar {
        return getSunset(getCalculator(location, calendar), calendar)
    }

    private fun getSunrise(calculator: SunriseSunsetCalculator, calendar: Calendar) : Calendar {
        return calculator.getOfficialSunriseCalendarForDate(calendar)
    }

    private fun getSunrise(location: Location, calendar: Calendar) : Calendar {
        return getSunrise(getCalculator(location, calendar), calendar)
    }

    private fun getSunriseSunset(location: Location, calendar: Calendar) : Pair<Calendar, Calendar> {
        val calculator = getCalculator(location, calendar)
        val sunrise = getSunrise(calculator, calendar)
        val sunset = getSunset(calculator, calendar)
        return Pair(sunrise, sunset)
    }
}