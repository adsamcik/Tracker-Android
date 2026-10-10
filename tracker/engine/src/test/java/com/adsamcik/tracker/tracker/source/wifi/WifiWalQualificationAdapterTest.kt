package com.adsamcik.tracker.tracker.source.wifi

import com.adsamcik.tracker.shared.base.database.data.SourceRegistrationStateEntity
import com.adsamcik.tracker.shared.base.database.data.WifiWalCarrierAuthenticator
import com.adsamcik.tracker.tracker.source.coordinator.RoomTrackingRolloutStateStore
import com.adsamcik.tracker.tracker.source.coordinator.TrackingRolloutState
import com.adsamcik.tracker.tracker.source.coordinator.CaptureReachabilityMode
import com.adsamcik.tracker.tracker.source.model.SourceEvidenceCandidate
import com.adsamcik.tracker.tracker.source.model.SourceDeliveryCandidate
import com.adsamcik.tracker.tracker.source.model.SourceDeliveryUnit
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceQuality
import com.adsamcik.tracker.tracker.source.ingress.RoomDurableSourceIngress
import com.adsamcik.tracker.tracker.source.ingress.DeliveryAdmissionResult
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleSnapshot
import javax.inject.Provider
import io.mockk.coEvery
import io.mockk.mockk
import com.adsamcik.tracker.tracker.source.lifecycle.DefaultSourceLifecycleProofAdapterFactory
import com.adsamcik.tracker.tracker.source.location.LocationWalQualificationAdapter
import com.adsamcik.tracker.tracker.source.activity.ActivityCapturedWalAdmissionAdapter
import com.adsamcik.tracker.stats.api.repository.SourceProofKey
import com.adsamcik.tracker.stats.api.repository.SourceEvidenceIdentity
import com.adsamcik.tracker.stats.api.repository.SourceProofScope
import com.adsamcik.tracker.stats.api.repository.SourceProofResult
import com.adsamcik.tracker.stats.api.repository.RecordingProofState
import com.adsamcik.tracker.shared.model.tracking.TrackingSource
import com.adsamcik.tracker.shared.model.tracking.TrackingPurpose
import com.adsamcik.tracker.tracker.source.cell.CellWalQualificationAdapter
import android.app.Application
import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.tracker.source.advanceRetainedSourceOperations
import com.adsamcik.tracker.shared.base.database.persistCapturedRadioFactRetentionProofs
import com.adsamcik.tracker.shared.base.database.dao.synchronizeLifecycle
import com.adsamcik.tracker.shared.base.database.data.AcquisitionPlanRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.CapturedRadioFactRetentionProofEntity
import com.adsamcik.tracker.shared.base.database.data.LifecycleDesiredActionEntity
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDesiredPlanEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceEventRetentionProofEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventRetentionScopeProofEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.LEGACY_WIFI_WAL_PAYLOAD_VERSION
import com.adsamcik.tracker.shared.base.database.data.WIFI_WAL_PAYLOAD_VERSION
import com.adsamcik.tracker.shared.base.time.BootClockDomainProvider
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionFailureEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductLaneExecutionAuthority
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceRunRetirementEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import com.adsamcik.tracker.shared.base.database.data.WifiCaptureDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.WifiCapturedFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.WifiCapturedFactRevisionIntegrity
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.stats.api.repository.ExportPortableCapturedWifiRequest
import com.adsamcik.tracker.stats.api.repository.ExportPortableCapturedWifiResult
import com.adsamcik.tracker.stats.api.repository.DeleteSelectedWifiHistoryRequest
import com.adsamcik.tracker.stats.api.repository.DeleteSelectedWifiHistoryResult
import com.adsamcik.tracker.stats.api.repository.ImportPortableCapturedWifiRequest
import com.adsamcik.tracker.stats.api.repository.ImportPortableCapturedWifiResult
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductEvaluation
import com.adsamcik.tracker.stats.api.repository.PortableCapturedWifiEntryV1
import com.adsamcik.tracker.stats.api.repository.PortableCapturedWifiImportReceipt
import com.adsamcik.tracker.stats.api.repository.PortableWifiAcquisitionCompleteness
import com.adsamcik.tracker.stats.api.repository.PortableWifiCaptureCoverage
import com.adsamcik.tracker.stats.api.repository.PortableWifiIdentityKind
import com.adsamcik.tracker.stats.api.repository.PortableWifiIntegrity
import com.adsamcik.tracker.stats.api.repository.PortableWifiOpaqueIdentity
import com.adsamcik.tracker.stats.api.repository.PortableWifiRunAvailability
import com.adsamcik.tracker.stats.api.repository.PortableWifiUnavailableReason
import com.adsamcik.tracker.stats.api.repository.PortableWifiUnverifiableReason
import com.adsamcik.tracker.stats.api.repository.ReexportImportedCapturedWifiRequest
import com.adsamcik.tracker.stats.api.repository.ReexportImportedCapturedWifiResult
import com.adsamcik.tracker.stats.api.repository.WifiCapturedPortableFormatV1
import com.adsamcik.tracker.stats.api.repository.WifiImportedHistorySelection
import com.adsamcik.tracker.stats.api.repository.WifiImportedHistorySelectionKey
import com.adsamcik.tracker.stats.api.repository.WifiHistorySelection
import com.adsamcik.tracker.stats.api.repository.WifiLocalHistorySelectionKey
import com.adsamcik.tracker.stats.api.repository.WifiDeletedHistoryResult
import com.adsamcik.tracker.tracker.source.coordinator.ExecutableSourceLaneCatalog
import com.adsamcik.tracker.tracker.source.coordinator.SourcePlanCodec
import com.adsamcik.tracker.tracker.source.ingress.DefaultSourcePayloadCodec
import com.adsamcik.tracker.tracker.source.model.DirectSourceDemandPurpose
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.RetryBackoff
import com.adsamcik.tracker.tracker.source.model.SourceDemandContractFactory
import com.adsamcik.tracker.tracker.source.model.SourceEventId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.WifiAccessPointEvidence
import com.adsamcik.tracker.tracker.source.model.WifiMode
import com.adsamcik.tracker.tracker.source.model.WifiPlan
import com.adsamcik.tracker.tracker.source.model.WifiResultSnapshotPayload
import com.adsamcik.tracker.tracker.source.model.physicalConfigurationFingerprint
import com.adsamcik.tracker.tracker.source.runtime.wifiProviderDeliveryIdentity
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WifiWalQualificationAdapterTest {
	private lateinit var database: AppDatabase
	private val payloadCodec = DefaultSourcePayloadCodec()
	private val planCodec = SourcePlanCodec()
	private lateinit var subject: WifiWalQualificationAdapter
	private lateinit var writer: WifiCapturedFactWriter
	private lateinit var maintenance: WifiCapturedFactMaintenance

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		subject = WifiWalQualificationAdapter(
			database,
			payloadCodec,
			planCodec,
			BootClockDomainProvider { BOOT_ID },
		)
		writer = WifiCapturedFactWriter(database, subject)
		maintenance = WifiCapturedFactMaintenance(database, payloadCodec, planCodec)
	}

	@After
	fun tearDown() {
		database.close()
	}


	@Test
	fun `Wi-Fi writer retains named mode agreement when session changes after actual qualification`() = runTest {
		installValidFixture(candidateWriter = true)
		val result = writer.write(EVENT_ID) { checkpoint ->
			if (checkpoint == WifiCapturedWriteCheckpoint.QUALIFIED) {
				val dao = database.sourceSessionDao()
				val session = requireNotNull(dao.session(LOGICAL_ID))
				assertEquals(1, dao.updateSession(session.copy(sessionMode = "AUTOMATIC", automationEpoch = 1L)))
			}
		}
		assertEquals(WifiCapturedWriteResult.Rejected(WifiCapturedWriteRejection.MANIFEST_WRITER_AUTHORITY_MISMATCH), result)
		assertEquals(0L, database.wifiCapturedFactDao().revisionCount())
		assertEquals(0L, database.wifiCapturedFactDao().cursorCount())
		assertEquals("MANUAL", database.sourceSessionDao().session(LOGICAL_ID)?.sessionMode)
	}

	@Test
	fun `final manual Wi-Fi writer keeps original saved details and binding`() = runTest {
		installValidFixture(candidateWriter = true, payloadVersion = WIFI_WAL_PAYLOAD_VERSION)
		val qualified = assertIs<WifiWalAdapterResult.Evaluated>(subject.qualify(EVENT_ID))
		// Real original qualification; this writer-only case constructs the new named binding.
		// The g2 adapter/producer chain is tested separately by the shared-setup companion.
		installWriterContractFixture(2L, "MANUAL", advanceOwner = true)
		val qualifier = mockk<WifiWalQualificationAdapter>()
		coEvery { qualifier.qualify(EVENT_ID, any()) } returns qualified
		val finalWriter = WifiCapturedFactWriter(database, qualifier)
		val applied = assertIs<WifiCapturedWriteResult.Applied>(finalWriter.write(EVENT_ID))
		val saved = requireNotNull(database.wifiCapturedFactDao().revision(
			SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_ID,
			SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_VERSION, applied.logicalFactId, 1L))
		assertEquals(2L, saved.writerBindingGeneration)
		assertEquals(4L, saved.writerOwnerGeneration)
		val children = database.radioObservationChildDao().wifiSessionChildren(
			SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_ID,
			SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_VERSION, applied.logicalFactId, 1L, 65)
		assertEquals(2, children.size)
		assertEquals(EVENT_ID.value, saved.sourceEventId)
		assertIs<WifiCapturedWriteResult.Unchanged>(finalWriter.write(EVENT_ID))
		assertEquals(1L, database.wifiCapturedFactDao().revisionCount())
	}

	@Test
	fun `final automatic Wi-Fi writer keeps original saved details and binding`() = runTest {
		installValidFixture(candidateWriter = true, payloadVersion = WIFI_WAL_PAYLOAD_VERSION)
		val qualified = assertIs<WifiWalAdapterResult.Evaluated>(subject.qualify(EVENT_ID))
		// Real original qualification; this writer-only case constructs the new named binding.
		// The g2 adapter/producer chain is tested separately by the shared-setup companion.
		installWriterContractFixture(2L, "AUTOMATIC", advanceOwner = true)
		val qualifier = mockk<WifiWalQualificationAdapter>()
		coEvery { qualifier.qualify(EVENT_ID, any()) } returns qualified
		val finalWriter = WifiCapturedFactWriter(database, qualifier)
		val applied = assertIs<WifiCapturedWriteResult.Applied>(finalWriter.write(EVENT_ID))
		val saved = requireNotNull(database.wifiCapturedFactDao().revision(
			SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_ID,
			SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_VERSION, applied.logicalFactId, 1L))
		assertEquals(2L, saved.writerBindingGeneration)
		assertEquals(4L, saved.writerOwnerGeneration)
		val children = database.radioObservationChildDao().wifiSessionChildren(
			SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_ID,
			SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_VERSION, applied.logicalFactId, 1L, 65)
		assertEquals(2, children.size)
		assertEquals(EVENT_ID.value, saved.sourceEventId)
		assertIs<WifiCapturedWriteResult.Unchanged>(finalWriter.write(EVENT_ID))
		assertEquals(1L, database.wifiCapturedFactDao().revisionCount())
	}

	@Test
	fun `original Wi-Fi writer cannot borrow automatic mode or final binding original ownership`() = runTest {
		installValidFixture(candidateWriter = true)
		val qualified = assertIs<WifiWalAdapterResult.Evaluated>(subject.qualify(EVENT_ID))
		val qualifier = mockk<WifiWalQualificationAdapter>()
		coEvery { qualifier.qualify(EVENT_ID, any()) } returns qualified
		val finalWriter = WifiCapturedFactWriter(database, qualifier)
		installWriterContractFixture(1L, "AUTOMATIC", advanceOwner = false)
		assertEquals(WifiCapturedWriteResult.Rejected(WifiCapturedWriteRejection.MANIFEST_WRITER_AUTHORITY_MISMATCH), finalWriter.write(EVENT_ID))
		installWriterContractFixture(2L, "AUTOMATIC", advanceOwner = false)
		assertEquals(WifiCapturedWriteResult.Rejected(WifiCapturedWriteRejection.MANIFEST_WRITER_AUTHORITY_MISMATCH), finalWriter.write(EVENT_ID))
		assertEquals(0L, database.wifiCapturedFactDao().revisionCount())
		assertEquals(0L, database.wifiCapturedFactDao().cursorCount())
	}

	@Test
	fun `final manual Wi-Fi maintenance authenticates its exact original named writer`() = runTest {
		installValidFixture(candidateWriter = true, payloadVersion = WIFI_WAL_PAYLOAD_VERSION)
		val qualified = assertIs<WifiWalAdapterResult.Evaluated>(subject.qualify(EVENT_ID))
		installWriterContractFixture(2L, "MANUAL", advanceOwner = true)
		val qualifier = mockk<WifiWalQualificationAdapter>()
		coEvery { qualifier.qualify(EVENT_ID, any()) } returns qualified
		assertIs<WifiCapturedWriteResult.Applied>(WifiCapturedFactWriter(database, qualifier).write(EVENT_ID))
		val markedAt = OBSERVED_WALL_MS + 100L
		database.sourceEvidenceStateDao().synchronizeLifecycle(0L, OBSERVED_WALL_MS, markedAt)
		assertEquals(WifiCapturedRetentionResult.Pruned(1, 1),
			maintenance.pruneAffectedByRetentionFloor(OBSERVED_WALL_MS, 0L, 0L, markedAt))
		assertEquals(0L, database.wifiCapturedFactDao().revisionCount())
		val proof = database.capturedRadioFactRetentionProofDao().allForSource(WIFI_SOURCE).single()
		assertEquals(2L, proof.writerBindingGeneration)
		assertEquals(4L, proof.writerOwnerGeneration)
	}


	@Test
	fun `original Wi-Fi maintenance remains valid after the current owner advances to final`() = runTest {
		installValidFixture(candidateWriter = true)
		assertIs<WifiCapturedWriteResult.Applied>(writer.write(EVENT_ID))
		assertEquals(1, database.sourceDestinationOwnerDao().compareAndSetOwner(WIFI_SOURCE,
			SourceDestinationOwnerEntity.DESTINATION_SESSION_WIFI,
			SourceDestinationOwnerEntity.OWNER_WIFI_SESSION_FACTS, 2L,
			SourceDestinationOwnerEntity.OWNER_WIFI_SESSION_FACTS, 4L, OBSERVED_WALL_MS + 50L))
		val markedAt = OBSERVED_WALL_MS + 100L
		database.sourceEvidenceStateDao().synchronizeLifecycle(0L, OBSERVED_WALL_MS, markedAt)
		assertEquals(WifiCapturedRetentionResult.Pruned(1, 1),
			maintenance.pruneAffectedByRetentionFloor(OBSERVED_WALL_MS, 0L, 0L, markedAt))
		assertEquals(0L, database.wifiCapturedFactDao().revisionCount())
		assertEquals(1L, database.capturedRadioFactRetentionProofDao().allForSource(WIFI_SOURCE).single().writerBindingGeneration)
	}


	@Test
	fun `Wi-Fi maintenance does not lend a final named manifest to an original saved fact`() = runTest {
		installValidFixture(candidateWriter = true)
		assertIs<WifiCapturedWriteResult.Applied>(writer.write(EVENT_ID))
		installWriterContractFixture(2L, "MANUAL", advanceOwner = true)
		val markedAt = OBSERVED_WALL_MS + 100L
		database.sourceEvidenceStateDao().synchronizeLifecycle(0L, OBSERVED_WALL_MS, markedAt)
		assertEquals(WifiCapturedRetentionResult.Blocked(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE),
			maintenance.pruneAffectedByRetentionFloor(OBSERVED_WALL_MS, 0L, 0L, markedAt))
		assertEquals(1L, database.wifiCapturedFactDao().revisionCount())
		assertEquals(0, database.capturedRadioFactRetentionProofDao().allForSource(WIFI_SOURCE).size)
	}


	@Test
	fun `Wi-Fi maintenance rejects a contained current owner before mutation`() = runTest {
		installValidFixture(candidateWriter = true)
		assertIs<WifiCapturedWriteResult.Applied>(writer.write(EVENT_ID))
		assertEquals(1, database.sourceDestinationOwnerDao().compareAndSetOwner(WIFI_SOURCE,
			SourceDestinationOwnerEntity.DESTINATION_SESSION_WIFI,
			SourceDestinationOwnerEntity.OWNER_WIFI_SESSION_FACTS, 2L,
			SourceDestinationOwnerEntity.OWNER_WIFI_SESSION_FACTS, 5L, OBSERVED_WALL_MS + 50L))
		val markedAt = OBSERVED_WALL_MS + 100L
		database.sourceEvidenceStateDao().synchronizeLifecycle(0L, OBSERVED_WALL_MS, markedAt)
		assertEquals(WifiCapturedRetentionResult.Blocked(WifiCapturedRetentionBlockedReason.DESTINATION_OWNER_CHANGED),
			maintenance.pruneAffectedByRetentionFloor(OBSERVED_WALL_MS, 0L, 0L, markedAt))
		assertEquals(1L, database.wifiCapturedFactDao().revisionCount())
	}


	@Test
	fun `native Wi-Fi export retains original authority after constructed quality and End operations`() = runTest {
		installPortableExportFixture()
		val before = mutableListOf<PortableCapturedWifiEntryV1>()
		assertIs<ExportPortableCapturedWifiResult.Exported>(portableExporter().export(
			ExportPortableCapturedWifiRequest(LOGICAL_ID)) { before += it })
		val run = requireNotNull(database.sourceSessionDao().serviceRun(RUN_ID))
		val session = requireNotNull(database.sourceSessionDao().session(LOGICAL_ID))
		val wal = requireNotNull(database.sourceEventWalDao().getByEventId(EVENT_ID.value))
		// Existing real qualifier/writer/Room fixture provides the recording; only later
		// operation-generation and operation-boot transitions are constructed here.
		for ((generation, boot) in listOf(run.leaseGeneration + 1L to run.bootId,
			run.leaseGeneration + 2L to "later-operation-boot")) {
			database.withTransaction {
				database.sourceSessionDao().updateServiceRun(run.copy(leaseGeneration = generation,
					bootId = boot, runRevision = run.runRevision + generation))
				database.sourceSessionDao().updateSession(session.copy(lifecycleLeaseGeneration = generation,
					lifecycleBootId = boot, lifecycleRevision = session.lifecycleRevision + generation))
			}
			val exported = mutableListOf<PortableCapturedWifiEntryV1>()
			assertIs<ExportPortableCapturedWifiResult.Exported>(portableExporter().export(
				ExportPortableCapturedWifiRequest(LOGICAL_ID)) {
				assertEquals(false, database.inTransaction())
				exported += it
			})
			assertEquals(before, exported)
		}
		val savedWal = requireNotNull(database.sourceEventWalDao().getByEventId(EVENT_ID.value))
		assertEquals(wal, savedWal.copy(payload = wal.payload))
		assertTrue(wal.payload.contentEquals(savedWal.payload))
	}

	@Test
	fun `native Wi-Fi export cannot lend newer action generation to original authorization`() = runTest {
		installPortableExportFixture()
		val run = requireNotNull(database.sourceSessionDao().serviceRun(RUN_ID))
		val session = requireNotNull(database.sourceSessionDao().session(LOGICAL_ID))
		database.sourceSessionDao().updateServiceRun(run.copy(leaseGeneration = run.leaseGeneration + 2L,
			runRevision = run.runRevision + 1L))
		database.sourceSessionDao().updateSession(session.copy(lifecycleLeaseGeneration = run.leaseGeneration + 2L))
		database.openHelper.writableDatabase.execSQL(
			"UPDATE lifecycle_desired_action SET lease_generation = ? WHERE service_run_id = ? AND desired_state = 'STARTED'",
			arrayOf<Any>(run.leaseGeneration + 1L, RUN_ID))
		var sinkCalls = 0
		assertIs<ExportPortableCapturedWifiResult.Unverifiable>(portableExporter().export(
			ExportPortableCapturedWifiRequest(LOGICAL_ID)) { sinkCalls++ })
		assertEquals(0, sinkCalls)
	}

	@Test
	fun `native Wi-Fi export preserves exact erased scope after later operations`() = runTest {
		installPortableExportFixture()
		val run = requireNotNull(database.sourceSessionDao().serviceRun(RUN_ID))
		val session = requireNotNull(database.sourceSessionDao().session(LOGICAL_ID))
		database.sourceSessionDao().updateServiceRun(run.copy(leaseGeneration = run.leaseGeneration + 2L,
			runRevision = run.runRevision + 1L))
		database.sourceSessionDao().updateSession(session.copy(lifecycleLeaseGeneration = run.leaseGeneration + 2L))
		installPortableDeletion()
		var sinkCalls = 0
		assertIs<ExportPortableCapturedWifiResult.Deleted>(portableExporter().export(
			ExportPortableCapturedWifiRequest(LOGICAL_ID)) { sinkCalls++ })
		assertEquals(0, sinkCalls)
	}

	@Test
	fun `native Wi-Fi export still rejects observations at the original capture closing boundary`() = runTest {
		installPortableExportFixture()
		val run = requireNotNull(database.sourceSessionDao().serviceRun(RUN_ID))
		val session = requireNotNull(database.sourceSessionDao().session(LOGICAL_ID))
		database.sourceSessionDao().updateServiceRun(run.copy(leaseGeneration = run.leaseGeneration + 2L,
			runRevision = run.runRevision + 1L, bootId = "later-operation-boot"))
		database.sourceSessionDao().updateSession(session.copy(lifecycleLeaseGeneration = run.leaseGeneration + 2L,
			lifecycleBootId = "later-operation-boot"))
		// Replace only the existing deny-all closing snapshot with a correctly encoded earlier
		// closing boundary. The saved scan includes an actual observation exactly at this cutoff.
		database.openHelper.writableDatabase.execSQL(
			"DELETE FROM source_authorization WHERE source_kind = ? AND registration_generation = ? AND authorization_revision = ?",
			arrayOf<Any>(WIFI_SOURCE, REGISTRATION_GENERATION, AUTHORIZATION_REVISION + 1L))
		installPortableClosingAuthorization(effectiveElapsedRealtimeNanos = OBSERVED_END_NANOS,
			effectiveWallTimeMs = OBSERVED_WALL_MS)
		var sinkCalls = 0
		assertIs<ExportPortableCapturedWifiResult.Unverifiable>(portableExporter().export(
			ExportPortableCapturedWifiRequest(LOGICAL_ID)) { sinkCalls++ })
		assertEquals(0, sinkCalls)
	}

	@Test
	fun `exact Wi-Fi-only WAL derives passive plan from manifest and produces identity-free fact`() = runTest {
		installValidFixture(configurationRevision = null)

		val evaluated = assertIs<WifiWalAdapterResult.Evaluated>(subject.qualify(EVENT_ID))
		val fact = assertIs<WifiCapturedFactClassification.FreshChanged>(evaluated.classification).fact

		assertEquals(setOf(SourceKind.WIFI), fact.authority.capturedSources)
		assertEquals(emptySet(), fact.authority.controlSources)
		assertEquals(RUN_ID, fact.authority.serviceRunId.value)
		assertEquals(SEGMENT_ID, fact.authority.sessionSegmentId)
		assertEquals(PLAN_REVISION, fact.authority.configurationRevision)
		assertEquals(ZONE_ID, fact.authority.zoneId)
		assertEquals(SOURCE_SEQUENCE, fact.evidenceBinding.sourceSequence)
		assertEquals(2, fact.aggregate.observationCount)
		assertEquals(2, fact.coverage.acceptedResultCount)
		assertEquals(0, fact.aggregate.bandMix.otherCount)
		assertEquals(1L, database.sourceEventWalDao().countAll())
	}

	@Test
	fun `production first source sequence zero remains valid in qualified evidence`() = runTest {
		installValidFixture(sourceSequence = 0L)

		val evaluated = assertIs<WifiWalAdapterResult.Evaluated>(subject.qualify(EVENT_ID))
		assertEquals(0L, assertIs<WifiCapturedFactClassification.FreshChanged>(
			evaluated.classification,
		).fact.evidenceBinding.sourceSequence)
	}

	@Test
	fun `canonical payload and provider delivery identity are recomputed`() = runTest {
		installValidFixture(deliveryIdentityOverride = "a".repeat(64))

		assertEquals(
			WifiWalAdapterResult.Rejected(WifiWalAdapterRejection.DELIVERY_IDENTITY_MISMATCH),
			subject.qualify(EVENT_ID),
		)
	}

	@Test
	fun `validly resigned trailing payload bytes are rejected`() = runTest {
		installValidFixture()
		val original = requireNotNull(database.sourceEventWalDao().getByEventId(EVENT_ID.value))
		val withTrailingByte = original.copy(payload = original.payload + byteArrayOf(0))
		val withChecksum = withTrailingByte.copy(
			payloadChecksum = withTrailingByte.calculatedPayloadChecksum(),
		)
		val resigned = withChecksum.copy(
			integrityIdentity = withChecksum.calculatedIntegrityIdentity(),
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_event_wal SET payload = ?, payload_checksum = ?, " +
				"integrity_identity = ? WHERE event_id = ?",
			arrayOf(
				resigned.payload,
				resigned.payloadChecksum,
				resigned.integrityIdentity,
				EVENT_ID.value,
			),
		)

		assertEquals(
			WifiWalAdapterResult.Rejected(WifiWalAdapterRejection.PAYLOAD_MISMATCH),
			subject.qualify(EVENT_ID),
		)
	}

	@Test
	fun `one-unit producer rejects an overflow sibling explicitly`() = runTest {
		installValidFixture()
		val original = requireNotNull(database.sourceEventWalDao().getByEventId(EVENT_ID.value))
		val unsignedSibling = original.copy(
			admissionOrdinal = 0L,
			eventId = "wifi-event-sibling",
			deliveryUnitIndex = 1,
			deliveryUnitCount = 2,
			sourceSequence = SOURCE_SEQUENCE + 1L,
			integrityIdentity = SourceEventWalEntity.LEGACY_PENDING_CHECKSUM,
		)
		val sibling = unsignedSibling.copy(
			integrityIdentity = unsignedSibling.calculatedIntegrityIdentity(),
		)
		assertEquals(2L, database.sourceEventWalDao().insertIgnoringDuplicate(sibling))

		assertEquals(
			WifiWalAdapterResult.Rejected(WifiWalAdapterRejection.DELIVERY_CARDINALITY_OVERFLOW),
			subject.qualify(EVENT_ID),
		)
	}

	@Test
	fun `authorization member traversal has an explicit bounded overflow`() = runTest {
		installValidFixture(authorizationDemandCount = 65)

		assertEquals(
			WifiWalAdapterResult.Rejected(WifiWalAdapterRejection.AUTHORIZATION_MEMBER_OVERFLOW),
			subject.qualify(EVENT_ID),
		)
	}

	@Test
	fun `passive WAL requires exact retained plan application settlement`() = runTest {
		installValidFixture(includePlanApplication = false)

		assertEquals(
			WifiWalAdapterResult.Rejected(
				WifiWalAdapterRejection.HISTORICAL_PLAN_APPLICATION_UNVERIFIABLE,
			),
			subject.qualify(EVENT_ID),
		)
	}

	@Test
	fun `in-place plan revision cannot collapse behind matching provider fingerprint`() = runTest {
		installValidFixture(planApplicationRevision = PLAN_REVISION + 1L)

		assertEquals(
			WifiWalAdapterResult.Rejected(
				WifiWalAdapterRejection.HISTORICAL_PLAN_APPLICATION_UNVERIFIABLE,
			),
			subject.qualify(EVENT_ID),
		)
	}

	@Test
	fun `ambiguous plan application lineage is rejected within an explicit bound`() = runTest {
		installValidFixture()
		database.sourceSessionDao().insertLifecycleActions(
			listOf(startAction(actionId = "wifi-start-action-duplicate", actionRevision = 2L)),
		)

		assertEquals(
			WifiWalAdapterResult.Rejected(
				WifiWalAdapterRejection.HISTORICAL_PLAN_APPLICATION_UNVERIFIABLE,
			),
			subject.qualify(EVENT_ID),
		)
	}

	@Test
	fun `malformed persisted Wi-Fi demand floor fails closed`() = runTest {
		installValidFixture(
			authorizationDemandCount = 2,
			lastDemandAcquisitionSpec = "wifi:v1:required=CACHED_ONLY",
		)

		assertEquals(
			WifiWalAdapterResult.Rejected(
				WifiWalAdapterRejection.AUTHORIZATION_MISMATCH,
			),
			subject.qualify(EVENT_ID),
		)
	}

	@Test
	fun `opportunistic Wi-Fi plan cannot claim a provider delivery deadline`() = runTest {
		installValidFixture(requestedDeliveryLatencyMs = 1_000L)

		assertEquals(
			WifiWalAdapterResult.Rejected(
				WifiWalAdapterRejection.HISTORICAL_DEMAND_CONTRACT_UNVERIFIABLE,
			),
			subject.qualify(EVENT_ID),
		)
	}

	@Test
	fun `cached-only plan cannot satisfy captured broadcast demand`() = runTest {
		installValidFixture(plan = wifiPlan().copy(mode = WifiMode.CACHED_ONLY))

		assertEquals(
			WifiWalAdapterResult.Rejected(
				WifiWalAdapterRejection.HISTORICAL_DEMAND_CONTRACT_UNVERIFIABLE,
			),
			subject.qualify(EVENT_ID),
		)
	}

	@Test
	fun `plan result age must satisfy every authorized demand`() = runTest {
		installValidFixture(
			plan = wifiPlan().copy(maximumAcceptableResultAgeMs = 2_000L),
			lastDemandMaximumAgeMs = 1_000L,
		)

		assertEquals(
			WifiWalAdapterResult.Rejected(
				WifiWalAdapterRejection.HISTORICAL_DEMAND_CONTRACT_UNVERIFIABLE,
			),
			subject.qualify(EVENT_ID),
		)
	}

	@Test
	fun `terminal session final admission ordinal must cover selected WAL`() = runTest {
		installValidFixture()
		val session = requireNotNull(database.sourceSessionDao().session(LOGICAL_ID))
		assertEquals(1, database.sourceSessionDao().updateSession(session.copy(finalAdmissionOrdinal = 0L)))

		assertEquals(
			WifiWalAdapterResult.Rejected(WifiWalAdapterRejection.SESSION_MISMATCH),
			subject.qualify(EVENT_ID),
		)
	}

	@Test
	fun `all reachable durable ingress lifecycle pairs remain eligible`() = runTest {
		installValidFixture()
		val admissionPairs = listOf("STARTING", "ACTIVE", "RECONFIGURING").flatMap { session ->
			listOf("STARTING", "ACTIVE").map { run -> session to run }
		}
		val reachablePairs = admissionPairs + listOf("ACTIVE" to "STOPPING", "STOPPING" to "STOPPING")

		reachablePairs.forEach { (sessionState, runState) ->
			setLiveLifecyclePair(sessionState, runState)
			assertIs<WifiWalAdapterResult.Evaluated>(subject.qualify(EVENT_ID))
		}
	}

	@Test
	fun `impossible live lifecycle pairs still fail closed`() = runTest {
		installValidFixture()
		val impossiblePairs = listOf(
			"STARTING" to "STOPPING",
			"RECONFIGURING" to "STOPPING",
			"STOPPING" to "STARTING",
			"STOPPING" to "ACTIVE",
			"ACTIVE" to "FINALIZED",
		)

		impossiblePairs.forEach { (sessionState, runState) ->
			setLiveLifecyclePair(sessionState, runState)
			assertEquals(
				WifiWalAdapterResult.Rejected(WifiWalAdapterRejection.SESSION_MISMATCH),
				subject.qualify(EVENT_ID),
			)
		}
	}

	@Test
	fun `terminal older run remains attributable after session moves to a valid live replacement`() = runTest {
		installValidFixture()
		installLiveReplacement()

		assertIs<WifiWalAdapterResult.Evaluated>(subject.qualify(EVENT_ID))
	}

	@Test
	fun `terminal older run requires the replacement manifest`() = runTest {
		installValidFixture()
		installLiveReplacement()
		database.openHelper.writableDatabase.execSQL(
			"DELETE FROM session_manifest_version WHERE logical_tracking_id = ? AND manifest_revision = ?",
			arrayOf<Any?>(LOGICAL_ID, REPLACEMENT_MANIFEST_REVISION),
		)
		assertEquals(
			WifiWalAdapterResult.Rejected(WifiWalAdapterRejection.SESSION_MISMATCH),
			subject.qualify(EVENT_ID),
		)
	}

	@Test
	fun `terminal older run requires the exact replacement manifest checksum`() = runTest {
		installValidFixture()
		installLiveReplacement()
		database.openHelper.writableDatabase.execSQL(
			"UPDATE session_manifest_version SET manifest_checksum = ? " +
				"WHERE logical_tracking_id = ? AND manifest_revision = ?",
			arrayOf<Any?>("0".repeat(64), LOGICAL_ID, REPLACEMENT_MANIFEST_REVISION),
		)
		assertEquals(
			WifiWalAdapterResult.Rejected(WifiWalAdapterRejection.SESSION_MISMATCH),
			subject.qualify(EVENT_ID),
		)
	}

	@Test
	fun `terminal older run rejects wrong replacement reverse binding`() = runTest {
		installValidFixture()
		installLiveReplacement()
		database.openHelper.writableDatabase.execSQL(
			"UPDATE session_segment SET logical_tracking_id = 'wrong-logical' WHERE id = ?",
			arrayOf(REPLACEMENT_SEGMENT_ID),
		)

		assertEquals(
			WifiWalAdapterResult.Rejected(WifiWalAdapterRejection.SESSION_MISMATCH),
			subject.qualify(EVENT_ID),
		)
	}

	@Test
	fun `terminal older run rejects stale replacement lifecycle action`() = runTest {
		installValidFixture()
		installLiveReplacement()
		database.openHelper.writableDatabase.execSQL(
			"UPDATE lifecycle_desired_action SET lease_generation = lease_generation + 1 WHERE action_id = ?",
			arrayOf(REPLACEMENT_ACTION_ID),
		)

		assertEquals(
			WifiWalAdapterResult.Rejected(WifiWalAdapterRejection.SESSION_MISMATCH),
			subject.qualify(EVENT_ID),
		)
	}

	@Test
	fun `terminal older run rejects a replacement registration from a stale data epoch`() = runTest {
		installValidFixture()
		installLiveReplacement()
		database.openHelper.writableDatabase.execSQL(
			"UPDATE provider_registration_generation SET collected_data_epoch = collected_data_epoch + 1 " +
				"WHERE source_kind = ? AND registration_generation = ?",
			arrayOf<Any?>(WIFI_SOURCE, REPLACEMENT_REGISTRATION_GENERATION),
		)

		assertEquals(
			WifiWalAdapterResult.Rejected(WifiWalAdapterRejection.SESSION_MISMATCH),
			subject.qualify(EVENT_ID),
		)
	}

	@Test
	fun `terminal older run rejects malformed replacement registration clocks and status`() = runTest {
		installValidFixture()
		installLiveReplacement()
		val registration = requireNotNull(database.sourceBrokerDao().registration(
			WIFI_SOURCE,
			REPLACEMENT_REGISTRATION_GENERATION,
		))
		val corruptions = listOf(
			"accepted_at_ms = NULL, accepted_elapsed_realtime_nanos = NULL",
			"reserved_at_ms = accepted_at_ms + 1",
			"reserved_elapsed_realtime_nanos = accepted_elapsed_realtime_nanos + 1",
			"accepted_at_ms = accepted_at_ms + 2",
			"accepted_elapsed_realtime_nanos = accepted_elapsed_realtime_nanos + 2",
			"retired_at_ms = accepted_at_ms, retired_elapsed_realtime_nanos = " +
				"accepted_elapsed_realtime_nanos, failure_code = 'retiring'",
			"status = 'RETIRING', retired_at_ms = NULL, retired_elapsed_realtime_nanos = NULL, " +
				"failure_code = NULL",
		)
		corruptions.forEach { assignment ->
			database.openHelper.writableDatabase.execSQL(
				"UPDATE provider_registration_generation SET $assignment " +
					"WHERE source_kind = ? AND registration_generation = ?",
				arrayOf<Any?>(WIFI_SOURCE, REPLACEMENT_REGISTRATION_GENERATION),
			)
			assertEquals(
				WifiWalAdapterResult.Rejected(WifiWalAdapterRejection.SESSION_MISMATCH),
				subject.qualify(EVENT_ID),
			)
			restoreReplacementRegistration(registration)
		}
	}

	@Test
	fun `terminal older run accepts a canonical retiring replacement registration`() = runTest {
		installValidFixture()
		installLiveReplacement()
		makeReplacementRetiring(
			retiredAtMs = SESSION_END_WALL_MS + 3L,
			retiredElapsedNanos = SESSION_END_NANOS + 3L,
		)

		assertIs<WifiWalAdapterResult.Evaluated>(subject.qualify(EVENT_ID))
	}

	@Test
	fun `retiring replacement cannot predate its accepted start action on either clock`() = runTest {
		installValidFixture()
		installLiveReplacement()
		makeReplacementRetiring(
			retiredAtMs = SESSION_END_WALL_MS + 1L,
			retiredElapsedNanos = SESSION_END_NANOS + 3L,
		)
		assertEquals(
			WifiWalAdapterResult.Rejected(WifiWalAdapterRejection.SESSION_MISMATCH),
			subject.qualify(EVENT_ID),
		)

		makeReplacementRetiring(
			retiredAtMs = SESSION_END_WALL_MS + 3L,
			retiredElapsedNanos = SESSION_END_NANOS + 1L,
		)
		assertEquals(
			WifiWalAdapterResult.Rejected(WifiWalAdapterRejection.SESSION_MISMATCH),
			subject.qualify(EVENT_ID),
		)
	}

	@Test
	fun `terminal run still named by nonterminal session remains invalid`() = runTest {
		installValidFixture()
		setLiveLifecyclePair(sessionState = "ACTIVE", runState = "FINALIZED")

		assertEquals(
			WifiWalAdapterResult.Rejected(WifiWalAdapterRejection.SESSION_MISMATCH),
			subject.qualify(EVENT_ID),
		)
	}

	@Test
	fun `same-registration next authorization ignores interleaved global revision`() = runTest {
		installValidFixture()
		database.sourceBrokerDao().insertRegistration(
			registration(generation = 2L, sourceInstance = "other-wifi-instance"),
		)
		database.sourceBrokerDao().insertAuthorizations(
			SourceBrokerAuthorization.rows(
				sourceKind = WIFI_SOURCE,
				registrationGeneration = 2L,
				authorizationRevision = 2L,
				demands = listOf(demand()),
				effectiveBootId = BOOT_ID,
				effectiveElapsedRealtimeNanos = OBSERVED_END_NANOS + 100L,
				effectiveWallTimeMs = OBSERVED_WALL_MS + 1L,
			),
		)
		database.sourceBrokerDao().insertAuthorizations(
			SourceBrokerAuthorization.rows(
				sourceKind = WIFI_SOURCE,
				registrationGeneration = REGISTRATION_GENERATION,
				authorizationRevision = 3L,
				demands = emptyList(),
				effectiveBootId = BOOT_ID,
				effectiveElapsedRealtimeNanos = OBSERVED_END_NANOS + 200L,
				effectiveWallTimeMs = OBSERVED_WALL_MS + 1L,
			),
		)

		val evaluated = assertIs<WifiWalAdapterResult.Evaluated>(subject.qualify(EVENT_ID))
		assertIs<WifiCapturedFactClassification.FreshChanged>(evaluated.classification)
	}

	@Test
	fun `authorization rotation inside provider interval fails closed`() = runTest {
		installValidFixture()
		database.sourceBrokerDao().insertAuthorizations(
			SourceBrokerAuthorization.rows(
				sourceKind = WIFI_SOURCE,
				registrationGeneration = REGISTRATION_GENERATION,
				authorizationRevision = 2L,
				demands = emptyList(),
				effectiveBootId = BOOT_ID,
				effectiveElapsedRealtimeNanos = OBSERVED_END_NANOS,
				effectiveWallTimeMs = OBSERVED_WALL_MS,
			),
		)

		assertEquals(
			WifiWalAdapterResult.Rejected(WifiWalAdapterRejection.AUTHORIZATION_MISMATCH),
			subject.qualify(EVENT_ID),
		)
	}

	@Test
	fun `retention evaluates oldest provider wall and full uncertainty`() = runTest {
		val providerSpanMs = (OBSERVED_END_NANOS - OBSERVED_START_NANOS) / NANOS_PER_MILLISECOND
		val earliestPossibleWall = OBSERVED_WALL_MS - providerSpanMs - WALL_UNCERTAINTY_MS
		installValidFixture(
			evidenceState = SourceEvidenceState(retainedFromMs = earliestPossibleWall + 1L),
		)

		assertEquals(
			WifiWalAdapterResult.Rejected(WifiWalAdapterRejection.BEFORE_RETENTION_FLOOR),
			subject.qualify(EVENT_ID),
		)
	}

	@Test
	fun `already admitted Wi-Fi backlog drains after later quality and End operation state`() = runTest {
		val event = installHistoricalWifiGroups()
		val wal = requireNotNull(database.sourceEventWalDao().getByEventId(event.value))
		assertEquals(0L, database.wifiCapturedFactDao().revisionCount())
		database.advanceRetainedSourceOperations(listOf(LOGICAL_ID to RUN_ID,
			"historical-wifi-second" to "historical-wifi-second-run"))
		val result = assertIs<WifiSessionFactDrainResult.Complete>(
			WifiSessionFactProjectionLane(database, subject, writer).drainAvailable())
		assertEquals(2, result.factsInserted)
		val facts = historicalWifiFacts()
		assertEquals(listOf("first-network", "second-network"), facts.sortedBy { it.coverageIntervalStartNanos }
			.flatMap { historicalWifiChildren(it) }.map { it.ssid })
		facts.forEach { fact -> assertTrue(WifiWalCarrierAuthenticator.authenticates(fact, wal, historicalWifiChildren(fact))) }
		val retainedWal = requireNotNull(database.sourceEventWalDao().getByEventId(event.value))
		assertEquals(wal, retainedWal.copy(payload = wal.payload))
		assertTrue(wal.payload.contentEquals(retainedWal.payload))
	}

	@Test
	fun `Wi-Fi backlog cannot borrow a later generation for its original accepted source action`() = runTest {
		val event = installHistoricalWifiGroups()
		database.advanceRetainedSourceOperations(listOf(LOGICAL_ID to RUN_ID,
			"historical-wifi-second" to "historical-wifi-second-run"))
		database.openHelper.writableDatabase.execSQL(
			"UPDATE lifecycle_desired_action SET lease_generation = ? WHERE source_kind = ? AND desired_state = 'STARTED'",
			arrayOf<Any>(LEASE_GENERATION + 2L, WIFI_SOURCE))
		val result = assertIs<WifiWalAdapterResult.Groups>(subject.qualify(event))
		assertEquals(2, result.results.size)
		assertTrue(result.results.all { it is WifiWalAdapterResult.Rejected })
		assertEquals(0L, database.wifiCapturedFactDao().revisionCount())
	}

	@Test
	fun `actual v6 Wi-Fi ingress after End saves both historical sessions from a denied callback`() = runTest {
		val event = installHistoricalWifiGroups()
		val raw = requireNotNull(database.sourceEventWalDao().getByEventId(event.value))
		assertEquals(null, raw.logicalTrackingId)
		assertEquals(0L, raw.authorizationPurposeEligibilityMask)
		val groups = assertIs<WifiWalAdapterResult.Groups>(subject.qualify(event))
		assertEquals(listOf(listOf(0), listOf(1)), groups.results.map {
			assertIs<WifiWalAdapterResult.Evaluated>(it).qualifiedItemIndexes
		})
		val complete = assertIs<WifiSessionFactDrainResult.Complete>(
			WifiSessionFactProjectionLane(database, subject, writer).drainAvailable())
		assertEquals(2, complete.factsInserted)
		val facts = historicalWifiFacts()
		assertEquals(setOf(LOGICAL_ID, "historical-wifi-second"), facts.map { it.logicalTrackingId }.toSet())
		facts.forEach { fact ->
			val children = historicalWifiChildren(fact)
			assertTrue(WifiWalCarrierAuthenticator.authenticates(fact, raw, children))
			assertEquals(1, children.size)
			val key = SourceProofKey(source = TrackingSource.WIFI, purpose = TrackingPurpose.SESSION_CAPTURE,
				evidenceIdentity = SourceEvidenceIdentity(event.value), sourcePolicyRevision = fact.sourcePolicyRevision,
				captureConsentEpoch = fact.captureConsentEpoch, collectedDataEpoch = fact.collectedDataEpoch,
				scope = SourceProofScope.Run(fact.logicalTrackingId, fact.serviceRunId))
			val lifecycle = historicalWifiLifecycleFactory().create(TrackingSource.WIFI)
			val recording = assertIs<SourceProofResult.Proven<RecordingProofState>>(lifecycle.recording(key)).state
			assertIs<SourceProofResult.Proven<*>>(lifecycle.materialized(key, recording))
		}
		assertEquals(listOf("first-network", "second-network"), facts.sortedBy { it.coverageIntervalStartNanos }
			.flatMap { historicalWifiChildren(it) }.map { it.ssid })
		assertIs<WifiCapturedWriteResult.Groups>(writer.write(event))
		assertEquals(2L, database.wifiCapturedFactDao().revisionCount())
		for (logical in listOf(LOGICAL_ID, "historical-wifi-second")) {
			val exported = mutableListOf<PortableCapturedWifiEntryV1>()
			assertIs<ExportPortableCapturedWifiResult.Exported>(portableExporter().export(
				ExportPortableCapturedWifiRequest(logical)) { exported += it })
			assertEquals(1, exported.single().runs.single().observations.size)
		}
	}

	@Test
	fun `Wi-Fi callback owned by the next session does not lend its consent to the previous scan`() = runTest {
		val event = installHistoricalWifiGroups(currentSecond = true)
		val raw = requireNotNull(database.sourceEventWalDao().getByEventId(event.value))
		assertEquals("historical-wifi-second", raw.logicalTrackingId)
		assertIs<WifiCapturedWriteResult.Groups>(writer.write(event))
		val facts = historicalWifiFacts()
		val first = facts.single { it.logicalTrackingId == LOGICAL_ID }
		val second = facts.single { it.logicalTrackingId != LOGICAL_ID }
		assertEquals(listOf(0), historicalWifiChildren(first).map { it.itemIndex })
		assertEquals(listOf(1), historicalWifiChildren(second).map { it.itemIndex })
		assertTrue(first.coverageIntervalEndNanos < second.sessionRunEffectStartNanos)
	}

	@Test
	fun `malformed Wi-Fi item cannot hide independently valid historical session scans`() = runTest {
		val event = installHistoricalWifiGroups(extraMalformed = true)
		assertIs<WifiCapturedWriteResult.Groups>(writer.write(event))
		assertEquals(2, historicalWifiFacts().size)
		historicalWifiFacts().forEach { assertEquals(1, historicalWifiChildren(it).size) }
	}

	@Test
	fun `cancellation after first historical Wi-Fi group rolls back all siblings and lane progress`() = runTest {
		installHistoricalWifiGroups()
		val lane = WifiSessionFactProjectionLane(database, subject, writer, writeCheckpoint = { _, checkpoint ->
			if (checkpoint == WifiCapturedWriteCheckpoint.REVISION_INSERTED) throw CancellationException("historical-wifi")
		})
		assertFailsWith<CancellationException> { lane.drainAvailable() }
		assertEquals(0L, database.wifiCapturedFactDao().revisionCount())
		assertEquals(0L, database.wifiCapturedFactDao().cursorCount())
		assertEquals(0L, database.sourceProjectionStateDao().activeProductLane(WIFI_SOURCE)?.contiguousAdmissionOrdinal)
	}

	@Test
	fun `an unchanged Wi-Fi authorization rotation preserves historical scans inside the same demand`() = runTest {
		val event = installHistoricalWifiGroups(unrelatedRotation = true)
		val groups = assertIs<WifiWalAdapterResult.Groups>(subject.qualify(event))
		assertEquals(2, groups.results.filterIsInstance<WifiWalAdapterResult.Evaluated>().size)
		assertIs<WifiCapturedWriteResult.Groups>(writer.write(event))
		assertEquals(2, historicalWifiFacts().size)
	}

	@Test
	fun `source erase accepts retained Wi-Fi policy siblings inside one run and keeps raw collision fencing`() = runTest {
		installValidFixture(candidateWriter = true)
		assertIs<WifiCapturedWriteResult.Applied>(writer.write(EVENT_ID))
		val markedAt = OBSERVED_WALL_MS + 10_000L
		database.sourceEvidenceStateDao().synchronizeLifecycle(0L, OBSERVED_WALL_MS, markedAt)
		assertEquals(WifiCapturedRetentionResult.Pruned(1, 1),
			maintenance.pruneAffectedByRetentionFloor(OBSERVED_WALL_MS, 0L, 0L, markedAt))
		val first = database.capturedRadioFactRetentionProofDao().allForSource(WIFI_SOURCE).single()
		val id = "7".repeat(64)
		val mutation = WifiCapturedFactRevisionIntegrity.mutationId(id, first.semanticRevision)
		val sibling = first.copy(logicalFactId = id, mutationId = mutation, cursorMutationId = mutation,
			sourcePolicyRevision = first.sourcePolicyRevision + 1L, captureConsentEpoch = first.captureConsentEpoch + 1L)
			.let { it.copy(effectChecksum = it.calculatedEffectChecksum()) }
		database.withTransaction { database.persistCapturedRadioFactRetentionProofs(listOf(sibling)) }
		database.openHelper.writableDatabase.execSQL("DELETE FROM source_event_wal WHERE source_kind = ?", arrayOf(WIFI_SOURCE))
		revokeCaptureConsent()
		assertEquals(WifiCapturedSourceDeletionResult.Deleted(0, 0, 1),
			maintenance.deleteAfterCaptureConsentReset(0L, 0L, REVOKED_POLICY_REVISION, REVOKED_CONSENT_EPOCH, DELETE_AT_MS))
		assertEquals(0L, database.capturedRadioFactRetentionProofDao().countAll())
		assertEquals(1L, database.wifiCapturedFactDao().deletionGenerationCount())
	}

	@Test
	fun `deleting one historical Wi-Fi session cannot discard a valid sibling in the same callback`() = runTest {
		val event = installHistoricalWifiGroups()
		installPortableDeletion()
		val result = assertIs<WifiSessionFactDrainResult.Complete>(
			WifiSessionFactProjectionLane(database, subject, writer).drainAvailable())
		assertEquals(1, result.factsInserted)
		val fact = historicalWifiFacts().single()
		assertEquals("historical-wifi-second", fact.logicalTrackingId)
		assertEquals(listOf(1), historicalWifiChildren(fact).map { it.itemIndex })
		val qualified = assertIs<WifiWalAdapterResult.Groups>(subject.qualify(event))
		assertTrue(qualified.results.any { it == WifiWalAdapterResult.Rejected(WifiWalAdapterRejection.DELETED_SCOPE) })
		assertTrue(qualified.results.any { it is WifiWalAdapterResult.Evaluated })
		assertIs<ExportPortableCapturedWifiResult.Exported>(portableExporter().export(
			ExportPortableCapturedWifiRequest("historical-wifi-second")) {})
	}

	@Test
	fun `failed historical Wi-Fi delivery is not settled by its denied callback header`() = runTest {
		val event = installHistoricalWifiGroups()
		val ordinal = requireNotNull(database.sourceEventWalDao().getByEventId(event.value)).admissionOrdinal
		database.sourceProjectionStateDao().saveFailure(SourceProjectionFailureEntity(
			projectionId = WifiSessionFactProjectionLane.WRITER_ID,
			projectionVersion = WifiSessionFactProjectionLane.WRITER_VERSION,
			admissionOrdinal = ordinal, attemptCount = 1, failureCode = "WIFI_ADAPTER_AUTHORITY_MISSING",
			terminal = true, lastAttemptAtMs = OBSERVED_WALL_MS))
		val failed = assertIs<WifiSessionFactDrainResult.Failed>(
			WifiSessionFactProjectionLane(database, subject, writer).drainAvailable())
		assertEquals(ordinal, failed.failedOrdinal)
		assertEquals(0L, database.sourceProjectionStateDao().activeProductLane(WIFI_SOURCE)?.contiguousAdmissionOrdinal)
		assertEquals(0L, database.wifiCapturedFactDao().revisionCount())
	}

	@Test
	fun `an ended Wi-Fi session with no callbacks exports while another capture keeps the physical owner`() = runTest {
		installSharedHistoricalWifiSettlement()
		assertEquals(SourceDemandEntity.STATUS_ACTIVE, database.sourceBrokerDao()
			.demandsByIds(listOf("historical-second-wifi-demand")).single().status)
		val emitted = mutableListOf<PortableCapturedWifiEntryV1>()
		assertIs<ExportPortableCapturedWifiResult.Exported>(portableExporter().export(
			ExportPortableCapturedWifiRequest(LOGICAL_ID)) { emitted += it })
		val run = emitted.single().runs.single()
		assertEquals(PortableWifiRunAvailability.NO_RETAINED_OBSERVATION, run.availability)
		assertTrue(run.observations.isEmpty())
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_demand SET retire_elapsed_realtime_nanos = retire_elapsed_realtime_nanos + 1 WHERE demand_id = ?",
			arrayOf("$DEMAND_ID-0"))
		assertIs<ExportPortableCapturedWifiResult.Unverifiable>(portableExporter().export(
			ExportPortableCapturedWifiRequest(LOGICAL_ID)) {})
	}

	@Test
	fun `acknowledged empty Wi-Fi session exports while independent background tracking stays active`() = runTest {
		installSharedHistoricalWifiSettlement(background = true)
		val background = database.sourceBrokerDao().demandsByIds(listOf("shared-wifi-background")).single()
		val before = requireNotNull(database.sourceBrokerDao().registration(WIFI_SOURCE, REGISTRATION_GENERATION))
		val emitted = mutableListOf<PortableCapturedWifiEntryV1>()
		assertIs<ExportPortableCapturedWifiResult.Exported>(portableExporter().export(
			ExportPortableCapturedWifiRequest(LOGICAL_ID)) { emitted += it })
		assertEquals(PortableWifiRunAvailability.NO_RETAINED_OBSERVATION, emitted.single().runs.single().availability)
		assertEquals(SourceDemandEntity.STATUS_ACTIVE, background.status)
		assertEquals(before, database.sourceBrokerDao().registration(WIFI_SOURCE, REGISTRATION_GENERATION))
		assertEquals(background, database.sourceBrokerDao().demandsByIds(listOf(background.demandId)).single())
	}

	@Test
	fun `empty shared Wi-Fi session rejects provider wall acceptance after its acknowledged cutoff`() = runTest {
		installSharedHistoricalWifiSettlement()
		val retirement = requireNotNull(database.sourceSessionDao().runRetirement(
			LOGICAL_ID, RUN_ID, WIFI_SOURCE, SOURCE_INSTANCE, REGISTRATION_GENERATION))
		val acceptedWall = retirement.cutoffWallTimeMs + 1L
		database.openHelper.writableDatabase.execSQL(
			"UPDATE provider_registration_generation SET accepted_at_ms = ? WHERE source_kind = ? AND registration_generation = ?",
			arrayOf<Any?>(acceptedWall, WIFI_SOURCE, REGISTRATION_GENERATION))
		// Keep accepted <= START acknowledgement valid; the independently acknowledged End is earlier.
		database.openHelper.writableDatabase.execSQL(
			"UPDATE lifecycle_desired_action SET acknowledged_at_ms = ? WHERE action_id = ?",
			arrayOf<Any?>(acceptedWall + 1L, "wifi-start-action"))
		assertEquals(sharedWifiSettlementUnverifiable(), exportPortableWithoutSink())
	}

	@Test
	fun `empty shared Wi-Fi session rejects provider elapsed acceptance after its acknowledged cutoff`() = runTest {
		installSharedHistoricalWifiSettlement()
		val retirement = requireNotNull(database.sourceSessionDao().runRetirement(
			LOGICAL_ID, RUN_ID, WIFI_SOURCE, SOURCE_INSTANCE, REGISTRATION_GENERATION))
		val acceptedElapsed = retirement.cutoffElapsedRealtimeNanos + 1L
		database.openHelper.writableDatabase.execSQL(
			"UPDATE provider_registration_generation SET accepted_elapsed_realtime_nanos = ? WHERE source_kind = ? AND registration_generation = ?",
			arrayOf<Any?>(acceptedElapsed, WIFI_SOURCE, REGISTRATION_GENERATION))
		database.openHelper.writableDatabase.execSQL(
			"UPDATE lifecycle_desired_action SET acknowledged_elapsed_realtime_nanos = ? WHERE action_id = ?",
			arrayOf<Any?>(acceptedElapsed + 1L, "wifi-start-action"))
		assertEquals(sharedWifiSettlementUnverifiable(), exportPortableWithoutSink())
	}

	@Test
	fun `empty shared Wi-Fi session cannot export before its retirement acknowledgement exists`() = runTest {
		installSharedHistoricalWifiSettlement(includeReceipt = false)
		assertEquals(sharedWifiSettlementUnverifiable(), exportPortableWithoutSink())
	}

	@Test
	fun `empty shared Wi-Fi session cannot export with only a requested retirement`() = runTest {
		installSharedHistoricalWifiSettlement()
		updateSharedWifiRetirement { it.copy(state = SourceRunRetirementEntity.STATE_REQUESTED,
			appliedRevision = null, callbackEntryBarrierSequence = null, lastSourceSequence = null,
			lastAdmissionOrdinal = null, failedAdmissionCount = null, unresolvedSequenceStart = null,
			unresolvedSequenceEnd = null, registrationRemovalOutcome = null, providerFlushOutcome = null,
			providerCoverage = null, appDrainComplete = null, stopStatus = null) }
		assertEquals(sharedWifiSettlementUnverifiable(), exportPortableWithoutSink())
	}

	@Test
	fun `empty shared Wi-Fi session rejects a retirement from an older lease`() = runTest {
		installSharedHistoricalWifiSettlement()
		updateSharedWifiRetirement { it.copy(leaseGeneration = LEASE_GENERATION + 1L) }
		assertEquals(sharedWifiSettlementUnverifiable(), exportPortableWithoutSink())
	}

	@Test
	fun `empty shared Wi-Fi session rejects a retirement cutoff different from its final closing`() = runTest {
		installSharedHistoricalWifiSettlement()
		// Wall time remains in bounds; only the measured cutoff no longer matches the closing authorization.
		updateSharedWifiRetirement { it.copy(cutoffElapsedRealtimeNanos = it.cutoffElapsedRealtimeNanos + 1L) }
		assertEquals(sharedWifiSettlementUnverifiable(), exportPortableWithoutSink())
	}

	@Test
	fun `empty shared Wi-Fi session rejects a retirement tail not present in completeness`() = runTest {
		installSharedHistoricalWifiSettlement()
		updateSharedWifiRetirement { it.copy(lastAdmissionOrdinal = 1L, lastSourceSequence = 0L) }
		assertEquals(sharedWifiSettlementUnverifiable(), exportPortableWithoutSink())
	}

	@Test
	fun `shared Wi-Fi session rejects an acknowledgement with failed admission`() = runTest {
		installSharedHistoricalWifiSettlement()
		updateSharedWifiRetirement { it.copy(failedAdmissionCount = 1L) }
		assertEquals(sharedWifiSettlementUnverifiable(), exportPortableWithoutSink())
	}

	@Test
	fun `shared Wi-Fi session rejects an acknowledgement with unresolved callbacks`() = runTest {
		installSharedHistoricalWifiSettlement()
		updateSharedWifiRetirement { it.copy(unresolvedSequenceStart = 1L, unresolvedSequenceEnd = 2L) }
		assertEquals(sharedWifiSettlementUnverifiable(), exportPortableWithoutSink())
	}

	@Test
	fun `shared Wi-Fi session requires a completed callback drain`() = runTest {
		installSharedHistoricalWifiSettlement()
		updateSharedWifiRetirement { it.copy(appDrainComplete = false) }
		assertEquals(sharedWifiSettlementUnverifiable(), exportPortableWithoutSink())
	}

	@Test
	fun `shared Wi-Fi session cannot borrow a later sibling snapshot instead of its final closing`() = runTest {
		installSharedHistoricalWifiSettlement()
		val sibling = database.sourceBrokerDao().demandsByIds(listOf("historical-second-wifi-demand")).single()
		database.openHelper.writableDatabase.execSQL(
			"DELETE FROM source_authorization WHERE source_kind = ? AND registration_generation = ? AND authorization_revision = 2",
			arrayOf<Any?>(WIFI_SOURCE, REGISTRATION_GENERATION))
		database.sourceBrokerDao().insertAuthorizations(SourceBrokerAuthorization.rows(WIFI_SOURCE, REGISTRATION_GENERATION,
			3L, listOf(sibling), BOOT_ID, 1_650_000_000L, OBSERVED_WALL_MS + 50L))
		// Both demand retirement and the immutable replacement closing agree with each other, but miss the acknowledged cutoff.
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_demand SET retire_elapsed_realtime_nanos = ?, retired_at_ms = ? WHERE demand_id = ?",
			arrayOf<Any?>(1_650_000_000L, OBSERVED_WALL_MS + 50L, "$DEMAND_ID-0"))
		assertEquals(sharedWifiSettlementUnverifiable(), exportPortableWithoutSink())
	}

	@Test
	fun `shared Wi-Fi callback prefix exports only when its acknowledged tail matches`() = runTest {
		installSharedHistoricalWifiSettlement(withObservation = true)
		val emitted = mutableListOf<PortableCapturedWifiEntryV1>()
		assertIs<ExportPortableCapturedWifiResult.Exported>(portableExporter().export(
			ExportPortableCapturedWifiRequest(LOGICAL_ID)) { emitted += it })
		assertEquals(1, emitted.single().runs.single().observations.size)
		updateSharedWifiRetirement { it.copy(lastAdmissionOrdinal = 2L) }
		assertEquals(sharedWifiSettlementUnverifiable(), exportPortableWithoutSink())
	}

	@Test
	fun `shared Wi-Fi callback prefix cannot outrun its acknowledged entry barrier`() = runTest {
		installSharedHistoricalWifiSettlement(withObservation = true)
		val row = database.trackingHistoryReadDao().completenessForSource(WIFI_SOURCE, LOGICAL_ID,
			listOf(RUN_ID), 2).single()
		database.sourceSessionDao().saveCompleteness(row.copy(lastSourceSequence = 2L))
		updateSharedWifiRetirement { it.copy(lastSourceSequence = 2L, callbackEntryBarrierSequence = 1L) }
		assertEquals(sharedWifiSettlementUnverifiable(), exportPortableWithoutSink())
	}

	@Test
	fun `shared Wi-Fi settlement preserves earlier policy windows before its final cutoff`() = runTest {
		installSharedHistoricalWifiSettlement()
		val sessionDao = database.sourceSessionDao()
		val changeElapsed = 1_400_000_000L
		val changeWall = OBSERVED_WALL_MS - 200L
		val source = sessionDao.manifestSources(LOGICAL_ID, MANIFEST_REVISION).single().copy(manifestRevision = 2L)
		val unsigned = requireNotNull(sessionDao.manifest(LOGICAL_ID, MANIFEST_REVISION)).copy(
			manifestRevision = 2L, startOrigin = "POLICY_RECONCILIATION", changeReason = "POLICY_RECONCILIATION",
			effectiveElapsedRealtimeNanos = changeElapsed, effectiveWallTimeMs = changeWall)
		sessionDao.insertManifest(unsigned.copy(manifestChecksum = SessionManifestIntegrity.compute(unsigned, listOf(source))))
		sessionDao.insertManifestSources(listOf(source))
		sessionDao.updateSession(requireNotNull(sessionDao.session(LOGICAL_ID)).copy(currentManifestRevision = 2L))
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_demand SET retire_elapsed_realtime_nanos = ?, retired_at_ms = ? WHERE demand_id = ?",
			arrayOf<Any?>(changeElapsed, changeWall, "$DEMAND_ID-0"))
		val finalDemand = demand().copy(demandId = "shared-final-policy-wifi", manifestRevision = 2L,
			requestedElapsedRealtimeNanos = changeElapsed, requestedAtMs = changeWall,
			retireElapsedRealtimeNanos = 1_550_000_000L, retiredAtMs = OBSERVED_WALL_MS - 50L)
		database.sourceBrokerDao().insertDemands(listOf(finalDemand))
		val sibling = database.sourceBrokerDao().demandsByIds(listOf("historical-second-wifi-demand")).single()
		database.openHelper.writableDatabase.execSQL(
			"DELETE FROM source_authorization WHERE source_kind = ? AND registration_generation = ? AND authorization_revision = 2",
			arrayOf<Any?>(WIFI_SOURCE, REGISTRATION_GENERATION))
		database.sourceBrokerDao().insertAuthorizations(SourceBrokerAuthorization.rows(WIFI_SOURCE, REGISTRATION_GENERATION,
			2L, listOf(finalDemand), BOOT_ID, changeElapsed, changeWall))
		database.sourceBrokerDao().insertAuthorizations(SourceBrokerAuthorization.rows(WIFI_SOURCE, REGISTRATION_GENERATION,
			3L, listOf(sibling), BOOT_ID, 1_550_000_000L, OBSERVED_WALL_MS - 50L))
		sessionDao.insertLifecycleActions(listOf(startAction(actionId = "shared-final-policy-action", actionRevision = 3L).copy(
			manifestRevision = 2L, startOrigin = "POLICY_RECONCILIATION", requestedAtMs = changeWall,
			requestedElapsedRealtimeNanos = changeElapsed, acknowledgedAtMs = changeWall + 1L,
			acknowledgedElapsedRealtimeNanos = changeElapsed + 1L)))
		assertIs<ExportPortableCapturedWifiResult.Exported>(portableExporter().export(
			ExportPortableCapturedWifiRequest(LOGICAL_ID)) {})
	}

	private fun sharedWifiSettlementUnverifiable() = ExportPortableCapturedWifiResult.Unverifiable(
		PortableWifiUnverifiableReason.WRITER_AUTHORITY_UNVERIFIABLE)

	private suspend fun updateSharedWifiRetirement(update: (SourceRunRetirementEntity) -> SourceRunRetirementEntity) {
		val row = requireNotNull(database.sourceSessionDao().runRetirement(LOGICAL_ID, RUN_ID, WIFI_SOURCE,
			SOURCE_INSTANCE, REGISTRATION_GENERATION))
		assertEquals(1, database.sourceSessionDao().updateRunRetirement(update(row)))
	}

	private suspend fun installSharedHistoricalWifiSettlement(
		background: Boolean = false,
		includeReceipt: Boolean = true,
		withObservation: Boolean = false,
	) {
		installHistoricalWifiGroups(currentSecond = true)
		if (withObservation) {
			assertIs<WifiSessionFactDrainResult.Complete>(WifiSessionFactProjectionLane(database, subject, writer).drainAvailable())
		} else database.openHelper.writableDatabase.execSQL("DELETE FROM source_event_wal WHERE source_kind = ?", arrayOf(WIFI_SOURCE))
		val sessionDao = database.sourceSessionDao()
		if (background) {
			installRetainedNoncapturePolicyAndConsent(1_550_000_000L, OBSERVED_WALL_MS - 50L)
			val ambient = nonCaptureDemand(78, SourceBrokerPurpose.AMBIENT_PRODUCT).copy(demandId = "shared-wifi-background",
				requestedElapsedRealtimeNanos = 1_550_000_000L, requestedAtMs = OBSERVED_WALL_MS - 50L)
			database.sourceBrokerDao().insertDemands(listOf(ambient))
			database.openHelper.writableDatabase.execSQL(
				"DELETE FROM source_authorization WHERE source_kind = ? AND registration_generation = ? AND authorization_revision = 2",
				arrayOf<Any?>(WIFI_SOURCE, REGISTRATION_GENERATION))
			database.sourceBrokerDao().insertAuthorizations(SourceBrokerAuthorization.rows(WIFI_SOURCE, REGISTRATION_GENERATION,
				2L, listOf(ambient), BOOT_ID, 1_550_000_000L, OBSERVED_WALL_MS - 50L))
		} else {
			// The sibling is genuinely ongoing, not a finalized fixture merely attached to an ACTIVE registration.
			database.openHelper.writableDatabase.execSQL(
				"UPDATE source_demand SET status = 'ACTIVE', retire_boot_id = NULL, retire_elapsed_realtime_nanos = NULL, retired_at_ms = NULL " +
					"WHERE demand_id = ?", arrayOf("historical-second-wifi-demand"))
			val siblingRun = requireNotNull(sessionDao.serviceRun("historical-wifi-second-run"))
			sessionDao.updateServiceRun(siblingRun.copy(state = "ACTIVE", completedAtMs = null, completionReason = null,
				runtimeAcknowledgement = "START_ACCEPTED", presentationAcknowledgement = SourceServiceRunEntity.PRESENTATION_PENDING,
				presentationAcknowledgedAtMs = null))
			sessionDao.updateSession(requireNotNull(sessionDao.session("historical-wifi-second")).copy(state = "ACTIVE",
				cutoffAtMs = null, cutoffElapsedNanos = null, completedAtMs = null, finalAdmissionOrdinal = null,
				currentServiceRunId = siblingRun.serviceRunId))
		}
		database.openHelper.writableDatabase.execSQL(
			"UPDATE provider_registration_generation SET status = 'ACTIVE', retired_at_ms = NULL, " +
				"retired_elapsed_realtime_nanos = NULL, failure_code = NULL WHERE source_kind = ? AND registration_generation = ?",
			arrayOf<Any?>(WIFI_SOURCE, REGISTRATION_GENERATION))
		val completeness = SourceSessionCompletenessEntity(LOGICAL_ID, RUN_ID, WIFI_SOURCE, SOURCE_INSTANCE,
			REGISTRATION_GENERATION, 1L.takeIf { withObservation }, 0L.takeIf { withObservation }, true,
			"PROVIDER_COMPLETENESS_UNOBSERVABLE", "COMPLETE", null, null, OBSERVED_WALL_MS + 1_000L)
		sessionDao.saveCompleteness(completeness)
		if (includeReceipt) sessionDao.insertRunRetirementIntent(SourceRunRetirementEntity(
			logicalTrackingId = LOGICAL_ID, serviceRunId = RUN_ID, sourceKind = WIFI_SOURCE,
			sourceInstanceId = SOURCE_INSTANCE, registrationGeneration = REGISTRATION_GENERATION,
			actionId = "wifi-start-action", attemptCount = 1, leaseGeneration = LEASE_GENERATION,
			cutoffElapsedRealtimeNanos = 1_550_000_000L, cutoffWallTimeMs = OBSERVED_WALL_MS - 50L,
			state = SourceRunRetirementEntity.STATE_ACKNOWLEDGED, appliedRevision = PLAN_REVISION,
			callbackEntryBarrierSequence = if (withObservation) 1L else 0L,
			lastSourceSequence = completeness.lastSourceSequence, lastAdmissionOrdinal = completeness.lastAdmissionOrdinal,
			failedAdmissionCount = 0L, unresolvedSequenceStart = null, unresolvedSequenceEnd = null,
			registrationRemovalOutcome = "NOT_REGISTERED", providerFlushOutcome = "NOT_SUPPORTED",
			providerCoverage = completeness.providerCoverage, appDrainComplete = true, stopStatus = "COMPLETE",
			updatedAtMs = OBSERVED_WALL_MS + 1_000L))
	}

	@Test
	fun `retention of an earlier Wi-Fi group keeps the later measured group exportable and replayable`() = runTest {
		val event = installHistoricalWifiGroups()
		assertIs<WifiSessionFactDrainResult.Complete>(WifiSessionFactProjectionLane(database, subject, writer).drainAvailable())
		val floor = OBSERVED_WALL_MS - 50L
		val appliedAt = OBSERVED_WALL_MS + 10_000L
		database.sourceEvidenceStateDao().synchronizeLifecycle(0L, floor, appliedAt)
		assertEquals(WifiCapturedRetentionResult.Pruned(1, 1), maintenance.pruneAffectedByRetentionFloor(
			floor, 0L, 0L, appliedAt))
		val retained = database.capturedRadioFactRetentionProofDao().allForSource(WIFI_SOURCE)
		assertEquals(1, retained.size)
		assertEquals(LOGICAL_ID, retained.single().logicalTrackingId)
		assertIs<WifiCapturedWriteResult.Groups>(writer.write(event))
		val facts = historicalWifiFacts().single()
		assertEquals("historical-wifi-second", facts.logicalTrackingId)
		val entries = mutableListOf<PortableCapturedWifiEntryV1>()
		assertIs<ExportPortableCapturedWifiResult.Exported>(portableExporter().export(
			ExportPortableCapturedWifiRequest("historical-wifi-second")) { entries += it })
		assertEquals(1, entries.single().runs.single().observations.size)
	}

	@Test
	fun `Wi-Fi lifecycle cannot reuse an aggregate whose required network child was removed`() = runTest {
		installValidFixture(candidateWriter = true, payloadVersion = WIFI_WAL_PAYLOAD_VERSION)
		assertIs<WifiCapturedWriteResult.Applied>(writer.write(EVENT_ID))
		val second = insertAdditionalWal(2, accessPoints().mapIndexed { index, point ->
			point.copy(identifierToken = "replacement-bssid-$index") }, OBSERVED_WALL_MS)
		assertIs<WifiCapturedWriteResult.Applied>(writer.write(second))
		val dependent = database.wifiCapturedFactDao().currentRevisionsForWriterAdmission(
			SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_ID, SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_VERSION, 2L, 4).single()
		assertEquals(WifiCapturedFactRevisionEntity.FACT_KIND_COVERAGE_ONLY, dependent.factKind)
		val key = SourceProofKey(TrackingSource.WIFI, TrackingPurpose.SESSION_CAPTURE,
			SourceEvidenceIdentity(second.value), dependent.sourcePolicyRevision, dependent.captureConsentEpoch,
			dependent.collectedDataEpoch, SourceProofScope.Run(LOGICAL_ID, RUN_ID))
		val lifecycle = historicalWifiLifecycleFactory().create(TrackingSource.WIFI)
		val recording = assertIs<SourceProofResult.Proven<RecordingProofState>>(lifecycle.recording(key)).state
		assertIs<SourceProofResult.Proven<*>>(lifecycle.materialized(key, recording))
		database.openHelper.writableDatabase.execSQL(
			"DELETE FROM wifi_captured_child WHERE logical_fact_id = ? AND item_index = 0",
			arrayOf(dependent.aggregateOwnerLogicalFactId))
		assertIs<SourceProofResult.Failed>(lifecycle.materialized(key, recording))
	}

	@Test
	fun `native Wi-Fi policy change inside one run retains both groups and source erase accepts their receipts`() = runTest {
		val event = installHistoricalWifiGroups()
		val split = 1_550_000_000L
		val end = 1_650_000_000L
		val policyDao = database.sourcePolicyDao()
		val sessionDao = database.sourceSessionDao()
		val originalPolicy = requireNotNull(policyDao.policyAtRevision(POLICY_REVISION, WIFI_SOURCE))
		val originalConsent = requireNotNull(policyDao.consentEpoch(WIFI_SOURCE, SourceBrokerPurpose.SESSION_CAPTURE, CONSENT_EPOCH))
		val policyRevision = 5L
		val consentEpoch = 5L
		val planRevision = 5L
		policyDao.insertPolicies(listOf(originalPolicy.copy(policyRevision = policyRevision, captureConsentEpoch = consentEpoch,
			effectiveElapsedRealtimeNanos = split, effectiveWallTimeMs = OBSERVED_WALL_MS - 50L)))
		policyDao.insertConsentEpochs(listOf(originalConsent.copy(epoch = consentEpoch, policyRevision = policyRevision,
			effectiveElapsedRealtimeNanos = split, effectiveWallTimeMs = OBSERVED_WALL_MS - 50L)))
		val encodedPlan = planCodec.encode(wifiPlan().copy(revision = planRevision))
		database.sourcePlanStateDao().insertRevision(AcquisitionPlanRevisionEntity(planRevision, "same-run-wifi-policy",
			OBSERVED_WALL_MS - 50L, "EFFECTIVE", policyRevision))
		database.sourcePlanStateDao().insertDesiredPlans(listOf(SourceDesiredPlanEntity(planRevision, WIFI_SOURCE,
			1, encodedPlan.bytes, encodedPlan.checksum)))
		val source = sessionDao.manifestSources(LOGICAL_ID, MANIFEST_REVISION).single().copy(
			manifestRevision = 2L, consentEpoch = consentEpoch)
		val unsignedManifest = requireNotNull(sessionDao.manifest(LOGICAL_ID, MANIFEST_REVISION)).copy(
			manifestRevision = 2L, acquisitionPlanRevision = planRevision, sourcePolicyRevision = policyRevision,
			startOrigin = "POLICY_RECONCILIATION", changeReason = "POLICY_RECONCILIATION",
			effectiveElapsedRealtimeNanos = split, effectiveWallTimeMs = OBSERVED_WALL_MS - 50L)
		sessionDao.insertManifest(unsignedManifest.copy(manifestChecksum = SessionManifestIntegrity.compute(unsignedManifest, listOf(source))))
		sessionDao.insertManifestSources(listOf(source))
		sessionDao.updateSession(requireNotNull(sessionDao.session(LOGICAL_ID)).copy(currentManifestRevision = 2L,
			desiredPlanRevision = planRevision, cutoffElapsedNanos = end, cutoffAtMs = OBSERVED_WALL_MS + 50L,
			completedAtMs = OBSERVED_WALL_MS + 50L))
		sessionDao.updateServiceRun(requireNotNull(sessionDao.serviceRun(RUN_ID)).copy(desiredPlanRevision = planRevision,
			completedAtMs = OBSERVED_WALL_MS + 50L, presentationAcknowledgedAtMs = OBSERVED_WALL_MS + 50L))
		database.openHelper.writableDatabase.execSQL("UPDATE session_segment SET end_time_ms = ? WHERE id = ?",
			arrayOf(OBSERVED_WALL_MS + 50L, SEGMENT_ID))
		val reconciledDemand = demand().copy(demandId = "same-run-wifi-policy", manifestRevision = 2L,
			sourcePolicyRevision = policyRevision, consentEpoch = consentEpoch, requestedElapsedRealtimeNanos = split,
			requestedAtMs = OBSERVED_WALL_MS - 50L, retireElapsedRealtimeNanos = end, retiredAtMs = OBSERVED_WALL_MS + 50L)
		database.sourceBrokerDao().insertDemands(listOf(reconciledDemand))
		database.openHelper.writableDatabase.execSQL(
			"DELETE FROM source_authorization WHERE source_kind = ? AND registration_generation = ? AND authorization_revision = 2",
			arrayOf<Any?>(WIFI_SOURCE, REGISTRATION_GENERATION))
		database.sourceBrokerDao().insertAuthorizations(SourceBrokerAuthorization.rows(WIFI_SOURCE, REGISTRATION_GENERATION,
			2L, listOf(reconciledDemand), BOOT_ID, split, OBSERVED_WALL_MS - 50L))
		// The generic two-session fixture's unused second demand is replaced by this same-run manifest.
		database.openHelper.writableDatabase.execSQL("DELETE FROM source_demand WHERE demand_id = ?",
			arrayOf("historical-second-wifi-demand"))
		sessionDao.insertLifecycleActions(listOf(startAction(actionId = "same-run-policy-action", actionRevision = 3L).copy(
			manifestRevision = 2L, desiredPlanRevision = planRevision, startOrigin = "POLICY_RECONCILIATION",
			requestedAtMs = OBSERVED_WALL_MS - 50L, requestedElapsedRealtimeNanos = split,
			acknowledgedAtMs = OBSERVED_WALL_MS - 49L, acknowledgedElapsedRealtimeNanos = split + 1L)))
		assertIs<WifiSessionFactDrainResult.Complete>(WifiSessionFactProjectionLane(database, subject, writer).drainAvailable())
		val facts = historicalWifiFacts()
		assertEquals(listOf(1L, 5L), facts.sortedBy { it.manifestRevision }.map { it.sourcePolicyRevision })
		assertEquals(setOf(RUN_ID), facts.map { it.serviceRunId }.toSet())
		assertEquals(setOf(1L, 2L), facts.map { it.manifestRevision }.toSet())
		assertEquals(setOf(event.value), facts.map { it.sourceEventId }.toSet())
		assertIs<ExportPortableCapturedWifiResult.Exported>(portableExporter().export(ExportPortableCapturedWifiRequest(LOGICAL_ID)) {})
		val floor = OBSERVED_WALL_MS + 1L
		val markedAt = OBSERVED_WALL_MS + 10_000L
		database.sourceEvidenceStateDao().synchronizeLifecycle(0L, floor, markedAt)
		assertEquals(WifiCapturedRetentionResult.Pruned(2, 2), maintenance.pruneAffectedByRetentionFloor(floor, 0L, 0L, markedAt))
		val receipts = database.capturedRadioFactRetentionProofDao().allForSource(WIFI_SOURCE)
		assertEquals(2, receipts.size)
		assertEquals(setOf(1L, 5L), receipts.map { it.sourcePolicyRevision }.toSet())
		database.openHelper.writableDatabase.execSQL("DELETE FROM source_event_wal WHERE source_kind = ?", arrayOf(WIFI_SOURCE))
		policyDao.insertPolicies(listOf(originalPolicy.copy(policyRevision = 6L, enabled = false, qosCode = QOS_OFF,
			capturePersistenceEligible = false, captureConsentEpoch = null, effectiveElapsedRealtimeNanos = SESSION_END_NANOS + 1L,
			effectiveWallTimeMs = SESSION_END_WALL_MS + 1L)))
		policyDao.insertConsentEpochs(listOf(originalConsent.copy(epoch = 6L, policyRevision = 6L, eligible = false,
			persistenceEligible = false, effectiveElapsedRealtimeNanos = SESSION_END_NANOS + 1L,
			effectiveWallTimeMs = SESSION_END_WALL_MS + 1L)))
		assertEquals(1, policyDao.compareAndSetAuthority(SourcePolicyAuthorityEntity.STATE_ACTIVE, POLICY_REVISION,
			SourcePolicyAuthorityEntity.STATE_ACTIVE, 6L, null, SESSION_END_WALL_MS + 1L))
		assertEquals(WifiCapturedSourceDeletionResult.Deleted(0, 0, 1),
			maintenance.deleteAfterCaptureConsentReset(0L, 0L, 6L, 6L, DELETE_AT_MS))
		assertEquals(0L, database.capturedRadioFactRetentionProofDao().countAll())
	}

	private fun historicalWifiLifecycleFactory() = DefaultSourceLifecycleProofAdapterFactory(
		database, payloadCodec, planCodec, mockk<LocationWalQualificationAdapter>(relaxed = true),
		mockk<ActivityCapturedWalAdmissionAdapter>(relaxed = true), subject,
		mockk<CellWalQualificationAdapter>(relaxed = true), BootClockDomainProvider { BOOT_ID },
	)

	private suspend fun historicalWifiFacts() = database.wifiCapturedFactDao().currentRevisionsForWriterAdmission(
		SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_ID, SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_VERSION, 1L, 10)

	private suspend fun historicalWifiChildren(fact: WifiCapturedFactRevisionEntity) = database.radioObservationChildDao()
		.wifiSessionChildren(fact.writerProjectionId, fact.writerProjectionVersion, fact.logicalFactId, fact.semanticRevision, 65)

	private suspend fun installHistoricalWifiGroups(
		currentSecond: Boolean = false,
		extraMalformed: Boolean = false,
		unrelatedRotation: Boolean = false,
	): SourceEventId {
		installValidFixture(candidateWriter = true, payloadVersion = WIFI_WAL_PAYLOAD_VERSION, insertWal = false)
		val split = 1_550_000_000L
		val end = 1_650_000_000L
		val sessionDao = database.sourceSessionDao()
		val firstSession = requireNotNull(sessionDao.session(LOGICAL_ID))
		val firstRun = requireNotNull(sessionDao.serviceRun(RUN_ID))
		sessionDao.updateSession(firstSession.copy(cutoffAtMs = OBSERVED_WALL_MS - 50L, cutoffElapsedNanos = split,
			completedAtMs = OBSERVED_WALL_MS - 50L, finalAdmissionOrdinal = 0L))
		sessionDao.updateServiceRun(firstRun.copy(completedAtMs = OBSERVED_WALL_MS - 50L,
			presentationAcknowledgedAtMs = OBSERVED_WALL_MS - 50L))
		database.openHelper.writableDatabase.execSQL("UPDATE session_segment SET end_time_ms = ? WHERE id = ?",
			arrayOf(OBSERVED_WALL_MS - 50L, SEGMENT_ID))
		database.openHelper.writableDatabase.execSQL("UPDATE source_demand SET retire_elapsed_realtime_nanos = ?, retired_at_ms = ? WHERE demand_id = ?",
			arrayOf<Any?>(split, OBSERVED_WALL_MS - 50L, "$DEMAND_ID-0"))
		val secondLogical = "historical-wifi-second"
		val secondRun = "historical-wifi-second-run"
		val secondSegment = database.sessionSegmentDao().insert(segment(secondRun).copy(id = 0L,
			logicalTrackingId = secondLogical, startTimeMs = OBSERVED_WALL_MS - 50L, endTimeMs = OBSERVED_WALL_MS + 50L))
		sessionDao.insertSession(firstSession.copy(logicalTrackingId = secondLogical, startedAtMs = OBSERVED_WALL_MS - 50L,
			startedElapsedNanos = split, cutoffAtMs = OBSERVED_WALL_MS + 50L, cutoffElapsedNanos = end,
			completedAtMs = OBSERVED_WALL_MS + 50L, finalAdmissionOrdinal = 0L))
		sessionDao.insertServiceRun(firstRun.copy(serviceRunId = secondRun, logicalTrackingId = secondLogical,
			sessionSegmentId = secondSegment, startedAtMs = OBSERVED_WALL_MS - 50L, startedElapsedNanos = split,
			completedAtMs = OBSERVED_WALL_MS + 50L, presentationAcknowledgedAtMs = OBSERVED_WALL_MS + 50L,
			startDeliveryToken = "historical-second-start", androidDeliveryUpdatedAtMs = OBSERVED_WALL_MS - 50L))
		val source = sessionDao.manifestSources(LOGICAL_ID, MANIFEST_REVISION).single().copy(logicalTrackingId = secondLogical)
		val unsignedManifest = requireNotNull(sessionDao.manifest(LOGICAL_ID, MANIFEST_REVISION)).copy(
			logicalTrackingId = secondLogical, serviceRunId = secondRun, effectiveElapsedRealtimeNanos = split,
			effectiveWallTimeMs = OBSERVED_WALL_MS - 50L)
		sessionDao.insertManifest(unsignedManifest.copy(manifestChecksum = SessionManifestIntegrity.compute(unsignedManifest, listOf(source))))
		sessionDao.insertManifestSources(listOf(source))
		val secondDemand = demand().copy(demandId = "historical-second-wifi-demand", consumerId = "session:$secondLogical",
			logicalTrackingId = secondLogical, serviceRunId = secondRun, requestedElapsedRealtimeNanos = split,
			requestedAtMs = OBSERVED_WALL_MS - 50L, retireElapsedRealtimeNanos = end, retiredAtMs = OBSERVED_WALL_MS + 50L)
		database.sourceBrokerDao().insertDemands(listOf(secondDemand))
		val secondRevision = if (unrelatedRotation) 3L else 2L
		if (unrelatedRotation) database.sourceBrokerDao().insertAuthorizations(SourceBrokerAuthorization.rows(WIFI_SOURCE,
			REGISTRATION_GENERATION, 2L, listOf(requireNotNull(database.sourceBrokerDao().demandsByIds(listOf("$DEMAND_ID-0")).singleOrNull())),
			BOOT_ID, 1_450_000_000L, OBSERVED_WALL_MS - 150L))
		val secondAuthorization = SourceBrokerAuthorization.rows(WIFI_SOURCE, REGISTRATION_GENERATION, secondRevision,
			listOf(secondDemand), BOOT_ID, split, OBSERVED_WALL_MS - 50L)
		database.sourceBrokerDao().insertAuthorizations(secondAuthorization)
		val callbackRevision = secondRevision + 1L
		val closing = SourceBrokerAuthorization.rows(WIFI_SOURCE, REGISTRATION_GENERATION, callbackRevision,
			emptyList(), BOOT_ID, end, OBSERVED_WALL_MS + 50L)
		if (!currentSecond) database.sourceBrokerDao().insertAuthorizations(closing)
		else {
			// Callback precedes the second session's End; the first session already ended.
			database.openHelper.writableDatabase.execSQL("UPDATE source_demand SET retire_elapsed_realtime_nanos = ?, retired_at_ms = ? WHERE demand_id = ?",
				arrayOf<Any?>(SESSION_END_NANOS, SESSION_END_WALL_MS, secondDemand.demandId))
			sessionDao.updateSession(requireNotNull(sessionDao.session(secondLogical)).copy(cutoffAtMs = SESSION_END_WALL_MS,
				cutoffElapsedNanos = SESSION_END_NANOS, completedAtMs = SESSION_END_WALL_MS))
			sessionDao.updateServiceRun(requireNotNull(sessionDao.serviceRun(secondRun)).copy(
				completedAtMs = SESSION_END_WALL_MS, presentationAcknowledgedAtMs = SESSION_END_WALL_MS))
			database.openHelper.writableDatabase.execSQL("UPDATE session_segment SET end_time_ms = ? WHERE id = ?",
				arrayOf(SESSION_END_WALL_MS, secondSegment))
		}
		sessionDao.insertLifecycleActions(listOf(startAction(actionId = "historical-second-action", actionRevision = 2L).copy(
			logicalTrackingId = secondLogical, serviceRunId = secondRun, requestedAtMs = OBSERVED_WALL_MS - 50L,
			requestedElapsedRealtimeNanos = split, acknowledgedAtMs = OBSERVED_WALL_MS - 49L,
			acknowledgedElapsedRealtimeNanos = split + 1L)))
		installWifiLane(SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL)
		val catalog = ExecutableSourceLaneCatalog.explicit(ExecutableSourceLaneCatalog.WIFI_SESSION_FACTS)
		RoomTrackingRolloutStateStore(database, catalog).save(TrackingRolloutState.eventCanonical(sources = setOf(SourceKind.WIFI),
			captureModes = mapOf(SourceKind.WIFI to setOf(CaptureReachabilityMode.MANUAL_SESSION_CAPTURE))), updatedAtMs = OBSERVED_WALL_MS)
		val registration = requireNotNull(database.sourceBrokerDao().registration(WIFI_SOURCE, REGISTRATION_GENERATION))
		database.sourceRegistrationStateDao().insertIfAbsent(SourceRegistrationStateEntity(WIFI_SOURCE, registration.ownerScope,
			SOURCE_INSTANCE, BOOT_ID, REGISTRATION_GENERATION, 0L, PLAN_REVISION, 0L, OBSERVED_WALL_MS))
		val lifecycle = mockk<CollectedDataLifecycleStore>()
		coEvery { lifecycle.snapshot() } returns CollectedDataLifecycleSnapshot(0L, null)
		val gate = object : TrackingStartupGate {
			override val isReady = true
			override suspend fun reconcile(retryFailedStorage: Boolean): TrackingStartupResult = TrackingStartupResult.Ready(false, 0L)
		}
		val points = accessPoints().mapIndexed { index, point -> point.copy(identifierToken = "network-$index",
			networkName = if (index == 0) "first-network" else "second-network") } + if (extraMalformed) listOf(
			WifiAccessPointEvidence("invalid-network", 100, -70, OBSERVED_END_NANOS - 1L)) else emptyList()
		val callback = if (currentSecond) secondAuthorization else closing
		val candidate = SourceEvidenceCandidate(providerDedupKey = null,
			logicalTrackingId = if (currentSecond) com.adsamcik.tracker.tracker.source.model.LogicalTrackingId(secondLogical) else null,
			serviceRunId = if (currentSecond) com.adsamcik.tracker.tracker.source.model.ServiceRunId(secondRun) else null,
			source = SourceKind.WIFI, sourceInstanceId = SourceInstanceId(SOURCE_INSTANCE), registrationGeneration = REGISTRATION_GENERATION,
			physicalConfigurationFingerprint = registration.physicalConfigurationFingerprint,
			authorizationRevision = callback.first().authorizationRevision,
			registrationPurposeEligibilityMask = callback.first().purposeEligibilityMask,
			registrationEligibilityFingerprint = callback.first().authorizationFingerprint, sourceSequence = 0L,
			configRevision = PLAN_REVISION, planAttribution = if (currentSecond) PlanAttribution.CAPTURED_REGISTRATION else PlanAttribution.RECEIVE_TIME_ONLY,
			clockDomainId = BOOT_ID, observedElapsedRealtimeNanos = OBSERVED_END_NANOS, receivedElapsedRealtimeNanos = RECEIVED_NANOS,
			receivedWallTimeMs = OBSERVED_WALL_MS + 100L,
			wallTimeMs = OBSERVED_WALL_MS, wallTimeUncertaintyMs = WALL_UNCERTAINTY_MS, capturedCollectedDataEpoch = 0L,
			sourcePolicyRevision = if (currentSecond) POLICY_REVISION else null,
			captureConsentEpoch = if (currentSecond) CONSENT_EPOCH else null,
			sessionManifestRevision = if (currentSecond) MANIFEST_REVISION else null,
			lifecycleLeaseGeneration = if (currentSecond) LEASE_GENERATION else null,
			acquiredAtMs = OBSERVED_WALL_MS, quality = SourceQuality(), payloadVersion = WIFI_WAL_PAYLOAD_VERSION,
			payload = WifiResultSnapshotPayload(points, OBSERVED_END_NANOS / NANOS_PER_MILLISECOND, null, ZONE_ID))
		val admitted = RoomDurableSourceIngress(database, lifecycle, payloadCodec, catalog, Provider { gate }).admit(
			SourceDeliveryCandidate(wifiProviderDeliveryIdentity(BOOT_ID, points, WIFI_WAL_PAYLOAD_VERSION),
				listOf(SourceDeliveryUnit(0, candidate, OBSERVED_START_NANOS))))
		val event = assertIs<DeliveryAdmissionResult.Admitted>(admitted, admitted.toString()).units.single().eventId
		listOf(LOGICAL_ID to RUN_ID, secondLogical to secondRun).forEach { (logical, run) -> sessionDao.saveCompleteness(
			SourceSessionCompletenessEntity(logical, run, WIFI_SOURCE, SOURCE_INSTANCE, REGISTRATION_GENERATION,
				null, null, true, "PROVIDER_COMPLETENESS_UNOBSERVABLE", "COMPLETE", null, null, OBSERVED_WALL_MS + 1_000L)) }
		return event
	}

	@Test
	fun `v6 maintenance and portable export preserve readings across authorization rotation`() = runTest {
		installValidFixture(candidateWriter = true, payloadVersion = WIFI_WAL_PAYLOAD_VERSION)
		rotateV6Callback(OBSERVED_START_NANOS + 50_000_000L)
		installPortableClosingAuthorization(authorizationRevision = 3L)
		materializePortableFixture()
		val fact = requireNotNull(database.wifiCapturedFactDao().revision(
			SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_ID,
			SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_VERSION,
			assertIs<WifiCapturedWriteResult.Unchanged>(writer.write(EVENT_ID)).logicalFactId, 1L,
		))
		assertEquals(2, fact.acceptedResultCount)
		assertEquals(OBSERVED_START_NANOS, fact.authorizationEffectStartNanos)
		val exports = mutableListOf<PortableCapturedWifiEntryV1>()
		assertIs<ExportPortableCapturedWifiResult.Exported>(portableExporter().export(
			ExportPortableCapturedWifiRequest(LOGICAL_ID),
		) { exports += it })
		assertEquals(1, exports.single().runs.single().observations.size)
		assertRetainedV6LineageCanBeAudited()
	}

	@Test
	fun `v6 new capture authorization cannot claim an older reading`() = runTest {
		installValidFixture(candidateWriter = true, payloadVersion = WIFI_WAL_PAYLOAD_VERSION)
		database.openHelper.writableDatabase.execSQL("DELETE FROM source_authorization")
		database.sourceBrokerDao().insertAuthorizations(SourceBrokerAuthorization.rows(
			WIFI_SOURCE, REGISTRATION_GENERATION, AUTHORIZATION_REVISION, emptyList(),
			BOOT_ID, AUTHORIZATION_START_NANOS, RUN_START_WALL_MS,
		))
		rotateV6Callback(OBSERVED_START_NANOS + 50_000_000L)
		val evaluated = assertIs<WifiWalAdapterResult.Evaluated>(subject.qualify(EVENT_ID))
		val fact = assertIs<WifiCapturedFactClassification.FreshChanged>(evaluated.classification).fact
		assertEquals(listOf(1), evaluated.qualifiedItemIndexes)
		assertEquals(1, fact.coverage.acceptedResultCount)
		assertEquals(1, fact.coverage.staleResultCount)
		assertEquals(OBSERVED_END_NANOS, fact.authority.temporalAuthority.authorizationEffect.startInclusiveNanos)
		assertIs<WifiCapturedWriteResult.Applied>(writer.write(EVENT_ID))
		assertRetainedV6LineageCanBeAudited()
	}

	@Test
	fun `writer seals the v6 carrier and retains it through correction retention and ordinary WAL expiry`() = runTest {
		installValidFixture(candidateWriter = true, payloadVersion = WIFI_WAL_PAYLOAD_VERSION)
		val first = assertIs<WifiCapturedWriteResult.Applied>(writer.write(EVENT_ID))
		installPortableClosingAuthorization()
		val correction = assertIs<WifiCapturedWriteResult.Applied>(writer.write(EVENT_ID))
		assertEquals(2L, correction.semanticRevision)
		val revisions = database.wifiCapturedFactDao().revisionsForFact(
			SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_ID,
			SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_VERSION, first.logicalFactId, 10)
		assertEquals(listOf(WIFI_WAL_PAYLOAD_VERSION, WIFI_WAL_PAYLOAD_VERSION),
			revisions.map { it.payloadVersion })
		assertTrue(WifiCapturedFactRevisionIntegrity.hasValidRetainedLineage(revisions.reversed()))
		materializePortableFixture()
		assertRetainedV6LineageCanBeAudited()
		assertEquals(0, database.sourceEventWalDao().deleteProjectedSourceBatch(
			WIFI_SOURCE, Long.MAX_VALUE, Long.MAX_VALUE, 10))
		assertEquals(1L, database.sourceEventWalDao().countAll())
		val emitted = mutableListOf<PortableCapturedWifiEntryV1>()
		assertIs<ExportPortableCapturedWifiResult.Exported>(portableExporter().export(
			ExportPortableCapturedWifiRequest(LOGICAL_ID)) { emitted += it })
		assertEquals(1, emitted.size)
	}

	@Test
	fun `native export and retention reject a v6 scan after all its network details disappear`() = runTest {
		installValidFixture(candidateWriter = true, payloadVersion = WIFI_WAL_PAYLOAD_VERSION)
		installPortableClosingAuthorization()
		materializePortableFixture()
		database.openHelper.writableDatabase.execSQL("DELETE FROM wifi_captured_child")
		assertEquals(ExportPortableCapturedWifiResult.Unverifiable(
			PortableWifiUnverifiableReason.FACT_AUTHORITY_UNVERIFIABLE), exportPortableWithoutSink())
		val floor = OBSERVED_WALL_MS - 1_000L
		val markedAt = OBSERVED_WALL_MS + 10_000L
		assertTrue(database.sourceEvidenceStateDao().synchronizeLifecycle(0L, floor, markedAt))
		assertEquals(WifiCapturedRetentionResult.Blocked(
			WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE),
			maintenance.pruneAffectedByRetentionFloor(floor, 0L, 0L, markedAt))
		assertEquals(1L, database.wifiCapturedFactDao().revisionCount())
	}

	@Test
	fun `writer correction keeps an older null carrier marker instead of guessing its format`() = runTest {
		installValidFixture(candidateWriter = true, payloadVersion = LEGACY_WIFI_WAL_PAYLOAD_VERSION)
		val first = assertIs<WifiCapturedWriteResult.Applied>(writer.write(EVENT_ID))
		val row = requireNotNull(database.wifiCapturedFactDao().revision(
			SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_ID,
			SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_VERSION, first.logicalFactId, 1L))
		assertEquals(LEGACY_WIFI_WAL_PAYLOAD_VERSION, row.payloadVersion)
		// Represent a pre-marker development row using its unchanged legacy checksum contract.
		val legacy = row.copy(payloadVersion = null).let {
			it.copy(effectChecksum = WifiCapturedFactRevisionIntegrity.effectChecksum(it))
		}
		database.openHelper.writableDatabase.execSQL(
			"UPDATE wifi_captured_fact_revision SET payload_version=NULL, effect_checksum=? WHERE logical_fact_id=?",
			arrayOf(legacy.effectChecksum, legacy.logicalFactId))
		database.openHelper.writableDatabase.execSQL(
			"UPDATE wifi_captured_fact_cursor SET latest_effect_checksum=? WHERE logical_fact_id=?",
			arrayOf(legacy.effectChecksum, legacy.logicalFactId))
		installPortableClosingAuthorization()
		val corrected = assertIs<WifiCapturedWriteResult.Applied>(writer.write(EVENT_ID))
		assertEquals(2L, corrected.semanticRevision)
		val revisions = database.wifiCapturedFactDao().revisionsForFact(
			SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_ID,
			SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_VERSION, first.logicalFactId, 10)
		assertEquals(listOf(null, null), revisions.map { it.payloadVersion })
		assertTrue(WifiCapturedFactRevisionIntegrity.hasValidRetainedLineage(revisions.reversed()))
		materializePortableFixture()
		val emitted = mutableListOf<PortableCapturedWifiEntryV1>()
		assertIs<ExportPortableCapturedWifiResult.Exported>(portableExporter().export(
			ExportPortableCapturedWifiRequest(LOGICAL_ID)) { emitted += it })
		assertEquals(1, emitted.size)
	}

	@Test
	fun `native export rejects a v6 scan with only part of its network list retained`() = runTest {
		installValidFixture(candidateWriter = true, payloadVersion = WIFI_WAL_PAYLOAD_VERSION)
		installPortableClosingAuthorization()
		materializePortableFixture()
		database.openHelper.writableDatabase.execSQL("DELETE FROM wifi_captured_child WHERE item_index=0")
		assertEquals(ExportPortableCapturedWifiResult.Unverifiable(
			PortableWifiUnverifiableReason.FACT_AUTHORITY_UNVERIFIABLE), exportPortableWithoutSink())
	}

	@Test
	fun `v6 callback revision must be the revision effective at receipt`() = runTest {
		installValidFixture(payloadVersion = WIFI_WAL_PAYLOAD_VERSION)
		installPortableClosingAuthorization(effectiveElapsedRealtimeNanos = OBSERVED_END_NANOS + 1L)
		assertEquals(WifiWalAdapterResult.Rejected(WifiWalAdapterRejection.AUTHORIZATION_MISMATCH),
			subject.qualify(EVENT_ID))
	}

	@Test
	fun `v6 cached reading before physical registration does not discard its valid sibling`() = runTest {
		installValidFixture(candidateWriter = true, payloadVersion = WIFI_WAL_PAYLOAD_VERSION,
			accessPoints = listOf(
				WifiAccessPointEvidence("", 2_412, -80, RUN_START_NANOS - 100_000_000L),
				WifiAccessPointEvidence("", 5_180, -60, OBSERVED_END_NANOS),
			))
		val evaluated = assertIs<WifiWalAdapterResult.Evaluated>(subject.qualify(EVENT_ID))
		assertEquals(listOf(1), evaluated.qualifiedItemIndexes)
		assertEquals(1, assertIs<WifiCapturedFactClassification.FreshChanged>(evaluated.classification)
			.fact.coverage.acceptedResultCount)
		assertIs<WifiCapturedWriteResult.Applied>(writer.write(EVENT_ID))
		assertRetainedV6LineageCanBeAudited()
	}

	private suspend fun assertRetainedV6LineageCanBeAudited() {
		val floor = OBSERVED_WALL_MS - 1_000L
		val markedAt = OBSERVED_WALL_MS + 10_000L
		assertTrue(database.sourceEvidenceStateDao().synchronizeLifecycle(0L, floor, markedAt))
		assertEquals(WifiCapturedRetentionResult.NoChange,
			maintenance.pruneAffectedByRetentionFloor(floor, 0L, 0L, markedAt))
	}

	private suspend fun rotateV6Callback(effectiveNanos: Long) {
		val demands = database.sourceBrokerDao().demandsByIds(listOf("$DEMAND_ID-0"))
		val rows = SourceBrokerAuthorization.rows(WIFI_SOURCE, REGISTRATION_GENERATION, 2L, demands,
			BOOT_ID, effectiveNanos, OBSERVED_WALL_MS)
		database.sourceBrokerDao().insertAuthorizations(rows)
		val wal = requireNotNull(database.sourceEventWalDao().getByEventId(EVENT_ID.value))
		val unsigned = wal.copy(authorizationRevision = 2L,
			authorizationPurposeEligibilityMask = rows.first().purposeEligibilityMask,
			authorizationFingerprint = rows.first().authorizationFingerprint)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_event_wal SET authorization_revision = ?, authorization_purpose_eligibility_mask = ?, " +
				"authorization_fingerprint = ?, integrity_identity = ? WHERE event_id = ?",
			arrayOf<Any?>(2L, unsigned.authorizationPurposeEligibilityMask, unsigned.authorizationFingerprint,
				unsigned.calculatedIntegrityIdentity(), EVENT_ID.value),
		)
	}

	@Test
	fun `current collected-data epoch rejects retained prior generation`() = runTest {
		installValidFixture(evidenceState = SourceEvidenceState(collectedDataEpoch = 1L))

		assertEquals(
			WifiWalAdapterResult.Rejected(WifiWalAdapterRejection.DELETED_EVIDENCE),
			subject.qualify(EVENT_ID),
		)
	}

	@Test
	fun `exact selected-session deletion fence prevents WAL resurrection`() = runTest {
		installValidFixture()
		database.sourceDeletionFenceDao().insertIfAbsent(
			SourceDeletionFenceEntity.createLogicalServiceRun(
				sourceKind = WIFI_SOURCE,
				purpose = SourceBrokerPurpose.SESSION_CAPTURE,
				logicalTrackingId = LOGICAL_ID,
				serviceRunId = RUN_ID,
				fenceGeneration = 1L,
				collectedDataEpoch = 0L,
				deletedAtMs = SESSION_END_WALL_MS,
			),
		)

		assertEquals(
			WifiWalAdapterResult.Rejected(WifiWalAdapterRejection.DELETED_SCOPE),
			subject.qualify(EVENT_ID),
		)
	}

	@Test
	fun `deleted WAL scope wins when its mutable presentation segment is already missing`() = runTest {
		installValidFixture()
		database.sourceDeletionFenceDao().insertIfAbsent(
			SourceDeletionFenceEntity.createLogicalServiceRun(
				sourceKind = WIFI_SOURCE,
				purpose = SourceBrokerPurpose.SESSION_CAPTURE,
				logicalTrackingId = LOGICAL_ID,
				serviceRunId = RUN_ID,
				fenceGeneration = 1L,
				collectedDataEpoch = 0L,
				deletedAtMs = SESSION_END_WALL_MS,
			),
		)
		database.openHelper.writableDatabase.execSQL(
			"DELETE FROM session_segment WHERE id = ?",
			arrayOf(SEGMENT_ID),
		)

		assertEquals(
			WifiWalAdapterResult.Rejected(WifiWalAdapterRejection.DELETED_SCOPE),
			subject.qualify(EVENT_ID),
		)
	}

	@Test
	fun `unfenced WAL with missing presentation segment remains malformed`() = runTest {
		installValidFixture()
		database.openHelper.writableDatabase.execSQL(
			"DELETE FROM session_segment WHERE id = ?",
			arrayOf(SEGMENT_ID),
		)

		assertEquals(
			WifiWalAdapterResult.Rejected(WifiWalAdapterRejection.MISSING_SEGMENT),
			subject.qualify(EVENT_ID),
		)
	}

	@Test
	fun `reverse run-segment binding is mandatory`() = runTest {
		installValidFixture(segmentRunId = "other-run")

		assertEquals(
			WifiWalAdapterResult.Rejected(WifiWalAdapterRejection.SEGMENT_MISMATCH),
			subject.qualify(EVENT_ID),
		)
	}

	@Test
	fun `stored manifest zone must resolve exactly`() = runTest {
		installValidFixture(zoneId = "Not/AZone")

		assertEquals(
			WifiWalAdapterResult.Rejected(WifiWalAdapterRejection.INVALID_STORED_ZONE),
			subject.qualify(EVENT_ID),
		)
	}

	@Test
	fun `identity-bearing access point input is rejected before product classification`() = runTest {
		installValidFixture(
			accessPoints = accessPoints().mapIndexed { index, accessPoint ->
				if (index == 0) accessPoint.copy(identifierToken = "raw-ssid") else accessPoint
			},
			deliveryIdentityOverride = "b".repeat(64),
		)

		assertEquals(
			WifiWalAdapterResult.Rejected(WifiWalAdapterRejection.MALFORMED_PRODUCER_DELIVERY),
			subject.qualify(EVENT_ID),
		)
	}

	@Test
	fun `empty retained payload cannot manufacture confirmed empty coverage`() = runTest {
		installValidFixture(
			accessPoints = emptyList(),
			deliveryIdentityOverride = "c".repeat(64),
		)

		assertEquals(
			WifiWalAdapterResult.Rejected(
				WifiWalAdapterRejection.MALFORMED_PRODUCER_DELIVERY,
			),
			subject.qualify(EVENT_ID),
		)
	}

	@Test
	fun `cancellation propagates before any caller can observe a partial qualification`() = runTest {
		installValidFixture()

		assertFailsWith<CancellationException> {
			subject.qualify(EVENT_ID) { _, _, _ -> throw CancellationException("cancel") }
		}
	}

	@Test
	fun `dormant writer appends one identity free revision then exact replay is unchanged`() = runTest {
		installValidFixture(candidateWriter = true)

		val applied = assertIs<WifiCapturedWriteResult.Applied>(writer.write(EVENT_ID))
		assertEquals(1L, applied.semanticRevision)
		assertEquals(1L, database.wifiCapturedFactDao().revisionCount())
		assertEquals(1L, database.wifiCapturedFactDao().cursorCount())

		val replay = assertIs<WifiCapturedWriteResult.Unchanged>(writer.write(EVENT_ID))
		assertEquals(applied.logicalFactId, replay.logicalFactId)
		assertEquals(1L, database.wifiCapturedFactDao().revisionCount())
	}

	@Test
	fun `writer remains dormant without exact destination ownership`() = runTest {
		installValidFixture()

		assertEquals(
			WifiCapturedWriteResult.Rejected(WifiCapturedWriteRejection.DESTINATION_OWNER_CHANGED),
			writer.write(EVENT_ID),
		)
		assertEquals(0L, database.wifiCapturedFactDao().revisionCount())
	}

	@Test
	fun `fresh unchanged aggregate writes bounded one hop coverage reference`() = runTest {
		installValidFixture(candidateWriter = true)
		val first = assertIs<WifiCapturedWriteResult.Applied>(writer.write(EVENT_ID))
		val secondId = insertSecondWal()

		assertIs<WifiCapturedWriteResult.Applied>(writer.write(secondId))
		val second = requireNotNull(database.sourceEventWalDao().getByEventId(secondId.value))
		val evaluated = assertIs<WifiWalAdapterResult.Evaluated>(subject.qualify(secondId))
		val qualified = assertIs<WifiCapturedFactClassification.FreshChanged>(evaluated.classification)
		val logicalId = WifiCapturedFactRevisionIntegrity.logicalFactId(
			requireNotNull(second.deliveryIdentity), LOGICAL_ID, RUN_ID, SEGMENT_ID,
			MANIFEST_REVISION, 0L, 0L,
		)
		val row = requireNotNull(database.wifiCapturedFactDao().revision(
			SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_ID,
			SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_VERSION,
			logicalId,
			1L,
		))
		assertEquals(WifiCapturedFactRevisionEntity.FACT_KIND_COVERAGE_ONLY, row.factKind)
		assertEquals(qualified.fact.aggregate.observationCount, row.acceptedResultCount)
		assertEquals(2L, database.wifiCapturedFactDao().revisionCount())
		assertFailsWith<SQLiteConstraintException> {
			database.openHelper.writableDatabase.execSQL(
				"DELETE FROM wifi_captured_fact_revision WHERE logical_fact_id = ?",
				arrayOf(first.logicalFactId),
			)
		}
	}

	@Test
	fun `canonical Wi-Fi lane atomically commits captured fact and both cursors`() = runTest {
		installValidFixture(candidateWriter = true)
		installWifiLane(SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL)
		val demandsBefore = database.sourceBrokerDao().activeDemands(WIFI_SOURCE)
		val registrationBefore = database.sourceBrokerDao().registration(
			WIFI_SOURCE,
			REGISTRATION_GENERATION,
		)

		val result = assertIs<WifiSessionFactDrainResult.Complete>(
			WifiSessionFactProjectionLane(database, subject, writer).drainAvailable(),
		)

		assertEquals(1L, result.lastCompletedOrdinal)
		assertEquals(1, result.factsInserted)
		assertEquals(1, result.eventsValidated)
		assertEquals(1L, database.wifiCapturedFactDao().revisionCount())
		assertEquals(1L, database.wifiCapturedFactDao().cursorCount())
		assertEquals(1L, database.sourceProjectionStateDao().activeProductLane(WIFI_SOURCE)
			?.contiguousAdmissionOrdinal)
		assertEquals(null, database.sourceProjectionStateDao().failure(
			WifiSessionFactProjectionLane.WRITER_ID,
			WifiSessionFactProjectionLane.WRITER_VERSION,
			1L,
		))
		assertEquals(demandsBefore, database.sourceBrokerDao().activeDemands(WIFI_SOURCE))
		assertEquals(
			registrationBefore,
			database.sourceBrokerDao().registration(WIFI_SOURCE, REGISTRATION_GENERATION),
		)
	}

	@Test
	fun `canonical Wi-Fi lane writes compact unchanged coverage against exact aggregate owner`() = runTest {
		installValidFixture(candidateWriter = true)
		val secondId = insertSecondWal()
		installWifiLane(SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL)

		val result = assertIs<WifiSessionFactDrainResult.Complete>(
			WifiSessionFactProjectionLane(database, subject, writer).drainAvailable(),
		)

		assertEquals(2L, result.lastCompletedOrdinal)
		assertEquals(2, result.factsInserted)
		assertEquals(2L, database.wifiCapturedFactDao().revisionCount())
		assertEquals(2L, database.wifiCapturedFactDao().cursorCount())
		val second = requireNotNull(database.sourceEventWalDao().getByEventId(secondId.value))
		val logicalId = WifiCapturedFactRevisionIntegrity.logicalFactId(
			requireNotNull(second.deliveryIdentity), LOGICAL_ID, RUN_ID, SEGMENT_ID,
			MANIFEST_REVISION, 0L, 0L,
		)
		val coverage = requireNotNull(database.wifiCapturedFactDao().revision(
			WifiSessionFactProjectionLane.WRITER_ID,
			WifiSessionFactProjectionLane.WRITER_VERSION,
			logicalId,
			1L,
		))
		assertEquals(WifiCapturedFactRevisionEntity.FACT_KIND_COVERAGE_ONLY, coverage.factKind)
		assertEquals(1L, coverage.aggregateOwnerSemanticRevision)
		assertEquals(1L, coverage.aggregateOwnerCursorRevision)
	}

	@Test
	fun `shadow Wi-Fi lane validates without invoking canonical writer`() = runTest {
		installValidFixture(candidateWriter = false)
		installWifiLane(SourceProductProjectionLaneEntity.STAGE_EVENT_SHADOW)

		val result = assertIs<WifiSessionFactDrainResult.Complete>(
			WifiSessionFactProjectionLane(database, subject, writer).drainAvailable(),
		)

		assertEquals(1, result.eventsValidated)
		assertEquals(0, result.factsInserted)
		assertEquals(0L, database.wifiCapturedFactDao().revisionCount())
		assertEquals(1L, database.sourceProjectionStateDao().activeProductLane(WIFI_SOURCE)
			?.contiguousAdmissionOrdinal)
	}

	@Test
	fun `valid CONTROL and AMBIENT Wi-Fi rows settle without captured history`() = runTest {
		installValidFixture(candidateWriter = true)
		rewriteWalPurpose(EVENT_ID, SourceBrokerPurpose.MASK_CONTROL_AUTOSTART)
		val secondId = insertAdditionalWal(2, accessPoints(), OBSERVED_WALL_MS + 200L)
		rewriteWalPurpose(secondId, SourceBrokerPurpose.MASK_AMBIENT_PRODUCT)
		installWifiLane(SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL)

		val result = assertIs<WifiSessionFactDrainResult.Complete>(
			WifiSessionFactProjectionLane(database, subject, writer).drainAvailable(),
		)

		assertEquals(2L, result.lastCompletedOrdinal)
		assertEquals(0, result.eventsValidated)
		assertEquals(0, result.factsInserted)
		assertEquals(0L, database.wifiCapturedFactDao().revisionCount())
	}

	@Test
	fun `corrupt noncapture Wi-Fi mask is terminal before lane cursor progress`() = runTest {
		installValidFixture(candidateWriter = true)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_event_wal SET authorization_purpose_eligibility_mask = ? WHERE event_id = ?",
			arrayOf<Any?>(SourceBrokerPurpose.MASK_CONTROL_AUTOSTART, EVENT_ID.value),
		)
		installWifiLane(SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL)

		val failed = assertTerminalWifiLaneFailure(
			WifiSessionFactProjectionLane(database, subject, writer),
		)

		assertEquals("WIFI_PREFLIGHT_WAL_INTEGRITY_MISMATCH", failed.failureCode)
	}

	@Test
	fun `corrupt Wi-Fi epoch cannot release terminal candidate`() = runTest {
		installValidFixture(candidateWriter = true)
		installWifiLane(SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL)
		corruptWalPayload()
		val lane = WifiSessionFactProjectionLane(database, subject, writer)
		assertTerminalWifiLaneFailure(lane)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_event_wal SET captured_collected_data_epoch = " +
				"captured_collected_data_epoch + 1 WHERE event_id = ?",
			arrayOf(EVENT_ID.value),
		)

		val repeated = assertTerminalWifiLaneFailure(lane)

		assertEquals("WIFI_ADAPTER_PAYLOAD_MISMATCH", repeated.failureCode)
	}

	@Test
	fun `corrupt Wi-Fi scope cannot release terminal candidate`() = runTest {
		installValidFixture(candidateWriter = true)
		installWifiLane(SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL)
		corruptWalPayload()
		val lane = WifiSessionFactProjectionLane(database, subject, writer)
		assertTerminalWifiLaneFailure(lane)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_event_wal SET logical_tracking_id = 'forged-wifi-logical', " +
				"service_run_id = 'forged-wifi-run' WHERE event_id = ?",
			arrayOf(EVENT_ID.value),
		)

		val repeated = assertTerminalWifiLaneFailure(lane)

		assertEquals("WIFI_ADAPTER_PAYLOAD_MISMATCH", repeated.failureCode)
	}

	@Test
	fun `corrupt Wi-Fi time cannot release terminal candidate`() = runTest {
		installValidFixture(candidateWriter = true)
		installWifiLane(SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL)
		corruptWalPayload()
		val lane = WifiSessionFactProjectionLane(database, subject, writer)
		assertTerminalWifiLaneFailure(lane)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_event_wal SET acquired_at_ms = 0, wall_time_ms = 0 WHERE event_id = ?",
			arrayOf(EVENT_ID.value),
		)

		val repeated = assertTerminalWifiLaneFailure(lane)

		assertEquals("WIFI_ADAPTER_PAYLOAD_MISMATCH", repeated.failureCode)
	}

	@Test
	fun `missing Wi-Fi WAL cannot release terminal candidate without deletion high water`() = runTest {
		installValidFixture(candidateWriter = true)
		installWifiLane(SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL)
		corruptWalPayload()
		val lane = WifiSessionFactProjectionLane(database, subject, writer)
		assertTerminalWifiLaneFailure(lane)
		database.openHelper.writableDatabase.execSQL(
			"DELETE FROM source_event_wal WHERE event_id = ?",
			arrayOf(EVENT_ID.value),
		)

		val repeated = assertTerminalWifiLaneFailure(lane)

		assertEquals("WIFI_ADAPTER_PAYLOAD_MISMATCH", repeated.failureCode)
		assertEquals(1, database.sourceEvidenceStateDao().updateAfterFullDeletion(
			epoch = 0L,
			retainedFromMs = null,
			deletedSourceEventHighWaterOrdinal = 1L,
			updatedAtMs = SESSION_END_WALL_MS,
		))
		val settled = assertIs<WifiSessionFactDrainResult.Complete>(lane.drainAvailable())
		assertEquals(1L, settled.lastCompletedOrdinal)
		assertEquals(0L, database.wifiCapturedFactDao().revisionCount())
	}

	@Test
	fun `deleted source high water releases terminal Wi-Fi ordinal without fact`() = runTest {
		installValidFixture(candidateWriter = true)
		installWifiLane(SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL)
		corruptWalPayload()
		val lane = WifiSessionFactProjectionLane(database, subject, writer)
		assertTerminalWifiLaneFailure(lane)
		assertEquals(1, database.sourceEvidenceStateDao().updateAfterFullDeletion(
			epoch = 0L,
			retainedFromMs = null,
			deletedSourceEventHighWaterOrdinal = 1L,
			updatedAtMs = SESSION_END_WALL_MS,
		))

		val settled = assertIs<WifiSessionFactDrainResult.Complete>(lane.drainAvailable())

		assertEquals(1L, settled.lastCompletedOrdinal)
		assertEquals(0L, database.wifiCapturedFactDao().revisionCount())
		assertEquals(null, database.sourceProjectionStateDao().failure(
			WifiSessionFactProjectionLane.WRITER_ID,
			WifiSessionFactProjectionLane.WRITER_VERSION,
			1L,
		))
	}

	@Test
	fun `retryable Wi-Fi poison preserves cursors and retries exact event`() = runTest {
		installValidFixture(candidateWriter = true)
		installWifiLane(SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL)
		var fail = true
		val lane = WifiSessionFactProjectionLane(
			database,
			subject,
			writer,
			writeCheckpoint = { _, checkpoint ->
				if (fail && checkpoint == WifiCapturedWriteCheckpoint.QUALIFIED) error("retry-wifi")
			},
		)

		val failed = assertIs<WifiSessionFactDrainResult.Failed>(lane.drainAvailable())
		assertEquals(false, failed.terminal)
		assertEquals(0L, database.wifiCapturedFactDao().revisionCount())
		assertEquals(0L, database.sourceProjectionStateDao().activeProductLane(WIFI_SOURCE)
			?.contiguousAdmissionOrdinal)
		fail = false
		val recovered = assertIs<WifiSessionFactDrainResult.Complete>(lane.drainAvailable())
		assertEquals(1, recovered.factsInserted)
		assertEquals(null, database.sourceProjectionStateDao().failure(
			WifiSessionFactProjectionLane.WRITER_ID,
			WifiSessionFactProjectionLane.WRITER_VERSION,
			1L,
		))
	}

	@Test
	fun `Wi-Fi lane cancellation rolls back fact failure and lane cursors`() = runTest {
		installValidFixture(candidateWriter = true)
		installWifiLane(SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL)
		val lane = WifiSessionFactProjectionLane(
			database,
			subject,
			writer,
			writeCheckpoint = { _, checkpoint ->
				if (checkpoint == WifiCapturedWriteCheckpoint.REVISION_INSERTED) {
					throw CancellationException("cancel-wifi-lane")
				}
			},
		)

		assertFailsWith<CancellationException> { lane.drainAvailable() }

		assertEquals(0L, database.wifiCapturedFactDao().revisionCount())
		assertEquals(0L, database.wifiCapturedFactDao().cursorCount())
		assertEquals(0L, database.sourceProjectionStateDao().activeProductLane(WIFI_SOURCE)
			?.contiguousAdmissionOrdinal)
		assertEquals(null, database.sourceProjectionStateDao().failure(
			WifiSessionFactProjectionLane.WRITER_ID,
			WifiSessionFactProjectionLane.WRITER_VERSION,
			1L,
		))
	}

	@Test
	fun `canonical Wi-Fi lane refuses stale destination owner before WAL mutation`() = runTest {
		installValidFixture(candidateWriter = true)
		installWifiLane(SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_destination_owner SET owner_generation = owner_generation + 1 " +
				"WHERE source_kind = ? AND destination = ?",
			arrayOf<Any?>(WIFI_SOURCE, SourceDestinationOwnerEntity.DESTINATION_SESSION_WIFI),
		)

		val changed = assertIs<WifiSessionFactDrainResult.AuthorityChanged>(
			WifiSessionFactProjectionLane(database, subject, writer).drainAvailable(),
		)

		assertEquals("WIFI_DESTINATION_OWNER_CHANGED", changed.reason)
		assertEquals(0L, database.wifiCapturedFactDao().revisionCount())
		assertEquals(0L, database.sourceProjectionStateDao().activeProductLane(WIFI_SOURCE)
			?.contiguousAdmissionOrdinal)
	}

	@Test
	fun `cancellation after revision append rolls back revision cursor and publication`() = runTest {
		installValidFixture(candidateWriter = true)
		val before = requireNotNull(database.sourceEvidenceStateDao().get()).revision

		assertFailsWith<CancellationException> {
			writer.write(EVENT_ID) { checkpoint ->
				if (checkpoint == WifiCapturedWriteCheckpoint.REVISION_INSERTED) {
					throw CancellationException("cancel writer")
				}
			}
		}
		assertEquals(0L, database.wifiCapturedFactDao().revisionCount())
		assertEquals(0L, database.wifiCapturedFactDao().cursorCount())
		assertEquals(before, requireNotNull(database.sourceEvidenceStateDao().get()).revision)
	}

	@Test
	fun `coverage replay rejects aggregate owner cursor rotation`() = runTest {
		installValidFixture(candidateWriter = true)
		val first = assertIs<WifiCapturedWriteResult.Applied>(writer.write(EVENT_ID))
		val secondId = insertSecondWal()
		assertIs<WifiCapturedWriteResult.Applied>(writer.write(secondId))
		database.openHelper.writableDatabase.execSQL(
			"UPDATE wifi_captured_fact_cursor SET cursor_revision = 2 WHERE logical_fact_id = ?",
			arrayOf(first.logicalFactId),
		)

		assertEquals(
			WifiCapturedWriteResult.Rejected(WifiCapturedWriteRejection.AGGREGATE_OWNER_MISMATCH),
			writer.write(secondId),
		)
	}

	@Test
	fun `maintenance rejects coverage owner with equal count but different aggregate`() = runTest {
		installValidFixture(candidateWriter = true)
		val first = assertIs<WifiCapturedWriteResult.Applied>(writer.write(EVENT_ID))
		val changedPoints = listOf(
			WifiAccessPointEvidence("", 2_412, -70, OBSERVED_END_NANOS + 100_000_000L),
			WifiAccessPointEvidence("", 2_412, -50, OBSERVED_END_NANOS + 200_000_000L),
		)
		val changedOwnerId = insertAdditionalWal(2, changedPoints, OBSERVED_WALL_MS + 200L)
		assertIs<WifiCapturedWriteResult.Applied>(writer.write(changedOwnerId))
		val matchingPoints = listOf(
			WifiAccessPointEvidence("", 2_412, -70, OBSERVED_END_NANOS + 300_000_000L),
			WifiAccessPointEvidence("", 2_412, -50, OBSERVED_END_NANOS + 400_000_000L),
		)
		val dependentId = insertAdditionalWal(3, matchingPoints, OBSERVED_WALL_MS + 400L)
		val dependent = assertIs<WifiCapturedWriteResult.Applied>(writer.write(dependentId))
		val dependentRevision = requireNotNull(database.wifiCapturedFactDao().revision(
			SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_ID,
			SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_VERSION,
			dependent.logicalFactId,
			1L,
		))
		assertEquals(WifiCapturedFactRevisionEntity.FACT_KIND_COVERAGE_ONLY, dependentRevision.factKind)
		val forged = dependentRevision.copy(
			aggregateOwnerLogicalFactId = first.logicalFactId,
			effectChecksum = WifiCapturedFactRevisionIntegrity.effectChecksum(
				dependentRevision.copy(aggregateOwnerLogicalFactId = first.logicalFactId),
			),
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE wifi_captured_fact_revision SET aggregate_owner_logical_fact_id = ?, " +
				"effect_checksum = ? WHERE logical_fact_id = ?",
			arrayOf(forged.aggregateOwnerLogicalFactId, forged.effectChecksum, forged.logicalFactId),
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE wifi_captured_fact_cursor SET latest_effect_checksum = ? WHERE logical_fact_id = ?",
			arrayOf(forged.effectChecksum, forged.logicalFactId),
		)
		val retainedFromMs = OBSERVED_WALL_MS - 1_000L
		val markedAtMs = OBSERVED_WALL_MS + 10_000L
		assertEquals(
			true,
			database.sourceEvidenceStateDao().synchronizeLifecycle(0L, retainedFromMs, markedAtMs),
		)

		assertEquals(
			WifiCapturedRetentionResult.Blocked(
				WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE,
			),
			maintenance.pruneAffectedByRetentionFloor(retainedFromMs, 0L, 0L, markedAtMs),
		)
	}

	@Test
	fun `source local deletion generation rejects old WAL before classification`() = runTest {
		installValidFixture(candidateWriter = true)
		database.openHelper.writableDatabase.execSQL(
			"INSERT INTO wifi_capture_deletion_generation(" +
				"logical_tracking_id, service_run_id, collected_data_epoch, generation, updated_at_ms" +
				") VALUES(?, ?, ?, ?, ?)",
			arrayOf<Any?>(LOGICAL_ID, RUN_ID, 0L, 1L, OBSERVED_WALL_MS),
		)

		assertEquals(
			WifiWalAdapterResult.Rejected(
				WifiWalAdapterRejection.SCOPE_DELETION_AUTHORITY_MISMATCH,
			),
			subject.qualify(EVENT_ID),
		)
		assertEquals(0L, database.wifiCapturedFactDao().revisionCount())
	}

	@Test
	fun `source local deletion generation advances only by exact sequential CAS`() = runTest {
		val dao = database.wifiCapturedFactDao()
		dao.insertDeletionGeneration(WifiCaptureDeletionGenerationEntity(
			logicalTrackingId = LOGICAL_ID,
			serviceRunId = RUN_ID,
			collectedDataEpoch = 0L,
			generation = 1L,
			updatedAtMs = OBSERVED_WALL_MS,
		))
		assertEquals(0, dao.advanceDeletionGenerationExact(
			LOGICAL_ID, RUN_ID, 0L, 0L, 1L, OBSERVED_WALL_MS + 1L,
		))
		assertEquals(1, dao.advanceDeletionGenerationExact(
			LOGICAL_ID, RUN_ID, 0L, 1L, 2L, OBSERVED_WALL_MS + 2L,
		))
		assertEquals(2L, requireNotNull(dao.deletionGeneration(LOGICAL_ID, RUN_ID)).generation)
	}

	@Test
	fun `retention removes the whole uncertainty affected aggregate dependency closure but keeps WAL`() = runTest {
		installValidFixture(candidateWriter = true)
		assertIs<WifiCapturedWriteResult.Applied>(writer.write(EVENT_ID))
		val secondId = insertSecondWal()
		assertIs<WifiCapturedWriteResult.Applied>(writer.write(secondId))
		val markedAtMs = OBSERVED_WALL_MS + 10_000L
		assertEquals(
			true,
			database.sourceEvidenceStateDao().synchronizeLifecycle(
				0L,
				OBSERVED_WALL_MS,
				markedAtMs,
			),
		)

		assertEquals(1, database.sourceEvidenceStateDao().incrementRevision(markedAtMs + 1L))

		assertEquals(
			WifiCapturedRetentionResult.Pruned(logicalFactCount = 2, revisionCount = 2),
			maintenance.pruneAffectedByRetentionFloor(
				beforeMs = OBSERVED_WALL_MS,
				expectedCollectedDataEpoch = 0L,
				expectedDeletedSourceEventHighWaterOrdinal = 0L,
				markedAtMs = markedAtMs,
			),
		)
		assertEquals(0L, database.wifiCapturedFactDao().revisionCount())
		assertEquals(0L, database.wifiCapturedFactDao().cursorCount())
		assertEquals(2L, database.sourceEventWalDao().countAll())
		assertEquals(markedAtMs + 1L, database.sourceEvidenceStateDao().get()?.updatedAtMs)
	}

	@Test
	fun `retention floor affecting only coverage also removes its aggregate owner`() = runTest {
		installValidFixture(candidateWriter = true)
		val owner = assertIs<WifiCapturedWriteResult.Applied>(writer.write(EVENT_ID))
		val secondId = insertSecondWal(observedWallTimeMs = OBSERVED_WALL_MS - 500L)
		val dependent = assertIs<WifiCapturedWriteResult.Applied>(writer.write(secondId))
		val retainedFromMs = OBSERVED_WALL_MS - 300L
		val markedAtMs = OBSERVED_WALL_MS + 10_000L
		assertEquals(
			true,
			database.sourceEvidenceStateDao().synchronizeLifecycle(0L, retainedFromMs, markedAtMs),
		)

		assertEquals(
			WifiCapturedRetentionResult.Pruned(logicalFactCount = 2, revisionCount = 2),
			maintenance.pruneAffectedByRetentionFloor(retainedFromMs, 0L, 0L, markedAtMs),
		)
		assertEquals(0L, database.wifiCapturedFactDao().revisionCount())
		assertEquals(0L, database.wifiCapturedFactDao().cursorCount())
		assertEquals(2L, database.sourceEventWalDao().countAll())
		val proofs = database.capturedRadioFactRetentionProofDao().allForSource(WIFI_SOURCE)
		assertEquals(true, proofs.all { it.scopeDeletionGeneration == 0L })
		assertEquals(
			CapturedRadioFactRetentionProofEntity.REASON_DEPENDENCY_CLOSURE,
			proofs.single { it.logicalFactId == owner.logicalFactId }.reason,
		)
		assertEquals(
			CapturedRadioFactRetentionProofEntity.REASON_DIRECT_FLOOR,
			proofs.single { it.logicalFactId == dependent.logicalFactId }.reason,
		)
		assertEquals(
			owner.logicalFactId,
			proofs.single { it.logicalFactId == dependent.logicalFactId }
				.aggregateOwnerLogicalFactId,
		)
		assertEquals(
			WifiCapturedWriteResult.Rejected(WifiCapturedWriteRejection.RETAINED_DATA),
			writer.write(secondId),
		)
	}

	@Test
	fun `cancellation after Wi-Fi retention proof staging rolls back proof and facts`() = runTest {
		installValidFixture(candidateWriter = true)
		assertIs<WifiCapturedWriteResult.Applied>(writer.write(EVENT_ID))
		val markedAtMs = OBSERVED_WALL_MS + 10_000L
		database.sourceEvidenceStateDao().synchronizeLifecycle(
			0L,
			OBSERVED_WALL_MS,
			markedAtMs,
		)

		assertFailsWith<CancellationException> {
			maintenance.pruneAffectedByRetentionFloor(
				beforeMs = OBSERVED_WALL_MS,
				expectedCollectedDataEpoch = 0L,
				expectedDeletedSourceEventHighWaterOrdinal = 0L,
				markedAtMs = markedAtMs,
				limits = WifiCapturedMaintenanceLimits(),
				checkpoint = { checkpoint ->
					if (checkpoint == WifiCapturedMaintenanceCheckpoint.RETENTION_PROOF_PERSISTED) {
						assertEquals(
							1L,
							database.capturedRadioFactRetentionProofDao().countAll(),
						)
						assertEquals(1L, database.wifiCapturedFactDao().revisionCount())
						throw CancellationException("test staged proof cancellation")
					}
				},
			)
		}

		assertEquals(0L, database.capturedRadioFactRetentionProofDao().countAll())
		assertEquals(1L, database.wifiCapturedFactDao().revisionCount())
	}

	@Test
	fun `retention leaves wholly retained lineage unchanged`() = runTest {
		installValidFixture(candidateWriter = true)
		assertIs<WifiCapturedWriteResult.Applied>(writer.write(EVENT_ID))
		val retainedFromMs = OBSERVED_WALL_MS - 1_000L
		val markedAtMs = OBSERVED_WALL_MS + 10_000L
		assertEquals(
			true,
			database.sourceEvidenceStateDao().synchronizeLifecycle(
				0L,
				retainedFromMs,
				markedAtMs,
			),
		)

		assertEquals(
			WifiCapturedRetentionResult.NoChange,
			maintenance.pruneAffectedByRetentionFloor(
				retainedFromMs,
				0L,
				0L,
				markedAtMs,
			),
		)
		assertEquals(1L, database.wifiCapturedFactDao().revisionCount())
	}

	@Test
	fun `Wi-Fi source erase fences proof-only scope before deleting retained proof`() =
		runTest {
			installValidFixture(candidateWriter = true)
			assertIs<WifiCapturedWriteResult.Applied>(writer.write(EVENT_ID))
			val markedAtMs = OBSERVED_WALL_MS + 10_000L
			database.sourceEvidenceStateDao().synchronizeLifecycle(
				0L,
				OBSERVED_WALL_MS,
				markedAtMs,
			)
			assertEquals(
				WifiCapturedRetentionResult.Pruned(1, 1),
				maintenance.pruneAffectedByRetentionFloor(
					OBSERVED_WALL_MS,
					0L,
					0L,
					markedAtMs,
				),
			)
			database.openHelper.writableDatabase.execSQL(
				"DELETE FROM source_event_wal WHERE source_kind = ?",
				arrayOf(WIFI_SOURCE),
			)
			revokeCaptureConsent()

			assertEquals(
				WifiCapturedSourceDeletionResult.Deleted(0, 0, 1),
				maintenance.deleteAfterCaptureConsentReset(
					0L,
					0L,
					REVOKED_POLICY_REVISION,
					REVOKED_CONSENT_EPOCH,
					DELETE_AT_MS,
				),
			)
			assertEquals(
				emptyList(),
				database.capturedRadioFactRetentionProofDao().allForSource(WIFI_SOURCE),
			)
			assertEquals(
				emptyList(),
				database.capturedRadioFactRetentionProofDao()
					.allForSource(SourceDestinationOwnerEntity.SOURCE_CELL),
			)
			val digest = SourceDeletionFenceEntity.logicalServiceRunIdentity(
				WIFI_SOURCE,
				SourceBrokerPurpose.SESSION_CAPTURE,
				LOGICAL_ID,
				RUN_ID,
			)
			assertEquals(
				true,
				database.sourceDeletionFenceDao().contains(
					WIFI_SOURCE,
					SourceBrokerPurpose.SESSION_CAPTURE,
					SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
					digest,
				),
			)
			assertEquals(
				WifiCaptureDeletionGenerationEntity(
					logicalTrackingId = LOGICAL_ID,
					serviceRunId = RUN_ID,
					collectedDataEpoch = 0L,
					generation = 1L,
					updatedAtMs = DELETE_AT_MS,
				),
				database.wifiCapturedFactDao().deletionGeneration(
					LOGICAL_ID,
					RUN_ID,
				),
			)
			assertEquals(
				WifiCapturedSourceDeletionResult.AlreadyDeleted,
				maintenance.deleteAfterCaptureConsentReset(
					0L,
					0L,
					REVOKED_POLICY_REVISION,
					REVOKED_CONSENT_EPOCH,
					DELETE_AT_MS + 1L,
				),
			)
		}

	@Test
	fun `Wi-Fi WAL retention proof alone installs both fences and preserves unrelated receipts`() =
		runTest {
			installValidFixture(candidateWriter = true)
			val captureWal = requireNotNull(database.sourceEventWalDao().getByEventId(EVENT_ID.value))
			val controlWal = insertNoncaptureWal(2, SourceBrokerPurpose.CONTROL_AUTOSTART)
			val ambientWal = insertNoncaptureWal(3, SourceBrokerPurpose.AMBIENT_PRODUCT)
			val captureProof = SourceEventRetentionProofEntity.createFromWal(captureWal)
			val controlProof = SourceEventRetentionProofEntity.createFromWal(controlWal)
			val ambientProof = SourceEventRetentionProofEntity.createFromWal(ambientWal)
			val otherSourceWal = captureWal.copy(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_CELL,
				eventId = "other-source-proof",
				providerDedupKey = "other-source-proof",
				integrityIdentity = "",
			)
			val otherSourceProof = SourceEventRetentionProofEntity.createFromWal(
				otherSourceWal.copy(
					integrityIdentity = otherSourceWal.calculatedIntegrityIdentity(),
				),
			)
			database.sourceEventRetentionProofDao().insertAll(
				listOf(captureProof, controlProof, ambientProof, otherSourceProof),
			)
			database.sourceEventRetentionScopeProofDao().insertIfAbsent(
				SourceEventRetentionScopeProofEntity.create(captureProof),
			)
			val otherSourceScope = SourceEventRetentionScopeProofEntity.create(otherSourceProof)
			database.sourceEventRetentionScopeProofDao().insertIfAbsent(otherSourceScope)
			database.openHelper.writableDatabase.execSQL(
				"DELETE FROM source_event_wal WHERE source_kind = ?",
				arrayOf(WIFI_SOURCE),
			)
			revokeCaptureConsent()

			assertEquals(
				WifiCapturedSourceDeletionResult.Deleted(0, 0, 1),
				maintenance.deleteAfterCaptureConsentReset(
					0L,
					0L,
					REVOKED_POLICY_REVISION,
					REVOKED_CONSENT_EPOCH,
					DELETE_AT_MS,
				),
			)
			assertEquals(
				WifiCaptureDeletionGenerationEntity(
					LOGICAL_ID,
					RUN_ID,
					0L,
					1L,
					DELETE_AT_MS,
				),
				database.wifiCapturedFactDao().deletionGeneration(
					LOGICAL_ID,
					RUN_ID,
				),
			)
			assertEquals(
				true,
				database.sourceDeletionFenceDao().contains(
					WIFI_SOURCE,
					SourceBrokerPurpose.SESSION_CAPTURE,
					SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
					requireNotNull(captureProof.logicalRunScopeDigest),
				),
			)
			assertEquals(null, database.sourceEventRetentionProofDao().get(
				WIFI_SOURCE,
				captureProof.eventId,
			))
			assertEquals(controlProof, database.sourceEventRetentionProofDao().get(
				WIFI_SOURCE,
				controlProof.eventId,
			))
			assertEquals(ambientProof, database.sourceEventRetentionProofDao().get(
				WIFI_SOURCE,
				ambientProof.eventId,
			))
			assertEquals(otherSourceProof, database.sourceEventRetentionProofDao().get(
				SourceDestinationOwnerEntity.SOURCE_CELL,
				otherSourceProof.eventId,
			))
			assertEquals(
				0L,
				database.sourceEventRetentionScopeProofDao().countForSource(WIFI_SOURCE),
			)
			assertEquals(
				otherSourceScope,
				database.sourceEventRetentionScopeProofDao().get(
					SourceDestinationOwnerEntity.SOURCE_CELL,
					otherSourceScope.logicalRunScopeDigest,
				),
			)

			database.sourceEventWalDao().insertIgnoringDuplicate(captureWal)
			assertEquals(
				WifiWalAdapterResult.Rejected(WifiWalAdapterRejection.DELETED_SCOPE),
				subject.qualify(EVENT_ID),
			)
		}

	@Test
	fun `corrupt Wi-Fi WAL retention proof blocks both deletion authorities`() = runTest {
		installValidFixture(candidateWriter = true)
		val captureWal = requireNotNull(database.sourceEventWalDao().getByEventId(EVENT_ID.value))
		val captureProof = SourceEventRetentionProofEntity.createFromWal(captureWal)
		database.sourceEventRetentionProofDao().insertAll(listOf(captureProof))
		database.sourceEventRetentionScopeProofDao().insertIfAbsent(
			SourceEventRetentionScopeProofEntity.create(captureProof),
		)
		database.openHelper.writableDatabase.execSQL(
			"DELETE FROM source_event_wal WHERE source_kind = ?",
			arrayOf(WIFI_SOURCE),
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_event_retention_proof SET effect_checksum = 'corrupt' " +
				"WHERE source_kind = ? AND event_id = ?",
			arrayOf<Any?>(WIFI_SOURCE, captureWal.eventId),
		)
		revokeCaptureConsent()

		assertEquals(
			WifiCapturedSourceDeletionResult.Blocked(
				WifiCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE,
			),
			maintenance.deleteAfterCaptureConsentReset(
				0L,
				0L,
				REVOKED_POLICY_REVISION,
				REVOKED_CONSENT_EPOCH,
				DELETE_AT_MS,
			),
		)
		assertEquals(0L, database.sourceDeletionFenceDao().countAll())
		assertEquals(0L, database.wifiCapturedFactDao().deletionGenerationCount())
		assertEquals(1L, database.sourceEventRetentionProofDao().countAll())
	}

	@Test
	fun `corrupt Wi-Fi scope authority fails closed without removing retained proof`() = runTest {
		installValidFixture(candidateWriter = true)
		val captureWal = requireNotNull(database.sourceEventWalDao().getByEventId(EVENT_ID.value))
		val captureProof = SourceEventRetentionProofEntity.createFromWal(captureWal)
		database.sourceEventRetentionProofDao().insertAll(listOf(captureProof))
		database.sourceEventRetentionScopeProofDao().insertIfAbsent(
			SourceEventRetentionScopeProofEntity.create(captureProof),
		)
		database.openHelper.writableDatabase.execSQL(
			"DELETE FROM source_event_wal WHERE source_kind = ?",
			arrayOf(WIFI_SOURCE),
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_event_retention_scope_proof SET effect_checksum = 'corrupt' " +
				"WHERE source_kind = ?",
			arrayOf(WIFI_SOURCE),
		)
		revokeCaptureConsent()

		assertEquals(
			WifiCapturedSourceDeletionResult.Blocked(
				WifiCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE,
			),
			maintenance.deleteAfterCaptureConsentReset(
				0L,
				0L,
				REVOKED_POLICY_REVISION,
				REVOKED_CONSENT_EPOCH,
				DELETE_AT_MS,
			),
		)
		assertEquals(0L, database.sourceDeletionFenceDao().countAll())
		assertEquals(0L, database.wifiCapturedFactDao().deletionGenerationCount())
		assertEquals(1L, database.sourceEventRetentionProofDao().countAll())
		assertEquals(1L, database.sourceEventRetentionScopeProofDao().countForSource(WIFI_SOURCE))
	}

	@Test
	fun `Wi-Fi source erase fences independent retained scopes sharing the original callback`() = runTest {
		installValidFixture(candidateWriter = true)
		assertIs<WifiCapturedWriteResult.Applied>(writer.write(EVENT_ID))
		val markedAtMs = OBSERVED_WALL_MS + 10_000L
		database.sourceEvidenceStateDao().synchronizeLifecycle(0L, OBSERVED_WALL_MS, markedAtMs)
		assertEquals(WifiCapturedRetentionResult.Pruned(1, 1),
			maintenance.pruneAffectedByRetentionFloor(OBSERVED_WALL_MS, 0L, 0L, markedAtMs))
		val first = database.capturedRadioFactRetentionProofDao().allForSource(WIFI_SOURCE).single()
		// This storage-consumer fixture represents a second independently retained session fact.
		// It exercises source cleanup only; historical Wi-Fi capture remains a separate slice.
		val secondId = "9".repeat(64)
		val secondMutation = WifiCapturedFactRevisionIntegrity.mutationId(secondId, first.semanticRevision)
		val secondRun = "second-retained-wifi-run"
		val secondDigest = SourceDeletionFenceEntity.logicalServiceRunIdentity(WIFI_SOURCE,
			SourceBrokerPurpose.SESSION_CAPTURE, LOGICAL_ID, secondRun)
		val unsigned = first.copy(logicalFactId = secondId, mutationId = secondMutation,
			cursorMutationId = secondMutation, serviceRunId = secondRun, logicalRunScopeDigest = secondDigest)
		val second = unsigned.copy(effectChecksum = unsigned.calculatedEffectChecksum())
		database.withTransaction { database.persistCapturedRadioFactRetentionProofs(listOf(second)) }
		database.openHelper.writableDatabase.execSQL("DELETE FROM source_event_wal WHERE source_kind = ?",
			arrayOf(WIFI_SOURCE))
		revokeCaptureConsent()

		assertEquals(WifiCapturedSourceDeletionResult.Deleted(0, 0, 2),
			maintenance.deleteAfterCaptureConsentReset(0L, 0L, REVOKED_POLICY_REVISION,
				REVOKED_CONSENT_EPOCH, DELETE_AT_MS))
		assertEquals(0L, database.capturedRadioFactRetentionProofDao().countAll())
		assertEquals(2L, database.wifiCapturedFactDao().deletionGenerationCount())
		for (digest in listOf(first.logicalRunScopeDigest, secondDigest)) {
			assertEquals(true, database.sourceDeletionFenceDao().contains(WIFI_SOURCE,
				SourceBrokerPurpose.SESSION_CAPTURE, SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN, digest))
		}
	}

	@Test
	fun `Wi-Fi mixed retained proof scopes for one event fail closed`() = runTest {
		installValidFixture(candidateWriter = true)
		assertIs<WifiCapturedWriteResult.Applied>(writer.write(EVENT_ID))
		val captureWal = requireNotNull(database.sourceEventWalDao().getByEventId(EVENT_ID.value))
		val markedAtMs = OBSERVED_WALL_MS + 10_000L
		database.sourceEvidenceStateDao().synchronizeLifecycle(0L, OBSERVED_WALL_MS, markedAtMs)
		assertEquals(
			WifiCapturedRetentionResult.Pruned(1, 1),
			maintenance.pruneAffectedByRetentionFloor(
				OBSERVED_WALL_MS,
				0L,
				0L,
				markedAtMs,
			),
		)
		val conflictingProof = SourceEventRetentionProofEntity.createFromWal(
			captureWal.copy(
				serviceRunId = "conflicting-run",
				integrityIdentity = "",
			).let { conflicting ->
				conflicting.copy(integrityIdentity = conflicting.calculatedIntegrityIdentity())
			},
		)
		database.sourceEventRetentionProofDao().insertAll(listOf(conflictingProof))
		database.sourceEventRetentionScopeProofDao().insertIfAbsent(
			SourceEventRetentionScopeProofEntity.create(conflictingProof),
		)
		database.openHelper.writableDatabase.execSQL(
			"DELETE FROM source_event_wal WHERE source_kind = ?",
			arrayOf(WIFI_SOURCE),
		)
		revokeCaptureConsent()

		assertEquals(
			WifiCapturedSourceDeletionResult.Blocked(
				WifiCapturedSourceDeletionBlockedReason.DELETION_FENCE_CONFLICT,
			),
			maintenance.deleteAfterCaptureConsentReset(
				0L,
				0L,
				REVOKED_POLICY_REVISION,
				REVOKED_CONSENT_EPOCH,
				DELETE_AT_MS,
			),
		)
		assertEquals(0L, database.sourceDeletionFenceDao().countAll())
		assertEquals(0L, database.wifiCapturedFactDao().deletionGenerationCount())
		assertEquals(1L, database.capturedRadioFactRetentionProofDao().countAll())
		assertEquals(1L, database.sourceEventRetentionProofDao().countAll())
	}

	@Test
	fun `retention rejects an epoch changed after its evidence snapshot`() = runTest {
		installValidFixture(candidateWriter = true)
		assertIs<WifiCapturedWriteResult.Applied>(writer.write(EVENT_ID))
		val retainedFromMs = OBSERVED_WALL_MS
		val markedAtMs = OBSERVED_WALL_MS + 10_000L
		assertEquals(
			true,
			database.sourceEvidenceStateDao().synchronizeLifecycle(0L, retainedFromMs, markedAtMs),
		)
		assertEquals(
			1,
			database.sourceEvidenceStateDao().updateLifecycle(
				epoch = 1L,
				retainedFromMs = retainedFromMs,
				updatedAtMs = markedAtMs + 1L,
			),
		)

		assertEquals(
			WifiCapturedRetentionResult.Blocked(
				WifiCapturedRetentionBlockedReason.SOURCE_EVIDENCE_AUTHORITY_CHANGED,
			),
			maintenance.pruneAffectedByRetentionFloor(
				beforeMs = retainedFromMs,
				expectedCollectedDataEpoch = 0L,
				expectedDeletedSourceEventHighWaterOrdinal = 0L,
				markedAtMs = markedAtMs + 2L,
			),
		)
		assertEquals(1L, database.wifiCapturedFactDao().revisionCount())
	}

	@Test
	fun `retention rejects a deleted high-water changed after its evidence snapshot`() = runTest {
		installValidFixture(candidateWriter = true)
		assertIs<WifiCapturedWriteResult.Applied>(writer.write(EVENT_ID))
		val retainedFromMs = OBSERVED_WALL_MS
		val markedAtMs = OBSERVED_WALL_MS + 10_000L
		assertEquals(
			true,
			database.sourceEvidenceStateDao().synchronizeLifecycle(0L, retainedFromMs, markedAtMs),
		)
		assertEquals(
			1,
			database.sourceEvidenceStateDao().updateAfterFullDeletion(
				epoch = 0L,
				retainedFromMs = retainedFromMs,
				deletedSourceEventHighWaterOrdinal = 1L,
				updatedAtMs = markedAtMs + 1L,
			),
		)

		assertEquals(
			WifiCapturedRetentionResult.Blocked(
				WifiCapturedRetentionBlockedReason.SOURCE_EVIDENCE_AUTHORITY_CHANGED,
			),
			maintenance.pruneAffectedByRetentionFloor(
				beforeMs = retainedFromMs,
				expectedCollectedDataEpoch = 0L,
				expectedDeletedSourceEventHighWaterOrdinal = 0L,
				markedAtMs = markedAtMs + 2L,
			),
		)
		assertEquals(1L, database.wifiCapturedFactDao().revisionCount())
	}

	@Test
	fun `retention rejects a cutoff changed after its evidence snapshot`() = runTest {
		installValidFixture(candidateWriter = true)
		assertIs<WifiCapturedWriteResult.Applied>(writer.write(EVENT_ID))
		val requestedRetainedFromMs = OBSERVED_WALL_MS - 1_000L
		val currentRetainedFromMs = OBSERVED_WALL_MS
		val markedAtMs = OBSERVED_WALL_MS + 10_000L
		assertEquals(
			true,
			database.sourceEvidenceStateDao().synchronizeLifecycle(
				epoch = 0L,
				retainedFromMs = requestedRetainedFromMs,
				updatedAtMs = markedAtMs,
			),
		)
		assertEquals(
			true,
			database.sourceEvidenceStateDao().synchronizeLifecycle(
				epoch = 0L,
				retainedFromMs = currentRetainedFromMs,
				updatedAtMs = markedAtMs + 1L,
			),
		)

		assertEquals(
			WifiCapturedRetentionResult.Blocked(
				WifiCapturedRetentionBlockedReason.SOURCE_EVIDENCE_AUTHORITY_CHANGED,
			),
			maintenance.pruneAffectedByRetentionFloor(
				beforeMs = requestedRetainedFromMs,
				expectedCollectedDataEpoch = 0L,
				expectedDeletedSourceEventHighWaterOrdinal = 0L,
				markedAtMs = markedAtMs + 2L,
			),
		)
		assertEquals(1L, database.wifiCapturedFactDao().revisionCount())
	}

	@Test
	fun `source deletion requires revoked capture consent`() = runTest {
		installValidFixture(candidateWriter = true)
		assertIs<WifiCapturedWriteResult.Applied>(writer.write(EVENT_ID))

		assertEquals(
			WifiCapturedSourceDeletionResult.Blocked(
				WifiCapturedSourceDeletionBlockedReason.CAPTURE_CONSENT_STILL_ELIGIBLE,
			),
			maintenance.deleteAfterCaptureConsentReset(
				0L,
				0L,
				POLICY_REVISION,
				CONSENT_EPOCH,
				DELETE_AT_MS,
			),
		)
		assertEquals(1L, database.wifiCapturedFactDao().revisionCount())
	}

	@Test
	fun `revoked source deletion fences exact WAL scope before removal and delayed replay stays rejected`() = runTest {
		installValidFixture(candidateWriter = true)
		assertIs<WifiCapturedWriteResult.Applied>(writer.write(EVENT_ID))
		revokeCaptureConsent()
		assertEquals(1, database.sourceEvidenceStateDao().incrementRevision(DELETE_AT_MS + 1L))

		assertEquals(
			WifiCapturedSourceDeletionResult.Deleted(1, 1, 1),
			maintenance.deleteAfterCaptureConsentReset(
				0L,
				0L,
				REVOKED_POLICY_REVISION,
				REVOKED_CONSENT_EPOCH,
				DELETE_AT_MS,
			),
		)
		assertEquals(0L, database.wifiCapturedFactDao().revisionCount())
		assertEquals(0L, database.wifiCapturedFactDao().cursorCount())
		assertEquals(1L, database.sourceEventWalDao().countAll())
		assertEquals(DELETE_AT_MS + 1L, database.sourceEvidenceStateDao().get()?.updatedAtMs)
		assertEquals(
			1L,
			requireNotNull(database.wifiCapturedFactDao().deletionGeneration(LOGICAL_ID, RUN_ID)).generation,
		)
		assertEquals(
			WifiWalAdapterResult.Rejected(WifiWalAdapterRejection.DELETED_SCOPE),
			subject.qualify(EVENT_ID),
		)
		assertEquals(
			WifiCapturedSourceDeletionResult.AlreadyDeleted,
			maintenance.deleteAfterCaptureConsentReset(
				0L,
				0L,
				REVOKED_POLICY_REVISION,
				REVOKED_CONSENT_EPOCH,
				DELETE_AT_MS,
			),
		)
	}

	@Test
	fun `WAL only capture scope is authenticated fenced and retained`() = runTest {
		installValidFixture(candidateWriter = true)
		revokeCaptureConsent()

		assertEquals(
			WifiCapturedSourceDeletionResult.Deleted(0, 0, 1),
			maintenance.deleteAfterCaptureConsentReset(
				0L,
				0L,
				REVOKED_POLICY_REVISION,
				REVOKED_CONSENT_EPOCH,
				DELETE_AT_MS,
			),
		)
		assertEquals(1L, database.sourceEventWalDao().countAll())
		assertEquals(
			1L,
			requireNotNull(database.wifiCapturedFactDao().deletionGeneration(LOGICAL_ID, RUN_ID)).generation,
		)
	}

	@Test
	fun `source deletion rejects active capture demand and nonterminal provider independently`() = runTest {
		installValidFixture(candidateWriter = true)
		revokeCaptureConsent()
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_demand SET status = 'ACTIVE', retire_boot_id = NULL, " +
				"retire_elapsed_realtime_nanos = NULL, retired_at_ms = NULL WHERE demand_id = ?",
			arrayOf("$DEMAND_ID-0"),
		)
		assertEquals(
			WifiCapturedSourceDeletionResult.Blocked(
				WifiCapturedSourceDeletionBlockedReason.CAPTURE_DEMAND_NOT_QUIESCED,
			),
			maintenance.deleteAfterCaptureConsentReset(
				0L,
				0L,
				REVOKED_POLICY_REVISION,
				REVOKED_CONSENT_EPOCH,
				DELETE_AT_MS,
			),
		)

		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_demand SET status = 'RETIRED', retire_boot_id = ?, " +
				"retire_elapsed_realtime_nanos = ?, retired_at_ms = ? WHERE demand_id = ?",
			arrayOf<Any?>(BOOT_ID, SESSION_END_NANOS, SESSION_END_WALL_MS, "$DEMAND_ID-0"),
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE provider_registration_generation SET status = 'ACTIVE', retired_at_ms = NULL, " +
				"retired_elapsed_realtime_nanos = NULL WHERE source_kind = ? " +
				"AND registration_generation = ?",
			arrayOf<Any?>(WIFI_SOURCE, REGISTRATION_GENERATION),
		)
		assertEquals(
			WifiCapturedSourceDeletionResult.Blocked(
				WifiCapturedSourceDeletionBlockedReason.CAPTURE_PROVIDER_NOT_QUIESCED,
			),
			maintenance.deleteAfterCaptureConsentReset(
				0L,
				0L,
				REVOKED_POLICY_REVISION,
				REVOKED_CONSENT_EPOCH,
				DELETE_AT_MS,
			),
		)
	}

	@Test
	fun `exact no-CAP successor lets Wi-Fi cleanup preserve current CONTROL`() =
		runTest {
			installValidFixture(candidateWriter = true)
			assertIs<WifiCapturedWriteResult.Applied>(writer.write(EVENT_ID))
			revokeCaptureConsent(keepAmbientAndControl = true)
			database.openHelper.writableDatabase.execSQL(
				"UPDATE source_policy SET qos_code = 2 WHERE policy_revision = ? AND source_kind = ?",
				arrayOf<Any?>(REVOKED_POLICY_REVISION, WIFI_SOURCE),
			)
			database.openHelper.writableDatabase.execSQL(
				"UPDATE source_demand SET status = 'RETIRING', retire_boot_id = ?, " +
					"retire_elapsed_realtime_nanos = ?, retired_at_ms = ? WHERE demand_id = ?",
				arrayOf<Any?>(
					BOOT_ID,
					SESSION_END_NANOS + 1L,
					SESSION_END_WALL_MS + 1L,
					"$DEMAND_ID-0",
				),
			)
			val control = nonCaptureDemand(41, SourceBrokerPurpose.CONTROL_AUTOSTART, qosCode = 2)
			val unrelated = control.copy(
				demandId = "$DEMAND_ID-unrelated-cell",
				consumerId = "app:cell-control",
				sourceKind = SourceKind.CELL.stableCode,
			)
			database.sourceBrokerDao().insertDemands(listOf(control, unrelated))
			val activeRegistration = registration(
				generation = REGISTRATION_GENERATION + 1L,
				sourceInstance = "$SOURCE_INSTANCE-noncapture",
			).copy(
				status = ProviderRegistrationGenerationEntity.STATUS_ACTIVE,
				retiredAtMs = null,
				retiredElapsedRealtimeNanos = null,
				captureCallbackBarrierAuthorizationRevision = AUTHORIZATION_REVISION + 2L,
			)
			database.sourceBrokerDao().insertRegistration(activeRegistration)
			val priorCapture = database.sourceBrokerDao().demandsByIds(listOf("$DEMAND_ID-0")).single()
			database.sourceBrokerDao().insertAuthorizations(SourceBrokerAuthorization.rows(WIFI_SOURCE,
				activeRegistration.registrationGeneration, AUTHORIZATION_REVISION + 1L, listOf(priorCapture),
				BOOT_ID, AUTHORIZATION_START_NANOS, RUN_START_WALL_MS))
			database.sourceBrokerDao().insertAuthorizations(
				SourceBrokerAuthorization.rows(
					sourceKind = WIFI_SOURCE,
					registrationGeneration = activeRegistration.registrationGeneration,
					authorizationRevision = AUTHORIZATION_REVISION + 2L,
					demands = listOf(control),
					effectiveBootId = BOOT_ID,
					effectiveElapsedRealtimeNanos = SESSION_END_NANOS + 1L,
					effectiveWallTimeMs = SESSION_END_WALL_MS + 1L,
				),
			)

			assertEquals(
				WifiCapturedSourceDeletionResult.Deleted(1, 1, 1),
				maintenance.deleteAfterCaptureConsentReset(
					0L,
					0L,
					REVOKED_POLICY_REVISION,
					REVOKED_CONSENT_EPOCH,
					DELETE_AT_MS,
				),
			)

			val retainedDemands = database.sourceBrokerDao().demandsByIds(
				listOf("$DEMAND_ID-0", control.demandId, unrelated.demandId),
			).associateBy(SourceDemandEntity::demandId)
			assertEquals(
				SourceDemandEntity.STATUS_RETIRED,
				retainedDemands.getValue("$DEMAND_ID-0").status,
			)
			assertEquals(
				SourceDemandEntity.STATUS_ACTIVE,
				retainedDemands.getValue(control.demandId).status,
			)
			assertEquals(
				SourceDemandEntity.STATUS_ACTIVE,
				retainedDemands.getValue(unrelated.demandId).status,
			)
			assertEquals(
				ProviderRegistrationGenerationEntity.STATUS_ACTIVE,
				database.sourceBrokerDao().registration(
					WIFI_SOURCE,
					activeRegistration.registrationGeneration,
				)?.status,
			)
			assertEquals(1L, database.sourceEventWalDao().countAll())
			assertEquals(0L, database.wifiCapturedFactDao().revisionCount())
			assertEquals(0L, database.wifiCapturedFactDao().cursorCount())
		}



	@Test
	fun `custom Wi-Fi policy preserves actual Ambient QoS zero during capture cleanup`() =
		runTest {
			installValidFixture(candidateWriter = true)
			assertIs<WifiCapturedWriteResult.Applied>(writer.write(EVENT_ID))
			revokeCaptureConsent(keepAmbientAndControl = true)
			database.openHelper.writableDatabase.execSQL(
				"UPDATE source_policy SET qos_code = 2 WHERE policy_revision = ? AND source_kind = ?",
				arrayOf<Any?>(REVOKED_POLICY_REVISION, WIFI_SOURCE),
			)
			database.openHelper.writableDatabase.execSQL(
				"UPDATE source_demand SET status = 'RETIRING', retire_boot_id = ?, " +
					"retire_elapsed_realtime_nanos = ?, retired_at_ms = ? WHERE demand_id = ?",
				arrayOf<Any?>(
					BOOT_ID,
					SESSION_END_NANOS + 1L,
					SESSION_END_WALL_MS + 1L,
					"$DEMAND_ID-0",
				),
			)
			val ambient = nonCaptureDemand(40, SourceBrokerPurpose.AMBIENT_PRODUCT)
			val control = nonCaptureDemand(41, SourceBrokerPurpose.CONTROL_AUTOSTART, qosCode = 2)
			assertEquals(0, ambient.qosCode)
			assertEquals(600_000L, ambient.maximumAgeMs)
			val unrelated = control.copy(
				demandId = "$DEMAND_ID-unrelated-cell",
				consumerId = "app:cell-control",
				sourceKind = SourceKind.CELL.stableCode,
			)
			database.sourceBrokerDao().insertDemands(listOf(ambient, control, unrelated))
			val activeRegistration = registration(
				generation = REGISTRATION_GENERATION + 1L,
				sourceInstance = "$SOURCE_INSTANCE-noncapture",
			).copy(
				status = ProviderRegistrationGenerationEntity.STATUS_ACTIVE,
				retiredAtMs = null,
				retiredElapsedRealtimeNanos = null,
				captureCallbackBarrierAuthorizationRevision = AUTHORIZATION_REVISION + 1L,
			)
			database.sourceBrokerDao().insertRegistration(activeRegistration)
			database.sourceBrokerDao().insertAuthorizations(
				SourceBrokerAuthorization.rows(
					sourceKind = WIFI_SOURCE,
					registrationGeneration = activeRegistration.registrationGeneration,
					authorizationRevision = AUTHORIZATION_REVISION + 1L,
					demands = listOf(ambient, control),
					effectiveBootId = BOOT_ID,
					effectiveElapsedRealtimeNanos = SESSION_END_NANOS + 1L,
					effectiveWallTimeMs = SESSION_END_WALL_MS + 1L,
				),
			)

			assertEquals(
				WifiCapturedSourceDeletionResult.Deleted(1, 1, 1),
				maintenance.deleteAfterCaptureConsentReset(
					0L,
					0L,
					REVOKED_POLICY_REVISION,
					REVOKED_CONSENT_EPOCH,
					DELETE_AT_MS,
				),
			)

			val retainedDemands = database.sourceBrokerDao().demandsByIds(
				listOf("$DEMAND_ID-0", ambient.demandId, control.demandId, unrelated.demandId),
			).associateBy(SourceDemandEntity::demandId)
			assertEquals(
				SourceDemandEntity.STATUS_RETIRED,
				retainedDemands.getValue("$DEMAND_ID-0").status,
			)
			assertEquals(
				SourceDemandEntity.STATUS_ACTIVE,
				retainedDemands.getValue(ambient.demandId).status,
			)
			assertEquals(
				SourceDemandEntity.STATUS_ACTIVE,
				retainedDemands.getValue(control.demandId).status,
			)
			assertEquals(
				SourceDemandEntity.STATUS_ACTIVE,
				retainedDemands.getValue(unrelated.demandId).status,
			)
			assertEquals(
				ProviderRegistrationGenerationEntity.STATUS_ACTIVE,
				database.sourceBrokerDao().registration(
					WIFI_SOURCE,
					activeRegistration.registrationGeneration,
				)?.status,
			)
			assertEquals(1L, database.sourceEventWalDao().countAll())
			assertEquals(0L, database.wifiCapturedFactDao().revisionCount())
			assertEquals(0L, database.wifiCapturedFactDao().cursorCount())
		}


	@Test
	fun `source deletion retires only authenticated capture while ambient and control stay active`() =
		runTest {
			installValidFixture(candidateWriter = true)
			assertIs<WifiCapturedWriteResult.Applied>(writer.write(EVENT_ID))
			revokeCaptureConsent(keepAmbientAndControl = true)
			database.openHelper.writableDatabase.execSQL(
				"UPDATE source_demand SET status = 'RETIRING', retire_boot_id = ?, " +
					"retire_elapsed_realtime_nanos = ?, retired_at_ms = ? WHERE demand_id = ?",
				arrayOf<Any?>(
					BOOT_ID,
					SESSION_END_NANOS + 1L,
					SESSION_END_WALL_MS + 1L,
					"$DEMAND_ID-0",
				),
			)
			val ambient = nonCaptureDemand(40, SourceBrokerPurpose.AMBIENT_PRODUCT)
			val control = nonCaptureDemand(41, SourceBrokerPurpose.CONTROL_AUTOSTART)
			val unrelated = control.copy(
				demandId = "$DEMAND_ID-unrelated-cell",
				consumerId = "app:cell-control",
				sourceKind = SourceKind.CELL.stableCode,
			)
			database.sourceBrokerDao().insertDemands(listOf(ambient, control, unrelated))
			val activeRegistration = registration(
				generation = REGISTRATION_GENERATION + 1L,
				sourceInstance = "$SOURCE_INSTANCE-noncapture",
			).copy(
				status = ProviderRegistrationGenerationEntity.STATUS_ACTIVE,
				retiredAtMs = null,
				retiredElapsedRealtimeNanos = null,
				captureCallbackBarrierAuthorizationRevision = AUTHORIZATION_REVISION + 1L,
			)
			database.sourceBrokerDao().insertRegistration(activeRegistration)
			database.sourceBrokerDao().insertAuthorizations(
				SourceBrokerAuthorization.rows(
					sourceKind = WIFI_SOURCE,
					registrationGeneration = activeRegistration.registrationGeneration,
					authorizationRevision = AUTHORIZATION_REVISION + 1L,
					demands = listOf(ambient, control),
					effectiveBootId = BOOT_ID,
					effectiveElapsedRealtimeNanos = SESSION_END_NANOS + 1L,
					effectiveWallTimeMs = SESSION_END_WALL_MS + 1L,
				),
			)

			assertEquals(
				WifiCapturedSourceDeletionResult.Deleted(1, 1, 1),
				maintenance.deleteAfterCaptureConsentReset(
					0L,
					0L,
					REVOKED_POLICY_REVISION,
					REVOKED_CONSENT_EPOCH,
					DELETE_AT_MS,
				),
			)

			val retainedDemands = database.sourceBrokerDao().demandsByIds(
				listOf("$DEMAND_ID-0", ambient.demandId, control.demandId, unrelated.demandId),
			).associateBy(SourceDemandEntity::demandId)
			assertEquals(
				SourceDemandEntity.STATUS_RETIRED,
				retainedDemands.getValue("$DEMAND_ID-0").status,
			)
			assertEquals(
				SourceDemandEntity.STATUS_ACTIVE,
				retainedDemands.getValue(ambient.demandId).status,
			)
			assertEquals(
				SourceDemandEntity.STATUS_ACTIVE,
				retainedDemands.getValue(control.demandId).status,
			)
			assertEquals(
				SourceDemandEntity.STATUS_ACTIVE,
				retainedDemands.getValue(unrelated.demandId).status,
			)
			assertEquals(
				ProviderRegistrationGenerationEntity.STATUS_ACTIVE,
				database.sourceBrokerDao().registration(
					WIFI_SOURCE,
					activeRegistration.registrationGeneration,
				)?.status,
			)
			assertEquals(1L, database.sourceEventWalDao().countAll())
			assertEquals(0L, database.wifiCapturedFactDao().revisionCount())
			assertEquals(0L, database.wifiCapturedFactDao().cursorCount())
		}

	@Test
	fun `active noncapture demand with a retirement boundary fails closed`() = runTest {
		installValidFixture(candidateWriter = true)
		assertIs<WifiCapturedWriteResult.Applied>(writer.write(EVENT_ID))
		revokeCaptureConsent(keepAmbientAndControl = true)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_demand SET status = 'RETIRING', retire_boot_id = ?, " +
				"retire_elapsed_realtime_nanos = ?, retired_at_ms = ? WHERE demand_id = ?",
			arrayOf<Any?>(
				BOOT_ID,
				SESSION_END_NANOS + 1L,
				SESSION_END_WALL_MS + 1L,
				"$DEMAND_ID-0",
			),
		)
		val malformedAmbient = nonCaptureDemand(42, SourceBrokerPurpose.AMBIENT_PRODUCT).copy(
			retireBootId = BOOT_ID,
			retireElapsedRealtimeNanos = SESSION_END_NANOS + 2L,
			retiredAtMs = SESSION_END_WALL_MS + 2L,
		)
		database.sourceBrokerDao().insertDemands(listOf(malformedAmbient))
		val activeRegistration = registration(
			generation = REGISTRATION_GENERATION + 1L,
			sourceInstance = "$SOURCE_INSTANCE-malformed-noncapture",
		).copy(
			status = ProviderRegistrationGenerationEntity.STATUS_ACTIVE,
			retiredAtMs = null,
			retiredElapsedRealtimeNanos = null,
			captureCallbackBarrierAuthorizationRevision = AUTHORIZATION_REVISION + 1L,
		)
		database.sourceBrokerDao().insertRegistration(activeRegistration)
		database.sourceBrokerDao().insertAuthorizations(
			SourceBrokerAuthorization.rows(
				sourceKind = WIFI_SOURCE,
				registrationGeneration = activeRegistration.registrationGeneration,
				authorizationRevision = AUTHORIZATION_REVISION + 1L,
				demands = listOf(malformedAmbient),
				effectiveBootId = BOOT_ID,
				effectiveElapsedRealtimeNanos = SESSION_END_NANOS + 1L,
				effectiveWallTimeMs = SESSION_END_WALL_MS + 1L,
			),
		)

		assertEquals(
			WifiCapturedSourceDeletionResult.Blocked(
				WifiCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE,
			),
			maintenance.deleteAfterCaptureConsentReset(
				0L,
				0L,
				REVOKED_POLICY_REVISION,
				REVOKED_CONSENT_EPOCH,
				DELETE_AT_MS,
			),
		)
		assertEquals(1L, database.wifiCapturedFactDao().revisionCount())
		assertEquals(0L, database.wifiCapturedFactDao().deletionGenerationCount())
	}

	@Test
	fun `custom Wi-Fi policy preserves authentic retained Ambient QoS zero WAL`() =
		runTest {
			installValidFixture(
				evidenceState = SourceEvidenceState(deletedSourceEventHighWaterOrdinal = 1L),
			)
			revokeCaptureConsent(keepAmbientAndControl = true)
			database.openHelper.writableDatabase.execSQL(
				"UPDATE source_policy SET qos_code = 2 WHERE policy_revision = ? AND source_kind = ?",
				arrayOf<Any?>(REVOKED_POLICY_REVISION, WIFI_SOURCE),
			)
			val ambientBefore = insertNoncaptureWal(2, SourceBrokerPurpose.AMBIENT_PRODUCT)

			assertEquals(
				WifiCapturedSourceDeletionResult.AlreadyDeleted,
				maintenance.deleteAfterCaptureConsentReset(
					0L,
					1L,
					REVOKED_POLICY_REVISION,
					REVOKED_CONSENT_EPOCH,
					DELETE_AT_MS,
				),
			)

			assertExactWal(ambientBefore)
			assertEquals(0L, database.wifiCapturedFactDao().deletionGenerationCount())
			assertEquals(0L, database.sourceEvidenceStateDao().get()?.revision)
		}


	@Test
	fun `source deletion preserves real ingress control and ambient WAL without capture scope or time`() =
		runTest {
			installValidFixture(
				evidenceState = SourceEvidenceState(deletedSourceEventHighWaterOrdinal = 1L),
			)
			revokeCaptureConsent(keepAmbientAndControl = true)
			val controlBefore = insertNoncaptureWal(2, SourceBrokerPurpose.CONTROL_AUTOSTART)
			val ambientBefore = insertNoncaptureWal(3, SourceBrokerPurpose.AMBIENT_PRODUCT)

			assertEquals(
				WifiCapturedSourceDeletionResult.AlreadyDeleted,
				maintenance.deleteAfterCaptureConsentReset(
					0L,
					1L,
					REVOKED_POLICY_REVISION,
					REVOKED_CONSENT_EPOCH,
					DELETE_AT_MS,
				),
			)

			assertExactWal(controlBefore)
			assertExactWal(ambientBefore)
			assertEquals(0L, database.wifiCapturedFactDao().deletionGenerationCount())
			assertEquals(0L, database.sourceEvidenceStateDao().get()?.revision)
		}

	@Test
	fun `old epoch and below high-water WAL are nonblocking before payload authentication`() = runTest {
		installValidFixture(evidenceState = SourceEvidenceState(
			collectedDataEpoch = 1L,
			deletedSourceEventHighWaterOrdinal = 1L,
		))
		val oldEpoch = insertSecondWal()
		rewriteWalEpochAndPayload(EVENT_ID, 1L, byteArrayOf(0))
		rewriteWalEpochAndPayload(oldEpoch, 0L, byteArrayOf(0))
		revokeCaptureConsent()

		assertEquals(
			WifiCapturedSourceDeletionResult.AlreadyDeleted,
			maintenance.deleteAfterCaptureConsentReset(
				1L,
				1L,
				REVOKED_POLICY_REVISION,
				REVOKED_CONSENT_EPOCH,
				DELETE_AT_MS,
			),
		)
		assertEquals(2L, database.sourceEventWalDao().countAll())
		assertEquals(0L, database.wifiCapturedFactDao().deletionGenerationCount())
	}

	@Test
	fun `noncapture mask with capture bindings cannot masquerade as retained WAL`() = runTest {
		installValidFixture()
		rewriteWalPurpose(EVENT_ID, SourceBrokerPurpose.MASK_CONTROL_AUTOSTART)
		revokeCaptureConsent()

		assertEquals(
			WifiCapturedSourceDeletionResult.Blocked(
				WifiCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE,
			),
			maintenance.deleteAfterCaptureConsentReset(
				0L,
				0L,
				REVOKED_POLICY_REVISION,
				REVOKED_CONSENT_EPOCH,
				DELETE_AT_MS,
			),
		)
		assertEquals(1L, database.sourceEventWalDao().countAll())
		assertEquals(0L, database.wifiCapturedFactDao().deletionGenerationCount())
	}

	@Test
	fun `newer authenticated capture WAL advances deletion staleness`() = runTest {
		installValidFixture(candidateWriter = true)
		insertSecondWal(DELETE_AT_MS + 100L)
		revokeCaptureConsent()

		assertEquals(
			WifiCapturedSourceDeletionResult.Blocked(
				WifiCapturedSourceDeletionBlockedReason.STALE_REQUEST,
			),
			maintenance.deleteAfterCaptureConsentReset(
				0L,
				0L,
				REVOKED_POLICY_REVISION,
				REVOKED_CONSENT_EPOCH,
				DELETE_AT_MS,
			),
		)
		assertEquals(2L, database.sourceEventWalDao().countAll())
		assertEquals(0L, database.wifiCapturedFactDao().deletionGenerationCount())
	}

	@Test
	fun `bounded WAL audit fails closed before source deletion`() = runTest {
		installValidFixture(candidateWriter = true)
		insertSecondWal()
		revokeCaptureConsent()

		assertEquals(
			WifiCapturedSourceDeletionResult.Blocked(
				WifiCapturedSourceDeletionBlockedReason.MAINTENANCE_BOUND_EXCEEDED,
			),
			maintenance.deleteAfterCaptureConsentReset(
				0L,
				0L,
				REVOKED_POLICY_REVISION,
				REVOKED_CONSENT_EPOCH,
				DELETE_AT_MS,
				WifiCapturedMaintenanceLimits(maximumWalEvents = 1),
			) {},
		)
		assertEquals(2L, database.sourceEventWalDao().countAll())
		assertEquals(0L, database.wifiCapturedFactDao().deletionGenerationCount())
	}

	@Test
	fun `cancellation after deletion fences rolls the whole source mutation back`() = runTest {
		installValidFixture(candidateWriter = true)
		assertIs<WifiCapturedWriteResult.Applied>(writer.write(EVENT_ID))
		revokeCaptureConsent()
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_demand SET status = 'RETIRING', retire_boot_id = ?, " +
				"retire_elapsed_realtime_nanos = ?, retired_at_ms = ? WHERE demand_id = ?",
			arrayOf<Any?>(
				BOOT_ID,
				SESSION_END_NANOS + 1L,
				SESSION_END_WALL_MS + 1L,
				"$DEMAND_ID-0",
			),
		)

		assertFailsWith<CancellationException> {
			maintenance.deleteAfterCaptureConsentReset(
				0L,
				0L,
				REVOKED_POLICY_REVISION,
				REVOKED_CONSENT_EPOCH,
				DELETE_AT_MS,
				WifiCapturedMaintenanceLimits(),
			) { checkpoint ->
				if (checkpoint == WifiCapturedMaintenanceCheckpoint.DELETION_FENCES_INSTALLED) {
					throw CancellationException("cancel deletion")
				}
			}
		}
		assertEquals(1L, database.wifiCapturedFactDao().revisionCount())
		assertEquals(1L, database.wifiCapturedFactDao().cursorCount())
		assertEquals(0L, database.wifiCapturedFactDao().deletionGenerationCount())
		assertEquals(
			SourceDemandEntity.STATUS_RETIRING,
			database.sourceBrokerDao().demandsByIds(listOf("$DEMAND_ID-0")).single().status,
		)
		val digest = SourceDeletionFenceEntity.logicalServiceRunIdentity(
			WIFI_SOURCE,
			SourceBrokerPurpose.SESSION_CAPTURE,
			LOGICAL_ID,
			RUN_ID,
		)
		assertEquals(
			false,
			database.sourceDeletionFenceDao().contains(
				WIFI_SOURCE,
				SourceBrokerPurpose.SESSION_CAPTURE,
				SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
				digest,
			),
		)
	}

	@Test
	fun `portable export emits deterministic identity-free captured Wi-Fi outside Room transaction`() = runTest {
		installPortableExportFixture()
		val emitted = mutableListOf<PortableCapturedWifiEntryV1>()
		var sinkObservedTransaction: Boolean? = null

		repeat(2) {
			val result = portableExporter().export(ExportPortableCapturedWifiRequest(LOGICAL_ID)) { entry ->
				sinkObservedTransaction = database.openHelper.writableDatabase.inTransaction()
				emitted += entry
			}
			assertIs<ExportPortableCapturedWifiResult.Exported>(result)
		}

		assertEquals(2, emitted.size)
		assertEquals(emitted.first(), emitted.last())
		assertEquals(false, sinkObservedTransaction)
		val entry = emitted.distinct().single()
		assertEquals(1, entry.runs.size)
		assertEquals(1, entry.runs.single().observations.size)
		assertEquals(PortableWifiCaptureCoverage.WHOLE_RUN, entry.runs.single().captureCoverage)
		assertEquals(PortableWifiRunAvailability.RETAINED, entry.runs.single().availability)
		assertTrue(LOGICAL_ID !in entry.toString())
		assertTrue(RUN_ID !in entry.toString())
		assertTrue(SOURCE_INSTANCE !in entry.toString())
		assertTrue(BOOT_ID !in entry.toString())
	}

	@Test
	fun `two database production export import read and latest reexport roundtrip is exact`() = runTest {
		installPortableExportFixture()
		assertTrue(database.sourceEventWalDao().countAll() > 0L)
		assertTrue(database.wifiCapturedFactDao().revisionCount() > 0L)
		val sourceRevisions = database.wifiCapturedFactDao().portableRevisionClosure(
			LOGICAL_ID,
			listOf(RUN_ID),
			8,
		)
		for (revision in sourceRevisions) {
			val wal = requireNotNull(database.sourceEventWalDao().getByEventId(revision.sourceEventId))
			assertEquals(wal.admissionOrdinal, revision.sourceAdmissionOrdinal)
			assertEquals(wal.integrityIdentity, revision.walIntegrityIdentity)
			assertEquals(wal.payloadChecksum, revision.payloadChecksum)
		}
		val exported = mutableListOf<PortableCapturedWifiEntryV1>()
		assertIs<ExportPortableCapturedWifiResult.Exported>(
			portableExporter().export(ExportPortableCapturedWifiRequest(LOGICAL_ID)) {
				exported += it
			},
		)
		val sourceEntry = exported.single()
		val target = AppDatabase.testDatabase(ApplicationProvider.getApplicationContext<Application>())
		try {
			target.sourceEvidenceStateDao().ensure(SourceEvidenceState(collectedDataEpoch = 0L))
			val importer = RoomImportPortableCapturedWifi(target, Dispatchers.IO)
			assertEquals(
				ImportPortableCapturedWifiResult.Applied(
					importRevision = 1L,
					physicalRunCount = sourceEntry.runs.size,
					observationCount = sourceEntry.runs.sumOf { it.observations.size },
				),
				importer.importEntry(
					ImportPortableCapturedWifiRequest(
						sourceEntry,
						PortableCapturedWifiImportReceipt(
							"roundtrip-job",
							"roundtrip-entry",
							"native.trackerwifi",
							10_000L,
						),
						0L,
					),
				),
			)
			val evaluator = RoomImportedWifiProductEvaluator(target)
			val selected = target.withTransaction {
				evaluator.selectIdentityInTransaction(
					WifiImportedHistorySelectionKey(sourceEntry.identity.value),
				)
			}
			val importedEntry = assertIs<ImportedWifiProductEvaluation.Readable>(selected).entry
			assertEquals(sourceEntry, importedEntry)
			assertEquals(
				sourceEntry.runs.single().observations.single().coverageStartTimeMs,
				importedEntry.runs.single().observations.single().coverageStartTimeMs,
			)
			assertEquals(
				sourceEntry.runs.single().observations.single().latestPossibleTimeMs,
				importedEntry.runs.single().observations.single().latestPossibleTimeMs,
			)

			val reexported = mutableListOf<PortableCapturedWifiEntryV1>()
			var sinkInTransaction = true
			val result = RoomReexportImportedCapturedWifi(
				target,
				evaluator,
				Dispatchers.IO,
			).reexport(
				ReexportImportedCapturedWifiRequest(
					WifiImportedHistorySelection(
						WifiImportedHistorySelectionKey(sourceEntry.identity.value),
						1L,
						sourceEntry.contentChecksum.value,
					),
					0L,
				),
			) {
				sinkInTransaction = target.openHelper.writableDatabase.inTransaction()
				reexported += it
			}
			assertIs<ReexportImportedCapturedWifiResult.Exported>(result)
			assertTrue(!sinkInTransaction)
			assertEquals(sourceEntry, reexported.single())
			assertEquals(0L, target.wifiCapturedFactDao().revisionCount())
			assertEquals(0L, target.sourceEventWalDao().countAll())
			assertEquals(null, target.sourceSessionDao().session(LOGICAL_ID))
		} finally {
			target.close()
		}
	}

	@Test
	fun `selected native Wi-Fi deletion preserves control and WAL while removing exact product scope`() =
		runTest {
			installMixedPortableExportFixture(activeContinuation = true)
			val emitted = mutableListOf<PortableCapturedWifiEntryV1>()
			assertIs<ExportPortableCapturedWifiResult.Exported>(
				portableExporter().export(ExportPortableCapturedWifiRequest(LOGICAL_ID)) {
					emitted += it
				},
			)
			val entry = emitted.single()
			val selection = WifiHistorySelection.Local(
				WifiLocalHistorySelectionKey(entry.identity.value),
			)
			val walCount = database.sourceEventWalDao().countAll()
			val demands = database.sourceBrokerDao().authorizationDemands(WIFI_SOURCE)
			val exporter = portableExporter()
			val deletion = RoomDeleteSelectedWifiHistory(
				database = database,
				importedEvaluator = RoomImportedWifiProductEvaluator(database),
				localPortableReader = exporter,
				maintenance = maintenance,
				ioDispatcher = Dispatchers.IO,
				limits = WifiSelectedDeletionLimits(),
				checkpoint = {},
			)

			assertEquals(
				DeleteSelectedWifiHistoryResult.Deleted(
					com.adsamcik.tracker.stats.api.repository.WifiHistoryOrigin.LOCAL,
					1,
					1,
				),
				deletion.delete(
					DeleteSelectedWifiHistoryRequest(selection, 0L, DELETE_AT_MS + 2_000L),
				),
			)
			assertEquals(0L, database.wifiCapturedFactDao().revisionCount())
			assertEquals(0L, database.wifiCapturedFactDao().cursorCount())
			assertEquals(null, database.sessionSegmentDao().getById(SEGMENT_ID))
			assertEquals(walCount, database.sourceEventWalDao().countAll())
			assertEquals(demands, database.sourceBrokerDao().authorizationDemands(WIFI_SOURCE))
			assertTrue(database.importedWifiDao().selectedDeletionReceipt(
				entry.identity.value,
				com.adsamcik.tracker.shared.base.database.data
					.WifiSelectedDeletionReceiptEntity.ORIGIN_LOCAL,
			) != null)
			val deleted = assertIs<WifiDeletedHistoryResult.Deleted>(
				database.withTransaction { deletion.readDeletedInTransaction(selection) },
			)
			assertEquals(
				com.adsamcik.tracker.stats.api.repository.WifiHistoryProductState.DELETED,
				deleted.entry.state,
			)
			assertEquals(selection, deleted.entry.selection)
			assertEquals(
				DeleteSelectedWifiHistoryResult.AlreadyDeleted(
					com.adsamcik.tracker.stats.api.repository.WifiHistoryOrigin.LOCAL,
				),
				deletion.delete(
					DeleteSelectedWifiHistoryRequest(selection, 0L, DELETE_AT_MS + 2_000L),
				),
			)
		}

	@Test
	fun `selected native deletion accepts production source sequence zero`() = runTest {
		installValidFixture(candidateWriter = true, sourceSequence = 0L)
		installPortableClosingAuthorization()
		installWifiLane(SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL)
		assertIs<WifiSessionFactDrainResult.Complete>(
			WifiSessionFactProjectionLane(database, subject, writer).drainAvailable(),
		)
		database.sourceSessionDao().saveCompleteness(
			wifiCompleteness(lastAdmissionOrdinal = 1L, lastSourceSequence = 0L),
		)
		val emitted = mutableListOf<PortableCapturedWifiEntryV1>()
		assertIs<ExportPortableCapturedWifiResult.Exported>(
			portableExporter().export(ExportPortableCapturedWifiRequest(LOGICAL_ID)) {
				emitted += it
			},
		)
		val exporter = portableExporter()
		val deletion = RoomDeleteSelectedWifiHistory(
			database,
			RoomImportedWifiProductEvaluator(database),
			exporter,
			maintenance,
			Dispatchers.IO,
			WifiSelectedDeletionLimits(),
			{},
		)

		assertIs<DeleteSelectedWifiHistoryResult.Deleted>(
			deletion.delete(
				DeleteSelectedWifiHistoryRequest(
					WifiHistorySelection.Local(
						WifiLocalHistorySelectionKey(emitted.single().identity.value),
					),
					0L,
					DELETE_AT_MS + 2_000L,
				),
			),
		)
	}

	@Test
	fun `selected native deletion blocks a physical row carrying non-Wi-Fi product data`() = runTest {
		installPortableExportFixture()
		val emitted = mutableListOf<PortableCapturedWifiEntryV1>()
		portableExporter().export(ExportPortableCapturedWifiRequest(LOGICAL_ID)) { emitted += it }
		database.openHelper.writableDatabase.execSQL(
			"UPDATE session_segment SET distance_m = 10 WHERE id = ?",
			arrayOf(SEGMENT_ID),
		)
		val exporter = portableExporter()
		val deletion = RoomDeleteSelectedWifiHistory(
			database,
			RoomImportedWifiProductEvaluator(database),
			exporter,
			maintenance,
			Dispatchers.IO,
			WifiSelectedDeletionLimits(),
			{},
		)

		assertEquals(
			DeleteSelectedWifiHistoryResult.Blocked(
				com.adsamcik.tracker.stats.api.repository.WifiHistoryDeletionBlockedReason
					.MIXED_CAPTURE_SCOPE,
			),
			deletion.delete(
				DeleteSelectedWifiHistoryRequest(
					WifiHistorySelection.Local(
						WifiLocalHistorySelectionKey(emitted.single().identity.value),
					),
					0L,
					DELETE_AT_MS + 2_000L,
				),
			),
		)
		assertTrue(database.wifiCapturedFactDao().revisionCount() > 0L)
	}

	@Test
	fun `selected native deletion preserves retained activity presentation without mutation`() = runTest {
		installPortableExportFixture()
		val emitted = mutableListOf<PortableCapturedWifiEntryV1>()
		portableExporter().export(ExportPortableCapturedWifiRequest(LOGICAL_ID)) { emitted += it }
		database.openHelper.writableDatabase.execSQL(
			"UPDATE session_segment SET primary_activity = ?, activity_confidence = ?, " +
				"inference_version = ? WHERE id = ?",
			arrayOf<Any?>(7, 91, "activity-v1", SEGMENT_ID),
		)
		val facts = database.wifiCapturedFactDao().revisionCount()
		val cursors = database.wifiCapturedFactDao().cursorCount()
		val wal = database.sourceEventWalDao().countAll()
		val exporter = portableExporter()
		val deletion = RoomDeleteSelectedWifiHistory(
			database,
			RoomImportedWifiProductEvaluator(database),
			exporter,
			maintenance,
			Dispatchers.IO,
			WifiSelectedDeletionLimits(),
			{},
		)

		assertEquals(
			DeleteSelectedWifiHistoryResult.Blocked(
				com.adsamcik.tracker.stats.api.repository.WifiHistoryDeletionBlockedReason
					.MIXED_CAPTURE_SCOPE,
			),
			deletion.delete(
				DeleteSelectedWifiHistoryRequest(
					WifiHistorySelection.Local(
						WifiLocalHistorySelectionKey(emitted.single().identity.value),
					),
					0L,
					DELETE_AT_MS + 2_000L,
				),
			),
		)

		val retained = requireNotNull(database.sessionSegmentDao().getById(SEGMENT_ID))
		assertEquals(7, retained.primaryActivity)
		assertEquals(91, retained.activityConfidence)
		assertEquals("activity-v1", retained.inferenceVersion)
		assertEquals(facts, database.wifiCapturedFactDao().revisionCount())
		assertEquals(cursors, database.wifiCapturedFactDao().cursorCount())
		assertEquals(0L, database.wifiCapturedFactDao().deletionGenerationCount())
		assertEquals(wal, database.sourceEventWalDao().countAll())
		assertEquals(0L, database.importedWifiDao().selectedDeletionReceiptCount())
		assertEquals(0L, database.importedWifiDao().selectedDeletionProtectedIdentityCount())
	}

	@Test
	fun `selected native deletion blocks explicit source retention loss without mutation`() = runTest {
		installPortableExportFixture()
		val emitted = mutableListOf<PortableCapturedWifiEntryV1>()
		portableExporter().export(ExportPortableCapturedWifiRequest(LOGICAL_ID)) { emitted += it }
		val complete = emitted.single()
		val lossyRuns = complete.runs.map { run ->
			PortableWifiIntegrity.createRun(
				identity = run.identity,
				deletionScopeDigest = run.deletionScopeDigest,
				startTimeMs = run.startTimeMs,
				endTimeMs = run.endTimeMs,
				storedZoneIds = run.storedZoneIds,
				captureCoverage = run.captureCoverage,
				availability = run.availability,
				acquisitionCompleteness = run.acquisitionCompleteness,
				hasUnresolvedProviderRange = run.hasUnresolvedProviderRange,
				retentionLoss = true,
				observations = run.observations,
			)
		}
		val lossy = PortableWifiIntegrity.createEntry(
			identity = complete.identity,
			sessionMode = complete.sessionMode,
			startTimeMs = complete.startTimeMs,
			endTimeMs = complete.endTimeMs,
			runs = lossyRuns,
		)
		val facts = database.wifiCapturedFactDao().revisionCount()
		val cursors = database.wifiCapturedFactDao().cursorCount()
		val wal = database.sourceEventWalDao().countAll()
		val deletion = RoomDeleteSelectedWifiHistory(
			database,
			RoomImportedWifiProductEvaluator(database),
			object : com.adsamcik.tracker.stats.api.repository.ReadLocalPortableCapturedWifi {
				override suspend fun readInTransaction(
					request: ExportPortableCapturedWifiRequest,
				) = com.adsamcik.tracker.stats.api.repository.ReadLocalPortableCapturedWifiResult
					.Ready(lossy)
			},
			maintenance,
			Dispatchers.IO,
			WifiSelectedDeletionLimits(),
			{},
		)

		assertEquals(
			DeleteSelectedWifiHistoryResult.Blocked(
				com.adsamcik.tracker.stats.api.repository.WifiHistoryDeletionBlockedReason
					.RETENTION_BOUNDARY,
			),
			deletion.delete(
				DeleteSelectedWifiHistoryRequest(
					WifiHistorySelection.Local(
						WifiLocalHistorySelectionKey(complete.identity.value),
					),
					0L,
					DELETE_AT_MS + 2_000L,
				),
			),
		)
		assertTrue(database.sessionSegmentDao().getById(SEGMENT_ID) != null)
		assertEquals(facts, database.wifiCapturedFactDao().revisionCount())
		assertEquals(cursors, database.wifiCapturedFactDao().cursorCount())
		assertEquals(0L, database.wifiCapturedFactDao().deletionGenerationCount())
		assertEquals(wal, database.sourceEventWalDao().countAll())
		assertEquals(0L, database.importedWifiDao().selectedDeletionReceiptCount())
	}

	@Test
	fun `selected native deletion blocks active capture without treating quiescence as ownership`() =
		runTest {
			installValidFixture(candidateWriter = true)
			setLiveLifecyclePair("ACTIVE", "ACTIVE")
			val exporter = portableExporter()
			val deletion = RoomDeleteSelectedWifiHistory(
				database,
				RoomImportedWifiProductEvaluator(database),
				exporter,
				maintenance,
				Dispatchers.IO,
				WifiSelectedDeletionLimits(),
				{},
			)

			assertEquals(
				DeleteSelectedWifiHistoryResult.Blocked(
					com.adsamcik.tracker.stats.api.repository.WifiHistoryDeletionBlockedReason.ACTIVE_CAPTURE,
				),
				deletion.delete(
					DeleteSelectedWifiHistoryRequest(
						WifiHistorySelection.Local(
							WifiLocalHistorySelectionKey(
								PortableWifiOpaqueIdentity.derive(
									PortableWifiIdentityKind.LOGICAL_ENTRY,
									LOGICAL_ID,
								).value,
							),
						),
						0L,
						DELETE_AT_MS + 2_000L,
					),
				),
			)
		}

	@Test
	fun `portable export uses per-demand freshness while preserving the full authorization fingerprint`() = runTest {
		installValidFixture(
			plan = wifiPlan().copy(maximumAcceptableResultAgeMs = 10L),
			authorizationDemandCount = 2,
			lastDemandMaximumAgeMs = 50L,
			candidateWriter = true,
		)
		installPortableClosingAuthorization()
		materializePortableFixture()
		val authorization = database.sourceBrokerDao().authorizationRevision(
			WIFI_SOURCE,
			REGISTRATION_GENERATION,
			AUTHORIZATION_REVISION,
		)
		val wal = requireNotNull(database.sourceEventWalDao().getByEventId(EVENT_ID.value))

		assertEquals(2, authorization.size)
		assertEquals(authorization.first().authorizationFingerprint, wal.authorizationFingerprint)
		assertEquals(SourceBrokerPurpose.MASK_SESSION_CAPTURE, wal.authorizationPurposeEligibilityMask)
		assertIs<ExportPortableCapturedWifiResult.Exported>(
			portableExporter().export(ExportPortableCapturedWifiRequest(LOGICAL_ID)) {},
		)
	}

	@Test
	fun `portable freshness saturates a maximum demand age without nanosecond overflow`() = runTest {
		installValidFixture(lastDemandMaximumAgeMs = Long.MAX_VALUE, candidateWriter = true)
		installPortableClosingAuthorization()
		materializePortableFixture()

		assertIs<ExportPortableCapturedWifiResult.Exported>(
			portableExporter().export(ExportPortableCapturedWifiRequest(LOGICAL_ID)) {},
		)
	}

	@Test
	fun `portable export rejects a qualified mask not derived from fresh authorization members`() = runTest {
		installPortableExportFixture()
		rewriteWalPurpose(
			EVENT_ID,
			SourceBrokerPurpose.MASK_SESSION_CAPTURE or SourceBrokerPurpose.MASK_CONTROL_AUTOSTART,
		)

		assertEquals(
			ExportPortableCapturedWifiResult.Unverifiable(
				PortableWifiUnverifiableReason.FACT_AUTHORITY_UNVERIFIABLE,
			),
			exportPortableWithoutSink(),
		)
	}

	@Test
	fun `portable export rejects a WAL fingerprint that is not the full authorization fingerprint`() = runTest {
		installPortableExportFixture()
		rewriteWalAuthorization(
			purposeMask = SourceBrokerPurpose.MASK_SESSION_CAPTURE,
			fingerprint = "f".repeat(64),
		)

		assertEquals(
			ExportPortableCapturedWifiResult.Unverifiable(
				PortableWifiUnverifiableReason.FACT_AUTHORITY_UNVERIFIABLE,
			),
			exportPortableWithoutSink(),
		)
	}

	@Test
	fun `custom Wi-Fi policy exports captured data beside original Ambient QoS zero authority`() = runTest {
		installMixedPortableExportFixture(activeContinuation = false, noncapturePolicyQosCode = 3)
		val ambient = database.sourceBrokerDao().demandsByIds(listOf("$DEMAND_ID-ambient")).single()
		assertEquals(0, ambient.qosCode)
		assertEquals(600_000L, ambient.maximumAgeMs)
		val emitted = mutableListOf<PortableCapturedWifiEntryV1>()

		assertIs<ExportPortableCapturedWifiResult.Exported>(
			portableExporter().export(ExportPortableCapturedWifiRequest(LOGICAL_ID)) { emitted += it },
		)
		assertEquals(1, emitted.single().runs.single().observations.size)
		assertTrue("CONTROL" !in emitted.single().toString())
		assertTrue("AMBIENT_PRODUCT" !in emitted.single().toString())

		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_consent_epoch SET eligible = 0 WHERE source_kind = ? AND purpose = ? AND epoch = ?",
			arrayOf<Any?>(WIFI_SOURCE, "CONTROL", CONTROL_CONSENT_EPOCH),
		)
		assertEquals(
			ExportPortableCapturedWifiResult.Unverifiable(
				PortableWifiUnverifiableReason.FACT_AUTHORITY_UNVERIFIABLE,
			),
			exportPortableWithoutSink(),
		)
	}


	@Test
	fun `portable export authenticates noncapture policy and consent without emitting it`() = runTest {
		installMixedPortableExportFixture(activeContinuation = false)
		val emitted = mutableListOf<PortableCapturedWifiEntryV1>()

		assertIs<ExportPortableCapturedWifiResult.Exported>(
			portableExporter().export(ExportPortableCapturedWifiRequest(LOGICAL_ID)) { emitted += it },
		)
		assertEquals(1, emitted.single().runs.single().observations.size)
		assertTrue("CONTROL" !in emitted.single().toString())
		assertTrue("AMBIENT_PRODUCT" !in emitted.single().toString())

		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_consent_epoch SET eligible = 0 WHERE source_kind = ? AND purpose = ? AND epoch = ?",
			arrayOf<Any?>(WIFI_SOURCE, "CONTROL", CONTROL_CONSENT_EPOCH),
		)
		assertEquals(
			ExportPortableCapturedWifiResult.Unverifiable(
				PortableWifiUnverifiableReason.FACT_AUTHORITY_UNVERIFIABLE,
			),
			exportPortableWithoutSink(),
		)
	}

	@Test
	fun `unobservable provider completeness remains partial and only barrier proof is complete`() = runTest {
		installPortableExportFixture()
		val emitted = mutableListOf<PortableCapturedWifiEntryV1>()

		assertIs<ExportPortableCapturedWifiResult.Exported>(
			portableExporter().export(ExportPortableCapturedWifiRequest(LOGICAL_ID)) { emitted += it },
		)
		assertEquals(
			PortableWifiAcquisitionCompleteness.PARTIAL,
			emitted.single().runs.single().acquisitionCompleteness,
		)

		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_session_completeness SET provider_coverage = ? WHERE logical_tracking_id = ?",
			arrayOf("CALLBACKS_ENTERED_BEFORE_BARRIER", LOGICAL_ID),
		)
		emitted.clear()
		assertIs<ExportPortableCapturedWifiResult.Exported>(
			portableExporter().export(ExportPortableCapturedWifiRequest(LOGICAL_ID)) { emitted += it },
		)
		assertEquals(
			PortableWifiAcquisitionCompleteness.COMPLETE,
			emitted.single().runs.single().acquisitionCompleteness,
		)
	}

	@Test
	fun `fact-backed completeness rejects an active capture demand`() = runTest {
		installPortableExportFixture()
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_demand SET status = 'ACTIVE', retire_boot_id = NULL, " +
				"retire_elapsed_realtime_nanos = NULL, retired_at_ms = NULL WHERE demand_id = ?",
			arrayOf("$DEMAND_ID-0"),
		)

		assertEquals(
			ExportPortableCapturedWifiResult.Unverifiable(
				PortableWifiUnverifiableReason.WRITER_AUTHORITY_UNVERIFIABLE,
			),
			exportPortableWithoutSink(),
		)
	}

	@Test
	fun `fact-backed completeness rejects a missing closing authorization`() = runTest {
		installPortableExportFixture()
		database.openHelper.writableDatabase.execSQL(
			"DELETE FROM source_authorization WHERE source_kind = ? AND registration_generation = ? " +
				"AND authorization_revision = ?",
			arrayOf<Any?>(WIFI_SOURCE, REGISTRATION_GENERATION, AUTHORIZATION_REVISION + 1L),
		)

		assertEquals(
			ExportPortableCapturedWifiResult.Unverifiable(
				PortableWifiUnverifiableReason.WRITER_AUTHORITY_UNVERIFIABLE,
			),
			exportPortableWithoutSink(),
		)
	}

	@Test
	fun `retired capture can export while the same registration continues for authenticated control`() = runTest {
		installMixedPortableExportFixture(activeContinuation = true)
		val emitted = mutableListOf<PortableCapturedWifiEntryV1>()

		assertIs<ExportPortableCapturedWifiResult.Exported>(
			portableExporter().export(ExportPortableCapturedWifiRequest(LOGICAL_ID)) { emitted += it },
		)
		assertEquals(1, emitted.single().runs.single().observations.size)
		assertTrue("CONTROL" !in emitted.single().toString())
		assertTrue("AMBIENT_PRODUCT" !in emitted.single().toString())
	}

	@Test
	fun `portable export accepts production first WAL sequence zero with callback high-water one`() = runTest {
		installValidFixture(candidateWriter = true, sourceSequence = 0L)
		installPortableClosingAuthorization()
		installWifiLane(SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL)
		assertIs<WifiSessionFactDrainResult.Complete>(
			WifiSessionFactProjectionLane(database, subject, writer).drainAvailable(),
		)
		database.sourceSessionDao().saveCompleteness(wifiCompleteness(lastSourceSequence = 1L))
		val emitted = mutableListOf<PortableCapturedWifiEntryV1>()

		assertIs<ExportPortableCapturedWifiResult.Exported>(
			portableExporter().export(ExportPortableCapturedWifiRequest(LOGICAL_ID)) { emitted += it },
		)
		assertTrue(
			database.wifiCapturedFactDao().portableRevisionClosure(LOGICAL_ID, listOf(RUN_ID), 8)
				.all { revision -> revision.sourceSequence == 0L },
		)
		assertEquals(1, emitted.single().runs.single().observations.size)
	}

	@Test
	fun `portable authority reads stay bounded as selected WAL rows grow`() = runTest {
		installPortableExportFixture()
		val oneWalQueryCount = portableAuthorityQueryCount()
		insertSecondWal()
		assertIs<WifiSessionFactDrainResult.Complete>(
			WifiSessionFactProjectionLane(database, subject, writer).drainAvailable(),
		)
		database.sourceSessionDao().saveCompleteness(
			wifiCompleteness(lastAdmissionOrdinal = 2L, lastSourceSequence = 999L),
		)
		val twoWalQueryCount = portableAuthorityQueryCount()

		assertEquals(oneWalQueryCount, twoWalQueryCount)
		assertTrue(twoWalQueryCount <= WIFI_PORTABLE_AUTHORITY_QUERY_LIMIT)
		assertIs<ExportPortableCapturedWifiResult.Exported>(
			portableExporter().export(ExportPortableCapturedWifiRequest(LOGICAL_ID)) {},
		)
	}

	@Test
	fun `portable export accepts an exact replay tail without equating callback high-water to WAL sequence`() =
		runTest {
			installPortableExportFixture()
			val durable = requireNotNull(database.sourceEventWalDao().getByEventId(EVENT_ID.value))
			assertEquals(-1L, database.sourceEventWalDao().insertIgnoringDuplicate(durable))
			database.sourceSessionDao().saveCompleteness(
				wifiCompleteness(lastAdmissionOrdinal = durable.admissionOrdinal, lastSourceSequence = 999L),
			)

			assertIs<ExportPortableCapturedWifiResult.Exported>(
				portableExporter().export(ExportPortableCapturedWifiRequest(LOGICAL_ID)) {},
			)
		}

	@Test
	fun `portable export ignores unrelated corrupt retained Wi-Fi history`() = runTest {
		installPortableExportFixture()
		installRetainedNoncapturePolicyAndConsent()
		val unrelated = insertNoncaptureWal(2, SourceBrokerPurpose.CONTROL_AUTOSTART)
		assertExactWal(unrelated)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_event_wal SET payload = X'00' WHERE event_id = ?",
			arrayOf(unrelated.eventId),
		)

		val emitted = mutableListOf<PortableCapturedWifiEntryV1>()
		assertIs<ExportPortableCapturedWifiResult.Exported>(
			portableExporter().export(ExportPortableCapturedWifiRequest(LOGICAL_ID)) { emitted += it },
		)
		assertEquals(1, emitted.single().runs.single().observations.size)
	}

	@Test
	fun `portable export excludes an unrelated corrupt dependent of a selected aggregate`() = runTest {
		installPortableExportFixture()
		insertSecondWal()
		assertIs<WifiSessionFactDrainResult.Complete>(
			WifiSessionFactProjectionLane(database, subject, writer).drainAvailable(),
		)
		database.sourceSessionDao().saveCompleteness(wifiCompleteness(2L, 2L))
		val dao = database.wifiCapturedFactDao()
		val selected = dao.portableRevisionClosure(LOGICAL_ID, listOf(RUN_ID), 8)
		val dependent = selected.single { revision ->
			revision.factKind == WifiCapturedFactRevisionEntity.FACT_KIND_COVERAGE_ONLY
		}
		val unrelatedLogicalId = WifiCapturedFactRevisionIntegrity.logicalFactId(
			dependent.sourceDeliveryIdentity,
			"unrelated-logical-entry",
			"unrelated-service-run",
			REPLACEMENT_SEGMENT_ID,
			dependent.manifestRevision,
			dependent.collectedDataEpoch,
			dependent.scopeDeletionGeneration,
		)
		val unsigned = dependent.copy(
			logicalFactId = unrelatedLogicalId,
			mutationId = WifiCapturedFactRevisionIntegrity.mutationId(unrelatedLogicalId, 1L),
			logicalTrackingId = "unrelated-logical-entry",
			serviceRunId = "unrelated-service-run",
			sessionSegmentId = REPLACEMENT_SEGMENT_ID,
			effectChecksum = "0".repeat(64),
		)
		val unrelated = unsigned.copy(
			effectChecksum = WifiCapturedFactRevisionIntegrity.effectChecksum(unsigned),
		)
		assertTrue(dao.insertRevision(unrelated) > 0L)
		val dependentCursor = dao.portableCursorClosure(LOGICAL_ID, listOf(RUN_ID), 8)
			.single { cursor -> cursor.logicalFactId == dependent.logicalFactId }
		assertTrue(dao.insertCursor(dependentCursor.copy(
			logicalFactId = unrelated.logicalFactId,
			logicalTrackingId = unrelated.logicalTrackingId,
			serviceRunId = unrelated.serviceRunId,
			sessionSegmentId = unrelated.sessionSegmentId,
			latestMutationId = unrelated.mutationId,
			latestEffectChecksum = unrelated.effectChecksum,
		)) > 0L)

		val emitted = mutableListOf<PortableCapturedWifiEntryV1>()
		assertIs<ExportPortableCapturedWifiResult.Exported>(
			portableExporter().export(ExportPortableCapturedWifiRequest(LOGICAL_ID)) { emitted += it },
		)
		assertEquals(2, emitted.single().runs.single().observations.size)
	}

	@Test
	fun `portable export rejects orphan reverse segment membership`() = runTest {
		installPortableExportFixture()
		assertEquals(
			REPLACEMENT_SEGMENT_ID,
			database.sessionSegmentDao().insert(
				segment("orphan-wifi-run").copy(id = REPLACEMENT_SEGMENT_ID),
			),
		)

		assertEquals(
			ExportPortableCapturedWifiResult.Unverifiable(
				PortableWifiUnverifiableReason.PHYSICAL_MEMBERSHIP_UNVERIFIABLE,
			),
			exportPortableWithoutSink(),
		)
	}

	@Test
	fun `portable export rejects mismatched forward segment membership`() = runTest {
		installPortableExportFixture()
		val segment = requireNotNull(database.sessionSegmentDao().getById(SEGMENT_ID))
		database.sessionSegmentDao().update(segment.copy(serviceRunId = "mismatched-wifi-run"))

		assertEquals(
			ExportPortableCapturedWifiResult.Unverifiable(
				PortableWifiUnverifiableReason.PHYSICAL_MEMBERSHIP_UNVERIFIABLE,
			),
			exportPortableWithoutSink(),
		)
	}

	@Test
	fun `portable export reports terminal Wi-Fi projection failure before lane lag`() = runTest {
		installValidFixture(candidateWriter = true)
		installPortableClosingAuthorization()
		installWifiLane(SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL)
		database.sourceSessionDao().saveCompleteness(wifiCompleteness())
		database.sourceProjectionStateDao().saveFailure(
			SourceProjectionFailureEntity(
				projectionId = WifiSessionFactProjectionLane.WRITER_ID,
				projectionVersion = WifiSessionFactProjectionLane.WRITER_VERSION,
				admissionOrdinal = 1L,
				attemptCount = 1,
				failureCode = "WIFI_CLASSIFICATION_STALE",
				terminal = true,
				lastAttemptAtMs = SESSION_END_WALL_MS,
			),
		)

		assertEquals(
			ExportPortableCapturedWifiResult.Unverifiable(
				PortableWifiUnverifiableReason.WRITER_AUTHORITY_UNVERIFIABLE,
			),
			exportPortableWithoutSink(),
		)
	}

	@Test
	fun `portable export distinguishes unprojected Wi-Fi lag from terminal failure`() = runTest {
		installValidFixture(candidateWriter = true)
		installPortableClosingAuthorization()
		installWifiLane(SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL)
		database.sourceSessionDao().saveCompleteness(wifiCompleteness())

		assertEquals(ExportPortableCapturedWifiResult.Materializing, exportPortableWithoutSink())
	}

	@Test
	fun `portable export rejects selected captured fact whose WAL is missing`() = runTest {
		installPortableExportFixture()
		database.openHelper.writableDatabase.execSQL(
			"DELETE FROM source_event_wal WHERE event_id = ?",
			arrayOf(EVENT_ID.value),
		)

		assertEquals(
			ExportPortableCapturedWifiResult.Unverifiable(
				PortableWifiUnverifiableReason.FACT_AUTHORITY_UNVERIFIABLE,
			),
			exportPortableWithoutSink(),
		)
	}

	@Test
	fun `portable export rejects a corrupt selected correction revision`() = runTest {
		installPortableExportFixture()
		database.openHelper.writableDatabase.execSQL(
			"UPDATE wifi_captured_fact_revision SET effect_checksum = ? WHERE logical_tracking_id = ?",
			arrayOf("f".repeat(64), LOGICAL_ID),
		)

		assertEquals(
			ExportPortableCapturedWifiResult.Unverifiable(
				PortableWifiUnverifiableReason.FACT_AUTHORITY_UNVERIFIABLE,
			),
			exportPortableWithoutSink(),
		)
	}

	@Test
	fun `portable export fails closed at the bounded Wi-Fi completeness limit`() = runTest {
		installPortableExportFixture()
		repeat(WifiCapturedPortableFormatV1.MAX_COMPLETENESS_ROWS) { index ->
			database.sourceSessionDao().saveCompleteness(
				wifiCompleteness().copy(
					serviceRunId = "overflow-run-$index",
					sourceInstanceId = "overflow-instance-$index",
					registrationGeneration = 10_000L + index,
				),
			)
		}

		assertEquals(
			ExportPortableCapturedWifiResult.Unverifiable(
				PortableWifiUnverifiableReason.DEPENDENCY_OVERFLOW,
			),
			exportPortableWithoutSink(),
		)
	}

	@Test
	fun `portable export rejects stale Wi-Fi destination ownership`() = runTest {
		installPortableExportFixture()
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_destination_owner SET owner_generation = owner_generation + 1 " +
				"WHERE source_kind = ? AND destination = ?",
			arrayOf<Any?>(WIFI_SOURCE, SourceDestinationOwnerEntity.DESTINATION_SESSION_WIFI),
		)

		assertEquals(
			ExportPortableCapturedWifiResult.Unverifiable(
				PortableWifiUnverifiableReason.WRITER_AUTHORITY_UNVERIFIABLE,
			),
			exportPortableWithoutSink(),
		)
	}

	@Test
	fun `portable export accepts settled clock-unverifiable WAL without fabricating zero`() = runTest {
		installValidFixture(
			candidateWriter = true,
			accessPoints = emptyList(),
			deliveryIdentityOverride = "c".repeat(64),
		)
		installPortableClosingAuthorization()
		installWifiLane(SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL)
		assertIs<WifiSessionFactDrainResult.Complete>(
			WifiSessionFactProjectionLane(database, subject, writer).drainAvailable(),
		)
		database.sourceSessionDao().saveCompleteness(wifiCompleteness())
		val emitted = mutableListOf<PortableCapturedWifiEntryV1>()

		assertIs<ExportPortableCapturedWifiResult.Exported>(
			portableExporter().export(ExportPortableCapturedWifiRequest(LOGICAL_ID)) { emitted += it },
		)
		assertTrue(emitted.single().runs.single().observations.isEmpty())
		assertEquals(0L, database.wifiCapturedFactDao().revisionCount())
	}

	@Test
	fun `portable export rejects a missing settled WAL instead of inventing zero callbacks`() = runTest {
		installValidFixture(
			candidateWriter = true,
			accessPoints = emptyList(),
			deliveryIdentityOverride = "c".repeat(64),
		)
		installPortableClosingAuthorization()
		installWifiLane(SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL)
		assertIs<WifiSessionFactDrainResult.Complete>(
			WifiSessionFactProjectionLane(database, subject, writer).drainAvailable(),
		)
		database.sourceSessionDao().saveCompleteness(wifiCompleteness())
		database.openHelper.writableDatabase.execSQL(
			"DELETE FROM source_event_wal WHERE event_id = ?",
			arrayOf(EVENT_ID.value),
		)

		assertEquals(
			ExportPortableCapturedWifiResult.Unverifiable(
				PortableWifiUnverifiableReason.WRITER_AUTHORITY_UNVERIFIABLE,
			),
			exportPortableWithoutSink(),
		)
	}

	@Test
	fun `portable export fails closed when captured authority is from an older epoch`() = runTest {
		installPortableExportFixture()
		assertEquals(1, database.sourceEvidenceStateDao().updateLifecycle(
			epoch = 1L,
			retainedFromMs = null,
			updatedAtMs = SESSION_END_WALL_MS + 1L,
		))

		assertEquals(
			ExportPortableCapturedWifiResult.Unverifiable(
				PortableWifiUnverifiableReason.FACT_AUTHORITY_UNVERIFIABLE,
			),
			exportPortableWithoutSink(),
		)
	}

	@Test
	fun `portable export reports exact deleted Wi-Fi run while preserving WAL`() = runTest {
		installPortableExportFixture()
		installPortableDeletion()

		assertEquals(ExportPortableCapturedWifiResult.Deleted, exportPortableWithoutSink())
		assertEquals(1L, database.sourceEventWalDao().countAll())
	}

	@Test
	fun `portable export rejects a mismatched Wi-Fi deletion generation and fence`() = runTest {
		installPortableExportFixture()
		installPortableDeletion()
		database.openHelper.writableDatabase.execSQL(
			"UPDATE wifi_capture_deletion_generation SET generation = 2 " +
				"WHERE logical_tracking_id = ? AND service_run_id = ?",
			arrayOf(LOGICAL_ID, RUN_ID),
		)

		assertEquals(
			ExportPortableCapturedWifiResult.Unverifiable(
				PortableWifiUnverifiableReason.FACT_AUTHORITY_UNVERIFIABLE,
			),
			exportPortableWithoutSink(),
		)
	}

	@Test
	fun `portable export marks captured replacement with zero callbacks without inventing an observation`() = runTest {
		installPortableExportFixture()
		finalizeZeroCallbackReplacement(withGap = false)
		val emitted = mutableListOf<PortableCapturedWifiEntryV1>()

		assertIs<ExportPortableCapturedWifiResult.Exported>(
			portableExporter().export(ExportPortableCapturedWifiRequest(LOGICAL_ID)) { emitted += it },
		)

		val replacement = emitted.single().runs.single { it.identity != emitted.single().runs.first().identity }
		assertEquals(PortableWifiRunAvailability.NO_RETAINED_OBSERVATION, replacement.availability)
		assertTrue(replacement.observations.isEmpty())
	}

	@Test
	fun `zero-callback replacement rejects cached-only desired plan`() = runTest {
		installPortableExportFixture()
		finalizeZeroCallbackReplacement(withGap = false)
		rewriteReplacementPlan(
			wifiPlan().copy(revision = REPLACEMENT_PLAN_REVISION, mode = WifiMode.CACHED_ONLY),
		)

		assertEquals(
			ExportPortableCapturedWifiResult.Unverifiable(
				PortableWifiUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE,
			),
			exportPortableWithoutSink(),
		)
	}

	@Test
	fun `gap-only replacement rejects a desired plan older than its capture demand allows`() = runTest {
		installPortableExportFixture()
		finalizeZeroCallbackReplacement(withGap = true)
		rewriteReplacementPlan(
			wifiPlan().copy(
				revision = REPLACEMENT_PLAN_REVISION,
				maximumAcceptableResultAgeMs = 5 * 60_000L + 1L,
			),
		)

		assertEquals(
			ExportPortableCapturedWifiResult.Unverifiable(
				PortableWifiUnverifiableReason.WRITER_AUTHORITY_UNVERIFIABLE,
			),
			exportPortableWithoutSink(),
		)
	}

	@Test
	fun `zero-callback replacement rejects the wrong registration fingerprint`() = runTest {
		installPortableExportFixture()
		finalizeZeroCallbackReplacement(withGap = false)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE provider_registration_generation SET physical_configuration_fingerprint = ? " +
				"WHERE source_kind = ? AND registration_generation = ?",
			arrayOf<Any?>("f".repeat(64), WIFI_SOURCE, REPLACEMENT_REGISTRATION_GENERATION),
		)

		assertEquals(
			ExportPortableCapturedWifiResult.Unverifiable(
				PortableWifiUnverifiableReason.WRITER_AUTHORITY_UNVERIFIABLE,
			),
			exportPortableWithoutSink(),
		)
	}

	@Test
	fun `portable export preserves gap-only replacement as partial missing evidence`() = runTest {
		installPortableExportFixture()
		finalizeZeroCallbackReplacement(withGap = true)
		val emitted = mutableListOf<PortableCapturedWifiEntryV1>()

		assertIs<ExportPortableCapturedWifiResult.Exported>(
			portableExporter().export(ExportPortableCapturedWifiRequest(LOGICAL_ID)) { emitted += it },
		)

		val replacement = emitted.single().runs.last()
		assertEquals(PortableWifiRunAvailability.NO_RETAINED_OBSERVATION, replacement.availability)
		assertEquals(true, replacement.hasUnresolvedProviderRange)
		assertTrue(replacement.observations.isEmpty())
	}

	@Test
	fun `portable export rejects zero-callback replacement without capture authorization`() = runTest {
		installPortableExportFixture()
		finalizeZeroCallbackReplacement(withGap = false)
		database.openHelper.writableDatabase.execSQL(
			"DELETE FROM source_authorization WHERE source_kind = ? AND registration_generation = ?",
			arrayOf<Any?>(WIFI_SOURCE, REPLACEMENT_REGISTRATION_GENERATION),
		)

		assertEquals(
			ExportPortableCapturedWifiResult.Unverifiable(
				PortableWifiUnverifiableReason.WRITER_AUTHORITY_UNVERIFIABLE,
			),
			exportPortableWithoutSink(),
		)
	}

	@Test
	fun `portable export rejects zero-callback replacement without its closing authorization`() = runTest {
		installPortableExportFixture()
		finalizeZeroCallbackReplacement(withGap = false)
		database.openHelper.writableDatabase.execSQL(
			"DELETE FROM source_authorization WHERE source_kind = ? AND registration_generation = ? " +
				"AND authorization_revision = ?",
			arrayOf<Any?>(
				WIFI_SOURCE,
				REPLACEMENT_REGISTRATION_GENERATION,
				AUTHORIZATION_REVISION + 2L,
			),
		)

		assertEquals(
			ExportPortableCapturedWifiResult.Unverifiable(
				PortableWifiUnverifiableReason.WRITER_AUTHORITY_UNVERIFIABLE,
			),
			exportPortableWithoutSink(),
		)
	}

	@Test
	fun `portable export reports partial Wi-Fi coverage across manifest revisions`() = runTest {
		installPortableExportFixture()
		installNoWifiReconciliationManifest()
		val emitted = mutableListOf<PortableCapturedWifiEntryV1>()

		assertIs<ExportPortableCapturedWifiResult.Exported>(
			portableExporter().export(ExportPortableCapturedWifiRequest(LOGICAL_ID)) { emitted += it },
		)

		assertEquals(PortableWifiCaptureCoverage.PARTIAL_RUN, emitted.single().runs.single().captureCoverage)
	}

	@Test
	fun `portable export fails closed when retained aggregate owner crosses retention floor`() = runTest {
		installValidFixture(candidateWriter = true)
		installPortableClosingAuthorization()
		insertSecondWal()
		installWifiLane(SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL)
		assertIs<WifiSessionFactDrainResult.Complete>(
			WifiSessionFactProjectionLane(database, subject, writer).drainAvailable(),
		)
		database.sourceSessionDao().saveCompleteness(wifiCompleteness(2L, 2L))
		assertEquals(1, database.sourceEvidenceStateDao().updateLifecycle(
			epoch = 0L,
			retainedFromMs = OBSERVED_WALL_MS,
			updatedAtMs = SESSION_END_WALL_MS,
		))

		assertEquals(
			ExportPortableCapturedWifiResult.Unavailable(PortableWifiUnavailableReason.RETENTION_LIMIT),
			exportPortableWithoutSink(),
		)
	}

	@Test
	fun `portable Wi-Fi sink failure and cancellation propagate without mutation`() = runTest {
		installPortableExportFixture()
		val revisionsBefore = database.wifiCapturedFactDao().revisionCount()

		assertFailsWith<IllegalStateException> {
			portableExporter().export(ExportPortableCapturedWifiRequest(LOGICAL_ID)) {
				throw IllegalStateException("portable Wi-Fi sink failure")
			}
		}
		assertFailsWith<CancellationException> {
			portableExporter().export(ExportPortableCapturedWifiRequest(LOGICAL_ID)) {
				throw CancellationException("portable Wi-Fi sink cancellation")
			}
		}
		assertEquals(revisionsBefore, database.wifiCapturedFactDao().revisionCount())
	}

	@Test
	fun `portable Wi-Fi closed storage is typed and never reaches sink`() = runTest {
		installPortableExportFixture()
		database.close()
		var sinkReached = false

		val result = portableExporter().export(ExportPortableCapturedWifiRequest(LOGICAL_ID)) {
			sinkReached = true
		}

		assertIs<ExportPortableCapturedWifiResult.RetryableFailure>(result)
		assertEquals(false, sinkReached)
	}

	private fun portableExporter() = RoomExportPortableCapturedWifi(
		database = database,
		maintenance = maintenance,
		planCodec = planCodec,
		laneExecutionAuthority = SourceProductLaneExecutionAuthority { lane ->
			lane.sourceKind == WIFI_SOURCE &&
				lane.bindingGeneration == SourceDestinationOwnerEntity.WIFI_FACT_BINDING_GENERATION &&
				lane.projectionId == WifiSessionFactProjectionLane.WRITER_ID &&
				lane.projectionVersion == WifiSessionFactProjectionLane.WRITER_VERSION
		},
		ioDispatcher = Dispatchers.Unconfined,
	)

	private suspend fun installPortableExportFixture() {
		installValidFixture(candidateWriter = true)
		installPortableClosingAuthorization()
		materializePortableFixture()
	}

	private suspend fun materializePortableFixture(
		completeness: SourceSessionCompletenessEntity = wifiCompleteness(),
	) {
		installWifiLane(SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL)
		assertIs<WifiSessionFactDrainResult.Complete>(
			WifiSessionFactProjectionLane(database, subject, writer).drainAvailable(),
		)
		database.sourceSessionDao().saveCompleteness(completeness)
	}

	private suspend fun installPortableClosingAuthorization(
		registrationGeneration: Long = REGISTRATION_GENERATION,
		authorizationRevision: Long = AUTHORIZATION_REVISION + 1L,
		effectiveElapsedRealtimeNanos: Long = SESSION_END_NANOS,
		effectiveWallTimeMs: Long = SESSION_END_WALL_MS,
		demands: List<SourceDemandEntity> = emptyList(),
	) {
		database.sourceBrokerDao().insertAuthorizations(
			SourceBrokerAuthorization.rows(
				sourceKind = WIFI_SOURCE,
				registrationGeneration = registrationGeneration,
				authorizationRevision = authorizationRevision,
				demands = demands,
				effectiveBootId = BOOT_ID,
				effectiveElapsedRealtimeNanos = effectiveElapsedRealtimeNanos,
				effectiveWallTimeMs = effectiveWallTimeMs,
			),
		)
	}

	private suspend fun installPortableDeletion() {
		database.openHelper.writableDatabase.execSQL("DELETE FROM wifi_captured_fact_cursor")
		database.openHelper.writableDatabase.execSQL("DELETE FROM wifi_captured_fact_revision")
		database.wifiCapturedFactDao().insertDeletionGeneration(
			WifiCaptureDeletionGenerationEntity(
				logicalTrackingId = LOGICAL_ID,
				serviceRunId = RUN_ID,
				collectedDataEpoch = 0L,
				generation = 1L,
				updatedAtMs = DELETE_AT_MS,
			),
		)
		database.sourceDeletionFenceDao().insertIfAbsent(
			SourceDeletionFenceEntity.createLogicalServiceRun(
				WIFI_SOURCE,
				SourceBrokerPurpose.SESSION_CAPTURE,
				LOGICAL_ID,
				RUN_ID,
				1L,
				0L,
				DELETE_AT_MS,
			),
		)
	}

	private suspend fun exportPortableWithoutSink(): ExportPortableCapturedWifiResult {
		var sinkReached = false
		val result = portableExporter().export(ExportPortableCapturedWifiRequest(LOGICAL_ID)) {
			sinkReached = true
		}
		assertEquals(false, sinkReached)
		return result
	}

	private suspend fun portableAuthorityQueryCount(): Int = database.withTransaction {
		val completeness = database.trackingHistoryReadDao().completenessForSource(
			WIFI_SOURCE,
			LOGICAL_ID,
			listOf(RUN_ID),
			WifiCapturedPortableFormatV1.MAX_COMPLETENESS_ROWS + 1,
		)
		maintenance.auditPortableInTransaction(
			evidence = requireNotNull(database.sourceEvidenceStateDao().get()),
			logicalTrackingId = LOGICAL_ID,
			serviceRunIds = listOf(RUN_ID),
			completeness = completeness,
			limits = WifiCapturedMaintenanceLimits(),
		).wal.portableAuthorityQueryCount
	}

	private fun wifiCompleteness(
		lastAdmissionOrdinal: Long? = 1L,
		lastSourceSequence: Long? = 1L,
	) = SourceSessionCompletenessEntity(
		logicalTrackingId = LOGICAL_ID,
		serviceRunId = RUN_ID,
		sourceKind = WIFI_SOURCE,
		sourceInstanceId = SOURCE_INSTANCE,
		registrationGeneration = REGISTRATION_GENERATION,
		lastAdmissionOrdinal = lastAdmissionOrdinal,
		lastSourceSequence = lastSourceSequence,
		appDrainComplete = true,
		providerCoverage = "PROVIDER_COMPLETENESS_UNOBSERVABLE",
		stopStatus = "COMPLETE",
		unresolvedSequenceStart = null,
		unresolvedSequenceEnd = null,
		updatedAtMs = SESSION_END_WALL_MS,
	)

	private suspend fun finalizeZeroCallbackReplacement(withGap: Boolean) {
		installLiveReplacement()
		val sessionDao = database.sourceSessionDao()
		val session = requireNotNull(sessionDao.session(LOGICAL_ID))
		val replacement = requireNotNull(sessionDao.serviceRun(REPLACEMENT_RUN_ID))
		val completedAtMs = SESSION_END_WALL_MS + 100L
		val completedElapsedNanos = SESSION_END_NANOS + 100L
		val stopBoundaryAtMs = SESSION_END_WALL_MS + 50L
		val stopBoundaryElapsedNanos = SESSION_END_NANOS + 50L
		val replacementDemand = demand().copy(
			demandId = "$DEMAND_ID-replacement",
			consumerId = "session:$LOGICAL_ID",
			serviceRunId = REPLACEMENT_RUN_ID,
			manifestRevision = REPLACEMENT_MANIFEST_REVISION,
			requestedAtMs = SESSION_END_WALL_MS + 1L,
			requestedElapsedRealtimeNanos = SESSION_END_NANOS + 1L,
			retireElapsedRealtimeNanos = stopBoundaryElapsedNanos,
			retiredAtMs = stopBoundaryAtMs,
		)
		database.sourceBrokerDao().insertDemands(listOf(replacementDemand))
		database.sourceBrokerDao().insertAuthorizations(
			SourceBrokerAuthorization.rows(
				sourceKind = WIFI_SOURCE,
				registrationGeneration = REPLACEMENT_REGISTRATION_GENERATION,
				authorizationRevision = AUTHORIZATION_REVISION + 1L,
				demands = listOf(replacementDemand),
				effectiveBootId = BOOT_ID,
				effectiveElapsedRealtimeNanos = SESSION_END_NANOS + 2L,
				effectiveWallTimeMs = SESSION_END_WALL_MS + 2L,
			),
		)
		database.sourceBrokerDao().insertAuthorizations(
			SourceBrokerAuthorization.rows(
				sourceKind = WIFI_SOURCE,
				registrationGeneration = REPLACEMENT_REGISTRATION_GENERATION,
				authorizationRevision = AUTHORIZATION_REVISION + 2L,
				demands = emptyList(),
				effectiveBootId = BOOT_ID,
				effectiveElapsedRealtimeNanos = stopBoundaryElapsedNanos,
				effectiveWallTimeMs = stopBoundaryAtMs,
			),
		)
		assertEquals(1, sessionDao.updateServiceRun(replacement.copy(
			state = "FINALIZED",
			completedAtMs = completedAtMs,
			completionReason = "USER_STOP",
			runtimeAcknowledgement = "STOP_ACCEPTED",
			runRevision = 2L,
			presentationAcknowledgement = SourceServiceRunEntity.PRESENTATION_QUIESCED,
			presentationAcknowledgedAtMs = completedAtMs,
		)))
		assertEquals(1, sessionDao.updateSession(session.copy(
			state = "FINALIZED",
			lifecycleRevision = session.lifecycleRevision + 1L,
			cutoffAtMs = stopBoundaryAtMs,
			cutoffElapsedNanos = stopBoundaryElapsedNanos,
			completedAtMs = completedAtMs,
			finalAdmissionOrdinal = 1L,
			currentServiceRunId = null,
		)))
		val segment = requireNotNull(database.sessionSegmentDao().getById(REPLACEMENT_SEGMENT_ID))
		database.sessionSegmentDao().update(segment.copy(endTimeMs = completedAtMs))
		database.openHelper.writableDatabase.execSQL(
			"UPDATE provider_registration_generation SET status = 'RETIRED', retired_at_ms = ?, " +
				"retired_elapsed_realtime_nanos = ?, failure_code = 'ORDERLY_STOP', " +
				"capture_callback_barrier_authorization_revision = ? " +
				"WHERE source_kind = ? AND registration_generation = ?",
			arrayOf<Any?>(
				completedAtMs,
				completedElapsedNanos,
				AUTHORIZATION_REVISION + 1L,
				WIFI_SOURCE,
				REPLACEMENT_REGISTRATION_GENERATION,
			),
		)
		sessionDao.saveCompleteness(
			SourceSessionCompletenessEntity(
				logicalTrackingId = LOGICAL_ID,
				serviceRunId = REPLACEMENT_RUN_ID,
				sourceKind = WIFI_SOURCE,
				sourceInstanceId = SOURCE_INSTANCE,
				registrationGeneration = REPLACEMENT_REGISTRATION_GENERATION,
				lastAdmissionOrdinal = null,
				lastSourceSequence = null,
				appDrainComplete = !withGap,
				providerCoverage = "PROVIDER_COMPLETENESS_UNOBSERVABLE",
				stopStatus = if (withGap) "TIMED_OUT" else "COMPLETE",
				unresolvedSequenceStart = 1L.takeIf { withGap },
				unresolvedSequenceEnd = 2L.takeIf { withGap },
				updatedAtMs = completedAtMs,
			),
		)
	}

	private suspend fun rewriteReplacementPlan(plan: WifiPlan) {
		require(plan.revision == REPLACEMENT_PLAN_REVISION)
		val encoded = planCodec.encode(plan)
		database.sourcePlanStateDao().insertRevision(
			AcquisitionPlanRevisionEntity(
				revision = REPLACEMENT_PLAN_REVISION,
				planId = "wifi-replacement-plan",
				createdAtMs = SESSION_END_WALL_MS + 1L,
				status = "EFFECTIVE",
				sourcePolicyRevision = POLICY_REVISION,
			),
		)
		database.sourcePlanStateDao().insertDesiredPlans(listOf(
			SourceDesiredPlanEntity(
				revision = REPLACEMENT_PLAN_REVISION,
				sourceKind = WIFI_SOURCE,
				payloadVersion = 1,
				payload = encoded.bytes,
				payloadChecksum = encoded.checksum,
			),
		))
		val sessionDao = database.sourceSessionDao()
		val manifest = requireNotNull(sessionDao.manifest(LOGICAL_ID, REPLACEMENT_MANIFEST_REVISION))
		val manifestSources = sessionDao.manifestSources(LOGICAL_ID, REPLACEMENT_MANIFEST_REVISION)
		val unsigned = manifest.copy(
			acquisitionPlanRevision = REPLACEMENT_PLAN_REVISION,
			manifestChecksum = "",
		)
		val checksum = SessionManifestIntegrity.compute(unsigned, manifestSources)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE session_manifest_version SET acquisition_plan_revision = ?, manifest_checksum = ? " +
				"WHERE logical_tracking_id = ? AND manifest_revision = ?",
			arrayOf<Any?>(
				REPLACEMENT_PLAN_REVISION,
				checksum,
				LOGICAL_ID,
				REPLACEMENT_MANIFEST_REVISION,
			),
		)
		val action = requireNotNull(sessionDao.lifecycleAction(REPLACEMENT_ACTION_ID))
		assertEquals(1, sessionDao.updateLifecycleAction(
			action.copy(desiredPlanRevision = REPLACEMENT_PLAN_REVISION),
		))
		val run = requireNotNull(sessionDao.serviceRun(REPLACEMENT_RUN_ID))
		assertEquals(1, sessionDao.updateServiceRun(
			run.copy(desiredPlanRevision = REPLACEMENT_PLAN_REVISION),
		))
		val session = requireNotNull(sessionDao.session(LOGICAL_ID))
		assertEquals(1, sessionDao.updateSession(
			session.copy(desiredPlanRevision = REPLACEMENT_PLAN_REVISION),
		))
		database.openHelper.writableDatabase.execSQL(
			"UPDATE provider_registration_generation SET physical_configuration_fingerprint = ? " +
				"WHERE source_kind = ? AND registration_generation = ?",
			arrayOf<Any?>(
				plan.physicalConfigurationFingerprint(),
				WIFI_SOURCE,
				REPLACEMENT_REGISTRATION_GENERATION,
			),
		)
	}

	private suspend fun installNoWifiReconciliationManifest() {
		val sessionDao = database.sourceSessionDao()
		val original = requireNotNull(sessionDao.manifest(LOGICAL_ID, MANIFEST_REVISION))
		val revision = MANIFEST_REVISION + 1L
		database.sourcePlanStateDao().insertRevision(
			AcquisitionPlanRevisionEntity(
				revision = revision,
				planId = "wifi-disabled-plan",
				createdAtMs = OBSERVED_WALL_MS + 10L,
				status = "EFFECTIVE",
				sourcePolicyRevision = POLICY_REVISION,
			),
		)
		val unsigned = original.copy(
			manifestRevision = revision,
			acquisitionPlanRevision = revision,
			startOrigin = "POLICY_RECONCILIATION",
			effectiveElapsedRealtimeNanos = OBSERVED_END_NANOS + 1L,
			effectiveWallTimeMs = OBSERVED_WALL_MS + 10L,
			changeReason = "POLICY_RECONCILIATION",
			manifestChecksum = "",
		)
		sessionDao.insertManifest(unsigned.copy(
			manifestChecksum = SessionManifestIntegrity.compute(unsigned, emptyList()),
		))
		val session = requireNotNull(sessionDao.session(LOGICAL_ID))
		val run = requireNotNull(sessionDao.serviceRun(RUN_ID))
		assertEquals(1, sessionDao.updateSession(session.copy(
			desiredPlanRevision = revision,
			currentManifestRevision = revision,
		)))
		assertEquals(1, sessionDao.updateServiceRun(run.copy(desiredPlanRevision = revision)))
	}

	private suspend fun setLiveLifecyclePair(sessionState: String, runState: String) {
		val session = requireNotNull(database.sourceSessionDao().session(LOGICAL_ID))
		val run = requireNotNull(database.sourceSessionDao().serviceRun(RUN_ID))
		val stoppingSession = sessionState == "STOPPING"
		assertEquals(1, database.sourceSessionDao().updateSession(
			session.copy(
				state = sessionState,
				completedAtMs = null,
				cutoffAtMs = SESSION_END_WALL_MS.takeIf { stoppingSession },
				cutoffElapsedNanos = SESSION_END_NANOS.takeIf { stoppingSession },
				finalAdmissionOrdinal = null,
				currentServiceRunId = RUN_ID,
			),
		))
		assertEquals(1, database.sourceSessionDao().updateServiceRun(
			run.copy(
				state = runState,
				completedAtMs = SESSION_END_WALL_MS.takeIf { runState in setOf("FINALIZED", "CLOSED", "FAILED") },
			),
		))
	}

	private suspend fun installLiveReplacement() {
		val sessionDao = database.sourceSessionDao()
		val session = requireNotNull(sessionDao.session(LOGICAL_ID))
		val terminalRun = requireNotNull(sessionDao.serviceRun(RUN_ID))
		val replacementStartWall = SESSION_END_WALL_MS + 1L
		val replacementStartElapsed = SESSION_END_NANOS + 1L
		val replacementSegment = segment(REPLACEMENT_RUN_ID).copy(
			id = REPLACEMENT_SEGMENT_ID,
			startTimeMs = replacementStartWall,
			endTimeMs = replacementStartWall,
			createdAt = replacementStartWall,
		)
		assertEquals(REPLACEMENT_SEGMENT_ID, database.sessionSegmentDao().insert(replacementSegment))
		val replacementRun = terminalRun.copy(
			serviceRunId = REPLACEMENT_RUN_ID,
			state = "ACTIVE",
			startedAtMs = replacementStartWall,
			startedElapsedNanos = replacementStartElapsed,
			completedAtMs = null,
			completionReason = null,
			runtimeAcknowledgement = "START_ACCEPTED",
			runRevision = 1L,
			startDeliveryToken = "wifi-replacement-start-token",
			preparedManifestRevision = REPLACEMENT_MANIFEST_REVISION,
			preparedIntentRevision = REPLACEMENT_INTENT_REVISION,
			sessionSegmentId = REPLACEMENT_SEGMENT_ID,
			presentationAcknowledgement = SourceServiceRunEntity.PRESENTATION_PENDING,
			presentationAcknowledgedAtMs = null,
		)
		sessionDao.insertServiceRun(replacementRun)
		val originalSource = requireNotNull(sessionDao.manifestSource(
			LOGICAL_ID,
			MANIFEST_REVISION,
			WIFI_SOURCE,
			SourceBrokerPurpose.SESSION_CAPTURE,
		))
		val replacementSource = originalSource.copy(manifestRevision = REPLACEMENT_MANIFEST_REVISION)
		val unsignedManifest = requireNotNull(sessionDao.manifest(LOGICAL_ID, MANIFEST_REVISION)).copy(
			manifestRevision = REPLACEMENT_MANIFEST_REVISION,
			serviceRunId = REPLACEMENT_RUN_ID,
			effectiveElapsedRealtimeNanos = replacementStartElapsed,
			effectiveWallTimeMs = replacementStartWall,
			changeReason = "REPLACEMENT_START",
			manifestChecksum = "",
		)
		sessionDao.insertManifest(unsignedManifest.copy(
			manifestChecksum = SessionManifestIntegrity.compute(unsignedManifest, listOf(replacementSource)),
		))
		sessionDao.insertManifestSources(listOf(replacementSource))
		sessionDao.insertLifecycleActions(listOf(
			startAction(actionId = REPLACEMENT_ACTION_ID, actionRevision = 2L).copy(
				serviceRunId = REPLACEMENT_RUN_ID,
				manifestRevision = REPLACEMENT_MANIFEST_REVISION,
				requestedAtMs = replacementStartWall,
				requestedElapsedRealtimeNanos = replacementStartElapsed,
				acknowledgedAtMs = replacementStartWall + 1L,
				acknowledgedElapsedRealtimeNanos = replacementStartElapsed + 1L,
				sourceInstanceId = SOURCE_INSTANCE,
				registrationGeneration = REPLACEMENT_REGISTRATION_GENERATION,
			),
		))
		database.sourceBrokerDao().insertRegistration(
			registration(
				generation = REPLACEMENT_REGISTRATION_GENERATION,
				sourceInstance = SOURCE_INSTANCE,
			).copy(
				status = ProviderRegistrationGenerationEntity.STATUS_ACTIVE,
				reservedAtMs = replacementStartWall,
				reservedElapsedRealtimeNanos = replacementStartElapsed,
				acceptedAtMs = replacementStartWall,
				acceptedElapsedRealtimeNanos = replacementStartElapsed,
				retiredAtMs = null,
				retiredElapsedRealtimeNanos = null,
			),
		)
		assertEquals(1, sessionDao.updateSession(
			session.copy(
				state = "ACTIVE",
				completedAtMs = null,
				cutoffAtMs = null,
				cutoffElapsedNanos = null,
				finalAdmissionOrdinal = null,
				currentManifestRevision = REPLACEMENT_MANIFEST_REVISION,
				currentIntentRevision = REPLACEMENT_INTENT_REVISION,
				currentServiceRunId = REPLACEMENT_RUN_ID,
			),
		))
	}

	private fun restoreReplacementRegistration(registration: ProviderRegistrationGenerationEntity) {
		database.openHelper.writableDatabase.execSQL(
			"UPDATE provider_registration_generation SET collected_data_epoch = ?, status = ?, " +
				"reserved_at_ms = ?, reserved_elapsed_realtime_nanos = ?, accepted_at_ms = ?, " +
				"accepted_elapsed_realtime_nanos = ?, retired_at_ms = ?, " +
				"retired_elapsed_realtime_nanos = ?, failure_code = ? " +
				"WHERE source_kind = ? AND registration_generation = ?",
			arrayOf<Any?>(
				registration.collectedDataEpoch,
				registration.status,
				registration.reservedAtMs,
				registration.reservedElapsedRealtimeNanos,
				registration.acceptedAtMs,
				registration.acceptedElapsedRealtimeNanos,
				registration.retiredAtMs,
				registration.retiredElapsedRealtimeNanos,
				registration.failureCode,
				registration.sourceKind,
				registration.registrationGeneration,
			),
		)
	}

	private suspend fun makeReplacementRetiring(retiredAtMs: Long, retiredElapsedNanos: Long) {
		val sessionDao = database.sourceSessionDao()
		val session = requireNotNull(sessionDao.session(LOGICAL_ID))
		val run = requireNotNull(sessionDao.serviceRun(REPLACEMENT_RUN_ID))
		assertEquals(1, sessionDao.updateSession(session.copy(
			state = "STOPPING",
			cutoffAtMs = SESSION_END_WALL_MS + 10L,
			cutoffElapsedNanos = SESSION_END_NANOS + 10L,
		)))
		assertEquals(1, sessionDao.updateServiceRun(run.copy(state = "STOPPING")))
		database.openHelper.writableDatabase.execSQL(
			"UPDATE provider_registration_generation SET status = 'RETIRING', retired_at_ms = ?, " +
				"retired_elapsed_realtime_nanos = ?, failure_code = 'session-stop' " +
				"WHERE source_kind = ? AND registration_generation = ?",
			arrayOf<Any?>(
				retiredAtMs,
				retiredElapsedNanos,
				WIFI_SOURCE,
				REPLACEMENT_REGISTRATION_GENERATION,
			),
		)
	}

	private suspend fun installWifiLane(stage: String) {
		val binding = ExecutableSourceLaneCatalog.WIFI_SESSION_FACTS
		database.sourceProjectionStateDao().installProductLane(
			SourceProductProjectionLaneEntity(
				sourceKind = binding.source.stableCode,
				bindingGeneration = binding.bindingGeneration,
				projectionId = binding.projectionId,
				projectionVersion = binding.projectionVersion,
				captureModeMask = binding.captureModeMask,
				productStage = stage,
				activatedRolloutRevision = ROLLOUT_REVISION,
				activationOrdinal = 1L,
				contiguousAdmissionOrdinal = 0L,
				retentionRequired = true,
				status = SourceProductProjectionLaneEntity.STATUS_ACTIVE,
				installedAtMs = RUN_START_WALL_MS,
				updatedAtMs = RUN_START_WALL_MS,
			),
		)
	}

	private suspend fun rewriteWalPurpose(eventId: SourceEventId, purposeMask: Long) {
		val original = requireNotNull(database.sourceEventWalDao().getByEventId(eventId.value))
		val unsigned = original.copy(
			authorizationPurposeEligibilityMask = purposeMask,
			integrityIdentity = SourceEventWalEntity.LEGACY_PENDING_CHECKSUM,
		)
		val rewritten = unsigned.copy(integrityIdentity = unsigned.calculatedIntegrityIdentity())
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_event_wal SET authorization_purpose_eligibility_mask = ?, " +
				"integrity_identity = ? WHERE event_id = ?",
			arrayOf<Any?>(purposeMask, rewritten.integrityIdentity, eventId.value),
		)
	}

	private fun corruptWalPayload() {
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_event_wal SET payload = X'00' WHERE event_id = ?",
			arrayOf(EVENT_ID.value),
		)
	}

	private suspend fun assertTerminalWifiLaneFailure(
		lane: WifiSessionFactProjectionLane,
	): WifiSessionFactDrainResult.Failed {
		val failed = assertIs<WifiSessionFactDrainResult.Failed>(lane.drainAvailable())
		assertEquals(0L, failed.lastCompletedOrdinal)
		assertEquals(1L, failed.failedOrdinal)
		assertEquals(true, failed.terminal)
		assertEquals(0L, database.wifiCapturedFactDao().revisionCount())
		assertEquals(0L, database.wifiCapturedFactDao().cursorCount())
		assertEquals(0L, database.sourceProjectionStateDao().activeProductLane(WIFI_SOURCE)
			?.contiguousAdmissionOrdinal)
		return failed
	}

	private suspend fun insertSecondWal(
		observedWallTimeMs: Long = OBSERVED_WALL_MS + 200L,
	): SourceEventId = insertAdditionalWal(
		index = 2,
		accessPoints = listOf(
			WifiAccessPointEvidence("", 2_412, -80, OBSERVED_END_NANOS + 100_000_000L),
			WifiAccessPointEvidence("", 5_180, -60, OBSERVED_END_NANOS + 200_000_000L),
		),
		observedWallTimeMs = observedWallTimeMs,
	)

	private suspend fun insertAdditionalWal(
		index: Int,
		accessPoints: List<WifiAccessPointEvidence>,
		observedWallTimeMs: Long,
	): SourceEventId {
		require(index > 1)
		require(accessPoints.isNotEmpty())
		val first = requireNotNull(database.sourceEventWalDao().getByEventId(EVENT_ID.value))
		val observedStart = requireNotNull(
			accessPoints.mapNotNull(WifiAccessPointEvidence::providerTimestampNanos).minOrNull(),
		)
		val observedEnd = requireNotNull(
			accessPoints.mapNotNull(WifiAccessPointEvidence::providerTimestampNanos).maxOrNull(),
		)
		val payload = WifiResultSnapshotPayload(
			accessPoints = accessPoints,
			platformTimestampMs = observedEnd / NANOS_PER_MILLISECOND,
			resultAgeMs = null,
			observationZoneId = ZONE_ID,
		)
		val encoded = payloadCodec.encode(payload, first.payloadVersion)
		val unsigned = first.copy(
			admissionOrdinal = 0L,
			eventId = "wifi-event-$index",
			deliveryIdentity = wifiProviderDeliveryIdentity(BOOT_ID, accessPoints, first.payloadVersion).value,
			sourceSequence = SOURCE_SEQUENCE + index - 1L,
			observedIntervalStartNanos = observedStart,
			observedElapsedNanos = observedEnd,
			receivedElapsedNanos = observedEnd + 100_000_000L,
			receivedWallTimeMs = observedWallTimeMs + 100L,
			wallTimeMs = observedWallTimeMs,
			acquiredAtMs = observedWallTimeMs,
			createdAtMs = observedWallTimeMs + 1L,
			payload = encoded.bytes,
			payloadChecksum = encoded.checksum,
			integrityIdentity = SourceEventWalEntity.LEGACY_PENDING_CHECKSUM,
		)
		val row = unsigned.copy(integrityIdentity = unsigned.calculatedIntegrityIdentity())
		assertEquals(index.toLong(), database.sourceEventWalDao().insertIgnoringDuplicate(row))
		database.openHelper.writableDatabase.execSQL(
			"UPDATE logical_tracking_session SET final_admission_ordinal = ? WHERE logical_tracking_id = ?",
			arrayOf<Any?>(index, LOGICAL_ID),
		)
		return SourceEventId(row.eventId)
	}

	private suspend fun insertNoncaptureWal(
		index: Int,
		purpose: String,
	): SourceEventWalEntity {
		require(index > 1)
		val observationOffsetNanos = index - 1L
		val demand = nonCaptureDemand(index, purpose).copy(
			status = SourceDemandEntity.STATUS_RETIRED,
			retireBootId = BOOT_ID,
			retireElapsedRealtimeNanos = NONCAPTURE_OBSERVED_END_NANOS + index,
			retiredAtMs = NONCAPTURE_WALL_TIME_MS + index,
		)
		database.sourceBrokerDao().insertDemands(listOf(demand))
		val generation = NONCAPTURE_REGISTRATION_GENERATION + index
		val authorizationRevision = NONCAPTURE_AUTHORIZATION_REVISION + index
		val sourceInstance = "$SOURCE_INSTANCE-noncapture-$index"
		val registration = registration(
			generation = generation,
			sourceInstance = sourceInstance,
		).copy(
			reservedAtMs = SESSION_END_WALL_MS + 1L,
			reservedElapsedRealtimeNanos = SESSION_END_NANOS + 1L,
			acceptedAtMs = SESSION_END_WALL_MS + 2L,
			acceptedElapsedRealtimeNanos = NONCAPTURE_REGISTRATION_START_NANOS,
			retiredAtMs = NONCAPTURE_WALL_TIME_MS + index,
			retiredElapsedRealtimeNanos = NONCAPTURE_OBSERVED_END_NANOS + index,
			captureCallbackBarrierAuthorizationRevision = 0L,
		)
		database.sourceBrokerDao().insertRegistration(registration)
		val authorizationRows = SourceBrokerAuthorization.rows(
			sourceKind = WIFI_SOURCE,
			registrationGeneration = generation,
			authorizationRevision = authorizationRevision,
			demands = listOf(demand),
			effectiveBootId = BOOT_ID,
			effectiveElapsedRealtimeNanos = NONCAPTURE_AUTHORIZATION_START_NANOS,
			effectiveWallTimeMs = SESSION_END_WALL_MS + 3L,
		)
		database.sourceBrokerDao().insertAuthorizations(authorizationRows)
		val accessPoints = listOf(
			WifiAccessPointEvidence("", 2_412, -80, NONCAPTURE_OBSERVED_START_NANOS + observationOffsetNanos),
			WifiAccessPointEvidence("", 5_180, -60, NONCAPTURE_OBSERVED_END_NANOS + observationOffsetNanos),
		)
		val payload = WifiResultSnapshotPayload(
			accessPoints = accessPoints,
			platformTimestampMs = NONCAPTURE_OBSERVED_END_NANOS / NANOS_PER_MILLISECOND,
			resultAgeMs = null,
			observationZoneId = ZONE_ID,
		)
		val encoded = payloadCodec.encode(payload, PAYLOAD_VERSION)
		val unsigned = SourceEventWalEntity(
			eventId = "wifi-noncapture-event-$index",
			providerDedupKey = null,
			deliveryIdentity = wifiProviderDeliveryIdentity(BOOT_ID, accessPoints, PAYLOAD_VERSION).value,
			deliveryUnitIndex = 0,
			deliveryUnitCount = 1,
			logicalTrackingId = null,
			serviceRunId = null,
			sourceKind = WIFI_SOURCE,
			sourceInstanceId = sourceInstance,
			registrationGeneration = generation,
			physicalConfigurationFingerprint = wifiPlan().physicalConfigurationFingerprint(),
			authorizationRevision = authorizationRevision,
			authorizationPurposeEligibilityMask = authorizationRows.first().purposeEligibilityMask,
			authorizationFingerprint = authorizationRows.first().authorizationFingerprint,
			sourceSequence = 1L,
			configRevision = null,
			planAttribution = PlanAttribution.RECEIVE_TIME_ONLY.ordinal,
			clockDomainId = BOOT_ID,
			observedElapsedNanos = NONCAPTURE_OBSERVED_END_NANOS + observationOffsetNanos,
			observedIntervalStartNanos = NONCAPTURE_OBSERVED_START_NANOS + observationOffsetNanos,
			receivedElapsedNanos = NONCAPTURE_RECEIVED_NANOS,
			receivedWallTimeMs = NONCAPTURE_WALL_TIME_MS + 100L,
			wallTimeMs = NONCAPTURE_WALL_TIME_MS,
			wallTimeUncertaintyMs = WALL_UNCERTAINTY_MS,
			capturedCollectedDataEpoch = 0L,
			activityAutomationEpoch = null,
			sourcePolicyRevision = null,
			captureConsentEpoch = null,
			sessionManifestRevision = null,
			lifecycleLeaseGeneration = null,
			acquiredAtMs = NONCAPTURE_WALL_TIME_MS,
			qualityFlags = 0L,
			qualityConfidence = null,
			payloadVersion = PAYLOAD_VERSION,
			payload = encoded.bytes,
			payloadChecksum = encoded.checksum,
			createdAtMs = DELETE_AT_MS + index,
		)
		val row = unsigned.copy(integrityIdentity = unsigned.calculatedIntegrityIdentity())
		assertEquals(index.toLong(), database.sourceEventWalDao().insertIgnoringDuplicate(row))
		return requireNotNull(database.sourceEventWalDao().getByEventId(row.eventId))
	}

	private suspend fun installRetainedNoncapturePolicyAndConsent(
		effectiveElapsedRealtimeNanos: Long = SESSION_END_NANOS + 1L,
		effectiveWallTimeMs: Long = SESSION_END_WALL_MS + 1L,
		qosCode: Int = QOS_OFF,
	) {
		database.sourcePolicyDao().insertPolicies(listOf(
			SourcePolicyEntity(
				policyRevision = REVOKED_POLICY_REVISION,
				sourceKind = WIFI_SOURCE,
				enabled = true,
				qosCode = qosCode,
				locationMinTimeSeconds = null,
				locationMinDistanceMeters = null,
				locationRequiredAccuracyMeters = null,
				capturePersistenceEligible = true,
				controlPersistenceEligible = false,
				ambientPersistenceEligible = true,
				captureConsentEpoch = CONSENT_EPOCH,
				controlConsentEpoch = CONTROL_CONSENT_EPOCH,
				ambientConsentEpoch = AMBIENT_CONSENT_EPOCH,
				effectiveBootId = BOOT_ID,
				effectiveElapsedRealtimeNanos = effectiveElapsedRealtimeNanos,
				effectiveWallTimeMs = effectiveWallTimeMs,
				changeReason = "TEST_NONCAPTURE_CONTINUATION",
			),
		))
		database.sourcePolicyDao().insertConsentEpochs(listOf(
			SourceConsentEpochEntity(
				sourceKind = WIFI_SOURCE,
				purpose = "CONTROL",
				epoch = CONTROL_CONSENT_EPOCH,
				eligible = true,
				persistenceEligible = false,
				policyRevision = REVOKED_POLICY_REVISION,
				effectiveBootId = BOOT_ID,
				effectiveElapsedRealtimeNanos = effectiveElapsedRealtimeNanos,
				effectiveWallTimeMs = effectiveWallTimeMs,
				changeReason = "TEST_CONTROL_CONTINUATION",
			),
			SourceConsentEpochEntity(
				sourceKind = WIFI_SOURCE,
				purpose = SourceBrokerPurpose.AMBIENT_PRODUCT,
				epoch = AMBIENT_CONSENT_EPOCH,
				eligible = true,
				persistenceEligible = true,
				policyRevision = REVOKED_POLICY_REVISION,
				effectiveBootId = BOOT_ID,
				effectiveElapsedRealtimeNanos = effectiveElapsedRealtimeNanos,
				effectiveWallTimeMs = effectiveWallTimeMs,
				changeReason = "TEST_AMBIENT_CONTINUATION",
			),
		))
	}

	private suspend fun installMixedPortableExportFixture(
		activeContinuation: Boolean,
		noncapturePolicyQosCode: Int = QOS_OFF,
	) {
		installValidFixture(candidateWriter = true)
		installRetainedNoncapturePolicyAndConsent(RUN_START_NANOS, RUN_START_WALL_MS, noncapturePolicyQosCode)
		val control = nonCaptureDemand(77, SourceBrokerPurpose.CONTROL_AUTOSTART, noncapturePolicyQosCode).copy(
			demandId = "$DEMAND_ID-control",
			requestedElapsedRealtimeNanos = RUN_START_NANOS,
			requestedAtMs = RUN_START_WALL_MS,
			status = if (activeContinuation) {
				SourceDemandEntity.STATUS_ACTIVE
			} else {
				SourceDemandEntity.STATUS_RETIRED
			},
			retireBootId = BOOT_ID.takeUnless { activeContinuation },
			retireElapsedRealtimeNanos = SESSION_END_NANOS.takeUnless { activeContinuation },
			retiredAtMs = SESSION_END_WALL_MS.takeUnless { activeContinuation },
		)
		val ambient = nonCaptureDemand(78, SourceBrokerPurpose.AMBIENT_PRODUCT).copy(
			demandId = "$DEMAND_ID-ambient",
			requestedElapsedRealtimeNanos = RUN_START_NANOS,
			requestedAtMs = RUN_START_WALL_MS,
			status = if (activeContinuation) {
				SourceDemandEntity.STATUS_ACTIVE
			} else {
				SourceDemandEntity.STATUS_RETIRED
			},
			retireBootId = BOOT_ID.takeUnless { activeContinuation },
			retireElapsedRealtimeNanos = SESSION_END_NANOS.takeUnless { activeContinuation },
			retiredAtMs = SESSION_END_WALL_MS.takeUnless { activeContinuation },
		)
		database.sourceBrokerDao().insertDemands(listOf(control, ambient))
		val capture = database.sourceBrokerDao().demandsByIds(listOf("$DEMAND_ID-0")).single()
		database.openHelper.writableDatabase.execSQL(
			"DELETE FROM source_authorization WHERE source_kind = ? AND registration_generation = ?",
			arrayOf<Any?>(WIFI_SOURCE, REGISTRATION_GENERATION),
		)
		val initial = SourceBrokerAuthorization.rows(
			sourceKind = WIFI_SOURCE,
			registrationGeneration = REGISTRATION_GENERATION,
			authorizationRevision = AUTHORIZATION_REVISION,
			demands = listOf(capture, control, ambient),
			effectiveBootId = BOOT_ID,
			effectiveElapsedRealtimeNanos = AUTHORIZATION_START_NANOS,
			effectiveWallTimeMs = RUN_START_WALL_MS,
		)
		database.sourceBrokerDao().insertAuthorizations(initial)
		installPortableClosingAuthorization(
			demands = listOf(control, ambient).takeIf { activeContinuation }.orEmpty(),
		)
		rewriteWalAuthorization(
			purposeMask = initial.first().purposeEligibilityMask,
			fingerprint = initial.first().authorizationFingerprint,
		)
		if (activeContinuation) {
			database.openHelper.writableDatabase.execSQL(
				"UPDATE provider_registration_generation SET status = 'ACTIVE', retired_at_ms = NULL, " +
					"retired_elapsed_realtime_nanos = NULL, failure_code = NULL " +
					"WHERE source_kind = ? AND registration_generation = ?",
				arrayOf<Any?>(WIFI_SOURCE, REGISTRATION_GENERATION),
			)
		}
		materializePortableFixture()
	}

	private suspend fun rewriteWalAuthorization(
		purposeMask: Long,
		fingerprint: String,
		eventId: String = EVENT_ID.value,
	) {
		val original = requireNotNull(database.sourceEventWalDao().getByEventId(eventId))
		val unsigned = original.copy(
			authorizationPurposeEligibilityMask = purposeMask,
			authorizationFingerprint = fingerprint,
			integrityIdentity = SourceEventWalEntity.LEGACY_PENDING_CHECKSUM,
		)
		val rewritten = unsigned.copy(integrityIdentity = unsigned.calculatedIntegrityIdentity())
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_event_wal SET authorization_purpose_eligibility_mask = ?, " +
				"authorization_fingerprint = ?, integrity_identity = ? WHERE event_id = ?",
			arrayOf<Any?>(purposeMask, fingerprint, rewritten.integrityIdentity, eventId),
		)
	}

	private suspend fun assertExactWal(expected: SourceEventWalEntity) {
		val actual = requireNotNull(database.sourceEventWalDao().getByEventId(expected.eventId))
		val excludedPayload = byteArrayOf()
		assertEquals(expected.copy(payload = excludedPayload), actual.copy(payload = excludedPayload))
		assertEquals(true, expected.payload.contentEquals(actual.payload))
	}

	private suspend fun rewriteWalEpochAndPayload(
		eventId: SourceEventId,
		collectedDataEpoch: Long,
		payload: ByteArray,
	) {
		val original = requireNotNull(database.sourceEventWalDao().getByEventId(eventId.value))
		val checksummed = original.copy(
			capturedCollectedDataEpoch = collectedDataEpoch,
			payload = payload,
			payloadChecksum = SourceEventWalEntity.LEGACY_PENDING_CHECKSUM,
			integrityIdentity = SourceEventWalEntity.LEGACY_PENDING_CHECKSUM,
		).let { row -> row.copy(payloadChecksum = row.calculatedPayloadChecksum()) }
		val rewritten = checksummed.copy(integrityIdentity = checksummed.calculatedIntegrityIdentity())
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_event_wal SET captured_collected_data_epoch = ?, payload = ?, " +
				"payload_checksum = ?, integrity_identity = ? WHERE event_id = ?",
			arrayOf(
				collectedDataEpoch,
				payload,
				rewritten.payloadChecksum,
				rewritten.integrityIdentity,
				eventId.value,
			),
		)
	}

	private suspend fun revokeCaptureConsent(keepAmbientAndControl: Boolean = false) {
		val policyDao = database.sourcePolicyDao()
		policyDao.insertPolicies(
			listOf(
				SourcePolicyEntity(
					policyRevision = REVOKED_POLICY_REVISION,
					sourceKind = WIFI_SOURCE,
					enabled = false,
					qosCode = QOS_OFF,
					locationMinTimeSeconds = null,
					locationMinDistanceMeters = null,
					locationRequiredAccuracyMeters = null,
					capturePersistenceEligible = false,
					controlPersistenceEligible = false,
					ambientPersistenceEligible = keepAmbientAndControl,
					captureConsentEpoch = null,
					controlConsentEpoch = CONTROL_CONSENT_EPOCH.takeIf { keepAmbientAndControl },
					ambientConsentEpoch = AMBIENT_CONSENT_EPOCH.takeIf { keepAmbientAndControl },
					effectiveBootId = BOOT_ID,
					effectiveElapsedRealtimeNanos = SESSION_END_NANOS + 1L,
					effectiveWallTimeMs = SESSION_END_WALL_MS + 1L,
					changeReason = "TEST_CAPTURE_CONSENT_RESET",
				),
			),
		)
		policyDao.insertConsentEpochs(
			buildList {
				add(
				SourceConsentEpochEntity(
					sourceKind = WIFI_SOURCE,
					purpose = SourceBrokerPurpose.SESSION_CAPTURE,
					epoch = REVOKED_CONSENT_EPOCH,
					eligible = false,
					persistenceEligible = false,
					policyRevision = REVOKED_POLICY_REVISION,
					effectiveBootId = BOOT_ID,
					effectiveElapsedRealtimeNanos = SESSION_END_NANOS + 1L,
					effectiveWallTimeMs = SESSION_END_WALL_MS + 1L,
					changeReason = "TEST_CAPTURE_CONSENT_RESET",
				),
				)
				if (keepAmbientAndControl) {
					add(
						SourceConsentEpochEntity(
							sourceKind = WIFI_SOURCE,
							purpose = SourceBrokerPurpose.AMBIENT_PRODUCT,
							epoch = AMBIENT_CONSENT_EPOCH,
							eligible = true,
							persistenceEligible = true,
							policyRevision = REVOKED_POLICY_REVISION,
							effectiveBootId = BOOT_ID,
							effectiveElapsedRealtimeNanos = SESSION_END_NANOS + 1L,
							effectiveWallTimeMs = SESSION_END_WALL_MS + 1L,
							changeReason = "TEST_AMBIENT_RETAINED",
						),
					)
					add(
						SourceConsentEpochEntity(
							sourceKind = WIFI_SOURCE,
							purpose = "CONTROL",
							epoch = CONTROL_CONSENT_EPOCH,
							eligible = true,
							persistenceEligible = false,
							policyRevision = REVOKED_POLICY_REVISION,
							effectiveBootId = BOOT_ID,
							effectiveElapsedRealtimeNanos = SESSION_END_NANOS + 1L,
							effectiveWallTimeMs = SESSION_END_WALL_MS + 1L,
							changeReason = "TEST_CONTROL_RETAINED",
						),
					)
				}
			},
		)
		assertEquals(
			1,
			policyDao.compareAndSetAuthority(
				SourcePolicyAuthorityEntity.STATE_ACTIVE,
				POLICY_REVISION,
				SourcePolicyAuthorityEntity.STATE_ACTIVE,
				REVOKED_POLICY_REVISION,
				null,
				SESSION_END_WALL_MS + 1L,
			),
		)
	}


	/** Constructed immutable writer binding only; actual setup/callback authority is outside this fixture. */
	private suspend fun installWriterContractFixture(generation: Long, mode: String, advanceOwner: Boolean) {
		database.withTransaction {
			val dao = database.sourceSessionDao()
			val source = dao.manifestSources(LOGICAL_ID, MANIFEST_REVISION).single()
			val changed = source.copy(writerBindingGeneration = generation, writerOwnerGeneration = generation * 2L)
			val origin = if (mode == "AUTOMATIC") "AUTOMATIC_BACKGROUND_START" else "MANUAL_FOREGROUND_START"
			val manifest = requireNotNull(dao.manifest(LOGICAL_ID, MANIFEST_REVISION)).copy(sessionMode = mode, startOrigin = origin)
			val checksum = SessionManifestIntegrity.compute(manifest, listOf(changed))
			database.openHelper.writableDatabase.execSQL("UPDATE session_manifest_source SET writer_binding_generation = ?, writer_owner_generation = ? WHERE logical_tracking_id = ? AND manifest_revision = ? AND source_kind = ? AND purpose = ?",
				arrayOf<Any?>(generation, generation * 2L, LOGICAL_ID, MANIFEST_REVISION, WIFI_SOURCE, SourceBrokerPurpose.SESSION_CAPTURE))
			database.openHelper.writableDatabase.execSQL("UPDATE session_manifest_version SET session_mode = ?, start_origin = ?, manifest_checksum = ? WHERE logical_tracking_id = ? AND manifest_revision = ?", arrayOf<Any?>(mode, origin, checksum, LOGICAL_ID, MANIFEST_REVISION))
			dao.updateSession(requireNotNull(dao.session(LOGICAL_ID)).copy(sessionMode = mode, startOrigin = origin))
			dao.updateServiceRun(requireNotNull(dao.serviceRun(RUN_ID)).copy(startOrigin = origin))
			if (advanceOwner) assertEquals(1, database.sourceDestinationOwnerDao().compareAndSetOwner(
				WIFI_SOURCE, SourceDestinationOwnerEntity.DESTINATION_SESSION_WIFI,
				SourceDestinationOwnerEntity.OWNER_WIFI_SESSION_FACTS, 2L,
				SourceDestinationOwnerEntity.OWNER_WIFI_SESSION_FACTS, 4L, OBSERVED_WALL_MS))
		}
	}

	private suspend fun installValidFixture(
		plan: WifiPlan = wifiPlan(),
		configurationRevision: Long? = null,
		sourceSequence: Long = SOURCE_SEQUENCE,
		accessPoints: List<WifiAccessPointEvidence> = accessPoints(),
		deliveryIdentityOverride: String? = null,
		segmentRunId: String = RUN_ID,
		evidenceState: SourceEvidenceState = SourceEvidenceState(),
		zoneId: String = ZONE_ID,
		authorizationDemandCount: Int = 1,
		lastDemandMaximumAgeMs: Long? = null,
		includePlanApplication: Boolean = true,
		planApplicationRevision: Long = PLAN_REVISION,
		lastDemandAcquisitionSpec: String? = null,
		requestedDeliveryLatencyMs: Long? = null,
		candidateWriter: Boolean = false,
		payloadVersion: Int = PAYLOAD_VERSION,
		insertWal: Boolean = true,
	) {
		database.sourceEvidenceStateDao().ensure(evidenceState)
		insertPlan(plan)
		installPolicyAndConsent()
		val segmentId = database.sessionSegmentDao().insert(segment(segmentRunId))
		assertEquals(SEGMENT_ID, segmentId)
		installSessionAndManifest(segmentId, zoneId, candidateWriter)
		if (candidateWriter) {
			database.sourceDestinationOwnerDao().insertIfAbsent(
				SourceDestinationOwnerEntity(
					sourceKind = WIFI_SOURCE,
					destination = SourceDestinationOwnerEntity.DESTINATION_SESSION_WIFI,
					owner = SourceDestinationOwnerEntity.OWNER_WIFI_SESSION_FACTS,
					ownerGeneration = SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
					updatedAtMs = RUN_START_WALL_MS,
				),
			)
		}
		val demands = List(authorizationDemandCount) { index ->
			demand(
				index = index,
				minimumAcquisitionSpec = if (index == authorizationDemandCount - 1) {
					lastDemandAcquisitionSpec ?: "wifi:v1:required=BROADCAST_CALLBACK"
				} else {
					"wifi:v1:required=BROADCAST_CALLBACK"
				},
				requestedDeliveryLatencyMs = requestedDeliveryLatencyMs,
			).let { candidate ->
				if (index == authorizationDemandCount - 1 && lastDemandMaximumAgeMs != null) {
					candidate.copy(maximumAgeMs = lastDemandMaximumAgeMs)
				} else candidate
			}
		}
		database.sourceBrokerDao().insertDemands(demands)
		database.sourceBrokerDao().insertRegistration(registration(plan.physicalConfigurationFingerprint()))
		val authorization = SourceBrokerAuthorization.rows(
			sourceKind = WIFI_SOURCE,
			registrationGeneration = REGISTRATION_GENERATION,
			authorizationRevision = AUTHORIZATION_REVISION,
			demands = demands,
			effectiveBootId = BOOT_ID,
			effectiveElapsedRealtimeNanos = AUTHORIZATION_START_NANOS,
			effectiveWallTimeMs = RUN_START_WALL_MS,
		)
		database.sourceBrokerDao().insertAuthorizations(authorization)
		if (includePlanApplication) {
			database.sourceSessionDao().insertLifecycleActions(
				listOf(startAction(desiredPlanRevision = planApplicationRevision)),
			)
		}
		val isEmpty = accessPoints.isEmpty()
		val observedStart = if (isEmpty) OBSERVED_END_NANOS else requireNotNull(
			accessPoints.mapNotNull(WifiAccessPointEvidence::providerTimestampNanos).minOrNull(),
		)
		val observedEnd = if (isEmpty) OBSERVED_END_NANOS else requireNotNull(
			accessPoints.mapNotNull(WifiAccessPointEvidence::providerTimestampNanos).maxOrNull(),
		)
		val payload = WifiResultSnapshotPayload(
			accessPoints = accessPoints,
			platformTimestampMs = observedEnd.takeUnless { isEmpty }?.div(NANOS_PER_MILLISECOND),
			resultAgeMs = null,
			observationZoneId = ZONE_ID,
		)
		val encodedPayload = payloadCodec.encode(payload, payloadVersion)
		val deliveryIdentity = deliveryIdentityOverride ?: wifiProviderDeliveryIdentity(
			BOOT_ID,
			accessPoints,
			payloadVersion,
		).value
		val unsignedWal = SourceEventWalEntity(
			eventId = EVENT_ID.value,
			providerDedupKey = null,
			deliveryIdentity = deliveryIdentity,
			deliveryUnitIndex = 0,
			deliveryUnitCount = 1,
			logicalTrackingId = LOGICAL_ID,
			serviceRunId = RUN_ID,
			sourceKind = WIFI_SOURCE,
			sourceInstanceId = SOURCE_INSTANCE,
			registrationGeneration = REGISTRATION_GENERATION,
			physicalConfigurationFingerprint = plan.physicalConfigurationFingerprint(),
			authorizationRevision = AUTHORIZATION_REVISION,
			authorizationPurposeEligibilityMask = SourceBrokerPurpose.MASK_SESSION_CAPTURE,
			authorizationFingerprint = authorization.first().authorizationFingerprint,
			sourceSequence = sourceSequence,
			configRevision = configurationRevision,
			planAttribution = PlanAttribution.CAPTURED_REGISTRATION.ordinal,
			clockDomainId = BOOT_ID,
			observedElapsedNanos = observedEnd,
			observedIntervalStartNanos = observedStart,
			receivedElapsedNanos = RECEIVED_NANOS,
			receivedWallTimeMs = OBSERVED_WALL_MS + 100L,
			wallTimeMs = OBSERVED_WALL_MS,
			wallTimeUncertaintyMs = WALL_UNCERTAINTY_MS,
			capturedCollectedDataEpoch = 0L,
			activityAutomationEpoch = null,
			sourcePolicyRevision = POLICY_REVISION,
			captureConsentEpoch = CONSENT_EPOCH,
			sessionManifestRevision = MANIFEST_REVISION,
			lifecycleLeaseGeneration = LEASE_GENERATION,
			acquiredAtMs = OBSERVED_WALL_MS,
			qualityFlags = 0L,
			qualityConfidence = null,
			payloadVersion = payloadVersion,
			payload = encodedPayload.bytes,
			payloadChecksum = encodedPayload.checksum,
			createdAtMs = OBSERVED_WALL_MS + 1L,
		)
		val wal = unsignedWal.copy(integrityIdentity = unsignedWal.calculatedIntegrityIdentity())
		if (insertWal) assertEquals(1L, database.sourceEventWalDao().insertIgnoringDuplicate(wal))
	}

	private suspend fun insertPlan(plan: WifiPlan) {
		val encoded = planCodec.encode(plan)
		database.sourcePlanStateDao().insertRevision(
			AcquisitionPlanRevisionEntity(
				revision = PLAN_REVISION,
				planId = "wifi-plan",
				createdAtMs = RUN_START_WALL_MS,
				status = "EFFECTIVE",
				sourcePolicyRevision = POLICY_REVISION,
			),
		)
		database.sourcePlanStateDao().insertDesiredPlans(
			listOf(
				SourceDesiredPlanEntity(
					revision = PLAN_REVISION,
					sourceKind = WIFI_SOURCE,
					payloadVersion = 1,
					payload = encoded.bytes,
					payloadChecksum = encoded.checksum,
				),
			),
		)
	}

	private suspend fun installPolicyAndConsent() {
		val policyDao = database.sourcePolicyDao()
		policyDao.ensureAuthority(
			SourcePolicyAuthorityEntity(
				bootstrapState = SourcePolicyAuthorityEntity.STATE_ACTIVE,
				currentPolicyRevision = POLICY_REVISION,
				legacySettingsFingerprint = null,
				updatedAtMs = RUN_START_WALL_MS,
			),
		)
		policyDao.insertPolicies(
			listOf(
				SourcePolicyEntity(
					policyRevision = POLICY_REVISION,
					sourceKind = WIFI_SOURCE,
					enabled = true,
					qosCode = QOS_CODE,
					locationMinTimeSeconds = null,
					locationMinDistanceMeters = null,
					locationRequiredAccuracyMeters = null,
					capturePersistenceEligible = true,
					controlPersistenceEligible = false,
					ambientPersistenceEligible = false,
					captureConsentEpoch = CONSENT_EPOCH,
					controlConsentEpoch = null,
					ambientConsentEpoch = null,
					effectiveBootId = BOOT_ID,
					effectiveElapsedRealtimeNanos = POLICY_START_NANOS,
					effectiveWallTimeMs = RUN_START_WALL_MS,
					changeReason = "TEST",
				),
			),
		)
		policyDao.insertConsentEpochs(
			listOf(
				SourceConsentEpochEntity(
					sourceKind = WIFI_SOURCE,
					purpose = SourceBrokerPurpose.SESSION_CAPTURE,
					epoch = CONSENT_EPOCH,
					eligible = true,
					persistenceEligible = true,
					policyRevision = POLICY_REVISION,
					effectiveBootId = BOOT_ID,
					effectiveElapsedRealtimeNanos = POLICY_START_NANOS,
					effectiveWallTimeMs = RUN_START_WALL_MS,
					changeReason = "TEST",
				),
			),
		)
	}

	private suspend fun installSessionAndManifest(
		segmentId: Long,
		zoneId: String,
		candidateWriter: Boolean,
	) {
		database.sourceSessionDao().insertSession(
			LogicalTrackingSessionEntity(
				logicalTrackingId = LOGICAL_ID,
				state = "FINALIZED",
				lifecycleRevision = 2L,
				desiredPlanRevision = PLAN_REVISION,
				rolloutRevision = ROLLOUT_REVISION,
				startOrigin = START_ORIGIN,
				clockDomainId = BOOT_ID,
				startedAtMs = RUN_START_WALL_MS,
				startedElapsedNanos = RUN_START_NANOS,
				cutoffAtMs = SESSION_END_WALL_MS,
				cutoffElapsedNanos = SESSION_END_NANOS,
				completedAtMs = SESSION_END_WALL_MS,
				finalAdmissionOrdinal = 1L,
				failureCode = null,
				sessionMode = "MANUAL",
				currentManifestRevision = MANIFEST_REVISION,
				currentIntentRevision = 1L,
				currentServiceRunId = null,
				lifecycleLeaseGeneration = LEASE_GENERATION,
				lifecycleBootId = BOOT_ID,
				automationEpoch = null,
			),
		)
		database.sourceSessionDao().insertServiceRun(
			SourceServiceRunEntity(
				serviceRunId = RUN_ID,
				logicalTrackingId = LOGICAL_ID,
				state = "FINALIZED",
				desiredPlanRevision = PLAN_REVISION,
				rolloutRevision = ROLLOUT_REVISION,
				foregroundCapabilityFlags = 0L,
				startedAtMs = RUN_START_WALL_MS,
				startedElapsedNanos = RUN_START_NANOS,
				completedAtMs = SESSION_END_WALL_MS,
				completionReason = "USER_STOP",
				bootId = BOOT_ID,
				leaseGeneration = LEASE_GENERATION,
				startOrigin = START_ORIGIN,
				desiredForegroundCapabilityFlags = 0L,
				appliedForegroundCapabilityFlags = 0L,
				runtimeAcknowledgement = "STOP_ACCEPTED",
				runtimeFailureCode = null,
				runRevision = 2L,
				startDeliveryToken = "wifi-start-token",
				startCommandGeneration = 1L,
				preparedManifestRevision = MANIFEST_REVISION,
				preparedIntentRevision = 1L,
				androidDeliveryState = "FOREGROUND_ACCEPTED",
				androidDeliveryUpdatedAtMs = RUN_START_WALL_MS,
				startIsUserInitiated = true,
				startIsAmbient = false,
				sessionSegmentId = segmentId,
				presentationAcknowledgement = SourceServiceRunEntity.PRESENTATION_QUIESCED,
				presentationAcknowledgedAtMs = SESSION_END_WALL_MS,
			),
		)
		val source = SessionManifestSourceEntity(
			logicalTrackingId = LOGICAL_ID,
			manifestRevision = MANIFEST_REVISION,
			sourceKind = WIFI_SOURCE,
			purpose = SourceBrokerPurpose.SESSION_CAPTURE,
			consentEpoch = CONSENT_EPOCH,
			persistenceEligible = true,
			qosCode = QOS_CODE,
			outputDestination = if (candidateWriter) {
				SourceDestinationOwnerEntity.DESTINATION_SESSION_WIFI
			} else null,
			writerOwner = if (candidateWriter) {
				SourceDestinationOwnerEntity.OWNER_WIFI_SESSION_FACTS
			} else null,
			writerOwnerGeneration = if (candidateWriter) {
				SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION
			} else null,
			writerProjectionId = if (candidateWriter) {
				SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_ID
			} else null,
			writerProjectionVersion = if (candidateWriter) {
				SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_VERSION
			} else null,
			writerBindingGeneration = if (candidateWriter) {
				SourceDestinationOwnerEntity.WIFI_FACT_BINDING_GENERATION
			} else null,
		)
		val unsigned = SessionManifestVersionEntity(
			logicalTrackingId = LOGICAL_ID,
			manifestRevision = MANIFEST_REVISION,
			serviceRunId = RUN_ID,
			sessionMode = "MANUAL",
			sourcePolicyRevision = POLICY_REVISION,
			acquisitionPlanRevision = PLAN_REVISION,
			rolloutRevision = ROLLOUT_REVISION,
			startOrigin = START_ORIGIN,
			effectiveBootId = BOOT_ID,
			effectiveElapsedRealtimeNanos = RUN_START_NANOS,
			effectiveWallTimeMs = RUN_START_WALL_MS,
			zoneId = zoneId,
			automationEpoch = null,
			changeReason = "MANUAL_START",
			manifestChecksum = "",
		)
		database.sourceSessionDao().insertManifest(
			unsigned.copy(manifestChecksum = SessionManifestIntegrity.compute(unsigned, listOf(source))),
		)
		database.sourceSessionDao().insertManifestSources(listOf(source))
	}

	private fun registration(
		fingerprint: String = wifiPlan().physicalConfigurationFingerprint(),
		generation: Long = REGISTRATION_GENERATION,
		sourceInstance: String = SOURCE_INSTANCE,
	) = ProviderRegistrationGenerationEntity(
		sourceKind = WIFI_SOURCE,
		registrationGeneration = generation,
		sourceInstanceId = sourceInstance,
		ownerScope = "source-broker:$WIFI_SOURCE",
		clockDomainId = BOOT_ID,
		physicalConfigurationFingerprint = fingerprint,
		collectedDataEpoch = 0L,
		providerResidency = ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND,
		providerProcessIncarnationId = "process-$generation",
		status = ProviderRegistrationGenerationEntity.STATUS_RETIRED,
		reservedAtMs = RUN_START_WALL_MS,
		reservedElapsedRealtimeNanos = RUN_START_NANOS,
		acceptedAtMs = RUN_START_WALL_MS,
		acceptedElapsedRealtimeNanos = REGISTRATION_START_NANOS,
		retiredAtMs = SESSION_END_WALL_MS,
		retiredElapsedRealtimeNanos = REGISTRATION_END_NANOS,
		failureCode = null,
		captureCallbackBarrierAuthorizationRevision = AUTHORIZATION_REVISION,
	)

	private fun demand(
		index: Int = 0,
		minimumAcquisitionSpec: String = "wifi:v1:required=BROADCAST_CALLBACK",
		requestedDeliveryLatencyMs: Long? = null,
	) = SourceDemandEntity(
		demandId = "$DEMAND_ID-$index",
		consumerId = "session:$LOGICAL_ID",
		sourceKind = WIFI_SOURCE,
		purpose = SourceBrokerPurpose.SESSION_CAPTURE,
		logicalTrackingId = LOGICAL_ID,
		serviceRunId = RUN_ID,
		manifestRevision = MANIFEST_REVISION,
		lifecycleLeaseGeneration = LEASE_GENERATION,
		sourcePolicyRevision = POLICY_REVISION,
		consentEpoch = CONSENT_EPOCH,
		persistenceEligible = true,
		qosCode = QOS_CODE,
		minimumAcquisitionSpec = minimumAcquisitionSpec,
		adaptiveReductionAllowed = true,
		maximumAgeMs = 5 * 60_000L,
		desiredLatencyMs = Long.MAX_VALUE,
		requestedDeliveryLatencyMs = requestedDeliveryLatencyMs,
		requestedBootId = BOOT_ID,
		requestedElapsedRealtimeNanos = RUN_START_NANOS,
		requestedAtMs = RUN_START_WALL_MS,
		status = SourceDemandEntity.STATUS_RETIRED,
		retireBootId = BOOT_ID,
		retireElapsedRealtimeNanos = SESSION_END_NANOS,
		retiredAtMs = SESSION_END_WALL_MS,
	)

	private fun nonCaptureDemand(index: Int, purpose: String, qosCode: Int = QOS_OFF): SourceDemandEntity {
		val directPurpose = when (purpose) {
			SourceBrokerPurpose.CONTROL_AUTOSTART ->
				DirectSourceDemandPurpose.CONTROL_AUTOSTART
			SourceBrokerPurpose.AMBIENT_PRODUCT -> DirectSourceDemandPurpose.AMBIENT_PRODUCT
			else -> error("Unsupported noncapture test purpose")
		}
		val contract = SourceDemandContractFactory.forQos(SourceKind.WIFI, qosCode, directPurpose)
		val ambient = purpose == SourceBrokerPurpose.AMBIENT_PRODUCT
		return demand(index).copy(
			consumerId = "app:wifi-${purpose.lowercase()}",
			purpose = purpose,
			logicalTrackingId = null,
			serviceRunId = null,
			manifestRevision = null,
			lifecycleLeaseGeneration = null,
			sourcePolicyRevision = REVOKED_POLICY_REVISION,
			consentEpoch = if (ambient) AMBIENT_CONSENT_EPOCH else CONTROL_CONSENT_EPOCH,
			persistenceEligible = ambient,
			qosCode = qosCode,
			minimumAcquisitionSpec = contract.encodeFloor(),
			adaptiveReductionAllowed = contract.adaptiveReductionAllowed,
			maximumAgeMs = contract.maximumProviderItemAgeMs,
			desiredLatencyMs = contract.targetPlanningLatencyMs,
			requestedDeliveryLatencyMs = contract.requestedDeliveryLatencyMs,
			requestedElapsedRealtimeNanos = SESSION_END_NANOS + 1L,
			requestedAtMs = SESSION_END_WALL_MS + 1L,
			status = SourceDemandEntity.STATUS_ACTIVE,
			retireBootId = null,
			retireElapsedRealtimeNanos = null,
			retiredAtMs = null,
		)
	}

	private fun startAction(
		desiredPlanRevision: Long = PLAN_REVISION,
		actionId: String = "wifi-start-action",
		actionRevision: Long = 1L,
	) = LifecycleDesiredActionEntity(
		actionId = actionId,
		logicalTrackingId = LOGICAL_ID,
		serviceRunId = RUN_ID,
		manifestRevision = MANIFEST_REVISION,
		actionRevision = actionRevision,
		actionFamily = "SOURCE_RUNTIME",
		sourceKind = WIFI_SOURCE,
		desiredState = "STARTED",
		desiredPlanRevision = desiredPlanRevision,
		sourcePolicyRevision = POLICY_REVISION,
		consentEpoch = CONSENT_EPOCH,
		startOrigin = START_ORIGIN,
		bootId = BOOT_ID,
		leaseGeneration = LEASE_GENERATION,
		requestedAtMs = RUN_START_WALL_MS,
		requestedElapsedRealtimeNanos = RUN_START_NANOS,
		status = "START_ACCEPTED",
		attemptCount = 1,
		acknowledgedAtMs = RUN_START_WALL_MS + 1L,
		acknowledgedElapsedRealtimeNanos = PLAN_APPLICATION_ACK_NANOS,
		failureCode = null,
		retryTrigger = null,
		sourceInstanceId = SOURCE_INSTANCE,
		registrationGeneration = REGISTRATION_GENERATION,
	)

	private fun segment(runId: String) = SessionSegment(
		id = SEGMENT_ID,
		startTimeMs = RUN_START_WALL_MS,
		endTimeMs = SESSION_END_WALL_MS,
		distanceM = 0f,
		steps = null,
		primaryActivity = null,
		activityConfidence = null,
		sampleCount = 0,
		source = SegmentSource.USER_CREATED,
		inferenceVersion = null,
		createdAt = SESSION_END_WALL_MS,
		logicalTrackingId = LOGICAL_ID,
		serviceRunId = runId,
	)

	private fun wifiPlan() = WifiPlan(
		revision = PLAN_REVISION,
		mode = WifiMode.BROADCAST_DRIVEN,
		minimumAttemptIntervalMs = 60_000L,
		maximumAcceptableResultAgeMs = 1_000L,
		unchangedResultDedupeWindowMs = 120_000L,
		backoff = RetryBackoff(1_000L, 60_000L),
	)

	private fun accessPoints() = listOf(
		WifiAccessPointEvidence("", 2_412, -80, OBSERVED_START_NANOS),
		WifiAccessPointEvidence("", 5_180, -60, OBSERVED_END_NANOS),
	)

	private companion object {
		val EVENT_ID = SourceEventId("wifi-event-1")
		val WIFI_SOURCE = SourceKind.WIFI.stableCode
		const val LOGICAL_ID = "logical-wifi"
		const val RUN_ID = "run-wifi"
		const val REPLACEMENT_RUN_ID = "run-wifi-replacement"
		const val REPLACEMENT_ACTION_ID = "wifi-replacement-start-action"
		const val SOURCE_INSTANCE = "wifi-instance"
		const val DEMAND_ID = "wifi-demand"
		const val BOOT_ID = "boot-1"
		const val ZONE_ID = "Europe/Prague"
		const val PLAN_REVISION = 1L
		const val REPLACEMENT_PLAN_REVISION = 2L
		const val POLICY_REVISION = 1L
		const val REVOKED_POLICY_REVISION = 2L
		const val CONSENT_EPOCH = 1L
		const val REVOKED_CONSENT_EPOCH = 2L
		const val AMBIENT_CONSENT_EPOCH = 3L
		const val CONTROL_CONSENT_EPOCH = 4L
		const val MANIFEST_REVISION = 1L
		const val REPLACEMENT_MANIFEST_REVISION = 2L
		const val REPLACEMENT_INTENT_REVISION = 2L
		const val LEASE_GENERATION = 1L
		const val REGISTRATION_GENERATION = 1L
		const val REPLACEMENT_REGISTRATION_GENERATION = 2L
		const val AUTHORIZATION_REVISION = 1L
		const val NONCAPTURE_REGISTRATION_GENERATION = 100L
		const val NONCAPTURE_AUTHORIZATION_REVISION = 100L
		const val ROLLOUT_REVISION = 1L
		const val SEGMENT_ID = 1L
		const val REPLACEMENT_SEGMENT_ID = 2L
		const val SOURCE_SEQUENCE = 1L
		const val PAYLOAD_VERSION = LEGACY_WIFI_WAL_PAYLOAD_VERSION
		const val QOS_CODE = 2
		const val QOS_OFF = 0
		const val START_ORIGIN = "MANUAL_FOREGROUND_START"
		const val RUN_START_NANOS = 1_000_000_000L
		const val POLICY_START_NANOS = 1_000_000_000L
		const val REGISTRATION_START_NANOS = 1_100_000_000L
		const val AUTHORIZATION_START_NANOS = 1_200_000_000L
		const val PLAN_APPLICATION_ACK_NANOS = 1_300_000_000L
		const val OBSERVED_START_NANOS = 1_500_000_000L
		const val OBSERVED_END_NANOS = 1_600_000_000L
		const val RECEIVED_NANOS = 1_700_000_000L
		const val REGISTRATION_END_NANOS = 3_000_000_000L
		const val SESSION_END_NANOS = 4_000_000_000L
		const val NONCAPTURE_REGISTRATION_START_NANOS = 4_100_000_000L
		const val NONCAPTURE_AUTHORIZATION_START_NANOS = 4_150_000_000L
		const val NONCAPTURE_OBSERVED_START_NANOS = 4_200_000_000L
		const val NONCAPTURE_OBSERVED_END_NANOS = 4_300_000_000L
		const val NONCAPTURE_RECEIVED_NANOS = 4_400_000_000L
		const val RUN_START_WALL_MS = 1_699_999_999_000L
		const val OBSERVED_WALL_MS = 1_700_000_000_000L
		const val SESSION_END_WALL_MS = 1_700_000_003_000L
		const val DELETE_AT_MS = 1_700_000_020_000L
		const val NONCAPTURE_WALL_TIME_MS = DELETE_AT_MS + 1_000L
		const val WALL_UNCERTAINTY_MS = 1L
		const val NANOS_PER_MILLISECOND = 1_000_000L
	}
}
