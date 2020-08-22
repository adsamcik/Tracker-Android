package com.adsamcik.tracker.shared.utils.style.update.implementation

import com.adsamcik.tracker.shared.base.R
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.utils.debug.assertEqual
import com.adsamcik.tracker.shared.utils.debug.assertMore
import com.adsamcik.tracker.shared.utils.debug.assertTrue
import com.adsamcik.tracker.shared.utils.style.SunSetRise
import com.adsamcik.tracker.shared.utils.style.update.abstraction.DayTimeStyleUpdate
import com.adsamcik.tracker.shared.utils.style.update.data.DefaultColorData
import com.adsamcik.tracker.shared.utils.style.update.data.DefaultColors
import com.adsamcik.tracker.shared.utils.style.update.data.UpdateData
import org.shredzone.commons.suncalc.SunTimes
import java.time.Duration
import java.time.ZonedDateTime

internal class MorningDayEveningNightTransitionUpdate : DayTimeStyleUpdate() {
	override val nameRes: Int = R.string.settings_color_update_mden_trans_title

	override val defaultColors: DefaultColors
		get() = DefaultColors(
				listOf(
						DefaultColorData(
								defaultColor = -32512,
								nameRes = R.string.settings_color_morning_title
						),
						DefaultColorData(
								defaultColor = -2031888,
								nameRes = R.string.settings_color_day_title
						),
						DefaultColorData(
								defaultColor = -13033421,
								nameRes = R.string.settings_color_evening_title
						),
						DefaultColorData(
								defaultColor = -16315596,
								nameRes = R.string.settings_color_night_title
						)
				)
		)

	override fun getUpdateData(
			time: ZonedDateTime,
			styleList: List<Int>,
			sunSetRise: SunSetRise
	): UpdateData {
		assertEqual(styleList.size, defaultColors.list.size)

		val localDate = time.toLocalDate()
		val sunData = sunSetRise.sunDataFor(time)
		val sunset = sunData.set?.with(localDate)
		val sunrise = sunData.rise?.with(localDate)

		if (sunData.isAlwaysUp || sunset == null) {
			return UpdateData(
					styleList[NOON],
					styleList[NOON],
					Time.DAY_IN_MILLISECONDS,
					0L
			)
		} else if (sunData.isAlwaysDown || sunrise == null) {
			return UpdateData(
					styleList[MIDNIGHT],
					styleList[MIDNIGHT],
					Time.DAY_IN_MILLISECONDS,
					0L
			)
		}

		val localUpdateData = calculateProgress(time, sunData)

		assertMore(localUpdateData.duration, 0) {
			"Duration was negative with sunrise of $sunrise, sunset of $sunset and current time $time"
		}

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
		assertTrue(now.isAfter(midnight))
		assertTrue(midnight.isBefore(sunrise))
		assertTrue(now.isBefore(sunrise))
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
		val midnightAdjusted: ZonedDateTime
		val sunsetAdjusted: ZonedDateTime
		if (sunset.isBefore(midnight)) {
			midnightAdjusted = midnight
			sunsetAdjusted = sunset
		} else {
			if (now.isBefore(midnight)) {
				midnightAdjusted = midnight
				sunsetAdjusted = sunset.minusDays(1)
			} else {
				midnightAdjusted = midnight.plusDays(1)
				sunsetAdjusted = sunset
			}
		}

		assertTrue(now.isAfter(sunsetAdjusted))
		assertTrue(sunsetAdjusted.isBefore(midnightAdjusted))
		assertTrue(now.isBefore(midnightAdjusted))

		return UpdateData(
				fromColor = SUNSET,
				toColor = MIDNIGHT,
				duration = Duration.between(sunsetAdjusted, midnightAdjusted).toMillis(),
				progress = Duration.between(sunsetAdjusted, now).toMillis()
		)
	}

	private fun betweenNoonAndSunset(
			now: ZonedDateTime,
			sunset: ZonedDateTime,
			noon: ZonedDateTime
	): UpdateData {
		assertTrue(now.isAfter(noon))
		assertTrue(noon.isBefore(sunset))
		assertTrue(now.isBefore(sunset))
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
		assertTrue(now.isAfter(sunrise))
		assertTrue(sunrise.isBefore(noon))
		assertTrue(now.isBefore(noon)) {
			"Now: ${now.toEpochSecond()} Noon: ${noon.toEpochSecond()} Sunrise: ${sunrise.toEpochSecond()}"
		}
		return UpdateData(
				fromColor = SUNRISE,
				toColor = NOON,
				duration = Duration.between(sunrise, noon).toMillis(),
				progress = Duration.between(sunrise, now).toMillis()
		)
	}

	private fun betweenSunriseAndSunset(
			now: ZonedDateTime,
			sunrise: ZonedDateTime,
			noon: ZonedDateTime,
			sunset: ZonedDateTime
	): UpdateData {
		return when {
			now.isAfter(noon) -> betweenNoonAndSunset(
					now,
					sunset,
					noon
			)
			now.isAfter(sunrise) -> betweenSunriseAndNoon(
					now,
					sunrise,
					noon
			)
			else -> throw IllegalStateException()
		}
	}

	private fun calculateProgress(
			now: ZonedDateTime,
			sunTimes: SunTimes
	): UpdateData {
		val localDate = now.toLocalDate()
		val sunrise = requireNotNull(sunTimes.rise).with(localDate)
		val sunset = requireNotNull(sunTimes.set).with(localDate)
		return if (now.isAfter(sunrise) && now.isBefore(sunset)) {
			val noon = requireNotNull(sunTimes.noon).with(localDate)
			betweenSunriseAndSunset(now, sunrise, noon, sunset)
		} else {
			val midnight = requireNotNull(sunTimes.nadir).with(localDate)
			if (now.isBefore(sunrise) && now.isAfter(midnight)) {
				betweenMidnightAndSunrise(now, sunrise, midnight)
			} else {
				betweenSunsetAndMidnight(now, sunset, midnight)
			}
		}
	}

	companion object {
		private const val MIDNIGHT = 3
		private const val SUNSET = 2
		private const val SUNRISE = 0
		private const val NOON = 1
	}
}
