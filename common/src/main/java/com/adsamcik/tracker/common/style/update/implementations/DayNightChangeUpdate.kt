package com.adsamcik.tracker.common.style.update.implementations

import com.adsamcik.tracker.common.R
import com.adsamcik.tracker.common.Time
import com.adsamcik.tracker.common.style.SunSetRise
import com.adsamcik.tracker.common.style.update.DayTimeStyleUpdate
import com.adsamcik.tracker.common.style.update.RequiredColorData
import com.adsamcik.tracker.common.style.update.RequiredColors
import com.adsamcik.tracker.common.style.update.StyleUpdate
import com.adsamcik.tracker.common.style.update.UpdateData
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

	override fun getUpdateData(styleList: List<Int>, sunSetRise: SunSetRise): UpdateData {
		val time = Calendar.getInstance()
		val sunset = sunSetRise.sunsetForToday()
		val sunrise = sunSetRise.sunriseForToday()

		val sunsetTime = sunset.timeInMillis
		val sunriseTime = sunrise.timeInMillis
		val nowTime = time.timeInMillis
		val dayDuration = sunsetTime - sunriseTime

		return when {
			(nowTime >= sunriseTime) and (nowTime < sunsetTime) -> {
				val progress = (nowTime - sunriseTime) / dayDuration

				UpdateData(
						styleList[DAY],
						styleList[NIGHT],
						dayDuration,
						progress
				)
			}
			nowTime >= sunsetTime -> {
				val nightDuration = Time.DAY_IN_MILLISECONDS - dayDuration
				val progress = (nowTime - sunsetTime) / nightDuration

				UpdateData(
						styleList[NIGHT],
						styleList[DAY],
						nightDuration,
						progress
				)
			}
			nowTime < sunriseTime -> {
				val nightDuration = Time.DAY_IN_MILLISECONDS - dayDuration
				val previousSunset = sunsetTime - Time.DAY_IN_MILLISECONDS
				val progress = (nowTime - previousSunset) / nightDuration
				UpdateData(
						styleList[NIGHT],
						styleList[DAY],
						nightDuration,
						progress
				)
			}
			else -> throw IllegalStateException()
		}
	}

	companion object {
		private const val DAY = 0
		private const val NIGHT = 1
	}

}
