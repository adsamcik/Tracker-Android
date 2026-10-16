package com.adsamcik.tracker.stats.api.repository

/** Read-only source-owned membership of one validated Location file. Never starts collection. */
interface LocationFileArchiveRepository {
	/**
	 * Reads declared and available point membership for the exact archive and data epoch without
	 * acquiring Location.
	 */
	suspend fun read(request: LocationFileArchiveRequest): LocationFileArchiveRead
}

/**
 * Exact lowercase SHA-256 file identity and expected collected-data epoch for a saved Location
 * membership read.
 */
data class LocationFileArchiveRequest(
	val archiveIdentity: String,
	val expectedCollectedDataEpoch: Long,
) {
	init {
		require(archiveIdentity.matches(Regex("[0-9a-f]{64}")))
		require(expectedCollectedDataEpoch >= 0L)
	}
}

/**
 * Saved Location file membership result, separating unavailable storage, changed data and
 * incomplete associations.
 */
sealed interface LocationFileArchiveRead {
	/**
	 * Reports original declared and currently available point membership for the requested saved
	 * Location file.
	 */
	data class Found(
		val archiveIdentity: String,
		val declaredPointCount: Int,
		val availablePointCount: Int,
	) : LocationFileArchiveRead

	/**
	 * The requested Location file identity has no saved archive membership.
	 */
	data object NotFound : LocationFileArchiveRead
	/**
	 * The expected collected-data epoch no longer matches the current saved data.
	 */
	data object DataChanged : LocationFileArchiveRead
	/** A source import has not saved every declared association; its generic receipt cannot complete. */
	data object IncompleteMembership : LocationFileArchiveRead
	/**
	 * Storage cannot provide the required snapshot or commit; this is not an empty-data result.
	 */
	data object StorageUnavailable : LocationFileArchiveRead
}
