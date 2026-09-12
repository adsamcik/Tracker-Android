package com.adsamcik.tracker.tracker.source.ambient.steps

import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

/**
 * Serializes the provider side of Ambient Steps demand reconciliation.
 *
 * Room is authoritative before and after every provider call, but no provider API runs inside a
 * Room transaction. A RESERVED row therefore survives interruption and is retried or explicitly
 * cleaned on the next reconciliation. This coordinator is intentionally not wired to Android
 * startup yet; deletion-safe cleanup journaling and concrete provider adapters are separate gates.
 */
internal class AmbientStepsProviderRegistrationCoordinator(
	private val registrations: AmbientStepsProviderRegistrationRepository,
	providerBackends: Set<AmbientStepsProviderBackend>,
) {
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
		when (demandState) {
			is AmbientStepsDemandReconciliation.DemandReady ->
				reconcileReady(demandState, boundary)
			else -> reconcileInactive(demandState, boundary)
		}
	}

	private suspend fun reconcileReady(
		demand: AmbientStepsDemandReconciliation.DemandReady,
		boundary: AmbientStepsDemandBoundary,
	): AmbientStepsProviderRegistrationResult {
		val pendingCleanup = cleanupMismatchedReservations(demand.provider, boundary)
		if (pendingCleanup != null) return pendingCleanup

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
			try {
				backend(demand.provider).ensureActive()
			} catch (cancellation: CancellationException) {
				throw cancellation
			} catch (_: Exception) {
				return activationFailed(reservation, boundary)
			}
		}

		if (reservation.requiresProviderAcceptance) {
			val predecessor = try {
				registrations.accept(reservation)
			} catch (cancellation: CancellationException) {
				throw cancellation
			} catch (_: Exception) {
				return acceptanceFailed(reservation, boundary)
			}
			rearmedIdentities += identityKey
			if (predecessor != null) {
				val cleanup = cleanupRetirement(predecessor, registrations.currentActive())
				if (cleanup != null) return cleanup
			}
		} else if (requiresRearm) {
			rearmedIdentities += identityKey
		}
		val remainingCleanup = cleanupRetirements(registrations.currentActive())
		if (remainingCleanup != null) return remainingCleanup

		return AmbientStepsProviderRegistrationResult.Active(
			provider = demand.provider,
			registrationGeneration = reservation.state.registrationGeneration,
			importAccess = demand.importAccess,
			optionalPermissions = demand.optionalPermissions,
			rearmedInThisProcess = requiresRearm,
		)
	}

	private suspend fun reconcileInactive(
		demandState: AmbientStepsDemandReconciliation,
		boundary: AmbientStepsDemandBoundary,
	): AmbientStepsProviderRegistrationResult {
		val reservationCleanup = cleanupMismatchedReservations(null, boundary)
		if (reservationCleanup != null) return reservationCleanup

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
		if (cleanup != null) return cleanup
		return AmbientStepsProviderRegistrationResult.Inactive(demandState)
	}

	private suspend fun activationFailed(
		reservation: AmbientStepsProviderRegistration,
		boundary: AmbientStepsDemandBoundary,
	): AmbientStepsProviderRegistrationResult {
		if (!reservation.requiresProviderAcceptance) {
			return failureWithCurrent(
				selectedProvider = reservation.provider,
				failure = AmbientStepsProviderRegistrationFailure.PROVIDER_ACTIVATION_FAILED,
			)
		}
		val predecessorProvider = reservation.predecessorState?.let { predecessor ->
			registrations.currentActive()
				?.takeIf { current ->
					current.registrationGeneration == predecessor.registrationGeneration &&
						current.sourceInstanceId == predecessor.sourceInstanceId
				}
				?.ambientStepsProviderOrNull()
		}
		val cleanupComplete = if (predecessorProvider == reservation.provider) {
			true
		} else {
			removeProvider(reservation.provider)
		}
		if (cleanupComplete) {
			try {
				registrations.failUnaccepted(
					reservation,
					"PROVIDER_ACTIVATION_FAILED",
					boundary,
				)
			} catch (cancellation: CancellationException) {
				throw cancellation
			} catch (_: Exception) {
				return failureWithCurrent(
					selectedProvider = reservation.provider,
					failure = AmbientStepsProviderRegistrationFailure.DURABLE_AUTHORITY_REJECTED,
				)
			}
		}
		return if (cleanupComplete) {
			failureWithCurrent(
				selectedProvider = reservation.provider,
				failure = AmbientStepsProviderRegistrationFailure.PROVIDER_ACTIVATION_FAILED,
			)
		} else {
			failureWithCurrent(
				selectedProvider = reservation.provider,
				failure = AmbientStepsProviderRegistrationFailure.PROVIDER_REMOVAL_FAILED,
			)
		}
	}

	private suspend fun acceptanceFailed(
		reservation: AmbientStepsProviderRegistration,
		boundary: AmbientStepsDemandBoundary,
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
			removeProvider(reservation.provider)
		}
		if (cleanupComplete) {
			try {
				registrations.failUnaccepted(
					reservation,
					"AUTHORITY_CHANGED_DURING_ACTIVATION",
					boundary,
				)
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
				AmbientStepsProviderRegistrationFailure.AUTHORITY_CHANGED_DURING_ACTIVATION
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
			val provider = registration.ambientStepsProviderOrNull() ?: return failureWithCurrent(
				desiredProvider,
				AmbientStepsProviderRegistrationFailure.PROVIDER_IDENTITY_INVALID,
			)
			if (provider == desiredProvider) continue
			if (!removeProvider(provider)) {
				return failureWithCurrent(
					desiredProvider,
					AmbientStepsProviderRegistrationFailure.PROVIDER_REMOVAL_FAILED,
				)
			}
			try {
				registrations.failUnaccepted(
					registration,
					"SUPERSEDED_UNACCEPTED_PROVIDER",
					boundary,
				)
			} catch (cancellation: CancellationException) {
				throw cancellation
			} catch (_: Exception) {
				return failureWithCurrent(
					desiredProvider,
					AmbientStepsProviderRegistrationFailure.DURABLE_AUTHORITY_REJECTED,
				)
			}
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
			if (failure != null) return failure
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
		val sharesGlobalProvider = current != null &&
			current.registrationGeneration != registration.registrationGeneration &&
			currentProvider == provider
		if (!sharesGlobalProvider && !removeProvider(provider)) {
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
				null
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

	private suspend fun removeProvider(provider: AmbientStepsProvider): Boolean = try {
		backend(provider).remove()
		true
	} catch (cancellation: CancellationException) {
		throw cancellation
	} catch (_: Exception) {
		false
	}

	private suspend fun failureWithCurrent(
		selectedProvider: AmbientStepsProvider?,
		failure: AmbientStepsProviderRegistrationFailure,
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
				retryable = true,
			)
		} else {
			AmbientStepsProviderRegistrationResult.Failed(
				selectedProvider = selectedProvider,
				failure = failure,
				retryable = true,
			)
		}
	}

	private fun backend(provider: AmbientStepsProvider): AmbientStepsProviderBackend =
		requireNotNull(backends[provider])
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
