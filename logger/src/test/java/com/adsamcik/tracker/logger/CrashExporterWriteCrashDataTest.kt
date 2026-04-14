package com.adsamcik.tracker.logger

import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.StringWriter

@DisplayName("CrashExporter.writeCrashData")
class CrashExporterWriteCrashDataTest {

	private fun createCrashData(
		timeStamp: Long = 1704067200000L, // 2024-01-01 ~00:00 UTC
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

	/**
	 * Invokes the private writeCrashData method via reflection.
	 */
	private fun invokeWriteCrashData(crash: CrashData): String {
		val writer = StringWriter()
		val method = CrashExporter::class.java.getDeclaredMethod(
			"writeCrashData",
			java.io.Writer::class.java,
			CrashData::class.java
		)
		method.isAccessible = true
		method.invoke(CrashExporter, writer, crash)
		return writer.toString()
	}

	@Nested
	@DisplayName("Basic fields")
	inner class BasicFields {

		@Test
		fun `writes exception name`() {
			val output = invokeWriteCrashData(createCrashData(exceptionName = "NullPointerException"))
			output.shouldContain("Exception: NullPointerException")
		}

		@Test
		fun `writes thread name`() {
			val output = invokeWriteCrashData(createCrashData(threadName = "worker-1"))
			output.shouldContain("Thread: worker-1")
		}

		@Test
		fun `writes app version`() {
			val output = invokeWriteCrashData(createCrashData(appVersion = "3.2.1"))
			output.shouldContain("App Version: 3.2.1")
		}

		@Test
		fun `writes android version`() {
			val output = invokeWriteCrashData(createCrashData(androidVersion = "13"))
			output.shouldContain("Android Version: 13")
		}

		@Test
		fun `writes time field`() {
			val output = invokeWriteCrashData(createCrashData())
			output.shouldContain("Time:")
		}
	}

	@Nested
	@DisplayName("Device info")
	inner class DeviceInfo {

		@Test
		fun `writes device manufacturer and model`() {
			val output = invokeWriteCrashData(
				createCrashData(
					deviceManufacturer = "Samsung",
					deviceModel = "Galaxy S24"
				)
			)
			output.shouldContain("Device: Samsung Galaxy S24")
		}

		@Test
		fun `writes memory in MB`() {
			val output = invokeWriteCrashData(
				createCrashData(
					availableMemory = 512L * 1024 * 1024,
					totalMemory = 1024L * 1024 * 1024
				)
			)
			output.shouldContain("Memory: 512MB / 1024MB")
		}

		@Test
		fun `writes network type`() {
			val output = invokeWriteCrashData(createCrashData(networkType = "Mobile"))
			output.shouldContain("Network: Mobile")
		}
	}

	@Nested
	@DisplayName("Battery status")
	inner class BatteryStatus {

		@Test
		fun `writes charging status when charging`() {
			val output = invokeWriteCrashData(
				createCrashData(batteryLevel = 80f, isCharging = true)
			)
			output.shouldContain("Battery: 80.0% (Charging)")
		}

		@Test
		fun `writes not charging when not charging`() {
			val output = invokeWriteCrashData(
				createCrashData(batteryLevel = 42f, isCharging = false)
			)
			output.shouldContain("Battery: 42.0% (Not Charging)")
		}
	}

	@Nested
	@DisplayName("Background status")
	inner class BackgroundStatus {

		@Test
		fun `writes Yes when in background`() {
			val output = invokeWriteCrashData(createCrashData(isInBackground = true))
			output.shouldContain("Background: Yes")
		}

		@Test
		fun `writes No when in foreground`() {
			val output = invokeWriteCrashData(createCrashData(isInBackground = false))
			output.shouldContain("Background: No")
		}
	}

	@Nested
	@DisplayName("Cause field")
	inner class CauseField {

		@Test
		fun `writes cause when present`() {
			val output = invokeWriteCrashData(
				createCrashData(cause = "IllegalStateException: root cause")
			)
			output.shouldContain("Cause:")
		}

		@Test
		fun `omits cause when null`() {
			val output = invokeWriteCrashData(createCrashData(cause = null))
			output.shouldNotContain("Cause:")
		}
	}

	@Nested
	@DisplayName("Stack trace")
	inner class StackTrace {

		@Test
		fun `writes stack trace section`() {
			val output = invokeWriteCrashData(
				createCrashData(stackTrace = "at com.example.App.run(App.kt:42)")
			)
			output.shouldContain("Stack Trace:")
		}

		@Test
		fun `includes stack trace content`() {
			val trace = "at com.example.App.run(App.kt:42)\nat java.lang.Thread.run(Thread.java:1012)"
			val output = invokeWriteCrashData(createCrashData(stackTrace = trace))
			// Stack trace is redacted by PiiRedactor but line numbers should survive
			output.shouldContain("App.kt:42")
			output.shouldContain("Thread.java:1012")
		}
	}

	@Nested
	@DisplayName("PII redaction in writeCrashData")
	inner class PiiRedaction {

		@Test
		fun `redacts coordinates from exception message`() {
			val output = invokeWriteCrashData(
				createCrashData(exceptionMessage = "Error at 40.71280, -74.00601")
			)
			output.shouldNotContain("40.71280")
			output.shouldNotContain("-74.00601")
			output.shouldContain("Message:")
		}

		@Test
		fun `redacts coordinates from stack trace`() {
			val output = invokeWriteCrashData(
				createCrashData(stackTrace = "Failed at location 52.52000, 13.40500")
			)
			output.shouldNotContain("52.52000")
			output.shouldNotContain("13.40500")
		}

		@Test
		fun `redacts coordinates from cause`() {
			val output = invokeWriteCrashData(
				createCrashData(cause = "GPS fix at lat=48.858844 lon=2.294351")
			)
			output.shouldNotContain("48.858844")
			output.shouldNotContain("2.294351")
		}
	}

	@Nested
	@DisplayName("formatAsDateTime extension")
	inner class FormatAsDateTime {

		@Test
		fun `format produces non-empty string`() {
			// formatAsDateTime is a private extension on Long compiled as a static method
			val method = CrashExporter::class.java.getDeclaredMethod(
				"formatAsDateTime", Long::class.javaPrimitiveType
			)
			method.isAccessible = true
			val result = method.invoke(CrashExporter, 1704067200000L) as String
			result.isNotEmpty().shouldBeTrue()
		}
	}
}
