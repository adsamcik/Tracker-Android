package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.RewriteQueriesToDropUnusedColumns
import androidx.room.Transaction
import androidx.sqlite.db.SupportSQLiteQuery
import com.adsamcik.tracker.shared.base.database.data.DatabaseWifiData
import com.adsamcik.tracker.shared.base.database.data.DateRange
import com.adsamcik.tracker.shared.base.database.data.location.TimeLocation2DWeighted

/**
 * Data access object for base Wi-Fi network data.
 */
@Dao
interface WifiDataDao : BaseDao<DatabaseWifiData> {
	/**
	 * Delete all Wi-Fi networks from database.
	 */
	@Query("DELETE FROM tracker_session")
	fun deleteAll()

	/**
	 * Update signal data (longitude, latitude, altitude, strength) for single Wi-Fi network.
	 * Data is only updated if the signal strength (level) is higher than last recorded.
	 */
	@Query(
			"""
		UPDATE wifi_data
		SET longitude = :longitude, latitude = :latitude, altitude = :altitude, level = :level
		WHERE bssid = :bssid AND level < :level"""
	)
	fun updateSignalDataIfCloser(
			bssid: String,
			longitude: Double,
			latitude: Double,
			altitude: Double?,
			level: Int
	)

	/**
	 * Update Wi-Fi network data.
	 */
	@Query(
			"""
		UPDATE wifi_data
		SET last_seen = :lastSeen, ssid = :ssid, capabilities = :capabilities, frequency = :frequency
		WHERE bssid = :bssid
		"""
	)
	fun updateData(
			bssid: String,
			ssid: String,
			capabilities: String,
			frequency: Int,
			lastSeen: Long
	)

	/**
	 * Upsert (Update if exists, insert otherwise) Wi-Fi network data.
	 */
	@Transaction
	fun upsert(objList: Collection<DatabaseWifiData>) {
		val insertResult = insert(objList)
		val updateList = objList.filterIndexed { index, _ -> insertResult[index] == -1L }

		updateList.forEach {
			if (it.longitude != null && it.latitude != null) {
				updateSignalDataIfCloser(it.bssid, it.longitude, it.latitude, it.altitude, it.level)
			}
			updateData(it.bssid, it.ssid, it.capabilities, it.frequency, it.lastSeen)
		}
	}

	/**
	 * Get all Wi-Fi networks in the database.
	 */
	@RewriteQueriesToDropUnusedColumns
	@Query("SELECT * from wifi_data")
	fun getAll(): List<DatabaseWifiData>

	/**
	 * Get Wi-Fi networks from database but at mouse [count].
	 */
	@RewriteQueriesToDropUnusedColumns
	@Query("SELECT * from wifi_data LIMIT :count")
	fun getAll(count: Long): List<DatabaseWifiData>

	/**
	 * Get all Wi-Fi networks from database using raw query.
	 */
	@RawQuery
	fun getAll(query: SupportSQLiteQuery): List<DatabaseWifiData>

	/**
	 * Get all Wi-Fi networks from database that are inside latitude and longitude constraints.
	 */
	@Query(
			"""
		SELECT last_seen as time, latitude as lat, longitude as lon, COUNT(*) as weight FROM wifi_data
		WHERE latitude >= :bottomLatitude and latitude <= :topLatitude
			and longitude >= :leftLongitude  and longitude <= :rightLongitude
		GROUP BY lat, lon
		"""
	)
	fun getAllInside(
			topLatitude: Double,
			rightLongitude: Double,
			bottomLatitude: Double,
			leftLongitude: Double
	): List<TimeLocation2DWeighted>


	/**
	 * Get all Wi-Fi networks from database that are inside latitude, longitude and time constraints.
	 */
	@Query(
			"""
		SELECT last_seen as time, latitude as lat, longitude as lon, COUNT(*) as weight FROM wifi_data
		WHERE last_seen >= :from and last_seen <= :to
			and latitude >= :bottomLatitude and longitude >= :leftLongitude
			and latitude <= :topLatitude and longitude <= :rightLongitude
		GROUP BY lat, lon
	"""
	)
	fun getAllInsideAndBetween(
			from: Long,
			to: Long,
			topLatitude: Double,
			rightLongitude: Double,
			bottomLatitude: Double,
			leftLongitude: Double
	): List<TimeLocation2DWeighted>

	/**
	 * Count all Wi-Fi networks in the database.
	 */
	@Query("SELECT COUNT(bssid) from wifi_data")
	fun count(): Long

	/**
	 * Count all Wi-Fi networks in the database.
	 */
	@Query("SELECT COUNT(bssid) FROM wifi_data WHERE last_seen >= :from and last_seen <= :to")
	fun count(from: Long, to: Long): Long

	/**
	 * Get time of the most recent and oldest Wi-Fi network record.
	 */
	@Query("SELECT MIN(last_seen) as start, MAX(last_seen) as endInclusive from wifi_data")
	fun range(): DateRange
}

