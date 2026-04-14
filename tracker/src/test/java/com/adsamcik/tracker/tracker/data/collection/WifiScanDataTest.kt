package com.adsamcik.tracker.tracker.data.collection

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("WifiScanData")
class WifiScanDataTest {

	@Nested
	@DisplayName("Construction")
	inner class Construction {

		@Test
		fun `preserves all fields`() {
			val data = WifiScanData(
				timeMillis = 1000L,
				relativeTimeNanos = 5_000_000L,
				data = emptyArray(),
			)
			data.timeMillis shouldBe 1000L
			data.relativeTimeNanos shouldBe 5_000_000L
			data.data.size shouldBe 0
		}
	}

	@Nested
	@DisplayName("Equality")
	inner class Equality {

		@Test
		fun `two instances with same empty data are equal`() {
			val a = WifiScanData(1000L, 5_000_000L, emptyArray())
			val b = WifiScanData(1000L, 5_000_000L, emptyArray())
			a shouldBe b
		}

		@Test
		fun `different timeMillis produces inequality`() {
			val a = WifiScanData(1000L, 5_000_000L, emptyArray())
			val b = WifiScanData(2000L, 5_000_000L, emptyArray())
			a shouldNotBe b
		}

		@Test
		fun `different relativeTimeNanos produces inequality`() {
			val a = WifiScanData(1000L, 5_000_000L, emptyArray())
			val b = WifiScanData(1000L, 6_000_000L, emptyArray())
			a shouldNotBe b
		}
	}

	@Nested
	@DisplayName("hashCode")
	inner class HashCode {

		@Test
		fun `two equal instances have same hashCode`() {
			val a = WifiScanData(1000L, 5_000_000L, emptyArray())
			val b = WifiScanData(1000L, 5_000_000L, emptyArray())
			a.hashCode() shouldBe b.hashCode()
		}

		@Test
		fun `different data produces different hashCode`() {
			val a = WifiScanData(1000L, 5_000_000L, emptyArray())
			val b = WifiScanData(2000L, 5_000_000L, emptyArray())
			// Not guaranteed but very likely for different inputs
			a.hashCode() shouldNotBe b.hashCode()
		}
	}
}
