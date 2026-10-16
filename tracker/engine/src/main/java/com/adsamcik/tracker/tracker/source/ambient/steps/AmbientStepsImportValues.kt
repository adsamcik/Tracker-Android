package com.adsamcik.tracker.tracker.source.ambient.steps

internal fun roundForwardToSecond(timeMs: Long): Long {
	require(timeMs >= 0L)
	val remainder = timeMs % AMBIENT_STEPS_IMPORT_MILLIS_PER_SECOND
	return if (remainder == 0L) {
		timeMs
	} else {
		Math.addExact(timeMs, AMBIENT_STEPS_IMPORT_MILLIS_PER_SECOND - remainder)
	}
}

internal fun roundBackwardToSecond(timeMs: Long): Long {
	require(timeMs >= 0L)
	return timeMs - (timeMs % AMBIENT_STEPS_IMPORT_MILLIS_PER_SECOND)
}

internal fun AmbientStepsProviderHandoffCommand.importBoundary(
	throughTimeMs: Long = roundBackwardToSecond(observedAtMs),
): AmbientStepsImportBoundary = AmbientStepsImportBoundary(
	observedBootId = observedBootId,
	observedElapsedRealtimeNanos = observedElapsedRealtimeNanos,
	observedAtMs = observedAtMs,
	throughTimeMs = throughTimeMs,
	zoneId = zoneId,
)

internal fun ineligible(reason: AmbientStepsImportIneligibleReason) =
	AmbientStepsImportResult.Ineligible(reason)

internal fun stale(reason: AmbientStepsImportStaleReason) = AmbientStepsImportResult.Stale(reason)

internal fun retryable(reason: AmbientStepsImportRetryableReason) =
	AmbientStepsImportResult.Retryable(reason)
