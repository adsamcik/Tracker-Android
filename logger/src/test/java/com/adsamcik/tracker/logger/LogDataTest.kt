package com.adsamcik.tracker.logger

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("LogData")
class LogDataTest {

	@Nested
	@DisplayName("Primary constructor (String data)")
	inner class PrimaryConstructor {

		@Test
		fun `creates instance with all fields`() {
			val timestamp = 1000L
			val logData = LogData(
				timeStamp = timestamp,
				message = "test message",
				data = "test data",
				source = "test source"
			)

			logData.timeStamp shouldBe 1000L
			logData.message shouldBe "test message"
			logData.data shouldBe "test data"
			logData.source shouldBe "test source"
		}

		@Test
		fun `defaults data to empty string`() {
			val logData = LogData(
				message = "msg",
				source = "src"
			)

			logData.data shouldBe ""
		}

		@Test
		fun `defaults id to zero`() {
			val logData = LogData(message = "msg", source = "src")
			logData.id shouldBe 0L
		}

		@Test
		fun `id is mutable for Room`() {
			val logData = LogData(message = "msg", source = "src")
			logData.id = 42L
			logData.id shouldBe 42L
		}

		@Test
		fun `timeStamp defaults to system millis`() {
			val before = System.currentTimeMillis()
			val logData = LogData(message = "msg", source = "src")
			val after = System.currentTimeMillis()

			(logData.timeStamp in before..after) shouldBe true
		}
	}

	@Nested
	@DisplayName("Secondary constructor (Any data)")
	inner class SecondaryConstructor {

		@Test
		fun `converts Any data to String via toString`() {
			val logData = LogData(
				message = "test",
				data = 12345,
				source = "src"
			)

			logData.data shouldBe "12345"
		}

		@Test
		fun `converts list to string`() {
			val list = listOf(1, 2, 3)
			val logData = LogData(
				message = "test",
				data = list,
				source = "src"
			)

			logData.data shouldBe "[1, 2, 3]"
		}

		@Test
		fun `converts custom object to string`() {
			val obj = object {
				override fun toString(): String = "CustomObject(x=1)"
			}
			val logData = LogData(
				message = "test",
				data = obj,
				source = "src"
			)

			logData.data shouldBe "CustomObject(x=1)"
		}

		@Test
		fun `preserves timestamp from secondary constructor`() {
			val timestamp = 9999L
			val logData = LogData(
				timeStamp = timestamp,
				message = "test",
				data = 42,
				source = "src"
			)

			logData.timeStamp shouldBe 9999L
		}
	}

	@Nested
	@DisplayName("Data class behavior")
	inner class DataClassBehavior {

		@Test
		fun `equals returns true for same field values`() {
			val a = LogData(timeStamp = 100L, message = "m", data = "d", source = "s")
			val b = LogData(timeStamp = 100L, message = "m", data = "d", source = "s")

			a shouldBe b
		}

		@Test
		fun `equals returns false for different messages`() {
			val a = LogData(timeStamp = 100L, message = "m1", data = "d", source = "s")
			val b = LogData(timeStamp = 100L, message = "m2", data = "d", source = "s")

			a shouldNotBe b
		}

		@Test
		fun `hashCode is consistent for equal instances`() {
			val a = LogData(timeStamp = 100L, message = "m", data = "d", source = "s")
			val b = LogData(timeStamp = 100L, message = "m", data = "d", source = "s")

			a.hashCode() shouldBe b.hashCode()
		}

		@Test
		fun `toString contains field values`() {
			val logData = LogData(
				timeStamp = 100L,
				message = "msg",
				data = "dat",
				source = "src"
			)
			val str = logData.toString()

			str.shouldContain("msg")
			str.shouldContain("dat")
			str.shouldContain("src")
			str.shouldContain("100")
		}

		@Test
		fun `copy creates independent instance`() {
			val original = LogData(timeStamp = 100L, message = "m", data = "d", source = "s")
			val copied = original.copy(message = "m2")

			copied.message shouldBe "m2"
			original.message shouldBe "m"
			copied.timeStamp shouldBe original.timeStamp
		}

		@Test
		fun `id is not part of equality check`() {
			val a = LogData(timeStamp = 100L, message = "m", data = "d", source = "s")
			a.id = 1L
			val b = LogData(timeStamp = 100L, message = "m", data = "d", source = "s")
			b.id = 2L

			a shouldBe b
		}
	}
}
