package com.adsamcik.tracker.tracker.controller

import com.adsamcik.tracker.shared.model.AltitudeDatum
import com.adsamcik.tracker.shared.model.AltitudeSource
import com.adsamcik.tracker.stats.api.repository.*
import com.adsamcik.tracker.tracker.data.session.TrackerSessionInfo
import com.adsamcik.tracker.tracker.data.session.TrackerSessionSnapshot
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.*
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** Real controller binding with explicit saved-reader fixtures; no Android/provider acceptance claim. */
class TrackerSavedLocationPresentationTest {
    @Test fun `every live surface shares one saved observation without using raw session counters`() = runTest {
        val history = mockk<TrackingHistoryRepository>()
        val upstream = MutableStateFlow<SavedLocationSummaryRead>(SavedLocationSummaryRead.NotFound)
        var subscriptions = 0
        every { history.observeLiveSavedLocationSummary(1) } returns flow {
            subscriptions++
            emitAll(upstream)
        }
        val controller = activeController(1)
        val job = controller.bindSavedLocationSummaries(history, backgroundScope)
        try {
            runCurrent()
            upstream.value = saved(1)
            runCurrent()
            val tracker = controller.savedLocationSummaryFlow
            val notification = controller.savedLocationSummaryFlow
            val widget = controller.savedLocationSummaryFlow
            assertSame(tracker, notification)
            assertSame(tracker, widget)
            assertEquals(123.0, assertIs<SavedLocationSummaryRead.Snapshot>(widget.value).summary.distanceMeters)
            assertEquals(9_000_000f, controller.sessionFlow.value?.distanceInM)
            assertEquals(1, subscriptions)
            verify(exactly = 1) { history.observeLiveSavedLocationSummary(1) }
        } finally { job.cancelAndJoin() }
    }

    @Test fun `background hosting alone does not start a live Location history observation`() = runTest {
        val history = mockk<TrackingHistoryRepository>()
        val controller = DefaultTrackerServiceController()
        controller.updateServiceRunning(true)
        controller.updateSession(TrackerSessionSnapshot(id = 1))
        val job = controller.bindSavedLocationSummaries(history, backgroundScope)
        try {
            runCurrent()
            assertEquals(SavedLocationSummaryRead.NotFound, controller.savedLocationSummaryFlow.value)
            verify(exactly = 0) { history.observeLiveSavedLocationSummary(any()) }
        } finally { job.cancelAndJoin() }
    }

    @Test fun `End clears immediately while its independent background host survives`() = runTest {
        val history = mockk<TrackingHistoryRepository>()
        val upstream = MutableStateFlow<SavedLocationSummaryRead>(saved(1))
        every { history.observeLiveSavedLocationSummary(1) } returns upstream
        val controller = activeController(1)
        val job = controller.bindSavedLocationSummaries(history, backgroundScope)
        try {
            runCurrent()
            assertIs<SavedLocationSummaryRead.Snapshot>(controller.savedLocationSummaryFlow.value)
            controller.updateSessionInfo(null)
            assertEquals(SavedLocationSummaryRead.NotFound, controller.savedLocationSummaryFlow.value)
            assertTrue(controller.isServiceRunning)
            upstream.value = saved(1, distance = 999.0)
            runCurrent()
            assertEquals(SavedLocationSummaryRead.NotFound, controller.savedLocationSummaryFlow.value)
        } finally { job.cancelAndJoin() }
    }

    @Test fun `physical host loss clears immediately and late saved results stay absent`() = runTest {
        val history = mockk<TrackingHistoryRepository>()
        val upstream = MutableStateFlow<SavedLocationSummaryRead>(saved(1))
        every { history.observeLiveSavedLocationSummary(1) } returns upstream
        val controller = activeController(1)
        val job = controller.bindSavedLocationSummaries(history, backgroundScope)
        try {
            runCurrent()
            controller.updateServiceRunning(false)
            assertEquals(SavedLocationSummaryRead.NotFound, controller.savedLocationSummaryFlow.value)
            upstream.value = saved(1, distance = 999.0)
            runCurrent()
            assertEquals(SavedLocationSummaryRead.NotFound, controller.savedLocationSummaryFlow.value)
        } finally { job.cancelAndJoin() }
    }

    @Test fun `replacement segment cannot display an old segment or mismatched result`() = runTest {
        val history = mockk<TrackingHistoryRepository>()
        val first = MutableStateFlow<SavedLocationSummaryRead>(saved(1))
        val second = MutableStateFlow<SavedLocationSummaryRead>(saved(1))
        every { history.observeLiveSavedLocationSummary(1) } returns first
        every { history.observeLiveSavedLocationSummary(2) } returns second
        val controller = activeController(1)
        val job = controller.bindSavedLocationSummaries(history, backgroundScope)
        try {
            runCurrent()
            controller.updateSession(TrackerSessionSnapshot(id = 2))
            assertEquals(SavedLocationSummaryRead.NotFound, controller.savedLocationSummaryFlow.value)
            first.value = saved(1, distance = 999.0)
            runCurrent()
            assertEquals(SavedLocationSummaryRead.NotFound, controller.savedLocationSummaryFlow.value)
            second.value = saved(2)
            runCurrent()
            assertEquals(SavedLocationSummarySelection.Session(2),
                assertIs<SavedLocationSummaryRead.Snapshot>(controller.savedLocationSummaryFlow.value).selection)
        } finally { job.cancelAndJoin() }
    }

    @Test fun `saved-reader failure is typed and End cannot retain the error or prior data`() = runTest {
        val history = mockk<TrackingHistoryRepository>()
        every { history.observeLiveSavedLocationSummary(1) } returns flow { throw IllegalStateException("Fixture storage failure") }
        val controller = activeController(1)
        val job = controller.bindSavedLocationSummaries(history, backgroundScope)
        try {
            runCurrent()
            assertEquals(HistoryProblemReason.STORAGE_UNAVAILABLE,
                assertIs<SavedLocationSummaryRead.Unavailable>(controller.savedLocationSummaryFlow.value).reason)
            controller.updateSession(null)
            assertEquals(SavedLocationSummaryRead.NotFound, controller.savedLocationSummaryFlow.value)
        } finally { job.cancelAndJoin() }
    }

    private fun activeController(id: Long) = DefaultTrackerServiceController().also {
        it.updateServiceRunning(true)
        it.updateSessionInfo(TrackerSessionInfo(true))
        it.updateSession(TrackerSessionSnapshot(id = id, distanceInM = 9_000_000f))
    }
    private fun saved(id: Long, distance: Double = 123.0) = SavedLocationSummaryRead.Snapshot(
        SavedLocationSummarySelection.Session(id), TrackingHistoryReadSnapshot(0, 1),
        SavedLocationSummary(2, SavedLocationFix(id, 3_000, 2, 0, 1_000, 0f, null, null, -12f,
            AltitudeDatum.RELATIVE_BAROMETRIC, AltitudeSource.BAROMETER_PREDICTION, null), distance, false))
}
