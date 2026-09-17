package com.adsamcik.tracker.tracker.source.runtime

import com.adsamcik.tracker.shared.model.tracking.TrackingPurpose
import com.adsamcik.tracker.shared.model.tracking.TrackingSource
import com.adsamcik.tracker.shared.model.tracking.TrackingSourcePurposeIdentity
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyAuthorityState
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyRepository
import com.adsamcik.tracker.tracker.api.AmbientLeaseStartResult
import com.adsamcik.tracker.tracker.api.AmbientReconciliationIdentity
import com.adsamcik.tracker.tracker.api.AmbientSourceOperationalAvailability
import com.adsamcik.tracker.tracker.api.AmbientSourceReconciliationCallback
import com.adsamcik.tracker.tracker.api.AmbientSourceReconciliationReport
import com.adsamcik.tracker.tracker.api.AmbientTrackingSource
import com.adsamcik.tracker.tracker.api.AutomaticControlLeaseStartResult
import com.adsamcik.tracker.tracker.api.AutomaticControlReconciliationCallback
import com.adsamcik.tracker.tracker.api.AutomaticControlReconciliationReport
import com.adsamcik.tracker.tracker.api.AutomaticTrackingOperationalAvailability
import com.adsamcik.tracker.tracker.api.AutomaticTrackingUnavailableReason
import com.adsamcik.tracker.tracker.api.TrackingPurposeAvailabilityReporter
import com.adsamcik.tracker.tracker.api.TrackingPurposeLeaseIdentity
import com.adsamcik.tracker.tracker.api.TrackingPurposeSettingsReconciler
import com.adsamcik.tracker.tracker.api.TrackingPurposeSourceOwnerRegistrar
import com.adsamcik.tracker.tracker.api.TrackingPurposeSourceOwnerRegistration
import com.adsamcik.tracker.tracker.source.coordinator.CaptureReachabilityMode
import com.adsamcik.tracker.tracker.source.coordinator.TrackingRolloutState
import com.adsamcik.tracker.tracker.source.coordinator.TrackingRolloutStateStore
import com.adsamcik.tracker.tracker.source.model.SourceKind
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

internal data class TrackingPurposeAuthoritySnapshot(
	val sourcePurpose: TrackingSourcePurposeIdentity,
	val policyRevision: Long,
	val consentEpoch: Long,
	val collectedDataEpoch: Long,
	val rolloutRevision: Long,
	val executionRevision: Long,
) {
	init {
		require(policyRevision > 0L)
		require(consentEpoch > 0L)
		require(collectedDataEpoch >= 0L)
		require(rolloutRevision >= 0L)
		require(executionRevision >= 0L)
	}
}

internal fun interface TrackingPurposeAuthorityReader {
	suspend fun read(
		sourcePurpose: TrackingSourcePurposeIdentity,
		registeredExecutionRevision: Long,
	): TrackingPurposeAuthoritySnapshot?
}

internal fun interface TrackingPurposeOwnerCasTokenFactory {
	fun next(sourcePurpose: TrackingSourcePurposeIdentity): String
}

@Singleton
internal class CurrentTrackingPurposeAuthorityReader @Inject constructor(
	private val sourcePolicyRepository: SourcePolicyRepository,
	private val collectedDataLifecycleStore: CollectedDataLifecycleStore,
	private val rolloutStateStore: TrackingRolloutStateStore,
) : TrackingPurposeAuthorityReader {
	override suspend fun read(
		sourcePurpose: TrackingSourcePurposeIdentity,
		registeredExecutionRevision: Long,
	): TrackingPurposeAuthoritySnapshot? {
		require(registeredExecutionRevision >= 0L)
		repeat(AUTHORITY_READ_ATTEMPTS) {
			val first = readOnce(sourcePurpose, registeredExecutionRevision) ?: return null
			val second = readOnce(sourcePurpose, registeredExecutionRevision) ?: return null
			if (first == second) return first
		}
		return null
	}

	private suspend fun readOnce(
		sourcePurpose: TrackingSourcePurposeIdentity,
		registeredExecutionRevision: Long,
	): TrackingPurposeAuthoritySnapshot? {
		val policyState = sourcePolicyRepository.currentState()
		val policySnapshot = (policyState as? SourcePolicyAuthorityState.Active)?.snapshot
			?: return null
		val policy = policySnapshot[sourcePurpose.source]
		val consentEpoch = policy.consentEpoch(sourcePurpose.purpose) ?: return null
		val lifecycle = collectedDataLifecycleStore.snapshot()
		val rollout = rolloutStateStore.load()
		val executionRevision = registeredExecutionRevision.takeIf {
			policy.persistenceEligible(sourcePurpose.purpose) &&
				rollout.supportsExecution(sourcePurpose)
		} ?: 0L
		return TrackingPurposeAuthoritySnapshot(
			sourcePurpose = sourcePurpose,
			policyRevision = policySnapshot.revision,
			consentEpoch = consentEpoch,
			collectedDataEpoch = lifecycle.epoch,
			rolloutRevision = rollout.revision,
			executionRevision = executionRevision,
		)
	}

	private companion object {
		const val AUTHORITY_READ_ATTEMPTS = 3
	}
}

@Singleton
internal class SerializedTrackingPurposeLeaseIssuer @Inject constructor(
	private val authorityReader: TrackingPurposeAuthorityReader,
	private val reporter: TrackingPurposeAvailabilityReporter,
	private val tokenFactory: TrackingPurposeOwnerCasTokenFactory,
) {
	private val mutex = Mutex()
	private var automaticIdentity: TrackingPurposeLeaseIdentity? = null
	private val ambientIdentities = mutableMapOf<AmbientTrackingSource, AmbientReconciliationIdentity>()

	suspend fun refreshAutomaticControl(
		executionRevision: Long,
	): AutomaticControlLeaseStartResult.Started? = mutex.withLock {
		val sourcePurpose = ACTIVITY_CONTROL
		val authority = readAuthority(sourcePurpose, executionRevision)
		if (authority == null) {
			reporter.invalidateAutomaticControl()
			automaticIdentity = null
			return@withLock null
		}
		val identity = authority.toLeaseIdentity(tokenFactory.next(sourcePurpose))
		when (val started = reporter.beginOrReplaceAutomaticControlLease(identity)) {
			is AutomaticControlLeaseStartResult.Started -> {
				automaticIdentity = identity
				started
			}
			is AutomaticControlLeaseStartResult.Rejected -> null
		}
	}

	suspend fun refreshAmbient(
		source: AmbientTrackingSource,
		executionRevision: Long,
	): AmbientLeaseStartResult.Started? = mutex.withLock {
		val sourcePurpose = source.canonicalSource.forPurpose(TrackingPurpose.AMBIENT_PRODUCT)
		val authority = readAuthority(sourcePurpose, executionRevision)
		if (authority == null) {
			reporter.invalidateAmbient(source)
			ambientIdentities.remove(source)
			return@withLock null
		}
		val identity = AmbientReconciliationIdentity.from(
			authority.toLeaseIdentity(tokenFactory.next(sourcePurpose)),
		)
		when (val started = reporter.beginOrReplaceAmbientLease(identity)) {
			is AmbientLeaseStartResult.Started -> {
				ambientIdentities[source] = identity
				started
			}
			is AmbientLeaseStartResult.Rejected -> null
		}
	}

	suspend fun clearAutomaticControl() = mutex.withLock {
		reporter.invalidateAutomaticControl()
		automaticIdentity = null
	}

	suspend fun clearAmbient(source: AmbientTrackingSource) = mutex.withLock {
		reporter.invalidateAmbient(source)
		ambientIdentities.remove(source)
	}

	suspend fun cancelAutomaticControl(identity: TrackingPurposeLeaseIdentity) = mutex.withLock {
		reporter.cancelAutomaticControlLease(identity)
		if (automaticIdentity == identity) automaticIdentity = null
	}

	suspend fun cancelAmbient(identity: AmbientReconciliationIdentity) = mutex.withLock {
		reporter.cancelAmbientLease(identity)
		if (ambientIdentities[identity.source] == identity) {
			ambientIdentities.remove(identity.source)
		}
	}

	private suspend fun readAuthority(
		sourcePurpose: TrackingSourcePurposeIdentity,
		executionRevision: Long,
	): TrackingPurposeAuthoritySnapshot? = try {
		authorityReader.read(sourcePurpose, executionRevision)
	} catch (cancelled: CancellationException) {
		throw cancelled
	} catch (_: Exception) {
		null
	}
}

@Singleton
internal class DefaultTrackingPurposePublicationRuntime @Inject constructor(
	private val leaseIssuer: SerializedTrackingPurposeLeaseIssuer,
	private val reporter: TrackingPurposeAvailabilityReporter,
) : TrackingPurposeSettingsReconciler, TrackingPurposeSourceOwnerRegistrar {
	private val ownerMutex = Mutex()
	private var automaticOwner: AutomaticControlOwnerRegistration? = null
	private val ambientOwners = mutableMapOf<AmbientTrackingSource, AmbientOwnerRegistration>()

	override suspend fun reconcileCurrentSettings() {
		reconcileAutomaticControl()
		AmbientTrackingSource.entries.forEach { source ->
			reconcileAmbient(source)
		}
	}

	override suspend fun registerAutomaticControlOwner(
		executionRevision: Long,
		callback: AutomaticControlReconciliationCallback,
	): TrackingPurposeSourceOwnerRegistration {
		require(executionRevision >= 0L)
		val registration = TrackingPurposeSourceOwnerRegistration(
			sourcePurpose = ACTIVITY_CONTROL,
			registrationId = UUID.randomUUID().toString(),
		)
		ownerMutex.withLock {
			automaticOwner = AutomaticControlOwnerRegistration(
				registration,
				executionRevision,
				callback,
			)
		}
		reconcileAutomaticControl()
		return registration
	}

	override suspend fun registerAmbientSourceOwner(
		source: AmbientTrackingSource,
		executionRevision: Long,
		callback: AmbientSourceReconciliationCallback,
	): TrackingPurposeSourceOwnerRegistration {
		require(executionRevision >= 0L)
		val registration = TrackingPurposeSourceOwnerRegistration(
			sourcePurpose = source.canonicalSource.forPurpose(TrackingPurpose.AMBIENT_PRODUCT),
			registrationId = UUID.randomUUID().toString(),
		)
		ownerMutex.withLock {
			ambientOwners[source] = AmbientOwnerRegistration(
				registration,
				executionRevision,
				callback,
			)
		}
		reconcileAmbient(source)
		return registration
	}

	override suspend fun unregister(registration: TrackingPurposeSourceOwnerRegistration) {
		if (registration.sourcePurpose == ACTIVITY_CONTROL) {
			val removed = ownerMutex.withLock {
				automaticOwner
					?.takeIf { it.registration == registration }
					?.also { automaticOwner = null }
			}
			if (removed != null) leaseIssuer.clearAutomaticControl()
			return
		}
		val source = AmbientTrackingSource.entries.singleOrNull { candidate ->
			candidate.canonicalSource == registration.sourcePurpose.source &&
				registration.sourcePurpose.purpose == TrackingPurpose.AMBIENT_PRODUCT
		} ?: return
		val removed = ownerMutex.withLock {
			ambientOwners[source]
				?.takeIf { it.registration == registration }
				?.also { ambientOwners.remove(source) }
		}
		if (removed != null) leaseIssuer.clearAmbient(source)
	}

	private suspend fun reconcileAutomaticControl() {
		val owner = ownerMutex.withLock { automaticOwner }
		val started = leaseIssuer.refreshAutomaticControl(owner?.executionRevision ?: 0L) ?: return
		if (!isCurrentAutomaticOwner(owner)) {
			leaseIssuer.cancelAutomaticControl(started.lease.identity)
			return
		}
		val availability = if (owner == null) {
			AutomaticTrackingOperationalAvailability.Unavailable(
				reason =
					AutomaticTrackingUnavailableReason.CONTROL_RETENTION_POLICY_UNAVAILABLE,
				lastIdentity = started.lease.identity,
			)
		} else {
			safelyReconcileAutomatic(owner.callback, started.lease) ?: return
		}
		if (!isCurrentAutomaticOwner(owner)) {
			leaseIssuer.cancelAutomaticControl(started.lease.identity)
			return
		}
		val report = runCatching {
			AutomaticControlReconciliationReport(started.lease.identity, availability)
		}.getOrNull() ?: return
		reporter.tryAccept(report)
	}

	private suspend fun reconcileAmbient(source: AmbientTrackingSource) {
		val owner = ownerMutex.withLock { ambientOwners[source] }
		val started = leaseIssuer.refreshAmbient(source, owner?.executionRevision ?: 0L) ?: return
		if (!isCurrentAmbientOwner(source, owner)) {
			leaseIssuer.cancelAmbient(started.lease.identity)
			return
		}
		if (owner == null) return
		val availability = safelyReconcileAmbient(owner.callback, started.lease) ?: return
		if (!isCurrentAmbientOwner(source, owner)) {
			leaseIssuer.cancelAmbient(started.lease.identity)
			return
		}
		val report = runCatching {
			AmbientSourceReconciliationReport(started.lease.identity, availability)
		}.getOrNull() ?: return
		reporter.tryAccept(report)
	}

	private suspend fun isCurrentAutomaticOwner(
		expected: AutomaticControlOwnerRegistration?,
	): Boolean = ownerMutex.withLock { automaticOwner == expected }

	private suspend fun isCurrentAmbientOwner(
		source: AmbientTrackingSource,
		expected: AmbientOwnerRegistration?,
	): Boolean = ownerMutex.withLock { ambientOwners[source] == expected }

	private suspend fun safelyReconcileAutomatic(
		callback: AutomaticControlReconciliationCallback,
		lease: com.adsamcik.tracker.tracker.api.AutomaticControlReconciliationLease,
	): AutomaticTrackingOperationalAvailability? = try {
		withTimeoutOrNull(SOURCE_OWNER_CALLBACK_TIMEOUT_MILLIS) {
			callback.reconcile(lease)
		}
	} catch (cancelled: CancellationException) {
		throw cancelled
	} catch (_: Exception) {
		null
	}

	private suspend fun safelyReconcileAmbient(
		callback: AmbientSourceReconciliationCallback,
		lease: com.adsamcik.tracker.tracker.api.AmbientReconciliationLease,
	): AmbientSourceOperationalAvailability? = try {
		withTimeoutOrNull(SOURCE_OWNER_CALLBACK_TIMEOUT_MILLIS) {
			callback.reconcile(lease)
		}
	} catch (cancelled: CancellationException) {
		throw cancelled
	} catch (_: Exception) {
		null
	}

	private companion object {
		const val SOURCE_OWNER_CALLBACK_TIMEOUT_MILLIS = 2_000L
	}
}

private data class AutomaticControlOwnerRegistration(
	val registration: TrackingPurposeSourceOwnerRegistration,
	val executionRevision: Long,
	val callback: AutomaticControlReconciliationCallback,
)

private data class AmbientOwnerRegistration(
	val registration: TrackingPurposeSourceOwnerRegistration,
	val executionRevision: Long,
	val callback: AmbientSourceReconciliationCallback,
)

private fun TrackingPurposeAuthoritySnapshot.toLeaseIdentity(
	ownerCasToken: String,
): TrackingPurposeLeaseIdentity = TrackingPurposeLeaseIdentity(
	sourcePurpose = sourcePurpose,
	policyRevision = policyRevision,
	consentEpoch = consentEpoch,
	collectedDataEpoch = collectedDataEpoch,
	rolloutRevision = rolloutRevision,
	executionRevision = executionRevision,
	ownerCasToken = ownerCasToken,
)

private fun TrackingRolloutState.supportsExecution(
	sourcePurpose: TrackingSourcePurposeIdentity,
): Boolean {
	val sourceKind = sourcePurpose.source.toSourceKind()
	return when (sourcePurpose.purpose) {
		TrackingPurpose.CONTROL ->
			sourcePurpose.source == TrackingSource.ACTIVITY &&
				isControlAcquisitionReachable(sourceKind)
		TrackingPurpose.AMBIENT_PRODUCT ->
			isCaptureReachable(sourceKind, CaptureReachabilityMode.AMBIENT)
		TrackingPurpose.SESSION_CAPTURE -> false
	}
}

private fun TrackingSource.toSourceKind(): SourceKind = when (this) {
	TrackingSource.LOCATION -> SourceKind.LOCATION
	TrackingSource.ACTIVITY -> SourceKind.ACTIVITY
	TrackingSource.STEPS -> SourceKind.STEPS
	TrackingSource.PRESSURE -> SourceKind.PRESSURE
	TrackingSource.WIFI -> SourceKind.WIFI
	TrackingSource.CELL -> SourceKind.CELL
}

private val ACTIVITY_CONTROL =
	TrackingSource.ACTIVITY.forPurpose(TrackingPurpose.CONTROL)
