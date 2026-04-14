package com.adsamcik.tracker.shared.base.extension

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime

class DateExtensionsTest {

	@Nested
	@DisplayName("isBeforeOrEqual")
	inner class IsBeforeOrEqual {
		@Test
		fun `returns true when before`() {
			val earlier = ZonedDateTime.of(2024, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
			val later = ZonedDateTime.of(2024, 1, 2, 0, 0, 0, 0, ZoneOffset.UTC)
			earlier.isBeforeOrEqual(later) shouldBe true
		}

		@Test
		fun `returns true when equal`() {
			val time = ZonedDateTime.of(2024, 1, 1, 12, 0, 0, 0, ZoneOffset.UTC)
			time.isBeforeOrEqual(time) shouldBe true
		}

		@Test
		fun `returns false when after`() {
			val earlier = ZonedDateTime.of(2024, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
			val later = ZonedDateTime.of(2024, 1, 2, 0, 0, 0, 0, ZoneOffset.UTC)
			later.isBeforeOrEqual(earlier) shouldBe false
		}

		@Test
		fun `works across time zones for equal instants`() {
			val utc = ZonedDateTime.of(2024, 6, 15, 12, 0, 0, 0, ZoneOffset.UTC)
			val plusTwo = ZonedDateTime.of(2024, 6, 15, 14, 0, 0, 0, ZoneOffset.ofHours(2))
			utc.isBeforeOrEqual(plusTwo) shouldBe true
		}
	}

	@Nested
	@DisplayName("isAfterOrEqual")
	inner class IsAfterOrEqual {
		@Test
		fun `returns true when after`() {
			val earlier = ZonedDateTime.of(2024, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
			val later = ZonedDateTime.of(2024, 1, 2, 0, 0, 0, 0, ZoneOffset.UTC)
			later.isAfterOrEqual(earlier) shouldBe true
		}

		@Test
		fun `returns true when equal`() {
			val time = ZonedDateTime.of(2024, 1, 1, 12, 0, 0, 0, ZoneOffset.UTC)
			time.isAfterOrEqual(time) shouldBe true
		}

		@Test
		fun `returns false when before`() {
			val earlier = ZonedDateTime.of(2024, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
			val later = ZonedDateTime.of(2024, 1, 2, 0, 0, 0, 0, ZoneOffset.UTC)
			earlier.isAfterOrEqual(later) shouldBe false
		}

		@Test
		fun `works across time zones for equal instants`() {
			val utc = ZonedDateTime.of(2024, 6, 15, 12, 0, 0, 0, ZoneOffset.UTC)
			val plusTwo = ZonedDateTime.of(2024, 6, 15, 14, 0, 0, 0, ZoneOffset.ofHours(2))
			plusTwo.isAfterOrEqual(utc) shouldBe true
		}
	}

	@Nested
	@DisplayName("isTheSameDay")
	inner class IsTheSameDay {
		@Test
		fun `same instant is same day`() {
			val instant = Instant.parse("2024-06-15T12:00:00Z")
			instant.isTheSameDay(instant) shouldBe true
		}

		@Test
		fun `different times same UTC day`() {
			val morning = Instant.parse("2024-06-15T08:00:00Z")
			val evening = Instant.parse("2024-06-15T22:00:00Z")
			morning.isTheSameDay(evening) shouldBe true
		}

		@Test
		fun `different UTC days`() {
			val day1 = Instant.parse("2024-06-15T23:59:59Z")
			val day2 = Instant.parse("2024-06-16T00:00:00Z")
			day1.isTheSameDay(day2) shouldBe false
		}

		@Test
		fun `same day of year different year is not same day`() {
			val year1 = Instant.parse("2023-06-15T12:00:00Z")
			val year2 = Instant.parse("2024-06-15T12:00:00Z")
			year1.isTheSameDay(year2) shouldBe false
		}

		@Test
		fun `start of day and end of day`() {
			val start = Instant.parse("2024-01-01T00:00:00Z")
			val end = Instant.parse("2024-01-01T23:59:59Z")
			start.isTheSameDay(end) shouldBe true
		}

		@Test
		fun `new years eve and new year`() {
			val dec31 = Instant.parse("2024-12-31T23:59:59Z")
			val jan1 = Instant.parse("2025-01-01T00:00:00Z")
			dec31.isTheSameDay(jan1) shouldBe false
		}
	}

	@Nested
	@DisplayName("toEpochMillis")
	inner class ToEpochMillis {
		@Test
		fun `epoch start returns 0`() {
			val epoch = ZonedDateTime.of(1970, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
			epoch.toEpochMillis() shouldBe 0L
		}

		@Test
		fun `1 second after epoch returns 1000`() {
			val time = ZonedDateTime.of(1970, 1, 1, 0, 0, 1, 0, ZoneOffset.UTC)
			time.toEpochMillis() shouldBe 1000L
		}

		@Test
		fun `known timestamp converts correctly`() {
			val time = ZonedDateTime.of(2024, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
			time.toEpochMillis() shouldBe time.toInstant().toEpochMilli()
		}

		@Test
		fun `different timezone same instant produces same millis`() {
			val utc = ZonedDateTime.of(2024, 6, 15, 12, 0, 0, 0, ZoneOffset.UTC)
			val tokyo = utc.withZoneSameInstant(ZoneOffset.ofHours(9))
			utc.toEpochMillis() shouldBe tokyo.toEpochMillis()
		}
	}
}
