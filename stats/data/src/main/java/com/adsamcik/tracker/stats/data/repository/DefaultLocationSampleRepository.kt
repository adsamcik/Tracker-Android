package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.dao.LocationSampleDao
import com.adsamcik.tracker.shared.base.database.dao.getAllBetweenChunked
import com.adsamcik.tracker.shared.base.mapper.toModel
import com.adsamcik.tracker.stats.api.repository.LocationSampleRepository
import javax.inject.Inject

class DefaultLocationSampleRepository @Inject constructor(
	private val locationSampleDao: LocationSampleDao,
) : LocationSampleRepository {
	override suspend fun getSamplesBetween(fromMs: Long, toMs: Long) =
		locationSampleDao.getAllBetweenChunked(fromMs, toMs).map { it.toModel() }

	override suspend fun getNearestWithCoordinates(timeMs: Long, toleranceMs: Long) =
		locationSampleDao.getNearestWithCoordinates(timeMs, toleranceMs)?.toModel()

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
	).map { it.toModel() }
}
