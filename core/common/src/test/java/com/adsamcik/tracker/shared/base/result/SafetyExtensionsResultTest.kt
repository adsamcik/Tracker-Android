package com.adsamcik.tracker.shared.base.result

import com.adsamcik.tracker.logging.api.ReporterFacade
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CancellationException

@DisplayName("SafetyExtensions result helpers")
class SafetyExtensionsResultTest {

	@BeforeEach
	fun setUp() {
		mockkObject(ReporterFacade)
		every { ReporterFacade.report(any<Throwable>()) } just Runs
	}

	@AfterEach
	fun tearDown() {
		unmockkObject(ReporterFacade)
	}

	@Nested
	@DisplayName("runWithReport")
	inner class RunWithReport {

		@Test
		fun `returns Result success when function succeeds`() {
			val result = runWithReport { /* no-op */ }

			result.isSuccess shouldBe true
			result.getOrThrow() shouldBe Unit
			verify(exactly = 0) { ReporterFacade.report(any<Throwable>()) }
		}

		@Test
		fun `returns Result failure with original exception when function fails`() {
			val exception = IllegalStateException("boom")

			val result = runWithReport { throw exception }

			result.isFailure shouldBe true
			result.exceptionOrNull().shouldBeSameInstanceAs(exception)
			verify(exactly = 1) { ReporterFacade.report(exception) }
		}

		@Test
		fun `rethrows CancellationException without reporting`() {
			val cancellation = CancellationException("cancelled")

			val thrown = assertThrows<CancellationException> {
				runWithReport { throw cancellation }
			}

			thrown.shouldBeSameInstanceAs(cancellation)
			verify(exactly = 0) { ReporterFacade.report(any<Throwable>()) }
		}
	}

	@Nested
	@DisplayName("runWithResultAndReport")
	inner class RunWithResultAndReport {

		@Test
		fun `returns Result success with value when function succeeds`() {
			val result = runWithResultAndReport { "success" }

			result.isSuccess shouldBe true
			result.getOrThrow() shouldBe "success"
			verify(exactly = 0) { ReporterFacade.report(any<Throwable>()) }
		}

		@Test
		fun `returns Result failure with original exception and reports it`() {
			val exception = IllegalArgumentException("reported")

			val result = runWithResultAndReport<String> { throw exception }

			result.isFailure shouldBe true
			result.exceptionOrNull().shouldBeSameInstanceAs(exception)
			verify(exactly = 1) { ReporterFacade.report(exception) }
		}

		@Test
		fun `rethrows CancellationException instead of swallowing it`() {
			val cancellation = CancellationException("stop")

			val thrown = assertThrows<CancellationException> {
				runWithResultAndReport<String> { throw cancellation }
			}

			thrown.shouldBeSameInstanceAs(cancellation)
			verify(exactly = 0) { ReporterFacade.report(any<Throwable>()) }
		}
	}

	@Nested
	@DisplayName("deprecated helpers")
	inner class DeprecatedHelpers {

		@Test
		fun `tryWithReport remains backward compatible`() {
			val result = tryWithReport { throw IllegalStateException("legacy") }

			result shouldBe false
			verify(exactly = 1) { ReporterFacade.report(any<Throwable>()) }
		}

		@Test
		fun `tryWithResultAndReport still returns fallback on failure`() {
			val exception = IllegalStateException("legacy")

			val result = tryWithResultAndReport(default = { "fallback" }) { throw exception }

			result shouldBe "fallback"
			verify(exactly = 1) { ReporterFacade.report(exception) }
		}
	}
}
