package com.adsamcik.tracker.tracker.service

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.adsamcik.tracker.shared.base.assist.Assist
import com.adsamcik.tracker.shared.base.extension.hasActivityPermission
import com.adsamcik.tracker.shared.base.extension.hasBackgroundLocationPermission
import com.adsamcik.tracker.shared.base.extension.hasCellScanPermission
import com.adsamcik.tracker.shared.base.extension.hasLocationPermission
import com.adsamcik.tracker.shared.base.extension.hasPressureSensor
import com.adsamcik.tracker.shared.base.extension.hasReadPhonePermission
import com.adsamcik.tracker.shared.base.extension.hasStepCounterSensor
import com.adsamcik.tracker.shared.base.extension.hasWifiScanPermission
import com.adsamcik.tracker.tracker.source.coordinator.SessionStartOrigin
import com.adsamcik.tracker.tracker.source.model.SourceKind

/** One Android capability snapshot shared by preflight and authoritative service preparation. */
internal fun Context.foregroundSourceCapabilities(
	startOrigin: SessionStartOrigin,
): ForegroundSourceCapabilities {
	val hardware = foregroundSourceHardware()
	return ForegroundSourceCapabilities(
		sdkInt = Build.VERSION.SDK_INT,
		startOrigin = startOrigin,
		hasForegroundLocationPermission = hasLocationPermission,
		hasBackgroundLocationPermission = hasBackgroundLocationPermission,
		locationHardwareAvailable = hardware.location,
		activity = hasActivityPermission && Assist.isPlayServicesAvailable(this),
		steps = hasActivityPermission && hasStepCounterSensor,
		pressure = hasPressureSensor,
		wifi = hardware.wifi && hasWifiScanPermission,
		cell = hardware.cell && hasCellScanPermission,
	)
}

/** Sources that granting precise Location would newly make available for a manual start. */
internal fun Context.sourcesUnlockedByPreciseLocationPermission(): Set<SourceKind> {
	if (hasWifiScanPermission) return emptySet()
	val hardware = foregroundSourceHardware()
	return buildSet {
		if (hardware.location && !hasLocationPermission) add(SourceKind.LOCATION)
		if (hardware.wifi) add(SourceKind.WIFI)
		if (hardware.cell && hasReadPhonePermission) add(SourceKind.CELL)
	}
}

private data class ForegroundSourceHardware(
	val location: Boolean,
	val wifi: Boolean,
	val cell: Boolean,
)

private fun Context.foregroundSourceHardware(): ForegroundSourceHardware {
	val packageManager = packageManager
	return ForegroundSourceHardware(
		location = packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION),
		wifi = packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI),
		cell = packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) ||
			(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
				packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_RADIO_ACCESS)),
	)
}
