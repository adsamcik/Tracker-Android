package com.adsamcik.tracker.tracker.service

import android.os.Build
import com.adsamcik.tracker.tracker.source.coordinator.SessionReconfigureResult
import com.adsamcik.tracker.tracker.source.coordinator.SessionStartOrigin
import com.adsamcik.tracker.tracker.source.coordinator.SourceSessionReconfigureOutcome
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
}
