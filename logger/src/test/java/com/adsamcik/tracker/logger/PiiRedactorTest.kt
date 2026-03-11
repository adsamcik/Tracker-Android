package com.adsamcik.tracker.logger

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("PiiRedactor")
class PiiRedactorTest {

	@Nested
	@DisplayName("Coordinate pattern (5+ decimals)")
	inner class CoordinatePattern {

		@Test
		fun `redacts 5-decimal coordinate`() {
			PiiRedactor.redact("Point: 40.71280") shouldBe "Point: [REDACTED]"
		}

		@Test
		fun `redacts 6-decimal coordinate`() {
			PiiRedactor.redact("GPS: 48.858844") shouldBe "GPS: [REDACTED]"
		}

		@Test
		fun `redacts negative coordinate`() {
			PiiRedactor.redact("Value: -74.00601") shouldBe "Value: [REDACTED]"
		}

		@Test
		fun `redacts coordinate pair`() {
			val result = PiiRedactor.redact("Fix at 52.52000, 13.40500")
			result.shouldNotContain("52.52000")
			result.shouldNotContain("13.40500")
		}

		@Test
		fun `preserves 4-decimal number without keyword`() {
			PiiRedactor.redact("Version 1.2345") shouldBe "Version 1.2345"
		}

		@Test
		fun `preserves short decimals`() {
			PiiRedactor.redact("Battery: 85.5%") shouldBe "Battery: 85.5%"
		}

		@Test
		fun `preserves integers`() {
			PiiRedactor.redact("Count is 42") shouldBe "Count is 42"
		}

		@Test
		fun `limits integer part to 1-3 digits`() {
			PiiRedactor.redact("ID: 12345.67890") shouldBe "ID: 12345.67890"
		}
	}

	@Nested
	@DisplayName("Keyword lat/lon patterns")
	inner class KeywordPatterns {

		@Test
		fun `redacts lat= pattern`() {
			val result = PiiRedactor.redact("lat=40.71 lon=-74.00")
			result.shouldNotContain("40.71")
			result.shouldNotContain("-74.00")
		}

		@Test
		fun `redacts latitude colon pattern`() {
			val result = PiiRedactor.redact("latitude: 51.5, longitude: -0.12")
			result.shouldNotContain("51.5")
			result.shouldNotContain("-0.12")
		}

		@Test
		fun `redacts location= pattern`() {
			val result = PiiRedactor.redact("location=51.5074")
			result.shouldNotContain("51.5074")
		}

		@Test
		fun `redacts coordinate( pattern`() {
			val result = PiiRedactor.redact("coord(48.85)")
			result.shouldNotContain("48.85")
		}

		@Test
		fun `redacts position= pattern`() {
			val result = PiiRedactor.redact("position=52.520")
			result.shouldNotContain("52.520")
		}

		@Test
		fun `is case insensitive`() {
			val result = PiiRedactor.redact("LAT=12.34 LON=56.78")
			result.shouldNotContain("12.34")
			result.shouldNotContain("56.78")
		}
	}

	@Nested
	@DisplayName("LatLng/Location toString patterns")
	inner class ToStringPatterns {

		@Test
		fun `redacts LatLng toString`() {
			val result = PiiRedactor.redact("Got LatLng(40.7128, -74.0060)")
			result.shouldNotContain("40.7128")
			result.shouldNotContain("-74.0060")
		}

		@Test
		fun `redacts Location toString`() {
			val result = PiiRedactor.redact("Location[gps lat=40.7128 lon=-74.006]")
			result.shouldNotContain("40.7128")
			result.shouldNotContain("-74.006")
		}

		@Test
		fun `redacts case-insensitive latlng`() {
			val result = PiiRedactor.redact("latlng(51.5, -0.12)")
			result.shouldNotContain("51.5")
		}
	}

	@Nested
	@DisplayName("Preserves safe content")
	inner class PreservesSafe {

		@Test
		fun `preserves normal error messages`() {
			val input = "NullPointerException: Cannot invoke method on null"
			PiiRedactor.redact(input) shouldBe input
		}

		@Test
		fun `preserves stack trace line numbers`() {
			val input = "at com.example.Tracker.update(Tracker.kt:42)"
			PiiRedactor.redact(input) shouldBe input
		}

		@Test
		fun `preserves version numbers`() {
			val input = "App version 3.14.1592"
			PiiRedactor.redact(input) shouldBe input
		}

		@Test
		fun `preserves empty string`() {
			PiiRedactor.redact("") shouldBe ""
		}
	}

	@Nested
	@DisplayName("Mixed content")
	inner class MixedContent {

		@Test
		fun `redacts coordinates in stack trace messages but preserves line numbers`() {
			val trace = """
				|java.lang.RuntimeException: Error at 52.52000, 13.40500
				|    at com.example.Tracker.onLocationChanged(Tracker.kt:42)
				|    at android.location.LocationManager.requestUpdates(LocationManager.java:1234)
			""".trimMargin()
			val result = PiiRedactor.redact(trace)
			result.shouldNotContain("52.52000")
			result.shouldNotContain("13.40500")
			result.shouldContain("Tracker.kt:42")
			result.shouldContain("LocationManager.java:1234")
		}

		@Test
		fun `redacts multiple patterns in same string`() {
			val input = "lat=40.71 at LatLng(48.858844, 2.294351) near 52.52000"
			val result = PiiRedactor.redact(input)
			result.shouldNotContain("40.71")
			result.shouldNotContain("48.858844")
			result.shouldNotContain("2.294351")
			result.shouldNotContain("52.52000")
		}
	}
}
