package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Daily snapshot of database storage usage.
 *
 * One row per calendar day, recording table row counts and total
 * database file size. Used for the storage usage chart in settings
 * and for data retention monitoring.
 */
@Entity(
	tableName = "storage_size_snapshot",
	indices = [
		Index(value = ["epoch_day"], unique = true)
	]
)
data class StorageSizeSnapshotEntity(
	@PrimaryKey(autoGenerate = true)
	val id: Long = 0,

	/** Calendar day as epoch day (days since 1970-01-01). */
	@ColumnInfo(name = "epoch_day")
	val epochDay: Long,

	/** Total database file size in bytes. */
	@ColumnInfo(name = "database_size_bytes")
	val databaseSizeBytes: Long,

	/** Total number of location records (legacy + sessionless). */
	@ColumnInfo(name = "location_count")
	val locationCount: Int,

	/** Total number of tracking sessions. */
	@ColumnInfo(name = "session_count")
	val sessionCount: Int,

	/** Total number of Wi-Fi observation records. */
	@ColumnInfo(name = "wifi_count")
	val wifiCount: Int,

	/** Total number of cell tower sample records. */
	@ColumnInfo(name = "cell_count")
	val cellCount: Int,

	/** Total number of exploration cells discovered. */
	@ColumnInfo(name = "exploration_cell_count")
	val explorationCellCount: Int,

	/** Total number of cached route entries. */
	@ColumnInfo(name = "route_cache_count")
	val routeCacheCount: Int,

	@ColumnInfo(name = "created_at")
	val createdAt: Long,
)
