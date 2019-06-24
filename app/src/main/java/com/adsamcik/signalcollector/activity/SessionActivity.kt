package com.adsamcik.signalcollector.activity

import androidx.room.Entity
import androidx.room.PrimaryKey

//todo add icon
@Entity(tableName = "session_activity")
data class SessionActivity(
		/**
		 * Name of the activity (Also used as primary key and identifier).
		 */
		@PrimaryKey var id: String
)

/*data class NativeSessionActivity(val id: String, var titleRes: Int, var iconRes: Int) {
	val sessionActivity get() = SessionActivity(id)
}*/

enum class NativeSessionActivity {
	RUN,
	WALK,
	BICYCLE,
	VEHICLE
}