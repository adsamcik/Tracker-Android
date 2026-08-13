package com.adsamcik.tracker.shared.base.extension

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.provider.Settings

/** The effective foreground location access exposed by Android right now. */
enum class ForegroundLocationCapability {
	UNAVAILABLE,
	DENIED,
	REVOKED,
	APPROXIMATE,
	PRECISE,
}

/**
 * The location access available to an automatic start that occurs while the app is backgrounded.
 *
 * [MANUAL_ONLY] means foreground location remains usable for a user-initiated tracking session,
 * but Android has not granted the background access needed by an automatic location start.
 */
enum class BackgroundLocationCapability {
	UNAVAILABLE,
	DENIED,
	REVOKED,
	MANUAL_ONLY,
	APPROXIMATE,
	PRECISE,
}

/** The effective capability for this app's scan-based [android.net.wifi.WifiManager] calls. */
enum class WifiScanCapability {
	UNAVAILABLE,
	MISSING_PRECISE_LOCATION,
	MISSING_NEARBY_WIFI,
	REVOKED,
	AVAILABLE,
}

/** Grant history retained by UI/persistence so a later loss is distinguishable from first denial. */
data class PermissionGrantHistory(
	val foregroundLocationGranted: Boolean = false,
	val backgroundLocationGranted: Boolean = false,
	val wifiScanGranted: Boolean = false,
)

/**
 * One Android permission/capability snapshot shared by onboarding, settings, and tracker runtime.
 * Raw grants stay visible so callers never equate coarse access with precise access.
 */
data class TrackingPermissionCapabilities(
	val apiLevel: Int,
	val locationFeatureAvailable: Boolean,
	val wifiFeatureAvailable: Boolean,
	val locationServicesEnabled: Boolean,
	val coarseLocationGranted: Boolean,
	val preciseLocationGranted: Boolean,
	val backgroundLocationGranted: Boolean,
	val nearbyWifiGranted: Boolean,
	val foregroundLocation: ForegroundLocationCapability,
	val backgroundLocation: BackgroundLocationCapability,
	val wifiScan: WifiScanCapability,
) {
	val hasForegroundLocation: Boolean
		get() = foregroundLocation == ForegroundLocationCapability.APPROXIMATE ||
			foregroundLocation == ForegroundLocationCapability.PRECISE

	val hasPreciseLocation: Boolean
		get() = foregroundLocation == ForegroundLocationCapability.PRECISE

	val hasBackgroundLocation: Boolean
		get() = backgroundLocation == BackgroundLocationCapability.APPROXIMATE ||
			backgroundLocation == BackgroundLocationCapability.PRECISE

	val hasWifiScan: Boolean
		get() = wifiScan == WifiScanCapability.AVAILABLE

	val hasWifiScanPermissions: Boolean
		get() = preciseLocationGranted && nearbyWifiGranted

	val isManualLocationOnly: Boolean
		get() = backgroundLocation == BackgroundLocationCapability.MANUAL_ONLY

	fun recordGrants(previous: PermissionGrantHistory): PermissionGrantHistory = PermissionGrantHistory(
		foregroundLocationGranted = previous.foregroundLocationGranted || hasForegroundLocation,
		backgroundLocationGranted = previous.backgroundLocationGranted || hasBackgroundLocation,
		wifiScanGranted = previous.wifiScanGranted || hasWifiScanPermissions,
	)

	companion object {
		fun denied(apiLevel: Int = Build.VERSION.SDK_INT): TrackingPermissionCapabilities = evaluate(
			apiLevel = apiLevel,
			locationFeatureAvailable = true,
			wifiFeatureAvailable = true,
			locationServicesEnabled = true,
			coarseLocationGranted = false,
			preciseLocationGranted = false,
			backgroundLocationGranted = false,
			nearbyWifiGranted = false,
		)

		fun evaluate(
			apiLevel: Int,
			locationFeatureAvailable: Boolean,
			wifiFeatureAvailable: Boolean,
			locationServicesEnabled: Boolean,
			coarseLocationGranted: Boolean,
			preciseLocationGranted: Boolean,
			backgroundLocationGranted: Boolean,
			nearbyWifiGranted: Boolean,
			history: PermissionGrantHistory = PermissionGrantHistory(),
		): TrackingPermissionCapabilities {
			val foreground = when {
				!locationFeatureAvailable -> ForegroundLocationCapability.UNAVAILABLE
				preciseLocationGranted -> ForegroundLocationCapability.PRECISE
				coarseLocationGranted -> ForegroundLocationCapability.APPROXIMATE
				history.foregroundLocationGranted -> ForegroundLocationCapability.REVOKED
				else -> ForegroundLocationCapability.DENIED
			}
			val background = when {
				!locationFeatureAvailable -> BackgroundLocationCapability.UNAVAILABLE
				foreground == ForegroundLocationCapability.REVOKED -> BackgroundLocationCapability.REVOKED
				foreground == ForegroundLocationCapability.DENIED -> BackgroundLocationCapability.DENIED
				apiLevel < Build.VERSION_CODES.Q -> foreground.asBackgroundCapability()
				backgroundLocationGranted -> foreground.asBackgroundCapability()
				history.backgroundLocationGranted -> BackgroundLocationCapability.REVOKED
				else -> BackgroundLocationCapability.MANUAL_ONLY
			}
			val wifi = when {
				!wifiFeatureAvailable || !locationServicesEnabled -> WifiScanCapability.UNAVAILABLE
				!preciseLocationGranted -> if (history.wifiScanGranted) {
					WifiScanCapability.REVOKED
				} else {
					WifiScanCapability.MISSING_PRECISE_LOCATION
				}
				apiLevel >= Build.VERSION_CODES.TIRAMISU && !nearbyWifiGranted ->
					if (history.wifiScanGranted) {
						WifiScanCapability.REVOKED
					} else {
						WifiScanCapability.MISSING_NEARBY_WIFI
					}
				else -> WifiScanCapability.AVAILABLE
			}
			return TrackingPermissionCapabilities(
				apiLevel = apiLevel,
				locationFeatureAvailable = locationFeatureAvailable,
				wifiFeatureAvailable = wifiFeatureAvailable,
				locationServicesEnabled = locationServicesEnabled,
				coarseLocationGranted = coarseLocationGranted,
				preciseLocationGranted = preciseLocationGranted,
				backgroundLocationGranted = apiLevel < Build.VERSION_CODES.Q || backgroundLocationGranted,
				nearbyWifiGranted = apiLevel < Build.VERSION_CODES.TIRAMISU || nearbyWifiGranted,
				foregroundLocation = foreground,
				backgroundLocation = background,
				wifiScan = wifi,
			)
		}
	}
}

private fun ForegroundLocationCapability.asBackgroundCapability(): BackgroundLocationCapability = when (this) {
	ForegroundLocationCapability.UNAVAILABLE -> BackgroundLocationCapability.UNAVAILABLE
	ForegroundLocationCapability.DENIED -> BackgroundLocationCapability.DENIED
	ForegroundLocationCapability.REVOKED -> BackgroundLocationCapability.REVOKED
	ForegroundLocationCapability.APPROXIMATE -> BackgroundLocationCapability.APPROXIMATE
	ForegroundLocationCapability.PRECISE -> BackgroundLocationCapability.PRECISE
}

/** Reads the platform once and evaluates all location/Wi-Fi capability branches consistently. */
fun Context.trackingPermissionCapabilities(
	history: PermissionGrantHistory = PermissionGrantHistory(),
): TrackingPermissionCapabilities {
	val locationManager = getSystemService(LocationManager::class.java)
	val servicesEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
		locationManager?.isLocationEnabled == true
	} else {
		@Suppress("DEPRECATION")
		Settings.Secure.getInt(
			contentResolver,
			Settings.Secure.LOCATION_MODE,
			Settings.Secure.LOCATION_MODE_OFF,
		) != Settings.Secure.LOCATION_MODE_OFF
	}
	return TrackingPermissionCapabilities.evaluate(
		apiLevel = Build.VERSION.SDK_INT,
		locationFeatureAvailable = packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION),
		wifiFeatureAvailable = packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI),
		locationServicesEnabled = servicesEnabled,
		coarseLocationGranted = hasSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION),
		preciseLocationGranted = hasSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION),
		backgroundLocationGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
			hasSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
		nearbyWifiGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
			hasSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES),
		history = history,
	)
}
