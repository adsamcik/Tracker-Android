package com.adsamcik.tracker.tracker.source.projection

import android.app.Application
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.activity.ActivityTransitionType
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.synchronizeLifecycle
import com.adsamcik.tracker.shared.base.database.rotateActivityAutomationEpochInTransaction
import com.adsamcik.tracker.shared.base.database.data.ActivityAutomationEpochEntity
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionLifecycleIntentVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
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
class ActivityAutomaticStartActionRepositoryTest {
	private lateinit var database: AppDatabase
	private lateinit var gate: FakeTrackingStartupGate
	private lateinit var subject: ActivityAutomaticStartActionRepository

	@Before
	fun setUp() {
		val application = ApplicationProvider.getApplicationContext<Application>()
		database = AppDatabase.testDatabase(application)
		gate = FakeTrackingStartupGate()
		subject = ActivityAutomaticStartActionRepository(database, gate)
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `reservation and platform request remain pending until lifecycle intent is durable`() = runTest {
		seedAuthority()
		val request = reservation()

		subject.reserve(request) shouldBe ActivityAutomaticStartReserveResult.Reserved(
			requireNotNull(database.activityAutomaticStartActionDao().current()),
		)
		subject.authorizeExternalStart(request.trigger, requestedAtMs = 2_000) shouldBe
			ActivityAutomaticStartRequestAuthorization.Authorized(
				requireNotNull(database.activityAutomaticStartActionDao().current()),
			)
		database.activityAutomaticStartActionDao().current()?.status shouldBe
			"START_REQUESTED"

		val pending = subject.reconcileLifecycleIntentAcceptance(
			request.trigger.triggerId,
			currentElapsedRealtimeNanos = 2_000,
			wallTimeMs = 2_100,
		)
		pending shouldBe ActivityAutomaticStartAcceptance.Pending(
			requireNotNull(database.activityAutomaticStartActionDao().current()),
		)
	}

	@Test
	fun `cold replay waits for an unexpired Android delivery then terminalizes at expiry`() = runTest {
		seedAuthority()
		val request = reservation()
		subject.reserve(request)
		subject.authorizeExternalStart(request.trigger, requestedAtMs = 2_000)

		(subject.reconcileLifecycleIntentAcceptance(
			request.trigger.triggerId,
			request.trigger.expiresElapsedRealtimeNanos,
			2_100,
		) is ActivityAutomaticStartAcceptance.Pending) shouldBe true
		subject.reconcileLifecycleIntentAcceptance(
			request.trigger.triggerId,
			request.trigger.expiresElapsedRealtimeNanos + 1,
			2_200,
		) shouldBe ActivityAutomaticStartAcceptance.Terminal(
			"AUTOMATIC_START_LIFECYCLE_INTENT_EXPIRED",
		)
		database.activityAutomaticStartActionDao().current()?.status shouldBe "TERMINAL"
	}

	@Test
	fun `two triggers serialize and delayed mutation cannot alter a reused slot`() = runTest {
		seedAuthority()
		val first = reservation()
		val second = reservation(ordinal = 2)
		subject.reserve(first)
		subject.authorizeExternalStart(first.trigger, requestedAtMs = 2_000)

		subject.reserve(second) shouldBe ActivityAutomaticStartReserveResult.ConflictingCurrent(
			requireNotNull(database.activityAutomaticStartActionDao().current()),
		)
		subject.markTerminalExact(first.trigger, 2_100, "PLATFORM_REJECTED") shouldBe true
		(subject.reserve(second) is ActivityAutomaticStartReserveResult.Reserved) shouldBe true

		subject.markTerminalExact(first.trigger, 2_200, "DELAYED_OLD_CALLER") shouldBe false
		database.activityAutomaticStartActionDao().current()?.triggerId shouldBe second.trigger.triggerId
		database.activityAutomaticStartActionDao().current()?.status shouldBe "RESERVED"
	}

	@Test
	fun `deletion epoch clears authority and old caller cannot mutate the replacement`() = runTest {
		seedAuthority()
		val old = reservation()
		subject.reserve(old)
		subject.authorizeExternalStart(old.trigger, requestedAtMs = 2_000)

		database.withTransaction {
			database.sourceEvidenceStateDao().synchronizeLifecycle(8, null, 3_000)
			database.activityAutomaticStartActionDao().deleteAll()
		}
		val replacement = reservation(ordinal = 2, collectedDataEpoch = 8)
		(subject.reserve(replacement) is ActivityAutomaticStartReserveResult.Reserved) shouldBe true

		subject.markTerminalExact(old.trigger, 3_100, "DELAYED_PRE_DELETION_CALLER") shouldBe false
		subject.validateForService(old.trigger) shouldBe
			ActivityAutomaticStartServiceValidation.Rejected("STALE_COLLECTED_DATA_EPOCH")
		database.activityAutomaticStartActionDao().current()?.triggerId shouldBe
			replacement.trigger.triggerId
	}

	@Test
	fun `accepted outbox acknowledgement survives later session finalization while redelivery rejects`() = runTest {
		seedAuthority()
		val request = reservation()
		subject.reserve(request)
		subject.authorizeExternalStart(request.trigger, requestedAtMs = 2_000)
		insertAcceptedLifecycle(request.trigger)

		val accepted = subject.reconcileLifecycleIntentAcceptance(
			request.trigger.triggerId,
			currentElapsedRealtimeNanos = 3_000,
			wallTimeMs = 3_100,
		)
		(accepted is ActivityAutomaticStartAcceptance.Accepted) shouldBe true
		database.activityAutomaticStartActionDao().current()?.status shouldBe
			"LIFECYCLE_INTENT_ACCEPTED"

		val session = requireNotNull(database.sourceSessionDao().session(LOGICAL_ID))
		database.sourceSessionDao().updateSession(
			session.copy(state = "FINALIZED", completedAtMs = 3_200),
		) shouldBe 1
		subject.validateForService(request.trigger) shouldBe
			ActivityAutomaticStartServiceValidation.Rejected(
				"AUTOMATIC_START_ACCEPTED_SESSION_TERMINAL",
			)
		val finalizedAcceptance = subject.reconcileLifecycleIntentAcceptance(
			request.trigger.triggerId,
			3_300,
			3_300,
		)
		(finalizedAcceptance is ActivityAutomaticStartAcceptance.Accepted) shouldBe true
	}

	@Test
	fun `automatic lifecycle acceptance fails closed when manifest bindings were tampered`() = runTest {
		seedAuthority()
		val request = reservation()
		subject.reserve(request)
		subject.authorizeExternalStart(request.trigger, requestedAtMs = 2_000)
		insertAcceptedLifecycle(request.trigger)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE session_manifest_source SET qos_code = qos_code + 1 " +
				"WHERE logical_tracking_id = ? AND manifest_revision = 1 AND purpose = 'CONTROL'",
			arrayOf(LOGICAL_ID),
		)

		subject.reconcileLifecycleIntentAcceptance(
			request.trigger.triggerId,
			currentElapsedRealtimeNanos = 3_000,
			wallTimeMs = 3_100,
		) shouldBe ActivityAutomaticStartAcceptance.Terminal(
			"AUTOMATIC_START_LIFECYCLE_IDENTITY_COLLISION",
		)
		database.activityAutomaticStartActionDao().current()?.status shouldBe "TERMINAL"
	}

	@Test
	fun `policy change after reservation closes external start authority`() = runTest {
		seedAuthority()
		val request = reservation()
		(subject.reserve(request) is ActivityAutomaticStartReserveResult.Reserved) shouldBe true
		insertActivityPolicy(revision = POLICY_REVISION + 1, controlConsentEpoch = null)
		database.sourcePolicyDao().compareAndSetAuthority(
			expectedBootstrapState = SourcePolicyAuthorityEntity.STATE_ACTIVE,
			expectedRevision = POLICY_REVISION,
			bootstrapState = SourcePolicyAuthorityEntity.STATE_ACTIVE,
			newRevision = POLICY_REVISION + 1,
			legacySettingsFingerprint = null,
			updatedAtMs = 2_000,
		) shouldBe 1

		subject.authorizeExternalStart(request.trigger, requestedAtMs = 2_100) shouldBe
			ActivityAutomaticStartRequestAuthorization.Rejected(
				"AUTOMATIC_START_SOURCE_POLICY_SUPERSEDED",
			)
		database.activityAutomaticStartActionDao().current()?.status shouldBe "RESERVED"
	}

	@Test
	fun `current activity policy accepts its unchanged earlier control consent epoch`() = runTest {
		seedAuthority(consentPolicyRevision = POLICY_REVISION - 1L)
		val request = reservation()

		database.sourcePolicyDao().consentEpoch(
			SourceKind.ACTIVITY.stableCode,
			"CONTROL",
			CONTROL_CONSENT_EPOCH,
		)?.policyRevision shouldBe POLICY_REVISION - 1L
		val earlierPolicy = database.sourcePolicyDao().policyAtRevision(
			POLICY_REVISION - 1L,
			SourceKind.ACTIVITY.stableCode,
		)
		val currentPolicy = database.sourcePolicyDao().policyAtRevision(
			POLICY_REVISION,
			SourceKind.ACTIVITY.stableCode,
		)
		earlierPolicy?.controlConsentEpoch shouldBe currentPolicy?.controlConsentEpoch
		(subject.reserve(request) is ActivityAutomaticStartReserveResult.Reserved) shouldBe true
		(subject.authorizeExternalStart(request.trigger, requestedAtMs = 2_000) is
			ActivityAutomaticStartRequestAuthorization.Authorized) shouldBe true
		(subject.validateForService(request.trigger) is
			ActivityAutomaticStartServiceValidation.Valid) shouldBe true
	}

	@Test
	fun `newer control consent history fences a policy reference to the old epoch`() = runTest {
		seedAuthority()
		val request = reservation()
		(subject.reserve(request) is ActivityAutomaticStartReserveResult.Reserved) shouldBe true
		database.sourcePolicyDao().insertConsentEpochs(
			listOf(
				SourceConsentEpochEntity(
					sourceKind = SourceKind.ACTIVITY.stableCode,
					purpose = "CONTROL",
					epoch = CONTROL_CONSENT_EPOCH + 1L,
					eligible = false,
					persistenceEligible = false,
					policyRevision = POLICY_REVISION + 1L,
					effectiveBootId = BOOT_ID,
					effectiveElapsedRealtimeNanos = 1_900L,
					effectiveWallTimeMs = 1_900L,
					changeReason = "TEST_CONTROL_REVOKED",
				),
			),
		)

		subject.authorizeExternalStart(request.trigger, requestedAtMs = 2_000) shouldBe
			ActivityAutomaticStartRequestAuthorization.Rejected(
				"AUTOMATIC_START_CONTROL_CONSENT_INELIGIBLE",
			)
	}

	@Test
	fun `epoch rotation rejects old action with unchanged policy and accepts fresh epoch`() = runTest {
		seedAuthority()
		val old = reservation(automationEpoch = 17)
		(subject.reserve(old) is ActivityAutomaticStartReserveResult.Reserved) shouldBe true

		database.withTransaction {
			database.rotateActivityAutomationEpochInTransaction(
				reason = "TEST_ROTATION",
				updatedAtMs = 2_000,
				bootClockDomainId = BOOT_ID,
				effectiveElapsedRealtimeNanos = 2_000,
			)
		}
		database.activityAutomationEpochDao().current()?.epoch shouldBe 18L
		subject.authorizeExternalStart(old.trigger, 2_100) shouldBe
			ActivityAutomaticStartRequestAuthorization.Rejected(
				"AUTOMATIC_START_AUTOMATION_EPOCH_SUPERSEDED",
			)

		val fresh = reservation(ordinal = 1_001, automationEpoch = 18)
		(subject.reserve(fresh) is ActivityAutomaticStartReserveResult.Reserved) shouldBe true
		(subject.authorizeExternalStart(fresh.trigger, 2_200) is
			ActivityAutomaticStartRequestAuthorization.Authorized) shouldBe true
		(subject.validateForService(fresh.trigger) is
			ActivityAutomaticStartServiceValidation.Valid) shouldBe true
	}

	@Test
	fun `same numbered epoch rejects evidence observed before its durable effective boundary`() =
		runTest {
			seedAuthority(effectiveElapsedRealtimeNanos = 2_000L)

			subject.reserve(reservation()) shouldBe ActivityAutomaticStartReserveResult.Rejected(
				"AUTOMATIC_START_EVIDENCE_PREDATES_AUTOMATION_EPOCH",
			)
		}

	@Test
	fun `startup generation closing during reservation terminalizes the exact slot`() = runTest {
		seedAuthority()
		gate.advanceGenerationAfterFirstRead = true

		subject.reserve(reservation()) shouldBe ActivityAutomaticStartReserveResult.Rejected(
			"TRACKING_STARTUP_GENERATION_CLOSED_DURING_RESERVATION",
		)
		database.activityAutomaticStartActionDao().current()?.status shouldBe "TERMINAL"
		database.activityAutomaticStartActionDao().current()?.terminalReason shouldBe
			"TRACKING_STARTUP_GENERATION_CLOSED_DURING_RESERVATION"
	}

	private suspend fun seedAuthority(
		consentPolicyRevision: Long = POLICY_REVISION,
		effectiveElapsedRealtimeNanos: Long = 0L,
	) {
		database.activityAutomationEpochDao().ensure(
			ActivityAutomationEpochEntity(
				epoch = AUTOMATION_EPOCH,
				automaticControlEnabled = true,
				bootClockDomainId = BOOT_ID,
				effectiveElapsedRealtimeNanos = effectiveElapsedRealtimeNanos,
				lastRotationReason = "TEST_SEED",
				updatedAtMs = 1_000,
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
				updatedAtMs = 1_000,
			),
		)
		if (consentPolicyRevision != POLICY_REVISION) {
			insertActivityPolicy(consentPolicyRevision, CONTROL_CONSENT_EPOCH)
		}
		insertActivityPolicy(POLICY_REVISION, CONTROL_CONSENT_EPOCH)
		database.sourcePolicyDao().insertConsentEpochs(
			listOf(
				SourceConsentEpochEntity(
					sourceKind = SourceKind.ACTIVITY.stableCode,
					purpose = "CONTROL",
					epoch = CONTROL_CONSENT_EPOCH,
					eligible = true,
					persistenceEligible = false,
					policyRevision = consentPolicyRevision,
					effectiveBootId = BOOT_ID,
					effectiveElapsedRealtimeNanos = 500,
					effectiveWallTimeMs = 500,
					changeReason = "TEST",
				),
			),
		)
		database.sourceBrokerDao().insertAuthorizations(
			listOf(
				SourceAuthorizationEntity(
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
					effectiveElapsedRealtimeNanos = 500,
					effectiveWallTimeMs = 500,
					logicalTrackingId = null,
					serviceRunId = null,
					manifestRevision = null,
					lifecycleLeaseGeneration = null,
				),
			),
		)
	}

	private suspend fun insertActivityPolicy(revision: Long, controlConsentEpoch: Long?) {
		database.sourcePolicyDao().insertPolicies(
			listOf(
				SourcePolicyEntity(
					policyRevision = revision,
					sourceKind = SourceKind.ACTIVITY.stableCode,
					enabled = true,
					qosCode = 1,
					locationMinTimeSeconds = null,
					locationMinDistanceMeters = null,
					locationRequiredAccuracyMeters = null,
					capturePersistenceEligible = true,
					controlPersistenceEligible = false,
					ambientPersistenceEligible = false,
					captureConsentEpoch = 20,
					controlConsentEpoch = controlConsentEpoch,
					ambientConsentEpoch = null,
					effectiveBootId = BOOT_ID,
					effectiveElapsedRealtimeNanos = 500,
					effectiveWallTimeMs = 500,
					changeReason = "TEST",
				),
			),
		)
	}

	private fun reservation(
		ordinal: Long = 1,
		collectedDataEpoch: Long = COLLECTED_DATA_EPOCH,
		automationEpoch: Long = AUTOMATION_EPOCH,
	): ActivityAutomaticStartReservation {
		val evidence = ActivityAutomationDeliveryEnvelope(
			admissionOrdinal = ordinal,
			activityType = DetectedActivityType.WALKING,
			confidence = 100,
			transitionType = ActivityTransitionType.ENTER,
			clockDomainId = BOOT_ID,
			observedElapsedRealtimeNanos = 1_000 + ordinal,
			receivedElapsedRealtimeNanos = 1_100 + ordinal,
			registrationGeneration = REGISTRATION_GENERATION,
			authorizationRevision = AUTHORIZATION_REVISION,
			authorizationFingerprint = AUTHORIZATION_FINGERPRINT,
			collectedDataEpoch = collectedDataEpoch,
			automationEpoch = automationEpoch,
		)
		val trigger = AutomaticTrackingStartTrigger(
			triggerId = "activity-transition:$BOOT_ID:$ordinal",
			kind = "ACTIVITY_TRANSITION:WALKING:ENTER",
			bootId = BOOT_ID,
			observedElapsedRealtimeNanos = evidence.observedElapsedRealtimeNanos,
			receivedElapsedRealtimeNanos = evidence.receivedElapsedRealtimeNanos,
			expiresElapsedRealtimeNanos = evidence.observedElapsedRealtimeNanos + 60_000_000_000,
			automationEpoch = automationEpoch,
			startContext = AutomaticTrackingStartContext.ACTIVITY_TRANSITION_CALLBACK,
			sourcePolicyRevision = POLICY_REVISION,
			intendedCaptureSourceMask = ACTIVITY_MASK,
			requestedCaptureSourceMask = ACTIVITY_MASK,
			intendedForegroundServiceTypeMask = FGS_TYPE_MASK,
			collectedDataEpoch = collectedDataEpoch,
		)
		return ActivityAutomaticStartReservation(
			trigger = trigger,
			effectStableId = "effect-$ordinal",
			evidence = evidence,
			controlConsentEpoch = CONTROL_CONSENT_EPOCH,
			reservedAtMs = 1_500,
		)
	}

	private suspend fun insertAcceptedLifecycle(trigger: AutomaticTrackingStartTrigger) {
		val dao = database.sourceSessionDao()
		dao.insertSession(
			LogicalTrackingSessionEntity(
				logicalTrackingId = LOGICAL_ID,
				state = "ACTIVE",
				lifecycleRevision = 1,
				desiredPlanRevision = 1,
				rolloutRevision = 1,
				startOrigin = "AUTOMATIC_BACKGROUND_START",
				clockDomainId = BOOT_ID,
				startedAtMs = 2_000,
				startedElapsedNanos = 2_000,
				cutoffAtMs = null,
				cutoffElapsedNanos = null,
				completedAtMs = null,
				finalAdmissionOrdinal = null,
				failureCode = null,
				sessionMode = "AUTOMATIC",
				currentManifestRevision = 1,
				currentIntentRevision = 1,
				currentServiceRunId = "run-1",
				lifecycleLeaseGeneration = 1,
				lifecycleBootId = BOOT_ID,
				automationEpoch = AUTOMATION_EPOCH,
			),
		)
		val bindings = listOf(
			SessionManifestSourceEntity(
				logicalTrackingId = LOGICAL_ID,
				manifestRevision = 1,
				sourceKind = SourceKind.ACTIVITY.stableCode,
				purpose = "SESSION_CAPTURE",
				consentEpoch = 20,
				persistenceEligible = true,
				qosCode = 1,
			),
			SessionManifestSourceEntity(
				logicalTrackingId = LOGICAL_ID,
				manifestRevision = 1,
				sourceKind = SourceKind.ACTIVITY.stableCode,
				purpose = "CONTROL",
				consentEpoch = CONTROL_CONSENT_EPOCH,
				persistenceEligible = false,
				qosCode = 1,
			),
		)
		val unsignedManifest = SessionManifestVersionEntity(
			logicalTrackingId = LOGICAL_ID,
			manifestRevision = 1,
			serviceRunId = "run-1",
			sessionMode = "AUTOMATIC",
			sourcePolicyRevision = POLICY_REVISION,
			acquisitionPlanRevision = 1,
			rolloutRevision = 1,
			startOrigin = "AUTOMATIC_BACKGROUND_START",
			effectiveBootId = BOOT_ID,
			effectiveElapsedRealtimeNanos = 2_000,
			effectiveWallTimeMs = 2_000,
			zoneId = "UTC",
			automationEpoch = AUTOMATION_EPOCH,
			changeReason = "TEST",
			manifestChecksum = "",
		)
		dao.insertManifest(
			unsignedManifest.copy(
				manifestChecksum = SessionManifestIntegrity.compute(unsignedManifest, bindings),
			),
		)
		dao.insertManifestSources(bindings)
		dao.insertLifecycleIntent(
			SessionLifecycleIntentVersionEntity(
				logicalTrackingId = LOGICAL_ID,
				intentRevision = 1,
				manifestRevision = 1,
				desiredState = "ACTIVE",
				startOrigin = "AUTOMATIC_BACKGROUND_START",
				requestBootId = BOOT_ID,
				requestedElapsedRealtimeNanos = 2_000,
				requestedWallTimeMs = 2_000,
				automationEpoch = AUTOMATION_EPOCH,
				triggerId = trigger.triggerId,
				triggerKind = trigger.kind,
				triggerBootId = trigger.bootId,
				triggerObservedElapsedRealtimeNanos = trigger.observedElapsedRealtimeNanos,
				triggerReceivedElapsedRealtimeNanos = trigger.receivedElapsedRealtimeNanos,
				triggerExpiresElapsedRealtimeNanos = trigger.expiresElapsedRealtimeNanos,
				stopReason = null,
				stopDeadlineBootId = null,
				stopDeadlineElapsedRealtimeNanos = null,
				intentChecksum = "intent-checksum",
				triggerCollectedDataEpoch = trigger.collectedDataEpoch,
			),
		)
		dao.insertServiceRun(
			SourceServiceRunEntity(
				serviceRunId = "run-1",
				logicalTrackingId = LOGICAL_ID,
				state = "STARTING",
				desiredPlanRevision = 1,
				rolloutRevision = 1,
				foregroundCapabilityFlags = 0,
				startedAtMs = 2_000,
				startedElapsedNanos = 2_000,
				completedAtMs = null,
				completionReason = null,
				bootId = BOOT_ID,
				leaseGeneration = 1,
				startOrigin = "AUTOMATIC_BACKGROUND_START",
				desiredForegroundCapabilityFlags = FGS_TYPE_MASK,
				appliedForegroundCapabilityFlags = null,
				runtimeAcknowledgement = "PENDING",
				runtimeFailureCode = null,
				runRevision = 1,
			),
		)
	}

	private companion object {
		const val BOOT_ID = "boot-7"
		const val POLICY_REVISION = 23L
		const val AUTOMATION_EPOCH = 17L
		const val CONTROL_CONSENT_EPOCH = 13L
		const val COLLECTED_DATA_EPOCH = 7L
		const val REGISTRATION_GENERATION = 5L
		const val AUTHORIZATION_REVISION = 6L
		const val AUTHORIZATION_FINGERPRINT = "activity-control-authorization"
		const val ACTIVITY_MASK = 2L
		const val FGS_TYPE_MASK = 256L
		const val LOGICAL_ID = "logical-automatic-1"
	}
}

private class FakeTrackingStartupGate(
	var ready: Boolean = true,
	var generation: Long = 1,
) : TrackingStartupGate {
	var advanceGenerationAfterFirstRead: Boolean = false
	private var generationReads: Int = 0

	override val isReady: Boolean get() = ready
	override val currentGeneration: Long
		get() = if (advanceGenerationAfterFirstRead && generationReads++ > 0) {
			generation + 1
		} else {
			generation
		}

	override suspend fun reconcile(retryFailedStorage: Boolean): TrackingStartupResult =
		TrackingStartupResult.Ready(legacyRecoveryPartial = false, liveCompletedThroughOrdinal = 0)
}
