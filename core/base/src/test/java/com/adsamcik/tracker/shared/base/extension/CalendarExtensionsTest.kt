package com.adsamcik.tracker.shared.base.extension

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar
import java.util.Date
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CalendarExtensionsTest {

	// region roundToDate
	@Test
	fun `roundToDate sets all time fields to zero`() {
			val cal = Calendar.getInstance().apply {
				set(2024, Calendar.JUNE, 15, 14, 30, 45)
				set(Calendar.MILLISECOND, 500)
			}
			cal.roundToDate()
			cal.get(Calendar.HOUR_OF_DAY) shouldBe 0
			cal.get(Calendar.MINUTE) shouldBe 0
			cal.get(Calendar.SECOND) shouldBe 0
			cal.get(Calendar.MILLISECOND) shouldBe 0
		}

	@Test
	fun `roundToDate preserves date fields`() {
		val cal = Calendar.getInstance().apply {
			set(2024, Calendar.JUNE, 15, 14, 30, 45)
		}
		cal.roundToDate()
		cal.year shouldBe 2024
		cal.month shouldBe Calendar.JUNE
		cal.day shouldBe 15
	}

	@Test
	fun `roundToDate already at midnight is no-op`() {
		val cal = Calendar.getInstance().apply {
			set(2024, Calendar.JANUARY, 1, 0, 0, 0)
			set(Calendar.MILLISECOND, 0)
		}
		val before = cal.timeInMillis
		cal.roundToDate()
		cal.timeInMillis shouldBe before
	}
	// endregion

	// region toDate
	@Test
	fun `toDate returns new calendar rounded to date`() {
		val cal = Calendar.getInstance().apply {
			set(2024, Calendar.MARCH, 20, 10, 15, 30)
		}
		val dateOnly = cal.toDate()
		dateOnly.get(Calendar.HOUR_OF_DAY) shouldBe 0
		dateOnly.get(Calendar.MINUTE) shouldBe 0
		dateOnly.year shouldBe 2024
		dateOnly.month shouldBe Calendar.MARCH
		dateOnly.day shouldBe 20
	}

	@Test
	fun `toDate does not modify original calendar`() {
		val cal = Calendar.getInstance().apply {
			set(2024, Calendar.MARCH, 20, 10, 15, 30)
		}
		cal.toDate()
		cal.get(Calendar.HOUR_OF_DAY) shouldBe 10
	}
	// endregion

	// region toDateUTC
	@Test
	fun `toDateUTC returns UTC calendar rounded to date`() {
		val cal = Calendar.getInstance().apply {
			set(2024, Calendar.MARCH, 20, 10, 15, 30)
		}
		val utcDate = cal.toDateUTC()
		utcDate.timeZone shouldBe TimeZone.getTimeZone("UTC")
		utcDate.get(Calendar.HOUR_OF_DAY) shouldBe 0
		utcDate.get(Calendar.MINUTE) shouldBe 0
		utcDate.get(Calendar.SECOND) shouldBe 0
		utcDate.get(Calendar.MILLISECOND) shouldBe 0
	}

	@Test
	fun `toDateUTC does not modify original calendar`() {
		val cal = Calendar.getInstance()
		val originalTz = cal.timeZone
		cal.toDateUTC()
		cal.timeZone shouldBe originalTz
	}
	// endregion

	// region cloneCalendar
	@Test
	fun `cloneCalendar returns equal but distinct instance`() {
		val cal = Calendar.getInstance()
		val clone = cal.cloneCalendar()
		clone.timeInMillis shouldBe cal.timeInMillis
		(clone !== cal) shouldBe true
	}

	@Test
	fun `cloneCalendar modifying clone does not affect original`() {
		val cal = Calendar.getInstance()
		val originalMillis = cal.timeInMillis
		val clone = cal.cloneCalendar()
		clone.add(Calendar.HOUR_OF_DAY, 5)
		cal.timeInMillis shouldBe originalMillis
	}
	// endregion

	// region toTimeSinceMidnight
	@Test
	fun `toTimeSinceMidnight midnight returns 0`() {
		val cal = Calendar.getInstance().apply {
			set(Calendar.HOUR_OF_DAY, 0)
			set(Calendar.MINUTE, 0)
			set(Calendar.SECOND, 0)
		}
		cal.toTimeSinceMidnight() shouldBe 0L
	}

	@Test
	fun `toTimeSinceMidnight 1 hour after midnight returns 3600000`() {
		val cal = Calendar.getInstance().apply {
			set(Calendar.HOUR_OF_DAY, 1)
			set(Calendar.MINUTE, 0)
			set(Calendar.SECOND, 0)
		}
		cal.toTimeSinceMidnight() shouldBe 3_600_000L
	}

	@Test
	fun `toTimeSinceMidnight mixed hours minutes seconds`() {
		val cal = Calendar.getInstance().apply {
			set(Calendar.HOUR_OF_DAY, 2)
			set(Calendar.MINUTE, 30)
			set(Calendar.SECOND, 15)
		}
		val expected = 2L * 3_600_000L + 30L * 60_000L + 15L * 1_000L
		cal.toTimeSinceMidnight() shouldBe expected
	}

	@Test
	fun `toTimeSinceMidnight end of day`() {
		val cal = Calendar.getInstance().apply {
			set(Calendar.HOUR_OF_DAY, 23)
			set(Calendar.MINUTE, 59)
			set(Calendar.SECOND, 59)
		}
		val expected = 23L * 3_600_000L + 59L * 60_000L + 59L * 1_000L
		cal.toTimeSinceMidnight() shouldBe expected
	}
	// endregion

	// region setDateFrom
	@Test
	fun `setDateFrom copies year and day of year from source`() {
		val source = Calendar.getInstance().apply {
			set(2024, Calendar.DECEMBER, 25, 10, 0, 0)
		}
		val target = Calendar.getInstance().apply {
			set(2020, Calendar.JANUARY, 1, 15, 30, 0)
		}
		target.setDateFrom(source)
		target.year shouldBe 2024
		target.dayOfYear shouldBe source.dayOfYear
	}

	@Test
	fun `setDateFrom returns the same calendar instance`() {
		val source = Calendar.getInstance()
		val target = Calendar.getInstance()
		val result = target.setDateFrom(source)
		(result === target) shouldBe true
	}
	// endregion

	// region property extensions
	@Test
	fun `month returns correct month`() {
		val cal = Calendar.getInstance().apply {
			set(2024, Calendar.MARCH, 15)
		}
		cal.month shouldBe Calendar.MARCH
	}

	@Test
	fun `day returns day of month`() {
		val cal = Calendar.getInstance().apply {
			set(2024, Calendar.MARCH, 15)
		}
		cal.day shouldBe 15
	}

	@Test
	fun `year returns correct year`() {
		val cal = Calendar.getInstance().apply {
			set(2024, Calendar.MARCH, 15)
		}
		cal.year shouldBe 2024
	}

	@Test
	fun `dayOfYear for Jan 1 is 1`() {
		val cal = Calendar.getInstance().apply {
			set(2024, Calendar.JANUARY, 1)
		}
		cal.dayOfYear shouldBe 1
	}

	@Test
	fun `dayOfYear for Dec 31 in leap year is 366`() {
		val cal = Calendar.getInstance().apply {
			set(2024, Calendar.DECEMBER, 31)
		}
		cal.dayOfYear shouldBe 366
	}
	// endregion

	// region Date.toCalendar
	@Test
	fun `converts Date to Calendar with same time`() {
		val date = Date(1_000_000L)
		val cal = date.toCalendar()
		cal.timeInMillis shouldBe 1_000_000L
	}

	@Test
	fun `epoch Date converts correctly`() {
		val date = Date(0L)
		val cal = date.toCalendar()
		cal.timeInMillis shouldBe 0L
	}
	// endregion

	// region toZonedDateTime
	@Test
	fun `toZonedDateTime preserves the same instant`() {
		val cal = Calendar.getInstance().apply {
			timeInMillis = 1_718_400_000_000L
		}
		val zdt = cal.toZonedDateTime()
		zdt.toInstant().toEpochMilli() shouldBe cal.timeInMillis
	}

	@Test
	fun `toZonedDateTime result is not null`() {
		val zdt = Calendar.getInstance().toZonedDateTime()
		zdt shouldNotBe null
	}
	// endregion

	// region createCalendarWithDate
	@Test
	fun `createCalendarWithDate creates calendar with correct date`() {
		val cal = createCalendarWithDate(2024, Calendar.JUNE, 15)
		cal.year shouldBe 2024
		cal.month shouldBe Calendar.JUNE
		cal.day shouldBe 15
	}

	@Test
	fun `createCalendarWithDate creates calendar for January`() {
		val cal = createCalendarWithDate(2000, Calendar.JANUARY, 1)
		cal.year shouldBe 2000
		cal.month shouldBe Calendar.JANUARY
		cal.day shouldBe 1
	}
	// endregion

	// region createCalendarWithTime
	@Test
	fun `createCalendarWithTime creates calendar with correct time millis`() {
		val timeMs = 1_718_400_000_000L
		val cal = createCalendarWithTime(timeMs)
		cal.timeInMillis shouldBe timeMs
	}

	@Test
	fun `createCalendarWithTime creates calendar at epoch`() {
		val cal = createCalendarWithTime(0L)
		cal.timeInMillis shouldBe 0L
	}
	// endregion
}
