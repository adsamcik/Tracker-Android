package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/** Local organization metadata. No observations, capture lifecycle or foreign synchronization ID. */
@Entity(
	tableName = "organization_annotation",
	primaryKeys = ["local_id"],
	indices = [
		Index(value = ["recording_adapter_key"], unique = true, name = "idx_organization_adapter"),
		Index(value = ["hidden", "start_time_ms", "end_time_ms"], name = "idx_organization_bounds"),
		Index(value = ["end_time_ms"], name = "idx_organization_end"),
	],
)
data class OrganizationAnnotationEntity(
	@ColumnInfo(name = "local_id") val localId: String,
	val revision: Long,
	@ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
	@ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long,
	@ColumnInfo(name = "recording_adapter_key") val recordingAdapterKey: String?,
	@ColumnInfo(name = "start_time_ms") val startTimeMs: Long?,
	@ColumnInfo(name = "end_time_ms") val endTimeMs: Long?,
	@ColumnInfo(name = "display_zone_id") val displayZoneId: String?,
	val name: String?,
	@ColumnInfo(name = "activity_label_id") val activityLabelId: Long?,
	val hidden: Boolean,
	/** Null is ordinary interval grouping; a stored empty array is an imported metadata-only group. */
	@ColumnInfo(name = "imported_source_selection") val importedSourceSelection: String? = null,
	/** Descriptive bundle text, never a foreign Activity ID or a new global Activity label. */
	@ColumnInfo(name = "imported_activity_label") val importedActivityLabel: String? = null,
)
