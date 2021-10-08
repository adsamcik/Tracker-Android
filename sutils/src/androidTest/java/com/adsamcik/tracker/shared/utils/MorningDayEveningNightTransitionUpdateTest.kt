package com.adsamcik.tracker.shared.utils

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.adsamcik.tracker.logger.assertEqual
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.utils.style.SunSetRise
import com.adsamcik.tracker.shared.utils.style.update.data.StyleConfigData
import com.adsamcik.tracker.shared.utils.style.update.data.UpdateData
import com.adsamcik.tracker.shared.utils.style.update.implementation.MorningDayEveningNightTransitionUpdate
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZonedDateTime

@RunWith(AndroidJUnit4::class)
class MorningDayEveningNightTransitionUpdateTest {

	private lateinit var context: Context
	private lateinit var sunSetRise: SunSetRise

	private lateinit var colorList: List<Int>

	@Before
	fun init() {
		context = InstrumentationRegistry.getInstrumentation().targetContext
		sunSetRise = SunSetRise().apply { initialize(context) }
		colorList = MorningDayEveningNightTransitionUpdate().defaultColors.list.map { it.defaultColor }
	}

	private fun update(time: ZonedDateTime): UpdateData {
		val update = MorningDayEveningNightTransitionUpdate()
		update.onEnable(context, StyleConfigData(colorList) {})
		val data = update.getUpdateData(time, colorList, sunSetRise)
		update.onDisable(context)
		return data
	}

	@Test
	fun morningTest() {
		val now = Time.now.toZonedDateTime()
		val sunData = sunSetRise.sunDataFor(now)
		val sunrise = requireNotNull(sunData.rise)
		val afterSunrise = sunrise.plusHours(1)
		val data = update(afterSunrise)

		assertEqual(colorList[0], data.fromColor)
		assertEqual(colorList[1], data.toColor)
	}

	@Test
	fun beforeSunsetTest() {
		val now = Time.now.toZonedDateTime()
		val sunData = sunSetRise.sunDataFor(now)
		val sunrise = requireNotNull(sunData.noon)
		val afterSunrise = sunrise.plusHours(1)
		val data = update(afterSunrise)

		assertEqual(colorList[1], data.fromColor)
		assertEqual(colorList[2], data.toColor)
	}

	@Test
	fun afterSunsetTest() {
		val now = Time.now.toZonedDateTime()
		val sunData = sunSetRise.sunDataFor(now)
		val sunset = requireNotNull(sunData.set)
		val afterSunset = sunset.plusHours(1)
		val data = update(afterSunset)

		assertEqual(colorList[2], data.fromColor)
		assertEqual(colorList[3], data.toColor)
	}

	@Test
	fun beforeMorningTest() {
		val now = Time.now.toZonedDateTime()
		val sunData = sunSetRise.sunDataFor(now)
		val sunrise = requireNotNull(sunData.rise)
		val afterSunrise = sunrise.minusHours(1)
		val data = update(afterSunrise)

		assertEqual(colorList[3], data.fromColor)
		assertEqual(colorList[0], data.toColor)
	}

	@Test
	fun beforeMidnight() {
		val now = Time.now.toZonedDateTime()
		val sunData = sunSetRise.sunDataFor(now)
		val sunrise = requireNotNull(sunData.nadir)
		val beforeMidnight = sunrise.minusMinutes(15)
		val data = update(beforeMidnight)

		assertEqual(colorList[2], data.fromColor)
		assertEqual(colorList[3], data.toColor)
	}
}

