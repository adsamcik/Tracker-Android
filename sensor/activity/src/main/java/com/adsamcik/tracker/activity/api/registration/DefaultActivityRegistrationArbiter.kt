package com.adsamcik.tracker.activity.api.registration

import android.content.Context
import android.os.SystemClock
import androidx.room.withTransaction
import com.adsamcik.tracker.activity.api.backend.GmsActivityRecognitionBackend
import com.adsamcik.tracker.activity.api.backend.RecognitionConfig
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductLaneExecutionAuthority
import com.adsamcik.tracker.shared.base.database.data.SourceRegistrationStateEntity
import com.adsamcik.tracker.shared.base.database.data.TrackingRolloutStateEntity
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
import kotlinx.coroutines.withTimeoutOrNull

/** Serializes and fences every physical GMS activity-recognition registration. */
@Singleton
class DefaultActivityRegistrationArbiter @Inject constructor(
	@ApplicationContext private val context: Context,
	private val bootClockDomainProvider: BootClockDomainProvider,
	private val database: AppDatabase,
	private val laneExecutionAuthority: SourceProductLaneExecutionAuthority,
	private val lifecycleStore: CollectedDataLifecycleStore,
	private val backend: GmsActivityRecognitionBackend,
	private val callbackAdmissionBarrier: ActivityCallbackAdmissionBarrier,
	private val startupGateProvider: Provider<TrackingStartupGate>,
	private val cleanupStore: ActivityRegistrationCleanupStore,
	private val cleanupScheduler: ActivityRegistrationCleanupScheduler,
	@ApplicationScope appScope: CoroutineScope,
) : ActivityRegistrationArbiter {
	private val mutex = Mutex()
	private val demands = mutableMapOf<ActivityRegistrationOwner, ActivityRegistrationDemand>()
	@Volatile private var current = EMPTY_SNAPSHOT
	private var currentCallbackMetadata: ActivityCallbackMetadata? = null
	private var systemRegistrationRequiresRearm = false
	private var deletionPaused = false

	init {
		// This one-time unique job also discovers and removes the released-v27 static PendingIntent.
		// The journal is outside Room, so scheduling is safe before tracking storage is ready.
		ensureCleanupRetryScheduled()
		// Session manifests may add/remove Activity as CONTROL without starting ActivitySourceRuntime.
		// Observe the durable authority so an unchanged physical request still receives the exact new
		// callback metadata and purpose/consent vector. Do not subscribe to Room until storage and
		// released-v27 recovery are safe: this singleton can be constructed during cold service injection.
		appScope.launch {
			val startupGate = startupGateProvider.get()
			if (startupGate.reconcile() !is TrackingStartupResult.Ready) startupGate.awaitReady()
			database.invalidationTracker
				.createFlow(
					"source_demand",
					"source_policy",
					"source_policy_authority",
					"tracking_rollout_state",
					"source_product_projection_lane",
					emitInitialState = true,
				)
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
		val requestedCombined = combineDemands()
		if (deletionPaused) return applied(current)
		if (requestedCombined.enabled && !startupGateProvider.get().isReady) {
			// Do not perform enabled storage reconciliation or provider registration while process-wide
			// recovery is closed. Cleanup remains legal, so fence an already active process-local
			// registration before retaining the logical owners for a later Ready reconciliation.
			if (current.active) {
				val stopped = fenceAndRemoveLocked(clearOwners = false)
				if (stopped.status != ActivityRegistrationStatus.APPLIED) return stopped
			}
			current = current.copy(owners = requestedCombined.owners)
			return failure(
				ActivityRegistrationStatus.BLOCKED,
				ActivityRegistrationFailureCode.STARTUP_RECOVERY_NOT_READY,
				true,
			)
		}
		if (requestedCombined.enabled) {
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
		if (!requestedCombined.enabled) {
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
		val acquisitionEligibility = activityAcquisitionEligibility(durableEligibility)
		if (!acquisitionEligibility.sourceEligible) {
			fenceAndRemoveLocked(clearOwners = false)
			return failure(
				ActivityRegistrationStatus.BLOCKED,
				ActivityRegistrationFailureCode.MISSING_DURABLE_DEMAND,
				false,
			)
		}
		if (acquisitionEligibility.automaticControlRefreshPending) {
			// SourcePolicy is already authoritative, but the application-scoped control demand has
			// not yet been replaced with its new policy/consent generation. Close callback authority
			// immediately while retaining an otherwise identical physical registration. The policy
			// observer then replaces the demand and this same generation rotates back to eligible.
			val identity = current.identity
			if (identity != null) {
				try {
					rotateAuthorization(
						identity,
						requestedCombined,
					)
				} catch (error: CancellationException) {
					throw error
				} catch (failure: Exception) {
					return failure(
						ActivityRegistrationStatus.FAILED,
						registrationMutationFailureCode(failure),
						true,
					)
				}
			}
			return failure(
				if (current.active || systemRegistrationRequiresRearm) {
					ActivityRegistrationStatus.DEGRADED
				} else {
					ActivityRegistrationStatus.BLOCKED
				},
				ActivityRegistrationFailureCode.AUTHORIZATION_REFRESH_PENDING,
				true,
			)
		}
		val combined = if (
			ActivityRegistrationOwner.AUTOMATIC_START_MONITOR in requestedCombined.owners &&
			!acquisitionEligibility.automaticControlEligible
		) {
			combineDemands(excludedOwner = ActivityRegistrationOwner.AUTOMATIC_START_MONITOR)
		} else {
			requestedCombined
		}
		if (!combined.enabled) {
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
		val currentClockDomainId = bootClockDomainProvider.current()
		val currentIdentity = current.identity
		if (systemRegistrationRequiresRearm &&
			currentIdentity != null &&
			currentIdentity.matches(
				physicalConfigurationFingerprint,
				currentClockDomainId,
				lifecycle.epoch,
			)
		) {
			try {
				val rotation = rotateAuthorization(
					currentIdentity,
					combined,
				)
				if (!rotation.continuationEligible) {
					return authorizationNoLongerEligibleLocked()
				}
			} catch (error: CancellationException) {
				throw error
			} catch (failure: Exception) {
				return failure(
					ActivityRegistrationStatus.FAILED,
					registrationMutationFailureCode(failure),
					true,
				)
			}
			val registered = backend.applyRegistration(combined.toRecognitionConfig(), currentIdentity)
			if (!registered) {
				return failure(
					ActivityRegistrationStatus.FAILED,
					ActivityRegistrationFailureCode.PROVIDER_REGISTRATION_FAILED,
					true,
				)
			}
			val stillEligible = database.withTransaction {
				val demands = database.sourceBrokerDao().authorizationDemands(ACTIVITY_SOURCE_KIND)
				activityAcquisitionEligibilityInTransaction(demands).allows(combined)
			}
			if (!stillEligible) {
				fenceAndRemoveLocked(clearOwners = false)
				return failure(
					ActivityRegistrationStatus.BLOCKED,
					ActivityRegistrationFailureCode.MISSING_DURABLE_DEMAND,
					false,
				)
			}
			systemRegistrationRequiresRearm = false
			currentCallbackMetadata = combined.callbackMetadata
			current = current.copy(
				active = true,
				owners = combined.owners,
				continuousRecognitionIntervalSeconds = combined.intervalSeconds,
				transitions = combined.transitions,
			)
			return applied(current)
		}
		if (current.active && current.matches(
				combined,
				physicalConfigurationFingerprint,
				currentClockDomainId,
				lifecycle.epoch,
			)
		) {
			val identity = requireNotNull(current.identity)
			if (currentCallbackMetadata != combined.callbackMetadata) {
				val metadataUpdated = backend.refreshRegistrationMetadata(
					combined.toRecognitionConfig(),
					identity,
				)
				if (!metadataUpdated) {
					return failure(
						ActivityRegistrationStatus.DEGRADED,
						ActivityRegistrationFailureCode.CALLBACK_METADATA_UPDATE_FAILED,
						true,
					)
				}
				// The PendingIntent now carries this metadata even if durable authorization rotation
				// subsequently fails. Remember it so a retry does not perform another binder update.
				currentCallbackMetadata = combined.callbackMetadata
			}
			try {
				val rotation = rotateAuthorization(
					identity,
					combined,
				)
				if (!rotation.continuationEligible) {
					return authorizationNoLongerEligibleLocked()
				}
			} catch (error: CancellationException) {
				throw error
			} catch (failure: Exception) {
				return failure(
					ActivityRegistrationStatus.FAILED,
					registrationMutationFailureCode(failure),
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
		val registered = backend.applyRegistration(combined.toRecognitionConfig(), identity)
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
		val activation = try {
			withContext(NonCancellable) {
				database.withTransaction {
					val demands = database.sourceBrokerDao().authorizationDemands(ACTIVITY_SOURCE_KIND)
					if (!activityAcquisitionEligibilityInTransaction(demands).allows(combined)) {
						return@withTransaction null
					}
					ActivityRegistrationActivation(
						database.sourceBrokerDao().acceptReservedReplacement(
							reservedState = reservation.state,
							expectedPointerGeneration = reservation.predecessorState?.registrationGeneration,
							expectedPointerInstanceId = reservation.predecessorState?.sourceInstanceId,
							requiredAuthorizationFingerprint = null,
							acceptedAtMs = System.currentTimeMillis(),
							acceptedElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
						),
					)
				}
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
		if (activation == null) {
			removeProviderOnly(identity, nonCancellable = true)
			try {
				markRegistrationFailed(identity, "ROLLOUT_OR_POLICY_CONTAINED")
			} catch (error: CancellationException) {
				throw error
			} catch (_: Exception) {
				// The unaccepted reservation remains non-authoritative and recoverable.
			}
			return failure(
				ActivityRegistrationStatus.BLOCKED,
				ActivityRegistrationFailureCode.MISSING_DURABLE_DEMAND,
				false,
			)
		}
		val previous = activation.previous

		current = ActivityRegistrationSnapshot(
			active = true,
			identity = identity,
			owners = combined.owners,
			continuousRecognitionIntervalSeconds = combined.intervalSeconds,
			transitions = combined.transitions,
		)
		currentCallbackMetadata = combined.callbackMetadata
		systemRegistrationRequiresRearm = false
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
		if (!current.active && !systemRegistrationRequiresRearm) {
			current = current.copy(owners = if (clearOwners) emptySet() else demands.keys.toSet())
			return applied(current)
		}
		val oldIdentity = current.identity
		if (oldIdentity != null) {
			try {
				retireRegistration(oldIdentity, "DEMAND_REMOVED")
			} catch (error: CancellationException) {
				throw error
			} catch (failure: Exception) {
				return failure(ActivityRegistrationStatus.FAILED, registrationMutationFailureCode(failure), true)
			}
		}
		current = ActivityRegistrationSnapshot(
			active = false,
			identity = oldIdentity,
			owners = if (clearOwners) emptySet() else demands.keys.toSet(),
			continuousRecognitionIntervalSeconds = null,
			transitions = emptySet(),
		)
		currentCallbackMetadata = null
		systemRegistrationRequiresRearm = false
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
					providerResidency =
						ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE,
					providerProcessIncarnationId = null,
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
		awaitCallbackBarrier(identity, terminal = true)
		val nowMs = System.currentTimeMillis()
		val nowElapsed = SystemClock.elapsedRealtimeNanos()
		database.withTransaction {
			appendAuthorizationIfChanged(identity, emptyList(), nowMs, nowElapsed)
			acknowledgeCaptureCallbackBarrier(identity)
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
		val identity = registration.toActivityRegistrationIdentity()
		awaitCallbackBarrier(identity, terminal = true)
		backend.removeRegistration(identity)
		database.withTransaction {
			acknowledgeCaptureCallbackBarrier(identity)
			check(
				database.sourceBrokerDao().completeRegistrationRetirement(
					registration.sourceKind,
					registration.registrationGeneration,
					registration.sourceInstanceId,
				) == 1,
			)
		}
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
		combined: CombinedDemand,
	): AuthorizationRotationResult {
		val prepared = prepareAuthorizationRotation(identity, combined)
		if (!prepared.requiresCaptureDrain) return prepared

		val rotation = try {
			awaitCallbackBarrier(identity, terminal = false)
			completeCaptureClosingRotation(identity, combined)
		} catch (error: CancellationException) {
			// A non-terminal fence must not strand an otherwise valid physical generation merely
			// because its caller was cancelled. Finish the bounded drain and revalidate from Room;
			// the cancellation is still propagated after this compensation completes.
			withContext(NonCancellable) {
				val drained = runCatching {
					awaitCallbackBarrier(identity, terminal = false)
				}.isSuccess
				if (drained) {
					runCatching {
						completeCaptureClosingRotation(identity, combined)
					}.getOrNull()?.let { compensated ->
						if (compensated.continuationEligible) {
							check(callbackAdmissionBarrier.reopen(identity)) {
								"Drained Activity callback generation could not reopen after cancellation"
							}
						}
					}
				}
			}
			throw error
		}
		if (rotation.continuationEligible) {
			check(callbackAdmissionBarrier.reopen(identity)) {
				"Drained Activity callback generation could not reopen for control-only continuation"
			}
		}
		return rotation
	}

	/**
	 * Re-derives every authorization mutation from authority read in the mutation transaction.
	 * Capture-closing changes are detected but deliberately not appended until the callback drain.
	 */
	private suspend fun prepareAuthorizationRotation(
		identity: ActivityRegistrationIdentity,
		combined: CombinedDemand,
	): AuthorizationRotationResult = database.withTransaction {
		val fresh = freshAuthorizationInTransaction(combined)
		val currentRows = database.sourceBrokerDao().latestAuthorization(
			ACTIVITY_SOURCE_KIND,
			identity.registrationGeneration,
		)
		val currentFingerprint = currentRows.toAuthorizationSnapshotOrNull()?.authorizationFingerprint
		val freshFingerprint = SourceBrokerAuthorization.fingerprint(fresh.authorizedDemands)
		if (currentFingerprint == freshFingerprint) return@withTransaction fresh.result
		val closesCapture = currentRows.any { authorization ->
			authorization.persistenceEligible && authorization.purpose in CAPTURE_PURPOSES
		} && fresh.authorizedDemands.none { demand ->
			demand.persistenceEligible && demand.purpose in CAPTURE_PURPOSES
		}
		if (closesCapture) {
			return@withTransaction fresh.result.copy(requiresCaptureDrain = true)
		}
		appendAuthorizationIfChanged(
			identity,
			fresh.authorizedDemands,
			System.currentTimeMillis(),
			SystemClock.elapsedRealtimeNanos(),
		)
		fresh.result
	}

	/**
	 * Commits the capture-closing authorization only from authority read in this transaction.
	 * The pre-drain demand list is deliberately not accepted here: consent, rollout, lane ownership,
	 * and durable demand may all have changed while an already-admitted callback was draining.
	 */
	private suspend fun completeCaptureClosingRotation(
		identity: ActivityRegistrationIdentity,
		combined: CombinedDemand,
	): AuthorizationRotationResult = database.withTransaction {
		val fresh = freshAuthorizationInTransaction(combined)
		appendAuthorizationIfChanged(
			identity,
			fresh.authorizedDemands,
			System.currentTimeMillis(),
			SystemClock.elapsedRealtimeNanos(),
		)
		acknowledgeCaptureCallbackBarrier(identity)
		fresh.result
	}

	private suspend fun freshAuthorizationInTransaction(
		combined: CombinedDemand,
	): FreshActivityAuthorization {
		val freshDemands = database.sourceBrokerDao().authorizationDemands(ACTIVITY_SOURCE_KIND)
		val eligibility = activityAcquisitionEligibilityInTransaction(freshDemands)
		val authorizedDemands = when {
			!eligibility.sourceEligible -> emptyList()
			eligibility.automaticControlRefreshPending ->
				eligibility.authorizedDemandsWhileRefreshPending
			else -> freshDemands
		}
		return FreshActivityAuthorization(
			authorizedDemands = authorizedDemands,
			result = AuthorizationRotationResult(
				continuationEligible = eligibility.allows(combined) &&
					!eligibility.automaticControlRefreshPending,
			),
		)
	}

	private suspend fun authorizationNoLongerEligibleLocked(): ActivityRegistrationResult {
		val stopped = fenceAndRemoveLocked(clearOwners = false)
		return if (stopped.status == ActivityRegistrationStatus.APPLIED) {
			failure(
				ActivityRegistrationStatus.BLOCKED,
				ActivityRegistrationFailureCode.MISSING_DURABLE_DEMAND,
				false,
			)
		} else {
			stopped
		}
	}

	private suspend fun appendAuthorizationIfChanged(
		identity: ActivityRegistrationIdentity,
		demands: List<SourceDemandEntity>,
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

	private suspend fun acknowledgeCaptureCallbackBarrier(identity: ActivityRegistrationIdentity) {
		val dao = database.sourceBrokerDao()
		val throughRevision = dao.maximumCaptureAuthorizationRevision(
			ACTIVITY_SOURCE_KIND,
			identity.registrationGeneration,
		)
		check(dao.acknowledgeCaptureCallbackBarrier(
			ACTIVITY_SOURCE_KIND,
			identity.registrationGeneration,
			identity.sourceInstanceId,
			throughRevision,
		) == 1) { "Activity registration changed before its callback barrier acknowledgement" }
	}

	private suspend fun awaitCallbackBarrier(
		identity: ActivityRegistrationIdentity,
		terminal: Boolean,
	) {
		val drained = withTimeoutOrNull(ACTIVITY_CALLBACK_BARRIER_TIMEOUT_MS) {
			if (terminal) {
				callbackAdmissionBarrier.tombstoneAndAwait(identity)
			} else {
				callbackAdmissionBarrier.fenceAndAwait(identity)
			}
			true
		}
		if (drained != true) throw ActivityCallbackBarrierNotDrainedException(identity)
	}

	private suspend fun hydratePersistedIdentityIfNeeded() {
		if (current.identity != null) return
		val persisted = database.sourceRegistrationStateDao().get(ACTIVITY_SOURCE_KIND, OWNER_SCOPE) ?: return
		val generation = database.sourceBrokerDao().registration(
			ACTIVITY_SOURCE_KIND,
			persisted.registrationGeneration,
		) ?: return
		val isSystemRegistrationAwaitingRearm =
			generation.status == ProviderRegistrationGenerationEntity.STATUS_ACTIVE &&
				generation.providerResidency ==
				ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE
		systemRegistrationRequiresRearm = isSystemRegistrationAwaitingRearm
		currentCallbackMetadata = null
		current = current.copy(
			active = generation.status == ProviderRegistrationGenerationEntity.STATUS_ACTIVE &&
				!isSystemRegistrationAwaitingRearm,
			identity = ActivityRegistrationIdentity(
				persisted.sourceInstanceId,
				persisted.registrationGeneration,
				persisted.collectedDataEpoch,
				generation.clockDomainId,
				generation.physicalConfigurationFingerprint,
			),
		)
	}

	private fun combineDemands(excludedOwner: ActivityRegistrationOwner? = null): CombinedDemand {
		val active = demands.filter { (owner, demand) ->
			owner != excludedOwner && demand.enabled
		}
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

	private suspend fun activityAcquisitionEligibility(
		durableDemands: List<SourceDemandEntity>,
	): ActivityAcquisitionEligibility = database.withTransaction {
		activityAcquisitionEligibilityInTransaction(durableDemands)
	}

	private suspend fun activityAcquisitionEligibilityInTransaction(
		durableDemands: List<SourceDemandEntity>,
	): ActivityAcquisitionEligibility {
		val rollout = database.trackingRolloutStateDao().get()
		val declaredReachability = rollout?.eventReachability()
			?: return ActivityAcquisitionEligibility.DENIED
		val verifiedCaptureModes = mutableMapOf<Int, Long>()
		for ((sourceKind, declaredLane) in declaredReachability.captureLanes) {
			val lane = database.sourceProjectionStateDao().activeProductLane(sourceKind)
			if (lane != null && lane.captureModeMask == declaredLane.captureModeMask &&
				laneExecutionAuthority.owns(lane) &&
				database.sourceProjectionStateDao().isProductLaneReachable(
					sourceKind = sourceKind,
					productStage = declaredLane.productStage,
					rolloutRevision = rollout.revision,
				)
			) {
				verifiedCaptureModes[sourceKind] = declaredLane.captureModeMask
			}
		}
		val reachability = RolloutReachability(
			captureModeMasks = verifiedCaptureModes,
			controlSources = declaredReachability.explicitControlSources + verifiedCaptureModes.keys,
		)
		val authority = database.sourcePolicyDao().authority()
		if (authority?.bootstrapState != SourcePolicyAuthorityEntity.STATE_ACTIVE) {
			return ActivityAcquisitionEligibility.DENIED
		}
		val policies = database.sourcePolicyDao().policiesAtRevision(authority.currentPolicyRevision)
		if (policies.map { it.sourceKind }.toSet() != SOURCE_KINDS) {
			return ActivityAcquisitionEligibility.DENIED
		}
		val policyBySource = policies.associateBy { it.sourceKind }
		if (durableDemands.isEmpty()) return ActivityAcquisitionEligibility.DENIED
		var automaticControlRefreshPending = false
		val authorizedDemands = mutableListOf<SourceDemandEntity>()
		for (demand in durableDemands) {
			val policy = policyBySource[demand.sourceKind]
				?: return ActivityAcquisitionEligibility.DENIED
			val invalid = when (demand.purpose) {
				SourceBrokerPurpose.SESSION_CAPTURE -> {
					val requiredCaptureMode = demand.sessionCaptureModeMaskInTransaction()
					requiredCaptureMode == null ||
						!reachability.hasCaptureMode(ACTIVITY_SOURCE_KIND, requiredCaptureMode) ||
						!policy.enabled || policy.captureConsentEpoch != demand.consentEpoch ||
						(demand.persistenceEligible && !policy.capturePersistenceEligible)
				}
				SourceBrokerPurpose.CONTROL_AUTOSTART,
				SourceBrokerPurpose.CONTROL_CONTINUATION,
				-> ACTIVITY_SOURCE_KIND !in reachability.controlSources ||
					policy.controlConsentEpoch != demand.consentEpoch ||
						(demand.persistenceEligible && !policy.controlPersistenceEligible)
				SourceBrokerPurpose.AMBIENT_PRODUCT ->
					!reachability.hasCaptureMode(ACTIVITY_SOURCE_KIND, AMBIENT_CAPTURE_MASK) ||
						policy.ambientConsentEpoch != demand.consentEpoch ||
						(demand.persistenceEligible && !policy.ambientPersistenceEligible)
				else -> true
			}
			if (!invalid) {
				if (demand.purpose == SourceBrokerPurpose.CONTROL_AUTOSTART &&
					demand.sourcePolicyRevision != authority.currentPolicyRevision
				) {
					automaticControlRefreshPending = true
				} else {
					authorizedDemands += demand
				}
				continue
			}
			val refreshableAutomaticControl =
				demand.purpose == SourceBrokerPurpose.CONTROL_AUTOSTART &&
					ACTIVITY_SOURCE_KIND in reachability.controlSources &&
					policy.controlConsentEpoch != null &&
					(!demand.persistenceEligible || policy.controlPersistenceEligible)
			if (refreshableAutomaticControl) {
				automaticControlRefreshPending = true
			} else {
				return ActivityAcquisitionEligibility.DENIED
			}
		}

		val hasAutomaticDemand = durableDemands.any {
			it.purpose == SourceBrokerPurpose.CONTROL_AUTOSTART
		}
		val automaticCaptureReachable = policies.any { policy ->
			reachability.hasCaptureMode(policy.sourceKind, AUTOMATIC_CAPTURE_MASK) &&
				policy.enabled &&
				policy.captureConsentEpoch != null &&
				policy.capturePersistenceEligible
		}
		return ActivityAcquisitionEligibility(
			sourceEligible = true,
			automaticControlEligible = hasAutomaticDemand && automaticCaptureReachable,
			automaticControlRefreshPending = automaticControlRefreshPending,
			authorizedDemandsWhileRefreshPending = authorizedDemands,
		)
	}

	/** Resolves the immutable service-run mode named by a session demand. Unknown intent fails closed. */
	private suspend fun SourceDemandEntity.sessionCaptureModeMaskInTransaction(): Long? {
		val runId = serviceRunId ?: return null
		val trackingId = logicalTrackingId ?: return null
		val run = database.sourceSessionDao().serviceRun(runId) ?: return null
		if (run.logicalTrackingId != trackingId) return null
		return when {
			run.startIsAmbient -> AMBIENT_CAPTURE_MASK
			run.startIsUserInitiated -> MANUAL_CAPTURE_MASK
			run.startOrigin in AUTOMATIC_RUN_ORIGINS -> AUTOMATIC_CAPTURE_MASK
			else -> null
		}
	}

	private fun TrackingRolloutStateEntity.eventReachability(): DeclaredRolloutReachability? {
		if (schemaVersion != CURRENT_ROLLOUT_SCHEMA || coordinatorMode != "EVENT") return null
		val owners = decodeSourceMap(sourceOwners) ?: return null
		val projections = decodeProjectionMap(projectionMode) ?: return null
		if (owners.keys != SOURCE_KINDS || projections.keys != SOURCE_KINDS) return null
		if (owners.values.any { it !in SOURCE_OWNER_MODES }) return null
		if (SOURCE_KINDS.any { sourceKind ->
			val projection = projections.getValue(sourceKind)
			val eventOwned = owners[sourceKind] == "EVENT"
			eventOwned != (projection.productStage in EVENT_PROJECTION_STAGES) ||
				eventOwned != (projection.captureModeMask != 0L)
		}) return null

		return DeclaredRolloutReachability(
			captureLanes = SOURCE_KINDS
				.filter { sourceKind -> owners[sourceKind] == "EVENT" }
				.associateWith(projections::getValue),
			explicitControlSources = SOURCE_KINDS.filterTo(mutableSetOf()) { sourceKind ->
				owners[sourceKind] == "CONTROL"
			},
		)
	}

	private fun decodeSourceMap(encoded: String): Map<Int, String>? = runCatching {
		encoded.split(',')
			.filter(String::isNotBlank)
			.associate { value ->
				val (sourceKind, state) = value.split(':', limit = 2)
				sourceKind.toInt() to state
		}
	}.getOrNull()

	private fun decodeProjectionMap(encoded: String): Map<Int, DeclaredCaptureLane>? = runCatching {
		encoded.split(',')
			.filter(String::isNotBlank)
			.associate { value ->
				val parts = value.split(':')
				require(parts.size == 3)
				val productStage = parts[1]
				val captureModeMask = parts[2].toLong()
				require(productStage in PROJECTION_STAGES)
				require(captureModeMask >= 0L && captureModeMask and ALL_CAPTURE_MASK.inv() == 0L)
				parts[0].toInt() to DeclaredCaptureLane(productStage, captureModeMask)
			}
	}.getOrNull()

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

	private fun ActivityRegistrationIdentity.matches(
		physicalConfigurationFingerprint: String,
		clockDomainId: String,
		collectedDataEpoch: Long,
	): Boolean =
		this.physicalConfigurationFingerprint == physicalConfigurationFingerprint &&
			this.clockDomainId == clockDomainId &&
			this.collectedDataEpoch == collectedDataEpoch

	private fun applied(snapshot: ActivityRegistrationSnapshot) =
		ActivityRegistrationResult(ActivityRegistrationStatus.APPLIED, snapshot)

	private fun failure(
		status: ActivityRegistrationStatus,
		code: ActivityRegistrationFailureCode,
		retryable: Boolean,
	) = ActivityRegistrationResult(status, current, code, retryable)

	private fun registrationMutationFailureCode(failure: Exception): ActivityRegistrationFailureCode =
		if (failure is ActivityCallbackBarrierNotDrainedException) {
			ActivityRegistrationFailureCode.CALLBACK_DRAIN_PENDING
		} else {
			ActivityRegistrationFailureCode.STORAGE_UNAVAILABLE
		}

	private data class CombinedDemand(
		val owners: Set<ActivityRegistrationOwner>,
		val intervalSeconds: Int?,
		val transitions: Set<com.adsamcik.tracker.activity.ActivityTransitionData>,
		val automaticRecognitionEligible: Boolean,
		val automaticTransitions: Set<com.adsamcik.tracker.activity.ActivityTransitionData>,
		val appliedRevision: Long?,
	) {
		val enabled: Boolean get() = intervalSeconds != null || transitions.isNotEmpty()
		val callbackMetadata = ActivityCallbackMetadata(
			automaticRecognitionEligible,
			automaticTransitions,
		)

		fun toRecognitionConfig() = RecognitionConfig(
			intervalSeconds = intervalSeconds ?: 0,
			requestedTransitions = transitions,
			automaticRecognitionEligible = automaticRecognitionEligible,
			automaticTransitions = automaticTransitions,
		)

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
			}
			return MessageDigest.getInstance("SHA-256")
				.digest(canonical.toByteArray(Charsets.UTF_8))
				.joinToString("") { byte -> "%02x".format(byte) }
		}
	}

	private data class ActivityCallbackMetadata(
		val automaticRecognitionEligible: Boolean,
		val automaticTransitions: Set<com.adsamcik.tracker.activity.ActivityTransitionData>,
	)

	private data class ReservedActivityRegistration(
		val identity: ActivityRegistrationIdentity,
		val state: SourceRegistrationStateEntity,
		val predecessorState: SourceRegistrationStateEntity?,
	)

	private data class ActivityRegistrationActivation(
		val previous: ProviderRegistrationGenerationEntity?,
	)

	private data class AuthorizationRotationResult(
		val continuationEligible: Boolean,
		val requiresCaptureDrain: Boolean = false,
	)

	private data class FreshActivityAuthorization(
		val authorizedDemands: List<SourceDemandEntity>,
		val result: AuthorizationRotationResult,
	)

	private data class ActivityAcquisitionEligibility(
		val sourceEligible: Boolean,
		val automaticControlEligible: Boolean,
		val automaticControlRefreshPending: Boolean,
		val authorizedDemandsWhileRefreshPending: List<SourceDemandEntity>,
	) {
		fun allows(combined: CombinedDemand): Boolean =
			sourceEligible &&
				(ActivityRegistrationOwner.AUTOMATIC_START_MONITOR !in combined.owners ||
					automaticControlEligible)

		companion object {
			val DENIED = ActivityAcquisitionEligibility(false, false, false, emptyList())
		}
	}

	private data class RolloutReachability(
		val captureModeMasks: Map<Int, Long>,
		val controlSources: Set<Int>,
	) {
		fun hasAnyCapture(sourceKind: Int): Boolean = captureModeMasks[sourceKind]?.let { it != 0L } == true

		fun hasCaptureMode(sourceKind: Int, modeMask: Long): Boolean =
			captureModeMasks[sourceKind]?.let { it and modeMask != 0L } == true
	}

	private data class DeclaredRolloutReachability(
		val captureLanes: Map<Int, DeclaredCaptureLane>,
		val explicitControlSources: Set<Int>,
	)

	private data class DeclaredCaptureLane(
		val productStage: String,
		val captureModeMask: Long,
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
		const val CURRENT_ROLLOUT_SCHEMA = 4
		const val MANUAL_CAPTURE_MASK = 1L shl 0
		const val AUTOMATIC_CAPTURE_MASK = 1L shl 1
		const val AMBIENT_CAPTURE_MASK = 1L shl 2
		const val ALL_CAPTURE_MASK = (1L shl 3) - 1L
		const val OWNER_SCOPE = "source-broker:2"
		const val ACTIVITY_CALLBACK_BARRIER_TIMEOUT_MS = 10_000L
		val CAPTURE_PURPOSES = setOf(
			SourceBrokerPurpose.SESSION_CAPTURE,
			SourceBrokerPurpose.AMBIENT_PRODUCT,
		)
		val AUTOMATIC_RUN_ORIGINS = setOf(
			"AUTOMATIC_BACKGROUND_START",
			"RECOVERY",
			"POLICY_RECONCILIATION",
		)
		val SOURCE_KINDS = (1..6).toSet()
		val SOURCE_OWNER_MODES = setOf("LEGACY", "EVENT", "CONTROL", "CONTAINED")
		val PROJECTION_STAGES = setOf("LEGACY_CANONICAL", "EVENT_SHADOW", "EVENT_CANONICAL")
		val EVENT_PROJECTION_STAGES = setOf("EVENT_SHADOW", "EVENT_CANONICAL")
		val EMPTY_SNAPSHOT = ActivityRegistrationSnapshot(false, null, emptySet(), null, emptySet())
	}
}

private class ActivityCallbackBarrierNotDrainedException(
	identity: ActivityRegistrationIdentity,
) : IllegalStateException(
	"Activity callback generation ${identity.registrationGeneration} did not reach a terminal drain",
)
