package com.adsamcik.tracker.activity.api.registration

import android.content.Context
import android.os.SystemClock
import androidx.room.withTransaction
import com.adsamcik.tracker.activity.api.backend.GmsActivityRecognitionBackend
import com.adsamcik.tracker.activity.api.backend.RecognitionConfig
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceRegistrationStateEntity
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull
import com.adsamcik.tracker.shared.base.di.ApplicationScope
import com.adsamcik.tracker.shared.base.extension.hasActivityPermission
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.base.time.BootClockDomainProvider
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Serializes and fences every physical GMS activity-recognition registration. */
@Singleton
class DefaultActivityRegistrationArbiter @Inject constructor(
	@ApplicationContext private val context: Context,
	private val bootClockDomainProvider: BootClockDomainProvider,
	private val database: AppDatabase,
	private val lifecycleStore: CollectedDataLifecycleStore,
	private val backend: GmsActivityRecognitionBackend,
	private val startupGateProvider: Provider<TrackingStartupGate>,
	private val cleanupStore: ActivityRegistrationCleanupStore,
	private val cleanupScheduler: ActivityRegistrationCleanupScheduler,
	@ApplicationScope appScope: CoroutineScope,
) : ActivityRegistrationArbiter {
	private val mutex = Mutex()
	private val demands = mutableMapOf<ActivityRegistrationOwner, ActivityRegistrationDemand>()
	@Volatile private var current = EMPTY_SNAPSHOT
	private var deletionPaused = false

	init {
		// This one-time unique job also discovers and removes the released-v27 static PendingIntent.
		// The journal is outside Room, so scheduling is safe before tracking storage is ready.
		ensureCleanupRetryScheduled()
		// Session manifests may add/remove Activity as CONTROL without starting ActivitySourceRuntime.
		// Observe the durable authority so an unchanged physical request is still rotated onto the
		// exact new purpose/consent vector. Do not subscribe to Room until storage and released-v27
		// recovery are safe: this singleton can be constructed during cold service injection.
		appScope.launch {
			val startupGate = startupGateProvider.get()
			if (startupGate.reconcile() !is TrackingStartupResult.Ready) startupGate.awaitReady()
			database.invalidationTracker
				.createFlow("source_demand", emitInitialState = true)
				.collect { reconcileDurableDemands() }
		}
	}

	override suspend fun setDemand(
		owner: ActivityRegistrationOwner,
		demand: ActivityRegistrationDemand,
	): ActivityRegistrationResult = mutex.withLock {
		if (demand.enabled) demands[owner] = demand else demands.remove(owner)
		reconcileLocked()
	}

	override suspend fun clearDemand(owner: ActivityRegistrationOwner): ActivityRegistrationResult = mutex.withLock {
		demands.remove(owner)
		reconcileLocked()
	}

	override suspend fun reconcileDurableDemands(): ActivityRegistrationResult = mutex.withLock {
		reconcileLocked()
	}

	override suspend fun closeForCollectedDataDeletion(): ActivityRegistrationResult = mutex.withLock {
		deletionPaused = true
		// A session cannot survive full collected-data deletion. The application monitor remains a
		// desired owner and will be installed with a fresh identity after the deletion commits.
		demands.remove(ActivityRegistrationOwner.ACTIVE_SESSION)
		demands.remove(ActivityRegistrationOwner.LEGACY_REQUEST_MANAGER)
		val stopped = fenceAndRemoveLocked(clearOwners = false)
		if (stopped.status == ActivityRegistrationStatus.FAILED ||
			stopped.status == ActivityRegistrationStatus.BLOCKED
		) return@withLock stopped

		val pendingRows = try {
			database.sourceBrokerDao().pendingProviderRemovals(ACTIVITY_SOURCE_KIND)
		} catch (error: CancellationException) {
			throw error
		} catch (_: Exception) {
			return@withLock failure(
				ActivityRegistrationStatus.FAILED,
				ActivityRegistrationFailureCode.STORAGE_UNAVAILABLE,
				true,
			)
		}
		if (pendingRows.isNotEmpty()) {
			try {
				cleanupStore.addPending(pendingRows.map { it.cleanupKey() })
			} catch (error: ActivityRegistrationCleanupStoreException) {
				return@withLock failure(
					ActivityRegistrationStatus.FAILED,
					if (error.corrupt) {
						ActivityRegistrationFailureCode.PROVIDER_CLEANUP_STATE_INVALID
					} else {
						ActivityRegistrationFailureCode.STORAGE_UNAVAILABLE
					},
					retryable = !error.corrupt,
				)
			}
		}

		when (val cleanup = retryPendingProviderCleanupLocked()) {
			ActivityProviderCleanupResult.COMPLETE -> applied(current)
			else -> if (cleanup.retryable) {
				ActivityRegistrationResult(
					status = ActivityRegistrationStatus.DEGRADED,
					snapshot = current,
					failureCode = ActivityRegistrationFailureCode.PROVIDER_REMOVAL_FAILED,
					retryable = true,
				)
			} else {
				failure(
					ActivityRegistrationStatus.FAILED,
					cleanup.failureCode
						?: ActivityRegistrationFailureCode.PROVIDER_CLEANUP_STATE_INVALID,
					false,
				)
			}
		}
	}

	override suspend fun resumeAfterCollectedDataDeletion(): ActivityRegistrationResult = mutex.withLock {
		deletionPaused = false
		reconcileLocked()
	}

	override suspend fun retryPendingProviderCleanup(): ActivityProviderCleanupResult = mutex.withLock {
		retryPendingProviderCleanupLocked()
	}

	override fun snapshot(): ActivityRegistrationSnapshot = current

	private suspend fun reconcileLocked(): ActivityRegistrationResult {
		val combined = combineDemands()
		if (deletionPaused) return applied(current)
		if (combined.enabled && !startupGateProvider.get().isReady) {
			// Do not perform enabled storage reconciliation or provider registration while process-wide
			// recovery is closed. Cleanup remains legal, so fence an already active process-local
			// registration before retaining the logical owners for a later Ready reconciliation.
			if (current.active) {
				val stopped = fenceAndRemoveLocked(clearOwners = false)
				if (stopped.status != ActivityRegistrationStatus.APPLIED) return stopped
			}
			current = current.copy(owners = combined.owners)
			return failure(
				ActivityRegistrationStatus.BLOCKED,
				ActivityRegistrationFailureCode.STARTUP_RECOVERY_NOT_READY,
				true,
			)
		}
		if (combined.enabled) {
			val cleanup = retryPendingProviderCleanupLocked()
			if (!cleanup.complete) {
				return failure(
					ActivityRegistrationStatus.BLOCKED,
					if (cleanup.failureCode == ActivityRegistrationFailureCode.PROVIDER_CLEANUP_STATE_INVALID) {
						ActivityRegistrationFailureCode.PROVIDER_CLEANUP_STATE_INVALID
					} else {
						ActivityRegistrationFailureCode.PROVIDER_CLEANUP_PENDING
					},
					cleanup.retryable,
				)
			}
		}
		hydratePersistedIdentityIfNeeded()
		val cleanupComplete = cleanupRetiringRegistrationsLocked()
		if (!combined.enabled) {
			val stopped = fenceAndRemoveLocked(clearOwners = true)
			return if (!cleanupComplete && stopped.status == ActivityRegistrationStatus.APPLIED) {
				ActivityRegistrationResult(
					ActivityRegistrationStatus.DEGRADED,
					stopped.snapshot,
					ActivityRegistrationFailureCode.PROVIDER_REMOVAL_FAILED,
					retryable = true,
				)
			} else {
				stopped
			}
		}
		if (!cleanupComplete) {
			return failure(
				if (current.active) ActivityRegistrationStatus.DEGRADED else ActivityRegistrationStatus.FAILED,
				ActivityRegistrationFailureCode.PROVIDER_REMOVAL_FAILED,
				true,
			)
		}
		val durableEligibility = database.sourceBrokerDao().authorizationDemands(ACTIVITY_SOURCE_KIND)
		if (durableEligibility.isEmpty()) {
			fenceAndRemoveLocked(clearOwners = false)
			return failure(
				ActivityRegistrationStatus.BLOCKED,
				ActivityRegistrationFailureCode.MISSING_DURABLE_DEMAND,
				false,
			)
		}
		val eligibilityMask = SourceBrokerAuthorization.purposeMask(durableEligibility)
		if (eligibilityMask == 0L) {
			fenceAndRemoveLocked(clearOwners = false)
			return failure(
				ActivityRegistrationStatus.BLOCKED,
				ActivityRegistrationFailureCode.MISSING_DURABLE_DEMAND,
				false,
			)
		}
		if (!context.hasActivityPermission) {
			return failure(ActivityRegistrationStatus.BLOCKED, ActivityRegistrationFailureCode.PERMISSION_MISSING, false)
		}
		if (!backend.isAvailable) {
			return failure(ActivityRegistrationStatus.BLOCKED, ActivityRegistrationFailureCode.PROVIDER_UNAVAILABLE, true)
		}
		val physicalConfigurationFingerprint = combined.physicalConfigurationFingerprint()
		val lifecycle = try {
			lifecycleStore.snapshot()
		} catch (error: CancellationException) {
			throw error
		} catch (_: Exception) {
			return failure(
				ActivityRegistrationStatus.FAILED,
				ActivityRegistrationFailureCode.STORAGE_UNAVAILABLE,
				true,
			)
		}
		if (current.active && current.matches(
				combined,
				physicalConfigurationFingerprint,
				bootClockDomainProvider.current(),
				lifecycle.epoch,
			)
		) {
			try {
				rotateAuthorization(
					requireNotNull(current.identity),
					durableEligibility,
					System.currentTimeMillis(),
					SystemClock.elapsedRealtimeNanos(),
				)
			} catch (error: CancellationException) {
				throw error
			} catch (_: Exception) {
				return failure(
					ActivityRegistrationStatus.FAILED,
					ActivityRegistrationFailureCode.STORAGE_UNAVAILABLE,
					true,
				)
			}
			current = current.copy(owners = combined.owners)
			return applied(current)
		}

		val reservation = try {
			reserveIdentity(combined, physicalConfigurationFingerprint)
		} catch (error: CancellationException) {
			throw error
		} catch (_: Exception) {
			return failure(ActivityRegistrationStatus.FAILED, ActivityRegistrationFailureCode.STORAGE_UNAVAILABLE, true)
		}
		val identity = reservation.identity
		val registered = backend.applyRegistration(
			RecognitionConfig(
				intervalSeconds = combined.intervalSeconds ?: 0,
				requestedTransitions = combined.transitions,
				automaticRecognitionEligible = combined.automaticRecognitionEligible,
				automaticTransitions = combined.automaticTransitions,
			),
			identity,
		)
		if (!registered) {
			try {
				markRegistrationFailed(identity, "PROVIDER_REGISTRATION_FAILED")
			} catch (error: CancellationException) {
				throw error
			} catch (_: Exception) {
				// The unaccepted reservation remains non-authoritative and recoverable.
			}
			return failure(
				if (current.active) ActivityRegistrationStatus.DEGRADED else ActivityRegistrationStatus.FAILED,
				ActivityRegistrationFailureCode.PROVIDER_REGISTRATION_FAILED,
				true,
			)
		}
		val previous = try {
			withContext(NonCancellable) {
				database.sourceBrokerDao().acceptReservedReplacement(
					reservedState = reservation.state,
					expectedPointerGeneration = reservation.predecessorState?.registrationGeneration,
					expectedPointerInstanceId = reservation.predecessorState?.sourceInstanceId,
					requiredAuthorizationFingerprint = null,
					acceptedAtMs = System.currentTimeMillis(),
					acceptedElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
				)
			}
		} catch (error: CancellationException) {
			throw error
		} catch (_: Exception) {
			removeProviderOnly(identity, nonCancellable = true)
			try {
				markRegistrationFailed(identity, "ACTIVATION_STORAGE_FAILED")
			} catch (error: CancellationException) {
				throw error
			} catch (_: Exception) {
				// The reservation remains non-authoritative and is safe to recover or fail later.
			}
			return failure(
				if (current.active) ActivityRegistrationStatus.DEGRADED else ActivityRegistrationStatus.FAILED,
				ActivityRegistrationFailureCode.STORAGE_UNAVAILABLE,
				true,
			)
		}

		current = ActivityRegistrationSnapshot(
			active = true,
			identity = identity,
			owners = combined.owners,
			continuousRecognitionIntervalSeconds = combined.intervalSeconds,
			transitions = combined.transitions,
		)
		val removalFailed = previous != null &&
			!removeAndCompleteRegistration(previous, nonCancellable = true)
		currentCoroutineContext().ensureActive()
		if (removalFailed) {
			return ActivityRegistrationResult(
				ActivityRegistrationStatus.DEGRADED,
				current,
				ActivityRegistrationFailureCode.PROVIDER_REMOVAL_FAILED,
				retryable = true,
			)
		}
		return applied(current)
	}

	private suspend fun fenceAndRemoveLocked(clearOwners: Boolean): ActivityRegistrationResult {
		hydratePersistedIdentityIfNeeded()
		if (!current.active) {
			current = current.copy(owners = if (clearOwners) emptySet() else demands.keys.toSet())
			return applied(current)
		}
		val oldIdentity = current.identity
		if (oldIdentity != null) {
			try {
				retireRegistration(oldIdentity, "DEMAND_REMOVED")
			} catch (error: CancellationException) {
				throw error
			} catch (_: Exception) {
				return failure(ActivityRegistrationStatus.FAILED, ActivityRegistrationFailureCode.STORAGE_UNAVAILABLE, true)
			}
		}
		current = ActivityRegistrationSnapshot(
			active = false,
			identity = oldIdentity,
			owners = if (clearOwners) emptySet() else demands.keys.toSet(),
			continuousRecognitionIntervalSeconds = null,
			transitions = emptySet(),
		)
		if (oldIdentity == null) return applied(current)
		val retiring = requireNotNull(
			database.sourceBrokerDao().registration(
				ACTIVITY_SOURCE_KIND,
				oldIdentity.registrationGeneration,
			),
		)
		return if (removeAndCompleteRegistration(retiring, nonCancellable = true)) {
			applied(current)
		} else {
			ActivityRegistrationResult(
				ActivityRegistrationStatus.DEGRADED,
				current,
				ActivityRegistrationFailureCode.PROVIDER_REMOVAL_FAILED,
				retryable = true,
			)
		}
	}

	private suspend fun reserveIdentity(
		combined: CombinedDemand,
		physicalConfigurationFingerprint: String,
	): ReservedActivityRegistration {
		val lifecycle = lifecycleStore.snapshot()
		val clockDomainId = bootClockDomainProvider.current()
		val nowMs = System.currentTimeMillis()
		val nowElapsed = SystemClock.elapsedRealtimeNanos()
		return database.withTransaction {
			val brokerDao = database.sourceBrokerDao()
			val demands = brokerDao.authorizationDemands(ACTIVITY_SOURCE_KIND)
			require(demands.isNotEmpty()) {
				"Activity registration requires an active durable broker demand"
			}
			val purposeEligibilityMask = SourceBrokerAuthorization.purposeMask(demands)
			require(purposeEligibilityMask > 0L) {
				"Activity broker demand has no recognized purpose eligibility"
			}
			val dao = database.sourceRegistrationStateDao()
			val existing = dao.get(ACTIVITY_SOURCE_KIND, OWNER_SCOPE)
			val authorizationFingerprint = SourceBrokerAuthorization.fingerprint(demands)
			val reusable = brokerDao.latestReservedRegistration(ACTIVITY_SOURCE_KIND, OWNER_SCOPE)
				?.takeIf { reserved ->
					reserved.clockDomainId == clockDomainId &&
						reserved.collectedDataEpoch == lifecycle.epoch &&
						reserved.physicalConfigurationFingerprint == physicalConfigurationFingerprint &&
						brokerDao.latestAuthorization(ACTIVITY_SOURCE_KIND, reserved.registrationGeneration)
							.toAuthorizationSnapshotOrNull()?.authorizationFingerprint == authorizationFingerprint &&
						(existing == null || reserved.registrationGeneration >= existing.registrationGeneration)
				}
			if (reusable != null) {
				val state = if (existing == null) {
					SourceRegistrationStateEntity(
						sourceKind = ACTIVITY_SOURCE_KIND,
						ownerScope = OWNER_SCOPE,
						sourceInstanceId = reusable.sourceInstanceId,
						clockDomainId = reusable.clockDomainId,
						registrationGeneration = reusable.registrationGeneration,
						nextSequence = 0L,
						appliedRevision = combined.appliedRevision,
						collectedDataEpoch = reusable.collectedDataEpoch,
						updatedAtMs = nowMs,
					)
				} else {
					existing.copy(
						sourceInstanceId = reusable.sourceInstanceId,
						clockDomainId = reusable.clockDomainId,
						registrationGeneration = reusable.registrationGeneration,
						appliedRevision = combined.appliedRevision,
						collectedDataEpoch = reusable.collectedDataEpoch,
						updatedAtMs = nowMs,
					)
				}
				return@withTransaction ReservedActivityRegistration(
					identity = reusable.toActivityRegistrationIdentity(),
					state = state,
					predecessorState = existing,
				)
			}
			val registrationGeneration = brokerDao.maximumRegistrationGeneration(ACTIVITY_SOURCE_KIND) + 1L
			val next = if (existing == null) {
				SourceRegistrationStateEntity(
					sourceKind = ACTIVITY_SOURCE_KIND,
					ownerScope = OWNER_SCOPE,
					sourceInstanceId = UUID.randomUUID().toString(),
					clockDomainId = clockDomainId,
					registrationGeneration = registrationGeneration,
					nextSequence = 0L,
					appliedRevision = combined.appliedRevision,
					collectedDataEpoch = lifecycle.epoch,
					updatedAtMs = nowMs,
				)
			} else {
				existing.copy(
					sourceInstanceId = if (
						existing.clockDomainId == clockDomainId && existing.collectedDataEpoch == lifecycle.epoch
					) existing.sourceInstanceId else UUID.randomUUID().toString(),
					clockDomainId = clockDomainId,
					registrationGeneration = registrationGeneration,
					nextSequence = if (
						existing.clockDomainId == clockDomainId && existing.collectedDataEpoch == lifecycle.epoch
					) existing.nextSequence else 0L,
					appliedRevision = combined.appliedRevision,
					collectedDataEpoch = lifecycle.epoch,
					updatedAtMs = nowMs,
				)
			}
			brokerDao.insertRegistration(
				ProviderRegistrationGenerationEntity(
					sourceKind = ACTIVITY_SOURCE_KIND,
					registrationGeneration = next.registrationGeneration,
					sourceInstanceId = next.sourceInstanceId,
					ownerScope = OWNER_SCOPE,
					clockDomainId = next.clockDomainId,
					physicalConfigurationFingerprint = physicalConfigurationFingerprint,
					collectedDataEpoch = next.collectedDataEpoch,
					status = ProviderRegistrationGenerationEntity.STATUS_RESERVED,
					reservedAtMs = nowMs,
					reservedElapsedRealtimeNanos = nowElapsed,
					acceptedAtMs = null,
					acceptedElapsedRealtimeNanos = null,
					retiredAtMs = null,
					retiredElapsedRealtimeNanos = null,
					failureCode = null,
				),
			)
			val authorizationRevision = brokerDao.maximumAuthorizationRevision(ACTIVITY_SOURCE_KIND) + 1L
			brokerDao.insertAuthorizations(
				SourceBrokerAuthorization.rows(
					ACTIVITY_SOURCE_KIND,
					next.registrationGeneration,
					authorizationRevision,
					demands,
					clockDomainId,
					nowElapsed,
					nowMs,
				),
			)
			ReservedActivityRegistration(
				identity = ActivityRegistrationIdentity(
					sourceInstanceId = next.sourceInstanceId,
					registrationGeneration = next.registrationGeneration,
					collectedDataEpoch = next.collectedDataEpoch,
					clockDomainId = next.clockDomainId,
					physicalConfigurationFingerprint = physicalConfigurationFingerprint,
				),
				state = next,
				predecessorState = existing,
			)
		}
	}

	private suspend fun retireRegistration(identity: ActivityRegistrationIdentity, reason: String) {
		val nowMs = System.currentTimeMillis()
		val nowElapsed = SystemClock.elapsedRealtimeNanos()
		database.withTransaction {
			rotateAuthorization(identity, emptyList(), nowMs, nowElapsed)
			check(database.sourceBrokerDao().markRegistrationRetiring(
				ACTIVITY_SOURCE_KIND,
				identity.registrationGeneration,
				identity.sourceInstanceId,
				nowMs,
				nowElapsed,
				reason,
			) == 1) { "Activity provider registration is not active" }
		}
	}

	private suspend fun cleanupRetiringRegistrationsLocked(): Boolean {
		val pending = database.sourceBrokerDao().pendingProviderRemovals(ACTIVITY_SOURCE_KIND)
		for (registration in pending) {
			if (!removeAndCompleteRegistration(registration, nonCancellable = false)) return false
		}
		return true
	}

	/**
	 * Cleans identities from the no-backup journal without touching Room. This remains valid after
	 * full collected-data deletion has removed every provider-generation row.
	 */
	private suspend fun retryPendingProviderCleanupLocked(): ActivityProviderCleanupResult {
		val state = try {
			cleanupStore.read()
		} catch (error: ActivityRegistrationCleanupStoreException) {
			return ActivityProviderCleanupResult(
				complete = false,
				pendingCount = 0,
				retryable = !error.corrupt,
				failureCode = if (error.corrupt) {
					ActivityRegistrationFailureCode.PROVIDER_CLEANUP_STATE_INVALID
				} else {
					ActivityRegistrationFailureCode.STORAGE_UNAVAILABLE
				},
			)
		}
		val work = buildSet {
			addAll(state.pending)
			if (!state.releasedV27Checked) add(ActivityRegistrationCleanupKey.RELEASED_V27)
		}
		if (work.isEmpty()) return ActivityProviderCleanupResult.COMPLETE

		val remaining = LinkedHashSet<ActivityRegistrationCleanupKey>()
		var releasedV27Checked = state.releasedV27Checked
		for (key in work) {
			try {
				backend.removePendingRegistration(key)
				if (key.kind == ActivityRegistrationCleanupKind.RELEASED_V27_STATIC) {
					releasedV27Checked = true
				}
			} catch (error: CancellationException) {
				throw error
			} catch (_: Exception) {
				remaining += key
				// The durable key, rather than the unchecked bit, owns all subsequent retries.
				if (key.kind == ActivityRegistrationCleanupKind.RELEASED_V27_STATIC) {
					releasedV27Checked = true
				}
			}
		}
		try {
			cleanupStore.write(
				ActivityRegistrationCleanupState(
					releasedV27Checked = releasedV27Checked,
					pending = remaining,
				),
			)
		} catch (error: ActivityRegistrationCleanupStoreException) {
			ensureCleanupRetryScheduled()
			return ActivityProviderCleanupResult(
				complete = false,
				pendingCount = remaining.size,
				retryable = !error.corrupt,
				failureCode = if (error.corrupt) {
					ActivityRegistrationFailureCode.PROVIDER_CLEANUP_STATE_INVALID
				} else {
					ActivityRegistrationFailureCode.STORAGE_UNAVAILABLE
				},
			)
		}
		if (remaining.isEmpty()) return ActivityProviderCleanupResult.COMPLETE
		ensureCleanupRetryScheduled()
		return ActivityProviderCleanupResult(
			complete = false,
			pendingCount = remaining.size,
			retryable = true,
			failureCode = ActivityRegistrationFailureCode.PROVIDER_REMOVAL_FAILED,
		)
	}

	private fun ensureCleanupRetryScheduled() {
		try {
			cleanupScheduler.ensureScheduled()
		} catch (_: IllegalStateException) {
			// The journal remains the crash authority. A later arbiter construction retries scheduling.
		}
	}

	private suspend fun removeAndCompleteRegistration(
		registration: ProviderRegistrationGenerationEntity,
		nonCancellable: Boolean,
	): Boolean = runProviderCleanup(nonCancellable) {
		backend.removeRegistration(registration.toActivityRegistrationIdentity())
		check(
			database.sourceBrokerDao().completeRegistrationRetirement(
				registration.sourceKind,
				registration.registrationGeneration,
				registration.sourceInstanceId,
			) == 1,
		)
	}

	private suspend fun removeProviderOnly(
		identity: ActivityRegistrationIdentity,
		nonCancellable: Boolean,
	): Boolean = runProviderCleanup(nonCancellable) {
		backend.removeRegistration(identity)
	}

	private suspend fun runProviderCleanup(
		nonCancellable: Boolean,
		block: suspend () -> Unit,
	): Boolean = try {
		if (nonCancellable) {
			val result = withContext(NonCancellable) { runCatching { block() } }
			currentCoroutineContext().ensureActive()
			result.getOrThrow()
		} else {
			block()
		}
		true
	} catch (error: CancellationException) {
		throw error
	} catch (_: Exception) {
		false
	}

	private suspend fun markRegistrationFailed(identity: ActivityRegistrationIdentity, reason: String) {
		database.sourceBrokerDao().finishRegistration(
			ACTIVITY_SOURCE_KIND,
			identity.registrationGeneration,
			identity.sourceInstanceId,
			ProviderRegistrationGenerationEntity.STATUS_FAILED,
			System.currentTimeMillis(),
			SystemClock.elapsedRealtimeNanos(),
			reason,
		)
	}

	private suspend fun rotateAuthorization(
		identity: ActivityRegistrationIdentity,
		demands: List<com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity>,
		wallTimeMs: Long,
		elapsedRealtimeNanos: Long,
	) {
		val dao = database.sourceBrokerDao()
		val current = dao.latestAuthorization(ACTIVITY_SOURCE_KIND, identity.registrationGeneration)
			.toAuthorizationSnapshotOrNull()
		val fingerprint = SourceBrokerAuthorization.fingerprint(demands)
		if (current?.authorizationFingerprint == fingerprint) return
		val revision = dao.maximumAuthorizationRevision(ACTIVITY_SOURCE_KIND) + 1L
		dao.insertAuthorizations(
			SourceBrokerAuthorization.rows(
				ACTIVITY_SOURCE_KIND,
				identity.registrationGeneration,
				revision,
				demands,
				identity.clockDomainId,
				elapsedRealtimeNanos,
				wallTimeMs,
			),
		)
	}

	private suspend fun hydratePersistedIdentityIfNeeded() {
		if (current.identity != null) return
		val persisted = database.sourceRegistrationStateDao().get(ACTIVITY_SOURCE_KIND, OWNER_SCOPE) ?: return
		val generation = database.sourceBrokerDao().registration(
			ACTIVITY_SOURCE_KIND,
			persisted.registrationGeneration,
		) ?: return
		current = current.copy(
			active = generation.status == ProviderRegistrationGenerationEntity.STATUS_ACTIVE,
			identity = ActivityRegistrationIdentity(
				persisted.sourceInstanceId,
				persisted.registrationGeneration,
				persisted.collectedDataEpoch,
				generation.clockDomainId,
				generation.physicalConfigurationFingerprint,
			),
		)
	}

	private fun combineDemands(): CombinedDemand {
		val active = demands.filterValues(ActivityRegistrationDemand::enabled)
		val automatic = active[ActivityRegistrationOwner.AUTOMATIC_START_MONITOR]
		return CombinedDemand(
			owners = active.keys,
			intervalSeconds = active.values.mapNotNull { it.continuousRecognitionIntervalSeconds }.minOrNull(),
			transitions = active.values.flatMap { it.transitions }.toSet(),
			automaticRecognitionEligible = automatic?.continuousRecognitionIntervalSeconds != null,
			automaticTransitions = automatic?.transitions.orEmpty(),
			appliedRevision = active.values.mapNotNull { it.planRevision }.maxOrNull(),
		)
	}

	private fun ActivityRegistrationSnapshot.matches(
		demand: CombinedDemand,
		physicalConfigurationFingerprint: String,
		clockDomainId: String,
		collectedDataEpoch: Long,
	): Boolean =
		continuousRecognitionIntervalSeconds == demand.intervalSeconds &&
			transitions == demand.transitions &&
			identity?.physicalConfigurationFingerprint == physicalConfigurationFingerprint &&
			identity?.clockDomainId == clockDomainId &&
			identity?.collectedDataEpoch == collectedDataEpoch

	private fun applied(snapshot: ActivityRegistrationSnapshot) =
		ActivityRegistrationResult(ActivityRegistrationStatus.APPLIED, snapshot)

	private fun failure(
		status: ActivityRegistrationStatus,
		code: ActivityRegistrationFailureCode,
		retryable: Boolean,
	) = ActivityRegistrationResult(status, current, code, retryable)

	private data class CombinedDemand(
		val owners: Set<ActivityRegistrationOwner>,
		val intervalSeconds: Int?,
		val transitions: Set<com.adsamcik.tracker.activity.ActivityTransitionData>,
		val automaticRecognitionEligible: Boolean,
		val automaticTransitions: Set<com.adsamcik.tracker.activity.ActivityTransitionData>,
		val appliedRevision: Long?,
	) {
		val enabled: Boolean get() = intervalSeconds != null || transitions.isNotEmpty()

		fun physicalConfigurationFingerprint(): String {
			val canonical = buildString {
				append(intervalSeconds ?: 0)
				append('\u001f')
				transitions.sortedWith(
					compareBy<com.adsamcik.tracker.activity.ActivityTransitionData> { it.activity.name }
						.thenBy { it.type.value },
				).forEach { transition ->
					append(transition.activity.name)
					append(':')
					append(transition.type.value)
					append('\u001e')
				}
				append('\u001f')
				append(if (automaticRecognitionEligible) "automatic-recognition" else "no-automatic-recognition")
				append('\u001f')
				automaticTransitions.sortedWith(
					compareBy<com.adsamcik.tracker.activity.ActivityTransitionData> { it.activity.name }
						.thenBy { it.type.value },
				).forEach { transition ->
					append("automatic:")
					append(transition.activity.name)
					append(':')
					append(transition.type.value)
					append('\u001e')
				}
			}
			return MessageDigest.getInstance("SHA-256")
				.digest(canonical.toByteArray(Charsets.UTF_8))
				.joinToString("") { byte -> "%02x".format(byte) }
		}
	}

	private data class ReservedActivityRegistration(
		val identity: ActivityRegistrationIdentity,
		val state: SourceRegistrationStateEntity,
		val predecessorState: SourceRegistrationStateEntity?,
	)

	private fun ProviderRegistrationGenerationEntity.toActivityRegistrationIdentity() =
		ActivityRegistrationIdentity(
			sourceInstanceId = sourceInstanceId,
			registrationGeneration = registrationGeneration,
			collectedDataEpoch = collectedDataEpoch,
			clockDomainId = clockDomainId,
			physicalConfigurationFingerprint = physicalConfigurationFingerprint,
		)

	private fun ProviderRegistrationGenerationEntity.cleanupKey() = ActivityRegistrationCleanupKey(
		kind = ActivityRegistrationCleanupKind.BROKERED,
		sourceInstanceId = sourceInstanceId,
		registrationGeneration = registrationGeneration,
	)

	private companion object {
		const val ACTIVITY_SOURCE_KIND = 2
		const val OWNER_SCOPE = "source-broker:2"
		val EMPTY_SNAPSHOT = ActivityRegistrationSnapshot(false, null, emptySet(), null, emptySet())
	}
}
