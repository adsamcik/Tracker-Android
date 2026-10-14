package com.adsamcik.tracker.impexp.importer

import com.adsamcik.tracker.impexp.importer.file.LocationFileConflictChoice
import com.adsamcik.tracker.impexp.importer.file.LocationFileImportPreview
import com.adsamcik.tracker.impexp.importer.file.LocationFileImportReview
import com.adsamcik.tracker.impexp.importer.worker.ImportWorker
import io.kotest.matchers.shouldBe
import org.junit.Test

class LocationImportReviewJobTest {
    @Test
    fun `queued import keeps one choice bound to exact file and saved points`() {
        val preview = LocationFileImportPreview(8, 2, 3, "file-sha", 11L, "saved-state-sha")
        for (choice in LocationFileConflictChoice.entries) {
            val review = LocationFileImportReview(preview, choice)
            val input = importJobInput("content://files/route.trackerlocation", 11L, review)
            input.locationFileReview() shouldBe review
            input.getLong(ImportWorker.ARG_ORIGINAL_DATA_EPOCH, -1L) shouldBe 11L
            input.getString(ImportWorker.ARG_FILE_URI) shouldBe "content://files/route.trackerlocation"
        }
    }

    @Test
    fun `ordinary import carries no Location replacement choice`() {
        importJobInput("content://files/wifi.trackerwifi", 12L).locationFileReview() shouldBe null
    }
}
