package com.adsamcik.tracker.shared.base.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.adsamcik.tracker.shared.base.database.legacy.hasCompletedLegacyImport
import com.adsamcik.tracker.sqlite.runtime.SQLiteXSupportSQLiteOpenHelperFactory
import java.io.File
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Populated released-main v12 is copied through the real importer into current native SQLiteX. */
@RunWith(AndroidJUnit4::class)
class ReleasedMainImportInstrumentedTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory())

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun prepareFixture() = clearFixture()

    @After fun clearFixture() {
        // These names belong only to the disposable base-test package, never the Dev application's databases.
        assertEquals("com.adsamcik.tracker.shared.base.test", context.packageName)
        listOf(LEGACY, ACTIVE, "legacy-normalized-staging").forEach(context::deleteDatabase)
    }

    @Test(timeout = 120_000L)
    fun releasedMainV12ImportsAllSavedKindsAndReopensWithoutChangingOriginalFiles() {
        helper.createDatabase(LEGACY, 12).use { source ->
            source.query("PRAGMA journal_mode=DELETE").use { row ->
                assertTrue(row.moveToFirst())
                assertEquals("delete", row.getString(0).lowercase())
            }
            seedReleasedRows(source)
            assertRow(source, "SELECT identity_hash FROM room_master_table WHERE id=42",
                "603376ce119f1b4f1d3e4866aec15b4e")
        }
        val original = context.getDatabasePath(LEGACY)
        val originalFamily = familySnapshot(original)
        assertTrue(originalFamily.isNotEmpty())
        repeat(2) {
            val database = AppDatabase.releasedBackupCopyBuilder(context, ACTIVE, original)
                .openHelperFactory(SQLiteXSupportSQLiteOpenHelperFactory(preserveDatabaseFilesOnCorruption = true))
                .allowMainThreadQueries().build()
            try {
                val raw = database.openHelper.writableDatabase
                assertEquals(32, raw.version)
                assertTrue(hasCompletedLegacyImport(raw))
                assertLocationAndSessions(raw)
                assertRadioDetails(raw)
                assertOtherReleasedValues(raw)
                assertRow(raw, "PRAGMA quick_check", "ok")
                raw.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
            } finally { database.close() }
            assertEquals(originalFamily, familySnapshot(original))
            SQLiteDatabase.openDatabase(original.path, null, SQLiteDatabase.OPEN_READONLY).use { source ->
                assertEquals(12, source.version)
                source.rawQuery("SELECT COUNT(*) FROM tracker_session", null).use { row ->
                    assertTrue(row.moveToFirst())
                    assertEquals(3L, row.getLong(0))
                }
            }
        }
        assertFalse(context.getDatabasePath("legacy-normalized-staging").exists())
    }

    /** Root copies this closed file to a fresh Debug profile before its Application is launched. */
    @Test(timeout = 120_000L)
    fun prepareLocationOnlyReleasedMainV12StartupFixture() {
        helper.createDatabase(LEGACY, 12).use { source ->
            source.query("PRAGMA journal_mode=DELETE").use { row ->
                assertTrue(row.moveToFirst())
                assertEquals("delete", row.getString(0).lowercase())
            }
            // Released v12 requires Activity metadata even when it is unknown (type 4, confidence 0).
            source.execSQL("INSERT INTO location_data(id,time,lat,lon,activity,confidence) " +
                "VALUES(101,1700100000000,50.1,14.2,4,0)")
            assertRow(source, "SELECT COUNT(*) FROM tracker_session", 0L)
        }
        val fixture = File(context.filesDir, "released-main12-location-only.db")
        context.getDatabasePath(LEGACY).copyTo(fixture, overwrite = true)
        SQLiteDatabase.openDatabase(fixture.path, null, SQLiteDatabase.OPEN_READONLY).use { raw ->
            assertEquals(12, raw.version)
            raw.rawQuery("SELECT COUNT(*) FROM location_data WHERE id=101", null).use { row ->
                assertTrue(row.moveToFirst()); assertEquals(1L, row.getLong(0))
            }
        }
    }

    private fun seedReleasedRows(source: SupportSQLiteDatabase) {
        source.execSQL("INSERT INTO activity(id,name,iconName) VALUES(7,'My cycling label','cycle')")
        source.execSQL("""INSERT INTO location_data(id,time,lat,lon,alt,activity,confidence)
            VALUES(101,1700100000000,50.123456789,14.234567891,221.125,7,90)""")
        source.execSQL("""INSERT INTO tracker_session(id,start,`end`,user_initiated,collections,
            distance,distance_on_foot,distance_in_vehicle,steps,session_activity_id) VALUES
            (201,1700099900000,1700100100000,0,2,125.0,24.25,100.75,20,7),
            (202,500,700,1,1,8.5,3.25,5.25,2,7),(203,800,-1,0,3,9.5,4.25,5.25,3,7)""")
        source.execSQL("""INSERT INTO wifi_data(bssid,longitude,latitude,altitude,first_seen,last_seen,
            ssid,capabilities,frequency,level) VALUES('02:00:00:00:00:01',14.456789123,50.345678912,222.25,
            1700099999000,1700100001000,'Released network','[WPA2]',2412,-55)""")
        source.execSQL("INSERT INTO network_operator(mcc,mnc,name) VALUES('230','001','Carrier')")
        source.execSQL("""INSERT INTO cell_location(id,time,mcc,mnc,cell_id,type,asu,lat,lon,alt)
            VALUES(301,1700100002000,'230','001',314159,1,18,50.567891234,14.678912345,223.375)""")
        source.execSQL("""INSERT INTO location_wifi_count(id,time,count,lat,lon,alt)
            VALUES(401,1700100003000,3,50.1,14.2,220.0)""")
    }

    private fun assertLocationAndSessions(raw: SupportSQLiteDatabase) {
        assertRow(raw, "SELECT COUNT(*) FROM location_sample", 1L)
        assertRow(raw, "SELECT COUNT(*) FROM location_observation", 1L)
        assertRow(raw, "SELECT legacy_lat,legacy_lon,legacy_alt_m FROM location_sample WHERE id=101",
            50.123456789, 14.234567891, 221.125)
        assertRow(raw, "SELECT COUNT(*) FROM activity_snapshot", 1L)
        assertRow(raw, "SELECT COUNT(*) FROM session_segment", 1L)
        assertRow(raw, """SELECT distance_m,steps,legacy_user_initiated,legacy_distance_on_foot_m,
            legacy_distance_in_vehicle_m,legacy_activity_id,primary_activity,logical_tracking_id,service_run_id
            FROM session_segment WHERE id=201""", 125.0, 20, 0, 24.25, 100.75, 7L, null, null, null)
        assertRow(raw, "SELECT name FROM activity WHERE id=7", "My cycling label")
        assertRow(raw, "SELECT activity_label_id FROM organization_annotation " +
            "WHERE recording_adapter_key='segment:201'", 7L)
        assertRow(raw, "SELECT COUNT(*) FROM logical_tracking_session", 0L)
        assertRow(raw, "SELECT COUNT(*) FROM source_service_run", 0L)
    }

    private fun assertRadioDetails(raw: SupportSQLiteDatabase) {
        assertRow(raw, "SELECT COUNT(*) FROM wifi_observation", 1L)
        assertRow(raw, "SELECT legacy_first_seen_ms,legacy_lat,legacy_lon,legacy_alt_m FROM wifi_observation",
            1_700_099_999_000L, 50.345678912, 14.456789123, 222.25)
        assertRow(raw, "SELECT bssid,ssid,capabilities,frequency,level FROM legacy_wifi_child",
            "02:00:00:00:00:01", "Released network", "[WPA2]", 2412, -55)
        assertRow(raw, "SELECT COUNT(*) FROM cell_sample", 1L)
        assertRow(raw, "SELECT legacy_source_id,legacy_mcc,legacy_mnc,legacy_lat,legacy_lon,legacy_alt_m " +
            "FROM cell_sample",
            301L, "230", "001", 50.567891234, 14.678912345, 223.375)
        assertRow(raw, "SELECT cell_id FROM legacy_cell_child", 314159L)
    }

    private fun assertOtherReleasedValues(raw: SupportSQLiteDatabase) {
        assertRow(raw, """SELECT start,`end`,collections,distance,distance_on_foot,distance_in_vehicle,
            steps,session_activity_id FROM legacy_rejected_tracker_session WHERE id=202""",
            500L, 700L, 1, 8.5, 3.25, 5.25, 2, 7L)
        assertRow(raw, "SELECT start,`end`,session_activity_id FROM legacy_rejected_tracker_session WHERE id=203",
            800L, -1L, 7L)
        assertRow(raw, "SELECT COUNT(*) FROM legacy_rejected_tracker_session", 2L)
        assertRow(raw, "SELECT time,count,lat,lon,alt FROM legacy_location_wifi_count WHERE id=401",
            1_700_100_003_000L, 3, 50.1, 14.2, 220.0)
        assertRow(raw, "SELECT name FROM network_operator WHERE mcc='230' AND mnc='001'", "Carrier")
    }

    private fun assertRow(database: SupportSQLiteDatabase, sql: String, vararg expected: Any?) {
        database.query(sql).use { row ->
            assertTrue(sql, row.moveToFirst())
            assertEquals(sql, expected.size, row.columnCount)
            expected.forEachIndexed { index, value ->
                when (value) {
                    null -> assertTrue(sql, row.isNull(index))
                    is Long -> assertEquals(sql, value, row.getLong(index))
                    is Int -> assertEquals(sql, value, row.getInt(index))
                    is Double -> assertEquals(sql, value, row.getDouble(index), 0.0)
                    is String -> assertEquals(sql, value, row.getString(index))
                    else -> error("Unsupported expected fixture value")
                }
            }
            assertFalse(sql, row.moveToNext())
        }
    }

    private fun familySnapshot(source: File): Map<String, String> =
        listOf("", "-wal", "-shm", "-journal").map { suffix -> File(source.path + suffix) }
            .filter(File::isFile).associate { file ->
                file.name to MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                    .joinToString("") { "%02x".format(it) }
            }

    private companion object {
        const val LEGACY = "main12-native.db"
        const val ACTIVE = "main32-native.db"
    }
}
