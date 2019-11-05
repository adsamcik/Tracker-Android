package com.adsamcik.tracker

import android.os.Build
import android.util.Log
import androidx.test.InstrumentationRegistry.getInstrumentation
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObjectNotFoundException
import androidx.test.uiautomator.UiSelector

val device: UiDevice get() = UiDevice.getInstance(getInstrumentation())!!

fun handlePermissions(accept: Boolean) {
	if (Build.VERSION.SDK_INT >= 23) {
		var value = if (accept) "Allow" else "Deny"
		if (Build.VERSION.SDK_INT >= 26) {
			value = value.toUpperCase()
		}
		val allowPermissions = device.findObject(UiSelector().text(value))
		if (allowPermissions.exists()) {
			try {
				allowPermissions.click()
			} catch (e: UiObjectNotFoundException) {
				Log.e("Advention", "There is no permissions dialog to interact with ")
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
