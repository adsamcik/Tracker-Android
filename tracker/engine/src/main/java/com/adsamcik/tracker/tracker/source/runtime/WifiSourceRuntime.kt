package com.adsamcik.tracker.tracker.source.runtime

import android.os.SystemClock
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.di.ApplicationScope
import com.adsamcik.tracker.tracker.source.runCatchingNonCancellation
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
import com.adsamcik.tracker.tracker.source.model.WifiAccessPointEvidence
import com.adsamcik.tracker.tracker.source.model.WifiMode
import com.adsamcik.tracker.tracker.source.model.WifiPlan
import com.adsamcik.tracker.tracker.source.model.WifiResultSnapshotPayload
import com.adsamcik.tracker.tracker.source.model.physicalConfigurationFingerprint
import com.adsamcik.tracker.tracker.source.model.sourceDeliveryIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WifiSourceRuntime @Inject internal constructor(
	@ApplicationScope private val applicationScope: CoroutineScope,
	private val registrations: SourceRegistrationRepository,
	private val backend: AndroidWifiSourceBackend,
	private val deviceStateProvider: AndroidConnectivityDeviceStateProvider,
	private val wakeups: CoalescingSourceWakeupScheduler,
) : ClaimedSourceRuntime<WifiPlan> {
	override val source = SourceKind.WIFI
	private val _capabilities = MutableStateFlow(capabilitiesNow())
	override val capabilities: StateFlow<SourceCapabilities> = _capabilities
	private val lifecycleMutex = Mutex()
	private val providerOperationMutex = Mutex()
	private val callbackLock = Any()
	private var registration: SourceRegistration? = null
	private var currentPlan: WifiPlan? = null
	private var currentSink: SourceEventSink? = null
	private var queue: WifiCallbackLane<WifiRuntimeInput>? = null
	private var actor: Job? = null
	private var accepting = false
	private var refreshAdmissionFenced = false
	private var callbackSequence = 0L
	private val processedCallbackSequence = MutableStateFlow(0L)
	@Volatile
	private var cutoffElapsedNanos: Long? = null
	private var metrics = RuntimeAdmissionMetrics()
	private var lastAdmissionOrdinalHighWater: Long? = null
	private var retirementIntent: WifiProviderRetirementIntent? = null
	private var ownerClaim: SourceRuntimeClaim? = null
	private val prerequisiteGate = WifiCallbackPrerequisiteGate(deviceStateProvider::wifi)

	override suspend fun start(plan: WifiPlan, sink: SourceEventSink): SourceStartResult =
		startWithClaim(null, plan, sink)

	override suspend fun start(
		claim: SourceRuntimeClaim,
		plan: WifiPlan,
		sink: SourceEventSink,
	): SourceStartResult {
		require(claim.source == source)
		return startWithClaim(claim, plan, sink)
	}

	private suspend fun startWithClaim(
		claim: SourceRuntimeClaim?,
		plan: WifiPlan,
		sink: SourceEventSink,
	): SourceStartResult = lifecycleMutex.withLock {
		require(currentPlan == null) { "Wi-Fi source is already started" }
		startLocked(claim, plan, sink)
	}

	override suspend fun reconfigure(plan: WifiPlan, sink: SourceEventSink): SourceApplyResult =
		reconfigureWithClaim(null, plan, sink)

	override suspend fun reconfigure(
		claim: SourceRuntimeClaim,
		plan: WifiPlan,
		sink: SourceEventSink,
	): SourceApplyResult {
		require(claim.source == source)
		return reconfigureWithClaim(claim, plan, sink)
	}

	private suspend fun reconfigureWithClaim(
		claim: SourceRuntimeClaim?,
		plan: WifiPlan,
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
		shutdownLocked(cutoff)
	}

	override suspend fun close() {
		lifecycleMutex.withLock {
			if (currentPlan != null) {
				shutdownLocked(null)
			} else {
				reconcilePendingProviderRetirements()
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
			shutdownLocked(cutoff).toOwnedShutdown()
		}
	}

	private suspend fun refreshCompatibleLocked(
		claim: SourceRuntimeClaim?,
		plan: WifiPlan,
		sink: SourceEventSink,
	): SourceApplyResult? {
		val activePlan = currentPlan ?: return null
		val activeRegistration = registration ?: return null
		if (!synchronized(callbackLock) {
				accepting && queue != null && registration === activeRegistration
			}
		) return null
		if (!plan.enabled ||
			activePlan.physicalConfigurationFingerprint() != plan.physicalConfigurationFingerprint()
		) return null
		val application = prerequisiteGate.application(plan) ?: return SourceApplyResult.Failed(
			appliedState(source, plan.revision, activeRegistration, SourceApplyStatus.FAILED,
				SystemClock.elapsedRealtimeNanos()),
			retryable = true,
		)
		_capabilities.value = capabilitiesNow()
		if (application.status == SourceApplyStatus.BLOCKED) {
			return SourceApplyResult.Failed(
				appliedState(source, plan.revision, activeRegistration, SourceApplyStatus.BLOCKED,
					SystemClock.elapsedRealtimeNanos()).copy(degradedReasons = application.reasons),
				retryable = false,
			)
		}
		var refreshFenceInstalled = false
		return try {
			providerOperationMutex.withLock providerOperation@{
				val refreshBoundary = synchronized(callbackLock) {
					if (!accepting || queue == null || registration !== activeRegistration) {
						null
					} else {
						// Close callback entry before rotating durable observed-time authorization.
						// A provider callback displaced by this fence is gap-accounted at entry.
						accepting = false
						refreshAdmissionFenced = true
						refreshFenceInstalled = true
						SystemClock.elapsedRealtimeNanos()
					}
				} ?: return@providerOperation null
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
					return@providerOperation SourceApplyResult.Failed(
						appliedState(source, plan.revision, activeRegistration, SourceApplyStatus.FAILED,
							SystemClock.elapsedRealtimeNanos()),
						retryable = true,
					)
				} ?: return@providerOperation null
				if (!activeRegistration.samePhysicalRegistration(refreshed) ||
					refreshed.requiresProviderAcceptance
				) return@providerOperation null
				val refreshedInPlace = synchronized(callbackLock) {
					val activeQueue = queue
					if (!refreshAdmissionFenced || activeQueue == null ||
						registration !== activeRegistration
					) false else {
						// The marker and callback offers share this lock. Older queued callbacks retain their
						// immutable authorization; callbacks entering after it capture the refreshed vector.
						if (activeQueue.offer(WifiRuntimeInput.Reconfigured(refreshed)) != WifiLaneOffer.ACCEPTED) {
							false
						} else {
							registration = refreshed
							currentPlan = plan
							currentSink = sink
							refreshAdmissionFenced = false
							accepting = true
							true
						}
					}
				}
				if (!refreshedInPlace) return@providerOperation null
				// Lifecycle ownership follows the durable authorization attempt even though the provider key
				// remains unchanged. A legacy refresh intentionally invalidates any older claimed attempt.
				ownerClaim = claim
				val state = appliedState(
					source, plan.revision, refreshed, application.status, SystemClock.elapsedRealtimeNanos(),
				).copy(degradedReasons = application.reasons)
				if (application.status == SourceApplyStatus.DEGRADED) {
					SourceApplyResult.Degraded(state)
				} else {
					SourceApplyResult.Applied(state)
				}
			}
		} catch (cancelled: CancellationException) {
			cleanupFencedRefreshFailure(refreshFenceInstalled, cancelled)
			throw cancelled
		} catch (fatal: Error) {
			cleanupFencedRefreshFailure(refreshFenceInstalled, fatal)
			throw fatal
		}
	}

	private suspend fun cleanupFencedRefreshFailure(
		refreshFenceInstalled: Boolean,
		originalFailure: Throwable,
	) {
		if (!refreshFenceInstalled) return
		// A fatal caller may never retry. Retire the exact fenced provider now; incomplete removal
		// remains represented by the retained retirement intent. Cleanup cannot replace the original.
		try {
			withContext(NonCancellable) { shutdownLocked(null) }
		} catch (@Suppress("TooGenericExceptionCaught") cleanupFailure: Throwable) {
			if (cleanupFailure !== originalFailure) originalFailure.addSuppressed(cleanupFailure)
		}
	}

	private suspend fun startLocked(
		claim: SourceRuntimeClaim?,
		plan: WifiPlan,
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
		if (!plan.enabled) return SourceStartResult.Started(
			appliedState(source, plan.revision, null, SourceApplyStatus.APPLIED, SystemClock.elapsedRealtimeNanos()),
		)
		val application = WifiPrerequisiteEvaluator.evaluate(plan, deviceStateProvider.wifi())
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
		val nextQueue = WifiCallbackLane<WifiRuntimeInput>()
		registration = nextRegistration
		currentPlan = plan
		currentSink = sink
		queue = nextQueue
		callbackSequence = 0L
		processedCallbackSequence.value = 0L
		cutoffElapsedNanos = null
		metrics = RuntimeAdmissionMetrics()
		lastAdmissionOrdinalHighWater = null
		refreshAdmissionFenced = false
		accepting = true
		// Publish shutdown authority before provider start: a throwing backend may have installed a
		// receiver before reporting failure, and exact cleanup must remain claim-addressable.
		ownerClaim = claim
		val started = try {
			backend.start { event -> onBackendEvent(nextRegistration, event) }
		} catch (cancelled: CancellationException) {
			cleanupFailedStart(nextRegistration, nextQueue, "PROVIDER_REGISTRATION_CANCELLED")
			throw cancelled
		} catch (fatal: Error) {
			cleanupFailedStart(nextRegistration, nextQueue, "PROVIDER_REGISTRATION_FATAL")
			throw fatal
		} catch (_: Exception) {
			false
		}
		if (!started) {
			cleanupFailedStart(nextRegistration, nextQueue, "PROVIDER_REGISTRATION_FAILED")
			return SourceStartResult.Failed(
				appliedState(source, plan.revision, nextRegistration, SourceApplyStatus.FAILED, SystemClock.elapsedRealtimeNanos()), true,
			)
		}
		val acceptedRegistration = try {
			if (nextRegistration.requiresProviderAcceptance) {
				val acceptedElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
				registrations.markAccepted(
					nextRegistration,
					System.currentTimeMillis(),
					acceptedElapsedRealtimeNanos,
				)
				nextRegistration.copy(
					providerAcceptedElapsedRealtimeNanos = acceptedElapsedRealtimeNanos,
				)
			} else {
				// ACTIVE reuse is authoritative only when the repository returned its exact durable
				// provider-acceptance boundary.
				nextRegistration.takeIf {
					it.providerAcceptedElapsedRealtimeNanos != null
				}
			}
		} catch (cancelled: CancellationException) {
			cleanupFailedStart(nextRegistration, nextQueue, "REGISTRATION_ACCEPTANCE_CANCELLED")
			throw cancelled
		} catch (fatal: Error) {
			cleanupFailedStart(nextRegistration, nextQueue, "REGISTRATION_ACCEPTANCE_FATAL")
			throw fatal
		} catch (_: Exception) {
			null
		}
		if (acceptedRegistration == null) {
			cleanupFailedStart(nextRegistration, nextQueue, "REGISTRATION_ACCEPTANCE_STALE")
			return SourceStartResult.Failed(
				appliedState(source, plan.revision, nextRegistration, SourceApplyStatus.FAILED, SystemClock.elapsedRealtimeNanos()), true,
			)
		}
		synchronized(callbackLock) {
			check(registration === nextRegistration)
			registration = acceptedRegistration
		}
		actor = applicationScope.launch { consume(nextQueue, acceptedRegistration) }
		val state = appliedState(
			source, plan.revision, acceptedRegistration, application.status, SystemClock.elapsedRealtimeNanos(),
		).copy(degradedReasons = application.reasons)
		return if (application.status == SourceApplyStatus.DEGRADED) {
			SourceStartResult.Degraded(state)
		} else SourceStartResult.Started(state)
	}

	private suspend fun cleanupFailedStart(
		failedRegistration: SourceRegistration,
		callbackLane: WifiCallbackLane<WifiRuntimeInput>,
		failureCode: String,
	) = withContext(NonCancellable) {
		val retirementBoundary = closeCallbackAdmission(callbackLane)
		val retirement = retireProviderRegistration(
			failedRegistration,
			reason = failureCode,
			retiredElapsedRealtimeNanos = retirementBoundary,
		)
		actor?.cancelAndJoin()
		settleFailedStartState(retirement)
	}

	private suspend fun shutdownLocked(cutoff: SessionCutoff?): SourceStopAck {
		val activeRegistration = registration ?: return unavailableAck(cutoff)
		val (barrier, retirementBoundary) = providerOperationMutex.withLock {
			synchronized(callbackLock) {
				cutoffElapsedNanos = cutoff?.elapsedRealtimeNanos
				accepting = false
				refreshAdmissionFenced = false
				callbackSequence to SystemClock.elapsedRealtimeNanos()
			}
		}
		wakeups.cancel(WAKEUP_ID)
		val retirement = retireProviderRegistration(
			activeRegistration,
			reason = "ORDERLY_STOP",
			retiredElapsedRealtimeNanos = retirementBoundary,
		)
		val removed = if (retirement == WifiProviderRetirement.COMPLETE) {
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
		val drained = activeActor == null || withTimeoutOrNull(remainingMs) { activeActor.join(); true } == true
		if (!drained) {
			val firstUnresolved = processedCallbackSequence.value + 1L
			if (firstUnresolved <= barrier) {
				metrics.recordFailure(
					firstUnresolved,
					barrier,
					RuntimeGapClassification.DRAIN_TIMED_OUT,
				)
			}
			activeActor.cancelAndJoin()
		}
		val admission = metrics.snapshot()
		val ack = SourceStopAck(
			source, SourceInstanceId(activeRegistration.state.sourceInstanceId),
			activeRegistration.state.registrationGeneration, currentPlan?.revision, barrier,
			admission.lastDurablyAdmittedSequence, admission.lastAdmissionOrdinal,
			admission.failedAdmissionCount, admission.unresolvedSequenceStart,
			admission.unresolvedSequenceEndInclusive, removed, ProviderFlushOutcome.NOT_SUPPORTED,
			ProviderCoverage.PROVIDER_COMPLETENESS_UNOBSERVABLE, drained,
			when {
				!drained -> SourceStopStatus.TIMED_OUT
				removed == RegistrationRemovalOutcome.FAILED -> SourceStopStatus.PROVIDER_FAILED
				else -> SourceStopStatus.COMPLETE
			},
		).withSessionMembership(ownerClaim)
		if (retirement == WifiProviderRetirement.COMPLETE) {
			clearActiveState()
		} else {
			retainProviderForRetirementRetry()
		}
		return ack
	}

	private fun closeCallbackAdmission(callbackLane: WifiCallbackLane<WifiRuntimeInput>): Long =
		synchronized(callbackLock) {
			accepting = false
			refreshAdmissionFenced = false
			callbackLane.close()
			SystemClock.elapsedRealtimeNanos()
		}

	private suspend fun retireProviderRegistration(
		activeRegistration: SourceRegistration,
		reason: String,
		retiredElapsedRealtimeNanos: Long,
	): WifiProviderRetirement = withContext(NonCancellable) {
		val intent = synchronized(callbackLock) {
			retirementIntent?.also { pending ->
				check(pending.registration.samePhysicalRegistration(activeRegistration)) {
					"A retained Wi-Fi receiver cannot be retired under a replacement registration"
				}
			} ?: WifiProviderRetirementIntent(
				registration = activeRegistration,
				reason = reason,
				retiredAtMs = System.currentTimeMillis(),
				retiredElapsedRealtimeNanos = retiredElapsedRealtimeNanos,
			).also { retirementIntent = it }
		}
		val token = intent.token ?: runCatchingNonCancellation {
			registrations.beginRetirement(
				intent.registration,
				intent.reason,
				intent.retiredAtMs,
				intent.retiredElapsedRealtimeNanos,
			)
		}.getOrElse {
			return@withContext WifiProviderRetirement.NOT_DURABLE
		}.also { intent.token = it }
		if (!runCatchingNonCancellation { backend.stop() }.getOrDefault(false)) {
			return@withContext WifiProviderRetirement.PENDING
		}
		val completed = runCatchingNonCancellation {
			registrations.completeRetirement(token)
		}.getOrDefault(false)
		if (completed) {
			synchronized(callbackLock) {
				if (retirementIntent === intent) retirementIntent = null
			}
			WifiProviderRetirement.COMPLETE
		} else {
			WifiProviderRetirement.PENDING
		}
	}

	private suspend fun reconcilePendingProviderRetirements(): Boolean = withContext(NonCancellable) {
		val pending = runCatchingNonCancellation {
			registrations.pendingRetirements(source)
		}.getOrElse { return@withContext false }
		if (pending.isEmpty()) return@withContext true
		if (pending.size != 1) return@withContext false
		if (!runCatchingNonCancellation { backend.stop() }.getOrDefault(false)) return@withContext false
		pending.all { token ->
			runCatchingNonCancellation {
				registrations.completeRetirement(token)
			}.getOrDefault(false)
		}
	}

	private fun settleFailedStartState(retirement: WifiProviderRetirement) {
		if (retirement == WifiProviderRetirement.COMPLETE) {
			clearActiveState()
		} else {
			retainProviderForRetirementRetry()
		}
	}

	private fun retainProviderForRetirementRetry() {
		synchronized(callbackLock) {
			accepting = false
			refreshAdmissionFenced = false
			currentSink = null
			queue = null
		}
		actor = null
	}

	// Early exits share callbackLock so stale, fenced, and overflowed callbacks cannot cross lanes.
	@Suppress("CyclomaticComplexMethod", "ReturnCount")
	private fun onBackendEvent(callbackRegistration: SourceRegistration, event: WifiBackendEvent) {
		synchronized(callbackLock) {
			val activeRegistration = registration ?: return
			if (!callbackRegistration.samePhysicalRegistration(activeRegistration)) return
			if (refreshAdmissionFenced) {
				val sequence = ++callbackSequence
				if (event.hasPotentialProductEvidence()) {
					metrics.recordFailure(sequence, classification = RuntimeGapClassification.ADMISSION_FAILED)
				}
				processedCallbackSequence.value = maxOf(processedCallbackSequence.value, sequence)
				return
			}
			val activePlan = currentPlan ?: return
			val activeSink = currentSink ?: return
			if (!accepting || !prerequisiteGate.allows(activePlan)
			) return
			val sequence = ++callbackSequence
			val offer = queue?.offer(
				WifiRuntimeInput.Backend(event, sequence, activeRegistration, activePlan, activeSink),
			) ?: WifiLaneOffer.CLOSED
			if (offer != WifiLaneOffer.ACCEPTED) {
				metrics.recordFailure(
					sequence,
					classification = if (offer == WifiLaneOffer.CAPACITY_EXHAUSTED) {
						RuntimeGapClassification.CALLBACK_BUFFER_OVERFLOW
					} else {
						RuntimeGapClassification.DRAIN_TIMED_OUT
					},
				)
			}
		}
	}

	private fun enqueueControl(callbackRegistration: SourceRegistration, input: WifiRuntimeInput) {
		synchronized(callbackLock) {
			val activeRegistration = registration ?: return
			if (!accepting || !callbackRegistration.samePhysicalRegistration(activeRegistration)) return
			queue?.offer(input)
		}
	}

	private suspend fun consume(
		inputs: WifiCallbackLane<WifiRuntimeInput>,
		physicalRegistration: SourceRegistration,
	) {
		var pendingAttemptId: String? = null
		var lastAttemptAtMs = Long.MIN_VALUE
		var backoff = RuntimeBackoffState()
		val replayGate = BoundedReplayIdentityGate()
		var budgetRegistration = physicalRegistration
		var acquisitionBudget = DirectAcquisitionBudget(
			maximumAttempts = MAX_ACTIVE_ATTEMPTS_PER_REGISTRATION,
			directCaptureRequested = physicalRegistration.purposeEligibilityMask and
				SourceBrokerPurpose.MASK_SESSION_CAPTURE != 0L,
		)

		suspend fun scheduleAttempt() {
			val plan = synchronized(callbackLock) {
				currentDirectAttemptPlanLocked(inputs, physicalRegistration)
			}
			if (plan == null || !acquisitionBudget.canRequest) {
				wakeups.cancel(WAKEUP_ID)
				return
			}
			val application = WifiPrerequisiteEvaluator.evaluate(plan, deviceStateProvider.wifi())
			if (application.status == SourceApplyStatus.BLOCKED || application.activeAttemptsDeferred) {
				wakeups.cancel(WAKEUP_ID)
				return
			}
			val now = SystemClock.elapsedRealtime()
			val earliest = nextAttemptDeadlineMs(
				now,
				lastAttemptAtMs.takeUnless { it == Long.MIN_VALUE },
				plan.minimumAttemptIntervalMs,
				backoff.delayMs(plan.backoff),
			)
			wakeups.schedule(
				sourceWakeupRequest(WAKEUP_ID, source, earliest, attemptWindow(plan.minimumAttemptIntervalMs)),
			) {
				enqueueControl(physicalRegistration, WifiRuntimeInput.Attempt)
			}
		}

		// Initial acquisition is actor-owned, so a synchronous provider burst cannot crowd this
		// control action out of the bounded callback lane.
		scheduleAttempt()
		inputs.consume inputLoop@{ input ->
			try {
				when (input) {
				WifiRuntimeInput.Attempt -> {
					val execution = providerOperationMutex.withLock {
						val plan = synchronized(callbackLock) {
							currentDirectAttemptPlanLocked(inputs, physicalRegistration)
						} ?: return@withLock null
						if (!prerequisiteGate.allows(plan)) return@withLock null
						val requestAllowed = synchronized(callbackLock) {
							currentDirectAttemptPlanLocked(inputs, physicalRegistration) == plan &&
								acquisitionBudget.consumeRequest()
					}
						if (!requestAllowed) return@withLock null
						val attemptId = UUID.randomUUID().toString()
						lastAttemptAtMs = SystemClock.elapsedRealtime()
						WifiAttemptExecution(
							attemptId,
							runCatchingNonCancellation { backend.requestScan() }
								.getOrDefault(WifiRequestOutcome.PROVIDER_FAILED),
						)
					}
					if (execution == null) {
						wakeups.cancel(WAKEUP_ID)
						return@inputLoop
					}
					when (execution.outcome) {
						WifiRequestOutcome.ACCEPTED -> {
							pendingAttemptId = execution.attemptId
							backoff = backoff.succeeded()
						}
						WifiRequestOutcome.THROTTLED -> {
							backoff = backoff.failed()
						}
						WifiRequestOutcome.PERMISSION_BLOCKED -> {
							backoff = backoff.failed()
						}
						WifiRequestOutcome.PROVIDER_FAILED -> {
							backoff = backoff.failed()
						}
					}
					scheduleAttempt()
				}
				is WifiRuntimeInput.Reconfigured -> {
					val hadDirectCapture = budgetRegistration.purposeEligibilityMask and
						SourceBrokerPurpose.MASK_SESSION_CAPTURE != 0L
					val hasDirectCapture = input.registration.purposeEligibilityMask and
						SourceBrokerPurpose.MASK_SESSION_CAPTURE != 0L
					if (hadDirectCapture != hasDirectCapture) {
						acquisitionBudget = DirectAcquisitionBudget(
							maximumAttempts = MAX_ACTIVE_ATTEMPTS_PER_REGISTRATION,
							directCaptureRequested = hasDirectCapture,
						)
					}
					budgetRegistration = input.registration
					scheduleAttempt()
				}
				is WifiRuntimeInput.Backend -> when (val event = input.event) {
					WifiBackendEvent.IdleStateChanged -> scheduleAttempt()
					is WifiBackendEvent.Results -> {
						val linkedAttempt = pendingAttemptId
						if (event.resultsUpdated != false && event.snapshot != null) {
							val admitted = admitSnapshot(
								event.snapshot, input.registration, input.sink, input.plan,
								input.callbackSequence, linkedAttempt,
								replayGate,
								event.receivedElapsedRealtimeNanos, event.receivedWallTimeMs,
							)
							if (admitted != null) {
								replayGate.record(admitted)
								acquisitionBudget.markQualifiedEvidence()
								wakeups.cancel(WAKEUP_ID)
							}
						}
						pendingAttemptId = null
					}
				}
				}
			} catch (cancelled: kotlinx.coroutines.CancellationException) {
				throw cancelled
			} catch (_: Exception) {
				if (input is WifiRuntimeInput.Backend) metrics.recordFailure(input.callbackSequence)
			} finally {
				if (input is WifiRuntimeInput.Backend) {
					processedCallbackSequence.value = maxOf(
						processedCallbackSequence.value,
						input.callbackSequence,
					)
				}
			}
		}
	}

	// This bounded state machine owns retry-time rebuild, typed cutoff salvage, and exact settlement.
	@Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
	private suspend fun admitSnapshot(
		snapshot: WifiBackendSnapshot,
		registration: SourceRegistration,
		sink: SourceEventSink,
		plan: WifiPlan,
		callbackSequence: Long,
		linkedAttemptId: String?,
		replayGate: BoundedReplayIdentityGate,
		receivedElapsedNanos: Long = SystemClock.elapsedRealtimeNanos(),
		receivedWallTimeMs: Long = System.currentTimeMillis(),
	): String? {
		var durableCutoffElapsedNanos: Long? = null
		var cutoffRebuildUsed = false
		while (true) {
			var attemptedIdentity: SourceDeliveryIdentity? = null
			var prerequisiteRejected = false
			val handoff = retryWifiDeliveryAdmission(
				admit = admissionAttempt@{
					if (!prerequisiteGate.allows(plan)) {
						prerequisiteRejected = true
						return@admissionAttempt null
					}
					val effectiveCutoff = listOfNotNull(
						cutoffElapsedNanos,
						durableCutoffElapsedNanos,
					).minOrNull()
					val prepared = prepareWifiSnapshotDelivery(
						snapshot = snapshot,
						registration = registration,
						plan = plan,
						linkedAttemptId = linkedAttemptId,
						receivedElapsedNanos = receivedElapsedNanos,
						receivedWallTimeMs = receivedWallTimeMs,
						cutoffElapsedRealtimeNanos = effectiveCutoff,
					) ?: return@admissionAttempt null
					if (!replayGate.shouldAdmit(prepared.identity.value)) return@admissionAttempt null
					attemptedIdentity = prepared.identity
					sink.admit(prepared.candidate)
				},
				retryDelaysMs = if (cutoffRebuildUsed) {
					longArrayOf()
				} else {
					WIFI_ADMISSION_RETRY_DELAYS_MS
				},
			)
			when (handoff) {
				is SourceDeliveryAdmissionHandoff.Durable -> {
					val ordinal = handoff.admissionOrdinals.singleOrNull() ?: run {
						metrics.recordFailure(callbackSequence)
						return null
					}
					recordDurableAdmission(callbackSequence, ordinal)
					return requireNotNull(attemptedIdentity).value
				}
				is SourceDeliveryAdmissionHandoff.Duplicate -> {
					val ordinal = handoff.existingAdmissionOrdinals.singleOrNull() ?: run {
						metrics.recordFailure(callbackSequence)
						return null
					}
					recordDurableAdmission(callbackSequence, ordinal)
					return requireNotNull(attemptedIdentity).value
				}
				is SourceDeliveryAdmissionHandoff.SessionCutoff -> {
					if (cutoffRebuildUsed) {
						metrics.recordFailure(callbackSequence)
						return null
					}
					// Room owns the stop linearization. Rebuild the original immutable provider
					// snapshot from its exact cutoff even if shutdown has not published locally yet.
					durableCutoffElapsedNanos = listOfNotNull(
						durableCutoffElapsedNanos,
						handoff.cutoffElapsedRealtimeNanos,
						cutoffElapsedNanos,
					).minOrNull()
					cutoffRebuildUsed = true
				}
				is SourceDeliveryAdmissionHandoff.TerminalFailure -> {
					metrics.recordFailure(callbackSequence)
					return null
				}
				is SourceDeliveryAdmissionHandoff.RetryableFailure -> {
					// The finite in-process retry budget is exhausted. Recovery is owned by a later
					// lifecycle reconciliation, not a power-hot polling loop in this callback actor.
					metrics.recordFailure(callbackSequence)
					return null
				}
				null -> {
					if (prerequisiteRejected) metrics.recordFailure(callbackSequence)
					return null
				}
			}
		}
	}

	private fun prepareWifiSnapshotDelivery(
		snapshot: WifiBackendSnapshot,
		registration: SourceRegistration,
		plan: WifiPlan,
		linkedAttemptId: String?,
		receivedElapsedNanos: Long,
		receivedWallTimeMs: Long,
		cutoffElapsedRealtimeNanos: Long?,
	): PreparedWifiDelivery? {
		val eligibleAccessPoints = qualifiedWifiProviderObservations(
			accessPoints = snapshot.accessPoints,
			receivedElapsedRealtimeNanos = receivedElapsedNanos,
			maximumAcceptableResultAgeMs = plan.maximumAcceptableResultAgeMs,
			providerAcceptedElapsedRealtimeNanos =
				registration.providerAcceptedElapsedRealtimeNanos,
			authorizationEffectiveElapsedRealtimeNanos =
				registration.authorization.effectiveElapsedRealtimeNanos,
			cutoffElapsedRealtimeNanos = cutoffElapsedRealtimeNanos,
		)
		if (eligibleAccessPoints.isEmpty()) {
			// RESULTS_UPDATED confirms a delivery, not a durable transition to empty coverage.
			// Until the provider exposes a cross-process transition identity, receipt time would
			// repeatedly turn unchanged emptiness into fabricated new product evidence.
			return null
		}
		val normalized = eligibleAccessPoints.map(WifiBackendAccessPoint::toMinimizedEvidence).sortedWith(
			compareBy<WifiAccessPointEvidence>(
				WifiAccessPointEvidence::frequencyMhz,
				WifiAccessPointEvidence::signalLevelDbm,
				WifiAccessPointEvidence::providerTimestampNanos,
			),
		)
		val observed = requireNotNull(
			normalized.mapNotNull(WifiAccessPointEvidence::providerTimestampNanos).maxOrNull(),
		)
		val observedIntervalStart = requireNotNull(
			normalized.mapNotNull(WifiAccessPointEvidence::providerTimestampNanos).minOrNull(),
		)
		val ageMs = ((receivedElapsedNanos - observed).coerceAtLeast(0L) / NANOS_PER_MILLISECOND)
		val snapshotIdentity = wifiProviderDeliveryIdentity(
			clockDomainId = registration.state.clockDomainId,
			accessPoints = normalized,
		)
		val attribution = when {
			linkedAttemptId != null -> PlanAttribution.LINKED_ATTEMPT
			else -> PlanAttribution.RECEIVE_TIME_ONLY
		}
		val wallTime = receivedWallTimeMs - ageMs
		val delivery = wifiDelivery(
			registration, observedIntervalStart, observed, receivedElapsedNanos,
			wallTime,
			if (linkedAttemptId != null) registration.state.appliedRevision else null,
			attribution,
			SourceQuality(),
			WifiResultSnapshotPayload(
				accessPoints = normalized,
				platformTimestampMs = observed / NANOS_PER_MILLISECOND,
				// Receipt-relative age is useful only for the live freshness decision above. Keeping
				// it out of durable bytes makes an exact provider replay semantically identical.
				resultAgeMs = null,
			),
			snapshotIdentity,
		)
		return PreparedWifiDelivery(snapshotIdentity, delivery)
	}

	private fun wifiDelivery(
		registration: SourceRegistration,
		observedIntervalStartNanos: Long,
		observedNanos: Long,
		receivedNanos: Long,
		wallTimeMs: Long,
		configRevision: Long?,
		attribution: PlanAttribution,
		quality: SourceQuality,
		payload: com.adsamcik.tracker.tracker.source.model.SourcePayload,
		deliveryIdentity: SourceDeliveryIdentity,
	): SourceDeliveryCandidate {
		val candidate = SourceEvidenceCandidate(
			// Stable replay identity belongs to SourceDeliveryCandidate. Keeping this null prevents the
			// legacy single-evidence path from conflating raw identity with runtime attribution.
			providerDedupKey = null,
			logicalTrackingId = null, serviceRunId = null, source = source,
			sourceInstanceId = SourceInstanceId(registration.state.sourceInstanceId),
			registrationGeneration = registration.state.registrationGeneration,
			physicalConfigurationFingerprint = registration.physicalConfigurationFingerprint,
			authorizationRevision = registration.authorization.authorizationRevision,
			registrationPurposeEligibilityMask = registration.purposeEligibilityMask,
			registrationEligibilityFingerprint = registration.eligibilityFingerprint,
			// The delivery transaction allocates a sequence only for a new durable observation.
			sourceSequence = 0L, configRevision = configRevision,
			planAttribution = attribution, clockDomainId = registration.state.clockDomainId,
			observedElapsedRealtimeNanos = observedNanos.coerceAtLeast(0L),
			receivedElapsedRealtimeNanos = receivedNanos.coerceAtLeast(0L),
			wallTimeMs = wallTimeMs.coerceAtLeast(0L), wallTimeUncertaintyMs = 1L,
			capturedCollectedDataEpoch = registration.state.collectedDataEpoch,
			acquiredAtMs = wallTimeMs.coerceAtLeast(0L), quality = quality,
			payloadVersion = WIFI_PAYLOAD_VERSION, payload = payload,
		)
		return SourceDeliveryCandidate(
			identity = deliveryIdentity,
			units = listOf(
				SourceDeliveryUnit(
					unitIndex = 0,
					evidence = candidate,
					observedIntervalStartElapsedRealtimeNanos = observedIntervalStartNanos,
				),
			),
		)
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

	private fun currentDirectAttemptPlanLocked(
		inputs: WifiCallbackLane<WifiRuntimeInput>,
		physicalRegistration: SourceRegistration,
	): WifiPlan? {
		val activeRegistration = registration ?: return null
		val plan = currentPlan ?: return null
		return plan.takeIf {
			accepting && queue === inputs && cutoffElapsedNanos == null && retirementIntent == null &&
				physicalRegistration.samePhysicalRegistration(activeRegistration) && plan.enabled &&
				plan.mode == WifiMode.ACTIVE_ATTEMPTS && activeRegistration.purposeEligibilityMask and
				SourceBrokerPurpose.MASK_SESSION_CAPTURE != 0L
		}
	}

	private fun capabilitiesNow(): SourceCapabilities {
		return wifiCapabilities(deviceStateProvider.wifi())
	}

	private fun clearActiveState() {
		synchronized(callbackLock) {
			accepting = false
			refreshAdmissionFenced = false
			registration = null
			queue = null
			retirementIntent = null
		}
		actor = null
		cutoffElapsedNanos = null
		currentPlan = null
		currentSink = null
		ownerClaim = null
	}

	private fun unavailableAck(cutoff: SessionCutoff?) = SourceStopAck(
		source, SourceInstanceId("unavailable-wifi"), 0L, currentPlan?.revision,
		callbackSequence, null, null, 0L, null, null,
		RegistrationRemovalOutcome.NOT_REGISTERED, ProviderFlushOutcome.NOT_REQUESTED,
		ProviderCoverage.PROVIDER_COMPLETENESS_UNOBSERVABLE, true,
		if (cutoff == null) SourceStopStatus.COMPLETE else SourceStopStatus.PROVIDER_FAILED,
	).withSessionMembership(ownerClaim)

	private fun attemptWindow(intervalMs: Long) = (intervalMs / 4L).coerceIn(0L, MAX_COALESCE_WINDOW_MS)

	private fun WifiBackendEvent.hasPotentialProductEvidence(): Boolean =
		this is WifiBackendEvent.Results && resultsUpdated == true &&
			snapshot?.accessPoints?.isNotEmpty() == true

	private sealed interface WifiRuntimeInput {
		data object Attempt : WifiRuntimeInput
		data class Reconfigured(val registration: SourceRegistration) : WifiRuntimeInput
		data class Backend(
			val event: WifiBackendEvent,
			val callbackSequence: Long,
			val registration: SourceRegistration,
			val plan: WifiPlan,
			val sink: SourceEventSink,
		) : WifiRuntimeInput
	}

	private data class WifiAttemptExecution(
		val attemptId: String,
		val outcome: WifiRequestOutcome,
	)

	private data class PreparedWifiDelivery(
		val identity: SourceDeliveryIdentity,
		val candidate: SourceDeliveryCandidate,
	)

	private companion object {
		const val WIFI_PAYLOAD_VERSION = 2
		const val WAKEUP_ID = "wifi-active-attempt"
		const val DEFAULT_DRAIN_TIMEOUT_MS = 3_000L
		const val NANOS_PER_MILLISECOND = 1_000_000L
		const val MAX_COALESCE_WINDOW_MS = 5_000L
		const val MAX_ACTIVE_ATTEMPTS_PER_REGISTRATION = 1
	}
}

private enum class WifiProviderRetirement { NOT_DURABLE, PENDING, COMPLETE }

private data class WifiProviderRetirementIntent(
	val registration: SourceRegistration,
	val reason: String,
	val retiredAtMs: Long,
	val retiredElapsedRealtimeNanos: Long,
	var token: SourceRegistrationRetirementToken? = null,
)

/**
 * Reports what this runtime can actually guarantee. Broadcasts and the single bounded active
 * attempt are opportunistic; neither establishes a wake-reliable sampling interval.
 */
internal fun wifiCapabilities(state: WifiDeviceState): SourceCapabilities {
	val available = state.wifiFeatureAvailable && state.fineLocationPermission &&
		state.locationServicesEnabled
	val reasons = buildSet {
		if (!state.wifiFeatureAvailable) add(SourceDegradedReason.HARDWARE_UNAVAILABLE)
		if (!state.fineLocationPermission) add(SourceDegradedReason.PERMISSION_MISSING)
		if (!state.locationServicesEnabled) add(SourceDegradedReason.PROVIDER_UNAVAILABLE)
	}
	return SourceCapabilities(
		available = available,
		batchingSupported = false,
		flushSupported = false,
		maximumBatchSize = null,
		minimumDelayMs = null,
		degradedReasons = reasons,
	)
}

internal enum class WifiLaneOffer { ACCEPTED, CAPACITY_EXHAUSTED, CLOSED }

/** Fixed-memory Wi-Fi FIFO. Capacity loss is surfaced through the runtime's unresolved range. */
internal class WifiCallbackLane<T>(
	capacity: Int = WIFI_CALLBACK_BUFFER_CAPACITY,
) {
	private val channel = Channel<T>(capacity)

	init {
		require(capacity > 0)
	}

	fun offer(input: T): WifiLaneOffer {
		val result = channel.trySend(input)
		return when {
			result.isSuccess -> WifiLaneOffer.ACCEPTED
			result.isClosed -> WifiLaneOffer.CLOSED
			else -> WifiLaneOffer.CAPACITY_EXHAUSTED
		}
	}

	fun close() = channel.close()

	suspend fun consume(action: suspend (T) -> Unit) {
		for (input in channel) action(input)
	}
}

/** Re-snapshots every prerequisite at each callback and persistence boundary. */
internal class WifiCallbackPrerequisiteGate(
	private val stateSnapshot: () -> WifiDeviceState,
) {
	fun application(plan: WifiPlan): WifiPlanApplication? = runCatchingNonCancellation {
		WifiPrerequisiteEvaluator.evaluate(plan, stateSnapshot())
	}.getOrNull()

	fun allows(plan: WifiPlan): Boolean = application(plan)
		?.let { it.status != SourceApplyStatus.BLOCKED }
		?: false
}

/** Finite retry for the atomic provider-delivery path; never represented as a wake schedule. */
internal suspend fun retryWifiDeliveryAdmission(
	admit: suspend () -> SourceDeliveryAdmissionHandoff?,
	retryDelaysMs: LongArray = WIFI_ADMISSION_RETRY_DELAYS_MS,
	waitBeforeRetry: suspend (Long) -> Unit = { delay(it) },
): SourceDeliveryAdmissionHandoff? {
	var retryIndex = 0
	while (true) {
		val handoff = admit() ?: return null
		if (handoff !is SourceDeliveryAdmissionHandoff.RetryableFailure) return handoff
		val retryDelayMs = retryDelaysMs.getOrNull(retryIndex++) ?: return handoff
		waitBeforeRetry(retryDelayMs)
	}
}

/** Keeps one callback unit inside its exact physical-registration, authorization, and stop bounds. */
internal fun qualifiedWifiProviderObservations(
	accessPoints: List<WifiBackendAccessPoint>,
	receivedElapsedRealtimeNanos: Long,
	maximumAcceptableResultAgeMs: Long,
	providerAcceptedElapsedRealtimeNanos: Long?,
	authorizationEffectiveElapsedRealtimeNanos: Long,
	cutoffElapsedRealtimeNanos: Long?,
): List<WifiBackendAccessPoint> {
	val providerBoundary = providerAcceptedElapsedRealtimeNanos ?: return emptyList()
	val effectiveStart = maxOf(providerBoundary, authorizationEffectiveElapsedRealtimeNanos)
	return freshProviderObservations(
		accessPoints,
		receivedElapsedRealtimeNanos,
		maximumAcceptableResultAgeMs,
		WifiBackendAccessPoint::providerTimestampNanos,
	).filter { accessPoint ->
		val providerTimestamp = accessPoint.providerTimestampNanos ?: return@filter false
		providerTimestamp >= effectiveStart &&
			cutoffElapsedRealtimeNanos?.let { providerTimestamp <= it } != false
	}
}

/**
 * Process-independent identity for a qualified provider delivery. It uses only the boot clock
 * domain and minimized product evidence; raw BSSID and runtime registration identity are absent.
 */
internal fun wifiProviderDeliveryIdentity(
	clockDomainId: String,
	accessPoints: List<WifiAccessPointEvidence>,
): SourceDeliveryIdentity {
	require(clockDomainId.isNotBlank())
	require(accessPoints.isNotEmpty())
	require(accessPoints.all { it.identifierToken == WITHHELD_RADIO_IDENTIFIER_TOKEN })
	val canonical = buildString {
		append("wifi-v1|")
		append(clockDomainId.length)
		append(':')
		append(clockDomainId)
		append("|items:")
		accessPoints.sortedWith(
			compareBy<WifiAccessPointEvidence>(
				WifiAccessPointEvidence::frequencyMhz,
				WifiAccessPointEvidence::signalLevelDbm,
				WifiAccessPointEvidence::providerTimestampNanos,
			),
		).forEach { item ->
			append(item.frequencyMhz)
			append(':')
			append(item.signalLevelDbm)
			append(':')
			append(requireNotNull(item.providerTimestampNanos))
			append('|')
		}
	}
	return sourceDeliveryIdentity(canonical.toByteArray(Charsets.UTF_8))
}

private fun SourceRegistration.samePhysicalRegistration(other: SourceRegistration): Boolean =
	state.sourceInstanceId == other.state.sourceInstanceId &&
		state.registrationGeneration == other.state.registrationGeneration &&
		physicalConfigurationFingerprint == other.physicalConfigurationFingerprint

private const val WIFI_CALLBACK_BUFFER_CAPACITY = 64
private val WIFI_ADMISSION_RETRY_DELAYS_MS = longArrayOf(25L, 250L, 1_000L)

internal fun WifiBackendAccessPoint.toMinimizedEvidence() = WifiAccessPointEvidence(
	identifierToken = WITHHELD_RADIO_IDENTIFIER_TOKEN,
	frequencyMhz = frequencyMhz,
	signalLevelDbm = signalLevelDbm,
	providerTimestampNanos = requireNotNull(providerTimestampNanos),
)
