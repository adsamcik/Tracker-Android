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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield

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
		if (intent.action != PassiveLocationIdentity.ACTION) {
			return
		}
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
						!gate.permitsNewCollection) {
						return@withTimeoutOrNull
					}
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
			} else {
				getParcelableArrayExtra(LocationManager.KEY_LOCATIONS)
			}
			batch?.filterIsInstance<Location>().orEmpty()
		} else {
			val single = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
				getParcelableExtra(LocationManager.KEY_LOCATION_CHANGED, Location::class.java)
			} else {
				getParcelableExtra(LocationManager.KEY_LOCATION_CHANGED)
			}
			listOfNotNull(single)
		}
	}
}

/** The received delivery is handed to the existing WAL before the receiver can acknowledge it. */
internal class PassiveLocationDeliveryAdmission @Inject constructor(
	private val startup: TrackingStartupGate,
	private val device: LocationDeviceStateProvider,
	private val registrations: SourceRegistrationRepository,
	private val sinkFactory: DurableSourceEventSinkFactory,
) {

	suspend fun admit(
		identity: PassiveLocationIdentity,
		locations: List<Location>,
		receivedElapsedNanos: Long,
		receivedWallTimeMs: Long,
	): PassiveLocationDeliveryOutcome {
		currentCoroutineContext().ensureActive()
		if (locations.isEmpty()) {
			return PassiveLocationDeliveryOutcome(0, 0)
		}
		if (startup.reconcileAdmission() !is TrackingAdmissionStartupResult.Ready ||
			!startup.permitsNewCollection || !permissionsAllowAdmission(device.snapshot())
		) {
			return PassiveLocationDeliveryOutcome(0, locations.size)
		}
		// Normalize the entire delivery once: chunk boundaries must not change source ordering,
		// exact duplicate removal, or the existing one-fix delivery identities.
		val normalized = normalizeLocationBatch(locations.map(::Location))
		return sinkFactory.withAtomicLocationAdmission { sink ->
			var admitted = 0
			var unverified = 0
			for (chunk in normalized.asSequence().chunked(WORK_CHUNK_SIZE)) {
				// Scheduling is cooperative; once the finite delivery is being committed, timeout
				// cancellation cannot leave an acknowledged prefix and an unsaved tail.
				yield()
				for (location in chunk) {
					if (admitLocation(identity, location, receivedElapsedNanos, receivedWallTimeMs, sink)) {
						admitted++
					} else {
						unverified++
					}
				}
			}
			PassiveLocationDeliveryOutcome(admitted, unverified)
		}
	}

	private suspend fun admitLocation(
		identity: PassiveLocationIdentity, location: Location, receivedElapsedNanos: Long,
		receivedWallTimeMs: Long, sink: SourceEventSink,
	): Boolean {
		currentCoroutineContext().ensureActive()
		if (!startup.permitsNewCollection || !permissionsAllowAdmission(device.snapshot())) {
			return false
		}
		val observed = location.qualifiedObservedElapsedRealtimeNanos(receivedElapsedNanos)
		if (observed == null || !location.isValidLocationEvidence()) {
			return false
		}
		val admission = qualifiedAdmission(identity, location, observed, receivedWallTimeMs) ?: return false
		val candidate = locationDeliveryCandidate(listOf(location), listOf(observed),
			receivedElapsedNanos, receivedWallTimeMs, admission.attribution, admission.approximate)
		return when (sink.admit(candidate)) {
			is SourceDeliveryAdmissionHandoff.Durable,
			is SourceDeliveryAdmissionHandoff.Duplicate -> true
			else -> false
		}
	}

	private suspend fun qualifiedAdmission(
		identity: PassiveLocationIdentity, location: Location, observed: Long, receivedWallTimeMs: Long,
	): PassiveLocationAdmission? {
		val admission = registrations.passiveLocationAdmissionAt(identity, observed) ?: return null
		if ((admission.approximate && !device.snapshot().coarsePermission) ||
			(!admission.approximate && !device.snapshot().finePermission)) {
			return null
		}
		if (startup.collectionResumeBoundary?.permitsObservation(identity.bootId, observed,
			location.time.takeIf { it > 0L } ?: receivedWallTimeMs) == false) {
			return null
		}
		return admission
	}

	private fun permissionsAllowAdmission(state: LocationDeviceState): Boolean =
		state.hasLocationPermission() &&
			(state.apiLevel < Build.VERSION_CODES.Q || state.backgroundLocationPermission)

	private companion object { const val WORK_CHUNK_SIZE = 64 }
}

internal data class PassiveLocationDeliveryOutcome(
	val admittedOrDuplicate: Int,
	val unverified: Int,
)
