package com.adsamcik.tracker.tracker.component.consumer.post

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.model.AltitudeDatum
import com.adsamcik.tracker.shared.model.AltitudeSource
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsRepository
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.shared.preferences.extension.formatDistance
import com.adsamcik.tracker.shared.preferences.settings.TrackerSettingsQuick
import com.adsamcik.tracker.stats.api.repository.*
import com.adsamcik.tracker.testing.fake.FakeTrackerServiceController
import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.data.session.TrackerSessionInfo
import com.adsamcik.tracker.tracker.data.session.TrackerSessionSnapshot
import com.adsamcik.tracker.tracker.notification.TrackerNotificationManager
import com.adsamcik.tracker.tracker.notification.TrackerNotificationProvider
import com.adsamcik.tracker.tracker.notification.component.DistanceNotificationComponent
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.*

/** Actual component/manager with explicit state, settings, launcher and preference fixtures; no Hilt/device proof. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SavedLocationNotificationUpdatesTest {
    @Test fun `saved summary updates the actual notification without a raw tracking cycle`() = fixtureTest {
        enable()
        reader.setSavedLocationSummary(saved(distance = 123.0))
        awaitTitle(context.getString(R.string.distance_value, formatted(123.0)))
        reader.setSavedLocationSummary(saved(distance = 456.0))
        awaitTitle(context.getString(R.string.distance_value, formatted(456.0)))
        assertEquals(9_000_000f, reader.sessionFlow.value?.distanceInM)
    }

    @Test fun `Location off removes its configured value and preserves customization`() = fixtureTest {
        enable()
        reader.setSavedLocationSummary(saved())
        awaitTitle(context.getString(R.string.distance_value, formatted(123.0)))
        choices.value = choices.value.copy(locationEnabled = false)
        awaitTitle(context.getString(R.string.notification_tracking_active))
        assertEquals(DistanceNotificationComponent::class.java.simpleName, distance.id)
        assertTrue(distance.preference.isInTitle)
    }

    @Test fun `clear or storage failure retracts old saved numbers rather than keeping a raw fallback`() = fixtureTest {
        enable()
        reader.setSavedLocationSummary(saved())
        awaitTitle(context.getString(R.string.distance_value, formatted(123.0)))
        reader.setSavedLocationSummary(SavedLocationSummaryRead.Unavailable(HistoryProblemReason.STORAGE_UNAVAILABLE))
        awaitTitle(context.getString(R.string.distance_value, context.getString(R.string.tracker_steps_value_unavailable)))
        reader.setSavedLocationSummary(SavedLocationSummaryRead.NotFound)
        awaitTitle(context.getString(R.string.distance_value, context.getString(R.string.tracker_steps_today_waiting)))
    }

    @Test fun `replacement segment cannot reuse the previous shared saved value`() = fixtureTest {
        enable()
        reader.setSavedLocationSummary(saved())
        awaitTitle(context.getString(R.string.distance_value, formatted(123.0)))
        reader.updateSession(TrackerSessionSnapshot(id = 8, start = 1_000, distanceInM = 8_000_000f))
        awaitTitle(context.getString(R.string.distance_value, context.getString(R.string.tracker_steps_today_waiting)))
        reader.setSavedLocationSummary(saved(id = 7, distance = 999.0))
        assertEquals(SavedLocationSummaryRead.NotFound, reader.savedLocationSummaryFlow.value)
        reader.setSavedLocationSummary(saved(id = 8, distance = 456.0))
        awaitTitle(context.getString(R.string.distance_value, formatted(456.0)))
    }

    @Test fun `End and service teardown cancel updates so late saved data cannot recreate notification`() = fixtureTest {
        enable()
        reader.setSavedLocationSummary(saved())
        awaitTitle(context.getString(R.string.distance_value, formatted(123.0)))
        reader.updateSessionInfo(null)
        component.onServiceStopped(context)
        updates!!.join()
        assertTrue(updates!!.isCancelled)
        reader.setSavedLocationSummary(saved(distance = 999.0))
        component.onForegroundServiceTypeChanged()
        assertNull(notification())
    }

    @Test fun `teardown while preferences load cannot install a late notification manager`() = fixtureTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { TrackerNotificationProvider.updatePreferences(any()) } coAnswers {
            entered.complete(Unit); release.await()
        }
        val pending = scope.launch { component.onEnable(context) }
        try {
            entered.await()
            component.onServiceStopped(context)
            release.complete(Unit)
            pending.join()
            updates = component.startSavedLocationUpdates(context, reader, params, scope)
            reader.setSavedLocationSummary(saved())
            yield()
            assertNull(notification())
        } finally { release.complete(Unit); pending.cancelAndJoin() }
    }

    private fun fixtureTest(block: suspend Fixture.() -> Unit) = runBlocking {
        mockkObject(TrackerNotificationProvider)
        val fixture = Fixture()
        try { withTimeout(5_000L) { fixture.block() } }
        finally { fixture.close(); unmockkObject(TrackerNotificationProvider) }
    }

    private class Fixture {
        val context: Context = spyk(ApplicationProvider.getApplicationContext<Context>())
        val reader = FakeTrackerServiceController().also {
            it.updateServiceRunning(true)
            it.updateSessionInfo(TrackerSessionInfo(true))
            it.updateSession(TrackerSessionSnapshot(id = 7, start = 1_000, distanceInM = 9_000_000f))
        }
        val choices = MutableStateFlow(TrackingParamsState(locationEnabled = true))
        val params = mockk<TrackingParamsRepository>().also { every { it.data } returns choices }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val distance = DistanceNotificationComponent().also { it.preference = it.defaultPreference }
        private val entryPoint = mockk<NotificationComponentEntryPoint>().also {
            every { it.trackerStateReader() } returns reader
            every { it.trackingParamsRepository() } returns params
        }
        val component = NotificationComponent { entryPoint }
        var updates: Job? = null

        init {
            val manager = mockk<PackageManager>(relaxed = true)
            every { context.packageManager } returns manager
            every { manager.getLaunchIntentForPackage(any()) } returns Intent().setComponent(
                ComponentName(context.packageName, "FixtureLauncher"))
            every { manager.getActivityInfo(any<ComponentName>(), any<Int>()) } returns ActivityInfo().apply {
                packageName = context.packageName; name = "FixtureLauncher"
            }
            coEvery { TrackerNotificationProvider.updatePreferences(any()) } returns Unit
            every { TrackerNotificationProvider.configuredComponents() } returns listOf(distance)
        }
        suspend fun enable() {
            component.onEnable(context)
            updates = component.startSavedLocationUpdates(context, reader, params, scope)
        }
        fun notification(): Notification? = shadowOf(context.getSystemService(Context.NOTIFICATION_SERVICE)
            as NotificationManager).getNotification(TrackerNotificationManager.NOTIFICATION_ID)
        suspend fun awaitTitle(expected: String) {
            while (notification()?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() != expected) yield()
        }
        fun formatted(value: Double) = context.resources.formatDistance(value, 0,
            TrackerSettingsQuick.lengthSystem(context))
        suspend fun close() {
            component.onServiceStopped(context)
            scope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }
    private fun saved(id: Long = 7, distance: Double = 123.0) = SavedLocationSummaryRead.Snapshot(
        SavedLocationSummarySelection.Session(id), TrackingHistoryReadSnapshot(0, 1),
        SavedLocationSummary(2, SavedLocationFix(1, 2_000, 0, 0, 10_000_000, 0f, null, null,
            -12f, AltitudeDatum.WGS84_ELLIPSOID, AltitudeSource.UNKNOWN_LEGACY, null), distance, false))
}
