package com.adsamcik.tracker.tracker.data

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("PersistenceResult")
class PersistenceResultTest {

	@Nested
	@DisplayName("Success")
	inner class SuccessTests {

		@Test
		fun `default record count is 1`() {
			val success = PersistenceResult.Success()
			success.recordCount shouldBe 1
		}

		@Test
		fun `custom record count is preserved`() {
			val success = PersistenceResult.Success(recordCount = 42)
			success.recordCount shouldBe 42
		}

		@Test
		fun `is a PersistenceResult`() {
			val result: PersistenceResult = PersistenceResult.Success()
			result.shouldBeInstanceOf<PersistenceResult.Success>()
		}

		@Test
		fun `two successes with same count are equal`() {
			PersistenceResult.Success(5) shouldBe PersistenceResult.Success(5)
		}

		@Test
		fun `two successes with different counts are not equal`() {
			PersistenceResult.Success(1) shouldNotBe PersistenceResult.Success(2)
		}
	}

	@Nested
	@DisplayName("Failure")
	inner class FailureTests {

		@Test
		fun `carries source and message`() {
			val failure = PersistenceResult.Failure(
				source = "location",
				message = "insert failed",
			)
			failure.source shouldBe "location"
			failure.message shouldBe "insert failed"
		}

		@Test
		fun `cause defaults to null`() {
			val failure = PersistenceResult.Failure(
				source = "cell",
				message = "error",
			)
			failure.cause shouldBe null
		}

		@Test
		fun `cause can be provided`() {
			val ex = RuntimeException("db locked")
			val failure = PersistenceResult.Failure(
				source = "wifi",
				message = "timeout",
				cause = ex,
			)
			failure.cause shouldBe ex
		}

		@Test
		fun `is a PersistenceResult`() {
			val result: PersistenceResult = PersistenceResult.Failure("src", "msg")
			result.shouldBeInstanceOf<PersistenceResult.Failure>()
		}

		@Test
		fun `two failures with same data are equal`() {
			val a = PersistenceResult.Failure("src", "msg")
			val b = PersistenceResult.Failure("src", "msg")
			a shouldBe b
		}
	}

	@Nested
	@DisplayName("Exhaustive when")
	inner class ExhaustiveWhen {

		@Test
		fun `when covers all branches`() {
			val results: List<PersistenceResult> = listOf(
				PersistenceResult.Success(),
				PersistenceResult.Failure("src", "msg"),
			)

			val labels = results.map {
				when (it) {
					is PersistenceResult.Success -> "success"
					is PersistenceResult.Failure -> "failure"
				}
			}

			labels shouldBe listOf("success", "failure")
		}
	}
}

@DisplayName("PersistenceError")
class PersistenceErrorTest {

	@Nested
	@DisplayName("Construction")
	inner class Construction {

		@Test
		fun `all fields are preserved`() {
			val ex = RuntimeException("oops")
			val error = PersistenceError(
				source = "LocationComponent",
				operation = "batch insert",
				recordCount = 10,
				cause = ex,
				timestamp = 12345L,
			)
			error.source shouldBe "LocationComponent"
			error.operation shouldBe "batch insert"
			error.recordCount shouldBe 10
			error.cause shouldBe ex
			error.timestamp shouldBe 12345L
		}

		@Test
		fun `timestamp has a sensible default`() {
			val before = System.currentTimeMillis()
			val error = PersistenceError(
				source = "test",
				operation = "op",
				recordCount = 1,
				cause = RuntimeException(),
			)
			val after = System.currentTimeMillis()
			(error.timestamp in before..after) shouldBe true
		}
	}

	@Nested
	@DisplayName("Data class equality")
	inner class Equality {

		@Test
		fun `two errors with same data are equal`() {
			val ex = RuntimeException("fail")
			val a = PersistenceError("src", "op", 5, ex, 1000L)
			val b = PersistenceError("src", "op", 5, ex, 1000L)
			a shouldBe b
		}

		@Test
		fun `errors with different sources are not equal`() {
			val ex = RuntimeException("fail")
			val a = PersistenceError("srcA", "op", 5, ex, 1000L)
			val b = PersistenceError("srcB", "op", 5, ex, 1000L)
			a shouldNotBe b
		}
	}
}
