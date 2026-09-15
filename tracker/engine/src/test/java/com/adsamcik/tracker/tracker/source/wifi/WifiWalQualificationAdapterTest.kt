package com.adsamcik.tracker.tracker.source.wifi

import android.app.Application
import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.synchronizeLifecycle
import com.adsamcik.tracker.shared.base.database.data.AcquisitionPlanRevisionEntity
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
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionFailureEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductLaneExecutionAuthority
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
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
		subject = WifiWalQualificationAdapter(database, payloadCodec, planCodec)
		writer = WifiCapturedFactWriter(database, subject)
		maintenance = WifiCapturedFactMaintenance(database, payloadCodec, planCodec)
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
			arrayOf(LOGICAL_ID, REPLACEMENT_MANIFEST_REVISION),
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
			arrayOf("0".repeat(64), LOGICAL_ID, REPLACEMENT_MANIFEST_REVISION),
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
			arrayOf(WIFI_SOURCE, REPLACEMENT_REGISTRATION_GENERATION),
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
				arrayOf(WIFI_SOURCE, REPLACEMENT_REGISTRATION_GENERATION),
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
			arrayOf(SourceBrokerPurpose.MASK_CONTROL_AUTOSTART, EVENT_ID.value),
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

		assertEquals("WIFI_ADAPTER_WAL_INTEGRITY_MISMATCH", repeated.failureCode)
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

		assertEquals("WIFI_ADAPTER_WAL_INTEGRITY_MISMATCH", repeated.failureCode)
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

		assertEquals("WIFI_ADAPTER_WAL_INTEGRITY_MISMATCH", repeated.failureCode)
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

		assertEquals("WIFI_ADAPTER_WAL_INTEGRITY_MISMATCH", repeated.failureCode)
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
			arrayOf(WIFI_SOURCE, SourceDestinationOwnerEntity.DESTINATION_SESSION_WIFI),
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
	}

	@Test
	fun `retention floor affecting only coverage also removes its aggregate owner`() = runTest {
		installValidFixture(candidateWriter = true)
		assertIs<WifiCapturedWriteResult.Applied>(writer.write(EVENT_ID))
		val secondId = insertSecondWal(observedWallTimeMs = OBSERVED_WALL_MS - 500L)
		assertIs<WifiCapturedWriteResult.Applied>(writer.write(secondId))
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
			arrayOf(BOOT_ID, SESSION_END_NANOS, SESSION_END_WALL_MS, "$DEMAND_ID-0"),
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE provider_registration_generation SET status = 'ACTIVE', retired_at_ms = NULL, " +
				"retired_elapsed_realtime_nanos = NULL WHERE source_kind = ? " +
				"AND registration_generation = ?",
			arrayOf(WIFI_SOURCE, REGISTRATION_GENERATION),
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
	fun `source deletion retires only authenticated capture while ambient and control stay active`() =
		runTest {
			installValidFixture(candidateWriter = true)
			assertIs<WifiCapturedWriteResult.Applied>(writer.write(EVENT_ID))
			revokeCaptureConsent(keepAmbientAndControl = true)
			database.openHelper.writableDatabase.execSQL(
				"UPDATE source_demand SET status = 'RETIRING', retire_boot_id = ?, " +
					"retire_elapsed_realtime_nanos = ?, retired_at_ms = ? WHERE demand_id = ?",
				arrayOf(
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
			arrayOf(
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
			arrayOf(
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
			assertEquals(sourceEntry, assertIs<ImportedWifiProductEvaluation.Readable>(selected).entry)

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
	fun `selected native deletion blocks active capture without treating quiescence as ownership`() =
		runTest {
			installValidFixture(candidateWriter = true)
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
			arrayOf(WIFI_SOURCE, "CONTROL", CONTROL_CONSENT_EPOCH),
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
			arrayOf(WIFI_SOURCE, REGISTRATION_GENERATION, AUTHORIZATION_REVISION + 1L),
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
			arrayOf(WIFI_SOURCE, SourceDestinationOwnerEntity.DESTINATION_SESSION_WIFI),
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
			arrayOf("f".repeat(64), WIFI_SOURCE, REPLACEMENT_REGISTRATION_GENERATION),
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
			arrayOf(WIFI_SOURCE, REPLACEMENT_REGISTRATION_GENERATION),
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
			arrayOf(
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
			arrayOf(
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
			arrayOf(
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
			arrayOf(
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
			arrayOf(
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
			arrayOf(
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
			arrayOf(purposeMask, rewritten.integrityIdentity, eventId.value),
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
		)
		val encoded = payloadCodec.encode(payload, PAYLOAD_VERSION)
		val unsigned = first.copy(
			admissionOrdinal = 0L,
			eventId = "wifi-event-$index",
			deliveryIdentity = wifiProviderDeliveryIdentity(BOOT_ID, accessPoints).value,
			sourceSequence = SOURCE_SEQUENCE + index - 1L,
			observedIntervalStartNanos = observedStart,
			observedElapsedNanos = observedEnd,
			receivedElapsedNanos = observedEnd + 100_000_000L,
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
			arrayOf(index, LOGICAL_ID),
		)
		return SourceEventId(row.eventId)
	}

	private suspend fun insertNoncaptureWal(
		index: Int,
		purpose: String,
	): SourceEventWalEntity {
		require(index > 1)
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
			WifiAccessPointEvidence("", 2_412, -80, NONCAPTURE_OBSERVED_START_NANOS),
			WifiAccessPointEvidence("", 5_180, -60, NONCAPTURE_OBSERVED_END_NANOS),
		)
		val payload = WifiResultSnapshotPayload(
			accessPoints = accessPoints,
			platformTimestampMs = NONCAPTURE_OBSERVED_END_NANOS / NANOS_PER_MILLISECOND,
			resultAgeMs = null,
		)
		val encoded = payloadCodec.encode(payload, PAYLOAD_VERSION)
		val unsigned = SourceEventWalEntity(
			eventId = "wifi-noncapture-event-$index",
			providerDedupKey = null,
			deliveryIdentity = wifiProviderDeliveryIdentity(BOOT_ID, accessPoints).value,
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
			observedElapsedNanos = NONCAPTURE_OBSERVED_END_NANOS,
			observedIntervalStartNanos = NONCAPTURE_OBSERVED_START_NANOS,
			receivedElapsedNanos = NONCAPTURE_RECEIVED_NANOS,
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
	) {
		database.sourcePolicyDao().insertPolicies(listOf(
			SourcePolicyEntity(
				policyRevision = REVOKED_POLICY_REVISION,
				sourceKind = WIFI_SOURCE,
				enabled = true,
				qosCode = QOS_OFF,
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

	private suspend fun installMixedPortableExportFixture(activeContinuation: Boolean) {
		installValidFixture(candidateWriter = true)
		installRetainedNoncapturePolicyAndConsent(RUN_START_NANOS, RUN_START_WALL_MS)
		val control = nonCaptureDemand(77, SourceBrokerPurpose.CONTROL_AUTOSTART).copy(
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
			arrayOf(WIFI_SOURCE, REGISTRATION_GENERATION),
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
				arrayOf(WIFI_SOURCE, REGISTRATION_GENERATION),
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
			arrayOf(purposeMask, fingerprint, rewritten.integrityIdentity, eventId),
		)
	}

	private suspend fun assertExactWal(expected: SourceEventWalEntity) {
		val actual = requireNotNull(database.sourceEventWalDao().getByEventId(expected.eventId))
		assertEquals(expected.copy(payload = byteArrayOf()), actual.copy(payload = byteArrayOf()))
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

	private fun nonCaptureDemand(index: Int, purpose: String): SourceDemandEntity {
		val directPurpose = when (purpose) {
			SourceBrokerPurpose.CONTROL_AUTOSTART ->
				DirectSourceDemandPurpose.CONTROL_AUTOSTART
			SourceBrokerPurpose.AMBIENT_PRODUCT -> DirectSourceDemandPurpose.AMBIENT_PRODUCT
			else -> error("Unsupported noncapture test purpose")
		}
		val contract = SourceDemandContractFactory.forQos(SourceKind.WIFI, QOS_OFF, directPurpose)
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
			qosCode = QOS_OFF,
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
		const val PAYLOAD_VERSION = 2
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
