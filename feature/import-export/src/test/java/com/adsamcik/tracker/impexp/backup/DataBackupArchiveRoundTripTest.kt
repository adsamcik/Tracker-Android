package com.adsamcik.tracker.impexp.backup

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.impexp.exporter.DatabaseBackupArchive
import com.adsamcik.tracker.impexp.exporter.DatabaseBackupScope
import com.adsamcik.tracker.impexp.exporter.SQLiteDatabaseCopyLockProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.legacy.ACTIVE_DATABASE_NAME
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DataBackupArchiveRoundTripTest {
    @Test
    fun `data backup keeps imported files and excludes settings schedules and diagnostics`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(ACTIVE_DATABASE_NAME)
        val settings = context.getDatabasePath("preference_database")
        val map = File(context.filesDir, "basemap/custom.pmtiles")
        val archive = File(context.cacheDir, "data-scope-backup.zip")
        try {
            val source = AppDatabase.fileBuilder(context, ACTIVE_DATABASE_NAME).allowMainThreadQueries().build()
            try {
                source.sourceEvidenceStateDao().ensure(SourceEvidenceState(collectedDataEpoch = 5L))
            } finally { source.close() }
            settings.parentFile!!.mkdirs()
            settings.writeText("settings must stay private")
            map.parentFile!!.mkdirs()
            map.writeBytes(byteArrayOf(1, 2, 3, 4))
            val sources = DatabaseBackupScope.discover(context)
            sources.any { it.file == settings } shouldBe false
            archive.outputStream().use {
                DatabaseBackupArchive.writeData(sources, it, SQLiteDatabaseCopyLockProvider)
            }
            val reader = DataBackupArchiveReader(context)
            val preview = reader.inspect(Uri.fromFile(archive)).shouldBeInstanceOf<DataBackupInspection.Ready>().preview
            val staged = reader.directory(preview.token)
            File(staged, "files/basemap/custom.pmtiles").readBytes().toList() shouldBe listOf<Byte>(1, 2, 3, 4)
            File(staged, "databases/preference_database").exists() shouldBe false
            File(staged, "databases/$ACTIVE_DATABASE_NAME").isFile shouldBe true
            reader.discard(preview.token)
            staged.exists() shouldBe false
            settings.readText() shouldBe "settings must stay private"
        } finally {
            context.deleteDatabase(ACTIVE_DATABASE_NAME)
            settings.delete(); map.delete(); archive.delete()
        }
    }

    @Test
    fun `ordinary source files and zip archives are not destructive data backups`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "ordinary-source.json")
        val zip = File(context.cacheDir, "ordinary-source.zip")
        try {
            file.writeText("{\"points\":[]}")
            ZipOutputStream(zip.outputStream()).use { output ->
                output.putNextEntry(ZipEntry("source.gpx")); output.write("<gpx/>".toByteArray()); output.closeEntry()
            }
            val reader = DataBackupArchiveReader(context)
            reader.inspect(Uri.fromFile(file)) shouldBe DataBackupInspection.NotBackup
            reader.inspect(Uri.fromFile(zip)) shouldBe DataBackupInspection.NotBackup
        } finally { file.delete(); zip.delete() }
    }

    @Test
    fun `a checksum mismatch or undeclared entry is rejected before any database changes`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val archive = File(context.cacheDir, "damaged-data-backup.zip")
        val name = "databases/$ACTIVE_DATABASE_NAME"
        try {
            ZipOutputStream(archive.outputStream()).use { output ->
                output.putNextEntry(ZipEntry(name)); output.write(byteArrayOf(1, 2)); output.closeEntry()
                output.putNextEntry(ZipEntry("manifest.json"))
                output.write(("{\"format\":\"tracker-database-backup\",\"version\":2,\"scope\":\"data\",\"files\":" +
                    "[{\"path\":\"$name\",\"sizeBytes\":2,\"sha256\":\"${"0".repeat(64)}\"}]}").toByteArray())
                output.closeEntry()
            }
            DataBackupArchiveReader(context).inspect(Uri.fromFile(archive)) shouldBe DataBackupInspection.Invalid
        } finally { archive.delete() }
    }
}
