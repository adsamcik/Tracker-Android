package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.SourceAwareHistoryPageUnavailableReason
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.Test

class ImportedHistoryUnionContractTest {
	@Test
	fun `imported recency orders newest tuple then source then opaque member identity`() {
		val rows = listOf(
			recency(HistorySource.CELL, 100L, "member-z"),
			recency(HistorySource.WIFI, 100L, "member-a"),
			recency(HistorySource.WIFI, 100L, "member-z"),
			recency(HistorySource.ACTIVITY, 200L, "member-a"),
		)

		rows.sortedWith(importedHistoryRecencyOrder) shouldContainExactly listOf(
			recency(HistorySource.ACTIVITY, 200L, "member-a"),
			recency(HistorySource.WIFI, 100L, "member-z"),
			recency(HistorySource.WIFI, 100L, "member-a"),
			recency(HistorySource.CELL, 100L, "member-z"),
		)
		rows.first().newestMemberTieIdentity.toString() shouldBe
			"ImportedHistoryRecencyTieIdentity"
	}

	@Test
	fun `imported recency rejects unsupported source and unrelated page failures`() {
		shouldThrow<IllegalArgumentException> {
			recency(HistorySource.STEPS, 100L, "member")
		}
		shouldThrow<IllegalArgumentException> {
			ImportedHistoryEligiblePage.Unavailable(
				SourceAwareHistoryPageUnavailableReason.CANDIDATE_SCAN_LIMIT,
			)
		}
	}

	private fun recency(
		source: HistorySource,
		startTimeMs: Long,
		tieIdentity: String,
	) = ImportedHistoryRecency(
		source = source,
		newestMemberStartTimeMs = startTimeMs,
		newestMemberTieIdentity = ImportedHistoryRecencyTieIdentity(tieIdentity),
	)
}
