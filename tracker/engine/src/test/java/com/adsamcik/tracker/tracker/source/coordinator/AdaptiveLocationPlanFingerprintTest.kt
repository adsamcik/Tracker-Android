package com.adsamcik.tracker.tracker.source.coordinator

import com.adsamcik.tracker.shared.preferences.tracking.LocationBackgroundMode
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.tracker.source.model.LocationBackend
import com.adsamcik.tracker.tracker.source.model.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AdaptiveLocationPlanFingerprintTest {
    @Test
    fun `overall overrides and actual participant set fence adopted delayed starts`() {
        val settings = TrackingParamsState(balanceProfileVersion = 1, overallBalanceLevel = 2,
            locationProfileVersion = 1, locationBalanceLevel = 2)
        val initial = inputs(settings)
        val original = fingerprint(initial)
        assertNotEquals(original, fingerprint(inputs(settings.copy(overallBalanceLevel = 3, locationBalanceLevel = 3))))
        assertNotEquals(original, fingerprint(inputs(settings.copy(sourceBalanceOverrides =
            com.adsamcik.tracker.shared.preferences.tracking.SourceBalanceOverrides(wifi = 5)))))
        assertNotEquals(original, fingerprint(initial.copy(environment = initial.environment.copy(liveSources = setOf(SourceKind.STEPS)))))
        assertEquals(original, fingerprint(inputs(settings.copy())))
    }

	@Test
	fun `new metadata does not alter a legacy prepared start`() {
		val legacy = inputs(TrackingParamsState())
		assertEquals(fingerprint(legacy), fingerprint(legacy.copy(
			environment = legacy.environment.copy(liveSources = setOf(SourceKind.LOCATION)),
			resolutionContext = legacy.resolutionContext.copy(locationLiveFocus = true),
		)))
	}

	@Test
	fun `balance background consent mode and assistance fence delayed adaptive starts`() {
		val settings = TrackingParamsState(locationProfileVersion = 1, locationBalanceLevel = 3,
			locationBackgroundMode = LocationBackgroundMode.ADAPTIVE)
		val original = fingerprint(inputs(settings))
		listOf(
			settings.copy(locationBalanceLevel = 4),
			settings.copy(locationBackgroundMode = LocationBackgroundMode.PASSIVE_ONLY),
			settings.copy(locationMotionAssistanceEnabled = true),
		).forEach { changed -> assertNotEquals(original, fingerprint(inputs(changed))) }
	}

	@Test
	fun `live participation and stationary profile are part of adaptive identity`() {
		val initial = inputs(TrackingParamsState(locationProfileVersion = 1, locationBalanceLevel = 3))
		assertNotEquals(fingerprint(initial), fingerprint(initial.copy(
			environment = initial.environment.copy(liveSources = setOf(SourceKind.LOCATION)),
			resolutionContext = initial.resolutionContext.copy(locationLiveFocus = true),
		)))
		assertNotEquals(fingerprint(initial), fingerprint(initial.copy(
			resolutionContext = initial.resolutionContext.copy(locationProfileLevel = 4),
		)))
		assertNotEquals(fingerprint(initial), fingerprint(initial.copy(
			resolutionContext = initial.resolutionContext.copy(
				batteryPressure = com.adsamcik.tracker.tracker.policy.BatteryPressure.STRONG),
		)))
	}

	private fun inputs(settings: TrackingParamsState) = SourceSessionPlanInputs(
		settings = settings,
		environment = SourcePlanEnvironment(LocationBackend.FRAMEWORK, true, emptySet()),
		resolutionContext = PlanResolutionContext(
			constraints = emptyMap(), powerSaver = false, doze = false, severeThermalPressure = false,
			locationProfileLevel = settings.locationBalanceLevel,
		),
		demands = emptyList(),
		clockDomainId = "boot-1",
		zoneId = "UTC",
	)

	private fun fingerprint(inputs: SourceSessionPlanInputs) = inputs.planInputsFingerprint(
		rolloutRevision = 1L,
		captureMode = CaptureReachabilityMode.MANUAL_SESSION_CAPTURE,
		startOrigin = SessionStartOrigin.MANUAL_FOREGROUND_START,
		foregroundCapabilityFlags = 1L,
		controlDependencies = emptySet(),
	)
}
