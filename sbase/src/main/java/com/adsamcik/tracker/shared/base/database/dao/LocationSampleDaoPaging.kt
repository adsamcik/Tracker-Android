package com.adsamcik.tracker.shared.base.database.dao

import com.adsamcik.tracker.shared.base.database.data.LocationSample

const val DEFAULT_LOCATION_SAMPLE_CHUNK_SIZE = 2_000

suspend fun LocationSampleDao.getAllBetweenChunked(
	fromMs: Long,
	toMs: Long,
	chunkSize: Int = DEFAULT_LOCATION_SAMPLE_CHUNK_SIZE,
): List<LocationSample> {
	val samples = mutableListOf<LocationSample>()
	var afterTimeMs: Long? = null
	var afterId: Long? = null

	while (true) {
		val chunk = getChunkBetweenOrdered(
			fromMs = fromMs,
			toMs = toMs,
			afterTimeMs = afterTimeMs,
			afterId = afterId,
			limit = chunkSize,
		)
		if (chunk.isEmpty()) break

		samples.addAll(chunk)

		val lastSample = chunk.last()
		afterTimeMs = lastSample.timeMs
		afterId = lastSample.id
		if (chunk.size < chunkSize) {
			break
		}
	}

	return samples
}
