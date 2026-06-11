package com.adsamcik.tracker.stats.api.error

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class StatsErrorTest {

	@Nested
	inner class DatabaseErrorType {

		@Test
		fun `is a StatsError`() {
			val error: StatsError = StatsError.DatabaseError("db fail")
			error.shouldBeInstanceOf<StatsError.DatabaseError>()
		}

		@Test
		fun `message is preserved`() {
			StatsError.DatabaseError("disk full").message shouldBe "disk full"
		}

		@Test
		fun `cause defaults to null`() {
			StatsError.DatabaseError("oops").cause.shouldBeNull()
		}

		@Test
		fun `cause is preserved when provided`() {
			val throwable = RuntimeException("root")
			val error = StatsError.DatabaseError("wrapped", throwable)
			error.cause shouldBe throwable
		}
	}

	@Nested
	inner class ProcessorErrorType {

		@Test
		fun `preserves processorId`() {
			val error = StatsError.ProcessorError("failed", "trip-detector")
			error.processorId shouldBe "trip-detector"
			error.message shouldBe "failed"
		}
	}

	@Nested
	inner class NotFoundType {

		@Test
		fun `preserves entity type and id`() {
			val error = StatsError.NotFound("missing", "Trip", "42")
			error.entityType shouldBe "Trip"
			error.id shouldBe "42"
			error.message shouldBe "missing"
		}
	}

	@Nested
	inner class DataClassEquality {

		@Test
		fun `same DatabaseError values are equal`() {
			StatsError.DatabaseError("x") shouldBe StatsError.DatabaseError("x")
		}

		@Test
		fun `different messages are not equal`() {
			val e1 = StatsError.ValidationError("a")
			val e2 = StatsError.ValidationError("b")
			assert(e1 != e2)
		}

		@Test
		fun `CheckpointError and ValidationError with same message are not equal`() {
			val e1: StatsError = StatsError.CheckpointError("fail")
			val e2: StatsError = StatsError.ValidationError("fail")
			assert(e1 != e2)
		}
	}

	@Nested
	inner class WhenExhaustiveness {

		@Test
		fun `all subtypes are covered by when expression`() {
			val errors: List<StatsError> = listOf(
				StatsError.DatabaseError("a"),
				StatsError.ProcessorError("b", "p1"),
				StatsError.CheckpointError("c"),
				StatsError.ValidationError("d"),
				StatsError.NotFound("e", "Trip", "1"),
			)
			errors.forEach { error ->
				val label = when (error) {
					is StatsError.DatabaseError -> "db"
					is StatsError.ProcessorError -> "proc"
					is StatsError.CheckpointError -> "chk"
					is StatsError.ValidationError -> "val"
					is StatsError.NotFound -> "nf"
				}
				assert(label.isNotEmpty())
			}
		}
	}
}
