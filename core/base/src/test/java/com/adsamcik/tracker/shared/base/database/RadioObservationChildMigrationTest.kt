package com.adsamcik.tracker.shared.base.database

import android.app.Application
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import io.kotest.matchers.shouldBe
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RadioObservationChildMigrationTest {
	private lateinit var context: Application

	@Before fun setUp() {
		context = ApplicationProvider.getApplicationContext()
		context.deleteDatabase(DATABASE_NAME)
	}

	@After fun tearDown() {
		context.deleteDatabase(DATABASE_NAME)
	}

	@Test fun `v28 upgrade retains known radio identity clock and device coordinate origin`() {
		val helper = FrameworkSQLiteOpenHelperFactory().create(
			SupportSQLiteOpenHelper.Configuration.builder(context).name(DATABASE_NAME)
				.callback(object : SupportSQLiteOpenHelper.Callback(28) {
					override fun onConfigure(db: SupportSQLiteDatabase) {
						db.setForeignKeyConstraintsEnabled(true)
					}

					override fun onCreate(db: SupportSQLiteDatabase) {
						createLegacyRadioTables(db)
						createPurposeParentTables(db)
						db.execSQL("INSERT INTO wifi_captured_fact_revision VALUES ('older-writer',1,'older-fact',1)")
						db.execSQL("CREATE TABLE location_sample (id INTEGER NOT NULL PRIMARY KEY)")
						db.execSQL("CREATE TABLE location_accepted_receipt (sample_id INTEGER NOT NULL PRIMARY KEY)")
						db.execSQL("CREATE TABLE location_sample_purpose_authority (sample_id INTEGER NOT NULL, " +
							"purpose TEXT NOT NULL, authority_id TEXT NOT NULL, PRIMARY KEY(sample_id, purpose, authority_id))")
						db.execSQL("INSERT INTO location_sample VALUES (1)")
						db.execSQL("INSERT INTO location_accepted_receipt VALUES (1)")
						db.execSQL("INSERT INTO location_sample_purpose_authority VALUES (1, 'SESSION_CAPTURE', 'original')")
						db.execSQL("CREATE TABLE organization_annotation (local_id TEXT NOT NULL PRIMARY KEY, name TEXT)")
						db.execSQL("INSERT INTO organization_annotation VALUES ('local-session', 'Morning walk')")
						db.execSQL("CREATE TABLE source_policy (policy_revision INTEGER NOT NULL, source_kind INTEGER NOT NULL, " +
							"PRIMARY KEY(policy_revision, source_kind))")
						db.execSQL("INSERT INTO source_policy VALUES (1, 4), (1, 5)")
						db.execSQL("INSERT INTO wifi_observation " +
							"(id,time_ms,bssid,ssid,capabilities,frequency,level,lat_e7,lon_e7," +
							"provenance,created_at,source_signal_id,source_item_index,source_time_ms," +
							"clock_domain_id,time_uncertainty_ms) VALUES " +
							"(7,1000,'aa:bb','Cafe','[WPA2]',2412,-63,500000000,140000000," +
							"'DIRECT',1001,'wifi-signal',0,999,'boot-1',5)")
						db.execSQL("INSERT INTO cell_sample " +
							"(id,time_ms,cell_id,lac,mcc,mnc,network_type,signal_strength,lat_e7," +
							"lon_e7,provenance,created_at,source_signal_id,source_item_index," +
							"source_time_ms,clock_domain_id,time_uncertainty_ms) VALUES " +
							"(8,1002,123456,44,230,1,13,-101,500000001,140000001," +
							"'NEAREST_LOCATION',1003,'cell-signal',0,1002,'boot-1',7)")
					}

					override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
				}).build(),
		)
		try {
			val db = helper.writableDatabase
			MIGRATION_28_29.migrate(db)
			readLong(db, "SELECT count(*) FROM pragma_table_info('wifi_captured_fact_revision') WHERE " +
				"name='payload_version' AND type='INTEGER' AND \"notnull\"=0 AND dflt_value='NULL'") shouldBe 1L
			readLong(db, "SELECT count(*) FROM wifi_captured_fact_revision WHERE " +
				"logical_fact_id='older-fact' AND payload_version IS NULL") shouldBe 1L
			readLong(db, "SELECT count(*) FROM source_policy WHERE background_notification_host_enabled = 0") shouldBe 2L
			readLong(db, "SELECT count(*) FROM pragma_table_info('source_policy') WHERE " +
				"name = 'background_notification_host_enabled' AND type = 'INTEGER' AND \"notnull\" = 1 AND dflt_value = '0'") shouldBe 1L
			readLong(db, "SELECT count(*) FROM pragma_table_info('ambient_wifi_fact_revision') WHERE name='purpose_demand_id'") shouldBe 1L
			readLong(db, "SELECT count(*) FROM pragma_table_info('ambient_cell_fact_revision') WHERE name='purpose_demand_id'") shouldBe 1L
			// Migration preserves old provenance as unknown; only new qualified writes can seal it.
			readLong(db, "SELECT retained_proof_version FROM location_accepted_receipt WHERE sample_id=1") shouldBe 0L
			readString(db, "SELECT retained_proof_checksum FROM location_accepted_receipt WHERE sample_id=1") shouldBe ""
			readString(db, "SELECT retained_proof_checksum FROM location_sample_purpose_authority WHERE sample_id=1") shouldBe ""
			readString(db, "SELECT name FROM organization_annotation WHERE local_id='local-session'") shouldBe "Morning walk"
			readLong(db, "SELECT count(*) FROM organization_annotation WHERE imported_source_selection IS NULL " +
				"AND imported_activity_label IS NULL") shouldBe 1L
			readLong(db, "SELECT count(*) FROM pragma_table_info('organization_annotation') WHERE " +
				"name IN ('imported_source_selection', 'imported_activity_label') AND type='TEXT' " +
				"AND \"notnull\"=0 AND dflt_value IS NULL") shouldBe 2L
			readLong(db, "SELECT count(*) FROM location_file_archive") shouldBe 0L
			db.execSQL("INSERT INTO location_file_archive VALUES ('archive', 'tracker-location', 1, 0, 2)")
			db.execSQL("INSERT INTO location_file_archive_point VALUES ('archive', 0, 'point-1', 1), ('archive', 1, 'missing-point', NULL)")
			db.execSQL("DELETE FROM location_sample WHERE id=1")
			readLong(db, "SELECT count(*) FROM location_file_archive_point WHERE sample_id IS NULL") shouldBe 2L
			readString(db, "SELECT portable_id FROM location_file_archive_point WHERE ordinal=0") shouldBe "point-1"
			db.execSQL("DELETE FROM location_file_archive WHERE identity='archive'")
			readLong(db, "SELECT count(*) FROM location_file_archive_point") shouldBe 0L
			readString(db, "SELECT bssid FROM legacy_wifi_child WHERE legacy_row_id=7") shouldBe "aa:bb"
			readString(db, "SELECT ssid FROM legacy_wifi_child WHERE legacy_row_id=7") shouldBe "Cafe"
			readLong(db, "SELECT lat_e7 FROM legacy_wifi_child WHERE legacy_row_id=7") shouldBe 500000000L
			readString(db, "SELECT provenance FROM legacy_wifi_child WHERE legacy_row_id=7") shouldBe "DIRECT"
			readString(db, "SELECT clock_domain_id FROM legacy_wifi_child WHERE legacy_row_id=7") shouldBe "boot-1"
			readLong(db, "SELECT time_uncertainty_ms FROM legacy_wifi_child WHERE legacy_row_id=7") shouldBe 5L
			readLong(db, "SELECT cell_id FROM legacy_cell_child WHERE legacy_row_id=8") shouldBe 123456L
			readString(db, "SELECT provenance FROM legacy_cell_child WHERE legacy_row_id=8") shouldBe "NEAREST_LOCATION"
			readLong(db, "SELECT source_time_ms FROM legacy_cell_child WHERE legacy_row_id=8") shouldBe 1002L
			db.execSQL("INSERT INTO wifi_captured_fact_revision " +
				"(writer_projection_id,writer_projection_version,logical_fact_id,semantic_revision) " +
				"VALUES ('writer',1,'session-fact',1)")
			db.execSQL("INSERT INTO ambient_wifi_fact_revision (writer_id,writer_version,logical_fact_id,semantic_revision) VALUES ('writer',1,'background-fact',1)")
			val checksum = "a".repeat(64)
			db.execSQL("INSERT INTO wifi_captured_child VALUES " +
				"('writer',1,'session-fact',1,0,100,1000,5,'aa:bb','Cafe',2412,-63,'$checksum')")
			db.execSQL("INSERT INTO ambient_wifi_child VALUES " +
				"('writer',1,'background-fact',1,0,100,1000,5,'aa:bb','Cafe',2412,-63,'$checksum')")
			db.execSQL("INSERT INTO imported_wifi_observation VALUES ('entry',1,'run','observation')")
			db.execSQL("INSERT INTO imported_ambient_cell_fact VALUES ('archive','fact',1)")
			db.execSQL("INSERT INTO imported_wifi_child VALUES " +
				"('entry',1,'run','observation',0,100,1000,5,'aa:bb','Cafe',2412,-63,'$checksum')")
			db.execSQL("INSERT INTO imported_ambient_cell_child VALUES " +
				"('archive','fact',1,0,101,1001,5,'tower','LTE',1,-90,'$checksum')")
			readLong(db, "SELECT count(*) FROM imported_wifi_child") shouldBe 1L
			readLong(db, "SELECT count(*) FROM imported_ambient_cell_child") shouldBe 1L
			db.execSQL("DELETE FROM imported_wifi_observation")
			readLong(db, "SELECT count(*) FROM imported_wifi_child") shouldBe 0L
			db.execSQL("DELETE FROM wifi_captured_child WHERE logical_fact_id='session-fact'")
			db.execSQL("DELETE FROM wifi_captured_fact_revision WHERE logical_fact_id='session-fact'")
			readLong(db, "SELECT count(*) FROM ambient_wifi_child") shouldBe 1L
			db.execSQL("UPDATE wifi_observation SET ssid='Renamed' WHERE id=7")
			readString(db, "SELECT ssid FROM legacy_wifi_child WHERE legacy_row_id=7") shouldBe "Renamed"
			db.execSQL("DELETE FROM wifi_observation WHERE id=7")
			readLong(db, "SELECT count(*) FROM legacy_wifi_child") shouldBe 0L
		} finally {
			helper.close()
		}
	}

	private fun createLegacyRadioTables(db: SupportSQLiteDatabase) {
		val stamp = "source_time_ms INTEGER, source_elapsed_realtime_nanos INTEGER, " +
			"source_first_elapsed_realtime_nanos INTEGER, received_time_ms INTEGER, " +
			"received_elapsed_realtime_nanos INTEGER, source_sequence INTEGER, " +
			"source_first_sequence INTEGER, clock_domain_id TEXT, boot_clock_domain_id TEXT, " +
			"source_age_ms INTEGER, time_uncertainty_ms INTEGER, capability_flags TEXT, " +
			"permission_precision TEXT"
		db.execSQL("CREATE TABLE wifi_observation (id INTEGER PRIMARY KEY, time_ms INTEGER NOT NULL, " +
			"bssid TEXT NOT NULL, ssid TEXT NOT NULL, capabilities TEXT NOT NULL, frequency INTEGER NOT NULL, " +
			"level INTEGER NOT NULL, lat_e7 INTEGER, lon_e7 INTEGER, provenance TEXT NOT NULL, " +
			"created_at INTEGER NOT NULL, source_signal_id TEXT, source_item_index INTEGER, $stamp)")
		db.execSQL("CREATE TABLE cell_sample (id INTEGER PRIMARY KEY, time_ms INTEGER NOT NULL, " +
			"cell_id INTEGER NOT NULL, lac INTEGER NOT NULL, mcc INTEGER NOT NULL, mnc INTEGER NOT NULL, " +
			"network_type INTEGER NOT NULL, signal_strength INTEGER NOT NULL, lat_e7 INTEGER, lon_e7 INTEGER, " +
			"provenance TEXT NOT NULL, created_at INTEGER NOT NULL, source_signal_id TEXT, " +
			"source_item_index INTEGER, $stamp)")
	}

	private fun createPurposeParentTables(db: SupportSQLiteDatabase) {
		listOf("wifi_captured_fact_revision", "cell_captured_fact_revision").forEach { table ->
			db.execSQL("CREATE TABLE $table (writer_projection_id TEXT NOT NULL, " +
				"writer_projection_version INTEGER NOT NULL, logical_fact_id TEXT NOT NULL, " +
				"semantic_revision INTEGER NOT NULL, PRIMARY KEY(writer_projection_id, " +
				"writer_projection_version, logical_fact_id, semantic_revision))")
		}
		listOf("ambient_wifi_fact_revision", "ambient_cell_fact_revision").forEach { table ->
			db.execSQL("CREATE TABLE $table (writer_id TEXT NOT NULL, writer_version INTEGER NOT NULL, " +
				"logical_fact_id TEXT NOT NULL, semantic_revision INTEGER NOT NULL, " +
				"PRIMARY KEY(writer_id, writer_version, logical_fact_id, semantic_revision))")
		}
		db.execSQL("CREATE TABLE imported_wifi_observation (entry_identity TEXT NOT NULL, " +
			"entry_import_revision INTEGER NOT NULL, run_identity TEXT NOT NULL, identity TEXT NOT NULL, " +
			"PRIMARY KEY(entry_identity, entry_import_revision, run_identity, identity))")
		db.execSQL("CREATE TABLE imported_cell_observation (entry_identity TEXT NOT NULL, " +
			"entry_import_revision INTEGER NOT NULL, run_identity TEXT NOT NULL, identity TEXT NOT NULL, " +
			"PRIMARY KEY(entry_identity, entry_import_revision, run_identity, identity))")
		listOf("wifi", "cell").forEach { source ->
			db.execSQL("CREATE TABLE imported_ambient_${source}_fact (archive_id TEXT NOT NULL, " +
				"fact_id TEXT NOT NULL, semantic_revision INTEGER NOT NULL, " +
				"PRIMARY KEY(archive_id, fact_id, semantic_revision))")
		}
	}

	private fun readString(db: SupportSQLiteDatabase, sql: String): String? =
		db.query(sql).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

	private fun readLong(db: SupportSQLiteDatabase, sql: String): Long? =
		db.query(sql).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }

	private companion object { const val DATABASE_NAME = "radio-child-migration-test.db" }
}
