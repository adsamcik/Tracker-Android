package com.adsamcik.tracker.impexp.exporter

import android.content.Context
import androidx.room.RoomDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.LocationSample
import com.adsamcik.tracker.shared.base.database.data.SampleQuality
import com.adsamcik.tracker.shared.base.database.legacy.ACTIVE_DATABASE_NAME
import com.adsamcik.tracker.sqlite.runtime.SQLiteXSupportSQLite
import com.adsamcik.tracker.sqlite.runtime.SQLiteXSupportSQLiteOpenHelperFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Actual production export alongside a live SQLiteX Room pool and its invalidation observer. */
@RunWith(AndroidJUnit4::class)
class DatabaseBackupNativeInstrumentedTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun prepare() = cleanup()
    @After fun cleanup() {
        assertEquals("com.adsamcik.tracker.impexp.test", context.packageName)
        context.deleteDatabase(ACTIVE_DATABASE_NAME)
        listOf("", "-wal", "-shm", "-journal").forEach { File(context.cacheDir, COPY_NAME + it).delete() }
    }

    @Test(timeout = 120_000L)
    fun dataExportPreservesLiveWalAndRoomObservationAndFurtherWrites() = runBlocking {
        val database = AppDatabase.fileBuilder(context, ACTIVE_DATABASE_NAME)
            .openHelperFactory(SQLiteXSupportSQLiteOpenHelperFactory(preserveDatabaseFilesOnCorruption = true))
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING).build()
        val updates = Channel<Long>(Channel.UNLIMITED)
        try {
            database.sourceEvidenceStateDao().ensure()
            database.locationSampleDao().insert(point(1_000))
            val observer = launch(Dispatchers.IO) {
                database.invalidationTracker.createFlow("location_sample", emitInitialState = true).collect {
                    updates.send(database.locationSampleDao().countAll())
                }
            }
            try {
                assertEquals(1L, withTimeout(WAIT_MS) { updates.receive() })
                assertLiveWal(database)
                val output = ByteArrayOutputStream()
                val result = withContext(Dispatchers.IO) {
                    DatabaseExporter().export(context, emptySequence(), output, null)
                }
                assertTrue(result.isSuccess)
                assertLiveWal(database)
                database.locationSampleDao().insert(point(2_000))
                withTimeout(WAIT_MS) {
                    var count = updates.receive()
                    while (count < 2L) count = updates.receive()
                    assertEquals(2L, count)
                }
                assertEquals(2L, database.locationSampleDao().countAll())
                assertLiveWal(database)
                assertArchivedData(output.toByteArray())
            } finally {
                observer.cancelAndJoin()
            }
        } finally {
            updates.close()
            database.close()
        }
    }

    private fun assertLiveWal(database: AppDatabase) {
        database.openHelper.writableDatabase.query("PRAGMA journal_mode").use { row ->
            assertTrue(row.moveToFirst())
            assertEquals("wal", row.getString(0))
        }
        val file = context.getDatabasePath(ACTIVE_DATABASE_NAME)
        assertTrue(File(file.path + "-wal").isFile)
        assertTrue(File(file.path + "-shm").length() > 0L)
    }

    private fun assertArchivedData(bytes: ByteArray) {
        val copy = File(context.cacheDir, COPY_NAME)
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == "databases/$ACTIVE_DATABASE_NAME") {
                    copy.writeBytes(zip.readBytes())
                }
                zip.closeEntry()
            }
        }
        assertTrue(copy.isFile)
        SQLiteXSupportSQLite.openReadOnly(copy.path).use { archived ->
            archived.query("SELECT time_ms FROM location_sample").use { row ->
                assertTrue(row.moveToFirst())
                assertEquals(1_000L, row.getLong(0))
                assertTrue(!row.moveToNext())
            }
            archived.query("PRAGMA quick_check").use { row ->
                assertTrue(row.moveToFirst())
                assertEquals("ok", row.getString(0))
            }
        }
    }

    private fun point(timeMs: Long) = LocationSample(timeMs = timeMs, elapsedRealtimeNanos = 0,
        latE7 = 500_000_000, lonE7 = 140_000_000, altitudeM = null, rawGpsAltitudeM = null,
        hAccM = null, vAccM = null, speedMps = null, speedAccuracyMps = null, provider = "saved",
        quality = SampleQuality.COARSE, motionState = null, policy = null, bucketId = null, createdAt = timeMs)

    private companion object {
        const val COPY_NAME = "native-backup-copy.db"
        const val WAIT_MS = 30_000L
    }
}
