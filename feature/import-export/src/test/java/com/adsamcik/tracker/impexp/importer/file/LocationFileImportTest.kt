package com.adsamcik.tracker.impexp.importer.file

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.impexp.importer.FileImportStream
import com.adsamcik.tracker.impexp.exporter.LocationFileExporter
import com.adsamcik.tracker.impexp.portable.LocationFileJsonV1
import com.adsamcik.tracker.impexp.portable.LocationFilePointV1
import com.adsamcik.tracker.impexp.portable.LocationFileOrigin
import com.adsamcik.tracker.shared.base.database.AppDatabase
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocationFileImportTest {
    private lateinit var context: Application
    private lateinit var database: AppDatabase

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = AppDatabase.testDatabase(context)
        runBlocking { database.sourceEvidenceStateDao().ensure() }
    }

    @After fun tearDown() = database.close()

    @Test fun `one saved point imports once and overlapping file skips its stable ID`() = runBlocking {
        val first = import(pointFile(500_000_000), "job-one")
        assertEquals(1, first.successCount)
        val identity = database.locationFileIdentityDao().byPortableId("point-a")
        assertNotNull(identity)
        assertEquals(500_000_000,
            database.locationSampleDao().byIds(listOf(requireNotNull(identity).sampleId))
                .single().latE7)

        val again = import(pointFile(500_000_000), "job-two")
        assertEquals(0, again.successCount)
        assertEquals(1, again.skippedCount)
        assertEquals(identity, database.locationFileIdentityDao().byPortableId("point-a"))

        val output = ByteArrayOutputStream()
        LocationFileExporter().exportAfter(context, emptySequence(), output,
            1_000L..1_000L, null, null, database, {})
        val reexported = mutableListOf<LocationFilePointV1>()
        LocationFileJsonV1().read(ByteArrayInputStream(output.toByteArray())) { reexported += it }
        assertEquals("point-a", reexported.single().pointId)
        assertEquals(500_000_000, reexported.single().measurement.latE7)
        assertEquals(LocationFileOrigin.UNKNOWN, reexported.single().origin)
    }

    @Test fun `edited point ID without a reviewed choice cannot change an existing point`() = runBlocking {
        import(pointFile(500_000_000), "job-one")
        val result = import(pointFile(500_000_001), "job-two")
        assertEquals(1, result.failedCount)
        val identity = requireNotNull(database.locationFileIdentityDao().byPortableId("point-a"))
        assertEquals(500_000_000,
            database.locationSampleDao().byIds(listOf(identity.sampleId)).single().latE7)
    }

    @Test fun `one file owns duplicate point membership across different outer jobs`() = runBlocking {
        val bytes = pointFile(500_000_000)
        val identity = preview(bytes).fileJobId
        import(bytes, "first-outer-job")
        val first = database.locationFileIdentityDao().archivePointPage(identity, -1, 256).single()
        import(bytes, "different-outer-job")
        val dao = database.locationFileIdentityDao()
        assertEquals(1, requireNotNull(dao.archive(identity)).pointCount)
        assertEquals(listOf(first), dao.archivePointPage(identity, -1, 256))
        assertEquals("point-a", first.portableId)
        assertEquals(requireNotNull(dao.byPortableId("point-a")).sampleId, first.sampleId)
        assertEquals(1L, database.locationSampleDao().countAll())
    }

    @Test fun `retention skipped points remain declared unavailable members`() = runBlocking {
        val epoch = requireNotNull(database.sourceEvidenceStateDao().get()).collectedDataEpoch
        database.sourceEvidenceStateDao().updateLifecycle(epoch, 2_000L, 2_000L)
        val bytes = pointFile(500_000_000)
        val identity = preview(bytes).fileJobId
        val result = import(bytes, "retained-file")
        assertEquals(1, result.skippedCount)
        val dao = database.locationFileIdentityDao()
        assertEquals(1, requireNotNull(dao.archive(identity)).pointCount)
        assertEquals("point-a", dao.archivePointPage(identity, -1, 256).single().portableId)
        assertNull(dao.archivePointPage(identity, -1, 256).single().sampleId)
        assertEquals(0, dao.archivePointCounts(identity, 2_000L).availableCount)
    }

    @Test fun `failed later membership batch retries without duplicating the saved prefix`() = runBlocking {
        val bytes = manyPointFile(260)
        val identity = preview(bytes).fileJobId
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_location_membership BEFORE INSERT ON location_file_archive_point " +
                "WHEN NEW.ordinal >= 256 BEGIN SELECT RAISE(ABORT, 'test write failure'); END",
        )
        assertThrows(android.database.sqlite.SQLiteException::class.java) {
            runBlocking { import(bytes, "resumable-file") }
        }
        val dao = database.locationFileIdentityDao()
        assertEquals(256, dao.archivePointCounts(identity, null).membershipCount)
        assertEquals(256L, database.locationSampleDao().countAll())
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_location_membership")

        val result = import(bytes, "resumable-file")
        assertEquals(4, result.successCount)
        assertEquals(256, result.skippedCount)
        assertEquals(260, dao.archivePointCounts(identity, null).membershipCount)
        assertEquals(260, dao.archivePointCounts(identity, null).availableCount)
        assertEquals(260L, database.locationSampleDao().countAll())
        assertEquals(256, dao.archivePointPage(identity, -1, 256).size)
        assertEquals(listOf(256, 257, 258, 259), dao.archivePointPage(identity, 255, 256).map { it.ordinal })
    }

    @Test fun `full clear removes Location archive membership with points`() = runBlocking {
        val bytes = pointFile(500_000_000)
        val identity = preview(bytes).fileJobId
        import(bytes, "file-before-clear")
        val epoch = requireNotNull(database.sourceEvidenceStateDao().get()).collectedDataEpoch
        AppDatabase.deleteAllCollectedDataForTesting(database, "location-membership-clear",
            epoch + 1L, null, 2_000L)
        val dao = database.locationFileIdentityDao()
        assertNull(dao.archive(identity))
        assertEquals(emptyList<Any>(), dao.archivePointPage(identity, -1, 256))
        assertNull(dao.byPortableId("point-a"))
    }

    @Test fun `a Location file cannot import without the original job epoch`() {
        assertThrows(java.io.IOException::class.java) {
            runBlocking {
                LocationFileImport().import(context, database,
                    FileImportStream(ByteArrayInputStream(pointFile(500_000_000)), "backup.trackerlocation")
                        .withImportReceipt("unbound-job", 1_000L))
            }
        }
        runBlocking { assertEquals(null, database.locationFileIdentityDao().byPortableId("point-a")) }
    }

    @Test fun `a stale direct or archive entry cannot adopt the new data epoch`() = runBlocking {
        val originalEpoch = requireNotNull(database.sourceEvidenceStateDao().get()).collectedDataEpoch
        database.sourceEvidenceStateDao().updateLifecycle(originalEpoch + 1L, null, 2_000L)
        listOf("direct-entry", "archive-entry-2").forEach { entryKey ->
            var opened = false
            val result = LocationFileImport().import(context, database,
                FileImportStream("backup.trackerlocation", entryKey, {
                    opened = true
                    ByteArrayInputStream(pointFile(500_000_000))
                }).withImportReceipt("stale-$entryKey", 1_000L).withOriginalDataEpoch(originalEpoch))
            assertEquals(1, result.failedCount)
            assertEquals(false, opened)
            assertEquals(null, database.locationFileIdentityDao().byPortableId("point-a"))
        }
    }

    @Test fun `clearing data during staging rejects the file and removes private staged bytes`() = runBlocking {
        val before = ownedStagedFiles()
        val originalEpoch = requireNotNull(database.sourceEvidenceStateDao().get()).collectedDataEpoch
        val input = object : ByteArrayInputStream(pointFile(500_000_000)) {
            private var advanced = false
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val read = super.read(buffer, offset, length)
                if (read < 0 && !advanced) {
                    advanced = true
                    runBlocking {
                        database.sourceEvidenceStateDao().updateLifecycle(originalEpoch + 1L, null, 2_000L)
                    }
                }
                return read
            }
        }
        val result = LocationFileImport().import(context, database,
            FileImportStream(input, "backup.trackerlocation")
                .withImportReceipt("cleared-during-staging", 1_000L)
                .withOriginalDataEpoch(originalEpoch))
        assertEquals(1, result.failedCount)
        assertEquals(null, database.locationFileIdentityDao().byPortableId("point-a"))
        assertEquals(originalEpoch + 1L,
            requireNotNull(database.sourceEvidenceStateDao().get()).collectedDataEpoch)
        assertEquals(before, ownedStagedFiles())
    }

    @Test fun `saved point changed after warning requires a fresh review`() = runBlocking {
        import(pointFile(500_000_000), "job-one")
        val editedFile = pointFile(500_000_002)
        val reviewed = preview(editedFile)
        assertEquals(1, reviewed.conflictCount)
        database.openHelper.writableDatabase.execSQL(
            "UPDATE location_sample SET lat_e7 = 500000001 WHERE id = " +
                requireNotNull(database.locationFileIdentityDao().byPortableId("point-a")).sampleId,
        )

        val changed = preview(editedFile)
        assertEquals(reviewed.fileJobId, changed.fileJobId)
        assertEquals(reviewed.conflictCount, changed.conflictCount)
        org.junit.Assert.assertNotEquals(reviewed.existingStateHash, changed.existingStateHash)
        val result = import(editedFile, "job-two",
            LocationFileImportReview(reviewed, LocationFileConflictChoice.KEEP_EXISTING))
        assertEquals(1, result.failedCount)
        val identity = requireNotNull(database.locationFileIdentityDao().byPortableId("point-a"))
        assertEquals(500_000_001,
            database.locationSampleDao().byIds(listOf(identity.sampleId)).single().latE7)
    }

    @Test fun `cancelled Location import removes its private staged bytes`() {
        val before = context.cacheDir.listFiles().orEmpty()
            .filter { it.name.startsWith("tracker-location-import-") }.map { it.name }.toSet()
        val interrupted = object : InputStream() {
            private var delivered = false
            override fun read(): Int = throw CancellationException("Import cancelled")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (delivered) throw CancellationException("Import cancelled")
                delivered = true
                buffer[offset] = 'x'.code.toByte()
                return 1
            }
        }
        assertThrows(CancellationException::class.java) {
            runBlocking {
                LocationFileImport().import(context, database,
                    FileImportStream(interrupted, "backup.trackerlocation")
                        .withImportReceipt("cancelled-job", 1_000L)
                        .withOriginalDataEpoch(requireNotNull(database.sourceEvidenceStateDao().get())
                            .collectedDataEpoch))
            }
        }
        val after = context.cacheDir.listFiles().orEmpty()
            .filter { it.name.startsWith("tracker-location-import-") }.map { it.name }.toSet()
        assertEquals(before, after)
    }

    private suspend fun import(bytes: ByteArray, jobId: String,
        review: LocationFileImportReview? = null) = LocationFileImport().import(
        context, database,
        FileImportStream(ByteArrayInputStream(bytes), "backup.trackerlocation")
            .withImportReceipt(jobId, 1_000L)
            .withOriginalDataEpoch(requireNotNull(database.sourceEvidenceStateDao().get()).collectedDataEpoch)
            .let { stream -> review?.let(stream::withLocationReview) ?: stream },
    )

    private fun ownedStagedFiles() = context.cacheDir.listFiles().orEmpty()
        .filter { it.name.startsWith("tracker-location-import-") }.map { it.name }.toSet()

    private suspend fun preview(bytes: ByteArray): LocationFileImportPreview =
        LocationPointIdIndex.open(context.cacheDir).use { index ->
            inspectLocationFile(database, ByteArrayInputStream(bytes),
                requireNotNull(database.sourceEvidenceStateDao().get()).collectedDataEpoch, index)
        }

    private fun pointFile(latitudeE7: Int): ByteArray = listOf(
        """{"format":"tracker-location","version":1}""",
        """{"type":"point","id":"point-a","origin":"unknown","timeMs":1000,"latE7":$latitudeE7,"lonE7":140000000,"provider":"gps","quality":"high","permissionPrecision":"FINE","isMock":false,"altitudeDatum":"unknown_legacy","altitudeSource":"unknown_legacy","altitudeConversionStatus":"unknown_legacy","rawGpsAltitudeDatum":"unknown_legacy"}""",
        """{"type":"end","pointCount":1}""",
    ).joinToString("\n", postfix = "\n").toByteArray()

    private fun manyPointFile(count: Int): ByteArray {
        val template = pointFile(500_000_000).toString(Charsets.UTF_8).lineSequence().elementAt(1)
        return (listOf("""{"format":"tracker-location","version":1}""") +
            (0 until count).map { ordinal ->
                template.replace("point-a", "point-$ordinal").replace("\"timeMs\":1000", "\"timeMs\":${1_000 + ordinal}")
            } + listOf("""{"type":"end","pointCount":$count}"""))
            .joinToString("\n", postfix = "\n").toByteArray()
    }
}
