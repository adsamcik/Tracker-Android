package com.adsamcik.tracker.stats.api.repository

import com.adsamcik.tracker.shared.model.SkiRunSegment

/**
 * Android presentation contract for ski session detail data.
 */
interface SkiRunSegmentRepository {
	suspend fun getSegmentsByTimeRange(startMs: Long, endMs: Long): List<SkiRunSegment>
}
