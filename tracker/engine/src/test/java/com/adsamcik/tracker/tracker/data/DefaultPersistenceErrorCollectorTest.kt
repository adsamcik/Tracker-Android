package com.adsamcik.tracker.tracker.data

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("DefaultPersistenceErrorCollector")
class DefaultPersistenceErrorCollectorTest {

	private fun createError(source: String = "test"): PersistenceError = PersistenceError(
		source = source,
		operation = "insert",
		recordCount = 1,
		cause = RuntimeException("test error"),
		timestamp = 1000L,
	)

	@Nested
	@DisplayName("reportError (suspend)")
	inner class ReportError {

		@Test
		fun `reported error is available in errors flow`() = runTest {
			val collector = DefaultPersistenceErrorCollector()
			val error = createError()

			collector.reportError(error)

			val emitted = collector.errors.first()
			emitted shouldBe error
		}

		@Test
		fun `multiple errors are emitted in order`() = runTest {
			val collector = DefaultPersistenceErrorCollector()
			val error1 = createError("source1")
			val error2 = createError("source2")

			collector.reportError(error1)
			collector.reportError(error2)

			// Replay buffer should contain both
			val replayed = collector.errors.replayCache
			replayed.size shouldBe 2
			replayed[0] shouldBe error1
			replayed[1] shouldBe error2
		}
	}

	@Nested
	@DisplayName("Replay buffer")
	inner class ReplayBuffer {

		@Test
		fun `replay buffer retains up to 5 errors`() = runTest {
			val collector = DefaultPersistenceErrorCollector()

			repeat(5) { i ->
				collector.reportError(createError("src$i"))
			}

			collector.errors.replayCache.size shouldBe 5
		}

		@Test
		fun `replay buffer drops oldest when exceeding 5`() = runTest {
			val collector = DefaultPersistenceErrorCollector()

			repeat(7) { i ->
				collector.reportError(createError("src$i"))
			}

			// Only last 5 should remain
			collector.errors.replayCache.size shouldBe 5
			collector.errors.replayCache.first().source shouldBe "src2"
			collector.errors.replayCache.last().source shouldBe "src6"
		}
	}

	@Nested
	@DisplayName("clear")
	inner class Clear {

		@Test
		fun `clear does not throw`() {
			val collector = DefaultPersistenceErrorCollector()
			collector.clear()
		}
	}
}
