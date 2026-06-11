package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.dao.SkiRunSegmentDao
import com.adsamcik.tracker.shared.base.database.data.SkiRunSegment
import com.adsamcik.tracker.shared.base.database.data.SkiSegmentType
import com.adsamcik.tracker.shared.base.mapper.toModel
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class DefaultSkiRunSegmentRepositoryTest {

	private val skiRunSegmentDao: SkiRunSegmentDao = mockk()
	private val repository = DefaultSkiRunSegmentRepository(skiRunSegmentDao)

	@Test
	fun `getSegmentsByTimeRange delegates to dao`() = runTest {
		val segments = listOf(
			SkiRunSegment(
				id = 1L,
				sessionId = 7L,
				runIndex = 0,
				segmentType = SkiSegmentType.DOWNHILL_RUN,
				startTimeMs = 1_000L,
				endTimeMs = 2_000L,
				verticalM = -120f,
				distanceM = 900f,
				maxSpeedMps = 12f,
				avgSpeedMps = 8f,
				createdAt = 0L,
			),
		)
		coEvery { skiRunSegmentDao.getByTimeRange(1_000L, 5_000L) } returns segments

		repository.getSegmentsByTimeRange(1_000L, 5_000L) shouldBe segments.map { it.toModel() }

		coVerify(exactly = 1) { skiRunSegmentDao.getByTimeRange(1_000L, 5_000L) }
	}
}
