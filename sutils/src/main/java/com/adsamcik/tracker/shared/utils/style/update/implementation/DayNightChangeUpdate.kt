package com.adsamcik.tracker.shared.utils.style.update.implementation

import com.adsamcik.tracker.shared.base.R
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.extension.toZonedDateTime
import com.adsamcik.tracker.shared.utils.style.SunSetRise
import com.adsamcik.tracker.shared.utils.style.update.abstraction.DayTimeStyleUpdate
import com.adsamcik.tracker.shared.utils.style.update.data.DefaultColorData
import com.adsamcik.tracker.shared.utils.style.update.data.DefaultColors
import com.adsamcik.tracker.shared.utils.style.update.data.UpdateData
import java.time.Duration
import java.time.ZonedDateTime

internal class DayNightChangeUpdate : DayTimeStyleUpdate() {
	override val nameRes: Int = R.string.settings_color_update_dn_switch_title

	override val defaultColors: DefaultColors
		get() = DefaultColors(
				listOf(
						DefaultColorData(
								defaultColor = -2031888,
								nameRes = R.string.settings_color_day_title
						),
						DefaultColorData(
								defaultColor = -16315596,
								nameRes = R.string.settings_color_night_title
						)
				)
		)

	private fun calculateDay(
			nowTime: ZonedDateTime,
			sunriseTime: ZonedDateTime,
			sunsetTime: ZonedDateTime,
			styleList: List<Int>
	): UpdateData {
		val nightDuration = Duration.between(sunriseTime, sunsetTime)
		val dayDuration = Duration.ofDays(1L) - nightDuration
		val progress = (dayDuration - (Duration.between(
				sunsetTime,
				nowTime
		))).toMillis() / dayDuration.toMillis()

		return UpdateData(
				styleList[DAY],
				styleList[NIGHT],
				dayDuration.toMillis(),
				progress
		)
	}

	private fun calculateNight(
			nowTime: ZonedDateTime,
			sunriseTime: ZonedDateTime,
			sunsetTime: ZonedDateTime,
			styleList: List<Int>
	): UpdateData {
		val dayDuration = Duration.between(sunsetTime, sunriseTime)
		val nightDuration = Duration.ofDays(1L) - dayDuration
		val progress = (nightDuration - Duration.between(
				sunsetTime,
				nowTime
		)).toMillis() / nightDuration.toMillis()

		return UpdateData(
				styleList[NIGHT],
				styleList[DAY],
				nightDuration.toMillis(),
				progress
		)
	}

	@Suppress("ComplexMethod")
	override fun getUpdateData(styleList: List<Int>, sunSetRise: SunSetRise): UpdateData {
		val time = Time.now.toZonedDateTime()
		val sunData = sunSetRise.sunDataFor(time)
		val sunset = sunData.set
		val sunrise = sunData.rise

		if (sunData.isAlwaysUp) {
			return UpdateData(styleList[DAY], styleList[DAY], Long.MAX_VALUE, 0L)
		} else if (sunData.isAlwaysDown) {
			return UpdateData(styleList[NIGHT], styleList[NIGHT], Long.MAX_VALUE, 0L)
		}

		require(sunset != null)
		require(sunrise != null)

		return if (sunrise < sunset) {
			calculateNight(time, sunrise, sunset, styleList)
		} else {
			calculateDay(time, sunrise, sunset, styleList)
		}
	}

	companion object {
		private const val DAY = 0
		private const val NIGHT = 1
	}

}
