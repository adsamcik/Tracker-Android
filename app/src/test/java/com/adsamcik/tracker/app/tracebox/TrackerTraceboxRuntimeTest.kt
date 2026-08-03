package com.adsamcik.tracker.app.tracebox

import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrackerTraceboxRuntimeTest {
    @Test
    fun `only exact package process is the Tracker client process`() {
        isTrackerMainProcessName("com.example.tracker", "com.example.tracker") shouldBe true
        isTrackerMainProcessName(
            "com.example.tracker:tracebox_handler",
            "com.example.tracker",
        ) shouldBe false
        isTrackerMainProcessName(null, "com.example.tracker") shouldBe false
    }

    @Test
    fun `only dedicated suffix is recognized as Tracebox handler`() {
        isTraceboxHandlerProcessName(
            "com.example.tracker:tracebox_handler",
            "com.example.tracker",
        ) shouldBe true
        isTraceboxHandlerProcessName(
            "com.example.tracker:worker",
            "com.example.tracker",
        ) shouldBe false
        isTraceboxHandlerProcessName(null, "com.example.tracker") shouldBe false
    }

}
