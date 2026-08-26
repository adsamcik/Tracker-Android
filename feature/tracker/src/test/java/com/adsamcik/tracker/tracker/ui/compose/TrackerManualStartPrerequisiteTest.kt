package com.adsamcik.tracker.tracker.ui.compose

import android.Manifest
import com.adsamcik.tracker.shared.utils.compose.permission.PermissionType
import com.adsamcik.tracker.tracker.api.ManualTrackingStartPrerequisite
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TrackerManualStartPrerequisiteTest {
	@Test
	fun `Activity and Steps repair uses activity recognition permission`() {
		ManualTrackingStartPrerequisite.ACTIVITY_RECOGNITION_PERMISSION
			.toTrackerPermissionRequest() shouldBe TrackerManualStartPermissionRequest(
			prerequisite = ManualTrackingStartPrerequisite.ACTIVITY_RECOGNITION_PERMISSION,
			type = PermissionType.ACTIVITY_RECOGNITION,
			permission = Manifest.permission.ACTIVITY_RECOGNITION,
		)
	}

	@Test
	fun `Cell phone-state repair uses phone permission rationale`() {
		ManualTrackingStartPrerequisite.READ_PHONE_STATE_PERMISSION
			.toTrackerPermissionRequest() shouldBe TrackerManualStartPermissionRequest(
			prerequisite = ManualTrackingStartPrerequisite.READ_PHONE_STATE_PERMISSION,
			type = PermissionType.PHONE_STATE,
			permission = Manifest.permission.READ_PHONE_STATE,
		)
	}
}
