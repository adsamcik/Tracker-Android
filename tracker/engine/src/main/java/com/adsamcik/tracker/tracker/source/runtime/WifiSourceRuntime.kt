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
	private val callbackLock = Any()
	private var registration: SourceRegistration? = null
	private var currentPlan: WifiPlan? = null
	private var currentSink: SourceEventSink? = null
	private var queue: WifiCallbackLane<WifiRuntimeInput>? = null
	private var actor: Job? = null
	private var accepting = false
	private var callbackSequence = 0L
	private val processedCallbackSequence = MutableStateFlow(0L)
	private var cutoffElapsedNanos: Long? = null
	private var metrics = RuntimeAdmissionMetrics()
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
		val refreshed = runCatchingNonCancellation {
			registrations.refreshActiveAuthorization(
				source,
				activeRegistration,
				plan.revision,
				plan.physicalConfigurationFingerprint(),
				System.currentTimeMillis(),
			)
		}.getOrElse {
			return SourceApplyResult.Failed(
				appliedState(source, plan.revision, activeRegistration, SourceApplyStatus.FAILED,
					SystemClock.elapsedRealtimeNanos()),
				retryable = true,
			)
		} ?: return null
		if (!activeRegistration.samePhysicalRegistration(refreshed) || refreshed.requiresProviderAcceptance) return null
		val refreshedInPlace = synchronized(callbackLock) {
			val activeQueue = queue
			if (!accepting || activeQueue == null || registration !== activeRegistration) false else {
				// The marker and callback offers share this lock. Older queued callbacks retain their
				// immutable authorization; callbacks entering after it capture the refreshed vector.
				if (activeQueue.offer(WifiRuntimeInput.Reconfigured(refreshed)) != WifiLaneOffer.ACCEPTED) {
					false
				} else {
					registration = refreshed
					currentPlan = plan
					currentSink = sink
					true
				}
			}
		}
		if (!refreshedInPlace) return null
		// Lifecycle ownership follows the durable authorization attempt even though the provider key
		// remains unchanged. A legacy refresh intentionally invalidates any older claimed attempt.
		ownerClaim = claim
		val state = appliedState(
			source, plan.revision, refreshed, application.status, SystemClock.elapsedRealtimeNanos(),
		).copy(degradedReasons = application.reasons)
		return if (application.status == SourceApplyStatus.DEGRADED) {
			SourceApplyResult.Degraded(state)
		} else {
			SourceApplyResult.Applied(state)
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
		val accepted = try {
			registrations.markAccepted(nextRegistration, System.currentTimeMillis())
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
		actor = applicationScope.launch { consume(nextQueue, nextRegistration) }
		val state = appliedState(
			source, plan.revision, nextRegistration, application.status, SystemClock.elapsedRealtimeNanos(),
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
		cutoffElapsedNanos = cutoff?.elapsedRealtimeNanos
		wakeups.cancel(WAKEUP_ID)
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
			currentSink = null
			queue = null
		}
		actor = null
	}

	private fun onBackendEvent(callbackRegistration: SourceRegistration, event: WifiBackendEvent) {
		synchronized(callbackLock) {
			val activeRegistration = registration ?: return
			val activePlan = currentPlan ?: return
			val activeSink = currentSink ?: return
			if (!accepting || !callbackRegistration.samePhysicalRegistration(activeRegistration) ||
				!prerequisiteGate.allows(activePlan)
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
			val plan = currentPlan ?: return
			if (plan.mode != WifiMode.ACTIVE_ATTEMPTS || !acquisitionBudget.canRequest) {
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
					val plan = currentPlan ?: return@inputLoop
					if (!prerequisiteGate.allows(plan) || !acquisitionBudget.consumeRequest()) {
						wakeups.cancel(WAKEUP_ID)
						return@inputLoop
					}
					val attemptId = UUID.randomUUID().toString()
					val now = SystemClock.elapsedRealtime()
					lastAttemptAtMs = now
					val requestOutcome = runCatchingNonCancellation { backend.requestScan() }
						.getOrDefault(WifiRequestOutcome.PROVIDER_FAILED)
					when (requestOutcome) {
						WifiRequestOutcome.ACCEPTED -> {
							pendingAttemptId = attemptId
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
								replayGate, event.resultsUpdated == true,
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

	private suspend fun admitSnapshot(
		snapshot: WifiBackendSnapshot,
		registration: SourceRegistration,
		sink: SourceEventSink,
		plan: WifiPlan,
		callbackSequence: Long,
		linkedAttemptId: String?,
		replayGate: BoundedReplayIdentityGate,
		confirmedFreshEmptyCallback: Boolean,
		receivedElapsedNanos: Long = SystemClock.elapsedRealtimeNanos(),
		receivedWallTimeMs: Long = System.currentTimeMillis(),
	): String? {
		val eligibleAccessPoints = freshProviderObservations(
			snapshot.accessPoints,
			receivedElapsedNanos,
			plan.maximumAcceptableResultAgeMs,
			WifiBackendAccessPoint::providerTimestampNanos,
		)
		val emptyCoverage = shouldAdmitCoverageOnly(
			providerItemCount = snapshot.accessPoints.size,
			eligibleItemCount = eligibleAccessPoints.size,
			providerDeliveryConfirmedFresh = confirmedFreshEmptyCallback,
		)
		if (eligibleAccessPoints.isEmpty() && !emptyCoverage) return null
		val normalized = eligibleAccessPoints.map(WifiBackendAccessPoint::toMinimizedEvidence).sortedWith(
			compareBy<WifiAccessPointEvidence>(
				WifiAccessPointEvidence::frequencyMhz,
				WifiAccessPointEvidence::signalLevelDbm,
				WifiAccessPointEvidence::providerTimestampNanos,
			),
		)
		val observed = normalized.mapNotNull(WifiAccessPointEvidence::providerTimestampNanos)
			.maxOrNull() ?: receivedElapsedNanos
		val observedIntervalStart = normalized.mapNotNull(WifiAccessPointEvidence::providerTimestampNanos)
			.minOrNull() ?: observed
		val ageMs = ((receivedElapsedNanos - observed).coerceAtLeast(0L) / NANOS_PER_MILLISECOND)
		val snapshotIdentity = wifiProviderDeliveryIdentity(
			clockDomainId = registration.state.clockDomainId,
			accessPoints = normalized,
			emptyCoverage = emptyCoverage,
			receivedElapsedRealtimeNanos = receivedElapsedNanos,
		)
		if (!replayGate.shouldAdmit(snapshotIdentity.value)) return null
		val attribution = when {
			linkedAttemptId != null -> PlanAttribution.LINKED_ATTEMPT
			else -> PlanAttribution.RECEIVE_TIME_ONLY
		}
		val wallTime = receivedWallTimeMs - ageMs
		admit(
			registration, sink, plan, callbackSequence, observedIntervalStart, observed,
			receivedElapsedNanos, wallTime,
			if (linkedAttemptId != null) registration.state.appliedRevision else null,
			attribution,
			SourceQuality(),
			WifiResultSnapshotPayload(
				accessPoints = normalized,
				platformTimestampMs = if (emptyCoverage) null else observed / NANOS_PER_MILLISECOND,
				resultAgeMs = ageMs,
			),
			snapshotIdentity,
		).takeIf { it } ?: return null
		return snapshotIdentity.value
	}

	private suspend fun admit(
		registration: SourceRegistration,
		sink: SourceEventSink,
		plan: WifiPlan,
		callbackSequence: Long,
		observedIntervalStartNanos: Long,
		observedNanos: Long,
		receivedNanos: Long,
		wallTimeMs: Long,
		configRevision: Long?,
		attribution: PlanAttribution,
		quality: SourceQuality,
		payload: com.adsamcik.tracker.tracker.source.model.SourcePayload,
		deliveryIdentity: SourceDeliveryIdentity,
	): Boolean {
		if (cutoffElapsedNanos?.let { observedNanos > it } == true) return false
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
		val delivery = SourceDeliveryCandidate(
			identity = deliveryIdentity,
			units = listOf(
				SourceDeliveryUnit(
					unitIndex = 0,
					evidence = candidate,
					observedIntervalStartElapsedRealtimeNanos = observedIntervalStartNanos,
				),
			),
		)
		when (val handoff = retryWifiDeliveryAdmission(
			admit = { sink.admit(delivery) },
		)) {
			is SourceDeliveryAdmissionHandoff.Durable -> {
				val ordinal = handoff.admissionOrdinals.singleOrNull() ?: run {
					metrics.recordFailure(callbackSequence)
					return false
				}
				metrics.recordDurable(callbackSequence, ordinal)
				return true
			}
			is SourceDeliveryAdmissionHandoff.Duplicate -> {
				val ordinal = handoff.existingAdmissionOrdinals.singleOrNull() ?: run {
					metrics.recordFailure(callbackSequence)
					return false
				}
				metrics.recordDurable(callbackSequence, ordinal)
				return true
			}
			is SourceDeliveryAdmissionHandoff.TerminalFailure -> {
				metrics.recordFailure(callbackSequence)
				return false
			}
			is SourceDeliveryAdmissionHandoff.RetryableFailure -> {
				// The finite in-process retry budget is exhausted. Recovery is owned by a later
				// lifecycle reconciliation, not a power-hot polling loop in this callback actor.
				metrics.recordFailure(callbackSequence)
				return false
			}
		}
	}

	private fun capabilitiesNow(): SourceCapabilities {
		return wifiCapabilities(deviceStateProvider.wifi())
	}

	private fun clearActiveState() {
		synchronized(callbackLock) {
			accepting = false
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
	admit: suspend () -> SourceDeliveryAdmissionHandoff,
): SourceDeliveryAdmissionHandoff {
	var retryIndex = 0
	while (true) {
		val handoff = admit()
		if (handoff !is SourceDeliveryAdmissionHandoff.RetryableFailure) return handoff
		if (retryIndex >= WIFI_ADMISSION_RETRY_DELAYS_MS.size) return handoff
		delay(WIFI_ADMISSION_RETRY_DELAYS_MS[retryIndex++])
	}
}

/**
 * Process-independent identity for a qualified provider delivery. It uses only the boot clock
 * domain and minimized product evidence; raw BSSID and runtime registration identity are absent.
 */
internal fun wifiProviderDeliveryIdentity(
	clockDomainId: String,
	accessPoints: List<WifiAccessPointEvidence>,
	emptyCoverage: Boolean,
	receivedElapsedRealtimeNanos: Long,
): SourceDeliveryIdentity {
	require(clockDomainId.isNotBlank())
	val canonical = if (emptyCoverage) {
		require(accessPoints.isEmpty())
		require(receivedElapsedRealtimeNanos >= 0L)
		"wifi-v1|${clockDomainId.length}:$clockDomainId|empty:$receivedElapsedRealtimeNanos"
	} else {
		require(accessPoints.isNotEmpty())
		buildString {
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
