package com.adsamcik.tracker.tracker.api

/** Sources whose session-capture reachability may be exposed outside the tracking engine. */
enum class TrackingCaptureSource {
	LOCATION,
	WIFI,
	CELL,
	ACTIVITY,
	STEPS,
	PRESSURE,
}

/**
 * Authoritative read-only view of the sources this app binary may capture for a manual session.
 *
 * Settings and Android capabilities remain separate inputs: a source must be enabled, capable,
 * and present in [reachableSources] before a manual start may rely on it.
 */
sealed interface ManualTrackingCaptureReachability {
	/** Startup is ready and the current rollout authority was read successfully. */
	data class Available(
		val rolloutRevision: Long,
		val reachableSources: Set<TrackingCaptureSource>,
	) : ManualTrackingCaptureReachability {
		init {
			require(rolloutRevision >= 0L)
		}
	}

	/** Startup is not ready, so target Room must remain unopened from this read boundary. */
	data object Unavailable : ManualTrackingCaptureReachability
}

/** Read-only tracker API boundary for the current manual-session rollout. */
fun interface ManualTrackingCaptureReachabilityReader {
	suspend fun read(): ManualTrackingCaptureReachability
}
