package com.adsamcik.tracker.common.style.update.implementation

import com.adsamcik.tracker.common.R
import com.adsamcik.tracker.common.Time
import com.adsamcik.tracker.common.extension.setDateFrom
import com.adsamcik.tracker.common.extension.toCalendar
import com.adsamcik.tracker.common.style.SunSetRise
import com.adsamcik.tracker.common.style.update.abstraction.DayTimeStyleUpdate
import com.adsamcik.tracker.common.style.update.data.RequiredColorData
import com.adsamcik.tracker.common.style.update.data.RequiredColors
import com.adsamcik.tracker.common.style.update.data.UpdateData
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

		val sunsetTime = sunset.toCalendar().setDateFrom(time).timeInMillis
		val sunriseTime = sunrise.toCalendar().setDateFrom(time).timeInMillis

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
