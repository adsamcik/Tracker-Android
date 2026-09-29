package com.adsamcik.tracker.tracker.source.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.di.ApplicationScope
import com.adsamcik.tracker.shared.base.startup.TrackingAdmissionStartupResult
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.tracker.source.ingress.DurableSourceEventSinkFactory
import com.adsamcik.tracker.tracker.source.model.LocationBackend
import com.google.android.gms.location.LocationResult
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface PassiveLocationReceiverEntryPoint {
	fun admission(): PassiveLocationDeliveryAdmission
	fun trackingStartupGate(): TrackingStartupGate
	fun dispatchers(): DispatchersProvider
	@ApplicationScope fun applicationScope(): CoroutineScope
}

/** An explicit, non-exported PendingIntent entry point; URI fields identify only a candidate. */
internal class PassiveLocationReceiver : BroadcastReceiver() {
	override fun onReceive(context: Context, intent: Intent) {
		if (intent.action != PassiveLocationIdentity.ACTION) return
		val identity = PassiveLocationIdentity.fromUri(intent.data) ?: return
		val receivedElapsedNanos = SystemClock.elapsedRealtimeNanos()
		val receivedWallTimeMs = System.currentTimeMillis()
		val result = goAsync()
		val entry = try {
			EntryPointAccessors.fromApplication(context.applicationContext,
				PassiveLocationReceiverEntryPoint::class.java)
		} catch (_: Exception) {
			result.finish()
			return
		}
		val job = try { entry.applicationScope().launch(entry.dispatchers().io) {
			try {
				val remainingMs = ((receivedElapsedNanos + WORK_BUDGET_NANOS -
					SystemClock.elapsedRealtimeNanos()) / 1_000_000L).coerceAtLeast(1L)
				withTimeoutOrNull(remainingMs) {
					val gate = entry.trackingStartupGate()
					if (gate.reconcileAdmission() !is TrackingAdmissionStartupResult.Ready ||
						!gate.permitsNewCollection) return@withTimeoutOrNull
					val decoded = intent.passiveLocations(identity.backend)
					entry.admission().admit(identity, decoded,
						receivedElapsedNanos, receivedWallTimeMs)
				}
			} catch (cancelled: CancellationException) {
				throw cancelled
			} catch (_: Exception) {
				// A provider result, startup or storage failure is an unknown coverage span.
			}
		} } catch (_: Exception) {
			result.finish()
			return
		}
		job.invokeOnCompletion { result.finish() }
	}

	private companion object {
		const val WORK_BUDGET_NANOS = 5_000_000_000L
	}
}

/** Decode only the provider selected by authenticated durable request bytes. */
@Suppress("DEPRECATION")
private fun Intent.passiveLocations(backend: LocationBackend): List<Location> = when (backend) {
	LocationBackend.FUSED -> LocationResult.extractResult(this)?.locations.orEmpty()
	LocationBackend.FRAMEWORK -> {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && hasExtra(LocationManager.KEY_LOCATIONS)) {
			val batch = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
				getParcelableArrayExtra(LocationManager.KEY_LOCATIONS, Location::class.java)
			} else getParcelableArrayExtra(LocationManager.KEY_LOCATIONS)
			batch?.filterIsInstance<Location>().orEmpty()
		} else {
			val single = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
				getParcelableExtra(LocationManager.KEY_LOCATION_CHANGED, Location::class.java)
			} else getParcelableExtra(LocationManager.KEY_LOCATION_CHANGED)
			listOfNotNull(single)
		}
	}
}

/** All work is bounded by the receiver deadline; the shared ingress owns durable admission. */
internal class PassiveLocationDeliveryAdmission @Inject constructor(
	private val startup: TrackingStartupGate,
	private val device: LocationDeviceStateProvider,
	private val registrations: SourceRegistrationRepository,
	sinkFactory: DurableSourceEventSinkFactory,
) {
	private val sink = sinkFactory.unbound

	suspend fun admit(
		identity: PassiveLocationIdentity,
		locations: List<Location>,
		receivedElapsedNanos: Long,
		receivedWallTimeMs: Long,
	): PassiveLocationDeliveryOutcome {
		if (locations.isEmpty()) return PassiveLocationDeliveryOutcome(0, 0, false)
		val overflow = locations.size > MAX_DELIVERY_FIXES
		if (startup.reconcileAdmission() !is TrackingAdmissionStartupResult.Ready ||
			!startup.permitsNewCollection || !permissionsAllowAdmission(device.snapshot())
		) return PassiveLocationDeliveryOutcome(0, locations.size, overflow)
		var admitted = 0
		var unverified = if (overflow) locations.size - MAX_DELIVERY_FIXES else 0
		for (location in normalizeLocationBatch(locations.take(MAX_DELIVERY_FIXES).map(::Location))) {
			if (!startup.permitsNewCollection || !permissionsAllowAdmission(device.snapshot())) {
				unverified++
				continue
			}
			val observed = location.qualifiedObservedElapsedRealtimeNanos(receivedElapsedNanos)
			if (observed == null || !location.isValidLocationEvidence()) {
				unverified++
				continue
			}
			val admission = registrations.passiveLocationAdmissionAt(identity, observed)
			if (admission == null || (admission.approximate && !device.snapshot().coarsePermission) ||
				(!admission.approximate && !device.snapshot().finePermission) ||
				startup.collectionResumeBoundary?.permitsObservation(identity.bootId, observed,
					location.time.takeIf { it > 0L } ?: receivedWallTimeMs) == false
			) {
				unverified++
				continue
			}
			val candidate = locationDeliveryCandidate(listOf(location), listOf(observed),
				receivedElapsedNanos, receivedWallTimeMs, admission.attribution,
				admission.approximate)
			when (sink.admit(candidate)) {
				is SourceDeliveryAdmissionHandoff.Durable,
				is SourceDeliveryAdmissionHandoff.Duplicate -> admitted++
				else -> unverified++
			}
		}
		return PassiveLocationDeliveryOutcome(admitted, unverified, overflow)
	}

	private fun permissionsAllowAdmission(state: LocationDeviceState): Boolean =
		state.hasLocationPermission() &&
			(state.apiLevel < Build.VERSION_CODES.Q || state.backgroundLocationPermission)

	private companion object { const val MAX_DELIVERY_FIXES = 64 }
}

internal data class PassiveLocationDeliveryOutcome(
	val admittedOrDuplicate: Int,
	val unverified: Int,
	val overflow: Boolean,
)
