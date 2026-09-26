package com.adsamcik.tracker.tracker.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.tracker.api.AmbientReconciliationIdentity
import com.adsamcik.tracker.tracker.api.AmbientTrackingSource
import com.adsamcik.tracker.tracker.resilience.TrackingLifecycleCommandAuthority
import com.adsamcik.tracker.tracker.source.control.LocationMotionScope
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
) {
	private val lock = Any()
	private val requests = mutableMapOf<String, Request>()
	private val _active = MutableStateFlow<BackgroundLocationHostResult.Active?>(null)
	val active = _active.asStateFlow()
	private var serviceOwner: Any? = null
	private var onRelease: (() -> Unit)? = null

	suspend fun acquire(identity: AmbientReconciliationIdentity): BackgroundLocationHostResult {
		if (identity.source != AmbientTrackingSource.LOCATION || !authorityCurrent(identity)) {
			return BackgroundLocationHostResult.Blocked("LOCATION_AUTHORITY_UNAVAILABLE")
		}
		synchronized(lock) { _active.value?.takeIf { it.identity == identity } }?.let { return it }
		val request = Request(identity, startup.currentGeneration, commands.latestStopGeneration())
		val ticket = UUID.randomUUID().toString()
		synchronized(lock) { requests[ticket] = request }
		var delivered = false
		return try {
			withContext(dispatchers.main) {
				ContextCompat.startForegroundService(context, Intent(context, TrackerService::class.java)
					.setAction(ACTION_ACQUIRE).putExtra(EXTRA_TICKET, ticket))
			}
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
				!delivered && _active.value?.identity == identity
			}
			if (abandoned) withContext(NonCancellable) { release(identity) }
		}
	}

	/** Exact identity comparison prevents a late predecessor close from releasing its successor. */
	suspend fun release(identity: AmbientReconciliationIdentity): Boolean {
		val callback = synchronized(lock) {
			requests.values.filter { it.identity == identity }.forEach {
				it.result.complete(BackgroundLocationHostResult.Blocked("LOCATION_HOST_LEASE_RELEASED"))
			}
			requests.entries.removeAll { it.value.identity == identity }
			if (_active.value?.identity != identity) return false
			_active.value = null
			onRelease
		}
		withContext(dispatchers.main) { callback?.invoke() }
		return true
	}

	internal fun attach(owner: Any, released: () -> Unit) = synchronized(lock) {
		serviceOwner = owner
		onRelease = released
	}

	internal fun detach(owner: Any): AmbientReconciliationIdentity? = synchronized(lock) {
		if (serviceOwner !== owner) return@synchronized null
		val retiring = _active.value?.identity
		serviceOwner = null
		onRelease = null
		_active.value = null
		requests.values.forEach { it.result.complete(BackgroundLocationHostResult.Blocked("LOCATION_HOST_DESTROYED")) }
		requests.clear()
		retiring
	}

	/** Only an in-process ticket survives to this method; Android redelivery cannot recreate it. */
	internal suspend fun acceptDelivery(
		ticket: String,
		owner: Any,
		scope: LocationMotionScope,
		promote: () -> Boolean,
	): Boolean {
		val request = synchronized(lock) { requests[ticket] } ?: return false
		val valid = startup.permitsNewCollection && startup.isReadyGeneration(request.startupGeneration) &&
			commands.latestStopGeneration() == request.stopGeneration && commands.latestUnhandledStop() == null &&
			authorityCurrent(request.identity)
		if (!valid || !promote()) {
			request.result.complete(BackgroundLocationHostResult.Blocked("LOCATION_HOST_AUTHORITY_OR_FOREGROUND_UNAVAILABLE"))
			return false
		}
		return synchronized(lock) {
			if (serviceOwner !== owner || requests[ticket] !== request || request.result.isCompleted ||
				!startup.permitsNewCollection || !startup.isReadyGeneration(request.startupGeneration) ||
				commands.latestStopGeneration() != request.stopGeneration
			) return@synchronized false
			val accepted = BackgroundLocationHostResult.Active(request.identity, scope)
			_active.value = accepted
			request.result.complete(accepted)
			true
		}
	}

	/** Used by the owning service before retaining its host after a live session ends. */
	internal suspend fun isCurrentLeaseValid(): Boolean = active.value?.let { authorityCurrent(it.identity) } == true

	private suspend fun authorityCurrent(identity: AmbientReconciliationIdentity): Boolean {
		if (!startup.permitsNewCollection || !startup.isReady || commands.latestUnhandledStop() != null ||
			lifecycle.snapshot().epoch != identity.collectedDataEpoch
		) return false
		return database.withTransaction {
			val dao = database.sourcePolicyDao()
			val pointer = dao.authority() ?: return@withTransaction false
			val policy = dao.policyAtRevision(identity.policyRevision, 1) ?: return@withTransaction false
			val consent = dao.latestConsentEpoch(1, SourceBrokerPurpose.AMBIENT_PRODUCT) ?: return@withTransaction false
			pointer.bootstrapState == SourcePolicyAuthorityEntity.STATE_ACTIVE &&
				pointer.currentPolicyRevision == identity.policyRevision && policy.balanceProfileVersion == 1 &&
				policy.locationBackgroundMode == 2 && policy.ambientPersistenceEligible &&
				policy.ambientConsentEpoch == identity.consentEpoch && consent.epoch == identity.consentEpoch &&
				consent.eligible && consent.persistenceEligible
		}
	}

	private class Request(val identity: AmbientReconciliationIdentity, val startupGeneration: Long, val stopGeneration: Long) {
		val result = CompletableDeferred<BackgroundLocationHostResult>()
	}

	internal companion object {
		const val ACTION_ACQUIRE = "com.adsamcik.tracker.action.ACQUIRE_BACKGROUND_LOCATION_HOST"
		const val EXTRA_TICKET = "background_location_host_ticket"
	}
}
