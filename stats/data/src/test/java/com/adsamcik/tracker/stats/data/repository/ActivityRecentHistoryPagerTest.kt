package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.dao.ActivityLogicalHistoryCandidate
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryCause
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryEntry
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryEntryKey
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryPage
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryProductState
import com.adsamcik.tracker.stats.api.value.EpochMs
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ActivityRecentHistoryPagerTest {
	@Test
	fun `pages in recency order and stops after requested accepted results`() = runTest {
		val pages = listOf(
			listOf(candidate(1L, factStartMs = 10L, logicalRecencyMs = 40L), candidate(2L, 30L, 30L)),
			listOf(candidate(3L, 20L, 20L), candidate(4L, 10L, 10L)),
		)
		var pageCalls = 0
		val requestedCursors = mutableListOf<Pair<Long?, Long?>>()

		val result = ActivityRecentHistoryPager.collect(
			limit = 3,
			pageSize = 2,
			candidateBudget = 8,
			loadPage = { _, beforeStart, beforeId ->
				requestedCursors += beforeStart to beforeId
				pages.getOrElse(pageCalls++) { emptyList() }
			},
			compose = { seeds ->
				ActivityHistoryPage.Available(seeds.map { entry(it.id * 10L) })
			},
		) as ActivityHistoryPage.Available

		result.entries.map { it.startTime.raw } shouldContainExactly listOf(10L, 20L, 30L)
		pageCalls shouldBe 2
		requestedCursors shouldContainExactly listOf(null to null, 30L to 2L)
	}

	@Test
	fun `candidate budget exhaustion is typed and hides partial results`() = runTest {
		val result = ActivityRecentHistoryPager.collect(
			limit = 3,
			pageSize = 1,
			candidateBudget = 2,
			loadPage = { _, _, _ -> listOf(candidate(1L, 1L, 1L)) },
			compose = { ActivityHistoryPage.Available(emptyList()) },
		)

		result shouldBe ActivityHistoryPage.Failed(ActivityHistoryCause.READ_BUDGET_EXCEEDED)
	}

	private fun candidate(id: Long, factStartMs: Long, logicalRecencyMs: Long) =
		ActivityLogicalHistoryCandidate(
			segment = SessionSegment(
				id = id,
				startTimeMs = factStartMs,
				endTimeMs = factStartMs + 1L,
				distanceM = 0f,
				steps = null,
				primaryActivity = null,
				activityConfidence = null,
				sampleCount = 0,
				source = SegmentSource.INFERRED_HIGH_CONFIDENCE,
				inferenceVersion = null,
				createdAt = factStartMs,
				logicalTrackingId = "logical-$id",
				serviceRunId = "run-$id",
			),
			logicalRecencyStartMs = logicalRecencyMs,
			logicalRecencySegmentId = id,
		)

	private fun entry(startMs: Long) = ActivityHistoryEntry(
		key = ActivityHistoryEntryKey("activity-$startMs"),
		startTime = EpochMs(startMs),
		endTime = EpochMs(startMs),
		storedZoneIds = emptySet(),
		state = ActivityHistoryProductState.UNAVAILABLE,
		coverage = ActivityHistoryCoverage.NONE,
		activeTime = null,
		fragments = emptyList(),
		causes = setOf(ActivityHistoryCause.NO_QUALIFIED_FACTS),
	)
}
