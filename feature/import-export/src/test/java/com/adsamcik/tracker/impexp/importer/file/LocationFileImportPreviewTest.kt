package com.adsamcik.tracker.impexp.importer.file

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.adsamcik.tracker.impexp.portable.InvalidLocationFile
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.LocationFileIdentityDao
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocationFileImportPreviewTest {
    private val identities = mockk<LocationFileIdentityDao>()
    private val database = mockk<AppDatabase> {
        every { locationFileIdentityDao() } returns identities
    }

    @Test fun `whole valid file has stable job identity and no invented conflict`() = runBlocking {
        coEvery { identities.byPortableIds(any()) } returns emptyList()
        val source = fileBytes(footerCount = 1)

        val first = inspect(source, 7)
        val repeat = inspect(source, 7)

        assertEquals(1, first.pointCount)
        assertEquals(0, first.conflictCount)
        assertEquals(0, first.matchingCount)
        assertEquals(7L, first.dataEpoch)
        assertEquals(first.fileJobId, repeat.fileJobId)
    }

    @Test fun `invalid footer is rejected before conflict choice`() {
        coEvery { identities.byPortableIds(any()) } returns emptyList()
        assertThrows(InvalidLocationFile::class.java) {
            runBlocking {
                inspect(fileBytes(footerCount = 2), 1)
            }
        }
    }

    @Test fun `point ID repeated after a full query batch is rejected before a conflict choice`() {
        coEvery { identities.byPortableIds(any()) } returns emptyList()
        val original = fileBytes(footerCount = 1).toString(Charsets.UTF_8)
        val point = original.lines()[1]
        val repeated = (listOf(original.lines()[0]) +
            (1..256).map { index -> point.replace("point-1", "point-$index") } +
            point + """{"type":"end","pointCount":257}""").joinToString("\n", postfix = "\n")
        assertThrows(InvalidLocationFile::class.java) {
            runBlocking { inspect(repeated.toByteArray(), 1) }
        }
    }

    @Test fun `owned preview cleanup leaves unrelated cache files alone`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val unrelated = File(context.cacheDir, "unrelated-location-note.txt")
        unrelated.writeText("keep")
        val stale = File(context.cacheDir, "tracker-location-point-ids-stale.db")
        stale.writeText("stale")
        try {
            LocationPointIdIndex.clearOwnedStaging(context)
            assertEquals(false, stale.exists())
            assertEquals(true, unrelated.exists())
        } finally {
            unrelated.delete()
            stale.delete()
        }
    }

    @Test fun `failed private Location cleanup is reported`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val nonEmpty = File(context.cacheDir, "tracker-location-cleanup-test")
        val child = File(nonEmpty, "still-here")
        nonEmpty.mkdirs()
        child.writeText("saved")
        try {
            assertThrows(IOException::class.java) {
                deleteOwnedLocationTempFile(nonEmpty, "Location import")
            }
            assertEquals(true, child.exists())
        } finally {
            child.delete()
            nonEmpty.delete()
        }
    }

    @Test fun `closing the point index removes its private database files`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val before = context.cacheDir.listFiles().orEmpty()
            .filter { it.name.startsWith("tracker-location-point-ids-") }.map { it.name }.toSet()
        LocationPointIdIndex.open(context.cacheDir).use { pointIds ->
            pointIds.add("point-to-clean")
        }
        val after = context.cacheDir.listFiles().orEmpty()
            .filter { it.name.startsWith("tracker-location-point-ids-") }.map { it.name }.toSet()
        assertEquals(before, after)
    }

    @Test fun `point ID index closes each batch before a coroutine changes threads`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        LocationPointIdIndex.open(context.cacheDir).use { pointIds ->
            pointIds.add("point-a")
            pointIds.finish()
            withContext(Dispatchers.IO) {
                pointIds.recordExisting("point-a", "saved measurement")
                pointIds.finish()
            }
            assertEquals(true, pointIds.expectedExisting(listOf("point-a"))["point-a"]?.first)
            assertEquals("saved measurement",
                pointIds.expectedExisting(listOf("point-a"))["point-a"]?.second)
        }
    }

    private suspend fun inspect(bytes: ByteArray, epoch: Long): LocationFileImportPreview =
        LocationPointIdIndex.open(File(System.getProperty("java.io.tmpdir"))).use { pointIds ->
            inspectLocationFile(database, ByteArrayInputStream(bytes), epoch, pointIds)
        }

    private fun fileBytes(footerCount: Int): ByteArray = listOf(
        """{"format":"tracker-location","version":1}""",
        """{"type":"point","id":"point-1","origin":"unknown","timeMs":100,"latE7":500000000,"lonE7":140000000,"provider":"gps","quality":"high","permissionPrecision":"FINE","isMock":false,"altitudeDatum":"unknown_legacy","altitudeSource":"unknown_legacy","altitudeConversionStatus":"unknown_legacy","rawGpsAltitudeDatum":"unknown_legacy"}""",
        """{"type":"end","pointCount":$footerCount}""",
    ).joinToString("\n", postfix = "\n").toByteArray()
}
