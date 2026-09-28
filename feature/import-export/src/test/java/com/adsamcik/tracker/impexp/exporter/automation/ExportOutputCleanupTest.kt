package com.adsamcik.tracker.impexp.exporter.automation

import android.app.Application
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.io.IOException
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertFailsWith

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExportOutputCleanupTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `private scheduled output removes its unfinished file`() {
        val file = File(context.cacheDir, "unfinished-scheduled-export.json")
        try {
            file.writeText("partial")
            FileExportOutput(file).deletePartial()
            file.exists() shouldBe false
        } finally {
            file.delete()
        }
    }

    @Test
    fun `failed document deletion reports the remaining destination`() {
        val document = mockk<DocumentFile>()
        every { document.delete() } returns false
        every { document.uri } returns android.net.Uri.parse("content://documents/unfinished")

        val failure = assertFailsWith<IOException> {
            DocumentFileExportOutput(context, document, "unfinished.json").deletePartial()
        }
        failure.message?.contains("content://documents/unfinished") shouldBe true
    }
}
