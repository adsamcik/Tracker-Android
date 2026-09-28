package com.adsamcik.tracker.app.settings

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.impexp.OwnedTransferFiles
import io.kotest.matchers.shouldBe
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OwnedTransferFilesTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

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
        val shareCopy = File(context.cacheDir, "sharable/share.json").apply {
            parentFile!!.mkdirs()
            writeText("share")
        }
        try {
            OwnedTransferFiles.clearForFullDeletion(context)
            databaseCopy.exists() shouldBe false
            zipCopy.exists() shouldBe false
            shareCopy.exists() shouldBe false
            outside.readText() shouldBe "keep"
        } finally {
            outside.delete()
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
