package com.adsamcik.tracker.tracker.resilience

internal fun shouldScheduleTrackerRestart(
	descriptor: ActiveTrackingSessionDescriptor?,
	gracefulStopRequested: Boolean,
	restartAlreadyScheduled: Boolean,
): Boolean = descriptor?.isRestartEligible == true &&
	!gracefulStopRequested &&
	!restartAlreadyScheduled
