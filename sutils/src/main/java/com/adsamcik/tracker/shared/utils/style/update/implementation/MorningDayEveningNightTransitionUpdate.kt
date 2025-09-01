package com.adsamcik.tracker.shared.utils.style.update.implementation

import com.adsamcik.tracker.shared.base.logging.Asserts
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
import kotlin.math.abs
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
		Asserts.assertEqual(styleList.size, defaultColors.list.size)

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

		Asserts.assertMore(localUpdateData.duration, 0L) {
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
		val (duration, progress) = progressForInterval(midnight, sunrise, now)
	return UpdateData(
		fromColor = MIDNIGHT,
		toColor = SUNRISE,
		duration = duration,
		progress = progress
	)
    }

    private fun betweenSunsetAndMidnight(
	    now: ZonedDateTime,
	    sunset: ZonedDateTime,
	    midnight: ZonedDateTime
    ): UpdateData {
		val (duration, progress) = progressForInterval(sunset, midnight, now)
	return UpdateData(
		fromColor = SUNSET,
		toColor = MIDNIGHT,
		duration = duration,
		progress = progress
	)
    }

    private fun betweenNoonAndSunset(
	    now: ZonedDateTime,
	    noon: ZonedDateTime,
	    sunset: ZonedDateTime
    ): UpdateData {
		val (duration, progress) = progressForInterval(noon, sunset, now)
	return UpdateData(
		fromColor = NOON,
		toColor = SUNSET,
		duration = duration,
		progress = progress
	)
    }

    private fun betweenSunriseAndNoon(
	    now: ZonedDateTime,
	    sunrise: ZonedDateTime,
	    noon: ZonedDateTime
    ): UpdateData {
		val (duration, progress) = progressForInterval(sunrise, noon, now)
	return UpdateData(
		fromColor = SUNRISE,
		toColor = NOON,
		duration = duration,
		progress = progress
	)
    }

	private fun positiveModulo(value: Long, mod: Long): Long = ((value % mod) + mod) % mod

	private fun cycleDurationMillis(start: ZonedDateTime, end: ZonedDateTime): Long {
		val diff = Duration.between(start, end).toMillis()
		return positiveModulo(diff, Time.DAY_IN_MILLISECONDS)
	}

	private fun progressForInterval(start: ZonedDateTime, end: ZonedDateTime, now: ZonedDateTime): Pair<Long, Long> {
		val duration = cycleDurationMillis(start, end)
		val rawProgress = positiveModulo(Duration.between(start, now).toMillis(), Time.DAY_IN_MILLISECONDS)
		val progress = rawProgress.coerceIn(0L, duration)
		return duration to progress
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

		val sortedDayPartList = dayPartList.sortedBy { abs(it.distance) }
		val first = sortedDayPartList[0]

		return when (first.partOfDay) {
			PartOfDay.SUNRISE -> {
				// If we're before sunrise, we're between midnight -> sunrise, otherwise sunrise -> noon
				if (time.isBeforeOrEqual(first.time)) {
					betweenMidnightAndSunrise(
							time,
							requireNotNull(sunTimes.nadir),
							first.time
					)
				} else {
					betweenSunriseAndNoon(
							time,
							first.time,
							requireNotNull(sunTimes.noon)
					)
				}
			}
			PartOfDay.NOON -> {
				// If we're before noon, sunrise -> noon, otherwise noon -> sunset
				if (time.isBeforeOrEqual(first.time)) {
					betweenSunriseAndNoon(
							time,
							requireNotNull(sunTimes.rise),
							first.time
					)
				} else {
					betweenNoonAndSunset(
							time,
							first.time,
							requireNotNull(sunTimes.set)
					)
				}
			}
			PartOfDay.SUNSET -> {
				// If we're before sunset, noon -> sunset, otherwise sunset -> midnight
				if (time.isBeforeOrEqual(first.time)) {
					betweenNoonAndSunset(
							time,
							requireNotNull(sunTimes.noon),
							first.time
					)
				} else {
					betweenSunsetAndMidnight(
							time,
							first.time,
							requireNotNull(sunTimes.nadir)
					)
				}
			}
			PartOfDay.MIDNIGHT -> {
				// If we're before midnight, sunset -> midnight, otherwise midnight -> sunrise
				if (time.isBeforeOrEqual(first.time)) {
					betweenSunsetAndMidnight(
							time,
							requireNotNull(sunTimes.set),
							first.time
					)
				} else {
					betweenMidnightAndSunrise(
							time,
							first.time,
							requireNotNull(sunTimes.rise)
					)
				}
			}
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
