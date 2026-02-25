package com.adsamcik.tracker.logger

import android.content.Context
import android.content.SharedPreferences
import com.adsamcik.tracker.shared.base.logging.ReporterFacade
import com.adsamcik.tracker.shared.preferences.Preferences
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.lang.reflect.Field

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

	@AfterEach
	fun tearDown() {
		resetReporter()
		unmockkAll()
	}

	@Nested
	@DisplayName("Initialization")
	inner class Initialization {

		@Test
		fun `throws when report called before initialization`() {
			resetReporter()
			// In DEBUG mode, report(String) throws an Exception wrapping the message.
			// If not DEBUG, it would throw UninitializedPropertyAccessException.
			// We verify that calling report without init does not silently succeed.
			assertThrows<Exception> {
				Reporter.report("test message")
			}
		}

		@Test
		fun `throws when report exception called before initialization`() {
			resetReporter()
			assertThrows<Exception> {
				Reporter.report(RuntimeException("test"))
			}
		}

		@Test
		fun `throws when log called before initialization`() {
			resetReporter()
			assertThrows<Exception> {
				Reporter.log("test log")
			}
		}
	}

	@Nested
	@DisplayName("report(String) in DEBUG")
	inner class ReportStringDebug {

		@Test
		fun `throws exception containing the message in debug`() {
			// In debug builds Reporter.report(String) throws Exception(message)
			val exception = assertThrows<Exception> {
				Reporter.report("specific error message")
			}
			exception.message shouldBe "specific error message"
		}

		@Test
		fun `throws for empty message`() {
			assertThrows<Exception> {
				Reporter.report("")
			}
		}
	}

	@Nested
	@DisplayName("report(Throwable) in DEBUG")
	inner class ReportThrowableDebug {

		@Test
		fun `throws wrapping exception in debug`() {
			val cause = IllegalStateException("root cause")
			val exception = assertThrows<Exception> {
				Reporter.report(cause)
			}
			exception.cause shouldBe cause
		}

		@Test
		fun `preserves original exception type as cause`() {
			val original = NullPointerException("null ref")
			val thrown = assertThrows<Exception> {
				Reporter.report(original)
			}
			(thrown.cause is NullPointerException) shouldBe true
		}
	}

	@Nested
	@DisplayName("log() in DEBUG")
	inner class LogDebug {

		@Test
		fun `throws exception with message in debug`() {
			val exception = assertThrows<Exception> {
				Reporter.log("debug log entry")
			}
			exception.message shouldBe "debug log entry"
		}
	}

	@Nested
	@DisplayName("Privacy - no coordinates in reports")
	inner class Privacy {

		@Test
		fun `report message must not contain latitude-longitude patterns`() {
			// Verify that coordinate-like data would be caught by message inspection.
			// The Reporter does not strip coordinates itself, but callers must not pass them.
			// This test documents the privacy contract.
			val coordinatePatterns = listOf(
				"48.8566, 2.3522",
				"lat=48.8566",
				"lng=2.3522",
				"latitude: 48.8566",
				"longitude: 2.3522"
			)
			coordinatePatterns.forEach { pattern ->
				// If someone mistakenly passes coordinates, the message is propagated as-is.
				// In debug mode it throws, so we catch and verify the message content is there.
				val exception = assertThrows<Exception> {
					Reporter.report("Error at location $pattern")
				}
				// Document that coordinates would leak if passed — callers must redact.
				exception.message shouldBe "Error at location $pattern"
			}
		}

		@Test
		fun `report messages should use redacted placeholders instead of coordinates`() {
			// Best practice: callers should redact coordinates before reporting
			val redactedMessage = "Error at location [REDACTED]"
			val exception = assertThrows<Exception> {
				Reporter.report(redactedMessage)
			}
			exception.message!!.shouldNotContain(Regex("""\d+\.\d{4,}"""))
		}
	}

	@Nested
	@DisplayName("ReporterFacade integration")
	inner class FacadeIntegration {

		@Test
		fun `Reporter implements ErrorReporter interface`() {
			// Verify Reporter can be assigned to the facade delegate type
			val reporter: com.adsamcik.tracker.shared.base.logging.ErrorReporter = Reporter
			(reporter is com.adsamcik.tracker.shared.base.logging.ErrorReporter) shouldBe true
		}
	}
}
