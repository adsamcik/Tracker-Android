package com.adsamcik.signalcollector.utility

import android.os.Build
import com.adsamcik.signalcollector.signin.User

data class DeviceId(val manufacturer: String, val model: String, val userID: String) {
    companion object {
        fun thisDevice(user: User): DeviceId = DeviceId(Build.MANUFACTURER, Build.MODEL, user.id)

        fun thisDevice(userId: String): DeviceId = DeviceId(Build.MANUFACTURER, Build.MODEL, userId)
    }
}