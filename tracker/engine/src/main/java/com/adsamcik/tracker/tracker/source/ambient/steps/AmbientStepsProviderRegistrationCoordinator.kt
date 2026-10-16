package com.adsamcik.tracker.tracker.source.ambient.steps

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.time.Clock
import com.adsamcik.tracker.tracker.source.runtime.BootClockDomainProvider
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Provider calls are idempotent and global to one Ambient Steps mechanism. */
internal interface AmbientStepsProviderBackend {
	val provider: AmbientStepsProvider

	/** Ensures that the system-owned provider is available for later opportunistic import. */
	suspend fun ensureActive()

	/** Removes this provider's system-owned collection when it has no accepted generation. */
	suspend fun remove()
}

internal enum class AmbientStepsProviderRegistrationFailure {
	DURABLE_AUTHORITY_REJECTED,
	PROVIDER_ACTIVATION_FAILED,
	PROVIDER_REMOVAL_FAILED,
	PROVIDER_IDENTITY_INVALID,
	AUTHORITY_CHANGED_DURING_ACTIVATION,
	CLEANUP_JOURNAL_UNAVAILABLE,
	PROVIDER_CLEANUP_STATE_INVALID,
}

internal sealed interface AmbientStepsProviderRegistrationResult {
	data class Active(
		val provider: AmbientStepsProvider,
		val registrationGeneration: Long,
		val importAccess: AmbientStepsImportAccess,
		val optionalPermissions: Set<AmbientStepsPermission>,
		val rearmedInThisProcess: Boolean,
	) : AmbientStepsProviderRegistrationResult

	data class Inactive(
		val demandState: AmbientStepsDemandReconciliation,
	) : AmbientStepsProviderRegistrationResult {
		init {
			require(demandState !is AmbientStepsDemandReconciliation.DemandReady)
		}
	}

	data class Degraded(
		val selectedProvider: AmbientStepsProvider?,
		val activeRegistrationGeneration: Long?,
		val failure: AmbientStepsProviderRegistrationFailure,
		val retryable: Boolean,
	) : AmbientStepsProviderRegistrationResult

	data class Failed(
		val selectedProvider: AmbientStepsProvider?,
		val failure: AmbientStepsProviderRegistrationFailure,
		val retryable: Boolean,
	) : AmbientStepsProviderRegistrationResult
}

internal data class AmbientStepsProviderCleanupResult(
	val complete: Boolean,
	val pendingProviders: Set<AmbientStepsProvider>,
	val failure: AmbientStepsProviderRegistrationFailure? = null,
	val retryable: Boolean = false,
) {
	init {
		require(complete == (pendingProviders.isEmpty() && failure == null))
		require(failure != null || !retryable)
	}
}

/**
 * Serializes the provider side of Ambient Steps demand reconciliation.
 *
 * Room is authoritative before and after every provider call, but no provider API runs inside a
 * Room transaction. A RESERVED row therefore survives interruption and is retried or explicitly
 * cleaned on the next reconciliation. Cleanup debt is journalled outside collected storage before
 * provider work. Concrete Android adapters and application lifecycle wiring remain separate gates.
 */
internal class AmbientStepsProviderRegistrationCoordinator(
	private val registrations: AmbientStepsProviderRegistrationRepository,
	private val cleanupStore: AmbientStepsProviderCleanupStore,
	providerBackends: Set<AmbientStepsProviderBackend>,
	private val drainPredecessor: suspend (
		ProviderRegistrationGenerationEntity,
		ProviderRegistrationGenerationEntity,
	) -> Boolean = { _, _ -> true },
) {
	@Inject
	constructor(
		registrations: AmbientStepsProviderRegistrationRepository,
		cleanupStore: AmbientStepsProviderCleanupStore,
		healthConnectBackend: HealthConnectAmbientStepsProviderBackend,
		localRecordingBackend: LocalRecordingAmbientStepsProviderBackend,
		handoffCoordinator: AmbientStepsProviderHandoffCoordinator,
		capabilityResolver: AndroidAmbientStepsCapabilityResolver,
		clock: Clock,
		bootClockDomainProvider: BootClockDomainProvider,
	) : this(
		registrations = registrations,
		cleanupStore = cleanupStore,
		providerBackends = setOf(healthConnectBackend, localRecordingBackend),
		drainPredecessor = { predecessor, successor ->
			withTimeoutOrNull(AmbientStepsImportRunner.ATTEMPT_TIMEOUT_MILLIS) {
				val platform = capabilityResolver.snapshot()
				val oldProvider = requireNotNull(predecessor.ambientStepsProviderOrNull())
				val canRead = when (oldProvider) {
					AmbientStepsProvider.HEALTH_CONNECT_MOBILE_STEPS ->
						platform.healthConnect == HealthConnectAmbientStepsAvailability.AVAILABLE &&
							platform.healthConnectReadStepsGranted &&
							(platform.healthConnectBackgroundReadGranted || ProcessLifecycleOwner.get()
								.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
					AmbientStepsProvider.LOCAL_RECORDING_STEPS ->
						platform.localRecording == LocalRecordingAmbientStepsAvailability.AVAILABLE &&
							(!platform.activityRecognitionRequired || platform.activityRecognitionGranted)
				}
				handoffCoordinator.execute(
					AmbientStepsProviderHandoffCommand(
						predecessorRegistrationGeneration = predecessor.registrationGeneration,
						successorRegistrationGeneration = successor.registrationGeneration,
						observedBootId = bootClockDomainProvider.current(),
						observedElapsedRealtimeNanos = clock.elapsedRealtimeNanos(),
						observedAtMs = clock.currentTimeMillis(),
						zoneId = ZoneId.systemDefault(),
						predecessorReadAllowed = canRead,
					),
				) is AmbientStepsProviderHandoffResult.Completed
			} == true
		},
	)

	private val mutex = Mutex()
	private val backends = providerBackends.associateBy(AmbientStepsProviderBackend::provider).also { indexed ->
		require(indexed.size == providerBackends.size) { "Ambient Steps provider backends must be unique" }
		require(indexed.keys == AmbientStepsProvider.entries.toSet()) {
			"Every Ambient Steps provider requires one backend"
		}
	}
	private val rearmedIdentities = mutableSetOf<String>()

	internal suspend fun reconcile(
		demandState: AmbientStepsDemandReconciliation,
		boundary: AmbientStepsDemandBoundary,
	): AmbientStepsProviderRegistrationResult = mutex.withLock {
		cleanupOrphanedJournalProviders()?.let { return@withLock it }
		when (demandState) {
			is AmbientStepsDemandReconciliation.DemandReady ->
				reconcileReady(demandState, boundary)
			else -> if (!demandState.retirementComplete) {
				failureWithCurrent(
					selectedProvider = null,
					failure =
						AmbientStepsProviderRegistrationFailure.DURABLE_AUTHORITY_REJECTED,
				)
			} else {
				reconcileInactive(demandState, boundary)
			}
		}
	}

	/** Removes every journalled provider without depending on collected Room rows. */
	internal suspend fun closeForCollectedDataDeletion(): AmbientStepsProviderCleanupResult =
		mutex.withLock {
			rearmedIdentities.clear()
			val pending = try {
				cleanupStore.read().pending
			} catch (error: AmbientStepsProviderCleanupStoreException) {
				return@withLock AmbientStepsProviderCleanupResult(
					complete = false,
					pendingProviders = AmbientStepsProvider.entries.toSet(),
					failure = error.toRegistrationFailure(),
					retryable = !error.corrupt,
				)
			}
			for (provider in pending) {
				if (!callRemoveProvider(provider)) {
					return@withLock cleanupStore.cleanupResult(
						AmbientStepsProviderRegistrationFailure.PROVIDER_REMOVAL_FAILED,
					)
				}
				try {
					cleanupStore.removePending(provider)
				} catch (error: AmbientStepsProviderCleanupStoreException) {
					return@withLock cleanupStore.cleanupResult(error.toRegistrationFailure(), !error.corrupt)
				}
			}
			AmbientStepsProviderCleanupResult(complete = true, pendingProviders = emptySet())
		}

	// Keep reservation, activation, acceptance, predecessor and remaining cleanup failure exits ordered.
	@Suppress("ReturnCount")
	private suspend fun reconcileReady(
		demand: AmbientStepsDemandReconciliation.DemandReady,
		boundary: AmbientStepsDemandBoundary,
	): AmbientStepsProviderRegistrationResult {
		val pendingCleanup = cleanupMismatchedReservations(demand.provider, boundary)
		if (pendingCleanup != null) {
			return pendingCleanup
		}

		val reservation = try {
			registrations.reserve(demand.provider, demand.demandId, boundary)
		} catch (cancellation: CancellationException) {
			throw cancellation
		} catch (_: Exception) {
			return failureWithCurrent(
				selectedProvider = demand.provider,
				failure = AmbientStepsProviderRegistrationFailure.DURABLE_AUTHORITY_REJECTED,
			)
		}
		val identityKey = reservation.identityKey()
		val requiresRearm = reservation.requiresProviderAcceptance || identityKey !in rearmedIdentities
		if (requiresRearm) {
			rearmProvider(demand.provider, reservation, boundary)?.let { return it }
		}
		acceptAndRetirePredecessor(reservation, identityKey, boundary, requiresRearm)?.let { return it }
		val remainingCleanup = cleanupRetirements(registrations.currentActive())
		if (remainingCleanup != null) {
			return remainingCleanup
		}

		return AmbientStepsProviderRegistrationResult.Active(
			provider = demand.provider,
			registrationGeneration = reservation.state.registrationGeneration,
			importAccess = demand.importAccess,
			optionalPermissions = demand.optionalPermissions,
			rearmedInThisProcess = requiresRearm,
		)
	}

	private suspend fun acceptAndRetirePredecessor(
		reservation: AmbientStepsProviderRegistration,
		identityKey: String,
		boundary: AmbientStepsDemandBoundary,
		requiresRearm: Boolean,
	): AmbientStepsProviderRegistrationResult? {
		if (!reservation.requiresProviderAcceptance) {
			if (requiresRearm) {
				rearmedIdentities += identityKey
			}
			return null
		}
		val predecessor = try {
			registrations.accept(reservation)
		} catch (cancellation: CancellationException) {
			throw cancellation
		} catch (_: Exception) {
			return cleanupRejectedReservation(reservation, boundary,
				AmbientStepsProviderRegistrationFailure.AUTHORITY_CHANGED_DURING_ACTIVATION,
				"Ambient Steps provider reservation changed before rejection")
		}
		rearmedIdentities += identityKey
		if (predecessor != null) {
			val cleanup = cleanupRetirement(predecessor, registrations.currentActive())
			if (cleanup != null) {
				return cleanup
			}
		}
		return null
	}

	private suspend fun rearmProvider(
		provider: AmbientStepsProvider,
		reservation: AmbientStepsProviderRegistration,
		boundary: AmbientStepsDemandBoundary,
	): AmbientStepsProviderRegistrationResult? {
		try {
			cleanupStore.addPending(provider)
		} catch (error: AmbientStepsProviderCleanupStoreException) {
			return failureWithCurrent(provider, error.toRegistrationFailure(), !error.corrupt)
		}
		try {
			check(withTimeoutOrNull(AmbientStepsImportRunner.ATTEMPT_TIMEOUT_MILLIS) {
				requireNotNull(backends[provider]).ensureActive()
				true
			} == true) { "Ambient Steps provider activation timed out" }
		} catch (cancellation: CancellationException) {
			throw cancellation
		} catch (_: Exception) {
			if (!reservation.requiresProviderAcceptance) {
				return failureWithCurrent(reservation.provider,
					AmbientStepsProviderRegistrationFailure.PROVIDER_ACTIVATION_FAILED)
			}
			return cleanupRejectedReservation(reservation, boundary,
				AmbientStepsProviderRegistrationFailure.PROVIDER_ACTIVATION_FAILED,
				"Ambient Steps provider reservation changed before failure")
		}
		return null
	}

	// Keep ordered durable-read, physical cleanup and retryable failure exits explicit.
	@Suppress("ReturnCount")
	private suspend fun cleanupOrphanedJournalProviders():
		AmbientStepsProviderRegistrationResult? {
		val pending = try {
			cleanupStore.read().pending
		} catch (error: AmbientStepsProviderCleanupStoreException) {
			return failureWithCurrent(
				selectedProvider = null,
				failure = error.toRegistrationFailure(),
				retryable = !error.corrupt,
			)
		}
		for (provider in pending) {
			val owned = try {
				registrations.hasNonterminalProvider(provider)
			} catch (cancellation: CancellationException) {
				throw cancellation
			} catch (_: Exception) {
				return failureWithCurrent(
					selectedProvider = null,
					failure = AmbientStepsProviderRegistrationFailure.DURABLE_AUTHORITY_REJECTED,
				)
			}
			if (owned) {
				continue
			}
			if (!callRemoveProvider(provider)) {
				return failureWithCurrent(
					selectedProvider = null,
					failure = AmbientStepsProviderRegistrationFailure.PROVIDER_REMOVAL_FAILED,
				)
			}
			try {
				cleanupStore.removePending(provider)
			} catch (error: AmbientStepsProviderCleanupStoreException) {
				return failureWithCurrent(
					selectedProvider = null,
					failure = error.toRegistrationFailure(),
					retryable = !error.corrupt,
				)
			}
		}
		return null
	}

	// Keep ordered durable-read, physical cleanup and retryable failure exits explicit.
	@Suppress("ReturnCount")
	private suspend fun reconcileInactive(
		demandState: AmbientStepsDemandReconciliation,
		boundary: AmbientStepsDemandBoundary,
	): AmbientStepsProviderRegistrationResult {
		val reservationCleanup = cleanupMismatchedReservations(null, boundary)
		if (reservationCleanup != null) {
			return reservationCleanup
		}

		val active = registrations.currentActive()
		if (active != null) {
			val retiring = try {
				registrations.markActiveRetiring(active, "AMBIENT_DEMAND_REMOVED", boundary)
			} catch (cancellation: CancellationException) {
				throw cancellation
			} catch (_: Exception) {
				return failureWithCurrent(
					selectedProvider = null,
					failure = AmbientStepsProviderRegistrationFailure.DURABLE_AUTHORITY_REJECTED,
				)
			}
			if (retiring == null) {
				return failureWithCurrent(
					selectedProvider = null,
					failure = AmbientStepsProviderRegistrationFailure.DURABLE_AUTHORITY_REJECTED,
				)
			}
		}
		val cleanup = cleanupRetirements(current = null)
		if (cleanup != null) {
			return cleanup
		}
		return AmbientStepsProviderRegistrationResult.Inactive(demandState)
	}

	private suspend fun cleanupRejectedReservation(
		reservation: AmbientStepsProviderRegistration,
		boundary: AmbientStepsDemandBoundary,
		failure: AmbientStepsProviderRegistrationFailure,
		failureMessage: String,
	): AmbientStepsProviderRegistrationResult {
		val predecessorProvider = reservation.predecessorState?.let { predecessor ->
			registrations.currentActive()
				?.takeIf { current ->
					current.registrationGeneration == predecessor.registrationGeneration &&
						current.sourceInstanceId == predecessor.sourceInstanceId
				}
				?.ambientStepsProviderOrNull()
		}
		val cleanupComplete = if (predecessorProvider == reservation.provider) {
			// The provider is global, so removing it would also disable the still-current generation.
			true
		} else {
			callRemoveProvider(reservation.provider)
		}
		if (cleanupComplete) {
			try {
				check(registrations.failUnaccepted(
					reservation,
					failure.name,
					boundary,
				)) { failureMessage }
				clearJournalIfUnowned(reservation.provider)?.let { return it }
			} catch (cancellation: CancellationException) {
				throw cancellation
			} catch (_: Exception) {
				return failureWithCurrent(
					selectedProvider = reservation.provider,
					failure = AmbientStepsProviderRegistrationFailure.DURABLE_AUTHORITY_REJECTED,
				)
			}
		}
		return failureWithCurrent(
			selectedProvider = reservation.provider,
			failure = if (cleanupComplete) {
				failure
			} else {
				AmbientStepsProviderRegistrationFailure.PROVIDER_REMOVAL_FAILED
			},
		)
	}

	private suspend fun cleanupMismatchedReservations(
		desiredProvider: AmbientStepsProvider?,
		boundary: AmbientStepsDemandBoundary,
	): AmbientStepsProviderRegistrationResult? {
		val pending = try {
			registrations.pendingReservations()
		} catch (cancellation: CancellationException) {
			throw cancellation
		} catch (_: Exception) {
			return failureWithCurrent(
				desiredProvider,
				AmbientStepsProviderRegistrationFailure.DURABLE_AUTHORITY_REJECTED,
			)
		}
		for (registration in pending) {
			cleanupMismatchedReservation(registration, desiredProvider, boundary)?.let { return it }
		}
		return null
	}

	// Keep identity, same-provider skip, physical removal and exact reservation cleanup exits ordered.
	@Suppress("ReturnCount")
	private suspend fun cleanupMismatchedReservation(
		registration: ProviderRegistrationGenerationEntity,
		desiredProvider: AmbientStepsProvider?,
		boundary: AmbientStepsDemandBoundary,
	): AmbientStepsProviderRegistrationResult? {
		val provider = registration.ambientStepsProviderOrNull() ?: return failureWithCurrent(
			desiredProvider,
			AmbientStepsProviderRegistrationFailure.PROVIDER_IDENTITY_INVALID,
		)
		if (provider == desiredProvider) {
			return null
		}
		if (!callRemoveProvider(provider)) {
			return failureWithCurrent(
				desiredProvider,
				AmbientStepsProviderRegistrationFailure.PROVIDER_REMOVAL_FAILED,
			)
		}
		try {
			check(registrations.failUnaccepted(
				registration,
				"SUPERSEDED_UNACCEPTED_PROVIDER",
				boundary,
			)) { "Ambient Steps provider reservation changed during cleanup" }
			clearJournalIfUnowned(provider)?.let { return it }
		} catch (cancellation: CancellationException) {
			throw cancellation
		} catch (_: Exception) {
			return failureWithCurrent(
				desiredProvider,
				AmbientStepsProviderRegistrationFailure.DURABLE_AUTHORITY_REJECTED,
			)
		}
		return null
	}

	private suspend fun cleanupRetirements(
		current: ProviderRegistrationGenerationEntity?,
	): AmbientStepsProviderRegistrationResult? {
		val pending = try {
			registrations.pendingRetirements()
		} catch (cancellation: CancellationException) {
			throw cancellation
		} catch (_: Exception) {
			return failureWithCurrent(
				current?.ambientStepsProviderOrNull(),
				AmbientStepsProviderRegistrationFailure.DURABLE_AUTHORITY_REJECTED,
			)
		}
		for (registration in pending) {
			val failure = cleanupRetirement(registration, current)
			if (failure != null) {
				return failure
			}
		}
		return null
	}

	private suspend fun cleanupRetirement(
		registration: ProviderRegistrationGenerationEntity,
		current: ProviderRegistrationGenerationEntity?,
	): AmbientStepsProviderRegistrationResult? {
		val provider = registration.ambientStepsProviderOrNull() ?: return failureWithCurrent(
			current?.ambientStepsProviderOrNull(),
			AmbientStepsProviderRegistrationFailure.PROVIDER_IDENTITY_INVALID,
		)
		val currentProvider = current?.ambientStepsProviderOrNull()
		drainRetiringPredecessor(registration, current, currentProvider, provider)?.let { return it }
		val sharesGlobalProvider = current != null &&
			current.registrationGeneration != registration.registrationGeneration &&
			currentProvider == provider
		if (!sharesGlobalProvider && !callRemoveProvider(provider)) {
			return failureWithCurrent(
				currentProvider,
				AmbientStepsProviderRegistrationFailure.PROVIDER_REMOVAL_FAILED,
			)
		}
		return try {
			if (!registrations.completeRetirement(registration)) {
				failureWithCurrent(
					currentProvider,
					AmbientStepsProviderRegistrationFailure.DURABLE_AUTHORITY_REJECTED,
				)
			} else {
				rearmedIdentities -= registration.identityKey()
				clearJournalIfUnowned(provider)
			}
		} catch (cancellation: CancellationException) {
			throw cancellation
		} catch (_: Exception) {
			failureWithCurrent(
				currentProvider,
				AmbientStepsProviderRegistrationFailure.DURABLE_AUTHORITY_REJECTED,
			)
		}
	}

	private suspend fun drainRetiringPredecessor(
		registration: ProviderRegistrationGenerationEntity,
		current: ProviderRegistrationGenerationEntity?,
		currentProvider: AmbientStepsProvider?,
		provider: AmbientStepsProvider,
	): AmbientStepsProviderRegistrationResult? {
		if (current != null && currentProvider != provider &&
			current.clockDomainId == registration.clockDomainId &&
			current.collectedDataEpoch == registration.collectedDataEpoch
		) {
			val drained = try {
				drainPredecessor(registration, current)
			} catch (cancelled: CancellationException) {
				throw cancelled
			} catch (_: Exception) {
				false
			}
			if (!drained) {
				return failureWithCurrent(
				currentProvider, AmbientStepsProviderRegistrationFailure.DURABLE_AUTHORITY_REJECTED,
			)
			}
		}
		return null
	}

	private suspend fun callRemoveProvider(provider: AmbientStepsProvider): Boolean = try {
		withTimeoutOrNull(AmbientStepsImportRunner.ATTEMPT_TIMEOUT_MILLIS) {
			requireNotNull(backends[provider]).remove()
			true
		} == true
	} catch (cancellation: CancellationException) {
		throw cancellation
	} catch (_: Exception) {
		false
	}

	private suspend fun clearJournalIfUnowned(
		provider: AmbientStepsProvider,
	): AmbientStepsProviderRegistrationResult? {
		val owned = try {
			registrations.hasNonterminalProvider(provider)
		} catch (cancellation: CancellationException) {
			throw cancellation
		} catch (_: Exception) {
			return failureWithCurrent(
				provider,
				AmbientStepsProviderRegistrationFailure.DURABLE_AUTHORITY_REJECTED,
			)
		}
		if (owned) {
			return null
		}
		return try {
			cleanupStore.removePending(provider)
			null
		} catch (error: AmbientStepsProviderCleanupStoreException) {
			failureWithCurrent(
				selectedProvider = provider,
				failure = error.toRegistrationFailure(),
				retryable = !error.corrupt,
			)
		}
	}

	private suspend fun failureWithCurrent(
		selectedProvider: AmbientStepsProvider?,
		failure: AmbientStepsProviderRegistrationFailure,
		retryable: Boolean = true,
	): AmbientStepsProviderRegistrationResult {
		val current = try {
			registrations.currentActive()
		} catch (cancellation: CancellationException) {
			throw cancellation
		} catch (_: Exception) {
			null
		}
		val cleanupDebt = try {
			registrations.pendingReservations().isNotEmpty() ||
				registrations.pendingRetirements().isNotEmpty()
		} catch (cancellation: CancellationException) {
			throw cancellation
		} catch (_: Exception) {
			false
		}
		return if (current != null || cleanupDebt) {
			AmbientStepsProviderRegistrationResult.Degraded(
				selectedProvider = selectedProvider,
				activeRegistrationGeneration = current?.registrationGeneration,
				failure = failure,
				retryable = retryable,
			)
		} else {
			AmbientStepsProviderRegistrationResult.Failed(
				selectedProvider = selectedProvider,
				failure = failure,
				retryable = retryable,
			)
		}
	}

}

private fun AmbientStepsProviderCleanupStore.cleanupResult(
	failure: AmbientStepsProviderRegistrationFailure,
	retryable: Boolean = true,
): AmbientStepsProviderCleanupResult {
	val pending = try {
		read().pending
	} catch (_: AmbientStepsProviderCleanupStoreException) {
		AmbientStepsProvider.entries.toSet()
	}
	return AmbientStepsProviderCleanupResult(
		complete = false,
		pendingProviders = pending,
		failure = failure,
		retryable = retryable,
	)
}

private val AmbientStepsDemandReconciliation.retirementComplete: Boolean
	get() = when (this) {
		is AmbientStepsDemandReconciliation.DemandReady -> true
		is AmbientStepsDemandReconciliation.PermissionRequired -> retirementComplete
		is AmbientStepsDemandReconciliation.PolicyBlocked -> retirementComplete
		is AmbientStepsDemandReconciliation.Unavailable -> retirementComplete
	}

private fun AmbientStepsProviderRegistration.identityKey(): String = listOf(
	state.sourceInstanceId,
	state.registrationGeneration,
	state.collectedDataEpoch,
	physicalConfigurationFingerprint,
).joinToString(":")

private fun ProviderRegistrationGenerationEntity.identityKey(): String = listOf(
	sourceInstanceId,
	registrationGeneration,
	collectedDataEpoch,
	physicalConfigurationFingerprint,
).joinToString(":")

private fun AmbientStepsProviderCleanupStoreException.toRegistrationFailure():
	AmbientStepsProviderRegistrationFailure = if (corrupt) {
	AmbientStepsProviderRegistrationFailure.PROVIDER_CLEANUP_STATE_INVALID
	} else {
	AmbientStepsProviderRegistrationFailure.CLEANUP_JOURNAL_UNAVAILABLE
	}
