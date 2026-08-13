package com.adsamcik.tracker.tracker.source.runtime

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
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
	suspend fun start(plan: LocationPlan, onLocations: (List<Location>) -> Unit): Boolean
	suspend fun flush(): ProviderFlushOutcome
	suspend fun stop(): RegistrationRemovalOutcome
}

@Singleton
internal class FusedLocationSourceBackend @Inject constructor(
	@ApplicationContext context: Context,
) : LocationSourceBackendController {
	override val backend = LocationBackend.FUSED
	override val batchingSupported = true
	override val flushSupported = true
	private val client = LocationServices.getFusedLocationProviderClient(context)
	private var callback: LocationCallback? = null

	@SuppressLint("MissingPermission")
	override suspend fun start(plan: LocationPlan, onLocations: (List<Location>) -> Unit): Boolean {
		check(callback == null) { "Fused location backend is already registered" }
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
		return runCatching {
			client.requestLocationUpdates(request, nextCallback, Looper.getMainLooper()).await()
			callback = nextCallback
			true
		}.getOrDefault(false)
	}

	override suspend fun flush(): ProviderFlushOutcome = if (callback == null) {
		ProviderFlushOutcome.NOT_REQUESTED
	} else {
		runCatching { client.flushLocations().await() }
			.fold({ ProviderFlushOutcome.COMPLETE }, { ProviderFlushOutcome.FAILED })
	}

	override suspend fun stop(): RegistrationRemovalOutcome {
		val active = callback ?: return RegistrationRemovalOutcome.NOT_REGISTERED
		return runCatching { client.removeLocationUpdates(active).await() }
			.fold(
				onSuccess = {
					callback = null
					RegistrationRemovalOutcome.REMOVED
				},
				onFailure = { RegistrationRemovalOutcome.FAILED },
			)
	}
}

@Singleton
internal class FrameworkLocationSourceBackend @Inject constructor(
	@ApplicationContext context: Context,
) : LocationSourceBackendController {
	override val backend = LocationBackend.FRAMEWORK
	override val batchingSupported = false
	override val flushSupported = false
	private val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
	private var listener: LocationListener? = null

	@SuppressLint("MissingPermission")
	override suspend fun start(plan: LocationPlan, onLocations: (List<Location>) -> Unit): Boolean {
		check(listener == null) { "Framework location backend is already registered" }
		val provider = plan.mode.toFrameworkProvider(manager)
		val nextListener = object : LocationListener {
			override fun onLocationChanged(location: Location) = onLocations(listOf(Location(location)))
		}
		return runCatching {
			manager.requestLocationUpdates(
				provider,
				plan.requestedIntervalMs.coerceAtLeast(0L),
				plan.minimumDisplacementMeters.coerceAtLeast(0f),
				nextListener,
				Looper.getMainLooper(),
			)
			listener = nextListener
			true
		}.getOrDefault(false)
	}

	override suspend fun flush() = ProviderFlushOutcome.NOT_SUPPORTED

	override suspend fun stop(): RegistrationRemovalOutcome {
		val active = listener ?: return RegistrationRemovalOutcome.NOT_REGISTERED
		return runCatching { manager.removeUpdates(active) }
			.fold(
				onSuccess = {
					listener = null
					RegistrationRemovalOutcome.REMOVED
				},
				onFailure = { RegistrationRemovalOutcome.FAILED },
			)
	}
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

internal fun LocationMode.toFrameworkProvider(manager: LocationManager): String = when (this) {
	LocationMode.DISABLED, LocationMode.PASSIVE -> LocationManager.PASSIVE_PROVIDER
	LocationMode.LOW_POWER, LocationMode.BALANCED ->
		if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
			LocationManager.NETWORK_PROVIDER
		} else if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
			LocationManager.GPS_PROVIDER
		} else {
			LocationManager.PASSIVE_PROVIDER
		}
	LocationMode.HIGH_ACCURACY, LocationMode.PROBE ->
		if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
			LocationManager.GPS_PROVIDER
		} else if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
			LocationManager.NETWORK_PROVIDER
		} else {
			LocationManager.PASSIVE_PROVIDER
		}
}
