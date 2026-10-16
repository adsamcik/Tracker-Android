package com.adsamcik.tracker.tracker.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.adsamcik.tracker.tracker.api.TrackerServiceApi
import com.adsamcik.tracker.tracker.api.TrackingStopQuiescenceResult
import com.adsamcik.tracker.tracker.api.TrackingTurnOffResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.spyk
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test

class TrackerNotificationReceiverControlTest {
    private val context = mockk<Context>(relaxed = true).also {
        every { it.applicationContext } returns it
    }
    private val pendingResult = mockk<BroadcastReceiver.PendingResult>(relaxed = true)

    @Test
    fun `timed out End finishes broadcast without issuing another Stop`() = runTest {
        mockkObject(TrackerServiceApi)
        try {
            val receiver = createReceiver()
            receiver.applicationScope = this
            every { receiver.goAsync() } returns pendingResult
            val intent = actionIntent(TrackerNotificationReceiver.STOP_TRACKING_ACTION)
            coEvery { TrackerServiceApi.stopServiceAndAwaitQuiescence(context, any()) } coAnswers {
                withContext(NonCancellable) { delay(20_000L) }
                TrackingStopQuiescenceResult.HANDLED
            }

            receiver.onReceive(context, intent)
            runCurrent()
            advanceTimeBy(8_000L)
            runCurrent()

            verify(exactly = 1) { pendingResult.finish() }
            coVerify(exactly = 1) { TrackerServiceApi.stopServiceAndAwaitQuiescence(context, any()) }
            verify(exactly = 0) { TrackerServiceApi.stopService(context, any()) }

            advanceTimeBy(12_000L)
            runCurrent()
            verify(exactly = 1) { pendingResult.finish() }
        } finally {
            unmockkObject(TrackerServiceApi)
        }
    }

    @Test
    fun `unavailable Turn off does not issue an unscoped second command`() = runTest {
        mockkObject(TrackerServiceApi)
        try {
            val receiver = createReceiver()
            receiver.applicationScope = this
            every { receiver.goAsync() } returns pendingResult
            coEvery { TrackerServiceApi.turnOffTrackingAndAwait(context, any()) } returns
                TrackingTurnOffResult.UNAVAILABLE

            receiver.onReceive(context, actionIntent(TrackerNotificationReceiver.TURN_OFF_TRACKING_ACTION))
            runCurrent()

            verify(exactly = 1) { pendingResult.finish() }
            coVerify(exactly = 1) { TrackerServiceApi.turnOffTrackingAndAwait(context, any()) }
            verify(exactly = 0) { TrackerServiceApi.turnOffTracking(context) }
        } finally {
            unmockkObject(TrackerServiceApi)
        }
    }

    private fun createReceiver(): TrackerNotificationReceiver {
        val receiver = spyk(TrackerNotificationReceiver())
        // The generated Hilt base appears only on transformed Android classpaths. These tests
        // supply their dependencies directly and exercise the receiver's real control actions.
        generateSequence<Class<*>>(receiver.javaClass) { it.superclass }
            .firstOrNull { it.simpleName == "Hilt_TrackerNotificationReceiver" }
            ?.getDeclaredField("injected")
            ?.apply { isAccessible = true }
            ?.setBoolean(receiver, true)
        return receiver
    }

    private fun actionIntent(action: Int): Intent = mockk {
        every { getIntExtra(TrackerNotificationReceiver.ACTION_STRING, -1) } returns action
    }
}
