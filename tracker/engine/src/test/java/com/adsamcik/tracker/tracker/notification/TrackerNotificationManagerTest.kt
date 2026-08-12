package com.adsamcik.tracker.tracker.notification

import android.app.NotificationManager
import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

class TrackerNotificationManagerTest {
	@Test
	fun `tracking notification cancellation uses foreground notification id`() {
		val context = mockk<Context>()
		val notificationManager = mockk<NotificationManager>(relaxed = true)
		every { context.getSystemService(Context.NOTIFICATION_SERVICE) } returns notificationManager

		TrackerNotificationManager.cancelTrackingNotification(context)

		verify(exactly = 1) {
			notificationManager.cancel(TrackerNotificationManager.NOTIFICATION_ID)
		}
	}
}
