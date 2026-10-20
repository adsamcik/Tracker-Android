package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.TrackingHistoryReadDao
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.model.SegmentSource
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class SourceOnlyHistoryIntentTest {
    private val database = mockk<AppDatabase>()
    private val read = mockk<TrackingHistoryReadDao>()
    private val seed = SessionSegment(1L, 100L, 200L, 0f, null, null, null, 0,
        SegmentSource.USER_CREATED, null, 100L, logicalTrackingId = "logical", serviceRunId = "run")
    private val run = SourceServiceRunEntity("run", "logical", "ACTIVE", 1L, 1L, 0L, 100L, 10L,
        null, null, bootId = "boot", leaseGeneration = 1L, startOrigin = "MANUAL_FOREGROUND_START",
        runRevision = 1L, startDeliveryToken = "delivery", startCommandGeneration = 1L,
        preparedManifestRevision = 1L, preparedIntentRevision = 1L, sessionSegmentId = seed.id)

    @Test
    fun `verified other capture removes only known mixed candidates`() = runTest {
        val sources = listOf(source(SourceDestinationOwnerEntity.SOURCE_ACTIVITY),
            source(SourceDestinationOwnerEntity.SOURCE_CELL))
        stub(listOf(manifest(sources)), sources)
        val unknown = seed.copy(id = 2L, serviceRunId = "missing-run")

        database.sourceOnlyHistoryCandidates(listOf(seed, unknown), SourceDestinationOwnerEntity.SOURCE_ACTIVITY)
            .shouldBe(listOf(unknown))
        database.sourceOnlyHistoryCandidates(listOf(seed), SourceDestinationOwnerEntity.SOURCE_PRESSURE)
            .shouldBe(emptyList())
    }

    @Test
    fun `control and nonpersistent siblings do not exclude single source capture`() = runTest {
        val sources = listOf(source(SourceDestinationOwnerEntity.SOURCE_ACTIVITY),
            source(SourceDestinationOwnerEntity.SOURCE_CELL).copy(purpose = SessionManifestPurposeCode.CONTROL),
            source(SourceDestinationOwnerEntity.SOURCE_CELL).copy(persistenceEligible = false))
        stub(listOf(manifest(sources)), sources)

        database.sourceOnlyHistoryCandidates(listOf(seed), SourceDestinationOwnerEntity.SOURCE_ACTIVITY)
            .shouldBe(listOf(seed))
    }

    @Test
    fun `unverified checksum timeline and legacy binding use full qualification`() = runTest {
        val sources = listOf(source(SourceDestinationOwnerEntity.SOURCE_ACTIVITY),
            source(SourceDestinationOwnerEntity.SOURCE_CELL))
        val valid = manifest(sources)
        val variants = listOf(
            run to listOf(valid.copy(manifestChecksum = "invalid")),
            run.copy(desiredPlanRevision = 2L) to listOf(valid),
            run.copy(sessionSegmentId = 9L) to listOf(valid),
            run.copy(presentationAcknowledgement = SourceServiceRunEntity.PRESENTATION_LEGACY_UNVERIFIABLE)
                to listOf(valid),
            run to emptyList(),
        )
        for ((owner, manifests) in variants) {
            stub(manifests, sources, owner)
            database.sourceOnlyHistoryCandidates(listOf(seed), SourceDestinationOwnerEntity.SOURCE_ACTIVITY)
                .shouldBe(listOf(seed))
        }
    }

    @Test
    fun `unknown purpose and bounded dependency overflow use full qualification`() = runTest {
        val sources = listOf(source(SourceDestinationOwnerEntity.SOURCE_ACTIVITY),
            source(SourceDestinationOwnerEntity.SOURCE_CELL).copy(purpose = "UNKNOWN"))
        stub(listOf(manifest(sources)), sources)
        database.sourceOnlyHistoryCandidates(listOf(seed), SourceDestinationOwnerEntity.SOURCE_ACTIVITY)
            .shouldBe(listOf(seed))

        stub(List(1_025) { manifest(sources) }, sources)
        database.sourceOnlyHistoryCandidates(listOf(seed), SourceDestinationOwnerEntity.SOURCE_ACTIVITY)
            .shouldBe(listOf(seed))
    }

    @Test
    fun `earlier mixed revision cannot become single source through a later choice`() = runTest {
        val firstSources = listOf(source(SourceDestinationOwnerEntity.SOURCE_ACTIVITY),
            source(SourceDestinationOwnerEntity.SOURCE_CELL))
        val laterSources = listOf(source(SourceDestinationOwnerEntity.SOURCE_ACTIVITY).copy(manifestRevision = 2L))
        val laterUnsigned = manifest(laterSources).copy(manifestRevision = 2L, acquisitionPlanRevision = 2L,
            startOrigin = "POLICY_RECONCILIATION", changeReason = "POLICY_RECONCILIATION",
            effectiveElapsedRealtimeNanos = 20L)
        val later = laterUnsigned.copy(manifestChecksum = SessionManifestIntegrity.compute(laterUnsigned, laterSources))
        stub(listOf(manifest(firstSources), later), firstSources + laterSources, run.copy(desiredPlanRevision = 2L))

        database.sourceOnlyHistoryCandidates(listOf(seed), SourceDestinationOwnerEntity.SOURCE_ACTIVITY)
            .shouldBe(emptyList())
    }

    private fun source(kind: Int) = SessionManifestSourceEntity("logical", 1L, kind,
        SessionManifestPurposeCode.SESSION_CAPTURE, 1L, true, 1)

    private fun manifest(sources: List<SessionManifestSourceEntity>): SessionManifestVersionEntity {
        val unsigned = SessionManifestVersionEntity("logical", sources.first().manifestRevision, "run", "MANUAL",
            1L, 1L, 1L, "MANUAL_FOREGROUND_START", "boot", 10L, 100L, "UTC", null, "START", "")
        return unsigned.copy(manifestChecksum = SessionManifestIntegrity.compute(unsigned, sources))
    }

    private fun stub(
        manifests: List<SessionManifestVersionEntity>,
        sources: List<SessionManifestSourceEntity>,
        owner: SourceServiceRunEntity = run,
    ) {
        every { database.trackingHistoryReadDao() } returns read
        coEvery { read.serviceRuns(any()) } returns listOf(owner)
        coEvery { read.manifests(any(), any()) } returns manifests
        coEvery { read.manifestSources(any(), any()) } returns sources
    }
}
