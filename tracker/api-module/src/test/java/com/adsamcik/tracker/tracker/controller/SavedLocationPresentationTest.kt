package com.adsamcik.tracker.tracker.controller

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.stats.api.repository.*
import com.adsamcik.tracker.shared.model.AltitudeDatum
import com.adsamcik.tracker.shared.model.AltitudeSource
import com.adsamcik.tracker.tracker.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SavedLocationPresentationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun `only chosen exact live segment can expose a saved snapshot`() {
        val read = saved()
        assertSame(read, read.forLiveSegment(7, true))
        listOf(null, 0L, 8L).forEach { id ->
            assertEquals(SavedLocationSummaryRead.NotFound, read.forLiveSegment(id, true))
        }
        assertEquals(SavedLocationSummaryRead.NotFound, read.forLiveSegment(7, false))
    }

    @Test fun `covered zero and available partial measured distance keep their value`() {
        assertEquals("0.0 m", saved(distance = 0.0).formatSavedDistance(context) { "$it m" })
        assertEquals("123.0 m · ${context.getString(R.string.tracker_measurement_partial)}",
            saved(partial = true).formatSavedDistance(context) { "$it m" })
    }

    @Test fun `absent pair missing snapshot and failed read never fabricate zero`() {
        assertEquals(context.getString(R.string.tracker_steps_today_waiting),
            saved(distance = null).formatSavedDistance(context) { error("No measured pair") })
        assertEquals(context.getString(R.string.tracker_steps_today_waiting),
            SavedLocationSummaryRead.NotFound.formatSavedDistance(context) { error("Missing") })
        assertEquals(context.getString(R.string.tracker_steps_value_unavailable),
            SavedLocationSummaryRead.Unavailable(HistoryProblemReason.STORAGE_UNAVAILABLE)
                .formatSavedDistance(context) { error("Unavailable") })
        assertEquals(context.getString(R.string.tracker_measurement_partial),
            saved(distance = null, partial = true).formatSavedDistance(context) { error("Partial without pair") })
    }

    @Test fun `saved fix keeps optional accuracy original altitude datum and measured timestamp`() {
        val fix = saved().summary.latestFix!!
        assertNull(fix.horizontalAccuracyMeters)
        assertEquals(0f, fix.speedMetersPerSecond)
        assertEquals(-12f, fix.altitudeMeters)
        assertEquals(AltitudeDatum.WGS84_ELLIPSOID, fix.altitudeDatum)
        assertEquals(2_000L, fix.measuredTimeMs)
    }

    @Test fun `saved moving average preserves zero partial and waiting states`() {
        val covered = saved().copy(summary = saved().summary.copy(movingAverageSpeedMetersPerSecond = 0.0))
        assertEquals("0.0 m/s", covered.formatSavedAverageSpeed(context) { "$it m/s" })
        val partial = covered.copy(summary = covered.summary.copy(partial = true, movingAverageSpeedMetersPerSecond = 2.5))
        assertEquals("2.5 m/s · ${context.getString(R.string.tracker_measurement_partial)}",
            partial.formatSavedAverageSpeed(context) { "$it m/s" })
        assertEquals(context.getString(R.string.tracker_steps_today_waiting),
            saved().formatSavedAverageSpeed(context) { error("No average") })
    }

    private fun saved(id: Long = 7, distance: Double? = 123.0, partial: Boolean = false,
        accuracy: Float? = null, altitude: Float? = -12f) = SavedLocationSummaryRead.Snapshot(
        SavedLocationSummarySelection.Session(id), TrackingHistoryReadSnapshot(0, 1),
        SavedLocationSummary(2, SavedLocationFix(1, 2_000, 0, 0, 10_000_000, 0f, null, accuracy,
            altitude, AltitudeDatum.WGS84_ELLIPSOID, AltitudeSource.UNKNOWN_LEGACY, null), distance, partial))
}
