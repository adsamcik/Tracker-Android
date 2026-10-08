package com.adsamcik.tracker.stats.data.repository

import android.app.Application
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.*
import com.adsamcik.tracker.stats.api.repository.*
import io.mockk.mockk
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RetainedLocationContextReaderTest {
    private lateinit var database: AppDatabase
    private lateinit var reader: RetainedLocationContextReader

    @Before fun setup() = runBlocking {
        database = AppDatabase.testDatabase(ApplicationProvider.getApplicationContext<Application>())
        reader = RetainedLocationContextReader(database)
        database.sourceEvidenceStateDao().ensure()
    }
    @After fun cleanup() = database.close()


    @Test fun `all-day Location status reads native saved points without radio or Steps dependencies`() = runBlocking {
        installPoint()
        val read = assertIs<AllDaySourceStatusRead.Location>(statusQuery().read(
            AllDaySourceStatusRequest.Location(HistoryObservationWindow(0, 2_000))))
        assertEquals("event", read.points.single().sourceEventId)
        assertEquals(AllDayReadingTime(1_000, 10), read.points.single().time)
        assertTrue(read.qualificationGaps.isEmpty())
        assertNull(read.problem)
        assertNull(database.sourceEventWalDao().getByEventId("event"))
        assertEquals(1L, database.locationSampleDao().countAll())
    }

    @Test fun `all-day Location status excludes unverifiable points while retaining local history`() = runBlocking {
        val legacy = installPoint(sealed = false)
        database.locationSampleDao().insert(sample().copy(id = 0, sourceEventId = "foreign", sourceSignalId = "foreign"))
        val status = assertIs<AllDaySourceStatusRead.Location>(statusQuery().read(
            AllDaySourceStatusRequest.Location(HistoryObservationWindow(0, 2_000))))
        assertTrue(status.points.isEmpty())
        assertEquals(2L, database.locationSampleDao().countAll())
        val context: Application = ApplicationProvider.getApplicationContext()
        val history = assertIs<HistoryRangeRead.Page>(rangeReaderForRoomTest(database, context).read(
            HistoryRangeRequest(HistoryRange(0, 2_000, "UTC", setOf(HistorySource.LOCATION),
                setOf(HistoryOrigin.LOCAL), setOf(HistoryPurpose.BACKGROUND)))))
        assertTrue(history.rows.flatMap { it.sections }.filterIsInstance<HistorySection.Location>()
            .flatMap { it.points }.any { it.sampleId == legacy.sampleId })
    }

    @Test fun `all-day Location status keeps the historical grant when collection is off`() = runBlocking {
        val receipt = installPoint()
        database.sourcePolicyDao().insertPolicies(listOf(policy().copy(policyRevision = 2,
            ambientPersistenceEligible = false, enabled = false, ambientConsentEpoch = 1)))
        val query = statusQuery()
        assertEquals("event", assertIs<AllDaySourceStatusRead.Location>(query.read(
            AllDaySourceStatusRequest.Location(HistoryObservationWindow(0, 2_000)))).points.single().sourceEventId)
        database.openHelper.writableDatabase.execSQL("UPDATE location_sample SET lat_e7 = 1 WHERE id = ?", arrayOf(receipt.sampleId))
        val changed = assertIs<AllDaySourceStatusRead.Location>(query.read(
            AllDaySourceStatusRequest.Location(HistoryObservationWindow(0, 2_000))))
        assertTrue(changed.points.isEmpty())
        assertTrue(RadioLocationContextGap.LOCATION_PROVENANCE_UNVERIFIABLE in changed.qualificationGaps)
        assertEquals(1L, database.locationSampleDao().countAll())
    }

    @Test fun `all-day Location status obeys retention and full-clear epoch boundaries`() = runBlocking {
        installPoint()
        val query = statusQuery()
        database.sourceEvidenceStateDao().updateLifecycle(0, 1_000, 2_000)
        assertTrue(assertIs<AllDaySourceStatusRead.Location>(query.read(
            AllDaySourceStatusRequest.Location(HistoryObservationWindow(0, 2_000)))).points.isEmpty())
        database.sourceEvidenceStateDao().updateAfterFullDeletion(1, null, 1, 2_001)
        val afterClear = assertIs<AllDaySourceStatusRead.Location>(query.read(
            AllDaySourceStatusRequest.Location(HistoryObservationWindow(0, 2_000))))
        assertTrue(afterClear.points.isEmpty())
        assertEquals(1L, afterClear.snapshot.collectedDataEpoch)
        assertEquals(1L, database.locationSampleDao().countAll())
    }

    @Test fun `an imported copy cannot hide independently valid native all-day Location status`() = runBlocking {
        val receipt = installPoint()
        database.locationFileIdentityDao().insertIfAbsent(LocationFileIdentityEntity("imported-copy", receipt.sampleId, true, "unknown"))
        database.locationFileIdentityDao().recordArchive(LocationFileArchiveEntity("a".repeat(64),
            LocationFileArchiveEntity.FORMAT, LocationFileArchiveEntity.VERSION, 0, 1))
        database.locationFileIdentityDao().recordArchivePoints(listOf(LocationFileArchivePointEntity("a".repeat(64), 0, "imported-copy", receipt.sampleId)))
        val read = assertIs<AllDaySourceStatusRead.Location>(statusQuery().read(
            AllDaySourceStatusRequest.Location(HistoryObservationWindow(0, 2_000))))
        assertEquals("event", read.points.single().sourceEventId)
        assertTrue(read.qualificationGaps.isEmpty())
    }

    @Test fun `all-day native status rejects malformed evidence without changing saved data`() = runBlocking {
        val receipt = installPoint()
        val request = AllDaySourceStatusRequest.Location(HistoryObservationWindow(0, 2_000))
        val query = statusQuery()
        assertEquals("event", assertIs<AllDaySourceStatusRead.Location>(query.read(request))
            .points.single().sourceEventId)
        val evidence = requireNotNull(database.sourceEvidenceStateDao().get())
        val savedPoints = database.locationSampleDao().byIds(listOf(receipt.sampleId))
        val columns = listOf("revision" to evidence.revision,
            "collected_data_epoch" to evidence.collectedDataEpoch,
            "retained_from_ms" to evidence.retainedFromMs,
            "deleted_source_event_high_water_ordinal" to evidence.deletedSourceEventHighWaterOrdinal,
            "updated_at_ms" to evidence.updatedAtMs)
        for ((column, originalValue) in columns) {
            database.openHelper.writableDatabase.execSQL(
                "UPDATE source_evidence_state SET $column = -1 WHERE id = 1")
            val malformed = requireNotNull(database.sourceEvidenceStateDao().get())
            assertEquals(HistoryProblemReason.INTEGRITY_UNAVAILABLE,
                assertIs<AllDaySourceStatusRead.Unavailable>(query.read(request)).reason)
            assertEquals(savedPoints, database.locationSampleDao().byIds(listOf(receipt.sampleId)))
            assertEquals(malformed, database.sourceEvidenceStateDao().get())
            database.openHelper.writableDatabase.execSQL(
                "UPDATE source_evidence_state SET $column = ? WHERE id = 1", arrayOf(originalValue))
            assertEquals("event", assertIs<AllDaySourceStatusRead.Location>(query.read(request))
                .points.single().sourceEventId)
        }
        assertEquals(evidence, database.sourceEvidenceStateDao().get())
    }

    @Test fun `ordinary saved qualification keeps coordinates without optional accuracy but radio still requires it`() = runBlocking {
        val receipt = installPoint(savedSample = sample().copy(hAccM = null, speedMps = 3f))
        val saved = database.withTransaction { reader.qualifySavedInTransaction(listOf(receipt)) }
        assertEquals(1, saved.samples.size)
        assertNull(saved.samples.single().sample.hAccM)
        assertEquals(3f, saved.samples.single().sample.speedMps)
        assertIs<RadioContextScope.Ambient>(saved.samples.single().scope)
        assertTrue(saved.gaps.isEmpty())
        assertTrue(database.withTransaction { reader.qualifyInTransaction(listOf(receipt)) }.points.isEmpty())
        assertEquals(1L, database.locationSampleDao().countAll())
    }

    @Test fun `all-day status uses ordinary saved qualification without an accuracy requirement`() = runBlocking {
        installPoint(savedSample = sample().copy(hAccM = null))
        val read = assertIs<AllDaySourceStatusRead.Location>(statusQuery().read(
            AllDaySourceStatusRequest.Location(HistoryObservationWindow(0, 2_000))))
        assertEquals("event", read.points.single().sourceEventId)
        assertTrue(read.qualificationGaps.isEmpty())
        assertNull(read.problem)
    }

    @Test fun `invalid optional accuracy does not erase other original saved measurements`() = runBlocking {
        val receipt = installPoint(savedSample = sample().copy(hAccM = -1f, altitudeM = -12f))
        val saved = database.withTransaction { reader.qualifySavedInTransaction(listOf(receipt)) }
        assertEquals(-12f, saved.samples.single().sample.altitudeM)
        assertEquals(-1f, saved.samples.single().sample.hAccM)
        assertTrue(database.withTransaction { reader.qualifyInTransaction(listOf(receipt)) }.points.isEmpty())
    }

    @Test fun `shared saved qualification does not relax original content or epoch authority`() = runBlocking {
        val receipt = installPoint(savedSample = sample().copy(hAccM = null))
        assertEquals(1, database.withTransaction { reader.qualifySavedInTransaction(listOf(receipt)) }.samples.size)
        database.openHelper.writableDatabase.execSQL("UPDATE location_sample SET lon_e7 = 1 WHERE id = ?", arrayOf(receipt.sampleId))
        val changed = database.withTransaction { reader.qualifySavedInTransaction(listOf(receipt)) }
        assertTrue(changed.samples.isEmpty())
        assertTrue(RadioLocationContextGap.LOCATION_PROVENANCE_UNVERIFIABLE in changed.gaps)
        assertTrue(changed.erasedNativeSampleIds.isEmpty())
        assertEquals(1L, database.locationSampleDao().countAll())
    }

    private fun statusQuery() = RoomAllDaySourceStatusQuery(database, mockk(), mockk(), mockk(),
        kotlinx.coroutines.Dispatchers.Unconfined)

    @Test fun `retained ambient coarse point works without raw WAL or an active session`() = runBlocking {
        installPoint()
        val result = database.withTransaction { reader.readInTransaction(listOf(radio())) }
        assertEquals(1, result.points.size)
        assertEquals(1_200f, result.points.single().horizontalAccuracyMeters)
        assertEquals(emptySet(), result.gaps)
        assertNull(database.sourceEventWalDao().getByEventId("event"))
        val joined = RadioLocationContextResolver.resolve(listOf(radio()), result.points)
        assertEquals("event", joined.single().locationSourceEventId)
    }

    @Test fun `turning recording off preserves old grant but changing the canonical point invalidates context`() = runBlocking {
        val receipt = installPoint()
        database.sourcePolicyDao().insertPolicies(listOf(policy().copy(policyRevision = 2,
            ambientPersistenceEligible = false, enabled = false, ambientConsentEpoch = 1)))
        assertEquals(1, database.withTransaction { reader.readInTransaction(listOf(radio())) }.points.size)
        database.openHelper.writableDatabase.execSQL("UPDATE location_sample SET lat_e7 = 1 WHERE id = ?", arrayOf(receipt.sampleId))
        val changed = database.withTransaction { reader.readInTransaction(listOf(radio())) }
        assertTrue(changed.points.isEmpty())
        assertTrue(RadioLocationContextGap.LOCATION_PROVENANCE_UNVERIFIABLE in changed.gaps)
        // Ordinary saved data remains available. This read-only API never deletes or repairs it.
        assertEquals(1L, database.locationSampleDao().countAll())
    }

    @Test fun `legacy blank receipt and imported point never gain native enrichment authority`() = runBlocking {
        val legacy = installPoint(sealed = false)
        val read = database.withTransaction { reader.readInTransaction(listOf(radio())) }
        assertTrue(read.points.isEmpty())
        assertTrue(RadioLocationContextGap.LOCATION_PROVENANCE_UNVERIFIABLE in read.gaps)
        assertEquals(0, requireNotNull(database.locationPurposeDao().receipt(legacy.sampleId)).retainedProofVersion)
        val imported = sample().copy(id = 0, sourceEventId = "foreign", sourceSignalId = "foreign")
        database.locationSampleDao().insert(imported)
        assertTrue(database.withTransaction { reader.readInTransaction(listOf(radio())) }.points.isEmpty())
        assertEquals(2L, database.locationSampleDao().countAll())
    }

    @Test fun `retention and full-clear epoch fence context even while old coordinate rows remain`() = runBlocking {
        installPoint()
        database.sourceEvidenceStateDao().updateLifecycle(0, 1_000, 2_000)
        assertTrue(database.withTransaction { reader.readInTransaction(listOf(radio())) }.points.isEmpty())
        database.sourceEvidenceStateDao().updateAfterFullDeletion(1, null, 1, 2_001)
        assertTrue(database.withTransaction { reader.readInTransaction(listOf(radio())) }.points.isEmpty())
        assertEquals(1L, database.locationSampleDao().countAll())
    }

    @Test fun `wrong boot or capture purpose cannot borrow an ambient point`() = runBlocking {
        installPoint()
        val differentBoot = radio().copy(scope = RadioContextScope.Ambient(AmbientContextScope("other", "other", 0)))
        assertTrue(database.withTransaction { reader.readInTransaction(listOf(differentBoot)) }.points.isEmpty())
        val capture = radio().copy(scope = RadioContextScope.Session(SessionContextScope("logical", "run", 1, "boot", "boot", 0)))
        val points = database.withTransaction { reader.readInTransaction(listOf(capture)) }.points
        assertTrue(RadioLocationContextResolver.resolve(listOf(capture), points).isEmpty())
    }

    @Test fun `dependency overflow reports a gap instead of an incomplete Location snapshot`() = runBlocking {
        val receipt = installPoint()
        val result = database.withTransaction { reader.qualifyInTransaction(List(MAX_CONTEXT_POINTS + 1) { receipt }) }
        assertTrue(result.points.isEmpty())
        assertEquals(setOf(RadioLocationContextGap.LOCATION_DEPENDENCY_LIMIT), result.gaps)
    }

    @Test fun `optional run dependency limit preserves authenticated native erase IDs`() = runBlocking {
        val receipt = installPoint()
        installOptionalRunOverflow(receipt.sampleId)
        database.sourceEvidenceStateDao().updateAfterFullDeletion(0, null, 1, 2_000)
        val highWater = database.withTransaction { reader.qualifyInTransaction(listOf(receipt)) }
        assertTrue(highWater.points.isEmpty())
        assertEquals(setOf(RadioLocationContextGap.LOCATION_DEPENDENCY_LIMIT), highWater.gaps)
        assertEquals(setOf(receipt.sampleId), highWater.erasedNativeSampleIds)
        assertEquals(1L, database.locationSampleDao().countAll())
    }

    @Test fun `optional run dependency limit preserves stale dataset erase IDs`() = runBlocking {
        val receipt = installPoint()
        installOptionalRunOverflow(receipt.sampleId)
        database.sourceEvidenceStateDao().updateAfterFullDeletion(1, null, 0, 2_000)
        val result = database.withTransaction { reader.qualifyInTransaction(listOf(receipt)) }
        assertTrue(result.points.isEmpty())
        assertEquals(setOf(RadioLocationContextGap.LOCATION_DEPENDENCY_LIMIT), result.gaps)
        assertEquals(setOf(receipt.sampleId), result.erasedNativeSampleIds)
        assertEquals(1L, database.locationSampleDao().countAll())
    }

    @Test fun `optional run dependency limit cannot borrow erase authority from blank receipt`() = runBlocking {
        val receipt = installPoint(sealed = false)
        installOptionalRunOverflow(receipt.sampleId)
        database.sourceEvidenceStateDao().updateAfterFullDeletion(1, null, 1, 2_000)
        val result = database.withTransaction { reader.qualifyInTransaction(listOf(receipt)) }
        assertTrue(result.points.isEmpty())
        assertEquals(setOf(RadioLocationContextGap.LOCATION_DEPENDENCY_LIMIT), result.gaps)
        assertTrue(result.erasedNativeSampleIds.isEmpty())
        assertEquals(1L, database.locationSampleDao().countAll())
    }

    @Test fun `optional run dependency limit cannot borrow erase authority from corrupt or mismatched receipt`() = runBlocking {
        val original = installPoint()
        installOptionalRunOverflow(original.sampleId)
        database.sourceEvidenceStateDao().updateAfterFullDeletion(1, null, 1, 2_000)
        database.openHelper.writableDatabase.execSQL(
            "UPDATE location_accepted_receipt SET retained_proof_checksum = ? WHERE sample_id = ?",
            arrayOf("0".repeat(64), original.sampleId))
        val corrupt = requireNotNull(database.locationPurposeDao().receipt(original.sampleId))
        val corruptResult = database.withTransaction { reader.qualifyInTransaction(listOf(corrupt)) }
        assertEquals(setOf(RadioLocationContextGap.LOCATION_DEPENDENCY_LIMIT), corruptResult.gaps)
        assertTrue(corruptResult.erasedNativeSampleIds.isEmpty())
        database.openHelper.writableDatabase.execSQL(
            "UPDATE location_accepted_receipt SET retained_proof_checksum = ? WHERE sample_id = ?",
            arrayOf(original.retainedProofChecksum, original.sampleId))
        database.openHelper.writableDatabase.execSQL(
            "UPDATE location_sample SET lat_e7 = ? WHERE id = ?", arrayOf(500_000_001, original.sampleId))
        val mismatchedResult = database.withTransaction { reader.qualifyInTransaction(listOf(original)) }
        assertEquals(setOf(RadioLocationContextGap.LOCATION_DEPENDENCY_LIMIT), mismatchedResult.gaps)
        assertTrue(mismatchedResult.erasedNativeSampleIds.isEmpty())
        assertEquals(500_000_001, database.locationSampleDao().byIds(listOf(original.sampleId)).single().latE7)
    }

    @Test fun `optional SQLite read failure keeps authenticated erase IDs and its History problem`() = runBlocking {
        val receipt = installPoint()
        database.sourceEvidenceStateDao().updateAfterFullDeletion(0, null, 1, 2_000)
        // The exact receipt/sample/state remain readable; only the optional policy query fails.
        database.openHelper.writableDatabase.execSQL("DROP TABLE source_policy")
        val result = database.withTransaction { reader.qualifyInTransaction(listOf(receipt)) }
        assertTrue(result.points.isEmpty())
        assertEquals(setOf(receipt.sampleId), result.erasedNativeSampleIds)
        assertEquals(setOf(RadioLocationContextGap.LOCATION_AUTHORITY_UNAVAILABLE), result.gaps)
        assertEquals(HistoryProblemReason.STORAGE_UNAVAILABLE, result.historyProblem)
        assertEquals(1L, database.locationSampleDao().countAll())
    }

    @Test fun `optional purpose shape failure keeps authenticated erase IDs and its History problem`() = runBlocking {
        val receipt = installPoint()
        database.sourceEvidenceStateDao().updateAfterFullDeletion(0, null, 1, 2_000)
        // Room reads the existing purpose row through its validating constructor.
        database.openHelper.writableDatabase.execSQL(
            "UPDATE location_sample_purpose_authority SET effective_until_elapsed_nanos = effective_from_elapsed_nanos WHERE sample_id = ?",
            arrayOf(receipt.sampleId))
        val result = database.withTransaction { reader.qualifyInTransaction(listOf(receipt)) }
        assertTrue(result.points.isEmpty())
        assertEquals(setOf(receipt.sampleId), result.erasedNativeSampleIds)
        assertEquals(setOf(RadioLocationContextGap.LOCATION_AUTHORITY_UNAVAILABLE), result.gaps)
        assertEquals(HistoryProblemReason.INTEGRITY_UNAVAILABLE, result.historyProblem)
        assertEquals(1L, database.locationSampleDao().countAll())
    }

    @Test fun `missing older Location proof is not reported as a History read failure`() = runBlocking {
        val receipt = installPoint(sealed = false)
        val result = database.withTransaction { reader.qualifyInTransaction(listOf(receipt)) }
        assertTrue(result.points.isEmpty())
        assertTrue(result.erasedNativeSampleIds.isEmpty())
        assertEquals(setOf(RadioLocationContextGap.LOCATION_PROVENANCE_UNVERIFIABLE), result.gaps)
        assertNull(result.historyProblem)
    }

    /** Optional capture metadata deliberately lacks grants; it only exercises the real bounded run lookup. */
    private suspend fun installOptionalRunOverflow(sampleId: Long) {
        repeat(101) { index ->
            database.locationPurposeDao().insertPurposeIfAbsent(LocationSamplePurposeAuthorityEntity(
                sampleId, SourceBrokerPurpose.SESSION_CAPTURE, "context-limit:$index", 1, 0, 10, 11,
                "logical:$index", "run:$index", index + 1L, 1, 1, null, null, null, 0, "UTC"))
        }
    }

    private suspend fun installPoint(sealed: Boolean = true, savedSample: LocationSample = sample()): LocationAcceptedReceiptEntity {
        database.sourcePolicyDao().insertPolicies(listOf(policy()))
        database.sourcePolicyDao().insertConsentEpochs(listOf(SourceConsentEpochEntity(1, SourceBrokerPurpose.AMBIENT_PRODUCT,
            0, true, true, 1, "boot", 0, 0, "USER")))
        database.locationPurposeDao().insertRetentionAuthority(AmbientLocationRetentionAuthorityIntegrity.create(
            "LIVE_AMBIENT", 1, "ACTIVE", "retention", 1, 0, 0, "boot", 0, 0))
        val id = database.locationSampleDao().insert(savedSample)
        val sample = savedSample.copy(id = id)
        val initial = LocationAcceptedReceiptEntity(id, "event", requireNotNull(sample.sourceSignalId), 1, "a".repeat(64),
            LocationAcceptedReceiptIntegrity.canonicalChecksum(sample), "instance", 1, 0, "delivery", 0, 1,
            SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_PROJECTION_ID,
            SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_PROJECTION_VERSION, 1, "physical", 1, "authorization",
            "boot", "boot", 10, 11, 1_000, 1_010, 10, 0)
        val receipt = if (sealed) LocationAcceptedReceiptIntegrity.seal(initial) else initial
        val initialPurpose = LocationSamplePurposeAuthorityEntity(id, SourceBrokerPurpose.AMBIENT_PRODUCT,
            "ambient", 1, 0, 10, 11, null, null, null, null, null, "ambient", "retention", 1, 0, null)
        val purpose = if (sealed) LocationAcceptedReceiptIntegrity.seal(initialPurpose, receipt) else initialPurpose
        database.locationPurposeDao().insertReceiptIfAbsent(receipt)
        database.locationPurposeDao().insertPurposeIfAbsent(purpose)
        return receipt
    }

    private fun sample() = LocationSample(id = 0, timeMs = 1_000, elapsedRealtimeNanos = 10,
        latE7 = 500_000_000, lonE7 = 140_000_000, altitudeM = null, rawGpsAltitudeM = null,
        hAccM = 1_200f, vAccM = null, speedMps = null, speedAccuracyMps = null,
        provider = "fused", quality = SampleQuality.COARSE, motionState = null, policy = null, bucketId = null,
        createdAt = 1_010, receivedElapsedRealtimeNanos = 11, sourceEventId = "event",
        sourceSignalId = protectedLocationCanonicalProductIdentity("event"), clockDomainId = "boot", bootClockDomainId = "boot")

    private fun policy() = SourcePolicyEntity(policyRevision = 1, sourceKind = 1, enabled = true, qosCode = 1,
        locationMinTimeSeconds = null, locationMinDistanceMeters = null, locationRequiredAccuracyMeters = null,
        capturePersistenceEligible = false, controlPersistenceEligible = false, ambientPersistenceEligible = true,
        captureConsentEpoch = null, controlConsentEpoch = null, ambientConsentEpoch = 0,
        effectiveBootId = "boot", effectiveElapsedRealtimeNanos = 0, effectiveWallTimeMs = 0, changeReason = "USER")

    private fun radio() = RadioContextInput(RadioContextSource.CELL, "radio", RadioContextScope.Ambient(
        AmbientContextScope("boot", "boot", 0)), 10, 1_000, true)
}

