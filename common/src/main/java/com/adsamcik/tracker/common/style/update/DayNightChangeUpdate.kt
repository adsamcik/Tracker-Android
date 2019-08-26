package com.adsamcik.tracker.common.style.update

import com.adsamcik.tracker.common.R
import com.adsamcik.tracker.common.Time
import com.adsamcik.tracker.common.style.SunSetRise
import java.util.*

class DayNightChangeUpdate : StyleUpdate {
	override val requiredColorData: RequiredColors
		get() = RequiredColors(
				listOf(
						RequiredColorData(
								defaultColor = 0xE0FEF0,
								nameRes = R.string.settings_color_day_title
						),
						RequiredColorData(
								defaultColor = 0x070b34,
								nameRes = R.string.settings_color_night_title
						)
				)
		)

	override fun getUpdateData(styleList: List<Int>, sunSetRise: SunSetRise): UpdateData {
		val time = Calendar.getInstance()
		val sunset = sunSetRise.sunsetForToday()
		val sunrise = sunSetRise.sunriseForToday()

		val sunsetTime = sunset.timeInMillis
		val sunriseTime = sunrise.timeInMillis
		val nowTime = time.timeInMillis
		val dayDuration = sunsetTime - sunriseTime

		return when {
			time.after(sunrise) and time.before(sunset) -> {
				val progress = (nowTime - sunriseTime) / dayDuration

				UpdateData(styleList[NIGHT], styleList[DAY], dayDuration, progress)
			}
			time.after(sunset) -> {
				val nightDuration = Time.DAY_IN_MILLISECONDS - dayDuration
				val progress = (nowTime - sunsetTime) / nightDuration

				UpdateData(styleList[DAY], styleList[NIGHT], nightDuration, progress)
			}
			time.before(sunrise) -> {
				val nightDuration = Time.DAY_IN_MILLISECONDS - dayDuration
				val previousSunset = sunsetTime - Time.DAY_IN_MILLISECONDS
				val progress = (nowTime - previousSunset) / nightDuration
				UpdateData(styleList[DAY], styleList[NIGHT], nightDuration, progress)
			}
			else -> throw IllegalStateException()
		}
	}

	companion object {
		private const val DAY = 0
		private const val NIGHT = 1
	}

}
