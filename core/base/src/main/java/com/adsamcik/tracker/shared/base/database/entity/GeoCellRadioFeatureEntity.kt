package com.adsamcik.tracker.shared.base.database.entity

import androidx.room.ColumnInfo

/**
 * Identity-bearing serving-cell observation projection used by radio-aware map visualizations.
 */
data class GeoCellRadioFeatureEntity(
	val lat: Double,
	val lon: Double,
	val time: Long,
	@ColumnInfo(name = "cell_id")
	val cellId: Long,
	val lac: Int,
	val mcc: Int,
	val mnc: Int,
	@ColumnInfo(name = "network_type")
	val networkType: Int,
	val asu: Int?,
)
