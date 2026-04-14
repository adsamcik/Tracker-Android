package com.adsamcik.tracker.tracker.data

import android.database.sqlite.SQLiteDatabaseLockedException
import android.database.sqlite.SQLiteException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("withDatabaseRetry")
class DatabaseRetryTest {

	@Nested
	@DisplayName("Successful execution")
	inner class SuccessfulExecution {

		@Test
		fun `returns result on first attempt`() = runTest {
			val result = withDatabaseRetry { 42 }
			result shouldBe 42
		}

		@Test
		fun `returns result after transient failures`() = runTest {
			var attempts = 0
			val result = withDatabaseRetry(maxAttempts = 3) {
				attempts++
				if (attempts < 3) throw SQLiteDatabaseLockedException("locked")
				"success"
			}
			result shouldBe "success"
			attempts shouldBe 3
		}
	}

	@Nested
	@DisplayName("Retry behavior")
	inner class RetryBehavior {

		@Test
		fun `retries on SQLiteDatabaseLockedException`() = runTest {
			var attempts = 0
			val result = withDatabaseRetry(maxAttempts = 2) {
				attempts++
				if (attempts == 1) throw SQLiteDatabaseLockedException("locked")
				"ok"
			}
			result shouldBe "ok"
			attempts shouldBe 2
		}

		@Test
		fun `retries on SQLiteException with database is locked message`() = runTest {
			var attempts = 0
			// Android stubs don't pass the message to super(), so we override getMessage()
			val lockedException = object : SQLiteException("database is locked") {
				override val message: String get() = "database is locked"
			}
			val result = withDatabaseRetry(maxAttempts = 2) {
				attempts++
				if (attempts == 1) throw lockedException
				"ok"
			}
			result shouldBe "ok"
			attempts shouldBe 2
		}
	}

	@Nested
	@DisplayName("Non-retryable exceptions")
	inner class NonRetryable {

		@Test
		fun `SQLiteException without locked message is not retried`() = runTest {
			shouldThrow<SQLiteException> {
				withDatabaseRetry(maxAttempts = 3) {
					throw SQLiteException("constraint violation")
				}
			}
		}

		@Test
		fun `non-SQLite exceptions are not retried`() = runTest {
			var attempts = 0
			shouldThrow<IllegalStateException> {
				withDatabaseRetry(maxAttempts = 3) {
					attempts++
					throw IllegalStateException("not a db error")
				}
			}
			attempts shouldBe 1
		}
	}

	@Nested
	@DisplayName("Max attempts exhausted")
	inner class MaxAttemptsExhausted {

		@Test
		fun `throws after exhausting all retries`() = runTest {
			var attempts = 0
			shouldThrow<SQLiteDatabaseLockedException> {
				withDatabaseRetry(maxAttempts = 3) {
					attempts++
					throw SQLiteDatabaseLockedException("always locked")
				}
			}
			attempts shouldBe 3
		}

		@Test
		fun `throws on single attempt`() = runTest {
			shouldThrow<SQLiteDatabaseLockedException> {
				withDatabaseRetry(maxAttempts = 1) {
					throw SQLiteDatabaseLockedException("locked")
				}
			}
		}
	}

	@Nested
	@DisplayName("Preconditions")
	inner class Preconditions {

		@Test
		fun `maxAttempts must be at least 1`() = runTest {
			shouldThrow<IllegalArgumentException> {
				withDatabaseRetry(maxAttempts = 0) { "nope" }
			}
		}

		@Test
		fun `negative maxAttempts throws`() = runTest {
			shouldThrow<IllegalArgumentException> {
				withDatabaseRetry(maxAttempts = -1) { "nope" }
			}
		}
	}
}
