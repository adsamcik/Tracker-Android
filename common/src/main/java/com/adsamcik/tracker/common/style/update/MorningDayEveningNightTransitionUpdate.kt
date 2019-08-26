package com.adsamcik.tracker.common.style.update

import com.adsamcik.tracker.common.R
import com.adsamcik.tracker.common.Time
import com.adsamcik.tracker.common.extension.cloneCalendar
import com.adsamcik.tracker.common.style.SunSetRise
import java.util.*

class MorningDayEveningNightTransitionUpdate : StyleUpdate {
	override val requiredColorData: RequiredColors
		get() = RequiredColors(
				listOf(
						RequiredColorData(
								defaultColor = 0xff8100,
								nameRes = R.string.settings_color_morning_title
						),
						RequiredColorData(
								defaultColor = 0xE0FEF0,
								nameRes = R.string.settings_color_day_title
						),
						RequiredColorData(
								defaultColor = 0xB34D25,
								nameRes = R.string.settings_color_evening_title
						),
						RequiredColorData(
								defaultColor = 0x070b34,
								nameRes = R.string.settings_color_night_title
						)
				)
		)

	private val timer: Timer = Timer("ColorUpdate", true)

	override fun getUpdateData(
			styleList: List<Int>,
			sunSetRise: SunSetRise
	): UpdateData {
		require(styleList.size == requiredColorData.colorList.size)

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

	private fun betweenMidnightAndSunrise(
			now: Long,
			midnight: Long,
			sunrise: Long
	): UpdateData = UpdateData(
			fromColor = MIDNIGHT,
			toColor = SUNRISE,
			duration = sunrise - midnight,
			progress = now - midnight
	)

	private fun betweenSunsetAndMidnight(
			now: Long,
			sunset: Long,
			midnight: Long
	): UpdateData = UpdateData(
			fromColor = SUNSET,
			toColor = MIDNIGHT,
			duration = midnight - sunset,
			progress = now - sunset
	)

	private fun afterSunset(
			now: Calendar,
			sunrise: Calendar,
			sunset: Calendar
	): UpdateData {
//the max difference between days is approximately under 5 minutes, so it should be fine
		val tomorrowSunrise = sunrise.cloneCalendar().apply { add(Calendar.DAY_OF_YEAR, 1) }
		val midnight = tomorrowSunrise.timeInMillis - sunrise.timeInMillis
		return when {
			now.before(midnight) -> betweenSunsetAndMidnight(
					now.timeInMillis,
					sunset.timeInMillis,
					midnight
			)
			now.before(tomorrowSunrise) -> betweenMidnightAndSunrise(
					now.timeInMillis,
					midnight,
					tomorrowSunrise.timeInMillis
			)
			else -> throw IllegalStateException()
		}
	}

	private fun betweenNoonAndSunset(
			now: Long,
			noon: Long,
			sunset: Long
	): UpdateData = UpdateData(
			fromColor = NOON,
			toColor = SUNSET,
			duration = sunset - noon,
			progress = now - noon
	)

	private fun betweenSunriseAndNoon(
			now: Long,
			sunrise: Long,
			noon: Long
	): UpdateData = UpdateData(
			fromColor = SUNRISE,
			toColor = NOON,
			duration = noon - sunrise,
			progress = now - sunrise
	)

	private fun betweenSunriseAndSunset(
			now: Calendar,
			sunrise: Calendar,
			sunset: Calendar
	): UpdateData {
		val noon = (sunset.timeInMillis - sunrise.timeInMillis) / 2L + sunrise.timeInMillis
		return when {
			now.after(noon) -> betweenNoonAndSunset(now.timeInMillis, noon, sunset.timeInMillis)
			now.after(sunrise) -> betweenSunriseAndNoon(
					now.timeInMillis,
					sunrise.timeInMillis,
					noon
			)
			else -> throw IllegalStateException()
		}
	}

	private fun beforeSunrise(
			now: Calendar,
			sunrise: Calendar,
			sunset: Calendar
	): UpdateData {
		//the max difference between days is approximately under 5 minutes, so it should be fine
		val yesterdayApproxSunset = sunset.cloneCalendar().apply { add(Calendar.DAY_OF_YEAR, -1) }
		val midnight = (sunrise.timeInMillis - yesterdayApproxSunset.timeInMillis) / 2L + yesterdayApproxSunset.timeInMillis

		return when {
			now.after(midnight) -> betweenMidnightAndSunrise(
					now.timeInMillis,
					midnight,
					sunrise.timeInMillis
			)
			now.after(yesterdayApproxSunset) -> betweenSunsetAndMidnight(
					now.timeInMillis,
					sunset.timeInMillis,
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
		return when {
			now.after(sunset) -> afterSunset(now, sunrise, sunset)
			now.after(sunrise) -> betweenSunriseAndSunset(now, sunrise, sunset)
			else -> beforeSunrise(now, sunrise, sunset)
		}
	}

	companion object {
		private const val MIDNIGHT = 3
		private const val SUNSET = 2
		private const val SUNRISE = 0
		private const val NOON = 1
	}
}
