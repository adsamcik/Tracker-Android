package com.adsamcik.tracker.shared.base.database.entity

import androidx.room.ColumnInfo

/**
 * Cell signal projection used by map heatmaps when both ASU and radio technology are needed.
 */
data class GeoCellSignalFeatureEntity(
    val lat: Double,
    val lon: Double,
    val time: Long,
    val weight: Double,
    @ColumnInfo(name = "network_type")
    val networkType: Int,
)
