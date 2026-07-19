package com.adsamcik.tracker.shared.base.location

import android.location.Location
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.update

class DefaultUiLocationProvider(
    private val client: FusedLocationProviderClient,
    private val processLifecycle: Lifecycle,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : UiLocationProvider {
    private val _activeRequests = MutableStateFlow<List<UiLocationRequestInfo>>(emptyList())
    override val activeRequests = _activeRequests.asStateFlow()
    private val requests = ConcurrentHashMap<String, UiLocationRequestInfo>()

    override fun locationUpdates(request: UiLocationRequest): Flow<Location> = callbackFlow {
        val requestId = UUID.randomUUID().toString()
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

        val locationRequest = LocationRequest.Builder(request.intervalMillis)
            .setMinUpdateIntervalMillis(request.minUpdateIntervalMillis)
            .setPriority(
                if (request.highAccuracy) Priority.PRIORITY_HIGH_ACCURACY
                else Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            )
            .build()

        try {
            client.requestLocationUpdates(locationRequest, callback, Looper.getMainLooper())
            requests[requestId] = UiLocationRequestInfo(request.tag, nowMillis())
            publishRequests()
            processLifecycle.addObserver(observer)
        } catch (error: SecurityException) {
            close(error)
        } catch (error: IllegalStateException) {
            close(error)
        }

        awaitClose {
            processLifecycle.removeObserver(observer)
            requests.remove(requestId)
            publishRequests()
            client.removeLocationUpdates(callback)
        }
    }

    private fun publishRequests() {
        _activeRequests.value = requests.values.sortedBy(UiLocationRequestInfo::startedAtMillis)
    }
}
