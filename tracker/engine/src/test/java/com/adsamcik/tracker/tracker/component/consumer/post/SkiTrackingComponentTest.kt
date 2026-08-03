package com.adsamcik.tracker.tracker.component.consumer.post

import com.adsamcik.tracker.stats.engine.ski.RealTimeSkiState
import com.adsamcik.tracker.stats.engine.ski.SkiState
import com.adsamcik.tracker.stats.engine.ski.SkiStateListener
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SkiTrackingComponentTest {
	@Test
	fun `throwing listener does not block later listeners or state publication`() {
		val component = SkiTrackingComponent()
		var secondListenerInvoked = false
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
	}
}
