package com.adsamcik.tracker.tracker.control

import com.adsamcik.tracker.stats.api.PolicyTier
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.Test

class TrackingControlShadowTest {
	@Test
	fun `policy barrier publishes its acquisition command without a future sensor event`() {
		val shadow = TrackingControlShadow(
			flags = TrackingDecisionFeatureFlags(
				shadowEnabled = true,
				applyAcquisitionRequests = true,
			),
		)
		shadow.begin(
			logicalTrackingId = "logical",
			isUserInitiated = true,
			wallTimeMs = 0L,
			elapsedRealtimeNanos = 0L,
			clockDomainId = "boot-a",
			initialTier = PolicyTier.PRECISION,
		)

		shadow.onPolicyTier(
			tier = PolicyTier.OFF,
			wallTimeMs = 1L,
			elapsedRealtimeNanos = 1L,
			clockDomainId = "boot-a",
			locationEnabled = false,
			reason = "TEST_LOCATION_DISABLED",
		)

		val command = assertNotNull(shadow.latestAcquisitionCommand())
		assertEquals(LocationAcquisitionMode.DISABLED, command.request.mode)
		assertEquals("LIFECYCLE_OR_LOCATION_DISABLED", command.request.reason)
	}
}
