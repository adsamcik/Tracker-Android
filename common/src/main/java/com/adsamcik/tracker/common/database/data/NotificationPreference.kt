package com.adsamcik.tracker.common.database.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "notification")
data class NotificationPreference(
		@PrimaryKey val id: String,
		val order: Int,
		val isInTitle: Boolean,
		val isInContent: Boolean
) {
	companion object {
		val EMPTY = NotificationPreference("", 0, isInTitle = false, isInContent = false)
	}
}
