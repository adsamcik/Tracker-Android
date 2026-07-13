package com.adsamcik.tracker.tracker.api

import com.adsamcik.tracker.tracker.controller.TrackerServiceController
import com.adsamcik.tracker.tracker.service.ActivityWatcherController
import io.mockk.mockk
import io.mockk.verifyOrder
import org.junit.jupiter.api.Test

class TrackerServiceApiTest {
	@Test
	fun `stale service repair restarts automatic tracking watcher evaluation`() {
		val controller = mockk<TrackerServiceController>(relaxed = true)
		val activityWatcherController = mockk<ActivityWatcherController>(relaxed = true)

		TrackerServiceApi.repairStoppedServiceState(controller, activityWatcherController)

		verifyOrder {
			controller.updateServiceRunning(false)
			controller.updateSessionInfo(null)
			controller.updateSession(null)
			controller.updateCollectionData(null)
			controller.updatePersistenceErrorFlow(null)
			controller.updatePolicyState(null)
			controller.updatePolicyTier(any())
			controller.updateSkiState(null)
			controller.updateSailingState(null)
			controller.updatePlaneState(null)
			activityWatcherController.poke()
		}
	}
}
