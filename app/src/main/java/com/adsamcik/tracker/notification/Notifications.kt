package com.adsamcik.tracker.notification

import java.util.concurrent.atomic.AtomicInteger

object Notifications {
	private val id = AtomicInteger(0)

	fun uniqueNotificationId(): Int = id.incrementAndGet()
}
