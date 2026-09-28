package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** Stable point ID used only to keep overlapping Location files from duplicating saved points. */
@Entity(
	tableName = "location_file_identity",
	primaryKeys = ["portable_id"],
	foreignKeys = [ForeignKey(
		entity = LocationSample::class,
		parentColumns = ["id"],
		childColumns = ["sample_id"],
		onDelete = ForeignKey.CASCADE,
	)],
	indices = [Index(value = ["sample_id"], unique = true, name = "idx_location_file_sample")],
)
data class LocationFileIdentityEntity(
	@ColumnInfo(name = "portable_id") val portableId: String,
	@ColumnInfo(name = "sample_id") val sampleId: Long,
	/** True only for this file import; older data from other importers can remain unknown. */
	@ColumnInfo(name = "imported") val imported: Boolean,
	/** Descriptive label from the file; it never grants recording or consent authority. */
	@ColumnInfo(name = "reported_origin") val reportedOrigin: String,
)
