package com.adsamcik.tracker.tracker.controller

data class LiveSkiState(
	val state: LiveSkiPhase,
	val stateEntryTimeMs: Long,
	val stateDurationMs: Long,
	val completedRunCount: Int,
	val isConfirmedSkiSession: Boolean,
	val currentRunVerticalM: Float,
	val currentRunMaxSpeedMps: Float,
	val totalVerticalM: Float,
	val totalRunCount: Int,
	val isNearResort: Boolean = false,
	val currentLiftType: String? = null,
)

enum class LiveSkiPhase {
	IDLE,
	LIFT_UP,
	DOWNHILL_RUN,
	WALK,
}

data class LiveSailingState(
	val state: LiveSailingPhase,
	val stateEntryTimeMs: Long,
	val stateDurationMs: Long,
	val totalSailingDurationMs: Long,
	val totalSailingDistanceM: Float,
	val isConfirmedSailingSession: Boolean,
	val currentSpeedMps: Float,
	val maxSpeedMps: Float,
)

enum class LiveSailingPhase {
	IDLE,
	SAILING,
	WALK,
}

data class LivePlaneState(
	val state: LivePlanePhase,
	val stateEntryTimeMs: Long,
	val stateDurationMs: Long,
	val totalAirborneDurationMs: Long,
	val isConfirmedFlight: Boolean,
	val currentVerticalRateMps: Float,
	val maxSpeedMps: Float,
)

enum class LivePlanePhase {
	IDLE,
	CLIMBING,
	CRUISING,
	DESCENDING,
	WALK,
}
