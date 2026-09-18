package com.adsamcik.tracker.tracker.source.runtime

import com.adsamcik.tracker.shared.model.tracking.TrackingPurpose
import com.adsamcik.tracker.shared.model.tracking.TrackingSource
import com.adsamcik.tracker.shared.model.tracking.TrackingSourcePurposeIdentity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiAuthorityEntity
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.shared.preferences.retention.CurrentRetentionAuthority
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityProducer
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityResult
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityUnavailableReason
import com.adsamcik.tracker.shared.preferences.retention.isActiveApproval
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyAuthorityState
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyRepository
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.di.ApplicationScope
import com.adsamcik.tracker.tracker.api.AmbientPublicationAcceptance
import com.adsamcik.tracker.tracker.api.AmbientLeaseStartResult
import com.adsamcik.tracker.tracker.api.AmbientReconciliationIdentity
import com.adsamcik.tracker.tracker.api.AmbientSourceOperationalAvailability
import com.adsamcik.tracker.tracker.api.AmbientSourceReconciliationCallback
import com.adsamcik.tracker.tracker.api.AmbientSourceReconciliationReport
import com.adsamcik.tracker.tracker.api.AmbientSourceReconciliationResult
import com.adsamcik.tracker.tracker.api.AmbientTrackingSource
import com.adsamcik.tracker.tracker.api.AutomaticControlLeaseStartResult
import com.adsamcik.tracker.tracker.api.AutomaticControlPublicationAcceptance
import com.adsamcik.tracker.tracker.api.AutomaticControlReconciliationCallback
import com.adsamcik.tracker.tracker.api.AutomaticControlReconciliationReport
import com.adsamcik.tracker.tracker.api.AutomaticTrackingOperationalAvailability
import com.adsamcik.tracker.tracker.api.AutomaticTrackingUnavailableReason
import com.adsamcik.tracker.tracker.api.TrackingPurposeAvailabilityReporter
import com.adsamcik.tracker.tracker.api.TrackingPurposeDeletionFencer
import com.adsamcik.tracker.tracker.api.TrackingPurposeLeaseIdentity
import com.adsamcik.tracker.tracker.api.TrackingPurposeReconciliationRetryScheduler
import com.adsamcik.tracker.tracker.api.TrackingPurposeSettingsReconciler
import com.adsamcik.tracker.tracker.api.TrackingPurposeSettingsReconciliationDebt
import com.adsamcik.tracker.tracker.api.TrackingPurposeSettingsReconciliationFailure
import com.adsamcik.tracker.tracker.api.TrackingPurposeSettingsReconciliationFailureReason
import com.adsamcik.tracker.tracker.api.TrackingPurposeSettingsReconciliationResult
import com.adsamcik.tracker.tracker.api.TrackingPurposeSourceOwnerRegistrar
import com.adsamcik.tracker.tracker.api.TrackingPurposeSourceOwnerRegistration
import com.adsamcik.tracker.tracker.api.TrackingRetentionFloorReconciler
import com.adsamcik.tracker.tracker.api.TrackingRetentionFloorReconciliationDebt
import com.adsamcik.tracker.tracker.api.TrackingRetentionFloorReconciliationFailure
import com.adsamcik.tracker.tracker.api.TrackingRetentionFloorReconciliationFailureReason
import com.adsamcik.tracker.tracker.api.TrackingRetentionFloorReconciliationResult
import com.adsamcik.tracker.tracker.source.ambient.AmbientRadioReportPreparation
import com.adsamcik.tracker.tracker.source.ambient.cell.AmbientCellActivationRequest
import com.adsamcik.tracker.tracker.source.ambient.cell.AmbientCellDemandReconciler
import com.adsamcik.tracker.tracker.source.ambient.wifi.AmbientWifiActivationRequest
import com.adsamcik.tracker.tracker.source.ambient.wifi.AmbientWifiDemandReconciler
import com.adsamcik.tracker.tracker.source.coordinator.CaptureReachabilityMode
import com.adsamcik.tracker.tracker.source.coordinator.TrackingRolloutState
import com.adsamcik.tracker.tracker.source.coordinator.TrackingRolloutStateStore
import com.adsamcik.tracker.tracker.source.model.SourceKind
import java.util.UUID
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal data class TrackingPurposeAuthoritySnapshot(
	val sourcePurpose: TrackingSourcePurposeIdentity,
	val policyRevision: Long,
	val consentEpoch: Long,
	val collectedDataEpoch: Long,
	val rolloutRevision: Long,
	val executionRevision: Long,
	val retainedFromMs: Long? = null,
	val retentionPolicyId: String? = null,
	val retentionApprovalRevision: Long? = null,
) {
	init {
		require(policyRevision > 0L)
		require(consentEpoch > 0L)
		require(collectedDataEpoch >= 0L)
		require(rolloutRevision >= 0L)
		require(executionRevision >= 0L)
		require(retainedFromMs == null || retainedFromMs >= 0L)
		require((retentionPolicyId == null) == (retentionApprovalRevision == null))
		require(retentionPolicyId == null || retentionPolicyId.isNotBlank())
		require(retentionApprovalRevision == null || retentionApprovalRevision > 0L)
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
internal class TrackingPurposeExecutionRevisionRegistry @Inject constructor() {
	private val mutableRevisions =
		MutableStateFlow<Map<TrackingSourcePurposeIdentity, Long>>(emptyMap())
	val revisions: StateFlow<Map<TrackingSourcePurposeIdentity, Long>> =
		mutableRevisions.asStateFlow()

	fun update(sourcePurpose: TrackingSourcePurposeIdentity, executionRevision: Long) {
		require(executionRevision >= 0L)
		mutableRevisions.value = mutableRevisions.value + (sourcePurpose to executionRevision)
	}

	fun remove(sourcePurpose: TrackingSourcePurposeIdentity) {
		mutableRevisions.value = mutableRevisions.value - sourcePurpose
	}
}

@Singleton
internal class CurrentTrackingPurposeAuthorityReader @Inject constructor(
	private val sourcePolicyRepository: SourcePolicyRepository,
	private val collectedDataLifecycleStore: CollectedDataLifecycleStore,
	private val rolloutStateStore: TrackingRolloutStateStore,
	private val retentionAuthorityReader: RetentionAuthorityProducer,
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
		val retention = if (sourcePurpose.purpose == TrackingPurpose.AMBIENT_PRODUCT) {
			retentionAuthorityReader.currentLiveAmbient(
				source = sourcePurpose.source,
				expectedSourcePolicyRevision = policySnapshot.revision,
				expectedAmbientConsentEpoch = consentEpoch,
				expectedCollectedDataEpoch = lifecycle.epoch,
				expectedRetainedFromMs = lifecycle.retainedFromMs,
			) as? CurrentRetentionAuthority.Approved ?: return null
		} else {
			null
		}
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
			retainedFromMs = lifecycle.retainedFromMs,
			retentionPolicyId = retention?.opaquePolicyId,
			retentionApprovalRevision = retention?.approvalRevision,
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
	private var automaticInFlight = false
	private val ambientInFlight = mutableSetOf<AmbientTrackingSource>()

	suspend fun refreshAutomaticControl(
		executionRevision: Long,
	): AutomaticLeaseRefresh = mutex.withLock {
		val sourcePurpose = ACTIVITY_CONTROL
		val authority = readAuthority(sourcePurpose, executionRevision)
		if (authority == null) {
			reporter.invalidateAutomaticControl()
			automaticIdentity = null
			automaticInFlight = false
			return@withLock AutomaticLeaseRefresh.Rejected
		}
		val matchingIdentity = automaticIdentity?.takeIf { it.matches(authority) }
		if (matchingIdentity != null && automaticInFlight) {
			return@withLock AutomaticLeaseRefresh.InProgress(
				com.adsamcik.tracker.tracker.api.AutomaticControlReconciliationLease(
					matchingIdentity,
				),
			)
		}
		val identity = authority.toLeaseIdentity(tokenFactory.next(sourcePurpose))
		when (val started = reporter.beginOrReplaceAutomaticControlLease(identity)) {
			is AutomaticControlLeaseStartResult.Started -> {
				automaticIdentity = identity
				automaticInFlight = true
				AutomaticLeaseRefresh.Issued(started.lease)
			}
			is AutomaticControlLeaseStartResult.InProgress ->
				AutomaticLeaseRefresh.InProgress(started.lease)
			is AutomaticControlLeaseStartResult.Rejected -> {
				AutomaticLeaseRefresh.Rejected
			}
		}
	}

	suspend fun refreshAmbient(
		source: AmbientTrackingSource,
		executionRevision: Long,
	): AmbientLeaseRefresh = mutex.withLock {
		val sourcePurpose = source.canonicalSource.forPurpose(TrackingPurpose.AMBIENT_PRODUCT)
		val authority = readAuthority(sourcePurpose, executionRevision)
		if (authority == null) {
			reporter.invalidateAmbient(source)
			ambientIdentities.remove(source)
			ambientInFlight.remove(source)
			return@withLock AmbientLeaseRefresh.Rejected
		}
		val matchingIdentity = ambientIdentities[source]
			?.takeIf { it.matches(authority) }
		if (matchingIdentity != null && source in ambientInFlight) {
			return@withLock AmbientLeaseRefresh.InProgress(
				com.adsamcik.tracker.tracker.api.AmbientReconciliationLease(matchingIdentity),
			)
		}
		val identity = AmbientReconciliationIdentity.from(
				authority.toLeaseIdentity(tokenFactory.next(sourcePurpose)),
				authority.retentionPolicyId,
				authority.retentionApprovalRevision,
			)
		when (val started = reporter.beginOrReplaceAmbientLease(identity)) {
			is AmbientLeaseStartResult.Started -> {
				ambientIdentities[source] = identity
				ambientInFlight += source
				AmbientLeaseRefresh.Issued(started.lease)
			}
			is AmbientLeaseStartResult.InProgress ->
				AmbientLeaseRefresh.InProgress(started.lease)
			is AmbientLeaseStartResult.Rejected -> {
				AmbientLeaseRefresh.Rejected
			}
		}
	}

	suspend fun clearAutomaticControl() = mutex.withLock {
		reporter.invalidateAutomaticControl()
		automaticIdentity = null
		automaticInFlight = false
	}

	suspend fun clearAmbient(source: AmbientTrackingSource) = mutex.withLock {
		reporter.invalidateAmbient(source)
		ambientIdentities.remove(source)
		ambientInFlight.remove(source)
	}

	suspend fun currentAmbientLease(source: AmbientTrackingSource):
		com.adsamcik.tracker.tracker.api.AmbientReconciliationLease? = mutex.withLock {
		ambientIdentities[source]?.let {
			com.adsamcik.tracker.tracker.api.AmbientReconciliationLease(it)
		}
	}

	suspend fun currentAutomaticControlLease():
		com.adsamcik.tracker.tracker.api.AutomaticControlReconciliationLease? = mutex.withLock {
		automaticIdentity?.let {
			com.adsamcik.tracker.tracker.api.AutomaticControlReconciliationLease(it)
		}
	}

	suspend fun cancelAutomaticControl(identity: TrackingPurposeLeaseIdentity) = mutex.withLock {
		reporter.cancelAutomaticControlLease(identity)
		if (automaticIdentity == identity) {
			automaticIdentity = null
			automaticInFlight = false
		}
	}

	suspend fun cancelAmbient(identity: AmbientReconciliationIdentity) = mutex.withLock {
		reporter.cancelAmbientLease(identity)
		if (ambientIdentities[identity.source] == identity) {
			reporter.invalidateAmbient(identity.source)
			ambientIdentities.remove(identity.source)
			ambientInFlight.remove(identity.source)
		}
	}

	suspend fun completeAutomaticControl(identity: TrackingPurposeLeaseIdentity) = mutex.withLock {
		if (automaticIdentity == identity) automaticInFlight = false
	}

	suspend fun completeAmbient(identity: AmbientReconciliationIdentity) = mutex.withLock {
		if (ambientIdentities[identity.source] == identity) {
			ambientInFlight.remove(identity.source)
		}
	}

	suspend fun isCurrentAmbient(
		identity: AmbientReconciliationIdentity,
		executionRevision: Long,
	): Boolean = mutex.withLock {
		readAuthority(identity.sourcePurpose, executionRevision)?.let(identity::matches) == true
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

private sealed interface AutomaticLeaseRefresh {
	data class Issued(
		val lease: com.adsamcik.tracker.tracker.api.AutomaticControlReconciliationLease,
	) : AutomaticLeaseRefresh

	data class InProgress(
		val lease: com.adsamcik.tracker.tracker.api.AutomaticControlReconciliationLease,
	) : AutomaticLeaseRefresh

	data object Rejected : AutomaticLeaseRefresh
}

private sealed interface AmbientLeaseRefresh {
	data class Issued(
		val lease: com.adsamcik.tracker.tracker.api.AmbientReconciliationLease,
	) : AmbientLeaseRefresh

	data class InProgress(
		val lease: com.adsamcik.tracker.tracker.api.AmbientReconciliationLease,
	) : AmbientLeaseRefresh

	data object Rejected : AmbientLeaseRefresh
}

@Singleton
internal class DefaultTrackingPurposePublicationRuntime internal constructor(
	private val leaseIssuer: SerializedTrackingPurposeLeaseIssuer,
	private val reporter: TrackingPurposeAvailabilityReporter,
	private val executionRevisionRegistry: TrackingPurposeExecutionRevisionRegistry,
	private val retentionAuthorityProducer: RetentionAuthorityProducer,
	private val trackingStartupGateProvider: Provider<TrackingStartupGate>? = null,
	private val ambientWifiDemandReconcilerProvider: Provider<AmbientWifiDemandReconciler>? = null,
	private val ambientCellDemandReconcilerProvider: Provider<AmbientCellDemandReconciler>? = null,
	private val ambientStepsPurposeOwnerProvider: Provider<AmbientStepsPurposeOwner>? = null,
	private val ownerCallbackScope: CoroutineScope =
		CoroutineScope(SupervisorJob() + Dispatchers.Default),
	private val ownerCallbackTimeoutMillis: Long = SOURCE_OWNER_CALLBACK_TIMEOUT_MILLIS,
	private val retryScheduler: TrackingPurposeReconciliationRetryScheduler? = null,
) : TrackingPurposeSettingsReconciler,
	TrackingPurposeSourceOwnerRegistrar,
	TrackingRetentionFloorReconciler,
	TrackingPurposeDeletionFencer {
	@Inject
	constructor(
		leaseIssuer: SerializedTrackingPurposeLeaseIssuer,
		reporter: TrackingPurposeAvailabilityReporter,
		executionRevisionRegistry: TrackingPurposeExecutionRevisionRegistry,
		retentionAuthorityProducer: RetentionAuthorityProducer,
		trackingStartupGateProvider: Provider<TrackingStartupGate>,
		ambientWifiDemandReconcilerProvider: Provider<AmbientWifiDemandReconciler>,
		ambientCellDemandReconcilerProvider: Provider<AmbientCellDemandReconciler>,
		ambientStepsPurposeOwnerProvider: Provider<AmbientStepsPurposeOwner>,
		@ApplicationScope ownerCallbackScope: CoroutineScope,
		retryScheduler: TrackingPurposeReconciliationRetryScheduler,
	) : this(
		leaseIssuer,
		reporter,
		executionRevisionRegistry,
		retentionAuthorityProducer,
		trackingStartupGateProvider,
		ambientWifiDemandReconcilerProvider,
		ambientCellDemandReconcilerProvider,
		ambientStepsPurposeOwnerProvider,
		ownerCallbackScope,
		SOURCE_OWNER_CALLBACK_TIMEOUT_MILLIS,
		retryScheduler,
	)

	private val ownerMutex = Mutex()
	private val automaticOperationMutex = Mutex()
	private val ambientOperationMutexes =
		AmbientTrackingSource.entries.associateWith { Mutex() }
	private val ownerFlightMutex = Mutex()
	private val reconciliationFlightMutex = Mutex()
	private var automaticReconciliationFlight:
		CompletableDeferred<Result<Boolean>>? = null
	private val ambientReconciliationFlights =
		mutableMapOf<
			AmbientReconciliationRequest,
			CompletableDeferred<Result<TrackingRetentionFloorReconciliationFailureReason?>>,
		>()
	private var automaticOwnerFlight:
		Pair<TrackingPurposeLeaseIdentity, Deferred<Result<AutomaticTrackingOperationalAvailability>>>? =
		null
	private val ambientOwnerFlights =
		mutableMapOf<AmbientReconciliationIdentity, Deferred<Result<AmbientSourceOperationalAvailability>>>()
	private val cleanupFlights =
		mutableMapOf<OwnerCleanupKey, Deferred<Boolean>>()
	private var automaticOwner: AutomaticControlOwnerRegistration? = null
	private val ambientOwners = mutableMapOf<AmbientTrackingSource, AmbientOwnerRegistration>()
	private val previouslyOwnedAmbientSources = mutableSetOf<AmbientTrackingSource>()

	init {
		require(ownerCallbackTimeoutMillis > 0L)
	}

	override suspend fun reconcileCurrentSettings(): TrackingPurposeSettingsReconciliationResult {
		val failures = mutableListOf<TrackingPurposeSettingsReconciliationFailure>()
		val retentionResults = try {
			retentionAuthorityProducer.reconcileCurrentSettings()
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: Exception) {
			DURABLE_AMBIENT_COMPONENTS.keys.map { source ->
				RetentionAuthorityResult.Unavailable(
					source = source,
					scope = com.adsamcik.tracker.shared.preferences.retention
						.RetentionAuthorityScope.LIVE_AMBIENT,
					reason = RetentionAuthorityUnavailableReason.STORAGE_UNAVAILABLE,
				)
			}
		}
		DURABLE_AMBIENT_COMPONENTS.forEach { (component, source) ->
			val results = retentionResults.filter {
				it.source == component &&
					it.scope == com.adsamcik.tracker.shared.preferences.retention
						.RetentionAuthorityScope.LIVE_AMBIENT
			}
			if (results.size != 1) {
				failures += TrackingPurposeSettingsReconciliationFailure(
					source,
					TrackingPurposeSettingsReconciliationFailureReason
						.RETENTION_RESULT_SET_INVALID,
				)
			} else {
				(results.single() as? RetentionAuthorityResult.Unavailable)?.let { unavailable ->
					failures += TrackingPurposeSettingsReconciliationFailure(
						source,
						TrackingPurposeSettingsReconciliationFailureReason
							.RETENTION_AUTHORITY_UNAVAILABLE,
						unavailable.reason.name,
					)
				}
			}
		}

		val reconciledSources = mutableSetOf<AmbientTrackingSource>()
		val ready = withCurrentReadyGeneration { startupGeneration ->
			ensureBuiltInAmbientOwners(AmbientTrackingSource.entries.toSet())
			if (!reconcileAutomaticControl(startupGeneration)) {
				failures += TrackingPurposeSettingsReconciliationFailure(
					source = null,
					reason = TrackingPurposeSettingsReconciliationFailureReason
						.AUTOMATIC_CONTROL_RECONCILIATION_FAILED,
				)
			}
			AmbientTrackingSource.entries.forEach { source ->
				val failure = try {
					reconcileAmbient(
						source,
						expectedRetainedFromMs = null,
						requireApproval = false,
						requireOwner = false,
						expectedStartupGeneration = startupGeneration,
					)
				} catch (cancelled: CancellationException) {
					throw cancelled
				} catch (_: Exception) {
					TrackingRetentionFloorReconciliationFailureReason
						.OWNER_RECONCILIATION_FAILED
				}
				if (failure == null) {
					reconciledSources += source
				} else {
					failures += failure.toSettingsFailure(source)
				}
			}
		}
		if (ready == null) {
			failures += TrackingPurposeSettingsReconciliationFailure(
				source = null,
				reason = TrackingPurposeSettingsReconciliationFailureReason
					.STARTUP_GENERATION_CHANGED,
			)
		}
		return finishSettingsReconciliation(failures, reconciledSources)
	}

	override suspend fun fenceForCollectedDataDeletion():
		TrackingPurposeSettingsReconciliationResult {
		ensureBuiltInAmbientOwners(AmbientTrackingSource.entries.toSet())
		val failures = mutableListOf<TrackingPurposeSettingsReconciliationFailure>()
		automaticOperationMutex.withLock {
			val owner = ownerMutex.withLock { automaticOwner }
			val lease = leaseIssuer.currentAutomaticControlLease()
			if (owner != null && lease != null) {
				if (
					awaitExistingAutomaticOwnerFlight(lease.identity) ==
					ExistingOwnerFlight.IN_PROGRESS
				) {
					failures += TrackingPurposeSettingsReconciliationFailure(
						source = null,
						reason = TrackingPurposeSettingsReconciliationFailureReason
							.OWNER_OPERATION_IN_PROGRESS,
					)
				} else if (compensateAndCancelAutomatic(owner, lease) != OwnerCleanup.COMPLETE) {
					failures += TrackingPurposeSettingsReconciliationFailure(
						source = null,
						reason = TrackingPurposeSettingsReconciliationFailureReason
							.COMPENSATION_FAILED,
					)
				}
			} else {
				leaseIssuer.clearAutomaticControl()
			}
		}
		val sources = ownerMutex.withLock {
			(previouslyOwnedAmbientSources + ambientOwners.keys).toSet()
		}
		sources.sortedBy { it.ordinal }.forEach { source ->
			val failure = ambientOperationMutexes.getValue(source).withLock {
				closeAmbientForCollectedDataDeletionLocked(source)
			}
			if (failure == null) {
				Unit
			} else {
				failures += failure.toSettingsFailure(source)
			}
		}
		return if (failures.isEmpty()) {
			TrackingPurposeSettingsReconciliationResult.Complete(sources)
		} else {
			TrackingPurposeSettingsReconciliationResult.Debt(
				TrackingPurposeSettingsReconciliationDebt(failures),
			)
		}
	}

	override suspend fun reconcile(
		expectedStartupGeneration: Long,
		retainedFromMs: Long,
		approvedSources: Set<AmbientTrackingSource>,
	): TrackingRetentionFloorReconciliationResult {
		require(retainedFromMs >= 0L)
		require(approvedSources.all { it in RETENTION_FLOOR_PROVIDER_SOURCES })
		return withReadyGeneration(expectedStartupGeneration) {
			ensureBuiltInAmbientOwners(RETENTION_FLOOR_PROVIDER_SOURCES)
			val previouslyOwned = ownerMutex.withLock {
				previouslyOwnedAmbientSources.toSet()
			}
			val sourcesToReconcile = RETENTION_FLOOR_PROVIDER_SOURCES + previouslyOwned
			val failures = buildList {
				if (!reconcileAutomaticControl(expectedStartupGeneration)) {
					add(
						TrackingRetentionFloorReconciliationFailure(
							source = null,
							reason = TrackingRetentionFloorReconciliationFailureReason
								.OWNER_RECONCILIATION_FAILED,
						),
					)
				}
				sourcesToReconcile.sortedBy { it.ordinal }.forEach { source ->
					val failure = if (source in approvedSources) {
						reconcileAmbient(
							source,
							expectedRetainedFromMs = retainedFromMs,
							requireApproval = true,
							requireOwner = true,
							expectedStartupGeneration = expectedStartupGeneration,
						)
					} else {
						ambientOperationMutexes.getValue(source).withLock {
							retireAmbientAfterRetentionAuthorityFailureLocked(
								source,
								requireOwner = source in previouslyOwned,
							)
						}
					}
					failure?.let { reason ->
						add(TrackingRetentionFloorReconciliationFailure(source, reason))
					}
				}
			}
			if (failures.isNotEmpty()) {
				return@withReadyGeneration TrackingRetentionFloorReconciliationResult.Retryable(
					TrackingRetentionFloorReconciliationDebt(retainedFromMs, failures),
				)
			}
			publishForReadyGeneration(expectedStartupGeneration) {
				TrackingRetentionFloorReconciliationResult.Complete(
					retainedFromMs,
					approvedSources,
				)
			} ?: run {
				leaseIssuer.clearAutomaticControl()
				val rollbackFailures = sourcesToReconcile
					.sortedBy { it.ordinal }
					.mapNotNull { source ->
						ambientOperationMutexes.getValue(source).withLock {
							retireAmbientAfterRetentionAuthorityFailureLocked(
								source,
								requireOwner = source in approvedSources,
							)
						}?.let { reason ->
							TrackingRetentionFloorReconciliationFailure(source, reason)
						}
					}
				TrackingRetentionFloorReconciliationResult.Retryable(
					TrackingRetentionFloorReconciliationDebt(
						retainedFromMs,
						listOf(
							TrackingRetentionFloorReconciliationFailure(
								source = null,
								reason =
									TrackingRetentionFloorReconciliationFailureReason
										.STARTUP_GENERATION_CHANGED,
							),
						) + rollbackFailures,
					),
				)
			}
		} ?: retentionFloorRetry(
			retainedFromMs,
			TrackingRetentionFloorReconciliationFailure(
				source = null,
				reason =
					TrackingRetentionFloorReconciliationFailureReason.STARTUP_GENERATION_CHANGED,
			),
		)
	}

	private suspend fun ensureBuiltInAmbientOwners(
		sources: Set<AmbientTrackingSource>,
	) {
		ownerMutex.withLock {
			if (
				AmbientTrackingSource.STEPS in sources &&
				ambientOwners[AmbientTrackingSource.STEPS] == null &&
				ambientStepsPurposeOwnerProvider != null
			) {
				ambientOwners[AmbientTrackingSource.STEPS] = AmbientOwnerRegistration(
					TrackingPurposeSourceOwnerRegistration(
						AmbientTrackingSource.STEPS.canonicalSource.forPurpose(
							TrackingPurpose.AMBIENT_PRODUCT,
						),
						BUILT_IN_STEPS_REGISTRATION_ID,
					),
					BUILT_IN_STEPS_EXECUTION_REVISION,
					builtInStepsCallback(ambientStepsPurposeOwnerProvider),
				)
				previouslyOwnedAmbientSources += AmbientTrackingSource.STEPS
				executionRevisionRegistry.update(
					AmbientTrackingSource.STEPS.canonicalSource.forPurpose(
						TrackingPurpose.AMBIENT_PRODUCT,
					),
					BUILT_IN_STEPS_EXECUTION_REVISION,
				)
			}
			if (
				AmbientTrackingSource.WIFI in sources &&
				ambientOwners[AmbientTrackingSource.WIFI] == null &&
				ambientWifiDemandReconcilerProvider != null
			) {
				ambientOwners[AmbientTrackingSource.WIFI] = AmbientOwnerRegistration(
					TrackingPurposeSourceOwnerRegistration(
						AmbientTrackingSource.WIFI.canonicalSource.forPurpose(
							TrackingPurpose.AMBIENT_PRODUCT,
						),
						BUILT_IN_WIFI_REGISTRATION_ID,
					),
					AmbientWifiAuthorityEntity.FIRST_WRITER_OWNER_GENERATION,
					builtInWifiCallback(ambientWifiDemandReconcilerProvider),
				)
				previouslyOwnedAmbientSources += AmbientTrackingSource.WIFI
				executionRevisionRegistry.update(
					AmbientTrackingSource.WIFI.canonicalSource.forPurpose(
						TrackingPurpose.AMBIENT_PRODUCT,
					),
					AmbientWifiAuthorityEntity.FIRST_WRITER_OWNER_GENERATION,
				)
			}
			if (
				AmbientTrackingSource.CELL in sources &&
				ambientOwners[AmbientTrackingSource.CELL] == null &&
				ambientCellDemandReconcilerProvider != null
			) {
				ambientOwners[AmbientTrackingSource.CELL] = AmbientOwnerRegistration(
					TrackingPurposeSourceOwnerRegistration(
						AmbientTrackingSource.CELL.canonicalSource.forPurpose(
							TrackingPurpose.AMBIENT_PRODUCT,
						),
						BUILT_IN_CELL_REGISTRATION_ID,
					),
					AmbientCellAuthorityEntity.FIRST_WRITER_OWNER_GENERATION,
					builtInCellCallback(ambientCellDemandReconcilerProvider),
				)
				previouslyOwnedAmbientSources += AmbientTrackingSource.CELL
				executionRevisionRegistry.update(
					AmbientTrackingSource.CELL.canonicalSource.forPurpose(
						TrackingPurpose.AMBIENT_PRODUCT,
					),
					AmbientCellAuthorityEntity.FIRST_WRITER_OWNER_GENERATION,
				)
			}
		}
	}

	private fun builtInStepsCallback(
		owner: Provider<AmbientStepsPurposeOwner>,
	): AmbientSourceReconciliationCallback = object : AmbientSourceReconciliationCallback {
		override suspend fun reconcile(
			lease: com.adsamcik.tracker.tracker.api.AmbientReconciliationLease,
		): AmbientSourceOperationalAvailability = owner.get().reconcile(lease)

		override suspend fun compensate(
			lease: com.adsamcik.tracker.tracker.api.AmbientReconciliationLease,
		): Boolean = owner.get().retireAfterRetentionAuthorityFailure(lease)

		override suspend fun retireAfterRetentionAuthorityFailure(
			previousLease: com.adsamcik.tracker.tracker.api.AmbientReconciliationLease?,
		): Boolean = owner.get().retireAfterRetentionAuthorityFailure(previousLease)

		override suspend fun closeForCollectedDataDeletion(
			previousLease: com.adsamcik.tracker.tracker.api.AmbientReconciliationLease?,
		): Boolean = owner.get().closeForCollectedDataDeletion(previousLease)
	}

	private fun builtInWifiCallback(
		reconciler: Provider<AmbientWifiDemandReconciler>,
	): AmbientSourceReconciliationCallback = object : AmbientSourceReconciliationCallback {
		override suspend fun reconcile(
			lease: com.adsamcik.tracker.tracker.api.AmbientReconciliationLease,
		): AmbientSourceOperationalAvailability =
			reconciler.get().reconcilePurposeAvailability(
				lease,
				AmbientWifiActivationRequest(enabled = true),
			).availabilityOrPending(AmbientTrackingSource.WIFI, lease)

		override suspend fun compensate(
			lease: com.adsamcik.tracker.tracker.api.AmbientReconciliationLease,
		): Boolean = reconciler.get().retireAfterRetentionAuthorityFailure(lease)

		override suspend fun retireAfterRetentionAuthorityFailure(
			previousLease: com.adsamcik.tracker.tracker.api.AmbientReconciliationLease?,
		): Boolean = reconciler.get().retireAfterRetentionAuthorityFailure(previousLease)

		override suspend fun closeForCollectedDataDeletion(
			previousLease: com.adsamcik.tracker.tracker.api.AmbientReconciliationLease?,
		): Boolean = reconciler.get().closeForCollectedDataDeletion()
	}

	private fun builtInCellCallback(
		reconciler: Provider<AmbientCellDemandReconciler>,
	): AmbientSourceReconciliationCallback = object : AmbientSourceReconciliationCallback {
		override suspend fun reconcile(
			lease: com.adsamcik.tracker.tracker.api.AmbientReconciliationLease,
		): AmbientSourceOperationalAvailability =
			reconciler.get().reconcilePurposeAvailability(
				lease,
				AmbientCellActivationRequest(enabled = true),
			).availabilityOrPending(AmbientTrackingSource.CELL, lease)

		override suspend fun compensate(
			lease: com.adsamcik.tracker.tracker.api.AmbientReconciliationLease,
		): Boolean = reconciler.get().retireAfterRetentionAuthorityFailure(lease)

		override suspend fun retireAfterRetentionAuthorityFailure(
			previousLease: com.adsamcik.tracker.tracker.api.AmbientReconciliationLease?,
		): Boolean = reconciler.get().retireAfterRetentionAuthorityFailure(previousLease)

		override suspend fun closeForCollectedDataDeletion(
			previousLease: com.adsamcik.tracker.tracker.api.AmbientReconciliationLease?,
		): Boolean = reconciler.get().closeForCollectedDataDeletion()
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
		automaticOperationMutex.withLock {
			val existing = ownerMutex.withLock { automaticOwner }
			val existingLease = leaseIssuer.currentAutomaticControlLease()
			if (existing != null && existingLease != null) {
				check(
					awaitExistingAutomaticOwnerFlight(existingLease.identity) !=
						ExistingOwnerFlight.IN_PROGRESS,
				) {
					"Automatic CONTROL owner replacement is waiting for its exact operation"
				}
				check(compensateAndCancelAutomatic(existing, existingLease) == OwnerCleanup.COMPLETE) {
					"Automatic CONTROL owner cannot be replaced before exact compensation"
				}
			}
			leaseIssuer.clearAutomaticControl()
			ownerMutex.withLock {
				automaticOwner = AutomaticControlOwnerRegistration(
					registration,
					executionRevision,
					callback,
				)
				executionRevisionRegistry.update(ACTIVITY_CONTROL, executionRevision)
			}
			withCurrentReadyGeneration { startupGeneration ->
				reconcileAutomaticControlLocked(startupGeneration)
			}
		}
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
		ambientOperationMutexes.getValue(source).withLock {
			val existing = ownerMutex.withLock { ambientOwners[source] }
			if (existing != null) {
				val retirementFailure = retireAmbientAfterRetentionAuthorityFailureLocked(
					source = source,
					requireOwner = true,
				)
				check(retirementFailure == null) {
					"Ambient source owner cannot be replaced before exact retirement: " +
						retirementFailure
				}
			}
			leaseIssuer.clearAmbient(source)
			ownerMutex.withLock {
				ambientOwners[source] = AmbientOwnerRegistration(
					registration,
					executionRevision,
					callback,
				)
				previouslyOwnedAmbientSources += source
				executionRevisionRegistry.update(
					source.canonicalSource.forPurpose(TrackingPurpose.AMBIENT_PRODUCT),
					executionRevision,
				)
			}
			withCurrentReadyGeneration { startupGeneration ->
				reconcileAmbientLocked(
					source,
					expectedRetainedFromMs = null,
					requireApproval = false,
					requireOwner = false,
					expectedStartupGeneration = startupGeneration,
				)
			}
		}
		return registration
	}

	override suspend fun unregister(registration: TrackingPurposeSourceOwnerRegistration) {
		if (registration.sourcePurpose == ACTIVITY_CONTROL) {
			automaticOperationMutex.withLock automatic@{
				val existing = ownerMutex.withLock {
					automaticOwner?.takeIf { it.registration == registration }
				} ?: return@automatic
				leaseIssuer.currentAutomaticControlLease()?.let { lease ->
					check(
						awaitExistingAutomaticOwnerFlight(lease.identity) !=
							ExistingOwnerFlight.IN_PROGRESS,
					)
					check(compensateAndCancelAutomatic(existing, lease) == OwnerCleanup.COMPLETE)
				}
				val removed = ownerMutex.withLock {
					automaticOwner
						?.takeIf { it.registration == registration }
						?.also {
							automaticOwner = null
							executionRevisionRegistry.remove(ACTIVITY_CONTROL)
						}
				}
				if (removed != null) leaseIssuer.clearAutomaticControl()
			}
			return
		}
		val source = AmbientTrackingSource.entries.singleOrNull { candidate ->
			candidate.canonicalSource == registration.sourcePurpose.source &&
				registration.sourcePurpose.purpose == TrackingPurpose.AMBIENT_PRODUCT
		} ?: return
		ambientOperationMutexes.getValue(source).withLock ambient@{
			if (ownerMutex.withLock {
				ambientOwners[source]?.takeIf { it.registration == registration }
			} == null) return@ambient
			val retirementFailure = retireAmbientAfterRetentionAuthorityFailureLocked(
				source = source,
				requireOwner = true,
			)
			check(retirementFailure == null) {
				"Ambient source owner cannot unregister before exact physical retirement: " +
					retirementFailure
			}
			val removed = ownerMutex.withLock {
				ambientOwners[source]
					?.takeIf { it.registration == registration }
					?.also {
						ambientOwners.remove(source)
						previouslyOwnedAmbientSources.remove(source)
						executionRevisionRegistry.remove(registration.sourcePurpose)
					}
			}
			if (removed != null) leaseIssuer.clearAmbient(source)
		}
	}

	private suspend fun reconcileAutomaticControl(expectedStartupGeneration: Long?): Boolean {
		val selection = reconciliationFlightMutex.withLock {
			automaticReconciliationFlight?.let {
				return@withLock ReconciliationFlightSelection(it, owner = false)
			}
			ReconciliationFlightSelection(
				CompletableDeferred<Result<Boolean>>().also {
					automaticReconciliationFlight = it
				},
				owner = true,
			)
		}
		if (!selection.owner) return selection.flight.await().getOrThrow()
		return try {
			automaticOperationMutex.withLock {
				reconcileAutomaticControlLocked(expectedStartupGeneration)
			}.also { selection.flight.complete(Result.success(it)) }
		} catch (failure: Throwable) {
			selection.flight.complete(Result.failure(failure))
			throw failure
		} finally {
			reconciliationFlightMutex.withLock {
				if (automaticReconciliationFlight === selection.flight) {
					automaticReconciliationFlight = null
				}
			}
		}
	}

	private suspend fun reconcileAutomaticControlLocked(expectedStartupGeneration: Long?): Boolean {
		val owner = ownerMutex.withLock { automaticOwner }
		val refresh = leaseIssuer.refreshAutomaticControl(owner?.executionRevision ?: 0L)
		val lease = when (refresh) {
			is AutomaticLeaseRefresh.Issued -> refresh.lease
			is AutomaticLeaseRefresh.InProgress -> refresh.lease
			AutomaticLeaseRefresh.Rejected -> return true
		}
		if (!isCurrentAutomaticOwner(owner)) {
			leaseIssuer.cancelAutomaticControl(lease.identity)
			return false
		}
		val availability = if (owner == null) {
			AutomaticTrackingOperationalAvailability.Unavailable(
				reason =
					AutomaticTrackingUnavailableReason.CONTROL_RETENTION_POLICY_UNAVAILABLE,
				lastIdentity = lease.identity,
			)
		} else {
			when (
				val call = runBoundedAutomaticOwnerCall(lease.identity) {
					owner.callback.reconcile(lease)
				}
			) {
				is BoundedOwnerCall.Completed -> call.value
				BoundedOwnerCall.Failed,
				-> {
					compensateAndCancelAutomatic(owner, lease)
					return false
				}
				BoundedOwnerCall.TimedOut -> return false
			}
		}
		if (!isCurrentAutomaticOwner(owner)) {
			if (owner != null) compensateAndCancelAutomatic(owner, lease)
			else leaseIssuer.cancelAutomaticControl(lease.identity)
			return false
		}
		if (!readyGenerationStillCurrent(expectedStartupGeneration)) {
			if (owner != null) compensateAndCancelAutomatic(owner, lease)
			else leaseIssuer.cancelAutomaticControl(lease.identity)
			return false
		}
		val report = runCatching {
			AutomaticControlReconciliationReport(lease.identity, availability)
		}.getOrNull() ?: run {
			if (owner != null) compensateAndCancelAutomatic(owner, lease)
			else leaseIssuer.cancelAutomaticControl(lease.identity)
			return false
		}
		val acceptance = publishForReadyGeneration(expectedStartupGeneration) {
			reporter.tryAccept(report)
		}
		if (acceptance !is AutomaticControlPublicationAcceptance.Accepted) {
			if (owner != null) compensateAndCancelAutomatic(owner, lease)
			else leaseIssuer.cancelAutomaticControl(lease.identity)
			return false
		}
		clearAutomaticOwnerFlight(lease.identity)
		leaseIssuer.completeAutomaticControl(lease.identity)
		return true
	}

	private suspend fun reconcileAmbient(
		source: AmbientTrackingSource,
		expectedRetainedFromMs: Long?,
		requireApproval: Boolean,
		requireOwner: Boolean,
		expectedStartupGeneration: Long?,
	): TrackingRetentionFloorReconciliationFailureReason? {
		val request = AmbientReconciliationRequest(
			source,
			expectedRetainedFromMs,
			requireApproval,
			requireOwner,
			expectedStartupGeneration,
		)
		val selection = reconciliationFlightMutex.withLock {
			ambientReconciliationFlights[request]?.let {
				return@withLock ReconciliationFlightSelection(it, owner = false)
			}
			ReconciliationFlightSelection(
				CompletableDeferred<
					Result<TrackingRetentionFloorReconciliationFailureReason?>,
				>().also { ambientReconciliationFlights[request] = it },
				owner = true,
			)
		}
		if (!selection.owner) return selection.flight.await().getOrThrow()
		return try {
			ambientOperationMutexes.getValue(source).withLock {
				reconcileAmbientLocked(
					source,
					expectedRetainedFromMs,
					requireApproval,
					requireOwner,
					expectedStartupGeneration,
				)
			}.also { selection.flight.complete(Result.success(it)) }
		} catch (failure: Throwable) {
			selection.flight.complete(Result.failure(failure))
			throw failure
		} finally {
			reconciliationFlightMutex.withLock {
				if (ambientReconciliationFlights[request] === selection.flight) {
					ambientReconciliationFlights.remove(request)
				}
			}
		}
	}

	private suspend fun reconcileAmbientLocked(
		source: AmbientTrackingSource,
		expectedRetainedFromMs: Long?,
		requireApproval: Boolean,
		requireOwner: Boolean,
		expectedStartupGeneration: Long?,
	): TrackingRetentionFloorReconciliationFailureReason? {
		val retention = retentionAuthorityProducer.reconcileLiveAmbient(source.canonicalSource)
		if (!retention.isActiveApproval()) {
			val retirementFailure = retireAmbientAfterRetentionAuthorityFailureLocked(
				source,
				requireOwner,
			)
			val unavailable = retention as? RetentionAuthorityResult.Unavailable
			if (unavailable != null &&
				unavailable.reason !=
					RetentionAuthorityUnavailableReason.PURPOSE_AUTHORITY_UNAVAILABLE
			) {
				reporter.publishAmbientUnavailable(
					source,
					com.adsamcik.tracker.tracker.api.AmbientSourceUnavailableReason
						.RETENTION_POLICY_UNAVAILABLE,
				)
			}
			if (retirementFailure != null) return retirementFailure
			return if (requireApproval) {
				TrackingRetentionFloorReconciliationFailureReason
					.RETENTION_AUTHORITY_UNAVAILABLE
			} else {
				null
			}
		}
		val owner = ownerMutex.withLock { ambientOwners[source] }
		if (owner == null) {
			leaseIssuer.clearAmbient(source)
			reporter.publishAmbientUnavailable(
				source,
				com.adsamcik.tracker.tracker.api.AmbientSourceUnavailableReason
					.PROVIDER_UNAVAILABLE,
			)
			return TrackingRetentionFloorReconciliationFailureReason.OWNER_MISSING
		}
		val previousLease = leaseIssuer.currentAmbientLease(source)
		val refresh = leaseIssuer.refreshAmbient(source, owner.executionRevision)
		val lease = when (refresh) {
			is AmbientLeaseRefresh.Issued -> refresh.lease
			is AmbientLeaseRefresh.InProgress -> refresh.lease
			AmbientLeaseRefresh.Rejected -> null
		}
		if (lease == null) {
			val retirementFailure = retireAmbientAfterRetentionAuthorityFailureLocked(
				source,
				requireOwner,
				previousLease,
			)
			return retirementFailure
				?: TrackingRetentionFloorReconciliationFailureReason
					.RETENTION_AUTHORITY_UNAVAILABLE
		}
		if (
			expectedRetainedFromMs != null &&
			lease.purposeLeaseIdentity.retainedFromMs != expectedRetainedFromMs
		) {
			return failureAfterCompensation(
				compensateAndCancelAmbient(owner, lease),
				TrackingRetentionFloorReconciliationFailureReason.AUTHORITY_FLOOR_MISMATCH,
			)
		}
		if (!isCurrentAmbientOwner(source, owner)) {
			return failureAfterCompensation(
				compensateAndCancelAmbient(owner, lease),
				TrackingRetentionFloorReconciliationFailureReason.OWNER_RECONCILIATION_FAILED,
			)
		}
		if (!leaseIssuer.isCurrentAmbient(lease.identity, owner.executionRevision)) {
			return failureAfterCompensation(
				compensateAndCancelAmbient(owner, lease),
				TrackingRetentionFloorReconciliationFailureReason
					.RETENTION_AUTHORITY_UNAVAILABLE,
			)
		}
		if (!readyGenerationStillCurrent(expectedStartupGeneration)) {
			return failureAfterCompensation(
				compensateAndCancelAmbient(owner, lease),
				TrackingRetentionFloorReconciliationFailureReason.STARTUP_GENERATION_CHANGED,
			)
		}
		val ownerCall = runBoundedAmbientOwnerCall(lease.identity) {
			owner.callback.reconcile(lease)
		}
		val availability = when (ownerCall) {
			is BoundedOwnerCall.Completed -> ownerCall.value
			BoundedOwnerCall.Failed -> {
				return failureAfterCompensation(
					compensateAndCancelAmbient(owner, lease),
					TrackingRetentionFloorReconciliationFailureReason
						.OWNER_RECONCILIATION_FAILED,
				)
			}
			BoundedOwnerCall.TimedOut ->
				return TrackingRetentionFloorReconciliationFailureReason
					.OWNER_OPERATION_IN_PROGRESS
		}
		if (!leaseIssuer.isCurrentAmbient(lease.identity, owner.executionRevision)) {
			return failureAfterCompensation(
				compensateAndCancelAmbient(owner, lease),
				TrackingRetentionFloorReconciliationFailureReason
					.RETENTION_AUTHORITY_UNAVAILABLE,
			)
		}
		if (!isCurrentAmbientOwner(source, owner)) {
			return failureAfterCompensation(
				compensateAndCancelAmbient(owner, lease),
				TrackingRetentionFloorReconciliationFailureReason.PUBLICATION_REJECTED,
			)
		}
		if (!readyGenerationStillCurrent(expectedStartupGeneration)) {
			return failureAfterCompensation(
				compensateAndCancelAmbient(owner, lease),
				TrackingRetentionFloorReconciliationFailureReason.STARTUP_GENERATION_CHANGED,
			)
		}
		val report = try {
			AmbientSourceReconciliationReport(lease.identity, availability)
		} catch (_: IllegalArgumentException) {
			return failureAfterCompensation(
				compensateAndCancelAmbient(owner, lease),
				TrackingRetentionFloorReconciliationFailureReason.PUBLICATION_REJECTED,
			)
		}
		return when (publishForReadyGeneration(expectedStartupGeneration) {
			reporter.tryAccept(report)
		}) {
			is AmbientPublicationAcceptance.Accepted -> null
			is AmbientPublicationAcceptance.Rejected ->
				failureAfterCompensation(
					compensateAndCancelAmbient(owner, lease),
					TrackingRetentionFloorReconciliationFailureReason.PUBLICATION_REJECTED,
				)
			null -> failureAfterCompensation(
				compensateAndCancelAmbient(owner, lease),
				TrackingRetentionFloorReconciliationFailureReason.STARTUP_GENERATION_CHANGED,
			)
		}.also { failure ->
			if (failure == null) {
				clearAmbientOwnerFlight(lease.identity)
				leaseIssuer.completeAmbient(lease.identity)
			}
		}
	}

	private suspend fun retireAmbientAfterRetentionAuthorityFailureLocked(
		source: AmbientTrackingSource,
		requireOwner: Boolean,
		knownPreviousLease:
			com.adsamcik.tracker.tracker.api.AmbientReconciliationLease? = null,
	): TrackingRetentionFloorReconciliationFailureReason? {
		val owner = ownerMutex.withLock { ambientOwners[source] }
		val previousLease = knownPreviousLease ?: leaseIssuer.currentAmbientLease(source)
		if (owner == null) {
			val wasPreviouslyOwned = ownerMutex.withLock {
				source in previouslyOwnedAmbientSources
			}
			leaseIssuer.clearAmbient(source)
			reporter.publishAmbientUnavailable(
				source,
				com.adsamcik.tracker.tracker.api.AmbientSourceUnavailableReason
					.RETENTION_POLICY_UNAVAILABLE,
			)
			return if (requireOwner || previousLease != null || wasPreviouslyOwned) {
				TrackingRetentionFloorReconciliationFailureReason.OWNER_MISSING
			} else {
				null
			}
		}
		if (
			previousLease != null &&
			awaitExistingAmbientOwnerFlight(previousLease.identity) ==
				ExistingOwnerFlight.IN_PROGRESS
		) {
			return TrackingRetentionFloorReconciliationFailureReason.OWNER_OPERATION_IN_PROGRESS
		}
		val cleanup = runBoundedCleanup(
			OwnerCleanupKey(
				source.name,
				previousLease?.identity?.ownerCasToken ?: owner.registration.registrationId,
				OwnerCleanupPurpose.RETENTION_FAILURE,
			),
		) {
			owner.callback.retireAfterRetentionAuthorityFailure(previousLease)
		}
		withContext(NonCancellable) {
			reporter.publishAmbientUnavailable(
				source,
				com.adsamcik.tracker.tracker.api.AmbientSourceUnavailableReason
					.RETENTION_POLICY_UNAVAILABLE,
			)
			if (cleanup == OwnerCleanup.COMPLETE) {
				leaseIssuer.clearAmbient(source)
				previousLease?.let { clearAmbientOwnerFlight(it.identity) }
			}
		}
		if (!isCurrentAmbientOwner(source, owner)) {
			return TrackingRetentionFloorReconciliationFailureReason.OWNER_RECONCILIATION_FAILED
		}
		return when (cleanup) {
			OwnerCleanup.COMPLETE -> null
			OwnerCleanup.FAILED ->
				TrackingRetentionFloorReconciliationFailureReason.RETIREMENT_FAILED
			OwnerCleanup.TIMED_OUT ->
				TrackingRetentionFloorReconciliationFailureReason.COMPENSATION_TIMED_OUT
		}
	}

	private suspend fun closeAmbientForCollectedDataDeletionLocked(
		source: AmbientTrackingSource,
	): TrackingRetentionFloorReconciliationFailureReason? {
		val owner = ownerMutex.withLock { ambientOwners[source] }
			?: return TrackingRetentionFloorReconciliationFailureReason.OWNER_MISSING
		val previousLease = leaseIssuer.currentAmbientLease(source)
		if (
			previousLease != null &&
			awaitExistingAmbientOwnerFlight(previousLease.identity) ==
				ExistingOwnerFlight.IN_PROGRESS
		) {
			return TrackingRetentionFloorReconciliationFailureReason.OWNER_OPERATION_IN_PROGRESS
		}
		val cleanup = runBoundedCleanup(
			OwnerCleanupKey(
				source.name,
				previousLease?.identity?.ownerCasToken ?: owner.registration.registrationId,
				OwnerCleanupPurpose.COLLECTED_DATA_DELETION,
			),
		) {
			owner.callback.closeForCollectedDataDeletion(previousLease)
		}
		reporter.publishAmbientUnavailable(
			source,
			com.adsamcik.tracker.tracker.api.AmbientSourceUnavailableReason
				.RETENTION_POLICY_UNAVAILABLE,
		)
		if (cleanup == OwnerCleanup.COMPLETE) {
			leaseIssuer.clearAmbient(source)
			previousLease?.let { clearAmbientOwnerFlight(it.identity) }
		}
		return when (cleanup) {
			OwnerCleanup.COMPLETE -> null
			OwnerCleanup.FAILED ->
				TrackingRetentionFloorReconciliationFailureReason.RETIREMENT_FAILED
			OwnerCleanup.TIMED_OUT ->
				TrackingRetentionFloorReconciliationFailureReason.OWNER_OPERATION_IN_PROGRESS
		}
	}

	private suspend fun isCurrentAutomaticOwner(
		expected: AutomaticControlOwnerRegistration?,
	): Boolean = ownerMutex.withLock { automaticOwner == expected }

	private suspend fun isCurrentAmbientOwner(
		source: AmbientTrackingSource,
		expected: AmbientOwnerRegistration?,
	): Boolean = ownerMutex.withLock { ambientOwners[source] == expected }

	private suspend fun compensateAndCancelAmbient(
		owner: AmbientOwnerRegistration,
		lease: com.adsamcik.tracker.tracker.api.AmbientReconciliationLease,
	): OwnerCleanup = withContext(NonCancellable) {
		if (
			awaitExistingAmbientOwnerFlight(lease.identity) ==
			ExistingOwnerFlight.IN_PROGRESS
		) {
			return@withContext OwnerCleanup.TIMED_OUT
		}
		val cleanup = runBoundedCleanup(
			OwnerCleanupKey(
				lease.identity.source.name,
				lease.identity.ownerCasToken,
				OwnerCleanupPurpose.COMPENSATION,
			),
		) {
			owner.callback.compensate(lease)
		}
		reporter.invalidateAmbient(lease.identity.source)
		if (cleanup == OwnerCleanup.COMPLETE) {
			leaseIssuer.cancelAmbient(lease.identity)
			clearAmbientOwnerFlight(lease.identity)
		}
		cleanup
	}

	private suspend fun compensateAndCancelAutomatic(
		owner: AutomaticControlOwnerRegistration,
		lease: com.adsamcik.tracker.tracker.api.AutomaticControlReconciliationLease,
	): OwnerCleanup = withContext(NonCancellable) {
		if (
			awaitExistingAutomaticOwnerFlight(lease.identity) ==
			ExistingOwnerFlight.IN_PROGRESS
		) {
			return@withContext OwnerCleanup.TIMED_OUT
		}
		val cleanup = runBoundedCleanup(
			OwnerCleanupKey(
				source = "ACTIVITY_CONTROL",
				ownerToken = lease.identity.ownerCasToken,
				purpose = OwnerCleanupPurpose.COMPENSATION,
			),
		) {
			owner.callback.compensate(lease)
		}
		reporter.invalidateAutomaticControl()
		if (cleanup == OwnerCleanup.COMPLETE) {
			leaseIssuer.cancelAutomaticControl(lease.identity)
			clearAutomaticOwnerFlight(lease.identity)
		}
		cleanup
	}

	private suspend fun runBoundedAutomaticOwnerCall(
		identity: TrackingPurposeLeaseIdentity,
		operation: suspend () -> AutomaticTrackingOperationalAvailability,
	): BoundedOwnerCall<AutomaticTrackingOperationalAvailability> {
		val task = ownerFlightMutex.withLock {
			automaticOwnerFlight
				?.takeIf { it.first == identity }
				?.second
				?: ownerCallbackScope.async {
					runCatching { operation() }
				}.also { created ->
					automaticOwnerFlight = identity to created
				}
		}
		return awaitOwnerCall(task)
	}

	private suspend fun runBoundedAmbientOwnerCall(
		identity: AmbientReconciliationIdentity,
		operation: suspend () -> AmbientSourceOperationalAvailability,
	): BoundedOwnerCall<AmbientSourceOperationalAvailability> {
		val task = ownerFlightMutex.withLock {
			ambientOwnerFlights[identity] ?: ownerCallbackScope.async {
				runCatching { operation() }
			}.also { ambientOwnerFlights[identity] = it }
		}
		return awaitOwnerCall(task)
	}

	private suspend fun awaitExistingAmbientOwnerFlight(
		identity: AmbientReconciliationIdentity,
	): ExistingOwnerFlight {
		val task = ownerFlightMutex.withLock {
			ambientOwnerFlights[identity]
		} ?: return ExistingOwnerFlight.NONE
		return if (withTimeoutOrNull(ownerCallbackTimeoutMillis) {
			task.join()
			true
		} == null) {
			ExistingOwnerFlight.IN_PROGRESS
		} else {
			ExistingOwnerFlight.TERMINAL
		}
	}

	private suspend fun awaitExistingAutomaticOwnerFlight(
		identity: TrackingPurposeLeaseIdentity,
	): ExistingOwnerFlight {
		val task = ownerFlightMutex.withLock {
			automaticOwnerFlight?.takeIf { it.first == identity }?.second
		} ?: return ExistingOwnerFlight.NONE
		return if (withTimeoutOrNull(ownerCallbackTimeoutMillis) {
			task.join()
			true
		} == null) {
			ExistingOwnerFlight.IN_PROGRESS
		} else {
			ExistingOwnerFlight.TERMINAL
		}
	}

	private suspend fun <T> awaitOwnerCall(
		task: Deferred<Result<T>>,
	): BoundedOwnerCall<T> {
		val result = try {
			withTimeoutOrNull(ownerCallbackTimeoutMillis) { task.await() }
				?: return BoundedOwnerCall.TimedOut
		} catch (cancelled: CancellationException) {
			if (task.isCancelled) return BoundedOwnerCall.Failed
			throw cancelled
		}
		return result.fold(
			onSuccess = { BoundedOwnerCall.Completed(it) },
			onFailure = { BoundedOwnerCall.Failed },
		)
	}

	private suspend fun runBoundedCleanup(
		key: OwnerCleanupKey,
		operation: suspend () -> Boolean,
	): OwnerCleanup = withContext(NonCancellable) {
		val task = ownerFlightMutex.withLock {
			cleanupFlights[key] ?: ownerCallbackScope.async {
				runCatching { operation() }.getOrDefault(false)
			}.also { cleanupFlights[key] = it }
		}
		val completed = try {
			withTimeoutOrNull(ownerCallbackTimeoutMillis) {
				task.await()
			}
		} catch (_: CancellationException) {
			false
		}
		when {
			completed == null -> OwnerCleanup.TIMED_OUT
			completed -> {
				ownerFlightMutex.withLock {
					if (cleanupFlights[key] === task) cleanupFlights.remove(key)
				}
				OwnerCleanup.COMPLETE
			}
			else -> {
				ownerFlightMutex.withLock {
					if (cleanupFlights[key] === task) cleanupFlights.remove(key)
				}
				OwnerCleanup.FAILED
			}
		}
	}

	private suspend fun clearAutomaticOwnerFlight(identity: TrackingPurposeLeaseIdentity) {
		ownerFlightMutex.withLock {
			if (automaticOwnerFlight?.first == identity) automaticOwnerFlight = null
		}
	}

	private suspend fun clearAmbientOwnerFlight(identity: AmbientReconciliationIdentity) {
		ownerFlightMutex.withLock {
			ambientOwnerFlights.remove(identity)
		}
	}

	private suspend fun finishSettingsReconciliation(
		failures: List<TrackingPurposeSettingsReconciliationFailure>,
		reconciledSources: Set<AmbientTrackingSource>,
	): TrackingPurposeSettingsReconciliationResult {
		if (failures.isEmpty()) {
			return TrackingPurposeSettingsReconciliationResult.Complete(reconciledSources)
		}
		var debt = TrackingPurposeSettingsReconciliationDebt(failures)
		val scheduler = retryScheduler
		val scheduled = if (scheduler == null) {
			true
		} else {
			try {
				scheduler.schedule(debt)
			} catch (cancelled: CancellationException) {
				throw cancelled
			} catch (_: Exception) {
				false
			}
		}
		if (!scheduled) {
			debt = TrackingPurposeSettingsReconciliationDebt(
				debt.failures + TrackingPurposeSettingsReconciliationFailure(
					source = null,
					reason = TrackingPurposeSettingsReconciliationFailureReason
						.RETRY_SCHEDULING_FAILED,
				),
			)
		}
		return TrackingPurposeSettingsReconciliationResult.Debt(debt)
	}

	private fun failureAfterCompensation(
		cleanup: OwnerCleanup,
		onComplete: TrackingRetentionFloorReconciliationFailureReason,
	): TrackingRetentionFloorReconciliationFailureReason = when (cleanup) {
		OwnerCleanup.COMPLETE -> onComplete
		OwnerCleanup.FAILED ->
			TrackingRetentionFloorReconciliationFailureReason.COMPENSATION_FAILED
		OwnerCleanup.TIMED_OUT ->
			TrackingRetentionFloorReconciliationFailureReason.COMPENSATION_TIMED_OUT
	}

	private suspend fun <T> withCurrentReadyGeneration(
		operation: suspend (Long?) -> T,
	): T? {
		val gate = trackingStartupGateProvider?.get() ?: return operation(null)
		val generation = gate.currentGeneration
		return withReadyGeneration(generation) { operation(generation) }
	}

	private suspend fun <T> withReadyGeneration(
		expectedGeneration: Long,
		operation: suspend () -> T,
	): T? {
		val gate = trackingStartupGateProvider?.get() ?: return operation()
		return gate.withReadyGenerationOperation(expectedGeneration, operation)
	}

	private fun <T> publishForReadyGeneration(
		expectedGeneration: Long?,
		operation: () -> T,
	): T? {
		if (expectedGeneration == null) return operation()
		val gate = trackingStartupGateProvider?.get() ?: return operation()
		return gate.withReadyGeneration(expectedGeneration, operation)
	}

	private fun readyGenerationStillCurrent(expectedGeneration: Long?): Boolean {
		if (expectedGeneration == null) return true
		val gate = trackingStartupGateProvider?.get() ?: return true
		return gate.isReadyGeneration(expectedGeneration)
	}

	private companion object {
		const val SOURCE_OWNER_CALLBACK_TIMEOUT_MILLIS = 2_000L
		const val BUILT_IN_STEPS_REGISTRATION_ID = "builtin:ambient:steps"
		const val BUILT_IN_STEPS_EXECUTION_REVISION = 1L
		const val BUILT_IN_WIFI_REGISTRATION_ID = "builtin:ambient:wifi"
		const val BUILT_IN_CELL_REGISTRATION_ID = "builtin:ambient:cell"
		val RETENTION_FLOOR_PROVIDER_SOURCES = setOf(
			AmbientTrackingSource.STEPS,
			AmbientTrackingSource.WIFI,
			AmbientTrackingSource.CELL,
		)
		val DURABLE_AMBIENT_COMPONENTS = linkedMapOf(
			com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent.STEPS to
				AmbientTrackingSource.STEPS,
			com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent.WIFI to
				AmbientTrackingSource.WIFI,
			com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent.CELL to
				AmbientTrackingSource.CELL,
		)
	}
}

private sealed interface BoundedOwnerCall<out T> {
	data class Completed<T>(val value: T) : BoundedOwnerCall<T>
	data object Failed : BoundedOwnerCall<Nothing>
	data object TimedOut : BoundedOwnerCall<Nothing>
}

private data class ReconciliationFlightSelection<T>(
	val flight: CompletableDeferred<Result<T>>,
	val owner: Boolean,
)

private data class AmbientReconciliationRequest(
	val source: AmbientTrackingSource,
	val expectedRetainedFromMs: Long?,
	val requireApproval: Boolean,
	val requireOwner: Boolean,
	val expectedStartupGeneration: Long?,
)

private enum class OwnerCleanup {
	COMPLETE,
	FAILED,
	TIMED_OUT,
}

private enum class ExistingOwnerFlight {
	NONE,
	IN_PROGRESS,
	TERMINAL,
}

private data class OwnerCleanupKey(
	val source: String,
	val ownerToken: String,
	val purpose: OwnerCleanupPurpose,
)

private enum class OwnerCleanupPurpose {
	COMPENSATION,
	RETENTION_FAILURE,
	COLLECTED_DATA_DELETION,
}

private fun AmbientRadioReportPreparation.availabilityOrPending(
	source: AmbientTrackingSource,
	lease: com.adsamcik.tracker.tracker.api.AmbientReconciliationLease,
): AmbientSourceOperationalAvailability = when (this) {
	is AmbientRadioReportPreparation.Prepared -> when (val result = result) {
		is AmbientSourceReconciliationResult.Reconciled -> result.report.availability
		is AmbientSourceReconciliationResult.Unavailable -> result.report.availability
	}
	is AmbientRadioReportPreparation.Rejected ->
		AmbientSourceOperationalAvailability.reconciliationPending(
			source,
			lease.purposeLeaseIdentity,
		)
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
	retainedFromMs = retainedFromMs,
)

private fun TrackingPurposeLeaseIdentity.matches(
	authority: TrackingPurposeAuthoritySnapshot,
): Boolean = sourcePurpose == authority.sourcePurpose &&
	policyRevision == authority.policyRevision &&
	consentEpoch == authority.consentEpoch &&
	collectedDataEpoch == authority.collectedDataEpoch &&
	rolloutRevision == authority.rolloutRevision &&
	executionRevision == authority.executionRevision &&
	retainedFromMs == authority.retainedFromMs

private fun AmbientReconciliationIdentity.matches(
	authority: TrackingPurposeAuthoritySnapshot,
): Boolean = purposeLeaseIdentity.matches(authority) &&
	retentionPolicyId == authority.retentionPolicyId &&
	retentionApprovalRevision == authority.retentionApprovalRevision

private fun TrackingRetentionFloorReconciliationFailureReason.toSettingsFailure(
	source: AmbientTrackingSource?,
): TrackingPurposeSettingsReconciliationFailure =
	TrackingPurposeSettingsReconciliationFailure(
		source = source,
		reason = when (this) {
			TrackingRetentionFloorReconciliationFailureReason.STARTUP_GENERATION_CHANGED ->
				TrackingPurposeSettingsReconciliationFailureReason.STARTUP_GENERATION_CHANGED
			TrackingRetentionFloorReconciliationFailureReason.RETENTION_AUTHORITY_UNAVAILABLE,
			TrackingRetentionFloorReconciliationFailureReason.AUTHORITY_FLOOR_MISMATCH,
			-> TrackingPurposeSettingsReconciliationFailureReason
				.RETENTION_AUTHORITY_UNAVAILABLE
			TrackingRetentionFloorReconciliationFailureReason.OWNER_RECONCILIATION_FAILED ->
				TrackingPurposeSettingsReconciliationFailureReason.OWNER_RECONCILIATION_FAILED
			TrackingRetentionFloorReconciliationFailureReason.OWNER_MISSING ->
				TrackingPurposeSettingsReconciliationFailureReason.OWNER_MISSING
			TrackingRetentionFloorReconciliationFailureReason.PUBLICATION_REJECTED ->
				TrackingPurposeSettingsReconciliationFailureReason.PUBLICATION_REJECTED
			TrackingRetentionFloorReconciliationFailureReason.COMPENSATION_FAILED ->
				TrackingPurposeSettingsReconciliationFailureReason.COMPENSATION_FAILED
			TrackingRetentionFloorReconciliationFailureReason.COMPENSATION_TIMED_OUT ->
				TrackingPurposeSettingsReconciliationFailureReason.COMPENSATION_TIMED_OUT
			TrackingRetentionFloorReconciliationFailureReason.RETIREMENT_FAILED ->
				TrackingPurposeSettingsReconciliationFailureReason.RETIREMENT_FAILED
			TrackingRetentionFloorReconciliationFailureReason.OWNER_OPERATION_IN_PROGRESS ->
				TrackingPurposeSettingsReconciliationFailureReason.OWNER_OPERATION_IN_PROGRESS
		},
	)

private fun retentionFloorRetry(
	retainedFromMs: Long,
	failure: TrackingRetentionFloorReconciliationFailure,
): TrackingRetentionFloorReconciliationResult.Retryable =
	TrackingRetentionFloorReconciliationResult.Retryable(
		TrackingRetentionFloorReconciliationDebt(
			retainedFromMs = retainedFromMs,
			failures = listOf(failure),
		),
	)

internal fun TrackingRolloutState.supportsExecution(
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

internal fun TrackingSource.toSourceKind(): SourceKind = when (this) {
	TrackingSource.LOCATION -> SourceKind.LOCATION
	TrackingSource.ACTIVITY -> SourceKind.ACTIVITY
	TrackingSource.STEPS -> SourceKind.STEPS
	TrackingSource.PRESSURE -> SourceKind.PRESSURE
	TrackingSource.WIFI -> SourceKind.WIFI
	TrackingSource.CELL -> SourceKind.CELL
}

private val ACTIVITY_CONTROL =
	TrackingSource.ACTIVITY.forPurpose(TrackingPurpose.CONTROL)
