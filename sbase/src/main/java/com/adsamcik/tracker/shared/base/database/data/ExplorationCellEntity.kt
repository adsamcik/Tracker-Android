package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Represents a discovered S2 cell for exploration tracking.
 * Each row records that the user has visited this geographic cell.
 */
@Entity(
	tableName = "exploration_cell",
	indices = [
		Index(value = ["cell_token"], unique = true),
		Index(value = ["level"]),
		Index(value = ["first_discovered_at"]),
		Index(value = ["level", "first_discovered_at"])
	]
)
data class ExplorationCellEntity(
	@PrimaryKey(autoGenerate = true)
	val id: Long = 0,

	/** S2 cell token (hex string) - unique identifier for this cell */
	@ColumnInfo(name = "cell_token")
	val cellToken: String,

	/** S2 cell level (typically 14 for ~0.8 km² exploration cells) */
	@ColumnInfo(name = "level")
	val level: Int,

	/** Discovery quality tier (0=PASSED_THROUGH, 1=BRIEFLY_VISITED, 2=VISITED, 3=EXPLORED, 4=THOROUGHLY_EXPLORED) */
	@ColumnInfo(name = "quality")
	val quality: Int,

	/** Timestamp when this cell was first discovered */
	@ColumnInfo(name = "first_discovered_at")
	val firstDiscoveredAt: Long,

	/** Timestamp of the most recent visit */
	@ColumnInfo(name = "last_visited_at")
	val lastVisitedAt: Long,

	/** Number of times this cell has been visited */
	@ColumnInfo(name = "visit_count", defaultValue = "1")
	val visitCount: Int = 1,

	/** Bitmask of seasons visited (bit 0=spring, 1=summer, 2=autumn, 3=winter) */
	@ColumnInfo(name = "season_bitmask", defaultValue = "0")
	val seasonBitmask: Int = 0,

	/** Center latitude in E7 format */
	@ColumnInfo(name = "center_lat_e7")
	val centerLatE7: Int,

	/** Center longitude in E7 format */
	@ColumnInfo(name = "center_lon_e7")
	val centerLonE7: Int,

	@ColumnInfo(name = "created_at")
	val createdAt: Long
)
