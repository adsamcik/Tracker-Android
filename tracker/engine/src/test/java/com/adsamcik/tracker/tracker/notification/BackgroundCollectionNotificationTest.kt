package com.adsamcik.tracker.tracker.notification

import android.app.Application
import android.app.Notification
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.tracker.source.model.SourceKind
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackgroundCollectionNotificationTest {
    @Test
    fun `radio only notification names chosen sources without claiming Location or live session counters`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val notification = TrackerNotificationManager.getBackgroundCollectionForegroundNotification(context,
            setOf(SourceKind.WIFI, SourceKind.CELL))
        notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString() shouldBe "Background tracking"
        notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString() shouldBe "Enabled: Wi-Fi, Cell. Open Tracker to manage tracking."
        notification.actions.size shouldBe 1
    }
}
