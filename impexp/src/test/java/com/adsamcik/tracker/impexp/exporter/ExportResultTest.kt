package com.adsamcik.tracker.impexp.exporter

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("ExportResult")
class ExportResultTest {

	@Nested
	@DisplayName("Success")
	inner class SuccessTests {

		@Test
		fun `Success is instance of ExportResult`() {
			ExportResult.Success.shouldBeInstanceOf<ExportResult>()
		}

		@Test
		fun `Success isSuccess is true`() {
			ExportResult.Success.isSuccess shouldBe true
		}

		@Test
		fun `Success is a data object singleton`() {
			val a = ExportResult.Success
			val b = ExportResult.Success
			(a === b) shouldBe true
		}
	}

	@Nested
	@DisplayName("Error")
	inner class ErrorTests {

		@Test
		fun `Error is instance of ExportResult`() {
			ExportResult.Error().shouldBeInstanceOf<ExportResult>()
		}

		@Test
		fun `Error isSuccess is false`() {
			ExportResult.Error().isSuccess shouldBe false
		}

		@Test
		fun `Error with null message`() {
			val error = ExportResult.Error(message = null)
			error.message shouldBe null
		}

		@Test
		fun `Error equality based on message`() {
			val a = ExportResult.Error(null)
			val b = ExportResult.Error(null)
			a shouldBe b
		}
	}
}
