package com.adsamcik.tracker.tracker.controller

import com.adsamcik.tracker.stats.engine.plane.PlaneState
import com.adsamcik.tracker.stats.engine.plane.RealTimePlaneState
import com.adsamcik.tracker.stats.engine.sailing.RealTimeSailingState
import com.adsamcik.tracker.stats.engine.sailing.SailingState
import com.adsamcik.tracker.stats.engine.ski.RealTimeSkiState
import com.adsamcik.tracker.stats.engine.ski.SkiState
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class DetectorStateMappersTest {
	@Test
	fun `maps ski detector state to tracker API projection`() {
		val state = RealTimeSkiState(
			state = SkiState.LIFT_UP,
			stateEntryTimeMs = 1L,
			stateDurationMs = 2L,
			completedRunCount = 3,
			isConfirmedSkiSession = true,
			currentRunVerticalM = 4f,
			currentRunMaxSpeedMps = 5f,
			totalVerticalM = 6f,
			totalRunCount = 7,
			isNearResort = true,
			currentLiftType = "gondola",
		)

		state.toLiveState() shouldBe LiveSkiState(
			state = LiveSkiPhase.LIFT_UP,
			stateEntryTimeMs = 1L,
			stateDurationMs = 2L,
			completedRunCount = 3,
			isConfirmedSkiSession = true,
			currentRunVerticalM = 4f,
			currentRunMaxSpeedMps = 5f,
			totalVerticalM = 6f,
			totalRunCount = 7,
			isNearResort = true,
			currentLiftType = "gondola",
		)
	}

	@Test
	fun `maps sailing detector state to tracker API projection`() {
		val state = RealTimeSailingState(
			state = SailingState.SAILING,
			stateEntryTimeMs = 1L,
			stateDurationMs = 2L,
			totalSailingDurationMs = 3L,
			totalSailingDistanceM = 4f,
			isConfirmedSailingSession = true,
			currentSpeedMps = 5f,
			maxSpeedMps = 6f,
		)

		state.toLiveState() shouldBe LiveSailingState(
			state = LiveSailingPhase.SAILING,
			stateEntryTimeMs = 1L,
			stateDurationMs = 2L,
			totalSailingDurationMs = 3L,
			totalSailingDistanceM = 4f,
			isConfirmedSailingSession = true,
			currentSpeedMps = 5f,
			maxSpeedMps = 6f,
		)
	}

	@Test
	fun `maps plane detector state to tracker API projection`() {
		val state = RealTimePlaneState(
			state = PlaneState.CLIMBING,
			stateEntryTimeMs = 1L,
			stateDurationMs = 2L,
			totalAirborneDurationMs = 3L,
			isConfirmedFlight = true,
			currentVerticalRateMps = 4f,
			maxSpeedMps = 5f,
		)

		state.toLiveState() shouldBe LivePlaneState(
			state = LivePlanePhase.CLIMBING,
			stateEntryTimeMs = 1L,
			stateDurationMs = 2L,
			totalAirborneDurationMs = 3L,
			isConfirmedFlight = true,
			currentVerticalRateMps = 4f,
			maxSpeedMps = 5f,
		)
	}
}
