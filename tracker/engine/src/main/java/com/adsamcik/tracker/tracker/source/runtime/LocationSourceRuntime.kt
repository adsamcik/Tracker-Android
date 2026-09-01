package com.adsamcik.tracker.tracker.source.runtime

import android.content.Context
import android.location.Location
import android.os.SystemClock
import com.adsamcik.tracker.shared.base.di.ApplicationScope
import com.adsamcik.tracker.tracker.source.runCatchingNonCancellation
import com.adsamcik.tracker.tracker.source.model.LocationBackend
import com.adsamcik.tracker.tracker.source.model.LocationFixPayload
import com.adsamcik.tracker.tracker.source.model.LocationMode
import com.adsamcik.tracker.tracker.source.model.LocationPlan
import com.adsamcik.tracker.tracker.source.model.physicalConfigurationFingerprint
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.SourceApplyStatus
import com.adsamcik.tracker.tracker.source.model.SourceDegradedReason
import com.adsamcik.tracker.tracker.source.model.SourceDeliveryCandidate
import com.adsamcik.tracker.tracker.source.model.SourceDeliveryUnit
import com.adsamcik.tracker.tracker.source.model.SourceEvidenceCandidate
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourceQuality
import com.adsamcik.tracker.tracker.source.model.SourceQualityFlag
import com.adsamcik.tracker.tracker.source.model.sourceDeliveryIdentity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@Singleton
class LocationSourceRuntime @Inject internal constructor(
	@ApplicationContext private val context: Context,
	@ApplicationScope private val applicationScope: CoroutineScope,
	private val registrations: SourceRegistrationRepository,
	private val fusedBackend: FusedLocationSourceBackend,
	private val frameworkBackend: FrameworkLocationSourceBackend,
	private val prerequisiteEvaluator: LocationPrerequisiteEvaluator,
	private val deviceStateProvider: LocationDeviceStateProvider,
) : ClaimedSourceRuntime<LocationPlan> {
	override val source = SourceKind.LOCATION
	private val _capabilities = MutableStateFlow(currentCapabilities())
	override val capabilities: StateFlow<SourceCapabilities> = _capabilities
	private val lifecycleMutex = Mutex()
	private val callbackLock = Any()
	private var registration: SourceRegistration? = null
	private var currentPlan: LocationPlan? = null
	private var currentSink: SourceEventSink? = null
	private var activeBackend: LocationSourceBackendController? = null
	private var pendingRetirementToken: SourceRegistrationRetirementToken? = null
	private var callbackToken: LocationCallbackToken? = null
	private var callbackContext: LocationCallbackContext? = null
	private var queue: LocationCallbackLane? = null
	private var actor: Job? = null
	private var acceptingCallbacks = false
	private var callbackEntrySequence = 0L
	private val processedCallbackSequence = MutableStateFlow(0L)
	@Volatile
	private var cutoffElapsedNanos: Long? = null
	private var metrics = RuntimeAdmissionMetrics()
	private var lastAdmissionOrdinalHighWater: Long? = null
	private var callbackOfferFailureCoverage = LocationCallbackFailureCoverage()
	private var terminalStopAck: SourceStopAck? = null
	private var retainedRetirementAck: SourceStopAck? = null
	private var retainedRetirementActor: Job? = null
	private var providerRetirementCompletedWhileActorRetained = false
	private var ownerClaim: SourceRuntimeClaim? = null
	private val permissionGate = LocationPermissionGate(deviceStateProvider)

	override suspend fun start(plan: LocationPlan, sink: SourceEventSink): SourceStartResult =
		startWithClaim(null, plan, sink)

	override suspend fun start(
		claim: SourceRuntimeClaim,
		plan: LocationPlan,
		sink: SourceEventSink,
	): SourceStartResult {
		require(claim.source == source)
		return startWithClaim(claim, plan, sink)
	}

	private suspend fun startWithClaim(
		claim: SourceRuntimeClaim?,
		plan: LocationPlan,
		sink: SourceEventSink,
	): SourceStartResult = lifecycleMutex.withLock {
		if (currentPlan != null) {
			require(!isAcceptingCallbacks()) { "Location source is already started" }
			if (!retryOwnedRetirementLocked()) {
				return@withLock SourceStartResult.Failed(
					appliedState(
						source,
						plan.revision,
						registration,
						SourceApplyStatus.FAILED,
						SystemClock.elapsedRealtimeNanos(),
					),
					retryable = true,
				)
			}
		}
		if (!reconcilePendingRetirementLocked()) {
			return@withLock SourceStartResult.Failed(
				appliedState(
					source,
					plan.revision,
					null,
					SourceApplyStatus.FAILED,
					SystemClock.elapsedRealtimeNanos(),
				),
				retryable = true,
			)
		}
		startLocked(claim, plan, sink)
	}

	override suspend fun reconfigure(plan: LocationPlan, sink: SourceEventSink): SourceApplyResult =
		reconfigureWithClaim(null, plan, sink)

	override suspend fun reconfigure(
		claim: SourceRuntimeClaim,
		plan: LocationPlan,
		sink: SourceEventSink,
	): SourceApplyResult {
		require(claim.source == source)
		return reconfigureWithClaim(claim, plan, sink)
	}

	private suspend fun reconfigureWithClaim(
		claim: SourceRuntimeClaim?,
		plan: LocationPlan,
		sink: SourceEventSink,
	): SourceApplyResult = lifecycleMutex.withLock {
		if (isAcceptingCallbacks()) {
			refreshCompatibleLocked(claim, plan, sink)?.let { refreshed ->
				return@withLock refreshed
			}
		}
		var predecessorStopAck: SourceStopAck? = null
		if (currentPlan != null) {
			val stopped = if (isAcceptingCallbacks() || hasUnretiredCallbackLane()) {
				shutdownLocked(null, RETIRE_REASON_RECONFIGURE)
			} else {
				null
			}
			predecessorStopAck = stopped ?: terminalStopAck
			val cleanupComplete = stopped?.status == SourceStopStatus.COMPLETE ||
				(stopped == null && retryOwnedRetirementLocked())
			if (!cleanupComplete) {
				return@withLock SourceApplyResult.Failed(
					appliedState(source, plan.revision, null, SourceApplyStatus.FAILED, SystemClock.elapsedRealtimeNanos()),
					retryable = true,
					stopAck = predecessorStopAck,
				)
			}
		}
		if (!reconcilePendingRetirementLocked()) {
			return@withLock SourceApplyResult.Failed(
				appliedState(source, plan.revision, null, SourceApplyStatus.FAILED, SystemClock.elapsedRealtimeNanos()),
				retryable = true,
			)
		}
		if (!plan.enabled) {
			return@withLock SourceApplyResult.Applied(
				appliedState(source, plan.revision, null, SourceApplyStatus.APPLIED, SystemClock.elapsedRealtimeNanos()),
				stopAck = predecessorStopAck,
			)
		}
		when (val result = startLocked(claim, plan, sink)) {
			is SourceStartResult.Started -> SourceApplyResult.Applied(result.applied, predecessorStopAck)
			is SourceStartResult.Degraded -> SourceApplyResult.Degraded(result.applied, predecessorStopAck)
			is SourceStartResult.Blocked -> SourceApplyResult.Failed(
				result.applied,
				retryable = false,
				stopAck = predecessorStopAck,
			)
			is SourceStartResult.Failed -> SourceApplyResult.Failed(
				result.applied,
				result.retryable,
				predecessorStopAck,
			)
		}
	}

	override suspend fun quiesce(cutoff: SessionCutoff): SourceStopAck = lifecycleMutex.withLock {
		terminalStopAck ?: shutdownLocked(cutoff, RETIRE_REASON_SESSION_STOP)
	}

	override suspend fun close() {
		lifecycleMutex.withLock {
			if (currentPlan != null) {
				if (isAcceptingCallbacks()) {
					shutdownLocked(null, RETIRE_REASON_RUNTIME_CLOSE)
				} else {
					retryOwnedRetirementLocked()
				}
			} else {
				reconcilePendingRetirementLocked()
			}
		}
	}

	override suspend fun shutdownIfOwned(
		claim: SourceRuntimeClaim,
		cutoff: SessionCutoff,
	): OwnedSourceShutdown {
		require(claim.source == source)
		return lifecycleMutex.withLock {
			if (ownerClaim != claim) return@withLock OwnedSourceShutdown.NotOwned
			val provider = registration?.providerKey()
			if (isAcceptingCallbacks() || hasUnretiredCallbackLane()) {
				return@withLock shutdownLocked(cutoff, RETIRE_REASON_SESSION_STOP).toOwnedShutdown()
			}
			val retirementComplete = retryOwnedRetirementLocked()
			val acknowledgement = terminalStopAck ?: retainedRetirementAck
			if (!retirementComplete) {
				OwnedSourceShutdown.Incomplete(provider, acknowledgement)
			} else {
				acknowledgement?.toOwnedShutdown()
					?: OwnedSourceShutdown.Released(provider, stopAck = null)
			}
		}
	}

	/**
	 * Refreshes callback attribution without replacing unchanged Android provider work. The callback
	 * context swap shares [callbackLock] with callback entry, so already queued batches retain their
	 * old immutable authorization/config/sink while later callbacks capture the refreshed vector.
	 */
	private suspend fun refreshCompatibleLocked(
		claim: SourceRuntimeClaim?,
		plan: LocationPlan,
		sink: SourceEventSink,
	): SourceApplyResult? {
		val activePlan = currentPlan ?: return null
		val activeRegistration = registration ?: return null
		val activeToken = callbackToken ?: return null
		val activeContext = callbackContext ?: return null
		if (!plan.enabled) return null
		_capabilities.value = currentCapabilities(activeBackend)
		val application = prerequisiteEvaluator.evaluate(
			plan,
			deviceStateProvider.snapshot(),
			LocationStartContext.SESSION_ALREADY_FOREGROUND,
		)
		if (application.status == LocationPlanApplicationStatus.BLOCKED) return null
		val effectivePlan = application.plan
		val physicalFingerprint = effectivePlan.physicalConfigurationFingerprint()
		if (activePlan.physicalConfigurationFingerprint() != physicalFingerprint) return null
		val refreshBoundary = synchronized(callbackLock) {
			if (!acceptingCallbacks || callbackToken !== activeToken || registration !== activeRegistration) {
				return null
			}
			// A callback is either admitted with the old immutable context before this boundary or
			// rejected until the refreshed durable authorization and context are installed.
			acceptingCallbacks = false
			SystemClock.elapsedRealtimeNanos()
		}
		val refreshed = runCatchingNonCancellation {
			registrations.refreshActiveAuthorization(
				source,
				activeRegistration,
				plan.revision,
				physicalFingerprint,
				System.currentTimeMillis(),
				refreshBoundary,
			)
		}.getOrElse { return null } ?: return null
		if (refreshed.requiresProviderAcceptance ||
			refreshed.state.sourceInstanceId != activeRegistration.state.sourceInstanceId ||
			refreshed.state.registrationGeneration != activeRegistration.state.registrationGeneration ||
			refreshed.physicalConfigurationFingerprint != activeRegistration.physicalConfigurationFingerprint
		) return null
		val switched = synchronized(callbackLock) {
			if (acceptingCallbacks || callbackToken !== activeToken || registration !== activeRegistration) {
				false
			} else {
				registration = refreshed
				currentPlan = effectivePlan
				currentSink = sink
				callbackContext = refreshed.toLocationCallbackContext(
					plan = effectivePlan,
					sink = sink,
					minimumObservedElapsedRealtimeNanos = maxOf(
						activeContext.minimumObservedElapsedRealtimeNanos,
						refreshBoundary,
						refreshed.authorization.effectiveElapsedRealtimeNanos,
					),
				)
				acceptingCallbacks = true
				true
			}
		}
		if (!switched) return null
		// Same-pair authorization refresh is still a new lifecycle attempt. Transfer shutdown
		// authority only after the refreshed immutable callback context has committed.
		ownerClaim = claim
		val applyStatus = if (application.status == LocationPlanApplicationStatus.DEGRADED) {
			SourceApplyStatus.DEGRADED
		} else {
			SourceApplyStatus.APPLIED
		}
		val state = appliedState(
			source,
			plan.revision,
			refreshed,
			applyStatus,
			SystemClock.elapsedRealtimeNanos(),
		).copy(degradedReasons = application.reasons)
		return if (applyStatus == SourceApplyStatus.DEGRADED) {
			SourceApplyResult.Degraded(state)
		} else {
			SourceApplyResult.Applied(state)
		}
	}

	private suspend fun startLocked(
		claim: SourceRuntimeClaim?,
		plan: LocationPlan,
		sink: SourceEventSink,
	): SourceStartResult {
		terminalStopAck = null
		if (!plan.enabled) return SourceStartResult.Started(
			appliedState(source, plan.revision, null, SourceApplyStatus.APPLIED, SystemClock.elapsedRealtimeNanos()),
		)
		_capabilities.value = currentCapabilities()
		val application = prerequisiteEvaluator.evaluate(
			plan,
			deviceStateProvider.snapshot(),
			LocationStartContext.SESSION_ALREADY_FOREGROUND,
		)
		if (application.status == LocationPlanApplicationStatus.BLOCKED) return SourceStartResult.Blocked(
			appliedState(source, plan.revision, null, SourceApplyStatus.BLOCKED, SystemClock.elapsedRealtimeNanos())
				.copy(degradedReasons = application.reasons),
		)
		val effectivePlan = application.plan
		val nextRegistration = runCatchingNonCancellation {
			registrations.begin(
				source,
				plan.revision,
				effectivePlan.physicalConfigurationFingerprint(),
				System.currentTimeMillis(),
			)
		}.getOrElse {
			return SourceStartResult.Failed(
				appliedState(source, plan.revision, null, SourceApplyStatus.FAILED, SystemClock.elapsedRealtimeNanos()),
				retryable = true,
			)
		}
		val backend: LocationSourceBackendController = when (effectivePlan.backend) {
			LocationBackend.FUSED -> fusedBackend
			LocationBackend.FRAMEWORK -> frameworkBackend
		}
		val nextQueue = LocationCallbackLane()
		val nextCallbackToken = LocationCallbackToken()
		synchronized(callbackLock) {
			registration = nextRegistration
			currentPlan = effectivePlan
			currentSink = sink
			activeBackend = backend
			callbackToken = nextCallbackToken
			callbackContext = null
			queue = nextQueue
			acceptingCallbacks = false
		}
		callbackEntrySequence = 0L
		processedCallbackSequence.value = 0L
		cutoffElapsedNanos = null
		metrics = RuntimeAdmissionMetrics()
		lastAdmissionOrdinalHighWater = null
		callbackOfferFailureCoverage = LocationCallbackFailureCoverage()
		// Provider start is apply-then-report: bind the explicit attempt before the call so a
		// throwing or partially failed publication remains exactly claim-addressable.
		ownerClaim = claim
		val startOutcome = try {
			backend.start(effectivePlan) { locations -> onLocationBatch(nextCallbackToken, locations) }
		} catch (cancelled: CancellationException) {
			cleanupFailedStart(nextRegistration, backend, nextQueue, "PROVIDER_REGISTRATION_CANCELLED")
			throw cancelled
		} catch (fatal: Error) {
			cleanupFailedStart(nextRegistration, backend, nextQueue, "PROVIDER_REGISTRATION_FATAL")
			throw fatal
		} catch (_: Exception) {
			// A backend implementation must normally return CLEANUP_REQUIRED. Treat an unexpected
			// throw as apply-then-fail too: the exact provisional handle remains backend-owned.
			LocationBackendStartOutcome.CLEANUP_REQUIRED
		}
		when (startOutcome) {
			LocationBackendStartOutcome.STARTED -> Unit
			LocationBackendStartOutcome.CLEANUP_REQUIRED -> {
				cleanupFailedStart(nextRegistration, backend, nextQueue, "PROVIDER_REGISTRATION_FAILED")
				return SourceStartResult.Failed(
					appliedState(source, plan.revision, nextRegistration, SourceApplyStatus.FAILED,
						SystemClock.elapsedRealtimeNanos()),
					retryable = true,
				)
			}
			LocationBackendStartOutcome.BLOCKED_BY_RETAINED_REGISTRATION -> {
				cleanupBlockedReservation(nextRegistration, nextQueue)
				return SourceStartResult.Failed(
					appliedState(source, plan.revision, nextRegistration, SourceApplyStatus.FAILED,
						SystemClock.elapsedRealtimeNanos()),
					retryable = true,
				)
			}
		}
		// A durable ACTIVE row may be reused even though this runtime has just attached a new local
		// provider callback. In that case durable history cannot prove when this callback became
		// authoritative, so the local post-start boundary is deliberately stricter.
		val postBackendStartElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
		var minimumObservedElapsedRealtimeNanos = postBackendStartElapsedRealtimeNanos
		val accepted = try {
			if (nextRegistration.requiresProviderAcceptance) {
				val acceptedElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
				registrations.markAccepted(
					nextRegistration,
					System.currentTimeMillis(),
					acceptedElapsedRealtimeNanos,
				)
				minimumObservedElapsedRealtimeNanos = acceptedElapsedRealtimeNanos
			}
			true
		} catch (cancelled: CancellationException) {
			cleanupFailedStart(nextRegistration, backend, nextQueue, "REGISTRATION_ACCEPTANCE_CANCELLED")
			throw cancelled
		} catch (fatal: Error) {
			cleanupFailedStart(nextRegistration, backend, nextQueue, "REGISTRATION_ACCEPTANCE_FATAL")
			throw fatal
		} catch (_: Exception) {
			false
		}
		if (!accepted) {
			cleanupFailedStart(nextRegistration, backend, nextQueue, "REGISTRATION_ACCEPTANCE_STALE")
			return SourceStartResult.Failed(
				appliedState(source, plan.revision, nextRegistration, SourceApplyStatus.FAILED, SystemClock.elapsedRealtimeNanos()),
				retryable = true,
			)
		}
		val nextActor = applicationScope.launch(start = CoroutineStart.LAZY) { consume(nextQueue) }
		actor = nextActor
		nextActor.invokeOnCompletion { failure ->
			if (failure == null) return@invokeOnCompletion
			val ownsFailedLane = synchronized(callbackLock) {
				if (actor !== nextActor || callbackToken !== nextCallbackToken ||
					registration !== nextRegistration || !acceptingCallbacks
				) {
					false
				} else {
					// A supervised fatal actor must not leave Android feeding a dead consumer.
					acceptingCallbacks = false
					nextQueue.close()
					true
				}
			}
			if (ownsFailedLane) {
				applicationScope.launch {
					settleExceptionalActorFailure(nextActor, nextRegistration, backend)
				}
			}
		}
		synchronized(callbackLock) {
			check(callbackToken === nextCallbackToken && registration === nextRegistration)
			callbackContext = nextRegistration.toLocationCallbackContext(
				plan = effectivePlan,
				sink = sink,
				minimumObservedElapsedRealtimeNanos = maxOf(
					minimumObservedElapsedRealtimeNanos,
					nextRegistration.authorization.effectiveElapsedRealtimeNanos,
				),
			)
			acceptingCallbacks = true
		}
		nextActor.start()
		_capabilities.value = currentCapabilities(backend)
		val approximate = application.status == LocationPlanApplicationStatus.DEGRADED
		val state = appliedState(
			source,
			plan.revision,
			nextRegistration,
			if (approximate) SourceApplyStatus.DEGRADED else SourceApplyStatus.APPLIED,
			SystemClock.elapsedRealtimeNanos(),
		).copy(degradedReasons = application.reasons)
		return if (approximate) SourceStartResult.Degraded(state) else SourceStartResult.Started(state)
	}

	private suspend fun cleanupFailedStart(
		failedRegistration: SourceRegistration,
		backend: LocationSourceBackendController,
		callbackLane: LocationCallbackLane,
		failureCode: String,
	) = withContext(NonCancellable) {
		val retiredAtMs: Long
		val retiredElapsedRealtimeNanos: Long
		synchronized(callbackLock) {
			acceptingCallbacks = false
			callbackLane.close()
			retiredAtMs = System.currentTimeMillis()
			retiredElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
		}
		actor?.cancelAndJoin()
		val retirement = retireProviderLocked(
			failedRegistration,
			backend,
			failureCode,
			retiredAtMs,
			retiredElapsedRealtimeNanos,
		)
		if (retirement.durability == LocationRetirementDurability.COMPLETE) {
			clearActiveState()
		} else {
			retainRetirementOwnership()
		}
	}

	private suspend fun cleanupBlockedReservation(
		blockedRegistration: SourceRegistration,
		callbackLane: LocationCallbackLane,
	) = withContext(NonCancellable) {
		synchronized(callbackLock) {
			acceptingCallbacks = false
			callbackLane.close()
		}
		// This exact reservation never reached the provider. Do not remove the older retained handle
		// under its identity; fail only the untouched reservation and leave the backend fail-closed.
		withTimeoutOrNull(PROVIDER_OPERATION_TIMEOUT_MS) {
			runCatchingNonCancellation {
				registrations.failUnacceptedReservation(
					blockedRegistration,
					"BACKEND_RETAINED_REGISTRATION",
					System.currentTimeMillis(),
				)
			}
		}
		clearActiveState()
	}

	private suspend fun shutdownLocked(cutoff: SessionCutoff?, retirementReason: String): SourceStopAck {
		val acknowledgement = withContext(NonCancellable) {
			settleShutdownLocked(cutoff, retirementReason)
		}
		currentCoroutineContext().ensureActive()
		return acknowledgement
	}

	private suspend fun settleShutdownLocked(cutoff: SessionCutoff?, retirementReason: String): SourceStopAck {
		val activeRegistration = registration ?: return unavailableAck(cutoff)
		val backend = activeBackend
		cutoffElapsedNanos = cutoff?.elapsedRealtimeNanos
		val flushOutcome = if (backend == null || !isAcceptingCallbacks()) ProviderFlushOutcome.NOT_REQUESTED else {
			withTimeoutOrNull(PROVIDER_OPERATION_TIMEOUT_MS) { backend.flush() }
				?: ProviderFlushOutcome.TIMED_OUT
		}
		val barrier: Long
		val retiredAtMs: Long
		val retiredElapsedRealtimeNanos: Long
		synchronized(callbackLock) {
			acceptingCallbacks = false
			barrier = callbackEntrySequence
			retiredAtMs = System.currentTimeMillis()
			retiredElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
		}
		val retirement = retireProviderLocked(
			activeRegistration,
			backend,
			retirementReason,
			retiredAtMs,
			retiredElapsedRealtimeNanos,
		)
		synchronized(callbackLock) { queue?.close() }
		val activeActor = actor
		val remainingMs = cutoff?.let {
			((it.deadlineElapsedRealtimeNanos - SystemClock.elapsedRealtimeNanos()) /
				LOCATION_NANOS_PER_MILLISECOND)
				.coerceAtLeast(1L)
		} ?: DEFAULT_DRAIN_TIMEOUT_MS
		val actorSettledBeforeDeadline = activeActor == null ||
			withTimeoutOrNull(remainingMs) { activeActor.join(); true } == true
		val actorSettledAfterCancellation = if (!actorSettledBeforeDeadline) {
			requireNotNull(activeActor).cancel()
			withTimeoutOrNull(PROVIDER_OPERATION_TIMEOUT_MS) { activeActor.join(); true } == true
		} else {
			true
		}
		val unaccountedTail = callbackOfferFailureCoverage.unaccountedRanges(
			processedCallbackSequence.value,
			barrier,
		)
		unaccountedTail.forEach { unresolved ->
			metrics.recordFailure(
				unresolved.first,
				unresolved.last,
				if (actorSettledBeforeDeadline) {
					RuntimeGapClassification.ADMISSION_FAILED
				} else {
					RuntimeGapClassification.DRAIN_TIMED_OUT
				},
			)
		}
		val drainComplete = actorSettledBeforeDeadline && unaccountedTail.isEmpty()
		val admission = metrics.snapshot()
		val ack = SourceStopAck(
			source = source,
			sourceInstanceId = SourceInstanceId(activeRegistration.state.sourceInstanceId),
			registrationGeneration = activeRegistration.state.registrationGeneration,
			appliedRevision = currentPlan?.revision,
			callbackEntryBarrierSequence = barrier,
			lastDurablyAdmittedSequence = admission.lastDurablyAdmittedSequence,
			lastAdmissionOrdinal = admission.lastAdmissionOrdinal,
			failedAdmissionCount = admission.failedAdmissionCount,
			unresolvedSequenceStart = admission.unresolvedSequenceStart,
			unresolvedSequenceEndInclusive = admission.unresolvedSequenceEndInclusive,
			registrationRemovalOutcome = retirement.removalOutcome,
			providerFlushOutcome = flushOutcome,
			// Neither FLP flushLocations nor LocationManager removal proves provider-wide completeness.
			providerCoverage = ProviderCoverage.PROVIDER_COMPLETENESS_UNOBSERVABLE,
			appDrainComplete = drainComplete,
			status = when {
				!drainComplete -> SourceStopStatus.TIMED_OUT
				retirement.durability != LocationRetirementDurability.COMPLETE ->
					SourceStopStatus.PROVIDER_FAILED
				else -> SourceStopStatus.COMPLETE
			},
		).withSessionMembership(ownerClaim)
		if (retirement.durability == LocationRetirementDurability.COMPLETE) {
			if (actorSettledAfterCancellation) {
				clearActiveState()
			} else {
				retainRetirementUntilActorSettles(
					requireNotNull(activeActor),
					providerRetirementComplete = true,
				)
			}
		} else {
			if (actorSettledAfterCancellation) {
				retainRetirementOwnership()
			} else {
				retainRetirementUntilActorSettles(
					requireNotNull(activeActor),
					providerRetirementComplete = false,
				)
			}
		}
		retainedRetirementAck = ack.takeUnless { actorSettledAfterCancellation }
		if (retirement.durability == LocationRetirementDurability.COMPLETE) {
			terminalStopAck = ack
		}
		return ack
	}

	private suspend fun settleExceptionalActorFailure(
		failedActor: Job,
		failedRegistration: SourceRegistration,
		backend: LocationSourceBackendController,
	) = lifecycleMutex.withLock {
		if (actor !== failedActor || registration !== failedRegistration || activeBackend !== backend) return@withLock
		if (terminalStopAck == null) {
			settleShutdownLocked(null, RETIRE_REASON_ACTOR_FAILURE)
		}
	}

	private fun isAcceptingCallbacks(): Boolean = synchronized(callbackLock) { acceptingCallbacks }

	/** A compatible-refresh failure may fence entry before the still-live actor has been retired. */
	private fun hasUnretiredCallbackLane(): Boolean = actor != null && retainedRetirementActor == null

	/**
	 * Retries retirement for the exact registration/backend pair retained by this runtime. Provider
	 * removal is never attempted until the retirement boundary is durable.
	 */
	private suspend fun retryOwnedRetirementLocked(): Boolean {
		val retainedActor = retainedRetirementActor
		if (providerRetirementCompletedWhileActorRetained) {
			if (retainedActor?.isCompleted != true) return false
			clearActiveState()
			return true
		}
		val ownedRegistration = registration
		if (ownedRegistration == null) {
			val reconciled = reconcilePendingRetirementLocked()
			if (reconciled) clearActiveState()
			return reconciled
		}
		if (isAcceptingCallbacks()) return false
		val retirement = retireProviderLocked(
			ownedRegistration,
			activeBackend,
			RETIRE_REASON_RETRY,
			System.currentTimeMillis(),
			SystemClock.elapsedRealtimeNanos(),
		)
		return if (retirement.durability == LocationRetirementDurability.COMPLETE) {
			if (retainedActor != null && !retainedActor.isCompleted) {
				terminalStopAck = retainedRetirementAck?.copy(
					registrationRemovalOutcome = retirement.removalOutcome,
				)
				retainRetirementUntilActorSettles(retainedActor, providerRetirementComplete = true)
				false
			} else {
				clearActiveState()
				true
			}
		} else {
			if (retainedActor != null && !retainedActor.isCompleted) {
				retainRetirementUntilActorSettles(retainedActor, providerRetirementComplete = false)
			} else {
				retainRetirementOwnership()
			}
			false
		}
	}

	/**
	 * Reconciles at most one process-local durable retirement. A retained backend object without a
	 * durable token is intentionally not removed: there is no exact durable authority for that side
	 * effect, so admission of a replacement remains fenced.
	 */
	private suspend fun reconcilePendingRetirementLocked(): Boolean = withContext(NonCancellable) {
		val pending = withTimeoutOrNull(PROVIDER_OPERATION_TIMEOUT_MS) {
			runCatchingNonCancellation { registrations.pendingRetirements(source) }.getOrNull()
		} ?: return@withContext false
		if (pending.size > 1) return@withContext false

		val retainedBackends = retainedBackendsOrNull() ?: return@withContext false
		if (pending.isEmpty()) {
			// A handle with no durable RETIRING row cannot be safely removed or replaced.
			if (retainedBackends.isNotEmpty()) return@withContext false
			pendingRetirementToken = null
			return@withContext true
		}

		val token = pending.single()
		val locallyOwnedToken = pendingRetirementToken
		if (locallyOwnedToken != null && !locallyOwnedToken.sameRetirementAs(token)) {
			return@withContext false
		}
		val locallyOwnedRegistration = registration
		if (locallyOwnedRegistration != null && !token.belongsTo(locallyOwnedRegistration)) {
			return@withContext false
		}
		pendingRetirementToken = token

		val preferredBackend = activeBackend
		val retirement = removeAndCompleteRetirementLocked(
			token = token,
			preferredBackend = preferredBackend,
			knownRetainedBackends = retainedBackends,
		)
		if (retirement.durability == LocationRetirementDurability.COMPLETE) {
			clearActiveState()
			true
		} else {
			if (locallyOwnedRegistration != null) retainRetirementOwnership()
			false
		}
	}

	private suspend fun retireProviderLocked(
		activeRegistration: SourceRegistration,
		backend: LocationSourceBackendController?,
		reason: String,
		retiredAtMs: Long,
		retiredElapsedRealtimeNanos: Long,
	): LocationRetirementAttempt = withContext(NonCancellable) {
		val existingToken = pendingRetirementToken
		if (existingToken != null && !existingToken.belongsTo(activeRegistration)) {
			return@withContext LocationRetirementAttempt(
				LocationRetirementDurability.NOT_DURABLE,
				RegistrationRemovalOutcome.FAILED,
			)
		}
		val token = existingToken ?: withTimeoutOrNull(PROVIDER_OPERATION_TIMEOUT_MS) {
			runCatchingNonCancellation {
				registrations.beginRetirement(
					activeRegistration,
					reason,
					retiredAtMs,
					retiredElapsedRealtimeNanos,
				)
			}.getOrNull()
		} ?: return@withContext LocationRetirementAttempt(
			LocationRetirementDurability.NOT_DURABLE,
			RegistrationRemovalOutcome.FAILED,
		)
		pendingRetirementToken = token
		removeAndCompleteRetirementLocked(token, backend)
	}

	private suspend fun removeAndCompleteRetirementLocked(
		token: SourceRegistrationRetirementToken,
		preferredBackend: LocationSourceBackendController?,
		knownRetainedBackends: List<LocationSourceBackendController>? = null,
	): LocationRetirementAttempt {
		if (token.source != source) return LocationRetirementAttempt(
			LocationRetirementDurability.PENDING,
			RegistrationRemovalOutcome.FAILED,
		)
		val retainedBackends = knownRetainedBackends ?: retainedBackendsOrNull()
			?: return LocationRetirementAttempt(
				LocationRetirementDurability.PENDING,
				RegistrationRemovalOutcome.FAILED,
			)
		// Multiple exact handles, or a handle in a backend other than the locally recorded owner,
		// cannot be attributed to this single token. Leave every handle untouched and fail closed.
		if (retainedBackends.size > 1 ||
			(preferredBackend != null && retainedBackends.any { it !== preferredBackend })
		) return LocationRetirementAttempt(
			LocationRetirementDurability.PENDING,
			RegistrationRemovalOutcome.FAILED,
		)
		val backendToStop = preferredBackend ?: retainedBackends.singleOrNull()
		val removal = if (backendToStop == null) {
			RegistrationRemovalOutcome.NOT_REGISTERED
		} else {
			withTimeoutOrNull(PROVIDER_OPERATION_TIMEOUT_MS) {
				runCatchingNonCancellation { backendToStop.stop() }
					.getOrDefault(RegistrationRemovalOutcome.FAILED)
			} ?: RegistrationRemovalOutcome.FAILED
		}
		if (removal !in setOf(
				RegistrationRemovalOutcome.REMOVED,
				RegistrationRemovalOutcome.NOT_REGISTERED,
			)
		) return LocationRetirementAttempt(LocationRetirementDurability.PENDING, removal)

		val completed = withTimeoutOrNull(PROVIDER_OPERATION_TIMEOUT_MS) {
			runCatchingNonCancellation { registrations.completeRetirement(token) }.getOrDefault(false)
		} == true
		return if (completed) {
			pendingRetirementToken = null
			LocationRetirementAttempt(LocationRetirementDurability.COMPLETE, removal)
		} else {
			LocationRetirementAttempt(LocationRetirementDurability.PENDING, removal)
		}
	}

	private fun retainedBackendsOrNull(): List<LocationSourceBackendController>? = runCatchingNonCancellation {
		buildList {
			if (fusedBackend.hasRetainedRegistration) add(fusedBackend)
			if (frameworkBackend.hasRetainedRegistration) add(frameworkBackend)
		}
	}.getOrNull()

	private fun retainRetirementOwnership() {
		synchronized(callbackLock) {
			acceptingCallbacks = false
			callbackToken = null
			callbackContext = null
			queue = null
		}
		actor = null
		retainedRetirementAck = null
		retainedRetirementActor = null
		providerRetirementCompletedWhileActorRetained = false
		currentSink = null
	}

	private fun retainRetirementUntilActorSettles(
		retiredActor: Job,
		providerRetirementComplete: Boolean,
	) {
		synchronized(callbackLock) {
			acceptingCallbacks = false
			callbackToken = null
			callbackContext = null
			queue = null
		}
		actor = retiredActor
		retainedRetirementActor = retiredActor
		providerRetirementCompletedWhileActorRetained = providerRetirementComplete
		currentSink = null
	}

	private fun onLocationBatch(callbackGeneration: LocationCallbackToken, locations: List<Location>) {
		if (locations.isEmpty() || !hasCurrentLocationPermission()) return
		val receivedElapsed = SystemClock.elapsedRealtimeNanos()
		val receivedWall = System.currentTimeMillis()
		synchronized(callbackLock) {
			val context = callbackContext
			if (!acceptingCallbacks || callbackToken !== callbackGeneration || context == null) return
			val callbackSequence = ++callbackEntrySequence
			val outcome = queue?.offer(
				RawLocationBatch(
					locations.map(::Location),
					receivedElapsed,
					receivedWall,
					callbackSequence,
					context,
				),
			) ?: LocationLaneOffer.CLOSED
			if (outcome != LocationLaneOffer.ACCEPTED) {
				metrics.recordFailure(
					callbackSequence,
					classification = if (outcome == LocationLaneOffer.CAPACITY_EXHAUSTED) {
						RuntimeGapClassification.CALLBACK_BUFFER_OVERFLOW
					} else {
						RuntimeGapClassification.ADMISSION_FAILED
					},
				)
				callbackOfferFailureCoverage.record(callbackSequence..callbackSequence)
			}
		}
	}

	private suspend fun consume(
		batches: LocationCallbackLane,
	) {
		batches.consume { batch ->
			try {
				admitLocationBatch(batch)
			} catch (cancelled: CancellationException) {
				throw cancelled
			} catch (fatal: Error) {
				metrics.recordFailure(batch.callbackSequence)
				throw fatal
			} catch (_: Exception) {
				metrics.recordFailure(batch.callbackSequence)
			} finally {
				processedCallbackSequence.value = maxOf(
					processedCallbackSequence.value,
					batch.callbackSequence,
				)
			}
		}
	}

	// Keep one bounded admission state machine so gap accounting remains exactly once per callback.
	@Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
	private suspend fun admitLocationBatch(
		batch: RawLocationBatch,
	) {
		var gapRecorded = false
		fun recordGapOnce() {
			if (!gapRecorded) {
				metrics.recordFailure(batch.callbackSequence)
				gapRecorded = true
			}
		}
		try {
			var retryIndex = 0
			while (true) {
				if (!hasCurrentLocationPermission()) {
					recordGapOnce()
					return
				}
				// Quiesce may install a cutoff while this batch is in a bounded retry delay. Rebuild
				// from the immutable raw batch before every sink call so no later retry can cross it.
				val partition = batch.partitionAgainst(cutoffElapsedNanos)
				if (partition.poisonCount > 0) recordGapOnce()
				if (partition.eligible.isEmpty()) return
				val delivery = locationDeliveryCandidate(
					locations = partition.eligible.map(QualifiedLocationFix::location),
					observedTimes = partition.eligible.map(QualifiedLocationFix::observedElapsedRealtimeNanos),
					receivedElapsedNanos = batch.receivedElapsedNanos,
					receivedWallTimeMs = batch.receivedWallTimeMs,
					attribution = batch.context.attribution,
					approximate = batch.context.approximate,
				)
				when (val handoff = batch.context.sink.admit(delivery)) {
					is SourceDeliveryAdmissionHandoff.Durable -> {
						val lastOrdinal = handoff.admissionOrdinals.maxOrNull()
						if (lastOrdinal != null) recordDurableAdmission(batch.callbackSequence, lastOrdinal)
						else recordGapOnce()
						return
					}
					is SourceDeliveryAdmissionHandoff.Duplicate -> {
						val lastOrdinal = handoff.existingAdmissionOrdinals.maxOrNull()
						if (lastOrdinal != null) recordDurableAdmission(batch.callbackSequence, lastOrdinal)
						else recordGapOnce()
						return
					}
					is SourceDeliveryAdmissionHandoff.SessionCutoff -> {
						// Location does not reinterpret an already-composed atomic delivery here.
						recordGapOnce()
						return
					}
					is SourceDeliveryAdmissionHandoff.TerminalFailure -> {
						if (handoff.code.recordsLocationAdmissionGap()) recordGapOnce()
						return
					}
					is SourceDeliveryAdmissionHandoff.RetryableFailure -> {
						val retryDelay = locationAdmissionRetryDelayMs(retryIndex)
						if (retryDelay == null) {
							recordGapOnce()
							return
						}
						retryIndex++
						// This is bounded in-process reconciliation, never a wake-reliable schedule.
						delay(retryDelay)
					}
				}
			}
		} catch (cancelled: CancellationException) {
			recordGapOnce()
			throw cancelled
		} catch (_: Exception) {
			recordGapOnce()
		}
	}

	private fun recordDurableAdmission(callbackSequence: Long, admissionOrdinal: Long) {
		// A replay can resolve a later callback with an older WAL row. Advance callback resolution
		// without allowing that duplicate ordinal to regress the physical run's stop boundary.
		val ordinalHighWater = lastAdmissionOrdinalHighWater
			?.coerceAtLeast(admissionOrdinal)
			?: admissionOrdinal
		lastAdmissionOrdinalHighWater = ordinalHighWater
		metrics.recordDurable(callbackSequence, ordinalHighWater)
	}

	private fun hasCurrentLocationPermission(): Boolean = permissionGate.allowsCurrentCallback()

	private fun currentCapabilities(backend: LocationSourceBackendController? = null): SourceCapabilities {
		val device = deviceStateProvider.snapshot()
		val permission = device.coarsePermission || device.finePermission
		return SourceCapabilities(
			available = device.locationFeatureAvailable && permission,
			batchingSupported = backend?.batchingSupported ?: device.fusedProviderAvailable,
			flushSupported = backend?.flushSupported ?: device.fusedProviderAvailable,
			maximumBatchSize = null,
			minimumDelayMs = null,
			degradedReasons = if (permission) emptySet() else setOf(SourceDegradedReason.PERMISSION_MISSING),
		)
	}

	private fun clearActiveState() {
		synchronized(callbackLock) {
			acceptingCallbacks = false
			registration = null
			callbackToken = null
			callbackContext = null
			queue = null
		}
		actor = null
		activeBackend = null
		pendingRetirementToken = null
		retainedRetirementAck = null
		retainedRetirementActor = null
		providerRetirementCompletedWhileActorRetained = false
		cutoffElapsedNanos = null
		currentPlan = null
		currentSink = null
		ownerClaim = null
	}

	private fun unavailableAck(cutoff: SessionCutoff?): SourceStopAck {
		val admission = metrics.snapshot()
		return SourceStopAck(
			source,
			SourceInstanceId("unavailable-location"),
			0L,
			currentPlan?.revision,
			callbackEntrySequence,
			admission.lastDurablyAdmittedSequence,
			admission.lastAdmissionOrdinal,
			admission.failedAdmissionCount,
			admission.unresolvedSequenceStart,
			admission.unresolvedSequenceEndInclusive,
			RegistrationRemovalOutcome.NOT_REGISTERED,
			ProviderFlushOutcome.NOT_REQUESTED,
			ProviderCoverage.PROVIDER_COMPLETENESS_UNOBSERVABLE,
			true,
			if (cutoff == null) SourceStopStatus.COMPLETE else SourceStopStatus.PROVIDER_FAILED,
		).withSessionMembership(ownerClaim)
	}

	private companion object {
		const val PROVIDER_OPERATION_TIMEOUT_MS = 2_000L
		const val DEFAULT_DRAIN_TIMEOUT_MS = 3_000L
		const val RETIRE_REASON_RECONFIGURE = "RECONFIGURE"
		const val RETIRE_REASON_SESSION_STOP = "ORDERLY_SESSION_STOP"
		const val RETIRE_REASON_RUNTIME_CLOSE = "RUNTIME_CLOSE"
		const val RETIRE_REASON_RETRY = "RETIREMENT_RETRY"
		const val RETIRE_REASON_ACTOR_FAILURE = "CALLBACK_ACTOR_FAILURE"
	}
}

/** Compact coverage for callback offers rejected before the actor can process their sequence. */
private class LocationCallbackFailureCoverage {
	private val lock = Any()
	private val ranges = mutableListOf<LongRange>()

	fun record(range: LongRange) {
		require(range.first >= 0L && range.first <= range.last)
		synchronized(lock) {
			val merged = mutableListOf<LongRange>()
			(ranges + listOf(range)).sortedBy { it.first }.forEach { candidate ->
				val previous = merged.lastOrNull()
				if (previous == null || candidate.first > previous.last + 1L) {
					merged.add(candidate)
				} else {
					merged[merged.lastIndex] = previous.first..maxOf(previous.last, candidate.last)
				}
			}
			ranges.clear()
			ranges.addAll(merged)
		}
	}

	fun unaccountedRanges(processedSequence: Long, barrierSequence: Long): List<LongRange> {
		require(processedSequence >= 0L)
		require(barrierSequence >= 0L)
		var cursor = processedSequence + 1L
		if (cursor > barrierSequence) return emptyList()
		val accounted = synchronized(lock) { ranges.toList() }
		return buildList {
			accounted.forEach { range ->
				if (range.last < cursor) return@forEach
				if (range.first > barrierSequence) return@forEach
				if (range.first > cursor) add(cursor..minOf(barrierSequence, range.first - 1L))
				cursor = maxOf(cursor, range.last + 1L)
			}
			if (cursor <= barrierSequence) add(cursor..barrierSequence)
		}
	}
}

private enum class LocationRetirementDurability { NOT_DURABLE, PENDING, COMPLETE }

private data class LocationRetirementAttempt(
	val durability: LocationRetirementDurability,
	val removalOutcome: RegistrationRemovalOutcome,
)

private fun SourceRegistrationRetirementToken.belongsTo(
	registration: SourceRegistration,
): Boolean = source.stableCode == registration.state.sourceKind &&
	sourceInstanceId.value == registration.state.sourceInstanceId &&
	registrationGeneration == registration.state.registrationGeneration

private fun SourceRegistrationRetirementToken.sameRetirementAs(
	other: SourceRegistrationRetirementToken,
): Boolean = source == other.source &&
	sourceInstanceId == other.sourceInstanceId &&
	registrationGeneration == other.registrationGeneration &&
	processIncarnationId == other.processIncarnationId &&
	retiredAtMs == other.retiredAtMs &&
	retiredElapsedRealtimeNanos == other.retiredElapsedRealtimeNanos

internal data class RawLocationBatch(
	val locations: List<Location>,
	val receivedElapsedNanos: Long,
	val receivedWallTimeMs: Long,
	val callbackSequence: Long,
	val context: LocationCallbackContext,
)

internal class LocationCallbackToken

internal data class LocationCallbackContext(
	val attribution: LocationDeliveryAttribution,
	val approximate: Boolean,
	val sink: SourceEventSink,
	val minimumObservedElapsedRealtimeNanos: Long,
	val maximumEvidenceAgeNanos: Long = Long.MAX_VALUE,
) {
	init {
		require(minimumObservedElapsedRealtimeNanos >= 0L)
	}
}

internal enum class LocationLaneOffer { ACCEPTED, CAPACITY_EXHAUSTED, CLOSED }

/** Fixed-memory Location FIFO. Capacity loss is surfaced as an explicit unresolved callback gap. */
internal class LocationCallbackLane(
	capacity: Int = LOCATION_CALLBACK_BUFFER_CAPACITY,
) {
	private val channel = Channel<RawLocationBatch>(capacity)

	fun offer(batch: RawLocationBatch): LocationLaneOffer {
		val result = channel.trySend(batch)
		return when {
			result.isSuccess -> LocationLaneOffer.ACCEPTED
			result.isClosed -> LocationLaneOffer.CLOSED
			else -> LocationLaneOffer.CAPACITY_EXHAUSTED
		}
	}

	fun close() = channel.close()

	suspend fun consume(action: suspend (RawLocationBatch) -> Unit) {
		for (batch in channel) action(batch)
	}
}

internal data class LocationDeliveryAttribution(
	val sourceInstanceId: SourceInstanceId,
	val registrationGeneration: Long,
	val physicalConfigurationFingerprint: String,
	val authorizationRevision: Long,
	val purposeEligibilityMask: Long,
	val eligibilityFingerprint: String,
	val configRevision: Long?,
	val clockDomainId: String,
	val collectedDataEpoch: Long,
)

internal data class QualifiedLocationFix(
	val location: Location,
	val observedElapsedRealtimeNanos: Long,
)

internal data class LocationBatchPartition(
	val eligible: List<QualifiedLocationFix>,
	val poisonCount: Int,
	val postCutoffCount: Int,
	val staleCount: Int,
	val preBoundaryCount: Int,
)

private fun RawLocationBatch.partitionAgainst(
	cutoffElapsedRealtimeNanos: Long?,
): LocationBatchPartition = partitionLocationBatch(
	locations = locations,
	receivedElapsedRealtimeNanos = receivedElapsedNanos,
	cutoffElapsedRealtimeNanos = cutoffElapsedRealtimeNanos,
	minimumObservedElapsedRealtimeNanos = context.minimumObservedElapsedRealtimeNanos,
	maximumEvidenceAgeNanos = context.maximumEvidenceAgeNanos,
)

/**
 * Separates source poison from policy-excluded post-cutoff evidence without sacrificing valid
 * fixes from the same callback. Eligible units retain canonical provider-observation ordering.
 */
internal fun partitionLocationBatch(
	locations: List<Location>,
	receivedElapsedRealtimeNanos: Long,
	cutoffElapsedRealtimeNanos: Long?,
	minimumObservedElapsedRealtimeNanos: Long = 0L,
	maximumEvidenceAgeNanos: Long = Long.MAX_VALUE,
): LocationBatchPartition {
	require(minimumObservedElapsedRealtimeNanos >= 0L)
	require(maximumEvidenceAgeNanos >= 0L)
	val eligible = mutableListOf<QualifiedLocationFix>()
	var poisonCount = 0
	var postCutoffCount = 0
	var staleCount = 0
	var preBoundaryCount = 0
	normalizeLocationBatch(locations).forEach { location ->
		val observed = location.qualifiedObservedElapsedRealtimeNanos(receivedElapsedRealtimeNanos)
		if (observed == null || !location.isValidLocationEvidence()) {
			poisonCount++
		} else if (observed < minimumObservedElapsedRealtimeNanos) {
			preBoundaryCount++
		} else if (cutoffElapsedRealtimeNanos?.let { observed > it } == true) {
			postCutoffCount++
		} else if (receivedElapsedRealtimeNanos - observed > maximumEvidenceAgeNanos) {
			staleCount++
		} else {
			eligible += QualifiedLocationFix(location, observed)
		}
	}
	return LocationBatchPartition(eligible, poisonCount, postCutoffCount, staleCount, preBoundaryCount)
}

internal fun LocationDeviceState.hasLocationPermission(): Boolean = coarsePermission || finePermission

internal class LocationPermissionGate(
	private val stateProvider: LocationDeviceStateProvider,
) {
	/** Deliberately snapshots on every callback/admission attempt so revocation is never cached. */
	fun allowsCurrentCallback(): Boolean = runCatchingNonCancellation {
		stateProvider.snapshot().hasLocationPermission()
	}.getOrDefault(false)
}

private fun SourceRegistration.toLocationDeliveryAttribution() = LocationDeliveryAttribution(
	sourceInstanceId = SourceInstanceId(state.sourceInstanceId),
	registrationGeneration = state.registrationGeneration,
	physicalConfigurationFingerprint = physicalConfigurationFingerprint,
	authorizationRevision = authorization.authorizationRevision,
	purposeEligibilityMask = purposeEligibilityMask,
	eligibilityFingerprint = eligibilityFingerprint,
	configRevision = state.appliedRevision,
	clockDomainId = state.clockDomainId,
	collectedDataEpoch = state.collectedDataEpoch,
)

private fun SourceRegistration.toLocationCallbackContext(
	plan: LocationPlan,
	sink: SourceEventSink,
	minimumObservedElapsedRealtimeNanos: Long,
) = LocationCallbackContext(
	attribution = toLocationDeliveryAttribution(),
	approximate = !plan.preciseLocationAvailable,
	sink = sink,
	minimumObservedElapsedRealtimeNanos = minimumObservedElapsedRealtimeNanos,
	maximumEvidenceAgeNanos = plan.maximumEvidenceAgeNanos(),
)

internal fun LocationPlan.maximumEvidenceAgeNanos(): Long {
	val maximumAgeMs = maxOf(requestedIntervalMs, maximumBatchDelayMs).coerceAtLeast(1L)
	return if (maximumAgeMs > Long.MAX_VALUE / LOCATION_NANOS_PER_MILLISECOND) {
		Long.MAX_VALUE
	} else {
		maximumAgeMs * LOCATION_NANOS_PER_MILLISECOND
	}
}

internal fun locationAdmissionRetryDelayMs(retryIndex: Int): Long? {
	require(retryIndex >= 0)
	return LOCATION_ADMISSION_RETRY_DELAYS_MS.getOrNull(retryIndex)
}

private fun SourceAdmissionFailureCode.recordsLocationAdmissionGap(): Boolean = when (this) {
	SourceAdmissionFailureCode.STALE_REGISTRATION_GENERATION,
	SourceAdmissionFailureCode.SOURCE_POLICY_STALE,
	SourceAdmissionFailureCode.STALE_COLLECTED_DATA_EPOCH,
	SourceAdmissionFailureCode.BEFORE_RETENTION_BOUNDARY -> false
	SourceAdmissionFailureCode.INVALID_EVIDENCE,
	SourceAdmissionFailureCode.CODEC_UNSUPPORTED,
	SourceAdmissionFailureCode.STORAGE_UNAVAILABLE,
	SourceAdmissionFailureCode.STORAGE_FULL,
	SourceAdmissionFailureCode.ATOMIC_CHECKPOINT_UNSUPPORTED,
	SourceAdmissionFailureCode.UNKNOWN -> true
}

internal fun locationDeliveryCandidate(
	locations: List<Location>,
	observedTimes: List<Long>,
	receivedElapsedNanos: Long,
	receivedWallTimeMs: Long,
	attribution: LocationDeliveryAttribution,
	approximate: Boolean,
): SourceDeliveryCandidate {
	require(locations.isNotEmpty())
	require(observedTimes.size == locations.size)
	require(locations.indices.all { index ->
		observedTimes[index] == locations[index].elapsedRealtimeNanos &&
			observedTimes[index] > 0L && observedTimes[index] <= receivedElapsedNanos &&
			locations[index].isValidLocationEvidence()
	}) { "Every location delivery unit must carry qualified provider evidence" }
	return SourceDeliveryCandidate(
		identity = sourceDeliveryIdentity(canonicalLocationDeliveryBytes(locations)),
		units = locations.mapIndexed { index, location ->
			val observedNanos = observedTimes[index]
			val delayNanos = receivedElapsedNanos - observedNanos
			val acquiredAtMs = location.time.takeIf { it > 0L }
				?: (receivedWallTimeMs - delayNanos / LOCATION_NANOS_PER_MILLISECOND).coerceAtLeast(0L)
			SourceDeliveryUnit(
				unitIndex = index,
				evidence = SourceEvidenceCandidate(
					providerDedupKey = null,
					logicalTrackingId = null,
					serviceRunId = null,
					source = SourceKind.LOCATION,
					sourceInstanceId = attribution.sourceInstanceId,
					registrationGeneration = attribution.registrationGeneration,
					physicalConfigurationFingerprint = attribution.physicalConfigurationFingerprint,
					authorizationRevision = attribution.authorizationRevision,
					registrationPurposeEligibilityMask = attribution.purposeEligibilityMask,
					registrationEligibilityFingerprint = attribution.eligibilityFingerprint,
					// The delivery ingress allocates the contiguous range inside its WAL transaction.
					sourceSequence = 0L,
					configRevision = attribution.configRevision,
					planAttribution = PlanAttribution.CAPTURED_REGISTRATION,
					clockDomainId = attribution.clockDomainId,
					observedElapsedRealtimeNanos = observedNanos,
					receivedElapsedRealtimeNanos = receivedElapsedNanos,
					wallTimeMs = acquiredAtMs,
					wallTimeUncertaintyMs = if (location.time > 0L) 0L else 1L,
					capturedCollectedDataEpoch = attribution.collectedDataEpoch,
					acquiredAtMs = acquiredAtMs,
					quality = SourceQuality(
						flags = buildSet {
							if (approximate) add(SourceQualityFlag.APPROXIMATE)
							if (locations.size > 1 || delayNanos >= LOCATION_BATCHED_AFTER_NANOS) {
								add(SourceQualityFlag.BATCHED)
							}
						},
					),
					payloadVersion = 1,
					payload = LocationFixPayload(
						latitudeDegrees = location.latitude,
						longitudeDegrees = location.longitude,
						horizontalAccuracyMeters = location.accuracy,
						altitudeMeters = location.altitude.takeIf { location.hasAltitude() },
						verticalAccuracyMeters = location.verticalAccuracyMeters
							.takeIf { location.hasVerticalAccuracy() },
						speedMetersPerSecond = location.speed.takeIf { location.hasSpeed() },
						bearingDegrees = location.bearing.takeIf { location.hasBearing() },
						provider = location.provider ?: "unknown",
					),
				),
			)
		},
	)
}

private fun canonicalLocationDeliveryBytes(locations: List<Location>): ByteArray =
	ByteArrayOutputStream().use { bytes ->
		DataOutputStream(bytes).use { output ->
			output.writeInt(LOCATION_DELIVERY_MAGIC)
			output.writeInt(LOCATION_DELIVERY_VERSION)
			output.writeInt(locations.size)
			locations.forEach { location ->
				val providerBytes = (location.provider ?: "unknown").encodeToByteArray()
				output.writeInt(providerBytes.size)
				output.write(providerBytes)
				output.writeLong(location.elapsedRealtimeNanos)
				output.writeLong(location.time)
				output.writeLong(java.lang.Double.doubleToRawLongBits(location.latitude))
				output.writeLong(java.lang.Double.doubleToRawLongBits(location.longitude))
				output.writeInt(java.lang.Float.floatToRawIntBits(location.accuracy))
				output.writeOptionalDouble(location.hasAltitude(), location.altitude)
				output.writeOptionalFloat(location.hasVerticalAccuracy(), location.verticalAccuracyMeters)
				output.writeOptionalFloat(location.hasSpeed(), location.speed)
				output.writeOptionalFloat(location.hasBearing(), location.bearing)
			}
		}
		bytes.toByteArray()
	}

private fun DataOutputStream.writeOptionalDouble(present: Boolean, value: Double) {
	writeBoolean(present)
	if (present) writeLong(java.lang.Double.doubleToRawLongBits(value))
}

private fun DataOutputStream.writeOptionalFloat(present: Boolean, value: Float) {
	writeBoolean(present)
	if (present) writeInt(java.lang.Float.floatToRawIntBits(value))
}

private const val LOCATION_DELIVERY_MAGIC = 0x4c4f4342 // LOCB
private const val LOCATION_DELIVERY_VERSION = 1
private const val LOCATION_NANOS_PER_MILLISECOND = 1_000_000L
private const val LOCATION_BATCHED_AFTER_NANOS = 5_000L * LOCATION_NANOS_PER_MILLISECOND
private const val LOCATION_CALLBACK_BUFFER_CAPACITY = 64
private val LOCATION_ADMISSION_RETRY_DELAYS_MS = longArrayOf(25L, 100L, 500L, 2_000L)
