package com.adsamcik.tracker.tracker.source.coordinator

import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionLifecycleIntentVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.tracker.source.model.SourceKind
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class RecoverySourceCallerAuthorityCandidateTest {
	@Test
	fun `exact current manifest intent and service owner resolve the Room successor`() {
		val fixture = fixture()

		currentRecoverySourceCallerAuthorityCandidate(
			logicalTrackingId = LOGICAL_ID,
			serviceRunId = SERVICE_RUN_ID,
			session = fixture.session,
			run = fixture.run,
			manifest = fixture.manifest,
			bindings = fixture.bindings,
			intent = fixture.intent,
		)?.let { candidate ->
			candidate.manifestIdentity.logicalTrackingId shouldBe LOGICAL_ID
			candidate.manifestIdentity.manifestRevision shouldBe MANIFEST_REVISION
			candidate.reference.value shouldBe CALLER_REFERENCE
		} ?: error("Expected exact Room caller authority")
		currentRecoverySourceCallerAuthorityCandidate(
			logicalTrackingId = LOGICAL_ID,
			serviceRunId = SERVICE_RUN_ID,
			session = fixture.session.copy(state = SessionLifecycleState.RECONFIGURING.name),
			run = fixture.run.copy(state = SessionLifecycleState.RECONFIGURING.name),
			manifest = fixture.manifest,
			bindings = fixture.bindings,
			intent = fixture.intent,
		)?.reference?.value shouldBe CALLER_REFERENCE
	}

	@Test
	fun `stale manifest or unrelated service run cannot become recovery authority`() {
		val fixture = fixture()

		currentRecoverySourceCallerAuthorityCandidate(
			LOGICAL_ID,
			SERVICE_RUN_ID,
			fixture.session.copy(currentManifestRevision = MANIFEST_REVISION - 1L),
			fixture.run,
			fixture.manifest,
			fixture.bindings,
			fixture.intent,
		).shouldBeNull()
		currentRecoverySourceCallerAuthorityCandidate(
			LOGICAL_ID,
			SERVICE_RUN_ID,
			fixture.session,
			fixture.run.copy(serviceRunId = "unrelated-run"),
			fixture.manifest,
			fixture.bindings,
			fixture.intent,
		).shouldBeNull()
	}

	@Test
	fun `malformed current intent fails closed before accepted authority authentication`() {
		val fixture = fixture()

		currentRecoverySourceCallerAuthorityCandidate(
			LOGICAL_ID,
			SERVICE_RUN_ID,
			fixture.session,
			fixture.run,
			fixture.manifest,
			fixture.bindings,
			fixture.intent.copy(intentChecksum = "malformed"),
		).shouldBeNull()
	}

	@Test
	fun `completed restart suspension retains its exact current caller authority`() {
		val fixture = fixture()
		val reason = "ANDROID_RESTART"
		val suspendedIntent = fixture.intent.copy(
			startOrigin = SessionStartOrigin.RECOVERY.name,
			stopReason = reason,
			intentChecksum = stableLifecycleChecksum(
				LOGICAL_ID,
				INTENT_REVISION,
				MANIFEST_REVISION,
				LifecycleDesiredState.ACTIVE,
				reason,
				BOOT_ID,
				20L,
				CALLER_REFERENCE,
			),
		)

		currentRecoverySourceCallerAuthorityCandidate(
			LOGICAL_ID,
			SERVICE_RUN_ID,
			fixture.session.copy(currentServiceRunId = null),
			fixture.run.copy(
				state = SessionLifecycleState.FINALIZED.name,
				completedAtMs = 30L,
				completionReason = reason,
				runtimeAcknowledgement = LifecycleActionStatus.STOP_ACCEPTED.name,
			),
			fixture.manifest,
			fixture.bindings,
			suspendedIntent,
		)?.reference?.value shouldBe CALLER_REFERENCE

		listOf(
			SuspendedLookalike(
				session = fixture.session.copy(currentServiceRunId = SERVICE_RUN_ID),
				run = fixture.run.copy(
					state = SessionLifecycleState.FINALIZED.name,
					completedAtMs = 30L,
					completionReason = reason,
					runtimeAcknowledgement = LifecycleActionStatus.STOP_ACCEPTED.name,
				),
				intent = suspendedIntent,
			),
			SuspendedLookalike(
				session = fixture.session.copy(currentServiceRunId = null),
				run = fixture.run.copy(
					state = SessionLifecycleState.FINALIZED.name,
					completedAtMs = 30L,
					completionReason = "UNRELATED_STOP",
					runtimeAcknowledgement = LifecycleActionStatus.STOP_ACCEPTED.name,
				),
				intent = suspendedIntent,
			),
			SuspendedLookalike(
				session = fixture.session.copy(currentServiceRunId = null),
				run = fixture.run.copy(
					state = SessionLifecycleState.FINALIZED.name,
					completedAtMs = 30L,
					completionReason = reason,
					runtimeAcknowledgement = LifecycleActionStatus.START_ACCEPTED.name,
				),
				intent = suspendedIntent,
			),
			SuspendedLookalike(
				session = fixture.session.copy(currentServiceRunId = null),
				run = fixture.run.copy(
					state = SessionLifecycleState.FINALIZED.name,
					completedAtMs = 30L,
					completionReason = reason,
					runtimeAcknowledgement = LifecycleActionStatus.STOP_ACCEPTED.name,
				),
				intent = suspendedIntent.copy(intentChecksum = "malformed"),
			),
		).forEach { lookalike ->
			currentRecoverySourceCallerAuthorityCandidate(
				LOGICAL_ID,
				SERVICE_RUN_ID,
				lookalike.session,
				lookalike.run,
				fixture.manifest,
				fixture.bindings,
				lookalike.intent,
			).shouldBeNull()
		}
	}

	private fun fixture(): Fixture {
		val bindings = listOf(
			SessionManifestSourceEntity(
				logicalTrackingId = LOGICAL_ID,
				manifestRevision = MANIFEST_REVISION,
				sourceKind = SourceKind.LOCATION.stableCode,
				purpose = SourceBrokerPurpose.SESSION_CAPTURE,
				consentEpoch = 3L,
				persistenceEligible = false,
				qosCode = 1,
			),
		)
		val unsignedManifest = SessionManifestVersionEntity(
			logicalTrackingId = LOGICAL_ID,
			manifestRevision = MANIFEST_REVISION,
			serviceRunId = SERVICE_RUN_ID,
			sessionMode = SessionMode.MANUAL.name,
			sourcePolicyRevision = 4L,
			acquisitionPlanRevision = PLAN_REVISION,
			rolloutRevision = ROLLOUT_REVISION,
			startOrigin = SessionStartOrigin.POLICY_RECONCILIATION.name,
			effectiveBootId = BOOT_ID,
			effectiveElapsedRealtimeNanos = 20L,
			effectiveWallTimeMs = 20L,
			zoneId = "UTC",
			automationEpoch = null,
			changeReason = "POLICY_RECONCILIATION",
			manifestChecksum = "",
		)
		val manifest = unsignedManifest.copy(
			manifestChecksum = SessionManifestIntegrity.compute(unsignedManifest, bindings),
		)
		val intent = SessionLifecycleIntentVersionEntity(
			logicalTrackingId = LOGICAL_ID,
			intentRevision = INTENT_REVISION,
			manifestRevision = MANIFEST_REVISION,
			desiredState = LifecycleDesiredState.ACTIVE.name,
			startOrigin = SessionStartOrigin.POLICY_RECONCILIATION.name,
			requestBootId = BOOT_ID,
			requestedElapsedRealtimeNanos = 20L,
			requestedWallTimeMs = 20L,
			automationEpoch = null,
			triggerId = null,
			triggerKind = null,
			triggerBootId = null,
			triggerObservedElapsedRealtimeNanos = null,
			triggerReceivedElapsedRealtimeNanos = null,
			triggerExpiresElapsedRealtimeNanos = null,
			stopReason = null,
			stopDeadlineBootId = null,
			stopDeadlineElapsedRealtimeNanos = null,
			intentChecksum = stableLifecycleChecksum(
				LOGICAL_ID,
				INTENT_REVISION,
				MANIFEST_REVISION,
				LifecycleDesiredState.ACTIVE,
				SessionStartOrigin.POLICY_RECONCILIATION,
				BOOT_ID,
				20L,
				20L,
				null,
				null,
				null,
				CALLER_REFERENCE,
			),
			sourceCallerAuthorityReference = CALLER_REFERENCE,
		)
		return Fixture(
			session = LogicalTrackingSessionEntity(
				logicalTrackingId = LOGICAL_ID,
				state = SessionLifecycleState.ACTIVE.name,
				lifecycleRevision = 5L,
				desiredPlanRevision = PLAN_REVISION,
				rolloutRevision = ROLLOUT_REVISION,
				startOrigin = SessionStartOrigin.MANUAL_FOREGROUND_START.name,
				clockDomainId = BOOT_ID,
				startedAtMs = 1L,
				startedElapsedNanos = 1L,
				cutoffAtMs = null,
				cutoffElapsedNanos = null,
				completedAtMs = null,
				finalAdmissionOrdinal = null,
				failureCode = null,
				sessionMode = SessionMode.MANUAL.name,
				currentManifestRevision = MANIFEST_REVISION,
				currentIntentRevision = INTENT_REVISION,
				currentServiceRunId = SERVICE_RUN_ID,
				lifecycleLeaseGeneration = LEASE_GENERATION,
				lifecycleBootId = BOOT_ID,
			),
			run = SourceServiceRunEntity(
				serviceRunId = SERVICE_RUN_ID,
				logicalTrackingId = LOGICAL_ID,
				state = SessionLifecycleState.ACTIVE.name,
				desiredPlanRevision = PLAN_REVISION,
				rolloutRevision = ROLLOUT_REVISION,
				foregroundCapabilityFlags = 1L,
				startedAtMs = 1L,
				startedElapsedNanos = 1L,
				completedAtMs = null,
				completionReason = null,
				bootId = BOOT_ID,
				leaseGeneration = LEASE_GENERATION,
				startOrigin = SessionStartOrigin.MANUAL_FOREGROUND_START.name,
				desiredForegroundCapabilityFlags = 1L,
				appliedForegroundCapabilityFlags = 1L,
				runtimeAcknowledgement = LifecycleActionStatus.START_ACCEPTED.name,
				runRevision = 2L,
				startDeliveryToken = "delivery-token",
				startCommandGeneration = 1L,
				preparedManifestRevision = 1L,
				preparedIntentRevision = 1L,
				androidDeliveryState = AndroidStartDeliveryState.FOREGROUND_ACCEPTED.name,
				startIsUserInitiated = true,
			),
			manifest = manifest,
			bindings = bindings,
			intent = intent,
		)
	}

	private data class Fixture(
		val session: LogicalTrackingSessionEntity,
		val run: SourceServiceRunEntity,
		val manifest: SessionManifestVersionEntity,
		val bindings: List<SessionManifestSourceEntity>,
		val intent: SessionLifecycleIntentVersionEntity,
	)

	private data class SuspendedLookalike(
		val session: LogicalTrackingSessionEntity,
		val run: SourceServiceRunEntity,
		val intent: SessionLifecycleIntentVersionEntity,
	)

	private companion object {
		const val LOGICAL_ID = "logical"
		const val SERVICE_RUN_ID = "service-run"
		const val BOOT_ID = "boot"
		const val MANIFEST_REVISION = 2L
		const val INTENT_REVISION = 2L
		const val PLAN_REVISION = 2L
		const val ROLLOUT_REVISION = 5L
		const val LEASE_GENERATION = 7L
		const val CALLER_REFERENCE = "caller-successor"
	}
}
