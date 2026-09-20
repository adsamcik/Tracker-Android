package com.adsamcik.tracker.tracker.resilience

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStore
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.tracker.api.SourceCallerReplayReference
import com.adsamcik.tracker.tracker.failure.isTrackingOperationalFailure
import com.google.protobuf.InvalidProtocolBufferException
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

internal object ActiveTrackingSessionSerializer : Serializer<ActiveTrackingSessionProto> {
	override val defaultValue: ActiveTrackingSessionProto =
		ActiveTrackingSessionProto.getDefaultInstance()

	override suspend fun readFrom(input: InputStream): ActiveTrackingSessionProto {
		try {
			return ActiveTrackingSessionProto.parseFrom(input)
		} catch (exception: InvalidProtocolBufferException) {
			throw CorruptionException("Cannot read active tracking session proto", exception)
		}
	}

	override suspend fun writeTo(
		t: ActiveTrackingSessionProto,
		output: OutputStream,
	) {
		t.writeTo(output)
	}

}

private const val CORRUPTION_RESET_PENDING_MARKER =
	"__TRACKER_ACTIVE_SESSION_CORRUPTION_RESET_PENDING__"

internal val activeTrackingSessionCorruptionHandler =
	ReplaceFileCorruptionHandler<ActiveTrackingSessionProto> {
		ActiveTrackingSessionProto.newBuilder()
			.setPolicyTier(CORRUPTION_RESET_PENDING_MARKER)
			.build()
	}

private val Context.activeTrackingSessionDataStore: DataStore<ActiveTrackingSessionProto> by dataStore(
	fileName = "active_tracking_session.pb",
	serializer = ActiveTrackingSessionSerializer,
	corruptionHandler = activeTrackingSessionCorruptionHandler,
)

@Singleton
class DefaultActiveTrackingSessionStore internal constructor(
	private val dataStore: DataStore<ActiveTrackingSessionProto>,
	private val dispatchers: DispatchersProvider,
) : ActiveTrackingSessionStore {
	@Inject
	constructor(
		@ApplicationContext context: Context,
		dispatchers: DispatchersProvider,
	) : this(context.activeTrackingSessionDataStore, dispatchers)

	override suspend fun read(): ActiveTrackingSessionStoreResult = withContext(dispatchers.io) {
		runStoreOperation {
			// Normalize legacy descriptors as part of the read.  Older installs have no logical or
			// service-run IDs; assigning them once here prevents every subsequent read from inventing
			// a different correlation identity.
			val stored = dataStore.updateData { current ->
				current.normalizedForCurrentContract()
			}
			if (stored.isCorruptionResetPending()) {
				corruptionResetRequired()
			} else {
				ActiveTrackingSessionStoreResult.Success(
					stored.toDescriptor(),
				)
			}
		}
	}

	override suspend fun save(
		descriptor: ActiveTrackingSessionDescriptor,
	): ActiveTrackingSessionStoreResult = withContext(dispatchers.io) {
		runStoreOperation {
			var resetRequired = false
			dataStore.updateData { current ->
				if (current.requiresCorruptionReset()) {
					resetRequired = true
					current
				} else {
					descriptor.toProto()
				}
			}
			if (resetRequired) corruptionResetRequired()
			else ActiveTrackingSessionStoreResult.Success(descriptor)
		}
	}

	override suspend fun mergeServiceDescriptor(
		descriptor: ActiveTrackingSessionDescriptor,
	): ActiveTrackingSessionStoreResult = withContext(dispatchers.io) {
		runStoreOperation {
			var persisted: ActiveTrackingSessionProto? = null
			var resetRequired = false
			dataStore.updateData { current ->
				if (current.requiresCorruptionReset()) {
					resetRequired = true
					current
				} else {
					mergeServiceDescriptorForPersistence(current.toDescriptor(), descriptor)
						.toProto()
						.also { persisted = it }
				}
			}
			if (resetRequired) corruptionResetRequired()
			else ActiveTrackingSessionStoreResult.Success(persisted?.toDescriptor())
		}
	}

	override suspend fun replaceExact(
		expected: ActiveTrackingSessionDescriptor,
		replacement: ActiveTrackingSessionDescriptor,
	): ActiveTrackingSessionStoreResult = withContext(dispatchers.io) {
		runStoreOperation {
			var persisted: ActiveTrackingSessionProto? = null
			var resetRequired = false
			dataStore.updateData { current ->
				if (current.requiresCorruptionReset()) {
					resetRequired = true
					current
				} else if (current.toDescriptor() == expected) {
					replacement.toProto().also { persisted = it }
				} else {
					current.also { persisted = it }
				}
			}
			if (resetRequired) corruptionResetRequired()
			else ActiveTrackingSessionStoreResult.Success(persisted?.toDescriptor())
		}
	}

	override suspend fun bindSessionSegmentIfCurrent(
		expected: ActiveTrackingSessionDescriptor,
		sessionSegmentId: Long,
	): ActiveTrackingSessionStoreResult = withContext(dispatchers.io) {
		require(sessionSegmentId > 0L)
		runStoreOperation {
			val bound = expected.copy(sessionSegmentId = sessionSegmentId)
			var persisted: ActiveTrackingSessionProto? = null
			var resetRequired = false
			dataStore.updateData { current ->
				if (current.requiresCorruptionReset()) {
					resetRequired = true
					current
				} else when (current.toDescriptor()) {
					expected -> bound.toProto().also { persisted = it }
					bound -> current.also { persisted = it }
					else -> current.also { persisted = it }
				}
			}
			if (resetRequired) corruptionResetRequired()
			else ActiveTrackingSessionStoreResult.Success(persisted?.toDescriptor())
		}
	}

	override suspend fun clear(): ActiveTrackingSessionStoreResult = withContext(dispatchers.io) {
		runStoreOperation {
			var resetRequired = false
			dataStore.updateData { current ->
				if (current.requiresCorruptionReset()) {
					resetRequired = true
					current
				} else {
					defaultProto()
				}
			}
			if (resetRequired) corruptionResetRequired()
			else ActiveTrackingSessionStoreResult.Success(null)
		}
	}

	override suspend fun resetCorruptState(): ActiveTrackingSessionStoreResult =
		withContext(dispatchers.io) {
			runStoreOperation {
				var resetConfirmed = false
				val stored = dataStore.updateData { current ->
					if (current.isCorruptionResetPending() ||
						current == defaultProto() ||
						current.requiresCorruptionReset()
					) {
						resetConfirmed = true
						defaultProto()
					} else {
						current
					}
				}
				if (resetConfirmed) {
					ActiveTrackingSessionStoreResult.Success(null)
				} else {
					ActiveTrackingSessionStoreResult.Success(stored.toDescriptor())
				}
			}
		}

	override suspend fun clearIfCurrent(
		descriptor: ActiveTrackingSessionDescriptor,
	): ActiveTrackingSessionStoreResult = withContext(dispatchers.io) {
		runStoreOperation {
			var remaining: ActiveTrackingSessionProto? = null
			var resetRequired = false
			dataStore.updateData { current ->
				if (current.requiresCorruptionReset()) {
					resetRequired = true
					current
				} else {
					val currentDescriptor = current.toDescriptor()
					if (
						currentDescriptor?.logicalTrackingId == descriptor.logicalTrackingId &&
						currentDescriptor.serviceRunId == descriptor.serviceRunId &&
						currentDescriptor.lifecycleState == LogicalTrackingLifecycleState.STOP_CANDIDATE &&
						currentDescriptor.lifecycleRevision == descriptor.lifecycleRevision
					) {
						defaultProto().also { remaining = it }
					} else {
						remaining = current
						current
					}
				}
			}
			if (resetRequired) corruptionResetRequired()
			else ActiveTrackingSessionStoreResult.Success(remaining?.toDescriptor())
		}
	}

	override suspend fun clearExact(
		descriptor: ActiveTrackingSessionDescriptor,
	): ActiveTrackingSessionStoreResult = withContext(dispatchers.io) {
		runStoreOperation {
			var remaining: ActiveTrackingSessionProto? = null
			var resetRequired = false
			dataStore.updateData { current ->
				if (current.requiresCorruptionReset()) {
					resetRequired = true
					current
				} else if (current.toDescriptor() == descriptor) {
					defaultProto().also { remaining = it }
				} else {
					remaining = current
					current
				}
			}
			if (resetRequired) corruptionResetRequired()
			else ActiveTrackingSessionStoreResult.Success(remaining?.toDescriptor())
		}
	}

	private fun corruptionResetRequired(): ActiveTrackingSessionStoreResult.Failure =
		ActiveTrackingSessionStoreResult.Failure(
			ActiveTrackingSessionStoreCorruptionException(
				CorruptionException(
					"Corrupt active tracking session was replaced and awaits reset confirmation",
					null,
				),
			),
			ActiveTrackingSessionStoreFailureKind.CORRUPT,
		)

	private suspend inline fun runStoreOperation(
		operation: suspend () -> ActiveTrackingSessionStoreResult,
	): ActiveTrackingSessionStoreResult {
		return try {
			operation()
		} catch (exception: CancellationException) {
			throw exception
		} catch (exception: CorruptionException) {
			ActiveTrackingSessionStoreResult.Failure(
				ActiveTrackingSessionStoreCorruptionException(exception),
				ActiveTrackingSessionStoreFailureKind.CORRUPT,
			)
		} catch (exception: Exception) {
			exception.corruptionCause()?.let { corruption ->
				return ActiveTrackingSessionStoreResult.Failure(
					ActiveTrackingSessionStoreCorruptionException(corruption),
					ActiveTrackingSessionStoreFailureKind.CORRUPT,
				)
			}
			if (!exception.isTrackingOperationalFailure()) throw exception
			ActiveTrackingSessionStoreResult.Failure(
				exception,
				ActiveTrackingSessionStoreFailureKind.UNAVAILABLE,
			)
		}
	}
}

private fun defaultProto(): ActiveTrackingSessionProto =
	ActiveTrackingSessionProto.getDefaultInstance()

private fun ActiveTrackingSessionProto.isCorruptionResetPending(): Boolean =
	!active && policyTier == CORRUPTION_RESET_PENDING_MARKER

private fun ActiveTrackingSessionProto.requiresCorruptionReset(): Boolean {
	if (isCorruptionResetPending()) return true
	return try {
		toDescriptor()
		false
	} catch (_: CorruptionException) {
		true
	}
}

private fun Throwable.corruptionCause(): CorruptionException? {
	val visited = mutableSetOf<Throwable>()
	var current: Throwable? = this
	while (current != null && visited.add(current)) {
		if (current is CorruptionException) return current
		current = current.cause
	}
	return null
}

private fun ActiveTrackingSessionProto.toDescriptor(): ActiveTrackingSessionDescriptor? {
	if (!active) return null
	val tier = PolicyTier.entries.firstOrNull { it.name == policyTier }
		?.takeUnless { it == PolicyTier.OFF }
		?: corruptActiveTrackingSession("Active descriptor has an invalid policy tier")
	val persistedLifecycleState = lifecycleState.toLifecycleStateOrNull()
		?: corruptActiveTrackingSession("Active descriptor has an invalid lifecycle state")
	val stopCandidate = if (persistedLifecycleState == LogicalTrackingLifecycleState.STOP_CANDIDATE) {
		TrackingStopCandidate(
			reason = stopCandidateReason.toStopCandidateReason(),
			requestedAtEpochMs = stopCandidateRequestedAtEpochMs.takeIf { it > 0L },
		)
	} else {
		null
	}
	val persistedRestartBootId = restartBootId.takeIf { it.isNotBlank() && restartToken.isNotBlank() }
	val persistedRestartToken = restartToken.takeIf { persistedRestartBootId != null }
	return try {
		ActiveTrackingSessionDescriptor(
			isUserInitiated = userInitiated,
			isAmbient = ambient,
			policyTier = tier,
			logicalTrackingId = logicalTrackingId.ifBlank { newDescriptorCorrelationId() },
			serviceRunId = serviceRunId.ifBlank { newDescriptorCorrelationId() },
			lifecycleState = persistedLifecycleState,
			lifecycleRevision = lifecycleRevision,
			lifecycleChangedAtEpochMs = lifecycleChangedAtEpochMs.takeIf { it > 0L },
			stopCandidate = stopCandidate,
			restartBootId = persistedRestartBootId,
			restartToken = persistedRestartToken,
			sessionSegmentId = sessionSegmentId.takeIf { it > 0L },
			sourceCallerAuthorityReference = sourceCallerAuthorityReference
				.takeIf(String::isNotBlank)
				?.let(::SourceCallerReplayReference),
			pendingRetirementSourceCallerAuthorityReference =
				pendingRetirementSourceCallerAuthorityReference
					.takeIf(String::isNotBlank)
					?.let(::SourceCallerReplayReference),
			catalogReconfigurationDebt = if (
				hasCatalogReconfigurationDebt() &&
				catalogReconfigurationDebt.hasExactDesiredPlanIdentity()
			) {
				catalogReconfigurationDebt.toDebt()
			} else {
				null
			},
			appliedSourcePlanIdentity = if (hasAppliedSourcePlanIdentity()) {
				appliedSourcePlanIdentity.toIdentity()
			} else {
				null
			},
			desiredSourcePlanIdentity = if (hasDesiredSourcePlanIdentity()) {
				desiredSourcePlanIdentity.toIdentity()
			} else {
				null
			},
		)
	} catch (exception: IllegalArgumentException) {
		throw CorruptionException("Active tracking session descriptor is invalid", exception)
	}
}

private fun ActiveTrackingSessionProto.normalizedForCurrentContract(): ActiveTrackingSessionProto {
	val descriptor = toDescriptor() ?: return this
	return descriptor.toProto()
}

private fun ActiveTrackingSessionDescriptor.toProto(): ActiveTrackingSessionProto =
	ActiveTrackingSessionProto.newBuilder()
		.setActive(true)
		.setUserInitiated(isUserInitiated)
		.setAmbient(isAmbient)
		.setPolicyTier(policyTier.name)
		.setLogicalTrackingId(logicalTrackingId)
		.setServiceRunId(serviceRunId)
		.setLifecycleState(lifecycleState.name)
		.setLifecycleRevision(lifecycleRevision)
		.setLifecycleChangedAtEpochMs(lifecycleChangedAtEpochMs ?: 0L)
		.setStopCandidateReason(stopCandidate?.reason?.name.orEmpty())
		.setStopCandidateRequestedAtEpochMs(stopCandidate?.requestedAtEpochMs ?: 0L)
		.setRestartBootId(restartBootId.orEmpty())
		.setRestartToken(restartToken.orEmpty())
		.setSessionSegmentId(sessionSegmentId ?: 0L)
		.setSourceCallerAuthorityReference(sourceCallerAuthorityReference?.value.orEmpty())
		.setPendingRetirementSourceCallerAuthorityReference(
			pendingRetirementSourceCallerAuthorityReference?.value.orEmpty(),
		)
		.apply {
			catalogReconfigurationDebt?.let { debt ->
				setCatalogReconfigurationDebt(debt.toProto())
			}
			appliedSourcePlanIdentity?.let { identity ->
				setAppliedSourcePlanIdentity(identity.toProto())
			}
			desiredSourcePlanIdentity?.let { identity ->
				setDesiredSourcePlanIdentity(identity.toProto())
			}
		}
		.build()

private fun SourcePlanIdentityProto.toIdentity(): SourcePlanIdentity = try {
	SourcePlanIdentity(
		version = version,
		generation = generation,
		inputsFingerprint = inputsFingerprint,
		planFingerprint = planFingerprint,
	)
} catch (exception: IllegalArgumentException) {
	throw CorruptionException("Active source-plan identity is invalid", exception)
}

private fun SourcePlanIdentity.toProto(): SourcePlanIdentityProto =
	SourcePlanIdentityProto.newBuilder()
		.setVersion(version)
		.setGeneration(generation)
		.setInputsFingerprint(inputsFingerprint)
		.setPlanFingerprint(planFingerprint)
		.build()

private fun CatalogReconfigurationDebtProto.toDebt(): CatalogReconfigurationDebt =
	CatalogReconfigurationDebt(
		logicalTrackingId = logicalTrackingId,
		serviceRunId = serviceRunId,
		sourcePolicyRevision = sourcePolicyRevision,
		desiredPlanGeneration = desiredPlanGeneration,
		desiredPlanFingerprint = desiredPlanFingerprint,
		requestedPlanRevision = requestedPlanRevision,
		requestedPlanId = requestedPlanId,
		requestedPlanCreatedAtMs = requestedPlanCreatedAtMs,
		requestedPlans = requestedPlansList.map { plan ->
			CatalogReconfigurationSourcePlan(
				sourceStableCode = plan.sourceStableCode,
				payloadVersion = plan.payloadVersion,
				payloadBase64 = plan.payloadBase64,
				payloadChecksum = plan.payloadChecksum,
			)
		},
		deferredSourceMask = deferredSourceMask,
		clockDomainId = clockDomainId,
		zoneId = zoneId,
		foregroundCapabilityFlags = foregroundCapabilityFlags,
		controlDependencyMask = controlDependencyMask,
	)

private fun CatalogReconfigurationDebt.toProto(): CatalogReconfigurationDebtProto =
	CatalogReconfigurationDebtProto.newBuilder()
		.setLogicalTrackingId(logicalTrackingId)
		.setServiceRunId(serviceRunId)
		.setSourcePolicyRevision(sourcePolicyRevision)
		.setDesiredPlanGeneration(desiredPlanGeneration)
		.setDesiredPlanFingerprint(desiredPlanFingerprint)
		.setRequestedPlanRevision(requestedPlanRevision)
		.setRequestedPlanId(requestedPlanId)
		.setRequestedPlanCreatedAtMs(requestedPlanCreatedAtMs)
		.addAllRequestedPlans(requestedPlans.map { plan ->
			CatalogReconfigurationSourcePlanProto.newBuilder()
				.setSourceStableCode(plan.sourceStableCode)
				.setPayloadVersion(plan.payloadVersion)
				.setPayloadBase64(plan.payloadBase64)
				.setPayloadChecksum(plan.payloadChecksum)
				.build()
		})
		.setDeferredSourceMask(deferredSourceMask)
		.setClockDomainId(clockDomainId)
		.setZoneId(zoneId)
		.setForegroundCapabilityFlags(foregroundCapabilityFlags)
		.setControlDependencyMask(controlDependencyMask)
		.build()

private fun CatalogReconfigurationDebtProto.hasExactDesiredPlanIdentity(): Boolean =
	desiredPlanGeneration > 0L && desiredPlanFingerprint.matches(Regex("[0-9a-f]{64}"))

/**
 * Old proto records did not carry correlation IDs.  Keep generation here (rather than deriving
 * IDs from user flags/tier) so two independent legacy sessions can never be accidentally joined.
 */
private fun newDescriptorCorrelationId(): String = java.util.UUID.randomUUID().toString()

private fun String.toLifecycleStateOrNull(): LogicalTrackingLifecycleState? = when {
	isBlank() -> LogicalTrackingLifecycleState.ACTIVE // legacy v1 descriptor
	else -> LogicalTrackingLifecycleState.entries.firstOrNull { it.name == this }
}

private fun corruptActiveTrackingSession(message: String): Nothing =
	throw CorruptionException(message, IllegalArgumentException(message))

private fun String.toStopCandidateReason(): TrackingStopCandidateReason =
	TrackingStopCandidateReason.entries.firstOrNull { it.name == this }
		?: TrackingStopCandidateReason.UNKNOWN

private fun mergeServiceDescriptorForPersistence(
	current: ActiveTrackingSessionDescriptor?,
	proposed: ActiveTrackingSessionDescriptor,
): ActiveTrackingSessionDescriptor {
	if (current == null) return proposed
	if (
		current.logicalTrackingId != proposed.logicalTrackingId ||
		current.serviceRunId != proposed.serviceRunId
	) return current
	if (current.lifecycleRevision > proposed.lifecycleRevision ||
		(current.lifecycleRevision == proposed.lifecycleRevision &&
			current.lifecycleState != proposed.lifecycleState)
	) return current
	return proposed.copy(
		sourceCallerAuthorityReference = current.sourceCallerAuthorityReference,
		pendingRetirementSourceCallerAuthorityReference =
			current.pendingRetirementSourceCallerAuthorityReference,
		catalogReconfigurationDebt = current.catalogReconfigurationDebt,
		appliedSourcePlanIdentity = current.appliedSourcePlanIdentity,
		desiredSourcePlanIdentity = current.desiredSourcePlanIdentity,
	)
}
