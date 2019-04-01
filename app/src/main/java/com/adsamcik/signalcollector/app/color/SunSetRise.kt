package com.adsamcik.signalcollector.app.color

import com.adsamcik.signalcollector.misc.extension.roundToDate
import com.luckycatlabs.sunrisesunset.SunriseSunsetCalculator
import com.luckycatlabs.sunrisesunset.dto.Location
import java.util.*

/**
 * Class used for calculation of next sunset and sunrise.
 */
class SunSetRise {
	private var location: Location? = null
	private var lastUpdate: Calendar? = null

	/**
	 * Calculates when will next sunset be
	 *
	 * @return Calendar instance containing time and date of the next sunset
	 */
	fun nextSunset(): Calendar {
		val calendar = Calendar.getInstance()
		return if (location != null) {
			val calculator = getCalculator(location!!, calendar)
			val sunset = getSunset(calculator, calendar)
			if (sunset < calendar) {
				calendar.add(Calendar.DAY_OF_WEEK, 1)
				getSunset(location!!, calendar)
			} else
				sunset
		} else {
			calendar.roundToDate()
			calendar.set(Calendar.HOUR_OF_DAY, 21)
			calendar
		}
	}

	/**
	 * Calculates when will next sunrise be
	 *
	 * @return Calendar instance containing time and date of the next sunrise
	 */
	fun nextSunrise(): Calendar {
		val calendar = Calendar.getInstance()
		return if (location != null) {
			val calculator = getCalculator(location!!, calendar)
			val sunrise = getSunrise(calculator, calendar)
			if (sunrise < calendar) {
				calendar.add(Calendar.DAY_OF_WEEK, 1)
				getSunrise(location!!, calendar)
			} else
				sunrise
		} else {
			calendar.roundToDate()
			calendar.set(Calendar.HOUR_OF_DAY, 7)
			calendar
		}
	}


	/**
	 * This method should be called to update location used for calculating next sunrise/sunset.
	 * Proper location is required to make the calculations accurate.
	 */
	@Synchronized
	fun updateLocation(loc: android.location.Location) {
		this.location = Location(loc.latitude, loc.longitude)
		lastUpdate = Calendar.getInstance()
	}

	private fun getCalculator(location: Location, calendar: Calendar): SunriseSunsetCalculator {
		return SunriseSunsetCalculator(location, calendar.timeZone)
	}

	private fun getSunset(calculator: SunriseSunsetCalculator, calendar: Calendar): Calendar {
		return calculator.getOfficialSunsetCalendarForDate(calendar)
	}

	private fun getSunset(location: Location, calendar: Calendar): Calendar {
		return getSunset(getCalculator(location, calendar), calendar)
	}

	private fun getSunrise(calculator: SunriseSunsetCalculator, calendar: Calendar): Calendar {
		return calculator.getOfficialSunriseCalendarForDate(calendar)
	}

	private fun getSunrise(location: Location, calendar: Calendar): Calendar {
		return getSunrise(getCalculator(location, calendar), calendar)
	}

	private fun getSunriseSunset(location: Location, calendar: Calendar): Pair<Calendar, Calendar> {
		val calculator = getCalculator(location, calendar)
		val sunrise = getSunrise(calculator, calendar)
		val sunset = getSunset(calculator, calendar)
		return Pair(sunrise, sunset)
	}
}