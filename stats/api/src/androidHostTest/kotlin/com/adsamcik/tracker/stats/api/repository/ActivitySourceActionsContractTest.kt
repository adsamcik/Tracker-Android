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
		shouldThrow<IllegalArgumentException> {
			ActivitySourceEraseResult.Blocked(
				ActivitySourceEraseBlockedReason.STALE_REQUEST,
				importedEntriesErasedBeforeFailure = -1,
			)
		}
		shouldThrow<IllegalArgumentException> {
			ActivitySourceEraseResult.ContinuationRequired(
				localProductErasedBeforeContinuation = false,
				importedLiveEntryCount = 0,
				importedRetainedEntryCount = 0,
				importedPhysicalRunCount = 0,
			)
		}
	}

	@Test
	fun `selected deletion requires source-issued selection rather than time inference`() {
		val key = ActivityHistoryEntryKey("opaque")
		val localSelection = ActivityHistorySelection.Local(key)
		val importedSelection = ActivityHistorySelection.Imported(
			ActivityImportedHistorySelection(
				key = key,
				identity = ActivityImportedHistoryIdentity("a".repeat(64)),
				importRevision = 2L,
				contentChecksum = ActivityImportedHistoryDigest("b".repeat(64)),
				runDeletionScopes = listOf(
					ActivityImportedHistoryRunDeletionScope(
						ActivityImportedHistoryIdentity("c".repeat(64)),
						ActivityImportedHistoryDeletionScopeDigest("d".repeat(64)),
					),
				),
				windowIdentities = listOf(ActivityImportedHistoryIdentity("e".repeat(64))),
				readSnapshot = ActivityImportedHistoryReadSnapshot(7L, 9L),
			),
		)
		val local = ActivitySelectionDeletionRequest(localSelection, 10L)
		val imported = ActivitySelectionDeletionRequest(importedSelection, 10L)

		local.origin shouldBe ActivityHistoryOrigin.LOCAL
		imported.origin shouldBe ActivityHistoryOrigin.IMPORTED
		imported.selection shouldBe importedSelection
		local.deletedAtMs shouldBe imported.deletedAtMs
		shouldThrow<IllegalArgumentException> { local.copy(deletedAtMs = -1L) }
	}
}
