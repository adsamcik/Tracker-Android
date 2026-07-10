package com.adsamcik.tracker.tracker.api

import com.adsamcik.tracker.shared.base.data.GroupedActivity
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("resolveDetectionPermissionAction")
class DetectionPermissionActionTest {

	private val onFoot = GroupedActivity.ON_FOOT.ordinal
	private val still = GroupedActivity.STILL.ordinal

	@Nested
	@DisplayName("permission revoked while active")
	inner class Revoked {
		@Test
		fun `disables active detection when permission is lost`() {
			resolveDetectionPermissionAction(
				isActive = true,
				hasActivityPermission = false,
				autoTrackingMode = onFoot,
			) shouldBe AutoTrackingPreferenceAction.DISABLE
		}

		@Test
		fun `disables even when the configured mode is still`() {
			resolveDetectionPermissionAction(
				isActive = true,
				hasActivityPermission = false,
				autoTrackingMode = still,
			) shouldBe AutoTrackingPreferenceAction.DISABLE
		}
	}

	@Nested
	@DisplayName("permission present")
	inner class Granted {
		@Test
		fun `re-arms detection when previously stopped and a movement mode is selected`() {
			resolveDetectionPermissionAction(
				isActive = false,
				hasActivityPermission = true,
				autoTrackingMode = onFoot,
			) shouldBe AutoTrackingPreferenceAction.ENABLE
		}

		@Test
		fun `does nothing when stopped but the mode is still`() {
			resolveDetectionPermissionAction(
				isActive = false,
				hasActivityPermission = true,
				autoTrackingMode = still,
			) shouldBe AutoTrackingPreferenceAction.NONE
		}

		@Test
		fun `does nothing when already active with permission`() {
			resolveDetectionPermissionAction(
				isActive = true,
				hasActivityPermission = true,
				autoTrackingMode = onFoot,
			) shouldBe AutoTrackingPreferenceAction.NONE
		}
	}

	@Nested
	@DisplayName("permission absent and inactive")
	inner class InactiveNoPermission {
		@Test
		fun `does nothing`() {
			resolveDetectionPermissionAction(
				isActive = false,
				hasActivityPermission = false,
				autoTrackingMode = onFoot,
			) shouldBe AutoTrackingPreferenceAction.NONE
		}
	}
}
