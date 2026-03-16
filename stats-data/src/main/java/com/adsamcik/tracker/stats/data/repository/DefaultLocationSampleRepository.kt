package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.dao.LocationSampleDao
import com.adsamcik.tracker.stats.api.repository.LocationSampleRepository
import javax.inject.Inject

class DefaultLocationSampleRepository @Inject constructor(
	private val locationSampleDao: LocationSampleDao,
) : LocationSampleRepository {
	override suspend fun getSamplesBetween(fromMs: Long, toMs: Long) =
		locationSampleDao.getAllBetween(fromMs, toMs)

	override suspend fun getOrderedChunkBetween(
		fromMs: Long,
		toMs: Long,
		afterTimeMs: Long?,
		afterId: Long?,
		limit: Int,
	) = locationSampleDao.getChunkBetweenOrdered(
		fromMs = fromMs,
		toMs = toMs,
		afterTimeMs = afterTimeMs,
		afterId = afterId,
		limit = limit,
	)
}
