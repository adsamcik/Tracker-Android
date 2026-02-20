package com.adsamcik.tracker.stats.engine.segment

/**
 * Tunable thresholds for the session segment detector.
 *
 * Defaults are conservative to minimize false trip detections while
 * maintaining reasonable sensitivity for urban commuting scenarios.
 *
 * @property driftRadiusM GPS drift suppression: signals within this radius of the
 *   anchor point are treated as stationary (meters)
 * @property departureDisplacementM Minimum displacement from anchor to confirm departure (meters)
 * @property departureConfirmationMs Time window for departure confirmation (millis)
 * @property departureMinSteps Alternative departure confirmation via step count
 * @property stillnessSpeedThreshold Speed below this is considered stationary (m/s)
 * @property stillCyclesForStopPending Consecutive still cycles to enter STOP_PENDING
 * @property walkStopTimeoutMs Stop timeout for walking trips (millis)
 * @property driveStopTimeoutMs Stop timeout for driving trips (millis)
 * @property transitStopTimeoutMs Stop timeout for transit trips (millis)
 * @property minActivityConfidence Minimum confidence to consider activity recognition
 * @property departureActivityConfidence Minimum confidence for activity-based departure trigger
 * @property inferenceVersion Version tag written to persisted segments
 */
data class SegmentDetectorConfig(
	val driftRadiusM: Float = 25f,
	val departureDisplacementM: Float = 50f,
	val departureConfirmationMs: Long = 120_000L,
	val departureMinSteps: Int = 100,
	val stillnessSpeedThreshold: Float = 0.3f,
	val stillCyclesForStopPending: Int = 3,
	val walkStopTimeoutMs: Long = 300_000L,
	val driveStopTimeoutMs: Long = 600_000L,
	val transitStopTimeoutMs: Long = 900_000L,
	val minActivityConfidence: Int = 50,
	val departureActivityConfidence: Int = 60,
	val inferenceVersion: String = "detector-v1",
)
