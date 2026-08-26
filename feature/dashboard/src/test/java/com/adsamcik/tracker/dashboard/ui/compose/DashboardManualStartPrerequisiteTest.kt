package com.adsamcik.tracker.dashboard.ui.compose

import android.Manifest
import com.adsamcik.tracker.shared.utils.compose.permission.PermissionType
import com.adsamcik.tracker.tracker.api.ManualTrackingStartPrerequisite
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

internal class DashboardManualStartPrerequisiteTest {
	@Test
	fun `known permission prerequisites map to their exact Android permission`() {
		ManualTrackingStartPrerequisite.PRECISE_LOCATION_PERMISSION
			.toDashboardPermissionRequest() shouldBe DashboardManualStartPermissionRequest(
			prerequisite = ManualTrackingStartPrerequisite.PRECISE_LOCATION_PERMISSION,
			type = PermissionType.LOCATION_FOREGROUND,
			permission = Manifest.permission.ACCESS_FINE_LOCATION,
		)
		ManualTrackingStartPrerequisite.ACTIVITY_RECOGNITION_PERMISSION
			.toDashboardPermissionRequest() shouldBe DashboardManualStartPermissionRequest(
			prerequisite = ManualTrackingStartPrerequisite.ACTIVITY_RECOGNITION_PERMISSION,
			type = PermissionType.ACTIVITY_RECOGNITION,
			permission = Manifest.permission.ACTIVITY_RECOGNITION,
		)
		ManualTrackingStartPrerequisite.READ_PHONE_STATE_PERMISSION
			.toDashboardPermissionRequest() shouldBe DashboardManualStartPermissionRequest(
			prerequisite = ManualTrackingStartPrerequisite.READ_PHONE_STATE_PERMISSION,
			type = PermissionType.PHONE_STATE,
			permission = Manifest.permission.READ_PHONE_STATE,
		)
	}

	@Test
	fun `Location Services is a settings repair rather than a runtime permission`() {
		ManualTrackingStartPrerequisite.LOCATION_SERVICES
			.toDashboardPermissionRequest() shouldBe null
	}
}
