package com.adsamcik.tracker.stats.api.repository

import com.adsamcik.tracker.shared.model.LocationSample

/**
 * Android presentation/export contract for route samples.
 */
interface LocationSampleRepository {
	suspend fun getSamplesBetween(fromMs: Long, toMs: Long): List<LocationSample>
	suspend fun getNearestWithCoordinates(timeMs: Long, toleranceMs: Long): LocationSample? =
		getSamplesBetween(timeMs - toleranceMs, timeMs + toleranceMs)
			.filter { it.latE7 != null && it.lonE7 != null }
			.minByOrNull { kotlin.math.abs(it.timeMs - timeMs) }

	suspend fun getOrderedChunkBetween(
		fromMs: Long,
		toMs: Long,
		afterTimeMs: Long?,
		afterId: Long?,
		limit: Int,
	): List<LocationSample>
}
