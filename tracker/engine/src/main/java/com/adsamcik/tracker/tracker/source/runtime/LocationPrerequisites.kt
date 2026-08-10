package com.adsamcik.tracker.tracker.source.runtime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.adsamcik.tracker.shared.base.assist.Assist
import com.adsamcik.tracker.tracker.source.model.LocationBackend
import com.adsamcik.tracker.tracker.source.model.LocationMode
import com.adsamcik.tracker.tracker.source.model.LocationPlan
import com.adsamcik.tracker.tracker.source.model.SourceDegradedReason
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

enum class LocationStartContext { SESSION_ALREADY_FOREGROUND, MANUAL_FOREGROUND_START, AUTOMATIC_BACKGROUND_START }

data class LocationDeviceState(
	val apiLevel: Int,
	val locationFeatureAvailable: Boolean,
	val locationServicesEnabled: Boolean,
	val coarsePermission: Boolean,
	val finePermission: Boolean,
	val backgroundLocationPermission: Boolean,
	val fusedProviderAvailable: Boolean,
	val foregroundServiceLocationCapability: Boolean,
	val backgroundForegroundServiceStartLegal: Boolean,
)

data class LocationPlanApplication(
	val plan: LocationPlan,
	val status: LocationPlanApplicationStatus,
	val reasons: Set<SourceDegradedReason>,
)

enum class LocationPlanApplicationStatus { APPLIED, DEGRADED, BLOCKED }

/** Pure Android device/permission matrix used before either location backend is touched. */
class LocationPrerequisiteEvaluator @Inject constructor() {
	fun evaluate(
		plan: LocationPlan,
		device: LocationDeviceState,
		startContext: LocationStartContext,
	): LocationPlanApplication {
		if (!plan.enabled) return LocationPlanApplication(plan, LocationPlanApplicationStatus.APPLIED, emptySet())
		val reasons = mutableSetOf<SourceDegradedReason>()
		if (!device.locationFeatureAvailable) reasons += SourceDegradedReason.HARDWARE_UNAVAILABLE
		if (!device.locationServicesEnabled && plan.mode != LocationMode.PASSIVE) {
			reasons += SourceDegradedReason.PROVIDER_UNAVAILABLE
		}
		if (!device.coarsePermission && !device.finePermission) reasons += SourceDegradedReason.PERMISSION_MISSING
		if (plan.backend == LocationBackend.FUSED && !device.fusedProviderAvailable) {
			reasons += SourceDegradedReason.PROVIDER_UNAVAILABLE
		}
		if (!device.foregroundServiceLocationCapability) {
			reasons += SourceDegradedReason.FOREGROUND_CAPABILITY_MISSING
		}
		if (startContext == LocationStartContext.AUTOMATIC_BACKGROUND_START &&
			!device.backgroundForegroundServiceStartLegal
		) reasons += SourceDegradedReason.BACKGROUND_START_ILLEGAL
		if (device.apiLevel >= Build.VERSION_CODES.Q &&
			startContext == LocationStartContext.AUTOMATIC_BACKGROUND_START &&
			!device.backgroundLocationPermission
		) reasons += SourceDegradedReason.PERMISSION_MISSING

		val hardBlocked = reasons.isNotEmpty()
		if (hardBlocked) {
			return LocationPlanApplication(
				plan.copy(mode = LocationMode.DISABLED),
				LocationPlanApplicationStatus.BLOCKED,
				reasons,
			)
		}
		if (!device.finePermission || !plan.preciseLocationAvailable) {
			val approximatePlan = plan.copy(
				mode = if (plan.mode in setOf(LocationMode.HIGH_ACCURACY, LocationMode.PROBE)) {
					LocationMode.BALANCED
				} else plan.mode,
				preciseLocationAvailable = false,
			)
			return LocationPlanApplication(
				approximatePlan,
				LocationPlanApplicationStatus.DEGRADED,
				setOf(SourceDegradedReason.PERMISSION_MISSING),
			)
		}
		return LocationPlanApplication(plan, LocationPlanApplicationStatus.APPLIED, emptySet())
	}
}

interface LocationDeviceStateProvider {
	fun snapshot(): LocationDeviceState
}

@Singleton
class AndroidLocationDeviceStateProvider @Inject constructor(
	@ApplicationContext private val context: Context,
) : LocationDeviceStateProvider {
	override fun snapshot(): LocationDeviceState {
		val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
		val locationEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
			manager.isLocationEnabled
		} else {
			manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
				manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
		}
		return LocationDeviceState(
			apiLevel = Build.VERSION.SDK_INT,
			locationFeatureAvailable = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION),
			locationServicesEnabled = locationEnabled,
			coarsePermission = granted(Manifest.permission.ACCESS_COARSE_LOCATION),
			finePermission = granted(Manifest.permission.ACCESS_FINE_LOCATION),
			backgroundLocationPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
				granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
			fusedProviderAvailable = Assist.isPlayServicesAvailable(context),
			// TrackerService promotes its location type before applying the source plan. The service
			// owns manifest/type legality; the runtime still consumes this explicit matrix field.
			foregroundServiceLocationCapability = true,
			backgroundForegroundServiceStartLegal = true,
		)
	}

	private fun granted(permission: String): Boolean =
		ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

