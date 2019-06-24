package com.adsamcik.signalcollector.common.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

//todo add icon
@Entity(tableName = "activity", indices = [Index("name")])
data class SessionActivity(
		@PrimaryKey var id: Long,
		var name: String,
		val iconName: String?
)