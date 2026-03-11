package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A frequently visited location cluster. Centers are stored as E7 integers
 * (degrees * 1e7) matching the sessionless coordinate format.
 */
@Entity(
	tableName = "frequent_place",
	indices = [
		Index(
			value = ["center_lat_e7", "center_lon_e7"],
			name = "idx_frequent_place_coords"
		),
		Index(value = ["last_visit_ms"])
	]
)
data class FrequentPlaceEntity(
	@PrimaryKey(autoGenerate = true)
	val id: Long = 0,

	@ColumnInfo(name = "center_lat_e7")
	val centerLatE7: Int,

	@ColumnInfo(name = "center_lon_e7")
	val centerLonE7: Int,

	@ColumnInfo(name = "radius_m")
	val radiusM: Float,

	@ColumnInfo(name = "visit_count")
	val visitCount: Int,

	@ColumnInfo(name = "first_visit_ms")
	val firstVisitMs: Long,

	@ColumnInfo(name = "last_visit_ms")
	val lastVisitMs: Long,

	@ColumnInfo(name = "auto_category")
	val autoCategory: String?,

	@ColumnInfo(name = "created_at")
	val createdAt: Long
)
