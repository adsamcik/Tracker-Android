package com.adsamcik.tracker.shared.base.database.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.adsamcik.tracker.shared.base.data.ActivityInfo

@Entity(tableName = "debug_activity")
data class DatabaseDebugActivity(
		val time: Long, @Embedded val activity: ActivityInfo,
		val action: String?
) {
	@PrimaryKey(autoGenerate = true)
	var id: Int = 0
}
