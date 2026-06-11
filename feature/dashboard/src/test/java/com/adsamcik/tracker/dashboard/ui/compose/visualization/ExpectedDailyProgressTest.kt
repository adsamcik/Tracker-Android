package com.adsamcik.tracker.dashboard.ui.compose.visualization

import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Test

class ExpectedDailyProgressTest {

	@Test
	fun beforeWakeTime_returnsZero() {
		val result = expectedDailyProgress(now = LocalTime.of(5, 30))
		assertEquals(0f, result, 0.001f)
	}

	@Test
	fun atWakeStart_returnsZero() {
		val result = expectedDailyProgress(now = LocalTime.of(6, 0))
		assertEquals(0f, result, 0.001f)
	}

	@Test
	fun midday_returnsHalf() {
		val result = expectedDailyProgress(now = LocalTime.of(14, 0))
		assertEquals(0.5f, result, 0.001f)
	}

	@Test
	fun atWakeEnd_returnsOne() {
		val result = expectedDailyProgress(now = LocalTime.of(22, 0))
		assertEquals(1f, result, 0.001f)
	}

	@Test
	fun afterWakeEnd_returnsOne() {
		val result = expectedDailyProgress(now = LocalTime.of(23, 30))
		assertEquals(1f, result, 0.001f)
	}

	@Test
	fun quarterDay_returnsQuarter() {
		// 6:00 to 22:00 = 16 hours. Quarter = 4 hours => 10:00
		val result = expectedDailyProgress(now = LocalTime.of(10, 0))
		assertEquals(0.25f, result, 0.001f)
	}

	@Test
	fun customWakeRange_returnsCorrectFraction() {
		val result = expectedDailyProgress(
			now = LocalTime.of(12, 0),
			wakeStart = LocalTime.of(8, 0),
			wakeEnd = LocalTime.of(20, 0),
		)
		// 12 - 8 = 4 hours out of 12 hours total => 0.333
		assertEquals(0.333f, result, 0.001f)
	}

	@Test
	fun invalidWakeRange_returnsZero() {
		// wakeStart after wakeEnd is invalid
		val result = expectedDailyProgress(
			now = LocalTime.of(12, 0),
			wakeStart = LocalTime.of(20, 0),
			wakeEnd = LocalTime.of(8, 0),
		)
		assertEquals(0f, result, 0.001f)
	}
}
