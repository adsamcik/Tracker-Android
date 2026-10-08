package com.adsamcik.tracker.tracker.ui.compose

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.adsamcik.tracker.stats.api.repository.*
import com.adsamcik.tracker.shared.model.AltitudeDatum
import com.adsamcik.tracker.shared.model.AltitudeSource
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.shared.preferences.settings.TrackerSettingsState
import com.adsamcik.tracker.shared.preferences.settings.TrackerSettingsQuick
import com.adsamcik.tracker.shared.preferences.extension.formatDistance
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.data.session.TrackerSessionSnapshot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrackerSavedLocationDisplayTest {
    @get:Rule val composeRule = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun `coordinates zero speed and negative original altitude remain usable without accuracy`() {
        val metrics = assertNotNull(buildLocationMetrics(context, saved().summary.latestFix,
            TrackerSettingsState.DEFAULT, false, {}))
        assertNull(metrics.primary)
        assertEquals(3, metrics.secondary.size)
        assertEquals(context.getString(R.string.coordinates_title), metrics.secondary[0].label)
        assertEquals(context.getString(R.string.speed_title), metrics.secondary[1].label)
        assertTrue(metrics.secondary[1].copyableValue!!.contains("0.0m/s"))
        assertEquals(context.getString(R.string.altitude_title), metrics.secondary[2].label)
        assertTrue(metrics.secondary[2].copyableValue!!.contains("-12.0m"))
        assertEquals(formatRelativeUpdate(context, 2_000L), metrics.status)
    }

    @Test fun `zero accuracy is displayed while an absent saved fix has no invented detail`() {
        assertNotNull(buildLocationMetrics(context, saved(accuracy = 0f).summary.latestFix,
            TrackerSettingsState.DEFAULT, false, {})?.primary)
        assertNull(buildLocationMetrics(context, null, TrackerSettingsState.DEFAULT, false, {}))
    }

    @Test fun `Tracker route shows shared saved distance and partial state rather than raw session counter`() {
        composeRule.setContent { AppTheme {
            StatusAndQuickStatsCard(true, TrackerSessionSnapshot(id = 7, start = 1_000, distanceInM = 9_000_000f),
                null, 61_000, {}, savedLocation = saved(partial = true))
        } }
        composeRule.onNodeWithText(context.getString(R.string.tracker_route_tab)).performClick()
        val distance = context.resources.formatDistance(123.0, 0, TrackerSettingsQuick.lengthSystem(context))
        composeRule.onNodeWithText("$distance · ${context.getString(R.string.tracker_measurement_partial)}")
            .assertIsDisplayed()
    }

    @Test fun `Location off suppresses Location tabs even when an old saved snapshot exists`() {
        composeRule.setContent { AppTheme {
            StatusAndQuickStatsCard(true, TrackerSessionSnapshot(id = 7, start = 1_000, distanceInM = 9_000_000f),
                null, 61_000, {}, savedLocation = saved(),
                trackingParams = TrackingParamsState(locationEnabled = false, activityEnabled = false,
                    wifiEnabled = false, cellEnabled = false))
        } }
        composeRule.onAllNodesWithText(context.getString(R.string.tracker_route_tab)).assertCountEquals(0)
        composeRule.onAllNodesWithText(context.getString(R.string.speed_title)).assertCountEquals(0)
        composeRule.onNodeWithText(context.getString(R.string.duration_title)).assertIsDisplayed()
    }
    private fun saved(id: Long = 7, distance: Double? = 123.0, partial: Boolean = false,
        accuracy: Float? = null, altitude: Float? = -12f) = SavedLocationSummaryRead.Snapshot(
        SavedLocationSummarySelection.Session(id), TrackingHistoryReadSnapshot(0, 1),
        SavedLocationSummary(2, SavedLocationFix(1, 2_000, 0, 0, 10_000_000, 0f, null, accuracy,
            altitude, AltitudeDatum.WGS84_ELLIPSOID, AltitudeSource.UNKNOWN_LEGACY, null), distance, partial))
}
