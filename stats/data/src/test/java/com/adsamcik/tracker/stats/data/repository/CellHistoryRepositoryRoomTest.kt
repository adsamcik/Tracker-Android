package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.dao.CellCapturedFactDao

import com.adsamcik.tracker.shared.base.database.dao.SourceSessionDao

import android.app.Application
import com.adsamcik.tracker.shared.base.database.data.AmbientCellFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellFactCursorEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellFactIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellChildEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellRetentionAuthorityIntegrity
import com.adsamcik.tracker.stats.api.repository.HistoryRange
import com.adsamcik.tracker.stats.api.repository.HistoryPurpose
import kotlinx.coroutines.Dispatchers
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.CellCapturedSelectedDeletionResult
import com.adsamcik.tracker.shared.base.database.deleteSelectedCapturedCellFactsInTransaction
import com.adsamcik.tracker.shared.base.database.PortableCapturedCellEntryV1
import com.adsamcik.tracker.shared.base.database.ExportPortableCapturedCellResult
import com.adsamcik.tracker.shared.base.database.ExportPortableCapturedCellRequest
import com.adsamcik.tracker.shared.base.database.RoomExportPortableCapturedCell
import com.adsamcik.tracker.shared.base.database.ImportPortableCapturedCellRequest
import com.adsamcik.tracker.shared.base.database.ImportPortableCapturedCellResult
import com.adsamcik.tracker.shared.base.database.PortableCapturedCellRunV1
import com.adsamcik.tracker.shared.base.database.PortableCellImportReceipt
import com.adsamcik.tracker.shared.base.database.PortableCellIdentityKind
import com.adsamcik.tracker.shared.base.database.PortableCellOpaqueIdentity
import com.adsamcik.tracker.shared.base.database.ReadLocalPortableCapturedCellResult
import com.adsamcik.tracker.shared.base.database.RoomImportPortableCapturedCell
import com.adsamcik.tracker.shared.base.database.RoomReadLocalPortableCapturedCell
import com.adsamcik.tracker.shared.base.database.data.AcquisitionPlanRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedFactCursorEntity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedFactRevisionIntegrity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedDeletedRunEntity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedEntryDeletionReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.CELL_REFRESH_OUTCOME_CALLBACK
import com.adsamcik.tracker.shared.base.database.data.LEGACY_CELL_WAL_PAYLOAD_VERSION
import com.adsamcik.tracker.shared.base.database.data.CELL_WAL_PAYLOAD_VERSION
import com.adsamcik.tracker.shared.base.database.data.CellCapturedChildEntity
import com.adsamcik.tracker.shared.base.database.data.CellChildChecksumInput
import com.adsamcik.tracker.shared.base.database.data.RadioChildOwner
import com.adsamcik.tracker.shared.base.database.data.RadioChildPurpose
import com.adsamcik.tracker.shared.base.database.data.RadioObservationChildIntegrity
import com.adsamcik.tracker.shared.base.database.data.CellProviderDeliveryIdentityFact
import com.adsamcik.tracker.shared.base.database.data.CellWalPayload
import com.adsamcik.tracker.shared.base.database.data.CellWalPayloadCodec
import com.adsamcik.tracker.shared.base.database.data.CellWalPayloadObservation
import com.adsamcik.tracker.shared.base.database.data.canonicalCellProviderDeliveryIdentity
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceWriterGenerationContract
import com.adsamcik.tracker.shared.base.database.data.SourceDesiredPlanEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductLaneExecutionAuthority
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import com.adsamcik.tracker.shared.base.time.BootClockDomainProvider
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.shared.model.tracking.TrackingPurpose
import com.adsamcik.tracker.shared.model.tracking.TrackingSource
import com.adsamcik.tracker.stats.api.repository.CellHistoryCause
import com.adsamcik.tracker.stats.api.repository.CellHistoryOrigin
import com.adsamcik.tracker.stats.api.repository.CellHistoryPage
import com.adsamcik.tracker.stats.api.repository.CellHistoryProductState
import com.adsamcik.tracker.stats.api.repository.CellHistoryQuery
import com.adsamcik.tracker.stats.api.repository.CellHistoryRangePage
import com.adsamcik.tracker.stats.api.repository.CellHistoryRangeRequest
import com.adsamcik.tracker.stats.api.repository.CellHistoryRangeScope
import com.adsamcik.tracker.stats.api.repository.CellHistoryRangeUnavailableReason
import com.adsamcik.tracker.stats.api.repository.CellHistoryStructuralDay
import com.adsamcik.tracker.stats.api.repository.CellHistoryStructuralDayCompleteness
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.HistoryMeasurement
import com.adsamcik.tracker.stats.api.repository.HistoryOrigin
import com.adsamcik.tracker.shared.base.database.data.ImportedCellChildEntity
import com.adsamcik.tracker.shared.base.database.data.PortableRadioChildIntegrity
import com.adsamcik.tracker.stats.api.repository.LocalCellHistoryIdentity
import com.adsamcik.tracker.stats.api.repository.LocalCellHistorySelection
import com.adsamcik.tracker.stats.api.repository.MaterializedProofState
import com.adsamcik.tracker.stats.api.repository.SourceEvidenceIdentity
import com.adsamcik.tracker.stats.api.repository.SourceFactIdentity
import com.adsamcik.tracker.stats.api.repository.SourceFactLookupRequest
import com.adsamcik.tracker.stats.api.repository.SourceFactLookupResult
import com.adsamcik.tracker.stats.api.repository.SourceAwareHistoryPageUnavailableReason
import com.adsamcik.tracker.stats.api.repository.SourceProofCompleteness
import com.adsamcik.tracker.stats.api.repository.SourceProofCoverage
import com.adsamcik.tracker.stats.api.repository.SourceProofKey
import com.adsamcik.tracker.stats.api.repository.SourceProofLineage
import com.adsamcik.tracker.stats.api.repository.SourceProofReason
import com.adsamcik.tracker.stats.api.repository.SourceProofScope
import com.adsamcik.tracker.stats.api.repository.SourceProofWriterIdentity
import com.adsamcik.tracker.stats.api.value.EpochMs
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertFailsWith
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@Suppress("LargeClass")
class CellHistoryRepositoryRoomTest {
	private lateinit var database: AppDatabase

	@Before
	fun setUp() {
		val application = ApplicationProvider.getApplicationContext<Application>()
		database = AppDatabase.testDatabase(application)
	}

    @Test fun `canonical dual-purpose Cell has one physical head and chooses independently qualified capture`() = runTest {
        val group = joinedCanonicalCellGroup(perReadingGroup(buildGroup(970, 1, setOf(0))))
        persist(listOf(group)); persistPerReadingChildren(group); persistCanonicalCellBackground(group)
        val range = canonicalCellRange(group)
        database.withTransaction {
            val reader = canonicalCellReader()
            val heads = reader.candidates(HistorySource.CELL, range, null)
            heads.size shouldBe 1
            heads.single().physicalEventId shouldBe group.runs.single().facts.first().sourceEventId
            val read = reader.qualify(HistorySource.CELL, heads, range,
                measuredCapturedRadioFixture(database, cell = repository { true }))
            read.rows.size shouldBe 1
            read.rows.values.single().purpose shouldBe HistoryPurpose.CAPTURE
            (read.rows.values.single().measurement as HistoryMeasurement.Cell).tower.registered shouldBe true
            read.problems shouldBe emptySet()
        }
    }

    @Test fun `canonical Cell falls back to valid background when only capture is erased`() = runTest {
        val group = joinedCanonicalCellGroup(perReadingGroup(buildGroup(971, 1, setOf(0))))
        persist(listOf(group)); persistPerReadingChildren(group); persistCanonicalCellBackground(group)
        val run = group.runs.single()
        database.sourceDeletionFenceDao().insertIfAbsent(SourceDeletionFenceEntity.createLogicalServiceRun(
            CELL_SOURCE, SourceBrokerPurpose.SESSION_CAPTURE, group.session.logicalTrackingId,
            run.run.serviceRunId, 1L, 0L, run.segment.endTimeMs + 1L))
        val range = canonicalCellRange(group)
        database.withTransaction {
            val reader = canonicalCellReader()
            val heads = reader.candidates(HistorySource.CELL, range, null)
            heads.size shouldBe 1
            val read = reader.qualify(HistorySource.CELL, heads, range,
                measuredCapturedRadioFixture(database, cell = repository { true }))
            read.rows.size shouldBe 1
            read.rows.values.single().purpose shouldBe HistoryPurpose.BACKGROUND
        }
    }

    @Test fun `canonical capture-only Cell never borrows its valid background purpose`() = runTest {
        val group = joinedCanonicalCellGroup(perReadingGroup(buildGroup(972, 1, setOf(0))))
        persist(listOf(group)); persistPerReadingChildren(group); persistCanonicalCellBackground(group)
        val run = group.runs.single()
        database.sourceDeletionFenceDao().insertIfAbsent(SourceDeletionFenceEntity.createLogicalServiceRun(
            CELL_SOURCE, SourceBrokerPurpose.SESSION_CAPTURE, group.session.logicalTrackingId,
            run.run.serviceRunId, 1L, 0L, run.segment.endTimeMs + 1L))
        val range = canonicalCellRange(group).copy(purposes = setOf(HistoryPurpose.CAPTURE))
        database.withTransaction {
            val reader = canonicalCellReader()
            val heads = reader.candidates(HistorySource.CELL, range, null)
            heads.size shouldBe 1
            reader.qualify(HistorySource.CELL, heads, range,
                measuredCapturedRadioFixture(database, cell = repository { true })).rows shouldBe emptyMap()
        }
    }

    @Test fun `canonical changed Cell child tuple remains a separate qualified measurement`() = runTest {
        val group = joinedCanonicalCellGroup(perReadingGroup(buildGroup(973, 1, setOf(0))))
        persist(listOf(group)); persistPerReadingChildren(group); persistCanonicalCellBackground(group)
        val original = database.radioObservationChildDao().cellCurrentBackgroundChildrenForFactIds(
            listOf(canonicalAmbientCellId(group.runs.single())), 2).single()
        val owner = RadioChildOwner(RadioChildPurpose.BACKGROUND, original.writerId, original.writerVersion,
            original.logicalFactId, original.semanticRevision, original.itemIndex)
        val changedIdentity = "LTE:changed-original-detail"
        val checksum = RadioObservationChildIntegrity.cell(CellChildChecksumInput(owner,
            original.providerTimestampNanos, original.observedWallTimeMs, original.wallTimeUncertaintyMs,
            changedIdentity, original.radioType, original.registered, original.signalDbm))
        database.openHelper.writableDatabase.execSQL("UPDATE ambient_cell_child SET tower_identity = ?, content_checksum = ? WHERE logical_fact_id = ?",
            arrayOf<Any?>(changedIdentity, checksum, original.logicalFactId))
        val range = canonicalCellRange(group)
        database.withTransaction {
            val reader = canonicalCellReader()
            val heads = reader.candidates(HistorySource.CELL, range, null)
            heads.size shouldBe 2
            heads.map { it.physicalIdentity }.distinct().size shouldBe 2
            val read = reader.qualify(HistorySource.CELL, heads, range,
                measuredCapturedRadioFixture(database, cell = repository { true }))
            read.rows.size shouldBe 2
            read.rows.values.map { (it.measurement as HistoryMeasurement.Cell).tower.identity }.toSet() shouldBe setOf(null, changedIdentity)
        }
    }

    @Test fun `canonical Cell pages more than one hundred physical heads and their purpose copies without loss`() = runTest {
        val groups = (980..1081).map { joinedCanonicalCellGroup(perReadingGroup(buildGroup(it, 1, setOf(0)))) }
        persist(groups)
        groups.forEach { persistPerReadingChildren(it); persistCanonicalCellBackground(it) }
        val range = HistoryRange(groups.minOf { it.session.startedAtMs },
            groups.maxOf { requireNotNull(it.session.completedAtMs) } + 1L, "UTC",
            setOf(HistorySource.CELL), setOf(HistoryOrigin.LOCAL),
            setOf(HistoryPurpose.CAPTURE, HistoryPurpose.BACKGROUND), com.adsamcik.tracker.stats.api.repository.HistoryBrowseMode.OBSERVATIONS)
        database.withTransaction {
            val reader = canonicalCellReader()
            val first = reader.candidates(HistorySource.CELL, range, null)
            first.size shouldBe 101
            val consumed = first.take(100)
            val firstRead = reader.qualify(HistorySource.CELL, consumed, range,
                measuredCapturedRadioFixture(database, cell = repository { true }))
            firstRead.rows.size shouldBe 100
            val next = reader.candidates(HistorySource.CELL, range, measuredHistoryPosition(consumed.last()))
            next.size shouldBe 101
            val second = next.take(100)
            val secondRead = reader.qualify(HistorySource.CELL, second, range,
                measuredCapturedRadioFixture(database, cell = repository { true }))
            secondRead.rows.size shouldBe 100
            val last = reader.candidates(HistorySource.CELL, range, measuredHistoryPosition(second.last()))
            last.size shouldBe 4
            val finalRead = reader.qualify(HistorySource.CELL, last, range,
                measuredCapturedRadioFixture(database, cell = repository { true }))
            finalRead.rows.size shouldBe 4
            (consumed + second + last).map(::measuredHistoryPosition).distinct().size shouldBe 204
        }
    }

    @Test fun `canonical Cell propagates cancellation from reached real capture qualification`() = runTest {
        val group = joinedCanonicalCellGroup(perReadingGroup(buildGroup(974, 1, setOf(0))))
        persist(listOf(group)); persistPerReadingChildren(group); persistCanonicalCellBackground(group)
        val range = canonicalCellRange(group)
        var reached = false
        var returned = false
        assertFailsWith<CancellationException> {
            database.withTransaction {
                val reader = canonicalCellReader()
                val heads = reader.candidates(HistorySource.CELL, range, null)
                reader.qualify(HistorySource.CELL, heads, range,
                    measuredCapturedRadioFixture(database, cell = repository {
                        reached = true; throw CancellationException("reached capture authority fixture") }))
                returned = true
            }
        }
        reached shouldBe true
        returned shouldBe false
    }

    private fun canonicalCellReader() = MeasuredRadioHistoryReader(database,
        RoomAmbientWifiRepository(database, Dispatchers.Unconfined), RoomAmbientCellRepository(database, Dispatchers.Unconfined))

    private fun canonicalCellRange(group: Group): HistoryRange {
        val fact = group.runs.single().facts.first()
        return HistoryRange(fact.observedWallTimeMs, fact.observedWallTimeMs + 1L, "UTC",
            setOf(HistorySource.CELL), setOf(HistoryOrigin.LOCAL),
            setOf(HistoryPurpose.CAPTURE, HistoryPurpose.BACKGROUND), com.adsamcik.tracker.stats.api.repository.HistoryBrowseMode.OBSERVATIONS)
    }

    /** Same original callback and each purpose's own durable grant/receipt; no provider is activated. */
    private fun joinedCanonicalCellGroup(group: Group) = group.copy(runs = group.runs.map { run ->
        val bg = run.demands.single().copy(demandId = "canonical-bg-${run.run.serviceRunId}",
            consumerId = "canonical-background", purpose = SourceBrokerPurpose.AMBIENT_PRODUCT,
            logicalTrackingId = null, serviceRunId = null, manifestRevision = null, lifecycleLeaseGeneration = null,
            consentEpoch = run.policy.policyRevision, liveAmbientRetentionPolicyId = "canonical-retention",
            liveAmbientRetentionApprovalRevision = run.policy.policyRevision)
        val demands = run.demands + bg
        val grants = run.authorizations.groupBy { it.authorizationRevision }.flatMap { (revision, rows) ->
            val first = rows.first()
            SourceBrokerAuthorization.rows(CELL_SOURCE, requireNotNull(run.provider).registrationGeneration,
                revision, if (first.purposeEligibilityMask == 0L) {
                    emptyList()
                } else {
                    demands
                }, BOOT_ID,
                first.effectiveElapsedRealtimeNanos, first.effectiveWallTimeMs)
        }
        val facts = run.facts.map { original ->
            val grant = grants.first { it.authorizationRevision == original.authorizationRevision }
            val unsigned = original.copy(authorizationFingerprint = grant.authorizationFingerprint,
                purposeEligibilityMask = grant.purposeEligibilityMask)
            val wal = walFor(unsigned)
            val stamped = unsigned.copy(walIntegrityIdentity = wal.integrityIdentity, payloadChecksum = wal.payloadChecksum)
            stamped.copy(effectChecksum = CellCapturedFactRevisionIntegrity.effectChecksum(stamped))
        }
        run.copy(demands = demands, authorizations = grants, facts = facts,
            policy = run.policy.copy(ambientPersistenceEligible = true, ambientConsentEpoch = run.policy.policyRevision),
            cursors = facts.groupBy { it.logicalFactId }.values.map { cursor(it.maxBy { fact -> fact.semanticRevision }) })
    })

    private fun canonicalAmbientCellId(run: BuiltRun): String {
        val fact = run.facts.first { it.factKind == CellCapturedFactRevisionEntity.FACT_KIND_AGGREGATE }
        return AmbientCellFactIntegrity.logicalFactId(fact.sourceDeliveryIdentity, run.policy.policyRevision,
            fact.collectedDataEpoch, 0L, run.demands.single { it.purpose == SourceBrokerPurpose.AMBIENT_PRODUCT }.demandId)
    }

    private suspend fun persistCanonicalCellBackground(group: Group) {
        for (run in group.runs) {
            val bg = run.demands.single { it.purpose == SourceBrokerPurpose.AMBIENT_PRODUCT }
            database.sourcePolicyDao().insertConsentEpochs(listOf(run.consent.copy(
                purpose = SourceBrokerPurpose.AMBIENT_PRODUCT, epoch = run.policy.policyRevision)))
            database.ambientCellFactDao().insertRetentionAuthority(AmbientCellRetentionAuthorityIntegrity.create(
                "LIVE_AMBIENT", run.policy.policyRevision, "ACTIVE", "canonical-retention",
                run.policy.policyRevision, run.policy.policyRevision, 0L, BOOT_ID,
                run.policy.effectiveElapsedRealtimeNanos, run.policy.effectiveWallTimeMs))
            val source = run.facts.filter { it.factKind == CellCapturedFactRevisionEntity.FACT_KIND_AGGREGATE }.maxBy { it.semanticRevision }
            val child = database.radioObservationChildDao().cellCurrentSessionChildrenByFactIds(source.writerProjectionId, source.writerProjectionVersion, listOf(source.logicalFactId), 2).single()
            val date = java.time.Instant.ofEpochMilli(source.observedWallTimeMs).atZone(ZoneId.of(ZONE_ID)).toLocalDate()
            val id = canonicalAmbientCellId(run)
            val unsigned = AmbientCellFactRevisionEntity(writerId = AmbientCellFactRevisionEntity.WRITER_ID,
                writerVersion = AmbientCellFactRevisionEntity.WRITER_VERSION, writerOwnerGeneration = 1L,
                logicalFactId = id, semanticRevision = 1L, supersedesSemanticRevision = null,
                mutationId = AmbientCellFactIntegrity.mutationId(id, 1L), factKind = AmbientCellFactRevisionEntity.FACT_KIND_AGGREGATE,
                aggregateOwnerLogicalFactId = null, aggregateOwnerSemanticRevision = null,
                sourceEventId = source.sourceEventId, sourceAdmissionOrdinal = source.sourceAdmissionOrdinal,
                walIntegrityIdentity = source.walIntegrityIdentity, payloadChecksum = source.payloadChecksum,
                sourceDeliveryIdentity = source.sourceDeliveryIdentity, sourceInstanceId = source.sourceInstanceId,
                registrationGeneration = source.registrationGeneration, configurationRevision = source.configurationRevision,
                physicalConfigurationFingerprint = source.physicalConfigurationFingerprint, authorizationRevision = source.authorizationRevision,
                authorizationFingerprint = source.authorizationFingerprint, purposeEligibilityMask = source.purposeEligibilityMask,
                sourcePolicyRevision = run.policy.policyRevision, ambientConsentEpoch = bg.consentEpoch,
                retentionPolicyId = "canonical-retention", retentionApprovalRevision = run.policy.policyRevision,
                collectedDataEpoch = source.collectedDataEpoch, scopeDeletionGeneration = 0L,
                clockDomainId = source.clockDomainId, storedZoneId = ZONE_ID, structuralEpochDay = date.toEpochDay(),
                structuralDayStartTimeMs = date.atStartOfDay(ZoneId.of(ZONE_ID)).toInstant().toEpochMilli(),
                structuralDayEndTimeMs = date.plusDays(1L).atStartOfDay(ZoneId.of(ZONE_ID)).toInstant().toEpochMilli(),
                observedIntervalStartNanos = source.observedIntervalStartNanos, observedElapsedNanos = source.observedElapsedNanos,
                receivedElapsedNanos = source.receivedElapsedNanos, coverageStartTimeMs = child.observedWallTimeMs - child.wallTimeUncertaintyMs,
                observedWallTimeMs = source.observedWallTimeMs, wallTimeUncertaintyMs = source.wallTimeUncertaintyMs,
                coverageCompleteness = AmbientCellFactRevisionEntity.COMPLETENESS_COMPLETE,
                subscriptionCompleteness = source.subscriptionCompleteness, observationCount = source.observationCount,
                registeredObservationCount = source.registeredObservationCount, gsmCount = source.gsmCount, cdmaCount = source.cdmaCount,
                wcdmaCount = source.wcdmaCount, tdscdmaCount = source.tdscdmaCount, lteCount = source.lteCount, nrCount = source.nrCount,
                qualityUnknownCount = source.qualityUnknownCount, qualityNoneOrUnknownCount = source.qualityNoneOrUnknownCount,
                qualityPoorCount = source.qualityPoorCount, qualityModerateCount = source.qualityModerateCount,
                qualityGoodCount = source.qualityGoodCount, qualityGreatCount = source.qualityGreatCount,
                qualityFlags = source.qualityFlags, qualityConfidence = source.qualityConfidence, effectChecksum = ZERO_SHA,
                appliedAtMs = source.createdAtMs, purposeDemandId = bg.demandId)
            val fact = unsigned.copy(effectChecksum = AmbientCellFactIntegrity.effectChecksum(unsigned))
            database.ambientCellFactDao().insertRevision(fact)
            database.ambientCellFactDao().insertCursor(AmbientCellFactCursorEntity(fact.writerId, fact.writerVersion,
                id, 1L, fact.mutationId, fact.effectChecksum, fact.sourceAdmissionOrdinal, 0L, 0L, 1L, fact.appliedAtMs))
            val owner = RadioChildOwner(RadioChildPurpose.BACKGROUND, fact.writerId, fact.writerVersion, id, 1L, child.itemIndex)
            database.radioObservationChildDao().insertCellBackground(listOf(AmbientCellChildEntity(fact.writerId, fact.writerVersion,
                id, 1L, child.itemIndex, child.providerTimestampNanos, child.observedWallTimeMs, child.wallTimeUncertaintyMs,
                child.towerIdentity, child.radioType, child.registered, child.signalDbm,
                RadioObservationChildIntegrity.cell(CellChildChecksumInput(owner, child.providerTimestampNanos,
                    child.observedWallTimeMs, child.wallTimeUncertaintyMs, child.towerIdentity, child.radioType,
                    child.registered, child.signalDbm)))))
        }
    }

	@After
	fun tearDown() = database.close()


	@Test
	fun `final automatic Cell saved history uses its named binding in organized and measured views`() = runTest {
		val group = withWriterContract(perReadingGroup(buildGroup(995, 1, setOf(0))), 2L, "AUTOMATIC")
		persist(listOf(group), writerBindingGeneration = 2L)
		persistPerReadingChildren(group)
		val entry = (repository { true }.session(group.runs.single().segment.id) as CellHistoryQuery.Found).entry
		entry.observations.isNotEmpty() shouldBe true
		entry.observations.map { it.observedTime.raw } shouldBe group.runs.single().facts.map { it.observedWallTimeMs }.distinct()
		val fact = group.runs.single().facts.maxBy { it.semanticRevision }
		val selected = database.withTransaction { repository { true }.measuredFactInTransaction(fact.logicalFactId, fact.semanticRevision) }
		selected?.observation shouldBe entry.observations.first()
		selected?.children?.isNotEmpty() shouldBe true

	}

	@Test
	fun `saved Cell cannot borrow a final manifest for original facts or a manual mask for automatic capture`() = runTest {
		val group = withWriterContract(perReadingGroup(buildGroup(995, 1, setOf(0))), 2L, "AUTOMATIC")
		persist(listOf(group), writerBindingGeneration = 2L)
		persistPerReadingChildren(group)
		database.openHelper.writableDatabase.execSQL("UPDATE source_product_projection_lane SET capture_mode_mask = 1 WHERE source_kind = ? AND binding_generation = 2", arrayOf<Any?>(CELL_SOURCE))
		val invalidMask = (repository { true }.session(group.runs.single().segment.id) as CellHistoryQuery.Found).entry
		invalidMask.observations shouldBe emptyList()
		database.openHelper.writableDatabase.execSQL("UPDATE source_product_projection_lane SET capture_mode_mask = 3 WHERE source_kind = ? AND binding_generation = 2", arrayOf<Any?>(CELL_SOURCE))
		val fact = group.runs.single().facts.first()
		val changed = fact.copy(writerBindingGeneration = 1L, writerOwnerGeneration = 2L)
		val checksum = CellCapturedFactRevisionIntegrity.effectChecksum(changed)
		database.openHelper.writableDatabase.execSQL("UPDATE cell_captured_fact_revision SET writer_binding_generation = 1, writer_owner_generation = 2, effect_checksum = ? WHERE logical_fact_id = ? AND semantic_revision = ?", arrayOf<Any?>(checksum, fact.logicalFactId, fact.semanticRevision))
		database.openHelper.writableDatabase.execSQL("UPDATE cell_captured_fact_cursor SET writer_owner_generation = 2, latest_effect_checksum = ? WHERE logical_fact_id = ?", arrayOf<Any?>(checksum, fact.logicalFactId))
		(repository { true }.session(group.runs.single().segment.id) as CellHistoryQuery.Found).entry.observations shouldBe emptyList()
	}

	@Test
	fun `native Cell export retains original authority after constructed quality and End operations`() = runTest {
		val group = buildGroup(940, 1, setOf(0))
		persist(listOf(group))
		val run = group.runs.single()
		val originalDemands = database.sourceBrokerDao().demandsByIds(run.demands.map { it.demandId })
		val originalWal = run.facts.map { it.sourceEventId }.distinct().associateWith {
			requireNotNull(database.sourceEventWalDao().getByEventId(it))
		}
		val authority = SourceProductLaneExecutionAuthority { database.inTransaction() }
		val before = (database.withTransaction {
			RoomReadLocalPortableCapturedCell(database, authority).readInTransaction(group.session.logicalTrackingId)
		} as ReadLocalPortableCapturedCellResult.Ready).entry
		val exporter = RoomExportPortableCapturedCell(
			database, authority, UnconfinedTestDispatcher(testScheduler))
		// Only the later quality/End operation metadata is constructed. Original provider,
		// carrier, authorization, demand and complete native entry are existing Room fixtures.
		for ((generation, boot) in listOf(run.run.leaseGeneration + 1L to run.run.bootId,
			run.run.leaseGeneration + 2L to "later-operation-boot")) {
			database.withTransaction {
				database.sourceSessionDao().updateServiceRun(run.run.copy(leaseGeneration = generation,
					bootId = boot, runRevision = run.run.runRevision + generation))
				database.sourceSessionDao().updateSession(group.session.copy(lifecycleLeaseGeneration = generation,
					lifecycleBootId = boot, lifecycleRevision = group.session.lifecycleRevision + generation))
			}
			val exported = mutableListOf<PortableCapturedCellEntryV1>()
			val result = exporter.export(ExportPortableCapturedCellRequest(
				group.session.logicalTrackingId)) {
				database.inTransaction() shouldBe false
				exported += it
			}
			(result is ExportPortableCapturedCellResult.Exported) shouldBe true
			exported shouldBe listOf(before)
		}
		database.sourceBrokerDao().demandsByIds(run.demands.map { it.demandId }) shouldBe originalDemands
		originalWal.forEach { (id, beforeWal) ->
			val afterWal = requireNotNull(database.sourceEventWalDao().getByEventId(id))
			afterWal.copy(payload = beforeWal.payload) shouldBe beforeWal
			afterWal.payload.contentEquals(beforeWal.payload) shouldBe true
		}
	}

	@Test
	fun `native Cell export cannot lend newer demand generation to original authorization`() = runTest {
		val group = buildGroup(941, 1, setOf(0))
		persist(listOf(group))
		val run = group.runs.single()
		database.sourceSessionDao().updateServiceRun(run.run.copy(leaseGeneration = run.run.leaseGeneration + 2L,
			runRevision = run.run.runRevision + 1L))
		database.sourceSessionDao().updateSession(group.session.copy(lifecycleLeaseGeneration = run.run.leaseGeneration + 2L))
		val captureDemand = run.demands.single { it.purpose == SourceBrokerPurpose.SESSION_CAPTURE }
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_demand SET lifecycle_lease_generation = ? WHERE demand_id = ?",
			arrayOf<Any>(run.run.leaseGeneration + 1L, captureDemand.demandId))
		var sinkCalls = 0
		val result = RoomExportPortableCapturedCell(database,
			SourceProductLaneExecutionAuthority { database.inTransaction() }, UnconfinedTestDispatcher(testScheduler))
			.export(ExportPortableCapturedCellRequest(group.session.logicalTrackingId)) {
				sinkCalls++
			}
		(result is ExportPortableCapturedCellResult.Unverifiable) shouldBe true
		sinkCalls shouldBe 0
	}

	@Test
	fun `native Cell export does not ignore exact scope fence after operation changes`() = runTest {
		val group = buildGroup(942, 1, setOf(0))
		persist(listOf(group))
		val run = group.runs.single()
		database.sourceSessionDao().updateServiceRun(run.run.copy(leaseGeneration = run.run.leaseGeneration + 2L,
			runRevision = run.run.runRevision + 1L))
		database.sourceSessionDao().updateSession(group.session.copy(lifecycleLeaseGeneration = run.run.leaseGeneration + 2L))
		database.sourceDeletionFenceDao().insertIfAbsent(SourceDeletionFenceEntity.createLogicalServiceRun(
			CELL_SOURCE, SourceBrokerPurpose.SESSION_CAPTURE, group.session.logicalTrackingId, run.run.serviceRunId,
			1L, 0L, run.segment.endTimeMs + 1L))
		var sinkCalls = 0
		val result = RoomExportPortableCapturedCell(database,
			SourceProductLaneExecutionAuthority { database.inTransaction() }, UnconfinedTestDispatcher(testScheduler))
			.export(ExportPortableCapturedCellRequest(group.session.logicalTrackingId)) {
				sinkCalls++
			}
		// A fence with still-present products is an unfinished cleanup, not a fabricated Deleted result.
		(result is ExportPortableCapturedCellResult.Unverifiable) shouldBe true
		sinkCalls shouldBe 0
	}

	@Test
	fun `measured Cell preserves current revision and good parent beside corrupt sibling in one run`() = runTest {
		val group = perReadingGroup(buildGroup(940, 1, setOf(0)))
		persist(listOf(group))
		persistPerReadingChildren(group)
		val aggregate = group.runs.single().facts.single { it.semanticRevision == 2L }
		val dependent = group.runs.single().facts.single { it.factKind == CellCapturedFactRevisionEntity.FACT_KIND_COVERAGE_ONLY }
		database.openHelper.writableDatabase.execSQL("UPDATE cell_captured_fact_revision SET effect_checksum = ? WHERE logical_fact_id = ?",
			arrayOf<Any?>("f".repeat(64), dependent.logicalFactId))
		val range = measuredCaptureRange(group.session.startedAtMs, requireNotNull(group.session.completedAtMs) + 1L, HistorySource.CELL)
		val result = database.withTransaction {
			val raw = database.radioObservationChildDao().cellMeasuredHistoryCandidates(range.fromInclusiveMs,
				range.toExclusiveMs, listOf("LOCAL"), listOf("CAPTURE"), null, null, null, 100)
			raw.single { it.factId == aggregate.logicalFactId }.factRevision shouldBe 2L
			measuredCapturedRadioFixture(database, cell = repository { true }).qualify(HistorySource.CELL, raw, range)
		}
		result.rows.size shouldBe 1
		result.rows.keys.single().startTimeMs shouldBe aggregate.observedWallTimeMs
		(result.rows.values.single().measurement as HistoryMeasurement.Cell).tower.registered shouldBe true
		result.problems.isNotEmpty() shouldBe true
	}

	@Test
	fun `measured older Cell summaries retain real coverage and include observation exactly at selected start`() = runTest {
		val group = buildGroup(941, 1, setOf(0))
		persist(listOf(group))
		val current = group.runs.single().facts.single { it.semanticRevision == 2L }
		val range = measuredCaptureRange(current.observedWallTimeMs, current.observedWallTimeMs + 1L, HistorySource.CELL)
		val result = database.withTransaction {
			val raw = database.radioObservationChildDao().cellMeasuredHistoryCandidates(range.fromInclusiveMs,
				range.toExclusiveMs, listOf("LOCAL"), listOf("CAPTURE"), null, null, null, 100)
			raw.single { it.factId == current.logicalFactId }.kind shouldBe "captured_cell_summary"
			measuredCapturedRadioFixture(database, cell = repository { true }).qualify(HistorySource.CELL, raw, range)
		}
		val row = result.rows.values.single { (it.measurement as HistoryMeasurement.CellSummary).observation.observedTime.raw == current.observedWallTimeMs }
		row.toExclusiveMs shouldBe current.observedWallTimeMs
		val observation = (row.measurement as HistoryMeasurement.CellSummary).observation
		row.position.startTimeMs shouldBe observation.intervalStartTime.raw
		observation.towers shouldBe emptyList()
		result.problems shouldBe emptySet()
	}

	@Test
	fun `measured Cell later End generation keeps saved towers until exact deletion`() = runTest {
		val group = perReadingGroup(buildGroup(942, 1, setOf(0)))
		persist(listOf(group))
		persistPerReadingChildren(group)
		val run = group.runs.single()
		database.sourceSessionDao().updateServiceRun(run.run.copy(leaseGeneration = run.run.leaseGeneration + 1L,
			runRevision = run.run.runRevision + 1L, bootId = "later-operation"))
		database.sourceSessionDao().updateSession(group.session.copy(lifecycleLeaseGeneration = run.run.leaseGeneration + 1L,
			lifecycleRevision = group.session.lifecycleRevision + 1L, lifecycleBootId = "later-operation"))
		val range = measuredCaptureRange(group.session.startedAtMs, requireNotNull(group.session.completedAtMs) + 1L, HistorySource.CELL)
		suspend fun read() = database.withTransaction {
			val raw = database.radioObservationChildDao().cellMeasuredHistoryCandidates(range.fromInclusiveMs,
				range.toExclusiveMs, listOf("LOCAL"), listOf("CAPTURE"), null, null, null, 100)
			measuredCapturedRadioFixture(database, cell = repository { true }).qualify(HistorySource.CELL, raw, range)
		}
		read().rows.size shouldBe 2
		database.sourceDeletionFenceDao().insertIfAbsent(SourceDeletionFenceEntity.createLogicalServiceRun(
			CELL_SOURCE, SourceBrokerPurpose.SESSION_CAPTURE, group.session.logicalTrackingId,
			run.run.serviceRunId, 1L, 0L, run.segment.endTimeMs + 1L))
		read().rows shouldBe emptyMap()
	}

	@Test
	fun `measured imported Cell detail is suppressed only for an identical independently readable native child`() = runTest {
		val group = perReadingGroup(buildGroup(943, 1, setOf(0)))
		persist(listOf(group))
		persistPerReadingChildren(group)
		val importedChildren = importMeasuredCellChildren(group)
		val range = measuredCaptureRange(group.session.startedAtMs, requireNotNull(group.session.completedAtMs) + 1L,
			HistorySource.CELL, setOf(HistoryOrigin.LOCAL, HistoryOrigin.IMPORTED))
		suspend fun read(selectedRange: com.adsamcik.tracker.stats.api.repository.HistoryRange) = database.withTransaction {
			val raw = database.radioObservationChildDao().cellMeasuredHistoryCandidates(selectedRange.fromInclusiveMs,
				selectedRange.toExclusiveMs, selectedRange.origins.map { it.name }, listOf("CAPTURE"), null, null, null, 100)
			measuredCapturedRadioFixture(database, cell = repository { true }).qualify(HistorySource.CELL, raw, selectedRange)
		}
		val mixed = read(range)
		mixed.problems shouldBe emptySet()
		mixed.rows.size shouldBe 2
		mixed.rows.values.all { it.origins == setOf(HistoryOrigin.LOCAL) } shouldBe true
		val importedOnly = read(range.copy(origins = setOf(HistoryOrigin.IMPORTED)))
		importedOnly.rows.size shouldBe importedChildren.size
		importedOnly.rows.values.all { it.origins == setOf(HistoryOrigin.IMPORTED) } shouldBe true
		val changed = importedChildren.first()
		database.openHelper.writableDatabase.execSQL("UPDATE imported_cell_child SET tower_identity = ?, content_checksum = ? " +
			"WHERE entry_identity = ? AND entry_import_revision = ? AND observation_identity = ? AND item_index = ?",
			arrayOf<Any?>("user-edited-tower", measuredCellChildChecksum(changed.copy(towerIdentity = "user-edited-tower"), 2L),
				changed.entryIdentity, changed.entryImportRevision, changed.observationIdentity, changed.itemIndex))
		val edited = read(range)
		edited.problems shouldBe emptySet()
		edited.rows.size shouldBe 3
		(edited.rows.values.single { it.origins == setOf(HistoryOrigin.IMPORTED) }.measurement as HistoryMeasurement.Cell)
			.tower.identity shouldBe "user-edited-tower"
	}

	@Test
	fun `measured imported Cell keeps later observation when original entry is retention limited`() = runTest {
		val group = perReadingGroup(buildGroup(944, 1, setOf(0)))
		persist(listOf(group))
		persistPerReadingChildren(group)
		importMeasuredCellChildren(group)
		val dependent = group.runs.single().facts.single { it.factKind == CellCapturedFactRevisionEntity.FACT_KIND_COVERAGE_ONLY }
		val entryIdentity = PortableCellOpaqueIdentity.derive(PortableCellIdentityKind.LOGICAL_ENTRY, group.session.logicalTrackingId)
		val original = database.withTransaction {
			repository { true }.measuredImportedEvaluationInTransaction(entryIdentity.value)
		} as com.adsamcik.tracker.shared.base.database.ImportedCellProductEvaluation.Readable
		val later = original.entry.runs.single().observations.single { it.identity.value ==
			PortableCellOpaqueIdentity.derive(PortableCellIdentityKind.OBSERVATION, dependent.logicalFactId).value }
		database.sourceEvidenceStateDao().updateLifecycle(0L, later.coverageStartTimeMs, requireNotNull(group.session.completedAtMs)) shouldBe 1
		val range = measuredCaptureRange(group.session.startedAtMs, requireNotNull(group.session.completedAtMs) + 1L,
			HistorySource.CELL, setOf(HistoryOrigin.IMPORTED))
		val result = database.withTransaction {
			val evaluation = repository { true }.measuredImportedEvaluationInTransaction(entryIdentity.value)
			(evaluation as com.adsamcik.tracker.shared.base.database.ImportedCellProductEvaluation.Readable).retentionLimited shouldBe true
			val raw = database.radioObservationChildDao().cellMeasuredHistoryCandidates(range.fromInclusiveMs,
				range.toExclusiveMs, listOf("IMPORTED"), listOf("CAPTURE"), null, null, null, 100)
			measuredCapturedRadioFixture(database, cell = repository { true }).qualify(HistorySource.CELL, raw, range)
		}
		result.problems shouldBe emptySet()
		result.rows.size shouldBe 1
		(result.rows.values.single().measurement as HistoryMeasurement.Cell).partial shouldBe true
		result.rows.keys.single().startTimeMs shouldBe later.observedTimeMs
	}

	@Test
	fun `imported Cell session name follows the exact native reading without adding a duplicate`() = runTest {
		val group = perReadingGroup(buildGroup(950, 1, setOf(0)))
		persist(listOf(group)); persistPerReadingChildren(group)
		val children = importMeasuredCellChildren(group)
		installCellBundleName(group, children.first().entryIdentity)
		val read = cellBundleNames(group)
		read.rows.size shouldBe children.size
		read.rows.all { it.origins == setOf(HistoryOrigin.LOCAL) } shouldBe true
		read.rows.all { it.sessionNames == listOf("Imported trip") } shouldBe true
		read.problems shouldBe emptySet()
	}

	@Test
	fun `edited imported Cell child cannot lend its name to the original native child`() = runTest {
		val group = perReadingGroup(buildGroup(951, 1, setOf(0)))
		persist(listOf(group)); persistPerReadingChildren(group)
		val children = importMeasuredCellChildren(group)
		installCellBundleName(group, children.first().entryIdentity)
		val changed = children.first().copy(towerIdentity = "LTE:user-edited")
		database.openHelper.writableDatabase.execSQL("UPDATE imported_cell_child SET tower_identity = ?, content_checksum = ? " +
			"WHERE entry_identity = ? AND entry_import_revision = ? AND observation_identity = ? AND item_index = ?",
			arrayOf<Any?>(changed.towerIdentity, measuredCellChildChecksum(changed, 2L), changed.entryIdentity,
				changed.entryImportRevision, changed.observationIdentity, changed.itemIndex))
		val result = cellBundleNames(group)
		result.rows.size shouldBe 3
		val original = result.rows.single { it.origins == setOf(HistoryOrigin.LOCAL) &&
			(it.measurement as HistoryMeasurement.Cell).tower.observedTimeMs == changed.observedWallTimeMs }
		original.sessionNames shouldBe emptyList()
		val edited = result.rows.single { it.origins == setOf(HistoryOrigin.IMPORTED) }
		edited.sessionNames shouldBe listOf("Imported trip")
		(edited.measurement as HistoryMeasurement.Cell).tower.identity shouldBe changed.towerIdentity
		result.problems shouldBe emptySet()
	}

	@Test
	fun `Cell bundle labels the same background leaf through its independently valid original capture`() = runTest {
		val group = joinedCanonicalCellGroup(perReadingGroup(buildGroup(952, 1, setOf(0))))
		persist(listOf(group)); persistPerReadingChildren(group); persistCanonicalCellBackground(group)
		val children = importMeasuredCellChildren(group)
		installCellBundleName(group, children.first().entryIdentity)
		val range = canonicalCellRange(group).copy(purposes = setOf(HistoryPurpose.BACKGROUND))
		val result = database.withTransaction {
			val raw = canonicalCellReader().candidates(HistorySource.CELL, range, null).take(100)
			val capture = measuredCapturedRadioFixture(database, cell = repository { true })
			val rows = canonicalCellReader().qualify(HistorySource.CELL, raw, range, capture).rows.values.toList()
			MeasuredSessionNamesReader(database, capture).decorate(rows, raw, range)
		}
		result.rows.size shouldBe 1
		result.rows.single().purpose shouldBe HistoryPurpose.BACKGROUND
		result.rows.single().sessionNames shouldBe listOf("Imported trip")
		result.problems shouldBe emptySet()
	}

	@Test
	fun `Cell bundle metadata never revives an explicitly erased native capture`() = runTest {
		val group = perReadingGroup(buildGroup(953, 1, setOf(0)))
		persist(listOf(group)); persistPerReadingChildren(group)
		val children = importMeasuredCellChildren(group)
		val range = measuredCaptureRange(group.session.startedAtMs, requireNotNull(group.session.completedAtMs) + 1L,
			HistorySource.CELL, setOf(HistoryOrigin.LOCAL, HistoryOrigin.IMPORTED))
		val keys = database.radioObservationChildDao().cellMeasuredHistoryCandidates(range.fromInclusiveMs,
			range.toExclusiveMs, listOf("LOCAL"), listOf("CAPTURE"), null, null, null, 100)
		val capture = measuredCapturedRadioFixture(database, cell = repository { true })
		database.withTransaction { capture.importedNativeMembership(HistorySource.CELL,
			children.first().entryIdentity, keys, range).positions.size } shouldBe 2
		val run = group.runs.single()
		database.withTransaction {
			database.deleteSelectedCapturedCellFactsInTransaction(
				logicalTrackingId = group.session.logicalTrackingId,
				serviceRunIds = listOf(run.run.serviceRunId),
				expectedCollectedDataEpoch = 0L,
				deletedAtMs = run.segment.endTimeMs + 1L,
			)
		} shouldBe CellCapturedSelectedDeletionResult.Deleted(2, 3, 1)
		database.withTransaction { capture.importedNativeMembership(HistorySource.CELL,
			children.first().entryIdentity, keys, range).positions } shouldBe emptySet()
	}

	@Test
	fun `one corrupt imported Cell parent preserves the valid sibling session name and reading`() = runTest {
		val group = perReadingGroup(buildGroup(954, 1, setOf(0)))
		persist(listOf(group)); persistPerReadingChildren(group)
		val children = importMeasuredCellChildren(group)
		installCellBundleName(group, children.first().entryIdentity)
		val bad = children.first()
		database.openHelper.writableDatabase.execSQL("UPDATE imported_cell_child SET content_checksum = ? " +
			"WHERE entry_identity = ? AND entry_import_revision = ? AND observation_identity = ? AND item_index = ?",
			arrayOf<Any?>("f".repeat(64), bad.entryIdentity, bad.entryImportRevision, bad.observationIdentity, bad.itemIndex))
		val entry = database.withTransaction { repository { true }.measuredImportedEvaluationInTransaction(bad.entryIdentity) }
		(entry is com.adsamcik.tracker.shared.base.database.ImportedCellProductEvaluation.Readable) shouldBe true
		val result = cellBundleNames(group)
		result.rows.size shouldBe 2
		result.rows.all { it.origins == setOf(HistoryOrigin.LOCAL) } shouldBe true
		result.rows.single { (it.measurement as HistoryMeasurement.Cell).tower.observedTimeMs == bad.observedWallTimeMs }
			.sessionNames shouldBe emptyList()
		result.rows.single { (it.measurement as HistoryMeasurement.Cell).tower.observedTimeMs != bad.observedWallTimeMs }
			.sessionNames shouldBe listOf("Imported trip")
		result.problems shouldBe setOf(com.adsamcik.tracker.stats.api.repository.HistoryProblem(
			HistorySource.CELL, com.adsamcik.tracker.stats.api.repository.HistoryProblemReason.INTEGRITY_UNAVAILABLE))
	}

	private suspend fun installCellBundleName(group: Group, entryIdentity: String) {
		database.organizationAnnotationDao().insert(com.adsamcik.tracker.shared.base.database.data.OrganizationAnnotationEntity(
			"imported-cell-name", 1L, 0L, 0L, null, group.session.startedAtMs,
			requireNotNull(group.session.completedAtMs) + 1L, "UTC", "Imported trip", null, false,
			com.adsamcik.tracker.stats.api.repository.OrganizationSourceSelectionCodec.encode(listOf(
				com.adsamcik.tracker.stats.api.repository.OrganizationSourceSelector.CapturedEntry(HistorySource.CELL, entryIdentity)))))
	}

	private suspend fun cellBundleNames(group: Group): MeasuredSessionNamesRead = database.withTransaction {
		val range = measuredCaptureRange(group.session.startedAtMs, requireNotNull(group.session.completedAtMs) + 1L,
			HistorySource.CELL, setOf(HistoryOrigin.LOCAL, HistoryOrigin.IMPORTED))
		val raw = database.radioObservationChildDao().cellMeasuredHistoryCandidates(range.fromInclusiveMs,
			range.toExclusiveMs, range.origins.map { it.name }, listOf("CAPTURE"), null, null, null, 100)
		val capture = measuredCapturedRadioFixture(database, cell = repository { true })
		val read = capture.qualify(HistorySource.CELL, raw, range)
		val names = MeasuredSessionNamesReader(database, capture).decorate(read.rows.values.toList(), raw, range)
		names.copy(problems = names.problems + read.problems)
	}

	private suspend fun importMeasuredCellChildren(group: Group): List<ImportedCellChildEntity> {
		val portable = database.withTransaction {
			RoomReadLocalPortableCapturedCell(database, SourceProductLaneExecutionAuthority { database.inTransaction() })
				.readInTransaction(group.session.logicalTrackingId)
		} as ReadLocalPortableCapturedCellResult.Ready
		RoomImportPortableCapturedCell(database, UnconfinedTestDispatcher()).importEntry(ImportPortableCapturedCellRequest(
			portable.entry, PortableCellImportReceipt("measured-cell", "measured-cell", "measured.trackercell", portable.entry.endTimeMs), 0L))
			.shouldBe(ImportPortableCapturedCellResult.Applied(1L, portable.entry.runs.size,
				portable.entry.runs.sumOf { it.observations.size }))
		val rows = group.runs.flatMap { run -> run.facts.filter { fact -> run.cursors.any {
			it.logicalFactId == fact.logicalFactId && it.latestSemanticRevision == fact.semanticRevision } }.flatMap { fact ->
			val children = database.radioObservationChildDao().cellSessionChildren(fact.writerProjectionId,
				fact.writerProjectionVersion, fact.logicalFactId, fact.semanticRevision, 100)
			children.map { child -> ImportedCellChildEntity(portable.entry.identity.value, 1L,
				PortableCellOpaqueIdentity.derive(PortableCellIdentityKind.PHYSICAL_RUN, fact.serviceRunId).value,
				PortableCellOpaqueIdentity.derive(PortableCellIdentityKind.OBSERVATION, fact.logicalFactId).value,
				child.itemIndex, child.providerTimestampNanos, child.observedWallTimeMs, child.wallTimeUncertaintyMs,
				child.towerIdentity, child.radioType, child.registered, child.signalDbm, "0".repeat(64)).let { row ->
				row.copy(contentChecksum = measuredCellChildChecksum(row, fact.semanticRevision)) } }
		} }
		database.importedRadioChildDao().insertCellSession(rows)
		return rows
	}

	private fun measuredCellChildChecksum(child: ImportedCellChildEntity, semanticRevision: Long): String = PortableRadioChildIntegrity.digest("cell",
		listOf(child.entryIdentity, child.runIdentity, child.observationIdentity),
		listOf(semanticRevision, child.itemIndex,
			child.providerTimestampNanos, child.observedWallTimeMs, child.wallTimeUncertaintyMs, child.towerIdentity,
			child.radioType, child.registered, child.signalDbm))


	@Test
	fun `saved Cell survives later quality End and operation boot generations without rewriting observations`() = runTest {
		val group = buildGroup(930, 1, setOf(0))
		persist(listOf(group))
		val run = group.runs.single()
		val originalDemands = database.sourceBrokerDao().demandsByIds(run.demands.map { it.demandId })
		val originalWal = run.facts.map { it.sourceEventId }.distinct().associateWith {
			requireNotNull(database.sourceEventWalDao().getByEventId(it))
		}
		val original = (repository { true }.session(run.segment.id) as CellHistoryQuery.Found).entry
		original.observations.isNotEmpty() shouldBe true
		for (generation in listOf(run.run.leaseGeneration + 1L, run.run.leaseGeneration + 2L)) {
			database.withTransaction {
				database.sourceSessionDao().updateServiceRun(run.run.copy(leaseGeneration = generation,
					runRevision = run.run.runRevision + generation, bootId = "later-operation-boot"))
				database.sourceSessionDao().updateSession(group.session.copy(lifecycleLeaseGeneration = generation,
					lifecycleBootId = "later-operation-boot", lifecycleRevision = group.session.lifecycleRevision + generation))
			}
			val after = (repository { true }.session(run.segment.id) as CellHistoryQuery.Found).entry
			after.observations shouldBe original.observations
			after.causes shouldBe original.causes
		}
		database.sourceBrokerDao().demandsByIds(run.demands.map { it.demandId }) shouldBe originalDemands
		originalWal.forEach { (id, beforeWal) ->
			val afterWal = requireNotNull(database.sourceEventWalDao().getByEventId(id))
			afterWal.copy(payload = beforeWal.payload) shouldBe beforeWal
			afterWal.payload.contentEquals(beforeWal.payload) shouldBe true
		}
	}

	@Test
	fun `saved Cell cannot borrow newer demand authority and deletion still retracts history`() = runTest {
		val group = buildGroup(931, 1, setOf(0))
		persist(listOf(group))
		val run = group.runs.single()
		database.sourceSessionDao().updateServiceRun(run.run.copy(leaseGeneration = run.run.leaseGeneration + 2L,
			runRevision = run.run.runRevision + 1L))
		database.sourceSessionDao().updateSession(group.session.copy(lifecycleLeaseGeneration = run.run.leaseGeneration + 2L))
		val captureDemand = run.demands.single { it.purpose == SourceBrokerPurpose.SESSION_CAPTURE }
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_demand SET lifecycle_lease_generation = ? WHERE demand_id = ?",
			arrayOf<Any>(run.run.leaseGeneration + 1L, captureDemand.demandId))
		val invalid = (repository { true }.session(run.segment.id) as CellHistoryQuery.Found).entry
		invalid.state shouldBe CellHistoryProductState.FAILED
		invalid.observations shouldBe emptyList()
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_demand SET lifecycle_lease_generation = ? WHERE demand_id = ?",
			arrayOf<Any>(requireNotNull(captureDemand.lifecycleLeaseGeneration), captureDemand.demandId))
		(repository { true }.session(run.segment.id) as CellHistoryQuery.Found).entry.observations.isNotEmpty() shouldBe true
		database.sourceDeletionFenceDao().insertIfAbsent(SourceDeletionFenceEntity.createLogicalServiceRun(
			CELL_SOURCE, SourceBrokerPurpose.SESSION_CAPTURE, group.session.logicalTrackingId,
			run.run.serviceRunId, 1, 0, run.segment.endTimeMs + 1L))
		(repository { true }.session(run.segment.id) as CellHistoryQuery.Found).entry.observations shouldBe emptyList()
	}

	@Test
	fun `missing original Cell Stop never lends a retry clock and preserves partial measured data`() = runTest {
		val group = buildGroup(932, 1, setOf(0))
		persist(listOf(group))
		val run = group.runs.single()
		val original = (repository { true }.session(run.segment.id) as CellHistoryQuery.Found).entry
		val stop = requireNotNull(database.sourceSessionDao().firstStopIntent(group.session.logicalTrackingId))
		val retry = stop.copy(intentRevision = stop.intentRevision + 1L, requestBootId = "retry-boot",
			stopDeadlineBootId = "retry-boot")
		database.sourceSessionDao().insertLifecycleIntent(retry.copy(intentChecksum =
			com.adsamcik.tracker.shared.base.database.data.SessionLifecycleIntegrity.checksum(retry.logicalTrackingId,
				retry.intentRevision, retry.manifestRevision, retry.desiredState, retry.stopReason,
				retry.requestBootId, retry.requestedElapsedRealtimeNanos, retry.stopDeadlineElapsedRealtimeNanos)))
		database.openHelper.writableDatabase.execSQL(
			"DELETE FROM session_lifecycle_intent_version WHERE logical_tracking_id = ? AND intent_revision = ?",
			arrayOf<Any>(stop.logicalTrackingId, stop.intentRevision))
		val after = (repository { true }.session(run.segment.id) as CellHistoryQuery.Found).entry
		after.observations shouldBe original.observations
		(after.causes.contains(CellHistoryCause.ACQUISITION_INCOMPLETE)) shouldBe true
		after.state shouldBe CellHistoryProductState.PARTIAL
	}

	@Test
	fun `paired Cell map reader uses the real captured history snapshot without Location collection`() = runTest {
		val group = buildGroup(911, 1, setOf(0))
		persist(listOf(group))
		var authorityChecks = 0
		val dispatcher = UnconfinedTestDispatcher(testScheduler)
		val paired = RoomRadioLocationContextRepository(database, io.mockk.mockk(), repository(BOOT_ID) {
			authorityChecks += 1
			database.inTransaction()
		}, RoomAmbientWifiRepository(database, dispatcher), RoomAmbientCellRepository(database, dispatcher),
			RetainedLocationContextReader(database), dispatcher)
		val result = kotlin.test.assertIs<com.adsamcik.tracker.stats.api.repository.RadioLocationContextReadResult.Ready>(
			paired.read(com.adsamcik.tracker.stats.api.repository.RadioLocationContextRequest(
				com.adsamcik.tracker.stats.api.repository.RadioContextSource.CELL, 0L, Long.MAX_VALUE)))
		result.observations shouldHaveSize 2
		val observation = result.observations.single { it.sourceEventId == group.runs.single().facts.first().sourceEventId }
		observation.sourceEventId shouldBe group.runs.single().facts.first().sourceEventId
		observation.purpose shouldBe com.adsamcik.tracker.stats.api.repository.RadioContextPurpose.CAPTURE
		observation.signalQuality shouldBe com.adsamcik.tracker.stats.api.repository.RadioContextSignalQuality.Cell(
			com.adsamcik.tracker.stats.api.repository.CellHistorySignalQuality(0, 0, 0, 0, 1, 0))
		observation.location shouldBe com.adsamcik.tracker.stats.api.repository.OptionalRetainedLocationContext.Unlocated(
			com.adsamcik.tracker.stats.api.repository.RadioLocationContextGap.NO_COMPATIBLE_LOCATION)
		result.gaps shouldBe emptySet()
		authorityChecks shouldBe 1
		database.locationSampleDao().countAll() shouldBe 0L
	}

	@Test
	fun `paired Cell map reader retains unchanged deliveries and retracts invalid aggregate owners`() = runTest {
		val group = buildGroup(914, 1, setOf(0))
		persist(listOf(group))
		val dispatcher = UnconfinedTestDispatcher(testScheduler)
		val paired = RoomRadioLocationContextRepository(database, io.mockk.mockk(), repository(BOOT_ID) { database.inTransaction() },
			RoomAmbientWifiRepository(database, dispatcher), RoomAmbientCellRepository(database, dispatcher),
			RetainedLocationContextReader(database), dispatcher)
		val request = com.adsamcik.tracker.stats.api.repository.RadioLocationContextRequest(
			com.adsamcik.tracker.stats.api.repository.RadioContextSource.CELL, 0L, Long.MAX_VALUE)
		val ready = kotlin.test.assertIs<com.adsamcik.tracker.stats.api.repository.RadioLocationContextReadResult.Ready>(paired.read(request))
		ready.observations shouldHaveSize 2
		ready.observations.all { (it.signalQuality as com.adsamcik.tracker.stats.api.repository.RadioContextSignalQuality.Cell)
			.value.totalCount == 1 } shouldBe true
		val run = group.runs.single()
		val owner = run.facts.filter { it.factKind == CellCapturedFactRevisionEntity.FACT_KIND_AGGREGATE }.maxBy { it.semanticRevision }
		val dependent = run.facts.single { it.factKind == CellCapturedFactRevisionEntity.FACT_KIND_COVERAGE_ONLY }
		val unsigned = owner.copy(semanticRevision = owner.semanticRevision + 1L,
			supersedesSemanticRevision = owner.semanticRevision,
			mutationId = CellCapturedFactRevisionIntegrity.mutationId(owner.logicalFactId, owner.semanticRevision + 1L))
		val corrected = unsigned.copy(effectChecksum = CellCapturedFactRevisionIntegrity.effectChecksum(unsigned))
		val dao = database.cellCapturedFactDao()
		database.withTransaction {
			dao.insertRevision(corrected)
			dao.deleteExactCursors(owner.writerProjectionId, owner.writerProjectionVersion, listOf(owner.logicalFactId))
			dao.insertCursor(cursor(corrected))
		}
		val afterCorrection = kotlin.test.assertIs<com.adsamcik.tracker.stats.api.repository.RadioLocationContextReadResult.Ready>(paired.read(request))
		afterCorrection.observations.none { it.sourceEventId == dependent.sourceEventId } shouldBe true
		kotlin.test.assertTrue(afterCorrection.gaps.isNotEmpty())
		database.withTransaction {
			dao.deleteExactCursors(owner.writerProjectionId, owner.writerProjectionVersion, listOf(owner.logicalFactId))
			dao.deleteExactRevisionLineages(owner.writerProjectionId, owner.writerProjectionVersion, listOf(owner.logicalFactId))
		}
		val afterDeletion = kotlin.test.assertIs<com.adsamcik.tracker.stats.api.repository.RadioLocationContextReadResult.Ready>(paired.read(request))
		afterDeletion.observations.none { it.sourceEventId == dependent.sourceEventId } shouldBe true
		kotlin.test.assertTrue(afterDeletion.gaps.isNotEmpty())
	}

	@Test
	fun `paired Cell map reader rejects stale captured epoch and reports source limit explicitly`() = runTest {
		persist(listOf(buildGroup(912, 1, setOf(0)), buildGroup(913, 1, setOf(0))))
		val dispatcher = UnconfinedTestDispatcher(testScheduler)
		val paired = RoomRadioLocationContextRepository(database, io.mockk.mockk(), repository(BOOT_ID) { database.inTransaction() },
			RoomAmbientWifiRepository(database, dispatcher), RoomAmbientCellRepository(database, dispatcher),
			RetainedLocationContextReader(database), dispatcher)
		val request = com.adsamcik.tracker.stats.api.repository.RadioLocationContextRequest(
			com.adsamcik.tracker.stats.api.repository.RadioContextSource.CELL, 0L, Long.MAX_VALUE, 1)
		val limited = kotlin.test.assertIs<com.adsamcik.tracker.stats.api.repository.RadioLocationContextReadResult.Ready>(paired.read(request))
		limited.observations shouldBe emptyList()
		limited.gaps shouldBe setOf(com.adsamcik.tracker.stats.api.repository.RadioContextReadGap(
			com.adsamcik.tracker.stats.api.repository.RadioContextPurpose.CAPTURE,
			com.adsamcik.tracker.stats.api.repository.RadioContextReadGapReason.DEPENDENCY_LIMIT))
		database.sourceEvidenceStateDao().updateLifecycle(1L, null, WALL_BASE_MS)
		val cleared = kotlin.test.assertIs<com.adsamcik.tracker.stats.api.repository.RadioLocationContextReadResult.Ready>(
			paired.read(request.copy(limit = 10)))
		cleared.observations shouldBe emptyList()
		kotlin.test.assertTrue(cleared.gaps.isNotEmpty())
	}

	@Test
	fun `writer admission reverse lookup selects cursor authenticated revision two`() = runTest {
		val run = buildGroup(901, 1, setOf(0)).runs.single()
		val lineage = run.facts.groupBy(CellCapturedFactRevisionEntity::logicalFactId)
			.values.single { revisions -> revisions.size == 2 }
			.sortedBy(CellCapturedFactRevisionEntity::semanticRevision)
		lineage.forEach { database.cellCapturedFactDao().insertRevision(it) }
		database.cellCapturedFactDao().insertCursor(cursor(lineage.last()))

		database.cellCapturedFactDao().currentRevisionsForWriterAdmission(
			SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID,
			SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION,
			lineage.first().sourceAdmissionOrdinal,
			2,
		) shouldBe listOf(lineage.last())
	}

	@Test
	fun `corrected current Cell revision remains queryable through its exact WAL carrier`() = runTest {
		val group = buildGroup(904, 1, setOf(0))
		persist(listOf(group))
		val corrected = group.runs.single().facts.single { fact ->
			fact.factKind == CellCapturedFactRevisionEntity.FACT_KIND_AGGREGATE &&
				fact.semanticRevision == 2L
		}

		val result = RoomSourceFactLookupReader(
			database,
			UnconfinedTestDispatcher(testScheduler),
		).lookup(cellFactLookupRequest(corrected))

		val found = result as SourceFactLookupResult.Found
		found.state.lineage.semanticRevision shouldBe 2L
		found.state.lineage.cursorRevision shouldBe 2L
	}

	@Test
	fun `coverage-only Cell query authenticates its exact current aggregate owner`() = runTest {
		val group = buildGroup(906, 1, setOf(0))
		persist(listOf(group))
		val dependent = group.runs.single().facts.single { fact ->
			fact.factKind == CellCapturedFactRevisionEntity.FACT_KIND_COVERAGE_ONLY
		}

		val result = RoomSourceFactLookupReader(
			database,
			UnconfinedTestDispatcher(testScheduler),
		).lookup(cellFactLookupRequest(dependent))

		(result as SourceFactLookupResult.Found).state.lineage.factIdentity?.value shouldBe
			dependent.logicalFactId
	}

	@Test
	fun `coverage-only Cell query rejects a stale aggregate owner cursor`() = runTest {
		val group = buildGroup(907, 1, setOf(0))
		persist(listOf(group))
		val dependent = group.runs.single().facts.single { fact ->
			fact.factKind == CellCapturedFactRevisionEntity.FACT_KIND_COVERAGE_ONLY
		}
		val owner = group.runs.single().facts
			.filter { it.factKind == CellCapturedFactRevisionEntity.FACT_KIND_AGGREGATE }
			.maxBy(CellCapturedFactRevisionEntity::semanticRevision)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE cell_captured_fact_cursor SET latest_semantic_revision = ? " +
				"WHERE logical_fact_id = ?",
			arrayOf<Any?>(owner.semanticRevision - 1L, owner.logicalFactId),
		)

		RoomSourceFactLookupReader(
			database,
			UnconfinedTestDispatcher(testScheduler),
		).lookup(cellFactLookupRequest(dependent)) shouldBe
			SourceFactLookupResult.NotFound(SourceProofReason.STALE)
	}

	// Keep the forged WAL, revised facts, and cursor together to verify both readers reject the same forged lineage.
	@Suppress("LongMethod")
	@Test
	fun `rehashed Cell fact and cursor cannot replace recorded WAL identity or payload`() = runTest {
		val group = buildGroup(905, 1, setOf(0))
		persist(listOf(group))
		val ownerLineage = group.runs.single().facts
			.filter { it.factKind == CellCapturedFactRevisionEntity.FACT_KIND_AGGREGATE }
			.sortedBy(CellCapturedFactRevisionEntity::semanticRevision)
		val originalWal = requireNotNull(
			database.sourceEventWalDao().getByEventId(ownerLineage.first().sourceEventId),
		)
		val arbitraryPayload = "forged-cell-payload".toByteArray(Charsets.UTF_8)
		val payloadForged = originalWal.copy(
			payload = arbitraryPayload,
			payloadChecksum = originalWal.copy(payload = arbitraryPayload).calculatedPayloadChecksum(),
			integrityIdentity = SourceEventWalEntity.LEGACY_PENDING_CHECKSUM,
		)
		val forgedWal = payloadForged.copy(
			integrityIdentity = payloadForged.calculatedIntegrityIdentity(),
		)
		val forgedLineage = ownerLineage.map { fact ->
			val unsigned = fact.copy(
				walIntegrityIdentity = forgedWal.integrityIdentity,
				payloadChecksum = forgedWal.payloadChecksum,
				effectChecksum = ZERO_SHA,
			)
			unsigned.copy(
				effectChecksum = CellCapturedFactRevisionIntegrity.effectChecksum(unsigned),
			)
		}
		val forgedCurrent = forgedLineage.last()
		database.withTransaction {
			val dao = database.cellCapturedFactDao()
			dao.deleteExactCursors(
				SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID,
				SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION,
				listOf(forgedCurrent.logicalFactId),
			)
			dao.deleteExactRevisionLineages(
				SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID,
				SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION,
				listOf(forgedCurrent.logicalFactId),
			)
			database.openHelper.writableDatabase.execSQL(
				"UPDATE source_event_wal SET payload = ?, payload_checksum = ?, " +
					"integrity_identity = ? WHERE event_id = ?",
				arrayOf<Any?>(
					forgedWal.payload,
					forgedWal.payloadChecksum,
					forgedWal.integrityIdentity,
					forgedWal.eventId,
				),
			)
			forgedLineage.forEach { dao.insertRevision(it) }
			dao.insertCursor(cursor(forgedCurrent))
		}

		val exact = RoomSourceFactLookupReader(
			database,
			UnconfinedTestDispatcher(testScheduler),
		).lookup(cellFactLookupRequest(forgedCurrent))
		(exact as SourceFactLookupResult.Failed).reason shouldBe
			SourceProofReason.INTEGRITY_FAILURE
		val history = repository { true }.session(group.runs.single().segment.id)
		val entry = (history as CellHistoryQuery.Found).entry
		entry.state shouldBe CellHistoryProductState.FAILED
		entry.causes shouldBe setOf(CellHistoryCause.WRITER_PROVENANCE_INVALID)
		entry.observations shouldBe emptyList()
	}

	@Test
	fun `writer admission reverse lookup exposes ambiguous current heads for fail closed handling`() =
		runTest {
			val firstLineage = buildGroup(902, 1, setOf(0)).runs.single().facts
				.groupBy(CellCapturedFactRevisionEntity::logicalFactId)
				.values.single { revisions -> revisions.size == 2 }
				.sortedBy(CellCapturedFactRevisionEntity::semanticRevision)
			val secondLineage = buildGroup(903, 1, setOf(0)).runs.single().facts
				.groupBy(CellCapturedFactRevisionEntity::logicalFactId)
				.values.single { revisions -> revisions.size == 2 }
				.sortedBy(CellCapturedFactRevisionEntity::semanticRevision)
				.map { fact ->
					val unsigned = fact.copy(
						sourceAdmissionOrdinal = firstLineage.first().sourceAdmissionOrdinal,
						effectChecksum = ZERO_SHA,
					)
					unsigned.copy(
						effectChecksum = CellCapturedFactRevisionIntegrity.effectChecksum(unsigned),
					)
				}
			(firstLineage + secondLineage).forEach {
				database.cellCapturedFactDao().insertRevision(it)
			}
			database.cellCapturedFactDao().insertCursor(cursor(firstLineage.last()))
			database.cellCapturedFactDao().insertCursor(cursor(secondLineage.last()))

			database.cellCapturedFactDao().currentRevisionsForWriterAdmission(
				SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID,
				SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION,
				firstLineage.first().sourceAdmissionOrdinal,
				2,
			).size shouldBe 2
		}

	@Test
	fun `selected and recent expand thirty three replacement runs in one Room transaction`() = runTest {
		val group = buildGroup(groupIndex = 1, runCount = 33, factRunIndexes = setOf(0))
		persist(listOf(group))
		var authorityChecks = 0
		val repository = repository {
			authorityChecks += 1
			database.inTransaction()
		}

		val selected = repository.session(group.runs.first().segment.id)
		val recent = repository.recent(1)

		val selectedEntry = (selected as CellHistoryQuery.Found).entry
		val recentEntry = (recent as CellHistoryPage.Available).entries.single()
		repository.detail(requireNotNull(selectedEntry.selection)) shouldBe selected
		selectedEntry.selection.toString() shouldBe
			"LocalCellHistorySelection(identity=LocalCellHistoryIdentity)"
		repository.detail(
			LocalCellHistorySelection(LocalCellHistoryIdentity("f".repeat(64))),
		) shouldBe CellHistoryQuery.NotFound
		selectedEntry shouldBe recentEntry
		selectedEntry.startTime.raw shouldBe group.runs.first().segment.startTimeMs
		selectedEntry.endTime.raw shouldBe group.runs.last().segment.endTimeMs
		selectedEntry.observations shouldHaveSize 2
		selectedEntry.observations.all { it.localSourceEventId != null } shouldBe true
		selectedEntry.state shouldBe CellHistoryProductState.PARTIAL
		selectedEntry.causes shouldBe setOf(
			CellHistoryCause.ACQUISITION_INCOMPLETE,
			CellHistoryCause.SUBSCRIPTION_GROUPING_UNKNOWN,
		)
		val grouped = database.withTransaction {
			repository.selectBySegmentIdsInTransaction(
				listOf(group.runs.first().segment.id, group.runs.last().segment.id),
			)
		} as CellComposedPage.Available
		grouped.entries.single().let { composed ->
			composed.logicalTrackingId shouldBe group.session.logicalTrackingId
			composed.physicalSegmentIds shouldBe group.runs.map { it.segment.id }
			composed.recencyStartTimeMs shouldBe group.runs.last().segment.startTimeMs
			composed.recencySegmentId shouldBe group.runs.last().segment.id
			composed.recencyTieIdentity shouldBe PortableCellOpaqueIdentity.derive(
				PortableCellIdentityKind.PHYSICAL_RUN,
				group.runs.last().run.serviceRunId,
			)
			composed.capturesOnlyCell shouldBe true
			composed.entry shouldBe selectedEntry
		}
		val sourcePage = database.withTransaction {
			repository.recentCellHistoryInTransaction(1)
		} as CellSourceComposedPage.Available
		(sourcePage.entries.single() as CellSourceComposedEntry.Local).let { source ->
			source.group shouldBe grouped.entries.single()
			source.recency.memberStartTimeMs shouldBe source.group.recencyStartTimeMs
			source.recency.memberStartTimeMs shouldBe group.runs.last().segment.startTimeMs
			source.entry.startTime.raw shouldBe group.runs.first().segment.startTimeMs
			source.recency.tieIdentity shouldBe source.group.recencyTieIdentity
		}
		authorityChecks shouldBe 5
	}

	@Test
	fun `public and transactional recent suppress an exact authenticated same-origin duplicate`() =
		runTest {
			val group = buildGroup(groupIndex = 1, runCount = 1, factRunIndexes = setOf(0))
			persist(listOf(group))
			val authority = SourceProductLaneExecutionAuthority { database.inTransaction() }
			val localRead = database.withTransaction {
				RoomReadLocalPortableCapturedCell(database, authority)
					.readInTransaction(group.session.logicalTrackingId)
			}
			check(localRead is ReadLocalPortableCapturedCellResult.Ready) {
				"Local Cell fixture could not be read: $localRead"
			}
			val portable = localRead
			RoomImportPortableCapturedCell(
				database,
				UnconfinedTestDispatcher(testScheduler),
			).importEntry(
				ImportPortableCapturedCellRequest(
					entry = portable.entry,
					receipt = PortableCellImportReceipt(
						jobId = "same-origin-exact",
						entryKey = "same-origin-exact",
						sourceName = "same-origin.trackercell",
						receivedAtMs = portable.entry.endTimeMs,
					),
					expectedCollectedDataEpoch = 0L,
				),
			) shouldBe ImportPortableCapturedCellResult.Applied(
				importRevision = 1L,
				physicalRunCount = portable.entry.runs.size,
				observationCount = portable.entry.runs.sumOf { it.observations.size },
			)
			val repository = repository { database.inTransaction() }

			(repository.recent(10) as CellHistoryPage.Available).entries.single().let { entry ->
				entry.origin shouldBe CellHistoryOrigin.Local
				entry.selection shouldBe LocalCellHistorySelection(
					LocalCellHistoryIdentity(portable.entry.identity.value),
				)
			}
			val sourcePage = database.withTransaction {
				repository.recentCellHistoryInTransaction(10)
			} as CellSourceComposedPage.Available
			val source = sourcePage.entries.single() as CellSourceComposedEntry.Local
			source.group.logicalTrackingId shouldBe group.session.logicalTrackingId
			source.group.physicalSegmentIds shouldBe listOf(group.runs.single().segment.id)
			source.entry.origin shouldBe CellHistoryOrigin.Local
			source.recency.memberStartTimeMs shouldBe group.runs.single().segment.startTimeMs
			source.recency.tieIdentity shouldBe portable.entry.runs.single().identity
			database.withTransaction {
				repository.recentImportedEligibleForSharedHistoryInTransaction(10)
			} shouldBe ImportedHistoryEligiblePage.Available(
				emptyList<CellImportedHistoryEligibleEntry>(),
			)
		}

	@Test
	fun `shared eligible page fails closed for readable unequal local origin collision`() = runTest {
		val source = AppDatabase.testDatabase(
			ApplicationProvider.getApplicationContext<Application>(),
		)
		try {
			val localGroup = buildGroup(groupIndex = 1, runCount = 1, factRunIndexes = setOf(0))
			val importedGroup = buildGroup(groupIndex = 1, runCount = 2, factRunIndexes = setOf(0, 1))
			persist(listOf(localGroup))
			persist(listOf(importedGroup), target = source)
			val portable = source.withTransaction {
				RoomReadLocalPortableCapturedCell(
					source,
					SourceProductLaneExecutionAuthority { source.inTransaction() },
				).readInTransaction(importedGroup.session.logicalTrackingId)
			} as ReadLocalPortableCapturedCellResult.Ready
			RoomImportPortableCapturedCell(
				database,
				UnconfinedTestDispatcher(testScheduler),
			).importEntry(
				ImportPortableCapturedCellRequest(
					entry = portable.entry,
					receipt = PortableCellImportReceipt(
						jobId = "same-origin-unequal",
						entryKey = "same-origin-unequal",
						sourceName = "same-origin-unequal.trackercell",
						receivedAtMs = portable.entry.endTimeMs,
					),
					expectedCollectedDataEpoch = 0L,
				),
			) shouldBe ImportPortableCapturedCellResult.Applied(
				importRevision = 1L,
				physicalRunCount = portable.entry.runs.size,
				observationCount = portable.entry.runs.sumOf { it.observations.size },
			)

			database.withTransaction {
				repository { database.inTransaction() }
					.recentImportedEligibleForSharedHistoryInTransaction(10)
			} shouldBe ImportedHistoryEligiblePage.Unavailable(
				SourceAwareHistoryPageUnavailableReason.SOURCE_INTEGRITY_FAILURE,
			)
		} finally {
			source.close()
		}
	}

	@Test
	fun `later page collision discards earlier eligible Cell entries and selectors`() = runTest {
		val source = AppDatabase.testDatabase(
			ApplicationProvider.getApplicationContext<Application>(),
		)
		try {
			val localGroup = buildGroup(groupIndex = 1, runCount = 1, factRunIndexes = setOf(0))
			val importedGroups = listOf(
				buildGroup(groupIndex = 1, runCount = 2, factRunIndexes = setOf(0, 1)),
			) + (2..33).map { index ->
				buildGroup(groupIndex = index, runCount = 1, factRunIndexes = setOf(0))
			}
			persist(listOf(localGroup))
			persist(importedGroups, target = source)
			val importer = RoomImportPortableCapturedCell(
				database,
				UnconfinedTestDispatcher(testScheduler),
			)
			for (group in importedGroups) {
				val portable = source.withTransaction {
					RoomReadLocalPortableCapturedCell(
						source,
						SourceProductLaneExecutionAuthority { source.inTransaction() },
					).readInTransaction(group.session.logicalTrackingId)
				} as ReadLocalPortableCapturedCellResult.Ready
				importer.importEntry(
					ImportPortableCapturedCellRequest(
						entry = portable.entry,
						receipt = PortableCellImportReceipt(
							jobId = "late-collision-${group.session.logicalTrackingId}",
							entryKey = "late-collision-${group.session.logicalTrackingId}",
							sourceName = "late-collision.trackercell",
							receivedAtMs = portable.entry.endTimeMs,
						),
						expectedCollectedDataEpoch = 0L,
					),
				) shouldBe ImportPortableCapturedCellResult.Applied(
					importRevision = 1L,
					physicalRunCount = portable.entry.runs.size,
					observationCount = portable.entry.runs.sumOf { it.observations.size },
				)
			}
			val checkpoints = mutableListOf<Int>()
			val repository = DefaultCellHistoryRepository(
				database,
				SourceProductLaneExecutionAuthority { database.inTransaction() },
				UnconfinedTestDispatcher(testScheduler),
				sharedHistoryPageCheckpoint = { completedPageCount ->
					checkpoints += completedPageCount
				},
			)

			database.withTransaction {
				repository.recentImportedEligibleForSharedHistoryInTransaction(32)
			} shouldBe ImportedHistoryEligiblePage.Unavailable(
				SourceAwareHistoryPageUnavailableReason.SOURCE_INTEGRITY_FAILURE,
			)
			checkpoints shouldBe listOf(0, 1)
		} finally {
			source.close()
		}
	}

	// Keep cross-database import and newer local candidates together to verify imported recency before the page
	// limit.
	@Suppress("LongMethod")
	@Test
	fun `eligible imported page is not starved by newer local candidates and uses newest run recency`() =
		runTest {
			val source = AppDatabase.testDatabase(
				ApplicationProvider.getApplicationContext<Application>(),
			)
			try {
				val importedGroup = buildGroup(
					groupIndex = 1,
					runCount = 3,
					factRunIndexes = setOf(0, 1, 2),
				)
				persist(listOf(importedGroup), target = source)
				val portable = source.withTransaction {
					RoomReadLocalPortableCapturedCell(
						source,
						SourceProductLaneExecutionAuthority { source.inTransaction() },
					).readInTransaction(importedGroup.session.logicalTrackingId)
				} as ReadLocalPortableCapturedCellResult.Ready
				persist(
					(2..6).map { index ->
						buildGroup(index, runCount = 1, factRunIndexes = setOf(0))
					},
				)
				RoomImportPortableCapturedCell(
					database,
					UnconfinedTestDispatcher(testScheduler),
				).importEntry(
					ImportPortableCapturedCellRequest(
						entry = portable.entry,
						receipt = PortableCellImportReceipt(
							jobId = "shared-history-older-import",
							entryKey = "shared-history-older-import",
							sourceName = "shared-history.trackercell",
							receivedAtMs = portable.entry.endTimeMs,
						),
						expectedCollectedDataEpoch = 0L,
					),
				) shouldBe ImportPortableCapturedCellResult.Applied(
					importRevision = 1L,
					physicalRunCount = portable.entry.runs.size,
					observationCount = portable.entry.runs.sumOf { it.observations.size },
				)
				val repository = repository { database.inTransaction() }

				(repository.recent(1) as CellHistoryPage.Available).entries.single().origin shouldBe
					CellHistoryOrigin.Local
				val page = database.withTransaction {
					repository.recentImportedEligibleForSharedHistoryInTransaction(1)
				} as ImportedHistoryEligiblePage.Available<*>
				val eligible = page.entries.single() as CellImportedHistoryEligibleEntry
				val newest = portable.entry.runs.maxWithOrNull(
					compareBy<PortableCapturedCellRunV1>(
						PortableCapturedCellRunV1::startTimeMs,
						{ it.identity.value },
					),
				)
				requireNotNull(newest)
				eligible.selection shouldBe
					(eligible.entry.origin as CellHistoryOrigin.Imported).selection
				eligible.recency.source shouldBe HistorySource.CELL
				eligible.recency.newestMemberStartTimeMs shouldBe newest.startTimeMs
				eligible.recency.newestMemberTieIdentity shouldBe
					ImportedHistoryRecencyTieIdentity(newest.identity.value)
				eligible.recency.newestMemberStartTimeMs shouldBe
					importedGroup.runs.last().segment.startTimeMs
				eligible.recency.newestMemberStartTimeMs shouldBe
					(portable.entry.startTimeMs + 2_000L)
			} finally {
				source.close()
			}
		}

	@Test
	fun `recent pages thirty three logical candidates by exact logical recency`() = runTest {
		val groups = (1..33).map { index -> buildGroup(index, runCount = 1, factRunIndexes = setOf(0)) }
		persist(groups)
		var everyCheckWasTransactional = true
		val repository = repository {
			everyCheckWasTransactional = everyCheckWasTransactional && database.inTransaction()
			true
		}

		val page = repository.recent(33) as CellHistoryPage.Available

		page.entries shouldHaveSize 33
		page.entries.map { it.startTime.raw } shouldBe groups.asReversed().map { it.runs.single().segment.startTimeMs }
		everyCheckWasTransactional shouldBe true
	}

	// Follow one bounded Cell page sequence through continuation checks and the structural-day view of its same rows.
	@Suppress("LongMethod")
	@Test
	fun `bounded wall and structural ranges find older local Cell entries before applying page limit`() =
		runTest {
			val groups = (1..3).map { index ->
				buildGroup(index, runCount = 1, factRunIndexes = setOf(0))
			}
			persist(groups)
			val repository = repository { true }
			val oldest = groups.first().runs.single().segment
			val newest = groups.last().runs.single().segment

			(repository.recent(1) as CellHistoryPage.Available).entries.single().startTime.raw shouldBe
				newest.startTimeMs
			val wallRequest = CellHistoryRangeRequest(
				scope = CellHistoryRangeScope.WallTime(
					EpochMs(oldest.startTimeMs),
					EpochMs(newest.endTimeMs + 1L),
				),
				limit = 1,
			)
			val firstPage = repository.range(wallRequest) as CellHistoryRangePage.Available
			firstPage.entries.single().entry.startTime.raw shouldBe newest.startTimeMs
			firstPage.continuation.toString() shouldBe "CellHistoryRangeContinuation"
			val secondPage = repository.range(
				wallRequest.copy(continuation = firstPage.continuation),
			) as CellHistoryRangePage.Available
			repository.range(
				CellHistoryRangeRequest(
					CellHistoryRangeScope.WallTime(
						EpochMs(oldest.startTimeMs + 1L),
						EpochMs(newest.endTimeMs + 1L),
					),
					1,
					firstPage.continuation,
				),
			) shouldBe CellHistoryRangePage.Unavailable(
				CellHistoryRangeUnavailableReason.INVALID_CONTINUATION,
			)
			repository { true }.range(
				wallRequest.copy(continuation = firstPage.continuation),
			) shouldBe CellHistoryRangePage.Unavailable(
				CellHistoryRangeUnavailableReason.INVALID_CONTINUATION,
			)
			val thirdPage = repository.range(
				wallRequest.copy(continuation = secondPage.continuation),
			) as CellHistoryRangePage.Available
			listOf(firstPage, secondPage, thirdPage).flatMap { page ->
				page.entries.map { it.entry.startTime.raw }
			} shouldBe groups.asReversed().map { it.runs.single().segment.startTimeMs }
			thirdPage.continuation shouldBe null

			val epochDay = Instant.ofEpochMilli(oldest.startTimeMs)
				.atZone(ZoneId.of(ZONE_ID)).toLocalDate().toEpochDay()
			val structural = repository.range(
				CellHistoryRangeRequest(
					CellHistoryRangeScope.StructuralDays(epochDay, epochDay),
					limit = 100,
				),
			) as CellHistoryRangePage.Available
			structural.entries.any { entry -> entry.entry.startTime.raw == oldest.startTimeMs } shouldBe true
			structural.entries.flatMap { it.structuralDays }.toSet() shouldBe
				setOf(CellHistoryStructuralDay(epochDay, ZONE_ID))
			structural.entries.all {
				it.structuralDayCompleteness == CellHistoryStructuralDayCompleteness.EXACT
			} shouldBe true
		}

	@Test
	fun `range candidate cap plus one is a typed failure rather than an incomplete page`() = runTest {
		val groups = (1..257).map { index ->
			buildGroup(index, runCount = 1, factRunIndexes = setOf(0))
		}
		persist(groups)
		val first = groups.first().runs.single().segment
		val last = groups.last().runs.single().segment

		repository { true }.range(
			CellHistoryRangeRequest(
				CellHistoryRangeScope.WallTime(
					EpochMs(first.startTimeMs),
					EpochMs(last.endTimeMs + 1L),
				),
				limit = 100,
			),
		) shouldBe CellHistoryRangePage.Failed(CellHistoryCause.READ_BUDGET_EXCEEDED)
	}

	@Test
	fun `range discovers exact Cell intent before the first fact without fabricating observations`() =
		runTest {
			val group = buildGroup(groupIndex = 1, runCount = 1, factRunIndexes = emptySet())
			persist(listOf(group))
			val segment = group.runs.single().segment

			val range = repository { true }.range(
				CellHistoryRangeRequest(
					CellHistoryRangeScope.WallTime(
						EpochMs(segment.startTimeMs),
						EpochMs(segment.endTimeMs + 1L),
					),
					limit = 10,
				),
			) as CellHistoryRangePage.Available

			range.entries.single().entry.state shouldBe CellHistoryProductState.MISSING
			range.entries.single().entry.causes shouldBe setOf(CellHistoryCause.NO_QUALIFIED_FACTS)
			range.entries.single().entry.observations shouldBe emptyList()
			range.entries.single().structuralDayCompleteness shouldBe
				CellHistoryStructuralDayCompleteness.UNAVAILABLE
			val sourcePage = database.withTransaction {
				repository { true }.recentCellOnlyInTransaction(1)
			} as CellComposedPage.Available
			sourcePage.entries.single().let { composed ->
				composed.capturesOnlyCell shouldBe true
				composed.physicalSegmentIds shouldBe listOf(segment.id)
				composed.entry.state shouldBe CellHistoryProductState.MISSING
				composed.entry.observations shouldBe emptyList()
			}
		}

	@Test
	fun `bounded source batch reports membership overflow instead of returning partial physical owners`() =
		runTest {
			val group = buildGroup(groupIndex = 1, runCount = 129, factRunIndexes = setOf(0))
			persist(listOf(group))

			database.withTransaction {
				repository { true }.selectBySegmentIdsInTransaction(
					listOf(group.runs.first().segment.id),
				)
			} shouldBe CellComposedPage.Failed(CellHistoryCause.READ_BUDGET_EXCEEDED)
		}

	@Test
	fun `Cell fact presence cannot fabricate exact only Cell capture intent`() = runTest {
		val group = buildGroup(groupIndex = 1, runCount = 1, factRunIndexes = setOf(0))
		persist(listOf(group))
		val run = group.runs.single()
		val pressure = run.source.copy(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
			consentEpoch = run.source.consentEpoch + 10L,
			outputDestination = SourceDestinationOwnerEntity.DESTINATION_SESSION_PRESSURE,
			writerOwner = SourceDestinationOwnerEntity.OWNER_PRESSURE_SESSION_FACTS,
			writerProjectionId = SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID,
			writerProjectionVersion = SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_VERSION,
			writerBindingGeneration = SourceDestinationOwnerEntity.PRESSURE_FACT_BINDING_GENERATION,
		)
		database.sourceSessionDao().insertManifestSources(listOf(pressure))
		val checksum = SessionManifestIntegrity.compute(run.manifest, listOf(run.source, pressure))
		database.openHelper.writableDatabase.execSQL(
			"UPDATE session_manifest_version SET manifest_checksum = ? " +
				"WHERE logical_tracking_id = ? AND manifest_revision = ?",
			arrayOf<Any?>(checksum, group.session.logicalTrackingId, run.manifest.manifestRevision),
		)
		val repository = repository { true }

		val grouped = database.withTransaction {
			repository.selectBySegmentIdsInTransaction(listOf(run.segment.id))
		} as CellComposedPage.Available
		grouped.entries.single().capturesOnlyCell shouldBe false
		(database.withTransaction {
			repository.recentCellOnlyInTransaction(1)
		} as CellComposedPage.Available).entries shouldBe emptyList()
	}

	@Test
	fun `cursor-carried scope exposes a moved current lineage as integrity failure`() = runTest {
		val group = buildGroup(groupIndex = 1, runCount = 1, factRunIndexes = setOf(0))
		persist(listOf(group))
		val moved = group.runs.single().facts.first().logicalFactId
		database.openHelper.writableDatabase.execSQL(
			"UPDATE cell_captured_fact_revision SET logical_tracking_id = 'moved-logical' " +
				"WHERE logical_fact_id = ?",
			arrayOf<Any?>(moved),
		)

		repository { true }.recent(1) shouldBe
			CellHistoryPage.Failed(CellHistoryCause.FACT_INTEGRITY_FAILED)
	}

	@Test
	fun `opaque local lookup rejects a run swapped onto another logical segment`() = runTest {
		val first = buildGroup(groupIndex = 1, runCount = 1, factRunIndexes = setOf(0))
		val second = buildGroup(groupIndex = 2, runCount = 1, factRunIndexes = setOf(0))
		persist(listOf(first, second))
		val repository = repository { true }
		val selection = requireNotNull(
			(repository.session(first.runs.single().segment.id) as CellHistoryQuery.Found)
				.entry.selection,
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_service_run SET session_segment_id = NULL WHERE service_run_id = ?",
			arrayOf<Any?>(second.runs.single().run.serviceRunId),
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_service_run SET session_segment_id = ? WHERE service_run_id = ?",
			arrayOf<Any?>(
				second.runs.single().segment.id,
				first.runs.single().run.serviceRunId,
			),
		)

		assertInvalidLocalSelection(repository, selection)
	}

	@Test
	fun `opaque local lookup reports invalid membership after identity owner loses all runs`() = runTest {
		val group = buildGroup(groupIndex = 1, runCount = 1, factRunIndexes = emptySet())
		database.sourceSessionDao().insertSession(group.session)
		val selection = LocalCellHistorySelection(
			LocalCellHistoryIdentity(
				PortableCellOpaqueIdentity.derive(
					PortableCellIdentityKind.LOGICAL_ENTRY,
					group.session.logicalTrackingId,
				).value,
			),
		)

		assertInvalidLocalSelection(repository { true }, selection)
	}

	@Test
	fun `opaque local lookup reports invalid membership when selected run segment is missing`() =
		runTest {
			val group = buildGroup(groupIndex = 1, runCount = 1, factRunIndexes = setOf(0))
			persist(listOf(group))
			val repository = repository { true }
			val selection = requireNotNull(
				(repository.session(group.runs.single().segment.id) as CellHistoryQuery.Found)
					.entry.selection,
			)
			database.openHelper.writableDatabase.execSQL(
				"DELETE FROM session_segment WHERE id = ?",
				arrayOf<Any?>(group.runs.single().segment.id),
			)

			assertInvalidLocalSelection(repository, selection)
		}

	// Follow one saved Cell selection through deletion, repeat reads, and corruption of its matching deletion fence.
	@Suppress("LongMethod")
	@Test
	fun `authenticated local deletion receipt returns deleted until one fence pair is corrupt`() =
		runTest {
			val group = buildGroup(groupIndex = 1, runCount = 1, factRunIndexes = setOf(0))
			persist(listOf(group))
			val built = group.runs.single()
			val repository = repository { true }
			val selection = requireNotNull(
				(repository.session(built.segment.id) as CellHistoryQuery.Found).entry.selection,
			) as LocalCellHistorySelection
			val deletedRun = CellCapturedDeletedRunEntity.create(
				logicalTrackingId = group.session.logicalTrackingId,
				serviceRunId = built.run.serviceRunId,
				sessionSegmentId = built.segment.id,
				startTimeMs = built.segment.startTimeMs,
				endTimeMs = built.segment.endTimeMs,
				collectedDataEpoch = 0L,
				deletedAtMs = 900L,
			)
			database.withTransaction {
				val dao = database.cellCapturedFactDao()
				dao.insertEntryDeletionReceipt(
					CellCapturedEntryDeletionReceiptEntity.create(
						logicalTrackingId = group.session.logicalTrackingId,
						entryIdentity = selection.identity.value,
						collectedDataEpoch = 0L,
						runFootprints = listOf(deletedRun),
						deletedAtMs = 900L,
					),
				)
				dao.insertDeletedRuns(listOf(deletedRun))
				database.sourceDeletionFenceDao().insertIfAbsent(
					SourceDeletionFenceEntity.createLogicalServiceRun(
						SourceDestinationOwnerEntity.SOURCE_CELL,
						SessionManifestPurposeCode.SESSION_CAPTURE,
						group.session.logicalTrackingId,
						built.run.serviceRunId,
						1L,
						0L,
						900L,
					),
				)
				dao.insertDeletionGeneration(
					com.adsamcik.tracker.shared.base.database.data
						.CellCaptureDeletionGenerationEntity(
							group.session.logicalTrackingId,
							built.run.serviceRunId,
							0L,
							1L,
							900L,
						),
				)
				val ids = built.cursors.map { it.logicalFactId }
				dao.deleteExactCursors(
					SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID,
					SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION,
					ids,
				)
				dao.deleteExactRevisionLineages(
					SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID,
					SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION,
					ids,
				)
				database.sessionSegmentDao().deleteExact(
					built.segment.id,
					group.session.logicalTrackingId,
					built.run.serviceRunId,
				)
				database.sourceEvidenceStateDao().incrementRevision(900L)
			}

			repeat(2) {
				(repository.detail(selection) as CellHistoryQuery.Found).entry.let { deleted ->
					deleted.selection shouldBe selection
					deleted.state shouldBe CellHistoryProductState.DELETED
					deleted.causes shouldBe setOf(CellHistoryCause.DELETED)
					deleted.startTime.raw shouldBe built.segment.startTimeMs
					deleted.endTime.raw shouldBe built.segment.endTimeMs
					deleted.observations shouldBe emptyList()
				}
			}
			database.openHelper.writableDatabase.execSQL(
				"UPDATE cell_capture_deletion_generation SET generation = 2 " +
					"WHERE logical_tracking_id = ? AND service_run_id = ?",
				arrayOf<Any?>(group.session.logicalTrackingId, built.run.serviceRunId),
			)
			(repository.detail(selection) as CellHistoryQuery.Found).entry.let { corrupt ->
				corrupt.state shouldBe CellHistoryProductState.FAILED
				corrupt.causes shouldBe setOf(CellHistoryCause.FACT_INTEGRITY_FAILED)
				corrupt.observations shouldBe emptyList()
			}
		}

	@Test
	fun `cursor-carried scope exposes a missing current revision as integrity failure`() = runTest {
		val group = buildGroup(groupIndex = 1, runCount = 1, factRunIndexes = setOf(0))
		persist(listOf(group))
		val cursor = ownerCursor(group)
		database.openHelper.writableDatabase.execSQL(
			"DELETE FROM cell_captured_fact_revision WHERE logical_fact_id = ? AND semantic_revision = ?",
			arrayOf<Any?>(cursor.logicalFactId, cursor.latestSemanticRevision),
		)

		assertRecentFactIntegrityFailure()
	}

	@Test
	fun `cursor-carried scope exposes every corrupt current head field as integrity failure`() = runTest {
		val group = buildGroup(groupIndex = 1, runCount = 1, factRunIndexes = setOf(0))
		persist(listOf(group))
		val cursor = ownerCursor(group)
		val corruptions = listOf(
			"latest_semantic_revision" to cursor.latestSemanticRevision + 1L,
			"latest_mutation_id" to sha256("wrong-current-mutation"),
			"latest_effect_checksum" to sha256("wrong-current-effect"),
			"latest_source_admission_ordinal" to cursor.latestSourceAdmissionOrdinal + 1L,
		)
		corruptions.forEach { (column, value) ->
			database.openHelper.writableDatabase.execSQL(
				"UPDATE cell_captured_fact_cursor SET $column = ? WHERE logical_fact_id = ?",
				arrayOf<Any?>(value, cursor.logicalFactId),
			)
			assertRecentFactIntegrityFailure()
			restoreCursorHead(cursor)
		}
	}

	@Test
	fun `coverage-only fact cannot reuse aggregate below deletion high water`() = runTest {
		val group = buildGroup(groupIndex = 1, runCount = 1, factRunIndexes = setOf(0))
		val ownerOrdinal = group.runs.single().facts.first().sourceAdmissionOrdinal
		persist(listOf(group), SourceEvidenceState(
			collectedDataEpoch = 0L,
			deletedSourceEventHighWaterOrdinal = ownerOrdinal,
		))

		val query = repository { true }.session(group.runs.single().segment.id)

		val entry = (query as CellHistoryQuery.Found).entry
		entry.state shouldBe CellHistoryProductState.UNAVAILABLE
		entry.causes shouldBe setOf(CellHistoryCause.PRIVACY_EPOCH_MISMATCH)
		entry.observations shouldBe emptyList()
	}

	@Test
	fun `coverage-only fact cannot reuse aggregate before retention floor`() = runTest {
		val group = buildGroup(groupIndex = 1, runCount = 1, factRunIndexes = setOf(0))
		val facts = group.runs.single().facts
		val owner = facts.first { it.factKind == CellCapturedFactRevisionEntity.FACT_KIND_AGGREGATE }
		val dependent = facts.first { it.factKind == CellCapturedFactRevisionEntity.FACT_KIND_COVERAGE_ONLY }
		val floor = owner.observedWallTimeMs + owner.wallTimeUncertaintyMs + 1L
		check(floor < dependent.observedWallTimeMs - dependent.wallTimeUncertaintyMs)
		persist(listOf(group), SourceEvidenceState(collectedDataEpoch = 0L, retainedFromMs = floor))

		val query = repository { true }.session(group.runs.single().segment.id)

		val entry = (query as CellHistoryQuery.Found).entry
		entry.state shouldBe CellHistoryProductState.UNAVAILABLE
		entry.causes shouldBe setOf(CellHistoryCause.RETENTION_LIMIT)
		entry.observations shouldBe emptyList()

		RoomSourceFactLookupReader(
			database,
			UnconfinedTestDispatcher(testScheduler),
		).lookup(cellFactLookupRequest(dependent)) shouldBe
			SourceFactLookupResult.Deleted(SourceProofReason.RETENTION_EXPIRED)
	}

	@Test
	fun `prior boot active Cell provider cannot authenticate history`() = runTest {
		val group = boundarylessProviderGroup(
			status = ProviderRegistrationGenerationEntity.STATUS_ACTIVE,
			failureCode = null,
		)
		persist(listOf(group))

		val entry = (
			repository(currentClockDomainId = "boot-current", authority = { true })
				.session(group.runs.single().segment.id) as CellHistoryQuery.Found
			).entry

		entry.state shouldBe CellHistoryProductState.FAILED
		entry.causes shouldBe setOf(CellHistoryCause.WRITER_PROVENANCE_INVALID)
		entry.observations shouldBe emptyList()
	}

	@Test
	fun `reconciled prior boot Cell provider authenticates history`() = runTest {
		val group = boundarylessProviderGroup(
			status = ProviderRegistrationGenerationEntity.STATUS_RETIRED,
			failureCode = ProviderRegistrationGenerationEntity
				.FAILURE_PRIOR_PROCESS_INCARNATION_ENDED,
		)
		persist(listOf(group))

		val entry = (
			repository(currentClockDomainId = "boot-current", authority = { true })
				.session(group.runs.single().segment.id) as CellHistoryQuery.Found
			).entry

		entry.state shouldBe CellHistoryProductState.PARTIAL
		entry.causes shouldBe setOf(
			CellHistoryCause.ACQUISITION_INCOMPLETE,
			CellHistoryCause.SUBSCRIPTION_GROUPING_UNKNOWN,
		)
		entry.observations shouldHaveSize 1
	}

	@Test
	fun `membership limit plus one is a typed overflow and cancellation propagates`() = runTest {
		val group = buildGroup(groupIndex = 1, runCount = 129, factRunIndexes = setOf(0))
		persist(listOf(group))
		val repository = repository { true }

		repository.recent(1) shouldBe CellHistoryPage.Failed(CellHistoryCause.READ_BUDGET_EXCEEDED)
		assertFailsWith<CancellationException> {
			withContext(Job().apply { cancel() }) { repository.recent(1) }
		}
	}

	@Test
	fun `version six Cell history retains exact towers across callback authorization rotation`() = runTest {
		val group = perReadingGroup(buildGroup(244, 1, setOf(0)))
		persist(listOf(group))
		persistPerReadingChildren(group)
		val entry = (repository { true }.recent(1) as CellHistoryPage.Available).entries.single()
		entry.state shouldBe CellHistoryProductState.PARTIAL
		entry.observations shouldHaveSize 2
		entry.observations.all { it.towers.size == 1 } shouldBe true
	}

	@Test
	fun `historical Cell history and exact lookup use stored purpose after a denied callback beyond End`() = runTest {
		val original = perReadingGroup(buildGroup(246, 1, setOf(0)))
		val originalRun = original.runs.single()
		val denied = originalRun.authorizations.first { it.authorizationRevision == 3L }
		fun callbackWal(fact: CellCapturedFactRevisionEntity): SourceEventWalEntity {
			val unsigned = walFor(fact).copy(logicalTrackingId = null, serviceRunId = null,
				authorizationRevision = denied.authorizationRevision,
				authorizationFingerprint = denied.authorizationFingerprint,
				authorizationPurposeEligibilityMask = 0L, planAttribution = 2,
				sourcePolicyRevision = null, captureConsentEpoch = null,
				sessionManifestRevision = null, lifecycleLeaseGeneration = null)
			return unsigned.copy(integrityIdentity = unsigned.calculatedIntegrityIdentity())
		}
		val facts = originalRun.facts.map { originalFact ->
			val received = originalFact.copy(receivedElapsedNanos = denied.effectiveElapsedRealtimeNanos + 1_000_000L)
			val raw = callbackWal(received)
			val unsigned = received.copy(walIntegrityIdentity = raw.integrityIdentity)
			unsigned.copy(effectChecksum = CellCapturedFactRevisionIntegrity.effectChecksum(unsigned))
		}
		val run = originalRun.copy(facts = facts,
			cursors = facts.groupBy { it.logicalFactId }.values.map { cursor(it.maxBy { fact -> fact.semanticRevision }) },
			completeness = originalRun.completeness.copy(lastAdmissionOrdinal = null, lastSourceSequence = null))
		val group = original.copy(session = original.session.copy(finalAdmissionOrdinal = 0L), runs = listOf(run))
		persist(listOf(group))
		persistPerReadingChildren(group)
		facts.distinctBy { it.sourceEventId }.forEach { fact ->
			val raw = callbackWal(fact)
			database.openHelper.writableDatabase.execSQL(
				"UPDATE source_event_wal SET logical_tracking_id = NULL, service_run_id = NULL, " +
					"source_policy_revision = NULL, capture_consent_epoch = NULL, session_manifest_revision = NULL, " +
					"lifecycle_lease_generation = NULL, authorization_revision = ?, authorization_fingerprint = ?, " +
					"authorization_purpose_eligibility_mask = 0, plan_attribution = 2, integrity_identity = ? " +
					"WHERE event_id = ?",
				arrayOf<Any?>(raw.authorizationRevision, raw.authorizationFingerprint, raw.integrityIdentity, raw.eventId))
		}

		val entry = (repository { true }.recent(1) as CellHistoryPage.Available).entries.single()
		entry.state shouldBe CellHistoryProductState.PARTIAL
		entry.observations shouldHaveSize 2
		entry.observations.all { it.towers.size == 1 } shouldBe true
		val current = run.facts.filter { it.semanticRevision == 2L }.single()
		val lookup = RoomSourceFactLookupReader(database, UnconfinedTestDispatcher(testScheduler))
			.lookup(cellFactLookupRequest(current))
		(lookup as SourceFactLookupResult.Found).state.lineage.semanticRevision shouldBe current.semanticRevision
	}

	@Test
	fun `version six Cell history rejects a parent that claims permission before it began`() = runTest {
		val authorized = perReadingGroup(buildGroup(245, 1, setOf(0)))
		val group = authorized.copy(runs = authorized.runs.map { run ->
			val first = run.authorizations.first { it.authorizationRevision == 1L }
			val denied = SourceBrokerAuthorization.rows(CELL_SOURCE, first.registrationGeneration,
				1L, emptyList(), BOOT_ID, first.effectiveElapsedRealtimeNanos, first.effectiveWallTimeMs)
			val prematureFacts = run.facts.map { fact ->
				val premature = fact.copy(authorizationEffectStartNanos = first.effectiveElapsedRealtimeNanos)
				premature.copy(effectChecksum = CellCapturedFactRevisionIntegrity.effectChecksum(premature))
			}
			run.copy(
				authorizations = denied + run.authorizations.filter { it.authorizationRevision != 1L },
				facts = prematureFacts,
				cursors = prematureFacts.groupBy { it.logicalFactId }.values.map { lineage ->
					cursor(lineage.maxBy { it.semanticRevision })
				},
			)
		})
		persist(listOf(group))
		persistPerReadingChildren(group)
		val entry = (repository { true }.recent(1) as CellHistoryPage.Available).entries.single()
		entry.state shouldBe CellHistoryProductState.FAILED
		entry.causes shouldBe setOf(CellHistoryCause.WRITER_PROVENANCE_INVALID)
		entry.observations shouldBe emptyList()
	}

	@Test
	fun `version six Cell exact lookup rejects missing tower rows consistently with history`() = runTest {
		val group = perReadingGroup(buildGroup(247, 1, setOf(0)))
		persist(listOf(group))
		persistPerReadingChildren(group)
		val current = group.runs.single().facts.single { it.semanticRevision == 2L }
		val reader = RoomSourceFactLookupReader(database, UnconfinedTestDispatcher(testScheduler))
		(reader.lookup(cellFactLookupRequest(current)) as SourceFactLookupResult.Found)
			.state.lineage.semanticRevision shouldBe current.semanticRevision
		val before = (repository { true }.session(group.runs.single().segment.id) as CellHistoryQuery.Found).entry
		before.observations shouldHaveSize 2
		database.radioObservationChildDao().deleteCellSessionRevision(
			current.writerProjectionId, current.writerProjectionVersion,
			current.logicalFactId, current.semanticRevision) shouldBe 1

		reader.lookup(cellFactLookupRequest(current)) shouldBe
			SourceFactLookupResult.Failed(SourceProofReason.INTEGRITY_FAILURE)
		val after = (repository { true }.session(group.runs.single().segment.id) as CellHistoryQuery.Found).entry
		after.state shouldBe CellHistoryProductState.FAILED
		after.causes shouldBe setOf(CellHistoryCause.WRITER_PROVENANCE_INVALID)
		after.observations shouldBe emptyList()
	}

	@Test
	fun `version six Cell exact lookup rejects corrupted tower rows consistently with history`() = runTest {
		val group = perReadingGroup(buildGroup(248, 1, setOf(0)))
		persist(listOf(group))
		persistPerReadingChildren(group)
		val current = group.runs.single().facts.single { it.semanticRevision == 2L }
		val reader = RoomSourceFactLookupReader(database, UnconfinedTestDispatcher(testScheduler))
		(reader.lookup(cellFactLookupRequest(current)) as SourceFactLookupResult.Found)
			.state.lineage.semanticRevision shouldBe current.semanticRevision
		database.openHelper.writableDatabase.execSQL(
			"UPDATE cell_captured_child SET tower_identity = ? WHERE logical_fact_id = ? " +
				"AND semantic_revision = ?",
			arrayOf<Any>("changed-tower", current.logicalFactId, current.semanticRevision))

		reader.lookup(cellFactLookupRequest(current)) shouldBe
			SourceFactLookupResult.Failed(SourceProofReason.INTEGRITY_FAILURE)
		val after = (repository { true }.session(group.runs.single().segment.id) as CellHistoryQuery.Found).entry
		after.state shouldBe CellHistoryProductState.FAILED
		after.causes shouldBe setOf(CellHistoryCause.WRITER_PROVENANCE_INVALID)
		after.observations shouldBe emptyList()
	}

	private fun perReadingGroup(group: Group) = group.copy(runs = group.runs.map { run ->
		val provider = requireNotNull(run.provider)
		val firstPoint = run.facts.minOf { it.coverageIntervalStartNanos }
		val rotated = SourceBrokerAuthorization.rows(CELL_SOURCE, provider.registrationGeneration,
			2L, run.demands, BOOT_ID, firstPoint - 1L, run.facts.minOf { it.observedWallTimeMs })
		val closing = run.authorizations.first { it.authorizationRevision == 2L }.let { row ->
			SourceBrokerAuthorization.rows(CELL_SOURCE, provider.registrationGeneration, 3L,
				emptyList(), BOOT_ID, row.effectiveElapsedRealtimeNanos, row.effectiveWallTimeMs)
		}
		fun delivery(fact: CellCapturedFactRevisionEntity): String = canonicalCellProviderDeliveryIdentity(
			BOOT_ID, cellPayload(fact.observedElapsedNanos).observations.map { item ->
				CellProviderDeliveryIdentityFact(item.radioType, item.registered, item.signalLevelDbm,
					requireNotNull(item.providerTimestampNanos), item.identifierToken)
			}, CELL_WAL_PAYLOAD_VERSION)
		val ids = run.facts.associate { original ->
			original.logicalFactId to CellCapturedFactRevisionIntegrity.logicalFactId(delivery(original),
				original.logicalTrackingId, original.serviceRunId, original.sessionSegmentId,
				original.manifestRevision, original.collectedDataEpoch, original.scopeDeletionGeneration)
		}
		val facts = run.facts.map { original ->
			val id = ids.getValue(original.logicalFactId)
			val unsigned = original.copy(payloadVersion = CELL_WAL_PAYLOAD_VERSION,
				logicalFactId = id, mutationId = CellCapturedFactRevisionIntegrity.mutationId(id, original.semanticRevision),
				aggregateOwnerLogicalFactId = original.aggregateOwnerLogicalFactId?.let(ids::getValue),
				sourceDeliveryIdentity = delivery(original), canonicalProviderSemanticsDigest = delivery(original),
				authorizationRevision = 2L,
				authorizationEffectStartNanos = rotated.first().effectiveElapsedRealtimeNanos,
				authorizationFingerprint = rotated.first().authorizationFingerprint)
			val wal = walFor(unsigned)
			val withWal = unsigned.copy(walIntegrityIdentity = wal.integrityIdentity, payloadChecksum = wal.payloadChecksum)
			withWal.copy(effectChecksum = CellCapturedFactRevisionIntegrity.effectChecksum(withWal))
		}
		run.copy(authorizations = run.authorizations.filter { it.authorizationRevision == 1L } + rotated + closing,
			facts = facts, cursors = facts.groupBy { it.logicalFactId }.values.map { lineage ->
				cursor(lineage.maxBy { it.semanticRevision }) })
	})

	private suspend fun persistPerReadingChildren(group: Group) {
		val children = group.runs.flatMap { it.facts }.map { fact ->
			val item = cellPayload(fact.observedElapsedNanos).observations.single()
			val owner = RadioChildOwner(RadioChildPurpose.SESSION, fact.writerProjectionId,
				fact.writerProjectionVersion, fact.logicalFactId, fact.semanticRevision, 0)
			val input = CellChildChecksumInput(owner, fact.observedElapsedNanos, fact.observedWallTimeMs,
				fact.wallTimeUncertaintyMs, null, item.radioType, item.registered, item.signalLevelDbm)
			CellCapturedChildEntity(fact.writerProjectionId, fact.writerProjectionVersion,
				fact.logicalFactId, fact.semanticRevision, 0, fact.observedElapsedNanos,
				fact.observedWallTimeMs, fact.wallTimeUncertaintyMs, null, item.radioType,
				item.registered, item.signalLevelDbm, RadioObservationChildIntegrity.cell(input))
		}
		database.radioObservationChildDao().insertCellSession(children)
	}

	private fun repository(
		currentClockDomainId: String? = null,
		authority: () -> Boolean,
	) = DefaultCellHistoryRepository(
		database,
		SourceProductLaneExecutionAuthority { authority() },
		UnconfinedTestDispatcher(),
		{ _ -> },
		currentClockDomainId?.let { bootId -> BootClockDomainProvider { bootId } },
	)

	private suspend fun assertInvalidLocalSelection(
		repository: DefaultCellHistoryRepository,
		selection: com.adsamcik.tracker.stats.api.repository.CellHistoryEntrySelection,
	) {
		val entry = (repository.detail(selection) as CellHistoryQuery.Found).entry
		entry.selection shouldBe selection
		entry.state shouldBe CellHistoryProductState.FAILED
		entry.causes shouldBe setOf(CellHistoryCause.PHYSICAL_MEMBERSHIP_INVALID)
		entry.startTime.raw shouldBe 0L
		entry.endTime.raw shouldBe 0L
		entry.observations shouldBe emptyList()
	}

	/** Source-only original/final contract fixture; no Android or setup activation. */
	private fun withWriterContract(group: Group, generation: Long, mode: String): Group {
		val origin = if (mode == "AUTOMATIC") {
			"AUTOMATIC_BACKGROUND_START"
		} else {
			START_ORIGIN
		}
		return group.copy(session = group.session.copy(sessionMode = mode, startOrigin = origin),
			runs = group.runs.map { run ->
				val source = run.source.copy(writerBindingGeneration = generation, writerOwnerGeneration = generation * 2L)
				val unsignedManifest = run.manifest.copy(sessionMode = mode, startOrigin = origin)
				val manifest = unsignedManifest.copy(manifestChecksum = SessionManifestIntegrity.compute(unsignedManifest, listOf(source)))
				val facts = run.facts.map { original ->
					val changed = original.copy(writerBindingGeneration = generation, writerOwnerGeneration = generation * 2L)
					changed.copy(effectChecksum = CellCapturedFactRevisionIntegrity.effectChecksum(changed))
				}
				val cursors = run.cursors.map { cursor ->
					val current = facts.single { it.logicalFactId == cursor.logicalFactId && it.semanticRevision == cursor.latestSemanticRevision }
					cursor.copy(writerOwnerGeneration = generation * 2L, latestEffectChecksum = current.effectChecksum)
				}
				run.copy(run = run.run.copy(startOrigin = origin), source = source, manifest = manifest, facts = facts, cursors = cursors)
			})
	}

	// Follow one real Cell write through retirement and bound tampering without rewriting the saved fact.
	@Suppress("LongMethod")
	@Test
	fun `real live Cell writer history survives provider retirement without rewriting saved facts`() = runTest {
		val closed = buildGroup(994, 1, setOf(0))
		val built = closed.runs.single()
		val originalProvider = requireNotNull(built.provider)
		val wal = walFor(built.facts.first())
		val live = closed.copy(session = closed.session.copy(state = "ACTIVE", currentServiceRunId = built.run.serviceRunId,
			cutoffAtMs = null, cutoffElapsedNanos = null, completedAtMs = null, finalAdmissionOrdinal = null),
			runs = listOf(built.copy(run = built.run.copy(state = "ACTIVE", completedAtMs = null, completionReason = null,
				runtimeAcknowledgement = "START_ACCEPTED", presentationAcknowledgement = SourceServiceRunEntity.PRESENTATION_PENDING,
				presentationAcknowledgedAtMs = null), provider = originalProvider.copy(
				status = ProviderRegistrationGenerationEntity.STATUS_ACTIVE, retiredAtMs = null,
				retiredElapsedRealtimeNanos = null, captureCallbackBarrierAuthorizationRevision = 0L),
				demands = built.demands.map { it.copy(status = SourceDemandEntity.STATUS_ACTIVE,
					retireBootId = null, retireElapsedRealtimeNanos = null, retiredAtMs = null) },
				authorizations = built.authorizations.filter { it.authorizationRevision == 1L },
				facts = emptyList(), cursors = emptyList(), completeness = built.completeness.copy(
					lastAdmissionOrdinal = wal.admissionOrdinal, lastSourceSequence = wal.sourceSequence))))
		persist(listOf(live))
		database.sourceEventWalDao().insertAbortingOnUnexpectedConflict(wal)
		writeCellWithRealProducer(wal.eventId)
		val dao = database.cellCapturedFactDao()
		val saved = dao.currentRevisionsForWriterAdmission(SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID,
			SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION, wal.admissionOrdinal, 2).single()
		saved.providerAcceptanceEndNanos shouldBe Long.MAX_VALUE
		saved.authorizationEffectEndNanos shouldBe Long.MAX_VALUE
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_product_projection_lane SET contiguous_admission_ordinal = ? WHERE source_kind = ?",
			arrayOf<Any?>(wal.admissionOrdinal, CELL_SOURCE))
		val before = (repository(BOOT_ID) { true }.session(built.segment.id) as CellHistoryQuery.Found).entry
		before.observations shouldHaveSize 1

		val providerEnd = requireNotNull(originalProvider.retiredElapsedRealtimeNanos)
		database.withTransaction {
			database.openHelper.writableDatabase.execSQL(
				"UPDATE provider_registration_generation SET status = 'RETIRED', retired_at_ms = ?, " +
					"retired_elapsed_realtime_nanos = ? WHERE source_kind = ? AND registration_generation = ?",
				arrayOf<Any?>(originalProvider.retiredAtMs, providerEnd, CELL_SOURCE, saved.registrationGeneration))
			database.sourceBrokerDao().insertAuthorizations(built.authorizations.filter { it.authorizationRevision > 1L })
			database.sourceSessionDao().updateServiceRun(built.run)
			val stopped = closed.session.copy(finalAdmissionOrdinal = wal.admissionOrdinal)
			database.sourceSessionDao().updateSession(stopped)
			database.installOriginalStopFixture(stopped, built.manifest)
		}
		val after = (repository(BOOT_ID) { true }.session(built.segment.id) as CellHistoryQuery.Found).entry
		after.observations shouldBe before.observations
		dao.revision(saved.writerProjectionId, saved.writerProjectionVersion, saved.logicalFactId, 1L) shouldBe saved

		// Original open bounds never authorize a reading at or beyond the actual retirement.
		database.openHelper.writableDatabase.execSQL(
			"UPDATE provider_registration_generation SET retired_elapsed_realtime_nanos = ? WHERE source_kind = ?",
			arrayOf<Any?>(saved.coverageIntervalEndNanos, CELL_SOURCE))
		(repository(BOOT_ID) { true }.session(built.segment.id) as CellHistoryQuery.Found).entry.observations shouldBe emptyList()
		database.openHelper.writableDatabase.execSQL(
			"UPDATE provider_registration_generation SET retired_elapsed_realtime_nanos = ? WHERE source_kind = ?",
			arrayOf<Any?>(providerEnd, CELL_SOURCE))
		val altered = saved.copy(providerAcceptanceEndNanos = providerEnd + 1L)
		val checksum = CellCapturedFactRevisionIntegrity.effectChecksum(altered)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE cell_captured_fact_revision SET provider_acceptance_end_nanos = ?, effect_checksum = ? WHERE logical_fact_id = ?",
			arrayOf<Any?>(altered.providerAcceptanceEndNanos, checksum, saved.logicalFactId))
		database.openHelper.writableDatabase.execSQL(
			"UPDATE cell_captured_fact_cursor SET latest_effect_checksum = ? WHERE logical_fact_id = ?",
			arrayOf<Any?>(checksum, saved.logicalFactId))
		(repository(BOOT_ID) { true }.session(built.segment.id) as CellHistoryQuery.Found).entry.observations shouldBe emptyList()
	}

	/** JVM access keeps the real tracker writer internal to its production module. */
	private suspend fun writeCellWithRealProducer(eventId: String) {
		val adapterType = Class.forName("com.adsamcik.tracker.tracker.source.cell.CellWalQualificationAdapter")
		val adapter = adapterType.constructors.single { it.parameterCount == 3 }.newInstance(database,
			com.adsamcik.tracker.tracker.source.coordinator.SourcePlanCodec(), BootClockDomainProvider { BOOT_ID })
		val writerType = Class.forName("com.adsamcik.tracker.tracker.source.cell.CellCapturedFactWriter")
		val writer = writerType.getConstructor(AppDatabase::class.java, adapterType).newInstance(database, adapter)
		val write = writerType.methods.single { it.name.startsWith("write-") && it.parameterCount == 2 &&
			it.parameterTypes[0] == String::class.java }
		kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Any?> { continuation ->
			write.invoke(writer, eventId, continuation)
		}
	}
	private suspend fun persist(
		groups: List<Group>,
		evidenceState: SourceEvidenceState = SourceEvidenceState(collectedDataEpoch = 0L),
		target: AppDatabase = database,
		writerBindingGeneration: Long = 1L,
	) {
		val runs = groups.flatMap(Group::runs)
		val sessionDao = target.sourceSessionDao()
		val policyDao = target.sourcePolicyDao()
		val planDao = target.sourcePlanStateDao()
		val brokerDao = target.sourceBrokerDao()
		val factDao = target.cellCapturedFactDao()
		target.sourceEvidenceStateDao().ensure(evidenceState)
		target.sourceDestinationOwnerDao().insertIfAbsent(
			SourceDestinationOwnerEntity(
				sourceKind = CELL_SOURCE,
				destination = SourceDestinationOwnerEntity.DESTINATION_SESSION_CELL,
				owner = SourceDestinationOwnerEntity.OWNER_CELL_SESSION_FACTS,
				ownerGeneration = SourceWriterGenerationContract.canonicalOwnerGeneration(writerBindingGeneration),
				updatedAtMs = 0L,
			),
		)
		groups.map(Group::session).forEach { sessionDao.insertSession(it) }
		target.sessionSegmentDao().insert(runs.map(BuiltRun::segment))
		runs.sortedBy { it.run.startedAtMs }.forEach { sessionDao.insertServiceRun(it.run) }
		runs.sortedBy { it.manifest.manifestRevision }.forEach { sessionDao.insertManifest(it.manifest) }
		sessionDao.insertManifestSources(runs.map(BuiltRun::source))
		groups.forEach { group -> target.installOriginalStopFixture(group.session, group.runs.last().manifest) }
		policyDao.insertPolicies(runs.map(BuiltRun::policy).sortedBy(SourcePolicyEntity::policyRevision))
		policyDao.insertConsentEpochs(runs.map(BuiltRun::consent).sortedBy(SourceConsentEpochEntity::epoch))
		runs.map(BuiltRun::planHeader).sortedBy(AcquisitionPlanRevisionEntity::revision)
			.forEach { planDao.insertRevision(it) }
		planDao.insertDesiredPlans(runs.map(BuiltRun::desiredPlan).sortedBy(SourceDesiredPlanEntity::revision))
		brokerDao.insertDemands(runs.flatMap(BuiltRun::demands))
		runs.mapNotNull(BuiltRun::provider).sortedBy(ProviderRegistrationGenerationEntity::registrationGeneration)
			.forEach { brokerDao.insertRegistration(it) }
		brokerDao.insertAuthorizations(runs.flatMap(BuiltRun::authorizations))
		persistCapturedCellFixture(runs, target, writerBindingGeneration, sessionDao, factDao)
	}

	private suspend fun persistCapturedCellFixture(
		runs: List<BuiltRun>, target: AppDatabase, writerBindingGeneration: Long,
		sessionDao: SourceSessionDao, factDao: CellCapturedFactDao,
	) {
		target.sourceProjectionStateDao().installProductLane(lane(runs.flatMap(BuiltRun::facts)
			.maxOfOrNull(CellCapturedFactRevisionEntity::sourceAdmissionOrdinal) ?: 1L).copy(
			bindingGeneration = writerBindingGeneration, captureModeMask = requireNotNull(SourceWriterGenerationContract.captureModeMaskForKnownBinding(writerBindingGeneration))))
		runs.forEach { sessionDao.saveCompleteness(it.completeness) }
		runs.flatMap(BuiltRun::facts)
			.distinctBy(CellCapturedFactRevisionEntity::sourceEventId)
			.map(::walFor)
			.forEach { target.sourceEventWalDao().insertAbortingOnUnexpectedConflict(it) }
		runs.flatMap(BuiltRun::facts).sortedWith(compareBy(
			CellCapturedFactRevisionEntity::logicalFactId,
			CellCapturedFactRevisionEntity::semanticRevision,
		)).forEach { factDao.insertRevision(it) }
		runs.flatMap(BuiltRun::cursors).forEach { factDao.insertCursor(it) }
	}

	private fun buildGroup(
		groupIndex: Int,
		runCount: Int,
		factRunIndexes: Set<Int>,
	): Group {
		require(groupIndex > 0 && runCount > 0)
		val logicalId = "logical-cell-$groupIndex"
		val specs = (0 until runCount).map { local ->
			val global = groupIndex * 1_000L + local + 1L
			val startWall = WALL_BASE_MS + groupIndex * 1_000_000L + local * 1_000L
			val startElapsed = 1_000_000_000L + groupIndex * 100_000_000_000L + local * 1_000_000_000L
			RunSpec(local + 1L, global, "run-$groupIndex-${local + 1}", startWall, startElapsed,
				local in factRunIndexes)
		}
		val sessionEndElapsed = specs.maxOf { it.startElapsedNanos + RUN_DURATION_NANOS }
		val sessionEndWall = specs.maxOf { it.startWallMs + RUN_DURATION_MS }
		val built = specs.map { buildRun(logicalId, it, sessionEndElapsed) }
		val last = built.last()
		val finalAdmission = built.flatMap(BuiltRun::facts)
			.maxOfOrNull(CellCapturedFactRevisionEntity::sourceAdmissionOrdinal) ?: 1L
		return Group(
			LogicalTrackingSessionEntity(
				logicalTrackingId = logicalId,
				state = "FINALIZED",
				lifecycleRevision = 2L,
				desiredPlanRevision = last.run.desiredPlanRevision,
				rolloutRevision = ROLLOUT_REVISION,
				startOrigin = START_ORIGIN,
				clockDomainId = BOOT_ID,
				startedAtMs = built.first().run.startedAtMs,
				startedElapsedNanos = built.first().run.startedElapsedNanos,
				cutoffAtMs = sessionEndWall,
				cutoffElapsedNanos = sessionEndElapsed,
				completedAtMs = sessionEndWall,
				finalAdmissionOrdinal = finalAdmission,
				failureCode = null,
				sessionMode = "MANUAL",
				currentManifestRevision = specs.last().manifestRevision,
				currentIntentRevision = 1L,
				currentServiceRunId = null,
				lifecycleLeaseGeneration = LEASE_GENERATION,
				lifecycleBootId = BOOT_ID,
				automationEpoch = null,
			),
			built,
		)
	}

	@Suppress("LongMethod")
	private fun buildRun(logicalId: String, spec: RunSpec, sessionEndElapsed: Long): BuiltRun {
		val segmentId = spec.globalRevision
		val runEndElapsed = spec.startElapsedNanos + RUN_DURATION_NANOS
		val runEndWall = spec.startWallMs + RUN_DURATION_MS
		val segment = SessionSegment(
			id = segmentId,
			startTimeMs = spec.startWallMs,
			endTimeMs = runEndWall,
			distanceM = 0f,
			steps = null,
			primaryActivity = null,
			activityConfidence = null,
			sampleCount = 0,
			source = SegmentSource.USER_CREATED,
			inferenceVersion = null,
			createdAt = runEndWall,
			logicalTrackingId = logicalId,
			serviceRunId = spec.runId,
		)
		val run = SourceServiceRunEntity(
			serviceRunId = spec.runId,
			logicalTrackingId = logicalId,
			state = "FINALIZED",
			desiredPlanRevision = spec.globalRevision,
			rolloutRevision = ROLLOUT_REVISION,
			foregroundCapabilityFlags = 0L,
			startedAtMs = spec.startWallMs,
			startedElapsedNanos = spec.startElapsedNanos,
			completedAtMs = runEndWall,
			completionReason = "USER_STOP",
			bootId = BOOT_ID,
			leaseGeneration = LEASE_GENERATION,
			startOrigin = START_ORIGIN,
			desiredForegroundCapabilityFlags = 0L,
			appliedForegroundCapabilityFlags = 0L,
			runtimeAcknowledgement = "STOP_ACCEPTED",
			runtimeFailureCode = null,
			runRevision = 2L,
			startDeliveryToken = "start-${spec.globalRevision}",
			startCommandGeneration = 1L,
			preparedManifestRevision = spec.manifestRevision,
			preparedIntentRevision = 1L,
			androidDeliveryState = "FOREGROUND_ACCEPTED",
			androidDeliveryUpdatedAtMs = spec.startWallMs,
			startIsUserInitiated = true,
			startIsAmbient = false,
			sessionSegmentId = segmentId,
			presentationAcknowledgement = SourceServiceRunEntity.PRESENTATION_QUIESCED,
			presentationAcknowledgedAtMs = runEndWall,
		)
		val source = SessionManifestSourceEntity(
			logicalTrackingId = logicalId,
			manifestRevision = spec.manifestRevision,
			sourceKind = CELL_SOURCE,
			purpose = SourceBrokerPurpose.SESSION_CAPTURE,
			consentEpoch = spec.globalRevision,
			persistenceEligible = true,
			qosCode = QOS_CODE,
			outputDestination = SourceDestinationOwnerEntity.DESTINATION_SESSION_CELL,
			writerOwner = SourceDestinationOwnerEntity.OWNER_CELL_SESSION_FACTS,
			writerOwnerGeneration = SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
			writerProjectionId = SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID,
			writerProjectionVersion = SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION,
			writerBindingGeneration = SourceDestinationOwnerEntity.CELL_FACT_BINDING_GENERATION,
		)
		val unsignedManifest = SessionManifestVersionEntity(
			logicalTrackingId = logicalId,
			manifestRevision = spec.manifestRevision,
			serviceRunId = spec.runId,
			sessionMode = "MANUAL",
			sourcePolicyRevision = spec.globalRevision,
			acquisitionPlanRevision = spec.globalRevision,
			rolloutRevision = ROLLOUT_REVISION,
			startOrigin = START_ORIGIN,
			effectiveBootId = BOOT_ID,
			effectiveElapsedRealtimeNanos = spec.startElapsedNanos,
			effectiveWallTimeMs = spec.startWallMs,
			zoneId = ZONE_ID,
			automationEpoch = null,
			changeReason = "REPLACEMENT_START",
			manifestChecksum = "pending",
		)
		val manifest = unsignedManifest.copy(
			manifestChecksum = SessionManifestIntegrity.compute(unsignedManifest, listOf(source)),
		)
		val policy = SourcePolicyEntity(
			policyRevision = spec.globalRevision,
			sourceKind = CELL_SOURCE,
			enabled = true,
			qosCode = QOS_CODE,
			locationMinTimeSeconds = null,
			locationMinDistanceMeters = null,
			locationRequiredAccuracyMeters = null,
			capturePersistenceEligible = true,
			controlPersistenceEligible = false,
			ambientPersistenceEligible = false,
			captureConsentEpoch = spec.globalRevision,
			controlConsentEpoch = null,
			ambientConsentEpoch = null,
			effectiveBootId = BOOT_ID,
			effectiveElapsedRealtimeNanos = spec.startElapsedNanos,
			effectiveWallTimeMs = spec.startWallMs,
			changeReason = "TEST",
		)
		val consent = SourceConsentEpochEntity(
			sourceKind = CELL_SOURCE,
			purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
			epoch = spec.globalRevision,
			eligible = true,
			persistenceEligible = true,
			policyRevision = spec.globalRevision,
			effectiveBootId = BOOT_ID,
			effectiveElapsedRealtimeNanos = spec.startElapsedNanos,
			effectiveWallTimeMs = spec.startWallMs,
			changeReason = "TEST",
		)
		val desiredPlan = desiredPlan(spec.globalRevision)
		val plan = requireNotNull(CellHistoryPlanIntegrity.decode(desiredPlan))
		val planHeader = AcquisitionPlanRevisionEntity(
			revision = spec.globalRevision,
			planId = "cell-plan-${spec.globalRevision}",
			createdAtMs = spec.startWallMs,
			status = "EFFECTIVE",
			sourcePolicyRevision = spec.globalRevision,
		)
		if (!spec.hasFact) {
			return BuiltRun(segment, run, manifest, source, policy, consent, planHeader, desiredPlan,
				emptyList(), null, emptyList(), emptyList(), emptyList(),
				completeness(logicalId, spec, null))
		}
		val demand = demand(logicalId, spec, runEndElapsed, runEndWall)
		val authorization = SourceBrokerAuthorization.rows(
			sourceKind = CELL_SOURCE,
			registrationGeneration = spec.globalRevision,
			authorizationRevision = 1L,
			demands = listOf(demand),
			effectiveBootId = BOOT_ID,
			effectiveElapsedRealtimeNanos = spec.startElapsedNanos,
			effectiveWallTimeMs = spec.startWallMs,
		)
		val deny = SourceBrokerAuthorization.rows(
			sourceKind = CELL_SOURCE,
			registrationGeneration = spec.globalRevision,
			authorizationRevision = 2L,
			demands = emptyList(),
			effectiveBootId = BOOT_ID,
			effectiveElapsedRealtimeNanos = runEndElapsed,
			effectiveWallTimeMs = runEndWall,
		)
		val provider = ProviderRegistrationGenerationEntity(
			sourceKind = CELL_SOURCE,
			registrationGeneration = spec.globalRevision,
			sourceInstanceId = "cell-instance-${spec.globalRevision}",
			ownerScope = "source-broker:$CELL_SOURCE",
			clockDomainId = BOOT_ID,
			physicalConfigurationFingerprint = plan.physicalConfigurationFingerprint,
			collectedDataEpoch = 0L,
			providerResidency = ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND,
			providerProcessIncarnationId = "process-${spec.globalRevision}",
			status = ProviderRegistrationGenerationEntity.STATUS_RETIRED,
			reservedAtMs = spec.startWallMs,
			reservedElapsedRealtimeNanos = spec.startElapsedNanos,
			acceptedAtMs = spec.startWallMs,
			acceptedElapsedRealtimeNanos = spec.startElapsedNanos,
			retiredAtMs = runEndWall,
			retiredElapsedRealtimeNanos = runEndElapsed,
			failureCode = null,
			captureCallbackBarrierAuthorizationRevision = 1L,
		)
		val authority = FactAuthority(
			logicalId, spec, segmentId, plan.physicalConfigurationFingerprint,
			authorization.first().authorizationFingerprint, sessionEndElapsed, runEndElapsed,
		)
		val ownerV1 = aggregateFact(authority, admissionOrdinal(spec, 1), correctionOpen = true)
		val ownerV2 = settle(ownerV1, runEndElapsed, sessionEndElapsed)
		val dependent = coverageFact(authority, admissionOrdinal(spec, 2), ownerV2)
		return BuiltRun(
			segment, run, manifest, source, policy, consent, planHeader, desiredPlan,
			listOf(demand), provider, authorization + deny,
			listOf(ownerV1, ownerV2, dependent),
			listOf(cursor(ownerV2), cursor(dependent)),
			completeness(logicalId, spec, dependent.sourceAdmissionOrdinal),
		)
	}

	@Suppress("LongMethod")
	private fun baseFact(
		authority: FactAuthority,
		admissionOrdinal: Long,
		coverageOffset: Long,
	): CellCapturedFactRevisionEntity {
		val providerTime = authority.spec.startElapsedNanos + coverageOffset
		val payload = cellPayload(providerTime)
		val deliveryIdentity = canonicalDeliveryIdentity(payload, BOOT_ID)
		val logicalFactId = CellCapturedFactRevisionIntegrity.logicalFactId(
			deliveryIdentity, authority.logicalId, authority.spec.runId, authority.segmentId,
			authority.spec.manifestRevision, 0L, 0L,
		)
		val unsigned = CellCapturedFactRevisionEntity(
			writerProjectionId = SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID,
			writerProjectionVersion = SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION,
			writerBindingGeneration = SourceDestinationOwnerEntity.CELL_FACT_BINDING_GENERATION,
			writerOwnerGeneration = SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
			logicalFactId = logicalFactId,
			semanticRevision = 1L,
			supersedesSemanticRevision = null,
			mutationId = CellCapturedFactRevisionIntegrity.mutationId(logicalFactId, 1L),
			factKind = CellCapturedFactRevisionEntity.FACT_KIND_AGGREGATE,
			aggregateOwnerLogicalFactId = null,
			aggregateOwnerSemanticRevision = null,
			logicalTrackingId = authority.logicalId,
			serviceRunId = authority.spec.runId,
			sessionSegmentId = authority.segmentId,
			purpose = SourceBrokerPurpose.SESSION_CAPTURE,
			sourceDeliveryIdentity = deliveryIdentity,
			sourceEventId = "cell-event-$admissionOrdinal",
			sourceAdmissionOrdinal = admissionOrdinal,
			walIntegrityIdentity = ZERO_SHA,
			payloadChecksum = ZERO_SHA,
			deliveryUnitIndex = 0,
			deliveryUnitCount = 1,
			sourceSequence = admissionOrdinal,
			planAttribution = CellCapturedFactRevisionEntity.PLAN_ATTRIBUTION_CAPTURED_REGISTRATION,
			payloadVersion = LEGACY_CELL_WAL_PAYLOAD_VERSION,
			canonicalProviderSemanticsDigest = deliveryIdentity,
			sourceInstanceId = "cell-instance-${authority.spec.globalRevision}",
			registrationGeneration = authority.spec.globalRevision,
			configurationRevision = authority.spec.globalRevision,
			physicalConfigurationFingerprint = authority.physicalFingerprint,
			authorizationRevision = 1L,
			authorizationFingerprint = authority.authorizationFingerprint,
			purposeEligibilityMask = SourceBrokerPurpose.MASK_SESSION_CAPTURE,
			sourcePolicyRevision = authority.spec.globalRevision,
			captureConsentEpoch = authority.spec.globalRevision,
			manifestRevision = authority.spec.manifestRevision,
			lifecycleLeaseGeneration = LEASE_GENERATION,
			collectedDataEpoch = 0L,
			scopeDeletionGeneration = 0L,
			clockDomainId = BOOT_ID,
			storedZoneId = ZONE_ID,
			structuralEpochDay = Instant.ofEpochMilli(authority.spec.startWallMs + coverageOffset / 1_000_000L)
				.atZone(ZoneId.of(ZONE_ID)).toLocalDate().toEpochDay(),
			providerAcceptanceStartNanos = authority.spec.startElapsedNanos,
			providerAcceptanceEndNanos = Long.MAX_VALUE,
			authorizationEffectStartNanos = authority.spec.startElapsedNanos,
			authorizationEffectEndNanos = Long.MAX_VALUE,
			consentEffectStartNanos = authority.spec.startElapsedNanos,
			consentEffectEndNanos = Long.MAX_VALUE,
			sessionRunEffectStartNanos = authority.spec.startElapsedNanos,
			sessionRunEffectEndNanos = Long.MAX_VALUE,
			deletionEffectStartNanos = authority.spec.startElapsedNanos,
			deletionEffectEndNanos = Long.MAX_VALUE,
			maximumObservationAgeNanos = 1_000_000_000L,
			observedIntervalStartNanos = authority.spec.startElapsedNanos + coverageOffset,
			observedElapsedNanos = authority.spec.startElapsedNanos + coverageOffset,
			receivedElapsedNanos = authority.spec.startElapsedNanos + coverageOffset + 10_000_000L,
			coverageIntervalStartNanos = authority.spec.startElapsedNanos + coverageOffset,
			coverageIntervalEndNanos = authority.spec.startElapsedNanos + coverageOffset,
			observedWallTimeMs = authority.spec.startWallMs + coverageOffset / 1_000_000L,
			wallTimeUncertaintyMs = 1L,
			acquiredAtMs = authority.spec.startWallMs + coverageOffset / 1_000_000L,
			createdAtMs = authority.spec.startWallMs + coverageOffset / 1_000_000L,
			qualityFlags = 0L,
			qualityConfidence = null,
			availability = CellCapturedFactRevisionEntity.AVAILABILITY_AVAILABLE,
			submittedChildCount = 1,
			acceptedChildCount = 1,
			staleChildCount = 0,
			futureTimeChildCount = 0,
			missingTimeChildCount = 0,
			clockUnverifiableChildCount = 0,
			authorityMismatchChildCount = 0,
			unsupportedTechnologyChildCount = 0,
			subscriptionCompleteness = CellCapturedFactRevisionEntity.SUBSCRIPTION_COMPLETENESS_UNKNOWN,
			childCompleteness = CellCapturedFactRevisionEntity.CHILD_COMPLETENESS_COMPLETE,
			observationCount = 1,
			registeredObservationCount = 1,
			gsmCount = 0,
			cdmaCount = 0,
			wcdmaCount = 0,
			tdscdmaCount = 0,
			lteCount = 1,
			nrCount = 0,
			qualityUnknownCount = 0,
			qualityNoneOrUnknownCount = 0,
			qualityPoorCount = 0,
			qualityModerateCount = 0,
			qualityGoodCount = 1,
			qualityGreatCount = 0,
			weakObservationCount = 0,
			knownQualityObservationCount = 1,
			allKnownQualityIsWeak = false,
			effectChecksum = ZERO_SHA,
			appliedAtMs = authority.spec.startWallMs + coverageOffset / 1_000_000L,
		)
		val wal = walFor(unsigned)
		return unsigned.copy(
			walIntegrityIdentity = wal.integrityIdentity,
			payloadChecksum = wal.payloadChecksum,
		)
	}

	private fun aggregateFact(
		authority: FactAuthority,
		admissionOrdinal: Long,
		correctionOpen: Boolean,
	): CellCapturedFactRevisionEntity {
		val base = baseFact(authority, admissionOrdinal, OWNER_OFFSET_NANOS)
		val identified = base.copy(
			mutationId = CellCapturedFactRevisionIntegrity.mutationId(base.logicalFactId, 1L),
			providerAcceptanceEndNanos = if (correctionOpen) {
				Long.MAX_VALUE
			} else {
				authority.runEndElapsed
			},
			authorizationEffectEndNanos = if (correctionOpen) {
				Long.MAX_VALUE
			} else {
				authority.runEndElapsed
			},
			consentEffectEndNanos = if (correctionOpen) {
				Long.MAX_VALUE
			} else {
				authority.runEndElapsed
			},
			sessionRunEffectEndNanos = if (correctionOpen) {
				Long.MAX_VALUE
			} else {
				authority.runEndElapsed
			},
			deletionEffectEndNanos = if (correctionOpen) {
				Long.MAX_VALUE
			} else {
				authority.sessionEndElapsed
			},
		)
		return identified.copy(effectChecksum = CellCapturedFactRevisionIntegrity.effectChecksum(identified))
	}

	private fun settle(
		first: CellCapturedFactRevisionEntity,
		runEndElapsed: Long,
		sessionEndElapsed: Long,
	): CellCapturedFactRevisionEntity {
		val unsigned = first.copy(
			semanticRevision = 2L,
			supersedesSemanticRevision = 1L,
			mutationId = CellCapturedFactRevisionIntegrity.mutationId(first.logicalFactId, 2L),
			providerAcceptanceEndNanos = runEndElapsed,
			authorizationEffectEndNanos = runEndElapsed,
			consentEffectEndNanos = runEndElapsed,
			sessionRunEffectEndNanos = runEndElapsed,
			deletionEffectEndNanos = sessionEndElapsed,
			effectChecksum = ZERO_SHA,
		)
		return unsigned.copy(effectChecksum = CellCapturedFactRevisionIntegrity.effectChecksum(unsigned))
	}

	private fun coverageFact(
		authority: FactAuthority,
		admissionOrdinal: Long,
		owner: CellCapturedFactRevisionEntity,
	): CellCapturedFactRevisionEntity {
		val base = baseFact(authority, admissionOrdinal, DEPENDENT_OFFSET_NANOS)
		val unsigned = base.copy(
			mutationId = CellCapturedFactRevisionIntegrity.mutationId(base.logicalFactId, 1L),
			factKind = CellCapturedFactRevisionEntity.FACT_KIND_COVERAGE_ONLY,
			aggregateOwnerLogicalFactId = owner.logicalFactId,
			aggregateOwnerSemanticRevision = owner.semanticRevision,
			providerAcceptanceEndNanos = authority.runEndElapsed,
			authorizationEffectEndNanos = authority.runEndElapsed,
			consentEffectEndNanos = authority.runEndElapsed,
			sessionRunEffectEndNanos = authority.runEndElapsed,
			deletionEffectEndNanos = authority.sessionEndElapsed,
			observationCount = null,
			registeredObservationCount = null,
			gsmCount = null,
			cdmaCount = null,
			wcdmaCount = null,
			tdscdmaCount = null,
			lteCount = null,
			nrCount = null,
			qualityUnknownCount = null,
			qualityNoneOrUnknownCount = null,
			qualityPoorCount = null,
			qualityModerateCount = null,
			qualityGoodCount = null,
			qualityGreatCount = null,
			weakObservationCount = null,
			knownQualityObservationCount = null,
			allKnownQualityIsWeak = null,
			effectChecksum = ZERO_SHA,
		)
		return unsigned.copy(effectChecksum = CellCapturedFactRevisionIntegrity.effectChecksum(unsigned))
	}

	private fun boundarylessProviderGroup(
		status: String,
		failureCode: String?,
	): Group {
		val original = buildGroup(groupIndex = 81, runCount = 1, factRunIndexes = setOf(0))
		val built = original.runs.single()
		val provider = requireNotNull(built.provider).copy(
			status = status,
			retiredAtMs = null,
			retiredElapsedRealtimeNanos = null,
			failureCode = failureCode,
		)
		// Reusing an aggregate requires a bounded owner. A prior-boot provider has no
		// observed stop boundary, so this fixture keeps its independent aggregate.
		val facts = built.facts.filter {
			it.factKind == CellCapturedFactRevisionEntity.FACT_KIND_AGGREGATE
		}.map { fact ->
			val unsigned = fact.copy(
				providerAcceptanceEndNanos = Long.MAX_VALUE,
				effectChecksum = ZERO_SHA,
			)
			unsigned.copy(
				effectChecksum = CellCapturedFactRevisionIntegrity.effectChecksum(unsigned),
			)
		}
		val currentFacts = facts.groupBy(CellCapturedFactRevisionEntity::logicalFactId)
			.values.map { lineage ->
				lineage.maxBy(CellCapturedFactRevisionEntity::semanticRevision)
			}
		return original.copy(
			session = original.session.copy(finalAdmissionOrdinal = facts.maxOf {
				it.sourceAdmissionOrdinal
			}),
			runs = listOf(
				built.copy(
					provider = provider,
					facts = facts,
					cursors = currentFacts.map(::cursor),
					completeness = built.completeness.copy(
						lastAdmissionOrdinal = facts.maxOf { it.sourceAdmissionOrdinal },
						lastSourceSequence = facts.maxOf { it.sourceSequence },
					),
				),
			),
		)
	}

	private fun cursor(fact: CellCapturedFactRevisionEntity) = CellCapturedFactCursorEntity(
		writerProjectionId = fact.writerProjectionId,
		writerProjectionVersion = fact.writerProjectionVersion,
		logicalFactId = fact.logicalFactId,
		logicalTrackingId = fact.logicalTrackingId,
		serviceRunId = fact.serviceRunId,
		sessionSegmentId = fact.sessionSegmentId,
		writerOwnerGeneration = fact.writerOwnerGeneration,
		collectedDataEpoch = fact.collectedDataEpoch,
		scopeDeletionGeneration = fact.scopeDeletionGeneration,
		latestSemanticRevision = fact.semanticRevision,
		latestMutationId = fact.mutationId,
		latestEffectChecksum = fact.effectChecksum,
		latestSourceAdmissionOrdinal = fact.sourceAdmissionOrdinal,
		cursorRevision = fact.semanticRevision,
		updatedAtMs = fact.appliedAtMs,
	)

	private fun ownerCursor(group: Group): CellCapturedFactCursorEntity = group.runs.single().cursors
		.single { cursor -> cursor.latestSemanticRevision == 2L }

	private fun cellFactLookupRequest(
		fact: CellCapturedFactRevisionEntity,
	): SourceFactLookupRequest = SourceFactLookupRequest(
		key = SourceProofKey(
			source = TrackingSource.CELL,
			purpose = TrackingPurpose.SESSION_CAPTURE,
			evidenceIdentity = SourceEvidenceIdentity(fact.sourceEventId),
			sourcePolicyRevision = fact.sourcePolicyRevision,
			captureConsentEpoch = fact.captureConsentEpoch,
			collectedDataEpoch = fact.collectedDataEpoch,
			scope = SourceProofScope.Run(fact.logicalTrackingId, fact.serviceRunId),
		),
		materialized = MaterializedProofState(
			lineage = SourceProofLineage(
				evidenceIdentity = SourceEvidenceIdentity(fact.sourceEventId),
				sourceAdmissionOrdinal = fact.sourceAdmissionOrdinal,
				sourceEvidenceRevision = 0L,
				factIdentity = SourceFactIdentity(fact.logicalFactId),
				semanticRevision = fact.semanticRevision,
				writer = SourceProofWriterIdentity(
					fact.writerProjectionId,
					fact.writerProjectionVersion,
					fact.writerBindingGeneration,
					fact.writerOwnerGeneration,
				),
				cursorRevision = fact.semanticRevision,
			),
			coverage = if (fact.coverageIntervalEndNanos > fact.coverageIntervalStartNanos) {
				SourceProofCoverage.ProviderInterval(
					fact.coverageIntervalStartNanos,
					fact.coverageIntervalEndNanos,
				)
			} else {
				SourceProofCoverage.ProviderPoint(fact.coverageIntervalStartNanos)
			},
			completeness = when (fact.childCompleteness) {
				CellCapturedFactRevisionEntity.CHILD_COMPLETENESS_COMPLETE ->
					SourceProofCompleteness.COMPLETE
				CellCapturedFactRevisionEntity.CHILD_COMPLETENESS_PARTIAL ->
					SourceProofCompleteness.PARTIAL
				else -> SourceProofCompleteness.UNKNOWN
			},
		),
	)

	private fun walFor(fact: CellCapturedFactRevisionEntity): SourceEventWalEntity {
		val payload = cellPayload(fact.observedElapsedNanos)
		val encoded = CellWalPayloadCodec.encode(payload, fact.payloadVersion)
		val unsigned = SourceEventWalEntity(
			admissionOrdinal = fact.sourceAdmissionOrdinal,
			eventId = fact.sourceEventId,
			providerDedupKey = null,
			deliveryIdentity = fact.sourceDeliveryIdentity,
			deliveryUnitIndex = fact.deliveryUnitIndex,
			deliveryUnitCount = fact.deliveryUnitCount,
			logicalTrackingId = fact.logicalTrackingId,
			serviceRunId = fact.serviceRunId,
			sourceKind = CELL_SOURCE,
			sourceInstanceId = fact.sourceInstanceId,
			registrationGeneration = fact.registrationGeneration,
			physicalConfigurationFingerprint = fact.physicalConfigurationFingerprint,
			authorizationRevision = fact.authorizationRevision,
			authorizationPurposeEligibilityMask = fact.purposeEligibilityMask,
			authorizationFingerprint = fact.authorizationFingerprint,
			sourceSequence = fact.sourceSequence,
			configRevision = fact.configurationRevision,
			planAttribution = 0,
			clockDomainId = fact.clockDomainId,
			observedElapsedNanos = fact.observedElapsedNanos,
			observedIntervalStartNanos = fact.observedIntervalStartNanos,
			receivedElapsedNanos = fact.receivedElapsedNanos,
			wallTimeMs = fact.observedWallTimeMs,
			wallTimeUncertaintyMs = fact.wallTimeUncertaintyMs,
			capturedCollectedDataEpoch = fact.collectedDataEpoch,
			sourcePolicyRevision = fact.sourcePolicyRevision,
			captureConsentEpoch = fact.captureConsentEpoch,
			sessionManifestRevision = fact.manifestRevision,
			lifecycleLeaseGeneration = fact.lifecycleLeaseGeneration,
			acquiredAtMs = fact.acquiredAtMs,
			qualityFlags = fact.qualityFlags,
			qualityConfidence = fact.qualityConfidence,
			payloadVersion = fact.payloadVersion,
			payload = encoded.toByteArray(),
			payloadChecksum = encoded.checksum,
			integrityIdentity = SourceEventWalEntity.LEGACY_PENDING_CHECKSUM,
			createdAtMs = fact.createdAtMs,
		)
		return unsigned.copy(integrityIdentity = unsigned.calculatedIntegrityIdentity())
	}

	private fun cellPayload(providerTimeNanos: Long) = CellWalPayload(
		subscriptionId = null,
		observations = listOf(
			CellWalPayloadObservation(
				identifierToken = "",
				radioType = "LTE",
				registered = true,
				signalLevelDbm = -95,
				providerTimestampNanos = providerTimeNanos,
			),
		),
		refreshOutcomeCode = CELL_REFRESH_OUTCOME_CALLBACK,
		observationZoneId = ZONE_ID,
	)

	private fun canonicalDeliveryIdentity(payload: CellWalPayload, clockDomainId: String): String =
		canonicalCellProviderDeliveryIdentity(
			clockDomainId,
			payload.observations.map { observation ->
				CellProviderDeliveryIdentityFact(
					radioType = observation.radioType,
					registered = observation.registered,
					signalLevelDbm = observation.signalLevelDbm,
					providerTimestampNanos = requireNotNull(observation.providerTimestampNanos),
				)
			},
			LEGACY_CELL_WAL_PAYLOAD_VERSION,
		)

	private suspend fun assertRecentFactIntegrityFailure() {
		val page = repository { true }.recent(1) as CellHistoryPage.Available
		val entry = page.entries.single()
		entry.state shouldBe CellHistoryProductState.FAILED
		entry.causes shouldBe setOf(CellHistoryCause.FACT_INTEGRITY_FAILED)
		entry.observations shouldBe emptyList()
	}

	private fun restoreCursorHead(cursor: CellCapturedFactCursorEntity) {
		database.openHelper.writableDatabase.execSQL(
			"UPDATE cell_captured_fact_cursor SET latest_semantic_revision = ?, " +
				"latest_mutation_id = ?, latest_effect_checksum = ?, " +
				"latest_source_admission_ordinal = ? WHERE logical_fact_id = ?",
			arrayOf<Any?>(
				cursor.latestSemanticRevision,
				cursor.latestMutationId,
				cursor.latestEffectChecksum,
				cursor.latestSourceAdmissionOrdinal,
				cursor.logicalFactId,
			),
		)
	}

	private fun demand(logicalId: String, spec: RunSpec, runEndElapsed: Long, runEndWall: Long) =
		SourceDemandEntity(
			demandId = "demand-${spec.globalRevision}",
			consumerId = "session:${spec.runId}",
			sourceKind = CELL_SOURCE,
			purpose = SourceBrokerPurpose.SESSION_CAPTURE,
			logicalTrackingId = logicalId,
			serviceRunId = spec.runId,
			manifestRevision = spec.manifestRevision,
			lifecycleLeaseGeneration = LEASE_GENERATION,
			sourcePolicyRevision = spec.globalRevision,
			consentEpoch = spec.globalRevision,
			persistenceEligible = true,
			qosCode = QOS_CODE,
			minimumAcquisitionSpec = "cell:v1:change_callbacks",
			adaptiveReductionAllowed = false,
			maximumAgeMs = 1_000L,
			desiredLatencyMs = 1_000L,
			requestedDeliveryLatencyMs = 0L,
			requestedBootId = BOOT_ID,
			requestedElapsedRealtimeNanos = spec.startElapsedNanos,
			requestedAtMs = spec.startWallMs,
			status = SourceDemandEntity.STATUS_RETIRED,
			retireBootId = BOOT_ID,
			retireElapsedRealtimeNanos = runEndElapsed,
			retiredAtMs = runEndWall,
		)

	private fun completeness(logicalId: String, spec: RunSpec, lastOrdinal: Long?) =
		SourceSessionCompletenessEntity(
			logicalTrackingId = logicalId,
			serviceRunId = spec.runId,
			sourceKind = CELL_SOURCE,
			sourceInstanceId = if (lastOrdinal == null) {
				"cell-unregistered-${spec.globalRevision}"
			} else
				{
				"cell-instance-${spec.globalRevision}"
			},
			registrationGeneration = if (lastOrdinal == null) {
				0L
			} else {
				spec.globalRevision
			},
			lastAdmissionOrdinal = lastOrdinal,
			lastSourceSequence = lastOrdinal,
			appDrainComplete = true,
			providerCoverage = "PROVIDER_COMPLETENESS_UNOBSERVABLE",
			stopStatus = "COMPLETE",
			unresolvedSequenceStart = null,
			unresolvedSequenceEnd = null,
			updatedAtMs = spec.startWallMs + RUN_DURATION_MS,
		)

	private fun desiredPlan(revision: Long): SourceDesiredPlanEntity {
		val payload = ByteArrayOutputStream().use { bytes ->
			DataOutputStream(bytes).use { output ->
				output.writeInt(1)
				output.writeUTF("CELL")
				output.writeLong(revision)
				output.writeUTF("OBSERVE_CHANGES")
				output.writeLong(60_000L)
				output.writeLong(1_000L)
				output.writeInt(0)
				output.writeLong(1_000L)
				output.writeLong(60_000L)
				output.writeDouble(2.0)
			}
			bytes.toByteArray()
		}
		return SourceDesiredPlanEntity(revision, CELL_SOURCE, 1, payload, sha256(payload))
	}

	private fun lane(throughOrdinal: Long) = SourceProductProjectionLaneEntity(
		sourceKind = CELL_SOURCE,
		bindingGeneration = SourceDestinationOwnerEntity.CELL_FACT_BINDING_GENERATION,
		projectionId = SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID,
		projectionVersion = SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION,
		captureModeMask = 1L,
		productStage = SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL,
		activatedRolloutRevision = ROLLOUT_REVISION,
		activationOrdinal = 1L,
		contiguousAdmissionOrdinal = throughOrdinal,
		captureAdmissionCutoffOrdinal = null,
		retentionRequired = true,
		status = SourceProductProjectionLaneEntity.STATUS_ACTIVE,
		terminalDisposition = null,
		terminalAtMs = null,
		installedAtMs = 0L,
		updatedAtMs = 0L,
	)

	private fun admissionOrdinal(spec: RunSpec, offset: Long) = spec.globalRevision * 10L + offset

	private fun sha256(text: String): String = sha256(text.toByteArray(Charsets.UTF_8))

	private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
		.digest(bytes).joinToString("") { "%02x".format(it) }

	private data class Group(val session: LogicalTrackingSessionEntity, val runs: List<BuiltRun>)

	@Suppress("LongParameterList")
	private data class BuiltRun(
		val segment: SessionSegment,
		val run: SourceServiceRunEntity,
		val manifest: SessionManifestVersionEntity,
		val source: SessionManifestSourceEntity,
		val policy: SourcePolicyEntity,
		val consent: SourceConsentEpochEntity,
		val planHeader: AcquisitionPlanRevisionEntity,
		val desiredPlan: SourceDesiredPlanEntity,
		val demands: List<SourceDemandEntity>,
		val provider: ProviderRegistrationGenerationEntity?,
		val authorizations: List<SourceAuthorizationEntity>,
		val facts: List<CellCapturedFactRevisionEntity>,
		val cursors: List<CellCapturedFactCursorEntity>,
		val completeness: SourceSessionCompletenessEntity,
	)

	private data class RunSpec(
		val manifestRevision: Long,
		val globalRevision: Long,
		val runId: String,
		val startWallMs: Long,
		val startElapsedNanos: Long,
		val hasFact: Boolean,
	)

	private data class FactAuthority(
		val logicalId: String,
		val spec: RunSpec,
		val segmentId: Long,
		val physicalFingerprint: String,
		val authorizationFingerprint: String,
		val sessionEndElapsed: Long,
		val runEndElapsed: Long,
	)

	private companion object {
		const val CELL_SOURCE = SourceDestinationOwnerEntity.SOURCE_CELL
		const val BOOT_ID = "boot-cell-history"
		const val ZONE_ID = "UTC"
		const val START_ORIGIN = "MANUAL_FOREGROUND_START"
		const val QOS_CODE = 2
		const val LEASE_GENERATION = 1L
		const val ROLLOUT_REVISION = 1L
		const val RUN_DURATION_NANOS = 800_000_000L
		const val RUN_DURATION_MS = 800L
		const val OWNER_OFFSET_NANOS = 200_000_000L
		const val DEPENDENT_OFFSET_NANOS = 400_000_000L
		const val WALL_BASE_MS = 1_700_000_000_000L
		const val ZERO_SHA = "0000000000000000000000000000000000000000000000000000000000000000"
	}
}
