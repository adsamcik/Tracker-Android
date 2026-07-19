package com.adsamcik.tracker.tracker.service

interface ActivityWatcherController {
	fun poke()

	fun pauseForDataDeletion()

	fun resumeAfterDataDeletion()
}
