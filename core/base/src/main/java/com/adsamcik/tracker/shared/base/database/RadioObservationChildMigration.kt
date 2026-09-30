package com.adsamcik.tracker.shared.base.database

import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

private val legacyStampFields = listOf(
	"source_time_ms", "source_elapsed_realtime_nanos", "source_first_elapsed_realtime_nanos",
	"received_time_ms", "received_elapsed_realtime_nanos", "source_sequence",
	"source_first_sequence", "clock_domain_id", "boot_clock_domain_id", "source_age_ms",
	"time_uncertainty_ms", "capability_flags", "permission_precision",
)
private val legacyStampColumnNames = legacyStampFields.joinToString(", ")
private val legacyStampNewValues = legacyStampFields.joinToString(", ") { "NEW.$it" }
private val legacyStampColumnDefinitions = legacyStampFields.joinToString(",\n") { field ->
	"$field ${if (field in setOf("clock_domain_id", "boot_clock_domain_id", "capability_flags", "permission_precision")) "TEXT" else "INTEGER"}"
}

/** Complete the unshipped v29 data model while preserving existing observation meaning. */
val MIGRATION_28_29: Migration = object : Migration(28, 29) {
	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL("ALTER TABLE ambient_wifi_fact_revision ADD COLUMN purpose_demand_id TEXT DEFAULT NULL")
		db.execSQL("ALTER TABLE ambient_cell_fact_revision ADD COLUMN purpose_demand_id TEXT DEFAULT NULL")
		db.execSQL("ALTER TABLE source_policy ADD COLUMN background_notification_host_enabled INTEGER NOT NULL DEFAULT 0")
		db.execSQL("ALTER TABLE location_accepted_receipt ADD COLUMN retained_proof_version INTEGER NOT NULL DEFAULT 0")
		db.execSQL("ALTER TABLE location_accepted_receipt ADD COLUMN retained_proof_checksum TEXT NOT NULL DEFAULT ''")
		db.execSQL("ALTER TABLE location_sample_purpose_authority ADD COLUMN retained_proof_checksum TEXT NOT NULL DEFAULT ''")
		createLocationFileArchiveTables(db)
		createRadioObservationChildTables(db)
		copyKnownLegacyRadioRows(db)
		installLegacyRadioChildMirrors(db)
	}
}

internal fun createLocationFileArchiveTables(db: SupportSQLiteDatabase) {
	db.execSQL("""
		CREATE TABLE IF NOT EXISTS location_file_archive (
			identity TEXT NOT NULL, source_format TEXT NOT NULL,
			source_schema_version INTEGER NOT NULL, collected_data_epoch INTEGER NOT NULL,
			point_count INTEGER NOT NULL, PRIMARY KEY(identity)
		)
	""".trimIndent())
	db.execSQL("""
		CREATE TABLE IF NOT EXISTS location_file_archive_point (
			archive_identity TEXT NOT NULL, ordinal INTEGER NOT NULL,
			portable_id TEXT NOT NULL, sample_id INTEGER,
			PRIMARY KEY(archive_identity, ordinal),
			FOREIGN KEY(archive_identity) REFERENCES location_file_archive(identity)
			ON UPDATE NO ACTION ON DELETE CASCADE,
			FOREIGN KEY(sample_id) REFERENCES location_sample(id)
			ON UPDATE NO ACTION ON DELETE SET NULL
		)
	""".trimIndent())
	db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_location_archive_point_identity " +
		"ON location_file_archive_point(archive_identity, portable_id)")
	db.execSQL("CREATE INDEX IF NOT EXISTS idx_location_archive_point_sample " +
		"ON location_file_archive_point(sample_id)")
}

/** Room creates new v29 tables itself; the released-data importer runs later in onCreate. */
internal object RadioObservationChildRoomCallback : RoomDatabase.Callback() {
	override fun onCreate(db: SupportSQLiteDatabase) {
		installLegacyRadioChildMirrors(db)
	}
}

internal fun createRadioObservationChildTables(db: SupportSQLiteDatabase) {
	db.execSQL("""
		CREATE TABLE IF NOT EXISTS wifi_captured_child (
			writer_projection_id TEXT NOT NULL, writer_projection_version INTEGER NOT NULL,
			logical_fact_id TEXT NOT NULL, semantic_revision INTEGER NOT NULL, item_index INTEGER NOT NULL,
			provider_timestamp_nanos INTEGER NOT NULL, observed_wall_time_ms INTEGER NOT NULL,
			wall_time_uncertainty_ms INTEGER NOT NULL, bssid TEXT, ssid TEXT,
			frequency_mhz INTEGER NOT NULL, signal_dbm INTEGER NOT NULL, content_checksum TEXT NOT NULL,
			PRIMARY KEY(writer_projection_id, writer_projection_version, logical_fact_id, semantic_revision, item_index),
			FOREIGN KEY(writer_projection_id, writer_projection_version, logical_fact_id, semantic_revision)
			REFERENCES wifi_captured_fact_revision(writer_projection_id, writer_projection_version, logical_fact_id, semantic_revision)
			ON UPDATE NO ACTION ON DELETE RESTRICT
		)
	""".trimIndent())
	db.execSQL("CREATE INDEX IF NOT EXISTS idx_wifi_captured_child_time ON wifi_captured_child(observed_wall_time_ms)")
	db.execSQL("CREATE INDEX IF NOT EXISTS idx_wifi_captured_child_identity_time ON wifi_captured_child(bssid, observed_wall_time_ms)")
	db.execSQL("""
		CREATE TABLE IF NOT EXISTS cell_captured_child (
			writer_projection_id TEXT NOT NULL, writer_projection_version INTEGER NOT NULL,
			logical_fact_id TEXT NOT NULL, semantic_revision INTEGER NOT NULL, item_index INTEGER NOT NULL,
			provider_timestamp_nanos INTEGER NOT NULL, observed_wall_time_ms INTEGER NOT NULL,
			wall_time_uncertainty_ms INTEGER NOT NULL, tower_identity TEXT, radio_type TEXT NOT NULL,
			registered INTEGER NOT NULL, signal_dbm INTEGER, content_checksum TEXT NOT NULL,
			PRIMARY KEY(writer_projection_id, writer_projection_version, logical_fact_id, semantic_revision, item_index),
			FOREIGN KEY(writer_projection_id, writer_projection_version, logical_fact_id, semantic_revision)
			REFERENCES cell_captured_fact_revision(writer_projection_id, writer_projection_version, logical_fact_id, semantic_revision)
			ON UPDATE NO ACTION ON DELETE RESTRICT
		)
	""".trimIndent())
	db.execSQL("CREATE INDEX IF NOT EXISTS idx_cell_captured_child_time ON cell_captured_child(observed_wall_time_ms)")
	db.execSQL("CREATE INDEX IF NOT EXISTS idx_cell_captured_child_identity_time ON cell_captured_child(tower_identity, observed_wall_time_ms)")
	db.execSQL("""
		CREATE TABLE IF NOT EXISTS ambient_wifi_child (
			writer_id TEXT NOT NULL, writer_version INTEGER NOT NULL,
			logical_fact_id TEXT NOT NULL, semantic_revision INTEGER NOT NULL, item_index INTEGER NOT NULL,
			provider_timestamp_nanos INTEGER NOT NULL, observed_wall_time_ms INTEGER NOT NULL,
			wall_time_uncertainty_ms INTEGER NOT NULL, bssid TEXT, ssid TEXT,
			frequency_mhz INTEGER NOT NULL, signal_dbm INTEGER NOT NULL, content_checksum TEXT NOT NULL,
			PRIMARY KEY(writer_id, writer_version, logical_fact_id, semantic_revision, item_index),
			FOREIGN KEY(writer_id, writer_version, logical_fact_id, semantic_revision)
			REFERENCES ambient_wifi_fact_revision(writer_id, writer_version, logical_fact_id, semantic_revision)
			ON UPDATE NO ACTION ON DELETE RESTRICT
		)
	""".trimIndent())
	db.execSQL("CREATE INDEX IF NOT EXISTS idx_ambient_wifi_child_time ON ambient_wifi_child(observed_wall_time_ms)")
	db.execSQL("CREATE INDEX IF NOT EXISTS idx_ambient_wifi_child_identity_time ON ambient_wifi_child(bssid, observed_wall_time_ms)")
	db.execSQL("""
		CREATE TABLE IF NOT EXISTS ambient_cell_child (
			writer_id TEXT NOT NULL, writer_version INTEGER NOT NULL,
			logical_fact_id TEXT NOT NULL, semantic_revision INTEGER NOT NULL, item_index INTEGER NOT NULL,
			provider_timestamp_nanos INTEGER NOT NULL, observed_wall_time_ms INTEGER NOT NULL,
			wall_time_uncertainty_ms INTEGER NOT NULL, tower_identity TEXT, radio_type TEXT NOT NULL,
			registered INTEGER NOT NULL, signal_dbm INTEGER, content_checksum TEXT NOT NULL,
			PRIMARY KEY(writer_id, writer_version, logical_fact_id, semantic_revision, item_index),
			FOREIGN KEY(writer_id, writer_version, logical_fact_id, semantic_revision)
			REFERENCES ambient_cell_fact_revision(writer_id, writer_version, logical_fact_id, semantic_revision)
			ON UPDATE NO ACTION ON DELETE RESTRICT
		)
	""".trimIndent())
	db.execSQL("CREATE INDEX IF NOT EXISTS idx_ambient_cell_child_time ON ambient_cell_child(observed_wall_time_ms)")
	db.execSQL("CREATE INDEX IF NOT EXISTS idx_ambient_cell_child_identity_time ON ambient_cell_child(tower_identity, observed_wall_time_ms)")
	db.execSQL("""
		CREATE TABLE IF NOT EXISTS legacy_wifi_child (
			legacy_row_id INTEGER NOT NULL PRIMARY KEY, time_ms INTEGER NOT NULL,
			bssid TEXT NOT NULL, ssid TEXT NOT NULL, capabilities TEXT NOT NULL,
			frequency INTEGER NOT NULL, level INTEGER NOT NULL, lat_e7 INTEGER, lon_e7 INTEGER,
			provenance TEXT NOT NULL, created_at INTEGER NOT NULL,
			source_signal_id TEXT, source_item_index INTEGER,
			$legacyStampColumnDefinitions
		)
	""".trimIndent())
	db.execSQL("CREATE INDEX IF NOT EXISTS idx_legacy_wifi_child_time ON legacy_wifi_child(time_ms)")
	db.execSQL("""
		CREATE TABLE IF NOT EXISTS legacy_cell_child (
			legacy_row_id INTEGER NOT NULL PRIMARY KEY, time_ms INTEGER NOT NULL,
			cell_id INTEGER NOT NULL, lac INTEGER NOT NULL, mcc INTEGER NOT NULL, mnc INTEGER NOT NULL,
			network_type INTEGER NOT NULL, signal_strength INTEGER NOT NULL,
			lat_e7 INTEGER, lon_e7 INTEGER, provenance TEXT NOT NULL, created_at INTEGER NOT NULL,
			source_signal_id TEXT, source_item_index INTEGER,
			$legacyStampColumnDefinitions
		)
	""".trimIndent())
	db.execSQL("CREATE INDEX IF NOT EXISTS idx_legacy_cell_child_time ON legacy_cell_child(time_ms)")
	db.execSQL("""
		CREATE TABLE IF NOT EXISTS imported_wifi_child (
			entry_identity TEXT NOT NULL, entry_import_revision INTEGER NOT NULL,
			run_identity TEXT NOT NULL, observation_identity TEXT NOT NULL, item_index INTEGER NOT NULL,
			provider_timestamp_nanos INTEGER NOT NULL, observed_wall_time_ms INTEGER NOT NULL,
			wall_time_uncertainty_ms INTEGER NOT NULL, bssid TEXT, ssid TEXT,
			frequency_mhz INTEGER NOT NULL, signal_dbm INTEGER NOT NULL, content_checksum TEXT NOT NULL,
			PRIMARY KEY(entry_identity, entry_import_revision, run_identity, observation_identity, item_index),
			FOREIGN KEY(entry_identity, entry_import_revision, run_identity, observation_identity)
			REFERENCES imported_wifi_observation(entry_identity, entry_import_revision, run_identity, identity)
			ON UPDATE NO ACTION ON DELETE CASCADE
		)
	""".trimIndent())
	db.execSQL("CREATE INDEX IF NOT EXISTS idx_imported_wifi_child_parent ON imported_wifi_child(entry_identity, entry_import_revision, run_identity, observation_identity)")
	db.execSQL("""
		CREATE TABLE IF NOT EXISTS imported_cell_child (
			entry_identity TEXT NOT NULL, entry_import_revision INTEGER NOT NULL,
			run_identity TEXT NOT NULL, observation_identity TEXT NOT NULL, item_index INTEGER NOT NULL,
			provider_timestamp_nanos INTEGER NOT NULL, observed_wall_time_ms INTEGER NOT NULL,
			wall_time_uncertainty_ms INTEGER NOT NULL, tower_identity TEXT, radio_type TEXT NOT NULL,
			registered INTEGER NOT NULL, signal_dbm INTEGER, content_checksum TEXT NOT NULL,
			PRIMARY KEY(entry_identity, entry_import_revision, run_identity, observation_identity, item_index),
			FOREIGN KEY(entry_identity, entry_import_revision, run_identity, observation_identity)
			REFERENCES imported_cell_observation(entry_identity, entry_import_revision, run_identity, identity)
			ON UPDATE NO ACTION ON DELETE CASCADE
		)
	""".trimIndent())
	db.execSQL("CREATE INDEX IF NOT EXISTS idx_imported_cell_child_parent ON imported_cell_child(entry_identity, entry_import_revision, run_identity, observation_identity)")
	db.execSQL("""
		CREATE TABLE IF NOT EXISTS imported_ambient_wifi_child (
			archive_id TEXT NOT NULL, fact_id TEXT NOT NULL, semantic_revision INTEGER NOT NULL,
			item_index INTEGER NOT NULL, provider_timestamp_nanos INTEGER NOT NULL,
			observed_wall_time_ms INTEGER NOT NULL, wall_time_uncertainty_ms INTEGER NOT NULL,
			bssid TEXT, ssid TEXT, frequency_mhz INTEGER NOT NULL, signal_dbm INTEGER NOT NULL,
			content_checksum TEXT NOT NULL,
			PRIMARY KEY(archive_id, fact_id, semantic_revision, item_index),
			FOREIGN KEY(archive_id, fact_id, semantic_revision)
			REFERENCES imported_ambient_wifi_fact(archive_id, fact_id, semantic_revision)
			ON UPDATE NO ACTION ON DELETE CASCADE
		)
	""".trimIndent())
	db.execSQL("CREATE INDEX IF NOT EXISTS idx_imported_ambient_wifi_child_parent ON imported_ambient_wifi_child(archive_id, fact_id, semantic_revision)")
	db.execSQL("""
		CREATE TABLE IF NOT EXISTS imported_ambient_cell_child (
			archive_id TEXT NOT NULL, fact_id TEXT NOT NULL, semantic_revision INTEGER NOT NULL,
			item_index INTEGER NOT NULL, provider_timestamp_nanos INTEGER NOT NULL,
			observed_wall_time_ms INTEGER NOT NULL, wall_time_uncertainty_ms INTEGER NOT NULL,
			tower_identity TEXT, radio_type TEXT NOT NULL, registered INTEGER NOT NULL,
			signal_dbm INTEGER, content_checksum TEXT NOT NULL,
			PRIMARY KEY(archive_id, fact_id, semantic_revision, item_index),
			FOREIGN KEY(archive_id, fact_id, semantic_revision)
			REFERENCES imported_ambient_cell_fact(archive_id, fact_id, semantic_revision)
			ON UPDATE NO ACTION ON DELETE CASCADE
		)
	""".trimIndent())
	db.execSQL("CREATE INDEX IF NOT EXISTS idx_imported_ambient_cell_child_parent ON imported_ambient_cell_child(archive_id, fact_id, semantic_revision)")
}

/** Legacy rows have no trustworthy session/background purpose, so retain their original origin. */
internal fun copyKnownLegacyRadioRows(db: SupportSQLiteDatabase) {
	db.execSQL("""
		INSERT OR IGNORE INTO legacy_wifi_child
		(legacy_row_id, time_ms, bssid, ssid, capabilities, frequency, level, lat_e7, lon_e7,
		 provenance, created_at, source_signal_id, source_item_index, $legacyStampColumnNames)
		SELECT id, time_ms, bssid, ssid, capabilities, frequency, level, lat_e7, lon_e7,
		 provenance, created_at, source_signal_id, source_item_index, $legacyStampColumnNames FROM wifi_observation
	""".trimIndent())
	db.execSQL("""
		INSERT OR IGNORE INTO legacy_cell_child
		(legacy_row_id, time_ms, cell_id, lac, mcc, mnc, network_type, signal_strength,
		 lat_e7, lon_e7, provenance, created_at, source_signal_id, source_item_index, $legacyStampColumnNames)
		SELECT id, time_ms, cell_id, lac, mcc, mnc, network_type, signal_strength,
		 lat_e7, lon_e7, provenance, created_at, source_signal_id, source_item_index, $legacyStampColumnNames FROM cell_sample
	""".trimIndent())
}

/** The one-time released-v26 import occurs after fresh Room creation; mirror only its legacy rows. */
internal fun installLegacyRadioChildMirrors(db: SupportSQLiteDatabase) {
	db.execSQL("""
		CREATE TRIGGER IF NOT EXISTS mirror_legacy_wifi_child_insert AFTER INSERT ON wifi_observation BEGIN
			INSERT OR REPLACE INTO legacy_wifi_child
			(legacy_row_id, time_ms, bssid, ssid, capabilities, frequency, level, lat_e7, lon_e7,
			 provenance, created_at, source_signal_id, source_item_index, $legacyStampColumnNames)
			VALUES (NEW.id, NEW.time_ms, NEW.bssid, NEW.ssid, NEW.capabilities, NEW.frequency,
			 NEW.level, NEW.lat_e7, NEW.lon_e7, NEW.provenance, NEW.created_at,
			 NEW.source_signal_id, NEW.source_item_index, $legacyStampNewValues);
		END
	""".trimIndent())
	db.execSQL("""
		CREATE TRIGGER IF NOT EXISTS mirror_legacy_wifi_child_update AFTER UPDATE ON wifi_observation BEGIN
			INSERT OR REPLACE INTO legacy_wifi_child
			(legacy_row_id, time_ms, bssid, ssid, capabilities, frequency, level, lat_e7, lon_e7,
			 provenance, created_at, source_signal_id, source_item_index, $legacyStampColumnNames)
			VALUES (NEW.id, NEW.time_ms, NEW.bssid, NEW.ssid, NEW.capabilities, NEW.frequency,
			 NEW.level, NEW.lat_e7, NEW.lon_e7, NEW.provenance, NEW.created_at,
			 NEW.source_signal_id, NEW.source_item_index, $legacyStampNewValues);
		END
	""".trimIndent())
	db.execSQL("""
		CREATE TRIGGER IF NOT EXISTS mirror_legacy_wifi_child_delete AFTER DELETE ON wifi_observation BEGIN
			DELETE FROM legacy_wifi_child WHERE legacy_row_id = OLD.id;
		END
	""".trimIndent())
	db.execSQL("""
		CREATE TRIGGER IF NOT EXISTS mirror_legacy_cell_child_insert AFTER INSERT ON cell_sample BEGIN
			INSERT OR REPLACE INTO legacy_cell_child
			(legacy_row_id, time_ms, cell_id, lac, mcc, mnc, network_type, signal_strength,
			 lat_e7, lon_e7, provenance, created_at, source_signal_id, source_item_index, $legacyStampColumnNames)
			VALUES (NEW.id, NEW.time_ms, NEW.cell_id, NEW.lac, NEW.mcc, NEW.mnc,
			 NEW.network_type, NEW.signal_strength, NEW.lat_e7, NEW.lon_e7, NEW.provenance,
			 NEW.created_at, NEW.source_signal_id, NEW.source_item_index, $legacyStampNewValues);
		END
	""".trimIndent())
	db.execSQL("""
		CREATE TRIGGER IF NOT EXISTS mirror_legacy_cell_child_update AFTER UPDATE ON cell_sample BEGIN
			INSERT OR REPLACE INTO legacy_cell_child
			(legacy_row_id, time_ms, cell_id, lac, mcc, mnc, network_type, signal_strength,
			 lat_e7, lon_e7, provenance, created_at, source_signal_id, source_item_index, $legacyStampColumnNames)
			VALUES (NEW.id, NEW.time_ms, NEW.cell_id, NEW.lac, NEW.mcc, NEW.mnc,
			 NEW.network_type, NEW.signal_strength, NEW.lat_e7, NEW.lon_e7, NEW.provenance,
			 NEW.created_at, NEW.source_signal_id, NEW.source_item_index, $legacyStampNewValues);
		END
	""".trimIndent())
	db.execSQL("""
		CREATE TRIGGER IF NOT EXISTS mirror_legacy_cell_child_delete AFTER DELETE ON cell_sample BEGIN
			DELETE FROM legacy_cell_child WHERE legacy_row_id = OLD.id;
		END
	""".trimIndent())
}
