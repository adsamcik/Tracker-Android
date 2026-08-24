package com.adsamcik.tracker.shared.base.database.data

import io.kotest.matchers.shouldBe
import org.junit.Test

class ActivityAutomaticStartActionEntityTest {
	@Test
	fun `wall clock rollback does not invalidate monotonic automatic start authority`() {
		val action = validAction().copy(
			status = ActivityAutomaticStartActionEntity.STATUS_TERMINAL,
			startRequestedAtMs = 900L,
			lifecycleIntentAcceptedAtMs = 800L,
			terminalAtMs = 700L,
			terminalReason = "STALE_AFTER_CLOCK_CORRECTION",
		)

		action.reservedAtMs shouldBe 1_000L
		action.terminalAtMs shouldBe 700L
	}

	private fun validAction() = ActivityAutomaticStartActionEntity(
		triggerId = "trigger-1",
		effectStableId = "effect-1",
		admissionOrdinal = 1L,
		triggerKind = "ACTIVITY_TRANSITION",
		bootId = "android-boot-count:42",
		observedElapsedRealtimeNanos = 100L,
		receivedElapsedRealtimeNanos = 110L,
		expiresElapsedRealtimeNanos = 120L,
		automationEpoch = 1L,
		sourcePolicyRevision = 1L,
		controlConsentEpoch = 1L,
		collectedDataEpoch = 1L,
		requestedCaptureSourceMask = 1L,
		intendedCaptureSourceMask = 1L,
		intendedForegroundServiceTypeMask = 0L,
		registrationGeneration = 1L,
		authorizationRevision = 1L,
		authorizationFingerprint = "authorization-1",
		startOrigin = ActivityAutomaticStartActionEntity.START_ORIGIN_ACTIVITY_TRANSITION_CALLBACK,
		status = ActivityAutomaticStartActionEntity.STATUS_RESERVED,
		reservedAtMs = 1_000L,
		startRequestedAtMs = null,
		lifecycleIntentAcceptedAtMs = null,
		acceptedLogicalTrackingId = null,
		acceptedIntentRevision = null,
		terminalAtMs = null,
		terminalReason = null,
	)
}
