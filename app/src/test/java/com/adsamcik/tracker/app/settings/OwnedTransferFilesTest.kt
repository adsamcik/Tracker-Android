package com.adsamcik.tracker.app.settings

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.core.content.FileProvider
import com.adsamcik.tracker.impexp.OwnedTransferFiles
import com.adsamcik.tracker.impexp.exporter.automation.ExportDestination
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import java.io.File
import java.util.UUID
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OwnedTransferFilesTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `new private scheduled exports ignore an older custom directory`() {
        val target = OwnedTransferFiles.privateScheduledExportFile(
            context,
            ExportDestination.PrivateStorage(relativeDirectory = "backups"),
            "tracker-export_20260928-120000.json",
        )
        target.parentFile!!.canonicalFile shouldBe File(context.filesDir, "exports").canonicalFile
        shouldThrow<IllegalArgumentException> {
            OwnedTransferFiles.privateScheduledExportFile(
                context,
                ExportDestination.PrivateStorage(relativeDirectory = "backups"),
                "../other.json",
            )
        }
    }

    @Test
    fun `two plans exporting in the same second reserve distinct private files`() {
        val fileName = "tracker-export_${UUID.randomUUID()}.json"
        val first = OwnedTransferFiles.reservePrivateScheduledExportFile(
            context, ExportDestination.PrivateStorage(relativeDirectory = "old-a"), fileName,
        )
        try {
            first.writeText("first plan")
            val second = OwnedTransferFiles.reservePrivateScheduledExportFile(
                context, ExportDestination.PrivateStorage(relativeDirectory = "old-b"), fileName,
            )
            try {
                second.absolutePath shouldBe File(context.filesDir, "exports")
                    .resolve(fileName.removeSuffix(".json") + "-1.json").absolutePath
                first.readText() shouldBe "first plan"
            } finally {
                second.delete()
            }
        } finally {
            first.delete()
        }
    }

    @Test
    fun `full clear removes only known private staging and share files`() {
        val outside = File(context.cacheDir, "not-a-transfer.txt").apply { writeText("keep") }
        val databaseCopy = File(context.cacheDir, "database-import/db-import-123.db").apply {
            parentFile!!.mkdirs()
            writeText("import")
        }
        val zipCopy = File(context.cacheDir, "zip-import/zip-entry-456.tmp").apply {
            parentFile!!.mkdirs()
            writeText("zip")
        }
        val completedPrivateExport = File(context.filesDir, "exports/complete.zip").apply {
            parentFile!!.mkdirs()
            writeText("completed")
        }
        val portableSpool = File(context.cacheDir, "portable-export-spool/unfinished.tmp").apply {
            parentFile!!.mkdirs()
            writeText("spool")
        }
        val sessionBundleCopy = File(context.cacheDir, "session-bundle-import/child.tmp").apply {
            parentFile!!.mkdirs()
            writeText("bundle")
        }
        try {
            OwnedTransferFiles.clearForFullDeletion(context)
            databaseCopy.exists() shouldBe false
            zipCopy.exists() shouldBe false
            completedPrivateExport.exists() shouldBe false
            portableSpool.exists() shouldBe false
            sessionBundleCopy.exists() shouldBe false
            outside.readText() shouldBe "keep"
        } finally {
            outside.delete()
            portableSpool.delete()
            sessionBundleCopy.delete()
            completedPrivateExport.delete()
        }
    }

    @Test
    fun `full clear revokes and removes an app-owned shared file`() {
        // AndroidX FileProvider uses '/' when matching canonical roots; Windows Robolectric uses '\\'.
        assumeTrue(File.separatorChar == '/')
        ReflectionHelpers.getStaticField<MutableMap<String, *>>(
            FileProvider::class.java, "sCache",
        ).remove("${context.packageName}.fileprovider")
        val shareCopy = File(context.cacheDir, "sharable/share.json").apply {
            parentFile!!.mkdirs()
            writeText("share")
        }
        try {
            OwnedTransferFiles.clearForFullDeletion(context)
            shareCopy.exists() shouldBe false
        } finally {
            shareCopy.delete()
        }
    }

    @Test
    fun `older private staging names are removed too`() {
        val unexpected = File(context.cacheDir, "database-import/old-copy.db").apply {
            parentFile!!.mkdirs()
            writeText("unknown")
        }
        try {
            OwnedTransferFiles.clearForFullDeletion(context)
            unexpected.exists() shouldBe false
        } finally {
            unexpected.delete()
        }
    }

}
