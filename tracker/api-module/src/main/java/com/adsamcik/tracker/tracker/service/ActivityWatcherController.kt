package com.adsamcik.tracker.tracker.service

interface ActivityWatcherController {
	fun poke()

	/** Keeps the watcher foreground-service bridge aligned with the persisted automatic mode. */
	fun applyAutoTrackingMode(mode: Int)

	fun pauseForDataDeletion()

	fun resumeAfterDataDeletion()
}
