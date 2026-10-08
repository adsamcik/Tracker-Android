package com.adsamcik.tracker.app.widget.glance

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.stats.api.repository.*
import com.adsamcik.tracker.shared.model.AltitudeDatum
import com.adsamcik.tracker.shared.model.AltitudeSource
import com.adsamcik.tracker.R
import com.adsamcik.tracker.tracker.data.session.TrackerSessionSnapshot
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActiveSessionSavedLocationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val session = TrackerSessionSnapshot(id = 7, start = 1_000, distanceInM = 9_000_000f)

    @Test fun `widget distance uses the shared saved value not the raw session counter`() {
        val rows = rows(saved())
        assertEquals(WidgetFormatters.formatDistance(context, 123f), rows.first().value)
    }

    @Test fun `widget retains zero and marks measured partial sections`() {
        assertEquals(WidgetFormatters.formatDistance(context, 0f), rows(saved(distance = 0.0)).first().value)
        assertEquals("${WidgetFormatters.formatDistance(context, 123f)} · ${context.getString(com.adsamcik.tracker.tracker.R.string.tracker_measurement_partial)}",
            rows(saved(partial = true)).first().value)
        assertTrue(WidgetFormatters.formatSpeed(context, 0f) != context.getString(R.string.widget_speed_unknown))
    }

    @Test fun `wrong segment and unavailable saved data never fall back to a raw positive value`() {
        assertEquals(context.getString(com.adsamcik.tracker.tracker.R.string.tracker_component_waiting_data),
            rows(saved(id = 8)).first().value)
        assertEquals(context.getString(com.adsamcik.tracker.tracker.R.string.tracker_steps_value_unavailable),
            rows(SavedLocationSummaryRead.Unavailable(HistoryProblemReason.STORAGE_UNAVAILABLE)).first().value)
    }

    @Test fun `unchosen Location and Steps leave only session organization metadata`() {
        val rows = buildActiveSessionStats(context, session, WidgetStepsPresentation.Ready(999), 61_000,
            savedLocation = saved(), locationEnabled = false, stepsEnabled = false)
        assertEquals(listOf(R.string.widget_session_duration, R.string.widget_collections), rows.map { it.labelRes })
    }

    private fun rows(read: SavedLocationSummaryRead) = buildActiveSessionStats(context, session,
        WidgetStepsPresentation.NotCaptured, 61_000, savedLocation = read)
    private fun saved(id: Long = 7, distance: Double? = 123.0, partial: Boolean = false,
        accuracy: Float? = null, altitude: Float? = -12f) = SavedLocationSummaryRead.Snapshot(
        SavedLocationSummarySelection.Session(id), TrackingHistoryReadSnapshot(0, 1),
        SavedLocationSummary(2, SavedLocationFix(1, 2_000, 0, 0, 10_000_000, 0f, null, accuracy,
            altitude, AltitudeDatum.WGS84_ELLIPSOID, AltitudeSource.UNKNOWN_LEGACY, null), distance, partial))
}
