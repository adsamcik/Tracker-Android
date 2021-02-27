package com.adsamcik.tracker.shared.utils.style

import android.content.Context
import android.os.Looper
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.data.BaseLocation
import com.adsamcik.tracker.shared.base.data.LengthUnit
import com.adsamcik.tracker.shared.base.data.Location
import com.adsamcik.tracker.shared.base.extension.toZonedDateTime
import com.adsamcik.tracker.shared.preferences.Preferences
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import org.shredzone.commons.suncalc.SunTimes
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock


typealias SunSetRiseChangeListener = (SunSetRise) -> Unit

/**
 * Class used for calculation of next sunset and sunrise.
 */
//todo add central location API to better manage passive location updates in the future
@Suppress("MemberVisibilityCanBePrivate")
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

	/**
	 * Finds nearest sunrise within 24 hours from now.
	 *
	 * @return Time of the nearest sunrise within 24 hours or null if there won't be one
	 */
	fun sunriseForToday(): ZonedDateTime? = sunriseFor(Time.now)

	/**
	 * Finds nearest sunrise within 24 hours from given date.
	 *
	 * @return Time of the nearest sunrise within 24 hours or null if there won't be one
	 */
	fun sunriseFor(dateTime: ZonedDateTime): ZonedDateTime? {
		val location = location
		return if (location != null) {
			getSunrise(location, dateTime)
		} else {
			dateTime.withHour(DEFAULT_SUNRISE)
		}
	}

	/**
	 * Finds nearest sunset within 24 hours from now.
	 *
	 * @return Time of the nearest sunset within 24 hours or null if there won't be one
	 */
	fun sunsetForToday(): ZonedDateTime? = sunsetFor(Time.now)

	/**
	 * Finds nearest sunset within 24 hours from given date.
	 *
	 * @return Time of the nearest sunset within 24 hours or null if there won't be one
	 */
	fun sunsetFor(dateTime: ZonedDateTime): ZonedDateTime? {
		val location = location
		return if (location != null) {
			getSunset(location, dateTime)
		} else {
			dateTime.withHour(DEFAULT_SUNSET)
		}
	}

	/**
	 * Returns sun data for given date
	 *
	 * @return Sun data
	 */
	fun sunDataFor(dateTime: ZonedDateTime): SunTimes {
		return getCalculator(dateTime, location)
	}

	private fun requestLocationUpdates(context: Context) {
		val locationProvider = LocationServices.getFusedLocationProviderClient(context)
		val request = LocationRequest.create().apply {
			priority = LocationRequest.PRIORITY_NO_POWER
			interval = Time.MINUTE_IN_MILLISECONDS
		}

		locationProvider.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
	}

	/**
	 * Initializes instance with location
	 */
	fun initialize(context: Context) {
		val appContext = context.applicationContext
		this.appContext = appContext

		requestLocationUpdates(appContext)

		val preferences = Preferences.getPref(appContext)
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

	/**
	 * Add change listener
	 */
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
				Preferences.getPref(context).edit {
					setDouble(LAST_LATITUDE_KEY, loc.latitude)
					setDouble(LAST_LONGITUDE_KEY, loc.longitude)
				}

				listeners.forEach { it.invoke(this) }
				this.location = BaseLocation(loc.latitude, loc.longitude)
			}
		}
	}

	private fun getCalculator(dateTime: ZonedDateTime, location: BaseLocation? = null): SunTimes {
		val truncated = dateTime.truncatedTo(ChronoUnit.MINUTES)
		return SunTimes
				.compute()
				.apply {
					if (location?.isValid == true) {
						at(location.latitude, location.longitude)
					}
					on(truncated)
					twilight(SunTimes.Twilight.VISUAL)
				}
				.execute()
	}

	private fun getSunset(calculator: SunTimes): ZonedDateTime? {
		return calculator.set
	}

	private fun getSunset(location: BaseLocation, dateTime: ZonedDateTime): ZonedDateTime? {
		return getSunset(getCalculator(dateTime, location))
	}

	private fun getSunrise(calculator: SunTimes): ZonedDateTime? {
		return calculator.rise
	}

	private fun getSunrise(location: BaseLocation, dateTime: ZonedDateTime): ZonedDateTime? {
		return getSunrise(getCalculator(dateTime, location))
	}

	companion object {
		private const val LAST_LATITUDE_KEY = "SunSetLatitude"
		private const val LAST_LONGITUDE_KEY = "SunSetLongitude"

		private const val MIN_DIFFERENCE_IN_KILOMETERS = 50.0

		private const val DEFAULT_SUNSET = 21
		private const val DEFAULT_SUNRISE = 7
	}
}
