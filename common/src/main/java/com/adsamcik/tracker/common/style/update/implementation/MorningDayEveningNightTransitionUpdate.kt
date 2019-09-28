package com.adsamcik.tracker.common.style.update.implementation

import com.adsamcik.tracker.common.R
import com.adsamcik.tracker.common.Time
import com.adsamcik.tracker.common.style.SunSetRise
import com.adsamcik.tracker.common.style.update.abstraction.DayTimeStyleUpdate
import com.adsamcik.tracker.common.style.update.data.RequiredColorData
import com.adsamcik.tracker.common.style.update.data.RequiredColors
import com.adsamcik.tracker.common.style.update.data.UpdateData
import java.util.*

internal class MorningDayEveningNightTransitionUpdate : DayTimeStyleUpdate() {
	override val nameRes: Int = R.string.settings_color_update_mden_trans_title

	override val requiredColorData: RequiredColors
		get() = RequiredColors(
				listOf(
						RequiredColorData(
								defaultColor = -32512,
								nameRes = R.string.settings_color_morning_title
						),
						RequiredColorData(
								defaultColor = -2031888,
								nameRes = R.string.settings_color_day_title
						),
						RequiredColorData(
								defaultColor = -13033421,
								nameRes = R.string.settings_color_evening_title
						),
						RequiredColorData(
								defaultColor = -16315596,
								nameRes = R.string.settings_color_night_title
						)
				)
		)

	override fun getUpdateData(
			styleList: List<Int>,
			sunSetRise: SunSetRise
	): UpdateData {
		require(styleList.size == requiredColorData.list.size) {
			"Expected list of size ${requiredColorData.list.size} but got ${styleList.size}"
		}

		val time = Time.now

		val sunsetTime = sunSetRise.sunsetForToday()
		val sunriseTime = sunSetRise.sunriseForToday()

		val localUpdateData = calculateProgress(time, sunriseTime, sunsetTime)

		return UpdateData(
				styleList[localUpdateData.fromColor],
				styleList[localUpdateData.toColor],
				localUpdateData.duration,
				localUpdateData.progress
		)
	}

	private fun calculateMidTime(first: Long, second: Long): Long {
		require(first < second)
		return (second - first) / 2L + first
	}

	private fun betweenMidnightAndSunrise(
			now: Long,
			midnight: Long,
			sunrise: Long
	): UpdateData =
			UpdateData(
					fromColor = MIDNIGHT,
					toColor = SUNRISE,
					duration = sunrise - midnight,
					progress = now - midnight
			)

	private fun betweenSunsetAndMidnight(
			now: Long,
			sunset: Long,
			midnight: Long
	): UpdateData =
			UpdateData(
					fromColor = SUNSET,
					toColor = MIDNIGHT,
					duration = midnight - sunset,
					progress = now - sunset
			)

	private fun afterSunset(
			now: Long,
			sunrise: Long,
			sunset: Long
	): UpdateData {
//the max difference between days is approximately under 5 minutes, so it should be fine
		val tomorrowSunrise = sunrise + Time.DAY_IN_MILLISECONDS
		val midnight = calculateMidTime(sunset, tomorrowSunrise)
		return when {
			now < midnight -> betweenSunsetAndMidnight(
					now,
					sunset,
					midnight
			)
			now <= tomorrowSunrise -> betweenMidnightAndSunrise(
					now,
					midnight,
					tomorrowSunrise
			)
			else -> throw IllegalStateException()
		}
	}

	private fun betweenNoonAndSunset(
			now: Long,
			noon: Long,
			sunset: Long
	): UpdateData =
			UpdateData(
					fromColor = NOON,
					toColor = SUNSET,
					duration = sunset - noon,
					progress = now - noon
			)

	private fun betweenSunriseAndNoon(
			now: Long,
			sunrise: Long,
			noon: Long
	): UpdateData =
			UpdateData(
					fromColor = SUNRISE,
					toColor = NOON,
					duration = noon - sunrise,
					progress = now - sunrise
			)

	private fun betweenSunriseAndSunset(
			now: Long,
			sunrise: Long,
			sunset: Long
	): UpdateData {
		val noonInMillis = calculateMidTime(sunrise, sunset)
		return when {
			now >= noonInMillis -> betweenNoonAndSunset(
					now,
					noonInMillis,
					sunset
			)
			now >= sunrise -> betweenSunriseAndNoon(
					now,
					sunrise,
					noonInMillis
			)
			else -> throw IllegalStateException()
		}
	}

	private fun beforeSunrise(
			now: Long,
			sunrise: Long,
			sunset: Long
	): UpdateData {
		//the max difference between days is approximately under 5 minutes, so it should be fine
		val yesterdayApproxSunset = sunset - Time.DAY_IN_MILLISECONDS
		val midnight = calculateMidTime(yesterdayApproxSunset, sunrise)

		return when {
			now >= midnight -> betweenMidnightAndSunrise(
					now,
					midnight,
					sunrise
			)
			now >= yesterdayApproxSunset -> betweenSunsetAndMidnight(
					now,
					sunset,
					midnight
			)
			else -> throw IllegalStateException()
		}
	}

	private fun calculateProgress(
			now: Calendar,
			sunrise: Calendar,
			sunset: Calendar
	): UpdateData {
		val nowInMillis = now.timeInMillis
		val sunriseInMillis = sunrise.timeInMillis
		val sunsetInMillis = sunset.timeInMillis
		return when {
			nowInMillis >= sunsetInMillis -> afterSunset(
					nowInMillis,
					sunriseInMillis,
					sunsetInMillis
			)
			nowInMillis >= sunriseInMillis -> betweenSunriseAndSunset(
					nowInMillis,
					sunriseInMillis,
					sunsetInMillis
			)
			else -> beforeSunrise(nowInMillis, sunriseInMillis, sunsetInMillis)
		}
	}

	companion object {
		private const val MIDNIGHT = 3
		private const val SUNSET = 2
		private const val SUNRISE = 0
		private const val NOON = 1
	}
}
