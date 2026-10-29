package com.adsamcik.tracker.impexp.importer

import com.adsamcik.tracker.impexp.importer.file.LocationFileConflictChoice
import com.adsamcik.tracker.impexp.importer.file.LocationFileImportPreview
import com.adsamcik.tracker.impexp.importer.file.LocationFileImportReview
import com.adsamcik.tracker.impexp.importer.worker.ImportWorker
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.assertions.throwables.shouldThrow
import org.junit.Test
import java.util.UUID

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
    fun `replacement receipts follow one durable worker request while ordinary files keep deduplication`() {
        val preview = LocationFileImportPreview(1, 1, 0, "file-sha", 11L, "saved-state-sha")
        val keep = LocationFileImportReview(preview, LocationFileConflictChoice.KEEP_EXISTING)
        val replace = keep.copy(choice = LocationFileConflictChoice.REPLACE_WITH_FILE)
        val workerId = UUID(0L, 1L)
        reviewedLocationImportJobId("file-sha", null, workerId) shouldBe "file-sha"
        reviewedLocationImportJobId("file-sha", keep, workerId) shouldBe "file-sha"
        val replacement = reviewedLocationImportJobId("file-sha", replace, workerId)
        replacement shouldNotBe "file-sha"
        reviewedLocationImportJobId("file-sha", replace, workerId) shouldBe replacement
        reviewedLocationImportJobId("file-sha", replace, UUID(0L, 2L)) shouldNotBe replacement
        reviewedLocationImportJobId("file-sha", replace.copy(
            preview = preview.copy(existingStateHash = "changed-saved-state")), workerId) shouldBe replacement
        reviewedLocationImportJobId("file-sha", replace.copy(
            preview = preview.copy(dataEpoch = 12L)), workerId) shouldNotBe replacement
        reviewedLocationImportJobId("file-sha", replace.copy(
            preview = preview.copy(conflictCount = 0)), workerId) shouldBe "file-sha"
    }

    @Test
    fun `a reviewed choice cannot reuse a receipt for different file bytes`() {
        val review = LocationFileImportReview(
            LocationFileImportPreview(1, 1, 0, "reviewed-file-sha", 11L, "saved-state-sha"),
            LocationFileConflictChoice.REPLACE_WITH_FILE,
        )
        shouldThrow<PermanentImportInputException> {
            reviewedLocationImportJobId("different-file-sha", review, UUID(0L, 1L))
        }
    }

    @Test
    fun `ordinary import carries no Location replacement choice`() {
        importJobInput("content://files/wifi.trackerwifi", 12L).locationFileReview() shouldBe null
    }
}
