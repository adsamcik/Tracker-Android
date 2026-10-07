package com.adsamcik.tracker.stats.data.repository

import android.app.Application
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.removeLocationAmbientPurposeInCurrentTransaction
import com.adsamcik.tracker.shared.base.database.data.AmbientLocationRetentionAuthorityIntegrity
import com.adsamcik.tracker.shared.base.database.data.LocationAcceptedReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.LocationAcceptedReceiptIntegrity
import com.adsamcik.tracker.shared.base.database.data.LocationSample
import com.adsamcik.tracker.shared.base.database.data.LocationSamplePurposeAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.SampleQuality
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.protectedLocationCanonicalProductIdentity
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.stats.api.repository.RadioContextSource
import com.adsamcik.tracker.stats.api.repository.RadioLocationContextGap
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real retained Room reads; no transient WAL, provider, acquisition adapter, or writer is installed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RetainedLocationPurposeLifecycleRoomTest {
    private lateinit var database: AppDatabase
    private lateinit var reader: RetainedLocationContextReader

    @Before fun setup() = runBlocking {
        database = AppDatabase.testDatabase(ApplicationProvider.getApplicationContext<Application>())
        database.sourceEvidenceStateDao().ensure()
        reader = RetainedLocationContextReader(database)
    }

    @After fun cleanup() = database.close()

    @Test fun `closed run retains both qualified purposes without original WAL or live registration`() = runBlocking {
        val receipt = installJoinedPoint()
        val before = requireNotNull(database.sourceEvidenceStateDao().get())
        val saved = database.locationSampleDao().byIds(listOf(receipt.sampleId)).single()
        val read = read(receipt)

        assertEquals(setOf(captureScope, ambientScope), read.points.map { it.scope }.toSet())
        assertTrue(read.gaps.isEmpty())
        assertNull(read.historyProblem)
        assertNull(database.sourceEventWalDao().getByEventId(EVENT))
        assertEquals("FINALIZED", requireNotNull(database.sourceSessionDao().serviceRun(RUN)).state)
        assertEquals(before, database.sourceEvidenceStateDao().get())
        assertEquals(saved, database.locationSampleDao().byIds(listOf(receipt.sampleId)).single())
        assertEquals(EVENT, RadioLocationContextResolver.resolve(listOf(radio(captureScope)), read.points)
            .single().locationSourceEventId)
    }

    @Test fun `a later Off policy leaves closed capture and ambient history unchanged`() = runBlocking {
        val receipt = installJoinedPoint()
        database.sourcePolicyDao().insertPolicies(listOf(policy().copy(policyRevision = 2, enabled = false,
            capturePersistenceEligible = false, ambientPersistenceEligible = false,
            captureConsentEpoch = 1, ambientConsentEpoch = 1)))
        database.sourcePolicyDao().insertConsentEpochs(listOf(consent(SourceBrokerPurpose.SESSION_CAPTURE)
            .copy(epoch = 1, eligible = false, persistenceEligible = false, policyRevision = 2),
            consent(SourceBrokerPurpose.AMBIENT_PRODUCT)
                .copy(epoch = 1, eligible = false, persistenceEligible = false, policyRevision = 2)))

        val read = read(receipt)
        assertEquals(setOf(captureScope, ambientScope), read.points.map { it.scope }.toSet())
        assertTrue(read.gaps.isEmpty())
        assertEquals(1L, database.locationSampleDao().countAll())
        assertEquals(2, database.locationPurposeDao().purposes(receipt.sampleId, 3).size)
    }

    @Test fun `exact capture erase leaves independently retained ambient context usable`() = runBlocking {
        val receipt = installJoinedPoint()
        assertEquals(setOf(captureScope, ambientScope), read(receipt).points.map { it.scope }.toSet())
        database.sourceDeletionFenceDao().insertIfAbsent(SourceDeletionFenceEntity.createLogicalServiceRun(
            LOCATION, SourceBrokerPurpose.SESSION_CAPTURE, LOGICAL, RUN, 1, 0, 2_100))

        val read = read(receipt)
        assertEquals(listOf(ambientScope), read.points.map { it.scope })
        assertTrue(RadioLocationContextGap.LOCATION_AUTHORITY_UNAVAILABLE in read.gaps)
        assertTrue(RadioLocationContextResolver.resolve(listOf(radio(captureScope)), read.points).isEmpty())
        assertEquals(EVENT, RadioLocationContextResolver.resolve(listOf(radio(ambientScope)), read.points)
            .single().locationSourceEventId)
        assertEquals(1L, database.locationSampleDao().countAll())
    }

    @Test fun `removing ambient purpose keeps the same native sample under its capture grant`() = runBlocking {
        val receipt = installJoinedPoint()
        database.withTransaction {
            database.locationPurposeDao().insertRetentionAuthority(AmbientLocationRetentionAuthorityIntegrity.create(
                "LIVE_AMBIENT", 2, "REVOKED", "retention", 1, 0, 0, BOOT, 100, 2_100))
            database.removeLocationAmbientPurposeInCurrentTransaction()
        }

        val read = read(receipt)
        assertEquals(listOf(captureScope), read.points.map { it.scope })
        assertTrue(read.gaps.isEmpty())
        assertTrue(RadioLocationContextResolver.resolve(listOf(radio(ambientScope)), read.points).isEmpty())
        assertEquals(EVENT, RadioLocationContextResolver.resolve(listOf(radio(captureScope)), read.points)
            .single().locationSourceEventId)
        assertEquals(1L, database.locationSampleDao().countAll())
        assertEquals(SourceBrokerPurpose.SESSION_CAPTURE,
            database.locationPurposeDao().purposes(receipt.sampleId, 2).single().purpose)
    }

    @Test fun `a damaged capture grant cannot invalidate or borrow the valid ambient sibling`() = runBlocking {
        val receipt = installJoinedPoint()
        database.openHelper.writableDatabase.execSQL(
            "UPDATE location_sample_purpose_authority SET retained_proof_checksum = ? " +
                "WHERE sample_id = ? AND purpose = ?",
            arrayOf("0".repeat(64), receipt.sampleId, SourceBrokerPurpose.SESSION_CAPTURE))

        val read = read(receipt)
        assertEquals(listOf(ambientScope), read.points.map { it.scope })
        assertTrue(RadioLocationContextGap.LOCATION_PROVENANCE_UNVERIFIABLE in read.gaps)
        assertTrue(RadioLocationContextResolver.resolve(listOf(radio(captureScope)), read.points).isEmpty())
        assertEquals(1L, database.locationSampleDao().countAll())
    }

    @Test fun `unknown older purpose proof is preserved but never upgraded during a read`() = runBlocking {
        val receipt = installJoinedPoint(sealed = false)
        val saved = database.locationSampleDao().byIds(listOf(receipt.sampleId))
        val originalPurposes = database.locationPurposeDao().purposes(receipt.sampleId, 3)
        val read = read(receipt)

        assertTrue(read.points.isEmpty())
        assertEquals(setOf(RadioLocationContextGap.LOCATION_PROVENANCE_UNVERIFIABLE), read.gaps)
        assertNull(read.historyProblem)
        assertFalse(LocationAcceptedReceiptIntegrity.hasRetainedIntegrity(receipt))
        assertEquals(receipt, database.locationPurposeDao().receipt(receipt.sampleId))
        assertEquals(originalPurposes, database.locationPurposeDao().purposes(receipt.sampleId, 3))
        assertEquals(saved, database.locationSampleDao().byIds(listOf(receipt.sampleId)))
        assertEquals(1L, database.locationSampleDao().countAll())
    }

    @Test fun `dataset erase fences both native purposes while a retained read never deletes or repairs rows`() = runBlocking {
        val receipt = installJoinedPoint()
        database.sourceEvidenceStateDao().updateAfterFullDeletion(0, null, receipt.sourceAdmissionOrdinal, 2_100)
        val read = read(receipt)
        assertTrue(read.points.isEmpty())
        assertEquals(setOf(receipt.sampleId), read.erasedNativeSampleIds)
        assertTrue(read.gaps.isEmpty())
        assertEquals(receipt, database.locationPurposeDao().receipt(receipt.sampleId))
        assertEquals(2, database.locationPurposeDao().purposes(receipt.sampleId, 3).size)
        assertEquals(1L, database.locationSampleDao().countAll())
    }

    @Test fun `retention uses the original uncertain observation time for both retained purposes`() = runBlocking {
        val receipt = installJoinedPoint()
        database.sourceEvidenceStateDao().updateLifecycle(0, 991, 2_100)
        // Its point time is 1,000 ms but its earliest possible time is 990 ms.
        val read = read(receipt)
        assertTrue(read.points.isEmpty())
        assertTrue(read.erasedNativeSampleIds.isEmpty())
        assertTrue(read.gaps.isEmpty())
        assertEquals(1L, database.locationSampleDao().countAll())
        assertEquals(2, database.locationPurposeDao().purposes(receipt.sampleId, 3).size)
    }

    @Test fun `a newer dataset epoch rejects both original grants without borrowing a current scope`() = runBlocking {
        val receipt = installJoinedPoint()
        database.sourceEvidenceStateDao().updateAfterFullDeletion(1, null, 0, 2_100)
        val read = read(receipt)
        assertTrue(read.points.isEmpty())
        assertEquals(setOf(receipt.sampleId), read.erasedNativeSampleIds)
        assertTrue(read.gaps.isEmpty())
        assertEquals(receipt, database.locationPurposeDao().receipt(receipt.sampleId))
        assertEquals(2, database.locationPurposeDao().purposes(receipt.sampleId, 3).size)
        assertEquals(1L, database.locationSampleDao().countAll())
    }

    private suspend fun read(receipt: LocationAcceptedReceiptEntity) = database.withTransaction {
        reader.qualifyInTransaction(listOf(receipt))
    }

    private suspend fun installJoinedPoint(sealed: Boolean = true): LocationAcceptedReceiptEntity {
        database.sourcePolicyDao().insertPolicies(listOf(policy()))
        database.sourcePolicyDao().insertConsentEpochs(listOf(consent(SourceBrokerPurpose.SESSION_CAPTURE),
            consent(SourceBrokerPurpose.AMBIENT_PRODUCT)))
        database.locationPurposeDao().insertRetentionAuthority(AmbientLocationRetentionAuthorityIntegrity.create(
            "LIVE_AMBIENT", 1, "ACTIVE", "retention", 1, 0, 0, BOOT, 0, 0))
        val member = SessionManifestSourceEntity(LOGICAL, 1, LOCATION, SourceBrokerPurpose.SESSION_CAPTURE,
            0, true, 1, outputDestination = SourceDestinationOwnerEntity.DESTINATION_SESSION_LOCATION,
            writerOwner = SourceDestinationOwnerEntity.OWNER_EXISTING_LOCATION_CANONICAL_PIPELINE,
            writerOwnerGeneration = SourceDestinationOwnerEntity.INITIAL_EXISTING_LOCATION_GENERATION,
            writerProjectionId = SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_PROJECTION_ID,
            writerProjectionVersion = SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_PROJECTION_VERSION,
            writerBindingGeneration = SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_BINDING_GENERATION)
        val initialManifest = SessionManifestVersionEntity(LOGICAL, 1, RUN, "MANUAL", 1, 1, 1,
            "MANUAL_FOREGROUND_START", BOOT, 0, 0, "UTC", null, "USER", "")
        val manifest = initialManifest.copy(manifestChecksum = SessionManifestIntegrity.compute(initialManifest, listOf(member)))
        val segmentId = database.sessionSegmentDao().insert(SessionSegment(startTimeMs = 0, endTimeMs = 2_000,
            distanceM = 0f, steps = null, primaryActivity = null, activityConfidence = null, sampleCount = 1,
            source = SegmentSource.USER_CREATED, inferenceVersion = null, createdAt = 0,
            logicalTrackingId = LOGICAL, serviceRunId = RUN))
        assertEquals(1L, segmentId)
        val run = SourceServiceRunEntity(RUN, LOGICAL, "FINALIZED", 1, 1, 0, 0, 0, 2_000, "USER_STOP",
            bootId = BOOT, leaseGeneration = 1, startOrigin = "MANUAL_FOREGROUND_START", runRevision = 1,
            startDeliveryToken = "delivery", startCommandGeneration = 1, preparedManifestRevision = 1,
            preparedIntentRevision = 1, sessionSegmentId = segmentId, runtimeAcknowledgement = "STOP_ACCEPTED",
            androidDeliveryState = "FOREGROUND_ACCEPTED", androidDeliveryUpdatedAtMs = 0,
            startIsUserInitiated = true, presentationAcknowledgement = SourceServiceRunEntity.PRESENTATION_QUIESCED,
            presentationAcknowledgedAtMs = 2_000)
        assertTrue(SessionManifestIntegrity.verify(manifest, listOf(member)))
        assertTrue(SessionManifestIntegrity.hasValidServiceRunTimeline(run, listOf(manifest)))
        database.sourceSessionDao().insertServiceRun(run)
        database.sourceSessionDao().insertManifest(manifest)
        database.sourceSessionDao().insertManifestSources(listOf(member))
        val original = LocationSample(id = 0, timeMs = 1_000, elapsedRealtimeNanos = 10,
            latE7 = 500_000_000, lonE7 = 140_000_000, altitudeM = null, rawGpsAltitudeM = null,
            hAccM = 1_200f, vAccM = null, speedMps = null, speedAccuracyMps = null,
            provider = "fused", quality = SampleQuality.COARSE, motionState = null, policy = null, bucketId = null,
            createdAt = 1_010, receivedElapsedRealtimeNanos = 11, sourceEventId = EVENT,
            sourceSignalId = protectedLocationCanonicalProductIdentity(EVENT), clockDomainId = BOOT, bootClockDomainId = BOOT)
        val id = database.locationSampleDao().insert(original)
        val sample = original.copy(id = id)
        val initialReceipt = LocationAcceptedReceiptEntity(id, EVENT, requireNotNull(sample.sourceSignalId), 1,
            "a".repeat(64), LocationAcceptedReceiptIntegrity.canonicalChecksum(sample), "instance", 1, 0,
            "delivery", 0, 1, SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_PROJECTION_ID,
            SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_PROJECTION_VERSION, 1, "physical", 1,
            "authorization", BOOT, BOOT, 10, 11, 1_000, 1_010, 10, 0)
        val receipt = if (sealed) LocationAcceptedReceiptIntegrity.seal(initialReceipt) else initialReceipt
        database.locationPurposeDao().insertReceiptIfAbsent(receipt)
        val purposes = listOf(
            LocationSamplePurposeAuthorityEntity(id, SourceBrokerPurpose.SESSION_CAPTURE, RUN,
                1, 0, 10, 11, LOGICAL, RUN, 1, 1, 1, null, null, null, 0, "UTC"),
            LocationSamplePurposeAuthorityEntity(id, SourceBrokerPurpose.AMBIENT_PRODUCT, "ambient",
                1, 0, 10, 11, null, null, null, null, null, "ambient", "retention", 1, 0, null))
        for (purpose in purposes) database.locationPurposeDao().insertPurposeIfAbsent(
            if (sealed) LocationAcceptedReceiptIntegrity.seal(purpose, receipt) else purpose)
        return receipt
    }

    private fun policy() = SourcePolicyEntity(policyRevision = 1, sourceKind = LOCATION, enabled = true, qosCode = 1,
        locationMinTimeSeconds = null, locationMinDistanceMeters = null, locationRequiredAccuracyMeters = null,
        capturePersistenceEligible = true, controlPersistenceEligible = false, ambientPersistenceEligible = true,
        captureConsentEpoch = 0, controlConsentEpoch = null, ambientConsentEpoch = 0,
        effectiveBootId = BOOT, effectiveElapsedRealtimeNanos = 0, effectiveWallTimeMs = 0, changeReason = "USER")

    private fun consent(purpose: String) = SourceConsentEpochEntity(LOCATION, purpose, 0, true, true,
        1, BOOT, 0, 0, "USER")

    private fun radio(scope: RadioContextScope) = RadioContextInput(RadioContextSource.CELL, "radio", scope,
        10, 1_000, true)

    private val captureScope = RadioContextScope.Session(SessionContextScope(LOGICAL, RUN, 1, BOOT, BOOT, 0))
    private val ambientScope = RadioContextScope.Ambient(AmbientContextScope(BOOT, BOOT, 0))

    private companion object {
        const val LOCATION = SourceDestinationOwnerEntity.SOURCE_LOCATION
        const val LOGICAL = "logical"
        const val RUN = "run"
        const val BOOT = "boot"
        const val EVENT = "event"
    }
}
