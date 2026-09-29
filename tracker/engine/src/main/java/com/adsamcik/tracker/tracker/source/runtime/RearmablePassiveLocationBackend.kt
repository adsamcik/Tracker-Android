package com.adsamcik.tracker.tracker.source.runtime

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import com.adsamcik.tracker.tracker.source.model.LocationBackend
import com.adsamcik.tracker.tracker.source.model.LocationMode
import com.adsamcik.tracker.tracker.source.model.LocationPlan
import com.adsamcik.tracker.tracker.source.model.physicalConfigurationFingerprint
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/** Filter identity is reconstructed from durable registration, never from provider result extras. */
internal data class PassiveLocationIdentity(
	val backend: LocationBackend,
	val sourceInstanceId: String,
	val generation: Long,
	val bootId: String,
	val fingerprint: String,
	val collectedDataEpoch: Long,
) {
	init {
		require(sourceInstanceId.isNotBlank() && generation > 0L && bootId.isNotBlank())
		require(fingerprint.matches(Regex("[0-9a-f]{64}")) && collectedDataEpoch >= 0L)
	}

	fun uri(): Uri = Uri.Builder().scheme(SCHEME).authority(AUTHORITY)
		.appendPath(VERSION).appendPath(backend.name).appendPath(sourceInstanceId)
		.appendPath(generation.toString()).appendPath(bootId).appendPath(fingerprint)
		.appendPath(collectedDataEpoch.toString()).build()

	companion object {
		const val ACTION = "com.adsamcik.tracker.LOCATION_PASSIVE_DELIVERY"
		private const val SCHEME = "tracker"
		private const val AUTHORITY = "passive-location"
		private const val VERSION = "v1"

		fun fromUri(uri: Uri?): PassiveLocationIdentity? = runCatching {
			val candidate = uri ?: return null
			if (candidate.scheme != SCHEME || candidate.authority != AUTHORITY ||
				candidate.pathSegments.size != 7 || candidate.pathSegments[0] != VERSION ||
				candidate.query != null || candidate.fragment != null) return null
			PassiveLocationIdentity(
				backend = LocationBackend.valueOf(candidate.pathSegments[1]),
				sourceInstanceId = candidate.pathSegments[2],
				generation = candidate.pathSegments[3].toLong(),
				bootId = candidate.pathSegments[4],
				fingerprint = candidate.pathSegments[5],
				collectedDataEpoch = candidate.pathSegments[6].toLong(),
			)
		}.getOrNull()
	}
}

internal data class PassiveLocationOperations(
	val requestFused: suspend (LocationPlan, PendingIntent) -> Unit,
	val removeFused: suspend (PendingIntent) -> Unit,
	val requestFramework: suspend (LocationPlan, PendingIntent) -> Unit,
	val removeFramework: suspend (PendingIntent) -> Unit,
	val frameworkPassiveAvailable: () -> Boolean,
)

/** The one shared runtime selects this backend only for an authorized passive system plan. */
@Singleton
internal class RearmablePassiveLocationBackend internal constructor(
	private val context: Context,
	private val operations: PassiveLocationOperations,
) : LocationSourceBackendController {
	@Inject constructor(@ApplicationContext context: Context) : this(context, androidOperations(context))

	private var prepared: PassiveLocationIdentity? = null
	private var retained: PassiveLocationIdentity? = null
	override val backend: LocationBackend get() = retained?.backend ?: prepared?.backend ?: LocationBackend.FUSED
	override val batchingSupported: Boolean get() = false
	override val flushSupported: Boolean get() = false
	override val hasRetainedRegistration: Boolean get() = retained != null
	internal fun retains(identity: PassiveLocationIdentity): Boolean = retained == identity

	fun prepare(identity: PassiveLocationIdentity) {
		check(retained == null) { "A passive provider must retire before replacement" }
		prepared = identity
	}

	override fun resolvePlan(plan: LocationPlan): LocationPlan? = plan.takeIf {
		it.mode == LocationMode.PASSIVE && it.passiveSystemDelivery &&
			(it.backend != LocationBackend.FRAMEWORK || operations.frameworkPassiveAvailable())
	}

	override suspend fun start(plan: LocationPlan, onLocations: (List<Location>) -> Unit): LocationBackendStartOutcome {
		@Suppress("UNUSED_VARIABLE") val providerResultsArriveAtReceiver = onLocations
		val identity = prepared ?: return LocationBackendStartOutcome.CLEANUP_REQUIRED
		if (retained != null) return LocationBackendStartOutcome.BLOCKED_BY_RETAINED_REGISTRATION
		check(plan.physicalConfigurationFingerprint() == identity.fingerprint && plan.backend == identity.backend)
		val pendingIntent = runCatching { pendingIntent(identity, create = true) }.getOrNull()
			?: return LocationBackendStartOutcome.CLEANUP_REQUIRED
		// A throwing/ambiguous provider call may have registered the request. Keep the exact handle.
		retained = identity
		return try {
			when (identity.backend) {
				LocationBackend.FUSED -> operations.requestFused(plan, pendingIntent)
				LocationBackend.FRAMEWORK -> operations.requestFramework(plan, pendingIntent)
			}
			LocationBackendStartOutcome.STARTED
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: Exception) {
			LocationBackendStartOutcome.CLEANUP_REQUIRED
		}
	}

	override suspend fun flush(): ProviderFlushOutcome = ProviderFlushOutcome.NOT_REQUESTED
	override suspend fun stop(): RegistrationRemovalOutcome {
		val identity = retained ?: return RegistrationRemovalOutcome.NOT_REGISTERED
		val result = removeExact(identity)
		if (result in setOf(RegistrationRemovalOutcome.REMOVED, RegistrationRemovalOutcome.NOT_REGISTERED)) {
			retained = null
			prepared = null
		}
		return result
	}

	/** Also removes a same-boot handle after process death, when no Kotlin callback object survives. */
	suspend fun removeExact(identity: PassiveLocationIdentity): RegistrationRemovalOutcome {
		val pendingIntent = runCatching { pendingIntent(identity, create = false) }
			.getOrElse { return RegistrationRemovalOutcome.FAILED }
			?: return RegistrationRemovalOutcome.NOT_REGISTERED.also {
				if (retained == identity) { retained = null; prepared = null }
			}
		return try {
			when (identity.backend) {
				LocationBackend.FUSED -> operations.removeFused(pendingIntent)
				LocationBackend.FRAMEWORK -> operations.removeFramework(pendingIntent)
			}
			pendingIntent.cancel()
			if (retained == identity) { retained = null; prepared = null }
			RegistrationRemovalOutcome.REMOVED
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: Exception) {
			RegistrationRemovalOutcome.FAILED
		}
	}

	private fun pendingIntent(identity: PassiveLocationIdentity, create: Boolean): PendingIntent? {
		val intent = Intent(PassiveLocationIdentity.ACTION).setClass(context, PassiveLocationReceiver::class.java)
			.setData(identity.uri())
		val flags = PendingIntent.FLAG_MUTABLE or
			(if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE)
		return PendingIntent.getBroadcast(context, 0, intent, flags)
	}
}

private fun androidOperations(context: Context): PassiveLocationOperations {
	val fused = LocationServices.getFusedLocationProviderClient(context)
	val framework = context.getSystemService(LocationManager::class.java)
	return PassiveLocationOperations(
		requestFused = { plan, pending ->
			val request = LocationRequest.Builder(plan.requestedIntervalMs.coerceAtLeast(1L))
				.setPriority(Priority.PRIORITY_PASSIVE)
				.setMinUpdateIntervalMillis(plan.minimumUpdateIntervalMs.coerceAtLeast(0L))
				.setMinUpdateDistanceMeters(plan.minimumDisplacementMeters.coerceAtLeast(0f))
				.setMaxUpdateDelayMillis(plan.maximumBatchDelayMs.coerceAtLeast(0L)).build()
			fused.requestLocationUpdates(request, pending).await()
		},
		removeFused = { pending -> fused.removeLocationUpdates(pending).await() },
		requestFramework = { plan, pending ->
			@Suppress("DEPRECATION")
			framework.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER,
				plan.requestedIntervalMs.coerceAtLeast(0L), plan.minimumDisplacementMeters.coerceAtLeast(0f),
				pending)
		},
		removeFramework = { pending -> framework.removeUpdates(pending) },
		frameworkPassiveAvailable = { framework.getProvider(LocationManager.PASSIVE_PROVIDER) != null },
	)
}
