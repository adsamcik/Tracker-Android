package com.adsamcik.tracker.tracker.controller

import com.adsamcik.tracker.tracker.data.session.TrackerSessionInfo
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TrackerSessionStartFeedbackTest {
    private val controller = DefaultTrackerServiceController()

    @Test
    fun `accepted enqueue stays pending until its real completion`() {
        controller.updateSessionStart(10L, starting = true)
        controller.startingSessionGenerationFlow.value shouldBe 10L
        controller.updateServiceRunning(true)
        controller.startingSessionGenerationFlow.value shouldBe 10L
        controller.updateSessionInfo(TrackerSessionInfo(true))
        controller.updateSessionStart(10L, starting = false)
        controller.startingSessionGenerationFlow.value shouldBe null
        controller.updateSessionStart(10L, starting = true)
        controller.startingSessionGenerationFlow.value shouldBe null
    }

    @Test
    fun `old failure and teardown cannot clear a replacement Start`() {
        controller.updateSessionStart(10L, starting = true)
        controller.updateSessionStart(12L, starting = true)
        controller.updateSessionStart(10L, starting = false)
        controller.startingSessionGenerationFlow.value shouldBe 12L
        controller.updateSessionStart(12L, starting = false)
        controller.startingSessionGenerationFlow.value shouldBe null
    }

    @Test
    fun `End fences an older queued Start without clearing a later Start`() {
        controller.updateSessionStart(10L, starting = true)
        controller.updateSessionStart(11L, starting = false)
        controller.updateSessionStart(10L, starting = true)
        controller.startingSessionGenerationFlow.value shouldBe null
        controller.updateSessionStart(12L, starting = true)
        controller.updateSessionStart(11L, starting = false)
        controller.startingSessionGenerationFlow.value shouldBe 12L
    }

    @Test
    fun `background service hosting never means a session is starting`() {
        controller.updateServiceRunning(true)
        controller.startingSessionGenerationFlow.value shouldBe null
        controller.sessionInfoFlow.value shouldBe null
    }
}
