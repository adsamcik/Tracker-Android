package com.adsamcik.tracker.tracker.source.runtime

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener2
import android.hardware.SensorManager
import android.os.SystemClock
import com.adsamcik.tracker.shared.base.di.ApplicationScope
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceRuntimeStateEntity
import com.adsamcik.tracker.shared.base.extension.hasActivityPermission
import com.adsamcik.tracker.tracker.source.ingress.STEP_BOUNDARY_KIND_PAYLOAD_VERSION
import com.adsamcik.tracker.tracker.source.runCatchingNonCancellation
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.SourceApplyStatus
import com.adsamcik.tracker.tracker.source.model.SourceEvidenceCandidate
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourceQuality
import com.adsamcik.tracker.tracker.source.model.SourceQualityFlag
import com.adsamcik.tracker.tracker.source.model.StepsPlan
import com.adsamcik.tracker.tracker.source.model.physicalConfigurationFingerprint
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@Singleton
class StepSourceRuntime @Inject constructor(
	@ApplicationContext private val context: Context,
	@ApplicationScope private val applicationScope: CoroutineScope,
	private val registrations: SourceRegistrationRepository,
) : SourceRuntime<StepsPlan> {
	override val source: SourceKind = SourceKind.STEPS
	private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
	private val sensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
	private val _capabilities = MutableStateFlow(sensor.toCapabilities())
	override val capabilities: StateFlow<SourceCapabilities> = _capabilities

	private val lifecycleMutex = Mutex()
	private val callbackLock = Any()
	private val callbackGate = StepCallbackGenerationGate()
	private var registration: SourceRegistration? = null
	private var callbackToken: StepCallbackToken? = null
	private var listener: StepRegistrationListener? = null
	private var overflowPaused = false
	private var overflowPauseRemovalComplete = true
	private var providerRetirement: StepProviderRetirementIntent? = null
	private var terminalSettlement: StepTerminalSettlementIntent? = null
	private var terminalStopAck: SourceStopAck? = null
	private var currentPlan: StepsPlan? = null
	private var currentSink: SourceEventSink? = null
	private var queue: StepCallbackLane? = null
	private var callbackAttributionTimeline: StepObservedAuthorizationTimeline? = null
	private var pendingAuthorizationRefresh: PendingStepAuthorizationRefresh? = null
	private var actor: Job? = null
	@Volatile private var overflowRecoveryJob: Job? = null
	private var acceptingCallbacks = false
	private var callbackEntrySequence = 0L
	@Volatile private var cutoffElapsedNanos: Long? = null
	@Volatile private var admissionDeadlineElapsedNanos: Long? = null
	private var lastQueuedProviderElapsedNanos: Long? = null
	private var flushCompletion: CompletableDeferred<Unit>? = null
	private var batchingEnabled = false
	private var metrics = RuntimeAdmissionMetrics()
	private val processedCallbackSequence = MutableStateFlow(0L)
	private val recentEvidence = RecentStepEvidenceTracker(SystemClock::elapsedRealtimeNanos)

	override suspend fun start(plan: StepsPlan, sink: SourceEventSink): SourceStartResult = lifecycleMutex.withLock {
		if (providerRetirement != null) {
			retryPendingRetirementLocked()
			if (providerRetirement != null) {
				return@withLock SourceStartResult.Failed(
					appliedState(source, plan.revision, null, SourceApplyStatus.FAILED,
						SystemClock.elapsedRealtimeNanos()),
					retryable = true,
				)
			}
		}
		require(currentPlan == null) { "Step source is already started" }
		startLocked(plan, sink)
	}

	override suspend fun reconfigure(plan: StepsPlan, sink: SourceEventSink): SourceApplyResult = lifecycleMutex.withLock {
		refreshCompatibleLocked(plan, sink)?.let { refreshed ->
			return@withLock refreshed
		}
		if (currentPlan != null) {
			val previous = shutdownLocked(null)
			if (!previous.appDrainComplete ||
				previous.registrationRemovalOutcome != RegistrationRemovalOutcome.REMOVED
			) {
				return@withLock SourceApplyResult.Failed(
					appliedState(source, plan.revision, null, SourceApplyStatus.FAILED, SystemClock.elapsedRealtimeNanos()),
					retryable = true,
				)
			}
		}
		if (!plan.enabled) {
			currentPlan = null
			currentSink = sink
			return@withLock SourceApplyResult.Applied(
				appliedState(source, plan.revision, null, SourceApplyStatus.APPLIED, SystemClock.elapsedRealtimeNanos()),
			)
		}
		when (val result = startLocked(plan, sink)) {
			is SourceStartResult.Started -> SourceApplyResult.Applied(result.applied)
			is SourceStartResult.Degraded -> SourceApplyResult.Degraded(result.applied)
			is SourceStartResult.Blocked -> SourceApplyResult.Failed(result.applied, retryable = false)
			is SourceStartResult.Failed -> SourceApplyResult.Failed(result.applied, result.retryable)
		}
	}

	override suspend fun quiesce(cutoff: SessionCutoff): SourceStopAck = lifecycleMutex.withLock {
		terminalStopAck ?: shutdownLocked(cutoff)
	}

	/**
	 * Refreshes broker authorization without changing the compatible physical registration. A null
	 * result means the caller must perform a normal stop/restart for a different physical plan.
	 */
	internal suspend fun refreshCompatible(
		plan: StepsPlan,
		sink: SourceEventSink,
	): SourceApplyResult? = lifecycleMutex.withLock { refreshCompatibleLocked(plan, sink) }

	/** Drains callbacks through a session cutoff while another durable consumer keeps Steps alive. */
	internal suspend fun sharedCutoff(cutoff: SessionCutoff): SourceStopAck = lifecycleMutex.withLock {
		sharedCutoffLocked(cutoff)
	}

	internal fun hasRecentControlSteps(): Boolean {
		val active = synchronized(callbackLock) {
			registration?.takeIf { acceptingCallbacks }
		} ?: return false
		return recentEvidence.hasRecent(active, RECENT_CONTROL_STEP_WINDOW_NANOS)
	}

	override suspend fun close() = lifecycleMutex.withLock {
		if (currentPlan != null || providerRetirement != null) shutdownLocked(null)
	}

	private suspend fun refreshCompatibleLocked(
		plan: StepsPlan,
		sink: SourceEventSink,
	): SourceApplyResult? {
		val activePlan = currentPlan ?: return null
		val activeRegistration = registration ?: return null
		val activeSink = currentSink ?: return null
		if (!plan.enabled ||
			activePlan.physicalConfigurationFingerprint() != plan.physicalConfigurationFingerprint()
		) return null
		val priorAttribution = StepCallbackAttribution(activeRegistration, activeSink)
		val pendingRefresh = synchronized(callbackLock) {
			if (!acceptingCallbacks || pendingAuthorizationRefresh != null) return null
			PendingStepAuthorizationRefresh(
				effectiveElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
			).also { pendingAuthorizationRefresh = it }
		}
		var refreshCompleted = false
		try {
			val refreshed = runCatchingNonCancellation {
				registrations.refreshActiveAuthorization(
					source,
					activeRegistration,
					plan.revision,
					plan.physicalConfigurationFingerprint(),
					System.currentTimeMillis(),
					pendingRefresh.effectiveElapsedRealtimeNanos,
				)
			}.getOrElse {
				return SourceApplyResult.Failed(
					appliedState(source, plan.revision, activeRegistration, SourceApplyStatus.FAILED,
						SystemClock.elapsedRealtimeNanos()),
					retryable = true,
				)
			} ?: return null
			val compatible = refreshed.state.sourceInstanceId == activeRegistration.state.sourceInstanceId &&
				refreshed.state.registrationGeneration == activeRegistration.state.registrationGeneration &&
				refreshed.physicalConfigurationFingerprint == activeRegistration.physicalConfigurationFingerprint &&
				!refreshed.requiresProviderAcceptance
			val stillAccepting = synchronized(callbackLock) {
				if (compatible) {
					// The latch closes the authorization-refresh/timeline race at this boundary.
					callbackAttributionTimeline?.refresh(refreshed, sink)
					registration = refreshed
					currentSink = sink
					pendingRefresh.complete(StepCallbackAttribution(refreshed, sink))
				} else {
					pendingRefresh.complete(priorAttribution)
				}
				if (pendingAuthorizationRefresh === pendingRefresh) pendingAuthorizationRefresh = null
				refreshCompleted = true
				compatible && acceptingCallbacks && callbackAttributionTimeline != null
			}
			if (!compatible || !stillAccepting) return null
			currentPlan = plan
			return SourceApplyResult.Applied(
				appliedState(source, plan.revision, refreshed, SourceApplyStatus.APPLIED,
					SystemClock.elapsedRealtimeNanos()),
			)
		} finally {
			if (!refreshCompleted) synchronized(callbackLock) {
				pendingRefresh.complete(priorAttribution)
				if (pendingAuthorizationRefresh === pendingRefresh) pendingAuthorizationRefresh = null
			}
		}
	}

	private suspend fun sharedCutoffLocked(cutoff: SessionCutoff): SourceStopAck {
		val activeRegistration = registration ?: return unavailableAck(cutoff)
		val activeListener = listener
		val activeToken = callbackToken
		val flushOutcome = if (activeListener != null && activeToken != null) {
			flushProvider(cutoff, activeListener, activeToken)
		} else {
			ProviderFlushOutcome.NOT_REQUESTED
		}
		val (barrier, overflowSequence) = synchronized(callbackLock) {
			callbackEntrySequence to queue?.overflowSequence
		}
		val remainingMs = ((cutoff.deadlineElapsedRealtimeNanos - SystemClock.elapsedRealtimeNanos()) /
			NANOS_PER_MILLISECOND).coerceAtLeast(1L)
		val drainComplete = barrier <= processedCallbackSequence.value ||
			withTimeoutOrNull(remainingMs) {
				processedCallbackSequence.first { processed -> processed >= barrier }
				true
			} == true
		if (!drainComplete) {
			unaccountedStepDrainRanges(processedCallbackSequence.value, barrier, overflowSequence)
				.forEach { range -> metrics.recordFailure(range.first, range.last, RuntimeGapClassification.DRAIN_TIMED_OUT) }
		}
		val admission = metrics.snapshot()
		return SourceStopAck(
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
			registrationRemovalOutcome = RegistrationRemovalOutcome.NOT_REGISTERED,
			providerFlushOutcome = flushOutcome,
			providerCoverage = sensorProviderCoverage(batchingEnabled, flushOutcome),
			appDrainComplete = drainComplete,
			status = if (drainComplete) SourceStopStatus.COMPLETE else SourceStopStatus.TIMED_OUT,
		)
	}

	private suspend fun startLocked(plan: StepsPlan, sink: SourceEventSink): SourceStartResult {
		if (!plan.enabled) {
			return SourceStartResult.Started(
				appliedState(source, plan.revision, null, SourceApplyStatus.APPLIED, SystemClock.elapsedRealtimeNanos()),
			)
		}
		val stepSensor = sensor ?: return SourceStartResult.Blocked(
			appliedState(source, plan.revision, null, SourceApplyStatus.BLOCKED, SystemClock.elapsedRealtimeNanos()),
		)
		val nextRegistration = runCatchingNonCancellation {
			registrations.begin(source, plan.revision, plan.physicalConfigurationFingerprint(), System.currentTimeMillis())
		}.getOrElse {
			return SourceStartResult.Failed(
				appliedState(source, plan.revision, null, SourceApplyStatus.FAILED, SystemClock.elapsedRealtimeNanos()),
				retryable = true,
			)
		}
		terminalStopAck = null
		val baselineBoundary = StepBaselineBoundary(nextRegistration.state.registrationGeneration)
		val saved = registrations.loadRuntimeState(nextRegistration)
		val recovery = recoverStepRuntimeState(
			saved = saved,
			currentRegistrationGeneration = nextRegistration.state.registrationGeneration,
			reusedPhysicalRegistration = nextRegistration.predecessorState
				?.registrationGeneration == nextRegistration.state.registrationGeneration,
		)
		// A process boundary can miss cumulative callbacks even when Android retained the same
		// physical generation. Never stitch the persisted count across that unverifiable interval.
		val accumulator = StepWindowAccumulator(initialBaseline = null, boundary = baselineBoundary)
		val restoredMetrics = recovery.metrics
		metrics = RuntimeAdmissionMetrics(
			lastDurablyAdmittedSequence = restoredMetrics?.lastDurablyAdmittedSequence,
			lastAdmissionOrdinal = restoredMetrics?.lastAdmissionOrdinal,
			failedAdmissionCount = restoredMetrics?.failedAdmissionCount ?: 0L,
			unresolvedSequenceStart = restoredMetrics?.unresolvedSequenceStart,
			unresolvedSequenceEndInclusive = restoredMetrics?.unresolvedSequenceEndInclusive,
			gapClassifications = restoredMetrics?.gapClassifications.orEmpty(),
		)
		callbackEntrySequence = recovery.callbackEntrySequence
		processedCallbackSequence.value = callbackEntrySequence
		cutoffElapsedNanos = null
		admissionDeadlineElapsedNanos = null
		val nextQueue = newStepCallbackLane()
		val nextToken = synchronized(callbackLock) {
			callbackGate.activate(
				nextRegistration.state.registrationGeneration,
				nextRegistration.eligibilityFingerprint,
			).also { callbackToken = it }
		}
		val nextListener = StepRegistrationListener(nextToken)
		listener = nextListener
		currentPlan = plan
		synchronized(callbackLock) {
			registration = nextRegistration
			currentSink = sink
			queue = nextQueue
			callbackAttributionTimeline = StepObservedAuthorizationTimeline(nextRegistration, sink)
			lastQueuedProviderElapsedNanos = null
			overflowPaused = false
			overflowPauseRemovalComplete = false
			acceptingCallbacks = false
		}
		val maximumLatencyUs = plan.maximumReportLatencyMs
			.coerceAtLeast(0L)
			.coerceAtMost((Int.MAX_VALUE / MICROS_PER_MILLISECOND).toLong())
			.times(MICROS_PER_MILLISECOND.toLong())
			.toInt()
		// Open the bounded lane before SensorManager can synchronously deliver the first callback.
		// Consumption still waits for provider acceptance and the initial durable checkpoint below.
		val startupCheckpointCausalOrderElapsedNanos = synchronized(callbackLock) {
			check(callbackGate.accepts(nextToken) && queue === nextQueue)
			// This checkpoint is the pre-intake barrier. Preserve the captured instant so a
			// synchronous registration callback is causally newer even if persistence follows it.
			val causalOrderElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
			acceptingCallbacks = true
			causalOrderElapsedRealtimeNanos
		}
		val registered = try {
			sensorManager.registerListener(
				nextListener,
				stepSensor,
				SensorManager.SENSOR_DELAY_NORMAL,
				maximumLatencyUs,
			)
		} catch (cancelled: CancellationException) {
			cleanupFailedStartBeforeRethrow(
				nextRegistration,
				nextQueue,
				"PROVIDER_REGISTRATION_CANCELLED",
				cancelled,
			)
		} catch (fatal: Error) {
			cleanupFailedStartBeforeRethrow(
				nextRegistration,
				nextQueue,
				"PROVIDER_REGISTRATION_FATAL",
				fatal,
			)
		} catch (_: Exception) {
			false
		}
		if (!registered) {
			cleanupFailedStart(nextRegistration, nextQueue, "PROVIDER_REGISTRATION_FAILED")
			return SourceStartResult.Failed(
				appliedState(source, plan.revision, nextRegistration, SourceApplyStatus.FAILED, SystemClock.elapsedRealtimeNanos()),
				retryable = true,
			)
		}
		val accepted = try {
			if (nextRegistration.requiresProviderAcceptance) {
				registrations.markAccepted(nextRegistration, System.currentTimeMillis())
			}
			true
		} catch (cancelled: CancellationException) {
			cleanupFailedStartBeforeRethrow(
				nextRegistration,
				nextQueue,
				"REGISTRATION_ACCEPTANCE_CANCELLED",
				cancelled,
			)
		} catch (fatal: Error) {
			cleanupFailedStartBeforeRethrow(
				nextRegistration,
				nextQueue,
				"REGISTRATION_ACCEPTANCE_FATAL",
				fatal,
			)
		} catch (_: Exception) {
			false
		}
		if (!accepted) {
			cleanupFailedStart(nextRegistration, nextQueue, "REGISTRATION_ACCEPTANCE_STALE")
			return SourceStartResult.Failed(
				appliedState(source, plan.revision, nextRegistration, SourceApplyStatus.FAILED, SystemClock.elapsedRealtimeNanos()),
				retryable = true,
			)
		}
		val initialized = try {
			registrations.saveSensorRuntimeCheckpoint(
				registration = nextRegistration,
				// Do not claim callbacks queued while provider acceptance was being committed.
				lastProviderSequence = recovery.callbackEntrySequence,
				checkpoint = SensorRuntimeCheckpoint(
					lifecycle = RuntimeCheckpointLifecycle.ACTIVE,
					metrics = metrics.snapshot(),
					componentStateVersion = STEP_BASELINE_VERSION,
					componentPayload = ByteArray(0),
					causalOrderElapsedRealtimeNanos = startupCheckpointCausalOrderElapsedNanos,
				),
				updatedAtMs = stepCheckpointOrderMillis(startupCheckpointCausalOrderElapsedNanos),
			)
			true
		} catch (cancelled: CancellationException) {
			cleanupFailedStartBeforeRethrow(
				nextRegistration,
				nextQueue,
				"RUNTIME_CHECKPOINT_CANCELLED",
				cancelled,
			)
		} catch (fatal: Error) {
			cleanupFailedStartBeforeRethrow(
				nextRegistration,
				nextQueue,
				"RUNTIME_CHECKPOINT_FATAL",
				fatal,
			)
		} catch (_: Exception) {
			false
		}
		if (!initialized) {
			cleanupFailedStart(nextRegistration, nextQueue, "RUNTIME_CHECKPOINT_FAILED")
			return SourceStartResult.Failed(
				appliedState(source, plan.revision, nextRegistration, SourceApplyStatus.FAILED,
					SystemClock.elapsedRealtimeNanos()),
				retryable = true,
			)
		}
		synchronized(callbackLock) { overflowPauseRemovalComplete = true }
		launchStepActor(nextQueue, accumulator)
		batchingEnabled = maximumLatencyUs > 0 && stepSensor.fifoMaxEventCount > 0
		return SourceStartResult.Started(
			appliedState(source, plan.revision, nextRegistration, SourceApplyStatus.APPLIED, SystemClock.elapsedRealtimeNanos()),
		)
	}

	private suspend fun cleanupFailedStart(
		failedRegistration: SourceRegistration,
		callbackLane: StepCallbackLane,
		reason: String,
	) = withContext(NonCancellable) {
		val retirementBoundary = synchronized(callbackLock) {
			acceptingCallbacks = false
			callbackToken?.let(callbackGate::retire)
			callbackLane.close()
			SystemClock.elapsedRealtimeNanos()
		}
		val retirement = retireProviderRegistration(
			activeRegistration = failedRegistration,
			reason = reason,
			retiredElapsedRealtimeNanos = retirementBoundary,
			providerAlreadyRemoved = false,
			deadlineElapsedRealtimeNanos = providerSettlementDeadline(),
		)
		val actorSettled = settleActor(providerSettlementDeadline())
		if (retirement == StepProviderRetirement.COMPLETE && actorSettled) {
			clearActiveState()
		} else {
			retainProviderForRetirementRetry(retainActor = !actorSettled)
		}
	}

	private suspend fun cleanupFailedStartBeforeRethrow(
		failedRegistration: SourceRegistration,
		callbackLane: StepCallbackLane,
		reason: String,
		original: Throwable,
	): Nothing {
		try {
			cleanupFailedStart(failedRegistration, callbackLane, reason)
		} catch (cleanupFailure: Throwable) {
			if (cleanupFailure !== original) original.addSuppressed(cleanupFailure)
		}
		throw original
	}

	private suspend fun shutdownLocked(cutoff: SessionCutoff?): SourceStopAck {
		val ack = withContext(NonCancellable) { settleShutdownLocked(cutoff) }
		currentCoroutineContext().ensureActive()
		return ack
	}

	private suspend fun settleShutdownLocked(cutoff: SessionCutoff?): SourceStopAck {
		val drainDeadline = cutoff?.deadlineElapsedRealtimeNanos
			?: providerSettlementDeadline(DEFAULT_DRAIN_TIMEOUT_MS)
		providerRetirement?.let { return retryPendingRetirementLocked(drainDeadline) }
		val activeRegistration = registration ?: return unavailableAck(cutoff)
		overflowRecoveryJob?.cancel()
		overflowRecoveryJob = null
		val activeListener = listener
		val activeToken = callbackToken
		synchronized(callbackLock) {
			cutoffElapsedNanos = cutoff?.elapsedRealtimeNanos
			admissionDeadlineElapsedNanos = drainDeadline
		}
		var terminalFailure: Throwable? = null
		fun retainTerminalFailure(failure: Throwable) {
			val existing = terminalFailure
			if (existing == null) {
				terminalFailure = failure
			} else if (existing !== failure) {
				existing.addSuppressed(failure)
			}
		}
		val flushOutcome = try {
			if (activeListener != null && activeToken != null) {
				flushProvider(cutoff, activeListener, activeToken)
			} else {
				ProviderFlushOutcome.NOT_REQUESTED
			}
		} catch (cancelled: CancellationException) {
			retainTerminalFailure(cancelled)
			ProviderFlushOutcome.FAILED
		} catch (fatal: Error) {
			retainTerminalFailure(fatal)
			ProviderFlushOutcome.FAILED
		} catch (_: Exception) {
			ProviderFlushOutcome.FAILED
		}
		val barrier: Long
		val overflowSequence: Long?
		val terminalCheckpointCausalOrderElapsedNanos: Long
		synchronized(callbackLock) {
			acceptingCallbacks = false
			activeToken?.let(callbackGate::retire)
			barrier = callbackEntrySequence
			overflowSequence = queue?.overflowSequence
			// Capture the post-intake barrier once. Retries/suspension must not move this state
			// later than callback facts which entered after a future provider lifetime begins.
			terminalCheckpointCausalOrderElapsedNanos = SystemClock.elapsedRealtimeNanos()
		}
		val retirement = try {
			retireProviderRegistration(
				activeRegistration = activeRegistration,
				reason = "ORDERLY_STOP",
				retiredElapsedRealtimeNanos = terminalCheckpointCausalOrderElapsedNanos,
				providerAlreadyRemoved = synchronized(callbackLock) {
					overflowPaused && overflowPauseRemovalComplete
				},
				deadlineElapsedRealtimeNanos = drainDeadline,
			)
		} catch (cancelled: CancellationException) {
			retainTerminalFailure(cancelled)
			StepProviderRetirement.PENDING
		} catch (fatal: Error) {
			retainTerminalFailure(fatal)
			StepProviderRetirement.PENDING
		}
		val removal = if (retirement == StepProviderRetirement.COMPLETE) {
			RegistrationRemovalOutcome.REMOVED
		} else {
			RegistrationRemovalOutcome.FAILED
		}
		val activeQueue = synchronized(callbackLock) {
			queue.also { it?.close() }
		}
		var drainComplete = barrier <= processedCallbackSequence.value
		if (!drainComplete && actor != null) {
			val remainingMs = remainingTimeoutMillis(drainDeadline)
			drainComplete = withTimeoutOrNull(remainingMs) {
				processedCallbackSequence.first { processed -> processed >= barrier }
				true
			} == true
		}
		val actorSettled = if (drainComplete) settleActor(drainDeadline, cancel = false) else settleActor(drainDeadline)
		@Suppress("UNUSED_VARIABLE") val keepReferenceUntilDrain = activeQueue
		if (!drainComplete) {
			unaccountedStepDrainRanges(processedCallbackSequence.value, barrier, overflowSequence)
				.forEach { range -> metrics.recordFailure(range.first, range.last, RuntimeGapClassification.DRAIN_TIMED_OUT) }
		}
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
			registrationRemovalOutcome = removal,
			providerFlushOutcome = flushOutcome,
			providerCoverage = sensorProviderCoverage(batchingEnabled, flushOutcome),
			appDrainComplete = drainComplete,
			status = when {
				!drainComplete || !actorSettled || retirement == StepProviderRetirement.TIMED_OUT -> SourceStopStatus.TIMED_OUT
				removal == RegistrationRemovalOutcome.FAILED -> SourceStopStatus.PROVIDER_FAILED
				else -> SourceStopStatus.COMPLETE
			},
		)
		providerRetirement?.stopAck = ack
		val terminal = StepTerminalSettlementIntent(
			registration = activeRegistration,
			barrier = barrier,
			lifecycle = if (drainComplete) RuntimeCheckpointLifecycle.QUIESCED else RuntimeCheckpointLifecycle.TIMED_OUT,
			causalOrderElapsedRealtimeNanos = terminalCheckpointCausalOrderElapsedNanos,
			admission = admission,
			ack = ack,
		).also { terminalSettlement = it }
		try {
			settleTerminalCheckpoint(terminal)
		} catch (failure: Throwable) {
			retainProviderForRetirementRetry(retainActor = !actorSettled)
			throw failure
		}
		if (retirement == StepProviderRetirement.COMPLETE && actorSettled) {
			terminalStopAck = ack
			clearActiveState()
		} else {
			retainProviderForRetirementRetry(retainActor = !actorSettled)
		}
		terminalFailure?.let { throw it }
		return ack
	}

	private suspend fun retryPendingRetirementLocked(
		deadlineElapsedRealtimeNanos: Long = providerSettlementDeadline(),
	): SourceStopAck {
		val intent = requireNotNull(providerRetirement)
		val retirement = settleProviderRetirement(intent, deadlineElapsedRealtimeNanos)
		val actorSettled = settleActor(deadlineElapsedRealtimeNanos)
		val terminal = terminalSettlement
		if (terminal != null && !terminal.checkpointConfirmed) {
			try {
				settleTerminalCheckpoint(terminal)
			} catch (failure: Throwable) {
				retainProviderForRetirementRetry(retainActor = !actorSettled)
				throw failure
			}
		}
		val previous = terminal?.ack ?: intent.stopAck ?: retirementOnlyAck(intent)
		val completed = retirement == StepProviderRetirement.COMPLETE
		val ack = previous.copy(
			registrationRemovalOutcome = if (completed) {
				RegistrationRemovalOutcome.REMOVED
			} else {
				RegistrationRemovalOutcome.FAILED
			},
			status = when {
				!previous.appDrainComplete || !actorSettled || retirement == StepProviderRetirement.TIMED_OUT -> SourceStopStatus.TIMED_OUT
				completed && terminal?.checkpointConfirmed != false -> SourceStopStatus.COMPLETE
				else -> SourceStopStatus.PROVIDER_FAILED
			},
		)
		intent.stopAck = ack
		if (completed && actorSettled && terminal?.checkpointConfirmed != false) {
			terminalStopAck = ack
			clearActiveState()
		} else {
			retainProviderForRetirementRetry(retainActor = !actorSettled)
		}
		return ack
	}

	private suspend fun retireProviderRegistration(
		activeRegistration: SourceRegistration,
		reason: String,
		retiredElapsedRealtimeNanos: Long,
		providerAlreadyRemoved: Boolean,
		deadlineElapsedRealtimeNanos: Long,
	): StepProviderRetirement {
		val intent = providerRetirement?.also { pending ->
			check(pending.registration.samePhysicalRegistrationAs(activeRegistration)) {
				"A retained step listener cannot be retired under a replacement registration"
			}
		} ?: StepProviderRetirementIntent(
			registration = activeRegistration,
			listener = listener,
			reason = reason,
			retiredAtMs = System.currentTimeMillis(),
			retiredElapsedRealtimeNanos = retiredElapsedRealtimeNanos,
			removalConfirmed = providerAlreadyRemoved,
		).also { providerRetirement = it }
		return settleProviderRetirement(intent, deadlineElapsedRealtimeNanos)
	}

	private suspend fun settleProviderRetirement(
		intent: StepProviderRetirementIntent,
		deadlineElapsedRealtimeNanos: Long,
	): StepProviderRetirement = withContext(NonCancellable) {
		if (intent.completionConfirmed) return@withContext StepProviderRetirement.COMPLETE
		val token = intent.token ?: run {
			var completed = false
			val begun = withTimeoutOrNull(providerOperationTimeoutMillis(deadlineElapsedRealtimeNanos)) {
				val result = try {
					registrations.beginRetirement(
						intent.registration, intent.reason, intent.retiredAtMs,
						intent.retiredElapsedRealtimeNanos,
					)
				} catch (cancelled: CancellationException) {
					throw cancelled
				} catch (fatal: Error) {
					throw fatal
				} catch (_: Exception) {
					null
				}
				completed = true
				result
			}
			if (!completed) return@withContext StepProviderRetirement.TIMED_OUT
			if (begun == null) return@withContext StepProviderRetirement.NOT_DURABLE
			begun.also { intent.token = it }
		}
		if (!intent.removalConfirmed) {
			val exactListener = intent.listener
				?: return@withContext StepProviderRetirement.PENDING
			try {
				sensorManager.unregisterListener(exactListener)
				intent.removalConfirmed = true
			} catch (cancelled: CancellationException) {
				throw cancelled
			} catch (fatal: Error) {
				throw fatal
			} catch (_: Exception) {
				return@withContext StepProviderRetirement.PENDING
			}
		}
		var operationCompleted = false
		val completed = withTimeoutOrNull(providerOperationTimeoutMillis(deadlineElapsedRealtimeNanos)) {
			val result = try {
				registrations.completeRetirement(token)
			} catch (cancelled: CancellationException) {
				throw cancelled
			} catch (fatal: Error) {
				throw fatal
			} catch (_: Exception) {
				false
			}
			operationCompleted = true
			result
		}
		if (!operationCompleted) return@withContext StepProviderRetirement.TIMED_OUT
		if (completed == true) {
			intent.completionConfirmed = true
			StepProviderRetirement.COMPLETE
		} else {
			StepProviderRetirement.PENDING
		}
	}

	private fun retirementOnlyAck(
		intent: StepProviderRetirementIntent,
	): SourceStopAck {
		val admission = metrics.snapshot()
		return SourceStopAck(
			source = source,
			sourceInstanceId = SourceInstanceId(intent.registration.state.sourceInstanceId),
			registrationGeneration = intent.registration.state.registrationGeneration,
			appliedRevision = currentPlan?.revision,
			callbackEntryBarrierSequence = callbackEntrySequence,
			lastDurablyAdmittedSequence = admission.lastDurablyAdmittedSequence,
			lastAdmissionOrdinal = admission.lastAdmissionOrdinal,
			failedAdmissionCount = admission.failedAdmissionCount,
			unresolvedSequenceStart = admission.unresolvedSequenceStart,
			unresolvedSequenceEndInclusive = admission.unresolvedSequenceEndInclusive,
			registrationRemovalOutcome = RegistrationRemovalOutcome.FAILED,
			providerFlushOutcome = ProviderFlushOutcome.NOT_REQUESTED,
			providerCoverage = ProviderCoverage.PROVIDER_COMPLETENESS_UNOBSERVABLE,
			appDrainComplete = true,
			status = SourceStopStatus.PROVIDER_FAILED,
		)
	}

	private fun retainProviderForRetirementRetry(retainActor: Boolean = false) {
		overflowRecoveryJob?.cancel()
		overflowRecoveryJob = null
		synchronized(callbackLock) {
			acceptingCallbacks = false
			callbackToken?.let(callbackGate::retire)
			currentSink = null
			queue?.close()
			queue = null
			callbackAttributionTimeline = null
			pendingAuthorizationRefresh = null
			flushCompletion = null
		}
		if (!retainActor) actor = null
	}

	private suspend fun settleActor(
		deadlineElapsedRealtimeNanos: Long,
		cancel: Boolean = true,
	): Boolean = withContext(NonCancellable) {
		val activeActor = actor ?: return@withContext true
		if (cancel) activeActor.cancel()
		if (activeActor.isCompleted) return@withContext true
		withTimeoutOrNull(providerOperationTimeoutMillis(deadlineElapsedRealtimeNanos)) {
			activeActor.join()
			true
		} == true
	}

	private fun providerSettlementDeadline(timeoutMs: Long = PROVIDER_FLUSH_TIMEOUT_MS): Long =
		SystemClock.elapsedRealtimeNanos() + timeoutMs * NANOS_PER_MILLISECOND

	private fun providerOperationTimeoutMillis(deadlineElapsedRealtimeNanos: Long): Long =
		remainingTimeoutMillis(deadlineElapsedRealtimeNanos).coerceAtMost(PROVIDER_FLUSH_TIMEOUT_MS)

	private suspend fun flushProvider(
		cutoff: SessionCutoff?,
		activeListener: StepRegistrationListener,
		activeToken: StepCallbackToken,
	): ProviderFlushOutcome {
		if (!batchingEnabled) return ProviderFlushOutcome.NOT_SUPPORTED
		val completion = CompletableDeferred<Unit>()
		synchronized(callbackLock) {
			if (!callbackGate.accepts(activeToken)) return ProviderFlushOutcome.NOT_REQUESTED
			flushCompletion = completion
		}
		if (!sensorManager.flush(activeListener)) {
			synchronized(callbackLock) { if (flushCompletion === completion) flushCompletion = null }
			return ProviderFlushOutcome.FAILED
		}
		val timeoutMs = cutoff?.let {
			((it.deadlineElapsedRealtimeNanos - SystemClock.elapsedRealtimeNanos()) / NANOS_PER_MILLISECOND)
				.coerceIn(1L, PROVIDER_FLUSH_TIMEOUT_MS)
		} ?: PROVIDER_FLUSH_TIMEOUT_MS
		val completed = withTimeoutOrNull(timeoutMs) { completion.await(); true } == true
		synchronized(callbackLock) { if (flushCompletion === completion) flushCompletion = null }
		return if (completed) ProviderFlushOutcome.COMPLETE else ProviderFlushOutcome.TIMED_OUT
	}

	private suspend fun consume(
		lane: StepCallbackLane,
		accumulator: StepWindowAccumulator,
	) {
		val drained = consumeStepCallbackLane(
			events = lane.events,
			consumeHead = { event -> consumeOne(event, accumulator) },
			markProcessed = { sequence ->
				processedCallbackSequence.value = maxOf(processedCallbackSequence.value, sequence)
			},
		)
		if (drained) lane.overflowSequence?.let { overflowSequence ->
			processedCallbackSequence.value = maxOf(
				processedCallbackSequence.value,
				overflowSequence,
			)
			scheduleOverflowRecovery(lane)
		}
	}

	/** Publishes identity and completion fencing before a callback actor is allowed to execute. */
	private fun launchStepActor(lane: StepCallbackLane, accumulator: StepWindowAccumulator) {
		val nextActor = applicationScope.launch(start = CoroutineStart.LAZY) { consume(lane, accumulator) }
		actor = nextActor
		nextActor.invokeOnCompletion { failure ->
			if (failure == null) return@invokeOnCompletion
			val ownsFailedLane = synchronized(callbackLock) {
				if (actor !== nextActor || queue !== lane || currentPlan == null ||
					admissionDeadlineElapsedNanos != null
				) {
					false
				} else {
					acceptingCallbacks = false
					callbackToken?.let(callbackGate::retire)
					lane.close()
					true
				}
			}
			if (ownsFailedLane) applicationScope.launch {
				lifecycleMutex.withLock {
					if (actor === nextActor && currentPlan != null) shutdownLocked(null)
				}
			}
		}
		nextActor.start()
	}

	private fun scheduleOverflowRecovery(overflowedLane: StepCallbackLane) {
		val recovery = applicationScope.launch(start = CoroutineStart.LAZY) {
			try {
				recoverOverflowedLane(
					overflowedLane = overflowedLane,
					recoveryOwner = requireNotNull(coroutineContext[Job]),
				)
			} finally {
				val completed = coroutineContext[Job]
				synchronized(callbackLock) {
					if (overflowRecoveryJob === completed) overflowRecoveryJob = null
				}
			}
		}
		val scheduled = synchronized(callbackLock) {
			if (queue !== overflowedLane || overflowRecoveryJob != null ||
				admissionDeadlineElapsedNanos != null || currentPlan == null
			) {
				false
			} else {
				overflowRecoveryJob = recovery
				true
			}
		}
		if (scheduled) recovery.start() else recovery.cancel()
	}

	private suspend fun recoverOverflowedLane(
		overflowedLane: StepCallbackLane,
		recoveryOwner: Job,
	) {
		var retryIndex = 0
		while (true) {
			val outcome = lifecycleMutex.withLock {
				val activeRegistration = registration
				val activePlan = currentPlan
				val overflowSequence = overflowedLane.overflowSequence
				val overflowCausalOrderElapsedNanos =
					overflowedLane.overflowCausalOrderElapsedRealtimeNanos
				recoverStepOverflowAttempt(
					canRecover = {
						queue === overflowedLane && activeRegistration != null && activePlan != null &&
							overflowSequence != null && overflowCausalOrderElapsedNanos != null &&
							admissionDeadlineElapsedNanos == null &&
							context.hasActivityPermission
					},
					persistGapAndReset = {
						runCatchingNonCancellation {
							registrations.saveSensorRuntimeCheckpoint(
								registration = requireNotNull(activeRegistration),
								lastProviderSequence = requireNotNull(overflowSequence),
								checkpoint = SensorRuntimeCheckpoint(
									lifecycle = RuntimeCheckpointLifecycle.ACTIVE,
									metrics = metrics.snapshot(),
									componentStateVersion = STEP_BASELINE_VERSION,
									componentPayload = ByteArray(0),
									causalOrderElapsedRealtimeNanos =
										requireNotNull(overflowCausalOrderElapsedNanos),
								),
								updatedAtMs = stepCheckpointOrderMillis(
									requireNotNull(overflowCausalOrderElapsedNanos),
								),
							)
						}.isSuccess
					},
					resumeProvider = {
						resumeAfterOverflowLocked(
							overflowedLane,
							requireNotNull(activeRegistration),
							requireNotNull(activePlan),
							recoveryOwner,
						)
					},
				)
			}
			when (outcome) {
				StepOverflowRecoveryOutcome.RESUMED,
				StepOverflowRecoveryOutcome.SUPERSEDED -> return
				StepOverflowRecoveryOutcome.RETRY -> {
					val delayMs = STEP_OVERFLOW_RECOVERY_DELAYS_MS[
						retryIndex.coerceAtMost(STEP_OVERFLOW_RECOVERY_DELAYS_MS.lastIndex)
					]
					retryIndex++
					delay(delayMs)
				}
			}
		}
	}

	/**
	 * Reuses the durable physical generation only after its old callback token is fenced and the
	 * overflow gap plus empty baseline is durable. That reset separates the two provider lifetimes.
	 */
	private fun resumeAfterOverflowLocked(
		overflowedLane: StepCallbackLane,
		activeRegistration: SourceRegistration,
		activePlan: StepsPlan,
		recoveryOwner: Job,
	): Boolean {
		val activeSensor = sensor ?: return false
		if (queue !== overflowedLane || admissionDeadlineElapsedNanos != null ||
			!context.hasActivityPermission
		) return false
		val listenerToRemove = synchronized(callbackLock) {
			if (overflowPaused && !overflowPauseRemovalComplete) listener else null
		}
		if (listenerToRemove != null) {
			val removed = try {
				sensorManager.unregisterListener(listenerToRemove)
				true
			} catch (cancelled: CancellationException) {
				throw cancelled
			} catch (fatal: Error) {
				throw fatal
			} catch (_: Exception) {
				false
			}
			if (!removed) return false
			val stillCurrent = synchronized(callbackLock) {
				if (queue !== overflowedLane || !overflowPaused || listener !== listenerToRemove ||
					admissionDeadlineElapsedNanos != null
				) {
					false
				} else {
					listener = null
					overflowPauseRemovalComplete = true
					true
				}
			}
			if (!stillCurrent) return false
		}
		val nextLane = newStepCallbackLane()
		val nextTokenAndListener = synchronized(callbackLock) {
			if (queue !== overflowedLane || admissionDeadlineElapsedNanos != null ||
				!context.hasActivityPermission || !overflowPaused ||
				!overflowPauseRemovalComplete || listener != null
			) {
				null
			} else {
				val token = callbackGate.activate(
					activeRegistration.state.registrationGeneration,
					activeRegistration.eligibilityFingerprint,
				)
				val nextListener = StepRegistrationListener(token)
				callbackToken = token
				listener = nextListener
				queue = nextLane
				lastQueuedProviderElapsedNanos = null
				overflowPaused = false
				overflowPauseRemovalComplete = false
				acceptingCallbacks = true
				token to nextListener
			}
		} ?: run {
			nextLane.close()
			return false
		}
		val (nextToken, nextListener) = nextTokenAndListener
		val maximumLatencyUs = activePlan.maximumReportLatencyMs
			.coerceAtLeast(0L)
			.coerceAtMost((Int.MAX_VALUE / MICROS_PER_MILLISECOND).toLong())
			.times(MICROS_PER_MILLISECOND.toLong())
			.toInt()
		val registered = attemptStepProviderRegistration(
			register = {
				sensorManager.registerListener(
					nextListener,
					activeSensor,
					SensorManager.SENSOR_DELAY_NORMAL,
					maximumLatencyUs,
				)
			},
			rollback = {
				synchronized(callbackLock) {
					if (queue === nextLane) {
						acceptingCallbacks = false
						callbackGate.retire(nextToken)
						callbackToken = null
						overflowPaused = true
						overflowPauseRemovalComplete = false
						queue = overflowedLane
					}
				}
				var cleanupFailure: Throwable? = null
				val removed = try {
					sensorManager.unregisterListener(nextListener)
					true
				} catch (cancelled: CancellationException) {
					cleanupFailure = cancelled
					false
				} catch (fatal: Error) {
					cleanupFailure = fatal
					false
				} catch (_: Exception) {
					false
				}
				synchronized(callbackLock) {
					if (overflowPaused && listener === nextListener) {
						overflowPauseRemovalComplete = removed
						if (removed) listener = null
					}
				}
				nextLane.close()
				cleanupFailure?.let { throw it }
			},
		)
		if (!registered) return false
		synchronized(callbackLock) {
			if (listener === nextListener && queue === nextLane && acceptingCallbacks &&
				callbackToken === nextToken && callbackGate.accepts(nextToken) && !overflowPaused
			) {
				overflowPauseRemovalComplete = true
			}
		}
		// Release the predecessor before the replacement actor can observe and recover its own
		// synchronous-registration overflow. The predecessor's finally block is identity-fenced, so
		// it cannot clear a successor installed by that actor.
		synchronized(callbackLock) {
			if (overflowRecoveryJob === recoveryOwner) overflowRecoveryJob = null
		}
		launchStepActor(
			nextLane,
			StepWindowAccumulator(
				initialBaseline = null,
				boundary = StepBaselineBoundary(activeRegistration.state.registrationGeneration),
			),
		)
		batchingEnabled = maximumLatencyUs > 0 && activeSensor.fifoMaxEventCount > 0
		// A synchronous callback may fill the provisional lane before registerListener returns.
		// In that case the new actor drains it and schedules the next bounded recovery attempt; do
		// not overwrite the callback's fenced/paused state as a healthy resume.
		return true
	}

	/** Returns false only while this event remains the unresolved FIFO head at the stop deadline. */
	private suspend fun consumeOne(event: RawStep, accumulator: StepWindowAccumulator): Boolean {
		if (cutoffElapsedNanos?.let { event.observedElapsedNanos > it } == true) return true
		val attribution = event.attribution.resolve()
		val activeRegistration = attribution.registration
		val preview = accumulator.preview(
			activeRegistration.state.clockDomainId,
			event.cumulativeCount,
			event.observedElapsedNanos,
			event.providerSequence,
			event.receivedElapsedNanos,
			activeRegistration.stepAuthorizationBoundary(),
		) ?: run {
			metrics.recordFailure(event.providerSequence)
			return persistStepHeadCheckpointUntilResolved(event, activeRegistration, accumulator)
		}
		val payload = preview.payload
		val delayNanos = (event.receivedElapsedNanos - event.observedElapsedNanos).coerceAtLeast(0L)
		val acquiredAtMs = (event.receivedWallTimeMs - delayNanos / NANOS_PER_MILLISECOND).coerceAtLeast(0L)
		val prepared = when (val preparation = retryStepPhaseUntilResolved(
			attempt = {
				runCatchingNonCancellation {
					val sourceSequence = registrations.allocateSequence(
						activeRegistration,
						event.receivedWallTimeMs,
					)
					val candidate = SourceEvidenceCandidate(
						providerDedupKey = stepProviderDedupKey(
							activeRegistration.state.clockDomainId,
							event.observedElapsedNanos,
							event.cumulativeCount,
						),
						logicalTrackingId = null,
						serviceRunId = null,
						source = source,
						sourceInstanceId = SourceInstanceId(activeRegistration.state.sourceInstanceId),
						registrationGeneration = activeRegistration.state.registrationGeneration,
						physicalConfigurationFingerprint = activeRegistration.physicalConfigurationFingerprint,
						authorizationRevision = activeRegistration.authorization.authorizationRevision,
						registrationPurposeEligibilityMask = activeRegistration.purposeEligibilityMask,
						registrationEligibilityFingerprint = activeRegistration.eligibilityFingerprint,
						sourceSequence = sourceSequence,
						configRevision = activeRegistration.state.appliedRevision,
						planAttribution = PlanAttribution.CAPTURED_REGISTRATION,
						clockDomainId = activeRegistration.state.clockDomainId,
						observedElapsedRealtimeNanos = event.observedElapsedNanos,
						receivedElapsedRealtimeNanos = event.receivedElapsedNanos,
						wallTimeMs = acquiredAtMs,
						wallTimeUncertaintyMs = 1L,
						capturedCollectedDataEpoch = activeRegistration.state.collectedDataEpoch,
						acquiredAtMs = acquiredAtMs,
						quality = SourceQuality(
							flags = if (delayNanos >= BATCHED_AFTER_NANOS) {
								setOf(SourceQualityFlag.BATCHED)
							} else {
								emptySet()
							},
						),
						payloadVersion = STEP_BOUNDARY_KIND_PAYLOAD_VERSION,
						payload = payload,
					)
					PreparedStepAdmission(
						candidate = candidate,
						checkpoint = activeRegistration.sensorAdmissionCheckpoint(
							providerSequenceThrough = event.providerSequence,
							checkpoint = stepAtomicRuntimeCheckpoint(
								metrics.snapshot(),
								preview,
								event.receivedElapsedNanos,
							),
							updatedAtMs = stepCheckpointOrderMillis(event.receivedElapsedNanos),
						),
					)
				}.getOrNull()
			},
			deadlineElapsedRealtimeNanos = { admissionDeadlineElapsedNanos },
			elapsedRealtimeNanos = SystemClock::elapsedRealtimeNanos,
		)) {
			is StepPhaseResolution.Resolved -> preparation.value
			StepPhaseResolution.DeadlineExceeded -> return false
		}
		val handoff = when (val resolution = attribution.sink.admitStepHeadAtomicallyUntilResolved(
			candidate = prepared.candidate,
			checkpoint = prepared.checkpoint,
			deadlineElapsedRealtimeNanos = { admissionDeadlineElapsedNanos },
			elapsedRealtimeNanos = SystemClock::elapsedRealtimeNanos,
		)) {
			StepAdmissionResolution.DeadlineExceeded -> return false
			is StepAdmissionResolution.Resolved -> resolution.handoff
		}
		return when (handoff) {
			is SourceAdmissionHandoff.Durable -> {
				check(commitDurableStepPreview(accumulator, preview, handoff))
				metrics.recordDurable(event.providerSequence, handoff.admissionOrdinal)
				recentEvidence.recordQualified(activeRegistration, payload, event.observedElapsedNanos)
				true
			}
			is SourceAdmissionHandoff.Duplicate -> {
				check(commitDurableStepPreview(accumulator, preview, handoff))
				metrics.recordDurable(event.providerSequence, handoff.existingAdmissionOrdinal)
				recentEvidence.recordQualified(activeRegistration, payload, event.observedElapsedNanos)
				true
			}
			is SourceAdmissionHandoff.TerminalFailure -> {
				metrics.recordFailure(event.providerSequence)
				persistStepHeadCheckpointUntilResolved(event, activeRegistration, accumulator)
			}
			is SourceAdmissionHandoff.RetryableFailure -> error(
				"Step atomic admission retry loop returned an unresolved retryable handoff",
			)
		}
	}

	private suspend fun persistStepHeadCheckpointUntilResolved(
		event: RawStep,
		activeRegistration: SourceRegistration,
		accumulator: StepWindowAccumulator,
	): Boolean = when (retryStepPhaseUntilResolved(
		attempt = {
			if (persistStepHeadCheckpointOnce(event, activeRegistration, accumulator)) Unit else null
		},
		deadlineElapsedRealtimeNanos = { admissionDeadlineElapsedNanos },
		elapsedRealtimeNanos = SystemClock::elapsedRealtimeNanos,
	)) {
		is StepPhaseResolution.Resolved -> true
		StepPhaseResolution.DeadlineExceeded -> false
	}

	private suspend fun persistStepHeadCheckpointOnce(
		event: RawStep,
		activeRegistration: SourceRegistration,
		accumulator: StepWindowAccumulator,
	): Boolean = runCatchingNonCancellation {
		registrations.saveSensorRuntimeCheckpoint(
			registration = activeRegistration,
			lastProviderSequence = event.providerSequence,
			checkpoint = SensorRuntimeCheckpoint(
				RuntimeCheckpointLifecycle.ACTIVE,
				metrics.snapshot(),
				STEP_BASELINE_VERSION,
				accumulator.snapshot()?.encode() ?: ByteArray(0),
				event.receivedElapsedNanos,
			),
			updatedAtMs = stepCheckpointOrderMillis(event.receivedElapsedNanos),
		)
	}.isSuccess

	private fun onSensorChanged(callbackToken: StepCallbackToken, event: SensorEvent) {
		if (event.sensor.type != Sensor.TYPE_STEP_COUNTER) return
		if (!context.hasActivityPermission) return
		val cumulative = event.values.firstOrNull()?.takeIf { it.isFinite() }?.toLong() ?: return
		if (cumulative < 0L) return
		val receivedElapsed = SystemClock.elapsedRealtimeNanos()
		val receivedWall = System.currentTimeMillis()
		var pauseListener: StepRegistrationListener? = null
		synchronized(callbackLock) {
			if (!acceptingCallbacks || !callbackGate.accepts(callbackToken) ||
				!context.hasActivityPermission
			) return
			if (!isFreshStepSampleTimestamp(
				providerElapsedNanos = event.timestamp,
				receivedElapsedNanos = receivedElapsed,
				previousProviderElapsedNanos = lastQueuedProviderElapsedNanos,
			)) return
			if (cutoffElapsedNanos?.let { event.timestamp > it } == true) return
			val attribution = stepCallbackAttributionReference(
				timeline = callbackAttributionTimeline ?: return,
				pendingRefresh = pendingAuthorizationRefresh,
				observedElapsedRealtimeNanos = event.timestamp,
			) ?: return
			val providerSequence = ++callbackEntrySequence
			val accepted = requireNotNull(queue).offer(
				RawStep(
					cumulative,
					event.timestamp,
					receivedElapsed,
					receivedWall,
					providerSequence,
					attribution,
				),
			)
			if (accepted) {
				lastQueuedProviderElapsedNanos = event.timestamp
			} else {
				metrics.recordFailure(
					providerSequence,
					classification = RuntimeGapClassification.CALLBACK_BUFFER_OVERFLOW,
				)
				acceptingCallbacks = false
				callbackGate.retire(callbackToken)
				overflowPaused = true
				overflowPauseRemovalComplete = false
				pauseListener = listener
			}
		}
		pauseListener?.let { paused ->
			val removed = try {
				sensorManager.unregisterListener(paused)
				true
			} catch (cancelled: CancellationException) {
				throw cancelled
			} catch (fatal: Error) {
				throw fatal
			} catch (_: Exception) {
				false
			}
			synchronized(callbackLock) {
				if (overflowPaused && listener === paused) {
					overflowPauseRemovalComplete = removed
					if (removed) listener = null
				}
			}
		}
	}

	private suspend fun persistTerminalCheckpoint(
		activeRegistration: SourceRegistration,
		lastProviderSequence: Long,
		lifecycle: RuntimeCheckpointLifecycle,
		causalOrderElapsedRealtimeNanos: Long,
		admission: RuntimeAdmissionSnapshot,
	) {
		val saved = registrations.loadRuntimeState(activeRegistration)
		val boundary = StepBaselineBoundary(
			activeRegistration.state.registrationGeneration,
		)
		val component = saved
			?.takeIf { lifecycle != RuntimeCheckpointLifecycle.TIMED_OUT }
			?.takeIf { it.registrationGeneration == activeRegistration.state.registrationGeneration }
			?.let { decodeSensorRuntimeCheckpoint(it, STEP_BASELINE_VERSION) }
			?.takeIf {
				decodeStepBaseline(it.componentPayload, it.componentStateVersion, boundary) != null
			}
		registrations.saveSensorRuntimeCheckpoint(
			activeRegistration,
			lastProviderSequence,
			SensorRuntimeCheckpoint(
				lifecycle,
				admission,
				component?.componentStateVersion ?: STEP_BASELINE_VERSION,
				component?.componentPayload ?: ByteArray(0),
				causalOrderElapsedRealtimeNanos,
			),
			stepCheckpointOrderMillis(causalOrderElapsedRealtimeNanos),
		)
	}

	private suspend fun settleTerminalCheckpoint(intent: StepTerminalSettlementIntent) {
		if (intent.checkpointConfirmed) return
		persistTerminalCheckpoint(
			activeRegistration = intent.registration,
			lastProviderSequence = intent.barrier,
			lifecycle = intent.lifecycle,
			causalOrderElapsedRealtimeNanos = intent.causalOrderElapsedRealtimeNanos,
			admission = intent.admission,
		)
		intent.checkpointConfirmed = true
	}

	private fun onFlushCompleted(callbackToken: StepCallbackToken, sensor: Sensor?) {
		if (sensor?.type == Sensor.TYPE_STEP_COUNTER) {
			synchronized(callbackLock) {
				if (callbackGate.accepts(callbackToken)) flushCompletion?.complete(Unit)
			}
		}
	}

	private fun clearActiveState() {
		check(actor?.isCompleted != false) {
			"Step provider ownership cannot be cleared while its callback actor is still running"
		}
		overflowRecoveryJob?.cancel()
		overflowRecoveryJob = null
		synchronized(callbackLock) {
			val activeAttribution = registration?.let { activeRegistration ->
				currentSink?.let { activeSink -> StepCallbackAttribution(activeRegistration, activeSink) }
			}
			if (activeAttribution != null) pendingAuthorizationRefresh?.complete(activeAttribution)
			pendingAuthorizationRefresh = null
			acceptingCallbacks = false
			callbackToken?.let(callbackGate::retire)
			callbackToken = null
			registration = null
			currentSink = null
			queue = null
			callbackAttributionTimeline = null
			lastQueuedProviderElapsedNanos = null
			flushCompletion = null
			overflowPaused = false
			overflowPauseRemovalComplete = true
		}
		listener = null
		providerRetirement = null
		terminalSettlement = null
		actor = null
		batchingEnabled = false
		cutoffElapsedNanos = null
		admissionDeadlineElapsedNanos = null
		currentPlan = null
	}

	private inner class StepRegistrationListener(
		private val token: StepCallbackToken,
	) : SensorEventListener2 {
		override fun onSensorChanged(event: SensorEvent) = this@StepSourceRuntime.onSensorChanged(token, event)

		override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

		override fun onFlushCompleted(sensor: Sensor?) = this@StepSourceRuntime.onFlushCompleted(token, sensor)
	}

	private inner class StepProviderRetirementIntent(
		val registration: SourceRegistration,
		val listener: StepRegistrationListener?,
		val reason: String,
		val retiredAtMs: Long,
		val retiredElapsedRealtimeNanos: Long,
		var removalConfirmed: Boolean,
		var token: SourceRegistrationRetirementToken? = null,
		var completionConfirmed: Boolean = false,
		var stopAck: SourceStopAck? = null,
	)

	private class StepTerminalSettlementIntent(
		val registration: SourceRegistration,
		val barrier: Long,
		val lifecycle: RuntimeCheckpointLifecycle,
		val causalOrderElapsedRealtimeNanos: Long,
		val admission: RuntimeAdmissionSnapshot,
		val ack: SourceStopAck,
		var checkpointConfirmed: Boolean = false,
	)

	private fun unavailableAck(cutoff: SessionCutoff?): SourceStopAck {
		val admission = metrics.snapshot()
		return SourceStopAck(
		source = source,
		sourceInstanceId = SourceInstanceId("unavailable-steps"),
		registrationGeneration = 0L,
		appliedRevision = currentPlan?.revision,
		callbackEntryBarrierSequence = callbackEntrySequence,
		lastDurablyAdmittedSequence = admission.lastDurablyAdmittedSequence,
		lastAdmissionOrdinal = admission.lastAdmissionOrdinal,
		failedAdmissionCount = admission.failedAdmissionCount,
		unresolvedSequenceStart = admission.unresolvedSequenceStart,
		unresolvedSequenceEndInclusive = admission.unresolvedSequenceEndInclusive,
		registrationRemovalOutcome = RegistrationRemovalOutcome.NOT_REGISTERED,
		providerFlushOutcome = ProviderFlushOutcome.NOT_REQUESTED,
		providerCoverage = ProviderCoverage.PROVIDER_COMPLETENESS_UNOBSERVABLE,
		appDrainComplete = true,
		status = if (cutoff == null) SourceStopStatus.COMPLETE else SourceStopStatus.PROVIDER_FAILED,
		)
	}

	private fun Sensor?.toCapabilities(): SourceCapabilities = SourceCapabilities(
		available = this != null && context.packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_STEP_COUNTER),
		batchingSupported = this?.fifoMaxEventCount?.let { it > 0 } == true,
		flushSupported = this?.fifoMaxEventCount?.let { it > 0 } == true,
		maximumBatchSize = this?.fifoMaxEventCount,
		minimumDelayMs = this?.minDelay?.takeIf { it >= 0 }?.div(MICROS_PER_MILLISECOND)?.toLong(),
	)

	private fun remainingTimeoutMillis(deadlineElapsedNanos: Long): Long {
		val remainingNanos = deadlineElapsedNanos - SystemClock.elapsedRealtimeNanos()
		if (remainingNanos <= 0L) return 1L
		return (remainingNanos - 1L) / NANOS_PER_MILLISECOND + 1L
	}

	private companion object {
		const val MICROS_PER_MILLISECOND = 1_000
		const val NANOS_PER_MILLISECOND = 1_000_000L
		const val BATCHED_AFTER_NANOS = 5_000L * NANOS_PER_MILLISECOND
		const val PROVIDER_FLUSH_TIMEOUT_MS = 1_000L
		const val DEFAULT_DRAIN_TIMEOUT_MS = 2_000L
	}
}

private enum class StepProviderRetirement { NOT_DURABLE, PENDING, TIMED_OUT, COMPLETE }

/** Excludes the overflow sequence, whose failure was recorded synchronously at callback entry. */
internal fun unaccountedStepDrainRanges(
	processedThrough: Long,
	barrier: Long,
	alreadyAccountedOverflowSequence: Long?,
): List<LongRange> {
	val first = processedThrough + 1L
	if (first > barrier) return emptyList()
	val overflow = alreadyAccountedOverflowSequence
	if (overflow == null || overflow !in first..barrier) return listOf(first..barrier)
	return buildList(2) {
		if (first < overflow) add(first until overflow)
		if (overflow < barrier) add((overflow + 1L)..barrier)
	}
}

private fun SourceRegistration.samePhysicalRegistrationAs(other: SourceRegistration): Boolean =
	state.sourceKind == other.state.sourceKind &&
		state.sourceInstanceId == other.state.sourceInstanceId &&
		state.registrationGeneration == other.state.registrationGeneration &&
		state.clockDomainId == other.state.clockDomainId &&
		state.collectedDataEpoch == other.state.collectedDataEpoch &&
		physicalConfigurationFingerprint == other.physicalConfigurationFingerprint

internal data class RawStep(
	val cumulativeCount: Long,
	val observedElapsedNanos: Long,
	val receivedElapsedNanos: Long,
	val receivedWallTimeMs: Long,
	val providerSequence: Long,
	val attribution: StepCallbackAttributionReference,
)

internal data class PreparedStepAdmission(
	val candidate: SourceEvidenceCandidate<*>,
	val checkpoint: SensorAdmissionCheckpoint,
)

/** Fixed-size FIFO that pauses acquisition at the first callback it cannot retain. */
internal class StepCallbackLane(capacity: Int = STEP_CALLBACK_LANE_CAPACITY) {
	private val channel = Channel<RawStep>(capacity)
	val events: ReceiveChannel<RawStep> get() = channel
	var overflowSequence: Long? = null
		private set
	var overflowCausalOrderElapsedRealtimeNanos: Long? = null
		private set

	init {
		require(capacity > 0)
	}

	fun offer(event: RawStep): Boolean {
		if (overflowSequence != null) return false
		if (channel.trySend(event).isSuccess) return true
		overflowSequence = event.providerSequence
		overflowCausalOrderElapsedRealtimeNanos = event.receivedElapsedNanos
		channel.close()
		return false
	}

	fun close() = channel.close()
}

internal fun newStepCallbackLane(capacity: Int = STEP_CALLBACK_LANE_CAPACITY) =
	StepCallbackLane(capacity)

/** Strict FIFO: a deadline-expired head stops the lane and leaves its sequence unprocessed. */
internal suspend fun consumeStepCallbackLane(
	events: ReceiveChannel<RawStep>,
	consumeHead: suspend (RawStep) -> Boolean,
	markProcessed: (Long) -> Unit,
): Boolean {
	for (event in events) {
		if (!consumeHead(event)) return false
		markProcessed(event.providerSequence)
	}
	return true
}

internal sealed interface StepAdmissionResolution {
	data class Resolved(val handoff: SourceAdmissionHandoff) : StepAdmissionResolution
	data object DeadlineExceeded : StepAdmissionResolution
}

internal sealed interface StepPhaseResolution<out T> {
	data class Resolved<T>(val value: T) : StepPhaseResolution<T>
	data object DeadlineExceeded : StepPhaseResolution<Nothing>
}

/** Treats both false and an Android registration exception as an unaccepted provisional lane. */
internal inline fun attemptStepProviderRegistration(
	register: () -> Boolean,
	rollback: () -> Unit,
): Boolean {
	val registered = try {
		register()
	} catch (cancelled: CancellationException) {
		try {
			rollback()
		} catch (cleanupFailure: Throwable) {
			if (cleanupFailure !== cancelled) cancelled.addSuppressed(cleanupFailure)
		}
		throw cancelled
	} catch (fatal: Error) {
		try {
			rollback()
		} catch (cleanupFailure: Throwable) {
			if (cleanupFailure !== fatal) fatal.addSuppressed(cleanupFailure)
		}
		throw fatal
	} catch (_: Exception) {
		false
	}
	if (!registered) rollback()
	return registered
}

/**
 * Retries only while this process and runtime remain alive. Coroutine delay is deliberately
 * non-waking; the capped schedule does not create alarms or keep the device awake.
 */
internal suspend fun <T : Any> retryStepPhaseUntilResolved(
	attempt: suspend () -> T?,
	deadlineElapsedRealtimeNanos: () -> Long?,
	elapsedRealtimeNanos: () -> Long,
	retryDelay: suspend (Long) -> Unit = { delay(it) },
): StepPhaseResolution<T> {
	var retryIndex = 0
	while (true) {
		val deadlineBeforeAttempt = deadlineElapsedRealtimeNanos()
		if (deadlineBeforeAttempt != null && elapsedRealtimeNanos() >= deadlineBeforeAttempt) {
			return StepPhaseResolution.DeadlineExceeded
		}
		attempt()?.let { return StepPhaseResolution.Resolved(it) }
		val requestedDelayMs = STEP_TRANSIENT_RETRY_DELAYS_MS[
			retryIndex.coerceAtMost(STEP_TRANSIENT_RETRY_DELAYS_MS.lastIndex)
		]
		retryIndex++
		val deadlineAfterAttempt = deadlineElapsedRealtimeNanos()
		val actualDelayMs = if (deadlineAfterAttempt == null) {
			requestedDelayMs
		} else {
			val remainingNanos = deadlineAfterAttempt - elapsedRealtimeNanos()
			if (remainingNanos <= 0L) return StepPhaseResolution.DeadlineExceeded
			minOf(
				requestedDelayMs,
				(remainingNanos - 1L) / STEP_NANOS_PER_MILLISECOND + 1L,
			)
		}
		retryDelay(actualDelayMs)
	}
}

internal enum class StepOverflowRecoveryOutcome {
	RESUMED,
	RETRY,
	SUPERSEDED,
}

/** Enforces durable gap/reset ordering before a paused Steps provider may resume. */
internal suspend fun recoverStepOverflowAttempt(
	canRecover: () -> Boolean,
	persistGapAndReset: suspend () -> Boolean,
	resumeProvider: () -> Boolean,
): StepOverflowRecoveryOutcome {
	if (!canRecover()) return StepOverflowRecoveryOutcome.SUPERSEDED
	if (!persistGapAndReset()) return StepOverflowRecoveryOutcome.RETRY
	if (!canRecover()) return StepOverflowRecoveryOutcome.SUPERSEDED
	return if (resumeProvider()) {
		StepOverflowRecoveryOutcome.RESUMED
	} else {
		StepOverflowRecoveryOutcome.RETRY
	}
}

/** Only an existing or newly durable WAL fact is allowed to advance the Steps baseline. */
internal fun commitDurableStepPreview(
	accumulator: StepWindowAccumulator,
	preview: StepWindowPreview,
	handoff: SourceAdmissionHandoff,
): Boolean = when (handoff) {
	is SourceAdmissionHandoff.Durable,
	is SourceAdmissionHandoff.Duplicate -> accumulator.commit(preview)
	is SourceAdmissionHandoff.TerminalFailure,
	is SourceAdmissionHandoff.RetryableFailure -> false
}

/**
 * Mirrors the callback-entry receive instant into the prepared state. Atomic ingress v3 derives
 * its persisted causal order from the candidate carrying that same full-nanosecond value.
 */
internal fun stepAtomicRuntimeCheckpoint(
	absoluteGapMetrics: RuntimeAdmissionSnapshot,
	preview: StepWindowPreview,
	causalOrderElapsedRealtimeNanos: Long,
): SensorRuntimeCheckpoint = SensorRuntimeCheckpoint(
	lifecycle = RuntimeCheckpointLifecycle.ACTIVE,
	metrics = absoluteGapMetrics,
	componentStateVersion = STEP_BASELINE_VERSION,
	componentPayload = preview.nextBaseline.encode(),
	causalOrderElapsedRealtimeNanos = causalOrderElapsedRealtimeNanos,
)

/** Atomic-only: unsupported/retryable sinks retain the exact prepared candidate/checkpoint head. */
internal suspend fun SourceEventSink.admitStepHeadAtomicallyUntilResolved(
	candidate: SourceEvidenceCandidate<*>,
	checkpoint: SensorAdmissionCheckpoint,
	deadlineElapsedRealtimeNanos: () -> Long?,
	elapsedRealtimeNanos: () -> Long,
	retryDelay: suspend (Long) -> Unit = { delay(it) },
): StepAdmissionResolution {
	var retryIndex = 0
	while (true) {
		val deadlineBeforeAttempt = deadlineElapsedRealtimeNanos()
		if (deadlineBeforeAttempt != null && elapsedRealtimeNanos() >= deadlineBeforeAttempt) {
			return StepAdmissionResolution.DeadlineExceeded
		}
		when (val handoff = admit(candidate, checkpoint)) {
			is SourceAdmissionHandoff.Durable,
			is SourceAdmissionHandoff.Duplicate,
			is SourceAdmissionHandoff.TerminalFailure ->
				return StepAdmissionResolution.Resolved(handoff)
			is SourceAdmissionHandoff.RetryableFailure -> Unit
		}

		val requestedDelayMs = STEP_TRANSIENT_RETRY_DELAYS_MS[
			retryIndex.coerceAtMost(STEP_TRANSIENT_RETRY_DELAYS_MS.lastIndex)
		]
		retryIndex++
		val deadlineAfterAttempt = deadlineElapsedRealtimeNanos()
		val actualDelayMs = if (deadlineAfterAttempt == null) {
			requestedDelayMs
		} else {
			val remainingNanos = deadlineAfterAttempt - elapsedRealtimeNanos()
			if (remainingNanos <= 0L) return StepAdmissionResolution.DeadlineExceeded
			minOf(
				requestedDelayMs,
				(remainingNanos - 1L) / STEP_NANOS_PER_MILLISECOND + 1L,
			)
		}
		retryDelay(actualDelayMs)
	}
}

internal data class StepRuntimeRecovery(
	val metrics: RuntimeAdmissionSnapshot?,
	val callbackEntrySequence: Long,
	val baseline: StepBaseline?,
)

/** A process boundary always invalidates the cumulative baseline and reserves one explicit gap. */
internal fun recoverStepRuntimeState(
	saved: SourceRuntimeStateEntity?,
	currentRegistrationGeneration: Long,
	reusedPhysicalRegistration: Boolean,
): StepRuntimeRecovery {
	require(currentRegistrationGeneration > 0L)
	if (!reusedPhysicalRegistration) return StepRuntimeRecovery(null, 0L, null)

	val sameGeneration = saved?.registrationGeneration == currentRegistrationGeneration
	val lastProviderSequence = if (sameGeneration) requireNotNull(saved).lastProviderSequence else 0L
	val checkpoint = saved
		?.takeIf { sameGeneration }
		?.let { decodeSensorRuntimeCheckpoint(it, STEP_BASELINE_VERSION) }
	val prior = checkpoint?.metrics
	val priorHighWater = maxOf(lastProviderSequence, prior?.unresolvedSequenceEndInclusive ?: 0L)
	require(priorHighWater < Long.MAX_VALUE) { "Steps callback sequence exhausted" }
	val gapSequence = priorHighWater + 1L
	val recoveredMetrics = RuntimeAdmissionMetrics(
		lastDurablyAdmittedSequence = prior?.lastDurablyAdmittedSequence,
		lastAdmissionOrdinal = prior?.lastAdmissionOrdinal,
		failedAdmissionCount = prior?.failedAdmissionCount ?: 0L,
		unresolvedSequenceStart = prior?.unresolvedSequenceStart,
		unresolvedSequenceEndInclusive = prior?.unresolvedSequenceEndInclusive,
		gapClassifications = prior?.gapClassifications.orEmpty(),
	).also {
		it.recordFailure(gapSequence, classification = RuntimeGapClassification.PROCESS_RESTARTED)
	}
	return StepRuntimeRecovery(
		metrics = recoveredMetrics.snapshot(),
		callbackEntrySequence = gapSequence,
		baseline = null,
	)
}

private fun SourceRegistration.stepAuthorizationBoundary() = StepAuthorizationBoundary(
	authorizationRevision = authorization.authorizationRevision,
	authorizationFingerprint = authorization.authorizationFingerprint,
	purposeEligibilityMask = purposeEligibilityMask,
	effectiveElapsedRealtimeNanos = authorization.effectiveElapsedRealtimeNanos,
)

internal data class StepCallbackAttribution(
	val registration: SourceRegistration,
	val sink: SourceEventSink,
)

internal sealed interface StepCallbackAttributionReference {
	suspend fun resolve(): StepCallbackAttribution
}

internal data class ResolvedStepCallbackAttribution(
	private val attribution: StepCallbackAttribution,
) : StepCallbackAttributionReference {
	override suspend fun resolve(): StepCallbackAttribution = attribution
}

/** Latches observations at/after begin()'s effective boundary until its durable result is known. */
internal class PendingStepAuthorizationRefresh(
	val effectiveElapsedRealtimeNanos: Long,
) : StepCallbackAttributionReference {
	private val resolution = CompletableDeferred<StepCallbackAttribution>()
	internal val isResolved: Boolean get() = resolution.isCompleted

	init {
		require(effectiveElapsedRealtimeNanos >= 0L)
	}

	fun complete(attribution: StepCallbackAttribution) {
		resolution.complete(attribution)
	}

	override suspend fun resolve(): StepCallbackAttribution = resolution.await()
}

internal fun stepCallbackAttributionReference(
	timeline: StepObservedAuthorizationTimeline,
	pendingRefresh: PendingStepAuthorizationRefresh?,
	observedElapsedRealtimeNanos: Long,
): StepCallbackAttributionReference? {
	if (pendingRefresh != null &&
		observedElapsedRealtimeNanos >= pendingRefresh.effectiveElapsedRealtimeNanos
	) return pendingRefresh
	return timeline.atObservedTime(observedElapsedRealtimeNanos)
		?.let(::ResolvedStepCallbackAttribution)
}

/** Stable across process recreation; callback sequence remains ordering/metrics metadata only. */
internal fun stepProviderDedupKey(
	bootClockDomainId: String,
	observedElapsedRealtimeNanos: Long,
	cumulativeCount: Long,
): String {
	require(bootClockDomainId.isNotBlank())
	require(observedElapsedRealtimeNanos > 0L)
	require(cumulativeCount >= 0L)
	return "steps:${bootClockDomainId.length}:$bootClockDomainId:" +
		"$observedElapsedRealtimeNanos:$cumulativeCount"
}

/** Monotonic same-boot checkpoint ordering; intentionally independent of wall-clock jumps. */
internal fun stepCheckpointOrderMillis(elapsedRealtimeNanos: Long): Long {
	require(elapsedRealtimeNanos >= 0L)
	return elapsedRealtimeNanos / STEP_NANOS_PER_MILLISECOND
}

/** Compact observed-time authorization history; monotonic sensor times prune obsolete entries. */
internal class StepObservedAuthorizationTimeline(
	initialRegistration: SourceRegistration,
	initialSink: SourceEventSink,
) {
	private val entries = ArrayDeque<StepCallbackAttribution>().apply {
		addLast(StepCallbackAttribution(initialRegistration, initialSink))
	}

	fun refresh(registration: SourceRegistration, sink: SourceEventSink) {
		val next = StepCallbackAttribution(registration, sink)
		val latest = entries.last()
		val latestAuthorization = latest.registration.authorization
		val nextAuthorization = registration.authorization
		require(nextAuthorization.effectiveElapsedRealtimeNanos >=
			latestAuthorization.effectiveElapsedRealtimeNanos)
		if (nextAuthorization.authorizationRevision == latestAuthorization.authorizationRevision &&
			nextAuthorization.authorizationFingerprint == latestAuthorization.authorizationFingerprint
		) {
			entries.removeLast()
		}
		entries.addLast(next)
	}

	fun atObservedTime(observedElapsedRealtimeNanos: Long): StepCallbackAttribution? {
		val selectedIndex = entries.indexOfLast { attribution ->
			attribution.registration.authorization.effectiveElapsedRealtimeNanos <=
				observedElapsedRealtimeNanos
		}
		if (selectedIndex < 0) return null
		repeat(selectedIndex) { entries.removeFirst() }
		return entries.first()
	}
}

internal data class RecentStepEvidenceIdentity(
	val bootClockDomainId: String,
	val collectedDataEpoch: Long,
	val registrationGeneration: Long,
	val authorizationRevision: Long,
	val authorizationFingerprint: String,
)

/** Process-local corroboration cache; durable replay never substitutes receipt time for observation. */
internal class RecentStepEvidenceTracker(
	private val elapsedRealtimeNanos: () -> Long,
) {
	private val lock = Any()
	@Volatile private var evidence: Evidence? = null

	fun recordQualified(
		registration: SourceRegistration,
		payload: com.adsamcik.tracker.tracker.source.model.StepCounterWindowPayload,
		observedElapsedNanos: Long,
	) {
		if (registration.purposeEligibilityMask and SourceBrokerPurpose.MASK_CONTROL_AUTOSTART == 0L ||
			payload.deltaCount <= 0L || payload.baselineReset ||
			payload.bootClockDomainId != registration.authorization.effectiveBootId ||
			payload.bootClockDomainId != registration.state.clockDomainId ||
			payload.windowStartElapsedRealtimeNanos <
				registration.authorization.effectiveElapsedRealtimeNanos ||
			payload.windowEndElapsedRealtimeNanos <
				registration.authorization.effectiveElapsedRealtimeNanos ||
			observedElapsedNanos != payload.windowEndElapsedRealtimeNanos
		) return
		val next = Evidence(registration.evidenceIdentity(), observedElapsedNanos)
		synchronized(lock) {
			val current = evidence
			// A duplicate replay retains its original sensor-observed timestamp. It may prove an
			// otherwise-empty cache, but can neither refresh nor replace newer evidence.
			if (current == null || current.identity != next.identity ||
				next.observedElapsedNanos > current.observedElapsedNanos
			) evidence = next
		}
	}

	fun hasRecent(registration: SourceRegistration, maximumAgeNanos: Long): Boolean {
		if (registration.purposeEligibilityMask and SourceBrokerPurpose.MASK_CONTROL_AUTOSTART == 0L) return false
		val snapshot = evidence ?: return false
		if (snapshot.identity != registration.evidenceIdentity()) return false
		val age = elapsedRealtimeNanos() - snapshot.observedElapsedNanos
		return age in 0L..maximumAgeNanos
	}

	private fun SourceRegistration.evidenceIdentity() = RecentStepEvidenceIdentity(
		bootClockDomainId = state.clockDomainId,
		collectedDataEpoch = state.collectedDataEpoch,
		registrationGeneration = state.registrationGeneration,
		authorizationRevision = authorization.authorizationRevision,
		authorizationFingerprint = authorization.authorizationFingerprint,
	)

	private data class Evidence(
		val identity: RecentStepEvidenceIdentity,
		val observedElapsedNanos: Long,
	)
}

internal class StepCallbackToken internal constructor(
	val registrationGeneration: Long,
	val eligibilityFingerprint: String,
)

/**
 * Identity gate shared by the listener callbacks and lifecycle lock. Token identity, rather than
 * generation equality alone, prevents a callback retained by SensorManager from entering a later
 * queue even if malformed persistence were ever to reuse generation metadata.
 */
internal class StepCallbackGenerationGate {
	private var active: StepCallbackToken? = null

	fun activate(registrationGeneration: Long, eligibilityFingerprint: String): StepCallbackToken {
		require(registrationGeneration > 0L)
		require(eligibilityFingerprint.isNotBlank())
		return StepCallbackToken(registrationGeneration, eligibilityFingerprint).also { active = it }
	}

	fun accepts(token: StepCallbackToken): Boolean = active === token

	fun retire(token: StepCallbackToken): Boolean {
		if (active !== token) return false
		active = null
		return true
	}
}

private const val RECENT_CONTROL_STEP_WINDOW_NANOS = 30_000L * 1_000_000L
private const val STEP_NANOS_PER_MILLISECOND = 1_000_000L
internal const val STEP_CALLBACK_LANE_CAPACITY = 64
private val STEP_TRANSIENT_RETRY_DELAYS_MS = longArrayOf(10L, 50L, 250L, 1_000L, 5_000L, 30_000L)
private val STEP_OVERFLOW_RECOVERY_DELAYS_MS = longArrayOf(50L, 250L, 1_000L, 5_000L, 30_000L)
