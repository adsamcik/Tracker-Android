package com.adsamcik.tracker.shared.utils.style.update.implementation

import com.adsamcik.tracker.shared.base.R
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.utils.style.SunSetRise
import com.adsamcik.tracker.shared.utils.style.update.abstraction.DayTimeStyleUpdate
import com.adsamcik.tracker.shared.utils.style.update.data.RequiredColorData
import com.adsamcik.tracker.shared.utils.style.update.data.RequiredColors
import com.adsamcik.tracker.shared.utils.style.update.data.UpdateData
import java.util.*

internal class DayNightChangeUpdate : DayTimeStyleUpdate() {
	override val nameRes: Int = R.string.settings_color_update_dn_switch_title

	override val requiredColorData: RequiredColors
		get() = RequiredColors(
				listOf(
						RequiredColorData(
								defaultColor = -2031888,
								nameRes = R.string.settings_color_day_title
						),
						RequiredColorData(
								defaultColor = -16315596,
								nameRes = R.string.settings_color_night_title
						)
				)
		)

	private fun calculateDay(
			nowTime: Long,
			sunriseTime: Long,
			sunsetTime: Long,
			styleList: List<Int>
	): UpdateData {
		val nightDuration = sunriseTime - sunsetTime
		val dayDuration = Time.DAY_IN_MILLISECONDS - nightDuration
		val progress = (dayDuration - (sunsetTime - nowTime)) / dayDuration

		return UpdateData(
				styleList[DAY],
				styleList[NIGHT],
				dayDuration,
				progress
		)
	}

	private fun calculateNight(
			nowTime: Long,
			sunriseTime: Long,
			sunsetTime: Long,
			styleList: List<Int>
	): UpdateData {
		val dayDuration = sunsetTime - sunriseTime
		val nightDuration = Time.DAY_IN_MILLISECONDS - dayDuration
		val progress = (nightDuration - (sunriseTime - nowTime)) / nightDuration

		return UpdateData(
				styleList[NIGHT],
				styleList[DAY],
				nightDuration,
				progress
		)
	}

	@Suppress("ComplexMethod")
	override fun getUpdateData(styleList: List<Int>, sunSetRise: SunSetRise): UpdateData {
		val time = Calendar.getInstance()
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

		val nowTime = time.timeInMillis
		val sunsetTime = sunset.time
		val sunriseTime = sunrise.time

		return if (sunriseTime < sunsetTime) {
			calculateNight(nowTime, sunriseTime, sunsetTime, styleList)
		} else {
			calculateDay(nowTime, sunriseTime, sunsetTime, styleList)
		}
	}

	companion object {
		private const val DAY = 0
		private const val NIGHT = 1
	}

}
