package com.adsamcik.tracker.map.basemap

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BasemapManagerImportValidationTest {

    private lateinit var context: Application
    private lateinit var manager: BasemapManager
    private lateinit var workDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val dispatchers = object : DispatchersProvider {
            override val io get() = UnconfinedTestDispatcher()
            override val default get() = UnconfinedTestDispatcher()
            override val main get() = UnconfinedTestDispatcher()
            override val unconfined get() = UnconfinedTestDispatcher()
        }
        manager = BasemapManager(context, dispatchers)
        workDir = File(context.cacheDir, "basemap-import-test").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        workDir.deleteRecursively()
        File(context.filesDir, "basemap").deleteRecursively()
    }

    @Test
    fun `valid PMTiles v3 file is accepted`() = runTest {
        val uri = writeFixture("valid.pmtiles", buildPmTilesHeader(version = 0x03) + ByteArray(64) { 0x42 })
        val result = manager.importBasemap(uri)
        assertTrue("Expected Success but was $result", result is BasemapImportResult.Success)
        assertTrue("Expected custom.pmtiles to exist after success", File(context.filesDir, "basemap/custom.pmtiles").exists())
    }

    @Test
    fun `file shorter than header is rejected as InvalidFormat`() = runTest {
        val uri = writeFixture("truncated.pmtiles", "PMT".encodeToByteArray())
        val result = manager.importBasemap(uri)
        assertTrue("Expected InvalidFormat but was $result", result is BasemapImportResult.InvalidFormat)
        assertFalse("Custom file must be deleted on validation failure", File(context.filesDir, "basemap/custom.pmtiles").exists())
    }

    @Test
    fun `wrong magic bytes are rejected as InvalidFormat`() = runTest {
        val bogus = "DEFINITELYNOTPMTILES".encodeToByteArray()
        val uri = writeFixture("bogus.pmtiles", bogus + ByteArray(32))
        val result = manager.importBasemap(uri)
        assertTrue("Expected InvalidFormat but was $result", result is BasemapImportResult.InvalidFormat)
        assertFalse(File(context.filesDir, "basemap/custom.pmtiles").exists())
    }

    @Test
    fun `wrong PMTiles version is rejected as InvalidFormat`() = runTest {
        val uri = writeFixture("v2.pmtiles", buildPmTilesHeader(version = 0x02) + ByteArray(32))
        val result = manager.importBasemap(uri)
        assertTrue("Expected InvalidFormat for v2 but was $result", result is BasemapImportResult.InvalidFormat)
        assertFalse(File(context.filesDir, "basemap/custom.pmtiles").exists())
    }

    @Test
    fun `empty file is rejected as InvalidFormat`() = runTest {
        val uri = writeFixture("empty.pmtiles", ByteArray(0))
        val result = manager.importBasemap(uri)
        assertTrue("Expected InvalidFormat for empty file but was $result", result is BasemapImportResult.InvalidFormat)
        assertFalse(File(context.filesDir, "basemap/custom.pmtiles").exists())
    }

    @Test
    fun `nonexistent Uri returns SourceOpenFailed`() = runTest {
        val missing = Uri.fromFile(File(workDir, "does-not-exist.pmtiles"))
        val result = manager.importBasemap(missing)
        assertEquals(
            BasemapImportResult.SourceOpenFailed::class,
            result::class,
        )
    }

    private fun writeFixture(name: String, bytes: ByteArray): Uri {
        val file = File(workDir, name)
        file.writeBytes(bytes)
        return Uri.fromFile(file)
    }

    private fun buildPmTilesHeader(version: Int): ByteArray {
        val magic = "PMTiles".encodeToByteArray()
        return magic + byteArrayOf(version.toByte())
    }
}
