package com.adsamcik.signalcollector.game.challenge.dao

import androidx.room.Dao
import androidx.room.Query

@Dao
interface ChallengeDao {

	@Query("SELECT * FROM location_data LEFT JOIN (SELECT lat, lon, COUNT(id) as count FROM location_data WHERE time < :from) WHERE time >= :from AND time <= :to AND count = null")
	fun newLocationsBetween(from: Long, to: Long)
}