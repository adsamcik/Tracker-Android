package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.dao.ActivityLogicalHistoryCandidate
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryCause
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryPage
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Bounded keyset traversal that stops as soon as the requested truthful entries are composed. */
internal object ActivityRecentHistoryPager {
	@Suppress("LongParameterList")
	suspend fun collect(
		limit: Int,
		pageSize: Int,
		candidateBudget: Int,
		loadPage: suspend (Int, Long?, Long?) -> List<ActivityLogicalHistoryCandidate>,
		compose: suspend (List<SessionSegment>) -> ActivityHistoryPage,
	): ActivityHistoryPage {
		val accepted = mutableListOf<com.adsamcik.tracker.stats.api.repository.ActivityHistoryEntry>()
		var scanned = 0
		var beforeStartTimeMs: Long? = null
		var beforeSegmentId: Long? = null
		while (accepted.size < limit) {
			currentCoroutineContext().ensureActive()
			val remaining = candidateBudget - scanned
			if (remaining == 0) return ActivityHistoryPage.Failed(ActivityHistoryCause.READ_BUDGET_EXCEEDED)
			val requestedPageSize = minOf(pageSize, remaining)
			val page = loadPage(requestedPageSize, beforeStartTimeMs, beforeSegmentId)
			if (page.isEmpty()) break
			require(page.size <= requestedPageSize) { "Activity candidate page exceeded its requested bound" }
			scanned += page.size
			when (val composed = compose(page.map { it.segment })) {
				is ActivityHistoryPage.Available -> accepted += composed.entries
				is ActivityHistoryPage.Failed -> return composed
			}
			val last = page.last()
			beforeStartTimeMs = last.logicalRecencyStartMs
			beforeSegmentId = last.logicalRecencySegmentId
			if (page.size < requestedPageSize) break
		}
		return ActivityHistoryPage.Available(accepted.take(limit))
	}
}
