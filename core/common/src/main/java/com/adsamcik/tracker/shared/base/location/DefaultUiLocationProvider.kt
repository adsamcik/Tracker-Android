package com.adsamcik.tracker.shared.base.location

import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class DefaultUiLocationProvider(
    private val client: FusedLocationProviderClient,
    private val processLifecycle: Lifecycle,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val locationManager: LocationManager? = null,
    private val fusedAvailable: () -> Boolean = { true },
) : UiLocationProvider {
    private val _activeRequests = MutableStateFlow<List<UiLocationRequestInfo>>(emptyList())
    override val activeRequests = _activeRequests.asStateFlow()
    private val requests = ConcurrentHashMap<String, UiLocationRequestInfo>()

    override fun locationUpdates(request: UiLocationRequest): Flow<Location> = callbackFlow {
        val requestId = UUID.randomUUID().toString()
        val stateLock = Any()
        var closed = false
        var published = false
        var fusedAttempted = false
        var frameworkListener: LocationListener? = null
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let(::trySend)
            }
        }
        val observer = object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                close()
            }
        }

        fun publishStarted() {
            val shouldPublish = synchronized(stateLock) {
                if (closed || published) false else {
                    published = true
                    requests[requestId] = UiLocationRequestInfo(request.tag, nowMillis())
                    true
                }
            }
            if (shouldPublish) publishRequests()
        }

        fun startFramework(): Boolean {
            val manager = locationManager ?: return false
            val provider = request.toFrameworkProvider(manager)
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    trySend(Location(location))
                }
            }
            return try {
                manager.requestLocationUpdates(
                    provider,
                    request.intervalMillis.coerceAtLeast(0L),
                    request.minimumDisplacementMeters.coerceAtLeast(0f),
                    listener,
                    Looper.getMainLooper(),
                )
                val removeImmediately = synchronized(stateLock) {
                    if (closed) true else {
                        frameworkListener = listener
                        false
                    }
                }
                if (removeImmediately) {
                    manager.removeUpdates(listener)
                } else {
                    publishStarted()
                }
                true
            } catch (error: RuntimeException) {
                close(error)
                false
            }
        }

        val locationRequest = LocationRequest.Builder(request.intervalMillis)
            .setMinUpdateIntervalMillis(request.minUpdateIntervalMillis)
            .setMinUpdateDistanceMeters(request.minimumDisplacementMeters.coerceAtLeast(0f))
            .setPriority(
                if (request.highAccuracy) Priority.PRIORITY_HIGH_ACCURACY
                else Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            )
            .build()

        processLifecycle.addObserver(observer)
        val registrationJob = launch {
            val shouldTryFused = runCatching(fusedAvailable).getOrDefault(false)
            val fusedRegistered = if (shouldTryFused) {
                synchronized(stateLock) { fusedAttempted = true }
                try {
                    client.requestLocationUpdates(locationRequest, callback, Looper.getMainLooper()).await()
                    true
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    false
                }
            } else {
                false
            }
            if (fusedRegistered) {
                val removeImmediately = synchronized(stateLock) { closed }
                if (removeImmediately) {
                    runCatching { client.removeLocationUpdates(callback) }
                } else {
                    publishStarted()
                }
            } else if (locationManager == null) {
                close(IllegalStateException("No location provider is available"))
            } else {
                startFramework()
            }
        }

        awaitClose {
            val (listener, removeFused) = synchronized(stateLock) {
                closed = true
                Pair(
                    frameworkListener.also { frameworkListener = null },
                    fusedAttempted,
                )
            }
            registrationJob.cancel()
            processLifecycle.removeObserver(observer)
            requests.remove(requestId)
            publishRequests()
            if (removeFused) runCatching { client.removeLocationUpdates(callback) }
            if (listener != null) runCatching { locationManager?.removeUpdates(listener) }
        }
    }

    private fun publishRequests() {
        _activeRequests.value = requests.values.sortedBy(UiLocationRequestInfo::startedAtMillis)
    }
}

private fun UiLocationRequest.toFrameworkProvider(manager: LocationManager): String =
    if (highAccuracy) {
        when {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> LocationManager.PASSIVE_PROVIDER
        }
    } else {
        when {
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> LocationManager.PASSIVE_PROVIDER
        }
    }
