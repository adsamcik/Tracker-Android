package com.adsamcik.tracker.tracker.ui.compose

import android.Manifest
import android.os.Build
import com.adsamcik.tracker.shared.utils.compose.permission.PermissionType
import com.adsamcik.tracker.tracker.api.ManualTrackingStartPrerequisite
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TrackerManualStartPrerequisiteTest {
	@Test
	fun `Android 12 precise repair requests fine and coarse together`() {
		ManualTrackingStartPrerequisite.PRECISE_LOCATION_PERMISSION
			.toTrackerPermissionRequest(Build.VERSION_CODES.S) shouldBe
			TrackerManualStartPermissionRequest(
				prerequisite = ManualTrackingStartPrerequisite.PRECISE_LOCATION_PERMISSION,
				type = PermissionType.LOCATION_FOREGROUND,
				permissions = listOf(
					Manifest.permission.ACCESS_FINE_LOCATION,
					Manifest.permission.ACCESS_COARSE_LOCATION,
				),
				requiredPermissions = setOf(Manifest.permission.ACCESS_FINE_LOCATION),
			)
	}

	@Test
	fun `Android 11 precise repair requests fine alone`() {
		ManualTrackingStartPrerequisite.PRECISE_LOCATION_PERMISSION
			.toTrackerPermissionRequest(Build.VERSION_CODES.R) shouldBe
			TrackerManualStartPermissionRequest(
				prerequisite = ManualTrackingStartPrerequisite.PRECISE_LOCATION_PERMISSION,
				type = PermissionType.LOCATION_FOREGROUND,
				permissions = listOf(Manifest.permission.ACCESS_FINE_LOCATION),
				requiredPermissions = setOf(Manifest.permission.ACCESS_FINE_LOCATION),
			)
	}

	@Test
	fun `Activity and Steps repair uses activity recognition permission`() {
		ManualTrackingStartPrerequisite.ACTIVITY_RECOGNITION_PERMISSION
			.toTrackerPermissionRequest() shouldBe TrackerManualStartPermissionRequest(
			prerequisite = ManualTrackingStartPrerequisite.ACTIVITY_RECOGNITION_PERMISSION,
			type = PermissionType.ACTIVITY_RECOGNITION,
			permissions = listOf(Manifest.permission.ACTIVITY_RECOGNITION),
			requiredPermissions = setOf(Manifest.permission.ACTIVITY_RECOGNITION),
		)
	}

	@Test
	fun `Cell phone-state repair uses phone permission rationale`() {
		ManualTrackingStartPrerequisite.READ_PHONE_STATE_PERMISSION
			.toTrackerPermissionRequest() shouldBe TrackerManualStartPermissionRequest(
			prerequisite = ManualTrackingStartPrerequisite.READ_PHONE_STATE_PERMISSION,
			type = PermissionType.PHONE_STATE,
			permissions = listOf(Manifest.permission.READ_PHONE_STATE),
			requiredPermissions = setOf(Manifest.permission.READ_PHONE_STATE),
		)
	}
}
