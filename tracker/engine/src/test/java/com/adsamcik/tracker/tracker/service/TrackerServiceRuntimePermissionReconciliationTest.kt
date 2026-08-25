package com.adsamcik.tracker.tracker.service

import android.content.pm.ServiceInfo
import android.os.Build
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.tracker.resilience.ActiveTrackingSessionDescriptor
import com.adsamcik.tracker.tracker.source.coordinator.CaptureReachabilityMode
import com.adsamcik.tracker.tracker.source.coordinator.SessionReconfigureResult
import com.adsamcik.tracker.tracker.source.coordinator.SessionStartOrigin
import com.adsamcik.tracker.tracker.source.coordinator.SourceSessionReconfigureOutcome
import com.adsamcik.tracker.tracker.source.coordinator.TrackingRolloutState
import com.adsamcik.tracker.tracker.source.model.SourceKind
import io.kotest.matchers.shouldBe
import org.junit.Test

class TrackerServiceRuntimePermissionReconciliationTest {
	@Test
	fun `mixed session keeps unrelated sources when activity permission disappears`() {
		acceptedForegroundSources(
			requestedSources = setOf(
				SourceKind.LOCATION,
				SourceKind.ACTIVITY,
				SourceKind.STEPS,
				SourceKind.PRESSURE,
			),
			capabilities = ForegroundSourceCapabilities(
				sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
				startOrigin = SessionStartOrigin.MANUAL_FOREGROUND_START,
				hasForegroundLocationPermission = true,
				hasBackgroundLocationPermission = false,
				locationHardwareAvailable = true,
				activity = false,
				steps = false,
				pressure = true,
				wifi = false,
				cell = false,
			),
		) shouldBe setOf(SourceKind.LOCATION, SourceKind.PRESSURE)
	}

	@Test
	fun `runtime permission reconfigure only stops on coordinator rejection`() {
		shouldStopAfterRuntimePermissionReconfigure(null) shouldBe false
		shouldStopAfterRuntimePermissionReconfigure(SourceSessionReconfigureOutcome.NotActive) shouldBe false
		shouldStopAfterRuntimePermissionReconfigure(SourceSessionReconfigureOutcome.Unchanged) shouldBe false
		shouldStopAfterRuntimePermissionReconfigure(
			SourceSessionReconfigureOutcome.Rejected(
				SessionReconfigureResult.InvalidState("TEST_REJECTION"),
			),
		) shouldBe true
	}

	@Test
	fun `revoking the last accepted capture source terminates the session`() {
		shouldStopAfterRuntimePermissionReconfigure(
			SourceSessionReconfigureOutcome.Unchanged,
			acceptedCaptureSourceCount = 0,
		) shouldBe true
		shouldStopAfterRuntimePermissionReconfigure(
			SourceSessionReconfigureOutcome.NotActive,
			acceptedCaptureSourceCount = 0,
		) shouldBe true
	}

	@Test
	fun `mixed session remains active while one accepted capture source survives`() {
		shouldStopAfterRuntimePermissionReconfigure(
			SourceSessionReconfigureOutcome.Unchanged,
			acceptedCaptureSourceCount = 1,
		) shouldBe false
	}

	@Test
	fun `automatic session excludes manual-only Location and Steps from foreground types`() {
		val rollout = TrackingRolloutState.eventShadow(
			sources = setOf(SourceKind.LOCATION, SourceKind.STEPS, SourceKind.PRESSURE),
			captureModes = mapOf(
				SourceKind.LOCATION to setOf(CaptureReachabilityMode.MANUAL_SESSION_CAPTURE),
				SourceKind.STEPS to setOf(CaptureReachabilityMode.MANUAL_SESSION_CAPTURE),
				SourceKind.PRESSURE to setOf(CaptureReachabilityMode.AUTOMATIC_SESSION_CAPTURE),
			),
		)
		val ownership = resolveActiveSessionOwnership(
			rollout = rollout,
			settings = trackingSettings(
				location = true,
				activity = false,
				steps = true,
				pressure = true,
			),
			descriptor = automaticDescriptor(),
		)
		val accepted = acceptedForegroundSources(
			requestedSources = ownership.enabledEventSources,
			capabilities = availableCapabilities(),
		)

		accepted shouldBe setOf(SourceKind.PRESSURE)
		foregroundServiceTypeCandidates(Build.VERSION_CODES.UPSIDE_DOWN_CAKE, accepted) shouldBe
			listOf(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
	}

	@Test
	fun `revoking last automatic-reachable source stops despite configured manual-only source`() {
		val rollout = TrackingRolloutState.eventShadow(
			sources = setOf(SourceKind.LOCATION, SourceKind.ACTIVITY),
			captureModes = mapOf(
				SourceKind.LOCATION to setOf(CaptureReachabilityMode.MANUAL_SESSION_CAPTURE),
				SourceKind.ACTIVITY to setOf(CaptureReachabilityMode.AUTOMATIC_SESSION_CAPTURE),
			),
		)
		val ownership = resolveActiveSessionOwnership(
			rollout = rollout,
			settings = trackingSettings(
				location = true,
				activity = true,
				steps = false,
				pressure = false,
			),
			descriptor = automaticDescriptor(),
		)
		val acceptedAfterRevocation = acceptedForegroundSources(
			requestedSources = ownership.enabledEventSources,
			capabilities = availableCapabilities(activity = false),
		)

		acceptedAfterRevocation shouldBe emptySet()
		shouldStopAfterRuntimePermissionReconfigure(
			SourceSessionReconfigureOutcome.Unchanged,
			acceptedCaptureSourceCount = acceptedAfterRevocation.size,
		) shouldBe true
	}

	private fun automaticDescriptor() = ActiveTrackingSessionDescriptor(
		isUserInitiated = false,
		isAmbient = false,
		policyTier = PolicyTier.ACTIVE,
	)

	private fun trackingSettings(
		location: Boolean,
		activity: Boolean,
		steps: Boolean,
		pressure: Boolean,
	) = TrackingParamsState(
		locationEnabled = location,
		activityEnabled = activity,
		stepsEnabled = steps,
		barometerEnabled = pressure,
		wifiEnabled = false,
		cellEnabled = false,
	)

	private fun availableCapabilities(
		activity: Boolean = true,
	) = ForegroundSourceCapabilities(
		sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
		startOrigin = SessionStartOrigin.AUTOMATIC_BACKGROUND_START,
		hasForegroundLocationPermission = true,
		hasBackgroundLocationPermission = true,
		locationHardwareAvailable = true,
		activity = activity,
		steps = true,
		pressure = true,
		wifi = true,
		cell = true,
	)
}
