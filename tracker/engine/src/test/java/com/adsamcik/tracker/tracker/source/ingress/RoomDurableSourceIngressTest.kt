package com.adsamcik.tracker.tracker.source.ingress

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleSnapshot
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.shared.preferences.tracking.RoomSourcePolicyRepository
import com.adsamcik.tracker.shared.preferences.tracking.SourceCollectionFrequency
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyEffectiveTime
import com.adsamcik.tracker.shared.preferences.tracking.SourcePurpose
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent
import com.adsamcik.tracker.tracker.source.model.ActivityTransitionPayload
import com.adsamcik.tracker.tracker.source.model.LogicalTrackingId
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.SourceEvidenceCandidate
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourceQuality
import com.adsamcik.tracker.tracker.source.model.ServiceRunId
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomDurableSourceIngressTest {
	private lateinit var database: AppDatabase
	private lateinit var lifecycle: FakeLifecycleStore
	private lateinit var subject: RoomDurableSourceIngress
	private var testPolicyRevision = 0L
	private var testCaptureEpoch = 0L
	private var testAmbientEpoch = 0L

	@Before
	fun setUp() = runTest {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState())
		lifecycle = FakeLifecycleStore(CollectedDataLifecycleSnapshot(0L, null))
		subject = RoomDurableSourceIngress(database, lifecycle, DefaultSourcePayloadCodec())
		val policy = RoomSourcePolicyRepository(database) {
			SourcePolicyEffectiveTime("boot", 40L, 40L)
		}
		val initial = policy.bootstrapFromLegacy(TrackingParamsState(legacySettingsMigrationCompleted = true))
		val ambient = policy.setNonCaptureConsent(
			expectedPolicyRevision = initial.revision,
			source = TrackingSourceComponent.ACTIVITY,
			purpose = SourcePurpose.AMBIENT_PRODUCT,
			eligible = true,
			persistenceEligible = true,
			reason = "TEST_AMBIENT_ELIGIBILITY",
		)
		testPolicyRevision = ambient.revision
		testCaptureEpoch = requireNotNull(ambient[TrackingSourceComponent.ACTIVITY].captureConsentEpoch)
		testAmbientEpoch = requireNotNull(ambient[TrackingSourceComponent.ACTIVITY].ambientConsentEpoch)
		installRegistrationGeneration()
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `admission is durable ordered replayable and idempotent by source sequence`() = runTest {
		val candidate = candidate(sequence = 1L)

		val admitted = subject.admit(candidate).shouldBeInstanceOf<AdmissionResult.Admitted>()
		val duplicate = subject.admit(candidate).shouldBeInstanceOf<AdmissionResult.Duplicate>()

		duplicate.eventId shouldBe admitted.eventId
		duplicate.existingAdmissionOrdinal shouldBe admitted.admissionOrdinal
		subject.committedBatch(0L, 1).single().eventId shouldBe admitted.eventId
	}

	@Test
	fun `same source sequence with different evidence is rejected as collision`() = runTest {
		subject.admit(candidate(sequence = 1L, activityType = 3))

		val collision = subject.admit(candidate(sequence = 1L, activityType = 7))
			.shouldBeInstanceOf<AdmissionResult.PermanentFailure>()

		collision.code shouldBe AdmissionFailureCode.IDENTITY_COLLISION
	}

	@Test
	fun `same source sequence with different observation envelope is rejected as collision`() = runTest {
		val original = candidate(sequence = 1L)
		subject.admit(original).shouldBeInstanceOf<AdmissionResult.Admitted>()

		val collision = subject.admit(original.copy(wallTimeUncertaintyMs = 2L))
			.shouldBeInstanceOf<AdmissionResult.PermanentFailure>()

		collision.code shouldBe AdmissionFailureCode.IDENTITY_COLLISION
	}

	@Test
	fun `valid but mutated payload bytes are rejected before decode`() = runTest {
		val admitted = subject.admit(candidate(sequence = 1L, activityType = 3))
			.shouldBeInstanceOf<AdmissionResult.Admitted>()
		val replacement = DefaultSourcePayloadCodec().encode(
			ActivityTransitionPayload(7, 1, 100L),
			1,
		).bytes
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_event_wal SET payload = ? WHERE event_id = ?",
			arrayOf(replacement, admitted.eventId.value),
		)

		val failure = shouldThrow<CorruptSourceEventException> {
			subject.committedBatch(0L, 1)
		}

		failure.admissionOrdinal shouldBe admitted.admissionOrdinal
		failure.failureCode shouldBe "RAW_PAYLOAD_INTEGRITY"
	}

	@Test
	fun `sessionless observation before its demand effective boundary is rejected`() = runTest {
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_authorization SET effective_elapsed_realtime_nanos = 200 " +
				"WHERE source_kind = ${SourceKind.ACTIVITY.stableCode} AND registration_generation = 1",
		)

		val result = subject.admit(candidate(sequence = 1L, observedElapsedNanos = 100L))
			.shouldBeInstanceOf<AdmissionResult.PermanentFailure>()

		result.code shouldBe AdmissionFailureCode.STALE_SOURCE_POLICY
		database.sourceEventWalDao().countAll() shouldBe 0L
	}

	@Test
	fun `provider retry with a newly allocated local sequence remains idempotent`() = runTest {
		val admitted = subject.admit(candidate(sequence = 1L, providerDedupKey = "provider-event"))
			.shouldBeInstanceOf<AdmissionResult.Admitted>()

		val duplicate = subject.admit(candidate(sequence = 2L, providerDedupKey = "provider-event"))
			.shouldBeInstanceOf<AdmissionResult.Duplicate>()

		duplicate.eventId shouldBe admitted.eventId
		database.sourceEventWalDao().countAll() shouldBe 1L
	}

	@Test
	fun `stale collected-data epoch is rejected before database admission`() = runTest {
		lifecycle.update(CollectedDataLifecycleSnapshot(2L, 500L))

		val result = subject.admit(candidate(sequence = 1L, epoch = 1L, acquiredAtMs = 600L))
			.shouldBeInstanceOf<AdmissionResult.PermanentFailure>()

		result.code shouldBe AdmissionFailureCode.STALE_COLLECTED_DATA_EPOCH
		database.sourceEventWalDao().countAll() shouldBe 0L
	}

	@Test
	fun `new epoch waits until Room deletion barrier catches up`() = runTest {
		lifecycle.update(CollectedDataLifecycleSnapshot(1L, 500L))

		val result = subject.admit(candidate(sequence = 1L, epoch = 1L, acquiredAtMs = 600L))
			.shouldBeInstanceOf<AdmissionResult.RetryableFailure>()

		result.code shouldBe AdmissionFailureCode.LIFECYCLE_BARRIER_IN_PROGRESS
	}

	@Test
	fun `candidate session hint cannot override observed authorization identity`() = runTest {
		installSession()
		subject.admit(
			candidate(sequence = 1L).copy(logicalTrackingId = LogicalTrackingId("forged-session")),
		).shouldBeInstanceOf<AdmissionResult.Admitted>()

		val event = subject.committedBatch(0L, 1).single().evidence
		event.logicalTrackingId shouldBe LogicalTrackingId(TEST_SESSION_ID)
		event.serviceRunId shouldBe ServiceRunId(TEST_RUN_ID)
		event.sessionManifestRevision shouldBe 1L
	}

	@Test
	fun `candidate policy and service stamps are replaced by observed authorization`() = runTest {
		val authorization = installSession()
		subject.admit(
			candidate(sequence = 1L).copy(
				serviceRunId = ServiceRunId("forged-run"),
				sourcePolicyRevision = Long.MAX_VALUE,
				captureConsentEpoch = Long.MAX_VALUE,
				sessionManifestRevision = Long.MAX_VALUE,
				lifecycleLeaseGeneration = Long.MAX_VALUE,
			),
		).shouldBeInstanceOf<AdmissionResult.Admitted>()

		val event = subject.committedBatch(0L, 1).single().evidence
		event.serviceRunId shouldBe ServiceRunId(TEST_RUN_ID)
		event.sourcePolicyRevision shouldBe authorization.policyRevision
		event.captureConsentEpoch shouldBe authorization.captureConsentEpoch
		event.sessionManifestRevision shouldBe 1L
		event.lifecycleLeaseGeneration shouldBe 7L
	}

	@Test
	fun `stale physical registration configuration is rejected before wal admission`() = runTest {
		val result = subject.admit(
			candidate(sequence = 1L).copy(physicalConfigurationFingerprint = "stale-physical"),
		).shouldBeInstanceOf<AdmissionResult.PermanentFailure>()

		result.code shouldBe AdmissionFailureCode.STALE_REGISTRATION_GENERATION
		database.sourceEventWalDao().countAll() shouldBe 0L
	}

	@Test
	fun `control only registration persists observation without session capture attribution`() = runTest {
		val authorization = installSession()
		installControlOnlyGeneration()

		subject.admit(
			sessionCandidate(sequence = 1L, authorization = authorization).copy(
				registrationGeneration = 2L,
				physicalConfigurationFingerprint = CONTROL_PHYSICAL_CONFIG,
				registrationPurposeEligibilityMask = SourceBrokerPurpose.MASK_CONTROL_AUTOSTART,
				registrationEligibilityFingerprint = CONTROL_ONLY_FINGERPRINT,
			),
		).shouldBeInstanceOf<AdmissionResult.Admitted>()

		val event = subject.committedBatch(0L, 1).single().evidence
		event.logicalTrackingId shouldBe null
		event.serviceRunId shouldBe null
		event.sourcePolicyRevision shouldBe null
		event.captureConsentEpoch shouldBe null
		event.sessionManifestRevision shouldBe null
		event.lifecycleLeaseGeneration shouldBe null
		event.registrationPurposeEligibilityMask shouldBe SourceBrokerPurpose.MASK_CONTROL_AUTOSTART
		event.planAttribution shouldBe PlanAttribution.RECEIVE_TIME_ONLY
	}

	@Test
	fun `current immutable manifest admits session qualified evidence`() = runTest {
		val authorization = installSession()

		val result = subject.admit(sessionCandidate(sequence = 1L, authorization = authorization))
			.shouldBeInstanceOf<AdmissionResult.Admitted>()

		val event = subject.committedBatch(0L, 1).single().evidence
		event.sessionManifestRevision shouldBe 1L
		event.lifecycleLeaseGeneration shouldBe 7L
	}

	@Test
	fun `authorization stop boundary admits pre boundary and rejects exact boundary`() = runTest {
		installSession(
			sessionState = "STOPPING",
			runState = "STOPPING",
			cutoffElapsedNanos = 100L,
		)
		database.sourceBrokerDao().insertAuthorizations(
			SourceBrokerAuthorization.rows(
				SourceKind.ACTIVITY.stableCode,
				1L,
				2L,
				emptyList(),
				"boot",
				100L,
				100L,
			),
		)

		subject.admit(
			candidate(sequence = 1L, observedElapsedNanos = 99L),
		).shouldBeInstanceOf<AdmissionResult.Admitted>()
		val rejected = subject.admit(
			candidate(sequence = 2L, observedElapsedNanos = 100L),
		).shouldBeInstanceOf<AdmissionResult.PermanentFailure>()

		rejected.code shouldBe AdmissionFailureCode.STALE_SOURCE_POLICY
		database.sourceEventWalDao().countAll() shouldBe 1L
	}

	@Test
	fun `revocation uses observed authorization boundary instead of candidate policy stamp`() = runTest {
		val settings = TrackingParamsState(legacySettingsMigrationCompleted = true)
		val policy = RoomSourcePolicyRepository(database) {
			SourcePolicyEffectiveTime("boot", 100L, 100L)
		}
		val current = policy.bootstrapFromLegacy(settings)
		val staleCandidate = candidate(sequence = 1L, observedElapsedNanos = 99L).copy(
			sourcePolicyRevision = current.revision,
			captureConsentEpoch = requireNotNull(
				current[TrackingSourceComponent.ACTIVITY].captureConsentEpoch,
			),
		)
		policy.replaceCaptureSettings(
			expectedPolicyRevision = current.revision,
			settings = settings.copy(
				activityEnabled = false,
				sourceCollectionSettings = settings.sourceCollectionSettings.copy(
					activity = SourceCollectionFrequency.OFF,
				),
			),
			reason = "TEST_REVOKE",
		)
		database.sourceBrokerDao().insertAuthorizations(
			SourceBrokerAuthorization.rows(
				SourceKind.ACTIVITY.stableCode,
				1L,
				2L,
				emptyList(),
				"boot",
				100L,
				100L,
			),
		)

		subject.admit(staleCandidate).shouldBeInstanceOf<AdmissionResult.Admitted>()
		val result = subject.admit(staleCandidate.copy(sourceSequence = 2L, observedElapsedRealtimeNanos = 100L))
			.shouldBeInstanceOf<AdmissionResult.PermanentFailure>()

		result.code shouldBe AdmissionFailureCode.STALE_SOURCE_POLICY
		database.sourceEventWalDao().countAll() shouldBe 1L
	}

	private fun candidate(
		sequence: Long,
		activityType: Int = 3,
		epoch: Long = 0L,
		acquiredAtMs: Long = 100L,
		providerDedupKey: String? = null,
		observedElapsedNanos: Long = 100L,
	) = SourceEvidenceCandidate(
		providerDedupKey = providerDedupKey,
		logicalTrackingId = null,
		serviceRunId = null,
		source = SourceKind.ACTIVITY,
		sourceInstanceId = SourceInstanceId("activity-instance"),
		registrationGeneration = 1L,
		physicalConfigurationFingerprint = TEST_PHYSICAL_CONFIG,
		registrationPurposeEligibilityMask = TEST_ELIGIBILITY_MASK,
		registrationEligibilityFingerprint = TEST_ELIGIBILITY_FINGERPRINT,
		sourceSequence = sequence,
		configRevision = 1L,
		planAttribution = PlanAttribution.CAPTURED_REGISTRATION,
		clockDomainId = "boot",
		observedElapsedRealtimeNanos = observedElapsedNanos,
		receivedElapsedRealtimeNanos = 110L,
		wallTimeMs = acquiredAtMs,
		wallTimeUncertaintyMs = 1L,
		capturedCollectedDataEpoch = epoch,
		acquiredAtMs = acquiredAtMs,
		quality = SourceQuality(),
		payloadVersion = 1,
		payload = ActivityTransitionPayload(activityType, 1, 100L),
	)

	private fun sessionCandidate(
		sequence: Long,
		authorization: SessionAuthorization,
		observedElapsedNanos: Long = 100L,
	) = candidate(sequence = sequence, observedElapsedNanos = observedElapsedNanos).copy(
		logicalTrackingId = LogicalTrackingId(TEST_SESSION_ID),
		serviceRunId = ServiceRunId(TEST_RUN_ID),
		sourcePolicyRevision = authorization.policyRevision,
		captureConsentEpoch = authorization.captureConsentEpoch,
		sessionManifestRevision = 1L,
		lifecycleLeaseGeneration = 7L,
	)

	private suspend fun installSession(
		sessionState: String = "ACTIVE",
		runState: String = "ACTIVE",
		cutoffElapsedNanos: Long? = null,
	): SessionAuthorization {
		val policyRepository = RoomSourcePolicyRepository(database) {
			SourcePolicyEffectiveTime("boot", 50L, 50L)
		}
		policyRepository.bootstrapFromLegacy(TrackingParamsState(legacySettingsMigrationCompleted = true))
		val authority = requireNotNull(database.sourcePolicyDao().authority())
		val policy = requireNotNull(
			database.sourcePolicyDao().policyAtRevision(
				authority.currentPolicyRevision,
				SourceKind.ACTIVITY.stableCode,
			),
		)
		val sessionDao = database.sourceSessionDao()
		sessionDao.insertSession(
			LogicalTrackingSessionEntity(
				logicalTrackingId = TEST_SESSION_ID,
				state = sessionState,
				lifecycleRevision = 1L,
				desiredPlanRevision = 1L,
				rolloutRevision = 1L,
				startOrigin = "MANUAL_FOREGROUND_START",
				clockDomainId = "boot",
				startedAtMs = 50L,
				startedElapsedNanos = 50L,
				cutoffAtMs = cutoffElapsedNanos,
				cutoffElapsedNanos = cutoffElapsedNanos,
				completedAtMs = null,
				finalAdmissionOrdinal = null,
				failureCode = null,
				sessionMode = "MANUAL",
				currentManifestRevision = 1L,
				currentIntentRevision = 1L,
				lifecycleLeaseGeneration = 7L,
				lifecycleBootId = "boot",
				automationEpoch = null,
			),
		)
		sessionDao.insertManifest(
			SessionManifestVersionEntity(
				logicalTrackingId = TEST_SESSION_ID,
				manifestRevision = 1L,
				sessionMode = "MANUAL",
				sourcePolicyRevision = authority.currentPolicyRevision,
				acquisitionPlanRevision = 1L,
				rolloutRevision = 1L,
				startOrigin = "MANUAL_FOREGROUND_START",
				effectiveBootId = "boot",
				effectiveElapsedRealtimeNanos = 50L,
				effectiveWallTimeMs = 50L,
				zoneId = "UTC",
				automationEpoch = null,
				changeReason = "TEST",
				manifestChecksum = "test-manifest",
			),
		)
		sessionDao.insertManifestSources(
			listOf(
				SessionManifestSourceEntity(
					logicalTrackingId = TEST_SESSION_ID,
					manifestRevision = 1L,
					sourceKind = SourceKind.ACTIVITY.stableCode,
					purpose = "SESSION_CAPTURE",
					consentEpoch = requireNotNull(policy.captureConsentEpoch),
					persistenceEligible = true,
					qosCode = policy.qosCode,
				),
			),
		)
		sessionDao.insertServiceRun(
			SourceServiceRunEntity(
				serviceRunId = TEST_RUN_ID,
				logicalTrackingId = TEST_SESSION_ID,
				state = runState,
				desiredPlanRevision = 1L,
				rolloutRevision = 1L,
				foregroundCapabilityFlags = 0L,
				startedAtMs = 50L,
				startedElapsedNanos = 50L,
				completedAtMs = null,
				completionReason = null,
				bootId = "boot",
				leaseGeneration = 7L,
				startOrigin = "MANUAL_FOREGROUND_START",
				desiredForegroundCapabilityFlags = 0L,
				appliedForegroundCapabilityFlags = 0L,
				runtimeAcknowledgement = "START_ACCEPTED",
				runtimeFailureCode = null,
				runRevision = 1L,
			),
		)
		return SessionAuthorization(
			policyRevision = authority.currentPolicyRevision,
			captureConsentEpoch = requireNotNull(policy.captureConsentEpoch),
		)
	}

	private suspend fun installRegistrationGeneration() {
		database.sourceBrokerDao().insertRegistration(
			ProviderRegistrationGenerationEntity(
				sourceKind = SourceKind.ACTIVITY.stableCode,
				registrationGeneration = 1L,
				sourceInstanceId = "activity-instance",
				ownerScope = "source-broker:${SourceKind.ACTIVITY.stableCode}",
				clockDomainId = "boot",
				physicalConfigurationFingerprint = TEST_PHYSICAL_CONFIG,
				collectedDataEpoch = 0L,
				status = ProviderRegistrationGenerationEntity.STATUS_ACTIVE,
				reservedAtMs = 50L,
				reservedElapsedRealtimeNanos = 40L,
				acceptedAtMs = 50L,
				acceptedElapsedRealtimeNanos = 50L,
				retiredAtMs = null,
				retiredElapsedRealtimeNanos = null,
				failureCode = null,
			),
		)
		database.sourceBrokerDao().insertAuthorizations(
			listOf(
				SourceAuthorizationEntity(
					sourceKind = SourceKind.ACTIVITY.stableCode,
					registrationGeneration = 1L,
					authorizationRevision = 1L,
					memberId = "demand:ambient-demand",
					authorizationFingerprint = TEST_ELIGIBILITY_FINGERPRINT,
					purposeEligibilityMask = TEST_ELIGIBILITY_MASK,
					demandId = "ambient-demand",
					consumerId = "app:test",
					purpose = SourceBrokerPurpose.AMBIENT_PRODUCT,
					sourcePolicyRevision = testPolicyRevision,
					consentEpoch = testAmbientEpoch,
					persistenceEligible = true,
					effectiveBootId = "boot",
					effectiveElapsedRealtimeNanos = 0L,
					effectiveWallTimeMs = 0L,
					logicalTrackingId = null,
					serviceRunId = null,
					manifestRevision = null,
					lifecycleLeaseGeneration = null,
				),
				SourceAuthorizationEntity(
					sourceKind = SourceKind.ACTIVITY.stableCode,
					registrationGeneration = 1L,
					authorizationRevision = 1L,
					memberId = "demand:session-demand",
					authorizationFingerprint = TEST_ELIGIBILITY_FINGERPRINT,
					purposeEligibilityMask = TEST_ELIGIBILITY_MASK,
					demandId = "session-demand",
					consumerId = "session:$TEST_SESSION_ID",
					purpose = SourceBrokerPurpose.SESSION_CAPTURE,
					sourcePolicyRevision = testPolicyRevision,
					consentEpoch = testCaptureEpoch,
					persistenceEligible = true,
					effectiveBootId = "boot",
					effectiveElapsedRealtimeNanos = 0L,
					effectiveWallTimeMs = 0L,
					logicalTrackingId = TEST_SESSION_ID,
					serviceRunId = TEST_RUN_ID,
					manifestRevision = 1L,
					lifecycleLeaseGeneration = 7L,
				),
			),
		)
	}

	private suspend fun installControlOnlyGeneration() {
		database.sourceBrokerDao().insertRegistration(
			ProviderRegistrationGenerationEntity(
				sourceKind = SourceKind.ACTIVITY.stableCode,
				registrationGeneration = 2L,
				sourceInstanceId = "activity-instance",
				ownerScope = "source-broker:${SourceKind.ACTIVITY.stableCode}",
				clockDomainId = "boot",
				physicalConfigurationFingerprint = CONTROL_PHYSICAL_CONFIG,
				collectedDataEpoch = 0L,
				status = ProviderRegistrationGenerationEntity.STATUS_ACTIVE,
				reservedAtMs = 60L,
				reservedElapsedRealtimeNanos = 50L,
				acceptedAtMs = 60L,
				acceptedElapsedRealtimeNanos = 60L,
				retiredAtMs = null,
				retiredElapsedRealtimeNanos = null,
				failureCode = null,
			),
		)
		database.sourceBrokerDao().insertAuthorizations(
			listOf(
				SourceAuthorizationEntity(
					sourceKind = SourceKind.ACTIVITY.stableCode,
					registrationGeneration = 2L,
					authorizationRevision = 2L,
					memberId = "demand:control-demand",
					authorizationFingerprint = CONTROL_ONLY_FINGERPRINT,
					purposeEligibilityMask = SourceBrokerPurpose.MASK_CONTROL_AUTOSTART,
					demandId = "control-demand",
					consumerId = "app:automation",
					purpose = SourceBrokerPurpose.CONTROL_AUTOSTART,
					sourcePolicyRevision = 1L,
					consentEpoch = 1L,
					persistenceEligible = false,
					effectiveBootId = "boot",
					effectiveElapsedRealtimeNanos = 0L,
					effectiveWallTimeMs = 0L,
					logicalTrackingId = null,
					serviceRunId = null,
					manifestRevision = null,
					lifecycleLeaseGeneration = null,
				),
			),
		)
	}

	private data class SessionAuthorization(
		val policyRevision: Long,
		val captureConsentEpoch: Long,
	)

	private companion object {
		const val TEST_SESSION_ID = "session"
		const val TEST_RUN_ID = "run"
		const val TEST_ELIGIBILITY_FINGERPRINT = "test-ambient-and-session"
		const val CONTROL_ONLY_FINGERPRINT = "test-control-only"
		const val TEST_PHYSICAL_CONFIG = "physical-config"
		const val CONTROL_PHYSICAL_CONFIG = "control-physical-config"
		const val TEST_ELIGIBILITY_MASK = SourceBrokerPurpose.MASK_AMBIENT_PRODUCT or
			SourceBrokerPurpose.MASK_SESSION_CAPTURE
	}
}

private class FakeLifecycleStore(initial: CollectedDataLifecycleSnapshot) : CollectedDataLifecycleStore {
	private val state = MutableStateFlow(initial)
	override val snapshots: Flow<CollectedDataLifecycleSnapshot> = state
	override suspend fun snapshot(): CollectedDataLifecycleSnapshot = state.value
	override suspend fun beginFullDeletion(deletedAtMs: Long): CollectedDataLifecycleSnapshot {
		val updated = state.value.copy(epoch = state.value.epoch + 1L, retainedFromMs = deletedAtMs)
		state.emit(updated)
		return updated
	}
	override suspend fun advanceRetainedFrom(retainedFromMs: Long): CollectedDataLifecycleSnapshot {
		val updated = state.value.copy(retainedFromMs = retainedFromMs)
		state.emit(updated)
		return updated
	}
	suspend fun update(value: CollectedDataLifecycleSnapshot) = state.emit(value)
}
