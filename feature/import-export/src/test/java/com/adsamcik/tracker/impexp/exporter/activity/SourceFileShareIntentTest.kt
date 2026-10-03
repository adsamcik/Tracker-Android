package com.adsamcik.tracker.impexp.exporter.activity

import android.content.Intent
import android.net.Uri
import com.adsamcik.tracker.impexp.exporter.LocationFileExporter
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SourceFileShareIntentTest {
    @Test fun `saved Location share carries its one chosen file and no trip metrics`() {
        val exporter = LocationFileExporter()
        val file = Uri.parse("content://tracker.fileprovider/chosen.trackerlocation")
        val intent = sourceFileShareIntent(file, exporter.mimeType)
        intent.action shouldBe Intent.ACTION_SEND
        intent.type shouldBe exporter.mimeType
        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) shouldBe file
        intent.hasExtra(Intent.EXTRA_SUBJECT) shouldBe false
        intent.hasExtra(Intent.EXTRA_TEXT) shouldBe false
        (intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) shouldBe true
        (intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0) shouldBe false
    }
}
