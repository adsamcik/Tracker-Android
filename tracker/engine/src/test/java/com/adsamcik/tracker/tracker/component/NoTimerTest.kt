package com.adsamcik.tracker.tracker.component

import android.content.Context
import io.mockk.mockk
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Regression coverage: NoTimer is the placeholder timer used before TrackerService
 * has had a chance to install a real CollectionTriggerComponent. onEnable must
 * throw to surface the programming error of starting collection on a placeholder,
 * but onDisable MUST be a no-op so OS-initiated teardown (e.g. service onDestroy
 * after permission revoke) does not crash inside TrackingOrchestrator.shutdown.
 */
class NoTimerTest {

    @Test
    fun `onEnable still throws to surface uninitialised placeholder use`() {
        val timer = NoTimer()
        val context = mockk<Context>(relaxed = true)
        val receiver = mockk<TrackerTimerReceiver>(relaxed = true)
        assertThrows(TrackerTimerNotInitializedException::class.java) {
            timer.onEnable(context, receiver)
        }
    }

    @Test
    fun `onDisable is a no-op when no real timer was installed`() {
        val timer = NoTimer()
        val context = mockk<Context>(relaxed = true)
        // No assertion needed beyond "does not throw"; the prior behaviour
        // crashed TrackerService.onDestroy after a permission-revoke kill.
        timer.onDisable(context)
    }
}
