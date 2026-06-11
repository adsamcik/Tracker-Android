package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.dao.SkiRunSegmentDao
import com.adsamcik.tracker.shared.base.mapper.toModel
import com.adsamcik.tracker.stats.api.repository.SkiRunSegmentRepository
import javax.inject.Inject

class DefaultSkiRunSegmentRepository @Inject constructor(
	private val skiRunSegmentDao: SkiRunSegmentDao,
) : SkiRunSegmentRepository {
	override suspend fun getSegmentsByTimeRange(startMs: Long, endMs: Long) =
		skiRunSegmentDao.getByTimeRange(startMs, endMs).map { it.toModel() }
}
