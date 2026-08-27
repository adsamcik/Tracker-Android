package com.adsamcik.tracker.tracker.source.runtime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import com.adsamcik.tracker.tracker.source.runCatchingNonCancellation
import com.adsamcik.tracker.tracker.source.model.LocationBackend
import com.adsamcik.tracker.tracker.source.model.LocationMode
import com.adsamcik.tracker.tracker.source.model.LocationPlan
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.tasks.await

internal interface LocationSourceBackendController {
	val backend: LocationBackend
	val batchingSupported: Boolean
	val flushSupported: Boolean
	val hasRetainedRegistration: Boolean
	suspend fun start(
		plan: LocationPlan,
		onLocations: (List<Location>) -> Unit,
	): LocationBackendStartOutcome
	suspend fun flush(): ProviderFlushOutcome
	suspend fun stop(): RegistrationRemovalOutcome
}

internal enum class LocationBackendStartOutcome {
	STARTED,
	CLEANUP_REQUIRED,
	BLOCKED_BY_RETAINED_REGISTRATION,
}

internal data class FusedLocationBackendOperations(
	val requestUpdates: suspend (LocationRequest, LocationCallback) -> Unit,
	val flushLocations: suspend () -> Unit,
	val removeUpdates: suspend (LocationCallback) -> Unit,
)

@Singleton
internal class FusedLocationSourceBackend internal constructor(
	private val operations: FusedLocationBackendOperations,
) : LocationSourceBackendController {
	@Inject
	constructor(
		@ApplicationContext context: Context,
	) : this(fusedLocationBackendOperations(context))

	override val backend = LocationBackend.FUSED
	override val batchingSupported = true
	override val flushSupported = true
	private var callback: LocationCallback? = null
	override val hasRetainedRegistration: Boolean get() = callback != null

	override suspend fun start(
		plan: LocationPlan,
		onLocations: (List<Location>) -> Unit,
	): LocationBackendStartOutcome {
		if (callback != null) return LocationBackendStartOutcome.BLOCKED_BY_RETAINED_REGISTRATION
		val nextCallback = object : LocationCallback() {
			override fun onLocationResult(result: LocationResult) {
				if (result.locations.isNotEmpty()) onLocations(result.locations.map(::Location))
			}
		}
		val request = LocationRequest.Builder(plan.requestedIntervalMs.coerceAtLeast(1L))
			.setPriority(plan.mode.toFusedPriority(plan.preciseLocationAvailable))
			.setMinUpdateIntervalMillis(plan.minimumUpdateIntervalMs.coerceAtLeast(0L))
			.setMinUpdateDistanceMeters(plan.minimumDisplacementMeters.coerceAtLeast(0f))
			.setMaxUpdateDelayMillis(plan.maximumBatchDelayMs.coerceAtLeast(0L))
			.setWaitForAccurateLocation(
				plan.preciseLocationAvailable && plan.mode in setOf(LocationMode.HIGH_ACCURACY, LocationMode.PROBE),
			)
			.build()
		// Keep the exact callback before the provider call. A failed/ambiguous Task can have applied
		// its side effect, so only the runtime's durable retirement path may remove this object.
		callback = nextCallback
		return runCatchingNonCancellation { operations.requestUpdates(request, nextCallback) }
			.fold(
				onSuccess = { LocationBackendStartOutcome.STARTED },
				onFailure = { LocationBackendStartOutcome.CLEANUP_REQUIRED },
			)
	}

	override suspend fun flush(): ProviderFlushOutcome = if (callback == null) {
		ProviderFlushOutcome.NOT_REQUESTED
	} else {
		runCatchingNonCancellation { operations.flushLocations() }
			.fold({ ProviderFlushOutcome.COMPLETE }, { ProviderFlushOutcome.FAILED })
	}

	override suspend fun stop(): RegistrationRemovalOutcome {
		val active = callback ?: return RegistrationRemovalOutcome.NOT_REGISTERED
		return runCatchingNonCancellation { operations.removeUpdates(active) }
			.fold(
				onSuccess = {
					callback = null
					RegistrationRemovalOutcome.REMOVED
				},
				onFailure = { RegistrationRemovalOutcome.FAILED },
			)
	}
}

private fun fusedLocationBackendOperations(context: Context): FusedLocationBackendOperations {
	val client = LocationServices.getFusedLocationProviderClient(context)
	return FusedLocationBackendOperations(
		requestUpdates = { request, callback ->
			if (
				ContextCompat.checkSelfPermission(
					context,
					Manifest.permission.ACCESS_FINE_LOCATION,
				) != PackageManager.PERMISSION_GRANTED &&
				ContextCompat.checkSelfPermission(
					context,
					Manifest.permission.ACCESS_COARSE_LOCATION,
				) != PackageManager.PERMISSION_GRANTED
			) {
				throw SecurityException("Foreground location permission is not granted")
			}
			client.requestLocationUpdates(request, callback, Looper.getMainLooper()).await()
		},
		flushLocations = { client.flushLocations().await() },
		removeUpdates = { callback -> client.removeLocationUpdates(callback).await() },
	)
}

internal data class FrameworkLocationBackendOperations(
	val isProviderEnabled: (String) -> Boolean,
	val requestUpdates: (String, Long, Float, LocationListener) -> Unit,
	val removeUpdates: (LocationListener) -> Unit,
)

@Singleton
internal class FrameworkLocationSourceBackend internal constructor(
	private val operations: FrameworkLocationBackendOperations,
) : LocationSourceBackendController {
	@Inject
	constructor(
		@ApplicationContext context: Context,
	) : this(frameworkLocationBackendOperations(context))

	override val backend = LocationBackend.FRAMEWORK
	override val batchingSupported = false
	override val flushSupported = false
	private var listener: LocationListener? = null
	override val hasRetainedRegistration: Boolean get() = listener != null

	override suspend fun start(
		plan: LocationPlan,
		onLocations: (List<Location>) -> Unit,
	): LocationBackendStartOutcome {
		if (listener != null) return LocationBackendStartOutcome.BLOCKED_BY_RETAINED_REGISTRATION
		val nextListener = object : LocationListener {
			override fun onLocationChanged(location: Location) = onLocations(listOf(Location(location)))
		}
		// LocationManager throws synchronously on many failures, but the exact listener is still
		// retained first so an OEM that applies-then-throws cannot escape runtime-owned cleanup.
		listener = nextListener
		return runCatchingNonCancellation {
			val provider = plan.mode.toFrameworkProvider(operations.isProviderEnabled)
			operations.requestUpdates(
				provider,
				plan.requestedIntervalMs.coerceAtLeast(0L),
				plan.minimumDisplacementMeters.coerceAtLeast(0f),
				nextListener,
			)
		}.fold(
			onSuccess = { LocationBackendStartOutcome.STARTED },
			onFailure = { LocationBackendStartOutcome.CLEANUP_REQUIRED },
		)
	}

	override suspend fun flush() = ProviderFlushOutcome.NOT_SUPPORTED

	override suspend fun stop(): RegistrationRemovalOutcome {
		val active = listener ?: return RegistrationRemovalOutcome.NOT_REGISTERED
		return runCatchingNonCancellation { operations.removeUpdates(active) }
			.fold(
				onSuccess = {
					listener = null
					RegistrationRemovalOutcome.REMOVED
				},
				onFailure = { RegistrationRemovalOutcome.FAILED },
			)
	}
}

private fun frameworkLocationBackendOperations(context: Context): FrameworkLocationBackendOperations {
	val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
	return FrameworkLocationBackendOperations(
		isProviderEnabled = manager::isProviderEnabled,
		requestUpdates = { provider, intervalMs, displacementMeters, listener ->
			if (
				ContextCompat.checkSelfPermission(
					context,
					Manifest.permission.ACCESS_FINE_LOCATION,
				) != PackageManager.PERMISSION_GRANTED &&
				ContextCompat.checkSelfPermission(
					context,
					Manifest.permission.ACCESS_COARSE_LOCATION,
				) != PackageManager.PERMISSION_GRANTED
			) {
				throw SecurityException("Foreground location permission is not granted")
			}
			manager.requestLocationUpdates(
				provider,
				intervalMs,
				displacementMeters,
				listener,
				Looper.getMainLooper(),
			)
		},
		removeUpdates = manager::removeUpdates,
	)
}

private fun LocationMode.toFusedPriority(precise: Boolean): Int = when (this) {
	LocationMode.DISABLED, LocationMode.PASSIVE -> Priority.PRIORITY_PASSIVE
	LocationMode.LOW_POWER -> Priority.PRIORITY_LOW_POWER
	LocationMode.BALANCED -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
	LocationMode.HIGH_ACCURACY, LocationMode.PROBE -> if (precise) {
		Priority.PRIORITY_HIGH_ACCURACY
	} else {
		Priority.PRIORITY_BALANCED_POWER_ACCURACY
	}
}

internal fun LocationMode.toFrameworkProvider(isProviderEnabled: (String) -> Boolean): String = when (this) {
	LocationMode.DISABLED, LocationMode.PASSIVE -> LocationManager.PASSIVE_PROVIDER
	LocationMode.LOW_POWER, LocationMode.BALANCED ->
		if (isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
			LocationManager.NETWORK_PROVIDER
		} else if (isProviderEnabled(LocationManager.GPS_PROVIDER)) {
			LocationManager.GPS_PROVIDER
		} else {
			LocationManager.PASSIVE_PROVIDER
		}
	LocationMode.HIGH_ACCURACY, LocationMode.PROBE ->
		if (isProviderEnabled(LocationManager.GPS_PROVIDER)) {
			LocationManager.GPS_PROVIDER
		} else if (isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
			LocationManager.NETWORK_PROVIDER
		} else {
			LocationManager.PASSIVE_PROVIDER
		}
}
