package com.adsamcik.tracker.tracker.api

import com.adsamcik.tracker.activity.ActivityTransitionData
import com.adsamcik.tracker.activity.ActivityTransitionType
import com.adsamcik.tracker.activity.api.backend.ActivityUpdate
import com.adsamcik.tracker.activity.api.backend.ActivityUpdateSource
import com.adsamcik.tracker.activity.api.backend.RecognizedActivity
import com.adsamcik.tracker.activity.api.backend.TransitionUpdate
import com.adsamcik.tracker.shared.base.data.GroupedActivity
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.stats.api.DetectedActivityType
import com.adsamcik.tracker.tracker.resilience.AutomaticTrackingStartContext
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("BackgroundTrackingApi Logic")
class BackgroundTrackingApiLogicTest {
	@Nested
	@DisplayName("control policy")
	inner class ControlPolicy {
		@Test
		fun `durable automation retries until policy and params share an authoritative revision`() {
			durableActivityAuthorityDisposition(
				paramsInitialized = false,
				activePolicyRevision = null,
				paramsPolicyRevision = null,
				activityControlEligible = false,
				currentAutomationEpoch = 17,
				effectAutomationEpoch = 17,
			) shouldBe ActivityAutomationDeliveryResult.RETRY

			durableActivityAuthorityDisposition(
				paramsInitialized = true,
				activePolicyRevision = 4,
				paramsPolicyRevision = 3,
				activityControlEligible = true,
				currentAutomationEpoch = 17,
				effectAutomationEpoch = 17,
			) shouldBe ActivityAutomationDeliveryResult.RETRY
		}

		@Test
		fun `current authoritative policy denial terminally suppresses old automation`() {
			durableActivityAuthorityDisposition(
				paramsInitialized = true,
				activePolicyRevision = 4,
				paramsPolicyRevision = 4,
				activityControlEligible = false,
				currentAutomationEpoch = 17,
				effectAutomationEpoch = 17,
			) shouldBe ActivityAutomationDeliveryResult.TERMINALLY_SUPPRESSED
		}

		@Test
		fun `reconsented control terminally suppresses evidence from the retired epoch`() {
			durableActivityAuthorityDisposition(
				paramsInitialized = true,
				activePolicyRevision = 4,
				paramsPolicyRevision = 4,
				activityControlEligible = true,
				currentControlConsentEpoch = 9,
				effectControlConsentEpoch = 8,
				currentAutomationEpoch = 17,
				effectAutomationEpoch = 17,
			) shouldBe ActivityAutomationDeliveryResult.TERMINALLY_SUPPRESSED
			durableActivityAuthorityDisposition(
				paramsInitialized = true,
				activePolicyRevision = 4,
				paramsPolicyRevision = 4,
				activityControlEligible = true,
				currentControlConsentEpoch = 9,
				effectControlConsentEpoch = 9,
				currentAutomationEpoch = 17,
				effectAutomationEpoch = 17,
			) shouldBe null

			durableActivityAuthorityDisposition(
				paramsInitialized = true,
				activePolicyRevision = 5,
				paramsPolicyRevision = 5,
				activityControlEligible = true,
				currentControlConsentEpoch = 9,
				effectControlConsentEpoch = 9,
				currentAutomationEpoch = 5,
				effectAutomationEpoch = 4,
			) shouldBe ActivityAutomationDeliveryResult.TERMINALLY_SUPPRESSED
		}

		@Test
		fun `platform enqueue result never fabricates lifecycle acceptance`() {
			durableActivityStartOutcome(startAccepted = false) shouldBe
				ActivityAutomationDeliveryResult.START_CONTEXT_EXPIRED
			durableActivityStartOutcome(startAccepted = true) shouldBe
				ActivityAutomationDeliveryResult.START_REQUESTED
		}

		@Test
		fun `durable replay cannot manufacture a transition callback start exemption`() {
			durableActivityStartContextDisposition(
				ActivityAutomationStartContext.DURABLE_REPLAY,
			) shouldBe ActivityAutomationDeliveryResult.START_CONTEXT_EXPIRED
			durableActivityStartContextDisposition(
				ActivityAutomationStartContext.FRESH_TRANSITION_CALLBACK,
			) shouldBe null
		}

		@Test
		fun `fresh transition carries its qualified envelope and policy epoch into service start`() {
			val evidence = ActivityAutomationDeliveryEnvelope(
				admissionOrdinal = 42,
				activityType = DetectedActivityType.WALKING,
				confidence = 100,
				transitionType = ActivityTransitionType.ENTER,
				clockDomainId = "boot-7",
				observedElapsedRealtimeNanos = 1_000,
				receivedElapsedRealtimeNanos = 1_100,
				registrationGeneration = 5,
				authorizationRevision = 8,
				authorizationFingerprint = "authorization",
				collectedDataEpoch = 4,
				automationEpoch = 17,
			)

			val trigger = requireNotNull(
				evidence.toAutomaticTrackingStartTrigger(
					ActivityAutomationStartContext.FRESH_TRANSITION_CALLBACK,
					sourcePolicyRevision = 23,
					intendedCaptureSourceMask = 2,
					requestedCaptureSourceMask = 2,
					intendedForegroundServiceTypeMask = 256,
				),
			)

			trigger.triggerId shouldBe "activity-transition:boot-7:42"
			trigger.kind shouldBe "ACTIVITY_TRANSITION:WALKING:ENTER"
			trigger.bootId shouldBe "boot-7"
			trigger.observedElapsedRealtimeNanos shouldBe 1_000
			trigger.receivedElapsedRealtimeNanos shouldBe 1_100
			trigger.expiresElapsedRealtimeNanos shouldBe 60_000_001_000L
			trigger.automationEpoch shouldBe 17
			trigger.sourcePolicyRevision shouldBe 23
			trigger.intendedCaptureSourceMask shouldBe 2
			trigger.requestedCaptureSourceMask shouldBe 2
			trigger.intendedForegroundServiceTypeMask shouldBe 256
			trigger.collectedDataEpoch shouldBe 4
			trigger.startContext shouldBe AutomaticTrackingStartContext.ACTIVITY_TRANSITION_CALLBACK
			evidence.toAutomaticTrackingStartTrigger(
				ActivityAutomationStartContext.DURABLE_REPLAY,
				sourcePolicyRevision = 23,
				intendedCaptureSourceMask = 2,
				requestedCaptureSourceMask = 2,
				intendedForegroundServiceTypeMask = 256,
			) shouldBe null
		}

		@Test
		fun `delayed callback cannot extend stale motion authority from receipt time`() {
			val evidence = ActivityAutomationDeliveryEnvelope(
				admissionOrdinal = 43,
				activityType = DetectedActivityType.WALKING,
				confidence = 100,
				transitionType = ActivityTransitionType.ENTER,
				clockDomainId = "boot-7",
				observedElapsedRealtimeNanos = 1_000,
				receivedElapsedRealtimeNanos = 59_000_001_000,
				registrationGeneration = 5,
				authorizationRevision = 8,
				authorizationFingerprint = "authorization",
				collectedDataEpoch = 4,
				automationEpoch = 17,
			)

			val trigger = requireNotNull(
				evidence.toAutomaticTrackingStartTrigger(
					ActivityAutomationStartContext.FRESH_TRANSITION_CALLBACK,
					sourcePolicyRevision = 23,
					intendedCaptureSourceMask = 2,
					requestedCaptureSourceMask = 2,
					intendedForegroundServiceTypeMask = 256,
				),
			)

			trigger.expiresElapsedRealtimeNanos shouldBe 60_000_001_000L
		}

		@Test
		fun `denied activity control forces automatic tracking off`() {
			effectiveAutomaticControlMode(GroupedActivity.ON_FOOT.ordinal, controlEligible = false) shouldBe
				GroupedActivity.STILL.ordinal
		}

		@Test
		fun `eligible activity control preserves the configured mode`() {
			effectiveAutomaticControlMode(GroupedActivity.ON_FOOT.ordinal, controlEligible = true) shouldBe
				GroupedActivity.ON_FOOT.ordinal
		}

		@Test
		fun `step corroboration requires its own control epoch and recognition mode`() {
			shouldUseStepCorroboration(useTransitionApi = false, stepControlEligible = false) shouldBe false
			shouldUseStepCorroboration(useTransitionApi = true, stepControlEligible = true) shouldBe false
			shouldUseStepCorroboration(useTransitionApi = false, stepControlEligible = true) shouldBe true
		}
	}

	@Nested
	@DisplayName("hasAnythingToTrack")
	inner class HasAnythingToTrack {
		private fun noSources() = TrackingParamsState(
			locationEnabled = false,
			cellEnabled = false,
			wifiEnabled = false,
			activityEnabled = false,
			stepsEnabled = false,
			barometerEnabled = false,
		)

		@Test
		fun `returns true when location is enabled`() {
			val params = noSources().copy(locationEnabled = true)
			hasAnythingToTrack(params) shouldBe true
		}

		@Test
		fun `returns true when cell is enabled`() {
			val params = noSources().copy(cellEnabled = true)
			hasAnythingToTrack(params) shouldBe true
		}

		@Test
		fun `returns true when wifi scanning is enabled`() {
			val params = noSources().copy(wifiEnabled = true)
			hasAnythingToTrack(params) shouldBe true
		}

		@Test
		fun `returns false when nothing is enabled`() {
			hasAnythingToTrack(noSources()) shouldBe false
		}

		@Test
		fun `barometer requires hardware availability`() {
			val params = noSources().copy(barometerEnabled = true)
			hasAnythingToTrack(params, barometerAvailable = true) shouldBe true
			hasAnythingToTrack(params, barometerAvailable = false) shouldBe false
		}

		@Test
		fun `returns true when only activity is enabled`() {
			val params = noSources().copy(activityEnabled = true)
			hasAnythingToTrack(params) shouldBe true
		}

		@Test
		fun `returns true when only steps are enabled`() {
			val params = noSources().copy(stepsEnabled = true)
			hasAnythingToTrack(params) shouldBe true
		}

		@Test
		fun `permission and hardware dependent sources require availability`() {
			val params = TrackingParamsState()

			hasAnythingToTrack(
				params = params,
				locationAvailable = false,
				activityAvailable = false,
				stepsAvailable = false,
				wifiAvailable = false,
				cellAvailable = false,
				barometerAvailable = false,
			) shouldBe false
		}
	}

	@Nested
	@DisplayName("canBackgroundTrackWithParams")
	inner class CanBackgroundTrackWithParams {
		@Test
		fun `returns false when activity is STILL`() {
			canBackgroundTrackWithParams(
				groupedActivity = GroupedActivity.STILL,
				isTrackerRunning = false,
				disabledUntilRecharge = false,
				autoTrackingMode = GroupedActivity.ON_FOOT.ordinal,
			) shouldBe false
		}

		@Test
		fun `returns false when activity is UNKNOWN`() {
			canBackgroundTrackWithParams(
				groupedActivity = GroupedActivity.UNKNOWN,
				isTrackerRunning = false,
				disabledUntilRecharge = false,
				autoTrackingMode = GroupedActivity.ON_FOOT.ordinal,
			) shouldBe false
		}

		@Test
		fun `returns false when tracker is already running`() {
			canBackgroundTrackWithParams(
				groupedActivity = GroupedActivity.ON_FOOT,
				isTrackerRunning = true,
				disabledUntilRecharge = false,
				autoTrackingMode = GroupedActivity.ON_FOOT.ordinal,
			) shouldBe false
		}

		@Test
		fun `returns false when disabled until recharge`() {
			canBackgroundTrackWithParams(
				groupedActivity = GroupedActivity.ON_FOOT,
				isTrackerRunning = false,
				disabledUntilRecharge = true,
				autoTrackingMode = GroupedActivity.ON_FOOT.ordinal,
			) shouldBe false
		}

		@Test
		fun `returns false when auto tracking mode is STILL`() {
			canBackgroundTrackWithParams(
				groupedActivity = GroupedActivity.ON_FOOT,
				isTrackerRunning = false,
				disabledUntilRecharge = false,
				autoTrackingMode = GroupedActivity.STILL.ordinal,
			) shouldBe false
		}

		@Test
		fun `returns true when activity matches preference exactly`() {
			canBackgroundTrackWithParams(
				groupedActivity = GroupedActivity.ON_FOOT,
				isTrackerRunning = false,
				disabledUntilRecharge = false,
				autoTrackingMode = GroupedActivity.ON_FOOT.ordinal,
			) shouldBe true
		}

		@Test
		fun `IN_VEHICLE mode allows ON_FOOT activity`() {
			canBackgroundTrackWithParams(
				groupedActivity = GroupedActivity.ON_FOOT,
				isTrackerRunning = false,
				disabledUntilRecharge = false,
				autoTrackingMode = GroupedActivity.IN_VEHICLE.ordinal,
			) shouldBe true
		}

		@Test
		fun `ON_FOOT mode does not allow IN_VEHICLE activity`() {
			canBackgroundTrackWithParams(
				groupedActivity = GroupedActivity.IN_VEHICLE,
				isTrackerRunning = false,
				disabledUntilRecharge = false,
				autoTrackingMode = GroupedActivity.ON_FOOT.ordinal,
			) shouldBe false
		}

		@Test
		fun `IN_VEHICLE mode allows IN_VEHICLE activity`() {
			canBackgroundTrackWithParams(
				groupedActivity = GroupedActivity.IN_VEHICLE,
				isTrackerRunning = false,
				disabledUntilRecharge = false,
				autoTrackingMode = GroupedActivity.IN_VEHICLE.ordinal,
			) shouldBe true
		}
	}

	@Nested
	@DisplayName("canContinueWithParams")
	inner class CanContinueWithParams {
		@Test
		fun `returns false when activity is STILL`() {
			canContinueWithParams(
				groupedActivity = GroupedActivity.STILL,
				autoTrackingMode = GroupedActivity.IN_VEHICLE.ordinal,
			) shouldBe false
		}

		@Test
		fun `IN_VEHICLE preference continues for ON_FOOT activity`() {
			canContinueWithParams(
				groupedActivity = GroupedActivity.ON_FOOT,
				autoTrackingMode = GroupedActivity.IN_VEHICLE.ordinal,
			) shouldBe true
		}

		@Test
		fun `IN_VEHICLE preference continues for IN_VEHICLE activity`() {
			canContinueWithParams(
				groupedActivity = GroupedActivity.IN_VEHICLE,
				autoTrackingMode = GroupedActivity.IN_VEHICLE.ordinal,
			) shouldBe true
		}

		@Test
		fun `ON_FOOT preference continues for ON_FOOT activity`() {
			canContinueWithParams(
				groupedActivity = GroupedActivity.ON_FOOT,
				autoTrackingMode = GroupedActivity.ON_FOOT.ordinal,
			) shouldBe true
		}

		@Test
		fun `ON_FOOT preference continues for UNKNOWN activity`() {
			canContinueWithParams(
				groupedActivity = GroupedActivity.UNKNOWN,
				autoTrackingMode = GroupedActivity.ON_FOOT.ordinal,
			) shouldBe true
		}

		@Test
		fun `ON_FOOT preference does not continue for IN_VEHICLE activity`() {
			canContinueWithParams(
				groupedActivity = GroupedActivity.IN_VEHICLE,
				autoTrackingMode = GroupedActivity.ON_FOOT.ordinal,
			) shouldBe false
		}

		@Test
		fun `STILL preference does not continue for any activity`() {
			canContinueWithParams(
				groupedActivity = GroupedActivity.ON_FOOT,
				autoTrackingMode = GroupedActivity.STILL.ordinal,
			) shouldBe false
		}
	}

	@Nested
	@DisplayName("resolveAutoTrackingPreferenceAction")
	inner class ResolveAutoTrackingPreferenceAction {
		@Test
		fun `STILL while active disables`() {
			resolveAutoTrackingPreferenceAction(
				newMode = GroupedActivity.STILL.ordinal,
				isActive = true,
				hasActivityPermission = true,
			) shouldBe AutoTrackingPreferenceAction.DISABLE
		}

		@Test
		fun `STILL while inactive without active subscription does nothing without permission`() {
			resolveAutoTrackingPreferenceAction(
				newMode = GroupedActivity.STILL.ordinal,
				isActive = false,
				hasActivityPermission = false,
			) shouldBe AutoTrackingPreferenceAction.NONE
		}

		@Test
		fun `STILL while inactive with permission remains disabled`() {
			resolveAutoTrackingPreferenceAction(
				newMode = GroupedActivity.STILL.ordinal,
				isActive = false,
				hasActivityPermission = true,
			) shouldBe AutoTrackingPreferenceAction.NONE
		}

		@Test
		fun `movement while inactive with permission enables`() {
			resolveAutoTrackingPreferenceAction(
				newMode = GroupedActivity.ON_FOOT.ordinal,
				isActive = false,
				hasActivityPermission = true,
			) shouldBe AutoTrackingPreferenceAction.ENABLE
		}

		@Test
		fun `movement while inactive without permission does nothing`() {
			resolveAutoTrackingPreferenceAction(
				newMode = GroupedActivity.ON_FOOT.ordinal,
				isActive = false,
				hasActivityPermission = false,
			) shouldBe AutoTrackingPreferenceAction.NONE
		}

		@Test
		fun `ON_FOOT to IN_VEHICLE while active reinitializes`() {
			// Regression: changing the activity requirement between two movement modes while
			// the watcher is already active must re-register so the new transitions take effect.
			resolveAutoTrackingPreferenceAction(
				newMode = GroupedActivity.IN_VEHICLE.ordinal,
				isActive = true,
				hasActivityPermission = true,
			) shouldBe AutoTrackingPreferenceAction.REINITIALIZE
		}

		@Test
		fun `IN_VEHICLE to ON_FOOT while active reinitializes`() {
			resolveAutoTrackingPreferenceAction(
				newMode = GroupedActivity.ON_FOOT.ordinal,
				isActive = true,
				hasActivityPermission = true,
			) shouldBe AutoTrackingPreferenceAction.REINITIALIZE
		}
	}

	@Nested
	@DisplayName("stop session when automation is disabled")
	inner class StopSessionWhenAutomationDisabled {
		@Test
		fun `disabled mode stops an automatic session`() {
			shouldStopSessionWhenAutoTrackingDisabled(
				newMode = GroupedActivity.STILL.ordinal,
				isUserInitiated = false,
			) shouldBe true
		}

		@Test
		fun `disabled mode preserves a manual session`() {
			shouldStopSessionWhenAutoTrackingDisabled(
				newMode = GroupedActivity.STILL.ordinal,
				isUserInitiated = true,
			) shouldBe false
		}

		@Test
		fun `movement mode does not stop the current session`() {
			shouldStopSessionWhenAutoTrackingDisabled(
				newMode = GroupedActivity.ON_FOOT.ordinal,
				isUserInitiated = false,
			) shouldBe false
		}
	}

	@Nested
	@DisplayName("automatic continuation grace")
	inner class AutomaticContinuationGrace {
		@Test
		fun `incompatible automatic activity schedules grace instead of immediate teardown`() {
			resolveAutomaticTrackingContinuationAction(
				isUserInitiated = false,
				canContinue = false,
			) shouldBe AutomaticTrackingContinuationAction.SCHEDULE_STOP_GRACE
		}

		@Test
		fun `compatible and user sessions cancel any pending automatic stop grace`() {
			resolveAutomaticTrackingContinuationAction(
				isUserInitiated = false,
				canContinue = true,
			) shouldBe AutomaticTrackingContinuationAction.KEEP
			resolveAutomaticTrackingContinuationAction(
				isUserInitiated = true,
				canContinue = false,
			) shouldBe AutomaticTrackingContinuationAction.KEEP
		}
	}

	@Nested
	@DisplayName("transition batch selection")
	inner class TransitionBatchSelection {
		@Test
		fun `newest matching transition wins when a batch contains stale still then walking`() {
			val still = ActivityTransitionData(
				activity = DetectedActivityType.STILL,
				type = ActivityTransitionType.ENTER,
			)
			val walking = ActivityTransitionData(
				activity = DetectedActivityType.WALKING,
				type = ActivityTransitionType.ENTER,
			)

			selectNewestConfiguredTransition(
				configuredTransitions = listOf(still, walking),
				updates = listOf(
					TransitionUpdate(
						activityType = DetectedActivityType.STILL,
						transitionType = ActivityTransitionType.ENTER,
						elapsedRealTimeNanos = 1L,
					),
					TransitionUpdate(
						activityType = DetectedActivityType.WALKING,
						transitionType = ActivityTransitionType.ENTER,
						elapsedRealTimeNanos = 2L,
					),
				),
			) shouldBe walking
		}

		@Test
		fun `unconfigured newer events do not hide the newest configured transition`() {
			val still = ActivityTransitionData(
				activity = DetectedActivityType.STILL,
				type = ActivityTransitionType.ENTER,
			)

			selectNewestConfiguredTransition(
				configuredTransitions = listOf(still),
				updates = listOf(
					TransitionUpdate(
						activityType = DetectedActivityType.STILL,
						transitionType = ActivityTransitionType.ENTER,
						elapsedRealTimeNanos = 1L,
					),
					TransitionUpdate(
						activityType = DetectedActivityType.WALKING,
						transitionType = ActivityTransitionType.EXIT,
						elapsedRealTimeNanos = 2L,
					),
				),
			) shouldBe still
		}
	}

	@Nested
	@DisplayName("activity update source")
	inner class ActivityUpdateSourceSelection {
		private val walking = RecognizedActivity(DetectedActivityType.WALKING, 100)

		@Test
		fun `confidence recognition updates drive change detection`() {
			isChangeDetectionUpdate(
				ActivityUpdate(
					activity = walking,
					elapsedTimeMillis = 1L,
					source = ActivityUpdateSource.RECOGNITION,
				),
			) shouldBe true
		}

		@Test
		fun `transition-derived watcher updates do not drive change detection`() {
			isChangeDetectionUpdate(
				ActivityUpdate(
					activity = walking,
					elapsedTimeMillis = 1L,
					source = ActivityUpdateSource.TRANSITION,
				),
			) shouldBe false
		}
	}

	@Nested
	@DisplayName("activity request removal reconciliation")
	inner class ActivityRequestRemovalReconciliation {
		@Test
		fun `retries removal until cleanup succeeds`() = runTest {
			var attempts = 0
			var failures = 0

			val removed = reconcileActivityRequestRemoval(
				initialRetryDelayMillis = 0L,
				maxRetryDelayMillis = 0L,
				shouldContinue = { true },
				onFailure = { failures++ },
			) {
				attempts++
				if (attempts < 3) error("remove failed")
			}

			removed shouldBe true
			attempts shouldBe 3
			failures shouldBe 2
		}

		@Test
		fun `stops retrying when removal is no longer desired`() = runTest {
			var shouldContinue = true
			var attempts = 0

			val removed = reconcileActivityRequestRemoval(
				initialRetryDelayMillis = 0L,
				maxRetryDelayMillis = 0L,
				shouldContinue = { shouldContinue },
				onFailure = { shouldContinue = false },
			) {
				attempts++
				error("remove failed")
			}

			removed shouldBe false
			attempts shouldBe 1
		}

		@Test
		fun `permanent removal failure stops at the configured attempt budget`() = runTest {
			var attempts = 0

			val removed = reconcileActivityRequestRemoval(
				initialRetryDelayMillis = 0L,
				maxRetryDelayMillis = 0L,
				maxAttempts = 3,
				shouldContinue = { true },
				onFailure = {},
			) {
				attempts++
				error("remove failed")
			}

			removed shouldBe false
			attempts shouldBe 3
		}
	}
}
