package com.adsamcik.tracker.tracker.source.runtime

import android.os.SystemClock
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.di.ApplicationScope
import com.adsamcik.tracker.tracker.source.runCatchingNonCancellation
import com.adsamcik.tracker.tracker.source.model.CellMode
import com.adsamcik.tracker.tracker.source.model.CellObservationEvidence
import com.adsamcik.tracker.tracker.source.model.CellPlan
import com.adsamcik.tracker.tracker.source.model.physicalConfigurationFingerprint
import com.adsamcik.tracker.tracker.source.model.CellRefreshOutcome
import com.adsamcik.tracker.tracker.source.model.CellSnapshotPayload
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.SourceApplyStatus
import com.adsamcik.tracker.tracker.source.model.SourceDegradedReason
import com.adsamcik.tracker.tracker.source.model.SourceDeliveryCandidate
import com.adsamcik.tracker.tracker.source.model.SourceDeliveryIdentity
import com.adsamcik.tracker.tracker.source.model.SourceDeliveryUnit
import com.adsamcik.tracker.tracker.source.model.SourceEvidenceCandidate
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourceQuality
import com.adsamcik.tracker.tracker.source.model.sourceDeliveryIdentity
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CellSourceRuntime @Inject internal constructor(
	@ApplicationScope private val applicationScope: CoroutineScope,
	private val registrations: SourceRegistrationRepository,
	private val backend: AndroidCellSourceBackend,
	private val deviceStateProvider: AndroidConnectivityDeviceStateProvider,
	private val wakeups: CoalescingSourceWakeupScheduler,
) : ClaimedSourceRuntime<CellPlan> {
	override val source = SourceKind.CELL
	private val _capabilities = MutableStateFlow(capabilitiesNow())
	override val capabilities: StateFlow<SourceCapabilities> = _capabilities
	private val lifecycleMutex = Mutex()
	private val callbackLock = Any()
	private var registration: SourceRegistration? = null
	private var callbackToken: CellCallbackToken? = null
	private var currentPlan: CellPlan? = null
	private var currentSink: SourceEventSink? = null
	private var queue: CellCallbackLane<CellRuntimeInput>? = null
	@Volatile
	private var actor: Job? = null
	private var accepting = false
	private var callbackSequence = 0L
	@Volatile
	private var cutoffElapsedNanos: Long? = null
	private var metrics = RuntimeAdmissionMetrics()
	private var lastAdmissionOrdinalHighWater: Long? = null
	private var retirementIntent: CellProviderRetirementIntent? = null
	private var automaticFailureAck: SourceStopAck? = null
	private var ownerClaim: SourceRuntimeClaim? = null
	private val processedCallbackSequence = MutableStateFlow(0L)
	private var callbackOfferFailureCoverage = CellCallbackFailureCoverage()
	private val prerequisiteGate = CellPrerequisiteGate(deviceStateProvider::cell)

	override suspend fun start(plan: CellPlan, sink: SourceEventSink): SourceStartResult =
		startWithClaim(null, plan, sink)

	override suspend fun start(
		claim: SourceRuntimeClaim,
		plan: CellPlan,
		sink: SourceEventSink,
	): SourceStartResult {
		require(claim.source == source)
		return startWithClaim(claim, plan, sink)
	}

	private suspend fun startWithClaim(
		claim: SourceRuntimeClaim?,
		plan: CellPlan,
		sink: SourceEventSink,
	): SourceStartResult = lifecycleMutex.withLock {
		require(currentPlan == null) { "Cell source is already started" }
		startLocked(claim, plan, sink)
	}

	override suspend fun reconfigure(plan: CellPlan, sink: SourceEventSink): SourceApplyResult =
		reconfigureWithClaim(null, plan, sink)

	override suspend fun reconfigure(
		claim: SourceRuntimeClaim,
		plan: CellPlan,
		sink: SourceEventSink,
	): SourceApplyResult {
		require(claim.source == source)
		return reconfigureWithClaim(claim, plan, sink)
	}

	private suspend fun reconfigureWithClaim(
		claim: SourceRuntimeClaim?,
		plan: CellPlan,
		sink: SourceEventSink,
	): SourceApplyResult = lifecycleMutex.withLock {
		refreshCompatibleLocked(claim, plan, sink)?.let { refreshed ->
			return@withLock refreshed
		}
		var predecessorStopAck: SourceStopAck? = null
		if (currentPlan != null) {
			val previous = shutdownLocked(null)
			predecessorStopAck = previous
			if (!previous.appDrainComplete ||
				previous.registrationRemovalOutcome != RegistrationRemovalOutcome.REMOVED
			) {
				return@withLock SourceApplyResult.Failed(
					appliedState(source, plan.revision, null, SourceApplyStatus.FAILED,
						SystemClock.elapsedRealtimeNanos()),
					retryable = true,
					stopAck = previous,
				)
			}
		}
		if (!plan.enabled) return@withLock SourceApplyResult.Applied(
			appliedState(source, plan.revision, null, SourceApplyStatus.APPLIED, SystemClock.elapsedRealtimeNanos()),
			stopAck = predecessorStopAck,
		)
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
		automaticFailureAck ?: shutdownLocked(cutoff)
	}

	override suspend fun close() = lifecycleMutex.withLock {
		if (currentPlan != null) shutdownLocked(null)
	}

	override suspend fun shutdownIfOwned(
		claim: SourceRuntimeClaim,
		cutoff: SessionCutoff,
	): OwnedSourceShutdown {
		require(claim.source == source)
		return lifecycleMutex.withLock {
			if (ownerClaim != claim) return@withLock OwnedSourceShutdown.NotOwned
			(automaticFailureAck ?: shutdownLocked(cutoff)).toOwnedShutdown()
		}
	}

	/**
	 * Refreshes policy and immutable authorization attribution without replacing the compatible
	 * Telephony registration. Inputs already in the lane retain the context captured at callback
	 * entry; only later callbacks observe this revision.
	 */
	private suspend fun refreshCompatibleLocked(
		claim: SourceRuntimeClaim?,
		plan: CellPlan,
		sink: SourceEventSink,
	): SourceApplyResult? {
		val activePlan = currentPlan ?: return null
		val activeRegistration = registration ?: return null
		val activeCallbackToken = callbackToken ?: return null
		if (!cellPlansSharePhysicalRegistration(activePlan, plan)) return null
		val application = CellPrerequisiteEvaluator.evaluate(plan, deviceStateProvider.cell())
		_capabilities.value = capabilitiesNow()
		if (application.status == SourceApplyStatus.BLOCKED) return null
		val refreshBoundary = synchronized(callbackLock) {
			if (!accepting || callbackToken !== activeCallbackToken || registration !== activeRegistration) {
				return null
			}
			// A provider callback either captures the old immutable context before this boundary or
			// is rejected until the refreshed durable authorization and callback context are installed.
			accepting = false
			SystemClock.elapsedRealtimeNanos()
		}
		val refreshed = runCatchingNonCancellation {
			registrations.refreshActiveAuthorization(
				source,
				activeRegistration,
				plan.revision,
				plan.physicalConfigurationFingerprint(),
				System.currentTimeMillis(),
				refreshBoundary,
			)
		}.getOrElse {
			return SourceApplyResult.Failed(
				appliedState(source, plan.revision, activeRegistration, SourceApplyStatus.FAILED,
					SystemClock.elapsedRealtimeNanos()),
				retryable = true,
			)
		} ?: return null
		if (refreshed.state.sourceInstanceId != activeRegistration.state.sourceInstanceId ||
			refreshed.state.registrationGeneration != activeRegistration.state.registrationGeneration ||
			refreshed.physicalConfigurationFingerprint != activeRegistration.physicalConfigurationFingerprint ||
			refreshed.requiresProviderAcceptance
		) return null
		val retained = synchronized(callbackLock) {
			if (accepting || callbackToken !== activeCallbackToken || registration !== activeRegistration) {
				false
			} else {
				registration = refreshed
				currentPlan = plan
				currentSink = sink
				val notified = queue?.offer(CellRuntimeInput.PlanChanged) == true
				if (notified) accepting = true
				notified
			}
		}
		if (!retained) return null
		// The compatible refresh is a new durable lifecycle attempt even though it retains the same
		// Telephony handle. Transfer authority only after its callback context commits.
		ownerClaim = claim
		val state = appliedState(
			source, plan.revision, refreshed, application.status, SystemClock.elapsedRealtimeNanos(),
		).copy(degradedReasons = application.reasons)
		return if (application.status == SourceApplyStatus.DEGRADED) {
			SourceApplyResult.Degraded(state)
		} else SourceApplyResult.Applied(state)
	}

	private suspend fun startLocked(
		claim: SourceRuntimeClaim?,
		plan: CellPlan,
		sink: SourceEventSink,
	): SourceStartResult {
		if (!reconcilePendingProviderRetirements()) {
			return SourceStartResult.Failed(
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
		automaticFailureAck = null
		if (!plan.enabled) return SourceStartResult.Started(
			appliedState(source, plan.revision, null, SourceApplyStatus.APPLIED, SystemClock.elapsedRealtimeNanos()),
		)
		val application = CellPrerequisiteEvaluator.evaluate(plan, deviceStateProvider.cell())
		_capabilities.value = capabilitiesNow()
		if (application.status == SourceApplyStatus.BLOCKED) return SourceStartResult.Blocked(
			appliedState(source, plan.revision, null, SourceApplyStatus.BLOCKED, SystemClock.elapsedRealtimeNanos())
				.copy(degradedReasons = application.reasons),
		)
		val nextRegistration = runCatchingNonCancellation {
			registrations.begin(source, plan.revision, plan.physicalConfigurationFingerprint(), System.currentTimeMillis())
		}.getOrElse {
			return SourceStartResult.Failed(
				appliedState(source, plan.revision, null, SourceApplyStatus.FAILED, SystemClock.elapsedRealtimeNanos()), true,
			)
		}
		val nextQueue = CellCallbackLane<CellRuntimeInput>()
		val nextCallbackToken = CellCallbackToken()
		synchronized(callbackLock) {
			registration = nextRegistration
			callbackToken = nextCallbackToken
			currentPlan = plan
			currentSink = sink
			queue = nextQueue
			accepting = false
		}
		callbackSequence = 0L
		processedCallbackSequence.value = 0L
		callbackOfferFailureCoverage = CellCallbackFailureCoverage()
		cutoffElapsedNanos = null
		metrics = RuntimeAdmissionMetrics()
		lastAdmissionOrdinalHighWater = null
		// A provider may throw after installing its callback. Bind the explicit action claim before
		// publication so a partially failed start remains exactly compensatable.
		ownerClaim = claim
		val providerStarted = try {
			backend.start(plan.subscriptionIds) { snapshot ->
				onBackendSnapshot(nextCallbackToken, snapshot, CellRefreshOutcome.CALLBACK)
			}
		} catch (cancelled: CancellationException) {
			cleanupFailedStart(nextRegistration, nextQueue, "PROVIDER_REGISTRATION_CANCELLED")
			throw cancelled
		} catch (fatal: Error) {
			cleanupFailedStart(nextRegistration, nextQueue, "PROVIDER_REGISTRATION_FATAL")
			throw fatal
		} catch (_: Exception) {
			false
		}
		if (!providerStarted) {
			cleanupFailedStart(nextRegistration, nextQueue, "PROVIDER_REGISTRATION_FAILED")
			return SourceStartResult.Failed(
				appliedState(source, plan.revision, nextRegistration, SourceApplyStatus.FAILED, SystemClock.elapsedRealtimeNanos()), true,
			)
		}
		val accepted = try {
			if (nextRegistration.requiresProviderAcceptance) {
				// A null result means there was no previously active generation to retire. Stale
				// reservations fail by exception inside the transactional repository operation.
				registrations.markAccepted(nextRegistration, System.currentTimeMillis())
			}
			true
		} catch (cancelled: CancellationException) {
			cleanupFailedStart(nextRegistration, nextQueue, "REGISTRATION_ACCEPTANCE_CANCELLED")
			throw cancelled
		} catch (fatal: Error) {
			cleanupFailedStart(nextRegistration, nextQueue, "REGISTRATION_ACCEPTANCE_FATAL")
			throw fatal
		} catch (_: Exception) {
			false
		}
		if (!accepted) {
			cleanupFailedStart(nextRegistration, nextQueue, "REGISTRATION_ACCEPTANCE_STALE")
			return SourceStartResult.Failed(
				appliedState(source, plan.revision, nextRegistration, SourceApplyStatus.FAILED, SystemClock.elapsedRealtimeNanos()), true,
			)
		}
		val nextActor = applicationScope.launch(start = CoroutineStart.LAZY) {
			consume(nextQueue, nextCallbackToken)
		}
		actor = nextActor
		nextActor.invokeOnCompletion { failure ->
			if (failure != null) {
				containFailedActor(nextActor, nextQueue)
			}
		}
		nextActor.start()
		synchronized(callbackLock) {
			check(callbackToken === nextCallbackToken && registration === nextRegistration)
			accepting = true
			check(nextQueue.offer(CellRuntimeInput.Started))
		}
		val state = appliedState(
			source, plan.revision, nextRegistration, application.status, SystemClock.elapsedRealtimeNanos(),
		).copy(degradedReasons = application.reasons)
		return if (application.status == SourceApplyStatus.DEGRADED) {
			SourceStartResult.Degraded(state)
		} else SourceStartResult.Started(state)
	}

	private fun containFailedActor(
		failedActor: Job,
		failedLane: CellCallbackLane<CellRuntimeInput>,
	) {
		val cleanupRequired = synchronized(callbackLock) {
			if (actor !== failedActor) {
				false
			} else {
				accepting = false
				failedLane.close()
				true
			}
		}
		if (!cleanupRequired) return
		applicationScope.launch {
			lifecycleMutex.withLock {
				if (actor === failedActor && currentPlan != null) {
					val acknowledgement = shutdownLocked(null)
					if (acknowledgement.registrationRemovalOutcome == RegistrationRemovalOutcome.REMOVED &&
						currentPlan == null
					) {
						automaticFailureAck = acknowledgement
					}
				}
			}
		}
	}

	private suspend fun cleanupFailedStart(
		failedRegistration: SourceRegistration,
		callbackLane: CellCallbackLane<CellRuntimeInput>,
		reason: String,
	) = withContext(NonCancellable) {
		val retirementBoundary = closeCallbackAdmission(callbackLane)
		val retirement = retireProviderRegistration(
			failedRegistration,
			reason,
			retirementBoundary,
		)
		settleFailedStartState(retirement)
	}

	private suspend fun shutdownLocked(cutoff: SessionCutoff?): SourceStopAck {
		val acknowledgement = withContext(NonCancellable) {
			settleShutdownLocked(cutoff)
		}
		currentCoroutineContext().ensureActive()
		return acknowledgement
	}

	private suspend fun settleShutdownLocked(cutoff: SessionCutoff?): SourceStopAck {
		val activeRegistration = registration ?: return unavailableAck(cutoff)
		synchronized(callbackLock) { cutoffElapsedNanos = cutoff?.elapsedRealtimeNanos }
		wakeups.cancel(REFRESH_WAKEUP_ID)
		wakeups.cancel(TIMEOUT_WAKEUP_ID)
		val barrier: Long
		val retirementBoundary: Long
		synchronized(callbackLock) {
			accepting = false
			barrier = callbackSequence
			retirementBoundary = SystemClock.elapsedRealtimeNanos()
		}
		val retirement = retireProviderRegistration(
			activeRegistration,
			reason = "ORDERLY_STOP",
			retiredElapsedRealtimeNanos = retirementBoundary,
		)
		val removed = if (retirement == CellProviderRetirement.COMPLETE) {
			RegistrationRemovalOutcome.REMOVED
		} else {
			RegistrationRemovalOutcome.FAILED
		}
		synchronized(callbackLock) { queue?.close() }
		val activeActor = actor
		val remainingMs = cutoff?.let {
			((it.deadlineElapsedRealtimeNanos - SystemClock.elapsedRealtimeNanos()) / NANOS_PER_MILLISECOND)
				.coerceAtLeast(1L)
		} ?: DEFAULT_DRAIN_TIMEOUT_MS
		val joinedBeforeDeadline = activeActor == null ||
			withTimeoutOrNull(remainingMs) { activeActor.join(); true } == true
		if (!joinedBeforeDeadline) {
			val timedOutActor = requireNotNull(activeActor)
			timedOutActor.cancel()
			withTimeoutOrNull(PROVIDER_OPERATION_TIMEOUT_MS) { timedOutActor.join() }
		}
		val unaccountedTail = callbackOfferFailureCoverage.unaccountedRanges(
			processedCallbackSequence.value,
			barrier,
		)
		unaccountedTail.forEach { unresolved ->
				metrics.recordFailure(
					unresolved.first,
					unresolved.last,
					RuntimeGapClassification.DRAIN_TIMED_OUT,
				)
		}
		val drainComplete = joinedBeforeDeadline && unaccountedTail.isEmpty()
		val admission = metrics.snapshot()
		val ack = SourceStopAck(
			source, SourceInstanceId(activeRegistration.state.sourceInstanceId),
			activeRegistration.state.registrationGeneration, currentPlan?.revision, barrier,
			admission.lastDurablyAdmittedSequence, admission.lastAdmissionOrdinal,
			admission.failedAdmissionCount, admission.unresolvedSequenceStart,
			admission.unresolvedSequenceEndInclusive, removed, ProviderFlushOutcome.NOT_SUPPORTED,
			ProviderCoverage.PROVIDER_COMPLETENESS_UNOBSERVABLE, drainComplete,
			when {
				!drainComplete -> SourceStopStatus.TIMED_OUT
				removed == RegistrationRemovalOutcome.FAILED -> SourceStopStatus.PROVIDER_FAILED
				else -> SourceStopStatus.COMPLETE
			},
		).withSessionMembership(ownerClaim)
		if (retirement == CellProviderRetirement.COMPLETE) {
			clearActiveState()
		} else {
			retainProviderForRetirementRetry()
		}
		return ack
	}

	private fun closeCallbackAdmission(callbackLane: CellCallbackLane<CellRuntimeInput>): Long =
		synchronized(callbackLock) {
			accepting = false
			callbackLane.close()
			SystemClock.elapsedRealtimeNanos()
		}

	private suspend fun retireProviderRegistration(
		activeRegistration: SourceRegistration,
		reason: String,
		retiredElapsedRealtimeNanos: Long,
	): CellProviderRetirement = withContext(NonCancellable) {
		val intent = synchronized(callbackLock) {
			retirementIntent?.also { pending ->
				check(pending.registration.samePhysicalRegistrationAs(activeRegistration)) {
					"A retained Cell provider cannot be retired under a replacement registration"
				}
			} ?: CellProviderRetirementIntent(
				registration = activeRegistration,
				reason = reason,
				retiredAtMs = System.currentTimeMillis(),
				retiredElapsedRealtimeNanos = retiredElapsedRealtimeNanos,
			).also { retirementIntent = it }
		}
		val token = intent.token ?: withTimeoutOrNull(PROVIDER_OPERATION_TIMEOUT_MS) {
			runCatchingNonCancellation {
				registrations.beginRetirement(
					intent.registration,
					intent.reason,
					intent.retiredAtMs,
					intent.retiredElapsedRealtimeNanos,
				)
			}.getOrNull()
		} ?: return@withContext CellProviderRetirement.NOT_DURABLE
		intent.token = token
		val removed = withTimeoutOrNull(PROVIDER_OPERATION_TIMEOUT_MS) {
			runCatchingNonCancellation { backend.stop() }.getOrDefault(false)
		} == true
		if (!removed) {
			return@withContext CellProviderRetirement.PENDING
		}
		val completed = withTimeoutOrNull(PROVIDER_OPERATION_TIMEOUT_MS) {
			runCatchingNonCancellation {
				registrations.completeRetirement(token)
			}.getOrDefault(false)
		} == true
		if (completed) CellProviderRetirement.COMPLETE else CellProviderRetirement.PENDING
	}

	private suspend fun reconcilePendingProviderRetirements(): Boolean {
		val reconciled = withContext(NonCancellable) {
			val pending = withTimeoutOrNull(PROVIDER_OPERATION_TIMEOUT_MS) {
				runCatchingNonCancellation {
					registrations.pendingRetirements(source)
				}.getOrNull()
			} ?: return@withContext false
			if (pending.isEmpty()) return@withContext !backend.hasRetainedRegistrations
			if (pending.size != 1) return@withContext false
			val token = pending.single()
			val localIntent = synchronized(callbackLock) { retirementIntent }
			if (localIntent != null && localIntent.token?.sameRetirementAs(token) != true) {
				return@withContext false
			}
			val removed = withTimeoutOrNull(PROVIDER_OPERATION_TIMEOUT_MS) {
				runCatchingNonCancellation { backend.stop() }.getOrDefault(false)
			} == true
			if (!removed) return@withContext false
			withTimeoutOrNull(PROVIDER_OPERATION_TIMEOUT_MS) {
				runCatchingNonCancellation {
					registrations.completeRetirement(token)
				}.getOrDefault(false)
			} == true
		}
		currentCoroutineContext().ensureActive()
		return reconciled
	}

	private fun settleFailedStartState(retirement: CellProviderRetirement) {
		if (retirement == CellProviderRetirement.COMPLETE) {
			clearActiveState()
		} else {
			retainProviderForRetirementRetry()
		}
	}

	private fun retainProviderForRetirementRetry() {
		synchronized(callbackLock) {
			accepting = false
			callbackToken = null
			currentSink = null
			queue = null
		}
		actor = null
	}

	private fun onBackendSnapshot(
		token: CellCallbackToken,
		snapshot: CellBackendSnapshot,
		outcome: CellRefreshOutcome,
	) {
		synchronized(callbackLock) {
			if (!accepting || callbackToken !== token) return
			val context = currentCallbackContextLocked() ?: return
			// This lock is also the compatible-authorization boundary. The live prerequisite read and
			// immutable context capture therefore precede the FIFO offer as one callback-entry step.
			if (!prerequisiteGate.allows(context.plan)) return
			val receivedElapsedNanos = SystemClock.elapsedRealtimeNanos()
			val receivedWallTimeMs = System.currentTimeMillis()
			val sequence = ++callbackSequence
			val accepted = queue?.offer(
				CellRuntimeInput.Snapshot(
					snapshot = snapshot,
					outcome = outcome,
					callbackSequence = sequence,
					receivedElapsedNanos = receivedElapsedNanos,
					receivedWallTimeMs = receivedWallTimeMs,
					context = context,
				),
			) == true
			if (!accepted) {
				callbackOfferFailureCoverage.record(sequence..sequence)
				metrics.recordFailure(
					sequence,
					classification = RuntimeGapClassification.CALLBACK_BUFFER_OVERFLOW,
				)
			}
		}
	}

	private fun enqueueControl(input: CellRuntimeInput) {
		synchronized(callbackLock) {
			if (accepting) queue?.offer(input)
		}
	}

	private fun currentCallbackContext(token: CellCallbackToken): CellCallbackContext? =
		synchronized(callbackLock) {
			if (!accepting || callbackToken !== token) null else currentCallbackContextLocked()
		}

	private fun currentCallbackContextLocked(): CellCallbackContext? {
		val activeRegistration = registration ?: return null
		val activePlan = currentPlan ?: return null
		val activeSink = currentSink ?: return null
		return CellCallbackContext(activeRegistration, activePlan, activeSink)
	}

	private suspend fun consume(
		inputs: CellCallbackLane<CellRuntimeInput>,
		activeToken: CellCallbackToken,
	) {
		var lastRefreshAtMs = Long.MIN_VALUE
		var backoff = RuntimeBackoffState()
		var awaitingRefresh = false
		val replayGateBySubscription = mutableMapOf<Int?, BoundedReplayIdentityGate>()
		var refreshAttempts = 0
		var qualifiedEvidenceReceived = false

		fun canRequestRefresh(context: CellCallbackContext): Boolean =
			refreshAttempts < MAX_REFRESH_ATTEMPTS_PER_REGISTRATION &&
				!qualifiedEvidenceReceived &&
				context.registration.purposeEligibilityMask and
					SourceBrokerPurpose.MASK_SESSION_CAPTURE != 0L

		suspend fun scheduleRefresh() {
			val context = currentCallbackContext(activeToken) ?: return
			val state = deviceStateProvider.cell()
			if (!prerequisiteGate.allows(context.plan) ||
				!shouldScheduleCellRefresh(context.plan.mode, state.refreshApiAvailable) ||
				!canRequestRefresh(context)
			) {
				wakeups.cancel(REFRESH_WAKEUP_ID)
				return
			}
			val now = SystemClock.elapsedRealtime()
			val earliest = nextAttemptDeadlineMs(
				now,
				lastRefreshAtMs.takeUnless { it == Long.MIN_VALUE },
				context.plan.minimumRefreshAttemptIntervalMs,
				backoff.delayMs(context.plan.backoff),
			)
			wakeups.schedule(
				sourceWakeupRequest(
					REFRESH_WAKEUP_ID, source, earliest,
					(context.plan.minimumRefreshAttemptIntervalMs / 4L)
						.coerceIn(0L, MAX_COALESCE_WINDOW_MS),
				),
			) {
				enqueueControl(CellRuntimeInput.Refresh)
			}
		}

		suspend fun recordSnapshot(input: CellRuntimeInput.Snapshot): Boolean {
			val qualified = qualifyCellSnapshot(
				input.snapshot,
				input.outcome,
				input.receivedElapsedNanos,
				input.context.plan.maximumAcceptableCachedAgeMs,
			) ?: return false
			val replayGate = replayGateBySubscription.getOrPut(input.snapshot.subscriptionId) {
				BoundedReplayIdentityGate()
			}
			if (!replayGate.shouldAdmit(qualified.snapshotIdentity)) return false
			val deliveryIdentity = cellProviderDeliveryIdentity(
				clockDomainId = input.context.registration.state.clockDomainId,
				observations = qualified.observations,
			)
			return if (admitSnapshot(
					input.context,
					qualified.observations,
					qualified.providerTimeNanos,
					input.receivedElapsedNanos,
					input.receivedWallTimeMs,
					PlanAttribution.CAPTURED_REGISTRATION,
					input.callbackSequence,
					deliveryIdentity,
				)
			) {
				replayGate.record(qualified.snapshotIdentity)
				true
			} else false
		}

		inputs.consume inputLoop@{ input ->
			when (input) {
				CellRuntimeInput.Started,
				CellRuntimeInput.PlanChanged -> {
					scheduleRefresh()
				}
				is CellRuntimeInput.Snapshot -> {
					try {
						if (recordSnapshot(input)) {
							qualifiedEvidenceReceived = true
							wakeups.cancel(REFRESH_WAKEUP_ID)
						}
					} catch (cancelled: CancellationException) {
						metrics.recordFailure(input.callbackSequence)
						throw cancelled
					} catch (fatal: Error) {
						metrics.recordFailure(input.callbackSequence)
						throw fatal
					} catch (_: Exception) {
						metrics.recordFailure(input.callbackSequence)
					} finally {
						processedCallbackSequence.value = maxOf(
							processedCallbackSequence.value,
							input.callbackSequence,
						)
					}
					if (awaitingRefresh) {
						awaitingRefresh = false
						wakeups.cancel(TIMEOUT_WAKEUP_ID)
						backoff = backoff.succeeded()
					}
					scheduleRefresh()
				}
				CellRuntimeInput.Refresh -> {
					val context = currentCallbackContext(activeToken) ?: return@inputLoop
					if (!canRequestRefresh(context) || !prerequisiteGate.allows(context.plan)) {
						scheduleRefresh()
						return@inputLoop
					}
					refreshAttempts++
					lastRefreshAtMs = SystemClock.elapsedRealtime()
					when (backend.requestRefresh { snapshot ->
						onBackendSnapshot(activeToken, snapshot, CellRefreshOutcome.CALLBACK)
					}) {
						CellRefreshRequestOutcome.REQUESTED -> {
							awaitingRefresh = true
							val timeoutAt = SystemClock.elapsedRealtime() + REFRESH_TIMEOUT_MS
							wakeups.schedule(
								sourceWakeupRequest(TIMEOUT_WAKEUP_ID, source, timeoutAt, 0L),
							) { enqueueControl(CellRuntimeInput.Timeout) }
						}
						CellRefreshRequestOutcome.NOT_SUPPORTED -> {
							backoff = backoff.succeeded()
						}
						CellRefreshRequestOutcome.PERMISSION_BLOCKED -> {
							backoff = backoff.failed()
						}
						CellRefreshRequestOutcome.PROVIDER_FAILED -> {
							backoff = backoff.failed()
						}
					}
					scheduleRefresh()
				}
				CellRuntimeInput.Timeout -> if (awaitingRefresh) {
					awaitingRefresh = false
					backoff = backoff.failed()
					scheduleRefresh()
				}
			}
		}
	}

	private suspend fun admitSnapshot(
		context: CellCallbackContext,
		observations: List<CellObservationEvidence>,
		providerTimeNanos: Long,
		receivedNanos: Long,
		receivedWallTimeMs: Long,
		attribution: PlanAttribution,
		callbackSequence: Long,
		deliveryIdentity: SourceDeliveryIdentity,
	): Boolean {
		val registration = context.registration
		val observed = providerTimeNanos
		if (!isAtOrBeforeCutoff(observed)) return false
		if (!prerequisiteGate.allows(context.plan)) {
			metrics.recordFailure(callbackSequence)
			return false
		}
		val delayMs = (receivedNanos - observed).coerceAtLeast(0L) / NANOS_PER_MILLISECOND
		val wallTime = (receivedWallTimeMs - delayMs).coerceAtLeast(0L)
		val candidate = SourceEvidenceCandidate(
			// Stable replay identity belongs to SourceDeliveryCandidate. Keeping this null prevents the
			// legacy single-evidence path from binding provider identity to a runtime registration.
			providerDedupKey = null,
			logicalTrackingId = null, serviceRunId = null, source = source,
			sourceInstanceId = SourceInstanceId(registration.state.sourceInstanceId),
			registrationGeneration = registration.state.registrationGeneration,
			physicalConfigurationFingerprint = registration.physicalConfigurationFingerprint,
			authorizationRevision = registration.authorization.authorizationRevision,
			registrationPurposeEligibilityMask = registration.purposeEligibilityMask,
			registrationEligibilityFingerprint = registration.eligibilityFingerprint,
			// Atomic ingress allocates a sequence only when this delivery is newly durable.
			sourceSequence = 0L,
			configRevision = registration.state.appliedRevision.takeUnless {
				attribution == PlanAttribution.RECEIVE_TIME_ONLY
			},
			planAttribution = attribution,
			clockDomainId = registration.state.clockDomainId,
			observedElapsedRealtimeNanos = observed.coerceAtLeast(0L),
			receivedElapsedRealtimeNanos = receivedNanos.coerceAtLeast(0L),
			wallTimeMs = wallTime, wallTimeUncertaintyMs = 1L,
			capturedCollectedDataEpoch = registration.state.collectedDataEpoch,
			acquiredAtMs = wallTime, quality = SourceQuality(), payloadVersion = 1,
			payload = minimizedCellSnapshotPayload(observations, CellRefreshOutcome.CALLBACK),
		)
		val intervalStart = observations.mapNotNull(CellObservationEvidence::providerTimestampNanos)
			.minOrNull() ?: observed.coerceAtLeast(0L)
		val delivery = SourceDeliveryCandidate(
			identity = deliveryIdentity,
			units = listOf(
				SourceDeliveryUnit(
					unitIndex = 0,
					evidence = candidate,
					observedIntervalStartElapsedRealtimeNanos = intervalStart,
				),
			),
		)
		when (val handoff = retryCellDeliveryAdmissionWithinBudget(
			prerequisiteAllows = {
				prerequisiteGate.allows(context.plan) && isAtOrBeforeCutoff(observed)
			},
			admit = { context.sink.admit(delivery) },
		)) {
			is SourceDeliveryAdmissionHandoff.Durable -> {
				val ordinal = handoff.admissionOrdinals.singleOrNull() ?: run {
					metrics.recordFailure(callbackSequence)
					return false
				}
				recordDurableAdmission(callbackSequence, ordinal)
				return true
			}
			is SourceDeliveryAdmissionHandoff.Duplicate -> {
				val ordinal = handoff.existingAdmissionOrdinals.singleOrNull() ?: run {
					metrics.recordFailure(callbackSequence)
					return false
				}
				recordDurableAdmission(callbackSequence, ordinal)
				return true
			}
			is SourceDeliveryAdmissionHandoff.SessionCutoff -> {
				// Cell does not reinterpret an already-composed atomic delivery.
				metrics.recordFailure(callbackSequence)
				return false
			}
			is SourceDeliveryAdmissionHandoff.TerminalFailure,
			is SourceDeliveryAdmissionHandoff.RetryableFailure,
			-> {
				metrics.recordFailure(callbackSequence)
				return false
			}
			null -> {
				metrics.recordFailure(callbackSequence)
				return false
			}
		}
	}

	private fun recordDurableAdmission(callbackSequence: Long, admissionOrdinal: Long) {
		// A duplicate may refer to an older WAL row than a delivery admitted earlier in this
		// physical run. Advance callback resolution without regressing the stop-ack WAL boundary.
		val ordinalHighWater = lastAdmissionOrdinalHighWater
			?.coerceAtLeast(admissionOrdinal)
			?: admissionOrdinal
		lastAdmissionOrdinalHighWater = ordinalHighWater
		metrics.recordDurable(callbackSequence, ordinalHighWater)
	}

	private fun isAtOrBeforeCutoff(observedElapsedRealtimeNanos: Long): Boolean =
		cutoffElapsedNanos?.let { observedElapsedRealtimeNanos <= it } != false

	private fun capabilitiesNow(): SourceCapabilities = cellCapabilities(deviceStateProvider.cell())

	private fun clearActiveState() {
		synchronized(callbackLock) {
			accepting = false
			registration = null
			callbackToken = null
			currentSink = null
			queue = null
			retirementIntent = null
		}
		actor = null
		cutoffElapsedNanos = null
		currentPlan = null
		ownerClaim = null
	}

	private fun unavailableAck(cutoff: SessionCutoff?) = SourceStopAck(
		source, SourceInstanceId("unavailable-cell"), 0L, currentPlan?.revision,
		callbackSequence, null, null, 0L, null, null,
		RegistrationRemovalOutcome.NOT_REGISTERED, ProviderFlushOutcome.NOT_REQUESTED,
		ProviderCoverage.PROVIDER_COMPLETENESS_UNOBSERVABLE, true,
		if (cutoff == null) SourceStopStatus.COMPLETE else SourceStopStatus.PROVIDER_FAILED,
	).withSessionMembership(ownerClaim)

	private sealed interface CellRuntimeInput {
		data object Started : CellRuntimeInput
		data object PlanChanged : CellRuntimeInput
		data object Refresh : CellRuntimeInput
		data object Timeout : CellRuntimeInput
		data class Snapshot(
			val snapshot: CellBackendSnapshot,
			val outcome: CellRefreshOutcome,
			val callbackSequence: Long,
			val receivedElapsedNanos: Long,
			val receivedWallTimeMs: Long,
			val context: CellCallbackContext,
		) : CellRuntimeInput
	}

	private companion object {
		const val REFRESH_WAKEUP_ID = "cell-sparse-refresh"
		const val TIMEOUT_WAKEUP_ID = "cell-refresh-timeout"
		const val DEFAULT_DRAIN_TIMEOUT_MS = 3_000L
		const val NANOS_PER_MILLISECOND = 1_000_000L
		const val MAX_COALESCE_WINDOW_MS = 5_000L
		const val REFRESH_TIMEOUT_MS = 20_000L
		const val MAX_REFRESH_ATTEMPTS_PER_REGISTRATION = 1
		const val PROVIDER_OPERATION_TIMEOUT_MS = 2_000L
	}
}

private class CellCallbackToken

private data class CellProviderRetirementIntent(
	val registration: SourceRegistration,
	val reason: String,
	val retiredAtMs: Long,
	val retiredElapsedRealtimeNanos: Long,
	var token: SourceRegistrationRetirementToken? = null,
)

private fun SourceRegistration.samePhysicalRegistrationAs(other: SourceRegistration): Boolean =
	state.sourceKind == other.state.sourceKind &&
		state.sourceInstanceId == other.state.sourceInstanceId &&
		state.registrationGeneration == other.state.registrationGeneration

private fun SourceRegistrationRetirementToken.sameRetirementAs(
	other: SourceRegistrationRetirementToken,
): Boolean = source == other.source &&
	sourceInstanceId == other.sourceInstanceId &&
	registrationGeneration == other.registrationGeneration &&
	processIncarnationId == other.processIncarnationId &&
	retiredAtMs == other.retiredAtMs &&
	retiredElapsedRealtimeNanos == other.retiredElapsedRealtimeNanos

private enum class CellProviderRetirement { NOT_DURABLE, PENDING, COMPLETE }

private data class CellCallbackContext(
	val registration: SourceRegistration,
	val plan: CellPlan,
	val sink: SourceEventSink,
)

/**
 * Fixed-memory Cell FIFO. A rejected provider callback is represented in process-local admission
 * metrics and, when shutdown is orderly, in [SourceStopAck]. Crash-durable handoff is separate.
 */
internal class CellCallbackLane<T>(capacity: Int = CELL_CALLBACK_BUFFER_CAPACITY) {
	private val channel: Channel<T>

	init {
		require(capacity > 0)
		channel = Channel(capacity)
	}

	fun offer(input: T): Boolean = channel.trySend(input).isSuccess

	fun close() = channel.close()

	suspend fun consume(action: suspend (T) -> Unit) {
		for (input in channel) action(input)
	}
}

/** Reads live Android state on every invocation; no reconciler or capability-flow cache is used. */
internal class CellPrerequisiteGate(
	private val stateProvider: () -> CellDeviceState,
) {
	fun allows(plan: CellPlan): Boolean = try {
		plan.enabled && CellPrerequisiteEvaluator.evaluate(plan, stateProvider()).status !=
			SourceApplyStatus.BLOCKED
	} catch (cancelled: CancellationException) {
		throw cancelled
	} catch (_: Exception) {
		false
	}
}

internal data class QualifiedCellSnapshot(
	val observations: List<CellObservationEvidence>,
	val providerTimeNanos: Long,
	val snapshotIdentity: String,
)

internal fun qualifyCellSnapshot(
	snapshot: CellBackendSnapshot,
	outcome: CellRefreshOutcome,
	receivedElapsedNanos: Long,
	maximumAcceptableAgeMs: Long,
): QualifiedCellSnapshot? {
	// Cached snapshots may help bootstrap provider state in memory, but neither they nor
	// operational outcomes can establish a fresh durable observation.
	if (outcome != CellRefreshOutcome.CALLBACK) return null
	val eligible = freshProviderObservations(
		snapshot.observations,
		receivedElapsedNanos,
		maximumAcceptableAgeMs,
		CellBackendObservation::providerTimestampNanos,
	)
	// Empty callbacks have no provider timestamp or durable transition state. Receipt-time identity
	// would persist repeated unchanged emptiness, so empty coverage remains deferred.
	if (eligible.isEmpty()) return null
	val providerTime = eligible.mapNotNull(CellBackendObservation::providerTimestampNanos)
		.maxOrNull() ?: return null
	val observations = eligible.map(CellBackendObservation::toMinimizedEvidence)
		.sortedWith(CELL_OBSERVATION_ORDER)
	val identity = observations.joinToString("|") {
		"${it.radioType.length}:${it.radioType}:${it.registered}:${it.signalLevelDbm}:" +
			it.providerTimestampNanos
	}
	return QualifiedCellSnapshot(observations, providerTime, identity)
}

/**
 * Retries the FIFO head inside a finite in-process budget. A retryable result after the final
 * delay is returned to the caller so the callback can be marked unresolved and the lane can keep
 * draining. A null means the live prerequisite disappeared before an admission attempt.
 */
internal suspend fun retryCellDeliveryAdmissionWithinBudget(
	prerequisiteAllows: () -> Boolean,
	admit: suspend () -> SourceDeliveryAdmissionHandoff,
	retryDelaysMs: LongArray = CELL_ADMISSION_RETRY_DELAYS_MS,
	waitBeforeRetry: suspend (Long) -> Unit = { delay(it) },
): SourceDeliveryAdmissionHandoff? {
	var retryIndex = 0
	while (true) {
		if (!prerequisiteAllows()) return null
		val result = admit()
		if (result !is SourceDeliveryAdmissionHandoff.RetryableFailure) return result
		val retryDelayMs = retryDelaysMs.getOrNull(retryIndex++) ?: return result
		waitBeforeRetry(retryDelayMs)
	}
}

internal fun unprocessedCellCallbackRange(processedSequence: Long, barrierSequence: Long): LongRange? {
	require(processedSequence >= 0L)
	require(barrierSequence >= 0L)
	val first = processedSequence + 1L
	return if (first <= barrierSequence) first..barrierSequence else null
}

/** Compact exact coverage for Cell callbacks rejected before actor processing. */
private class CellCallbackFailureCoverage {
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

internal fun cellPlansSharePhysicalRegistration(active: CellPlan, updated: CellPlan): Boolean =
	updated.enabled &&
		active.physicalConfigurationFingerprint() == updated.physicalConfigurationFingerprint()

internal fun cellCapabilities(state: CellDeviceState): SourceCapabilities {
	val available = state.radioFeatureAvailable && state.fineLocationPermission &&
		state.readPhoneStatePermission
	val reasons = buildSet {
		if (!state.radioFeatureAvailable) add(SourceDegradedReason.HARDWARE_UNAVAILABLE)
		if (!state.fineLocationPermission || !state.readPhoneStatePermission) {
			add(SourceDegradedReason.PERMISSION_MISSING)
		}
	}
	return SourceCapabilities(
		available = available,
		batchingSupported = false,
		flushSupported = false,
		maximumBatchSize = null,
		// requestCellInfoUpdate is a finite opportunistic probe, not a wake-reliable cadence.
		minimumDelayMs = null,
		degradedReasons = reasons,
	)
}

internal const val CELL_CALLBACK_BUFFER_CAPACITY = 64
private val CELL_ADMISSION_RETRY_DELAYS_MS = longArrayOf(10L, 50L, 250L)

internal fun CellBackendObservation.toMinimizedEvidence() = CellObservationEvidence(
	identifierToken = WITHHELD_RADIO_IDENTIFIER_TOKEN,
	radioType = radioType,
	registered = registered,
	signalLevelDbm = signalLevelDbm,
	providerTimestampNanos = providerTimestampNanos,
)

/**
 * Process-independent identity for one qualified Cell provider delivery. Only the boot clock
 * domain and minimized timestamped provider facts are used; raw cell/subscription identity and
 * runtime registration state never enter the durable digest.
 */
internal fun cellProviderDeliveryIdentity(
	clockDomainId: String,
	observations: List<CellObservationEvidence>,
): SourceDeliveryIdentity {
	require(clockDomainId.isNotBlank())
	require(observations.isNotEmpty()) { "Cell delivery identity requires provider facts" }
	require(observations.all { it.identifierToken == WITHHELD_RADIO_IDENTIFIER_TOKEN }) {
		"Cell delivery identity accepts minimized observations only"
	}
	val canonical = ByteArrayOutputStream().also { bytes ->
		DataOutputStream(bytes).use { output ->
			output.writeInt(CELL_DELIVERY_IDENTITY_VERSION)
			output.writeCanonicalString(clockDomainId)
			val ordered = observations.sortedWith(CELL_OBSERVATION_ORDER)
			output.writeInt(ordered.size)
			ordered.forEach { observation ->
				output.writeCanonicalString(observation.radioType)
				output.writeBoolean(observation.registered)
				output.writeBoolean(observation.signalLevelDbm != null)
				observation.signalLevelDbm?.let(output::writeInt)
				output.writeLong(requireNotNull(observation.providerTimestampNanos) {
					"Qualified Cell evidence requires provider time"
				})
			}
		}
	}.toByteArray()
	return sourceDeliveryIdentity(canonical)
}

private fun DataOutputStream.writeCanonicalString(value: String) {
	val encoded = value.toByteArray(Charsets.UTF_8)
	writeInt(encoded.size)
	write(encoded)
}

internal fun minimizedCellSnapshotPayload(
	observations: List<CellObservationEvidence>,
	outcome: CellRefreshOutcome,
) = CellSnapshotPayload(
	subscriptionId = null,
	observations = observations,
	refreshOutcome = outcome,
)

private val CELL_OBSERVATION_ORDER = compareBy<CellObservationEvidence>(
	CellObservationEvidence::radioType,
	CellObservationEvidence::registered,
	CellObservationEvidence::signalLevelDbm,
	CellObservationEvidence::providerTimestampNanos,
)

private const val CELL_DELIVERY_IDENTITY_VERSION = 1
