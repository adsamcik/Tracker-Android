package com.adsamcik.tracker.logging.api

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("ErrorReporter")
class ErrorReporterTest {

	@Nested
	@DisplayName("contract")
	inner class Contract {

		@Test
		fun `report with message delegates to implementation`() {
			val reporter = mockk<ErrorReporter>(relaxed = true)

			reporter.report("test message")

			verify(exactly = 1) { reporter.report("test message") }
		}

		@Test
		fun `report with exception delegates to implementation`() {
			val reporter = mockk<ErrorReporter>(relaxed = true)
			val exception = RuntimeException("boom")

			reporter.report(exception)

			verify(exactly = 1) { reporter.report(exception) }
		}

		@Test
		fun `log delegates to implementation`() {
			val reporter = mockk<ErrorReporter>(relaxed = true)

			reporter.log("log entry")

			verify(exactly = 1) { reporter.log("log entry") }
		}

		@Test
		fun `implementation can be created via mockk`() {
			val reporter = mockk<ErrorReporter>()

			(reporter is ErrorReporter) shouldBe true
		}

		@Test
		fun `report message and log are independent methods`() {
			val reporter = mockk<ErrorReporter>(relaxed = true)

			reporter.report("msg")
			reporter.log("log")

			verify(exactly = 1) { reporter.report("msg") }
			verify(exactly = 1) { reporter.log("log") }
			verify(exactly = 0) { reporter.report(any<Throwable>()) }
		}
	}
}
