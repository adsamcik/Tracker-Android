package com.adsamcik.tracker.stats.data.repository

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.*
import com.adsamcik.tracker.shared.base.time.BootClockDomainProvider
import com.adsamcik.tracker.stats.api.repository.*
import io.mockk.mockk
import kotlin.test.*
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real Room lineage/owner, permission and deletion reads composed with the production pairing API. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomRetainedAmbientRadioContextTest {
    private lateinit var database: AppDatabase

    @Before fun setup() { database = AppDatabase.testDatabase(ApplicationProvider.getApplicationContext<Application>()) }
    @After fun cleanup() = database.close()

    @Test fun `ambient Wi-Fi and Cell qualify without sessions and retain coarse Location context`() = runTest {
        installAuthority(WIFI)
        installAuthority(CELL)
        insert(wifi())
        insert(cell())
        installLocation()
        for (source in RadioContextSource.entries) {
            val result = read(source)
            val observation = result.observations.single()
            assertEquals(RadioContextPurpose.AMBIENT, observation.purpose)
            assertTrue(result.gaps.isEmpty())
            val point = assertIs<OptionalRetainedLocationContext.Located>(observation.location).value
            assertEquals("location", point.sourceEventId)
            assertEquals(1_200f, point.horizontalAccuracyMeters)
        }
        assertEquals(2L, database.locationSampleDao().countAll())
    }

    @Test fun `legacy unchanged callbacks use only their exact independently qualified aggregate owner`() = runTest {
        installAuthority(WIFI)
        installAuthority(CELL)
        val wifiOwner = wifi()
        val cellOwner = cell()
        insert(wifiOwner)
        insert(wifiCoverage(wifiOwner))
        insert(cellOwner)
        insert(cellCoverage(cellOwner))
        for (source in RadioContextSource.entries) {
            val observations = read(source).observations
            assertEquals(2, observations.size)
            assertTrue(observations.all { it.location is OptionalRetainedLocationContext.Unlocated })
            // References extend coverage, not the original native sample count.
            val counts = observations.map { observation -> when (val quality = observation.signalQuality) {
                is RadioContextSignalQuality.Wifi -> quality.value.sampleCount
                is RadioContextSignalQuality.Cell -> quality.value.totalCount
            } }
            assertEquals(listOf(1, 1), counts)
        }
    }

    @Test fun `capture-only purpose cannot lend quality to ambient readings or their owners`() = runTest {
        installAuthority(WIFI, SourceBrokerPurpose.SESSION_CAPTURE)
        installAuthority(CELL, SourceBrokerPurpose.SESSION_CAPTURE)
        val wifiOwner = wifi()
        val cellOwner = cell()
        insert(wifiOwner)
        insert(wifiCoverage(wifiOwner))
        insert(cellOwner)
        insert(cellCoverage(cellOwner))
        for (source in RadioContextSource.entries) {
            val result = read(source)
            assertTrue(result.observations.isEmpty())
            assertTrue(result.gaps.any { it.reason == RadioContextReadGapReason.SOURCE_PROVENANCE_UNVERIFIABLE })
        }
    }

    @Test fun `different consent cannot borrow an otherwise valid ambient owner`() = runTest {
        installAuthority(WIFI)
        installAuthority(CELL)
        val wifiOwner = wifi()
        val cellOwner = cell()
        insert(wifiOwner)
        insert(wifiCoverage(wifiOwner, consentEpoch = 1L))
        insert(cellOwner)
        insert(cellCoverage(cellOwner, consentEpoch = 1L))
        for (source in RadioContextSource.entries) {
            val result = read(source)
            assertEquals(1, result.observations.size)
            assertEquals(if (source == RadioContextSource.WIFI) "wifi" else "cell", result.observations.single().sourceEventId)
            assertTrue(result.gaps.any { it.reason == RadioContextReadGapReason.SOURCE_PROVENANCE_UNVERIFIABLE })
        }
    }

    @Test fun `deleting an aggregate prevents an unchanged reference from reviving it`() = runTest {
        installAuthority(WIFI)
        installAuthority(CELL)
        val wifiOwner = wifi()
        val cellOwner = cell()
        insert(wifiOwner)
        insert(wifiCoverage(wifiOwner))
        insert(cellOwner)
        insert(cellCoverage(cellOwner))
        database.ambientWifiFactDao().insertReplayFootprints(listOf(AmbientWifiFactIntegrity.createReplayFootprint(
            AmbientWifiReplayFootprintEntity.KIND_LOCAL_FACT, wifiOwner.logicalFactId, 1L, 0L, 1L, 2_800L)))
        database.ambientCellFactDao().insertReplayFootprints(listOf(AmbientCellFactIntegrity.createReplayFootprint(
            AmbientCellReplayFootprintEntity.KIND_LOCAL_FACT, cellOwner.logicalFactId, 1L, 0L, 1L, 2_800L)))
        for (source in RadioContextSource.entries) {
            val result = read(source)
            assertTrue(result.observations.isEmpty())
            assertTrue(result.gaps.any { it.reason == RadioContextReadGapReason.RETENTION_BOUNDARY })
        }
    }

    @Test fun `a newer reading cannot retain a pruned owner's samples or an old data epoch`() = runTest {
        installAuthority(WIFI)
        installAuthority(CELL)
        val wifiOwner = wifi()
        val cellOwner = cell()
        insert(wifiOwner)
        insert(wifiCoverage(wifiOwner))
        insert(cellOwner)
        insert(cellCoverage(cellOwner))
        database.sourceEvidenceStateDao().updateLifecycle(0L, 2_200L, 2_800L)
        for (source in RadioContextSource.entries) assertTrue(read(source).observations.isEmpty())
        database.sourceEvidenceStateDao().updateAfterFullDeletion(1L, null, 2L, 2_900L)
        for (source in RadioContextSource.entries) assertTrue(read(source).observations.isEmpty())
    }

    @Test fun `native effective corrections survive the current retention cutoff with their lineage`() = runTest {
        installAuthority(WIFI)
        installAuthority(CELL)
        val wifiOriginal = wifi()
        val cellOriginal = cell()
        insert(wifiOriginal)
        insert(cellOriginal)
        val wifiUnsigned = wifiOriginal.copy(semanticRevision = 2, supersedesSemanticRevision = 1,
            mutationId = AmbientWifiFactIntegrity.mutationId(wifiOriginal.logicalFactId, 2),
            coverageStartTimeMs = 2_400, observedWallTimeMs = 2_500, appliedAtMs = 2_600)
        val wifiCurrent = wifiUnsigned.copy(effectChecksum = AmbientWifiFactIntegrity.effectChecksum(wifiUnsigned))
        val cellUnsigned = cellOriginal.copy(semanticRevision = 2, supersedesSemanticRevision = 1,
            mutationId = AmbientCellFactIntegrity.mutationId(cellOriginal.logicalFactId, 2),
            coverageStartTimeMs = 2_400, observedWallTimeMs = 2_500, appliedAtMs = 2_600)
        val cellCurrent = cellUnsigned.copy(effectChecksum = AmbientCellFactIntegrity.effectChecksum(cellUnsigned))
        // Replace only fixture cursors; the original revisions remain the required lineage.
        database.ambientWifiFactDao().deleteLocalCursors(listOf(wifiOriginal.logicalFactId))
        database.ambientCellFactDao().deleteLocalCursors(listOf(cellOriginal.logicalFactId))
        insert(wifiCurrent)
        insert(cellCurrent)
        database.sourceEvidenceStateDao().updateLifecycle(0, 2_200, 2_800)
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val wifiRepository = RoomAmbientWifiRepository(database, dispatcher)
        val cellRepository = RoomAmbientCellRepository(database, dispatcher)
        val wifiReads = listOf(wifiRepository.read(AmbientWifiReadRequest(0, 3_000,
            setOf(AmbientWifiOrigin.LOCAL_DEVICE), 10)), wifiRepository.readDay(AmbientWifiDayReadRequest(
            0, "UTC", setOf(AmbientWifiOrigin.LOCAL_DEVICE), 10)))
        val cellReads = listOf(cellRepository.read(AmbientCellReadRequest(0, 3_000,
            setOf(AmbientCellOrigin.LOCAL_DEVICE), 10)), cellRepository.readDay(AmbientCellDayReadRequest(
            0, "UTC", setOf(AmbientCellOrigin.LOCAL_DEVICE), 10)))
        wifiReads.forEach { assertEquals(2L, assertIs<AmbientWifiReadResult.Snapshot>(it).facts.single().semanticRevision) }
        cellReads.forEach { assertEquals(2L, assertIs<AmbientCellReadResult.Snapshot>(it).facts.single().semanticRevision) }
        for (source in RadioContextSource.entries) assertEquals(1, read(source).observations.size)
        assertIs<ExportPortableAmbientWifiResult.Exported>(RoomAmbientWifiPortableTransfer(database, wifiRepository, dispatcher)
            .export(ExportPortableAmbientWifiRequest(2_200, 3_000, setOf(AmbientWifiOrigin.LOCAL_DEVICE))) {
                assertEquals(listOf(1L, 2L), it.facts.map { fact -> fact.semanticRevision })
            })
        assertIs<ExportPortableAmbientCellResult.Exported>(RoomAmbientCellPortableTransfer(database, cellRepository, dispatcher)
            .export(ExportPortableAmbientCellRequest(2_200, 3_000, setOf(AmbientCellOrigin.LOCAL_DEVICE))) {
                assertEquals(listOf(1L, 2L), it.facts.map { fact -> fact.semanticRevision })
            })
    }

    @Test fun `ordinary range and day history keep the historical grant after recording is off`() = runTest {
        installAuthority(WIFI)
        installAuthority(CELL)
        insert(wifi())
        insert(cell())
        for (source in listOf(WIFI, CELL)) database.sourcePolicyDao().insertPolicies(listOf(
            SourcePolicyEntity(2, source, false, 1, null, null, null, false, false, false,
                null, null, 1, "boot", 3_000_000_000, 3_000, "USER")))
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val wifi = RoomAmbientWifiRepository(database, dispatcher)
        val cell = RoomAmbientCellRepository(database, dispatcher)
        assertEquals(1, assertIs<AmbientWifiReadResult.Snapshot>(wifi.read(AmbientWifiReadRequest(
            0, 3_000, setOf(AmbientWifiOrigin.LOCAL_DEVICE), 10))).facts.size)
        assertEquals(1, assertIs<AmbientWifiReadResult.Snapshot>(wifi.readDay(AmbientWifiDayReadRequest(
            0, "UTC", setOf(AmbientWifiOrigin.LOCAL_DEVICE), 10))).facts.size)
        assertEquals(1, assertIs<AmbientCellReadResult.Snapshot>(cell.read(AmbientCellReadRequest(
            0, 3_000, setOf(AmbientCellOrigin.LOCAL_DEVICE), 10))).facts.size)
        assertEquals(1, assertIs<AmbientCellReadResult.Snapshot>(cell.readDay(AmbientCellDayReadRequest(
            0, "UTC", setOf(AmbientCellOrigin.LOCAL_DEVICE), 10))).facts.size)
        for (source in RadioContextSource.entries) assertEquals(1, read(source).observations.size)
    }

    @Test fun `ordinary readers filter the previous epoch before consulting removed recording context`() = runTest {
        installAuthority(WIFI)
        installAuthority(CELL)
        insert(wifi())
        insert(cell())
        database.sourceEvidenceStateDao().updateAfterFullDeletion(1, null, 2, 3_000)
        database.openHelper.writableDatabase.execSQL("DELETE FROM source_demand")
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        assertTrue(assertIs<AmbientWifiReadResult.Snapshot>(RoomAmbientWifiRepository(database, dispatcher).read(
            AmbientWifiReadRequest(0, 3_000, setOf(AmbientWifiOrigin.LOCAL_DEVICE), 10))).facts.isEmpty())
        assertTrue(assertIs<AmbientCellReadResult.Snapshot>(RoomAmbientCellRepository(database, dispatcher).read(
            AmbientCellReadRequest(0, 3_000, setOf(AmbientCellOrigin.LOCAL_DEVICE), 10))).facts.isEmpty())
        for (source in RadioContextSource.entries) assertTrue(read(source).observations.isEmpty())
    }

    @Test fun `ordinary history and paired context reject a changed native cursor independently`() = runTest {
        installAuthority(WIFI)
        installAuthority(CELL)
        insert(wifi())
        insert(cell())
        database.openHelper.writableDatabase.execSQL(
            "UPDATE ambient_wifi_fact_cursor SET latest_effect_checksum = ?", arrayOf("0".repeat(64)))
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        assertEquals(AmbientWifiReadResult.StorageUnavailable, RoomAmbientWifiRepository(database, dispatcher).read(
            AmbientWifiReadRequest(0, 3_000, setOf(AmbientWifiOrigin.LOCAL_DEVICE), 10)))
        val wifi = read(RadioContextSource.WIFI)
        assertTrue(wifi.observations.isEmpty())
        assertTrue(wifi.gaps.any { it.reason == RadioContextReadGapReason.SOURCE_PROVENANCE_UNVERIFIABLE })
        assertEquals(1, read(RadioContextSource.CELL).observations.size)
        assertEquals(1, assertIs<AmbientCellReadResult.Snapshot>(RoomAmbientCellRepository(database, dispatcher).read(
            AmbientCellReadRequest(0, 3_000, setOf(AmbientCellOrigin.LOCAL_DEVICE), 10))).facts.size)
    }

    @Test fun `active native history uses the injected current boot and rejects an old boot`() = runTest {
        installAuthority(WIFI)
        installAuthority(CELL)
        insert(wifi())
        insert(cell())
        database.openHelper.writableDatabase.execSQL("UPDATE provider_registration_generation " +
            "SET status = 'ACTIVE', retired_at_ms = NULL, retired_elapsed_realtime_nanos = NULL")
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val wifiWindow = AmbientWifiReadRequest(0, 3_000, setOf(AmbientWifiOrigin.LOCAL_DEVICE), 10)
        val cellWindow = AmbientCellReadRequest(0, 3_000, setOf(AmbientCellOrigin.LOCAL_DEVICE), 10)
        assertEquals(1, assertIs<AmbientWifiReadResult.Snapshot>(RoomAmbientWifiRepository(database, dispatcher,
            BootClockDomainProvider { "boot" }).read(wifiWindow)).facts.size)
        assertEquals(1, assertIs<AmbientCellReadResult.Snapshot>(RoomAmbientCellRepository(database, dispatcher,
            BootClockDomainProvider { "boot" }).read(cellWindow)).facts.size)
        assertEquals(AmbientWifiReadResult.StorageUnavailable, RoomAmbientWifiRepository(database, dispatcher,
            BootClockDomainProvider { "new-boot" }).read(wifiWindow))
        assertEquals(AmbientCellReadResult.StorageUnavailable, RoomAmbientCellRepository(database, dispatcher,
            BootClockDomainProvider { "new-boot" }).read(cellWindow))
    }

    @Test fun `ordinary native discovery limits report overflow without a truncated snapshot`() = runTest {
        installAuthority(WIFI)
        installAuthority(CELL)
        val wifiOwner = wifi()
        val cellOwner = cell()
        insert(wifiOwner)
        insert(wifiCoverage(wifiOwner))
        insert(cellOwner)
        insert(cellCoverage(cellOwner))
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        assertEquals(AmbientWifiReadResult.DependencyOverflow, RoomAmbientWifiRepository(database, dispatcher).read(
            AmbientWifiReadRequest(0, 3_000, setOf(AmbientWifiOrigin.LOCAL_DEVICE), 1)))
        assertEquals(AmbientCellReadResult.DependencyOverflow, RoomAmbientCellRepository(database, dispatcher).read(
            AmbientCellReadRequest(0, 3_000, setOf(AmbientCellOrigin.LOCAL_DEVICE), 1)))
    }

    private suspend fun read(source: RadioContextSource): RadioLocationContextReadResult.Ready {
        val dispatcher = UnconfinedTestDispatcher()
        // Capture repositories are never invoked: this fixture has no native capture candidates.
        val repository = RoomRadioLocationContextRepository(database, mockk(), mockk(),
            RoomAmbientWifiRepository(database, dispatcher), RoomAmbientCellRepository(database, dispatcher),
            RetainedLocationContextReader(database), dispatcher)
        return assertIs(repository.read(RadioLocationContextRequest(source, 0L, 3_000L)))
    }

    private suspend fun installAuthority(source: Int, purpose: String = SourceBrokerPurpose.AMBIENT_PRODUCT) {
        database.sourceEvidenceStateDao().ensure()
        database.sourcePolicyDao().insertPolicies(listOf(SourcePolicyEntity(1, source, true, 1,
            null, null, null, false, false, true, null, null, 0, "boot", 0, 0, "USER")))
        database.sourcePolicyDao().insertConsentEpochs(listOf(SourceConsentEpochEntity(source,
            SourceBrokerPurpose.AMBIENT_PRODUCT, 0, true, true, 1, "boot", 0, 0, "USER")))
        database.sourceBrokerDao().insertRegistration(ProviderRegistrationGenerationEntity(source, 1, "instance", "owner",
            "boot", "physical", 0, ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND, "process",
            ProviderRegistrationGenerationEntity.STATUS_RETIRED, 0, 0, 0, 0, 3_000, 3_000_000_000, null))
        val demand = demand(source, purpose)
        database.sourceBrokerDao().insertDemands(listOf(demand))
        database.sourceBrokerDao().insertAuthorizations(SourceBrokerAuthorization.rows(source, 1, 1,
            listOf(demand), "boot", 0, 0))
        if (source == WIFI) database.ambientWifiFactDao().insertRetentionAuthority(AmbientWifiRetentionAuthorityIntegrity.create(
            "LIVE_AMBIENT", 1, "ACTIVE", "retention", 1, 0, 0, "boot", 0, 0))
        else database.ambientCellFactDao().insertRetentionAuthority(AmbientCellRetentionAuthorityIntegrity.create(
            "LIVE_AMBIENT", 1, "ACTIVE", "retention", 1, 0, 0, "boot", 0, 0))
    }

    private fun demand(source: Int, purpose: String = SourceBrokerPurpose.AMBIENT_PRODUCT) = SourceDemandEntity(
        demandId = "demand-$source", consumerId = "consumer-$source", sourceKind = source, purpose = purpose,
        logicalTrackingId = "capture".takeIf { purpose == SourceBrokerPurpose.SESSION_CAPTURE },
        serviceRunId = "run".takeIf { purpose == SourceBrokerPurpose.SESSION_CAPTURE },
        manifestRevision = 1L.takeIf { purpose == SourceBrokerPurpose.SESSION_CAPTURE },
        lifecycleLeaseGeneration = 1L.takeIf { purpose == SourceBrokerPurpose.SESSION_CAPTURE },
        sourcePolicyRevision = 1L, consentEpoch = 0, persistenceEligible = true, qosCode = 1,
        maximumAgeMs = 60_000, desiredLatencyMs = 0, requestedBootId = "boot", requestedElapsedRealtimeNanos = 0,
        requestedAtMs = 0, status = "ACTIVE", retireBootId = null, retireElapsedRealtimeNanos = null, retiredAtMs = null,
        liveAmbientRetentionPolicyId = "retention".takeIf { purpose == SourceBrokerPurpose.AMBIENT_PRODUCT },
        liveAmbientRetentionApprovalRevision = 1L.takeIf { purpose == SourceBrokerPurpose.AMBIENT_PRODUCT })

    private fun wifi(): AmbientWifiFactRevisionEntity {
        val delivery = "a".repeat(64)
        val id = AmbientWifiFactIntegrity.logicalFactId(delivery, 0, 0, 0)
        val unsigned = AmbientWifiFactRevisionEntity(AmbientWifiFactRevisionEntity.WRITER_ID,
            AmbientWifiFactRevisionEntity.WRITER_VERSION, 1, id, 1, null, AmbientWifiFactIntegrity.mutationId(id, 1),
            AmbientWifiFactRevisionEntity.FACT_KIND_AGGREGATE, null, null, "wifi", 1, "b".repeat(64), "c".repeat(64),
            delivery, "instance", 1, 1, "physical", 1, SourceBrokerAuthorization.fingerprint(listOf(demand(WIFI))),
            SourceBrokerPurpose.MASK_AMBIENT_PRODUCT, 1, 0, "retention", 1, 0, 0, "boot", "UTC", 0, 0, 86_400_000,
            1_000_000_000, 1_100_000_000, 1_200_000_000, 1_900, 2_000, 1, "COMPLETE", 1, 1, 0, 0, 0,
            -40, -40, -40, 0, null, "0".repeat(64), 2_100)
        return unsigned.copy(effectChecksum = AmbientWifiFactIntegrity.effectChecksum(unsigned))
    }

    private fun cell(): AmbientCellFactRevisionEntity {
        val delivery = "d".repeat(64)
        val id = AmbientCellFactIntegrity.logicalFactId(delivery, 0, 0, 0)
        val unsigned = AmbientCellFactRevisionEntity(AmbientCellFactRevisionEntity.WRITER_ID,
            AmbientCellFactRevisionEntity.WRITER_VERSION, 1, id, 1, null, AmbientCellFactIntegrity.mutationId(id, 1),
            AmbientCellFactRevisionEntity.FACT_KIND_AGGREGATE, null, null, "cell", 1, "b".repeat(64), "c".repeat(64),
            delivery, "instance", 1, 1, "physical", 1, SourceBrokerAuthorization.fingerprint(listOf(demand(CELL))),
            SourceBrokerPurpose.MASK_AMBIENT_PRODUCT, 1, 0, "retention", 1, 0, 0, "boot", "UTC", 0, 0, 86_400_000,
            1_000_000_000, 1_100_000_000, 1_200_000_000, 1_900, 2_000, 1, "COMPLETE", "COMPLETE",
            1, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 1, 0, 0, null, "0".repeat(64), 2_100)
        return unsigned.copy(effectChecksum = AmbientCellFactIntegrity.effectChecksum(unsigned))
    }

    private fun wifiCoverage(owner: AmbientWifiFactRevisionEntity, consentEpoch: Long = 0): AmbientWifiFactRevisionEntity {
        val delivery = "e".repeat(64)
        val id = AmbientWifiFactIntegrity.logicalFactId(delivery, consentEpoch, 0, 0)
        val unsigned = owner.copy(logicalFactId = id, mutationId = AmbientWifiFactIntegrity.mutationId(id, 1),
            sourceEventId = "wifi-later", sourceAdmissionOrdinal = 2, sourceDeliveryIdentity = delivery,
            factKind = AmbientWifiFactRevisionEntity.FACT_KIND_COVERAGE_ONLY, aggregateOwnerLogicalFactId = owner.logicalFactId,
            aggregateOwnerSemanticRevision = 1, ambientConsentEpoch = consentEpoch, observedIntervalStartNanos = 2_000_000_000,
            observedElapsedNanos = 2_100_000_000, receivedElapsedNanos = 2_200_000_000, coverageStartTimeMs = 2_400,
            observedWallTimeMs = 2_500, appliedAtMs = 2_600, observationCount = null, twoPointFourGhzCount = null,
            fiveGhzCount = null, sixGhzCount = null, otherBandCount = null, strongestSignalDbm = null,
            weakestSignalDbm = null, signalSumDbm = null)
        return unsigned.copy(effectChecksum = AmbientWifiFactIntegrity.effectChecksum(unsigned))
    }

    private fun cellCoverage(owner: AmbientCellFactRevisionEntity, consentEpoch: Long = 0): AmbientCellFactRevisionEntity {
        val delivery = "f".repeat(64)
        val id = AmbientCellFactIntegrity.logicalFactId(delivery, consentEpoch, 0, 0)
        val unsigned = owner.copy(logicalFactId = id, mutationId = AmbientCellFactIntegrity.mutationId(id, 1),
            sourceEventId = "cell-later", sourceAdmissionOrdinal = 2, sourceDeliveryIdentity = delivery,
            factKind = AmbientCellFactRevisionEntity.FACT_KIND_COVERAGE_ONLY, aggregateOwnerLogicalFactId = owner.logicalFactId,
            aggregateOwnerSemanticRevision = 1, ambientConsentEpoch = consentEpoch, observedIntervalStartNanos = 2_000_000_000,
            observedElapsedNanos = 2_100_000_000, receivedElapsedNanos = 2_200_000_000, coverageStartTimeMs = 2_400,
            observedWallTimeMs = 2_500, appliedAtMs = 2_600, observationCount = null, registeredObservationCount = null,
            gsmCount = null, cdmaCount = null, wcdmaCount = null, tdscdmaCount = null, lteCount = null, nrCount = null,
            qualityUnknownCount = null, qualityNoneOrUnknownCount = null, qualityPoorCount = null,
            qualityModerateCount = null, qualityGoodCount = null, qualityGreatCount = null)
        return unsigned.copy(effectChecksum = AmbientCellFactIntegrity.effectChecksum(unsigned))
    }

    private suspend fun insert(fact: AmbientWifiFactRevisionEntity) {
        database.ambientWifiFactDao().insertRevision(fact)
        database.ambientWifiFactDao().insertCursor(AmbientWifiFactCursorEntity(fact.writerId, fact.writerVersion, fact.logicalFactId,
            fact.semanticRevision, fact.mutationId, fact.effectChecksum, fact.sourceAdmissionOrdinal, fact.collectedDataEpoch,
            fact.scopeDeletionGeneration, 1, fact.appliedAtMs))
    }
    private suspend fun insert(fact: AmbientCellFactRevisionEntity) {
        database.ambientCellFactDao().insertRevision(fact)
        database.ambientCellFactDao().insertCursor(AmbientCellFactCursorEntity(fact.writerId, fact.writerVersion, fact.logicalFactId,
            fact.semanticRevision, fact.mutationId, fact.effectChecksum, fact.sourceAdmissionOrdinal, fact.collectedDataEpoch,
            fact.scopeDeletionGeneration, 1, fact.appliedAtMs))
    }

    private suspend fun installLocation() {
        database.sourcePolicyDao().insertPolicies(listOf(SourcePolicyEntity(1, 1, true, 1, null, null, null,
            false, false, true, null, null, 0, "boot", 0, 0, "USER")))
        database.sourcePolicyDao().insertConsentEpochs(listOf(SourceConsentEpochEntity(1, SourceBrokerPurpose.AMBIENT_PRODUCT,
            0, true, true, 1, "boot", 0, 0, "USER")))
        database.locationPurposeDao().insertRetentionAuthority(AmbientLocationRetentionAuthorityIntegrity.create(
            "LIVE_AMBIENT", 1, "ACTIVE", "retention", 1, 0, 0, "boot", 0, 0))
        val original = LocationSample(id = 0, timeMs = 1_900, elapsedRealtimeNanos = 1_000_000_000,
            latE7 = 500_000_000, lonE7 = 140_000_000, altitudeM = null, rawGpsAltitudeM = null, hAccM = 1_200f,
            vAccM = null, speedMps = null, speedAccuracyMps = null, provider = "fused", quality = SampleQuality.COARSE,
            motionState = null, policy = null, bucketId = null, createdAt = 2_000, receivedElapsedRealtimeNanos = 1_200_000_000,
            sourceEventId = "location", sourceSignalId = protectedLocationCanonicalProductIdentity("location"),
            clockDomainId = "boot", bootClockDomainId = "boot")
        val id = database.locationSampleDao().insert(original)
        val sample = original.copy(id = id)
        val receipt = LocationAcceptedReceiptIntegrity.seal(LocationAcceptedReceiptEntity(id, "location", requireNotNull(sample.sourceSignalId),
            3, "a".repeat(64), LocationAcceptedReceiptIntegrity.canonicalChecksum(sample), "instance", 1, 0, "location-delivery", 0, 1,
            SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_PROJECTION_ID,
            SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_PROJECTION_VERSION, 1, "physical", 1, "authorization",
            "boot", "boot", 1_000_000_000, 1_200_000_000, 1_900, 2_000, 1, 0))
        val purpose = LocationAcceptedReceiptIntegrity.seal(LocationSamplePurposeAuthorityEntity(id,
            SourceBrokerPurpose.AMBIENT_PRODUCT, "location-ambient", 1, 0, 1_000_000_000, 1_000_000_001,
            null, null, null, null, null, "location-ambient", "retention", 1, 0, null), receipt)
        database.locationPurposeDao().insertReceiptIfAbsent(receipt)
        database.locationPurposeDao().insertPurposeIfAbsent(purpose)
        // Wi-Fi needs a bracket. Preserve the second actual endpoint rather than interpolate it.
        val second = sample.copy(id = 0, timeMs = 2_000, elapsedRealtimeNanos = 1_100_000_000,
            sourceEventId = "location-after", sourceSignalId = protectedLocationCanonicalProductIdentity("location-after"))
        val secondId = database.locationSampleDao().insert(second)
        val secondReceipt = LocationAcceptedReceiptIntegrity.seal(receipt.copy(sampleId = secondId, sourceEventId = "location-after",
            sourceSignalId = requireNotNull(second.sourceSignalId), sourceAdmissionOrdinal = 4, sourceSequence = 1,
            deliveryIdentity = "location-after-delivery",
            canonicalContentChecksum = LocationAcceptedReceiptIntegrity.canonicalChecksum(second.copy(id = secondId)),
            observedElapsedRealtimeNanos = 1_100_000_000, observedWallTimeMs = 2_000))
        database.locationPurposeDao().insertReceiptIfAbsent(secondReceipt)
        database.locationPurposeDao().insertPurposeIfAbsent(LocationAcceptedReceiptIntegrity.seal(purpose.copy(sampleId = secondId,
            effectiveFromElapsedNanos = 1_100_000_000, effectiveUntilElapsedNanos = 1_100_000_001), secondReceipt))
    }

    private companion object { const val WIFI = SourceDestinationOwnerEntity.SOURCE_WIFI; const val CELL = SourceDestinationOwnerEntity.SOURCE_CELL }
}
