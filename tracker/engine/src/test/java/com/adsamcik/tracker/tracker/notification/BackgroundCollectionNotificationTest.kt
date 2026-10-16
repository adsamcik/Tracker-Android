package com.adsamcik.tracker.tracker.notification

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.app.Notification
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.tracker.source.model.SourceKind
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackgroundCollectionNotificationTest {
    @Test
    fun `radio only notification names chosen sources without claiming Location or live session counters`() {
        val context = contextWithLauncher()
        val notification = TrackerNotificationManager.getBackgroundCollectionForegroundNotification(context,
            setOf(SourceKind.WIFI, SourceKind.CELL))
        notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString() shouldBe "Background tracking"
        notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString() shouldBe "Enabled: Wi-Fi, Cell. Open Tracker to manage tracking."
        notification.actions.size shouldBe 1
    }
    private fun contextWithLauncher(): Context {
        val application = ApplicationProvider.getApplicationContext<Context>()
        val packageManager = mockk<PackageManager>(relaxed = true)
        every { packageManager.getLaunchIntentForPackage(application.packageName) } returns
            Intent().setComponent(ComponentName(application.packageName, "test.MainActivity"))
        every { packageManager.getActivityInfo(any<ComponentName>(), any<Int>()) } returns ActivityInfo()
        return object : ContextWrapper(application) {
            override fun getPackageManager(): PackageManager = packageManager
        }
    }
}
