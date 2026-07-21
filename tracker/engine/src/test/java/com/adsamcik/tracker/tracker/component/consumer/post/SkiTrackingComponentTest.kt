package com.adsamcik.tracker.tracker.component.consumer.post

import com.adsamcik.tracker.stats.api.PolicyEscalationEngine
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.stats.engine.ski.RealTimeSkiState
import com.adsamcik.tracker.stats.engine.ski.SkiState
import com.adsamcik.tracker.stats.engine.ski.SkiStateListener
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

class SkiTrackingComponentTest {
	@Test
	fun `throwing listener does not block later listeners state publication or policy update`() {
		val component = SkiTrackingComponent()
		val escalationEngine = mockk<PolicyEscalationEngine>(relaxed = true)
		var secondListenerInvoked = false
		component.setEscalationEngine(escalationEngine)
		component.setSecondaryListeners(
			listOf(
				SkiStateListener { _, _ -> error("listener failure") },
				SkiStateListener { _, _ -> secondListenerInvoked = true },
			)
		)
		val newState = RealTimeSkiState(
			state = SkiState.DOWNHILL_RUN,
			stateEntryTimeMs = 12_000L,
			stateDurationMs = 3_000L,
			completedRunCount = 1,
			isConfirmedSkiSession = true,
			currentRunVerticalM = 20f,
			currentRunMaxSpeedMps = 15f,
			totalVerticalM = 20f,
			totalRunCount = 1,
		)

		component.onStateChanged(SkiState.LIFT_UP, newState)

		secondListenerInvoked.shouldBeTrue()
		component.skiState.value shouldBe newState
		verify(exactly = 1) {
			escalationEngine.setMinimumTier(PolicyTier.PRECISION, "ski descent detected")
		}
	}
}
