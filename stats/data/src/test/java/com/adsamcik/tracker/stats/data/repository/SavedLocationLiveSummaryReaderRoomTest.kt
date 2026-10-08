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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
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

/** Real Room readers with explicitly seeded saved writer grants; no provider/service acceptance claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SavedLocationLiveSummaryReaderRoomTest {
    private lateinit var database: AppDatabase
    private lateinit var reader: SavedLocationSummaryReader
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
        reader = SavedLocationSummaryReader(database, Dispatchers.Unconfined)
    }
    @After fun close() = database.close()

    @Test fun `one seed is extended without reading the growing session again`() = runBlocking {
        val selected = session("live")
        database.withTransaction { point(2_000, 0, 1, capture = selected) }
        val actual = database.locationPurposeDao()
        var fullPages = 0
        var continuationPages = 0
        val counted = object : LocationPurposeDao by actual {
            override suspend fun savedLogicalTripPointPage(logicalId: String, serviceRunIds: List<String>,
                afterTimeMs: Long?, afterId: Long?, limit: Int): List<com.adsamcik.tracker.shared.base.database.dao.LocationPathSampleRow> {
                fullPages++
                return actual.savedLogicalTripPointPage(logicalId, serviceRunIds, afterTimeMs, afterId, limit)
            }
            override suspend fun savedSessionReceiptPageAfterId(logicalId: String, runId: String, segmentId: Long,
                afterId: Long, throughId: Long, limit: Int): List<LocationAcceptedReceiptEntity> {
                continuationPages++
                return actual.savedSessionReceiptPageAfterId(logicalId, runId, segmentId, afterId, throughId, limit)
            }
        }
        val hints = SavedLocationLiveUpdateSignal()
        val live = SavedLocationLiveSummaryReader(database, Dispatchers.Unconfined, hints,
            readerWithPageFault(counted), counted)
        live.observeSession(selected.id).test {
            awaitCount(1)
            val seedPages = fullPages
            repeat(5) { index ->
                val next = database.withTransaction {
                    point(3_000L + index * 1_000L, (index + 1) * 100_000, index + 2L, capture = selected)
                }
                hints.pointCommitted(next.id)
                val read = awaitCount(index + 2L)
                assertEquals(distance(0, 100_000) * (index + 1), requireNotNull(read.summary.distanceMeters), 0.01)
                assertEquals(next.timeMs, read.summary.latestFix?.measuredTimeMs)
            }
            assertEquals(seedPages, fullPages)
            assertTrue(continuationPages >= 5)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `a missed hint still discovers every committed point through Room and receipt cursor`() = runBlocking {
        val selected = session("missed")
        database.withTransaction { point(2_000, 0, 1, capture = selected) }
        val live = SavedLocationLiveSummaryReader(database, Dispatchers.Unconfined, SavedLocationLiveUpdateSignal())
        live.observeSession(selected.id).test {
            awaitCount(1)
            database.withTransaction {
                repeat(300) { index -> point(3_000L + index * 1_000L, (index + 1) * 1_000, index + 2L, capture = selected) }
            }
            val read = awaitCount(301)
            assertEquals(distance(0, 1_000) * 300, requireNotNull(read.summary.distanceMeters), 0.01)
            assertFalse(read.summary.partial)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `conflated and duplicate hints do not double count saved points`() = runBlocking {
        val selected = session("conflated")
        val first = database.withTransaction { point(2_000, 0, 1, capture = selected) }
        val hints = SavedLocationLiveUpdateSignal()
        val live = SavedLocationLiveSummaryReader(database, Dispatchers.Unconfined, hints)
        live.observeSession(selected.id).test {
            awaitCount(1)
            val last = database.withTransaction {
                var lastPoint = first
                repeat(20) { index -> lastPoint = point(3_000L + index * 1_000L,
                    (index + 1) * 1_000, index + 2L, capture = selected) }
                lastPoint
            }
            repeat(20) { hints.pointCommitted(last.id) }
            val read = awaitCount(21)
            assertEquals(distance(0, 1_000) * 20, requireNotNull(read.summary.distanceMeters), 0.01)
            hints.pointCommitted(first.id)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(21L, database.locationSampleDao().countAll())
    }

    @Test fun `delayed older point rebuilds measured ordering instead of extending the wrong end`() = runBlocking {
        val selected = session("late")
        database.withTransaction {
            point(2_000, 0, 1, capture = selected)
            point(4_000, 200_000, 3, capture = selected)
        }
        val hints = SavedLocationLiveUpdateSignal()
        val live = SavedLocationLiveSummaryReader(database, Dispatchers.Unconfined, hints)
        live.observeSession(selected.id).test {
            assertTrue(awaitCount(2).summary.partial)
            val late = database.withTransaction { point(3_000, 100_000, 2, capture = selected) }
            hints.pointCommitted(late.id)
            val read = awaitCount(3)
            assertEquals(distance(0, 100_000) * 2, requireNotNull(read.summary.distanceMeters), 0.01)
            assertFalse(read.summary.partial)
            assertEquals(4_000L, read.summary.latestFix?.measuredTimeMs)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `point deletion retracts a saved section rather than keeping the cached total`() = runBlocking {
        val selected = session("delete")
        lateinit var middle: LocationSample
        database.withTransaction {
            point(2_000, 0, 1, capture = selected)
            middle = point(3_000, 100_000, 2, capture = selected)
            point(4_000, 200_000, 3, capture = selected)
        }
        val live = SavedLocationLiveSummaryReader(database, Dispatchers.Unconfined, SavedLocationLiveUpdateSignal())
        live.observeSession(selected.id).test {
            assertNotNull(awaitCount(3).summary.distanceMeters)
            database.withTransaction { database.locationSampleDao().deleteExactIds(listOf(middle.id)) }
            val read = awaitCount(2)
            assertNull(read.summary.distanceMeters)
            assertTrue(read.summary.partial)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `changed original row with the writer revision is requalified instead of reused`() = runBlocking {
        val selected = session("changed")
        lateinit var last: LocationSample
        database.withTransaction {
            point(2_000, 0, 1, capture = selected)
            last = point(3_000, 100_000, 2, capture = selected)
        }
        val live = SavedLocationLiveSummaryReader(database, Dispatchers.Unconfined, SavedLocationLiveUpdateSignal())
        live.observeSession(selected.id).test {
            awaitCount(2)
            database.withTransaction {
                database.locationSampleDao().update(last.copy(lonE7 = 300_000))
                database.sourceEvidenceStateDao().incrementRevision(5_000)
            }
            val read = awaitCount(1)
            assertNull(read.summary.distanceMeters)
            assertTrue(read.summary.partial)
            assertEquals(2_000L, read.summary.latestFix?.measuredTimeMs)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `epoch clear cannot publish the prior dataset total after another hint`() = runBlocking {
        val selected = session("clear")
        val last = database.withTransaction {
            point(2_000, 0, 1, capture = selected)
            point(3_000, 100_000, 2, capture = selected)
        }
        val hints = SavedLocationLiveUpdateSignal()
        val live = SavedLocationLiveSummaryReader(database, Dispatchers.Unconfined, hints)
        live.observeSession(selected.id).test {
            awaitCount(2)
            database.withTransaction { database.sourceEvidenceStateDao().updateLifecycle(1, null, 5_000) }
            hints.pointCommitted(last.id)
            val read = awaitCount(0)
            assertNull(read.summary.distanceMeters)
            assertNull(read.summary.latestFix)
            assertEquals(1L, read.readSnapshot.collectedDataEpoch)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `ambient and another session do not lend their saved points to the active segment`() = runBlocking {
        val selected = session("selected")
        val sibling = session("sibling")
        database.withTransaction { point(2_000, 0, 1, capture = selected) }
        val hints = SavedLocationLiveUpdateSignal()
        val live = SavedLocationLiveSummaryReader(database, Dispatchers.Unconfined, hints)
        live.observeSession(selected.id).test {
            awaitCount(1)
            database.withTransaction {
                point(3_000, 100_000, 2)
                point(4_000, 200_000, 1, capture = sibling, instance = "sibling-instance")
                point(5_000, 300_000, 3, capture = selected)
            }
            val read = awaitCount(2)
            assertNull(read.summary.distanceMeters)
            assertTrue(read.summary.partial)
            assertEquals(4L, database.locationSampleDao().countAll())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `an older receipt gaining exact session membership forces a complete catch-up`() = runBlocking {
        val selected = session("older-membership")
        val prior = database.withTransaction { point(2_000, 0, 1) }
        database.withTransaction { point(3_000, 100_000, 2, capture = selected) }
        val live = SavedLocationLiveSummaryReader(database, Dispatchers.Unconfined, SavedLocationLiveUpdateSignal())
        live.observeSession(selected.id).test {
            awaitCount(1)
            database.withTransaction {
                val receipt = requireNotNull(database.locationPurposeDao().receipt(prior.id))
                val purpose = LocationSamplePurposeAuthorityEntity(prior.id, SourceBrokerPurpose.SESSION_CAPTURE,
                    requireNotNull(selected.serviceRunId), 1, 0, 1, Long.MAX_VALUE, selected.logicalTrackingId,
                    selected.serviceRunId, selected.id, 1, 1, null, null, null, 0, "UTC")
                database.locationPurposeDao().insertPurposeIfAbsent(LocationAcceptedReceiptIntegrity.seal(purpose, receipt))
            }
            val read = awaitCount(2)
            assertEquals(distance(0, 100_000), requireNotNull(read.summary.distanceMeters), 0.01)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `a rejected last candidate remains a continuity gap after seeding`() = runBlocking {
        val selected = session("rejected-anchor")
        database.withTransaction {
            point(2_000, 0, 1, capture = selected)
            point(3_000, 100_000, 2, capture = selected, captureManifestRevision = 2)
        }
        val live = SavedLocationLiveSummaryReader(database, Dispatchers.Unconfined, SavedLocationLiveUpdateSignal())
        live.observeSession(selected.id).test {
            awaitCount(1)
            database.withTransaction { point(4_000, 200_000, 3, capture = selected) }
            val read = awaitCount(2)
            assertNull(read.summary.distanceMeters)
            assertTrue(read.summary.partial)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `real zero optional accuracy and measured timestamp survive the shared live read`() = runBlocking {
        val selected = session("zero")
        database.withTransaction { point(2_000, 0, 1, capture = selected) }
        val live = SavedLocationLiveSummaryReader(database, Dispatchers.Unconfined, SavedLocationLiveUpdateSignal())
        live.observeSession(selected.id).test {
            awaitCount(1)
            database.withTransaction { point(3_000, 0, 2, capture = selected, speed = 0f, altitude = -12f) }
            val read = awaitCount(2)
            assertEquals(0.0, read.summary.distanceMeters)
            assertEquals(3_000L, read.summary.latestFix?.measuredTimeMs)
            assertEquals(0f, read.summary.latestFix?.speedMetersPerSecond)
            assertNull(read.summary.latestFix?.horizontalAccuracyMeters)
            assertEquals(-12f, read.summary.latestFix?.altitudeMeters)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `a retained suffix stays useful and partial in finite and live summaries`() = runBlocking {
        val selected = session("retained-suffix")
        database.withTransaction {
            point(2_000, 0, 1, capture = selected)
            point(3_000, 100_000, 2, capture = selected)
            point(4_000, 200_000, 3, capture = selected)
        }
        val live = SavedLocationLiveSummaryReader(database, Dispatchers.Unconfined, SavedLocationLiveUpdateSignal())
        live.observeSession(selected.id).test {
            assertFalse(awaitCount(3).summary.partial)
            database.withTransaction { database.sourceEvidenceStateDao().updateLifecycle(0, 2_500, 5_000) }
            val read = awaitCount(2)
            assertTrue(read.summary.partial)
            assertEquals(distance(0, 100_000), requireNotNull(read.summary.distanceMeters), 0.01)
            val finite = snapshot(reader.readSession(selected.id))
            assertEquals(read.summary, finite.summary)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `a long saved session seeds completely then appends without a total point limit`() = runBlocking {
        withTimeout(90_000L) {
            val selected = session("long-live")
            database.withTransaction {
                repeat(4_097) { index -> point(2_000L + index * 1_000L, 0, index + 1L, capture = selected) }
            }
            val live = SavedLocationLiveSummaryReader(database, Dispatchers.Unconfined, SavedLocationLiveUpdateSignal())
            live.observeSession(selected.id).test {
                val initial = awaitCount(4_097)
                assertEquals(0.0, initial.summary.distanceMeters)
                assertFalse(initial.summary.partial)
                database.withTransaction { point(4_099_000, 0, 4_098, capture = selected) }
                val appended = awaitCount(4_098)
                assertEquals(0.0, appended.summary.distanceMeters)
                assertFalse(appended.summary.partial)
                assertEquals(4_099_000L, appended.summary.latestFix?.measuredTimeMs)
                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    private suspend fun ReceiveTurbine<SavedLocationSummaryRead>.awaitCount(count: Long): SavedLocationSummaryRead.Snapshot =
        withTimeout(5_000L) {
            while (true) {
                val read = awaitItem()
                if (read is SavedLocationSummaryRead.Snapshot && read.summary.pointCount == count) return@withTimeout read
            }
            error("Unreachable")
        }

    /** Only the page-fault boundary is artificial; all qualification DAOs and the outer transaction are real Room. */
    @Test fun `a coalesced post-purpose hint cannot lose same maximum receipt membership`() = runBlocking(Dispatchers.Unconfined) {
        val selected = session("coalesced-membership")
        val prior = database.withTransaction { point(2_000, 0, 1) }
        database.withTransaction { point(3_000, 100_000, 2, capture = selected) }
        val notices = MutableSharedFlow<Set<String>>(replay = 1, extraBufferCapacity = 4)
        notices.emit(setOf("location_sample"))
        val hints = SavedLocationLiveUpdateSignal()
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val observed = Channel<SavedLocationSummaryRead>(Channel.UNLIMITED)
        val live = SavedLocationLiveSummaryReader(database, Dispatchers.Unconfined, hints,
            invalidations = notices)
        val job = launch {
            var first = true
            live.observeSession(selected.id).collect { read ->
                observed.send(read)
                if (first) { first = false; entered.complete(Unit); release.await() }
            }
        }
        try {
            withTimeout(5_000L) { entered.await() }
            assertEquals(1L, snapshot(withTimeout(5_000L) { observed.receive() }).summary.pointCount)
            val maximum = database.locationPurposeDao().latestSavedReceiptSampleId()
            database.withTransaction {
                val receipt = requireNotNull(database.locationPurposeDao().receipt(prior.id))
                val grant = LocationSamplePurposeAuthorityEntity(prior.id, SourceBrokerPurpose.SESSION_CAPTURE,
                    requireNotNull(selected.serviceRunId), 1, 0, 1, Long.MAX_VALUE, selected.logicalTrackingId,
                    selected.serviceRunId, selected.id, 1, 1, null, null, null, 0, "UTC")
                database.locationPurposeDao().insertPurposeIfAbsent(LocationAcceptedReceiptIntegrity.seal(grant, receipt))
            }
            assertEquals(maximum, database.locationPurposeDao().latestSavedReceiptSampleId())
            // Synthetic notice delivery controls the exact coalescing race over actual Room membership.
            notices.emit(setOf("location_sample_purpose_authority"))
            yield()
            hints.pointCommitted(prior.id)
            yield()
            release.complete(Unit)
            val read = snapshot(withTimeout(5_000L) { observed.receive() })
            assertEquals(2L, read.summary.pointCount)
            assertEquals(distance(0, 100_000), requireNotNull(read.summary.distanceMeters), 0.01)
        } finally { release.complete(Unit); job.cancelAndJoin(); observed.close() }
    }

    @Test fun `a coalesced hint cannot lose an authority dependency rebuild`() = runBlocking(Dispatchers.Unconfined) {
        val selected = session("coalesced-dependency")
        database.withTransaction {
            point(2_000, 0, 1, capture = selected)
            point(3_000, 100_000, 2, capture = selected)
        }
        val notices = MutableSharedFlow<Set<String>>(replay = 1, extraBufferCapacity = 4)
        notices.emit(setOf("location_sample"))
        val hints = SavedLocationLiveUpdateSignal()
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val observed = Channel<SavedLocationSummaryRead>(Channel.UNLIMITED)
        var fullPages = 0
        val actual = database.locationPurposeDao()
        val counted = object : LocationPurposeDao by actual {
            override suspend fun savedLogicalTripPointPage(logicalId: String, serviceRunIds: List<String>,
                afterTimeMs: Long?, afterId: Long?, limit: Int): List<com.adsamcik.tracker.shared.base.database.dao.LocationPathSampleRow> {
                fullPages++
                return actual.savedLogicalTripPointPage(logicalId, serviceRunIds, afterTimeMs, afterId, limit)
            }
        }
        val live = SavedLocationLiveSummaryReader(database, Dispatchers.Unconfined, hints,
            readerWithPageFault(counted), counted, notices)
        val job = launch {
            var first = true
            live.observeSession(selected.id).collect { read ->
                observed.send(read)
                if (first) { first = false; entered.complete(Unit); release.await() }
            }
        }
        try {
            withTimeout(5_000L) { entered.await() }
            snapshot(withTimeout(5_000L) { observed.receive() })
            val seedPages = fullPages
            // A source-table notice must trigger requalification even when its current values remain equal.
            notices.emit(setOf("session_manifest_source"))
            yield()
            hints.pointCommitted(actual.latestSavedReceiptSampleId())
            yield()
            release.complete(Unit)
            withTimeout(5_000L) { while (fullPages <= seedPages) yield() }
            assertTrue(fullPages > seedPages)
        } finally { release.complete(Unit); job.cancelAndJoin(); observed.close() }
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
    private fun snapshot(read: SavedLocationSummaryRead) = assertIs<SavedLocationSummaryRead.Snapshot>(read)

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
        instance: String = "instance", clock: String = "boot", alsoBackground: Boolean = false,
        accuracy: Float? = null, speed: Float? = null, altitude: Float? = null,
        captureManifestRevision: Long = 1): LocationSample {
        ordinal++
        val event = "event:$ordinal"
        val elapsed = time * 1_000_000L
        val template = LocationSample(timeMs = time, elapsedRealtimeNanos = elapsed, latE7 = 0, lonE7 = longitude,
            altitudeM = altitude, rawGpsAltitudeM = null, hAccM = accuracy, vAccM = null, speedMps = speed,
            speedAccuracyMps = null, provider = "fused", quality = SampleQuality.COARSE,
            motionState = null, policy = null, bucketId = null, createdAt = time + 1,
            receivedElapsedRealtimeNanos = elapsed + 1, sourceEventId = event,
            sourceSignalId = protectedLocationCanonicalProductIdentity(event), clockDomainId = clock,
            bootClockDomainId = clock, altitudeDatum = AltitudeDatum.RELATIVE_BAROMETRIC,
            altitudeSource = AltitudeSource.BAROMETER_PREDICTION)
        val sample = template.copy(id = database.locationSampleDao().insert(template))
        val receipt = LocationAcceptedReceiptIntegrity.seal(LocationAcceptedReceiptEntity(sample.id, event,
            requireNotNull(sample.sourceSignalId), ordinal, "a".repeat(64), LocationAcceptedReceiptIntegrity.canonicalChecksum(sample),
            instance, 1, sequence, "delivery:$ordinal", 0, 1,
            SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_PROJECTION_ID,
            SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_PROJECTION_VERSION, 1, "physical", 1, "authorization",
            clock, clock, elapsed, elapsed + 1, time, time + 1, 2, 0))
        database.locationPurposeDao().insertReceiptIfAbsent(receipt)
        if (capture != null) {
            val purpose = LocationSamplePurposeAuthorityEntity(sample.id, SourceBrokerPurpose.SESSION_CAPTURE,
                requireNotNull(capture.serviceRunId), 1, 0, 1, Long.MAX_VALUE, capture.logicalTrackingId,
                capture.serviceRunId, capture.id, captureManifestRevision, 1, null, null, null, 0, "UTC")
            database.locationPurposeDao().insertPurposeIfAbsent(LocationAcceptedReceiptIntegrity.seal(purpose, receipt))
        }
        if (capture == null || alsoBackground) {
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
