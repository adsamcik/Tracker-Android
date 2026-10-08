package com.adsamcik.tracker.tracker.notification

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.stats.api.repository.*
import com.adsamcik.tracker.shared.model.AltitudeDatum
import com.adsamcik.tracker.shared.model.AltitudeSource
import com.adsamcik.tracker.shared.base.data.MutableCollectionData
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.shared.preferences.extension.formatDistance
import com.adsamcik.tracker.shared.preferences.settings.TrackerSettingsQuick
import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.notification.component.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SavedLocationNotificationTextTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val session = TrackerSession(id = 7, distanceInM = 9_000_000f,
        distanceOnFootInM = 8_000_000f, distanceInVehicleInM = 7_000_000f)
    private val data = MutableCollectionData(9_000L)

    @Test fun `notification retains covered zero and marks saved partial distance`() {
        val component = DistanceNotificationComponent()
        val zero = context.resources.formatDistance(0.0, 0, TrackerSettingsQuick.lengthSystem(context))
        assertEquals(context.getString(R.string.distance_value, zero),
            component.generateText(context, session, data, saved(distance = 0.0)))
        val value = context.resources.formatDistance(123.0, 0, TrackerSettingsQuick.lengthSystem(context))
        assertEquals(context.getString(R.string.distance_value,
            "$value · ${context.getString(R.string.tracker_measurement_partial)}"),
            component.generateText(context, session, data, saved(partial = true)))
    }

    @Test fun `missing or wrong segment never uses a raw distance or coordinates`() {
        val read = saved(id = 8)
        assertEquals(context.getString(R.string.distance_value,
            context.getString(R.string.tracker_component_waiting_data)),
            DistanceNotificationComponent().generateText(context, session, data, read))
        assertNull(LatitudeNotificationComponent().generateText(context, session, data, read))
        assertNull(SpeedNotificationComponent().generateText(context, session, data, SavedLocationSummaryRead.NotFound))
    }

    @Test fun `accuracy is optional and generic altitude accepts its saved original datum`() {
        val read = saved()
        assertNotNull(LatitudeNotificationComponent().generateText(context, session, data, read))
        assertNotNull(LongitudeNotificationComponent().generateText(context, session, data, read))
        assertNotNull(SpeedNotificationComponent().generateText(context, session, data, read))
        assertNull(HorizontalAccuracyNotificationComponent().generateText(context, session, data, read))
        assertNotNull(HorizontalAccuracyNotificationComponent().generateText(context, session, data, saved(accuracy = 0f)))
        assertEquals(context.getString(R.string.altitude_value,
            context.resources.formatDistance(-12f, 2, TrackerSettingsQuick.lengthSystem(context))),
            AltitudeNotificationComponent().generateText(context, session, data, read))
    }

    @Test fun `chosen-source gate leaves notification customization intact`() {
        val choices = TrackingParamsState(locationEnabled = false, activityEnabled = false,
            wifiEnabled = true, cellEnabled = false)
        listOf(DistanceNotificationComponent(), LatitudeNotificationComponent(), SpeedNotificationComponent(),
            AltitudeNotificationComponent(), HorizontalAccuracyNotificationComponent()).forEach {
            assertFalse(it.isChosen(choices))
            assertTrue(it.isChosen(choices.copy(locationEnabled = true)))
        }
        assertTrue(WiFiCountNotificationComponent().isChosen(choices))
        assertFalse(CellCountNotificationComponent().isChosen(choices))
        assertFalse(ActivityNotificationComponent().isChosen(choices))
        assertTrue(DurationNotificationComponent().isChosen(choices))
    }

    @Test fun `native mode-specific distance never presents raw counters as measured zero or complete values`() {
        val unavailable = context.getString(R.string.tracker_steps_value_unavailable)
        assertEquals(context.getString(R.string.distance_onfoot_value, unavailable),
            DistanceOnFootNotificationComponent().generateText(context, session, data, saved()))
        assertEquals(context.getString(R.string.distance_invehicle_value, unavailable),
            DistanceInVehicleNotificationComponent().generateText(context, session, data, saved()))
    }
    private fun saved(id: Long = 7, distance: Double? = 123.0, partial: Boolean = false,
        accuracy: Float? = null, altitude: Float? = -12f) = SavedLocationSummaryRead.Snapshot(
        SavedLocationSummarySelection.Session(id), TrackingHistoryReadSnapshot(0, 1),
        SavedLocationSummary(2, SavedLocationFix(1, 2_000, 0, 0, 10_000_000, 0f, null, accuracy,
            altitude, AltitudeDatum.WGS84_ELLIPSOID, AltitudeSource.UNKNOWN_LEGACY, null), distance, partial))
}
