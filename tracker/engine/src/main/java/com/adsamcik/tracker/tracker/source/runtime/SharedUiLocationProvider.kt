package com.adsamcik.tracker.tracker.source.runtime

import android.location.Location
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.adsamcik.tracker.shared.base.location.UiLocationProvider
import com.adsamcik.tracker.shared.base.location.UiLocationRequest
import com.adsamcik.tracker.shared.base.location.UiLocationRequestInfo
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withContext

/** One feature lease per collector; the tracker runtime owns the only physical registration. */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedUiLocationProvider constructor(
	private val owner: SharedLocationSourceController,
	private val processLifecycle: Lifecycle,
	private val elapsedRealtimeNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
	private val wallTimeMillis: () -> Long = System::currentTimeMillis,
) : UiLocationProvider {
	private val nextRequestId = AtomicLong()
	private val activeLock = Any()
	private val active = linkedMapOf<Long, UiLocationRequestInfo>()
	private val _activeRequests = MutableStateFlow<List<UiLocationRequestInfo>>(emptyList())
	override val activeRequests: StateFlow<List<UiLocationRequestInfo>> = _activeRequests.asStateFlow()

	override fun locationUpdates(request: UiLocationRequest): Flow<Location> = channelFlow {
		if (request.cancelOnAppBackground) {
			// A map that remains composed through app backgrounding releases and later reacquires
			// its own lease without needing an unrelated UI state change to restart its flow.
			processLifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
				this@channelFlow.collectLease(request)
			}
		} else {
			// GameSessionService is already a Location foreground service. Its collector lifetime
			// owns this lease even when the screen is off.
			collectLease(request)
		}
	}

	private suspend fun ProducerScope<Location>.collectLease(request: UiLocationRequest) {
		val leaseId = owner.acquireUiLease(request)
			?: throw IllegalStateException("Location is unavailable")
		val requestId = nextRequestId.incrementAndGet()
		try {
			currentCoroutineContext().ensureActive()
			if (!owner.uiPermissionGranted()) throw SecurityException("Location permission was removed")
			synchronized(activeLock) {
				active[requestId] = UiLocationRequestInfo(request.tag, wallTimeMillis())
				publishActiveLocked()
			}
			val health = flow<UiLocationEvent> {
				var unavailableChecks = 0
				while (true) {
					delay(5_000L)
					if (!owner.uiPermissionGranted()) throw SecurityException("Location permission was removed")
					unavailableChecks = if (owner.uiProviderAvailable()) 0 else unavailableChecks + 1
					if (unavailableChecks >= 2) throw IllegalStateException("Location provider stopped")
				}
			}
			merge(owner.uiLocationEvents, health).collect { event ->
				when (event) {
					UiLocationEvent.Ready -> Unit
					is UiLocationEvent.Fix -> if (event.location.isFreshForUi(
						request.maximumAgeMillis, elapsedRealtimeNanos())) {
						send(Location(event.location))
					}
					UiLocationEvent.Unavailable -> throw IllegalStateException("Location became unavailable")
				}
			}
		} finally {
			synchronized(activeLock) {
				active.remove(requestId)
				publishActiveLocked()
			}
			withContext(NonCancellable) { owner.releaseUiLease(leaseId) }
		}
	}

	private fun publishActiveLocked() {
		_activeRequests.value = active.values.toList()
	}
}

internal fun Location.isFreshForUi(maximumAgeMs: Long, nowElapsedNanos: Long): Boolean {
	if (!isValidLocationEvidence()) return false
	val observed = qualifiedObservedElapsedRealtimeNanos(nowElapsedNanos) ?: return false
	return nowElapsedNanos - observed <= maximumAgeMs * 1_000_000L
}
