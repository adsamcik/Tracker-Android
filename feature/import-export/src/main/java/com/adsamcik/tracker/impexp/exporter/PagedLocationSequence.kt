package com.adsamcik.tracker.impexp.exporter

import com.adsamcik.tracker.shared.base.database.dao.LocationSampleDao
import com.adsamcik.tracker.shared.base.database.data.LocationSample
import kotlinx.coroutines.runBlocking

/**
 * Provides a bounded [Sequence] for exporters that still consume location data lazily.
 * Each iterator fetches the next DAO chunk only after the current chunk is exhausted.
 */
internal fun pagedLocationSequence(
	locationSampleDao: LocationSampleDao,
	fromMs: Long,
	toMs: Long,
	pageSize: Int,
): Sequence<LocationSample> = Sequence {
	object : Iterator<LocationSample> {
		private var buffer: List<LocationSample> = emptyList()
		private var index = 0
		private var exhausted = false
		private var afterTimeMs: Long? = null
		private var afterId: Long? = null

		override fun hasNext(): Boolean {
			if (index < buffer.size) return true
			if (exhausted) return false
			loadNextPage()
			return index < buffer.size
		}

		override fun next(): LocationSample {
			if (!hasNext()) {
				throw NoSuchElementException("No more location samples available")
			}
			return buffer[index++]
		}

		private fun loadNextPage() {
			buffer = runBlocking {
				locationSampleDao.getChunkBetweenOrdered(
					fromMs = fromMs,
					toMs = toMs,
					afterTimeMs = afterTimeMs,
					afterId = afterId,
					limit = pageSize,
				)
			}
			index = 0
			if (buffer.isEmpty()) {
				exhausted = true
				return
			}
			buffer.last().let { last ->
				afterTimeMs = last.timeMs
				afterId = last.id
			}
		}
	}
}
