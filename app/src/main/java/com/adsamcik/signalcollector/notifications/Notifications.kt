package com.adsamcik.signalcollector.notifications

import java.util.concurrent.atomic.AtomicInteger

object Notifications {
    private val id = AtomicInteger(0)

    fun uniqueNotificationId() = id.incrementAndGet()
}