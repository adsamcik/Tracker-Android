package com.adsamcik.tracker.app.widget.glance

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
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
    fun `remaining launcher widgets have registered provider metadata`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        @Suppress("DEPRECATION")
        val receivers = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_RECEIVERS or PackageManager.GET_META_DATA,
        ).receivers.orEmpty().filter {
            it.name.startsWith("com.adsamcik.tracker.app.widget.glance.")
        }

        assertEquals(
            setOf(QuickStartWidgetReceiver::class.java.name, TodaySummaryWidgetReceiver::class.java.name),
            receivers.map { it.name }.toSet(),
        )
        assertEquals(2, receivers.size)
        receivers.forEach { receiver ->
            val providerResource = receiver.metaData.getInt("android.appwidget.provider")
            assertTrue(providerResource != 0)
            val provider = context.resources.getXml(providerResource)
            try {
                while (provider.eventType != org.xmlpull.v1.XmlPullParser.START_TAG &&
                    provider.eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) provider.next()
                assertEquals("appwidget-provider", provider.name)
            } finally {
                provider.close()
            }
        }
    }
}
