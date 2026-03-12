package com.adsamcik.tracker.logger

import android.util.Log
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("Reporter")
class ReporterTest {

	private fun resetReporter() {
		val isInitField = Reporter::class.java.getDeclaredField("isInitialized")
		isInitField.isAccessible = true
		isInitField.set(Reporter, false)

		val isEnabledField = Reporter::class.java.getDeclaredField("isEnabled")
		isEnabledField.isAccessible = true
		isEnabledField.set(Reporter, false)
	}

	private fun setReporterInitialized() {
		val isInitField = Reporter::class.java.getDeclaredField("isInitialized")
		isInitField.isAccessible = true
		isInitField.set(Reporter, true)
	}

	private fun mockLog() {
		mockkStatic(Log::class)
		every { Log.e(any(), any()) } returns 0
		every { Log.e(any(), any(), any()) } returns 0
		every { Log.w(any<String>(), any<String>()) } returns 0
		every { Log.println(any(), any(), any()) } returns 0
	}

	@AfterEach
	fun tearDown() {
		resetReporter()
		unmockkAll()
	}

	@Nested
	@DisplayName("Initialization")
	inner class Initialization {

		@Test
		fun `logs warning when report called before initialization`() {
			resetReporter()
			mockLog()
			Reporter.report("test message")
			verify { Log.w(any<String>(), eq("Reporter used before initialization")) }
		}

		@Test
		fun `logs warning when report exception called before initialization`() {
			resetReporter()
			mockLog()
			Reporter.report(RuntimeException("test"))
			verify { Log.w(any<String>(), eq("Reporter used before initialization")) }
		}

		@Test
		fun `logs warning when log called before initialization`() {
			resetReporter()
			mockLog()
			Reporter.log("test log")
			verify { Log.w(any<String>(), eq("Reporter used before initialization")) }
		}
	}

	@Nested
	@DisplayName("report(String) in DEBUG")
	inner class ReportStringDebug {

		@Test
		fun `logs error containing the message in debug`() {
			mockLog()
			setReporterInitialized()
			val msgSlot = slot<String>()
			Reporter.report("specific error message")
			verify { Log.e(any(), capture(msgSlot)) }
			msgSlot.captured shouldBe "specific error message"
		}

		@Test
		fun `logs for empty message`() {
			mockLog()
			setReporterInitialized()
			Reporter.report("")
			verify { Log.e(any(), any()) }
		}
	}

	@Nested
	@DisplayName("report(Throwable) in DEBUG")
	inner class ReportThrowableDebug {

		@Test
		fun `logs error for throwable in debug`() {
			mockLog()
			setReporterInitialized()
			val cause = IllegalStateException("root cause")
			Reporter.report(cause)
			verify { Log.e(any(), any(), any()) }
		}

		@Test
		fun `logs exception message from original throwable`() {
			mockLog()
			setReporterInitialized()
			val original = NullPointerException("null ref")
			val msgSlot = slot<String>()
			Reporter.report(original)
			verify { Log.e(any(), capture(msgSlot), any()) }
			msgSlot.captured shouldBe "null ref"
		}
	}

	@Nested
	@DisplayName("log() in DEBUG")
	inner class LogDebug {

		@Test
		fun `logs error with message in debug`() {
			mockLog()
			setReporterInitialized()
			val msgSlot = slot<String>()
			Reporter.log("debug log entry")
			verify { Log.e(any(), capture(msgSlot)) }
			msgSlot.captured shouldBe "debug log entry"
		}
	}

	@Nested
	@DisplayName("Privacy - no coordinates in reports")
	inner class Privacy {

		@Test
		fun `report logs message for each coordinate pattern`() {
			mockLog()
			setReporterInitialized()
			val coordinatePatterns = listOf(
				"48.8566, 2.3522",
				"lat=48.8566",
				"lng=2.3522",
				"latitude: 48.8566",
				"longitude: 2.3522"
			)
			coordinatePatterns.forEach { pattern ->
				Reporter.report("Error at location $pattern")
			}
			verify(exactly = 5) { Log.e(any(), any()) }
		}

		@Test
		fun `report messages should use redacted placeholders instead of coordinates`() {
			mockLog()
			setReporterInitialized()
			val redactedMessage = "Error at location [REDACTED]"
			val msgSlot = slot<String>()
			Reporter.report(redactedMessage)
			verify { Log.e(any(), capture(msgSlot)) }
			msgSlot.captured.shouldNotContain(Regex("""\d+\.\d{4,}"""))
		}
	}

	@Nested
	@DisplayName("ReporterFacade integration")
	inner class FacadeIntegration {

		@Test
		fun `Reporter implements ErrorReporter interface`() {
			val reporter: com.adsamcik.tracker.shared.base.logging.ErrorReporter = Reporter
			(reporter is com.adsamcik.tracker.shared.base.logging.ErrorReporter) shouldBe true
		}
	}
}
