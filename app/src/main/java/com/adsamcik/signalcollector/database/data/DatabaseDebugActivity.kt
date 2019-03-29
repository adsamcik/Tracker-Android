package com.adsamcik.signalcollector.database.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.adsamcik.signalcollector.activity.ActivityInfo

@Entity(tableName = "debug_activity")
data class DatabaseDebugActivity(val time: Long, @Embedded val activity: ActivityInfo, val action: String?) {
	@PrimaryKey(autoGenerate = true)
	var id: Int = 0
}