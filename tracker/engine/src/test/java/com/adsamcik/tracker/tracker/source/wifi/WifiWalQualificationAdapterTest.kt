package com.adsamcik.tracker.tracker.source.wifi

import android.app.Application
import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.AcquisitionPlanRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.LifecycleDesiredActionEntity
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
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.WifiCaptureDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.WifiCapturedFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.WifiCapturedFactRevisionIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.tracker.source.coordinator.SourcePlanCodec
import com.adsamcik.tracker.tracker.source.ingress.DefaultSourcePayloadCodec
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.RetryBackoff
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

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		subject = WifiWalQualificationAdapter(database, payloadCodec, planCodec)
		writer = WifiCapturedFactWriter(database, subject)
	}

	@After
	fun tearDown() {
		database.close()
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
	fun `positive source sequence and exact persisted sequence row are mandatory`() = runTest {
		installValidFixture(sourceSequence = 0L)

		assertEquals(
			WifiWalAdapterResult.Rejected(WifiWalAdapterRejection.MALFORMED_PRODUCER_DELIVERY),
			subject.qualify(EVENT_ID),
		)
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
				WifiWalAdapterRejection.HISTORICAL_DEMAND_CONTRACT_UNVERIFIABLE,
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
		installValidFixture(plan = wifiPlan().copy(maximumAcceptableResultAgeMs = 2_000L))

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
	fun `empty retained payload remains clock unverifiable rather than confirmed zero`() = runTest {
		installValidFixture(
			accessPoints = emptyList(),
			deliveryIdentityOverride = "c".repeat(64),
		)

		val evaluated = assertIs<WifiWalAdapterResult.Evaluated>(subject.qualify(EVENT_ID))
		assertEquals(WifiCapturedFactClassification.ClockUnverifiable, evaluated.classification)
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
	fun `source local deletion generation rejects old WAL before classification`() = runTest {
		installValidFixture(candidateWriter = true)
		database.openHelper.writableDatabase.execSQL(
			"INSERT INTO wifi_capture_deletion_generation(" +
				"logical_tracking_id, service_run_id, collected_data_epoch, generation, updated_at_ms" +
				") VALUES(?, ?, ?, ?, ?)",
			arrayOf(LOGICAL_ID, RUN_ID, 0L, 1L, OBSERVED_WALL_MS),
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

	private suspend fun insertSecondWal(): SourceEventId {
		val first = requireNotNull(database.sourceEventWalDao().getByEventId(EVENT_ID.value))
		val points = listOf(
			WifiAccessPointEvidence("", 2_412, -80, OBSERVED_END_NANOS + 100_000_000L),
			WifiAccessPointEvidence("", 5_180, -60, OBSERVED_END_NANOS + 200_000_000L),
		)
		val payload = WifiResultSnapshotPayload(
			accessPoints = points,
			platformTimestampMs = (OBSERVED_END_NANOS + 200_000_000L) / NANOS_PER_MILLISECOND,
			resultAgeMs = null,
		)
		val encoded = payloadCodec.encode(payload, PAYLOAD_VERSION)
		val unsigned = first.copy(
			admissionOrdinal = 0L,
			eventId = "wifi-event-2",
			deliveryIdentity = wifiProviderDeliveryIdentity(BOOT_ID, points).value,
			sourceSequence = SOURCE_SEQUENCE + 1L,
			observedIntervalStartNanos = OBSERVED_END_NANOS + 100_000_000L,
			observedElapsedNanos = OBSERVED_END_NANOS + 200_000_000L,
			receivedElapsedNanos = RECEIVED_NANOS + 200_000_000L,
			wallTimeMs = OBSERVED_WALL_MS + 200L,
			acquiredAtMs = OBSERVED_WALL_MS + 200L,
			createdAtMs = OBSERVED_WALL_MS + 201L,
			payload = encoded.bytes,
			payloadChecksum = encoded.checksum,
			integrityIdentity = SourceEventWalEntity.LEGACY_PENDING_CHECKSUM,
		)
		val row = unsigned.copy(integrityIdentity = unsigned.calculatedIntegrityIdentity())
		assertEquals(2L, database.sourceEventWalDao().insertIgnoringDuplicate(row))
		database.openHelper.writableDatabase.execSQL(
			"UPDATE logical_tracking_session SET final_admission_ordinal = 2 WHERE logical_tracking_id = ?",
			arrayOf(LOGICAL_ID),
		)
		return SourceEventId(row.eventId)
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
		includePlanApplication: Boolean = true,
		planApplicationRevision: Long = PLAN_REVISION,
		lastDemandAcquisitionSpec: String? = null,
		requestedDeliveryLatencyMs: Long? = null,
		candidateWriter: Boolean = false,
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
			)
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
		)
		val encodedPayload = payloadCodec.encode(payload, PAYLOAD_VERSION)
		val deliveryIdentity = deliveryIdentityOverride ?: wifiProviderDeliveryIdentity(
			BOOT_ID,
			accessPoints,
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
			payloadVersion = PAYLOAD_VERSION,
			payload = encodedPayload.bytes,
			payloadChecksum = encodedPayload.checksum,
			createdAtMs = OBSERVED_WALL_MS + 1L,
		)
		val wal = unsignedWal.copy(integrityIdentity = unsignedWal.calculatedIntegrityIdentity())
		assertEquals(1L, database.sourceEventWalDao().insertIgnoringDuplicate(wal))
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
		consumerId = "session:$RUN_ID:$index",
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
		adaptiveReductionAllowed = false,
		maximumAgeMs = 1_000L,
		desiredLatencyMs = 1_000L,
		requestedDeliveryLatencyMs = requestedDeliveryLatencyMs,
		requestedBootId = BOOT_ID,
		requestedElapsedRealtimeNanos = RUN_START_NANOS,
		requestedAtMs = RUN_START_WALL_MS,
		status = SourceDemandEntity.STATUS_RETIRED,
		retireBootId = BOOT_ID,
		retireElapsedRealtimeNanos = SESSION_END_NANOS,
		retiredAtMs = SESSION_END_WALL_MS,
	)

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
		const val SOURCE_INSTANCE = "wifi-instance"
		const val DEMAND_ID = "wifi-demand"
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
		const val SOURCE_SEQUENCE = 1L
		const val PAYLOAD_VERSION = 2
		const val QOS_CODE = 2
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
		const val RUN_START_WALL_MS = 1_699_999_999_000L
		const val OBSERVED_WALL_MS = 1_700_000_000_000L
		const val SESSION_END_WALL_MS = 1_700_000_003_000L
		const val WALL_UNCERTAINTY_MS = 1L
		const val NANOS_PER_MILLISECOND = 1_000_000L
	}
}
