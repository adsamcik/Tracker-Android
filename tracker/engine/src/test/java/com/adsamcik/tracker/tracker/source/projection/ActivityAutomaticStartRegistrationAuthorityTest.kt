package com.adsamcik.tracker.tracker.source.projection

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.activity.ActivityTransitionType
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.ActivityAutomaticStartActionEntity
import com.adsamcik.tracker.shared.base.database.data.ActivityAutomationEpochEntity
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.stats.api.DetectedActivityType
import com.adsamcik.tracker.tracker.api.ActivityAutomationDeliveryEnvelope
import com.adsamcik.tracker.tracker.resilience.AutomaticTrackingStartContext
import com.adsamcik.tracker.tracker.resilience.AutomaticTrackingStartTrigger
import com.adsamcik.tracker.tracker.source.model.SourceKind
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActivityAutomaticStartRegistrationAuthorityTest {
	private lateinit var database: AppDatabase
	private lateinit var subject: ActivityAutomaticStartActionRepository

	@Before
	fun setUp() {
		val application = ApplicationProvider.getApplicationContext<Application>()
		database = AppDatabase.testDatabase(application)
		subject = ActivityAutomaticStartActionRepository(
			database,
			RegistrationAuthorityTrackingStartupGate(),
		)
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `missing provider generation rejects automatic start reservation`() = runTest {
		seedAuthority(registration = null)

		subject.reserve(reservation()) shouldBe ActivityAutomaticStartReserveResult.Rejected(
			"AUTOMATIC_START_REGISTRATION_MISSING",
		)
	}

	@Test
	fun `failed provider generation rejects automatic start reservation`() = runTest {
		seedAuthority(
			registration = activityRegistration(
				status = ProviderRegistrationGenerationEntity.STATUS_FAILED,
				acceptedElapsedRealtimeNanos = null,
				retiredElapsedRealtimeNanos = 900L,
			),
		)

		subject.reserve(reservation()) shouldBe ActivityAutomaticStartReserveResult.Rejected(
			"AUTOMATIC_START_REGISTRATION_FAILED",
		)
	}

	@Test
	fun `unaccepted provider generation rejects automatic start reservation`() = runTest {
		seedAuthority(
			registration = activityRegistration(
				status = ProviderRegistrationGenerationEntity.STATUS_RESERVED,
				acceptedElapsedRealtimeNanos = null,
			),
		)

		subject.reserve(reservation()) shouldBe ActivityAutomaticStartReserveResult.Rejected(
			"AUTOMATIC_START_EVIDENCE_PREDATES_REGISTRATION_ACCEPTANCE",
		)
	}

	@Test
	fun `evidence before provider acceptance floor rejects automatic start reservation`() = runTest {
		seedAuthority(
			registration = activityRegistration(acceptedElapsedRealtimeNanos = 1_002L),
		)

		subject.reserve(reservation()) shouldBe ActivityAutomaticStartReserveResult.Rejected(
			"AUTOMATIC_START_EVIDENCE_PREDATES_REGISTRATION_ACCEPTANCE",
		)
	}

	@Test
	fun `evidence at provider retirement cutoff rejects automatic start reservation`() = runTest {
		seedAuthority(
			registration = activityRegistration(
				status = ProviderRegistrationGenerationEntity.STATUS_RETIRED,
				retiredElapsedRealtimeNanos = 1_001L,
			),
		)

		subject.reserve(reservation()) shouldBe ActivityAutomaticStartReserveResult.Rejected(
			"AUTOMATIC_START_EVIDENCE_AT_OR_AFTER_REGISTRATION_CUTOFF",
		)
	}

	@Test
	fun `retired provider still authorizes evidence observed inside its historical interval`() =
		runTest {
			seedAuthority(
				registration = activityRegistration(
					status = ProviderRegistrationGenerationEntity.STATUS_RETIRED,
					retiredElapsedRealtimeNanos = 2_000L,
				),
			)
			val request = reservation()

			(subject.reserve(request) is ActivityAutomaticStartReserveResult.Reserved) shouldBe true
			(subject.authorizeExternalStart(request.trigger, 2_000L) is
				ActivityAutomaticStartRequestAuthorization.Authorized) shouldBe true
			(subject.validateForService(request.trigger) is
				ActivityAutomaticStartServiceValidation.Valid) shouldBe true
		}

	@Test
	fun `registration boot and collected data epoch are exact authority`() = runTest {
		seedAuthority()

		database.activityAutomaticRegistrationFailure(
			registrationGeneration = REGISTRATION_GENERATION,
			bootId = "other-boot",
			collectedDataEpoch = COLLECTED_DATA_EPOCH,
			observedElapsedRealtimeNanos = 1_001L,
		) shouldBe "AUTOMATIC_START_REGISTRATION_IDENTITY_MISMATCH"
		database.activityAutomaticRegistrationFailure(
			registrationGeneration = REGISTRATION_GENERATION,
			bootId = BOOT_ID,
			collectedDataEpoch = COLLECTED_DATA_EPOCH + 1L,
			observedElapsedRealtimeNanos = 1_001L,
		) shouldBe "AUTOMATIC_START_REGISTRATION_IDENTITY_MISMATCH"
	}

	@Test
	fun `retirement after reservation fences the Android start request`() = runTest {
		seedAuthority()
		val request = reservation()
		(subject.reserve(request) is ActivityAutomaticStartReserveResult.Reserved) shouldBe true
		retireRegistrationAt(request.trigger.observedElapsedRealtimeNanos)

		subject.authorizeExternalStart(request.trigger, 2_000L) shouldBe
			ActivityAutomaticStartRequestAuthorization.Rejected(
				"AUTOMATIC_START_EVIDENCE_AT_OR_AFTER_REGISTRATION_CUTOFF",
			)
	}

	@Test
	fun `retirement after Android enqueue fences delayed service acceptance`() = runTest {
		seedAuthority()
		val request = reservation()
		(subject.reserve(request) is ActivityAutomaticStartReserveResult.Reserved) shouldBe true
		(subject.authorizeExternalStart(request.trigger, 2_000L) is
			ActivityAutomaticStartRequestAuthorization.Authorized) shouldBe true
		retireRegistrationAt(request.trigger.observedElapsedRealtimeNanos)

		subject.validateForService(request.trigger) shouldBe
			ActivityAutomaticStartServiceValidation.Rejected(
				"AUTOMATIC_START_EVIDENCE_AT_OR_AFTER_REGISTRATION_CUTOFF",
			)
	}

	@Test
	fun `retirement while enqueued terminalizes cold recovery before lifecycle intent`() = runTest {
		seedAuthority()
		val request = reservation()
		(subject.reserve(request) is ActivityAutomaticStartReserveResult.Reserved) shouldBe true
		(subject.authorizeExternalStart(request.trigger, 2_000L) is
			ActivityAutomaticStartRequestAuthorization.Authorized) shouldBe true
		retireRegistrationAt(request.trigger.observedElapsedRealtimeNanos)

		subject.reconcileLifecycleIntentAcceptance(
			triggerId = request.trigger.triggerId,
			currentElapsedRealtimeNanos = 2_000L,
			wallTimeMs = 2_100L,
		) shouldBe ActivityAutomaticStartAcceptance.Terminal(
			"AUTOMATIC_START_EVIDENCE_AT_OR_AFTER_REGISTRATION_CUTOFF",
		)
		database.activityAutomaticStartActionDao().current()?.status shouldBe
			ActivityAutomaticStartActionEntity.STATUS_TERMINAL
	}

	private suspend fun seedAuthority(
		registration: ProviderRegistrationGenerationEntity? = activityRegistration(),
	) {
		database.activityAutomationEpochDao().ensure(
			ActivityAutomationEpochEntity(
				epoch = AUTOMATION_EPOCH,
				automaticControlEnabled = true,
				bootClockDomainId = BOOT_ID,
				effectiveElapsedRealtimeNanos = 0L,
				lastRotationReason = "TEST_SEED",
				updatedAtMs = 1_000L,
			),
		)
		database.sourceEvidenceStateDao().ensure(
			SourceEvidenceState(collectedDataEpoch = COLLECTED_DATA_EPOCH),
		)
		database.sourcePolicyDao().ensureAuthority(
			SourcePolicyAuthorityEntity(
				bootstrapState = SourcePolicyAuthorityEntity.STATE_ACTIVE,
				currentPolicyRevision = POLICY_REVISION,
				legacySettingsFingerprint = null,
				updatedAtMs = 1_000L,
			),
		)
		database.sourcePolicyDao().insertPolicies(listOf(activityPolicy()))
		database.sourcePolicyDao().insertConsentEpochs(listOf(activityControlConsent()))
		registration?.let { database.sourceBrokerDao().insertRegistration(it) }
		database.sourceBrokerDao().insertAuthorizations(listOf(activityAuthorization()))
	}

	private fun activityPolicy() = SourcePolicyEntity(
		policyRevision = POLICY_REVISION,
		sourceKind = SourceKind.ACTIVITY.stableCode,
		enabled = true,
		qosCode = 1,
		locationMinTimeSeconds = null,
		locationMinDistanceMeters = null,
		locationRequiredAccuracyMeters = null,
		capturePersistenceEligible = true,
		controlPersistenceEligible = false,
		ambientPersistenceEligible = false,
		captureConsentEpoch = 20L,
		controlConsentEpoch = CONTROL_CONSENT_EPOCH,
		ambientConsentEpoch = null,
		effectiveBootId = BOOT_ID,
		effectiveElapsedRealtimeNanos = 500L,
		effectiveWallTimeMs = 500L,
		changeReason = "TEST",
	)

	private fun activityControlConsent() = SourceConsentEpochEntity(
		sourceKind = SourceKind.ACTIVITY.stableCode,
		purpose = "CONTROL",
		epoch = CONTROL_CONSENT_EPOCH,
		eligible = true,
		persistenceEligible = false,
		policyRevision = POLICY_REVISION,
		effectiveBootId = BOOT_ID,
		effectiveElapsedRealtimeNanos = 500L,
		effectiveWallTimeMs = 500L,
		changeReason = "TEST",
	)

	private fun activityAuthorization() = SourceAuthorizationEntity(
		sourceKind = SourceKind.ACTIVITY.stableCode,
		registrationGeneration = REGISTRATION_GENERATION,
		authorizationRevision = AUTHORIZATION_REVISION,
		memberId = "activity-control",
		authorizationFingerprint = AUTHORIZATION_FINGERPRINT,
		purposeEligibilityMask = SourceBrokerPurpose.MASK_CONTROL_AUTOSTART,
		demandId = "activity-control-demand",
		consumerId = "automatic-start",
		purpose = SourceBrokerPurpose.CONTROL_AUTOSTART,
		sourcePolicyRevision = POLICY_REVISION,
		consentEpoch = CONTROL_CONSENT_EPOCH,
		persistenceEligible = false,
		effectiveBootId = BOOT_ID,
		effectiveElapsedRealtimeNanos = 500L,
		effectiveWallTimeMs = 500L,
		logicalTrackingId = null,
		serviceRunId = null,
		manifestRevision = null,
		lifecycleLeaseGeneration = null,
	)

	private fun activityRegistration(
		status: String = ProviderRegistrationGenerationEntity.STATUS_ACTIVE,
		acceptedElapsedRealtimeNanos: Long? = 500L,
		retiredElapsedRealtimeNanos: Long? = null,
	) = ProviderRegistrationGenerationEntity(
		sourceKind = SourceKind.ACTIVITY.stableCode,
		registrationGeneration = REGISTRATION_GENERATION,
		sourceInstanceId = SOURCE_INSTANCE_ID,
		ownerScope = "activity-automatic-control",
		clockDomainId = BOOT_ID,
		physicalConfigurationFingerprint = "activity-physical",
		collectedDataEpoch = COLLECTED_DATA_EPOCH,
		providerResidency = ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE,
		providerProcessIncarnationId = null,
		status = status,
		reservedAtMs = 100L,
		reservedElapsedRealtimeNanos = 100L,
		acceptedAtMs = acceptedElapsedRealtimeNanos?.let { 500L },
		acceptedElapsedRealtimeNanos = acceptedElapsedRealtimeNanos,
		retiredAtMs = retiredElapsedRealtimeNanos?.let { 2_000L },
		retiredElapsedRealtimeNanos = retiredElapsedRealtimeNanos,
		failureCode = if (status == ProviderRegistrationGenerationEntity.STATUS_FAILED) {
			"TEST_REGISTRATION_FAILED"
		} else {
			null
		},
	)

	private fun reservation(): ActivityAutomaticStartReservation {
		val evidence = ActivityAutomationDeliveryEnvelope(
			admissionOrdinal = 1L,
			activityType = DetectedActivityType.WALKING,
			confidence = 100,
			transitionType = ActivityTransitionType.ENTER,
			clockDomainId = BOOT_ID,
			observedElapsedRealtimeNanos = OBSERVED_ELAPSED_REALTIME_NANOS,
			receivedElapsedRealtimeNanos = 1_101L,
			registrationGeneration = REGISTRATION_GENERATION,
			authorizationRevision = AUTHORIZATION_REVISION,
			authorizationFingerprint = AUTHORIZATION_FINGERPRINT,
			collectedDataEpoch = COLLECTED_DATA_EPOCH,
			automationEpoch = AUTOMATION_EPOCH,
		)
		return ActivityAutomaticStartReservation(
			trigger = AutomaticTrackingStartTrigger(
				triggerId = "activity-transition:$BOOT_ID:1",
				kind = "ACTIVITY_TRANSITION:WALKING:ENTER",
				bootId = BOOT_ID,
				observedElapsedRealtimeNanos = evidence.observedElapsedRealtimeNanos,
				receivedElapsedRealtimeNanos = evidence.receivedElapsedRealtimeNanos,
				expiresElapsedRealtimeNanos = 60_000_001_001L,
				automationEpoch = AUTOMATION_EPOCH,
				startContext = AutomaticTrackingStartContext.ACTIVITY_TRANSITION_CALLBACK,
				sourcePolicyRevision = POLICY_REVISION,
				intendedCaptureSourceMask = ACTIVITY_MASK,
				requestedCaptureSourceMask = ACTIVITY_MASK,
				intendedForegroundServiceTypeMask = FGS_TYPE_MASK,
				collectedDataEpoch = COLLECTED_DATA_EPOCH,
			),
			effectStableId = "activity-effect-1",
			evidence = evidence,
			controlConsentEpoch = CONTROL_CONSENT_EPOCH,
			reservedAtMs = 1_500L,
		)
	}

	private suspend fun retireRegistrationAt(cutoffElapsedRealtimeNanos: Long) {
		database.sourceBrokerDao().markRegistrationRetiring(
			sourceKind = SourceKind.ACTIVITY.stableCode,
			registrationGeneration = REGISTRATION_GENERATION,
			sourceInstanceId = SOURCE_INSTANCE_ID,
			retiredAtMs = 2_000L,
			retiredElapsedRealtimeNanos = cutoffElapsedRealtimeNanos,
			reason = "TEST_RETIREMENT",
		) shouldBe 1
		database.sourceBrokerDao().completeRegistrationRetirement(
			sourceKind = SourceKind.ACTIVITY.stableCode,
			registrationGeneration = REGISTRATION_GENERATION,
			sourceInstanceId = SOURCE_INSTANCE_ID,
		) shouldBe 1
	}

	private companion object {
		const val BOOT_ID = "boot-7"
		const val SOURCE_INSTANCE_ID = "activity-provider-5"
		const val POLICY_REVISION = 23L
		const val AUTOMATION_EPOCH = 17L
		const val CONTROL_CONSENT_EPOCH = 13L
		const val COLLECTED_DATA_EPOCH = 7L
		const val REGISTRATION_GENERATION = 5L
		const val AUTHORIZATION_REVISION = 6L
		const val AUTHORIZATION_FINGERPRINT = "activity-control-authorization"
		const val OBSERVED_ELAPSED_REALTIME_NANOS = 1_001L
		const val ACTIVITY_MASK = 2L
		const val FGS_TYPE_MASK = 256L
	}
}

private class RegistrationAuthorityTrackingStartupGate : TrackingStartupGate {
	override val isReady: Boolean = true
	override val currentGeneration: Long = 1L

	override suspend fun reconcile(retryFailedStorage: Boolean): TrackingStartupResult =
		TrackingStartupResult.Ready(legacyRecoveryPartial = false, liveCompletedThroughOrdinal = 0L)
}
