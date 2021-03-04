package com.adsamcik.tracker.shared.base.notification

import java.util.concurrent.atomic.AtomicInteger

/**
 * Provides basic utility for notifications.
 */
object Notifications {
	private val id = AtomicInteger(0)

	/**
	 * Returns unique notification ID.
	 */
	fun uniqueNotificationId(): Int = id.incrementAndGet()
}
