package com.adsamcik.tracker.tracker.source.location

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.AcquisitionPlanRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAppliedPlanStateEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDesiredPlanEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.tracker.source.coordinator.SourcePlanCodec
import com.adsamcik.tracker.tracker.source.ingress.DefaultSourcePayloadCodec
import com.adsamcik.tracker.tracker.source.model.LocationBackend
import com.adsamcik.tracker.tracker.source.model.LocationFixPayload
import com.adsamcik.tracker.tracker.source.model.LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION
import com.adsamcik.tracker.tracker.source.model.LocationMode
import com.adsamcik.tracker.tracker.source.model.LocationPlan
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.SourceEventId
import com.adsamcik.tracker.tracker.source.model.SourceQuality
import com.adsamcik.tracker.tracker.source.model.physicalConfigurationFingerprint
import com.adsamcik.tracker.tracker.source.model.sourceDeliveryIdentity
import com.adsamcik.tracker.tracker.source.model.toStableFlags
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocationWalQualificationAdapterTest {
	private lateinit var database: AppDatabase
	private val payloadCodec = DefaultSourcePayloadCodec()
	private val planCodec = SourcePlanCodec()
	private lateinit var subject: LocationWalQualificationAdapter

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		subject = LocationWalQualificationAdapter(database, payloadCodec, planCodec)
	}

	@After
	fun tearDown() {
		database.close()
	}

	@Test
	fun `canonical location WAL remains typed unverifiable without durable mock provenance`() = runTest {
		installValidFixture()

		assertEquals(
			LocationWalAdapterResult.Rejected(LocationWalAdapterRejection.MOCK_PROVENANCE_UNVERIFIABLE),
			subject.qualify(EVENT_ID),
		)
		assertEquals(1L, database.sourceEventWalDao().countAll())
	}

	@Test
	fun `version two WAL qualifies exact non-mock provenance without writing a second fact`() = runTest {
		installValidFixture(
			payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
			deliveryPayloads = listOf(locationPayload(isMock = false)),
		)

		val evaluated = assertIs<LocationWalAdapterResult.Evaluated>(subject.qualify(EVENT_ID))
		val qualified = assertIs<LocationObservationQualification.Qualified>(evaluated.qualification)

		assertFalse(qualified.command.productEffect.isMock)
		assertEquals(
			LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
			qualified.command.productEffect.durableEvidence.payloadVersion,
		)
		assertEquals(EVENT_ID, qualified.command.mutation.identity.sourceEventId)
		assertEquals(1L, database.sourceEventWalDao().countAll())
	}

	@Test
	fun `version two WAL retains positive mock provenance in qualified product effect`() = runTest {
		installValidFixture(
			payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
			deliveryPayloads = listOf(locationPayload(isMock = true)),
		)

		val evaluated = assertIs<LocationWalAdapterResult.Evaluated>(subject.qualify(EVENT_ID))
		val qualified = assertIs<LocationObservationQualification.Qualified>(evaluated.qualification)

		assertTrue(qualified.command.productEffect.isMock)
		assertTrue(requireNotNull(qualified.command.productEffect.payload.isMock))
	}

	@Test
	fun `version two mock provenance participates in exact delivery identity`() = runTest {
		val nonMockPayload = locationPayload(isMock = false)
		val nonMockIdentity = sourceDeliveryIdentity(
			canonicalLocationDelivery(
				payloads = listOf(nonMockPayload),
				payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
			),
		).value
		installValidFixture(
			payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
			deliveryPayloads = listOf(nonMockPayload.copy(isMock = true)),
			deliveryIdentityOverride = nonMockIdentity,
		)

		assertEquals(
			LocationWalAdapterResult.Rejected(LocationWalAdapterRejection.DELIVERY_IDENTITY_MISMATCH),
			subject.qualify(EVENT_ID),
		)
	}

	@Test
	fun `independent event and delivery queries preserve value identity before mock rejection`() = runTest {
		installValidFixture()
		val walDao = database.sourceEventWalDao()
		val eventRead = requireNotNull(walDao.getByEventId(EVENT_ID.value))
		val deliveryRead = walDao.deliveryEvents(
			sourceKind = LOCATION_SOURCE,
			collectedDataEpoch = eventRead.capturedCollectedDataEpoch,
			clockDomainId = eventRead.clockDomainId,
			deliveryIdentity = requireNotNull(eventRead.deliveryIdentity),
			limit = 2,
		).single()
		assertNotSame(eventRead, deliveryRead)
		assertNotSame(eventRead.payload, deliveryRead.payload)
		assertContentEquals(eventRead.payload, deliveryRead.payload)

		assertEquals(
			LocationWalAdapterResult.Rejected(LocationWalAdapterRejection.MOCK_PROVENANCE_UNVERIFIABLE),
			subject.qualify(EVENT_ID),
		)
	}

	@Test
	fun `later mutable applied-plan pointer cannot replace exact historical plan binding`() = runTest {
		installValidFixture()
		val laterPlan = locationPlan(2L).copy(requestedIntervalMs = 30_000L)
		insertPlan(laterPlan)
		database.sourcePlanStateDao().saveAppliedState(
			SourceAppliedPlanStateEntity(
				sourceKind = LOCATION_SOURCE,
				desiredRevision = 2L,
				appliedRevision = 2L,
				sourceInstanceId = SOURCE_INSTANCE,
				registrationGeneration = REGISTRATION_GENERATION + 1L,
				appliedAtElapsedNanos = 700L,
				status = "APPLIED",
				degradedReasons = "",
				updatedAtMs = 2_000L,
			),
		)

		assertEquals(
			LocationWalAdapterResult.Rejected(LocationWalAdapterRejection.MOCK_PROVENANCE_UNVERIFIABLE),
			subject.qualify(EVENT_ID),
		)
	}

	@Test
	fun `zero source sequence cannot be accepted as a durably allocated delivery`() = runTest {
		installValidFixture(sourceSequence = 0L)

		assertEquals(
			LocationWalAdapterResult.Rejected(LocationWalAdapterRejection.SOURCE_SEQUENCE_MISMATCH),
			subject.qualify(EVENT_ID),
		)
	}

	@Test
	fun `source sequence at exhausted allocator sentinel fails closed`() = runTest {
		installValidFixture(sourceSequence = Long.MAX_VALUE)

		assertEquals(
			LocationWalAdapterResult.Rejected(LocationWalAdapterRejection.SOURCE_SEQUENCE_MISMATCH),
			subject.qualify(EVENT_ID),
		)
	}

	@Test
	fun `next same-registration authorization may be sparse across global revisions`() = runTest {
		installValidFixture()
		val fingerprint = locationPlan(PLAN_REVISION).physicalConfigurationFingerprint()
		database.sourceBrokerDao().insertRegistration(
			registration(fingerprint).copy(
				registrationGeneration = REGISTRATION_GENERATION + 1L,
				sourceInstanceId = "interleaved-location-instance",
			),
		)
		database.sourceBrokerDao().insertAuthorizations(
			SourceBrokerAuthorization.rows(
				sourceKind = LOCATION_SOURCE,
				registrationGeneration = REGISTRATION_GENERATION + 1L,
				authorizationRevision = AUTHORIZATION_REVISION + 1L,
				demands = emptyList(),
				effectiveBootId = BOOT_ID,
				effectiveElapsedRealtimeNanos = 650L,
				effectiveWallTimeMs = 1_650L,
			),
		)
		database.sourceBrokerDao().insertAuthorizations(
			SourceBrokerAuthorization.rows(
				sourceKind = LOCATION_SOURCE,
				registrationGeneration = REGISTRATION_GENERATION,
				authorizationRevision = AUTHORIZATION_REVISION + 2L,
				demands = emptyList(),
				effectiveBootId = BOOT_ID,
				effectiveElapsedRealtimeNanos = 700L,
				effectiveWallTimeMs = 1_700L,
			),
		)

		assertEquals(
			LocationWalAdapterResult.Rejected(LocationWalAdapterRejection.MOCK_PROVENANCE_UNVERIFIABLE),
			subject.qualify(EVENT_ID),
		)
	}

	@Test
	fun `noncanonical WAL bytes fail before provider payload can reach qualifier`() = runTest {
		installValidFixture()
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_event_wal SET payload = X'00' WHERE event_id = ?",
			arrayOf(EVENT_ID.value),
		)

		assertEquals(
			LocationWalAdapterResult.Rejected(LocationWalAdapterRejection.WAL_INTEGRITY_MISMATCH),
			subject.qualify(EVENT_ID),
		)
	}

	@Test
	fun `delivery identity must authenticate the complete canonical provider batch`() = runTest {
		installValidFixture(deliveryIdentityOverride = "a".repeat(64))

		assertEquals(
			LocationWalAdapterResult.Rejected(LocationWalAdapterRejection.DELIVERY_IDENTITY_MISMATCH),
			subject.qualify(EVENT_ID),
		)
	}

	@Test
	fun `full deletion high-water prevents dormant shadow qualification`() = runTest {
		installValidFixture(
			evidenceState = SourceEvidenceState(
				collectedDataEpoch = 0L,
				deletedSourceEventHighWaterOrdinal = 1L,
			),
		)

		assertEquals(
			LocationWalAdapterResult.Rejected(LocationWalAdapterRejection.DELETED_EVIDENCE),
			subject.qualify(EVENT_ID),
		)
	}

	@Test
	fun `desired plan whose exact bytes do not own registration fails closed`() = runTest {
		val actualPlan = locationPlan(PLAN_REVISION)
		val differentPlan = actualPlan.copy(requestedIntervalMs = 2_000L)
		installValidFixture(plan = differentPlan, providerFingerprint = actualPlan.physicalConfigurationFingerprint())

		assertEquals(
			LocationWalAdapterResult.Rejected(LocationWalAdapterRejection.HISTORICAL_PLAN_MISMATCH),
			subject.qualify(EVENT_ID),
		)
	}

	@Test
	fun `complete canonical batch authenticates the exact selected indexed unit`() = runTest {
		installValidFixture(
			deliveryPayloads = listOf(
				locationPayload().copy(provider = "network"),
				locationPayload(),
			),
			selectedUnitIndex = 1,
		)

		assertEquals(
			LocationWalAdapterResult.Rejected(LocationWalAdapterRejection.MOCK_PROVENANCE_UNVERIFIABLE),
			subject.qualify(EVENT_ID),
		)
		assertEquals(2L, database.sourceEventWalDao().countAll())
	}

	@Test
	fun `missing sibling makes declared delivery cardinality unverifiable`() = runTest {
		installValidFixture(
			deliveryPayloads = listOf(locationPayload(), locationPayload().copy(provider = "network")),
		)
		database.openHelper.writableDatabase.execSQL(
			"DELETE FROM source_event_wal WHERE delivery_identity = ? AND delivery_unit_index = 1",
			arrayOf(requireNotNull(database.sourceEventWalDao().getByEventId(EVENT_ID.value)).deliveryIdentity),
		)

		assertEquals(
			LocationWalAdapterResult.Rejected(LocationWalAdapterRejection.MALFORMED_DELIVERY),
			subject.qualify(EVENT_ID),
		)
	}

	@Test
	fun `integrity-valid but malformed payload cannot be decoded as Location`() = runTest {
		installValidFixture()
		val original = requireNotNull(database.sourceEventWalDao().getByEventId(EVENT_ID.value))
		val withMalformedPayload = original.copy(payload = byteArrayOf(0x01))
		val withChecksum = withMalformedPayload.copy(
			payloadChecksum = withMalformedPayload.calculatedPayloadChecksum(),
		)
		val rewritten = withChecksum.copy(integrityIdentity = withChecksum.calculatedIntegrityIdentity())
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_event_wal SET payload = ?, payload_checksum = ?, integrity_identity = ? " +
				"WHERE event_id = ?",
			arrayOf(rewritten.payload, rewritten.payloadChecksum, rewritten.integrityIdentity, EVENT_ID.value),
		)

		assertEquals(
			LocationWalAdapterResult.Rejected(LocationWalAdapterRejection.MALFORMED_DELIVERY),
			subject.qualify(EVENT_ID),
		)
	}

	@Test
	fun `manifest checksum mismatch fails before captured source authority is trusted`() = runTest {
		installValidFixture()
		database.openHelper.writableDatabase.execSQL(
			"UPDATE session_manifest_source SET qos_code = ? WHERE logical_tracking_id = ? " +
				"AND manifest_revision = ? AND source_kind = ?",
			arrayOf(QOS_CODE - 1, LOGICAL_ID, MANIFEST_REVISION, LOCATION_SOURCE),
		)

		assertEquals(
			LocationWalAdapterResult.Rejected(LocationWalAdapterRejection.MANIFEST_TIMELINE_UNVERIFIABLE),
			subject.qualify(EVENT_ID),
		)
	}

	@Test
	fun `service run and segment must preserve exact reverse binding`() = runTest {
		installValidFixture(segmentRunId = "different-run")

		assertEquals(
			LocationWalAdapterResult.Rejected(LocationWalAdapterRejection.SEGMENT_MISMATCH),
			subject.qualify(EVENT_ID),
		)
	}

	@Test
	fun `manifest stored zone must be a valid ZoneId`() = runTest {
		installValidFixture(zoneId = "Not/AZone")

		assertEquals(
			LocationWalAdapterResult.Rejected(LocationWalAdapterRejection.INVALID_STORED_ZONE),
			subject.qualify(EVENT_ID),
		)
	}

	@Test
	fun `retention floor rejects an uncertainty interval that can precede it`() = runTest {
		installValidFixture(
			evidenceState = SourceEvidenceState(retainedFromMs = OBSERVED_WALL_MS + 1L),
			wallTimeUncertaintyMs = 1L,
		)

		assertEquals(
			LocationWalAdapterResult.Rejected(LocationWalAdapterRejection.RETAINED_EVIDENCE),
			subject.qualify(EVENT_ID),
		)
	}

	@Test
	fun `retention floor exactly at earliest possible wall preserves eligibility`() = runTest {
		installValidFixture(
			evidenceState = SourceEvidenceState(retainedFromMs = OBSERVED_WALL_MS - 1L),
			wallTimeUncertaintyMs = 1L,
		)

		assertEquals(
			LocationWalAdapterResult.Rejected(LocationWalAdapterRejection.MOCK_PROVENANCE_UNVERIFIABLE),
			subject.qualify(EVENT_ID),
		)
	}

	private suspend fun installValidFixture(
		plan: LocationPlan = locationPlan(PLAN_REVISION),
		providerFingerprint: String = plan.physicalConfigurationFingerprint(),
		deliveryIdentityOverride: String? = null,
		evidenceState: SourceEvidenceState = SourceEvidenceState(),
		sourceSequence: Long = SOURCE_SEQUENCE,
		payloadVersion: Int = LEGACY_LOCATION_PAYLOAD_VERSION,
		deliveryPayloads: List<LocationFixPayload> = listOf(locationPayload()),
		selectedUnitIndex: Int = 0,
		zoneId: String = ZONE_ID,
		segmentRunId: String = RUN_ID,
		wallTimeUncertaintyMs: Long = 0L,
	) {
		require(selectedUnitIndex in deliveryPayloads.indices)
		database.sourceEvidenceStateDao().ensure(evidenceState)
		insertPlan(plan)
		installPolicyAndConsent()
		val segmentId = database.sessionSegmentDao().insert(segment(segmentRunId))
		assertEquals(SEGMENT_ID, segmentId)
		installSessionAndManifest(segmentId, zoneId)
		val demand = demand()
		database.sourceBrokerDao().insertDemands(listOf(demand))
		database.sourceBrokerDao().insertRegistration(registration(providerFingerprint))
		val authorization = SourceBrokerAuthorization.rows(
			sourceKind = LOCATION_SOURCE,
			registrationGeneration = REGISTRATION_GENERATION,
			authorizationRevision = AUTHORIZATION_REVISION,
			demands = listOf(demand),
			effectiveBootId = BOOT_ID,
			effectiveElapsedRealtimeNanos = AUTHORIZATION_START_NANOS,
			effectiveWallTimeMs = 1_050L,
		)
		database.sourceBrokerDao().insertAuthorizations(authorization)
		val deliveryIdentity = deliveryIdentityOverride ?: sourceDeliveryIdentity(
			canonicalLocationDelivery(deliveryPayloads, payloadVersion),
		).value
		val walRows = deliveryPayloads.mapIndexed { index, payload ->
			val encodedPayload = payloadCodec.encode(payload, payloadVersion)
			val unsignedWal = SourceEventWalEntity(
				eventId = if (index == selectedUnitIndex) EVENT_ID.value else "location-event-sibling-$index",
				providerDedupKey = null,
				deliveryIdentity = deliveryIdentity,
				deliveryUnitIndex = index,
				deliveryUnitCount = deliveryPayloads.size,
				logicalTrackingId = LOGICAL_ID,
				serviceRunId = RUN_ID,
				sourceKind = LOCATION_SOURCE,
				sourceInstanceId = SOURCE_INSTANCE,
				registrationGeneration = REGISTRATION_GENERATION,
				physicalConfigurationFingerprint = providerFingerprint,
				authorizationRevision = AUTHORIZATION_REVISION,
				authorizationPurposeEligibilityMask = SourceBrokerPurpose.MASK_SESSION_CAPTURE,
				authorizationFingerprint = authorization.first().authorizationFingerprint,
				sourceSequence = Math.addExact(sourceSequence, index.toLong()),
				configRevision = PLAN_REVISION,
				planAttribution = PlanAttribution.CAPTURED_REGISTRATION.ordinal,
				clockDomainId = BOOT_ID,
				observedElapsedNanos = OBSERVED_NANOS,
				observedIntervalStartNanos = OBSERVED_NANOS,
				receivedElapsedNanos = RECEIVED_NANOS,
				wallTimeMs = OBSERVED_WALL_MS,
				wallTimeUncertaintyMs = wallTimeUncertaintyMs,
				capturedCollectedDataEpoch = 0L,
				sourcePolicyRevision = POLICY_REVISION,
				captureConsentEpoch = CONSENT_EPOCH,
				sessionManifestRevision = MANIFEST_REVISION,
				lifecycleLeaseGeneration = LEASE_GENERATION,
				acquiredAtMs = OBSERVED_WALL_MS,
				qualityFlags = SourceQuality().toStableFlags(),
				qualityConfidence = null,
				payloadVersion = payloadVersion,
				payload = encodedPayload.bytes,
				payloadChecksum = encodedPayload.checksum,
				createdAtMs = 1_600L,
			)
			unsignedWal.copy(integrityIdentity = unsignedWal.calculatedIntegrityIdentity())
		}
		assertEquals(walRows.size, database.sourceEventWalDao().insertDeliveryUnits(walRows).size)
	}

	private suspend fun insertPlan(plan: LocationPlan) {
		val encoded = planCodec.encode(plan)
		database.sourcePlanStateDao().insertRevision(
			AcquisitionPlanRevisionEntity(
				revision = plan.revision,
				planId = "plan-${plan.revision}",
				createdAtMs = 900L,
				status = "EFFECTIVE",
				sourcePolicyRevision = POLICY_REVISION,
			),
		)
		database.sourcePlanStateDao().insertDesiredPlans(
			listOf(
				SourceDesiredPlanEntity(
					revision = plan.revision,
					sourceKind = LOCATION_SOURCE,
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
				updatedAtMs = 1_000L,
			),
		)
		policyDao.insertPolicies(
			listOf(
				SourcePolicyEntity(
					policyRevision = POLICY_REVISION,
					sourceKind = LOCATION_SOURCE,
					enabled = true,
					qosCode = QOS_CODE,
					locationMinTimeSeconds = 1,
					locationMinDistanceMeters = 0,
					locationRequiredAccuracyMeters = 50,
					capturePersistenceEligible = true,
					controlPersistenceEligible = false,
					ambientPersistenceEligible = false,
					captureConsentEpoch = CONSENT_EPOCH,
					controlConsentEpoch = null,
					ambientConsentEpoch = null,
					effectiveBootId = BOOT_ID,
					effectiveElapsedRealtimeNanos = POLICY_START_NANOS,
					effectiveWallTimeMs = 1_000L,
					changeReason = "TEST",
				),
			),
		)
		policyDao.insertConsentEpochs(
			listOf(
				SourceConsentEpochEntity(
					sourceKind = LOCATION_SOURCE,
					purpose = SourceBrokerPurpose.SESSION_CAPTURE,
					epoch = CONSENT_EPOCH,
					eligible = true,
					persistenceEligible = true,
					policyRevision = POLICY_REVISION,
					effectiveBootId = BOOT_ID,
					effectiveElapsedRealtimeNanos = POLICY_START_NANOS,
					effectiveWallTimeMs = 1_000L,
					changeReason = "TEST",
				),
			),
		)
	}

	private suspend fun installSessionAndManifest(segmentId: Long, zoneId: String) {
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
				cutoffAtMs = 2_000L,
				cutoffElapsedNanos = SESSION_END_NANOS,
				completedAtMs = 2_000L,
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
				completedAtMs = 2_000L,
				completionReason = "USER_STOP",
				bootId = BOOT_ID,
				leaseGeneration = LEASE_GENERATION,
				startOrigin = START_ORIGIN,
				desiredForegroundCapabilityFlags = 0L,
				appliedForegroundCapabilityFlags = 0L,
				runtimeAcknowledgement = "STOP_ACCEPTED",
				runtimeFailureCode = null,
				runRevision = 2L,
				startDeliveryToken = "location-start-token",
				startCommandGeneration = 1L,
				preparedManifestRevision = MANIFEST_REVISION,
				preparedIntentRevision = 1L,
				androidDeliveryState = "FOREGROUND_ACCEPTED",
				androidDeliveryUpdatedAtMs = RUN_START_WALL_MS,
				startIsUserInitiated = true,
				startIsAmbient = false,
				sessionSegmentId = segmentId,
				presentationAcknowledgement = SourceServiceRunEntity.PRESENTATION_QUIESCED,
				presentationAcknowledgedAtMs = 2_000L,
			),
		)
		val source = SessionManifestSourceEntity(
			logicalTrackingId = LOGICAL_ID,
			manifestRevision = MANIFEST_REVISION,
			sourceKind = LOCATION_SOURCE,
			purpose = SourceBrokerPurpose.SESSION_CAPTURE,
			consentEpoch = CONSENT_EPOCH,
			persistenceEligible = true,
			qosCode = QOS_CODE,
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

	private fun registration(fingerprint: String) = ProviderRegistrationGenerationEntity(
		sourceKind = LOCATION_SOURCE,
		registrationGeneration = REGISTRATION_GENERATION,
		sourceInstanceId = SOURCE_INSTANCE,
		ownerScope = "LOCATION_RUNTIME",
		clockDomainId = BOOT_ID,
		physicalConfigurationFingerprint = fingerprint,
		collectedDataEpoch = 0L,
		providerResidency = ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND,
		providerProcessIncarnationId = "process-1",
		status = ProviderRegistrationGenerationEntity.STATUS_RETIRED,
		reservedAtMs = 1_000L,
		reservedElapsedRealtimeNanos = RUN_START_NANOS,
		acceptedAtMs = 1_050L,
		acceptedElapsedRealtimeNanos = REGISTRATION_START_NANOS,
		retiredAtMs = 1_900L,
		retiredElapsedRealtimeNanos = REGISTRATION_END_NANOS,
		failureCode = null,
		captureCallbackBarrierAuthorizationRevision = AUTHORIZATION_REVISION,
	)

	private fun demand() = SourceDemandEntity(
		demandId = DEMAND_ID,
		consumerId = "session:$RUN_ID",
		sourceKind = LOCATION_SOURCE,
		purpose = SourceBrokerPurpose.SESSION_CAPTURE,
		logicalTrackingId = LOGICAL_ID,
		serviceRunId = RUN_ID,
		manifestRevision = MANIFEST_REVISION,
		lifecycleLeaseGeneration = LEASE_GENERATION,
		sourcePolicyRevision = POLICY_REVISION,
		consentEpoch = CONSENT_EPOCH,
		persistenceEligible = true,
		qosCode = QOS_CODE,
		minimumAcquisitionSpec = "location:v1:high_accuracy",
		adaptiveReductionAllowed = false,
		maximumAgeMs = 1_000L,
		desiredLatencyMs = 1_000L,
		requestedDeliveryLatencyMs = 0L,
		requestedBootId = BOOT_ID,
		requestedElapsedRealtimeNanos = RUN_START_NANOS,
		requestedAtMs = RUN_START_WALL_MS,
		status = SourceDemandEntity.STATUS_RETIRED,
		retireBootId = BOOT_ID,
		retireElapsedRealtimeNanos = SESSION_END_NANOS,
		retiredAtMs = 2_000L,
	)

	private fun segment(serviceRunId: String) = SessionSegment(
		id = SEGMENT_ID,
		startTimeMs = RUN_START_WALL_MS,
		endTimeMs = 2_000L,
		distanceM = 10f,
		steps = null,
		primaryActivity = null,
		activityConfidence = null,
		sampleCount = 0,
		source = SegmentSource.USER_CREATED,
		inferenceVersion = null,
		createdAt = 2_000L,
		logicalTrackingId = LOGICAL_ID,
		serviceRunId = serviceRunId,
	)

	private fun locationPlan(revision: Long) = LocationPlan(
		revision = revision,
		backend = LocationBackend.FRAMEWORK,
		mode = LocationMode.HIGH_ACCURACY,
		requestedIntervalMs = 1_000L,
		minimumUpdateIntervalMs = 500L,
		minimumDisplacementMeters = 0f,
		maximumBatchDelayMs = 0L,
		probeDurationMs = null,
		preciseLocationAvailable = true,
	)

	private fun locationPayload(isMock: Boolean? = null) = LocationFixPayload(
		latitudeDegrees = 50.087,
		longitudeDegrees = 14.421,
		horizontalAccuracyMeters = 5f,
		altitudeMeters = 210.0,
		verticalAccuracyMeters = 3f,
		speedMetersPerSecond = 1.5f,
		bearingDegrees = 90f,
		provider = "gps",
		isMock = isMock,
	)

	private fun canonicalLocationDelivery(
		payloads: List<LocationFixPayload>,
		payloadVersion: Int = LEGACY_LOCATION_PAYLOAD_VERSION,
	): ByteArray =
		ByteArrayOutputStream().use { bytes ->
			DataOutputStream(bytes).use { output ->
				output.writeInt(0x4c4f4342)
				output.writeInt(payloadVersion)
				output.writeInt(payloads.size)
				payloads.forEach { payload ->
					val provider = payload.provider.encodeToByteArray()
					output.writeInt(provider.size)
					output.write(provider)
					output.writeLong(OBSERVED_NANOS)
					output.writeLong(OBSERVED_WALL_MS)
					output.writeLong(java.lang.Double.doubleToRawLongBits(payload.latitudeDegrees))
					output.writeLong(java.lang.Double.doubleToRawLongBits(payload.longitudeDegrees))
					output.writeInt(java.lang.Float.floatToRawIntBits(payload.horizontalAccuracyMeters))
					output.writeBoolean(true)
					output.writeLong(java.lang.Double.doubleToRawLongBits(requireNotNull(payload.altitudeMeters)))
					output.writeBoolean(true)
					output.writeInt(java.lang.Float.floatToRawIntBits(requireNotNull(payload.verticalAccuracyMeters)))
					output.writeBoolean(true)
					output.writeInt(java.lang.Float.floatToRawIntBits(requireNotNull(payload.speedMetersPerSecond)))
					output.writeBoolean(true)
					output.writeInt(java.lang.Float.floatToRawIntBits(requireNotNull(payload.bearingDegrees)))
					if (payloadVersion >= LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION) {
						output.writeBoolean(requireNotNull(payload.isMock))
					}
				}
			}
			bytes.toByteArray()
		}

	private companion object {
		val EVENT_ID = SourceEventId("location-event-1")
		const val LOCATION_SOURCE = 1
		const val LOGICAL_ID = "logical-location"
		const val RUN_ID = "run-location"
		const val SOURCE_INSTANCE = "location-instance"
		const val DEMAND_ID = "location-demand"
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
		const val LEGACY_LOCATION_PAYLOAD_VERSION = 1
		const val QOS_CODE = 2
		const val START_ORIGIN = "MANUAL_FOREGROUND_START"
		const val RUN_START_NANOS = 100L
		const val POLICY_START_NANOS = 100L
		const val REGISTRATION_START_NANOS = 120L
		const val AUTHORIZATION_START_NANOS = 140L
		const val OBSERVED_NANOS = 500L
		const val RECEIVED_NANOS = 600L
		const val REGISTRATION_END_NANOS = 800L
		const val SESSION_END_NANOS = 900L
		const val RUN_START_WALL_MS = 1_000L
		const val OBSERVED_WALL_MS = 1_500L
	}
}
