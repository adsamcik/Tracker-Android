package com.adsamcik.tracker.tracker.feed

import app.cash.turbine.test
import com.adsamcik.tracker.tracker.controller.DefaultTrackerServiceController
import com.adsamcik.tracker.tracker.data.session.TrackerSessionInfo
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class DefaultTrackerLiveLocationFeedTest {
    @Test
    fun `background hosting alone never selects the empty capture feed`() = runTest {
        val controller = DefaultTrackerServiceController()
        val feed = DefaultTrackerLiveLocationFeed(controller, backgroundScope)
        feed.isActiveFlow.test {
            awaitItem() shouldBe false
            controller.updateServiceRunning(true)
            runCurrent()
            feed.isActive shouldBe false
            expectNoEvents()
            controller.updateSessionInfo(TrackerSessionInfo(true))
            runCurrent()
            awaitItem() shouldBe true
            controller.updateSessionInfo(null)
            runCurrent()
            awaitItem() shouldBe false
            controller.isServiceRunning shouldBe true
        }
    }

    @Test
    fun `physical host loss fences capture feed while retirement metadata remains`() = runTest {
        val controller = DefaultTrackerServiceController()
        controller.updateServiceRunning(true)
        controller.updateSessionInfo(TrackerSessionInfo(true))
        val feed = DefaultTrackerLiveLocationFeed(controller, backgroundScope)
        feed.isActiveFlow.test {
            awaitItem() shouldBe true
            controller.updateServiceRunning(false)
            runCurrent()
            awaitItem() shouldBe false
            feed.isActive shouldBe false
            (controller.sessionInfoFlow.value != null) shouldBe true
        }
    }
}
