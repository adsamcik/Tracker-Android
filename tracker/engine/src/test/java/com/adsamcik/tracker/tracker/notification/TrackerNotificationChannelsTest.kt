package com.adsamcik.tracker.tracker.notification

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.R as BaseR
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TrackerNotificationChannelsTest {
    private lateinit var context: Context
    private lateinit var notificationManager: NotificationManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        notificationManager = context.getSystemService(NotificationManager::class.java)
    }

    @Test
    fun `ensure tracking channel creates low importance tracking channel`() {
        TrackerNotificationChannels.ensureTrackingChannel(context)

        val channel = notificationManager.getNotificationChannel(
            context.getString(BaseR.string.channel_track_id),
        )
        assertNotNull(channel)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertEquals(context.getString(BaseR.string.channel_track_name), channel.name)
    }

    @Test
    fun `ensure activity watcher channel creates non vibrating low importance channel`() {
        TrackerNotificationChannels.ensureActivityWatcherChannel(context)

        val channel = notificationManager.getNotificationChannel(
            context.getString(BaseR.string.channel_activity_watcher_id),
        )
        assertNotNull(channel)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertEquals(context.getString(BaseR.string.channel_activity_watcher_name), channel.name)
        assertFalse(channel.shouldVibrate())
    }

    @Test
    fun `ensure foreground service channels is idempotent`() {
        TrackerNotificationChannels.ensureForegroundServiceChannels(context)
        TrackerNotificationChannels.ensureForegroundServiceChannels(context)

        assertNotNull(
            notificationManager.getNotificationChannel(context.getString(BaseR.string.channel_track_id)),
        )
        assertNotNull(
            notificationManager.getNotificationChannel(context.getString(BaseR.string.channel_activity_watcher_id)),
        )
    }
}
