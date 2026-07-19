package com.adsamcik.tracker.tracker.resilience

import com.adsamcik.tracker.stats.api.PolicyTier
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TrackerRestartDecisionTest {
	private val userSession = ActiveTrackingSessionDescriptor(
		isUserInitiated = true,
		isAmbient = false,
		policyTier = PolicyTier.PRECISION,
	)

	@Test
	fun `unexpected user-session teardown schedules one restart`() {
		shouldScheduleTrackerRestart(userSession, false, false) shouldBe true
		shouldScheduleTrackerRestart(userSession, false, true) shouldBe false
	}

	@Test
	fun `graceful and automatic sessions never schedule watchdog restart`() {
		shouldScheduleTrackerRestart(userSession, true, false) shouldBe false
		shouldScheduleTrackerRestart(
			userSession.copy(isUserInitiated = false, isAmbient = true),
			false,
			false,
		) shouldBe false
		shouldScheduleTrackerRestart(null, false, false) shouldBe false
	}
}

