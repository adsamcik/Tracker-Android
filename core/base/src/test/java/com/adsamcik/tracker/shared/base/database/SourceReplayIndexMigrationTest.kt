package com.adsamcik.tracker.shared.base.database

import android.app.Application
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** IMPLEMENTED_UNVALIDATED: execute with the final convergence batch. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SourceReplayIndexMigrationTest {
    private val context: Application = ApplicationProvider.getApplicationContext()

    @After fun cleanup() { context.deleteDatabase(NAME) }

    @Test fun `v30 reopens without losing data and source replay uses the ordered index`() {
        openV30().use { helper ->
            helper.writableDatabase.execSQL(
                "INSERT INTO source_evidence_state(id,revision,collected_data_epoch,updated_at_ms) VALUES(1,9,0,1000)",
            )
        }
        repeat(2) {
            val database = AppDatabase.fileBuilder(context, NAME)
                .openHelperFactory(DevelopmentV28ContainmentOpenHelperFactory(context, NAME,
                    FrameworkSQLiteOpenHelperFactory()))
                .allowMainThreadQueries().build()
            try {
                val raw = database.openHelper.writableDatabase
                assertEquals(32, raw.version)
                raw.query("SELECT revision FROM source_evidence_state WHERE id=1").use {
                    assertTrue(it.moveToFirst())
                    assertEquals(9L, it.getLong(0))
                }
                raw.query("PRAGMA index_info(idx_source_event_wal_source_ordinal)").use { cursor ->
                    val names = buildList { while (cursor.moveToNext()) add(cursor.getString(2)) }
                    assertEquals(listOf("source_kind", "admission_ordinal"), names)
                }
                raw.query("EXPLAIN QUERY PLAN SELECT * FROM source_event_wal WHERE source_kind=2 " +
                    "AND admission_ordinal>100 AND admission_ordinal<=10000 ORDER BY admission_ordinal LIMIT 64").use {
                    val details = buildList { while (it.moveToNext()) add(it.getString(3)) }.joinToString(" ")
                    assertTrue(details, details.contains("idx_source_event_wal_source_ordinal"))
                    assertTrue(details, !details.contains("TEMP B-TREE"))
                }
            } finally { database.close() }
        }
    }

    @Test fun `v31 Location identities upgrade with their measurements and unknown continuity preserved`() {
        openSchema(31).use { helper ->
            helper.writableDatabase.execSQL("INSERT INTO location_sample(id,time_ms,elapsed_realtime_nanos," +
                "lat_e7,lon_e7,provider,quality,created_at) VALUES(1,1000,0,500000000,140000000,'gps','HIGH',1000)")
            helper.writableDatabase.execSQL("INSERT INTO location_file_identity(" +
                "portable_id,sample_id,imported,reported_origin) VALUES('original-point',1,1,'unknown')")
        }
        repeat(2) {
            val database = AppDatabase.fileBuilder(context, NAME)
                .openHelperFactory(DevelopmentV28ContainmentOpenHelperFactory(context, NAME,
                    FrameworkSQLiteOpenHelperFactory()))
                .allowMainThreadQueries().build()
            try {
                val raw = database.openHelper.writableDatabase
                assertEquals(32, raw.version)
                raw.query("SELECT i.portable_id,i.path_id,i.path_sequence,s.time_ms FROM location_file_identity i " +
                    "JOIN location_sample s ON s.id=i.sample_id").use {
                    assertTrue(it.moveToFirst())
                    assertEquals("original-point", it.getString(0))
                    assertTrue(it.isNull(1))
                    assertTrue(it.isNull(2))
                    assertEquals(1000L, it.getLong(3))
                }
            } finally { database.close() }
        }
    }

    private fun openV30() = openSchema(30)

    private fun openSchema(version: Int) = FrameworkSQLiteOpenHelperFactory().create(
        SupportSQLiteOpenHelper.Configuration.builder(context).name(NAME)
            .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    val relative = "schemas/com.adsamcik.tracker.shared.base.database.AppDatabase/${version}.json"
                    val schema = sequenceOf(File(relative), File("core/base/$relative")).first(File::isFile)
                    val definition = JSONObject(schema.readText()).getJSONObject("database")
                    val tables = definition.getJSONArray("entities")
                    for (index in 0 until tables.length()) {
                        val table = tables.getJSONObject(index)
                        val name = table.getString("tableName")
                        db.execSQL(table.getString("createSql").replace("${'$'}{TABLE_NAME}", name))
                        val indices = table.optJSONArray("indices") ?: continue
                        for (item in 0 until indices.length()) db.execSQL(indices.getJSONObject(item)
                            .getString("createSql").replace("${'$'}{TABLE_NAME}", name))
                    }
                    val setup = definition.getJSONArray("setupQueries")
                    val views = definition.optJSONArray("views")
                    if (views != null) {
                        for (index in 0 until views.length()) {
                            val view = views.getJSONObject(index)
                            db.execSQL(view.getString("createSql").replace("${'$'}{VIEW_NAME}", view.getString("viewName")))
                        }
                    }
                    for (index in 0 until setup.length()) db.execSQL(setup.getString(index))
                    TrackingOwnerValidationRoomCallback.onCreate(db)
                    FinalV28SchemaAssemblyRoomCallback.onCreate(db)
                    RadioObservationChildRoomCallback.onCreate(db)
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                    error("The fixture creates only the unchanged v30 input")
            }).build(),
    )

    private companion object { const val NAME = "source-replay-index-v30-v31.db" }
}
