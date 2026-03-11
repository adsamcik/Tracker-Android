package com.adsamcik.tracker.logger

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("CrashHandler PII redaction")
class CrashHandlerRedactPiiTest {

	@Nested
	@DisplayName("Coordinate patterns")
	inner class CoordinatePatterns {

		@Test
		fun `redacts decimal with 5+ decimal places`() {
			val input = "Error at 40.71280"
			val result = CrashHandler.redactPii(input)
			result shouldBe "Error at [REDACTED]"
		}

		@Test
		fun `redacts negative longitude-like decimal`() {
			val input = "Location was -74.00601"
			val result = CrashHandler.redactPii(input)
			result shouldBe "Location was [REDACTED]"
		}

		@Test
		fun `redacts coordinate pair in exception message`() {
			val input = "Failed to process 51.50740, -0.12780"
			val result = CrashHandler.redactPii(input)
			result.shouldNotContain("51.50740")
			result.shouldNotContain("-0.12780")
			result.shouldContain("Failed to process")
		}

		@Test
		fun `redacts high-precision coordinates`() {
			val input = "GPS fix: 48.858844, 2.294351"
			val result = CrashHandler.redactPii(input)
			result.shouldNotContain("48.858844")
			result.shouldNotContain("2.294351")
		}
	}

	@Nested
	@DisplayName("Explicit lat/lon references")
	inner class LatLonReferences {

		@Test
		fun `redacts lat= pattern`() {
			val input = "Error: lat=40.71 lon=-74.00"
			val result = CrashHandler.redactPii(input)
			result.shouldNotContain("40.71")
			result.shouldNotContain("-74.00")
		}

		@Test
		fun `redacts latitude colon pattern`() {
			val input = "latitude: 51.5, longitude: -0.12"
			val result = CrashHandler.redactPii(input)
			result.shouldNotContain("51.5")
			result.shouldNotContain("-0.12")
		}

		@Test
		fun `redacts case-insensitive Lat Lon`() {
			val input = "Lat=12.34 Lon=56.78"
			val result = CrashHandler.redactPii(input)
			result.shouldNotContain("12.34")
			result.shouldNotContain("56.78")
		}
	}

	@Nested
	@DisplayName("Preserves safe content")
	inner class PreservesSafeContent {

		@Test
		fun `preserves normal error messages`() {
			val input = "NullPointerException: Cannot invoke method on null object"
			val result = CrashHandler.redactPii(input)
			result shouldBe input
		}

		@Test
		fun `preserves short decimals`() {
			val input = "Battery level: 85.5%"
			val result = CrashHandler.redactPii(input)
			result shouldBe input
		}

		@Test
		fun `preserves integers`() {
			val input = "Index 42 out of bounds for length 10"
			val result = CrashHandler.redactPii(input)
			result shouldBe input
		}

		@Test
		fun `preserves stack trace class references`() {
			val input = "at com.example.LocationManager.update(LocationManager.kt:123)"
			val result = CrashHandler.redactPii(input)
			result shouldBe input
		}

		@Test
		fun `preserves empty string`() {
			CrashHandler.redactPii("") shouldBe ""
		}
	}

	@Nested
	@DisplayName("Mixed content")
	inner class MixedContent {

		@Test
		fun `redacts coordinates but preserves surrounding message`() {
			val input = "LocationException: invalid coordinate 40.71280 at index 3"
			val result = CrashHandler.redactPii(input)
			result.shouldContain("LocationException: invalid coordinate")
			result.shouldContain("at index 3")
			result.shouldNotContain("40.71280")
		}

		@Test
		fun `redacts only message, not stack trace line numbers`() {
			val stackTrace = """
				|java.lang.RuntimeException: Failed at 52.52000, 13.40500
				|    at com.example.Tracker.onLocationChanged(Tracker.kt:42)
				|    at android.location.LocationManager.requestUpdates(LocationManager.java:1234)
			""".trimMargin()
			val result = CrashHandler.redactPii(stackTrace)
			result.shouldNotContain("52.52000")
			result.shouldNotContain("13.40500")
			result.shouldContain("Tracker.kt:42")
			result.shouldContain("LocationManager.java:1234")
		}
	}
}
