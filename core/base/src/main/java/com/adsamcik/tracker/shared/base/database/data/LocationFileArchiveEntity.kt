package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** One validated Location file's content identity. Generic import receipts own completion. */
@Entity(tableName = "location_file_archive", primaryKeys = ["identity"])
data class LocationFileArchiveEntity(
	val identity: String,
	@ColumnInfo(name = "source_format") val sourceFormat: String,
	@ColumnInfo(name = "source_schema_version") val sourceSchemaVersion: Int,
	@ColumnInfo(name = "collected_data_epoch") val collectedDataEpoch: Long,
	@ColumnInfo(name = "point_count") val pointCount: Int,
) {
	init {
		require(identity.matches(LOCATION_ARCHIVE_IDENTITY))
		require(sourceFormat == FORMAT && sourceSchemaVersion == VERSION)
		require(collectedDataEpoch >= 0L && pointCount in 0..MAX_POINTS)
	}

	companion object {
		const val FORMAT = "tracker-location"
		const val VERSION = 1
		const val MAX_POINTS = 2_000_000
	}
}

/** Exact declared membership, including duplicate or unavailable points; no time-based join. */
@Entity(
	tableName = "location_file_archive_point",
	primaryKeys = ["archive_identity", "ordinal"],
	foreignKeys = [
		ForeignKey(entity = LocationFileArchiveEntity::class, parentColumns = ["identity"],
			childColumns = ["archive_identity"], onDelete = ForeignKey.CASCADE),
		ForeignKey(entity = LocationSample::class, parentColumns = ["id"],
			childColumns = ["sample_id"], onDelete = ForeignKey.SET_NULL),
	],
	indices = [
		Index(value = ["archive_identity", "portable_id"], unique = true,
			name = "idx_location_archive_point_identity"),
		Index(value = ["sample_id"], name = "idx_location_archive_point_sample"),
	],
)
data class LocationFileArchivePointEntity(
	@ColumnInfo(name = "archive_identity") val archiveIdentity: String,
	val ordinal: Int,
	@ColumnInfo(name = "portable_id") val portableId: String,
	@ColumnInfo(name = "sample_id") val sampleId: Long?,
) {
	init {
		require(archiveIdentity.matches(LOCATION_ARCHIVE_IDENTITY))
		require(ordinal in 0 until LocationFileArchiveEntity.MAX_POINTS)
		require(portableId.matches(LOCATION_ARCHIVE_POINT_IDENTITY))
		require(sampleId == null || sampleId > 0L)
	}
}

private val LOCATION_ARCHIVE_IDENTITY = Regex("[0-9a-f]{64}")
private val LOCATION_ARCHIVE_POINT_IDENTITY = Regex("[A-Za-z0-9._:-]{1,128}")
