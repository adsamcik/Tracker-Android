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
import com.adsamcik.tracker.tracker.api.TrackingPurposeLeaseIdentity
import com.adsamcik.tracker.tracker.api.TrackingPurposeSettingsReconciler
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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
) {
	init {
		require(policyRevision > 0L)
		require(consentEpoch > 0L)
		require(collectedDataEpoch >= 0L)
		require(rolloutRevision >= 0L)
		require(executionRevision >= 0L)
		require(retainedFromMs == null || retainedFromMs >= 0L)
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
		if (
			sourcePurpose.purpose == TrackingPurpose.AMBIENT_PRODUCT &&
			retentionAuthorityReader.currentLiveAmbient(
				source = sourcePurpose.source,
				expectedSourcePolicyRevision = policySnapshot.revision,
				expectedAmbientConsentEpoch = consentEpoch,
				expectedCollectedDataEpoch = lifecycle.epoch,
				expectedRetainedFromMs = lifecycle.retainedFromMs,
			) !is CurrentRetentionAuthority.Approved
		) {
			return null
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
		val matchingIdentity = automaticIdentity?.takeIf { it.matches(authority) }
		val identity =
			matchingIdentity ?: authority.toLeaseIdentity(tokenFactory.next(sourcePurpose))
		when (val started = reporter.beginOrReplaceAutomaticControlLease(identity)) {
			is AutomaticControlLeaseStartResult.Started -> {
				automaticIdentity = identity
				started
			}
			is AutomaticControlLeaseStartResult.Rejected -> {
				if (matchingIdentity == null) return@withLock null
				val replacement = authority.toLeaseIdentity(tokenFactory.next(sourcePurpose))
				when (val retried = reporter.beginOrReplaceAutomaticControlLease(replacement)) {
					is AutomaticControlLeaseStartResult.Started -> {
						automaticIdentity = replacement
						retried
					}
					is AutomaticControlLeaseStartResult.Rejected -> null
				}
			}
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
		val matchingIdentity = ambientIdentities[source]
			?.takeIf { it.purposeLeaseIdentity.matches(authority) }
		val identity = matchingIdentity ?: AmbientReconciliationIdentity.from(
				authority.toLeaseIdentity(tokenFactory.next(sourcePurpose)),
			)
		when (val started = reporter.beginOrReplaceAmbientLease(identity)) {
			is AmbientLeaseStartResult.Started -> {
				ambientIdentities[source] = identity
				started
			}
			is AmbientLeaseStartResult.Rejected -> {
				if (matchingIdentity == null) return@withLock null
				val replacement = AmbientReconciliationIdentity.from(
					authority.toLeaseIdentity(tokenFactory.next(sourcePurpose)),
				)
				when (val retried = reporter.beginOrReplaceAmbientLease(replacement)) {
					is AmbientLeaseStartResult.Started -> {
						ambientIdentities[source] = replacement
						retried
					}
					is AmbientLeaseStartResult.Rejected -> null
				}
			}
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

	suspend fun currentAmbientLease(source: AmbientTrackingSource):
		com.adsamcik.tracker.tracker.api.AmbientReconciliationLease? = mutex.withLock {
		ambientIdentities[source]?.let {
			com.adsamcik.tracker.tracker.api.AmbientReconciliationLease(it)
		}
	}

	suspend fun cancelAutomaticControl(identity: TrackingPurposeLeaseIdentity) = mutex.withLock {
		reporter.cancelAutomaticControlLease(identity)
		if (automaticIdentity == identity) automaticIdentity = null
	}

	suspend fun cancelAmbient(identity: AmbientReconciliationIdentity) = mutex.withLock {
		reporter.cancelAmbientLease(identity)
		if (ambientIdentities[identity.source] == identity) {
			reporter.invalidateAmbient(identity.source)
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
		CoroutineScope(Dispatchers.Default),
	private val ownerCallbackTimeoutMillis: Long = SOURCE_OWNER_CALLBACK_TIMEOUT_MILLIS,
) : TrackingPurposeSettingsReconciler,
	TrackingPurposeSourceOwnerRegistrar,
	TrackingRetentionFloorReconciler {
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
	)

	private val ownerMutex = Mutex()
	private var automaticOwner: AutomaticControlOwnerRegistration? = null
	private val ambientOwners = mutableMapOf<AmbientTrackingSource, AmbientOwnerRegistration>()
	private val boundedOwnerScope =
		CoroutineScope(ownerCallbackScope.coroutineContext.minusKey(Job))

	init {
		require(ownerCallbackTimeoutMillis > 0L)
	}

	override suspend fun reconcileCurrentSettings() {
		retentionAuthorityProducer.reconcileCurrentSettings()
		withCurrentReadyGeneration { startupGeneration ->
			ensureBuiltInAmbientOwners(AmbientTrackingSource.entries.toSet())
			reconcileAutomaticControl(startupGeneration)
			AmbientTrackingSource.entries.forEach { source ->
				reconcileAmbient(
					source,
					expectedRetainedFromMs = null,
					requireApproval = false,
					requireOwner = false,
					expectedStartupGeneration = startupGeneration,
				)
			}
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
				RETENTION_FLOOR_PROVIDER_SOURCES.sortedBy { it.ordinal }.forEach { source ->
					val failure = if (source in approvedSources) {
						reconcileAmbient(
							source,
							expectedRetainedFromMs = retainedFromMs,
							requireApproval = true,
							requireOwner = true,
							expectedStartupGeneration = expectedStartupGeneration,
						)
					} else {
						retireAmbientAfterRetentionAuthorityFailure(source, requireOwner = false)
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
				val rollbackFailures = RETENTION_FLOOR_PROVIDER_SOURCES
					.sortedBy { it.ordinal }
					.mapNotNull { source ->
						retireAmbientAfterRetentionAuthorityFailure(
							source,
							requireOwner = source in approvedSources,
						)?.let { reason ->
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
			reconcileAutomaticControl(startupGeneration)
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
		leaseIssuer.clearAmbient(source)
		ownerMutex.withLock {
			ambientOwners[source] = AmbientOwnerRegistration(
				registration,
				executionRevision,
				callback,
			)
			executionRevisionRegistry.update(
				source.canonicalSource.forPurpose(TrackingPurpose.AMBIENT_PRODUCT),
				executionRevision,
			)
		}
		withCurrentReadyGeneration { startupGeneration ->
			reconcileAmbient(
				source,
				expectedRetainedFromMs = null,
				requireApproval = false,
				requireOwner = false,
				expectedStartupGeneration = startupGeneration,
			)
		}
		return registration
	}

	override suspend fun unregister(registration: TrackingPurposeSourceOwnerRegistration) {
		if (registration.sourcePurpose == ACTIVITY_CONTROL) {
			val removed = ownerMutex.withLock {
				automaticOwner
					?.takeIf { it.registration == registration }
					?.also {
						automaticOwner = null
						executionRevisionRegistry.remove(ACTIVITY_CONTROL)
					}
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
				?.also {
					ambientOwners.remove(source)
					executionRevisionRegistry.remove(registration.sourcePurpose)
				}
		}
		if (removed != null) leaseIssuer.clearAmbient(source)
	}

	private suspend fun reconcileAutomaticControl(expectedStartupGeneration: Long?): Boolean {
		val owner = ownerMutex.withLock { automaticOwner }
		val started =
			leaseIssuer.refreshAutomaticControl(owner?.executionRevision ?: 0L) ?: return true
		if (!isCurrentAutomaticOwner(owner)) {
			leaseIssuer.cancelAutomaticControl(started.lease.identity)
			return false
		}
		val availability = if (owner == null) {
			AutomaticTrackingOperationalAvailability.Unavailable(
				reason =
					AutomaticTrackingUnavailableReason.CONTROL_RETENTION_POLICY_UNAVAILABLE,
				lastIdentity = started.lease.identity,
			)
		} else {
			when (
				val call = runBoundedOwnerCall {
					owner.callback.reconcile(started.lease)
				}
			) {
				is BoundedOwnerCall.Completed -> call.value
				BoundedOwnerCall.Failed,
				BoundedOwnerCall.TimedOut,
				-> {
					leaseIssuer.cancelAutomaticControl(started.lease.identity)
					return false
				}
			}
		}
		if (!isCurrentAutomaticOwner(owner)) {
			leaseIssuer.cancelAutomaticControl(started.lease.identity)
			return false
		}
		if (!readyGenerationStillCurrent(expectedStartupGeneration)) {
			leaseIssuer.cancelAutomaticControl(started.lease.identity)
			return false
		}
		val report = runCatching {
			AutomaticControlReconciliationReport(started.lease.identity, availability)
		}.getOrNull() ?: return false
		val acceptance = publishForReadyGeneration(expectedStartupGeneration) {
			reporter.tryAccept(report)
		}
		if (acceptance !is AutomaticControlPublicationAcceptance.Accepted) {
			leaseIssuer.cancelAutomaticControl(started.lease.identity)
			return false
		}
		return true
	}

	private suspend fun reconcileAmbient(
		source: AmbientTrackingSource,
		expectedRetainedFromMs: Long?,
		requireApproval: Boolean,
		requireOwner: Boolean,
		expectedStartupGeneration: Long?,
	): TrackingRetentionFloorReconciliationFailureReason? {
		val retention = retentionAuthorityProducer.reconcileLiveAmbient(source.canonicalSource)
		if (!retention.isActiveApproval()) {
			val retirementFailure = retireAmbientAfterRetentionAuthorityFailure(
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
			return if (requireOwner) {
				TrackingRetentionFloorReconciliationFailureReason.OWNER_MISSING
			} else {
				null
			}
		}
		val previousLease = leaseIssuer.currentAmbientLease(source)
		val started = leaseIssuer.refreshAmbient(source, owner.executionRevision)
		if (started == null) {
			val retirementFailure = retireAmbientAfterRetentionAuthorityFailure(
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
			started.lease.purposeLeaseIdentity.retainedFromMs != expectedRetainedFromMs
		) {
			leaseIssuer.cancelAmbient(started.lease.identity)
			return TrackingRetentionFloorReconciliationFailureReason.AUTHORITY_FLOOR_MISMATCH
		}
		if (!isCurrentAmbientOwner(source, owner)) {
			leaseIssuer.cancelAmbient(started.lease.identity)
			return TrackingRetentionFloorReconciliationFailureReason.OWNER_RECONCILIATION_FAILED
		}
		if (!readyGenerationStillCurrent(expectedStartupGeneration)) {
			leaseIssuer.cancelAmbient(started.lease.identity)
			return TrackingRetentionFloorReconciliationFailureReason.STARTUP_GENERATION_CHANGED
		}
		val ownerCall = try {
			runBoundedOwnerCall {
				owner.callback.reconcile(started.lease)
			}
		} catch (cancelled: CancellationException) {
			compensateAndCancelAmbient(owner, started.lease)
			throw cancelled
		}
		val availability = when (ownerCall) {
			is BoundedOwnerCall.Completed -> ownerCall.value
			BoundedOwnerCall.Failed,
			BoundedOwnerCall.TimedOut,
			-> {
				return failureAfterCompensation(
					compensateAndCancelAmbient(owner, started.lease),
					TrackingRetentionFloorReconciliationFailureReason
						.OWNER_RECONCILIATION_FAILED,
				)
			}
		}
		if (!isCurrentAmbientOwner(source, owner)) {
			return failureAfterCompensation(
				compensateAndCancelAmbient(owner, started.lease),
				TrackingRetentionFloorReconciliationFailureReason.PUBLICATION_REJECTED,
			)
		}
		if (!readyGenerationStillCurrent(expectedStartupGeneration)) {
			return failureAfterCompensation(
				compensateAndCancelAmbient(owner, started.lease),
				TrackingRetentionFloorReconciliationFailureReason.STARTUP_GENERATION_CHANGED,
			)
		}
		val report = try {
			AmbientSourceReconciliationReport(started.lease.identity, availability)
		} catch (_: IllegalArgumentException) {
			return failureAfterCompensation(
				compensateAndCancelAmbient(owner, started.lease),
				TrackingRetentionFloorReconciliationFailureReason.PUBLICATION_REJECTED,
			)
		}
		return when (publishForReadyGeneration(expectedStartupGeneration) {
			reporter.tryAccept(report)
		}) {
			is AmbientPublicationAcceptance.Accepted -> null
			is AmbientPublicationAcceptance.Rejected ->
				failureAfterCompensation(
					compensateAndCancelAmbient(owner, started.lease),
					TrackingRetentionFloorReconciliationFailureReason.PUBLICATION_REJECTED,
				)
			null -> failureAfterCompensation(
				compensateAndCancelAmbient(owner, started.lease),
				TrackingRetentionFloorReconciliationFailureReason.STARTUP_GENERATION_CHANGED,
			)
		}
	}

	private suspend fun retireAmbientAfterRetentionAuthorityFailure(
		source: AmbientTrackingSource,
		requireOwner: Boolean,
		knownPreviousLease:
			com.adsamcik.tracker.tracker.api.AmbientReconciliationLease? = null,
	): TrackingRetentionFloorReconciliationFailureReason? {
		val owner = ownerMutex.withLock { ambientOwners[source] }
		val previousLease = knownPreviousLease ?: leaseIssuer.currentAmbientLease(source)
		if (owner == null) {
			leaseIssuer.clearAmbient(source)
			reporter.publishAmbientUnavailable(
				source,
				com.adsamcik.tracker.tracker.api.AmbientSourceUnavailableReason
					.RETENTION_POLICY_UNAVAILABLE,
			)
			return if (requireOwner) {
				TrackingRetentionFloorReconciliationFailureReason.OWNER_MISSING
			} else {
				null
			}
		}
		val cleanup = try {
			runBoundedCleanup {
				owner.callback.retireAfterRetentionAuthorityFailure(previousLease)
			}
		} finally {
			withContext(NonCancellable) {
				leaseIssuer.clearAmbient(source)
				reporter.publishAmbientUnavailable(
					source,
					com.adsamcik.tracker.tracker.api.AmbientSourceUnavailableReason
						.RETENTION_POLICY_UNAVAILABLE,
				)
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
		val cleanup = runBoundedCleanup {
			owner.callback.compensate(lease)
		}
		leaseIssuer.cancelAmbient(lease.identity)
		cleanup
	}

	private suspend fun <T> runBoundedOwnerCall(
		operation: suspend () -> T,
	): BoundedOwnerCall<T> {
		val task = boundedOwnerScope.async {
			runCatching { operation() }
		}
		val result = try {
			withTimeoutOrNull(ownerCallbackTimeoutMillis) { task.await() }
		} catch (cancelled: CancellationException) {
			task.cancel()
			throw cancelled
		}
		if (result == null) {
			task.cancel()
			return BoundedOwnerCall.TimedOut
		}
		return result.fold(
			onSuccess = { BoundedOwnerCall.Completed(it) },
			onFailure = { BoundedOwnerCall.Failed },
		)
	}

	private suspend fun runBoundedCleanup(
		operation: suspend () -> Boolean,
	): OwnerCleanup = withContext(NonCancellable) {
		val task = boundedOwnerScope.async {
			runCatching { operation() }.getOrDefault(false)
		}
		val completed = withTimeoutOrNull(ownerCallbackTimeoutMillis) {
			task.await()
		}
		if (completed == null) {
			task.cancel()
			OwnerCleanup.TIMED_OUT
		} else if (completed) {
			OwnerCleanup.COMPLETE
		} else {
			OwnerCleanup.FAILED
		}
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
	}
}

private sealed interface BoundedOwnerCall<out T> {
	data class Completed<T>(val value: T) : BoundedOwnerCall<T>
	data object Failed : BoundedOwnerCall<Nothing>
	data object TimedOut : BoundedOwnerCall<Nothing>
}

private enum class OwnerCleanup {
	COMPLETE,
	FAILED,
	TIMED_OUT,
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
