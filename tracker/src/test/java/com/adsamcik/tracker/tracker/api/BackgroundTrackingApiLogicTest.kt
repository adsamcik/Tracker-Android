package com.adsamcik.tracker.tracker.api

import com.adsamcik.tracker.shared.base.data.GroupedActivity
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import io.kotest.matchers.shouldBe
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
		fun `returns false when nothing is enabled`() {
			val params = TrackingParamsState(
				locationEnabled = false,
				cellEnabled = false,
				wifiLocationCountEnabled = false,
				wifiNetworkEnabled = false,
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
		fun `ignores unrelated flags like steps and activity`() {
			val params = TrackingParamsState(
				locationEnabled = false,
				cellEnabled = false,
				wifiLocationCountEnabled = false,
				wifiNetworkEnabled = false,
				stepsEnabled = true,
				activityEnabled = true,
			)
			hasAnythingToTrack(params) shouldBe false
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
}
