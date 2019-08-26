package com.adsamcik.tracker.common.style

import android.content.Context
import android.os.Looper
import com.adsamcik.tracker.common.Time
import com.adsamcik.tracker.common.data.LengthUnit
import com.adsamcik.tracker.common.extension.roundToDate
import com.adsamcik.tracker.common.preference.Preferences
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.luckycatlabs.sunrisesunset.SunriseSunsetCalculator
import com.luckycatlabs.sunrisesunset.dto.Location
import java.util.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock


typealias SunSetRiseChangeListener = (SunSetRise) -> Unit

/**
 * Class used for calculation of next sunset and sunrise.
 */
//todo add central location API to better manage passive location updates in the future
class SunSetRise {
	private val locationLock = ReentrantLock()
	private var location: Location? = null

	private var appContext: Context? = null

	private var listeners = mutableSetOf<SunSetRiseChangeListener>()

	private val locationCallback = object : LocationCallback() {
		override fun onLocationResult(locationResult: LocationResult) {
			val context = appContext
			requireNotNull(context)
			updateLocation(context, locationResult.lastLocation)
		}

	}

	fun sunriseForToday(): Calendar = sunriseFor(Time.now)

	fun sunriseFor(calendar: Calendar): Calendar {
		val location = location
		return if (location != null) {
			return getSunrise(location, calendar)
		} else {
			calendar.roundToDate()
			calendar.set(Calendar.HOUR_OF_DAY, DEFAULT_SUNRISE)
			calendar
		}
	}

	fun sunsetForToday(): Calendar = sunsetFor(Time.now)

	fun sunsetFor(calendar: Calendar): Calendar {
		val location = location
		return if (location != null) {
			return getSunset(location, calendar)
		} else {
			calendar.roundToDate()
			calendar.set(Calendar.HOUR_OF_DAY, DEFAULT_SUNSET)
			calendar
		}
	}

	private fun requestLocationUpdates(context: Context) {
		val locationProvider = LocationServices.getFusedLocationProviderClient(context)
		val request = LocationRequest.create().apply {
			priority = LocationRequest.PRIORITY_NO_POWER
			interval = Time.MINUTE_IN_MILLISECONDS
		}

		locationProvider.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
	}

	fun initialize(context: Context) {
		val appContext = context.applicationContext
		this.appContext = appContext

		requestLocationUpdates(appContext)

		val preferences = Preferences.getPref(appContext)
		val lastLatitude = preferences.getDouble(LAST_LATITUDE_KEY, Double.NaN)
		val lastLongitude = preferences.getDouble(LAST_LONGITUDE_KEY, Double.NaN)

		val location = if (!lastLatitude.isNaN() && !lastLongitude.isNaN()) {
			Location(lastLatitude, lastLongitude)
		} else {
			return
		}

		locationLock.withLock {
			if (this.location == null) {
				this.location = location
			}
		}
	}

	fun addListener(listener: SunSetRiseChangeListener) {
		listeners.add(listener)
	}


	/**
	 * This method should be called to update location used for calculating next sunrise/sunset.
	 * Proper location is required to make the calculations accurate.
	 */
	private fun updateLocation(context: Context, loc: android.location.Location) {
		locationLock.withLock {
			val currentLocation = this.location
			val distance = if (currentLocation != null) {
				com.adsamcik.tracker.common.data.Location.distance(
						currentLocation.latitude.toDouble(),
						currentLocation.longitude.toDouble(),
						loc.latitude,
						loc.longitude,
						LengthUnit.Kilometer
				)
			} else {
				Double.POSITIVE_INFINITY
			}
			this.location = Location(loc.latitude, loc.longitude)

			if (distance > MIN_DIFFERENCE_IN_KILOMETERS) {
				Preferences.getPref(context).edit {
					setDouble(LAST_LATITUDE_KEY, loc.latitude)
					setDouble(LAST_LONGITUDE_KEY, loc.longitude)
				}

				listeners.forEach { it.invoke(this) }
			}
		}
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

	companion object {
		private const val LAST_LATITUDE_KEY = "SunSetLatitude"
		private const val LAST_LONGITUDE_KEY = "SunSetLongitude"

		private const val MIN_DIFFERENCE_IN_KILOMETERS = 50.0

		private const val DEFAULT_SUNSET = 21
		private const val DEFAULT_SUNRISE = 7
	}
}
