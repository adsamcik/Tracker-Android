package com.adsamcik.tracker.tracker.source.ambient.steps

import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.Test

class AmbientStepsCapabilitySelectorTest {
	@Test
	fun `Health Connect wins when both continuity providers are available`() {
		val selected = AmbientStepsCapabilitySelector.select(
			snapshot(
				healthConnect = HealthConnectAmbientStepsAvailability.AVAILABLE,
				healthConnectReadStepsGranted = true,
				localRecording = LocalRecordingAmbientStepsAvailability.AVAILABLE,
			),
		)

		assertEquals(
			AmbientStepsProvider.HEALTH_CONNECT_MOBILE_STEPS,
			assertIs<AmbientStepsCapability.ReadyForRegistration>(selected).provider,
		)
	}

	@Test
	fun `missing Health Connect grant never silently falls back to Local Recording`() {
		val selected = assertIs<AmbientStepsCapability.PermissionRequired>(
			AmbientStepsCapabilitySelector.select(
				snapshot(
					healthConnect = HealthConnectAmbientStepsAvailability.AVAILABLE,
					healthConnectReadStepsGranted = false,
					localRecording = LocalRecordingAmbientStepsAvailability.AVAILABLE,
				),
			),
		)

		assertEquals(AmbientStepsProvider.HEALTH_CONNECT_MOBILE_STEPS, selected.provider)
		assertEquals(setOf(AmbientStepsPermission.HEALTH_CONNECT_READ_STEPS), selected.requiredPermissions)
	}

	@Test
	fun `Health Connect remains useful in foreground when background reads are not granted`() {
		val selected = assertIs<AmbientStepsCapability.ReadyForRegistration>(
			AmbientStepsCapabilitySelector.select(
				snapshot(
					healthConnect = HealthConnectAmbientStepsAvailability.AVAILABLE,
					healthConnectReadStepsGranted = true,
					healthConnectBackgroundReadAvailable = true,
					healthConnectBackgroundReadGranted = false,
				),
			),
		)

		assertEquals(AmbientStepsImportAccess.FOREGROUND_ONLY, selected.importAccess)
		assertEquals(
			setOf(AmbientStepsPermission.HEALTH_CONNECT_BACKGROUND_READ),
			selected.optionalPermissions,
		)
	}

	@Test
	fun `Health Connect background grant changes import access but not provider`() {
		val selected = assertIs<AmbientStepsCapability.ReadyForRegistration>(
			AmbientStepsCapabilitySelector.select(
				snapshot(
					healthConnect = HealthConnectAmbientStepsAvailability.AVAILABLE,
					healthConnectReadStepsGranted = true,
					healthConnectBackgroundReadAvailable = true,
					healthConnectBackgroundReadGranted = true,
				),
			),
		)

		assertEquals(AmbientStepsProvider.HEALTH_CONNECT_MOBILE_STEPS, selected.provider)
		assertEquals(AmbientStepsImportAccess.BACKGROUND_ALLOWED, selected.importAccess)
		assertEquals(emptySet(), selected.optionalPermissions)
	}

	@Test
	fun `Local Recording is selected when Health Connect mobile steps is not capable`() {
		val selected = assertIs<AmbientStepsCapability.ReadyForRegistration>(
			AmbientStepsCapabilitySelector.select(
				snapshot(
					healthConnect = HealthConnectAmbientStepsAvailability.EXTENSION_TOO_OLD,
					localRecording = LocalRecordingAmbientStepsAvailability.AVAILABLE,
					activityRecognitionRequired = true,
					activityRecognitionGranted = true,
				),
			),
		)

		assertEquals(AmbientStepsProvider.LOCAL_RECORDING_STEPS, selected.provider)
		assertEquals(AmbientStepsImportAccess.BACKGROUND_ALLOWED, selected.importAccess)
	}

	@Test
	fun `Local Recording exposes its exact runtime permission requirement`() {
		val selected = assertIs<AmbientStepsCapability.PermissionRequired>(
			AmbientStepsCapabilitySelector.select(
				snapshot(
					healthConnect = HealthConnectAmbientStepsAvailability.PLATFORM_TOO_OLD,
					localRecording = LocalRecordingAmbientStepsAvailability.AVAILABLE,
					activityRecognitionRequired = true,
					activityRecognitionGranted = false,
				),
			),
		)

		assertEquals(AmbientStepsProvider.LOCAL_RECORDING_STEPS, selected.provider)
		assertEquals(setOf(AmbientStepsPermission.ACTIVITY_RECOGNITION), selected.requiredPermissions)
	}

	@Test
	fun `pre Android 10 Local Recording needs no runtime Activity Recognition grant`() {
		val selected = assertIs<AmbientStepsCapability.ReadyForRegistration>(
			AmbientStepsCapabilitySelector.select(
				snapshot(
					healthConnect = HealthConnectAmbientStepsAvailability.PLATFORM_TOO_OLD,
					localRecording = LocalRecordingAmbientStepsAvailability.AVAILABLE,
					activityRecognitionRequired = false,
					activityRecognitionGranted = false,
				),
			),
		)

		assertEquals(AmbientStepsProvider.LOCAL_RECORDING_STEPS, selected.provider)
	}

	@Test
	fun `Health Connect probe failure fails closed instead of switching providers`() {
		val selected = assertIs<AmbientStepsCapability.Unavailable>(
			AmbientStepsCapabilitySelector.select(
				snapshot(
					healthConnect = HealthConnectAmbientStepsAvailability.PROBE_FAILED,
					localRecording = LocalRecordingAmbientStepsAvailability.AVAILABLE,
					activityRecognitionGranted = true,
				),
			),
		)

		assertEquals(HealthConnectAmbientStepsAvailability.PROBE_FAILED, selected.healthConnect)
		assertEquals(LocalRecordingAmbientStepsAvailability.AVAILABLE, selected.localRecording)
	}

	@Test
	fun `absence of both providers stays typed and does not use direct session sensor`() {
		val selected = assertIs<AmbientStepsCapability.Unavailable>(
			AmbientStepsCapabilitySelector.select(
				snapshot(
					healthConnect = HealthConnectAmbientStepsAvailability.SDK_UNAVAILABLE,
					localRecording = LocalRecordingAmbientStepsAvailability.PLAY_SERVICES_MISSING,
				),
			),
		)

		assertEquals(null, selected.provider)
		assertEquals(HealthConnectAmbientStepsAvailability.SDK_UNAVAILABLE, selected.healthConnect)
		assertEquals(
			LocalRecordingAmbientStepsAvailability.PLAY_SERVICES_MISSING,
			selected.localRecording,
		)
	}

	private fun snapshot(
		healthConnect: HealthConnectAmbientStepsAvailability,
		healthConnectReadStepsGranted: Boolean = false,
		healthConnectBackgroundReadAvailable: Boolean = false,
		healthConnectBackgroundReadGranted: Boolean = false,
		localRecording: LocalRecordingAmbientStepsAvailability =
			LocalRecordingAmbientStepsAvailability.PLAY_SERVICES_UNAVAILABLE,
		activityRecognitionRequired: Boolean = true,
		activityRecognitionGranted: Boolean = false,
	) = AmbientStepsPlatformSnapshot(
		healthConnect = healthConnect,
		healthConnectReadStepsGranted = healthConnectReadStepsGranted,
		healthConnectBackgroundReadAvailable = healthConnectBackgroundReadAvailable,
		healthConnectBackgroundReadGranted = healthConnectBackgroundReadGranted,
		localRecording = localRecording,
		activityRecognitionRequired = activityRecognitionRequired,
		activityRecognitionGranted = activityRecognitionGranted,
	)
}
