package com.adsamcik.tracker.shared.utils.style.update.implementation

import com.adsamcik.tracker.shared.base.R
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.extension.toZonedDateTime
import com.adsamcik.tracker.shared.utils.style.SunSetRise
import com.adsamcik.tracker.shared.utils.style.update.abstraction.DayTimeStyleUpdate
import com.adsamcik.tracker.shared.utils.style.update.data.RequiredColorData
import com.adsamcik.tracker.shared.utils.style.update.data.RequiredColors
import com.adsamcik.tracker.shared.utils.style.update.data.UpdateData
import org.shredzone.commons.suncalc.SunTimes
import java.time.Duration
import java.time.ZonedDateTime

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

		val time = Time.now.toZonedDateTime()

		val sunData = sunSetRise.sunDataFor(time)
		val sunset = sunData.set
		val sunrise = sunData.rise

		if (sunData.isAlwaysUp || sunset == null) {
			return UpdateData(styleList[NOON], styleList[NOON], Long.MAX_VALUE, 0L)
		} else if (sunData.isAlwaysDown || sunrise == null) {
			return UpdateData(styleList[MIDNIGHT], styleList[MIDNIGHT], Long.MAX_VALUE, 0L)
		}

		val localUpdateData = calculateProgress(time, sunData)

		return UpdateData(
				styleList[localUpdateData.fromColor],
				styleList[localUpdateData.toColor],
				localUpdateData.duration,
				localUpdateData.progress
		)
	}

	private fun betweenMidnightAndSunrise(
			now: ZonedDateTime,
			sunrise: ZonedDateTime,
			midnight: ZonedDateTime
	): UpdateData {
		require(now.isAfter(midnight))
		require(sunrise.isAfter(midnight))
		require(now.isBefore(sunrise))
		return UpdateData(
				fromColor = MIDNIGHT,
				toColor = SUNRISE,
				duration = Duration.between(midnight, sunrise).toMillis(),
				progress = Duration.between(midnight, now).toMillis()
		)
	}

	private fun betweenSunsetAndMidnight(
			now: ZonedDateTime,
			sunset: ZonedDateTime,
			midnight: ZonedDateTime
	): UpdateData {
		require(now.isAfter(sunset))
		require(midnight.isAfter(sunset))
		require(now.isBefore(midnight))
		return UpdateData(
				fromColor = SUNSET,
				toColor = MIDNIGHT,
				duration = Duration.between(sunset, midnight).toMillis(),
				progress = Duration.between(sunset, now).toMillis()
		)
	}

	private fun betweenSunsetAndSunrise(
			now: ZonedDateTime,
			sunTimes: SunTimes
	): UpdateData {
		val sunrise = requireNotNull(sunTimes.rise)
		val sunset = requireNotNull(sunTimes.set)
		val approximateLastSunset = sunset.minusDays(1)
		val dstToSunrise = Duration.between(now, sunrise)
		val dstToSunset = Duration.between(approximateLastSunset, now)

		require(!dstToSunrise.isNegative) { "Sunrise is in the past $dstToSunrise (sunrise: $sunrise, now: $now)" }
		require(!dstToSunset.isNegative) {
			"Sunset is in the past $dstToSunset (sunset: $sunset, lastSunset: $approximateLastSunset, now: $now)"
		}

		return if (dstToSunrise < dstToSunset) {
			beforeSunrise(now, sunTimes)
		} else {
			afterSunset(now, sunTimes)
		}
	}

	private fun afterSunset(
			now: ZonedDateTime,
			sunTimes: SunTimes
	): UpdateData {
//the max difference between days is approximately under 5 minutes, so it should be fine
		val tomorrowSunrise = requireNotNull(sunTimes.rise)
		val midnight = requireNotNull(sunTimes.nadir)
		return when {
			now < midnight -> betweenSunsetAndMidnight(
					now,
					requireNotNull(sunTimes.set),
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
			now: ZonedDateTime,
			sunset: ZonedDateTime,
			noon: ZonedDateTime
	): UpdateData {
		require(now.isAfter(noon))
		require(sunset.isAfter(noon))
		require(now.isBefore(sunset))
		return UpdateData(
				fromColor = NOON,
				toColor = SUNSET,
				duration = Duration.between(noon, sunset).toMillis(),
				progress = Duration.between(noon, now).toMillis()
		)
	}

	private fun betweenSunriseAndNoon(
			now: ZonedDateTime,
			sunrise: ZonedDateTime,
			noon: ZonedDateTime
	): UpdateData {
		require(now.isAfter(sunrise))
		require(noon.isAfter(sunrise))
		require(now.isBefore(noon))
		return UpdateData(
				fromColor = SUNRISE,
				toColor = NOON,
				duration = Duration.between(sunrise, noon).toMillis(),
				progress = Duration.between(sunrise, now).toMillis()
		)
	}

	private fun betweenSunriseAndSunset(
			now: ZonedDateTime,
			sunTimes: SunTimes
	): UpdateData {
		val sunrise = requireNotNull(sunTimes.rise)
		val noon = requireNotNull(sunTimes.noon)
		val sunset = requireNotNull(sunTimes.set)
		return when {
			noon.isAfter(sunset) -> betweenNoonAndSunset(
					now,
					sunset,
					noon.minusDays(1)
			)
			sunrise.isAfter(noon) -> betweenSunriseAndNoon(
					now,
					sunrise.minusDays(1),
					noon
			)
			else -> throw IllegalStateException()
		}
	}

	private fun beforeSunrise(
			now: ZonedDateTime,
			sunTimes: SunTimes
	): UpdateData {
		//the max difference between days is approximately under 5 minutes, so it should be fine
		val yesterdayApproxSunset = requireNotNull(sunTimes.set).minusDays(1)
		val midnight = requireNotNull(sunTimes.nadir);

		return when {
			now >= midnight -> betweenMidnightAndSunrise(
					now,
					requireNotNull(sunTimes.rise),
					midnight
			)
			now >= yesterdayApproxSunset -> betweenSunsetAndMidnight(
					now,
					yesterdayApproxSunset,
					midnight
			)
			else -> throw IllegalStateException()
		}
	}

	private fun calculateProgress(
			now: ZonedDateTime,
			sunTimes: SunTimes
	): UpdateData {
		return if (sunTimes.set?.isAfter(sunTimes.rise) == false) {
			betweenSunriseAndSunset(now, sunTimes)
		} else {
			betweenSunsetAndSunrise(now, sunTimes)
		}
	}

	companion object {
		private const val MIDNIGHT = 3
		private const val SUNSET = 2
		private const val SUNRISE = 0
		private const val NOON = 1
	}
}
