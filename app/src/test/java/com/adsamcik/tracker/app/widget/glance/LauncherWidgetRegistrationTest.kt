package com.adsamcik.tracker.app.widget.glance

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Reads the packaged manifest without starting Tracker or any tracking provider. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class LauncherWidgetRegistrationTest {
    @Test
    fun `Tracker registers no Android launcher widgets`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        @Suppress("DEPRECATION")
        val receivers = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_RECEIVERS or PackageManager.GET_META_DATA,
        ).receivers.orEmpty()

        assertTrue(receivers.none { it.metaData?.containsKey("android.appwidget.provider") == true })
        assertTrue(receivers.none { it.name.startsWith("com.adsamcik.tracker.app.widget.glance.") })
    }
}
