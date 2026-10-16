package com.adsamcik.tracker.shared.base.database.data

/** Recover the real interval of older groups whose saved coverage was clipped at midnight. */
fun AmbientWifiFactRevisionEntity.measuredStartTimeMs(): Long? = measuredStartTimeMs(
	coverageStartTimeMs, observedWallTimeMs, observedIntervalStartNanos,
	observedElapsedNanos, wallTimeUncertaintyMs,
)

/**
 * Returns the uncertainty-adjusted UTC start recovered from the original elapsed interval.
 * Fractional milliseconds round outward; invalid or overflowing bounds return null.
 */
fun AmbientCellFactRevisionEntity.measuredStartTimeMs(): Long? = measuredStartTimeMs(
	coverageStartTimeMs, observedWallTimeMs, observedIntervalStartNanos,
	observedElapsedNanos, wallTimeUncertaintyMs,
)

private fun measuredStartTimeMs(
	coverageStartMs: Long,
	observedWallMs: Long,
	intervalStartNanos: Long,
	observedNanos: Long,
	uncertaintyMs: Long,
): Long? {
	// Reject only invalid scalar interval fields before recovering the measured start.
	@Suppress("ComplexCondition")
	if (coverageStartMs < 0L || observedWallMs < coverageStartMs || intervalStartNanos <= 0L ||
		observedNanos < intervalStartNanos || uncertaintyMs < 0L) {
		return null
	}
	val spanNanos = observedNanos - intervalStartNanos
	// The latest wall timestamp was already rounded. A fractional span can cross another
	// millisecond before it; round the lower bound outward, never individual readings.
	val spanMs = spanNanos / 1_000_000L + if (spanNanos % 1_000_000L == 0L) {
		0L
	} else {
		1L
	}
	if (spanMs > observedWallMs) {
		return null
	}
	val nominalStart = minOf(coverageStartMs, observedWallMs - spanMs)
	return (nominalStart - uncertaintyMs).takeIf { uncertaintyMs <= nominalStart }
}
