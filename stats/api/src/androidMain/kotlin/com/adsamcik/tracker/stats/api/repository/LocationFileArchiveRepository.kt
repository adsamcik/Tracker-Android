package com.adsamcik.tracker.stats.api.repository

/** Read-only source-owned membership of one validated Location file. Never starts collection. */
interface LocationFileArchiveRepository {
	suspend fun read(request: LocationFileArchiveRequest): LocationFileArchiveRead
}

data class LocationFileArchiveRequest(
	val archiveIdentity: String,
	val expectedCollectedDataEpoch: Long,
) {
	init {
		require(archiveIdentity.matches(Regex("[0-9a-f]{64}")))
		require(expectedCollectedDataEpoch >= 0L)
	}
}

sealed interface LocationFileArchiveRead {
	data class Found(
		val archiveIdentity: String,
		val declaredPointCount: Int,
		val availablePointCount: Int,
	) : LocationFileArchiveRead

	data object NotFound : LocationFileArchiveRead
	data object DataChanged : LocationFileArchiveRead
	/** A source import has not saved every declared association; its generic receipt cannot complete. */
	data object IncompleteMembership : LocationFileArchiveRead
	data object StorageUnavailable : LocationFileArchiveRead
}
