package com.adsamcik.tracker.tracker.source.coordinator

import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.tracker.source.projection.ActivityAutomationDrainSignal

internal object ReadyTrackingStartupGate : TrackingStartupGate {
	override val isReady: Boolean = true
	override val currentGeneration: Long = 1L

	override suspend fun reconcile(retryFailedStorage: Boolean): TrackingStartupResult =
		TrackingStartupResult.Ready(
			legacyRecoveryPartial = false,
			liveCompletedThroughOrdinal = 0L,
		)
}

internal val NoOpActivityAutomationDrainSignal = ActivityAutomationDrainSignal { }
