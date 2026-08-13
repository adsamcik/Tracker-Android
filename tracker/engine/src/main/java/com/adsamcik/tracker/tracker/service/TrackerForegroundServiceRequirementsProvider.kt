package com.adsamcik.tracker.tracker.service

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.adsamcik.tracker.shared.base.assist.Assist
import com.adsamcik.tracker.shared.base.extension.hasActivityPermission
import com.adsamcik.tracker.shared.base.extension.hasCellScanPermission
import com.adsamcik.tracker.shared.base.extension.hasPressureSensor
import com.adsamcik.tracker.shared.base.extension.hasStepCounterSensor
import com.adsamcik.tracker.shared.base.extension.trackingPermissionCapabilities
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.tracker.api.BackgroundTrackingApi
import com.adsamcik.tracker.tracker.api.TrackerForegroundServiceRequirements
import com.adsamcik.tracker.tracker.api.TrackerForegroundServiceRequirementsProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultTrackerForegroundServiceRequirementsProvider @Inject constructor(
	@ApplicationContext private val context: Context,
) : TrackerForegroundServiceRequirementsProvider {
	override fun current(
		isUserInitiated: Boolean,
		isAmbient: Boolean,
	): TrackerForegroundServiceRequirements? {
		val capabilities = context.trackingPermissionCapabilities()
		val packageManager = context.packageManager
		val hasActivityPermission = context.hasActivityPermission
		return resolveTrackerForegroundServiceRequirements(
			params = BackgroundTrackingApi.cachedParams,
			isUserInitiated = isUserInitiated,
			isAmbient = isAmbient,
			locationAvailable = capabilities.hasForegroundLocation,
			backgroundLocationAvailable = capabilities.hasBackgroundLocation,
			activityAvailable = hasActivityPermission && Assist.isPlayServicesAvailable(context),
			stepsAvailable = hasActivityPermission && context.hasStepCounterSensor,
			wifiAvailable = capabilities.hasWifiScan,
			cellAvailable = context.hasCellScanPermission && (
				packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) ||
					(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
						packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_RADIO_ACCESS))
				),
			barometerAvailable = context.hasPressureSensor,
		)
	}
}

/**
 * Resolves actual source categories, including the permission distinction from automatic starts.
 * An automatic non-ambient plan configured for location is rejected as a whole unless background
 * location is effective; a manual plan may continue with another available configured source.
 */
internal fun resolveTrackerForegroundServiceRequirements(
	params: TrackingParamsState,
	isUserInitiated: Boolean,
	isAmbient: Boolean,
	locationAvailable: Boolean,
	backgroundLocationAvailable: Boolean,
	activityAvailable: Boolean,
	stepsAvailable: Boolean,
	wifiAvailable: Boolean,
	cellAvailable: Boolean,
	barometerAvailable: Boolean,
): TrackerForegroundServiceRequirements? {
	if (
		!isUserInitiated &&
		!isAmbient &&
		params.locationEnabled &&
		(!locationAvailable || !backgroundLocationAvailable)
	) {
		return null
	}

	val requiresLocation = !isAmbient && params.locationEnabled && locationAvailable
	val requiresHealth = (params.activityEnabled && activityAvailable) ||
		(params.stepsEnabled && stepsAvailable)
	val hasSignalSources = (params.wifiEnabled && wifiAvailable) ||
		(params.cellEnabled && cellAvailable) ||
		(params.barometerEnabled && barometerAvailable)
	if (!requiresLocation && !requiresHealth && !hasSignalSources) return null

	return TrackerForegroundServiceRequirements(
		requiresLocation = requiresLocation,
		requiresHealth = requiresHealth,
		hasSignalSources = hasSignalSources,
	)
}
