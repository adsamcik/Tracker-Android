package com.adsamcik.tracker.impexp.exporter

import com.adsamcik.tracker.shared.base.database.dao.LocationSampleDao
import com.adsamcik.tracker.shared.base.database.dao.LocationPurposeDao
import com.adsamcik.tracker.shared.base.mapper.toModel
import com.adsamcik.tracker.shared.model.LocationSample
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
	initialAfterTimeMs: Long? = null,
	initialAfterId: Long? = null,
	verifyCollectedDataAccess: () -> Unit = {},
	locationPurposeDao: LocationPurposeDao? = null,
): Sequence<LocationSample> = Sequence {
	object : Iterator<LocationSample> {
		private var buffer: List<LocationSample> = emptyList()
		private var index = 0
		private var exhausted = false
		private var ambientAuthorityStamp: String? = null
		private var emittedAmbient = false
		private var afterTimeMs: Long? = initialAfterTimeMs
		private var afterId: Long? = initialAfterId

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
			verifyCollectedDataAccess()
			val before = locationPurposeDao?.let { runBlocking { it.ambientExportAuthorityStamp() } }
			if (emittedAmbient) check(before == ambientAuthorityStamp) { "Location export consent or retention changed" }
			val path = locationPurposeDao?.let { dao -> runBlocking {
				dao.visiblePathChunk(fromMs, toMs, afterTimeMs, afterId, pageSize)
			} }
			val chunk = path?.map { it.sample } ?: runBlocking {
				locationSampleDao.getChunkBetweenOrdered(
					fromMs = fromMs,
					toMs = toMs,
					afterTimeMs = afterTimeMs,
					afterId = afterId,
					limit = pageSize,
				)
			}
			verifyCollectedDataAccess()
			if (path?.any { it.ambientOnly } == true) {
				if (!emittedAmbient) ambientAuthorityStamp = before
				emittedAmbient = true
			}
			if (emittedAmbient) check(runBlocking { requireNotNull(locationPurposeDao).ambientExportAuthorityStamp() } == ambientAuthorityStamp) {
				"Location export consent or retention changed"
			}
			buffer = path?.map { row -> row.sample.toModel().copy(
				routeContinuityId = row.continuityId, routeSequence = row.continuitySequence, sessionless = row.ambientOnly,
			) } ?: chunk.map { it.toModel() }
			index = 0
			if (buffer.isEmpty()) {
				exhausted = true
				return
			}
			chunk.last().let { last ->
				afterTimeMs = last.timeMs
				afterId = last.id
			}
		}
	}
}
