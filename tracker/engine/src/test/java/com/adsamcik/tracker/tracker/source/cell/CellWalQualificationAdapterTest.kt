package com.adsamcik.tracker.tracker.source.cell

import io.kotest.matchers.shouldBe

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.CellCapturedRetentionResult
import com.adsamcik.tracker.shared.base.database.CellCapturedSourceDeletionResult
import com.adsamcik.tracker.shared.base.database.deleteCapturedCellFactsAfterConsentReset
import com.adsamcik.tracker.shared.base.database.pruneCapturedCellFactsAffectedByRetentionFloor
import com.adsamcik.tracker.shared.base.database.deleteSelectedCapturedCellFactsInTransaction
import com.adsamcik.tracker.shared.base.database.ReadLocalPortableCapturedCellResult
import com.adsamcik.tracker.shared.base.database.RoomReadLocalPortableCapturedCell
import com.adsamcik.tracker.shared.base.database.AmbientRadioRetentionDecision
import com.adsamcik.tracker.shared.base.database.applyAmbientCellRetentionDecision
import com.adsamcik.tracker.shared.base.database.data.AmbientCellAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellAuthorityIntegrity
import com.adsamcik.tracker.shared.base.database.data.SourceRegistrationStateEntity
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductLaneExecutionAuthority
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleSnapshot
import com.adsamcik.tracker.shared.base.database.data.AcquisitionPlanRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.CellCaptureDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedFactRevisionIntegrity
import com.adsamcik.tracker.shared.base.database.data.CellWalCarrierAuthentication
import com.adsamcik.tracker.shared.base.database.data.CellWalCarrierAuthenticator
import com.adsamcik.tracker.shared.base.database.data.CELL_WAL_PAYLOAD_VERSION
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
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
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.time.BootClockDomainProvider
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.shared.model.tracking.TrackingPurpose
import com.adsamcik.tracker.shared.model.tracking.TrackingSource
import com.adsamcik.tracker.stats.api.repository.RecordingProofState
import com.adsamcik.tracker.stats.api.repository.SourceEvidenceIdentity
import com.adsamcik.tracker.stats.api.repository.SourceProofCompleteness
import com.adsamcik.tracker.stats.api.repository.SourceProofCoverage
import com.adsamcik.tracker.stats.api.repository.SourceProofKey
import com.adsamcik.tracker.stats.api.repository.SourceProofLineage
import com.adsamcik.tracker.stats.api.repository.SourceProofReason
import com.adsamcik.tracker.stats.api.repository.SourceProofResult
import com.adsamcik.tracker.stats.api.repository.SourceProofScope
import com.adsamcik.tracker.stats.api.repository.SourceProofStage
import com.adsamcik.tracker.tracker.source.activity.ActivityCapturedWalAdmissionAdapter
import com.adsamcik.tracker.tracker.source.coordinator.ExecutableSourceLaneCatalog
import com.adsamcik.tracker.tracker.source.coordinator.CaptureReachabilityMode
import com.adsamcik.tracker.tracker.source.coordinator.RoomTrackingRolloutStateStore
import com.adsamcik.tracker.tracker.source.coordinator.TrackingRolloutState
import com.adsamcik.tracker.tracker.source.coordinator.SourcePlanCodec
import com.adsamcik.tracker.tracker.source.ingress.DefaultSourcePayloadCodec
import com.adsamcik.tracker.tracker.source.ingress.RoomDurableSourceIngress
import com.adsamcik.tracker.tracker.source.ingress.DeliveryAdmissionResult
import com.adsamcik.tracker.tracker.source.lifecycle.DefaultSourceLifecycleProofAdapterFactory
import com.adsamcik.tracker.tracker.source.ambient.cell.AmbientCellFactProjector
import com.adsamcik.tracker.tracker.source.ambient.cell.AmbientCellProjectionResult
import com.adsamcik.tracker.tracker.source.location.LocationWalQualificationAdapter
import com.adsamcik.tracker.tracker.source.model.CellMode
import com.adsamcik.tracker.tracker.source.model.CellObservationEvidence
import com.adsamcik.tracker.tracker.source.model.CellPlan
import com.adsamcik.tracker.tracker.source.model.CellRefreshOutcome
import com.adsamcik.tracker.tracker.source.model.CellSnapshotPayload
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.RADIO_OBSERVATION_ZONE_PAYLOAD_VERSION
import com.adsamcik.tracker.tracker.source.model.RetryBackoff
import com.adsamcik.tracker.tracker.source.model.SourceEventId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceEvidenceCandidate
import com.adsamcik.tracker.tracker.source.model.SourceDeliveryCandidate
import com.adsamcik.tracker.tracker.source.model.SourceDeliveryUnit
import com.adsamcik.tracker.tracker.source.model.SourceQuality
import com.adsamcik.tracker.tracker.source.model.physicalConfigurationFingerprint
import com.adsamcik.tracker.tracker.source.runtime.cellProviderDeliveryIdentity
import com.adsamcik.tracker.tracker.source.wifi.WifiWalQualificationAdapter
import io.mockk.mockk
import io.mockk.coEvery
import javax.inject.Provider
import java.time.Instant
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CellWalQualificationAdapterTest {
	private lateinit var database: AppDatabase
	private val payloadCodec = DefaultSourcePayloadCodec()
	private val planCodec = SourcePlanCodec()
	private lateinit var subject: CellWalQualificationAdapter
	private lateinit var writer: CellCapturedFactWriter

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		subject = CellWalQualificationAdapter(
			database,
			planCodec,
			BootClockDomainProvider { BOOT_ID },
		)
		writer = CellCapturedFactWriter(database, subject)
	}

	@After
	fun tearDown() {
		database.close()
	}

	@Test
	fun `exact first Cell WAL sequence zero and durable authority produce identity-free fact`() = runTest {
		installValidFixture()

		val evaluated = assertIs<CellWalAdapterResult.Evaluated>(subject.qualify(EVENT_ID))
		val fact = assertIs<CellCapturedFactClassification.FreshChanged>(evaluated.classification).fact

		assertEquals(setOf(SourceKind.CELL), fact.authority.capturedSources)
		assertEquals(emptySet(), fact.authority.controlSources)
		assertEquals(RUN_ID, fact.authority.serviceRunId.value)
		assertEquals(SEGMENT_ID, fact.authority.sessionSegmentId)
		assertEquals(PLAN_REVISION, fact.authority.configurationRevision)
		assertEquals(SOURCE_SEQUENCE, fact.evidenceBinding.sourceSequence)
		assertEquals(CellSubscriptionCompleteness.UNKNOWN, fact.coverage.subscriptionCompleteness)
		assertEquals(null, fact.coverage.expectedSubscriptionCount)
		assertEquals(null, fact.coverage.observedSubscriptionCount)
		assertEquals(2, fact.aggregate.observationCount)
		val expectedDay = Instant.ofEpochMilli(OBSERVED_WALL_MS)
			.atZone(ZoneId.of(ZONE_ID)).toLocalDate().toEpochDay()
		assertEquals(expectedDay, fact.authority.structuralEpochDay)
		assertEquals(1L, database.sourceEventWalDao().countAll())
	}

	@Test
	fun `real producer unit and nonnegative source sequence are mandatory`() = runTest {
		installValidFixture(sourceSequence = -1L)
		assertEquals(
			CellWalAdapterResult.Rejected(CellWalAdapterRejection.MALFORMED_PRODUCER_DELIVERY),
			subject.qualify(EVENT_ID),
		)
	}

	@Test
	fun `canonical WAL payload and provider delivery identity are recomputed`() = runTest {
		installValidFixture(deliveryIdentityOverride = "a".repeat(64))
		assertEquals(
			CellWalAdapterResult.Rejected(CellWalAdapterRejection.DELIVERY_IDENTITY_MISMATCH),
			subject.qualify(EVENT_ID),
		)

		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_event_wal SET payload = X'00' WHERE event_id = ?",
			arrayOf(EVENT_ID.value),
		)
		assertEquals(
			CellWalAdapterResult.Rejected(CellWalAdapterRejection.WAL_INTEGRITY_MISMATCH),
			subject.qualify(EVENT_ID),
		)
	}

	@Test
	fun `canonical WAL decoder rejects validly resigned trailing bytes`() = runTest {
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
			CellWalAdapterResult.Rejected(CellWalAdapterRejection.PAYLOAD_MISMATCH),
			subject.qualify(EVENT_ID),
		)
	}

	@Test
	fun `one-unit producer rejects a second WAL sibling`() = runTest {
		installValidFixture()
		val original = requireNotNull(database.sourceEventWalDao().getByEventId(EVENT_ID.value))
		val unsignedSibling = original.copy(
			admissionOrdinal = 0L,
			eventId = "cell-event-sibling",
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
			CellWalAdapterResult.Rejected(CellWalAdapterRejection.WAL_INTEGRITY_MISMATCH),
			subject.qualify(EVENT_ID),
		)
	}

	@Test
	fun `authorization successor is exact despite interleaved source-global revisions`() = runTest {
		installValidFixture()
		database.sourceBrokerDao().insertRegistration(
			registration(generation = 2L, sourceInstance = "other-cell-instance"),
		)
		database.sourceBrokerDao().insertAuthorizations(
			SourceBrokerAuthorization.rows(
				sourceKind = CELL_SOURCE,
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
				sourceKind = CELL_SOURCE,
				registrationGeneration = REGISTRATION_GENERATION,
				authorizationRevision = 3L,
				demands = emptyList(),
				effectiveBootId = BOOT_ID,
				effectiveElapsedRealtimeNanos = OBSERVED_END_NANOS + 200L,
				effectiveWallTimeMs = OBSERVED_WALL_MS + 1L,
			),
		)

		val evaluated = assertIs<CellWalAdapterResult.Evaluated>(subject.qualify(EVENT_ID))
		assertIs<CellCapturedFactClassification.FreshChanged>(evaluated.classification)
	}

	@Test
	fun `retention evaluates the oldest provider wall uncertainty bound`() = runTest {
		val providerSpanMs = (OBSERVED_END_NANOS - OBSERVED_START_NANOS) / 1_000_000L
		val earliestPossibleWall = OBSERVED_WALL_MS - providerSpanMs - WALL_UNCERTAINTY_MS
		installValidFixture(
			evidenceState = SourceEvidenceState(retainedFromMs = earliestPossibleWall + 1L),
		)

		assertEquals(
			CellWalAdapterResult.Rejected(CellWalAdapterRejection.BEFORE_RETENTION_FLOOR),
			subject.qualify(EVENT_ID),
		)
	}

	@Test
	fun `provider wall uncertainty spanning civil days is not assigned to one day`() = runTest {
		installValidFixture(
			// The fixture's observation is 22:13:20 UTC. This offset puts the same
			// authorized observation exactly on a local midnight boundary.
			zoneId = "UTC+01:46:40",
		)

		assertEquals(
			CellWalAdapterResult.Rejected(CellWalAdapterRejection.STRUCTURAL_DAY_UNVERIFIABLE),
			subject.qualify(EVENT_ID),
		)
	}

	@Test
	fun `reverse run segment binding is mandatory`() = runTest {
		installValidFixture(segmentRunId = "other-run")

		assertEquals(
			CellWalAdapterResult.Rejected(CellWalAdapterRejection.SEGMENT_MISMATCH),
			subject.qualify(EVENT_ID),
		)
	}

	@Test
	fun `selected deletion fence wins after its exact Cell segment is removed`() = runTest {
		installValidFixture()
		database.sourceDeletionFenceDao().insertIfAbsent(
			SourceDeletionFenceEntity.createLogicalServiceRun(
				sourceKind = CELL_SOURCE,
				purpose = SourceBrokerPurpose.SESSION_CAPTURE,
				logicalTrackingId = LOGICAL_ID,
				serviceRunId = RUN_ID,
				fenceGeneration = 1L,
				collectedDataEpoch = 0L,
				deletedAtMs = SESSION_END_WALL_MS,
			),
		)
		database.sessionSegmentDao().deleteExact(SEGMENT_ID, LOGICAL_ID, RUN_ID)

		assertEquals(
			CellWalAdapterResult.Rejected(CellWalAdapterRejection.DELETED_SCOPE),
			subject.qualify(EVENT_ID),
		)
	}

	@Test
	fun `missing Cell segment without a deletion fence remains corruption`() = runTest {
		installValidFixture()
		database.sessionSegmentDao().deleteExact(SEGMENT_ID, LOGICAL_ID, RUN_ID)

		assertEquals(
			CellWalAdapterResult.Rejected(CellWalAdapterRejection.MISSING_SEGMENT),
			subject.qualify(EVENT_ID),
		)
	}

	@Test
	fun `dormant candidate writer persists first source sequence zero atomically`() = runTest {
		installValidFixture(candidateWriter = true)
		val beforeEvidenceRevision = requireNotNull(database.sourceEvidenceStateDao().get()).revision

		val applied = assertIs<CellCapturedWriteResult.Applied>(writer.write(EVENT_ID))
		val cursor = requireNotNull(
			database.cellCapturedFactDao().cursor(
				SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID,
				SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION,
				applied.logicalFactId,
			),
		)
		val fact = requireNotNull(
			database.cellCapturedFactDao().revision(
				SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID,
				SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION,
				applied.logicalFactId,
				applied.semanticRevision,
			),
		)

		assertEquals(1L, database.cellCapturedFactDao().revisionCount())
		assertEquals(fact.semanticRevision, cursor.latestSemanticRevision)
		assertEquals(fact.mutationId, cursor.latestMutationId)
		assertEquals(fact.effectChecksum, cursor.latestEffectChecksum)
		assertEquals(0L, fact.sourceSequence)
		assertEquals(0L, fact.scopeDeletionGeneration)
		assertEquals("UNKNOWN", fact.subscriptionCompleteness)
		assertEquals(beforeEvidenceRevision + 1L, database.sourceEvidenceStateDao().get()?.revision)
	}

	@Test
	fun `version six Cell writer saves only authenticated tower readings under exact session fact`() = runTest {
		val towers = observations().mapIndexed { index, observation ->
			observation.copy(identifierToken = "310:260:123:${400 + index}")
		}
		installValidFixture(
			candidateWriter = true,
			observations = towers,
			payloadVersion = CELL_WAL_PAYLOAD_VERSION,
		)

		val applied = assertIs<CellCapturedWriteResult.Applied>(writer.write(EVENT_ID))
		val children = database.radioObservationChildDao().cellSessionChildren(
			SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID,
			SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION,
			applied.logicalFactId,
			applied.semanticRevision,
			10,
		)
		assertEquals(towers.map(CellObservationEvidence::identifierToken),
			children.map { it.towerIdentity })
		assertEquals(listOf(0, 1), children.map { it.itemIndex })
		val fact = fact(applied.logicalFactId, applied.semanticRevision)
		val wal = requireNotNull(database.sourceEventWalDao().getByEventId(EVENT_ID.value))
		assertIs<CellWalCarrierAuthentication.Authenticated>(
			CellWalCarrierAuthenticator.authenticate(fact, wal, children))
		assertIs<CellWalCarrierAuthentication.Rejected>(
			CellWalCarrierAuthenticator.authenticate(fact, wal, children.dropLast(1)))
		assertIs<CellWalCarrierAuthentication.Rejected>(
			CellWalCarrierAuthenticator.authenticate(fact, wal,
				children.map { it.copy(towerIdentity = "changed") }))
	}

	@Test
	fun `version five Cell history remains valid without tower children`() = runTest {
		installValidFixture(candidateWriter = true, payloadVersion = PAYLOAD_VERSION)
		val applied = assertIs<CellCapturedWriteResult.Applied>(writer.write(EVENT_ID))
		val fact = fact(applied.logicalFactId, applied.semanticRevision)
		val wal = requireNotNull(database.sourceEventWalDao().getByEventId(EVENT_ID.value))
		assertIs<CellWalCarrierAuthentication.Authenticated>(
			CellWalCarrierAuthenticator.authenticate(fact, wal, emptyList()))
	}

	@Test
	fun `Cell child wall uncertainty includes fractional millisecond rounding`() = runTest {
		val towers = observations().mapIndexed { index, item ->
			item.copy(
				providerTimestampNanos = requireNotNull(item.providerTimestampNanos) +
					if (index == 0) 1L else 0L,
				identifierToken = "tower-$index",
			)
		}
		installValidFixture(candidateWriter = true, observations = towers,
			payloadVersion = CELL_WAL_PAYLOAD_VERSION)
		val applied = assertIs<CellCapturedWriteResult.Applied>(writer.write(EVENT_ID))
		val children = database.radioObservationChildDao().cellSessionChildren(
			SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID,
			SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION,
			applied.logicalFactId, applied.semanticRevision, 10,
		)
		val wal = requireNotNull(database.sourceEventWalDao().getByEventId(EVENT_ID.value))
		val first = children.single { it.providerTimestampNanos == towers[0].providerTimestampNanos }
		assertEquals(requireNotNull(wal.wallTimeUncertaintyMs) + 1L, first.wallTimeUncertaintyMs)
	}

	@Test
	fun `session keeps earlier authorized tower reading when callback authorization changes`() = runTest {
		installValidFixture(
			candidateWriter = true,
			observations = observations().mapIndexed { index, item ->
				item.copy(identifierToken = "310:260:123:${500 + index}")
			},
			payloadVersion = CELL_WAL_PAYLOAD_VERSION,
		)
		val successor = SourceBrokerAuthorization.rows(
			sourceKind = CELL_SOURCE,
			registrationGeneration = REGISTRATION_GENERATION,
			authorizationRevision = 2L,
			demands = listOf(demand()),
			effectiveBootId = BOOT_ID,
			effectiveElapsedRealtimeNanos = 1_550_000_000L,
			effectiveWallTimeMs = OBSERVED_WALL_MS,
		)
		database.sourceBrokerDao().insertAuthorizations(successor)
		val original = requireNotNull(database.sourceEventWalDao().getByEventId(EVENT_ID.value))
		val unsigned = original.copy(
			authorizationRevision = 2L,
			authorizationFingerprint = successor.first().authorizationFingerprint,
			integrityIdentity = SourceEventWalEntity.LEGACY_PENDING_CHECKSUM,
		)
		val updated = unsigned.copy(integrityIdentity = unsigned.calculatedIntegrityIdentity())
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_event_wal SET authorization_revision = ?, " +
				"authorization_fingerprint = ?, integrity_identity = ? WHERE event_id = ?",
			arrayOf(2L, updated.authorizationFingerprint, updated.integrityIdentity, EVENT_ID.value),
		)

		val applied = assertIs<CellCapturedWriteResult.Applied>(writer.write(EVENT_ID))
		val fact = requireNotNull(database.cellCapturedFactDao().revision(
			SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID,
			SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION,
			applied.logicalFactId, applied.semanticRevision,
		))
		assertEquals(AUTHORIZATION_START_NANOS, fact.authorizationEffectStartNanos)
		assertEquals(2, database.radioObservationChildDao().cellSessionChildren(
			fact.writerProjectionId, fact.writerProjectionVersion,
			fact.logicalFactId, fact.semanticRevision, 10,
		).size)
		assertIs<CellCapturedWriteResult.Unchanged>(writer.write(EVENT_ID))
	}

	@Test
	fun `version six new Cell capture cannot claim an earlier tower reading`() = runTest {
		installValidFixture(candidateWriter = true, payloadVersion = CELL_WAL_PAYLOAD_VERSION)
		database.openHelper.writableDatabase.execSQL("DELETE FROM source_authorization")
		database.sourceBrokerDao().insertAuthorizations(SourceBrokerAuthorization.rows(
			CELL_SOURCE, REGISTRATION_GENERATION, AUTHORIZATION_REVISION, emptyList(),
			BOOT_ID, AUTHORIZATION_START_NANOS, RUN_START_WALL_MS,
		))
		val joinedAt = OBSERVED_START_NANOS + 50_000_000L
		rotatePerReadingCallback(joinedAt)

		val applied = assertIs<CellCapturedWriteResult.Applied>(writer.write(EVENT_ID))
		val saved = fact(applied.logicalFactId, applied.semanticRevision)
		assertEquals(1, saved.acceptedChildCount)
		assertEquals(1, saved.staleChildCount)
		assertEquals(joinedAt, saved.authorizationEffectStartNanos)
		assertEquals(listOf(1), savedChildren(saved).map { it.itemIndex })
		assertPerReadingCellRetentionAudit()
	}

	@Test
	fun `version six cached Cell reading before registration preserves its valid sibling`() = runTest {
		installValidFixture(candidateWriter = true, payloadVersion = CELL_WAL_PAYLOAD_VERSION,
			observations = observations().mapIndexed { index, item ->
				if (index == 0) item.copy(providerTimestampNanos = RUN_START_NANOS - 100_000_000L) else item
			})
		val applied = assertIs<CellCapturedWriteResult.Applied>(writer.write(EVENT_ID))
		val saved = fact(applied.logicalFactId, applied.semanticRevision)
		assertEquals(1, saved.acceptedChildCount)
		assertEquals(1, saved.staleChildCount)
		assertEquals(listOf(1), savedChildren(saved).map { it.itemIndex })
		assertPerReadingCellRetentionAudit()
	}

	@Test
	fun `version six Cell reading after retirement preserves its earlier valid sibling`() = runTest {
		installValidFixture(candidateWriter = true, payloadVersion = CELL_WAL_PAYLOAD_VERSION,
			observations = observations().mapIndexed { index, item ->
				if (index == 0) item.copy(providerTimestampNanos = OBSERVED_START_NANOS + 1L) else item
			})
		val retiredAt = OBSERVED_START_NANOS + 50_000_000L
		database.openHelper.writableDatabase.execSQL(
			"UPDATE provider_registration_generation SET retired_elapsed_realtime_nanos = ?, " +
				"retired_at_ms = ? WHERE source_kind = ? AND registration_generation = ?",
			arrayOf(retiredAt, OBSERVED_WALL_MS - 50L, CELL_SOURCE, REGISTRATION_GENERATION))

		val applied = assertIs<CellCapturedWriteResult.Applied>(writer.write(EVENT_ID))
		val saved = fact(applied.logicalFactId, applied.semanticRevision)
		assertEquals(1, saved.acceptedChildCount)
		assertEquals(1, saved.staleChildCount)
		assertEquals(listOf(0), savedChildren(saved).map { it.itemIndex })
		assertEquals(retiredAt, saved.providerAcceptanceEndNanos)
		assertEquals(WALL_UNCERTAINTY_MS + 1L, saved.wallTimeUncertaintyMs)
		assertEquals(saved.wallTimeUncertaintyMs, savedChildren(saved).single().wallTimeUncertaintyMs)
		assertIs<CellCapturedWriteResult.Unchanged>(writer.write(EVENT_ID))
		assertPerReadingCellRetentionAudit()
	}

	@Test
	fun `version six Cell parent bounds include a rounded sibling close to the latest reading`() = runTest {
		installValidFixture(candidateWriter = true, payloadVersion = CELL_WAL_PAYLOAD_VERSION,
			observations = observations().mapIndexed { index, item ->
				if (index == 0) item.copy(providerTimestampNanos = OBSERVED_END_NANOS - 1L) else item
			})
		val applied = assertIs<CellCapturedWriteResult.Applied>(writer.write(EVENT_ID))
		val saved = fact(applied.logicalFactId, applied.semanticRevision)
		assertEquals(2, saved.acceptedChildCount)
		assertEquals(WALL_UNCERTAINTY_MS + 1L, saved.wallTimeUncertaintyMs)
		assertEquals(saved.observedWallTimeMs + saved.wallTimeUncertaintyMs,
			savedChildren(saved).maxOf { it.observedWallTimeMs + it.wallTimeUncertaintyMs })
		assertIs<CellCapturedWriteResult.Unchanged>(writer.write(EVENT_ID))
		assertPerReadingCellRetentionAudit()
	}

	@Test
	fun `version six Cell callback must carry the authorization effective at receipt`() = runTest {
		installValidFixture(payloadVersion = CELL_WAL_PAYLOAD_VERSION)
		database.sourceBrokerDao().insertAuthorizations(SourceBrokerAuthorization.rows(
			CELL_SOURCE, REGISTRATION_GENERATION, 2L, emptyList(), BOOT_ID,
			OBSERVED_END_NANOS + 1L, OBSERVED_WALL_MS,
		))
		assertEquals(CellWalAdapterResult.Rejected(CellWalAdapterRejection.AUTHORIZATION_MISMATCH),
			subject.qualify(EVENT_ID))
	}

	@Test
	fun `version six Cell retention removes tower children with their parent fact`() = runTest {
		installValidFixture(candidateWriter = true, payloadVersion = CELL_WAL_PAYLOAD_VERSION)
		val applied = assertIs<CellCapturedWriteResult.Applied>(writer.write(EVENT_ID))
		val saved = fact(applied.logicalFactId, applied.semanticRevision)
		assertEquals(2, savedChildren(saved).size)
		val floor = OBSERVED_WALL_MS + 1L
		val markedAt = OBSERVED_WALL_MS + 10_000L
		database.sourceEvidenceStateDao().updateLifecycle(0L, floor, markedAt)
		assertIs<CellCapturedRetentionResult.Pruned>(database.pruneCapturedCellFactsAffectedByRetentionFloor(
			floor, 0L, 0L, markedAt))
		assertEquals(emptyList(), savedChildren(saved))
		assertEquals(0L, database.cellCapturedFactDao().revisionCount())
	}

	private suspend fun savedChildren(saved: CellCapturedFactRevisionEntity) =
		database.radioObservationChildDao().cellSessionChildren(saved.writerProjectionId,
			saved.writerProjectionVersion, saved.logicalFactId, saved.semanticRevision, 10)

	private suspend fun assertPerReadingCellRetentionAudit() {
		val floor = OBSERVED_WALL_MS - 1_000L
		val markedAt = OBSERVED_WALL_MS + 10_000L
		database.sourceEvidenceStateDao().updateLifecycle(0L, floor, markedAt)
		assertEquals(CellCapturedRetentionResult.NoChange,
			database.pruneCapturedCellFactsAffectedByRetentionFloor(floor, 0L, 0L, markedAt))
	}

	private suspend fun rotatePerReadingCallback(effectiveNanos: Long) {
		val rows = SourceBrokerAuthorization.rows(CELL_SOURCE, REGISTRATION_GENERATION, 2L,
			listOf(demand()), BOOT_ID, effectiveNanos, OBSERVED_WALL_MS)
		database.sourceBrokerDao().insertAuthorizations(rows)
		val wal = requireNotNull(database.sourceEventWalDao().getByEventId(EVENT_ID.value))
		val unsigned = wal.copy(authorizationRevision = 2L,
			authorizationFingerprint = rows.first().authorizationFingerprint,
			authorizationPurposeEligibilityMask = rows.first().purposeEligibilityMask)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_event_wal SET authorization_revision = ?, authorization_fingerprint = ?, " +
				"authorization_purpose_eligibility_mask = ?, integrity_identity = ? WHERE event_id = ?",
			arrayOf(2L, unsigned.authorizationFingerprint, unsigned.authorizationPurposeEligibilityMask,
				unsigned.calculatedIntegrityIdentity(), EVENT_ID.value))
	}

	@Test
	fun `canonical Cell lane commits one authenticated fact and both cursors`() = runTest {
		installValidFixture(candidateWriter = true)
		installCellLane(SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL)
		val lane = CellSessionFactProjectionLane(database, subject, writer)

		val result = assertIs<CellSessionFactDrainResult.Complete>(lane.drainAvailable())

		assertEquals(1L, result.lastCompletedOrdinal)
		assertEquals(1, result.factsInserted)
		assertEquals(1, result.eventsValidated)
		assertEquals(1L, database.cellCapturedFactDao().revisionCount())
		assertEquals(1L, database.cellCapturedFactDao().cursorCount())
		assertEquals(
			1L,
			database.sourceProjectionStateDao().activeProductLane(CELL_SOURCE)
				?.contiguousAdmissionOrdinal,
		)
	}

	@Test
	fun `shadow Cell lane validates without invoking the canonical writer`() = runTest {
		installValidFixture(candidateWriter = false)
		installCellLane(SourceProductProjectionLaneEntity.STAGE_EVENT_SHADOW)

		val result = assertIs<CellSessionFactDrainResult.Complete>(
			CellSessionFactProjectionLane(database, subject, writer).drainAvailable(),
		)

		assertEquals(1, result.eventsValidated)
		assertEquals(0, result.factsInserted)
		assertEquals(0L, database.cellCapturedFactDao().revisionCount())
		assertEquals(0L, database.cellCapturedFactDao().cursorCount())
		assertEquals(
			1L,
			database.sourceProjectionStateDao().activeProductLane(CELL_SOURCE)
				?.contiguousAdmissionOrdinal,
		)
	}

	@Test
	fun `noncapture Cell purpose masks settle without captured history`() = runTest {
		installValidFixture(candidateWriter = true)
		rewriteWalPurpose(EVENT_ID, SourceBrokerPurpose.MASK_CONTROL_AUTOSTART)
		val shifted = observations().map { observation ->
			observation.copy(
				providerTimestampNanos = requireNotNull(observation.providerTimestampNanos) +
					SECOND_DELIVERY_SHIFT_NANOS,
			)
		}
		insertAdditionalWal(SECOND_EVENT_ID, shifted, SOURCE_SEQUENCE + 1L)
		rewriteWalPurpose(SECOND_EVENT_ID, SourceBrokerPurpose.MASK_AMBIENT_PRODUCT)
		installCellLane(SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL)

		val result = assertIs<CellSessionFactDrainResult.Complete>(
			CellSessionFactProjectionLane(database, subject, writer).drainAvailable(),
		)

		assertEquals(2L, result.lastCompletedOrdinal)
		assertEquals(0, result.eventsValidated)
		assertEquals(0, result.factsInserted)
		assertEquals(0L, database.cellCapturedFactDao().revisionCount())
		assertEquals(0L, database.cellCapturedFactDao().cursorCount())
	}

	@Test
	fun `corrupt noncapture purpose mask is terminal before lane cursor progress`() = runTest {
		installValidFixture(candidateWriter = true)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_event_wal SET authorization_purpose_eligibility_mask = ? " +
				"WHERE event_id = ?",
			arrayOf<Any?>(SourceBrokerPurpose.MASK_CONTROL_AUTOSTART, EVENT_ID.value),
		)
		installCellLane(SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL)

		val failed = assertTerminalCellLaneFailure(
			CellSessionFactProjectionLane(database, subject, writer),
		)

		assertEquals("CELL_PREFLIGHT_WAL_INTEGRITY_MISMATCH", failed.failureCode)
	}

	@Test
	fun `corrupt Cell epoch cannot release a terminal candidate on repeat`() = runTest {
		installValidFixture(candidateWriter = true)
		installCellLane(SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL)
		corruptWalPayload()
		val lane = CellSessionFactProjectionLane(database, subject, writer)
		assertTerminalCellLaneFailure(lane)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_event_wal SET captured_collected_data_epoch = " +
				"captured_collected_data_epoch + 1 WHERE event_id = ?",
			arrayOf(EVENT_ID.value),
		)

		val repeated = assertTerminalCellLaneFailure(lane)

		assertEquals("CELL_ADAPTER_WAL_INTEGRITY_MISMATCH", repeated.failureCode)
	}

	@Test
	fun `missing Cell WAL cannot release its terminal candidate without deletion high water`() = runTest {
		installValidFixture(candidateWriter = true)
		installCellLane(SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL)
		corruptWalPayload()
		val lane = CellSessionFactProjectionLane(database, subject, writer)
		assertTerminalCellLaneFailure(lane)
		database.openHelper.writableDatabase.execSQL(
			"DELETE FROM source_event_wal WHERE event_id = ?",
			arrayOf(EVENT_ID.value),
		)

		val repeated = assertTerminalCellLaneFailure(lane)

		assertEquals("CELL_ADAPTER_WAL_INTEGRITY_MISMATCH", repeated.failureCode)
	}

	@Test
	fun `corrupt Cell scope and time cannot release a terminal candidate on repeat`() = runTest {
		installValidFixture(candidateWriter = true)
		installCellLane(SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL)
		corruptWalPayload()
		val lane = CellSessionFactProjectionLane(database, subject, writer)
		assertTerminalCellLaneFailure(lane)
		val forgedLogicalId = "forged-cell-logical"
		val forgedRunId = "forged-cell-run"
		assertEquals(
			true,
			database.sourceDeletionFenceDao().insertIfAbsent(
				SourceDeletionFenceEntity.createLogicalServiceRun(
					sourceKind = CELL_SOURCE,
					purpose = SourceBrokerPurpose.SESSION_CAPTURE,
					logicalTrackingId = forgedLogicalId,
					serviceRunId = forgedRunId,
					fenceGeneration = 1L,
					collectedDataEpoch = 0L,
					deletedAtMs = SESSION_END_WALL_MS,
				),
			) != -1L,
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_event_wal SET logical_tracking_id = ?, service_run_id = ? " +
				"WHERE event_id = ?",
			arrayOf(forgedLogicalId, forgedRunId, EVENT_ID.value),
		)

		assertTerminalCellLaneFailure(lane)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_evidence_state SET retained_from_ms = ? WHERE id = 1",
			arrayOf(OBSERVED_WALL_MS + 1L),
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_event_wal SET acquired_at_ms = 0, wall_time_ms = 0 " +
				"WHERE event_id = ?",
			arrayOf(EVENT_ID.value),
		)

		val repeated = assertTerminalCellLaneFailure(lane)

		assertEquals("CELL_ADAPTER_WAL_INTEGRITY_MISMATCH", repeated.failureCode)
	}

	@Test
	fun `corrupt Cell WAL is terminal and deletion later settles only the lane cursor`() = runTest {
		installValidFixture(candidateWriter = true)
		installCellLane(SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_event_wal SET payload = X'00' WHERE event_id = ?",
			arrayOf(EVENT_ID.value),
		)
		val lane = CellSessionFactProjectionLane(database, subject, writer)

		val failed = assertIs<CellSessionFactDrainResult.Failed>(lane.drainAvailable())
		assertEquals(0L, failed.lastCompletedOrdinal)
		assertEquals(1L, failed.failedOrdinal)
		assertEquals("CELL_ADAPTER_WAL_INTEGRITY_MISMATCH", failed.failureCode)
		assertEquals(true, failed.terminal)
		assertEquals(0L, database.cellCapturedFactDao().revisionCount())
		assertEquals(0L, database.cellCapturedFactDao().cursorCount())
		assertEquals(0L, database.sourceProjectionStateDao().activeProductLane(CELL_SOURCE)
			?.contiguousAdmissionOrdinal)

		assertEquals(
			1,
			database.sourceEvidenceStateDao().updateAfterFullDeletion(
				epoch = 0L,
				retainedFromMs = null,
				deletedSourceEventHighWaterOrdinal = 1L,
				updatedAtMs = SESSION_END_WALL_MS,
			),
		)
		val settled = assertIs<CellSessionFactDrainResult.Complete>(lane.drainAvailable())
		assertEquals(1L, settled.lastCompletedOrdinal)
		assertEquals(0L, database.cellCapturedFactDao().revisionCount())
		assertEquals(0L, database.cellCapturedFactDao().cursorCount())
		assertEquals(
			null,
			database.sourceProjectionStateDao().failure(
				CellSessionFactProjectionLane.WRITER_ID,
				CellSessionFactProjectionLane.WRITER_VERSION,
				1L,
			),
		)
	}

	@Test
	fun `retryable Cell lane failure keeps every cursor and retries exactly`() = runTest {
		installValidFixture(candidateWriter = true)
		installCellLane(SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL)
		var fail = true
		val lane = CellSessionFactProjectionLane(
			database,
			subject,
			writer,
			writeCheckpoint = { _, checkpoint ->
				if (fail && checkpoint == CellCapturedWriteCheckpoint.QUALIFIED) {
					error("retry-cell")
				}
			},
		)

		val failed = assertIs<CellSessionFactDrainResult.Failed>(lane.drainAvailable())
		assertEquals(false, failed.terminal)
		assertEquals(0L, database.cellCapturedFactDao().revisionCount())
		assertEquals(0L, database.cellCapturedFactDao().cursorCount())
		assertEquals(0L, database.sourceProjectionStateDao().activeProductLane(CELL_SOURCE)
			?.contiguousAdmissionOrdinal)

		fail = false
		val recovered = assertIs<CellSessionFactDrainResult.Complete>(lane.drainAvailable())
		assertEquals(1, recovered.factsInserted)
		assertEquals(1L, database.cellCapturedFactDao().revisionCount())
		assertEquals(1L, database.cellCapturedFactDao().cursorCount())
		assertEquals(
			null,
			database.sourceProjectionStateDao().failure(
				CellSessionFactProjectionLane.WRITER_ID,
				CellSessionFactProjectionLane.WRITER_VERSION,
				1L,
			),
		)
	}

	@Test
	fun `Cell lane cancellation after fact insert rolls back fact and lane cursors`() = runTest {
		installValidFixture(candidateWriter = true)
		installCellLane(SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL)
		val lane = CellSessionFactProjectionLane(
			database,
			subject,
			writer,
			writeCheckpoint = { _, checkpoint ->
				if (checkpoint == CellCapturedWriteCheckpoint.REVISION_INSERTED) {
					throw CancellationException("cancel-cell-lane")
				}
			},
		)

		assertFailsWith<CancellationException> { lane.drainAvailable() }

		assertEquals(0L, database.cellCapturedFactDao().revisionCount())
		assertEquals(0L, database.cellCapturedFactDao().cursorCount())
		assertEquals(0L, database.sourceProjectionStateDao().activeProductLane(CELL_SOURCE)
			?.contiguousAdmissionOrdinal)
		assertEquals(
			null,
			database.sourceProjectionStateDao().failure(
				CellSessionFactProjectionLane.WRITER_ID,
				CellSessionFactProjectionLane.WRITER_VERSION,
				1L,
			),
		)
	}

	@Test
	fun `canonical Cell lane refuses missing destination owner before WAL mutation`() = runTest {
		installValidFixture(candidateWriter = true, installDestinationOwner = false)
		installCellLane(SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL)

		val changed = assertIs<CellSessionFactDrainResult.AuthorityChanged>(
			CellSessionFactProjectionLane(database, subject, writer).drainAvailable(),
		)

		assertEquals("CELL_DESTINATION_OWNER_CHANGED", changed.reason)
		assertEquals(0L, database.cellCapturedFactDao().revisionCount())
		assertEquals(0L, database.cellCapturedFactDao().cursorCount())
		assertEquals(0L, database.sourceProjectionStateDao().activeProductLane(CELL_SOURCE)
			?.contiguousAdmissionOrdinal)
	}

	@Test
	fun `fact backed history discovery finds a Cell only zero sample segment`() = runTest {
		installValidFixture(candidateWriter = true)
		assertIs<CellCapturedWriteResult.Applied>(writer.write(EVENT_ID))

		val candidates = database.cellCapturedFactDao().logicalHistoryCandidatePage(
			SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID,
			SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION,
			SourceDestinationOwnerEntity.SOURCE_CELL,
			10,
			null,
			null,
		)

		assertEquals(1, candidates.size)
		assertEquals(SEGMENT_ID, candidates.single().segment.id)
		assertEquals(0, candidates.single().segment.sampleCount)
	}

	@Test
	fun `exact WAL replay is a zero mutation idempotent result`() = runTest {
		installValidFixture(candidateWriter = true)
		val first = assertIs<CellCapturedWriteResult.Applied>(writer.write(EVENT_ID))
		val evidenceRevision = requireNotNull(database.sourceEvidenceStateDao().get()).revision

		val replay = assertIs<CellCapturedWriteResult.Unchanged>(writer.write(EVENT_ID))

		assertEquals(first.logicalFactId, replay.logicalFactId)
		assertEquals(1L, database.cellCapturedFactDao().revisionCount())
		assertEquals(evidenceRevision, database.sourceEvidenceStateDao().get()?.revision)
	}

	@Test
	fun `finite immutable owner permits one coverage only reuse`() = runTest {
		installValidFixture(candidateWriter = true)
		val first = assertIs<CellCapturedWriteResult.Applied>(writer.write(EVENT_ID))
		val beforeEvidenceRevision = requireNotNull(database.sourceEvidenceStateDao().get()).revision
		val shifted = observations().map { observation ->
			observation.copy(
				providerTimestampNanos = requireNotNull(observation.providerTimestampNanos) +
					SECOND_DELIVERY_SHIFT_NANOS,
			)
		}
		insertAdditionalWal(SECOND_EVENT_ID, shifted, SOURCE_SEQUENCE + 1L)

		val second = assertIs<CellCapturedWriteResult.Applied>(writer.write(SECOND_EVENT_ID))
		val coverage = requireNotNull(
			database.cellCapturedFactDao().revision(
				SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID,
				SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION,
				second.logicalFactId,
				second.semanticRevision,
			),
		)

		assertEquals(CellCapturedFactRevisionEntity.FACT_KIND_COVERAGE_ONLY, coverage.factKind)
		assertEquals(first.logicalFactId, coverage.aggregateOwnerLogicalFactId)
		assertEquals(1L, coverage.aggregateOwnerSemanticRevision)
		assertEquals(null, coverage.observationCount)
		assertEquals(2L, database.cellCapturedFactDao().revisionCount())
		assertEquals(beforeEvidenceRevision + 1L, database.sourceEvidenceStateDao().get()?.revision)
	}

	@Test
	fun `dependent Cell replay cannot resurrect after its older aggregate owner expires`() = runTest {
		installValidFixture(candidateWriter = true)
		val ownerApplied = assertIs<CellCapturedWriteResult.Applied>(writer.write(EVENT_ID))
		val shifted = observations().map { observation ->
			observation.copy(
				providerTimestampNanos = requireNotNull(observation.providerTimestampNanos) +
					SECOND_DELIVERY_SHIFT_NANOS,
			)
		}
		insertAdditionalWal(SECOND_EVENT_ID, shifted, SOURCE_SEQUENCE + 1L)
		val dependentApplied = assertIs<CellCapturedWriteResult.Applied>(writer.write(SECOND_EVENT_ID))
		assertFactWalAuthenticated(EVENT_ID, ownerApplied)
		assertFactWalAuthenticated(SECOND_EVENT_ID, dependentApplied)
		val floor = OBSERVED_WALL_MS + 1L
		val markedAtMs = OBSERVED_WALL_MS + 10_000L
		database.sourceEvidenceStateDao().updateLifecycle(0L, floor, markedAtMs)

		assertEquals(
			CellCapturedRetentionResult.Pruned(2, 2),
			database.pruneCapturedCellFactsAffectedByRetentionFloor(
				floor,
				0L,
				0L,
				markedAtMs,
			),
		)
		assertEquals(
			CellCapturedWriteResult.Rejected(CellCapturedWriteRejection.RETAINED_DATA),
			writer.write(SECOND_EVENT_ID),
		)
	}

	@Test
	fun `Cell lifecycle materialization requires current owner and applies its retention floor`() =
		runTest {
			installValidFixture(candidateWriter = true)
			val ownerApplied = assertIs<CellCapturedWriteResult.Applied>(writer.write(EVENT_ID))
			val shifted = observations().map { observation ->
				observation.copy(
					providerTimestampNanos = requireNotNull(observation.providerTimestampNanos) +
						SECOND_DELIVERY_SHIFT_NANOS,
				)
			}
			insertAdditionalWal(SECOND_EVENT_ID, shifted, SOURCE_SEQUENCE + 1L)
			val dependentApplied =
				assertIs<CellCapturedWriteResult.Applied>(writer.write(SECOND_EVENT_ID))
			assertFactWalAuthenticated(EVENT_ID, ownerApplied)
			assertFactWalAuthenticated(SECOND_EVENT_ID, dependentApplied)
			val dependent = fact(dependentApplied.logicalFactId, dependentApplied.semanticRevision)
			val owner = fact(ownerApplied.logicalFactId, ownerApplied.semanticRevision)
			installCellLane(SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL)
			database.openHelper.writableDatabase.execSQL(
				"UPDATE source_product_projection_lane SET contiguous_admission_ordinal = 2 " +
					"WHERE source_kind = ?",
				arrayOf(CELL_SOURCE),
			)
			val recording = RecordingProofState(
				lineage = SourceProofLineage(
					evidenceIdentity = SourceEvidenceIdentity(SECOND_EVENT_ID.value),
					sourceAdmissionOrdinal = dependent.sourceAdmissionOrdinal,
					sourceEvidenceRevision =
						requireNotNull(database.sourceEvidenceStateDao().get()).revision,
				),
				coverage = SourceProofCoverage.ProviderInterval(
					dependent.coverageIntervalStartNanos,
					dependent.coverageIntervalEndNanos,
				),
				completeness = SourceProofCompleteness.COMPLETE,
			)
			val key = SourceProofKey(
				source = TrackingSource.CELL,
				purpose = TrackingPurpose.SESSION_CAPTURE,
				evidenceIdentity = SourceEvidenceIdentity(SECOND_EVENT_ID.value),
				sourcePolicyRevision = POLICY_REVISION,
				captureConsentEpoch = CONSENT_EPOCH,
				collectedDataEpoch = 0L,
				scope = SourceProofScope.Run(LOGICAL_ID, RUN_ID),
			)
			val adapter = lifecycleFactory().create(TrackingSource.CELL)

			val materialized = adapter.materialized(key, recording)
			assertIs<SourceProofResult.Proven<*>>(materialized, materialized.toString())

			val ownerCursor = requireNotNull(database.cellCapturedFactDao().cursor(
				SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID,
				SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION,
				owner.logicalFactId,
			))
			database.openHelper.writableDatabase.execSQL(
				"UPDATE cell_captured_fact_cursor SET latest_effect_checksum = ? " +
					"WHERE logical_fact_id = ?",
				arrayOf("0".repeat(64), owner.logicalFactId),
			)
			adapter.materialized(key, recording) shouldBe SourceProofResult.Unavailable(
				SourceProofStage.MATERIALIZED,
				SourceProofReason.STALE,
				recording.lineage,
			)
			database.openHelper.writableDatabase.execSQL(
				"UPDATE cell_captured_fact_cursor SET latest_effect_checksum = ? " +
					"WHERE logical_fact_id = ?",
				arrayOf(ownerCursor.latestEffectChecksum, owner.logicalFactId),
			)
			database.sourceEvidenceStateDao().updateLifecycle(
				epoch = 0L,
				retainedFromMs = owner.observedWallTimeMs + 1L,
				updatedAtMs = OBSERVED_WALL_MS + 10_000L,
			)

			adapter.materialized(key, recording) shouldBe SourceProofResult.Deleted(
				SourceProofStage.MATERIALIZED,
				SourceProofReason.RETENTION_EXPIRED,
				recording.lineage,
			)
		}

	@Test
	fun `historical open dependent self materializes after its owner settles`() = runTest {
		installValidFixture(candidateWriter = true, openAuthority = true)
		val first = assertIs<CellCapturedWriteResult.Applied>(writer.write(EVENT_ID))
		val shifted = observations().map { observation ->
			observation.copy(
				providerTimestampNanos = requireNotNull(observation.providerTimestampNanos) +
					SECOND_DELIVERY_SHIFT_NANOS,
			)
		}
		insertAdditionalWal(SECOND_EVENT_ID, shifted, SOURCE_SEQUENCE + 1L)

		val second = assertIs<CellCapturedWriteResult.Applied>(writer.write(SECOND_EVENT_ID))
		rewriteAsHistoricalCoverageOnly(second.logicalFactId, first.logicalFactId)
		val historicalDependent = fact(second.logicalFactId, second.semanticRevision)
		assertEquals(CellCapturedFactRevisionEntity.FACT_KIND_COVERAGE_ONLY, historicalDependent.factKind)
		assertEquals(first.logicalFactId, historicalDependent.aggregateOwnerLogicalFactId)
		assertEquals(1L, historicalDependent.aggregateOwnerSemanticRevision)

		settleFixtureAuthority()

		val settledOwner = assertIs<CellCapturedWriteResult.Applied>(writer.write(EVENT_ID))
		assertEquals(first.logicalFactId, settledOwner.logicalFactId)
		assertEquals(2L, settledOwner.semanticRevision)
		val settledDependent = assertIs<CellCapturedWriteResult.Applied>(writer.write(SECOND_EVENT_ID))
		val settledDependentFact = fact(
			settledDependent.logicalFactId,
			settledDependent.semanticRevision,
		)
		assertEquals(2L, settledDependent.semanticRevision)
		assertEquals(CellCapturedFactRevisionEntity.FACT_KIND_AGGREGATE, settledDependentFact.factKind)
		assertEquals(null, settledDependentFact.aggregateOwnerLogicalFactId)
		assertEquals(null, settledDependentFact.aggregateOwnerSemanticRevision)
	}

	@Test
	fun `same retained WAL can append an exact semantic correction`() = runTest {
		installValidFixture(candidateWriter = true, openAuthority = true)
		val first = assertIs<CellCapturedWriteResult.Applied>(writer.write(EVENT_ID))
		settleFixtureAuthority()

		val corrected = assertIs<CellCapturedWriteResult.Applied>(writer.write(EVENT_ID))
		val revision = requireNotNull(
			database.cellCapturedFactDao().revision(
				SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID,
				SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION,
				corrected.logicalFactId,
				corrected.semanticRevision,
			),
		)

		assertEquals(first.logicalFactId, corrected.logicalFactId)
		assertEquals(2L, revision.semanticRevision)
		assertEquals(1L, revision.supersedesSemanticRevision)
		assertEquals(1, revision.registeredObservationCount)
		assertEquals(SOURCE_SEQUENCE, revision.sourceSequence)
		assertEquals(2L, database.cellCapturedFactDao().revisionCount())
	}

	@Test
	fun `same WAL closes formerly open authority bounds after exact settlement`() = runTest {
		installValidFixture(candidateWriter = true, openAuthority = true)
		val first = assertIs<CellCapturedWriteResult.Applied>(writer.write(EVENT_ID))
		val open = fact(first.logicalFactId, 1L)
		assertEquals(Long.MAX_VALUE, open.providerAcceptanceEndNanos)
		assertEquals(Long.MAX_VALUE, open.authorizationEffectEndNanos)
		assertEquals(Long.MAX_VALUE, open.sessionRunEffectEndNanos)
		assertEquals(Long.MAX_VALUE, open.deletionEffectEndNanos)

		settleFixtureAuthority()

		val corrected = assertIs<CellCapturedWriteResult.Applied>(writer.write(EVENT_ID))
		val settled = fact(corrected.logicalFactId, corrected.semanticRevision)
		assertEquals(CellCapturedFactRevisionEntity.FACT_KIND_AGGREGATE, settled.factKind)
		assertEquals(REGISTRATION_END_NANOS, settled.providerAcceptanceEndNanos)
		assertEquals(REGISTRATION_END_NANOS, settled.authorizationEffectEndNanos)
		assertEquals(REGISTRATION_END_NANOS, settled.consentEffectEndNanos)
		assertEquals(REGISTRATION_END_NANOS, settled.sessionRunEffectEndNanos)
		assertEquals(SESSION_END_NANOS, settled.deletionEffectEndNanos)
	}

	@Test
	fun `finite historical bounds cannot move during correction`() = runTest {
		installValidFixture(candidateWriter = true)
		assertIs<CellCapturedWriteResult.Applied>(writer.write(EVENT_ID))
		database.openHelper.writableDatabase.execSQL(
			"UPDATE provider_registration_generation SET retired_elapsed_realtime_nanos = ? " +
				"WHERE source_kind = ? AND registration_generation = ?",
			arrayOf<Any?>(REGISTRATION_END_NANOS + 1L, CELL_SOURCE, REGISTRATION_GENERATION),
		)

		assertEquals(
			CellCapturedWriteResult.Rejected(CellCapturedWriteRejection.REVISION_CHAIN_MISMATCH),
			writer.write(EVENT_ID),
		)
	}

	@Test
	fun `retained callback materializes aggregate when prior owner falls below retention`() = runTest {
		installValidFixture(candidateWriter = true)
		assertIs<CellCapturedWriteResult.Applied>(writer.write(EVENT_ID))
		val shifted = observations().map { observation ->
			observation.copy(
				providerTimestampNanos = requireNotNull(observation.providerTimestampNanos) +
					SECOND_DELIVERY_SHIFT_NANOS,
			)
		}
		insertAdditionalWal(SECOND_EVENT_ID, shifted, SOURCE_SEQUENCE + 1L)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_evidence_state SET retained_from_ms = ? WHERE id = 1",
			arrayOf(OBSERVED_WALL_MS),
		)

		val second = assertIs<CellCapturedWriteResult.Applied>(writer.write(SECOND_EVENT_ID))
		val retained = fact(second.logicalFactId, second.semanticRevision)

		assertEquals(CellCapturedFactRevisionEntity.FACT_KIND_AGGREGATE, retained.factKind)
		assertEquals(null, retained.aggregateOwnerLogicalFactId)
		assertEquals(2, retained.observationCount)
	}

	@Test
	fun `coverage replay authenticates its exact owner through a later owner revision`() = runTest {
		installValidFixture(candidateWriter = true)
		val first = assertIs<CellCapturedWriteResult.Applied>(writer.write(EVENT_ID))
		val shifted = observations().map { observation ->
			observation.copy(
				providerTimestampNanos = requireNotNull(observation.providerTimestampNanos) +
					SECOND_DELIVERY_SHIFT_NANOS,
			)
		}
		insertAdditionalWal(SECOND_EVENT_ID, shifted, SOURCE_SEQUENCE + 1L)
		assertIs<CellCapturedWriteResult.Applied>(writer.write(SECOND_EVENT_ID))
		pointCursorAt(syntheticRevision(fact(first.logicalFactId, 1L), 2L))

		assertIs<CellCapturedWriteResult.Unchanged>(writer.write(SECOND_EVENT_ID))
	}

	@Test
	fun `discontinuous prior lineage fails closed before reuse`() = runTest {
		installValidFixture(candidateWriter = true)
		val first = assertIs<CellCapturedWriteResult.Applied>(writer.write(EVENT_ID))
		pointCursorAt(syntheticRevision(fact(first.logicalFactId, 1L), 3L))
		val shifted = observations().map { observation ->
			observation.copy(
				providerTimestampNanos = requireNotNull(observation.providerTimestampNanos) +
					SECOND_DELIVERY_SHIFT_NANOS,
			)
		}
		insertAdditionalWal(SECOND_EVENT_ID, shifted, SOURCE_SEQUENCE + 1L)

		assertEquals(
			CellCapturedWriteResult.Rejected(CellCapturedWriteRejection.REVISION_CHAIN_MISMATCH),
			writer.write(SECOND_EVENT_ID),
		)
	}

	@Test
	fun `lineage beyond source local maximum fails closed`() = runTest {
		installValidFixture(candidateWriter = true)
		val first = assertIs<CellCapturedWriteResult.Applied>(writer.write(EVENT_ID))
		var current = fact(first.logicalFactId, 1L)
		for (revision in 2L..257L) {
			current = syntheticRevision(current, revision)
			assertEquals(true, database.cellCapturedFactDao().insertRevision(current) != -1L)
		}
		pointCursorAt(current, insert = false)

		assertEquals(
			CellCapturedWriteResult.Rejected(CellCapturedWriteRejection.REVISION_CHAIN_MISMATCH),
			writer.write(EVENT_ID),
		)
	}

	@Test
	fun `manifest source integrity mismatch fails closed before persisted reuse`() = runTest {
		installValidFixture(candidateWriter = true)
		assertIs<CellCapturedWriteResult.Applied>(writer.write(EVENT_ID))
		database.openHelper.writableDatabase.execSQL(
			"UPDATE session_manifest_source SET qos_code = qos_code + 1 " +
				"WHERE logical_tracking_id = ? AND manifest_revision = ?",
			arrayOf<Any?>(LOGICAL_ID, MANIFEST_REVISION),
		)

		assertEquals(
			CellCapturedWriteResult.AdapterRejected(
				CellWalAdapterRejection.MANIFEST_TIMELINE_UNVERIFIABLE,
			),
			writer.write(EVENT_ID),
		)
	}

	@Test
	fun `cursor CAS collision rolls back an appended correction`() = runTest {
		installValidFixture(candidateWriter = true, openAuthority = true)
		val first = assertIs<CellCapturedWriteResult.Applied>(writer.write(EVENT_ID))
		settleFixtureAuthority()
		val evidenceRevision = requireNotNull(database.sourceEvidenceStateDao().get()).revision

		val result = writer.write(EVENT_ID) { checkpoint ->
			if (checkpoint == CellCapturedWriteCheckpoint.REVISION_INSERTED) {
				database.openHelper.writableDatabase.execSQL(
					"UPDATE cell_captured_fact_cursor SET cursor_revision = cursor_revision + 1 " +
						"WHERE logical_fact_id = ?",
					arrayOf(first.logicalFactId),
				)
			}
		}

		assertEquals(
			CellCapturedWriteResult.Rejected(CellCapturedWriteRejection.CURSOR_CHANGED),
			result,
		)
		assertEquals(1L, database.cellCapturedFactDao().revisionCount())
		assertEquals(
			1L,
			database.cellCapturedFactDao().cursor(
				SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID,
				SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION,
				first.logicalFactId,
			)?.cursorRevision,
		)
		assertEquals(evidenceRevision, database.sourceEvidenceStateDao().get()?.revision)
	}

	@Test
	fun `destination authority rotation after qualification rolls back the write`() = runTest {
		installValidFixture(candidateWriter = true, openAuthority = true)
		assertIs<CellCapturedWriteResult.Applied>(writer.write(EVENT_ID))
		settleFixtureAuthority()

		val result = writer.write(EVENT_ID) { checkpoint ->
			if (checkpoint == CellCapturedWriteCheckpoint.QUALIFIED) {
				database.openHelper.writableDatabase.execSQL(
					"UPDATE source_destination_owner SET owner_generation = 3 " +
						"WHERE source_kind = ? AND destination = ?",
					arrayOf<Any?>(
						CELL_SOURCE,
						SourceDestinationOwnerEntity.DESTINATION_SESSION_CELL,
					),
				)
				assertEquals(
					3L,
					database.sourceDestinationOwnerDao().get(
						CELL_SOURCE,
						SourceDestinationOwnerEntity.DESTINATION_SESSION_CELL,
					)?.ownerGeneration,
				)
			}
		}

		assertEquals(
			CellCapturedWriteResult.Rejected(CellCapturedWriteRejection.DESTINATION_OWNER_CHANGED),
			result,
		)
		assertEquals(1L, database.cellCapturedFactDao().revisionCount())
		assertEquals(
			SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
			database.sourceDestinationOwnerDao().get(
				CELL_SOURCE,
				SourceDestinationOwnerEntity.DESTINATION_SESSION_CELL,
			)?.ownerGeneration,
		)
	}

	@Test
	fun `retention rotation after qualification is rechecked and rolled back`() = runTest {
		installValidFixture(candidateWriter = true)

		val result = writer.write(EVENT_ID) { checkpoint ->
			if (checkpoint == CellCapturedWriteCheckpoint.QUALIFIED) {
				database.openHelper.writableDatabase.execSQL(
					"UPDATE source_evidence_state SET retained_from_ms = ? WHERE id = 1",
					arrayOf(OBSERVED_WALL_MS + 1L),
				)
			}
		}

		assertEquals(
			CellCapturedWriteResult.Rejected(CellCapturedWriteRejection.RETAINED_DATA),
			result,
		)
		assertEquals(0L, database.cellCapturedFactDao().revisionCount())
		assertEquals(null, database.sourceEvidenceStateDao().get()?.retainedFromMs)
	}

	@Test
	fun `cancellation after cursor advancement rolls back fact cursor and evidence`() = runTest {
		installValidFixture(candidateWriter = true)
		val evidenceRevision = requireNotNull(database.sourceEvidenceStateDao().get()).revision

		assertFailsWith<CancellationException> {
			writer.write(EVENT_ID) { checkpoint ->
				if (checkpoint == CellCapturedWriteCheckpoint.CURSOR_ADVANCED) {
					throw CancellationException("test cancellation")
				}
			}
		}

		assertEquals(0L, database.cellCapturedFactDao().revisionCount())
		assertEquals(0L, database.cellCapturedFactDao().cursorCount())
		assertEquals(evidenceRevision, database.sourceEvidenceStateDao().get()?.revision)
	}

	@Test
	fun `candidate writer remains dormant without exact destination owner`() = runTest {
		installValidFixture(candidateWriter = true, installDestinationOwner = false)

		assertEquals(
			CellCapturedWriteResult.Rejected(CellCapturedWriteRejection.DESTINATION_OWNER_CHANGED),
			writer.write(EVENT_ID),
		)
		assertEquals(0L, database.cellCapturedFactDao().revisionCount())
	}

	@Test
	fun `v1 WAL cannot be rebound to a later source deletion generation`() = runTest {
		installValidFixture(candidateWriter = true, deletionGeneration = 1L)

		assertEquals(
			CellCapturedWriteResult.AdapterRejected(
				CellWalAdapterRejection.SCOPE_DELETION_AUTHORITY_MISMATCH,
			),
			writer.write(EVENT_ID),
		)
		assertEquals(0L, database.cellCapturedFactDao().revisionCount())
	}

	@Test
	fun `source deletion generation advances only by exact sequential CAS`() = runTest {
		database.cellCapturedFactDao().insertDeletionGeneration(
			CellCaptureDeletionGenerationEntity(
				logicalTrackingId = LOGICAL_ID,
				serviceRunId = RUN_ID,
				collectedDataEpoch = 0L,
				generation = 1L,
				updatedAtMs = RUN_START_WALL_MS,
			),
		)

		assertEquals(
			0,
			database.cellCapturedFactDao().advanceDeletionGenerationExact(
				LOGICAL_ID, RUN_ID, 0L, 1L, 3L, RUN_START_WALL_MS + 1L,
			),
		)
		assertEquals(
			1,
			database.cellCapturedFactDao().advanceDeletionGenerationExact(
				LOGICAL_ID, RUN_ID, 0L, 1L, 2L, RUN_START_WALL_MS + 1L,
			),
		)
		assertEquals(2L, database.cellCapturedFactDao().deletionGeneration(LOGICAL_ID, RUN_ID)?.generation)
	}

	@Test
	fun `global epoch rotation blocks old generation zero WAL before scope reuse`() = runTest {
		installValidFixture(candidateWriter = true)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_evidence_state SET collected_data_epoch = 1 WHERE id = 1",
		)

		assertEquals(
			CellCapturedWriteResult.AdapterRejected(CellWalAdapterRejection.DELETED_EVIDENCE),
			writer.write(EVENT_ID),
		)
		assertEquals(0L, database.cellCapturedFactDao().revisionCount())
	}

	@Test
	fun `partial delivery persists only fresh child coverage while retaining full WAL interval`() = runTest {
		val staleTime = AUTHORIZATION_START_NANOS - 1L
		installValidFixture(
			candidateWriter = true,
			observations = listOf(
				CellObservationEvidence("", "LTE", true, -100, staleTime),
				CellObservationEvidence("", "NR", false, -85, OBSERVED_END_NANOS),
			),
		)

		val applied = assertIs<CellCapturedWriteResult.Applied>(writer.write(EVENT_ID))
		val fact = requireNotNull(
			database.cellCapturedFactDao().revision(
				SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID,
				SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION,
				applied.logicalFactId,
				applied.semanticRevision,
			),
		)

		assertEquals(staleTime, fact.observedIntervalStartNanos)
		assertEquals(OBSERVED_END_NANOS, fact.coverageIntervalStartNanos)
		assertEquals(OBSERVED_END_NANOS, fact.coverageIntervalEndNanos)
		assertEquals(1, fact.acceptedChildCount)
		assertEquals(1, fact.staleChildCount)
		assertEquals(
			true,
			CellWalCarrierAuthenticator.authenticates(
				fact,
				requireNotNull(database.sourceEventWalDao().getByEventId(EVENT_ID.value)),
			),
		)
	}

	@Test
	fun `pre-registration child does not discard registered Cell observation`() = runTest {
		val staleTime = REGISTRATION_START_NANOS - 1L
		installValidFixture(
			candidateWriter = true,
			observations = listOf(
				CellObservationEvidence("", "LTE", true, -100, staleTime),
				CellObservationEvidence("", "NR", false, -85, OBSERVED_END_NANOS),
			),
		)

		val applied = assertIs<CellCapturedWriteResult.Applied>(writer.write(EVENT_ID))
		val fact = requireNotNull(
			database.cellCapturedFactDao().revision(
				SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID,
				SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION,
				applied.logicalFactId,
				applied.semanticRevision,
			),
		)
		assertEquals(1, fact.acceptedChildCount)
		assertEquals(1, fact.staleChildCount)
		assertEquals(OBSERVED_END_NANOS, fact.coverageIntervalStartNanos)
	}

	private suspend fun assertFactWalAuthenticated(
		eventId: SourceEventId,
		applied: CellCapturedWriteResult.Applied,
	) {
		val revision = fact(applied.logicalFactId, applied.semanticRevision)
		val wal = requireNotNull(database.sourceEventWalDao().getByEventId(eventId.value))
		val authentication = CellWalCarrierAuthenticator.authenticate(revision, wal)
		assertIs<CellWalCarrierAuthentication.Authenticated>(
			authentication,
			authentication.toString(),
		)
	}

	@Test
	fun `actual v6 ingress with denied callback saves two historical sessions and original tower indexes`() = runTest {
		val event = installHistoricalCellGroups()
		val wal = requireNotNull(database.sourceEventWalDao().getByEventId(event.value))
		assertEquals(null, wal.logicalTrackingId)
		assertEquals(null, wal.serviceRunId)
		assertEquals(0L, wal.authorizationPurposeEligibilityMask)
		assertEquals(PlanAttribution.RECEIVE_TIME_ONLY.ordinal, wal.planAttribution)
		assertEquals(OBSERVED_START_NANOS, wal.observedIntervalStartNanos)
		assertEquals(OBSERVED_END_NANOS, wal.observedElapsedNanos)
		val lane = CellSessionFactProjectionLane(database, subject, writer)
		val drained = assertIs<CellSessionFactDrainResult.Complete>(lane.drainAvailable())
		assertEquals(2, drained.factsInserted)
		val facts = historicalCellFacts()
		assertEquals(listOf(LOGICAL_ID, HISTORICAL_SECOND_LOGICAL), facts.map { it.logicalTrackingId })
		assertEquals(listOf(1L, 2L), facts.map { it.authorizationRevision })
		assertEquals(listOf(0, 1), facts.flatMap { historicalCellChildren(it).map { child -> child.itemIndex } })
		assertEquals(listOf("first-tower", "second-tower"),
			facts.flatMap { historicalCellChildren(it).map { child -> child.towerIdentity } })
		facts.forEach { fact ->
			assertEquals(1, fact.acceptedChildCount)
			assertIs<CellWalCarrierAuthentication.Authenticated>(
				CellWalCarrierAuthenticator.authenticate(fact, wal, historicalCellChildren(fact)))
			val key = historicalCellKey(event, fact.logicalTrackingId, fact.serviceRunId)
			val adapter = lifecycleFactory().create(TrackingSource.CELL)
			val recording = assertIs<SourceProofResult.Proven<RecordingProofState>>(adapter.recording(key)).state
			assertIs<SourceProofResult.Proven<*>>(adapter.materialized(key, recording))
		}
		val replay = assertIs<CellCapturedWriteResult.Groups>(writer.write(event))
		assertEquals(2, replay.results.filterIsInstance<CellCapturedWriteResult.Unchanged>().size)
		assertEquals(2L, database.cellCapturedFactDao().revisionCount())
		assertEquals(2L, database.cellCapturedFactDao().cursorCount())
		assertEquals(0, assertIs<CellSessionFactDrainResult.Complete>(lane.drainAvailable()).factsInserted)
	}

	@Test
	fun `historical Cell purpose selectors keep a pre-consent reading out of the next session`() = runTest {
		val event = installHistoricalCellGroups()
		val groups = assertIs<CellWalAdapterResult.Groups>(subject.qualify(event))
		val facts = groups.results.filterIsInstance<CellWalAdapterResult.Evaluated>().map {
			assertIs<CellCapturedFactClassification.FreshChanged>(it.classification).fact
		}
		assertEquals(listOf(OBSERVED_START_NANOS, OBSERVED_END_NANOS),
			facts.map { it.coverage.providerIntervalStartElapsedRealtimeNanos })
		assertEquals(listOf(1, 1), facts.map { it.coverage.acceptedChildCount })
		assertEquals(listOf(1, 1), facts.map { it.coverage.staleChildCount })
		val second = groups.results.filterIsInstance<CellWalAdapterResult.Evaluated>().last()
		assertEquals(HISTORICAL_SECOND_LOGICAL,
			assertIs<CellCapturedFactClassification.FreshChanged>(second.classification).fact.authority.logicalTrackingId.value)
	}

	@Test
	fun `unsupported Cell item cannot hide two independently valid historical sessions`() = runTest {
		val event = installHistoricalCellGroups(extraUnsupportedItem = true)
		assertEquals(2, assertIs<CellSessionFactDrainResult.Complete>(
			CellSessionFactProjectionLane(database, subject, writer).drainAvailable()).factsInserted)
		val facts = historicalCellFacts()
		assertEquals(listOf(1, 1), facts.map { it.acceptedChildCount })
		assertEquals(listOf(0, 1), facts.map { it.unsupportedTechnologyChildCount })
		assertEquals(listOf(0, 1), facts.flatMap { historicalCellChildren(it).map { child -> child.itemIndex } })
		assertEquals(3, payloadCodec.decode(SourceKind.CELL, CELL_WAL_PAYLOAD_VERSION,
			requireNotNull(database.sourceEventWalDao().getByEventId(event.value)).payload)
			.let { assertIs<CellSnapshotPayload>(it).observations.size })
	}

	@Test
	fun `cancel after first historical Cell revision rolls back every sibling and lane checkpoint`() = runTest {
		val event = installHistoricalCellGroups()
		assertFailsWith<CancellationException> {
			writer.write(event) { checkpoint ->
				if (checkpoint == CellCapturedWriteCheckpoint.REVISION_INSERTED) throw CancellationException("cancel first group")
			}
		}
		assertEquals(0L, database.cellCapturedFactDao().revisionCount())
		assertEquals(0L, database.cellCapturedFactDao().cursorCount())
		assertEquals(0L, database.sourceProjectionStateDao().activeProductLane(CELL_SOURCE)?.contiguousAdmissionOrdinal)
		assertEquals(2, assertIs<CellSessionFactDrainResult.Complete>(
			CellSessionFactProjectionLane(database, subject, writer).drainAvailable()).factsInserted)
	}

	@Test
	fun `deleted historical Cell session does not discard its authorized sibling`() = runTest {
		val event = installHistoricalCellGroups()
		CellSessionFactProjectionLane(database, subject, writer).drainAvailable()
		database.withTransaction {
			database.deleteSelectedCapturedCellFactsInTransaction(LOGICAL_ID, listOf(RUN_ID), 0L,
				maxOf(System.currentTimeMillis(), OBSERVED_WALL_MS) + 10_000L)
		}
		assertEquals(listOf(HISTORICAL_SECOND_LOGICAL), historicalCellFacts().map { it.logicalTrackingId })
		val replay = assertIs<CellCapturedWriteResult.Groups>(writer.write(event))
		assertEquals(1, replay.results.filterIsInstance<CellCapturedWriteResult.Unchanged>().size)
		assertEquals(1, replay.results.filterIsInstance<CellCapturedWriteResult.AdapterRejected>().size)
		assertEquals(1L, database.cellCapturedFactDao().revisionCount())
		val reset = database.sourceProjectionStateDao().activeProductLane(CELL_SOURCE)!!
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_product_projection_lane SET contiguous_admission_ordinal = 0 WHERE source_kind = ?",
			arrayOf(CELL_SOURCE))
		assertEquals(1L, assertIs<CellSessionFactDrainResult.Complete>(
			CellSessionFactProjectionLane(database, subject, writer).drainAvailable()).lastCompletedOrdinal)
		assertEquals(reset.contiguousAdmissionOrdinal,
			database.sourceProjectionStateDao().activeProductLane(CELL_SOURCE)?.contiguousAdmissionOrdinal)
	}

	@Test
	fun `two Cell receipts sharing original callback survive retention and fence replay per session`() = runTest {
		val event = installHistoricalCellGroups()
		CellSessionFactProjectionLane(database, subject, writer).drainAvailable()
		val before = historicalCellFacts()
		val adapter = lifecycleFactory().create(TrackingSource.CELL)
		val recordings = before.associate { fact ->
			fact.logicalFactId to assertIs<SourceProofResult.Proven<RecordingProofState>>(adapter.recording(
				historicalCellKey(event, fact.logicalTrackingId, fact.serviceRunId))).state
		}
		val floor = OBSERVED_WALL_MS + 1L
		val retainedAt = maxOf(System.currentTimeMillis(), OBSERVED_WALL_MS) + 10_000L
		database.sourceEvidenceStateDao().updateLifecycle(0L, floor, retainedAt)
		assertEquals(CellCapturedRetentionResult.Pruned(2, 2),
			database.pruneCapturedCellFactsAffectedByRetentionFloor(floor, 0L, 0L, retainedAt))
		val proofs = database.capturedRadioFactRetentionProofDao().allForSource(CELL_SOURCE)
		assertEquals(2, proofs.size)
		assertEquals(setOf(event.value), proofs.map { it.sourceEventId }.toSet())
		assertEquals(before.map { it.logicalFactId }.toSet(), proofs.map { it.logicalFactId }.toSet())
		before.forEach { fact ->
			val key = historicalCellKey(event, fact.logicalTrackingId, fact.serviceRunId)
			assertEquals(SourceProofResult.Deleted(SourceProofStage.RECORDING, SourceProofReason.RETENTION_EXPIRED),
				adapter.recording(key))
			assertIs<SourceProofResult.Deleted>(adapter.materialized(key, recordings.getValue(fact.logicalFactId)))
		}
		val replay = assertIs<CellCapturedWriteResult.Groups>(writer.write(event))
		assertEquals(2, replay.results.filterIsInstance<CellCapturedWriteResult.AdapterRejected>().size)
		assertEquals(0L, database.cellCapturedFactDao().revisionCount())
		// Raw cleanup no longer has a current session header; each original receipt owns its scope.
		database.openHelper.writableDatabase.execSQL("DELETE FROM source_event_wal WHERE event_id = ?",
			arrayOf(event.value))
		before.forEach { fact ->
			val key = historicalCellKey(event, fact.logicalTrackingId, fact.serviceRunId)
			assertEquals(SourceProofResult.Deleted(SourceProofStage.RECORDING, SourceProofReason.RETENTION_EXPIRED),
				lifecycleFactory().create(TrackingSource.CELL).recording(key))
		}
	}

	@Test
	fun `historical Cell sessions around midnight keep original days and portable observation scope`() = runTest {
		val midnight = java.time.LocalDate.of(2026, 10, 4).atStartOfDay(ZoneId.of(ZONE_ID)).toInstant().toEpochMilli()
		val event = installHistoricalCellGroups(observedWallMs = midnight + 50L)
		CellSessionFactProjectionLane(database, subject, writer).drainAvailable()
		val facts = historicalCellFacts()
		assertEquals(listOf(java.time.LocalDate.of(2026, 10, 3).toEpochDay(),
			java.time.LocalDate.of(2026, 10, 4).toEpochDay()), facts.map { it.structuralEpochDay })
		assertEquals(listOf(midnight - 50L, midnight + 50L), facts.map { it.observedWallTimeMs })
		val reader = RoomReadLocalPortableCapturedCell(database, SourceProductLaneExecutionAuthority { lane ->
			lane.sourceKind == CELL_SOURCE && lane.projectionId == CellSessionFactProjectionLane.WRITER_ID &&
				lane.projectionVersion == CellSessionFactProjectionLane.WRITER_VERSION
		})
		facts.forEach { fact ->
			val result = database.withTransaction { reader.readInTransaction(fact.logicalTrackingId) }
			val entry = assertIs<ReadLocalPortableCapturedCellResult.Ready>(result, result.toString()).entry
			assertEquals(1, entry.observations.size)
			assertEquals(fact.observedWallTimeMs, entry.observations.single().observedTimeMs)
			assertEquals(fact.observedWallTimeMs - WALL_UNCERTAINTY_MS,
				entry.observations.single().coverageStartTimeMs)
			assertEquals(1, entry.observations.single().observationCount)
		}
		assertEquals(event.value, facts.first().sourceEventId)
		assertEquals(event.value, facts.last().sourceEventId)
	}

	@Test
	fun `historical Cell capture and background copies qualify independently and deleting one session keeps both siblings`() = runTest {
		val event = installHistoricalCellGroups(background = true)
		val wal = requireNotNull(database.sourceEventWalDao().getByEventId(event.value))
		assertEquals(null, wal.logicalTrackingId)
		assertEquals(SourceBrokerPurpose.MASK_AMBIENT_PRODUCT, wal.authorizationPurposeEligibilityMask)
		assertEquals(2, assertIs<CellSessionFactDrainResult.Complete>(
			CellSessionFactProjectionLane(database, subject, writer).drainAvailable()).factsInserted)
		val projector = AmbientCellFactProjector(database, payloadCodec, Dispatchers.Unconfined,
			BootClockDomainProvider { BOOT_ID })
		assertIs<AmbientCellProjectionResult.FactWritten>(projector.project(wal.admissionOrdinal))
		val ambient = database.ambientCellFactDao().effectiveLocalOverWindow(
			OBSERVED_WALL_MS - 1_000L, OBSERVED_WALL_MS + 1_000L, 10).single().fact
		assertEquals(2, ambient.observationCount)
		assertEquals(listOf("first-tower", "second-tower"), database.radioObservationChildDao().cellBackgroundChildren(
			ambient.writerId, ambient.writerVersion, ambient.logicalFactId, ambient.semanticRevision, 65).map { it.towerIdentity })
		database.withTransaction {
			database.deleteSelectedCapturedCellFactsInTransaction(LOGICAL_ID, listOf(RUN_ID), 0L,
				maxOf(System.currentTimeMillis(), OBSERVED_WALL_MS) + 10_000L)
		}
		assertEquals(listOf(HISTORICAL_SECOND_LOGICAL), historicalCellFacts().map { it.logicalTrackingId })
		assertEquals(2, database.radioObservationChildDao().cellBackgroundChildren(ambient.writerId,
			ambient.writerVersion, ambient.logicalFactId, ambient.semanticRevision, 65).size)
		assertIs<AmbientCellProjectionResult.Duplicate>(projector.project(wal.admissionOrdinal))
	}

	@Test
	fun `source erase accepts two historical Cell policies inside one run and fences their replay together`() = runTest {
		val event = installHistoricalCellGroups(sameRunPolicyRotation = true)
		assertEquals(2, assertIs<CellSessionFactDrainResult.Complete>(
			CellSessionFactProjectionLane(database, subject, writer).drainAvailable()).factsInserted)
		val facts = historicalCellFacts()
		assertEquals(setOf(LOGICAL_ID), facts.map { it.logicalTrackingId }.toSet())
		assertEquals(setOf(RUN_ID), facts.map { it.serviceRunId }.toSet())
		assertEquals(setOf(POLICY_REVISION, POLICY_REVISION + 1L), facts.map { it.sourcePolicyRevision }.toSet())
		assertEquals(setOf(MANIFEST_REVISION, MANIFEST_REVISION + 1L), facts.map { it.manifestRevision }.toSet())
		assertEquals(setOf(CONSENT_EPOCH), facts.map { it.captureConsentEpoch }.toSet())
		assertEquals(listOf(0, 1), facts.flatMap { historicalCellChildren(it).map { child -> child.itemIndex } }.sorted())
		val wal = requireNotNull(database.sourceEventWalDao().getByEventId(event.value))
		val revokedEpoch = revokeHistoricalCellCapture()
		val deletedAt = maxOf(System.currentTimeMillis(), SESSION_END_WALL_MS) + 10_000L

		assertEquals(CellCapturedSourceDeletionResult.Deleted(2, 2, 1),
			database.deleteCapturedCellFactsAfterConsentReset(0L, 0L, revokedEpoch, deletedAt))
		assertEquals(0L, database.cellCapturedFactDao().revisionCount())
		assertEquals(0L, database.cellCapturedFactDao().cursorCount())
		assertEquals(1L, database.cellCapturedFactDao().deletionGenerationCount())
		assertEquals(1L, database.sourceDeletionFenceDao().countAll())
		facts.forEach { assertEquals(emptyList(), historicalCellChildren(it)) }
		assertEquals(wal.integrityIdentity,
			database.sourceEventWalDao().getByEventId(event.value)?.integrityIdentity)
		val replay = assertIs<CellCapturedWriteResult.Groups>(writer.write(event))
		assertEquals(2, replay.results.filterIsInstance<CellCapturedWriteResult.AdapterRejected>().size)
		assertEquals(0L, database.cellCapturedFactDao().revisionCount())
		assertEquals(CellCapturedSourceDeletionResult.AlreadyDeleted,
			database.deleteCapturedCellFactsAfterConsentReset(0L, 0L, revokedEpoch, deletedAt + 1L))
	}

	@Test
	fun `proof only same run Cell policy and consent siblings erase under one original run fence`() = runTest {
		val event = installHistoricalCellGroups(sameRunPolicyRotation = true, rotateCaptureConsent = true)
		CellSessionFactProjectionLane(database, subject, writer).drainAvailable()
		val facts = historicalCellFacts()
		assertEquals(2, facts.size)
		assertEquals(setOf(CONSENT_EPOCH, CONSENT_EPOCH + 1L), facts.map { it.captureConsentEpoch }.toSet())
		val floor = OBSERVED_WALL_MS + 1L
		val retainedAt = maxOf(System.currentTimeMillis(), SESSION_END_WALL_MS) + 10_000L
		database.sourceEvidenceStateDao().updateLifecycle(0L, floor, retainedAt)
		assertEquals(CellCapturedRetentionResult.Pruned(2, 2),
			database.pruneCapturedCellFactsAffectedByRetentionFloor(floor, 0L, 0L, retainedAt))
		val proofs = database.capturedRadioFactRetentionProofDao().allForSource(CELL_SOURCE)
		assertEquals(2, proofs.size)
		assertEquals(setOf(event.value), proofs.map { it.sourceEventId }.toSet())
		database.openHelper.writableDatabase.execSQL("DELETE FROM source_event_wal WHERE event_id = ?",
			arrayOf(event.value))
		val revokedEpoch = revokeHistoricalCellCapture()

		assertEquals(CellCapturedSourceDeletionResult.Deleted(0, 0, 1),
			database.deleteCapturedCellFactsAfterConsentReset(0L, 0L, revokedEpoch, retainedAt + 1L))
		assertEquals(emptyList(), database.capturedRadioFactRetentionProofDao().allForSource(CELL_SOURCE))
		assertEquals(1L, database.cellCapturedFactDao().deletionGenerationCount())
		assertEquals(1L, database.sourceDeletionFenceDao().countAll())
		assertEquals(CellCapturedSourceDeletionResult.AlreadyDeleted,
			database.deleteCapturedCellFactsAfterConsentReset(0L, 0L, revokedEpoch, retainedAt + 2L))
	}

	private suspend fun revokeHistoricalCellCapture(): Long {
		val dao = database.sourcePolicyDao()
		val authority = requireNotNull(dao.authority())
		val previous = requireNotNull(dao.policyAtRevision(authority.currentPolicyRevision, CELL_SOURCE))
		val revokedEpoch = requireNotNull(dao.latestConsentEpoch(CELL_SOURCE, SourceBrokerPurpose.SESSION_CAPTURE)).epoch + 1L
		val revokedAt = SESSION_END_WALL_MS + 1L
		val nextRevision = authority.currentPolicyRevision + 1L
		dao.insertPolicies(listOf(previous.copy(policyRevision = nextRevision, enabled = false,
			capturePersistenceEligible = false, captureConsentEpoch = null,
			effectiveElapsedRealtimeNanos = REGISTRATION_END_NANOS + 1L,
			effectiveWallTimeMs = revokedAt, changeReason = "TEST_CAPTURE_ERASE")))
		dao.insertConsentEpochs(listOf(SourceConsentEpochEntity(CELL_SOURCE,
			SourceBrokerPurpose.SESSION_CAPTURE, revokedEpoch, false, false, nextRevision,
			BOOT_ID, REGISTRATION_END_NANOS + 1L, revokedAt, "TEST_CAPTURE_ERASE")))
		assertEquals(1, dao.compareAndSetAuthority(authority.bootstrapState, authority.currentPolicyRevision,
			SourcePolicyAuthorityEntity.STATE_ACTIVE, nextRevision, null, revokedAt))
		return revokedEpoch
	}

	@Test
	fun `Cell lifecycle proves all 256 towers and rejects an extra stored child`() = runTest {
		val originals = observations()
		val towers = List(256) { index ->
			originals[index % originals.size].copy(identifierToken = "tower-$index")
		}
		installValidFixture(candidateWriter = true, observations = towers,
			payloadVersion = CELL_WAL_PAYLOAD_VERSION)
		assertEquals(1, assertIs<CellSessionFactDrainResult.Complete>(
			CellSessionFactProjectionLane(database, subject, writer).drainAvailable()).factsInserted)
		val saved = historicalCellFacts().single()
		val children = database.radioObservationChildDao().cellSessionChildren(
			saved.writerProjectionId, saved.writerProjectionVersion,
			saved.logicalFactId, saved.semanticRevision, 257)
		assertEquals(256, children.size)
		val key = historicalCellKey(EVENT_ID, LOGICAL_ID, RUN_ID)
		val adapter = lifecycleFactory().create(TrackingSource.CELL)
		val recording = assertIs<SourceProofResult.Proven<RecordingProofState>>(adapter.recording(key)).state
		assertIs<SourceProofResult.Proven<*>>(adapter.materialized(key, recording))
		// An extra row must be read and rejected, rather than hidden by a 256-row limit.
		database.radioObservationChildDao().insertCellSession(listOf(children.first().copy(itemIndex = 256)))
		val after = assertIs<SourceProofResult.Failed>(adapter.materialized(key, recording))
		assertEquals(SourceProofReason.INTEGRITY_FAILURE, after.reason)
	}

	@Test
	fun `missing Cell tower rows invalidate only their original historical fact`() = runTest {
		val event = installHistoricalCellGroups()
		assertEquals(2, assertIs<CellSessionFactDrainResult.Complete>(
			CellSessionFactProjectionLane(database, subject, writer).drainAvailable()).factsInserted)
		val facts = historicalCellFacts()
		val adapter = lifecycleFactory().create(TrackingSource.CELL)
		val recordings = facts.associate { saved ->
			val key = historicalCellKey(event, saved.logicalTrackingId, saved.serviceRunId)
			val recording = assertIs<SourceProofResult.Proven<RecordingProofState>>(adapter.recording(key)).state
			assertIs<SourceProofResult.Proven<*>>(adapter.materialized(key, recording))
			saved.logicalFactId to recording
		}
		val broken = facts.first()
		assertEquals(1, database.radioObservationChildDao().deleteCellSessionRevision(
			broken.writerProjectionId, broken.writerProjectionVersion,
			broken.logicalFactId, broken.semanticRevision))
		val key = historicalCellKey(event, broken.logicalTrackingId, broken.serviceRunId)
		val failed = assertIs<SourceProofResult.Failed>(
			adapter.materialized(key, recordings.getValue(broken.logicalFactId)))
		assertEquals(SourceProofReason.INTEGRITY_FAILURE, failed.reason)
		val sibling = facts.last()
		assertIs<SourceProofResult.Proven<*>>(adapter.materialized(
			historicalCellKey(event, sibling.logicalTrackingId, sibling.serviceRunId),
			recordings.getValue(sibling.logicalFactId)))
	}

	private suspend fun historicalCellFacts() = database.cellCapturedFactDao().currentRevisionsForWriterAdmission(
		SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID,
		SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION, 1L, 10,
	).sortedBy { it.logicalTrackingId }

	private suspend fun historicalCellChildren(fact: CellCapturedFactRevisionEntity) =
		database.radioObservationChildDao().cellSessionChildren(fact.writerProjectionId,
			fact.writerProjectionVersion, fact.logicalFactId, fact.semanticRevision, 65)

	private fun historicalCellKey(event: SourceEventId, logical: String, run: String) = SourceProofKey(
		TrackingSource.CELL, TrackingPurpose.SESSION_CAPTURE, SourceEvidenceIdentity(event.value),
		POLICY_REVISION, CONSENT_EPOCH, 0L, SourceProofScope.Run(logical, run),
	)

	/** Uses real Room admission and original immutable provider/authorization rows; no provider starts. */
	private suspend fun installHistoricalCellGroups(
		observedWallMs: Long = OBSERVED_WALL_MS,
		extraUnsupportedItem: Boolean = false,
		background: Boolean = false,
		sameRunPolicyRotation: Boolean = false,
		rotateCaptureConsent: Boolean = false,
	): SourceEventId {
		require(!rotateCaptureConsent || sameRunPolicyRotation)
		val backgroundDemand = if (background) demand(true).copy(demandId = "cell-background-sibling",
			consumerId = "app:cell-background-sibling", purpose = SourceBrokerPurpose.AMBIENT_PRODUCT,
			logicalTrackingId = null, serviceRunId = null, manifestRevision = null, lifecycleLeaseGeneration = null,
			requestedElapsedRealtimeNanos = POLICY_START_NANOS) else null
		val siblings = listOfNotNull(backgroundDemand)
		installValidFixture(candidateWriter = true, payloadVersion = CELL_WAL_PAYLOAD_VERSION,
			insertWal = false, additionalDemands = siblings)
		if (backgroundDemand != null) {
			database.applyAmbientCellRetentionDecision(AmbientRadioRetentionDecision.GrantLiveAmbient(
				"privacy:cell:ambient:v1", 0L, POLICY_REVISION, CONSENT_EPOCH,
				BOOT_ID, POLICY_START_NANOS, RUN_START_WALL_MS))
			database.ambientCellFactDao().insertAuthority(AmbientCellAuthorityIntegrity.create(1L,
				AmbientCellAuthorityEntity.STATE_ACTIVE, POLICY_REVISION, CONSENT_EPOCH,
				"privacy:cell:ambient:v1", 1L, 0L, 0L, BOOT_ID, POLICY_START_NANOS, RUN_START_WALL_MS,
				1L, "ambient-cell-test-owner", 1L, backgroundDemand.demandId))
		}
		if (observedWallMs != OBSERVED_WALL_MS) {
			// This fixture represents the same real provider interval on another local calendar day.
			database.openHelper.writableDatabase.execSQL(
				"UPDATE provider_registration_generation SET reserved_at_ms = ?, accepted_at_ms = ?, retired_at_ms = ? " +
					"WHERE source_kind = ? AND registration_generation = ?",
				arrayOf(observedWallMs - 1_000L, observedWallMs - 900L, observedWallMs + 1_000L,
					CELL_SOURCE, REGISTRATION_GENERATION))
		}
		val split = 1_550_000_000L
		val end = 1_650_000_000L
		val splitWall = observedWallMs - 50L
		val endWall = observedWallMs + 50L
		val secondLogical = if (sameRunPolicyRotation) LOGICAL_ID else HISTORICAL_SECOND_LOGICAL
		val secondRun = if (sameRunPolicyRotation) RUN_ID else HISTORICAL_SECOND_RUN
		val secondPolicy = POLICY_REVISION + if (sameRunPolicyRotation) 1L else 0L
		val secondManifestRevision = MANIFEST_REVISION + if (sameRunPolicyRotation) 1L else 0L
		val secondPlan = PLAN_REVISION + if (sameRunPolicyRotation) 1L else 0L
		val secondConsent = CONSENT_EPOCH + if (rotateCaptureConsent) 1L else 0L
		if (sameRunPolicyRotation) {
			val policyDao = database.sourcePolicyDao()
			val originalPolicy = requireNotNull(policyDao.policyAtRevision(POLICY_REVISION, CELL_SOURCE))
			policyDao.insertPolicies(listOf(originalPolicy.copy(policyRevision = secondPolicy,
				captureConsentEpoch = secondConsent, effectiveElapsedRealtimeNanos = split,
				effectiveWallTimeMs = splitWall, changeReason = "TEST_POLICY_RECONCILIATION")))
			if (rotateCaptureConsent) policyDao.insertConsentEpochs(listOf(SourceConsentEpochEntity(
				CELL_SOURCE, SourceBrokerPurpose.SESSION_CAPTURE, secondConsent, true, true, secondPolicy,
				BOOT_ID, split, splitWall, "TEST_CAPTURE_REGRANT")))
			assertEquals(1, policyDao.compareAndSetAuthority(SourcePolicyAuthorityEntity.STATE_ACTIVE,
				POLICY_REVISION, SourcePolicyAuthorityEntity.STATE_ACTIVE, secondPolicy, null, splitWall))
			insertPlan(cellPlan().copy(revision = secondPlan), secondPolicy, splitWall)
		}
		val firstSession = requireNotNull(database.sourceSessionDao().session(LOGICAL_ID))
		val firstEndWall = if (sameRunPolicyRotation) endWall else splitWall
		val firstEndNanos = if (sameRunPolicyRotation) end else split
		database.sourceSessionDao().updateSession(firstSession.copy(cutoffAtMs = firstEndWall,
			cutoffElapsedNanos = firstEndNanos, completedAtMs = firstEndWall, finalAdmissionOrdinal = 0L,
			desiredPlanRevision = secondPlan, currentManifestRevision = secondManifestRevision))
		val firstRun = requireNotNull(database.sourceSessionDao().serviceRun(RUN_ID))
		database.sourceSessionDao().updateServiceRun(firstRun.copy(completedAtMs = firstEndWall,
			presentationAcknowledgedAtMs = firstEndWall, desiredPlanRevision = secondPlan))
		database.openHelper.writableDatabase.execSQL(
			"UPDATE session_segment SET end_time_ms = ? WHERE id = ?", arrayOf(firstEndWall, SEGMENT_ID))
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_demand SET retire_elapsed_realtime_nanos = ?, retired_at_ms = ? WHERE demand_id = ?",
			arrayOf(split, splitWall, DEMAND_ID))
		if (!sameRunPolicyRotation) {
			val secondSegment = database.sessionSegmentDao().insert(segment(secondRun).copy(
				id = 0L, logicalTrackingId = HISTORICAL_SECOND_LOGICAL, startTimeMs = splitWall, endTimeMs = endWall))
			database.sourceSessionDao().insertSession(firstSession.copy(logicalTrackingId = HISTORICAL_SECOND_LOGICAL,
				startedAtMs = splitWall, startedElapsedNanos = split, cutoffAtMs = endWall,
				cutoffElapsedNanos = end, completedAtMs = endWall, finalAdmissionOrdinal = 0L))
			database.sourceSessionDao().insertServiceRun(firstRun.copy(serviceRunId = HISTORICAL_SECOND_RUN,
				logicalTrackingId = HISTORICAL_SECOND_LOGICAL, sessionSegmentId = secondSegment,
				startedAtMs = splitWall, startedElapsedNanos = split, completedAtMs = endWall,
				presentationAcknowledgedAtMs = endWall, startDeliveryToken = "second-cell-start-token",
				androidDeliveryUpdatedAtMs = splitWall))
		}
		val source = database.sourceSessionDao().manifestSources(LOGICAL_ID, MANIFEST_REVISION).single()
		val secondSource = source.copy(logicalTrackingId = secondLogical,
			manifestRevision = secondManifestRevision, consentEpoch = secondConsent)
		val manifest = requireNotNull(database.sourceSessionDao().manifest(LOGICAL_ID, MANIFEST_REVISION))
		val secondManifest = manifest.copy(logicalTrackingId = secondLogical,
			serviceRunId = secondRun, manifestRevision = secondManifestRevision, sourcePolicyRevision = secondPolicy,
			acquisitionPlanRevision = secondPlan, effectiveElapsedRealtimeNanos = split, effectiveWallTimeMs = splitWall,
			startOrigin = if (sameRunPolicyRotation) "POLICY_RECONCILIATION" else manifest.startOrigin,
			changeReason = if (sameRunPolicyRotation) "POLICY_RECONCILIATION" else manifest.changeReason)
		database.sourceSessionDao().insertManifest(secondManifest.copy(
			manifestChecksum = SessionManifestIntegrity.compute(secondManifest, listOf(secondSource))))
		database.sourceSessionDao().insertManifestSources(listOf(secondSource))
		val secondDemand = demand().copy(demandId = "second-cell-demand",
			consumerId = "session:$secondLogical", logicalTrackingId = secondLogical, serviceRunId = secondRun,
			manifestRevision = secondManifestRevision, sourcePolicyRevision = secondPolicy, consentEpoch = secondConsent,
			requestedElapsedRealtimeNanos = split, requestedAtMs = splitWall,
			retireElapsedRealtimeNanos = end, retiredAtMs = endWall)
		database.sourceBrokerDao().insertDemands(listOf(secondDemand))
		database.sourceBrokerDao().insertAuthorizations(SourceBrokerAuthorization.rows(CELL_SOURCE,
			REGISTRATION_GENERATION, 2L, listOf(secondDemand) + siblings, BOOT_ID, split, splitWall))
		val denied = SourceBrokerAuthorization.rows(CELL_SOURCE, REGISTRATION_GENERATION, 3L,
			siblings, BOOT_ID, end, endWall)
		database.sourceBrokerDao().insertAuthorizations(denied)
		installCellLane(SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL)
		val catalog = ExecutableSourceLaneCatalog.explicit(ExecutableSourceLaneCatalog.CELL_SESSION_FACTS)
		RoomTrackingRolloutStateStore(database, catalog).save(TrackingRolloutState.eventCanonical(
			sources = setOf(SourceKind.CELL), captureModes = mapOf(SourceKind.CELL to setOf(CaptureReachabilityMode.MANUAL))),
			updatedAtMs = observedWallMs)
		val registration = requireNotNull(database.sourceBrokerDao().registration(CELL_SOURCE, REGISTRATION_GENERATION))
		database.sourceRegistrationStateDao().insertIfAbsent(SourceRegistrationStateEntity(CELL_SOURCE,
			registration.ownerScope, SOURCE_INSTANCE, BOOT_ID, REGISTRATION_GENERATION, 0L, 3L, 0L, observedWallMs))
		val lifecycle = mockk<CollectedDataLifecycleStore>()
		coEvery { lifecycle.snapshot() } returns CollectedDataLifecycleSnapshot(0L, null)
		val gate = object : TrackingStartupGate {
			override val isReady = true
			override suspend fun reconcile(retryFailedStorage: Boolean): TrackingStartupResult =
				TrackingStartupResult.Ready(false, 0L)
		}
		val towers = observations().mapIndexed { index, item -> item.copy(
			identifierToken = if (index == 0) "first-tower" else "second-tower") } +
			if (extraUnsupportedItem) listOf(CellObservationEvidence("unsupported-tower", "UNKNOWN", true, -70,
				OBSERVED_END_NANOS - 1L)) else emptyList()
		val evidence = SourceEvidenceCandidate(providerDedupKey = null, logicalTrackingId = null,
			serviceRunId = null, source = SourceKind.CELL, sourceInstanceId = SourceInstanceId(SOURCE_INSTANCE),
			registrationGeneration = REGISTRATION_GENERATION, physicalConfigurationFingerprint = registration.physicalConfigurationFingerprint,
			authorizationRevision = 3L, registrationPurposeEligibilityMask = denied.first().purposeEligibilityMask,
			registrationEligibilityFingerprint = denied.first().authorizationFingerprint, sourceSequence = 0L,
			configRevision = secondPlan, planAttribution = PlanAttribution.RECEIVE_TIME_ONLY, clockDomainId = BOOT_ID,
			observedElapsedRealtimeNanos = towers.maxOf { requireNotNull(it.providerTimestampNanos) },
			receivedElapsedRealtimeNanos = RECEIVED_NANOS, wallTimeMs = observedWallMs,
			wallTimeUncertaintyMs = WALL_UNCERTAINTY_MS, capturedCollectedDataEpoch = 0L, acquiredAtMs = observedWallMs,
			quality = SourceQuality(), payloadVersion = CELL_WAL_PAYLOAD_VERSION,
			payload = CellSnapshotPayload(null, towers, CellRefreshOutcome.CALLBACK, ZONE_ID))
		val result = RoomDurableSourceIngress(database, lifecycle, payloadCodec, catalog, Provider { gate }).admit(
			SourceDeliveryCandidate(cellProviderDeliveryIdentity(BOOT_ID, towers, CELL_WAL_PAYLOAD_VERSION),
				listOf(SourceDeliveryUnit(0, evidence, towers.minOf { requireNotNull(it.providerTimestampNanos) }))))
		val event = assertIs<DeliveryAdmissionResult.Admitted>(result, result.toString()).units.single().eventId
		listOf(LOGICAL_ID to RUN_ID, secondLogical to secondRun).distinct().forEach { (logical, run) ->
			database.sourceSessionDao().saveCompleteness(SourceSessionCompletenessEntity(logical, run, CELL_SOURCE,
				SOURCE_INSTANCE, REGISTRATION_GENERATION, null, null, true, "PROVIDER_COMPLETENESS_UNOBSERVABLE", "COMPLETE",
				null, null, observedWallMs + 1_000L))
		}
		return event
	}

	private suspend fun installValidFixture(
		sourceSequence: Long = SOURCE_SEQUENCE,
		deliveryIdentityOverride: String? = null,
		evidenceState: SourceEvidenceState = SourceEvidenceState(),
		zoneId: String = ZONE_ID,
		observedWallMs: Long = OBSERVED_WALL_MS,
		segmentRunId: String = RUN_ID,
		candidateWriter: Boolean = false,
		installDestinationOwner: Boolean = candidateWriter,
		deletionGeneration: Long? = null,
		observations: List<CellObservationEvidence> = observations(),
		openAuthority: Boolean = false,
		payloadVersion: Int = PAYLOAD_VERSION,
		insertWal: Boolean = true,
		additionalDemands: List<SourceDemandEntity> = emptyList(),
	) {
		database.sourceEvidenceStateDao().ensure(evidenceState)
		if (deletionGeneration != null) {
			database.cellCapturedFactDao().insertDeletionGeneration(
				CellCaptureDeletionGenerationEntity(
					logicalTrackingId = LOGICAL_ID,
					serviceRunId = RUN_ID,
					collectedDataEpoch = evidenceState.collectedDataEpoch,
					generation = deletionGeneration,
					updatedAtMs = RUN_START_WALL_MS,
				),
			)
		}
		if (installDestinationOwner) {
			database.sourceDestinationOwnerDao().insertIfAbsent(
				SourceDestinationOwnerEntity(
					sourceKind = CELL_SOURCE,
					destination = SourceDestinationOwnerEntity.DESTINATION_SESSION_CELL,
					owner = SourceDestinationOwnerEntity.OWNER_CELL_SESSION_FACTS,
					ownerGeneration = SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
					updatedAtMs = RUN_START_WALL_MS,
				),
			)
		}
		val plan = cellPlan()
		insertPlan(plan)
		installPolicyAndConsent(background = additionalDemands.isNotEmpty())
		val segmentId = database.sessionSegmentDao().insert(segment(segmentRunId))
		assertEquals(SEGMENT_ID, segmentId)
		installSessionAndManifest(segmentId, zoneId, candidateWriter, openAuthority)
		val demand = demand(openAuthority)
		database.sourceBrokerDao().insertDemands(listOf(demand) + additionalDemands)
		database.sourceBrokerDao().insertRegistration(
			registration(plan.physicalConfigurationFingerprint(), openAuthority = openAuthority),
		)
		val authorization = SourceBrokerAuthorization.rows(
			sourceKind = CELL_SOURCE,
			registrationGeneration = REGISTRATION_GENERATION,
			authorizationRevision = AUTHORIZATION_REVISION,
			demands = listOf(demand) + additionalDemands,
			effectiveBootId = BOOT_ID,
			effectiveElapsedRealtimeNanos = AUTHORIZATION_START_NANOS,
			effectiveWallTimeMs = RUN_START_WALL_MS,
		)
		database.sourceBrokerDao().insertAuthorizations(authorization)
		if (!insertWal) return
		val payload = CellSnapshotPayload(
			null,
			observations,
			CellRefreshOutcome.CALLBACK,
			zoneId,
		)
		val encodedPayload = payloadCodec.encode(payload, payloadVersion)
		val deliveryIdentity = deliveryIdentityOverride ?: cellProviderDeliveryIdentity(
			BOOT_ID,
			observations,
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
			sourceKind = CELL_SOURCE,
			sourceInstanceId = SOURCE_INSTANCE,
			registrationGeneration = REGISTRATION_GENERATION,
			physicalConfigurationFingerprint = plan.physicalConfigurationFingerprint(),
			authorizationRevision = AUTHORIZATION_REVISION,
			authorizationPurposeEligibilityMask = SourceBrokerPurpose.MASK_SESSION_CAPTURE,
			authorizationFingerprint = authorization.first().authorizationFingerprint,
			sourceSequence = sourceSequence,
			configRevision = PLAN_REVISION,
			planAttribution = PlanAttribution.CAPTURED_REGISTRATION.ordinal,
			clockDomainId = BOOT_ID,
			observedElapsedNanos = requireNotNull(
				observations.mapNotNull(CellObservationEvidence::providerTimestampNanos).maxOrNull(),
			),
			observedIntervalStartNanos = requireNotNull(
				observations.mapNotNull(CellObservationEvidence::providerTimestampNanos).minOrNull(),
			),
			receivedElapsedNanos = RECEIVED_NANOS,
			wallTimeMs = observedWallMs,
			wallTimeUncertaintyMs = WALL_UNCERTAINTY_MS,
			capturedCollectedDataEpoch = 0L,
			activityAutomationEpoch = null,
			sourcePolicyRevision = POLICY_REVISION,
			captureConsentEpoch = CONSENT_EPOCH,
			sessionManifestRevision = MANIFEST_REVISION,
			lifecycleLeaseGeneration = LEASE_GENERATION,
			acquiredAtMs = observedWallMs,
			qualityFlags = 0L,
			qualityConfidence = null,
			payloadVersion = payloadVersion,
			payload = encodedPayload.bytes,
			payloadChecksum = encodedPayload.checksum,
			createdAtMs = observedWallMs,
		)
		val wal = unsignedWal.copy(integrityIdentity = unsignedWal.calculatedIntegrityIdentity())
		assertEquals(1L, database.sourceEventWalDao().insertIgnoringDuplicate(wal))
	}

	private suspend fun insertAdditionalWal(
		eventId: SourceEventId,
		observations: List<CellObservationEvidence>,
		sourceSequence: Long,
	) {
		val original = requireNotNull(database.sourceEventWalDao().getByEventId(EVENT_ID.value))
		val encodedPayload = payloadCodec.encode(
			CellSnapshotPayload(
				null,
				observations,
				CellRefreshOutcome.CALLBACK,
				ZONE_ID,
			),
			PAYLOAD_VERSION,
		)
		val latestProviderTime = requireNotNull(
			observations.mapNotNull(CellObservationEvidence::providerTimestampNanos).maxOrNull(),
		)
		val observedWallMs = OBSERVED_WALL_MS + SECOND_DELIVERY_SHIFT_NANOS / 1_000_000L
		val unsigned = original.copy(
			admissionOrdinal = 0L,
			eventId = eventId.value,
			deliveryIdentity = cellProviderDeliveryIdentity(BOOT_ID, observations, PAYLOAD_VERSION).value,
			sourceSequence = sourceSequence,
			observedElapsedNanos = latestProviderTime,
			observedIntervalStartNanos = requireNotNull(
				observations.mapNotNull(CellObservationEvidence::providerTimestampNanos).minOrNull(),
			),
			receivedElapsedNanos = latestProviderTime + 100_000_000L,
			wallTimeMs = observedWallMs,
			acquiredAtMs = observedWallMs,
			payload = encodedPayload.bytes,
			payloadChecksum = encodedPayload.checksum,
			createdAtMs = observedWallMs,
			integrityIdentity = SourceEventWalEntity.LEGACY_PENDING_CHECKSUM,
		)
		val wal = unsigned.copy(integrityIdentity = unsigned.calculatedIntegrityIdentity())
		assertEquals(2L, database.sourceEventWalDao().insertIgnoringDuplicate(wal))
		database.openHelper.writableDatabase.execSQL(
			"UPDATE logical_tracking_session SET final_admission_ordinal = 2 " +
				"WHERE logical_tracking_id = ? AND final_admission_ordinal IS NOT NULL",
			arrayOf<Any>(LOGICAL_ID),
		)
	}

	private suspend fun installCellLane(stage: String) {
		val binding = ExecutableSourceLaneCatalog.CELL_SESSION_FACTS
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

	private fun lifecycleFactory() = DefaultSourceLifecycleProofAdapterFactory(
		database = database,
		payloadCodec = payloadCodec,
		planCodec = planCodec,
		locationQualifier = mockk<LocationWalQualificationAdapter>(relaxed = true),
		activityQualifier = mockk<ActivityCapturedWalAdmissionAdapter>(relaxed = true),
		wifiQualifier = mockk<WifiWalQualificationAdapter>(relaxed = true),
		cellQualifier = subject,
		bootClockDomainProvider = BootClockDomainProvider { BOOT_ID },
	)

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

	private suspend fun assertTerminalCellLaneFailure(
		lane: CellSessionFactProjectionLane,
	): CellSessionFactDrainResult.Failed {
		val failed = assertIs<CellSessionFactDrainResult.Failed>(lane.drainAvailable())
		assertEquals(0L, failed.lastCompletedOrdinal)
		assertEquals(1L, failed.failedOrdinal)
		assertEquals(true, failed.terminal)
		assertEquals(0L, database.cellCapturedFactDao().revisionCount())
		assertEquals(0L, database.cellCapturedFactDao().cursorCount())
		assertEquals(
			0L,
			database.sourceProjectionStateDao().activeProductLane(CELL_SOURCE)
				?.contiguousAdmissionOrdinal,
		)
		return failed
	}

	private suspend fun fact(
		logicalFactId: String,
		semanticRevision: Long,
	): CellCapturedFactRevisionEntity = requireNotNull(
		database.cellCapturedFactDao().revision(
			SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID,
			SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION,
			logicalFactId,
			semanticRevision,
		),
	)

	private fun syntheticRevision(
		base: CellCapturedFactRevisionEntity,
		semanticRevision: Long,
	): CellCapturedFactRevisionEntity {
		val unsigned = base.copy(
			semanticRevision = semanticRevision,
			supersedesSemanticRevision = semanticRevision - 1L,
			mutationId = CellCapturedFactRevisionIntegrity.mutationId(
				base.logicalFactId,
				semanticRevision,
			),
			effectChecksum = ZERO_CHECKSUM,
		)
		return unsigned.copy(
			effectChecksum = CellCapturedFactRevisionIntegrity.effectChecksum(unsigned),
		)
	}

	private suspend fun pointCursorAt(
		revision: CellCapturedFactRevisionEntity,
		insert: Boolean = true,
	) {
		if (insert) {
			assertEquals(true, database.cellCapturedFactDao().insertRevision(revision) != -1L)
		}
		database.openHelper.writableDatabase.execSQL(
			"UPDATE cell_captured_fact_cursor SET latest_semantic_revision = ?, " +
				"latest_mutation_id = ?, latest_effect_checksum = ?, " +
				"latest_source_admission_ordinal = ?, cursor_revision = cursor_revision + 1 " +
				"WHERE logical_fact_id = ?",
			arrayOf<Any?>(
				revision.semanticRevision,
				revision.mutationId,
				revision.effectChecksum,
				revision.sourceAdmissionOrdinal,
				revision.logicalFactId,
			),
		)
	}

	private fun settleFixtureAuthority() {
		database.openHelper.writableDatabase.execSQL(
			"UPDATE provider_registration_generation SET status = 'RETIRED', retired_at_ms = ?, " +
				"retired_elapsed_realtime_nanos = ? WHERE source_kind = ? " +
				"AND registration_generation = ?",
			arrayOf<Any?>(
				SESSION_END_WALL_MS,
				REGISTRATION_END_NANOS,
				CELL_SOURCE,
				REGISTRATION_GENERATION,
			),
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE logical_tracking_session SET state = 'FINALIZED', lifecycle_revision = 2, " +
				"cutoff_at_ms = ?, cutoff_elapsed_nanos = ?, completed_at_ms = ?, " +
				"final_admission_ordinal = 1, current_service_run_id = NULL " +
				"WHERE logical_tracking_id = ?",
			arrayOf<Any?>(SESSION_END_WALL_MS, SESSION_END_NANOS, SESSION_END_WALL_MS, LOGICAL_ID),
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_service_run SET state = 'FINALIZED', completed_at_ms = ?, " +
				"completion_reason = 'USER_STOP', runtime_acknowledgement = 'STOP_ACCEPTED', " +
				"run_revision = 2, presentation_acknowledgement = 'QUIESCED', " +
				"presentation_acknowledged_at_ms = ? WHERE service_run_id = ?",
			arrayOf<Any?>(SESSION_END_WALL_MS, SESSION_END_WALL_MS, RUN_ID),
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_demand SET status = 'RETIRED', retire_boot_id = ?, " +
				"retire_elapsed_realtime_nanos = ?, retired_at_ms = ? WHERE demand_id = ?",
			arrayOf<Any?>(BOOT_ID, SESSION_END_NANOS, SESSION_END_WALL_MS, DEMAND_ID),
		)
	}

	private suspend fun rewriteAsHistoricalCoverageOnly(
		logicalFactId: String,
		ownerLogicalFactId: String,
	) {
		val current = fact(logicalFactId, 1L)
		val unsigned = current.copy(
			factKind = CellCapturedFactRevisionEntity.FACT_KIND_COVERAGE_ONLY,
			aggregateOwnerLogicalFactId = ownerLogicalFactId,
			aggregateOwnerSemanticRevision = 1L,
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
			effectChecksum = ZERO_CHECKSUM,
		)
		val historical = unsigned.copy(
			effectChecksum = CellCapturedFactRevisionIntegrity.effectChecksum(unsigned),
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE cell_captured_fact_revision SET fact_kind = ?, " +
				"aggregate_owner_logical_fact_id = ?, aggregate_owner_semantic_revision = ?, " +
				"observation_count = NULL, registered_observation_count = NULL, " +
				"gsm_count = NULL, cdma_count = NULL, wcdma_count = NULL, tdscdma_count = NULL, " +
				"lte_count = NULL, nr_count = NULL, quality_unknown_count = NULL, " +
				"quality_none_or_unknown_count = NULL, quality_poor_count = NULL, " +
				"quality_moderate_count = NULL, quality_good_count = NULL, " +
				"quality_great_count = NULL, weak_observation_count = NULL, " +
				"known_quality_observation_count = NULL, all_known_quality_is_weak = NULL, " +
				"effect_checksum = ? WHERE logical_fact_id = ? AND semantic_revision = 1",
			arrayOf<Any?>(
				CellCapturedFactRevisionEntity.FACT_KIND_COVERAGE_ONLY,
				ownerLogicalFactId,
				1L,
				historical.effectChecksum,
				logicalFactId,
			),
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE cell_captured_fact_cursor SET latest_effect_checksum = ? " +
				"WHERE logical_fact_id = ?",
			arrayOf(historical.effectChecksum, logicalFactId),
		)
	}

	private suspend fun insertPlan(
		plan: CellPlan,
		sourcePolicyRevision: Long = POLICY_REVISION,
		createdAtMs: Long = RUN_START_WALL_MS,
	) {
		val encoded = planCodec.encode(plan)
		database.sourcePlanStateDao().insertRevision(
			AcquisitionPlanRevisionEntity(
				revision = plan.revision,
				planId = "cell-plan",
				createdAtMs = createdAtMs,
				status = "EFFECTIVE",
				sourcePolicyRevision = sourcePolicyRevision,
			),
		)
		database.sourcePlanStateDao().insertDesiredPlans(
			listOf(
				SourceDesiredPlanEntity(
					revision = plan.revision,
					sourceKind = CELL_SOURCE,
					payloadVersion = 1,
					payload = encoded.bytes,
					payloadChecksum = encoded.checksum,
				),
			),
		)
	}

	private suspend fun installPolicyAndConsent(background: Boolean = false) {
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
					sourceKind = CELL_SOURCE,
					enabled = true,
					qosCode = QOS_CODE,
					locationMinTimeSeconds = null,
					locationMinDistanceMeters = null,
					locationRequiredAccuracyMeters = null,
					capturePersistenceEligible = true,
					controlPersistenceEligible = false,
					ambientPersistenceEligible = background,
					captureConsentEpoch = CONSENT_EPOCH,
					controlConsentEpoch = null,
					ambientConsentEpoch = CONSENT_EPOCH.takeIf { background },
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
					sourceKind = CELL_SOURCE,
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
		if (background) policyDao.insertConsentEpochs(listOf(SourceConsentEpochEntity(
			CELL_SOURCE, SourceBrokerPurpose.AMBIENT_PRODUCT, CONSENT_EPOCH, true, true, POLICY_REVISION,
			BOOT_ID, POLICY_START_NANOS, RUN_START_WALL_MS, "TEST_BACKGROUND_SIBLING")))
	}

	private suspend fun installSessionAndManifest(
		segmentId: Long,
		zoneId: String,
		candidateWriter: Boolean,
		openAuthority: Boolean = false,
	) {
		database.sourceSessionDao().insertSession(
			LogicalTrackingSessionEntity(
				logicalTrackingId = LOGICAL_ID,
				state = if (openAuthority) "ACTIVE" else "FINALIZED",
				lifecycleRevision = if (openAuthority) 1L else 2L,
				desiredPlanRevision = PLAN_REVISION,
				rolloutRevision = ROLLOUT_REVISION,
				startOrigin = START_ORIGIN,
				clockDomainId = BOOT_ID,
				startedAtMs = RUN_START_WALL_MS,
				startedElapsedNanos = RUN_START_NANOS,
				cutoffAtMs = SESSION_END_WALL_MS.takeUnless { openAuthority },
				cutoffElapsedNanos = SESSION_END_NANOS.takeUnless { openAuthority },
				completedAtMs = SESSION_END_WALL_MS.takeUnless { openAuthority },
				finalAdmissionOrdinal = 1L.takeUnless { openAuthority },
				failureCode = null,
				sessionMode = "MANUAL",
				currentManifestRevision = MANIFEST_REVISION,
				currentIntentRevision = 1L,
				currentServiceRunId = RUN_ID.takeIf { openAuthority },
				lifecycleLeaseGeneration = LEASE_GENERATION,
				lifecycleBootId = BOOT_ID,
				automationEpoch = null,
			),
		)
		database.sourceSessionDao().insertServiceRun(
			SourceServiceRunEntity(
				serviceRunId = RUN_ID,
				logicalTrackingId = LOGICAL_ID,
				state = if (openAuthority) "ACTIVE" else "FINALIZED",
				desiredPlanRevision = PLAN_REVISION,
				rolloutRevision = ROLLOUT_REVISION,
				foregroundCapabilityFlags = 0L,
				startedAtMs = RUN_START_WALL_MS,
				startedElapsedNanos = RUN_START_NANOS,
				completedAtMs = SESSION_END_WALL_MS.takeUnless { openAuthority },
				completionReason = "USER_STOP".takeUnless { openAuthority },
				bootId = BOOT_ID,
				leaseGeneration = LEASE_GENERATION,
				startOrigin = START_ORIGIN,
				desiredForegroundCapabilityFlags = 0L,
				appliedForegroundCapabilityFlags = 0L,
				runtimeAcknowledgement = if (openAuthority) "START_ACCEPTED" else "STOP_ACCEPTED",
				runtimeFailureCode = null,
				runRevision = 2L,
				startDeliveryToken = "cell-start-token",
				startCommandGeneration = 1L,
				preparedManifestRevision = MANIFEST_REVISION,
				preparedIntentRevision = 1L,
				androidDeliveryState = "FOREGROUND_ACCEPTED",
				androidDeliveryUpdatedAtMs = RUN_START_WALL_MS,
				startIsUserInitiated = true,
				startIsAmbient = false,
				sessionSegmentId = segmentId,
				presentationAcknowledgement = if (openAuthority) {
					SourceServiceRunEntity.PRESENTATION_PENDING
				} else {
					SourceServiceRunEntity.PRESENTATION_QUIESCED
				},
				presentationAcknowledgedAtMs = SESSION_END_WALL_MS.takeUnless { openAuthority },
			),
		)
		val source = SessionManifestSourceEntity(
			logicalTrackingId = LOGICAL_ID,
			manifestRevision = MANIFEST_REVISION,
			sourceKind = CELL_SOURCE,
			purpose = SourceBrokerPurpose.SESSION_CAPTURE,
			consentEpoch = CONSENT_EPOCH,
			persistenceEligible = true,
			qosCode = QOS_CODE,
			outputDestination = if (candidateWriter) {
				SourceDestinationOwnerEntity.DESTINATION_SESSION_CELL
			} else null,
			writerOwner = if (candidateWriter) {
				SourceDestinationOwnerEntity.OWNER_CELL_SESSION_FACTS
			} else null,
			writerOwnerGeneration = if (candidateWriter) {
				SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION
			} else null,
			writerProjectionId = if (candidateWriter) {
				SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID
			} else null,
			writerProjectionVersion = if (candidateWriter) {
				SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION
			} else null,
			writerBindingGeneration = if (candidateWriter) {
				SourceDestinationOwnerEntity.CELL_FACT_BINDING_GENERATION
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
		fingerprint: String = cellPlan().physicalConfigurationFingerprint(),
		generation: Long = REGISTRATION_GENERATION,
		sourceInstance: String = SOURCE_INSTANCE,
		openAuthority: Boolean = false,
	) = ProviderRegistrationGenerationEntity(
		sourceKind = CELL_SOURCE,
		registrationGeneration = generation,
		sourceInstanceId = sourceInstance,
		ownerScope = "source-broker:$CELL_SOURCE",
		clockDomainId = BOOT_ID,
		physicalConfigurationFingerprint = fingerprint,
		collectedDataEpoch = 0L,
		providerResidency = ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND,
		providerProcessIncarnationId = "process-$generation",
		status = if (openAuthority) ProviderRegistrationGenerationEntity.STATUS_ACTIVE else
			ProviderRegistrationGenerationEntity.STATUS_RETIRED,
		reservedAtMs = RUN_START_WALL_MS,
		reservedElapsedRealtimeNanos = RUN_START_NANOS,
		acceptedAtMs = RUN_START_WALL_MS,
		acceptedElapsedRealtimeNanos = REGISTRATION_START_NANOS,
		retiredAtMs = SESSION_END_WALL_MS.takeUnless { openAuthority },
		retiredElapsedRealtimeNanos = REGISTRATION_END_NANOS.takeUnless { openAuthority },
		failureCode = null,
		captureCallbackBarrierAuthorizationRevision = AUTHORIZATION_REVISION,
	)

	private fun demand(openAuthority: Boolean = false) = SourceDemandEntity(
		demandId = DEMAND_ID,
		consumerId = "session:$RUN_ID",
		sourceKind = CELL_SOURCE,
		purpose = SourceBrokerPurpose.SESSION_CAPTURE,
		logicalTrackingId = LOGICAL_ID,
		serviceRunId = RUN_ID,
		manifestRevision = MANIFEST_REVISION,
		lifecycleLeaseGeneration = LEASE_GENERATION,
		sourcePolicyRevision = POLICY_REVISION,
		consentEpoch = CONSENT_EPOCH,
		persistenceEligible = true,
		qosCode = QOS_CODE,
		minimumAcquisitionSpec = "cell:v1:change_callbacks",
		adaptiveReductionAllowed = false,
		maximumAgeMs = 1_000L,
		desiredLatencyMs = 1_000L,
		requestedDeliveryLatencyMs = null,
		requestedBootId = BOOT_ID,
		requestedElapsedRealtimeNanos = RUN_START_NANOS,
		requestedAtMs = RUN_START_WALL_MS,
		status = if (openAuthority) SourceDemandEntity.STATUS_ACTIVE else SourceDemandEntity.STATUS_RETIRED,
		retireBootId = BOOT_ID.takeUnless { openAuthority },
		retireElapsedRealtimeNanos = SESSION_END_NANOS.takeUnless { openAuthority },
		retiredAtMs = SESSION_END_WALL_MS.takeUnless { openAuthority },
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

	private fun cellPlan() = CellPlan(
		revision = PLAN_REVISION,
		mode = CellMode.OBSERVE_CHANGES,
		minimumRefreshAttemptIntervalMs = 60_000L,
		maximumAcceptableCachedAgeMs = 1_000L,
		subscriptionIds = emptySet(),
		backoff = RetryBackoff(1_000L, 60_000L),
	)

	private fun observations() = listOf(
		CellObservationEvidence("", "LTE", true, -100, OBSERVED_START_NANOS),
		CellObservationEvidence("", "NR", false, -85, OBSERVED_END_NANOS),
	)

	private companion object {
		val EVENT_ID = SourceEventId("cell-event-0")
		val SECOND_EVENT_ID = SourceEventId("cell-event-1")
		val CELL_SOURCE = SourceKind.CELL.stableCode
		const val LOGICAL_ID = "logical-cell"
		const val HISTORICAL_SECOND_LOGICAL = "logical-cell-second"
		const val HISTORICAL_SECOND_RUN = "run-cell-second"
		const val RUN_ID = "run-cell"
		const val SOURCE_INSTANCE = "cell-instance"
		const val DEMAND_ID = "cell-demand"
		const val BOOT_ID = "boot-1"
		const val ZONE_ID = "Europe/Prague"
		const val PLAN_REVISION = 1L
		const val POLICY_REVISION = 1L
		const val CONSENT_EPOCH = 1L
		const val MANIFEST_REVISION = 1L
		const val LEASE_GENERATION = 1L
		const val REGISTRATION_GENERATION = 1L
		const val AUTHORIZATION_REVISION = 1L
		const val ROLLOUT_REVISION = 1L
		const val SEGMENT_ID = 1L
		const val SOURCE_SEQUENCE = 0L
		const val PAYLOAD_VERSION = RADIO_OBSERVATION_ZONE_PAYLOAD_VERSION
		const val QOS_CODE = 2
		const val START_ORIGIN = "MANUAL_FOREGROUND_START"
		const val RUN_START_NANOS = 1_000_000_000L
		const val POLICY_START_NANOS = 1_000_000_000L
		const val REGISTRATION_START_NANOS = 1_100_000_000L
		const val AUTHORIZATION_START_NANOS = 1_200_000_000L
		const val OBSERVED_START_NANOS = 1_500_000_000L
		const val OBSERVED_END_NANOS = 1_600_000_000L
		const val RECEIVED_NANOS = 1_700_000_000L
		const val REGISTRATION_END_NANOS = 3_000_000_000L
		const val SESSION_END_NANOS = 4_000_000_000L
		const val RUN_START_WALL_MS = 1_699_999_999_000L
		const val OBSERVED_WALL_MS = 1_700_000_000_000L
		const val SESSION_END_WALL_MS = 1_700_000_003_000L
		const val WALL_UNCERTAINTY_MS = 1L
		const val SECOND_DELIVERY_SHIFT_NANOS = 200_000_000L
		const val ZERO_CHECKSUM =
			"0000000000000000000000000000000000000000000000000000000000000000"
	}
}
