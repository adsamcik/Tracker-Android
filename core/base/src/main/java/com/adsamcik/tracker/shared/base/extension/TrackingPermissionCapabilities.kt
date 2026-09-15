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
	MISSING_REQUIRED_PERMISSION,
	REVOKED,
	AVAILABLE,
}

/** Platform prerequisites for this app's scan-only startScan()/getScanResults() path. */
object WifiScanPlatformRequirements {
	fun hasRequiredPermission(
		apiLevel: Int,
		coarseLocationGranted: Boolean,
		preciseLocationGranted: Boolean,
		changeWifiStateGranted: Boolean,
	): Boolean = when {
		apiLevel <= Build.VERSION_CODES.O_MR1 ->
			coarseLocationGranted || preciseLocationGranted || changeWifiStateGranted
		apiLevel == Build.VERSION_CODES.P -> coarseLocationGranted || preciseLocationGranted
		else -> preciseLocationGranted
	}

	fun requiresLocationServices(apiLevel: Int): Boolean = apiLevel >= Build.VERSION_CODES.P

	fun isReady(
		apiLevel: Int,
		coarseLocationGranted: Boolean,
		preciseLocationGranted: Boolean,
		changeWifiStateGranted: Boolean,
		locationServicesEnabled: Boolean,
	): Boolean = hasRequiredPermission(
		apiLevel,
		coarseLocationGranted,
		preciseLocationGranted,
		changeWifiStateGranted,
	) && (!requiresLocationServices(apiLevel) || locationServicesEnabled)
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
	val changeWifiStateGranted: Boolean,
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

	/**
	 * Permissions required by this app's scan-only Wi-Fi path.
	 *
	 * [android.Manifest.permission.NEARBY_WIFI_DEVICES] protects connection-management APIs on
	 * Android 13+, but this app only calls startScan()/getScanResults(). Android 8.1 and earlier
	 * accept coarse, fine, or CHANGE_WIFI_STATE; Android 9 accepts coarse or fine; Android 10+
	 * requires fine location for this target SDK.
	 */
	val hasWifiScanPermissions: Boolean
		get() = WifiScanPlatformRequirements.hasRequiredPermission(
			apiLevel,
			coarseLocationGranted,
			preciseLocationGranted,
			changeWifiStateGranted,
		)

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
			changeWifiStateGranted = false,
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
			changeWifiStateGranted: Boolean = false,
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
			val hasWifiPermission = WifiScanPlatformRequirements.hasRequiredPermission(
				apiLevel,
				coarseLocationGranted,
				preciseLocationGranted,
				changeWifiStateGranted,
			)
			val wifi = when {
				!wifiFeatureAvailable ||
					(WifiScanPlatformRequirements.requiresLocationServices(apiLevel) &&
						!locationServicesEnabled) -> WifiScanCapability.UNAVAILABLE
				!hasWifiPermission -> if (history.wifiScanGranted) {
					WifiScanCapability.REVOKED
				} else {
					WifiScanCapability.MISSING_REQUIRED_PERMISSION
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
				changeWifiStateGranted = changeWifiStateGranted,
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
		changeWifiStateGranted = hasSelfPermission(Manifest.permission.CHANGE_WIFI_STATE),
		history = history,
	)
}
