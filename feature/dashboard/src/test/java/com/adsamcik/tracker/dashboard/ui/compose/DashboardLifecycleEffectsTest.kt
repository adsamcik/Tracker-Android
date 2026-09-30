package com.adsamcik.tracker.dashboard.ui.compose

import android.content.Context
import android.content.Intent
import android.app.Activity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.adsamcik.tracker.dashboard.ui.DashboardViewModel
import com.adsamcik.tracker.tracker.api.ManualTrackingStartReadiness
import com.adsamcik.tracker.tracker.api.TrackerServiceApi
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DashboardLifecycleEffectsTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `resume invokes dashboard permission check`() {
        val owner = TestLifecycleOwner()
        val context = mockk<Context>()
        val viewModel = mockk<DashboardViewModel>(relaxed = true)

        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                DashboardPermissionResumeEffect(viewModel, context)
            }
        }

        composeRule.runOnIdle {
            owner.moveTo(Lifecycle.Event.ON_CREATE)
            owner.moveTo(Lifecycle.Event.ON_START)
            owner.moveTo(Lifecycle.Event.ON_RESUME)
        }

        verify(exactly = 1) {
            viewModel.checkPermission(context)
        }
    }

    @Test
    fun `resuming existing Dashboard hands off each original Start once and does not create a Start on reopen`() {
        val owner = TestLifecycleOwner()
        val context = mockk<Activity>()
        var launchIntent = Intent().putExtra("com.adsamcik.tracker.extra.MANUAL_START_GENERATION", 17L)
        every { context.intent } answers { launchIntent }
        val continued = mutableListOf<Long>()
        mockkObject(TrackerServiceApi)
        try {
            coEvery { TrackerServiceApi.readManualTrackingStartReadiness(context) } returns ManualTrackingStartReadiness.Ready(1L)
            composeRule.setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    DashboardManualStartResumeEffect(
                        context = context, pendingLocationServicesRepair = false, onReadiness = {},
                        onLocationServicesRepairCompleted = { error("No Location repair was requested") },
                        onLocationServicesRepairUnchanged = { error("No Location repair was requested") },
                        onStartContinuation = { continued += it },
                    )
                }
            }
            composeRule.runOnIdle {
                owner.moveTo(Lifecycle.Event.ON_CREATE)
                owner.moveTo(Lifecycle.Event.ON_START)
                owner.moveTo(Lifecycle.Event.ON_RESUME)
            }
            composeRule.runOnIdle { continued shouldBe listOf(17L) }
            composeRule.runOnIdle {
                owner.moveTo(Lifecycle.Event.ON_PAUSE)
                owner.moveTo(Lifecycle.Event.ON_RESUME)
            }
            composeRule.runOnIdle { continued shouldBe listOf(17L) }
            composeRule.runOnIdle {
                owner.moveTo(Lifecycle.Event.ON_PAUSE)
                launchIntent = Intent().putExtra("com.adsamcik.tracker.extra.MANUAL_START_GENERATION", 29L)
                owner.moveTo(Lifecycle.Event.ON_RESUME)
            }
            composeRule.runOnIdle { continued shouldBe listOf(17L, 29L) }
        } finally {
            unmockkObject(TrackerServiceApi)
        }
    }

    @Test
    fun `consistency check does not run before lifecycle is resumed`() = runTest {
        val owner = TestLifecycleOwner()
        owner.moveTo(Lifecycle.Event.ON_CREATE)
        var checkCount = 0

        val job = launch {
            runDashboardConsistencyChecksWhenResumed(
                lifecycle = owner.lifecycle,
                shouldContinue = { true },
                intervalMillis = 5_000,
            ) {
                checkCount += 1
                true
            }
        }
        runCurrent()

        advanceTimeBy(10_000)
        runCurrent()
        checkCount shouldBe 0

        owner.moveTo(Lifecycle.Event.ON_START)
        owner.moveTo(Lifecycle.Event.ON_RESUME)
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        checkCount shouldBe 1

        job.cancelAndJoin()
    }

    private class TestLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle = registry

        fun moveTo(event: Lifecycle.Event) {
            registry.handleLifecycleEvent(event)
        }
    }
}
