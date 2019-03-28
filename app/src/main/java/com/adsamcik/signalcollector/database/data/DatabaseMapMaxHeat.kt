package com.adsamcik.signalcollector.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity

@Entity(tableName = "map_max_heat", primaryKeys = ["layer_name", "zoom"])
data class DatabaseMapMaxHeat(@ColumnInfo(name = "layer_name") val layerName: String, val zoom: Int, @ColumnInfo(name = "max_heat") var maxHeat: Float)