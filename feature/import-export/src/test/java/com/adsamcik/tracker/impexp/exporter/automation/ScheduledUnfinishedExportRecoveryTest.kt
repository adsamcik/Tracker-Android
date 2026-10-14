package com.adsamcik.tracker.impexp.exporter.automation

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.time.SystemClock
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertFailsWith

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScheduledUnfinishedExportRecoveryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val store = ExportPlanStore(context, Dispatchers.Unconfined, SystemClock)

    @Test
    fun `scheduled document cleanup persists exact name and URI before propagating failure`() = runTest {
        val savedUri = Uri.parse("content://scheduled-test/document/failed")
        val document = mockk<DocumentFile> {
            every { delete() } returns false
            every { uri } returns savedUri
        }
        try {
            assertFailsWith<IOException> { cleanupPartialExportOutput(DocumentFileExportOutput(context, document, "failed.json"), store) }
            store.unfinishedFiles.first().first { it.uri == savedUri.toString() } shouldBe
                UnfinishedExportFile(savedUri.toString(), "failed.json")
            store.resetAllWatermarks()
            store.unfinishedFiles.first().any { it.uri == savedUri.toString() } shouldBe true
        } finally {
            store.forgetUnfinishedFile(savedUri.toString())
        }
    }

    @Test
    fun `private scheduled cleanup failure never creates an external notice`() = runTest {
        val before = store.unfinishedFiles.first()
        val output = mockk<ExportOutput> {
            every { unfinishedExternalFile } returns null
            every { deletePartial() } throws IOException("Private file unavailable")
        }
        assertFailsWith<IOException> { cleanupPartialExportOutput(output, store) }
        store.unfinishedFiles.first() shouldBe before
    }

    @Test
    fun `successful private scheduled cleanup removes only its own file`() = runTest {
        val first = File.createTempFile("scheduled-first-", ".json", context.cacheDir)
        val sibling = File.createTempFile("scheduled-sibling-", ".json", context.cacheDir)
        try {
            first.writeText("partial")
            sibling.writeText("complete")
            cleanupPartialExportOutput(FileExportOutput(first), store)
            first.exists() shouldBe false
            sibling.readText() shouldBe "complete"
        } finally {
            first.delete()
            sibling.delete()
        }
    }
}
