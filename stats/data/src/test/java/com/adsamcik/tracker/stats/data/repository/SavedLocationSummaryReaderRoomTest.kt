package com.adsamcik.tracker.stats.data.repository

import android.app.Application
import android.database.sqlite.SQLiteException
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
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real Room readers with explicitly seeded saved writer grants; no provider/service acceptance claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SavedLocationSummaryReaderRoomTest {
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

    @Test fun `saved session totals survive quality End and retry generations with original point identities`() = runBlocking {
        val selected = session("historical")
        val first = point(2_000, 0, 1, capture = selected)
        val second = point(3_000, 100_000, 2, capture = selected)
        val original = snapshot(reader.readSession(selected.id)).summary
        val run = requireNotNull(database.sourceSessionDao().serviceRun(requireNotNull(selected.serviceRunId)))
        for (generation in listOf(2L, 3L)) {
            database.sourceSessionDao().updateServiceRun(run.copy(leaseGeneration = generation,
                bootId = "later-operation", runRevision = run.runRevision + generation))
            val after = snapshot(reader.readSession(selected.id)).summary
            assertEquals(original, after)
            assertEquals(2L, after.pointCount)
            assertEquals(second.id, after.latestFix?.sampleId)
        }
        assertEquals(setOf(first, second), database.locationSampleDao().byIds(listOf(first.id, second.id)).toSet())
        Unit
    }

    @Test fun `empty and one-fix windows do not invent distance or zero`() = runBlocking {
        val window = HistoryObservationWindow(0, 10_000)
        assertNull(snapshot(reader.readWindow(window)).summary.distanceMeters)
        val point = point(2_000, 0, 1)
        val read = snapshot(reader.readWindow(window))
        assertEquals(1L, read.summary.pointCount)
        assertEquals(point.id, read.summary.latestFix?.sampleId)
        assertNull(read.summary.distanceMeters)
        assertFalse(read.summary.partial)
    }

    @Test fun `stationary coordinates without accuracy retain real zero and saved optional fields`() = runBlocking {
        point(2_000, 0, 1)
        val last = point(3_000, 0, 2, speed = 0f, altitude = -12f)
        val read = snapshot(reader.readWindow(HistoryObservationWindow(0, 10_000)))
        assertEquals(0.0, read.summary.distanceMeters)
        assertEquals(2L, read.summary.pointCount)
        assertFalse(read.summary.partial)
        val fix = requireNotNull(read.summary.latestFix)
        assertEquals(last.id, fix.sampleId)
        assertEquals(3_000L, fix.measuredTimeMs)
        assertEquals(2L, fix.wallTimeUncertaintyMs)
        assertEquals(0f, fix.speedMetersPerSecond)
        assertNull(fix.horizontalAccuracyMeters)
        assertEquals(-12f, fix.altitudeMeters)
        assertEquals(AltitudeDatum.RELATIVE_BAROMETRIC, fix.altitudeDatum)
        assertEquals(AltitudeSource.BAROMETER_PREDICTION, fix.altitudeSource)
        val receipts = database.locationPurposeDao().contextReceiptsOverWindow(0, 10_000, 10)
        assertTrue(database.withTransaction { RetainedLocationContextReader(database).qualifyInTransaction(receipts) }.points.isEmpty())
    }

    @Test fun `invalid optional accuracy and speed are absent while usable coordinates remain measured`() = runBlocking {
        point(2_000, 0, 1)
        point(3_000, 100_000, 2, accuracy = -1f, speed = -1f, altitude = -12f)
        val read = snapshot(reader.readWindow(HistoryObservationWindow(0, 10_000)))
        assertEquals(distance(0, 100_000), read.summary.distanceMeters)
        assertNull(read.summary.latestFix?.horizontalAccuracyMeters)
        assertNull(read.summary.latestFix?.speedMetersPerSecond)
        assertEquals(-12f, read.summary.latestFix?.altitudeMeters)
        assertFalse(read.summary.partial)
    }

    @Test fun `existing background History qualifies saved coordinates without accuracy`() = runBlocking {
        val sample = point(2_000, 0, 1)
        val history = rangeReaderForRoomTest(database, ApplicationProvider.getApplicationContext<Application>())
        val read = assertIs<HistoryRangeRead.Page>(history.read(HistoryRangeRequest(HistoryRange(0, 10_000, "UTC",
            setOf(HistorySource.LOCATION), setOf(HistoryOrigin.LOCAL), setOf(HistoryPurpose.BACKGROUND)))))
        val section = read.rows.single().sections.filterIsInstance<HistorySection.Location>().single()
        assertEquals(HistoryLocationCoverage.QUALIFIED, section.coverage)
        assertEquals(sample.id, section.points.single().sampleId)
        assertNull(section.points.single().accuracyMeters)
        assertTrue(read.problems.isEmpty())
    }

    @Test fun `existing native History qualifies saved coordinates without accuracy`() = runBlocking {
        val selected = session("history")
        val sample = point(2_000, 0, 1, capture = selected)
        val history = rangeReaderForRoomTest(database, ApplicationProvider.getApplicationContext<Application>())
        val read = assertIs<HistoryRangeRead.Page>(history.read(HistoryRangeRequest(HistoryRange(0, 10_000, "UTC",
            setOf(HistorySource.LOCATION), setOf(HistoryOrigin.LOCAL), setOf(HistoryPurpose.CAPTURE)))))
        val section = read.rows.single().sections.filterIsInstance<HistorySection.Location>().single()
        assertEquals(HistoryLocationCoverage.QUALIFIED, section.coverage)
        assertEquals(sample.id, section.points.single().sampleId)
        assertNull(section.points.single().accuracyMeters)
        assertTrue(read.problems.isEmpty())
    }

    @Test fun `long live session pages past 4096 without losing its anchor or borrowing raw totals`() = runBlocking {
        withTimeout(90_000L) {
            val session = session("long")
            repeat(4_097) { index -> point(2_000L + index * 1_000L, index * 1_000, index + 1L, capture = session) }
            val read = snapshot(reader.readSession(session.id))
            assertEquals(SavedLocationSummarySelection.Session(session.id), read.selection)
            assertEquals(4_097L, read.summary.pointCount)
            assertEquals(distance(0, 1_000) * 4_096, requireNotNull(read.summary.distanceMeters), 0.01)
            assertEquals(4_098_000L, read.summary.latestFix?.measuredTimeMs)
            assertFalse(read.summary.partial)
            assertNull(read.problem)
            assertEquals(9_000_000f, requireNotNull(database.sessionSegmentDao().getById(session.id)).distanceM)
            assertNull(database.sourceEventWalDao().getByEventId("event:1"))
        }
    }

    @Test fun `sequence jumps clock changes and long gaps never bridge sections`() = runBlocking {
        point(2_000, 0, 1)
        point(3_000, 100_000, 2)
        point(4_000, 900_000, 4)
        point(5_000, 1_000_000, 5)
        point(6_000, 9_000_000, 6, clock = "other")
        point(130_000, 10_000_000, 7, clock = "other")
        point(131_000, 10_100_000, 8, clock = "other")
        val read = snapshot(reader.readWindow(HistoryObservationWindow(0, 200_000)))
        assertEquals(distance(0, 100_000) + distance(900_000, 1_000_000) + distance(10_000_000, 10_100_000),
            requireNotNull(read.summary.distanceMeters), 0.01)
        assertEquals(7L, read.summary.pointCount)
        assertTrue(read.summary.partial)
    }

    @Test fun `rejected middle point cannot join its valid neighbours or overwrite the original authority`() = runBlocking {
        point(2_000, 0, 1)
        val corrupt = point(3_000, 100_000, 2)
        point(4_000, 200_000, 3)
        point(5_000, 300_000, 4)
        database.locationSampleDao().update(corrupt.copy(lonE7 = 9_000_000))
        val read = snapshot(reader.readWindow(HistoryObservationWindow(0, 10_000)))
        assertEquals(3L, read.summary.pointCount)
        assertEquals(distance(200_000, 300_000), read.summary.distanceMeters)
        assertTrue(read.summary.partial)
        assertTrue(RadioLocationContextGap.LOCATION_PROVENANCE_UNVERIFIABLE in read.summary.qualificationGaps)
        assertEquals(4L, database.locationSampleDao().countAll())
        assertEquals(9_000_000, database.locationSampleDao().byIds(listOf(corrupt.id)).single().lonE7)
    }

    @Test fun `exact session excludes coincident background and another run while window reads their own grants`() = runBlocking {
        val selected = session("selected")
        val foreign = session("foreign")
        point(2_000, 0, 1, capture = selected)
        point(3_000, 100_000, 2, capture = selected)
        point(2_500, 9_000_000, 1, instance = "ambient")
        point(3_500, 10_000_000, 1, capture = foreign, instance = "foreign")
        val exact = snapshot(reader.readSession(selected.id))
        assertEquals(2L, exact.summary.pointCount)
        assertEquals(distance(0, 100_000), exact.summary.distanceMeters)
        assertEquals(4L, snapshot(reader.readWindow(HistoryObservationWindow(0, 10_000))).summary.pointCount)
    }

    @Test fun `End and later disabled policy keep retained original session readings without a physical owner`() = runBlocking {
        val selected = session("retained")
        point(2_000, 0, 1, capture = selected)
        point(3_000, 100_000, 2, capture = selected)
        val run = requireNotNull(database.sourceSessionDao().serviceRun(requireNotNull(selected.serviceRunId)))
        database.sourceSessionDao().updateServiceRun(run.copy(state = "FINALIZED", completedAtMs = 5_000,
            completionReason = "STOPPED", presentationAcknowledgement = SourceServiceRunEntity.PRESENTATION_QUIESCED,
            presentationAcknowledgedAtMs = 5_000))
        database.sourcePolicyDao().insertPolicies(listOf(policy().copy(policyRevision = 2, enabled = false,
            capturePersistenceEligible = false, ambientPersistenceEligible = false)))
        val read = snapshot(reader.readSession(selected.id))
        assertEquals(2L, read.summary.pointCount)
        assertEquals(distance(0, 100_000), read.summary.distanceMeters)
        assertFalse(read.summary.partial)
        assertNull(database.sourceEventWalDao().getByEventId("event:1"))
    }

    @Test fun `selected purpose erase preserves independently authorized background sibling`() = runBlocking {
        val selected = session("erased")
        point(2_000, 0, 1, capture = selected, alsoBackground = true)
        point(3_000, 100_000, 2, capture = selected, alsoBackground = true)
        val digest = SourceDeletionFenceEntity.logicalServiceRunIdentity(1, SourceBrokerPurpose.SESSION_CAPTURE,
            requireNotNull(selected.logicalTrackingId), requireNotNull(selected.serviceRunId))
        database.sourceDeletionFenceDao().insertIfAbsent(SourceDeletionFenceEntity.createForOriginalRunDigest(1,
            SourceBrokerPurpose.SESSION_CAPTURE, digest, 1, 0, 5_000))
        assertNull(snapshot(reader.readSession(selected.id)).summary.distanceMeters)
        val sibling = snapshot(reader.readWindow(HistoryObservationWindow(0, 10_000)))
        assertEquals(2L, sibling.summary.pointCount)
        assertEquals(distance(0, 100_000), sibling.summary.distanceMeters)
        assertEquals(2L, database.locationSampleDao().countAll())
    }

    @Test fun `retention and full clear retract native values without inventing a measured zero`() = runBlocking {
        point(2_000, 0, 1)
        point(3_000, 100_000, 2)
        database.sourceEvidenceStateDao().updateLifecycle(0, 2_500, 4_000)
        val retained = snapshot(reader.readWindow(HistoryObservationWindow(0, 10_000)))
        assertEquals(1L, retained.summary.pointCount)
        assertNull(retained.summary.distanceMeters)
        database.sourceEvidenceStateDao().updateAfterFullDeletion(1, null, 2, 4_001)
        val cleared = snapshot(reader.readWindow(HistoryObservationWindow(0, 10_000)))
        assertEquals(1L, cleared.readSnapshot.collectedDataEpoch)
        assertEquals(0L, cleared.summary.pointCount)
        assertNull(cleared.summary.distanceMeters)
        assertNull(cleared.summary.latestFix)
        assertEquals(2L, database.locationSampleDao().countAll())
    }

    @Test fun `displayed DST day selects measured UTC fixes rather than delivery or session time`() = runBlocking {
        val date = LocalDate.of(2026, 10, 25)
        val zone = ZoneId.of("Europe/Prague")
        val from = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals(25 * 60 * 60 * 1_000L, to - from)
        point(from - 1, 0, 1)
        point(from, 100_000, 2)
        val last = point(to - 1, 200_000, 3)
        point(to, 300_000, 4)
        val read = snapshot(reader.readWindow(HistoryObservationWindow(from, to)))
        assertEquals(2L, read.summary.pointCount)
        assertEquals(last.id, read.summary.latestFix?.sampleId)
        assertNull(read.summary.distanceMeters)
        assertTrue(read.summary.partial)
    }

    @Test fun `many real run dependencies split qualification batches instead of dropping the window`() = runBlocking {
        withTimeout(90_000L) {
            repeat(101) { index -> point(2_000L + index, index * 1_000, 1,
                instance = "instance:$index", capture = session("session:$index")) }
            val read = snapshot(reader.readWindow(HistoryObservationWindow(0, 10_000)))
            assertEquals(101L, read.summary.pointCount)
            assertNull(read.problem)
            assertFalse(RadioLocationContextGap.LOCATION_DEPENDENCY_LIMIT in read.summary.qualificationGaps)
        }
    }

    @Test fun `more than one exact manifest-key chunk keeps every named reading in a long run`() = runBlocking {
        val selected = session("many-manifests")
        repeat(129) { index ->
            val revision = index + 1L
            if (revision > 1) namedManifest(selected, revision)
            point(2_000L + index * 1_000L, index * 1_000, revision,
                capture = selected, captureManifestRevision = revision)
        }
        val read = snapshot(reader.readSession(selected.id))
        assertEquals(129L, read.summary.pointCount)
        assertEquals(distance(0, 1_000) * 128, requireNotNull(read.summary.distanceMeters), 0.01)
        assertFalse(read.summary.partial)
        assertNull(read.problem)
        assertTrue(read.summary.qualificationGaps.isEmpty())
    }

    @Test fun `missing or mismatched named manifest preserves valid sibling data without borrowing another run`() = runBlocking {
        val selected = session("named-dependency")
        val valid = point(2_000, 0, 1, capture = selected)
        point(3_000, 100_000, 2, capture = selected, captureManifestRevision = 2)
        fun check(read: SavedLocationSummaryRead) {
            val result = snapshot(read)
            assertEquals(1L, result.summary.pointCount)
            assertEquals(valid.id, result.summary.latestFix?.sampleId)
            assertNull(result.summary.distanceMeters)
            assertTrue(result.summary.partial)
            assertTrue(RadioLocationContextGap.LOCATION_AUTHORITY_UNAVAILABLE in result.summary.qualificationGaps)
        }
        check(reader.readSession(selected.id))
        namedManifest(selected, 2, serviceRunId = "foreign-run")
        check(reader.readSession(selected.id))
        assertEquals(2L, database.locationSampleDao().countAll())
    }

    @Test fun `continuation storage failure keeps previously measured data with an honest partial state`() = runBlocking {
        repeat(300) { index -> point(2_000L + index * 1_000L, 0, index + 1L) }
        val original = database.locationPurposeDao()
        val fault = object : LocationPurposeDao by original {
            override suspend fun savedReceiptPageOverWindow(fromMs: Long, toMs: Long, afterTimeMs: Long?,
                afterSampleId: Long?, limit: Int): List<LocationAcceptedReceiptEntity> {
                if (afterTimeMs != null) throw SQLiteException("Fixture: second page unavailable")
                return original.savedReceiptPageOverWindow(fromMs, toMs, afterTimeMs, afterSampleId, limit)
            }
        }
        val fixtureReader = readerWithPageFault(fault)
        val read = database.withTransaction { snapshot(fixtureReader.readWindowInTransaction(
            HistoryObservationWindow(0, 1_000_000), currentSnapshot())) }
        assertEquals(256L, read.summary.pointCount)
        assertEquals(0.0, read.summary.distanceMeters)
        assertEquals(257_000L, read.summary.latestFix?.measuredTimeMs)
        assertTrue(read.summary.partial)
        assertEquals(HistoryProblemReason.STORAGE_UNAVAILABLE, read.problem)
        assertEquals(300L, database.locationSampleDao().countAll())
    }

    @Test fun `continuation cancellation propagates instead of publishing a prefix or retrying`() = runBlocking {
        repeat(300) { index -> point(2_000L + index * 1_000L, 0, index + 1L) }
        val original = database.locationPurposeDao()
        var pages = 0
        val fault = object : LocationPurposeDao by original {
            override suspend fun savedReceiptPageOverWindow(fromMs: Long, toMs: Long, afterTimeMs: Long?,
                afterSampleId: Long?, limit: Int): List<LocationAcceptedReceiptEntity> {
                pages++
                if (afterTimeMs != null) throw CancellationException("Fixture: cancelled second page")
                return original.savedReceiptPageOverWindow(fromMs, toMs, afterTimeMs, afterSampleId, limit)
            }
        }
        val fixtureReader = readerWithPageFault(fault)
        assertFailsWith<CancellationException> { database.withTransaction {
            fixtureReader.readWindowInTransaction(HistoryObservationWindow(0, 1_000_000), currentSnapshot())
        } }
        assertEquals(2, pages)
        assertEquals(300L, database.locationSampleDao().countAll())
    }

    @Test fun `missing session and closed storage remain typed without using raw session totals`() = runBlocking {
        assertEquals(SavedLocationSummaryRead.NotFound, reader.readSession(999))
        database.close()
        assertEquals(HistoryProblemReason.STORAGE_UNAVAILABLE,
            assertIs<SavedLocationSummaryRead.Unavailable>(reader.readWindow(HistoryObservationWindow(0, 10_000))).reason)
    }

    /** Only the page-fault boundary is artificial; all qualification DAOs and the outer transaction are real Room. */
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
    private suspend fun currentSnapshot(): TrackingHistoryReadSnapshot {
        val state = requireNotNull(database.sourceEvidenceStateDao().get())
        return TrackingHistoryReadSnapshot(state.collectedDataEpoch, state.revision)
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
    private suspend fun namedManifest(session: SessionSegment, revision: Long,
        serviceRunId: String = requireNotNull(session.serviceRunId)) {
        val logicalId = requireNotNull(session.logicalTrackingId)
        val member = SessionManifestSourceEntity(logicalId, revision, 1, SourceBrokerPurpose.SESSION_CAPTURE, 0, true, 1)
        val manifest = SessionManifestVersionEntity(logicalId, revision, serviceRunId, "MANUAL", 1, 1, 1,
            "POLICY_RECONCILIATION", "boot", revision, 1_000, "UTC", null, "POLICY_RECONCILIATION", "")
        database.sourceSessionDao().insertManifest(manifest.copy(manifestChecksum = SessionManifestIntegrity.compute(manifest, listOf(member))))
        database.sourceSessionDao().insertManifestSources(listOf(member))
    }
    private fun policy() = SourcePolicyEntity(1, 1, true, 1, null, null, null,
        true, false, true, 0, null, 0, "boot", 0, 0, "USER")
    private fun distance(first: Int, second: Int) = Location.distance(0.0, first / 1e7, 0.0, second / 1e7, LengthUnit.Meter)
}
