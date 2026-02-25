package com.adsamcik.tracker.impexp.importer

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("ImportResult")
class ImportResultTest {

	@Nested
	@DisplayName("Construction")
	inner class Construction {

		@Test
		fun `default values are all zero`() {
			val result = ImportResult()
			result.successCount shouldBe 0
			result.skippedCount shouldBe 0
			result.failedCount shouldBe 0
			result.errors shouldBe emptyList()
		}

		@Test
		fun `EMPTY companion has all zero values`() {
			ImportResult.EMPTY.successCount shouldBe 0
			ImportResult.EMPTY.skippedCount shouldBe 0
			ImportResult.EMPTY.failedCount shouldBe 0
			ImportResult.EMPTY.errors shouldBe emptyList()
		}

		@Test
		fun `custom values are retained`() {
			val result = ImportResult(
				successCount = 10,
				skippedCount = 3,
				failedCount = 1,
				errors = listOf("error1")
			)
			result.successCount shouldBe 10
			result.skippedCount shouldBe 3
			result.failedCount shouldBe 1
			result.errors shouldBe listOf("error1")
		}
	}

	@Nested
	@DisplayName("totalProcessed")
	inner class TotalProcessed {

		@Test
		fun `totalProcessed sums all counts`() {
			val result = ImportResult(successCount = 5, skippedCount = 2, failedCount = 1)
			result.totalProcessed shouldBe 8
		}

		@Test
		fun `totalProcessed is zero for empty result`() {
			ImportResult.EMPTY.totalProcessed shouldBe 0
		}
	}

	@Nested
	@DisplayName("Plus operator")
	inner class PlusOperator {

		@Test
		fun `adding two results sums all fields`() {
			val a = ImportResult(successCount = 5, skippedCount = 2, failedCount = 1, errors = listOf("e1"))
			val b = ImportResult(successCount = 3, skippedCount = 1, failedCount = 0, errors = listOf("e2"))
			val combined = a + b

			combined.successCount shouldBe 8
			combined.skippedCount shouldBe 3
			combined.failedCount shouldBe 1
			combined.errors shouldBe listOf("e1", "e2")
		}

		@Test
		fun `adding EMPTY to result returns same values`() {
			val result = ImportResult(successCount = 10, skippedCount = 5)
			val combined = result + ImportResult.EMPTY

			combined.successCount shouldBe 10
			combined.skippedCount shouldBe 5
			combined.failedCount shouldBe 0
			combined.errors shouldBe emptyList()
		}

		@Test
		fun `adding result to EMPTY returns same values`() {
			val result = ImportResult(successCount = 7, failedCount = 2, errors = listOf("err"))
			val combined = ImportResult.EMPTY + result

			combined.successCount shouldBe 7
			combined.failedCount shouldBe 2
			combined.errors shouldBe listOf("err")
		}

		@Test
		fun `chained addition works correctly`() {
			val a = ImportResult(successCount = 1)
			val b = ImportResult(successCount = 2, skippedCount = 1)
			val c = ImportResult(successCount = 3, failedCount = 1, errors = listOf("x"))

			val combined = a + b + c
			combined.successCount shouldBe 6
			combined.skippedCount shouldBe 1
			combined.failedCount shouldBe 1
			combined.errors shouldBe listOf("x")
		}
	}

	@Nested
	@DisplayName("Activity duplicate tracking")
	inner class ActivityDuplicateTracking {

		@Test
		fun `skipped count represents activity duplicates`() {
			// Simulates what DatabaseImport does: activity constraint violations
			// increment skippedCount instead of being silently swallowed
			val tableResult = ImportResult(successCount = 10, skippedCount = 3)
			tableResult.skippedCount shouldBe 3
			tableResult.totalProcessed shouldBe 13
		}

		@Test
		fun `failed count represents FK violations`() {
			val tableResult = ImportResult(
				successCount = 8,
				failedCount = 2,
				errors = listOf(
					"Constraint violation in table location",
					"Constraint violation in table location"
				)
			)
			tableResult.failedCount shouldBe 2
			tableResult.errors.size shouldBe 2
		}
	}
}
