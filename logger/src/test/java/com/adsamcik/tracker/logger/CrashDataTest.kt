package com.adsamcik.tracker.logger

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("CrashData")
class CrashDataTest {

	private fun createCrashData(
		timeStamp: Long = 1000L,
		exceptionName: String = "RuntimeException",
		exceptionMessage: String = "test error",
		stackTrace: String = "at com.test.Main(Main.kt:1)",
		cause: String? = null,
		threadName: String = "main",
		appVersion: String = "1.0.0",
		androidVersion: String = "14",
		deviceModel: String = "Pixel 7",
		deviceManufacturer: String = "Google",
		availableMemory: Long = 512L * 1024 * 1024,
		totalMemory: Long = 1024L * 1024 * 1024,
		batteryLevel: Float = 75f,
		isCharging: Boolean = false,
		networkType: String = "WiFi",
		isInBackground: Boolean = false
	) = CrashData(
		timeStamp = timeStamp,
		exceptionName = exceptionName,
		exceptionMessage = exceptionMessage,
		stackTrace = stackTrace,
		cause = cause,
		threadName = threadName,
		appVersion = appVersion,
		androidVersion = androidVersion,
		deviceModel = deviceModel,
		deviceManufacturer = deviceManufacturer,
		availableMemory = availableMemory,
		totalMemory = totalMemory,
		batteryLevel = batteryLevel,
		isCharging = isCharging,
		networkType = networkType,
		isInBackground = isInBackground
	)

	@Nested
	@DisplayName("Construction")
	inner class Construction {

		@Test
		fun `creates instance with all required fields`() {
			val crash = createCrashData()

			crash.timeStamp shouldBe 1000L
			crash.exceptionName shouldBe "RuntimeException"
			crash.exceptionMessage shouldBe "test error"
			crash.stackTrace shouldBe "at com.test.Main(Main.kt:1)"
			crash.threadName shouldBe "main"
			crash.appVersion shouldBe "1.0.0"
			crash.androidVersion shouldBe "14"
			crash.deviceModel shouldBe "Pixel 7"
			crash.deviceManufacturer shouldBe "Google"
			crash.availableMemory shouldBe 512L * 1024 * 1024
			crash.totalMemory shouldBe 1024L * 1024 * 1024
			crash.batteryLevel shouldBe 75f
			crash.isCharging shouldBe false
			crash.networkType shouldBe "WiFi"
			crash.isInBackground shouldBe false
		}

		@Test
		fun `cause defaults to null`() {
			val crash = createCrashData()
			crash.cause shouldBe null
		}

		@Test
		fun `cause can be set to a value`() {
			val crash = createCrashData(cause = "IllegalStateException: inner error")
			crash.cause shouldBe "IllegalStateException: inner error"
		}

		@Test
		fun `isInBackground defaults to false`() {
			val crash = CrashData(
				exceptionName = "E",
				exceptionMessage = "m",
				stackTrace = "s",
				threadName = "t",
				appVersion = "v",
				androidVersion = "a",
				deviceModel = "d",
				deviceManufacturer = "dm",
				availableMemory = 0L,
				totalMemory = 0L,
				batteryLevel = 0f,
				isCharging = false,
				networkType = "n"
			)
			crash.isInBackground shouldBe false
		}

		@Test
		fun `isInBackground can be set to true`() {
			val crash = createCrashData(isInBackground = true)
			crash.isInBackground shouldBe true
		}

		@Test
		fun `timeStamp defaults to current time`() {
			val before = System.currentTimeMillis()
			val crash = CrashData(
				exceptionName = "E",
				exceptionMessage = "m",
				stackTrace = "s",
				threadName = "t",
				appVersion = "v",
				androidVersion = "a",
				deviceModel = "d",
				deviceManufacturer = "dm",
				availableMemory = 0L,
				totalMemory = 0L,
				batteryLevel = 0f,
				isCharging = false,
				networkType = "n"
			)
			val after = System.currentTimeMillis()

			(crash.timeStamp in before..after) shouldBe true
		}
	}

	@Nested
	@DisplayName("Id field")
	inner class IdField {

		@Test
		fun `id defaults to zero`() {
			val crash = createCrashData()
			crash.id shouldBe 0L
		}

		@Test
		fun `id is mutable for Room`() {
			val crash = createCrashData()
			crash.id = 99L
			crash.id shouldBe 99L
		}
	}

	@Nested
	@DisplayName("Data class behavior")
	inner class DataClassBehavior {

		@Test
		fun `equals returns true for same field values`() {
			val a = createCrashData(timeStamp = 500L)
			val b = createCrashData(timeStamp = 500L)
			a shouldBe b
		}

		@Test
		fun `equals returns false for different exceptionName`() {
			val a = createCrashData(exceptionName = "NPE")
			val b = createCrashData(exceptionName = "ISE")
			a shouldNotBe b
		}

		@Test
		fun `hashCode is consistent for equal instances`() {
			val a = createCrashData(timeStamp = 500L)
			val b = createCrashData(timeStamp = 500L)
			a.hashCode() shouldBe b.hashCode()
		}

		@Test
		fun `toString contains key fields`() {
			val crash = createCrashData(
				exceptionName = "TestException",
				deviceModel = "TestDevice"
			)
			val str = crash.toString()
			str.shouldContain("TestException")
			str.shouldContain("TestDevice")
		}

		@Test
		fun `copy creates independent instance`() {
			val original = createCrashData(exceptionName = "Original")
			val copied = original.copy(exceptionName = "Copied")

			copied.exceptionName shouldBe "Copied"
			original.exceptionName shouldBe "Original"
		}

		@Test
		fun `id is not part of equality check`() {
			val a = createCrashData(timeStamp = 500L)
			a.id = 1L
			val b = createCrashData(timeStamp = 500L)
			b.id = 2L
			a shouldBe b
		}
	}

	@Nested
	@DisplayName("Device metadata fields")
	inner class DeviceMetadata {

		@Test
		fun `stores battery charging state`() {
			val charging = createCrashData(isCharging = true)
			val notCharging = createCrashData(isCharging = false)

			charging.isCharging shouldBe true
			notCharging.isCharging shouldBe false
		}

		@Test
		fun `stores memory values`() {
			val crash = createCrashData(
				availableMemory = 256L * 1024 * 1024,
				totalMemory = 8L * 1024 * 1024 * 1024
			)

			crash.availableMemory shouldBe 256L * 1024 * 1024
			crash.totalMemory shouldBe 8L * 1024 * 1024 * 1024
		}

		@Test
		fun `stores network type`() {
			val crash = createCrashData(networkType = "Mobile")
			crash.networkType shouldBe "Mobile"
		}

		@Test
		fun `stores battery level as float`() {
			val crash = createCrashData(batteryLevel = 42.5f)
			crash.batteryLevel shouldBe 42.5f
		}
	}
}
