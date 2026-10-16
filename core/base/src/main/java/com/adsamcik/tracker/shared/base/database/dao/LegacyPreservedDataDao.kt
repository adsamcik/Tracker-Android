package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Query
import com.adsamcik.tracker.shared.base.data.NetworkOperator
import com.adsamcik.tracker.shared.base.database.data.LegacyLocationWifiCount
import com.adsamcik.tracker.shared.base.database.data.LegacyRejectedTrackerSession

/** Typed access to the finite released compatibility records. They carry no inferred purpose. */
@Dao
interface LegacyPreservedDataDao {
	/**
	 * Reads `legacy_rejected_tracker_session` rows. Query conditions include `id = :id`. Returns null
	 * when no row matches.
	 */
	@Query("SELECT * FROM legacy_rejected_tracker_session WHERE id = :id")
	suspend fun rejectedSession(id: Long): LegacyRejectedTrackerSession?

	/** The limit is a page size; measured time and ID resume the same selected date range. */
	@Query("""SELECT * FROM legacy_location_wifi_count WHERE time >= :fromMs AND time < :toMs
		AND (:afterTimeMs IS NULL OR time > :afterTimeMs OR
		(time = :afterTimeMs AND id > COALESCE(:afterId, 0))) ORDER BY time, id LIMIT :limit""")
	suspend fun wifiCounts(fromMs: Long, toMs: Long, limit: Int,
		afterTimeMs: Long? = null, afterId: Long? = null): List<LegacyLocationWifiCount>

	/** Original released scan-count keys; this never invents an AP or a tracking purpose. */
	@Query("""SELECT 'legacy_wifi_count' AS kind, 'LEGACY' AS origin, 'UNKNOWN' AS purpose,
		length(CAST(id AS TEXT)) || ':' || CAST(id AS TEXT) AS identity, time AS startTimeMs, NULL AS endTimeMs,
		id AS rowId, NULL AS writerId, NULL AS writerVersion, NULL AS factId, NULL AS factRevision,
		NULL AS itemIndex, NULL AS physicalEventId, NULL AS physicalIdentity
		FROM legacy_location_wifi_count WHERE time >= :fromMs AND time < :toMs
		AND (:afterTime IS NULL OR time < :afterTime OR
		(time = :afterTime AND ('legacy:legacy_wifi_count' > :afterTag OR
		('legacy:legacy_wifi_count' = :afterTag AND length(CAST(id AS TEXT)) || ':' || CAST(id AS TEXT) > :afterIdentity))))
		ORDER BY time DESC, identity ASC LIMIT :limit""")
	suspend fun wifiCountHistoryCandidates(fromMs: Long, toMs: Long, afterTime: Long?, afterTag: String?,
		afterIdentity: String?, limit: Int): List<MeasuredHistoryCandidate>

	/**
	 * Reads `legacy_location_wifi_count` rows. Query conditions include `id IN (:ids)`.
	 */
	@Query("SELECT * FROM legacy_location_wifi_count WHERE id IN (:ids)")
	suspend fun wifiCountsByIds(ids: List<Long>): List<LegacyLocationWifiCount>

	/**
	 * Reads `network_operator` rows. Query conditions include `mcc = :mcc`, `mnc = :mnc`. Returns
	 * null when no row matches.
	 */
	@Query("SELECT * FROM network_operator WHERE mcc = :mcc AND mnc = :mnc")
	suspend fun networkOperator(mcc: String, mnc: String): NetworkOperator?

	/** Invoked inside the existing trip-retention authority transaction. */
	@Query(("DELETE FROM legacy_rejected_tracker_session WHERE start >= 0 AND `end` >= 0 AND start < " +
        ":cutoff AND `end` < :cutoff"))
	suspend fun deleteRejectedSessionsOlderThan(cutoff: Long)

	/** Invoked inside the existing radio-retention authority transaction. */
	@Query("DELETE FROM legacy_location_wifi_count WHERE time < :cutoff")
	suspend fun deleteWifiCountsOlderThan(cutoff: Long)

	/** The existing full-clear transaction owns these deletes; no purpose is guessed. */
	@Query("DELETE FROM legacy_rejected_tracker_session")
	fun deleteAllRejectedSessions()
	/**
	 * Deletes `legacy_location_wifi_count` rows.
	 */
	@Query("DELETE FROM legacy_location_wifi_count")
	fun deleteAllWifiCounts()
	/**
	 * Deletes `network_operator` rows.
	 */
	@Query("DELETE FROM network_operator")
	fun deleteAllNetworkOperators()
}
