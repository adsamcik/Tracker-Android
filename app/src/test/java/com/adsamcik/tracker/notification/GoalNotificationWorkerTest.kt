package com.adsamcik.tracker.notification

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Unit tests for [GoalNotificationWorker] threshold constants and logic.
 * Worker execution is tested via Robolectric integration tests;
 * these are fast pure-logic tests for the notification rules.
 */
class GoalNotificationWorkerTest {

    @Test
    fun `threshold 75 is less than threshold 90`() {
        (GoalNotificationWorker.THRESHOLD_75 < GoalNotificationWorker.THRESHOLD_90) shouldBe true
    }

    @Test
    fun `threshold 75 equals 75`() {
        GoalNotificationWorker.THRESHOLD_75 shouldBe 75
    }

    @Test
    fun `threshold 90 equals 90`() {
        GoalNotificationWorker.THRESHOLD_90 shouldBe 90
    }

    @Test
    fun `preference keys are distinct`() {
        val keys = setOf(
            GoalNotificationWorker.KEY_ENABLED.name,
            GoalNotificationWorker.KEY_LAST_NOTIFIED_DAY.name,
            GoalNotificationWorker.KEY_LAST_NOTIFIED_THRESHOLD.name,
        )
        keys.size shouldBe 3
    }
}
