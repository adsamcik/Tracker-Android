package com.adsamcik.tracker.app.widget.glance

import android.content.Context
import android.content.Intent
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import com.adsamcik.tracker.tracker.api.ManualTrackingStartRepairNavigation
import com.adsamcik.tracker.tracker.api.TrackerServiceApi
import com.adsamcik.tracker.tracker.api.TrackingStopQuiescenceResult
import com.adsamcik.tracker.tracker.api.TrackingTurnOffResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Test

class WidgetTrackingControlActionsTest {
    private val context = mockk<Context>(relaxed = true).also {
        every { it.applicationContext } returns it
    }
    private val glanceId = mockk<GlanceId>()
    private val parameters = mockk<ActionParameters>()

    @Test
    fun `session button ends only the session`() = runTest {
        mockkObject(TrackerServiceApi, WidgetUpdateScheduler)
        try {
            every { TrackerServiceApi.hasLiveCapture(context) } returns true
            coEvery { TrackerServiceApi.stopServiceAndAwaitQuiescence(context, any()) } returns
                TrackingStopQuiescenceResult.HANDLED
            coEvery { WidgetUpdateScheduler.updateAllWidgets(context) } returns Unit

            ToggleTrackingAction().onAction(context, glanceId, parameters)

            coVerify(exactly = 1) { TrackerServiceApi.stopServiceAndAwaitQuiescence(context, any()) }
            coVerify(exactly = 0) { TrackerServiceApi.turnOffTrackingAndAwait(context, any()) }
        } finally {
            unmockkObject(TrackerServiceApi, WidgetUpdateScheduler)
        }
    }

    @Test
    fun `turn off button closes all purposes without starting a session`() = runTest {
        mockkObject(TrackerServiceApi, WidgetUpdateScheduler)
        try {
            coEvery { TrackerServiceApi.turnOffTrackingAndAwait(context, any()) } returns
                TrackingTurnOffResult.COMPLETE
            coEvery { WidgetUpdateScheduler.updateAllWidgets(context) } returns Unit

            TurnOffTrackingAction().onAction(context, glanceId, parameters)

            coVerify(exactly = 1) { TrackerServiceApi.turnOffTrackingAndAwait(context, any()) }
            coVerify(exactly = 0) { TrackerServiceApi.requestManualTrackingStart(context) }
        } finally {
            unmockkObject(TrackerServiceApi, WidgetUpdateScheduler)
        }
    }

    @Test
    fun `failed End and Turn off repairs cannot request a new Start`() = runTest {
        mockkObject(TrackerServiceApi, WidgetUpdateScheduler, ManualTrackingStartRepairNavigation)
        try {
            every { TrackerServiceApi.hasLiveCapture(context) } returns true
            coEvery { TrackerServiceApi.stopServiceAndAwaitQuiescence(context, any()) } returns
                TrackingStopQuiescenceResult.RESERVATION_FAILED
            coEvery { TrackerServiceApi.turnOffTrackingAndAwait(context, any()) } returns
                TrackingTurnOffResult.UNAVAILABLE
            every {
                ManualTrackingStartRepairNavigation.createDashboardIntent(context, false)
            } returns mockk<Intent>()
            coEvery { WidgetUpdateScheduler.updateAllWidgets(context) } returns Unit

            ToggleTrackingAction().onAction(context, glanceId, parameters)
            TurnOffTrackingAction().onAction(context, glanceId, parameters)

            verify(exactly = 2) {
                ManualTrackingStartRepairNavigation.createDashboardIntent(context, false)
            }
            coVerify(exactly = 0) { TrackerServiceApi.requestManualTrackingStart(context) }
        } finally {
            unmockkObject(TrackerServiceApi, WidgetUpdateScheduler, ManualTrackingStartRepairNavigation)
        }
    }

    @Test
    fun `stalled End returns from widget action and opens repair without replaying Start`() = runTest {
        mockkObject(TrackerServiceApi, WidgetUpdateScheduler, ManualTrackingStartRepairNavigation)
        try {
            every { TrackerServiceApi.hasLiveCapture(context) } returns true
            coEvery { TrackerServiceApi.stopServiceAndAwaitQuiescence(context, any()) } coAnswers {
                delay(20_000L)
                TrackingStopQuiescenceResult.HANDLED
            }
            every {
                ManualTrackingStartRepairNavigation.createDashboardIntent(context, false)
            } returns mockk<Intent>()
            coEvery { WidgetUpdateScheduler.updateAllWidgets(context) } returns Unit

            ToggleTrackingAction().onAction(context, glanceId, parameters)

            verify(exactly = 1) {
                ManualTrackingStartRepairNavigation.createDashboardIntent(context, false)
            }
            coVerify(exactly = 0) { TrackerServiceApi.requestManualTrackingStart(context) }
        } finally {
            unmockkObject(TrackerServiceApi, WidgetUpdateScheduler, ManualTrackingStartRepairNavigation)
        }
    }
}
