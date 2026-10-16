package com.adsamcik.tracker.shared.base.database

import android.app.Application
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import java.io.File
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Upgrades the checked v29 schema and exercises the real Room reopening and deletion paths. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FinalTrackingAssemblyMigrationTest {
    private lateinit var context: Application
    @Before fun setup() { context = ApplicationProvider.getApplicationContext(); context.deleteDatabase(NAME) }
    @After fun cleanup() { context.deleteDatabase(NAME) }

    @Test fun `v29 originals survive upgrade current replacements reopen and deleting a point removes its correction`() {
        open(29).use { helper ->
            val db = helper.writableDatabase
            db.execSQL("INSERT INTO source_evidence_state(id,revision,collected_data_epoch,updated_at_ms) VALUES(1,0,0,1000)")
            db.execSQL("INSERT INTO location_sample(id,time_ms,elapsed_realtime_nanos,lat_e7,lon_e7,provider,quality,created_at) " +
                "VALUES(42,1000,1000000000,500000000,140000000,'fused','HIGH',1001)")
        }
        open(30).use { helper ->
            val db = helper.writableDatabase
            assertEquals(500_000_000L, value(db, "SELECT lat_e7 FROM effective_location_sample WHERE id=42"))
            db.execSQL("INSERT INTO location_point_correction(sample_id,collected_data_epoch,revision,time_ms,lat_e7,lon_e7," +
                "provider,quality,permission_precision,is_mock,alt_datum,alt_source,alt_conversion_status,raw_gps_alt_datum) " +
                "VALUES(42,0,1,2000,501000000,140000000,'file','HIGH','UNKNOWN',0," +
                "'unknown_legacy','unknown_legacy','unknown_legacy','unknown_legacy')")
            assertEquals(500_000_000L, value(db, "SELECT lat_e7 FROM location_sample WHERE id=42"))
            assertEquals(501_000_000L, value(db, "SELECT lat_e7 FROM effective_location_sample WHERE id=42"))
        }
        open(30).use { helper ->
            val db = helper.writableDatabase
            assertEquals(2_000L, value(db, "SELECT time_ms FROM effective_location_sample WHERE id=42"))
            assertEquals(0L, value(db, "SELECT elapsed_realtime_nanos FROM effective_location_sample WHERE id=42"))
            assertEquals(1_000L, value(db, "SELECT time_ms FROM location_sample WHERE id=42"))
            db.execSQL("UPDATE source_evidence_state SET collected_data_epoch=1 WHERE id=1")
            assertEquals(1_000L, value(db, "SELECT time_ms FROM effective_location_sample WHERE id=42"))
            db.execSQL("DELETE FROM location_sample WHERE id=42")
            assertEquals(0L, value(db, "SELECT COUNT(*) FROM location_point_correction"))
            assertEquals(0L, value(db, "SELECT COUNT(*) FROM effective_location_sample"))
        }
    }

    @Test fun `known v29 passes containment migrates and reopens through Room`() {
        open(29).use { helper ->
            helper.writableDatabase.execSQL("INSERT INTO source_evidence_state(id,revision,collected_data_epoch,updated_at_ms) VALUES(1,7,0,1000)")
        }
        assertEquals(ActiveDatabasePreflightResult.FinalV29,
            ActiveDatabasePreflight(context.getDatabasePath(NAME)).inspect())
        repeat(2) {
            val database = AppDatabase.fileBuilder(context, NAME)
                .openHelperFactory(DevelopmentV28ContainmentOpenHelperFactory(context, NAME,
                    FrameworkSQLiteOpenHelperFactory()))
                .allowMainThreadQueries().build()
            try {
                val raw = database.openHelper.writableDatabase
                assertEquals(CURRENT_DATABASE_VERSION, raw.version)
                assertEquals(7L, value(raw, "SELECT revision FROM source_evidence_state WHERE id=1"))
                raw.query("SELECT restored_data_epochs FROM source_evidence_state WHERE id=1").use {
                    assertTrue(it.moveToFirst()); assertEquals("", it.getString(0))
                }
                assertEquals(0L, value(raw, "SELECT COUNT(*) FROM imported_radio_file_membership"))
                assertEquals(0L, value(raw, "SELECT COUNT(*) FROM location_point_correction"))
            } finally { database.close() }
        }
    }

    @Test fun `restored corrections keep original identity until full clear removes retained epochs`(): Unit = runBlocking {
        val database = AppDatabase.fileBuilder(context, NAME).allowMainThreadQueries().build()
        try {
            val raw = database.openHelper.writableDatabase
            raw.execSQL("INSERT INTO source_evidence_state(id,revision,collected_data_epoch,updated_at_ms,restored_data_epochs) VALUES(1,0,3,1000,'0,2')")
            raw.execSQL("INSERT INTO location_sample(id,time_ms,elapsed_realtime_nanos,lat_e7,lon_e7,provider,quality,created_at) " +
                "VALUES(42,1000,1000000000,500000000,140000000,'fused','HIGH',1001)")
            raw.execSQL("INSERT INTO location_point_correction(sample_id,collected_data_epoch,revision,time_ms,lat_e7,lon_e7," +
                "provider,quality,permission_precision,is_mock,alt_datum,alt_source,alt_conversion_status,raw_gps_alt_datum) " +
                "VALUES(42,0,1,2000,501000000,140000000,'file','HIGH','UNKNOWN',0," +
                "'unknown_legacy','unknown_legacy','unknown_legacy','unknown_legacy')")
            assertEquals(2000L, value(raw, "SELECT time_ms FROM effective_location_sample WHERE id=42"))
            val restored = requireNotNull(database.sourceEvidenceStateDao().get())
            assertTrue(restored.acceptsRetainedEpoch(0))
            assertTrue(restored.acceptsRetainedEpoch(2))
            assertTrue(!restored.acceptsRetainedEpoch(1))
            AppDatabase.deleteAllCollectedDataForTesting(database, "restored-clear", 4, null, 3000)
            val cleared = requireNotNull(database.sourceEvidenceStateDao().get())
            assertEquals(4L, cleared.collectedDataEpoch)
            assertEquals("", cleared.restoredDataEpochs)
            assertEquals(0L, value(raw, "SELECT COUNT(*) FROM location_point_correction"))
            assertEquals(0L, value(raw, "SELECT COUNT(*) FROM effective_location_sample"))
        } finally { database.close() }
    }

    private fun open(version: Int) = FrameworkSQLiteOpenHelperFactory().create(
        SupportSQLiteOpenHelper.Configuration.builder(context).name(NAME)
            .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                override fun onConfigure(db: SupportSQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
                override fun onCreate(db: SupportSQLiteDatabase) {
                    val relative = "schemas/com.adsamcik.tracker.shared.base.database.AppDatabase/29.json"
                    val schema = sequenceOf(File(relative), File("core/base/$relative")).first(File::isFile)
                    val tables = JSONObject(schema.readText()).getJSONObject("database").getJSONArray("entities")
                    for (index in 0 until tables.length()) {
                        val table = tables.getJSONObject(index)
                        db.execSQL(table.getString("createSql").replace("${'$'}{TABLE_NAME}", table.getString("tableName")))
                        val indices = table.optJSONArray("indices")
                        if (indices != null) {
                            for (item in 0 until indices.length()) {
                            db.execSQL(indices.getJSONObject(item).getString("createSql")
                                .replace("${'$'}{TABLE_NAME}", table.getString("tableName")))
                        }
                        }
                    }
                    val setup = JSONObject(schema.readText()).getJSONObject("database").getJSONArray("setupQueries")
                    for (index in 0 until setup.length()) db.execSQL(setup.getString(index))
                    TrackingOwnerValidationRoomCallback.onCreate(db)
                    FinalV28SchemaAssemblyRoomCallback.onCreate(db)
                    RadioObservationChildRoomCallback.onCreate(db)
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    check(oldVersion == 29 && newVersion == 30)
                    MIGRATION_29_30.migrate(db)
                }
            }).build())

    private fun value(db: SupportSQLiteDatabase, sql: String): Long = db.query(sql).use {
        check(it.moveToFirst()); it.getLong(0)
    }
    private companion object { const val NAME = "final-tracking-v30-migration.db" }
}
