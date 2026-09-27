package com.adsamcik.tracker.stats.data.repository

import androidx.room.withTransaction
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.stats.api.repository.HistoryPosition
import com.adsamcik.tracker.stats.api.repository.HistoryRange
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** SQL discovery only. These candidates deliberately do not claim qualified product evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UnifiedHistoryCandidateQueriesRoomTest {
    private lateinit var database: AppDatabase
    @Before fun setup() {
        val context: Application = ApplicationProvider.getApplicationContext()
        database = AppDatabase.testDatabase(context)
    }
    @After fun close() = database.close()

    @Test fun `old bounded ranges page beyond the recent window without duplicates`() = runTest {
        for (id in 1L..251L) insert(id, id * 10, id * 10 + 5)
        val range = HistoryRange(0, 3000, "UTC")
        var after: HistoryPosition? = null
        val seen = mutableListOf<String>()
        do {
            val probe = database.trackingHistoryReadDao().unifiedHistoryCandidates(
                UnifiedHistoryCandidateQueries.native(range, after))
            val accepted = probe.take(100)
            seen += accepted.map { it.identity }
            after = accepted.lastOrNull()?.let { HistoryPosition(it.startTimeMs, "local", it.identity) }
        } while (probe.size == 101)
        seen.size shouldBe 251
        seen.distinct().size shouldBe 251
        seen.first() shouldBe "segment:251"
        seen.last() shouldBe "segment:1"
        database.trackingHistoryReadDao().unifiedHistoryCandidates(
            UnifiedHistoryCandidateQueries.native(HistoryRange(4000, 5000, "UTC"), null)).size shouldBe 0
    }

    @Test fun `global tie seek applies origin before the source-local identity`() = runTest {
        insert(1, 100, 200); insert(2, 100, 200); insert(3, 50, 80)
        val range = HistoryRange(0, 300, "UTC")
        suspend fun identities(origin: String, identity: String) =
            database.trackingHistoryReadDao().unifiedHistoryCandidates(
                UnifiedHistoryCandidateQueries.native(range, HistoryPosition(100, origin, identity)))
                .map { it.identity }
        identities("a", "segment:2") shouldBe listOf("segment:1", "segment:2", "segment:3")
        identities("local", "segment:1") shouldBe listOf("segment:2", "segment:3")
        identities("z", "segment:1") shouldBe listOf("segment:3")
    }

    @Test fun `half-open overlap includes cross-boundary records and exact point entries`() = runTest {
        insert(1, 50, 150); insert(2, 50, 100); insert(3, 200, 200)
        insert(4, 100, 100); insert(5, 90, 90)
        val found = database.trackingHistoryReadDao().unifiedHistoryCandidates(
            UnifiedHistoryCandidateQueries.native(HistoryRange(100, 200, "UTC"), null))
        found.map { it.identity } shouldBe listOf("segment:4", "segment:1")
    }

    @Test fun `reversed closed interval remains a typed uncertain candidate`() = runTest {
        insert(1, 150, 50)
        val row = database.trackingHistoryReadDao().unifiedHistoryCandidates(
            UnifiedHistoryCandidateQueries.native(HistoryRange(100, 200, "UTC"), null)).single()
        row.temporalUncertain shouldBe true
        row.candidateConflict shouldBe false
    }

    @Test fun `simultaneous imported live and retained shells yield one conflict-marked key`() = runTest {
        val identity = "a".repeat(64)
        val checksum = "b".repeat(64)
        val entry = com.adsamcik.tracker.shared.base.database.data.ImportedActivityEntryRevisionEntity(
            identity, 1L, null, checksum, "tracker-portable-captured-activity", 1,
            "MANUAL", 100L, 200L, 7L, "job:1", "entry:1", "backup.trackeractivity", 300L)
        database.importedActivityDao().insertEntryRevision(entry)
        database.importedActivityDao().insertEntryRevision(entry.copy(importRevision = 2L,
            supersedesImportRevision = 1L, importJobId = "job:2", importEntryKey = "entry:2"))
        // Deliberately inconsistent discovery-only shell. It grants no authenticated payload.
        database.openHelper.writableDatabase.execSQL("""INSERT INTO imported_activity_retention_receipt
            (entry_identity, collected_data_epoch, source_evidence_revision, retained_from_ms, retained_at_ms,
             latest_import_revision, latest_content_checksum, start_time_ms, end_time_ms, received_at_ms,
             revision_count, import_receipt_count, run_row_count, zone_epoch_row_count, window_row_count,
             fragment_row_count, run_deletion_count, run_deletion_set_checksum, source_fence_count,
             source_fence_set_checksum, protected_identity_count, protected_identity_set_checksum,
             lineage_authority_checksum, effect_checksum)
            VALUES (?, 7, 1, 0, 300, 2, ?, 100, 200, 300, 2, 2, 2, 2, 2, 2, 0, ?, 0, ?, 4, ?, ?, ?)""",
            arrayOf(identity, checksum, checksum, checksum, checksum, checksum, checksum))
        val range = HistoryRange(0, 1000, "UTC")
        val selected = database.trackingHistoryReadDao().unifiedHistoryCandidates(
            UnifiedHistoryCandidateQueries.imported("activity", range, null))
        selected.size shouldBe 1
        selected.single().identity shouldBe identity
        selected.single().candidateConflict shouldBe true
        val after = HistoryPosition(100, "imported:activity", identity)
        database.trackingHistoryReadDao().unifiedHistoryCandidates(
            UnifiedHistoryCandidateQueries.imported("activity", range, after)).size shouldBe 0
    }

    @Test fun `selected logical read separates absent and incomplete membership`() = runTest {
        val reader = LogicalTrackingHistoryReader(database, StepsSegmentHistorySelector(database,
            com.adsamcik.tracker.shared.base.database.data.SourceProductLaneExecutionAuthority { false }))
        database.withTransaction { reader.selectedLogicalMembersInTransaction("absent") } shouldBe SelectedLogicalHistoryRead.NotFound
        database.sourceSessionDao().insertSession(
            com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity(
                "incomplete", "FINALIZED", 1, 1, 1, "USER", "boot", 100, 100, 200, 200, 900, null, null))
        database.withTransaction { reader.selectedLogicalMembersInTransaction("incomplete") } shouldBe
            SelectedLogicalHistoryRead.Unavailable(HistoryPageDependencyLimit.LOGICAL_MEMBERSHIP)
    }

    @Test fun `selected logical membership contains every explicitly bound replacement run`() = runTest {
        database.sourceSessionDao().insertSession(
            com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity(
                "bound", "FINALIZED", 1, 1, 1, "USER", "boot", 100, 100, 300, 300, 900, null, null))
        for (id in 1L..2L) {
            val segment = SessionSegment(id = id, startTimeMs = id * 100, endTimeMs = id * 100 + 50,
                distanceM = 0f, steps = null, primaryActivity = null, activityConfidence = null,
                sampleCount = 0, source = SegmentSource.USER_CREATED, inferenceVersion = null,
                createdAt = 300, logicalTrackingId = "bound", serviceRunId = "run:$id")
            database.sessionSegmentDao().insert(segment)
            database.sourceSessionDao().insertServiceRun(
                com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity(
                    serviceRunId = "run:$id", logicalTrackingId = "bound", state = "FINALIZED",
                    desiredPlanRevision = 1, rolloutRevision = 1, foregroundCapabilityFlags = 0,
                    startedAtMs = segment.startTimeMs, startedElapsedNanos = segment.startTimeMs,
                    completedAtMs = segment.endTimeMs, completionReason = "STOPPED", sessionSegmentId = id))
        }
        val reader = LogicalTrackingHistoryReader(database, StepsSegmentHistorySelector(database,
            com.adsamcik.tracker.shared.base.database.data.SourceProductLaneExecutionAuthority { false }))
        val read = database.withTransaction { reader.selectedLogicalMembersInTransaction("bound") }
            as SelectedLogicalHistoryRead.Content
        read.members.map { it.segment.id } shouldBe listOf(1L, 2L)
        // Membership is proven; missing manifests still grant no qualified source payload.
        read.members.flatMap { it.qualifiedSources }.size shouldBe 0
    }

    private suspend fun insert(id: Long, start: Long, end: Long) {
        database.sessionSegmentDao().insert(SessionSegment(id = id, startTimeMs = start,
            endTimeMs = end, distanceM = 0f, steps = null, primaryActivity = null,
            activityConfidence = null, sampleCount = 0, source = SegmentSource.USER_CREATED,
            inferenceVersion = null, createdAt = end))
    }
}
