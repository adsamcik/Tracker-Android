package com.adsamcik.signalcollector

import android.os.Build
import android.support.test.InstrumentationRegistry.getInstrumentation
import android.support.test.uiautomator.UiDevice
import android.support.test.uiautomator.UiObjectNotFoundException
import android.support.test.uiautomator.UiSelector
import android.util.Log

val device get() = UiDevice.getInstance(getInstrumentation())!!

fun handlePermissions(accept: Boolean) {
    if (Build.VERSION.SDK_INT >= 23) {
        val allowPermissions = device.findObject(UiSelector().text(if (accept) "ALLOW" else "DENY"))
        if (allowPermissions.exists()) {
            try {
                allowPermissions.click()
            } catch (e: UiObjectNotFoundException) {
                Log.e("Signals", "There is no permissions dialog to interact with ")
            }

        }
    }
}

fun sleep(milliseconds: Int) {
    try {
        Thread.sleep(400)
    } catch (e: InterruptedException) {
        e.printStackTrace()
    }
}