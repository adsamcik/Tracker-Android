package com.adsamcik.tracker.stats.data.repository

import android.app.Application
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.data.LengthUnit
import com.adsamcik.tracker.shared.base.data.Location
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.LocationPurposeDao
import com.adsamcik.tracker.shared.base.database.data.*
import com.adsamcik.tracker.shared.model.AltitudeDatum
import com.adsamcik.tracker.shared.model.AltitudeSource
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.stats.api.repository.*
import io.mockk.every
import io.mockk.mockk
import kotlin.test.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real saved-data cache scopes with native receipt and historical-authority fixtures. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SavedTripMeasurementCacheRoomTest {
    private lateinit var database: AppDatabase
    private var ordinal = 0L

    @Before fun setup() = runBlocking {
        database = AppDatabase.testDatabase(ApplicationProvider.getApplicationContext<Application>())
        database.sourceEvidenceStateDao().ensure()
        database.sourcePolicyDao().insertPolicies(listOf(policy()))
        database.sourcePolicyDao().insertConsentEpochs(listOf(
            SourceConsentEpochEntity(1, SourceBrokerPurpose.AMBIENT_PRODUCT, 0, true, true, 1, "boot", 0, 0, "USER"),
            SourceConsentEpochEntity(1, SourceBrokerPurpose.SESSION_CAPTURE, 0, true, true, 1, "boot", 0, 0, "USER")))
        database.locationPurposeDao().insertRetentionAuthority(AmbientLocationRetentionAuthorityIntegrity.create(
            "LIVE_AMBIENT", 1, "ACTIVE", "retention", 1, 0, 0, "boot", 0, 0))
        Unit
    }
    @After fun close() = database.close()

    @Test fun `forward manifest and unrelated policy changes extend rather than reseed the route`() = runBlocking {
        val selected = session("forward-authority")
        database.withTransaction { point(2_000, 0, 1, capture = selected) }
        var fullPages = 0
        val actual = database.locationPurposeDao()
        val counted = object : LocationPurposeDao by actual {
            override suspend fun savedLogicalTripPointPage(logicalId: String, serviceRunIds: List<String>,
                afterTimeMs: Long?, afterId: Long?, limit: Int, fromMs: Long, toMs: Long): List<com.adsamcik.tracker.shared.base.database.dao.LocationPathSampleRow> {
                fullPages++
                return actual.savedLogicalTripPointPage(logicalId, serviceRunIds, afterTimeMs, afterId, limit, fromMs, toMs)
            }
        }
        val live = SavedLocationLiveSummaryReader(database, Dispatchers.Unconfined, SavedLocationLiveUpdateSignal(),
            readerWithPageFault(counted), counted)
        live.observeSession(selected.id).test {
            awaitCount(1)
            val seedPages = fullPages
            database.withTransaction {
                database.sourcePolicyDao().insertPolicies(listOf(policy().copy(policyRevision = 2, sourceKind = 2)))
                val original = requireNotNull(database.sourceSessionDao().manifest(requireNotNull(selected.logicalTrackingId), 1))
                val member = SessionManifestSourceEntity(requireNotNull(selected.logicalTrackingId), 2, 1,
                    SourceBrokerPurpose.SESSION_CAPTURE, 0, true, 1)
                val next = original.copy(manifestRevision = 2, effectiveElapsedRealtimeNanos = 2_500_000_000L,
                    effectiveWallTimeMs = 2_500L)
                database.sourceSessionDao().insertManifest(next.copy(manifestChecksum =
                    SessionManifestIntegrity.compute(next, listOf(member))))
                database.sourceSessionDao().insertManifestSources(listOf(member))
                point(3_000, 100_000, 2, capture = selected, captureManifestRevision = 2)
            }
            assertEquals(distance(0, 100_000), awaitCount(2).summary.distanceMeters)
            assertEquals(seedPages, fullPages)
            // An actual change to a referenced historical member must retract the cached points.
            database.withTransaction {
                database.openHelper.writableDatabase.execSQL("UPDATE session_manifest_source SET persistence_eligible = 0 " +
                    "WHERE logical_tracking_id = ? AND manifest_revision = 1", arrayOf(selected.logicalTrackingId))
                database.sourceEvidenceStateDao().incrementRevision(4_000)
            }
            val revised = awaitCount(1)
            assertNull(revised.summary.distanceMeters)
            assertTrue(fullPages > seedPages)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `visible trip cache keeps siblings and remeasures late membership corrections and deletion`() = runBlocking {
        val first = session("cached-first")
        val sibling = session("cached-sibling")
        val pages = mutableMapOf<String, Int>()
        val actual = database.locationPurposeDao()
        val counted = object : LocationPurposeDao by actual {
            override suspend fun savedLogicalTripPointPage(logicalId: String, serviceRunIds: List<String>,
                afterTimeMs: Long?, afterId: Long?, limit: Int, fromMs: Long, toMs: Long): List<com.adsamcik.tracker.shared.base.database.dao.LocationPathSampleRow> {
                pages[logicalId] = (pages[logicalId] ?: 0) + 1
                return actual.savedLogicalTripPointPage(logicalId, serviceRunIds, afterTimeMs, afterId, limit, fromMs, toMs)
            }
        }
        database.withTransaction {
            point(2_000, 0, 1, capture = first)
            point(4_000, 200_000, 3, capture = first)
            point(2_000, 0, 1, capture = sibling, instance = "sibling")
            point(4_000, 200_000, 3, capture = sibling, instance = "sibling")
        }
        val cache = SavedTripMeasurementCache(database, Dispatchers.Unconfined, readerWithPageFault(counted))
        cache.changed(emptySet())
        assertEquals(2L, cache.read(first.id).pointCount)
        assertEquals(2L, cache.read(sibling.id).pointCount)
        val firstPages = pages.getValue(requireNotNull(first.logicalTrackingId))
        val siblingPages = pages.getValue(requireNotNull(sibling.logicalTrackingId))
        advanceManifest(first)
        assertFalse(cache.changed(setOf("source_service_run", "session_manifest_version", "session_manifest_source")))
        cache.read(first.id)
        cache.read(sibling.id)
        assertEquals(firstPages, pages[first.logicalTrackingId])
        assertEquals(siblingPages, pages[sibling.logicalTrackingId])
        val background = database.withTransaction { point(3_000, 300_000, 1, instance = "ambient") }
        assertFalse(cache.changed(setOf("location_sample", "location_accepted_receipt", "location_sample_purpose_authority")))
        cache.read(first.id)
        cache.read(sibling.id)
        assertEquals(firstPages, pages[first.logicalTrackingId])
        assertEquals(siblingPages, pages[sibling.logicalTrackingId])

        val late = database.withTransaction { point(3_000, 100_000, 2, capture = first, captureManifestRevision = 2) }
        assertTrue(cache.changed(setOf("location_sample", "location_accepted_receipt", "location_sample_purpose_authority")))
        assertEquals(3L, cache.read(first.id).pointCount)
        cache.read(sibling.id)
        assertEquals(siblingPages, pages[sibling.logicalTrackingId])
        assertTrue(pages.getValue(requireNotNull(first.logicalTrackingId)) > firstPages)

        database.withTransaction {
            val receipt = requireNotNull(actual.receipt(background.id))
            val grant = LocationSamplePurposeAuthorityEntity(background.id, SourceBrokerPurpose.SESSION_CAPTURE,
                requireNotNull(sibling.serviceRunId), 1, 0, 1, Long.MAX_VALUE, sibling.logicalTrackingId,
                sibling.serviceRunId, sibling.id, 1, 1, null, null, null, 0, "UTC")
            actual.insertPurposeIfAbsent(LocationAcceptedReceiptIntegrity.seal(grant, receipt))
        }
        assertTrue(cache.changed(setOf("location_sample_purpose_authority")))
        assertEquals(3L, cache.read(sibling.id).pointCount)

        database.withTransaction {
            database.locationPointCorrectionDao().replace(LocationPointCorrection.from(late.id, 0, 1,
                late.copy(lonE7 = 400_000)))
        }
        assertTrue(cache.changed(setOf("location_point_correction")))
        assertEquals(distance(0, 400_000) + distance(400_000, 200_000), cache.read(first.id).distanceMeters)
        database.withTransaction { database.locationSampleDao().deleteExactIds(listOf(late.id)) }
        assertTrue(cache.changed(setOf("location_sample")))
        assertEquals(2L, cache.read(first.id).pointCount)
        assertNull(cache.read(first.id).distanceMeters)
    }

    private suspend fun advanceManifest(selected: SessionSegment) = database.withTransaction {
        val logicalId = requireNotNull(selected.logicalTrackingId)
        val run = requireNotNull(database.sourceSessionDao().serviceRun(requireNotNull(selected.serviceRunId)))
        database.sourceSessionDao().updateServiceRun(run.copy(runRevision = run.runRevision + 1,
            leaseGeneration = run.leaseGeneration + 1))
        val original = requireNotNull(database.sourceSessionDao().manifest(logicalId, 1))
        val member = SessionManifestSourceEntity(logicalId, 2, 1,
            SourceBrokerPurpose.SESSION_CAPTURE, 0, true, 1)
        val next = original.copy(manifestRevision = 2, effectiveElapsedRealtimeNanos = 2_500_000_000L,
            effectiveWallTimeMs = 2_500L)
        database.sourceSessionDao().insertManifest(next.copy(manifestChecksum =
            SessionManifestIntegrity.compute(next, listOf(member))))
        database.sourceSessionDao().insertManifestSources(listOf(member))
    }

    private suspend fun ReceiveTurbine<SavedLocationSummaryRead>.awaitCount(count: Long):
        SavedLocationSummaryRead.Snapshot = withTimeout(5_000L) {
        while (true) {
            val read = awaitItem()
            if (read is SavedLocationSummaryRead.Snapshot && read.summary.pointCount == count) {
                return@withTimeout read
            }
        }
        error("Unreachable")
    }

    private fun readerWithPageFault(purpose: LocationPurposeDao): SavedLocationSummaryReader {
        val handle = mockk<AppDatabase>()
        every { handle.locationPurposeDao() } returns purpose
        every { handle.sourceEvidenceStateDao() } returns database.sourceEvidenceStateDao()
        every { handle.locationSampleDao() } returns database.locationSampleDao()
        every { handle.sourcePolicyDao() } returns database.sourcePolicyDao()
        every { handle.trackingHistoryReadDao() } returns database.trackingHistoryReadDao()
        every { handle.sourceDeletionFenceDao() } returns database.sourceDeletionFenceDao()
        return SavedLocationSummaryReader(handle, Dispatchers.Unconfined)
    }

    private suspend fun session(identity: String): SessionSegment {
        val runId = "run:$identity"
        database.sourceSessionDao().insertSession(LogicalTrackingSessionEntity(identity, "ACTIVE", 1, 1, 1,
            "MANUAL_FOREGROUND_START", "boot", 1_000, 1, null, null, null, null, null,
            sessionMode = "MANUAL", currentManifestRevision = 1, currentIntentRevision = 1,
            currentServiceRunId = runId, lifecycleLeaseGeneration = 1, lifecycleBootId = "boot"))
        val template = SessionSegment(startTimeMs = 1_000, endTimeMs = 5_000, distanceM = 9_000_000f,
            steps = null, primaryActivity = null, activityConfidence = null, sampleCount = 2,
            source = SegmentSource.USER_CREATED, inferenceVersion = null, createdAt = 1_000,
            logicalTrackingId = identity, serviceRunId = runId)
        val segment = template.copy(id = database.sessionSegmentDao().insert(template))
        database.sourceSessionDao().insertServiceRun(SourceServiceRunEntity(runId, identity, "ACTIVE", 1, 1, 0,
            1_000, 1, null, null, bootId = "boot", leaseGeneration = 1,
            startOrigin = "MANUAL_FOREGROUND_START", runRevision = 1, startDeliveryToken = "start:$identity",
            startCommandGeneration = 1, preparedManifestRevision = 1, preparedIntentRevision = 1, sessionSegmentId = segment.id))
        val member = SessionManifestSourceEntity(identity, 1, 1, SourceBrokerPurpose.SESSION_CAPTURE, 0, true, 1)
        val manifest = SessionManifestVersionEntity(identity, 1, runId, "MANUAL", 1, 1, 1,
            "MANUAL_FOREGROUND_START", "boot", 1, 1_000, "UTC", null, "START", "")
        database.sourceSessionDao().insertManifest(manifest.copy(manifestChecksum = SessionManifestIntegrity.compute(manifest, listOf(member))))
        database.sourceSessionDao().insertManifestSources(listOf(member))
        return segment
    }

    private suspend fun point(time: Long, longitude: Int, sequence: Long, capture: SessionSegment? = null,
        instance: String = "instance",
        captureManifestRevision: Long = 1): LocationSample {
        ordinal++
        val event = "event:$ordinal"
        val elapsed = time * 1_000_000L
        val template = LocationSample(timeMs = time, elapsedRealtimeNanos = elapsed, latE7 = 0, lonE7 = longitude,
            altitudeM = null, rawGpsAltitudeM = null, hAccM = null, vAccM = null, speedMps = null,
            speedAccuracyMps = null, provider = "fused", quality = SampleQuality.COARSE,
            motionState = null, policy = null, bucketId = null, createdAt = time + 1,
            receivedElapsedRealtimeNanos = elapsed + 1, sourceEventId = event,
            sourceSignalId = protectedLocationCanonicalProductIdentity(event), clockDomainId = "boot",
            bootClockDomainId = "boot", altitudeDatum = AltitudeDatum.RELATIVE_BAROMETRIC,
            altitudeSource = AltitudeSource.BAROMETER_PREDICTION)
        val sample = template.copy(id = database.locationSampleDao().insert(template))
        val receipt = LocationAcceptedReceiptIntegrity.seal(LocationAcceptedReceiptEntity(sample.id, event,
            requireNotNull(sample.sourceSignalId), ordinal, "a".repeat(64), LocationAcceptedReceiptIntegrity.canonicalChecksum(sample),
            instance, 1, sequence, "delivery:$ordinal", 0, 1,
            SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_PROJECTION_ID,
            SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_PROJECTION_VERSION, 1, "physical", 1, "authorization",
            "boot", "boot", elapsed, elapsed + 1, time, time + 1, 2, 0))
        database.locationPurposeDao().insertReceiptIfAbsent(receipt)
        if (capture != null) {
            val purpose = LocationSamplePurposeAuthorityEntity(sample.id, SourceBrokerPurpose.SESSION_CAPTURE,
                requireNotNull(capture.serviceRunId), 1, 0, 1, Long.MAX_VALUE, capture.logicalTrackingId,
                capture.serviceRunId, capture.id, captureManifestRevision, 1, null, null, null, 0, "UTC")
            database.locationPurposeDao().insertPurposeIfAbsent(LocationAcceptedReceiptIntegrity.seal(purpose, receipt))
        }
        if (capture == null) {
            val purpose = LocationSamplePurposeAuthorityEntity(sample.id, SourceBrokerPurpose.AMBIENT_PRODUCT,
                "ambient", 1, 0, 1, Long.MAX_VALUE, null, null, null, null, null,
                "ambient", "retention", 1, 0, "UTC")
            database.locationPurposeDao().insertPurposeIfAbsent(LocationAcceptedReceiptIntegrity.seal(purpose, receipt))
        }
        return sample
    }
    private fun policy() = SourcePolicyEntity(1, 1, true, 1, null, null, null,
        true, false, true, 0, null, 0, "boot", 0, 0, "USER")
    private fun distance(first: Int, second: Int) = Location.distance(0.0, first / 1e7, 0.0, second / 1e7, LengthUnit.Meter)
}
