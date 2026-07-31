package com.adsamcik.tracker.testing.uiautomator

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.util.Locale

private const val DEFAULT_TIMEOUT_MS = 2_000L

/**
 * Gets the [UiDevice] instance for UI Automator interactions.
 */
val uiDevice: UiDevice
	get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

/**
 * Handles Android permission dialogs by clicking Accept or Deny.
 * Works across different Android API levels with appropriate button text.
 *
 * @param accept If true, grants the permission; if false, denies it
 * @param timeoutMs Timeout to wait for the permission dialog (default: 2s)
 * @return True if a permission dialog was found and handled
 */
fun handlePermissions(accept: Boolean, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Boolean {
	var buttonText = if (accept) "Allow" else "Deny"
	if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
		buttonText = buttonText.uppercase(Locale.getDefault())
	}

	val device = uiDevice
	val button = device.wait(
		Until.findObject(By.text(buttonText)),
		timeoutMs
	)

	return if (button != null) {
		try {
			button.click()
			true
		} catch (_: Exception) {
			false
		}
	} else {
		false
	}
}

/**
 * Handles system permission dialogs by attempting to click through them.
 * Tries multiple strategies: resource IDs first, then text fallbacks.
 *
 * @param preferGrant If true, prefers grant options; if false, prefers deny options
 * @param timeoutMs Timeout for each button search (default: 2s)
 * @return True if any dialog was handled
 */
fun handleSystemPermissionDialogs(
	preferGrant: Boolean = true,
	timeoutMs: Long = DEFAULT_TIMEOUT_MS
): Boolean {
	val device = uiDevice
	var handled = false

	// Permission controller resource IDs (Android 10+)
	val denyIds = listOf(
		"com.android.permissioncontroller:id/permission_deny_button",
		"com.android.permissioncontroller:id/permission_deny_and_dont_ask_again_button"
	)
	val grantIds = listOf(
		"com.android.permissioncontroller:id/permission_allow_button",
		"com.android.permissioncontroller:id/permission_allow_always_button",
		"com.android.permissioncontroller:id/permission_allow_foreground_only_button",
		"com.android.permissioncontroller:id/permission_allow_one_time_button"
	)

	// Order based on preference
	val orderedIds = if (preferGrant) {
		grantIds + denyIds
	} else {
		denyIds + grantIds
	}

	// Try resource IDs first (more reliable)
	for (resId in orderedIds) {
		val obj = device.wait(Until.findObject(By.res(resId)), timeoutMs)
		if (obj != null && runCatching { obj.click() }.isSuccess) {
			handled = true
			break
		}
	}

	// Text fallbacks for older Android versions or different locales
	if (!handled) {
		val denyTexts = listOf("Don't allow", "Deny", "DENY", "DON'T ALLOW")
		val grantTexts = listOf(
			"Allow", "ALLOW",
			"While using the app", "WHILE USING THE APP",
			"Only this time", "ONLY THIS TIME",
			"Allow only while using the app",
			"Continue", "CONTINUE",
			"OK", "Ok"
		)

		val orderedTexts = if (preferGrant) {
			grantTexts + denyTexts
		} else {
			denyTexts + grantTexts
		}

		for (text in orderedTexts) {
			val obj = device.wait(Until.findObject(By.text(text)), timeoutMs / 4)
			if (obj != null && runCatching { obj.click() }.isSuccess) {
				handled = true
				break
			}
		}
	}

	return handled
}

/**
 * Dismisses any visible system dialogs (not just permissions).
 * Useful for handling unexpected system popups during tests.
 *
 * @param timeoutMs Timeout for finding dialogs
 * @return True if any dialog was dismissed
 */
fun dismissSystemDialogs(timeoutMs: Long = DEFAULT_TIMEOUT_MS): Boolean {
	val device = uiDevice
	var dismissed = false

	val dismissTexts = listOf(
		"OK", "Ok", "CANCEL", "Cancel", "Close", "CLOSE",
		"Got it", "GOT IT", "Dismiss", "DISMISS",
		"Not now", "NOT NOW", "Skip", "SKIP"
	)

	for (text in dismissTexts) {
		val obj = device.wait(Until.findObject(By.text(text)), timeoutMs / 4)
		if (obj != null && runCatching { obj.click() }.isSuccess) {
			dismissed = true
			break
		}
	}

	return dismissed
}

/**
 * Waits for any permission dialogs to appear and handles them.
 * Useful when you expect a permission request but don't know exactly when it will appear.
 *
 * @param accept Whether to accept or deny permissions
 * @param maxAttempts Maximum number of dialogs to handle
 * @param delayBetweenAttemptsMs Delay between attempts
 * @return Number of dialogs handled
 */
fun waitAndHandlePermissionDialogs(
	accept: Boolean,
	maxAttempts: Int = 3,
	delayBetweenAttemptsMs: Long = 500L
): Int {
	var handledCount = 0

	repeat(maxAttempts) {
		val handled = handleSystemPermissionDialogs(preferGrant = accept)
		if (handled) {
			handledCount++
			Thread.sleep(delayBetweenAttemptsMs)
		}
	}

	return handledCount
}

/**
 * Presses the device back button.
 */
fun pressBack() {
	uiDevice.pressBack()
}

/**
 * Presses the device home button.
 */
fun pressHome() {
	uiDevice.pressHome()
}

/**
 * Waits for the app to be idle (no pending UI updates).
 *
 * @param timeoutMs Timeout for waiting
 */
fun waitForIdle(timeoutMs: Long = DEFAULT_TIMEOUT_MS) {
	uiDevice.waitForIdle(timeoutMs)
}
