package com.adsamcik.tracker.stats.api.repository

import com.adsamcik.tracker.shared.model.LocationSample

/**
 * Android presentation/export contract for route samples.
 */
interface LocationSampleRepository {
	suspend fun getSamplesBetween(fromMs: Long, toMs: Long): List<LocationSample>
	suspend fun getOrderedChunkBetween(
		fromMs: Long,
		toMs: Long,
		afterTimeMs: Long?,
		afterId: Long?,
		limit: Int,
	): List<LocationSample>
}
