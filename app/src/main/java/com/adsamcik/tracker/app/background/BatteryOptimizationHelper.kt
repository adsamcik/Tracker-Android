package com.adsamcik.tracker.app.background

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Helpers for guiding the user to exclude the app from battery optimization /
 * OEM background-kill features (e.g. Samsung "Sleeping apps").
 *
 * Compliance note: this intentionally does NOT use
 * `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` and does NOT declare the
 * `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission (which Google Play flags).
 * It only opens the relevant system settings screens, which require no special
 * permission and carry no Play-policy risk.
 */
object BatteryOptimizationHelper {

	private const val SAMSUNG = "samsung"

	// Samsung Device Care battery screen (best-effort; component may change across One UI versions).
	private const val SAMSUNG_DEVICE_CARE_PACKAGE = "com.samsung.android.lool"
	private const val SAMSUNG_BATTERY_ACTIVITY = "com.samsung.android.sm.ui.battery.BatteryActivity"

	/**
	 * @return true when the app is already exempt from battery optimization (Doze
	 * won't throttle its background work), false otherwise. Always true below API 23.
	 */
	fun isIgnoringBatteryOptimizations(context: Context): Boolean {
		val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
			?: return false
		return powerManager.isIgnoringBatteryOptimizations(context.packageName)
	}

	/**
	 * Opens the system battery-optimization list so the user can switch this app to
	 * "Don't optimize". Uses the settings list intent (no flagged permission).
	 *
	 * @return true if a settings screen was launched.
	 */
	fun openBatteryOptimizationSettings(context: Context): Boolean {
		val listIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
			.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
		if (tryStart(context, listIntent)) return true

		// Fallback: this app's details page, where battery settings are reachable.
		return tryStart(context, appDetailsIntent(context))
	}

	/**
	 * Best-effort deep link into the manufacturer's power-management screen. On
	 * Samsung this targets Device Care's battery screen (closest reachable point to
	 * the "Sleeping apps" list, which has no public intent). Falls back to the app
	 * details page when the OEM component is unavailable.
	 *
	 * @return true if a screen was launched.
	 */
	fun openManufacturerPowerSettings(context: Context): Boolean {
		if (isSamsungDevice()) {
			val deviceCare = Intent().setComponent(
				ComponentName(SAMSUNG_DEVICE_CARE_PACKAGE, SAMSUNG_BATTERY_ACTIVITY),
			).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
			if (tryStart(context, deviceCare)) return true
		}
		return tryStart(context, appDetailsIntent(context))
	}

	/** True when the current device is made by Samsung (One UI "Sleeping apps"). */
	fun isSamsungDevice(): Boolean = isSamsungManufacturer(Build.MANUFACTURER)

	/** Pure, testable manufacturer check. */
	internal fun isSamsungManufacturer(manufacturer: String?): Boolean =
		manufacturer?.trim()?.equals(SAMSUNG, ignoreCase = true) == true

	private fun appDetailsIntent(context: Context): Intent =
		Intent(
			Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
			Uri.fromParts("package", context.packageName, null),
		).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

	private fun tryStart(context: Context, intent: Intent): Boolean = try {
		context.startActivity(intent)
		true
	} catch (_: android.content.ActivityNotFoundException) {
		false
	} catch (_: SecurityException) {
		false
	}
}
