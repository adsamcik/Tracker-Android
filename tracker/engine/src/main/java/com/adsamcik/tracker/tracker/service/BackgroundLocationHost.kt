package com.adsamcik.tracker.tracker.service

import android.content.Context
import android.os.SystemClock
import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiAuthorityIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellAuthorityIntegrity
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.tracker.api.AmbientReconciliationIdentity
import com.adsamcik.tracker.tracker.api.AmbientTrackingSource
import com.adsamcik.tracker.tracker.api.TrackerServiceApi
import com.adsamcik.tracker.tracker.api.TrackingPurposeAvailabilityReporter
import com.adsamcik.tracker.tracker.resilience.TrackingLifecycleCommandAuthority
import com.adsamcik.tracker.tracker.source.control.LocationMotionScope
import com.adsamcik.tracker.tracker.source.model.SourceKind
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

sealed interface BackgroundLocationHostResult {
	data class Active(val identity: AmbientReconciliationIdentity, val scope: LocationMotionScope) : BackgroundLocationHostResult
	data class Blocked(val reason: String) : BackgroundLocationHostResult
}

/** A transient foreground host lease. This neither creates a session nor grants provider authority. */
@Singleton
class BackgroundLocationHost @Inject constructor(
	@ApplicationContext private val context: Context,
	private val database: AppDatabase,
	private val lifecycle: CollectedDataLifecycleStore,
	private val startup: TrackingStartupGate,
	private val commands: TrackingLifecycleCommandAuthority,
	private val dispatchers: DispatchersProvider,
	private val availabilityReporter: TrackingPurposeAvailabilityReporter? = null,
) {
	private val lock = Any()
	private val requests = mutableMapOf<String, Request>()
	private val _active = MutableStateFlow<BackgroundLocationHostResult.Active?>(null)
	private val _leases = MutableStateFlow<Map<AmbientTrackingSource, BackgroundLocationHostResult.Active>>(emptyMap())
	val leases = _leases.asStateFlow()
	val hasActiveSources: Boolean get() = leases.value.isNotEmpty()
	val hostedSources: Set<SourceKind> get() = leases.value.keys.mapNotNull { it.hostSourceKind() }.toSet()
	/** Location-only view retained for the existing Location owner and motion helper. */
	val active = _active.asStateFlow()
	private var serviceOwner: Any? = null
	private val activeTickets = mutableMapOf<AmbientTrackingSource, String>()
	private var onRelease: (() -> Unit)? = null

	suspend fun acquire(identity: AmbientReconciliationIdentity): BackgroundLocationHostResult {
		if (identity.source.hostSourceKind() == null || !authorityCurrent(identity)) {
			return BackgroundLocationHostResult.Blocked("LOCATION_AUTHORITY_UNAVAILABLE")
		}
		synchronized(lock) { _leases.value[identity.source]?.takeIf { it.identity == identity } }?.let { return it }
		val request = Request(identity, startup.currentGeneration, commands.latestStopGeneration(),
			synchronized(lock) { _leases.value[identity.source]?.identity })
		val ticket = UUID.randomUUID().toString()
		synchronized(lock) { requests[ticket] = request }
		var delivered = false
		return try {
			val enqueued = withContext(dispatchers.main) {
				TrackerServiceApi.enqueueBackgroundLocationHost(context, ticket)
			}
			if (!enqueued) return BackgroundLocationHostResult.Blocked("LOCATION_FOREGROUND_START_UNAVAILABLE")
			val result = withTimeoutOrNull(8_000L) { request.result.await() }
				?: BackgroundLocationHostResult.Blocked("LOCATION_HOST_ACK_TIMEOUT")
			delivered = result is BackgroundLocationHostResult.Active
			result
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: Exception) {
			BackgroundLocationHostResult.Blocked("LOCATION_FOREGROUND_START_UNAVAILABLE")
		} finally {
			val abandoned = synchronized(lock) {
				requests.remove(ticket)
				!delivered && activeTickets[identity.source] == ticket && _leases.value[identity.source]?.identity == identity
			}
			if (abandoned) withContext(NonCancellable) { releaseIfOwned(identity, expectedTicket = ticket) }
		}
	}

	/** Exact identity comparison prevents a late predecessor close from releasing its successor. */
	suspend fun release(identity: AmbientReconciliationIdentity): Boolean = releaseIfOwned(identity)

	private suspend fun releaseIfOwned(
		identity: AmbientReconciliationIdentity,
		expectedTicket: String? = null,
	): Boolean {
		val callback = synchronized(lock) {
			// Abandonment releases only its accepted ticket, atomically with clearing the lease.
			// Public purpose retirement still fences every pending delivery for that exact identity.
			if (expectedTicket != null && activeTickets[identity.source] != expectedTicket) return false
			if (expectedTicket == null) {
				requests.values.filter { it.identity == identity }.forEach {
					it.result.complete(BackgroundLocationHostResult.Blocked("LOCATION_HOST_LEASE_RELEASED"))
				}
				requests.entries.removeAll { it.value.identity == identity }
			}
			if (_leases.value[identity.source]?.identity != identity) return false
			_leases.value = _leases.value - identity.source
			_active.value = _leases.value[AmbientTrackingSource.LOCATION]
			activeTickets.remove(identity.source)
			if (identity.source != AmbientTrackingSource.LOCATION) {
				availabilityReporter?.reportAmbientForegroundHostLost(identity.purposeLeaseIdentity)
			}
			onRelease
		}
		withContext(dispatchers.main) { callback?.invoke() }
		return true
	}

	internal fun attach(owner: Any, released: () -> Unit) = synchronized(lock) {
		check(serviceOwner == null || serviceOwner === owner) { "Collection host already has a service owner" }
		serviceOwner = owner
		onRelease = released
	}

	internal fun detach(owner: Any): AmbientReconciliationIdentity? =
		detachAll(owner).firstOrNull { it.source == AmbientTrackingSource.LOCATION }

	internal fun detachAll(owner: Any): List<AmbientReconciliationIdentity> = synchronized(lock) {
		if (serviceOwner !== owner) return@synchronized emptyList()
		val retiring = _leases.value.values.map { it.identity }
		retiring.filter { it.source != AmbientTrackingSource.LOCATION }.forEach {
			availabilityReporter?.reportAmbientForegroundHostLost(it.purposeLeaseIdentity)
		}
		serviceOwner = null
		onRelease = null
		_active.value = null
		_leases.value = emptyMap()
		activeTickets.clear()
		requests.values.forEach { it.result.complete(BackgroundLocationHostResult.Blocked("LOCATION_HOST_DESTROYED")) }
		requests.clear()
		retiring
	}

	/** Only an in-process ticket survives to this method; Android redelivery cannot recreate it. */
	internal suspend fun acceptDelivery(
		ticket: String,
		owner: Any,
		scope: LocationMotionScope,
		promote: (Set<SourceKind>) -> Boolean,
	): Boolean {
		val request = synchronized(lock) { requests[ticket] } ?: return false
		val remainingMillis = ((request.foregroundDeadlineNanos - SystemClock.elapsedRealtimeNanos()) /
			1_000_000L).coerceAtMost(PRE_FOREGROUND_START_BUDGET_MILLIS)
		val promoted = if (remainingMillis <= 0L) false else withTimeoutOrNull(remainingMillis) {
			val valid = startup.permitsNewCollection && startup.isReadyGeneration(request.startupGeneration) &&
				commands.latestStopGeneration() == request.stopGeneration && commands.latestUnhandledStop() == null &&
				authorityCurrent(request.identity)
			if (!valid) false else withContext(dispatchers.main) {
				val stillOwnsDelivery = synchronized(lock) {
					serviceOwner === owner && requests[ticket] === request && !request.result.isCompleted
				}
				stillOwnsDelivery && SystemClock.elapsedRealtimeNanos() < request.foregroundDeadlineNanos &&
					startup.permitsNewCollection && startup.isReadyGeneration(request.startupGeneration) &&
					commands.latestStopGeneration() == request.stopGeneration &&
					promote(hostedSources + requireNotNull(request.identity.source.hostSourceKind()))
			}
		} == true
		if (!promoted) {
			request.result.complete(BackgroundLocationHostResult.Blocked("LOCATION_HOST_AUTHORITY_OR_FOREGROUND_UNAVAILABLE"))
			return false
		}
		return synchronized(lock) {
			val currentIdentity = _leases.value[request.identity.source]?.identity
			if (serviceOwner !== owner || requests[ticket] !== request || request.result.isCompleted ||
				!startup.permitsNewCollection || !startup.isReadyGeneration(request.startupGeneration) ||
				commands.latestStopGeneration() != request.stopGeneration ||
				(currentIdentity != request.previousIdentity && currentIdentity != request.identity)
			) return@synchronized false
			val accepted = BackgroundLocationHostResult.Active(request.identity, scope)
			_leases.value = _leases.value + (request.identity.source to accepted)
			_active.value = _leases.value[AmbientTrackingSource.LOCATION]
			activeTickets[request.identity.source] = ticket
			request.result.complete(accepted)
			true
		}
	}

	/** Used by the owning service before retaining its host after a live session ends. */
	internal suspend fun isCurrentLeaseValid(): Boolean {
		for (lease in leases.value.values) {
			if (!authorityCurrent(lease.identity)) release(lease.identity)
		}
		return hasActiveSources
	}

	/** A failed optional host leaves authorized passive callbacks available. */
	suspend fun reconcileRadio(identity: AmbientReconciliationIdentity, providerReady: Boolean): Boolean {
		require(identity.source == AmbientTrackingSource.WIFI || identity.source == AmbientTrackingSource.CELL)
		if (!providerReady) {
			releaseRadio(identity)
			return releaseInvalidRadioHost(identity.source)
		}
		if (!authorityCurrent(identity, requireHostingChoice = false)) return false
		val requested = database.sourcePolicyDao().policyAtRevision(identity.policyRevision,
			requireNotNull(identity.source.hostSourceKind()).stableCode)?.backgroundNotificationHostEnabled == true
		if (!requested) {
			releaseRadio(identity)
			return releaseInvalidRadioHost(identity.source)
		}
		return acquire(identity) is BackgroundLocationHostResult.Active
	}

	private suspend fun releaseInvalidRadioHost(source: AmbientTrackingSource): Boolean {
		val previous = leases.value[source] ?: return true
		if (!authorityCurrent(previous.identity)) release(previous.identity)
		return leases.value[source]?.identity != previous.identity || authorityCurrent(previous.identity)
	}

	/** Exact retirement never releases a successor or a independently hosted sibling. */
	suspend fun releaseRadio(identity: AmbientReconciliationIdentity?): Boolean {
		if (identity == null) return true
		// Also fence an exact pending delivery, even before it has acquired an active lease.
		val released = release(identity)
		return released || leases.value[identity.source]?.identity != identity
	}

	/** Called only after the caller has fenced this source for deletion or authority recovery. */
	suspend fun releaseRadioSource(source: AmbientTrackingSource): Boolean {
		require(source == AmbientTrackingSource.WIFI || source == AmbientTrackingSource.CELL)
		val identities = synchronized(lock) {
			(requests.values.filter { it.identity.source == source }.map { it.identity } +
				listOfNotNull(_leases.value[source]?.identity)).distinct()
		}
		var complete = true
		for (identity in identities) if (!releaseRadio(identity)) complete = false
		return complete
	}

	private suspend fun authorityCurrent(identity: AmbientReconciliationIdentity, requireHostingChoice: Boolean = true): Boolean {
		val lifecycleSnapshot = lifecycle.snapshot()
		if (!startup.permitsNewCollection || !startup.isReady || commands.latestUnhandledStop() != null ||
			lifecycleSnapshot.epoch != identity.collectedDataEpoch || lifecycleSnapshot.retainedFromMs != identity.retainedFromMs
		) return false
		return database.withTransaction {
			val kind = identity.source.hostSourceKind()?.stableCode ?: return@withTransaction false
			val dao = database.sourcePolicyDao()
			val pointer = dao.authority() ?: return@withTransaction false
			val policy = dao.policyAtRevision(identity.policyRevision, kind) ?: return@withTransaction false
			val consent = dao.latestConsentEpoch(kind, SourceBrokerPurpose.AMBIENT_PRODUCT) ?: return@withTransaction false
			val hostAllowed = if (identity.source == AmbientTrackingSource.LOCATION) {
				policy.balanceProfileVersion == 1 && policy.locationBackgroundMode == 2
			} else !requireHostingChoice || policy.backgroundNotificationHostEnabled
			val ownerCurrent = when (identity.source) {
				AmbientTrackingSource.WIFI -> database.ambientWifiFactDao().latestAuthority()?.let {
					AmbientWifiAuthorityIntegrity.isAuthentic(it) && it.isActive &&
						it.sourcePolicyRevision == identity.policyRevision && it.ambientConsentEpoch == identity.consentEpoch &&
						it.collectedDataEpoch == identity.collectedDataEpoch && it.rolloutRevision == identity.rolloutRevision &&
						it.ownerCasToken == identity.ownerCasToken && it.writerOwnerGeneration == identity.executionRevision &&
						it.retentionPolicyId == identity.retentionPolicyId &&
						it.retentionApprovalRevision == identity.retentionApprovalRevision
				} == true
				AmbientTrackingSource.CELL -> database.ambientCellFactDao().latestAuthority()?.let {
					AmbientCellAuthorityIntegrity.isAuthentic(it) && it.isActive &&
						it.sourcePolicyRevision == identity.policyRevision && it.ambientConsentEpoch == identity.consentEpoch &&
						it.collectedDataEpoch == identity.collectedDataEpoch && it.rolloutRevision == identity.rolloutRevision &&
						it.ownerCasToken == identity.ownerCasToken && it.writerOwnerGeneration == identity.executionRevision &&
						it.retentionPolicyId == identity.retentionPolicyId &&
						it.retentionApprovalRevision == identity.retentionApprovalRevision
				} == true
				AmbientTrackingSource.LOCATION -> true
				AmbientTrackingSource.STEPS -> false
			}
			pointer.bootstrapState == SourcePolicyAuthorityEntity.STATE_ACTIVE &&
				pointer.currentPolicyRevision == identity.policyRevision && hostAllowed && ownerCurrent && policy.ambientPersistenceEligible &&
				policy.ambientConsentEpoch == identity.consentEpoch && consent.epoch == identity.consentEpoch &&
				consent.eligible && consent.persistenceEligible
		}
	}

	private class Request(val identity: AmbientReconciliationIdentity, val startupGeneration: Long, val stopGeneration: Long,
		val previousIdentity: AmbientReconciliationIdentity?) {
		val foregroundDeadlineNanos = SystemClock.elapsedRealtimeNanos() +
			PRE_FOREGROUND_START_BUDGET_MILLIS * 1_000_000L
		val result = CompletableDeferred<BackgroundLocationHostResult>()
	}

}

private fun AmbientTrackingSource.hostSourceKind(): SourceKind? = when (this) {
	AmbientTrackingSource.LOCATION -> SourceKind.LOCATION
	AmbientTrackingSource.WIFI -> SourceKind.WIFI
	AmbientTrackingSource.CELL -> SourceKind.CELL
	AmbientTrackingSource.STEPS -> null
}
