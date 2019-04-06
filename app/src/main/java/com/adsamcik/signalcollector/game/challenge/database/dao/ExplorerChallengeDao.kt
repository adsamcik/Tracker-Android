package com.adsamcik.signalcollector.game.challenge.database.dao

import androidx.room.Dao
import androidx.room.Query

@Dao
interface ExplorerChallengeDao {

	@Query("SELECT DISTINCT COUNT(*) FROM location_data as current LEFT JOIN (SELECT round(lat, 4) as lat, 10 * (360.0 / (cos(lat * 0.01745) * 40075000)) as lon, COUNT(id) as count FROM location_data WHERE time < :from) as older ON round(current.lat, 4) = older.lat AND 10 * (360.0 / (cos(lon * 0.01745) * 40075000)) = older.lon WHERE time >= :from AND time <= :to AND count = null")
	fun newLocationsBetween(from: Long, to: Long): Int
}