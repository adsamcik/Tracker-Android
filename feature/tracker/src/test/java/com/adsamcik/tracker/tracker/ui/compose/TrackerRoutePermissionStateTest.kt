package com.adsamcik.tracker.tracker.ui.compose

import io.kotest.matchers.shouldBe
import org.junit.Test

class TrackerRoutePermissionStateTest {

    @Test
    fun `granted system result clears permission request state`() {
        resolveTrackerPermissionResult(granted = true) shouldBe TrackerPermissionResultState(
            hasLocationPermission = true,
            showLocationPermissionRequest = false,
            permissionDenied = false,
        )
    }

    @Test
    fun `denied system result clears permission request state`() {
        resolveTrackerPermissionResult(granted = false) shouldBe TrackerPermissionResultState(
            hasLocationPermission = false,
            showLocationPermissionRequest = false,
            permissionDenied = true,
        )
    }
}
