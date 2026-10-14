package com.adsamcik.tracker.impexp.exporter.automation

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.time.SystemClock
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CancellationException
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
class UnfinishedExportFileStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun store() = ExportPlanStore(context, Dispatchers.Unconfined, SystemClock)

    @Test
    fun `notice survives another store instance and full-clear watermark reset`() = runTest {
        val store = store()
        val file = UnfinishedExportFile("content://notice-test/document/clear", "unfinished.json")
        val plan = store.create(ExportBackupPlanDraft("Notice test", ExportFormat.JSON,
            ExportCadence.AfterSession, ExportScope.EntireHistory, ExportDestination.PrivateStorage()))
        try {
            store.rememberUnfinishedFile(file)
            store.updateWatermark(plan.id, 1_000L, 2L, 2_000L, 3)
            // This is the exact existing operation called by CollectedDataDeletionService.
            store.resetAllWatermarks()
            store().unfinishedFiles.first().first { it.uri == file.uri } shouldBe file
            store.getPlan(plan.id)!!.lastWatermarkMs shouldBe 0L
            store.setEnabled(plan.id, false)
            store.unfinishedFiles.first().first { it.uri == file.uri } shouldBe file
        } finally {
            store.forgetUnfinishedFile(file.uri)
            store.delete(plan.id)
        }
    }

    @Test
    fun `one URI is deduplicated while equal filenames keep distinct destinations`() = runTest {
        val store = store()
        val first = UnfinishedExportFile("content://notice-test/document/first", "same.json")
        val second = UnfinishedExportFile("content://notice-test/document/second", "same.json")
        try {
            store.rememberUnfinishedFile(first)
            store.rememberUnfinishedFile(first.copy(name = "renamed.json"))
            store.rememberUnfinishedFile(second)
            val saved = store.unfinishedFiles.first()
            saved.count { it.uri == first.uri } shouldBe 1
            saved.first { it.uri == first.uri }.name shouldBe "renamed.json"
            store.forgetUnfinishedFile(first.uri)
            store.unfinishedFiles.first().first { it.uri == second.uri } shouldBe second
        } finally {
            store.forgetUnfinishedFile(first.uri)
            store.forgetUnfinishedFile(second.uri)
        }
    }

    @Test
    fun `private file paths cannot become external cleanup notices`() {
        assertFailsWith<IllegalArgumentException> { UnfinishedExportFile("file:///private/share.json", "share.json") }
    }

    @Test
    fun `unreadable export storage never becomes a fabricated empty plan and notice list`() = runTest {
        assertFailsWith<CorruptionException> {
            ExportPlansSerializer.readFrom(ByteArrayInputStream(byteArrayOf(0x80.toByte())))
        }
        assertFailsWith<IOException> {
            ExportPlansSerializer.readFrom(object : InputStream() {
                override fun read(): Int = throw IOException("Read failed")
            })
        }
        assertFailsWith<CancellationException> {
            ExportPlansSerializer.readFrom(object : InputStream() {
                override fun read(): Int = throw CancellationException("Cancelled")
            })
        }
    }
}
