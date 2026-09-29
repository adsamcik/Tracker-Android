package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** Details carried by a user-imported Wi-Fi session observation. */
@Entity(
	tableName = "imported_wifi_child",
	primaryKeys = ["entry_identity", "entry_import_revision", "run_identity", "observation_identity", "item_index"],
	foreignKeys = [ForeignKey(
		entity = ImportedWifiObservationEntity::class,
		parentColumns = ["entry_identity", "entry_import_revision", "run_identity", "identity"],
		childColumns = ["entry_identity", "entry_import_revision", "run_identity", "observation_identity"],
		onDelete = ForeignKey.CASCADE,
	)],
	indices = [Index(value = ["entry_identity", "entry_import_revision", "run_identity", "observation_identity"], name = "idx_imported_wifi_child_parent")],
)
data class ImportedWifiChildEntity(
	@ColumnInfo(name = "entry_identity") val entryIdentity: String,
	@ColumnInfo(name = "entry_import_revision") val entryImportRevision: Long,
	@ColumnInfo(name = "run_identity") val runIdentity: String,
	@ColumnInfo(name = "observation_identity") val observationIdentity: String,
	@ColumnInfo(name = "item_index") val itemIndex: Int,
	@ColumnInfo(name = "provider_timestamp_nanos") val providerTimestampNanos: Long,
	@ColumnInfo(name = "observed_wall_time_ms") val observedWallTimeMs: Long,
	@ColumnInfo(name = "wall_time_uncertainty_ms") val wallTimeUncertaintyMs: Long,
	val bssid: String?,
	val ssid: String?,
	@ColumnInfo(name = "frequency_mhz") val frequencyMhz: Int,
	@ColumnInfo(name = "signal_dbm") val signalDbm: Int,
	@ColumnInfo(name = "content_checksum") val contentChecksum: String,
) { override fun toString(): String = "ImportedWifiChildEntity" }

/** Details carried by a user-imported Cell session observation. */
@Entity(
	tableName = "imported_cell_child",
	primaryKeys = ["entry_identity", "entry_import_revision", "run_identity", "observation_identity", "item_index"],
	foreignKeys = [ForeignKey(
		entity = ImportedCellObservationEntity::class,
		parentColumns = ["entry_identity", "entry_import_revision", "run_identity", "identity"],
		childColumns = ["entry_identity", "entry_import_revision", "run_identity", "observation_identity"],
		onDelete = ForeignKey.CASCADE,
	)],
	indices = [Index(value = ["entry_identity", "entry_import_revision", "run_identity", "observation_identity"], name = "idx_imported_cell_child_parent")],
)
data class ImportedCellChildEntity(
	@ColumnInfo(name = "entry_identity") val entryIdentity: String,
	@ColumnInfo(name = "entry_import_revision") val entryImportRevision: Long,
	@ColumnInfo(name = "run_identity") val runIdentity: String,
	@ColumnInfo(name = "observation_identity") val observationIdentity: String,
	@ColumnInfo(name = "item_index") val itemIndex: Int,
	@ColumnInfo(name = "provider_timestamp_nanos") val providerTimestampNanos: Long,
	@ColumnInfo(name = "observed_wall_time_ms") val observedWallTimeMs: Long,
	@ColumnInfo(name = "wall_time_uncertainty_ms") val wallTimeUncertaintyMs: Long,
	@ColumnInfo(name = "tower_identity") val towerIdentity: String?,
	@ColumnInfo(name = "radio_type") val radioType: String,
	val registered: Boolean,
	@ColumnInfo(name = "signal_dbm") val signalDbm: Int?,
	@ColumnInfo(name = "content_checksum") val contentChecksum: String,
) { override fun toString(): String = "ImportedCellChildEntity" }

/** Details carried by a user-imported sessionless Wi-Fi fact. */
@Entity(
	tableName = "imported_ambient_wifi_child",
	primaryKeys = ["archive_id", "fact_id", "semantic_revision", "item_index"],
	foreignKeys = [ForeignKey(
		entity = ImportedAmbientWifiFactEntity::class,
		parentColumns = ["archive_id", "fact_id", "semantic_revision"],
		childColumns = ["archive_id", "fact_id", "semantic_revision"],
		onDelete = ForeignKey.CASCADE,
	)],
	indices = [Index(value = ["archive_id", "fact_id", "semantic_revision"], name = "idx_imported_ambient_wifi_child_parent")],
)
data class ImportedAmbientWifiChildEntity(
	@ColumnInfo(name = "archive_id") val archiveId: String,
	@ColumnInfo(name = "fact_id") val factId: String,
	@ColumnInfo(name = "semantic_revision") val semanticRevision: Long,
	@ColumnInfo(name = "item_index") val itemIndex: Int,
	@ColumnInfo(name = "provider_timestamp_nanos") val providerTimestampNanos: Long,
	@ColumnInfo(name = "observed_wall_time_ms") val observedWallTimeMs: Long,
	@ColumnInfo(name = "wall_time_uncertainty_ms") val wallTimeUncertaintyMs: Long,
	val bssid: String?,
	val ssid: String?,
	@ColumnInfo(name = "frequency_mhz") val frequencyMhz: Int,
	@ColumnInfo(name = "signal_dbm") val signalDbm: Int,
	@ColumnInfo(name = "content_checksum") val contentChecksum: String,
) { override fun toString(): String = "ImportedAmbientWifiChildEntity" }

/** Details carried by a user-imported sessionless Cell fact. */
@Entity(
	tableName = "imported_ambient_cell_child",
	primaryKeys = ["archive_id", "fact_id", "semantic_revision", "item_index"],
	foreignKeys = [ForeignKey(
		entity = ImportedAmbientCellFactEntity::class,
		parentColumns = ["archive_id", "fact_id", "semantic_revision"],
		childColumns = ["archive_id", "fact_id", "semantic_revision"],
		onDelete = ForeignKey.CASCADE,
	)],
	indices = [Index(value = ["archive_id", "fact_id", "semantic_revision"], name = "idx_imported_ambient_cell_child_parent")],
)
data class ImportedAmbientCellChildEntity(
	@ColumnInfo(name = "archive_id") val archiveId: String,
	@ColumnInfo(name = "fact_id") val factId: String,
	@ColumnInfo(name = "semantic_revision") val semanticRevision: Long,
	@ColumnInfo(name = "item_index") val itemIndex: Int,
	@ColumnInfo(name = "provider_timestamp_nanos") val providerTimestampNanos: Long,
	@ColumnInfo(name = "observed_wall_time_ms") val observedWallTimeMs: Long,
	@ColumnInfo(name = "wall_time_uncertainty_ms") val wallTimeUncertaintyMs: Long,
	@ColumnInfo(name = "tower_identity") val towerIdentity: String?,
	@ColumnInfo(name = "radio_type") val radioType: String,
	val registered: Boolean,
	@ColumnInfo(name = "signal_dbm") val signalDbm: Int?,
	@ColumnInfo(name = "content_checksum") val contentChecksum: String,
) { override fun toString(): String = "ImportedAmbientCellChildEntity" }
