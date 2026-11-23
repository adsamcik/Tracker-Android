package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.stats.api.repository.SavedLocationSummary
import com.adsamcik.tracker.stats.api.repository.SavedLocationSummaryRead
import com.adsamcik.tracker.stats.api.repository.SavedLocationSummarySelection
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryReadSnapshot
import io.kotest.matchers.shouldBe
import org.junit.Test

/** IMPLEMENTED_UNVALIDATED: later Location authority cannot become a metric on an older page. */
class RecentHistorySnapshotTest {
    @Test fun `physical metrics require the exact page epoch and revision`() {
        val snapshot = TrackingHistoryReadSnapshot(3, 8)
        val summary = SavedLocationSummary(0, null, null, false)
        val read = SavedLocationSummaryRead.Snapshot(SavedLocationSummarySelection.Session(1), snapshot, summary)
        recentLocationSummary(snapshot, read) shouldBe summary
        recentLocationSummary(snapshot.copy(collectedDataEpoch = 4), read) shouldBe null
        recentLocationSummary(snapshot.copy(sourceEvidenceRevision = 9), read) shouldBe null
    }
}
