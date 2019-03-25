package com.adsamcik.signalcollector.database.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "map_max_heat")
data class DatabaseMapMaxHeat(@PrimaryKey val zoom: Int, var maxHeat: Float)