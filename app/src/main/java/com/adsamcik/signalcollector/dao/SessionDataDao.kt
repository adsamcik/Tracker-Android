package com.adsamcik.signalcollector.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Update
import com.adsamcik.signalcollector.data.TrackingSession

@Dao
interface SessionDataDao {
	@Insert
	fun insert(session: TrackingSession)

	@Update
	fun update(session: TrackingSession)

}