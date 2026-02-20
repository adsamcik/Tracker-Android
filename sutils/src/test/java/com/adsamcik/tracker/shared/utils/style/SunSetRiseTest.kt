package com.adsamcik.tracker.shared.utils.style

import com.adsamcik.tracker.shared.base.data.BaseLocation
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.Duration
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.math.abs

/**
 * Tests for [SunSetRise] sun calculation logic.
 *
 * Uses the underlying [SunTimes] library directly via [SunSetRise.sunDataFor]
 * to verify sunrise/sunset calculations for known locations and dates.
 *
 * Note: [SunTimes] computes the *next* rise/set from the given datetime.
 * We use midnight UTC as the base time so both rise and set fall on the same day
 * for most non-polar locations.
 */
class SunSetRiseTest {

	private fun createSunSetRise(lat: Double, lon: Double): SunSetRise {
		return SunSetRise().also {
			val locationField = SunSetRise::class.java.getDeclaredField("location")
			locationField.isAccessible = true
			locationField.set(it, BaseLocation(lat, lon))
		}
	}

	private fun utcDateTime(year: Int, month: Int, day: Int, hour: Int = 0): ZonedDateTime {
		return ZonedDateTime.of(year, month, day, hour, 0, 0, 0, ZoneOffset.UTC)
	}

	@Nested
	inner class `known location sunrise and sunset` {

		@ParameterizedTest(name = "lat={0}, lon={1} on {2}-{3}-{4}")
		@CsvSource(
			// London (51.5, -0.1) - June solstice 2024
			"51.5, -0.1, 2024, 6, 21",
			// New York (40.7, -74.0) - March equinox 2024
			"40.7, -74.0, 2024, 3, 20",
			// Tokyo (35.7, 139.7) - September equinox 2024
			"35.7, 139.7, 2024, 9, 22",
		)
		fun `sunrise and sunset are both returned for mid-latitude locations`(
			lat: Double,
			lon: Double,
			year: Int,
			month: Int,
			day: Int,
		) {
			val sunSetRise = createSunSetRise(lat, lon)
			val dateTime = utcDateTime(year, month, day)
			val sunData = sunSetRise.sunDataFor(dateTime)

			sunData.rise.shouldNotBeNull()
			sunData.set.shouldNotBeNull()
		}

		@Test
		fun `London June 21 has long daylight`() {
			val sunSetRise = createSunSetRise(51.5, -0.1)
			val dateTime = utcDateTime(2024, 6, 21)
			val sunData = sunSetRise.sunDataFor(dateTime)

			val rise = sunData.rise.shouldNotBeNull()
			val set = sunData.set.shouldNotBeNull()
			val dayMinutes = Duration.between(rise, set).toMinutes()

			// London summer solstice: ~16-17 hours of daylight
			dayMinutes.shouldBeGreaterThan(900L)
		}
	}

	@Nested
	inner class `equator locations` {

		@ParameterizedTest(name = "month={0}")
		@CsvSource("1", "3", "6", "9", "12")
		fun `sunrise near 6am and sunset near 6pm UTC year-round at equator`(month: Int) {
			val sunSetRise = createSunSetRise(0.0, 0.0)
			val dateTime = utcDateTime(2024, month, 15)
			val sunData = sunSetRise.sunDataFor(dateTime)

			val rise = sunData.rise.shouldNotBeNull()
			val set = sunData.set.shouldNotBeNull()

			// At (0,0) sunrise ~6:00 UTC, sunset ~18:00 UTC
			val riseHour = rise.withZoneSameInstant(ZoneOffset.UTC).hour
			abs(riseHour - 6).toDouble().shouldBeLessThan(2.0)

			val setHour = set.withZoneSameInstant(ZoneOffset.UTC).hour
			abs(setHour - 18).toDouble().shouldBeLessThan(2.0)
		}
	}

	@Nested
	inner class `polar regions` {

		@Test
		fun `high latitude in summer - next sunset is not on same day`() {
			// 80°N in June — polar day: sun doesn't set on this day
			val sunSetRise = createSunSetRise(80.0, 0.0)
			val dateTime = utcDateTime(2024, 6, 21)
			val sunData = sunSetRise.sunDataFor(dateTime)

			// Library finds the next sunset (weeks/months away) proving polar day
			val set = sunData.set
			if (set != null) {
				val daysUntilSet = Duration.between(dateTime, set).toDays()
				assert(daysUntilSet > 7) {
					"At 80°N in June, next sunset should be weeks away, was $daysUntilSet days"
				}
			}
			// set==null or isAlwaysUp also acceptable
		}

		@Test
		fun `high latitude in winter - next sunrise is not on same day`() {
			// 80°N in December — polar night: sun doesn't rise on this day
			val sunSetRise = createSunSetRise(80.0, 0.0)
			val dateTime = utcDateTime(2024, 12, 21)
			val sunData = sunSetRise.sunDataFor(dateTime)

			val rise = sunData.rise
			if (rise != null) {
				val daysUntilRise = Duration.between(dateTime, rise).toDays()
				assert(daysUntilRise > 7) {
					"At 80°N in Dec, next sunrise should be weeks away, was $daysUntilRise days"
				}
			}
		}

		@Test
		fun `south pole in December - next sunset is far in future`() {
			val sunSetRise = createSunSetRise(-85.0, 0.0)
			val dateTime = utcDateTime(2024, 12, 21)
			val sunData = sunSetRise.sunDataFor(dateTime)

			val set = sunData.set
			if (set != null) {
				val daysUntilSet = Duration.between(dateTime, set).toDays()
				assert(daysUntilSet > 7) {
					"Near south pole in Dec, next sunset should be weeks away, was $daysUntilSet days"
				}
			}
		}

		@Test
		fun `south pole in June - next sunrise is far in future`() {
			val sunSetRise = createSunSetRise(-85.0, 0.0)
			val dateTime = utcDateTime(2024, 6, 21)
			val sunData = sunSetRise.sunDataFor(dateTime)

			val rise = sunData.rise
			if (rise != null) {
				val daysUntilRise = Duration.between(dateTime, rise).toDays()
				assert(daysUntilRise > 7) {
					"Near south pole in Jun, next sunrise should be weeks away, was $daysUntilRise days"
				}
			}
		}
	}

	@Nested
	inner class `equinox dates` {

		@ParameterizedTest(name = "lat={0}: day length ~12h on equinox")
		@CsvSource(
			"0.0",    // equator
			"30.0",   // mid-latitude
			"45.0",   // mid-northern
			"-30.0",  // southern hemisphere
		)
		fun `day length approximately 12 hours on March equinox`(lat: Double) {
			val sunSetRise = createSunSetRise(lat, 0.0)
			val dateTime = utcDateTime(2024, 3, 20)
			val sunData = sunSetRise.sunDataFor(dateTime)

			val rise = sunData.rise.shouldNotBeNull()
			val set = sunData.set.shouldNotBeNull()

			val dayLengthMinutes = Duration.between(rise, set).toMinutes()
			// With VISUAL twilight, day appears slightly >12h; allow ±90 min tolerance
			abs(dayLengthMinutes - 720.0).shouldBeLessThan(90.0)
		}
	}

	@Nested
	inner class `edge cases` {

		@Test
		fun `international date line - positive longitude 179`() {
			val sunSetRise = createSunSetRise(0.0, 179.0)
			val dateTime = utcDateTime(2024, 6, 15)
			val sunData = sunSetRise.sunDataFor(dateTime)

			sunData.rise.shouldNotBeNull()
			sunData.set.shouldNotBeNull()
		}

		@Test
		fun `international date line - negative longitude -179`() {
			val sunSetRise = createSunSetRise(0.0, -179.0)
			val dateTime = utcDateTime(2024, 6, 15)
			val sunData = sunSetRise.sunDataFor(dateTime)

			sunData.rise.shouldNotBeNull()
			sunData.set.shouldNotBeNull()
		}

		@Test
		fun `Arctic circle boundary in summer does not crash`() {
			val sunSetRise = createSunSetRise(66.5, 25.0)
			val dateTime = utcDateTime(2024, 6, 21)
			// Just verify no exception
			sunSetRise.sunDataFor(dateTime)
		}

		@Test
		fun `sunriseFor and sunsetFor with location return non-null`() {
			val sunSetRise = createSunSetRise(51.5, -0.1) // London
			val dateTime = utcDateTime(2024, 6, 15)

			sunSetRise.sunriseFor(dateTime).shouldNotBeNull()
			sunSetRise.sunsetFor(dateTime).shouldNotBeNull()
		}

		@Test
		fun `no location returns default hours`() {
			val sunSetRise = SunSetRise()
			val dateTime = utcDateTime(2024, 6, 15, 10)

			val sunrise = sunSetRise.sunriseFor(dateTime)
			val sunset = sunSetRise.sunsetFor(dateTime)

			sunrise.shouldNotBeNull()
			sunset.shouldNotBeNull()

			sunrise.hour shouldBe 7
			sunset.hour shouldBe 21
		}
	}

	@Nested
	inner class `seasonal variation` {

		@Test
		fun `London summer days are longer than winter days`() {
			val sunSetRise = createSunSetRise(51.5, -0.1)
			val summerData = sunSetRise.sunDataFor(utcDateTime(2024, 6, 21))
			val winterData = sunSetRise.sunDataFor(utcDateTime(2024, 12, 21))

			val summerDay = Duration.between(
				summerData.rise.shouldNotBeNull(), summerData.set.shouldNotBeNull()
			).toMinutes()
			val winterDay = Duration.between(
				winterData.rise.shouldNotBeNull(), winterData.set.shouldNotBeNull()
			).toMinutes()

			assert(summerDay > winterDay) {
				"Summer ($summerDay min) should be longer than winter ($winterDay min)"
			}
		}

		@Test
		fun `southern hemisphere has opposite seasons`() {
			val sunSetRise = createSunSetRise(-33.9, 151.2) // Sydney
			val junData = sunSetRise.sunDataFor(utcDateTime(2024, 6, 21))
			val decData = sunSetRise.sunDataFor(utcDateTime(2024, 12, 21))

			val junDay = Duration.between(
				junData.rise.shouldNotBeNull(), junData.set.shouldNotBeNull()
			).toMinutes()
			val decDay = Duration.between(
				decData.rise.shouldNotBeNull(), decData.set.shouldNotBeNull()
			).toMinutes()

			assert(decDay > junDay) {
				"Dec day ($decDay min) should be longer than Jun day ($junDay min) in Sydney"
			}
		}
	}
}
