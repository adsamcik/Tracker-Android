package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** One accepted Wi-Fi result owned by one exact session fact revision. */
@Entity(
	tableName = "wifi_captured_child",
	primaryKeys = ["writer_projection_id", "writer_projection_version", "logical_fact_id", "semantic_revision", "item_index"],
	foreignKeys = [ForeignKey(
		entity = WifiCapturedFactRevisionEntity::class,
		parentColumns = ["writer_projection_id", "writer_projection_version", "logical_fact_id", "semantic_revision"],
		childColumns = ["writer_projection_id", "writer_projection_version", "logical_fact_id", "semantic_revision"],
		onDelete = ForeignKey.RESTRICT,
	)],
	indices = [
		Index(value = ["observed_wall_time_ms"], name = "idx_wifi_captured_child_time"),
		Index(value = ["bssid", "observed_wall_time_ms"], name = "idx_wifi_captured_child_identity_time"),
	],
)
data class WifiCapturedChildEntity(
	@ColumnInfo(name = "writer_projection_id") val writerProjectionId: String,
	@ColumnInfo(name = "writer_projection_version") val writerProjectionVersion: Int,
	@ColumnInfo(name = "logical_fact_id") val logicalFactId: String,
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
) {
	init { requireRadioChild(itemIndex, providerTimestampNanos, observedWallTimeMs, wallTimeUncertaintyMs, contentChecksum) }
	override fun toString(): String = "WifiCapturedChildEntity"
}

/** One accepted Cell result owned by one exact session fact revision. */
@Entity(
	tableName = "cell_captured_child",
	primaryKeys = ["writer_projection_id", "writer_projection_version", "logical_fact_id", "semantic_revision", "item_index"],
	foreignKeys = [ForeignKey(
		entity = CellCapturedFactRevisionEntity::class,
		parentColumns = ["writer_projection_id", "writer_projection_version", "logical_fact_id", "semantic_revision"],
		childColumns = ["writer_projection_id", "writer_projection_version", "logical_fact_id", "semantic_revision"],
		onDelete = ForeignKey.RESTRICT,
	)],
	indices = [
		Index(value = ["observed_wall_time_ms"], name = "idx_cell_captured_child_time"),
		Index(value = ["tower_identity", "observed_wall_time_ms"], name = "idx_cell_captured_child_identity_time"),
	],
)
data class CellCapturedChildEntity(
	@ColumnInfo(name = "writer_projection_id") val writerProjectionId: String,
	@ColumnInfo(name = "writer_projection_version") val writerProjectionVersion: Int,
	@ColumnInfo(name = "logical_fact_id") val logicalFactId: String,
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
) {
	init { requireRadioChild(itemIndex, providerTimestampNanos, observedWallTimeMs, wallTimeUncertaintyMs, contentChecksum) }
	override fun toString(): String = "CellCapturedChildEntity"
}

/** One accepted Wi-Fi result owned by one exact sessionless fact revision. */
@Entity(
	tableName = "ambient_wifi_child",
	primaryKeys = ["writer_id", "writer_version", "logical_fact_id", "semantic_revision", "item_index"],
	foreignKeys = [ForeignKey(
		entity = AmbientWifiFactRevisionEntity::class,
		parentColumns = ["writer_id", "writer_version", "logical_fact_id", "semantic_revision"],
		childColumns = ["writer_id", "writer_version", "logical_fact_id", "semantic_revision"],
		onDelete = ForeignKey.RESTRICT,
	)],
	indices = [
		Index(value = ["observed_wall_time_ms"], name = "idx_ambient_wifi_child_time"),
		Index(value = ["bssid", "observed_wall_time_ms"], name = "idx_ambient_wifi_child_identity_time"),
	],
)
data class AmbientWifiChildEntity(
	@ColumnInfo(name = "writer_id") val writerId: String,
	@ColumnInfo(name = "writer_version") val writerVersion: Int,
	@ColumnInfo(name = "logical_fact_id") val logicalFactId: String,
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
) {
	init { requireRadioChild(itemIndex, providerTimestampNanos, observedWallTimeMs, wallTimeUncertaintyMs, contentChecksum) }
	override fun toString(): String = "AmbientWifiChildEntity"
}

/** One accepted Cell result owned by one exact sessionless fact revision. */
@Entity(
	tableName = "ambient_cell_child",
	primaryKeys = ["writer_id", "writer_version", "logical_fact_id", "semantic_revision", "item_index"],
	foreignKeys = [ForeignKey(
		entity = AmbientCellFactRevisionEntity::class,
		parentColumns = ["writer_id", "writer_version", "logical_fact_id", "semantic_revision"],
		childColumns = ["writer_id", "writer_version", "logical_fact_id", "semantic_revision"],
		onDelete = ForeignKey.RESTRICT,
	)],
	indices = [
		Index(value = ["observed_wall_time_ms"], name = "idx_ambient_cell_child_time"),
		Index(value = ["tower_identity", "observed_wall_time_ms"], name = "idx_ambient_cell_child_identity_time"),
	],
)
data class AmbientCellChildEntity(
	@ColumnInfo(name = "writer_id") val writerId: String,
	@ColumnInfo(name = "writer_version") val writerVersion: Int,
	@ColumnInfo(name = "logical_fact_id") val logicalFactId: String,
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
) {
	init { requireRadioChild(itemIndex, providerTimestampNanos, observedWallTimeMs, wallTimeUncertaintyMs, contentChecksum) }
	override fun toString(): String = "AmbientCellChildEntity"
}

/** Copies of released identity-bearing rows. No session or background ownership is inferred. */
@Entity(tableName = "legacy_wifi_child", indices = [Index(value = ["time_ms"], name = "idx_legacy_wifi_child_time")])
data class LegacyWifiChildEntity(
	@PrimaryKey @ColumnInfo(name = "legacy_row_id") val legacyRowId: Long,
	@ColumnInfo(name = "time_ms") val timeMs: Long,
	val bssid: String,
	val ssid: String,
	val capabilities: String,
	val frequency: Int,
	val level: Int,
	@ColumnInfo(name = "lat_e7") val latE7: Int?,
	@ColumnInfo(name = "lon_e7") val lonE7: Int?,
	/** These coordinates describe the observed device, not the access point. */
	val provenance: String,
	@ColumnInfo(name = "created_at") val createdAt: Long,
	@ColumnInfo(name = "source_signal_id") val sourceSignalId: String?,
	@ColumnInfo(name = "source_item_index") val sourceItemIndex: Int?,
	@Embedded val observationStamp: ObservationStampColumns = ObservationStampColumns(),
) { override fun toString(): String = "LegacyWifiChildEntity" }

@Entity(tableName = "legacy_cell_child", indices = [Index(value = ["time_ms"], name = "idx_legacy_cell_child_time")])
data class LegacyCellChildEntity(
	@PrimaryKey @ColumnInfo(name = "legacy_row_id") val legacyRowId: Long,
	@ColumnInfo(name = "time_ms") val timeMs: Long,
	@ColumnInfo(name = "cell_id") val cellId: Long,
	val lac: Int,
	val mcc: Int,
	val mnc: Int,
	@ColumnInfo(name = "network_type") val networkType: Int,
	@ColumnInfo(name = "signal_strength") val signalStrength: Int,
	@ColumnInfo(name = "lat_e7") val latE7: Int?,
	@ColumnInfo(name = "lon_e7") val lonE7: Int?,
	/** These coordinates describe the observed device, not the tower. */
	val provenance: String,
	@ColumnInfo(name = "created_at") val createdAt: Long,
	@ColumnInfo(name = "source_signal_id") val sourceSignalId: String?,
	@ColumnInfo(name = "source_item_index") val sourceItemIndex: Int?,
	@Embedded val observationStamp: ObservationStampColumns = ObservationStampColumns(),
) { override fun toString(): String = "LegacyCellChildEntity" }

private fun requireRadioChild(index: Int, providerNanos: Long, wallMs: Long, uncertaintyMs: Long, checksum: String) {
	require(index >= 0 && providerNanos > 0L && wallMs >= 0L && uncertaintyMs >= 0L)
	require(RADIO_CHILD_SHA256.matches(checksum))
}

private val RADIO_CHILD_SHA256 = Regex("[0-9a-f]{64}")
