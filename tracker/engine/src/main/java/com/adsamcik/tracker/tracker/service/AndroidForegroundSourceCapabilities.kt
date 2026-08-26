package com.adsamcik.tracker.tracker.service

import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import com.adsamcik.tracker.shared.base.assist.Assist
import com.adsamcik.tracker.shared.base.extension.hasActivityPermission
import com.adsamcik.tracker.shared.base.extension.hasBackgroundLocationPermission
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
	val device = manualTrackingDeviceCapabilities()
	return ForegroundSourceCapabilities(
		sdkInt = Build.VERSION.SDK_INT,
		startOrigin = startOrigin,
		hasForegroundLocationPermission = device.hasAnyLocationPermission,
		hasBackgroundLocationPermission = hasBackgroundLocationPermission,
		locationHardwareAvailable =
			device.locationHardwareAvailable && device.locationServicesEnabled,
		activity = device.activityProviderAvailable && device.hasActivityRecognitionPermission,
		steps = device.stepCounterAvailable && device.hasActivityRecognitionPermission,
		pressure = device.pressureSensorAvailable,
		wifi = device.wifiHardwareAvailable && device.hasPreciseLocationPermission &&
			device.locationServicesEnabled,
		cell = device.cellHardwareAvailable && device.hasPreciseLocationPermission &&
			device.hasReadPhoneStatePermission && device.locationServicesEnabled,
	)
}

/** Factual source state used by manual preflight before the authoritative prepare repeats it. */
internal data class ManualTrackingSourceCapabilities(
	val supportedSources: Set<SourceKind>,
	val availableSources: Set<SourceKind>,
	val sourcesMissingPreciseLocationPermission: Set<SourceKind>,
	val sourcesMissingActivityRecognitionPermission: Set<SourceKind>,
	val sourcesMissingReadPhoneStatePermission: Set<SourceKind>,
	val sourcesBlockedByLocationServices: Set<SourceKind>,
)

internal data class ManualTrackingDeviceCapabilities(
	val locationHardwareAvailable: Boolean,
	val activityProviderAvailable: Boolean,
	val stepCounterAvailable: Boolean,
	val pressureSensorAvailable: Boolean,
	val wifiHardwareAvailable: Boolean,
	val cellHardwareAvailable: Boolean,
	val hasAnyLocationPermission: Boolean,
	val hasPreciseLocationPermission: Boolean,
	val hasActivityRecognitionPermission: Boolean,
	val hasReadPhoneStatePermission: Boolean,
	val locationServicesEnabled: Boolean,
)

internal fun ManualTrackingDeviceCapabilities.toManualTrackingSourceCapabilities():
	ManualTrackingSourceCapabilities {
	val supportedSources = buildSet {
		if (locationHardwareAvailable) add(SourceKind.LOCATION)
		if (activityProviderAvailable) add(SourceKind.ACTIVITY)
		if (stepCounterAvailable) add(SourceKind.STEPS)
		if (pressureSensorAvailable) add(SourceKind.PRESSURE)
		if (wifiHardwareAvailable) add(SourceKind.WIFI)
		if (cellHardwareAvailable) add(SourceKind.CELL)
	}
	val missingPreciseLocationPermission = buildSet {
		if (SourceKind.LOCATION in supportedSources && !hasAnyLocationPermission) {
			add(SourceKind.LOCATION)
		}
		if (SourceKind.WIFI in supportedSources && !hasPreciseLocationPermission) {
			add(SourceKind.WIFI)
		}
		if (SourceKind.CELL in supportedSources && !hasPreciseLocationPermission) {
			add(SourceKind.CELL)
		}
	}
	val missingActivityRecognitionPermission = buildSet {
		if (SourceKind.ACTIVITY in supportedSources && !hasActivityRecognitionPermission) {
			add(SourceKind.ACTIVITY)
		}
		if (SourceKind.STEPS in supportedSources && !hasActivityRecognitionPermission) {
			add(SourceKind.STEPS)
		}
	}
	val missingReadPhoneStatePermission = buildSet {
		if (SourceKind.CELL in supportedSources && !hasReadPhoneStatePermission) {
			add(SourceKind.CELL)
		}
	}
	val blockedByLocationServices = if (locationServicesEnabled) {
		emptySet()
	} else {
		supportedSources intersect setOf(SourceKind.LOCATION, SourceKind.WIFI, SourceKind.CELL)
	}
	val unavailableSources = missingPreciseLocationPermission +
		missingActivityRecognitionPermission +
		missingReadPhoneStatePermission +
		blockedByLocationServices
	return ManualTrackingSourceCapabilities(
		supportedSources = supportedSources,
		availableSources = supportedSources - unavailableSources,
		sourcesMissingPreciseLocationPermission = missingPreciseLocationPermission,
		sourcesMissingActivityRecognitionPermission = missingActivityRecognitionPermission,
		sourcesMissingReadPhoneStatePermission = missingReadPhoneStatePermission,
		sourcesBlockedByLocationServices = blockedByLocationServices,
	)
}

internal fun Context.manualTrackingSourceCapabilities(): ManualTrackingSourceCapabilities =
	manualTrackingDeviceCapabilities().toManualTrackingSourceCapabilities()

private fun Context.manualTrackingDeviceCapabilities(): ManualTrackingDeviceCapabilities {
	val packageManager = packageManager
	return ManualTrackingDeviceCapabilities(
		locationHardwareAvailable = packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION),
		activityProviderAvailable = Assist.isPlayServicesAvailable(this),
		stepCounterAvailable = hasStepCounterSensor,
		pressureSensorAvailable = hasPressureSensor,
		wifiHardwareAvailable = packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI),
		cellHardwareAvailable = packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) ||
			(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
				packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_RADIO_ACCESS)),
		hasAnyLocationPermission = hasLocationPermission,
		hasPreciseLocationPermission = hasWifiScanPermission,
		hasActivityRecognitionPermission = hasActivityPermission,
		hasReadPhoneStatePermission = hasReadPhonePermission,
		locationServicesEnabled = locationServicesEnabledForManualTracking(),
	)
}

@Suppress("DEPRECATION")
private fun Context.locationServicesEnabledForManualTracking(): Boolean = try {
	val manager = getSystemService(LocationManager::class.java) ?: return false
	if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
		manager.isLocationEnabled
	} else {
		manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
			manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
	}
} catch (_: RuntimeException) {
	false
}
