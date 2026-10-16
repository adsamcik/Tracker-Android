package com.adsamcik.tracker.app.background

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Reads the public Android background-restriction signals and opens only public, OEM-neutral
 * settings surfaces for a restriction the platform reports.
 *
 * Compliance note: this intentionally does NOT use
 * `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` and does NOT declare the
 * `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission (which Google Play flags).
 * It opens public system settings screens without requesting the battery-exemption permission.
 */
object BatteryOptimizationHelper {
	/**
	 * Returns nullable values so a missing service or an unsupported platform signal is never
	 * presented as evidence that a restriction exists or does not exist.
	 */
	fun readReliabilityState(context: Context): BackgroundReliabilityState {
		val powerManager = context.getSystemService(PowerManager::class.java)
		val activityManager = context.getSystemService(ActivityManager::class.java)
		return BackgroundReliabilityState(
			batteryOptimizationsExempt = powerManager
				?.isIgnoringBatteryOptimizations(context.packageName),
			backgroundRestricted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
				activityManager?.isBackgroundRestricted
			} else {
				null
			},
		)
	}

	/**
	 * Opens the system battery-optimization list so the user can switch this app to
	 * "Don't optimize". Uses the settings list intent (no flagged permission).
	 *
	 * @return true if a settings screen was launched.
	 */
	private fun openBatteryOptimizationSettings(context: Context): Boolean {
		val listIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
			.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
		if (tryStart(context, listIntent)) return true

		// Fallback: this app's details page, where battery settings are reachable.
		return tryStart(context, appDetailsIntent(context))
	}

	/**
	 * Opens the setting that corresponds to direct platform evidence. Unknown or already-clear
	 * states intentionally have no speculative remediation route.
	 */
	fun openReliabilitySettings(
		context: Context,
		state: BackgroundReliabilityState,
	): Boolean = when (state.condition) {
		BackgroundReliabilityCondition.BACKGROUND_RESTRICTED ->
			tryStart(context, appDetailsIntent(context))
		BackgroundReliabilityCondition.BATTERY_OPTIMIZED ->
			openBatteryOptimizationSettings(context)
		BackgroundReliabilityCondition.NO_DETECTED_RESTRICTION,
		BackgroundReliabilityCondition.UNKNOWN,
		-> false
	}

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

/** Current Android battery restrictions and optional repair destination for background collection. */
data class BackgroundReliabilityState(
	val batteryOptimizationsExempt: Boolean?,
	val backgroundRestricted: Boolean?,
) {
	val condition: BackgroundReliabilityCondition
		get() = resolveBackgroundReliabilityCondition(
			batteryOptimizationsExempt = batteryOptimizationsExempt,
			backgroundRestricted = backgroundRestricted,
		)

	val actionRequired: Boolean
		get() = condition == BackgroundReliabilityCondition.BACKGROUND_RESTRICTED ||
			condition == BackgroundReliabilityCondition.BATTERY_OPTIMIZED
}

/** User-facing categories of platform background execution restrictions. */
enum class BackgroundReliabilityCondition {
	BACKGROUND_RESTRICTED,
	BATTERY_OPTIMIZED,
	NO_DETECTED_RESTRICTION,
	UNKNOWN,
}

internal fun resolveBackgroundReliabilityCondition(
	batteryOptimizationsExempt: Boolean?,
	backgroundRestricted: Boolean?,
): BackgroundReliabilityCondition = when {
	backgroundRestricted == true -> BackgroundReliabilityCondition.BACKGROUND_RESTRICTED
	batteryOptimizationsExempt == false -> BackgroundReliabilityCondition.BATTERY_OPTIMIZED
	backgroundRestricted == false && batteryOptimizationsExempt == true ->
		BackgroundReliabilityCondition.NO_DETECTED_RESTRICTION
	else -> BackgroundReliabilityCondition.UNKNOWN
}
