package com.adsamcik.tracker.shared.base.result

import com.adsamcik.tracker.logging.api.ReporterFacade
import io.kotest.matchers.shouldBe
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

@DisplayName("SafetyExtensions")
class SafetyExtensionsTest {

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
	@DisplayName("tryWithReport")
	inner class TryWithReport {

		@Test
		fun `returns true when function succeeds`() {
			val result = tryWithReport { /* no-op */ }

			result shouldBe true
		}

		@Test
		fun `returns false when function throws exception`() {
			val result = tryWithReport { throw IllegalStateException("test error") }

			result shouldBe false
		}

		@Test
		fun `reports exception to ReporterFacade`() {
			val exception = IllegalArgumentException("reported")

			tryWithReport { throw exception }

			verify(exactly = 1) { ReporterFacade.report(exception) }
		}

		@Test
		fun `does not report when function succeeds`() {
			tryWithReport { /* no-op */ }

			verify(exactly = 0) { ReporterFacade.report(any<Throwable>()) }
		}

		@Test
		fun `executes the function body`() {
			var executed = false

			tryWithReport { executed = true }

			executed shouldBe true
		}

		@Test
		fun `handles RuntimeException`() {
			val result = tryWithReport { throw RuntimeException("runtime") }

			result shouldBe false
		}

		@Test
		fun `handles NullPointerException`() {
			val result = tryWithReport { throw NullPointerException() }

			result shouldBe false
		}
	}

	@Nested
	@DisplayName("tryWithResultAndReport")
	inner class TryWithResultAndReport {

		@Test
		fun `returns value when function succeeds`() {
			val result = tryWithResultAndReport(default = { "default" }) { "success" }

			result shouldBe "success"
		}

		@Test
		fun `returns default value when function throws`() {
			val result = tryWithResultAndReport(default = { "fallback" }) {
				throw RuntimeException("error")
			}

			result shouldBe "fallback"
		}

		@Test
		fun `reports exception to ReporterFacade`() {
			val exception = RuntimeException("test error")

			tryWithResultAndReport(default = { 0 }) { throw exception }

			verify(exactly = 1) { ReporterFacade.report(exception) }
		}

		@Test
		fun `does not report when function succeeds`() {
			tryWithResultAndReport(default = { 0 }) { 42 }

			verify(exactly = 0) { ReporterFacade.report(any<Throwable>()) }
		}

		@Test
		fun `handles null return value from function`() {
			val result = tryWithResultAndReport<String?>(default = { "default" }) { null }

			result shouldBe null
		}

		@Test
		fun `default lambda is not called on success`() {
			var defaultCalled = false

			tryWithResultAndReport(default = { defaultCalled = true; "default" }) { "success" }

			defaultCalled shouldBe false
		}

		@Test
		fun `default lambda is called on exception`() {
			var defaultCalled = false

			tryWithResultAndReport(default = { defaultCalled = true; "default" }) {
				throw RuntimeException()
			}

			defaultCalled shouldBe true
		}

		@Test
		fun `returns correct type for Int result`() {
			val result = tryWithResultAndReport(default = { -1 }) { 42 }

			result shouldBe 42
		}

		@Test
		fun `returns default for Int on exception`() {
			val result = tryWithResultAndReport(default = { -1 }) {
				throw ArithmeticException("divide by zero")
			}

			result shouldBe -1
		}

		@Test
		fun `works with list return type`() {
			val result = tryWithResultAndReport(default = { emptyList<String>() }) {
				listOf("a", "b")
			}

			result shouldBe listOf("a", "b")
		}

		@Test
		fun `returns empty list default on exception`() {
			val result = tryWithResultAndReport(default = { emptyList<String>() }) {
				throw IndexOutOfBoundsException()
			}

			result shouldBe emptyList()
		}
	}
}
