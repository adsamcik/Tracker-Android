package com.adsamcik.tracker.stats.api.repository

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ActivitySourceActionsContractTest {
	@Test
	fun `source erase authority and progress are nonnegative and typed`() {
		val request = ActivitySourceEraseRequest(
			expectedCollectedDataEpoch = 7L,
			expectedRevokedConsentEpoch = 9L,
			deletedAtMs = 10_000L,
		)
		request.expectedCollectedDataEpoch shouldBe 7L
		shouldThrow<IllegalArgumentException> {
			request.copy(expectedRevokedConsentEpoch = -1L)
		}
		shouldThrow<IllegalArgumentException> {
			ActivitySourceEraseResult.Erased(0, 0, 0, 0, 0, 0, 0)
		}
		shouldThrow<IllegalArgumentException> {
			ActivitySourceEraseResult.RetryableFailure(
				ActivitySourceEraseRetryableReason.CONCURRENT_STATE_CHANGE,
				importedEntriesErasedBeforeFailure = -1,
			)
		}
	}

	@Test
	fun `selected deletion requires explicit origin rather than time inference`() {
		val key = ActivityHistoryEntryKey("opaque")
		val local = ActivitySelectionDeletionRequest(key, ActivityHistoryOrigin.LOCAL, 10L)
		val imported = ActivitySelectionDeletionRequest(key, ActivityHistoryOrigin.IMPORTED, 10L)

		local.origin shouldBe ActivityHistoryOrigin.LOCAL
		imported.origin shouldBe ActivityHistoryOrigin.IMPORTED
		local.deletedAtMs shouldBe imported.deletedAtMs
		shouldThrow<IllegalArgumentException> { local.copy(deletedAtMs = -1L) }
	}
}
