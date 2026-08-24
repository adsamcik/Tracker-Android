package com.adsamcik.tracker.tracker.service

import com.adsamcik.tracker.tracker.resilience.AutomaticTrackingStartContext
import com.adsamcik.tracker.tracker.resilience.AutomaticTrackingStartTrigger
import com.adsamcik.tracker.tracker.source.model.SourceKind
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrackerAutomaticStartIntentTest {
	@Test
	fun `authenticated capture and foreground masks must match the current accepted set exactly`() {
		val requested = setOf(SourceKind.LOCATION, SourceKind.STEPS)
		val accepted = setOf(SourceKind.STEPS)

		automaticForegroundEnvelopeMatches(trigger, requested, accepted, 34) shouldBe true
		automaticForegroundEnvelopeMatches(
			trigger.copy(intendedCaptureSourceMask = 1L),
			requested,
			accepted,
			34,
		) shouldBe false
		automaticForegroundEnvelopeMatches(
			trigger.copy(
				intendedForegroundServiceTypeMask =
					android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE.toLong(),
			),
			requested,
			accepted,
			34,
		) shouldBe false
	}

	@Test
	fun `runtime boundary rejects permission revocation before descriptor persistence`() {
		validateAutomaticStartAtRuntime(
			automaticStartExpected = true,
			trigger = trigger,
			currentBootId = trigger.bootId,
			currentElapsedRealtimeNanos = trigger.receivedElapsedRealtimeNanos,
			currentPolicyRevision = trigger.sourcePolicyRevision,
			currentAutomationEpoch = trigger.automationEpoch,
			hasActivityPermission = false,
		) shouldBe AUTOMATIC_START_PERMISSION_REVOKED
	}

	@Test
	fun `runtime boundary accepts only current boot time and policy epoch`() {
		validateAutomaticStartAtRuntime(
			automaticStartExpected = true,
			trigger = trigger,
			currentBootId = trigger.bootId,
			currentElapsedRealtimeNanos = trigger.receivedElapsedRealtimeNanos,
			currentPolicyRevision = trigger.sourcePolicyRevision,
			currentAutomationEpoch = trigger.automationEpoch,
			hasActivityPermission = true,
		) shouldBe null
		validateAutomaticStartAtRuntime(
			automaticStartExpected = true,
			trigger = trigger,
			currentBootId = "new-boot",
			currentElapsedRealtimeNanos = trigger.receivedElapsedRealtimeNanos,
			currentPolicyRevision = trigger.sourcePolicyRevision,
			currentAutomationEpoch = trigger.automationEpoch,
			hasActivityPermission = true,
		) shouldBe AUTOMATIC_START_BOOT_STALE
		validateAutomaticStartAtRuntime(
			automaticStartExpected = true,
			trigger = trigger,
			currentBootId = trigger.bootId,
			currentElapsedRealtimeNanos = trigger.expiresElapsedRealtimeNanos + 1L,
			currentPolicyRevision = trigger.sourcePolicyRevision,
			currentAutomationEpoch = trigger.automationEpoch,
			hasActivityPermission = true,
		) shouldBe AUTOMATIC_START_TRIGGER_STALE
		validateAutomaticStartAtRuntime(
			automaticStartExpected = true,
			trigger = trigger,
			currentBootId = trigger.bootId,
			currentElapsedRealtimeNanos = trigger.receivedElapsedRealtimeNanos,
			currentPolicyRevision = trigger.sourcePolicyRevision + 1L,
			currentAutomationEpoch = trigger.automationEpoch,
			hasActivityPermission = true,
		) shouldBe AUTOMATIC_START_EPOCH_STALE
		validateAutomaticStartAtRuntime(
			automaticStartExpected = true,
			trigger = trigger,
			currentBootId = trigger.bootId,
			currentElapsedRealtimeNanos = trigger.receivedElapsedRealtimeNanos,
			currentPolicyRevision = trigger.sourcePolicyRevision,
			currentAutomationEpoch = trigger.automationEpoch + 1L,
			hasActivityPermission = true,
		) shouldBe AUTOMATIC_START_EPOCH_STALE
	}

	@Test
	fun `runtime boundary distinguishes ordinary automatic starts from manual and recovery starts`() {
		validateAutomaticStartAtRuntime(
			automaticStartExpected = true,
			trigger = null,
			currentBootId = trigger.bootId,
			currentElapsedRealtimeNanos = trigger.receivedElapsedRealtimeNanos,
			currentPolicyRevision = trigger.sourcePolicyRevision,
			currentAutomationEpoch = trigger.automationEpoch,
			hasActivityPermission = true,
		) shouldBe AUTOMATIC_START_TRIGGER_MISSING
		validateAutomaticStartAtRuntime(
			automaticStartExpected = false,
			trigger = null,
			currentBootId = trigger.bootId,
			currentElapsedRealtimeNanos = trigger.receivedElapsedRealtimeNanos,
			currentPolicyRevision = null,
			currentAutomationEpoch = null,
			hasActivityPermission = false,
		) shouldBe null
		validateAutomaticStartAtRuntime(
			automaticStartExpected = false,
			trigger = trigger,
			currentBootId = trigger.bootId,
			currentElapsedRealtimeNanos = trigger.receivedElapsedRealtimeNanos,
			currentPolicyRevision = trigger.sourcePolicyRevision,
			currentAutomationEpoch = trigger.automationEpoch,
			hasActivityPermission = true,
		) shouldBe AUTOMATIC_START_TRIGGER_UNEXPECTED
	}

	private companion object {
		val trigger = AutomaticTrackingStartTrigger(
			triggerId = "activity-transition:boot-1:42",
			kind = "ACTIVITY_TRANSITION:WALKING:ENTER",
			bootId = "boot-1",
			observedElapsedRealtimeNanos = 100L,
			receivedElapsedRealtimeNanos = 120L,
			expiresElapsedRealtimeNanos = 60_000_000_120L,
			automationEpoch = 7L,
			startContext = AutomaticTrackingStartContext.ACTIVITY_TRANSITION_CALLBACK,
			sourcePolicyRevision = 11L,
			intendedCaptureSourceMask = 1L shl 2,
			requestedCaptureSourceMask = (1L shl 0) or (1L shl 2),
			intendedForegroundServiceTypeMask =
				android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH.toLong(),
			collectedDataEpoch = 3L,
		)
	}
}
