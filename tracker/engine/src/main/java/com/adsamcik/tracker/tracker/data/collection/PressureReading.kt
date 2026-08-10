package com.adsamcik.tracker.tracker.data.collection

/** Aggregate barometric pressure for one event window. */
data class PressureReading(
	val pressureHpa: Float,
	val altitudeM: Float,
	val sampleCount: Int = 1,
	val minPressureHpa: Float = pressureHpa,
	val maxPressureHpa: Float = pressureHpa,
	val standardDeviationHpa: Float = 0f,
	val windowStartElapsedRealtimeNanos: Long? = null,
	val windowEndElapsedRealtimeNanos: Long? = null,
	val sourceFirstSequence: Long? = null,
	val sourceLastSequence: Long? = null,
)
