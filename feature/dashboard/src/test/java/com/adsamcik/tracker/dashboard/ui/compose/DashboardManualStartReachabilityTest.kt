package com.adsamcik.tracker.dashboard.ui.compose

import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.tracker.api.TrackingCaptureSource
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

internal class DashboardManualStartReachabilityTest {
	@Test
	fun `ready enabled source starts only when its manual capture lane is reachable`() {
		resolveDashboardManualStartDecision(
			params = onlySteps(),
			capabilities = ALL_CAPABILITIES,
			reachableSources = setOf(TrackingCaptureSource.STEPS),
		) shouldBe DashboardManualStartDecision.START

		resolveDashboardManualStartDecision(
			params = onlySteps(),
			capabilities = ALL_CAPABILITIES,
			reachableSources = emptySet(),
		) shouldBe DashboardManualStartDecision.TRACKING_UNAVAILABLE
	}

	@Test
	fun `contained location does not prompt for a permission that cannot make capture reachable`() {
		resolveDashboardManualStartDecision(
			params = onlyLocation(),
			capabilities = ALL_CAPABILITIES.copy(
				anyLocationPermissionGranted = false,
				preciseLocationPermissionGranted = false,
			),
			reachableSources = emptySet(),
		) shouldBe DashboardManualStartDecision.TRACKING_UNAVAILABLE
	}

	@Test
	fun `reachable location still preserves the contextual permission prompt`() {
		resolveDashboardManualStartDecision(
			params = onlyLocation(),
			capabilities = ALL_CAPABILITIES.copy(
				anyLocationPermissionGranted = false,
				preciseLocationPermissionGranted = false,
			),
			reachableSources = setOf(TrackingCaptureSource.LOCATION),
		) shouldBe DashboardManualStartDecision.REQUEST_PRECISE_LOCATION_PERMISSION
	}

	@Test
	fun `reachable Activity without Play services is not a viable manual capture source`() {
		resolveDashboardManualStartDecision(
			params = onlyActivity(),
			capabilities = ALL_CAPABILITIES.copy(playServicesAvailable = false),
			reachableSources = setOf(TrackingCaptureSource.ACTIVITY),
		) shouldBe DashboardManualStartDecision.NO_AVAILABLE_CAPTURE_SOURCE
	}

	@Test
	fun `failed authoritative enqueue remains tracking unavailable`() {
		resolveDashboardManualStartEnqueueDecision(enqueued = false) shouldBe
			DashboardManualStartEnqueueDecision.TRACKING_UNAVAILABLE
		resolveDashboardManualStartEnqueueDecision(enqueued = true) shouldBe
			DashboardManualStartEnqueueDecision.ENQUEUED
	}

	@Test
	fun `an unavailable reachable source does not borrow a contained source`() {
		resolveDashboardManualStartDecision(
			params = onlySteps().copy(locationEnabled = true),
			capabilities = ALL_CAPABILITIES.copy(
				activityPermissionGranted = false,
			),
			reachableSources = setOf(TrackingCaptureSource.STEPS),
		) shouldBe DashboardManualStartDecision.NO_AVAILABLE_CAPTURE_SOURCE
	}

	@Test
	fun `nothing enabled remains a settings decision rather than rollout containment`() {
		resolveDashboardManualStartDecision(
			params = TrackingParamsState(
				locationEnabled = false,
				activityEnabled = false,
				stepsEnabled = false,
				wifiEnabled = false,
				cellEnabled = false,
				barometerEnabled = false,
			),
			capabilities = ALL_CAPABILITIES,
			reachableSources = emptySet(),
		) shouldBe DashboardManualStartDecision.NO_AVAILABLE_CAPTURE_SOURCE
	}

	private companion object {
		val ALL_CAPABILITIES = DashboardCaptureCapabilities(
			locationHardwareAvailable = true,
			anyLocationPermissionGranted = true,
			preciseLocationPermissionGranted = true,
			activityPermissionGranted = true,
			stepCounterAvailable = true,
			wifiHardwareAvailable = true,
			cellHardwareAvailable = true,
			readPhoneStatePermissionGranted = true,
			pressureSensorAvailable = true,
			playServicesAvailable = true,
		)

		fun onlySteps() = TrackingParamsState(
			locationEnabled = false,
			activityEnabled = false,
			stepsEnabled = true,
			wifiEnabled = false,
			cellEnabled = false,
			barometerEnabled = false,
		)

		fun onlyLocation() = onlySteps().copy(
			locationEnabled = true,
			stepsEnabled = false,
		)

		fun onlyActivity() = onlySteps().copy(
			activityEnabled = true,
			stepsEnabled = false,
		)
	}
}
