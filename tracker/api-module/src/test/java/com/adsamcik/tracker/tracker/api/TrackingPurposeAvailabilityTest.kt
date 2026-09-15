package com.adsamcik.tracker.tracker.api

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TrackingPurposeAvailabilityTest {
	@Test
	fun `safe default contains automatic control and every approved ambient source`() {
		val snapshot = TrackingPurposeAvailabilitySnapshot.SAFE_DEFAULT

		snapshot.automaticControl shouldBe AutomaticTrackingOperationalAvailability.Unavailable(
			AutomaticTrackingUnavailableReason.CONTROL_RETENTION_POLICY_UNAVAILABLE,
		)
		AutomaticTrackingUnavailableReason.CONTROL_RETENTION_POLICY_UNAVAILABLE.stableCode shouldBe
			"CONTROL_RETENTION_POLICY_UNAVAILABLE"
		snapshot.ambientSources.keys shouldBe setOf(
			AmbientTrackingSource.STEPS,
			AmbientTrackingSource.LOCATION,
			AmbientTrackingSource.WIFI,
			AmbientTrackingSource.CELL,
		)
		snapshot.ambientSources.values.forEach { availability ->
			availability.isOperational.shouldBeFalse()
			availability.reason shouldBe AmbientSourceUnavailableReason.RETENTION_POLICY_UNAVAILABLE
			availability.reason?.stableCode shouldBe "AMBIENT_RETENTION_POLICY_UNAVAILABLE"
		}
	}

	@Test
	fun `availability snapshot can replace one source without changing siblings`() {
		val initial = TrackingPurposeAvailabilitySnapshot.SAFE_DEFAULT
		val steps = AmbientSourceOperationalAvailability(
			source = AmbientTrackingSource.STEPS,
			state = AmbientSourceOperationalState.PERMISSION_REQUIRED,
			mechanism = AmbientAcquisitionMechanism.HEALTH_CONNECT_MOBILE_STEPS,
			reason = AmbientSourceUnavailableReason.HEALTH_CONNECT_STEPS_PERMISSION_REQUIRED,
		)

		val changed = initial.copy(
			ambientSources = initial.ambientSources +
				(AmbientTrackingSource.STEPS to steps),
		)

		changed.ambientSources.getValue(AmbientTrackingSource.STEPS) shouldBe steps
		changed.ambientSources.getValue(AmbientTrackingSource.LOCATION).reason shouldBe
			AmbientSourceUnavailableReason.RETENTION_POLICY_UNAVAILABLE
	}
}
