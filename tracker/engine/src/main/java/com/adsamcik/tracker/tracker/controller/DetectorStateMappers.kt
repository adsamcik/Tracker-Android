package com.adsamcik.tracker.tracker.controller

import com.adsamcik.tracker.stats.engine.plane.PlaneState
import com.adsamcik.tracker.stats.engine.plane.RealTimePlaneState
import com.adsamcik.tracker.stats.engine.sailing.RealTimeSailingState
import com.adsamcik.tracker.stats.engine.sailing.SailingState
import com.adsamcik.tracker.stats.engine.ski.RealTimeSkiState
import com.adsamcik.tracker.stats.engine.ski.SkiState

internal fun RealTimeSkiState.toLiveState(): LiveSkiState = LiveSkiState(
	state = state.toLivePhase(),
	stateEntryTimeMs = stateEntryTimeMs,
	stateDurationMs = stateDurationMs,
	completedRunCount = completedRunCount,
	isConfirmedSkiSession = isConfirmedSkiSession,
	currentRunVerticalM = currentRunVerticalM,
	currentRunMaxSpeedMps = currentRunMaxSpeedMps,
	totalVerticalM = totalVerticalM,
	totalRunCount = totalRunCount,
	isNearResort = isNearResort,
	currentLiftType = currentLiftType,
)

private fun SkiState.toLivePhase(): LiveSkiPhase = when (this) {
	SkiState.IDLE -> LiveSkiPhase.IDLE
	SkiState.LIFT_UP -> LiveSkiPhase.LIFT_UP
	SkiState.DOWNHILL_RUN -> LiveSkiPhase.DOWNHILL_RUN
	SkiState.WALK -> LiveSkiPhase.WALK
}

internal fun RealTimeSailingState.toLiveState(): LiveSailingState = LiveSailingState(
	state = state.toLivePhase(),
	stateEntryTimeMs = stateEntryTimeMs,
	stateDurationMs = stateDurationMs,
	totalSailingDurationMs = totalSailingDurationMs,
	totalSailingDistanceM = totalSailingDistanceM,
	isConfirmedSailingSession = isConfirmedSailingSession,
	currentSpeedMps = currentSpeedMps,
	maxSpeedMps = maxSpeedMps,
)

private fun SailingState.toLivePhase(): LiveSailingPhase = when (this) {
	SailingState.IDLE -> LiveSailingPhase.IDLE
	SailingState.SAILING -> LiveSailingPhase.SAILING
	SailingState.WALK -> LiveSailingPhase.WALK
}

internal fun RealTimePlaneState.toLiveState(): LivePlaneState = LivePlaneState(
	state = state.toLivePhase(),
	stateEntryTimeMs = stateEntryTimeMs,
	stateDurationMs = stateDurationMs,
	totalAirborneDurationMs = totalAirborneDurationMs,
	isConfirmedFlight = isConfirmedFlight,
	currentVerticalRateMps = currentVerticalRateMps,
	maxSpeedMps = maxSpeedMps,
)

private fun PlaneState.toLivePhase(): LivePlanePhase = when (this) {
	PlaneState.IDLE -> LivePlanePhase.IDLE
	PlaneState.CLIMBING -> LivePlanePhase.CLIMBING
	PlaneState.CRUISING -> LivePlanePhase.CRUISING
	PlaneState.DESCENDING -> LivePlanePhase.DESCENDING
	PlaneState.WALK -> LivePlanePhase.WALK
}
