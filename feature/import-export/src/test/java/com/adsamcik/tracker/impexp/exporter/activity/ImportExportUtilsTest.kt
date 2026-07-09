package com.adsamcik.tracker.impexp.exporter.activity

import io.mockk.every
import io.mockk.mockk
import com.adsamcik.tracker.impexp.exporter.Exporter
import com.adsamcik.tracker.shared.base.data.NativeSessionActivity
import org.junit.Test
import io.kotest.matchers.shouldBe
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportExportUtilsTest {

    // --- preventDoubleExtension tests ---

    @Test
    fun `preventDoubleExtension removes extension when filename already ends with it`() {
        // Use a mime type that Robolectric's MimeTypeMap recognizes
        val exporter = mockk<Exporter> {
            every { mimeType } returns "application/xml"
        }
        val result = preventDoubleExtension("my_track.xml", exporter)
        result shouldBe "my_track"
    }

    @Test
    fun `preventDoubleExtension keeps filename unchanged when no duplicate extension`() {
        val exporter = mockk<Exporter> {
            every { mimeType } returns "application/gpx+xml"
        }
        val result = preventDoubleExtension("my_track", exporter)
        result shouldBe "my_track"
    }

    @Test
    fun `preventDoubleExtension handles kml mime type`() {
        val exporter = mockk<Exporter> {
            every { mimeType } returns "application/vnd.google-earth.kml+xml"
        }
        val result = preventDoubleExtension("export.kml", exporter)
        result shouldBe "export"
    }

    @Test
    fun `preventDoubleExtension does not remove partial extension match`() {
        val exporter = mockk<Exporter> {
            every { mimeType } returns "application/gpx+xml"
        }
        val result = preventDoubleExtension("my_track.gpx_backup", exporter)
        result shouldBe "my_track.gpx_backup"
    }

    @Test
    fun `preventDoubleExtension handles empty filename`() {
        val exporter = mockk<Exporter> {
            every { mimeType } returns "application/gpx+xml"
        }
        val result = preventDoubleExtension("", exporter)
        result shouldBe ""
    }

    @Test
    fun `preventDoubleExtension handles unknown mime type gracefully`() {
        val exporter = mockk<Exporter> {
            every { mimeType } returns "application/x-unknown"
        }
        val result = preventDoubleExtension("file.xyz", exporter)
        result shouldBe "file.xyz"
    }

    // --- mapActivityToEmoji tests ---

    @Test
    fun `mapActivityToEmoji walking id returns hiking emoji`() {
        mapActivityToEmoji(null, NativeSessionActivity.WALKING.id) shouldBe "🥾"
    }

    @Test
    fun `mapActivityToEmoji running id returns runner emoji`() {
        mapActivityToEmoji(null, NativeSessionActivity.RUNNING.id) shouldBe "🏃"
    }

    @Test
    fun `mapActivityToEmoji bicycle id returns cycling emoji`() {
        mapActivityToEmoji(null, NativeSessionActivity.BICYCLE.id) shouldBe "🚴"
    }

    @Test
    fun `mapActivityToEmoji vehicle id returns car emoji`() {
        mapActivityToEmoji(null, NativeSessionActivity.VEHICLE.id) shouldBe "🚗"
    }

    @Test
    fun `mapActivityToEmoji land vehicle id returns car emoji`() {
        mapActivityToEmoji(null, NativeSessionActivity.LAND_VEHICLE.id) shouldBe "🚗"
    }

    @Test
    fun `mapActivityToEmoji water vehicle id returns car emoji`() {
        mapActivityToEmoji(null, NativeSessionActivity.WATER_VEHICLE.id) shouldBe "🚗"
    }

    @Test
    fun `mapActivityToEmoji air vehicle id returns car emoji`() {
        mapActivityToEmoji(null, NativeSessionActivity.AIR_VEHICLE.id) shouldBe "🚗"
    }

    @Test
    fun `mapActivityToEmoji walk name matches walking emoji`() {
        mapActivityToEmoji("Morning Walk", null) shouldBe "🥾"
    }

    @Test
    fun `mapActivityToEmoji run name matches running emoji`() {
        mapActivityToEmoji("Evening Run", null) shouldBe "🏃"
    }

    @Test
    fun `mapActivityToEmoji bike name matches cycling emoji`() {
        mapActivityToEmoji("Bike ride", null) shouldBe "🚴"
    }

    @Test
    fun `mapActivityToEmoji cycle name matches cycling emoji`() {
        mapActivityToEmoji("cycle to work", null) shouldBe "🚴"
    }

    @Test
    fun `mapActivityToEmoji car name matches vehicle emoji`() {
        mapActivityToEmoji("car trip", null) shouldBe "🚗"
    }

    @Test
    fun `mapActivityToEmoji still name matches pause emoji`() {
        mapActivityToEmoji("still", null) shouldBe "⏸️"
    }

    @Test
    fun `mapActivityToEmoji unknown activity returns pin emoji`() {
        mapActivityToEmoji("swimming", null) shouldBe "📍"
    }

    @Test
    fun `mapActivityToEmoji null name and null id returns pin emoji`() {
        mapActivityToEmoji(null, null) shouldBe "📍"
    }

    @Test
    fun `mapActivityToEmoji id takes precedence over name`() {
        mapActivityToEmoji("run", NativeSessionActivity.WALKING.id) shouldBe "🥾"
    }

    @Test
    fun `mapActivityToEmoji case insensitive name matching`() {
        mapActivityToEmoji("WALKING", null) shouldBe "🥾"
        mapActivityToEmoji("Running", null) shouldBe "🏃"
        mapActivityToEmoji("BIKE ride", null) shouldBe "🚴"
    }

    // --- findAvailableFileName tests ---

    @Test
    fun `findAvailableFileName returns base name when no conflict`() {
        val dir = mockk<androidx.documentfile.provider.DocumentFile> {
            every { findFile("export.gpx") } returns null
        }
        findAvailableFileName(dir, "export", "gpx") shouldBe "export"
    }

    @Test
    fun `findAvailableFileName appends counter when file exists`() {
        val dir = mockk<androidx.documentfile.provider.DocumentFile> {
            every { findFile("export.gpx") } returns mockk()
            every { findFile("export_1.gpx") } returns null
        }
        findAvailableFileName(dir, "export", "gpx") shouldBe "export_1"
    }

    @Test
    fun `findAvailableFileName increments counter for multiple conflicts`() {
        val dir = mockk<androidx.documentfile.provider.DocumentFile> {
            every { findFile("export.gpx") } returns mockk()
            every { findFile("export_1.gpx") } returns mockk()
            every { findFile("export_2.gpx") } returns mockk()
            every { findFile("export_3.gpx") } returns null
        }
        findAvailableFileName(dir, "export", "gpx") shouldBe "export_3"
    }

    @Test
    fun `cleanupShareableDirectory deletes stale files and keeps fresh files`() {
        val dir = File("build/test-shareable-cleanup-${System.nanoTime()}")
        val oldFile = File(dir, "old.gpx")
        val freshFile = File(dir, "fresh.gpx")
        val subdirectory = File(dir, "nested")
        try {
            dir.mkdirs()
            subdirectory.mkdirs()
            oldFile.writeText("old")
            freshFile.writeText("fresh")
            oldFile.setLastModified(1_000L)
            freshFile.setLastModified(1_900L)

            cleanupShareableDirectory(
                directory = dir,
                nowMillis = 2_000L,
                maxAgeMillis = 500L,
            )

            oldFile.exists() shouldBe false
            freshFile.exists() shouldBe true
            subdirectory.exists() shouldBe true
        } finally {
            dir.deleteRecursively()
        }
    }
}
