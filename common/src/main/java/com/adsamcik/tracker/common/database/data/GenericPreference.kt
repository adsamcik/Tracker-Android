package com.adsamcik.tracker.common.database.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "generic")
data class GenericPreference(
		@PrimaryKey val id: String,
		val value: String
) {
	fun toLong(): Long = value.toLong()
	fun toInt(): Int = value.toInt()
	fun toFloat(): Float = value.toFloat()
	fun toDouble(): Double = value.toDouble()
}
