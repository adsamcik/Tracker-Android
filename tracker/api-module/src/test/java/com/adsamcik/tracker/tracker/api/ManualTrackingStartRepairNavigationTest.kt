package com.adsamcik.tracker.tracker.api

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import io.mockk.every
import io.mockk.mockk
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ManualTrackingStartRepairNavigationTest {
    private val packageManager = mockk<PackageManager>()
    private val context = mockk<Context>()

    @Test
    fun `Stop repair never carries the one-use Start request`() {
        every { context.packageName } returns "com.example.tracker"
        every { context.packageManager } returns packageManager
        val launchIntent = Intent("com.example.tracker.OPEN")
            .putExtra("com.adsamcik.tracker.extra.REEVALUATE_MANUAL_START", true)
        every { packageManager.getLaunchIntentForPackage("com.example.tracker") } returns launchIntent

        val intent = assertNotNull(
            ManualTrackingStartRepairNavigation.createDashboardIntent(
                context,
                reevaluateManualStart = false,
            ),
        )

        assertFalse(ManualTrackingStartRepairNavigation.consumeDashboardReevaluationRequest(intent))
    }

    @Test
    fun `Start repair carries a one-use Start request`() {
        every { context.packageName } returns "com.example.tracker"
        every { context.packageManager } returns packageManager
        every { packageManager.getLaunchIntentForPackage("com.example.tracker") } returns
            Intent("com.example.tracker.OPEN")

        val intent = assertNotNull(ManualTrackingStartRepairNavigation.createDashboardIntent(context))

        assertTrue(ManualTrackingStartRepairNavigation.consumeDashboardReevaluationRequest(intent))
        assertFalse(ManualTrackingStartRepairNavigation.consumeDashboardReevaluationRequest(intent))
    }
}
