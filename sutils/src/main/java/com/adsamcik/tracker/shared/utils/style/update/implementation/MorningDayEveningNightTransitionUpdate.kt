package com.adsamcik.tracker.shared.utils.style.update.implementation

import com.adsamcik.tracker.logger.assertEqual
import com.adsamcik.tracker.logger.assertMore
import com.adsamcik.tracker.logger.assertTrue
import com.adsamcik.tracker.shared.base.R
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.extension.isAfterOrEqual
import com.adsamcik.tracker.shared.base.extension.isBeforeOrEqual
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

		val localUpdateData = calculateProgress(time, sunData, sunSetRise)

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
			midnight: ZonedDateTime,
			sunrise: ZonedDateTime
	): UpdateData {
		assertTrue(now.isAfterOrEqual(midnight))
		assertTrue(midnight.isBeforeOrEqual(sunrise))
		assertTrue(now.isBeforeOrEqual(sunrise))
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
		assertTrue(now.isAfterOrEqual(sunset)) { "now $now, sunset $sunset, midnight $midnight" }
		assertTrue(sunset.isBeforeOrEqual(midnight)) { "now $now, sunset $sunset, midnight $midnight" }
		assertTrue(now.isBeforeOrEqual(midnight)) { "now $now, sunset $sunset, midnight $midnight" }

		return UpdateData(
				fromColor = SUNSET,
				toColor = MIDNIGHT,
				duration = Duration.between(sunset, midnight).toMillis(),
				progress = Duration.between(sunset, now).toMillis()
		)
	}

	private fun betweenNoonAndSunset(
			now: ZonedDateTime,
			noon: ZonedDateTime,
			sunset: ZonedDateTime
	): UpdateData {
		assertTrue(now.isAfterOrEqual(noon))
		assertTrue(noon.isBeforeOrEqual(sunset))
		assertTrue(now.isBeforeOrEqual(sunset))
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
		assertTrue(now.isAfterOrEqual(sunrise))
		assertTrue(sunrise.isBeforeOrEqual(noon))
		assertTrue(now.isBeforeOrEqual(noon)) {
			"Now: $now Noon: $noon Sunrise: $sunrise"
		}
		return UpdateData(
				fromColor = SUNRISE,
				toColor = NOON,
				duration = Duration.between(sunrise, noon).toMillis(),
				progress = Duration.between(sunrise, now).toMillis()
		)
	}

	private fun calculateProgress(
			time: ZonedDateTime,
			sunTimes: SunTimes,
			sunSetRise: SunSetRise
	): UpdateData {
		val sunrise = requireNotNull(sunTimes.rise)
		val sunriseDist = Duration.between(time, sunrise).toMillis()
		val sunset = requireNotNull(sunTimes.set)
		val sunsetDist = Duration.between(time, sunset).toMillis()
		val noon = requireNotNull(sunTimes.noon)
		val noonDist = Duration.between(time, noon).toMillis()
		val midnight = requireNotNull(sunTimes.nadir)
		val midnightDist = Duration.between(time, midnight).toMillis()
		val dayPartList = listOf(
				DayPartData(sunrise, sunriseDist, PartOfDay.SUNRISE),
				DayPartData(sunset, sunsetDist, PartOfDay.SUNSET),
				DayPartData(noon, noonDist, PartOfDay.NOON),
				DayPartData(midnight, midnightDist, PartOfDay.MIDNIGHT)
		)

		val sortedDayPartList = dayPartList.sortedBy { it.distance }
		val first = sortedDayPartList[0]

		val historicSunTimes = sunSetRise.sunDataFor(first.time.minusDays(1))

		return when (first.partOfDay) {
			PartOfDay.SUNRISE -> betweenMidnightAndSunrise(
					time,
					requireNotNull(historicSunTimes.nadir),
					first.time
			)
			PartOfDay.NOON -> betweenSunriseAndNoon(
					time,
					requireNotNull(historicSunTimes.rise),
					first.time
			)
			PartOfDay.SUNSET -> betweenNoonAndSunset(
					time,
					requireNotNull(historicSunTimes.noon),
					first.time
			)
			PartOfDay.MIDNIGHT -> betweenSunsetAndMidnight(
					time,
					requireNotNull(historicSunTimes.set),
					first.time
			)
		}
	}

	private data class DayPartData(
			val time: ZonedDateTime,
			val distance: Long,
			val partOfDay: PartOfDay
	)

	private enum class PartOfDay {
		MIDNIGHT,
		SUNRISE,
		NOON,
		SUNSET
	}

	companion object {
		private const val MIDNIGHT = 3
		private const val SUNSET = 2
		private const val SUNRISE = 0
		private const val NOON = 1
	}
}
