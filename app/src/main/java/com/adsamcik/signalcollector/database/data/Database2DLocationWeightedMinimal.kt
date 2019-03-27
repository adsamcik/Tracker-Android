package com.adsamcik.signalcollector.database.data

import androidx.room.ColumnInfo


data class Database2DLocationWeightedMinimal(
		val id: Int,
		@ColumnInfo(name = "lat")
		val latitude: Double,
		@ColumnInfo(name = "lon")
		val longitude: Double,
		val weight: Double)