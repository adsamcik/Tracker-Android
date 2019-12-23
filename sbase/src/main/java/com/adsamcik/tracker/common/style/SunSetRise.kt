package com.adsamcik.tracker.common.style

import android.content.Context
import android.os.Looper
import com.adsamcik.tracker.common.Time
import com.adsamcik.tracker.common.data.BaseLocation
import com.adsamcik.tracker.common.data.LengthUnit
import com.adsamcik.tracker.common.data.Location
import com.adsamcik.tracker.common.extension.toCalendar
import com.adsamcik.tracker.common.extension.toDate
import com.adsamcik.tracker.common.preferences.Preferences
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import org.shredzone.commons.suncalc.SunTimes
import org.shredzone.commons.suncalc.param.TimeResultParameter
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
	private var location: BaseLocation? = null

	private var appContext: Context? = null

	private var listeners = mutableSetOf<SunSetRiseChangeListener>()

	private val locationCallback = object : LocationCallback() {
		override fun onLocationResult(locationResult: LocationResult) {
			val context = appContext
			requireNotNull(context)
			updateLocation(context, locationResult.lastLocation)
		}

	}

	fun sunriseForToday(): Calendar? = sunriseFor(Time.now)

	fun sunriseFor(calendar: Calendar): Calendar? {
		val location = location
		return if (location != null) {
			return getSunrise(location, calendar)
		} else {
			val dateCalendar = calendar.toDate()
			dateCalendar.set(Calendar.HOUR_OF_DAY, DEFAULT_SUNRISE)
			dateCalendar
		}
	}

	fun sunsetForToday(): Calendar? = sunsetFor(Time.now)

	fun sunsetFor(calendar: Calendar): Calendar? {
		val location = location
		return if (location != null) {
			return getSunset(location, calendar)
		} else {
			val dateCalendar = calendar.toDate()
			dateCalendar.set(Calendar.HOUR_OF_DAY, DEFAULT_SUNSET)
			dateCalendar
		}
	}

	fun sunDataFor(calendar: Calendar): SunTimes {
		val location = location
		return if (location != null) {
			return getCalculator(location, calendar)
		} else {
			SunTimes.compute().execute()
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

		val preferences = com.adsamcik.tracker.common.preferences.Preferences.getPref(appContext)
		val lastLatitude = preferences.getDouble(LAST_LATITUDE_KEY, Double.NaN)
		val lastLongitude = preferences.getDouble(LAST_LONGITUDE_KEY, Double.NaN)

		val location = if (!lastLatitude.isNaN() && !lastLongitude.isNaN()) {
			BaseLocation(lastLatitude, lastLongitude)
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
				Location.distance(
						currentLocation.latitude,
						currentLocation.longitude,
						loc.latitude,
						loc.longitude,
						LengthUnit.Kilometer
				)
			} else {
				Double.POSITIVE_INFINITY
			}

			if (distance > MIN_DIFFERENCE_IN_KILOMETERS) {
				com.adsamcik.tracker.common.preferences.Preferences.getPref(context).edit {
					setDouble(LAST_LATITUDE_KEY, loc.latitude)
					setDouble(LAST_LONGITUDE_KEY, loc.longitude)
				}

				listeners.forEach { it.invoke(this) }
				this.location = BaseLocation(loc.latitude, loc.longitude)
			}
		}
	}

	private fun getCalculator(location: BaseLocation, calendar: Calendar): SunTimes {
		return SunTimes
				.compute().apply {
					if (location.isValid) {
						at(location.latitude, location.longitude)
					}
					on(calendar)
					truncatedTo(TimeResultParameter.Unit.MINUTES)
					twilight(SunTimes.Twilight.VISUAL)
				}
				.execute()
	}

	private fun getSunset(calculator: SunTimes): Calendar? {
		return calculator.set?.toCalendar()
	}

	private fun getSunset(location: BaseLocation, calendar: Calendar): Calendar? {
		return getSunset(getCalculator(location, calendar))
	}

	private fun getSunrise(calculator: SunTimes): Calendar? {
		return calculator.rise?.toCalendar()
	}

	private fun getSunrise(location: BaseLocation, calendar: Calendar): Calendar? {
		return getSunrise(getCalculator(location, calendar))
	}

	companion object {
		private const val LAST_LATITUDE_KEY = "SunSetLatitude"
		private const val LAST_LONGITUDE_KEY = "SunSetLongitude"

		private const val MIN_DIFFERENCE_IN_KILOMETERS = 50.0

		private const val DEFAULT_SUNSET = 21
		private const val DEFAULT_SUNRISE = 7
	}
}
