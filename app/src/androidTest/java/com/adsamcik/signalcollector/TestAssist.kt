package com.adsamcik.signalcollector

import android.os.Build
import androidx.test.InstrumentationRegistry.getInstrumentation
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObjectNotFoundException
import androidx.test.uiautomator.UiSelector
import android.util.Log

val device get() = UiDevice.getInstance(getInstrumentation())!!

fun handlePermissions(accept: Boolean) {
    if (Build.VERSION.SDK_INT >= 23) {
        var value = if (accept) "Allow" else "Deny"
        if (Build.VERSION.SDK_INT >= 26)
            value = value.toUpperCase()
        val allowPermissions = device.findObject(UiSelector().text(value))
        if (allowPermissions.exists()) {
            try {
                allowPermissions.click()
            } catch (e: UiObjectNotFoundException) {
                Log.e("Signals", "There is no permissions dialog to interact with ")
            }

        }
    }
}

fun sleep(milliseconds: Long) {
    try {
        Thread.sleep(milliseconds)
    } catch (e: InterruptedException) {
        e.printStackTrace()
    }
}