package com.adsamcik.tracker.tracker.api

import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.tracker.source.coordinator.CaptureReachabilityMode
import com.adsamcik.tracker.tracker.source.coordinator.TrackingRolloutStateStore
import com.adsamcik.tracker.tracker.source.model.SourceKind
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/** Maps the current source-local rollout authority onto the tracker API's read-only source model. */
@Singleton
class DefaultManualTrackingCaptureReachabilityReader @Inject constructor(
	private val startupGateProvider: Provider<TrackingStartupGate>,
	private val rolloutStateStoreProvider: Provider<TrackingRolloutStateStore>,
) : ManualTrackingCaptureReachabilityReader {
	override suspend fun read(): ManualTrackingCaptureReachability {
		val startupGate = startupGateProvider.get()
		val startupGeneration = startupGate.currentGeneration
		return startupGate.withReadyGenerationOperation(startupGeneration) {
			val rollout = rolloutStateStoreProvider.get().load()
			ManualTrackingCaptureReachability.Available(
				rolloutRevision = rollout.revision,
				reachableSources = SourceKind.entries
					.filterTo(linkedSetOf()) { source ->
						rollout.isCaptureReachable(
							source,
							CaptureReachabilityMode.MANUAL_SESSION_CAPTURE,
						)
					}
					.mapTo(linkedSetOf(), SourceKind::toApiCaptureSource),
			)
		} ?: ManualTrackingCaptureReachability.Unavailable
	}
}

private fun SourceKind.toApiCaptureSource(): TrackingCaptureSource = when (this) {
	SourceKind.LOCATION -> TrackingCaptureSource.LOCATION
	SourceKind.WIFI -> TrackingCaptureSource.WIFI
	SourceKind.CELL -> TrackingCaptureSource.CELL
	SourceKind.ACTIVITY -> TrackingCaptureSource.ACTIVITY
	SourceKind.STEPS -> TrackingCaptureSource.STEPS
	SourceKind.PRESSURE -> TrackingCaptureSource.PRESSURE
}
