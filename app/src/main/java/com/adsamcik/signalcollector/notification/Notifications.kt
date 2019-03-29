package com.adsamcik.signalcollector.notification

import java.util.concurrent.atomic.AtomicInteger

object Notifications {
    private val id = AtomicInteger(0)

    fun uniqueNotificationId() = id.incrementAndGet()
}