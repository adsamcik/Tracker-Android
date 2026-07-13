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
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("BackgroundTrackingApi Logic")
class BackgroundTrackingApiLogicTest {

	@Nested
	@DisplayName("hasAnythingToTrack")
	inner class HasAnythingToTrack {
		@Test
		fun `returns true when location is enabled`() {
			val params = TrackingParamsState(
				locationEnabled = true,
				cellEnabled = false,
				wifiLocationCountEnabled = false,
				wifiNetworkEnabled = false,
			)
			hasAnythingToTrack(params) shouldBe true
		}

		@Test
		fun `returns true when cell is enabled`() {
			val params = TrackingParamsState(
				locationEnabled = false,
				cellEnabled = true,
				wifiLocationCountEnabled = false,
				wifiNetworkEnabled = false,
			)
			hasAnythingToTrack(params) shouldBe true
		}

		@Test
		fun `returns true when wifi location count is enabled`() {
			val params = TrackingParamsState(
				locationEnabled = false,
				cellEnabled = false,
				wifiLocationCountEnabled = true,
				wifiNetworkEnabled = false,
			)
			hasAnythingToTrack(params) shouldBe true
		}

		@Test
		fun `returns true when wifi network is enabled`() {
			val params = TrackingParamsState(
				locationEnabled = false,
				cellEnabled = false,
				wifiLocationCountEnabled = false,
				wifiNetworkEnabled = true,
			)
			hasAnythingToTrack(params) shouldBe true
		}

		@Test
		fun `returns true when wifi scanning is enabled`() {
			val params = TrackingParamsState(
				locationEnabled = false,
				cellEnabled = false,
				wifiEnabled = true,
				wifiLocationCountEnabled = false,
				wifiNetworkEnabled = false,
			)
			hasAnythingToTrack(params) shouldBe true
		}

		@Test
		fun `returns false when nothing is enabled`() {
			val params = TrackingParamsState(
				locationEnabled = false,
				cellEnabled = false,
				wifiEnabled = false,
				wifiLocationCountEnabled = false,
				wifiNetworkEnabled = false,
				activityEnabled = false,
				stepsEnabled = false,
			)
			hasAnythingToTrack(params) shouldBe false
		}

		@Test
		fun `returns true when all are enabled`() {
			val params = TrackingParamsState(
				locationEnabled = true,
				cellEnabled = true,
				wifiLocationCountEnabled = true,
				wifiNetworkEnabled = true,
			)
			hasAnythingToTrack(params) shouldBe true
		}

		@Test
		fun `returns true when only activity is enabled`() {
			val params = TrackingParamsState(
				locationEnabled = false,
				cellEnabled = false,
				wifiEnabled = false,
				wifiLocationCountEnabled = false,
				wifiNetworkEnabled = false,
				activityEnabled = true,
				stepsEnabled = false,
			)
			hasAnythingToTrack(params) shouldBe true
		}

		@Test
		fun `returns true when only steps are enabled`() {
			val params = TrackingParamsState(
				locationEnabled = false,
				cellEnabled = false,
				wifiEnabled = false,
				wifiLocationCountEnabled = false,
				wifiNetworkEnabled = false,
				activityEnabled = false,
				stepsEnabled = true,
			)
			hasAnythingToTrack(params) shouldBe true
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
