package com.adsamcik.tracker.tracker.source.runtime

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener2
import android.hardware.SensorManager
import android.os.SystemClock
import com.adsamcik.tracker.shared.base.database.data.SourceRuntimeStateEntity
import com.adsamcik.tracker.shared.base.di.ApplicationScope
import com.adsamcik.tracker.tracker.source.runCatchingNonCancellation
import com.adsamcik.tracker.tracker.altitude.BarometricAltitudeFormula
import com.adsamcik.tracker.tracker.source.ingress.PRESSURE_QUALIFIED_WINDOW_PAYLOAD_VERSION
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.PressurePlan
import com.adsamcik.tracker.tracker.source.model.PressureSensorAccuracy
import com.adsamcik.tracker.tracker.source.model.PressureWindowClosureKind
import com.adsamcik.tracker.tracker.source.model.PressureWindowPayload
import com.adsamcik.tracker.tracker.source.model.SourceApplyStatus
import com.adsamcik.tracker.tracker.source.model.SourceDeliveryCandidate
import com.adsamcik.tracker.tracker.source.model.SourceDeliveryIdentity
import com.adsamcik.tracker.tracker.source.model.SourceDeliveryUnit
import com.adsamcik.tracker.tracker.source.model.SourceEvidenceCandidate
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourceQuality
import com.adsamcik.tracker.tracker.source.model.SourceQualityFlag
import com.adsamcik.tracker.tracker.source.model.sourceDeliveryIdentity
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@Singleton
class PressureSourceRuntime @Inject constructor(
	@ApplicationContext context: Context,
	@ApplicationScope private val applicationScope: CoroutineScope,
	private val registrations: SourceRegistrationRepository,
) : ClaimedSourceRuntime<PressurePlan> {
	override val source: SourceKind = SourceKind.PRESSURE
	private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
	private val sensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
	private val _capabilities = MutableStateFlow(sensor.toCapabilities())
	override val capabilities: StateFlow<SourceCapabilities> = _capabilities

	private val lifecycleMutex = Mutex()
	private val callbackLock = Any()
	private val callbackGate = PressureCallbackGenerationGate()
	private var registration: SourceRegistration? = null
	private var callbackToken: PressureCallbackToken? = null
	private var listener: PressureRegistrationListener? = null
	private var currentPlan: PressurePlan? = null
	private var currentProviderRequest: PressureProviderRequest? = null
	private var currentSink: SourceEventSink? = null
	private var windowLane: Channel<PressureCompletedWindow>? = null
	private var windowCapacity = PressureWindowCapacityGuard(MAX_PENDING_PRESSURE_WINDOWS)
	private var capacityResumeGate = PressureCapacityResumeGate(RESUME_LOW_WATER_WINDOWS)
	private var accumulator: PressureWindowAccumulator? = null
	private var accumulatorAttribution: PressureCallbackAttribution? = null
	private var callbackAttributionTimeline: PressureObservedAuthorizationTimeline? = null
	private var pendingAuthorizationRefresh: PressureAuthorizationRefreshLatch? = null
	private var lastAccumulatedReception: PressureReception? = null
	private var lastAcceptedProviderElapsedNanos: Long? = null
	private var capacityPauseListener: PressureRegistrationListener? = null
	private var capacityPauseRemovalComplete = true
	private var capacityResumeJob: Job? = null
	private var capacityResumeFence = 0L
	private var capacityResumeSerial = 0L
	private var capacityResumeReconciliationAllowed = true
	private var actor: Job? = null
	private var acceptingCallbacks = false
	private var callbackEntrySequence = 0L
	@Volatile private var lastCheckpointedProviderSequence = 0L
	@Volatile private var lastCheckpointedAdmission = EMPTY_PRESSURE_ADMISSION_SNAPSHOT
	@Volatile private var stopDeadlineElapsedNanos: Long? = null
	@Volatile private var laneDrainComplete = true
	private var cutoffElapsedNanos: Long? = null
	private var flushCompletion: CompletableDeferred<Unit>? = null
	private var batchingEnabled = false
	private var metrics = RuntimeAdmissionMetrics()
	private var retirementIntent: PressureProviderRetirementIntent? = null
	private var failureCoverage = PressureFailureRangeCoverage()
	private var exceptionalActorFailurePending = false
	private var terminalSettlementInProgress = false
	private var terminalStopAck: SourceStopAck? = null
	private var runtimeClaim: SourceRuntimeClaim? = null

	override suspend fun start(plan: PressurePlan, sink: SourceEventSink): SourceStartResult = lifecycleMutex.withLock {
		startForClaimLocked(claim = null, plan, sink)
	}

	override suspend fun start(
		claim: SourceRuntimeClaim,
		plan: PressurePlan,
		sink: SourceEventSink,
	): SourceStartResult = lifecycleMutex.withLock {
		require(claim.source == source)
		startForClaimLocked(claim, plan, sink)
	}

	private suspend fun startForClaimLocked(
		claim: SourceRuntimeClaim?,
		plan: PressurePlan,
		sink: SourceEventSink,
	): SourceStartResult {
		if (!settleOwnedRetirementLocked()) return failedStart(plan, registration)
		require(currentPlan == null) { "Pressure source is already started" }
		runtimeClaim = claim.takeIf { plan.enabled }
		return try {
			startLocked(plan, sink).also {
				if (registration == null && retirementIntent == null) runtimeClaim = null
			}
		} catch (error: Throwable) {
			if (registration == null && retirementIntent == null) runtimeClaim = null
			throw error
		}
	}

	override suspend fun reconfigure(plan: PressurePlan, sink: SourceEventSink): SourceApplyResult {
		fenceCapacityResume()
		return lifecycleMutex.withLock { reconfigureForClaimLocked(claim = null, plan, sink) }
	}

	override suspend fun reconfigure(
		claim: SourceRuntimeClaim,
		plan: PressurePlan,
		sink: SourceEventSink,
	): SourceApplyResult {
		require(claim.source == source)
		fenceCapacityResume()
		return lifecycleMutex.withLock { reconfigureForClaimLocked(claim, plan, sink) }
	}

	private suspend fun reconfigureForClaimLocked(
		claim: SourceRuntimeClaim?,
		plan: PressurePlan,
		sink: SourceEventSink,
	): SourceApplyResult {
		if (!settleOwnedRetirementLocked()) return failedApply(plan, registration)
		refreshCompatibleLocked(plan, sink) { runtimeClaim = claim }?.let { refreshed ->
			restoreCapacityResumeForActiveLifecycle()
			return refreshed
		}
		var predecessorStopAck: SourceStopAck? = terminalStopAck
		if (currentPlan != null) {
			val previous = shutdownLocked(null)
			predecessorStopAck = previous
			if (!previous.appDrainComplete ||
				previous.registrationRemovalOutcome != RegistrationRemovalOutcome.REMOVED
			) {
				return SourceApplyResult.Failed(
					appliedState(source, plan.revision, null, SourceApplyStatus.FAILED,
						SystemClock.elapsedRealtimeNanos()),
					retryable = true,
					stopAck = previous,
				)
			}
		}
		if (!plan.enabled) {
			currentSink = sink
			runtimeClaim = null
			return SourceApplyResult.Applied(
				appliedState(source, plan.revision, null, SourceApplyStatus.APPLIED,
					SystemClock.elapsedRealtimeNanos()),
				stopAck = predecessorStopAck,
			)
		}
		runtimeClaim = claim
		return when (val result = startLocked(plan, sink)) {
			is SourceStartResult.Started -> SourceApplyResult.Applied(result.applied, predecessorStopAck)
			is SourceStartResult.Degraded -> SourceApplyResult.Degraded(result.applied, predecessorStopAck)
			is SourceStartResult.Blocked -> SourceApplyResult.Failed(
				result.applied,
				retryable = false,
				stopAck = predecessorStopAck,
			).also {
				if (registration == null && retirementIntent == null) runtimeClaim = null
			}
			is SourceStartResult.Failed -> SourceApplyResult.Failed(
				result.applied,
				result.retryable,
				predecessorStopAck,
			).also {
				if (registration == null && retirementIntent == null) runtimeClaim = null
			}
		}
	}

	/** Refreshes authorization and policy state without replacing compatible SensorManager work. */
	internal suspend fun refreshCompatible(
		plan: PressurePlan,
		sink: SourceEventSink,
	): SourceApplyResult? = lifecycleMutex.withLock {
		if (!settleOwnedRetirementLocked()) failedApply(plan, registration) else refreshCompatibleLocked(plan, sink)
	}

	override suspend fun quiesce(cutoff: SessionCutoff): SourceStopAck {
		fenceCapacityResume()
		return lifecycleMutex.withLock {
			(requireNotNull(terminalStopAck ?: shutdownLocked(cutoff))).also(::clearClaimIfReleased)
		}
	}

	override suspend fun shutdownIfOwned(
		claim: SourceRuntimeClaim,
		cutoff: SessionCutoff,
	): OwnedSourceShutdown = lifecycleMutex.withLock {
		require(claim.source == source)
		if (runtimeClaim != claim) return@withLock OwnedSourceShutdown.NotOwned
		fenceCapacityResume()
		val acknowledgement = when {
			currentPlan != null || retirementIntent != null -> shutdownLocked(cutoff)
			terminalStopAck != null -> requireNotNull(terminalStopAck)
			else -> null
		}
		if (acknowledgement == null) {
			runtimeClaim = null
			OwnedSourceShutdown.Released(provider = null, stopAck = null)
		} else {
			acknowledgement.toOwnedShutdown().also { shutdown ->
				if (shutdown is OwnedSourceShutdown.Released) runtimeClaim = null
			}
		}
	}

	override suspend fun close() {
		fenceCapacityResume()
		lifecycleMutex.withLock {
			if (currentPlan != null || retirementIntent != null) {
				clearClaimIfReleased(shutdownLocked(null))
			} else {
				runtimeClaim = null
			}
		}
	}

	private fun clearClaimIfReleased(acknowledgement: SourceStopAck) {
		if (acknowledgement.toOwnedShutdown() is OwnedSourceShutdown.Released) runtimeClaim = null
	}

	private fun failedStart(plan: PressurePlan, active: SourceRegistration?) = SourceStartResult.Failed(
		appliedState(source, plan.revision, active, SourceApplyStatus.FAILED, SystemClock.elapsedRealtimeNanos()),
		retryable = true,
	)

	private fun failedApply(plan: PressurePlan, active: SourceRegistration?) = SourceApplyResult.Failed(
		appliedState(source, plan.revision, active, SourceApplyStatus.FAILED, SystemClock.elapsedRealtimeNanos()),
		retryable = true,
	)

	private suspend fun startLocked(plan: PressurePlan, sink: SourceEventSink): SourceStartResult {
		terminalStopAck = null
		if (!plan.enabled) {
			return SourceStartResult.Started(
				appliedState(source, plan.revision, null, SourceApplyStatus.APPLIED, SystemClock.elapsedRealtimeNanos()),
			)
		}
		val pressureSensor = sensor ?: return SourceStartResult.Blocked(
			appliedState(source, plan.revision, null, SourceApplyStatus.BLOCKED, SystemClock.elapsedRealtimeNanos()),
		)
		val providerRequest = plan.toPressureProviderRequest(
			sensorMinimumDelayMicros = pressureSensor.minDelay,
			sensorMaximumDelayMicros = pressureSensor.maxDelay,
			fifoMaxEventCount = pressureSensor.fifoMaxEventCount,
		)
		val nextRegistration = runCatchingNonCancellation {
			registrations.begin(
				source,
				plan.revision,
				providerRequest.physicalConfigurationFingerprint,
				System.currentTimeMillis(),
			)
		}.getOrElse {
			return SourceStartResult.Failed(
				appliedState(source, plan.revision, null, SourceApplyStatus.FAILED, SystemClock.elapsedRealtimeNanos()),
				retryable = true,
			)
		}
		val saved = registrations.loadRuntimeState(nextRegistration)
		val recovery = recoverPressureRuntimeState(
			saved = saved,
			currentRegistrationGeneration = nextRegistration.state.registrationGeneration,
			reusedActiveRegistration = !nextRegistration.requiresProviderAcceptance,
		)
		val nextAccumulator = newPressureAccumulator(plan, providerRequest, nextRegistration)
		val restored = recovery.metrics
		metrics = RuntimeAdmissionMetrics(
			lastDurablyAdmittedSequence = restored?.lastDurablyAdmittedSequence,
			lastAdmissionOrdinal = restored?.lastAdmissionOrdinal,
			failedAdmissionCount = restored?.failedAdmissionCount ?: 0L,
			unresolvedSequenceStart = restored?.unresolvedSequenceStart,
			unresolvedSequenceEndInclusive = restored?.unresolvedSequenceEndInclusive,
			gapClassifications = restored?.gapClassifications.orEmpty(),
		)
		failureCoverage = PressureFailureRangeCoverage()
		exceptionalActorFailurePending = false
		terminalSettlementInProgress = false
		lastCheckpointedAdmission = metrics.snapshot()
		callbackEntrySequence = recovery.callbackEntrySequence
		lastCheckpointedProviderSequence = recovery.callbackEntrySequence
		if (recovery.requiresPreAcquisitionCheckpoint) {
			val recoveryCheckpointElapsedNanos = SystemClock.elapsedRealtimeNanos()
			val restartGapPersisted = runCatchingNonCancellation {
				registrations.saveSensorRuntimeCheckpoint(
					registration = nextRegistration,
					lastProviderSequence = recovery.callbackEntrySequence,
					checkpoint = SensorRuntimeCheckpoint(
						RuntimeCheckpointLifecycle.ACTIVE,
						requireNotNull(recovery.metrics),
						PRESSURE_RUNTIME_COMPONENT_VERSION,
						ByteArray(0),
						causalOrderElapsedRealtimeNanos = recoveryCheckpointElapsedNanos,
					),
					updatedAtMs = pressureCheckpointOrderMillis(recoveryCheckpointElapsedNanos),
				)
			}.isSuccess
			if (!restartGapPersisted) {
				return SourceStartResult.Failed(
					appliedState(source, plan.revision, nextRegistration, SourceApplyStatus.FAILED,
						SystemClock.elapsedRealtimeNanos()),
					retryable = true,
				)
			}
			lastCheckpointedProviderSequence = recovery.callbackEntrySequence
			lastCheckpointedAdmission = requireNotNull(recovery.metrics)
		}
		cutoffElapsedNanos = null
		stopDeadlineElapsedNanos = null
		laneDrainComplete = true
		val nextWindowLane = Channel<PressureCompletedWindow>(MAX_PENDING_PRESSURE_WINDOWS)
		val nextToken = synchronized(callbackLock) {
			callbackGate.activate(
				nextRegistration.state.registrationGeneration,
				nextRegistration.eligibilityFingerprint,
			).also { callbackToken = it }
		}
		val nextListener = PressureRegistrationListener(nextToken)
		registration = nextRegistration
		listener = nextListener
		currentPlan = plan
		currentProviderRequest = providerRequest
		currentSink = sink
		synchronized(callbackLock) {
			windowLane = nextWindowLane
			windowCapacity = PressureWindowCapacityGuard(MAX_PENDING_PRESSURE_WINDOWS)
			capacityResumeGate = PressureCapacityResumeGate(RESUME_LOW_WATER_WINDOWS)
			capacityResumeReconciliationAllowed = true
			accumulator = nextAccumulator
			accumulatorAttribution = PressureCallbackAttribution(nextRegistration, sink)
			callbackAttributionTimeline = PressureObservedAuthorizationTimeline(nextRegistration, sink)
			lastAccumulatedReception = null
			lastAcceptedProviderElapsedNanos = null
			capacityPauseListener = null
			capacityPauseRemovalComplete = true
			pendingAuthorizationRefresh = null
			acceptingCallbacks = true
		}
		val registered = try {
				sensorManager.registerListener(
					nextListener,
					pressureSensor,
					providerRequest.samplePeriodMicros,
					providerRequest.maximumReportLatencyMicros,
				)
		} catch (cancelled: CancellationException) {
			cleanupFailedStart(nextRegistration, nextListener, nextToken, nextWindowLane,
				"PROVIDER_REGISTRATION_CANCELLED")
			throw cancelled
		} catch (fatal: Error) {
			cleanupFailedStart(nextRegistration, nextListener, nextToken, nextWindowLane,
				"PROVIDER_REGISTRATION_FATAL")
			throw fatal
		} catch (_: Exception) {
			false
		}
		if (!registered) {
			cleanupFailedStart(nextRegistration, nextListener, nextToken, nextWindowLane,
				"PROVIDER_REGISTRATION_FAILED")
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
			cleanupFailedStart(nextRegistration, nextListener, nextToken, nextWindowLane,
				"REGISTRATION_ACCEPTANCE_CANCELLED")
			throw cancelled
		} catch (fatal: Error) {
			cleanupFailedStart(nextRegistration, nextListener, nextToken, nextWindowLane,
				"REGISTRATION_ACCEPTANCE_FATAL")
			throw fatal
		} catch (_: Exception) {
			false
		}
		if (!accepted) {
			cleanupFailedStart(nextRegistration, nextListener, nextToken, nextWindowLane,
				"REGISTRATION_ACCEPTANCE_STALE")
			return SourceStartResult.Failed(
				appliedState(source, plan.revision, nextRegistration, SourceApplyStatus.FAILED, SystemClock.elapsedRealtimeNanos()),
				retryable = true,
			)
		}
		val nextActor = applicationScope.launch(start = CoroutineStart.LAZY) {
			laneDrainComplete = consume(nextWindowLane) == PressureWindowLaneDrain.COMPLETE
		}
		actor = nextActor
		nextActor.invokeOnCompletion { failure ->
			if (failure == null) return@invokeOnCompletion
			val ownsFailedLane = synchronized(callbackLock) {
				if (actor !== nextActor || registration !== nextRegistration ||
					terminalSettlementInProgress
				) {
					false
				} else {
					acceptingCallbacks = false
					callbackToken?.let(callbackGate::retire)
					callbackGate.retire(nextToken)
					capacityResumeReconciliationAllowed = false
					capacityResumeGate.fenceResume()
					exceptionalActorFailurePending = true
					laneDrainComplete = false
					nextWindowLane.close()
					true
				}
			}
			if (ownsFailedLane) {
				applicationScope.launch {
					settleExceptionalActorFailure(nextActor, nextRegistration, nextListener, nextToken)
				}
			}
		}
		nextActor.start()
		batchingEnabled = providerRequest.batchingEnabled
		val state = pressureAppliedState(plan, nextRegistration, providerRequest)
		return if (providerRequest.degradedReasons.isEmpty()) {
			SourceStartResult.Started(state)
		} else {
			SourceStartResult.Degraded(state)
		}
	}

	private fun newPressureAccumulator(
		plan: PressurePlan,
		providerRequest: PressureProviderRequest,
		activeRegistration: SourceRegistration,
	) = PressureWindowAccumulator(
		windowNanos = plan.aggregationWindowMs.coerceAtLeast(1L) * NANOS_PER_MILLISECOND,
		effectiveSamplePeriodMicros = providerRequest.samplePeriodMicros,
		effectiveMaximumReportLatencyMicros = providerRequest.maximumReportLatencyMicros,
		boundary = activeRegistration.pressureAccumulatorBoundary(),
	)

	private suspend fun refreshCompatibleLocked(
		plan: PressurePlan,
		sink: SourceEventSink,
		onAuthorizationCommitted: () -> Unit = {},
	): SourceApplyResult? {
		val activePlan = currentPlan ?: return null
		val activeProviderRequest = currentProviderRequest ?: return null
		val activeRegistration = registration ?: return null
		val activeSink = currentSink ?: return null
		val pressureSensor = sensor ?: return null
		val providerRequest = plan.toPressureProviderRequest(
			sensorMinimumDelayMicros = pressureSensor.minDelay,
			sensorMaximumDelayMicros = pressureSensor.maxDelay,
			fifoMaxEventCount = pressureSensor.fifoMaxEventCount,
		)
		if (!plan.enabled ||
			activeProviderRequest.physicalConfigurationFingerprint !=
			providerRequest.physicalConfigurationFingerprint
		) return null
		val refreshLatch = synchronized(callbackLock) {
			if (capacityResumeGate.capacityPaused) return null
			if (!acceptingCallbacks || pendingAuthorizationRefresh != null) null else {
				PressureAuthorizationRefreshLatch(
					effectiveElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
					maximumBufferedSamples = MAX_PENDING_PRESSURE_REFRESH_SAMPLES,
				).also { pendingAuthorizationRefresh = it }
			}
		}
		if (refreshLatch == null) {
			return SourceApplyResult.Failed(
				appliedState(source, plan.revision, activeRegistration, SourceApplyStatus.FAILED,
					SystemClock.elapsedRealtimeNanos()),
				retryable = true,
			)
		}
		val refreshResult = try {
			runCatchingNonCancellation {
				registrations.refreshActiveAuthorization(
					source,
					activeRegistration,
					plan.revision,
					providerRequest.physicalConfigurationFingerprint,
					System.currentTimeMillis(),
					refreshLatch.effectiveElapsedRealtimeNanos,
				)
			}
		} catch (cancellation: kotlinx.coroutines.CancellationException) {
			synchronized(callbackLock) {
				resolvePendingAuthorizationRefreshLocked(
					refreshLatch,
					activePlan,
					activeProviderRequest,
					PressureCallbackAttribution(activeRegistration, activeSink),
					refreshed = false,
				)
			}
			drainCapacityPause()
			throw cancellation
		}
		val refreshed = refreshResult.getOrElse {
			synchronized(callbackLock) {
				resolvePendingAuthorizationRefreshLocked(
					refreshLatch,
					activePlan,
					activeProviderRequest,
					PressureCallbackAttribution(activeRegistration, activeSink),
					refreshed = false,
				)
			}
			drainCapacityPause()
			return SourceApplyResult.Failed(
				appliedState(source, plan.revision, activeRegistration, SourceApplyStatus.FAILED,
					SystemClock.elapsedRealtimeNanos()),
				retryable = true,
			)
		} ?: run {
			synchronized(callbackLock) {
				resolvePendingAuthorizationRefreshLocked(
					refreshLatch,
					activePlan,
					activeProviderRequest,
					PressureCallbackAttribution(activeRegistration, activeSink),
					refreshed = false,
				)
			}
			drainCapacityPause()
			return null
		}
		if (refreshed.state.sourceInstanceId != activeRegistration.state.sourceInstanceId ||
			refreshed.state.registrationGeneration != activeRegistration.state.registrationGeneration ||
			refreshed.physicalConfigurationFingerprint != activeRegistration.physicalConfigurationFingerprint ||
			refreshed.requiresProviderAcceptance
		) {
			synchronized(callbackLock) {
				resolvePendingAuthorizationRefreshLocked(
					refreshLatch,
					activePlan,
					activeProviderRequest,
					PressureCallbackAttribution(activeRegistration, activeSink),
					refreshed = false,
				)
			}
			drainCapacityPause()
			return null
		}
		onAuthorizationCommitted()
		val swapped = synchronized(callbackLock) {
			resolvePendingAuthorizationRefreshLocked(
				refreshLatch,
				plan,
				providerRequest,
				PressureCallbackAttribution(
					registration = refreshed,
					sink = sink,
					effectiveElapsedRealtimeNanos = refreshLatch.effectiveElapsedRealtimeNanos,
				),
				refreshed = true,
			)
		}
		drainCapacityPause()
		if (!swapped) {
			return SourceApplyResult.Failed(
				appliedState(source, plan.revision, activeRegistration, SourceApplyStatus.FAILED,
					SystemClock.elapsedRealtimeNanos()),
				retryable = true,
			)
		}
		val state = pressureAppliedState(plan, refreshed, providerRequest)
		return if (providerRequest.degradedReasons.isEmpty()) {
			SourceApplyResult.Applied(state)
		} else {
			SourceApplyResult.Degraded(state)
		}
	}

	private fun pressureAppliedState(
		plan: PressurePlan,
		activeRegistration: SourceRegistration,
		providerRequest: PressureProviderRequest,
	) = appliedState(
		source = source,
		revision = plan.revision,
		registration = activeRegistration,
		status = if (providerRequest.degradedReasons.isEmpty()) {
			SourceApplyStatus.APPLIED
		} else {
			SourceApplyStatus.DEGRADED
		},
		elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
	).copy(degradedReasons = providerRequest.degradedReasons)

	private suspend fun cleanupFailedStart(
		failedRegistration: SourceRegistration,
		failedListener: PressureRegistrationListener,
		failedToken: PressureCallbackToken,
		failedLane: Channel<PressureCompletedWindow>,
		reason: String,
	) = withContext(NonCancellable) {
		val retirementBoundary = synchronized(callbackLock) {
			acceptingCallbacks = false
			callbackGate.retire(failedToken)
			failedLane.close()
			SystemClock.elapsedRealtimeNanos()
		}
		val retirement = retireProviderRegistration(
			failedRegistration,
			failedListener,
			failedToken,
			reason,
			retirementBoundary,
		)
		val actorSettled = settleRetainedActor(null)
		if (retirement == PressureProviderRetirement.COMPLETE && actorSettled) clearActiveState()
		else retainProviderForRetirementRetry(retainActor = !actorSettled)
	}

	private suspend fun settleOwnedRetirementLocked(): Boolean {
		val intent = retirementIntent ?: return true
		val retirement = retireProviderRegistration(
			intent.registration,
			intent.listener,
			intent.callbackToken,
			intent.reason,
			intent.retiredElapsedRealtimeNanos,
		)
		val actorSettled = if (retirement == PressureProviderRetirement.COMPLETE) {
			settleRetainedActor(null)
		} else {
			false
		}
		return if (retirement == PressureProviderRetirement.COMPLETE && actorSettled) {
			clearActiveState()
			true
		} else {
			retainProviderForRetirementRetry(retainActor = !actorSettled && actor != null)
			false
		}
	}

	/** Durable terminal fence -> exact listener removal -> exact token completion. */
	private suspend fun retireProviderRegistration(
		activeRegistration: SourceRegistration,
		activeListener: SensorEventListener2,
		activeToken: PressureCallbackToken,
		reason: String,
		retiredElapsedRealtimeNanos: Long,
		deadlineElapsedRealtimeNanos: Long? = null,
	): PressureProviderRetirement = withContext(NonCancellable) {
		val intent = synchronized(callbackLock) {
			retirementIntent?.also { retained ->
				check(retained.registration.samePressureRegistrationAs(activeRegistration))
				check(retained.listener === activeListener)
				check(retained.callbackToken === activeToken)
				check(retained.retiredElapsedRealtimeNanos == retiredElapsedRealtimeNanos)
			} ?: PressureProviderRetirementIntent(
				registration = activeRegistration,
				listener = activeListener,
				callbackToken = activeToken,
				reason = reason,
				retiredAtMs = System.currentTimeMillis(),
				retiredElapsedRealtimeNanos = retiredElapsedRealtimeNanos,
				providerRemovalComplete = capacityResumeGate.capacityPaused && capacityPauseRemovalComplete,
			).also { retirementIntent = it }
		}
		val durableToken = intent.retirementToken ?: run {
			var operationCompleted = false
			val token = withTimeoutOrNull(providerSettlementTimeoutMs(deadlineElapsedRealtimeNanos)) {
				val result = runCatchingNonCancellation {
					registrations.beginRetirement(
						intent.registration,
						intent.reason,
						intent.retiredAtMs,
						intent.retiredElapsedRealtimeNanos,
					)
				}
				operationCompleted = true
				result.getOrNull()
			}
			if (!operationCompleted) return@withContext PressureProviderRetirement.TIMED_OUT
			token ?: return@withContext PressureProviderRetirement.NOT_DURABLE
		}.also { intent.retirementToken = it }
		if (!intent.providerRemovalComplete) {
			val removed = try {
				sensorManager.unregisterListener(intent.listener)
				true
			} catch (cancelled: CancellationException) {
				throw cancelled
			} catch (_: Exception) {
				false
			}
			if (!removed) return@withContext PressureProviderRetirement.PENDING
			intent.providerRemovalComplete = true
		}
		if (intent.durableCompletionComplete) return@withContext PressureProviderRetirement.COMPLETE
		var operationCompleted = false
		val completed = withTimeoutOrNull(providerSettlementTimeoutMs(deadlineElapsedRealtimeNanos)) {
			val result = runCatchingNonCancellation {
				registrations.completeRetirement(durableToken)
			}
			operationCompleted = true
			result.getOrDefault(false)
		}
		if (!operationCompleted) return@withContext PressureProviderRetirement.TIMED_OUT
		if (completed == true) {
			intent.durableCompletionComplete = true
			PressureProviderRetirement.COMPLETE
		} else {
			PressureProviderRetirement.PENDING
		}
	}

	private fun providerSettlementTimeoutMs(deadlineElapsedRealtimeNanos: Long?): Long =
		deadlineElapsedRealtimeNanos?.let { deadline ->
			((deadline - SystemClock.elapsedRealtimeNanos()) / NANOS_PER_MILLISECOND)
				.coerceIn(1L, PROVIDER_FLUSH_TIMEOUT_MS)
		} ?: PROVIDER_FLUSH_TIMEOUT_MS

	private suspend fun settleRetainedActor(deadlineElapsedRealtimeNanos: Long?): Boolean {
		val retainedActor = actor ?: return true
		if (retainedActor.isCompleted) return true
		retainedActor.cancel()
		return withContext(NonCancellable) {
			withTimeoutOrNull(providerSettlementTimeoutMs(deadlineElapsedRealtimeNanos)) {
				retainedActor.join()
				true
			} == true
		}
	}

	private suspend fun shutdownLocked(cutoff: SessionCutoff?): SourceStopAck {
		val acknowledgement = withContext(NonCancellable) { settleShutdownLocked(cutoff) }
		currentCoroutineContext().ensureActive()
		return acknowledgement
	}

	private suspend fun settleShutdownLocked(cutoff: SessionCutoff?): SourceStopAck {
		val activeRegistration = registration ?: return unavailableAck(cutoff)
		val activeListener = listener ?: return unavailableAck(cutoff)
		val activeToken = callbackToken ?: return unavailableAck(cutoff)
		synchronized(callbackLock) {
			terminalSettlementInProgress = true
			cutoffElapsedNanos = cutoff?.elapsedRealtimeNanos
		}
		stopDeadlineElapsedNanos = cutoff?.deadlineElapsedRealtimeNanos
		val flushOutcome = if (retirementIntent == null) flushProvider(cutoff, activeListener, activeToken)
		else ProviderFlushOutcome.NOT_REQUESTED
		val barrier: Long
		val retirementBoundary: Long
		synchronized(callbackLock) {
			acceptingCallbacks = false
			callbackGate.retire(activeToken)
			barrier = callbackEntrySequence
			retirementBoundary = retirementIntent?.retiredElapsedRealtimeNanos
				?: SystemClock.elapsedRealtimeNanos()
			val terminalWindow = if (exceptionalActorFailurePending) {
				null
			} else {
				drainPressureWindowAtBoundary(accumulator, lastAccumulatedReception)
			}
			val terminalAttribution = accumulatorAttribution
			if (terminalWindow != null) {
				checkNotNull(terminalAttribution) {
					"Pressure terminal window had no observed-time authorization attribution"
				}
				enqueueWindowLocked(
					payload = terminalWindow.payload,
					reception = terminalWindow.reception,
					activeRegistration = terminalAttribution.registration,
					sink = terminalAttribution.sink,
					lifecycle = RuntimeCheckpointLifecycle.QUIESCED,
					terminal = true,
					checkpointOrderElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
				)
			}
			lastAccumulatedReception = null
		}
		val retirement = retireProviderRegistration(
			activeRegistration,
			activeListener,
			activeToken,
			"ORDERLY_STOP",
			retirementBoundary,
			cutoff?.deadlineElapsedRealtimeNanos,
		)
		val removal = if (retirement == PressureProviderRetirement.COMPLETE) {
			RegistrationRemovalOutcome.REMOVED
		} else RegistrationRemovalOutcome.FAILED
		synchronized(callbackLock) { windowLane?.close() }
		val activeActor = actor
		val actorJoined = if (activeActor == null) true else {
			val remainingMs = cutoff?.let {
				((it.deadlineElapsedRealtimeNanos - SystemClock.elapsedRealtimeNanos()) / NANOS_PER_MILLISECOND)
					.coerceAtLeast(1L)
			} ?: DEFAULT_DRAIN_TIMEOUT_MS
			withTimeoutOrNull(remainingMs) { activeActor.join(); true } == true
		}
		val drainComplete = actorJoined && laneDrainComplete &&
			windowCapacity.pendingWindowCount == 0 &&
			lastCheckpointedProviderSequence >= barrier
		val actorSettled = drainComplete || settleRetainedActor(cutoff?.deadlineElapsedRealtimeNanos)
		if (!drainComplete) {
			val firstAfterCheckpoint = lastCheckpointedProviderSequence + 1L
			if (firstAfterCheckpoint <= barrier) {
				recordPressureFailureExactly(
					firstAfterCheckpoint..barrier,
					RuntimeGapClassification.DRAIN_TIMED_OUT,
				)
			}
		}
		// Atomic window admission intentionally checkpoints ACTIVE. The terminal lifecycle barrier is
		// a separate no-fact monotonic save, even when the final partial became a durable fact.
		persistTerminalCheckpoint(
			activeRegistration,
			lastCheckpointedProviderSequence,
			if (drainComplete) RuntimeCheckpointLifecycle.QUIESCED else RuntimeCheckpointLifecycle.TIMED_OUT,
		)
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
				retirement == PressureProviderRetirement.TIMED_OUT || !drainComplete ->
					SourceStopStatus.TIMED_OUT
				removal == RegistrationRemovalOutcome.FAILED -> SourceStopStatus.PROVIDER_FAILED
				else -> SourceStopStatus.COMPLETE
			},
		).withSessionMembership(runtimeClaim)
		if (retirement == PressureProviderRetirement.COMPLETE && actorSettled) {
			clearActiveState()
			terminalStopAck = ack
		}
		else retainProviderForRetirementRetry(retainActor = !actorSettled)
		return ack
	}

	private suspend fun settleExceptionalActorFailure(
		failedActor: Job,
		failedRegistration: SourceRegistration,
		failedListener: PressureRegistrationListener,
		failedToken: PressureCallbackToken,
	) = lifecycleMutex.withLock {
		if (actor !== failedActor || registration !== failedRegistration) return@withLock
		synchronized(callbackLock) {
			if (listener == null) listener = failedListener
			if (callbackToken == null) callbackToken = failedToken
		}
		if (terminalStopAck == null) settleShutdownLocked(null)
	}

	private suspend fun flushProvider(
		cutoff: SessionCutoff?,
		activeListener: PressureRegistrationListener,
		activeToken: PressureCallbackToken,
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

	private suspend fun consume(windows: Channel<PressureCompletedWindow>): PressureWindowLaneDrain =
		consumePressureWindowLane(
			windows = windows,
			processHead = ::processWindowHead,
			onSettled = ::onWindowSettled,
		)

	private fun onWindowSettled(window: PressureCompletedWindow) {
		val scheduleResume = synchronized(callbackLock) {
			windowCapacity.windowSettled()
			window.settlement?.complete(Unit)
			capacityResumeGate.onSuccessfulPostGapCheckpoint(
				windowCapacity.pendingWindowCount,
				window.checkpointedCapacityGapSequence,
			)
		}
		if (scheduleResume) scheduleCapacityResume()
	}

	private fun scheduleCapacityResume() {
		val job = synchronized(callbackLock) {
			if (!capacityResumeReconciliationAllowed ||
				!capacityResumeGate.capacityPaused ||
				!capacityResumeGate.postGapCheckpointDurable ||
				capacityResumeJob?.isActive == true
			) return
			val fence = capacityResumeFence
			val serial = ++capacityResumeSerial
			applicationScope.launch(start = CoroutineStart.LAZY) {
				try {
					reconcileCapacityResume(fence)
				} finally {
					finishCapacityResume(serial)
				}
			}.also { capacityResumeJob = it }
		}
		job.start()
	}

	private suspend fun reconcileCapacityResume(fence: Long) {
		val prepared = lifecycleMutex.withLock {
			prepareCapacityResumeLocked(fence)
		} ?: return
		resumePressureAfterPartialSettlement(prepared.partialSettlement) {
			reconcilePressureResumeUntilSettled(
				attempt = {
					lifecycleMutex.withLock { attemptCapacityResumeLocked(prepared.context) }
				},
			)
		}
	}

	private fun prepareCapacityResumeLocked(fence: Long): PreparedPressureCapacityResume? =
		synchronized(callbackLock) {
			val plan = currentPlan ?: return@synchronized null
			val providerRequest = currentProviderRequest ?: return@synchronized null
			val activeRegistration = registration ?: return@synchronized null
			val sink = currentSink ?: return@synchronized null
			if (!capacityResumeCurrentLocked(fence, plan, providerRequest, activeRegistration, sink)) {
				return@synchronized null
			}
			val context = PressureCapacityResumeContext(
				fence,
				plan,
				providerRequest,
				activeRegistration,
				sink,
			)
			val boundaryWindow = drainPressureWindowAtBoundary(accumulator, lastAccumulatedReception)
			val boundaryAttribution = accumulatorAttribution
			val settlement = boundaryWindow?.let { boundary ->
				checkNotNull(boundaryAttribution) {
					"Pressure paused partial had no observed-time authorization attribution"
				}
				CompletableDeferred<Unit>().also { completion ->
					enqueueWindowLocked(
						payload = boundary.payload,
						reception = boundary.reception,
						activeRegistration = boundaryAttribution.registration,
						sink = boundaryAttribution.sink,
						lifecycle = RuntimeCheckpointLifecycle.ACTIVE,
						terminal = true,
						settlement = completion,
						checkpointOrderElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
					)
				}
			}
			lastAccumulatedReception = null
			PreparedPressureCapacityResume(context, settlement)
		}

	private fun attemptCapacityResumeLocked(
		context: PressureCapacityResumeContext,
	): PressureResumeAttempt {
		val pressureSensor = sensor ?: return PressureResumeAttempt.STALE
		// Confirm the retired handle's removal before reusing this physical-configuration generation.
		// Old and fresh listeners are token-fenced and are never intentionally registered together.
		val oldListenerToRemove = synchronized(callbackLock) {
			if (capacityPauseRemovalComplete) null else listener
		}
		if (oldListenerToRemove != null) {
			val removed = try {
				sensorManager.unregisterListener(oldListenerToRemove)
				true
			} catch (cancelled: CancellationException) {
				throw cancelled
			} catch (_: Exception) {
				false
			}
			if (!removed) return PressureResumeAttempt.RETRY
			synchronized(callbackLock) {
				if (!capacityResumeCurrentLocked(
						context.fence,
						context.plan,
						context.providerRequest,
						context.registration,
						context.sink,
					)
				) return PressureResumeAttempt.STALE
				capacityPauseRemovalComplete = true
				if (listener === oldListenerToRemove) {
					listener = null
					callbackToken = null
				}
			}
		}
		val canAttempt = synchronized(callbackLock) {
			capacityResumeCurrentLocked(
				context.fence,
				context.plan,
				context.providerRequest,
				context.registration,
				context.sink,
			) && accumulator?.snapshot() == null
		}
		if (!canAttempt) return PressureResumeAttempt.STALE

		val token = synchronized(callbackLock) {
			callbackGate.activate(
				context.registration.state.registrationGeneration,
				context.registration.eligibilityFingerprint,
			)
		}
		val resumedListener = PressureRegistrationListener(token)
		val stillCurrentBeforeRegister = synchronized(callbackLock) {
			capacityResumeCurrentLocked(
				context.fence,
				context.plan,
				context.providerRequest,
				context.registration,
				context.sink,
			) && callbackGate.accepts(token)
		}
		if (!stillCurrentBeforeRegister) {
			synchronized(callbackLock) { callbackGate.retire(token) }
			return PressureResumeAttempt.STALE
		}
		synchronized(callbackLock) {
			// SensorManager may retain a listener even when registration reports failure. Publish the
			// exact candidate before the call so cleanup/retry can never substitute a new object.
			callbackToken = token
			listener = resumedListener
			capacityPauseRemovalComplete = false
		}
		val registered = try {
				sensorManager.registerListener(
					resumedListener,
					pressureSensor,
					context.providerRequest.samplePeriodMicros,
					context.providerRequest.maximumReportLatencyMicros,
				)
		} catch (cancelled: CancellationException) {
			cleanupFailedCapacityResumeCandidate(token, resumedListener)
			throw cancelled
		} catch (fatal: Error) {
			cleanupFailedCapacityResumeCandidate(token, resumedListener)
			throw fatal
		} catch (_: Exception) {
			false
		}
		if (!registered) {
			cleanupFailedCapacityResumeCandidate(token, resumedListener)
			return PressureResumeAttempt.RETRY
		}

		val accepted = synchronized(callbackLock) {
			if (!capacityResumeCurrentLocked(
					context.fence,
					context.plan,
					context.providerRequest,
					context.registration,
					context.sink,
				) || !callbackGate.accepts(token)
			) {
				callbackGate.retire(token)
				false
			} else {
				// This is a token-fenced pause/resume of one unchanged physical configuration,
				// never a concurrent second registration or an authorization-generation change.
				callbackToken = token
				listener = resumedListener
				accumulator = newPressureAccumulator(
					context.plan,
					context.providerRequest,
					context.registration,
				)
				accumulatorAttribution = PressureCallbackAttribution(context.registration, context.sink)
				lastAccumulatedReception = null
				capacityResumeGate.onResumeSucceeded()
				capacityPauseRemovalComplete = true
				acceptingCallbacks = true
				true
			}
		}
		if (!accepted) {
			return if (cleanupFailedCapacityResumeCandidate(token, resumedListener)) {
				PressureResumeAttempt.STALE
			} else PressureResumeAttempt.RETRY
		}
		return PressureResumeAttempt.RESUMED
	}

	private fun cleanupFailedCapacityResumeCandidate(
		token: PressureCallbackToken,
		candidate: PressureRegistrationListener,
	): Boolean {
		synchronized(callbackLock) { callbackGate.retire(token) }
		val removed = try {
			sensorManager.unregisterListener(candidate)
			true
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: Exception) {
			false
		}
		synchronized(callbackLock) {
			if (listener === candidate) {
				capacityPauseRemovalComplete = removed
				// Even after defensive removal succeeds, retain this exact candidate/token as the
				// provider-removed owner until a lifecycle-locked retry atomically supersedes it or
				// terminal retirement durably consumes it.
			}
		}
		return removed
	}

	private fun capacityResumeCurrentLocked(
		fence: Long,
		plan: PressurePlan,
		providerRequest: PressureProviderRequest,
		activeRegistration: SourceRegistration,
		sink: SourceEventSink,
	): Boolean = capacityResumeFence == fence &&
		capacityResumeReconciliationAllowed &&
		capacityResumeGate.capacityPaused &&
		capacityResumeGate.postGapCheckpointDurable &&
		!acceptingCallbacks &&
		cutoffElapsedNanos == null &&
		stopDeadlineElapsedNanos == null &&
		currentPlan === plan &&
		currentProviderRequest === providerRequest &&
		registration === activeRegistration &&
		currentSink === sink &&
		windowLane != null

	private fun fenceCapacityResume() {
		val job = synchronized(callbackLock) {
			capacityResumeFence++
			capacityResumeReconciliationAllowed = false
			capacityResumeGate.fenceResume()
			capacityResumeJob
		}
		job?.cancel()
	}

	private fun restoreCapacityResumeForActiveLifecycle() {
		val schedule = synchronized(callbackLock) {
			if (currentPlan == null || cutoffElapsedNanos != null || stopDeadlineElapsedNanos != null) {
				return@synchronized false
			}
			capacityResumeReconciliationAllowed = true
			capacityResumeGate.capacityPaused && capacityResumeGate.postGapCheckpointDurable
		}
		if (schedule) scheduleCapacityResume()
	}

	private fun finishCapacityResume(serial: Long) {
		synchronized(callbackLock) {
			if (capacityResumeSerial != serial) return
			capacityResumeJob = null
			if (capacityResumeGate.capacityPaused) capacityResumeGate.fenceResume()
		}
	}

	private data class PressureCapacityResumeContext(
		val fence: Long,
		val plan: PressurePlan,
		val providerRequest: PressureProviderRequest,
		val registration: SourceRegistration,
		val sink: SourceEventSink,
	)

	private data class PreparedPressureCapacityResume(
		val context: PressureCapacityResumeContext,
		val partialSettlement: CompletableDeferred<Unit>?,
	)

	private suspend fun processWindowHead(window: PressureCompletedWindow): PressureWindowHeadResolution {
		val resolution = try {
			processPressureWindowHead(
				deadlineElapsedRealtimeNanos = { stopDeadlineElapsedNanos },
				nowElapsedRealtimeNanos = SystemClock::elapsedRealtimeNanos,
				prepare = { prepareWindowAdmission(window) },
				admit = { prepared ->
					window.sink.admit(prepared.delivery, prepared.checkpoint).toPressureWindowHandoff()
				},
				onAdmissionResolved = { prepared, handoff ->
					recordWindowResolution(window, prepared, handoff)
				},
				persistTerminalOutcome = { persistWindowCheckpoint(window) },
			)
		} catch (cancelled: CancellationException) {
			recordPressureFailureExactly(
				window.payload.firstProviderSequence..window.payload.lastProviderSequence,
			)
			throw cancelled
		} catch (fatal: Error) {
			recordPressureFailureExactly(
				window.payload.firstProviderSequence..window.payload.lastProviderSequence,
			)
			throw fatal
		}
		if (resolution == PressureWindowHeadResolution.DEADLINE_UNRESOLVED) {
			recordPressureFailureExactly(
				window.payload.firstProviderSequence..window.payload.lastProviderSequence,
				RuntimeGapClassification.DRAIN_TIMED_OUT,
			)
		}
		return resolution
	}

	private suspend fun prepareWindowAdmission(
		window: PressureCompletedWindow,
	): PreparedPressureAdmission? {
		val payload = window.payload
		val reception = window.reception
		val activeRegistration = window.registration
		val delayNanos = (reception.receivedElapsedNanos - payload.windowEndElapsedRealtimeNanos).coerceAtLeast(0L)
		val acquiredAtMs = (reception.receivedWallTimeMs - delayNanos / NANOS_PER_MILLISECOND).coerceAtLeast(0L)
		val candidate = SourceEvidenceCandidate(
			providerDedupKey = null,
			logicalTrackingId = null,
			serviceRunId = null,
			source = source,
			sourceInstanceId = SourceInstanceId(activeRegistration.state.sourceInstanceId),
			registrationGeneration = activeRegistration.state.registrationGeneration,
			physicalConfigurationFingerprint = activeRegistration.physicalConfigurationFingerprint,
			authorizationRevision = activeRegistration.authorization.authorizationRevision,
			registrationPurposeEligibilityMask = activeRegistration.purposeEligibilityMask,
			registrationEligibilityFingerprint = activeRegistration.eligibilityFingerprint,
			// Room allocates the only durable sequence after replay detection succeeds.
			sourceSequence = 0L,
			configRevision = activeRegistration.state.appliedRevision,
			planAttribution = PlanAttribution.CAPTURED_REGISTRATION,
			clockDomainId = activeRegistration.state.clockDomainId,
			observedElapsedRealtimeNanos = payload.windowEndElapsedRealtimeNanos,
			receivedElapsedRealtimeNanos = window.checkpointOrderElapsedRealtimeNanos,
			wallTimeMs = acquiredAtMs,
			wallTimeUncertaintyMs = 1L,
			capturedCollectedDataEpoch = activeRegistration.state.collectedDataEpoch,
			acquiredAtMs = acquiredAtMs,
			quality = pressureWindowQuality(payload, delayNanos),
			payloadVersion = PRESSURE_QUALIFIED_WINDOW_PAYLOAD_VERSION,
			payload = payload,
		)
		val delivery = SourceDeliveryCandidate(
			identity = pressureProviderDeliveryIdentity(activeRegistration.state.clockDomainId, payload),
			units = listOf(
				SourceDeliveryUnit(
					unitIndex = 0,
					evidence = candidate,
					observedIntervalStartElapsedRealtimeNanos =
						payload.windowStartElapsedRealtimeNanos,
				),
			),
		)
		val (absoluteGapMetrics, checkpointedCapacityGapSequence) = pressureCheckpointMetricsSnapshot()
		return PreparedPressureAdmission(
			delivery = delivery,
			checkpoint = activeRegistration.sensorAdmissionCheckpoint(
				providerSequenceThrough = payload.lastProviderSequence,
				checkpoint = pressureAtomicRuntimeCheckpoint(
					absoluteGapMetrics,
					causalOrderElapsedRealtimeNanos = window.checkpointOrderElapsedRealtimeNanos,
				),
				updatedAtMs = pressureCheckpointOrderMillis(window.checkpointOrderElapsedRealtimeNanos),
			),
			checkpointedCapacityGapSequence = checkpointedCapacityGapSequence,
			checkpointOrderElapsedRealtimeNanos = window.checkpointOrderElapsedRealtimeNanos,
		)
	}

	private fun pressureWindowQuality(payload: PressureWindowPayload, delayNanos: Long) = SourceQuality(
		flags = buildSet {
			if (delayNanos >= BATCHED_AFTER_NANOS) add(SourceQualityFlag.BATCHED)
			if (!payload.hasCompleteTargetCoverage()) add(SourceQualityFlag.INCOMPLETE_WINDOW)
		},
	)

	private fun pressureCheckpointMetricsSnapshot(): Pair<RuntimeAdmissionSnapshot, Long?> =
		synchronized(callbackLock) {
			val snapshot = metrics.snapshot()
			val gapSequence = capacityResumeGate.activeGapSequence
			val containsCurrentGap = gapSequence != null &&
				snapshot.gapClassifications.contains(RuntimeGapClassification.CALLBACK_BUFFER_OVERFLOW) &&
				(snapshot.unresolvedSequenceStart ?: Long.MAX_VALUE) <= gapSequence &&
				(snapshot.unresolvedSequenceEndInclusive ?: Long.MIN_VALUE) >= gapSequence
			snapshot to gapSequence.takeIf { containsCurrentGap }
		}

	private fun recordWindowResolution(
		window: PressureCompletedWindow,
		prepared: PreparedPressureAdmission,
		handoff: SourceAdmissionHandoff,
	) {
		val payload = window.payload
		when (handoff) {
			is SourceAdmissionHandoff.Durable -> {
				metrics.recordDurable(payload.lastProviderSequence, handoff.admissionOrdinal)
				recordAtomicPressureCheckpointInMemory(window, prepared)
			}
			is SourceAdmissionHandoff.Duplicate -> {
				metrics.recordDurable(payload.lastProviderSequence, handoff.existingAdmissionOrdinal)
				recordAtomicPressureCheckpointInMemory(window, prepared)
			}
			is SourceAdmissionHandoff.TerminalFailure -> recordPressureFailureExactly(
				payload.firstProviderSequence..payload.lastProviderSequence,
			)
			is SourceAdmissionHandoff.RetryableFailure ->
				error("Retryable Pressure handoff escaped the source-local FIFO loop")
		}
	}

	private fun recordAtomicPressureCheckpointInMemory(
		window: PressureCompletedWindow,
		prepared: PreparedPressureAdmission,
	) {
		lastCheckpointedProviderSequence = window.payload.lastProviderSequence
		lastCheckpointedAdmission = metrics.snapshot()
		window.checkpointedCapacityGapSequence = prepared.checkpointedCapacityGapSequence
	}

	private suspend fun persistWindowCheckpoint(window: PressureCompletedWindow): Boolean =
		runCatchingNonCancellation {
			val standaloneCheckpointElapsedNanos = SystemClock.elapsedRealtimeNanos()
			val (admission, checkpointedGapSequence) = pressureCheckpointMetricsSnapshot()
			registrations.saveSensorRuntimeCheckpoint(
				registration = window.registration,
				lastProviderSequence = window.payload.lastProviderSequence,
				checkpoint = SensorRuntimeCheckpoint(
					window.lifecycle,
					admission,
					PRESSURE_RUNTIME_COMPONENT_VERSION,
					ByteArray(0),
					causalOrderElapsedRealtimeNanos = standaloneCheckpointElapsedNanos,
				),
				updatedAtMs = pressureCheckpointOrderMillis(standaloneCheckpointElapsedNanos),
			)
			lastCheckpointedProviderSequence = window.payload.lastProviderSequence
			lastCheckpointedAdmission = admission
			window.checkpointedCapacityGapSequence = checkpointedGapSequence
		}.isSuccess

	private fun onSensorChanged(callbackToken: PressureCallbackToken, event: SensorEvent) {
		if (event.sensor.type != Sensor.TYPE_PRESSURE) return
		val pressure = event.values.firstOrNull() ?: return
		if (!BarometricAltitudeFormula.isValidPressure(pressure)) return
		val receivedElapsed = SystemClock.elapsedRealtimeNanos()
		val receivedWall = System.currentTimeMillis()
		synchronized(callbackLock) {
			if (!acceptingCallbacks || !callbackGate.accepts(callbackToken)) return
			if (!isFreshPressureSampleTimestamp(
				providerElapsedNanos = event.timestamp,
				receivedElapsedNanos = receivedElapsed,
				previousProviderElapsedNanos = lastAcceptedProviderElapsedNanos,
			)) return
			if (cutoffElapsedNanos?.let { event.timestamp > it } == true) return
			val providerSequence = ++callbackEntrySequence
			val sample = PendingPressureSample(
				pressureHectopascals = pressure,
				sensorAccuracy = event.accuracy.toPressureSensorAccuracy(),
				reception = PressureReception(
					observedElapsedNanos = event.timestamp,
					receivedElapsedNanos = receivedElapsed,
					receivedWallTimeMs = receivedWall,
					providerSequence = providerSequence,
				),
			)
			val refreshLatch = pendingAuthorizationRefresh
			if (refreshLatch != null && refreshLatch.shouldBuffer(event.timestamp)) {
				closePressureWindowAtRefreshBoundaryLocked(refreshLatch, receivedElapsed)
				val safeBufferedSamples =
					(MAX_PENDING_PRESSURE_WINDOWS - windowCapacity.pendingWindowCount).coerceAtLeast(0)
				when (refreshLatch.offer(sample, safeBufferedSamples)) {
					PressureRefreshLatchOffer.BUFFERED -> Unit
					PressureRefreshLatchOffer.OVERFLOW ->
						pausePressureForRefreshOverflowLocked(providerSequence)
				}
				lastAcceptedProviderElapsedNanos = event.timestamp
				return@synchronized
			}
			val attribution = callbackAttributionTimeline?.atObservedTime(event.timestamp) ?: return
			acceptPressureSampleLocked(
				sample,
				attribution,
				requireNotNull(currentPlan),
				requireNotNull(currentProviderRequest),
			)
			lastAcceptedProviderElapsedNanos = event.timestamp
		}
		drainCapacityPause()
	}

	private fun acceptPressureSampleLocked(
		sample: PendingPressureSample,
		attribution: PressureCallbackAttribution,
		plan: PressurePlan,
		providerRequest: PressureProviderRequest,
	) {
		val reception = sample.reception
		val currentAttribution = accumulatorAttribution ?: error(
			"Pressure sample had no accumulator authorization attribution",
		)
		if (!currentAttribution.samePressureBoundary(attribution)) {
			val boundaryWindow = drainPressureWindowAtBoundary(accumulator, lastAccumulatedReception)
			if (boundaryWindow != null) {
				enqueueWindowLocked(
					payload = boundaryWindow.payload,
					reception = boundaryWindow.reception,
					activeRegistration = currentAttribution.registration,
					sink = currentAttribution.sink,
					lifecycle = RuntimeCheckpointLifecycle.ACTIVE,
					terminal = false,
					checkpointOrderElapsedRealtimeNanos = reception.receivedElapsedNanos,
				)
			}
			accumulator = newPressureAccumulator(plan, providerRequest, attribution.registration)
			accumulatorAttribution = attribution
			lastAccumulatedReception = null
		}
		val completed = requireNotNull(accumulator).add(
			sample.pressureHectopascals,
			reception.observedElapsedNanos,
			reception.providerSequence,
			sample.sensorAccuracy,
		)
		if (completed != null) {
			enqueueWindowLocked(
				payload = completed,
				reception = requireNotNull(lastAccumulatedReception),
				activeRegistration = attribution.registration,
				sink = attribution.sink,
				lifecycle = RuntimeCheckpointLifecycle.ACTIVE,
				terminal = false,
				checkpointOrderElapsedRealtimeNanos = reception.receivedElapsedNanos,
			)
		}
		lastAccumulatedReception = reception
	}

	private fun closePressureWindowAtRefreshBoundaryLocked(
		refreshLatch: PressureAuthorizationRefreshLatch,
		checkpointOrderElapsedRealtimeNanos: Long,
	) {
		if (!refreshLatch.markBoundaryClosed()) return
		val attribution = checkNotNull(accumulatorAttribution) {
			"Pressure refresh boundary had no old authorization attribution"
		}
		val boundaryWindow = drainPressureWindowAtBoundary(accumulator, lastAccumulatedReception)
		if (boundaryWindow != null) {
			enqueueWindowLocked(
				payload = boundaryWindow.payload,
				reception = boundaryWindow.reception,
				activeRegistration = attribution.registration,
				sink = attribution.sink,
				lifecycle = RuntimeCheckpointLifecycle.ACTIVE,
				terminal = false,
				checkpointOrderElapsedRealtimeNanos = checkpointOrderElapsedRealtimeNanos,
			)
		}
		lastAccumulatedReception = null
	}

	private fun pausePressureForRefreshOverflowLocked(gapSequence: Long) {
		if (capacityResumeGate.capacityPaused) return
		acceptingCallbacks = false
		callbackToken?.let(callbackGate::retire)
		capacityPauseListener = listener
		capacityPauseRemovalComplete = false
		recordPressureFailureExactly(
			gapSequence..gapSequence,
			RuntimeGapClassification.CALLBACK_BUFFER_OVERFLOW,
		)
		capacityResumeGate.onCapacityPause(gapSequence)
	}

	private fun resolvePendingAuthorizationRefreshLocked(
		refreshLatch: PressureAuthorizationRefreshLatch,
		resolvedPlan: PressurePlan,
		resolvedProviderRequest: PressureProviderRequest,
		resolvedAttribution: PressureCallbackAttribution,
		refreshed: Boolean,
	): Boolean {
		if (pendingAuthorizationRefresh !== refreshLatch || currentPlan == null || registration == null ||
			cutoffElapsedNanos != null || windowLane == null
		) return false
		val timeline = callbackAttributionTimeline ?: return false
		if (refreshed) {
			timeline.refresh(
				resolvedAttribution.registration,
				resolvedAttribution.sink,
				refreshLatch.effectiveElapsedRealtimeNanos,
			)
			registration = resolvedAttribution.registration
			currentSink = resolvedAttribution.sink
			currentPlan = resolvedPlan
			currentProviderRequest = resolvedProviderRequest
		}
		val buffered = refreshLatch.drainBufferedSamples()
		pendingAuthorizationRefresh = null
		if (refreshLatch.boundaryClosed) {
			accumulator = newPressureAccumulator(
				resolvedPlan,
				resolvedProviderRequest,
				resolvedAttribution.registration,
			)
			accumulatorAttribution = resolvedAttribution
			lastAccumulatedReception = null
		}
		buffered.forEach { sample ->
			acceptPressureSampleLocked(
				sample,
				resolvedAttribution,
				resolvedPlan,
				resolvedProviderRequest,
			)
		}
		if (capacityResumeGate.capacityPaused) {
			val partial = drainPressureWindowAtBoundary(accumulator, lastAccumulatedReception)
			val attribution = accumulatorAttribution
			if (partial != null) {
				checkNotNull(attribution)
				enqueueWindowLocked(
					payload = partial.payload,
					reception = partial.reception,
					activeRegistration = attribution.registration,
					sink = attribution.sink,
					lifecycle = RuntimeCheckpointLifecycle.ACTIVE,
					terminal = true,
					checkpointOrderElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
				)
			}
			lastAccumulatedReception = null
		}
		return true
	}

	private suspend fun persistTerminalCheckpoint(
		activeRegistration: SourceRegistration,
		lastProviderSequence: Long,
		lifecycle: RuntimeCheckpointLifecycle,
	) {
		val terminalCheckpointElapsedNanos = SystemClock.elapsedRealtimeNanos()
		val currentAdmission = metrics.snapshot()
		val safeAdmission = currentAdmission.copy(
			lastDurablyAdmittedSequence = lastCheckpointedAdmission.lastDurablyAdmittedSequence,
			lastAdmissionOrdinal = lastCheckpointedAdmission.lastAdmissionOrdinal,
		)
		registrations.saveSensorRuntimeCheckpoint(
			activeRegistration,
			lastProviderSequence,
			SensorRuntimeCheckpoint(
				lifecycle,
				safeAdmission,
				PRESSURE_RUNTIME_COMPONENT_VERSION,
				ByteArray(0),
				causalOrderElapsedRealtimeNanos = terminalCheckpointElapsedNanos,
			),
			pressureCheckpointOrderMillis(terminalCheckpointElapsedNanos),
		)
	}

	/** Called only while [callbackLock] is held; acquisition retires before this bounded lane fills. */
	private fun enqueueWindowLocked(
		payload: PressureWindowPayload,
		reception: PressureReception,
		activeRegistration: SourceRegistration,
		sink: SourceEventSink,
		lifecycle: RuntimeCheckpointLifecycle,
		terminal: Boolean,
		settlement: CompletableDeferred<Unit>? = null,
		checkpointOrderElapsedRealtimeNanos: Long,
	) {
		val capacity = if (terminal) {
			windowCapacity.terminalWindowEnqueued()
		} else {
			windowCapacity.acquisitionWindowEnqueued()
		}
		check(requireNotNull(windowLane).trySend(
			PressureCompletedWindow(
				payload = payload,
				reception = reception,
				registration = activeRegistration,
				sink = sink,
				lifecycle = lifecycle,
				settlement = settlement,
				checkpointOrderElapsedRealtimeNanos = checkpointOrderElapsedRealtimeNanos,
			),
		).isSuccess) { "Active pressure window lane was unexpectedly closed" }
		if (capacity.pauseAcquisition && acceptingCallbacks) {
			acceptingCallbacks = false
			callbackToken?.let(callbackGate::retire)
			capacityPauseListener = listener
			capacityPauseRemovalComplete = false
			val gapSequence = ++callbackEntrySequence
			recordPressureFailureExactly(
				gapSequence..gapSequence,
				RuntimeGapClassification.CALLBACK_BUFFER_OVERFLOW,
			)
			capacityResumeGate.onCapacityPause(gapSequence)
		}
	}

	private fun drainCapacityPause() {
		val listenerToPause = synchronized(callbackLock) {
			capacityPauseListener.also { capacityPauseListener = null }
		}
		if (listenerToPause != null) {
			val removed = try {
				sensorManager.unregisterListener(listenerToPause)
				true
			} catch (cancelled: CancellationException) {
				throw cancelled
			} catch (_: Exception) {
				false
			}
			synchronized(callbackLock) {
				if (listener === listenerToPause && capacityResumeGate.capacityPaused) {
					capacityPauseRemovalComplete = removed
				}
			}
		}
	}

	private fun onFlushCompleted(callbackToken: PressureCallbackToken, sensor: Sensor?) {
		if (sensor?.type == Sensor.TYPE_PRESSURE) {
			synchronized(callbackLock) {
				if (callbackGate.accepts(callbackToken)) flushCompletion?.complete(Unit)
			}
		}
	}

	private fun clearActiveState() {
		synchronized(callbackLock) {
			acceptingCallbacks = false
			capacityResumeJob?.cancel()
			capacityResumeJob = null
			capacityResumeSerial++
			capacityResumeGate = PressureCapacityResumeGate(RESUME_LOW_WATER_WINDOWS)
			capacityResumeReconciliationAllowed = false
			callbackToken?.let(callbackGate::retire)
			callbackToken = null
			windowLane = null
			accumulator = null
			accumulatorAttribution = null
			callbackAttributionTimeline = null
			lastAccumulatedReception = null
			lastAcceptedProviderElapsedNanos = null
			capacityPauseListener = null
			capacityPauseRemovalComplete = true
			terminalSettlementInProgress = false
			pendingAuthorizationRefresh = null
			flushCompletion = null
		}
		registration = null
		listener = null
		actor = null
		batchingEnabled = false
		cutoffElapsedNanos = null
		stopDeadlineElapsedNanos = null
		currentPlan = null
		currentProviderRequest = null
		currentSink = null
		retirementIntent = null
		exceptionalActorFailurePending = false
	}

	private fun recordPressureFailureExactly(
		range: LongRange,
		classification: RuntimeGapClassification = RuntimeGapClassification.ADMISSION_FAILED,
	) {
		failureCoverage.recordAndReturnUnaccounted(range).forEach { unaccounted ->
			metrics.recordFailure(unaccounted.first, unaccounted.last, classification)
		}
	}

	private fun retainProviderForRetirementRetry(retainActor: Boolean = false) {
		synchronized(callbackLock) {
			acceptingCallbacks = false
			callbackToken?.let(callbackGate::retire)
			capacityResumeJob?.cancel()
			capacityResumeJob = null
			capacityResumeSerial++
			capacityResumeReconciliationAllowed = false
			capacityResumeGate.fenceResume()
			windowLane?.close()
			windowLane = null
			accumulator = null
			accumulatorAttribution = null
			callbackAttributionTimeline = null
			lastAccumulatedReception = null
			lastAcceptedProviderElapsedNanos = null
			capacityPauseListener = null
			pendingAuthorizationRefresh = null
			flushCompletion = null
		}
		currentSink = null
		if (!retainActor) actor = null
		batchingEnabled = false
	}

	private inner class PressureRegistrationListener(
		private val token: PressureCallbackToken,
	) : SensorEventListener2 {
		override fun onSensorChanged(event: SensorEvent) = this@PressureSourceRuntime.onSensorChanged(token, event)

		// SensorEvent carries the accuracy attributable to each accepted observation.
		override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

		override fun onFlushCompleted(sensor: Sensor?) = this@PressureSourceRuntime.onFlushCompleted(token, sensor)
	}

	private fun unavailableAck(cutoff: SessionCutoff?): SourceStopAck {
		val admission = metrics.snapshot()
		return SourceStopAck(
		source = source,
		sourceInstanceId = SourceInstanceId("unavailable-pressure"),
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
		).withSessionMembership(runtimeClaim)
	}

	private fun Sensor?.toCapabilities() = SourceCapabilities(
		available = this != null,
		batchingSupported = this?.fifoMaxEventCount?.let { it > 0 } == true,
		flushSupported = this?.fifoMaxEventCount?.let { it > 0 } == true,
		maximumBatchSize = this?.fifoMaxEventCount,
		minimumDelayMs = this?.minDelay?.takeIf { it >= 0 }?.div(MICROS_PER_MILLISECOND)?.toLong(),
	)

	private companion object {
		const val MICROS_PER_MILLISECOND = 1_000
		const val NANOS_PER_MILLISECOND = 1_000_000L
		const val BATCHED_AFTER_NANOS = 5_000L * NANOS_PER_MILLISECOND
		const val PROVIDER_FLUSH_TIMEOUT_MS = 1_000L
		const val DEFAULT_DRAIN_TIMEOUT_MS = 2_000L
		const val MAX_PENDING_PRESSURE_WINDOWS = 64
		const val MAX_PENDING_PRESSURE_REFRESH_SAMPLES = 64
		const val RESUME_LOW_WATER_WINDOWS = 16
	}
}

private data class PressureProviderRetirementIntent(
	val registration: SourceRegistration,
	val listener: SensorEventListener2,
	val callbackToken: PressureCallbackToken,
	val reason: String,
	val retiredAtMs: Long,
	val retiredElapsedRealtimeNanos: Long,
	var retirementToken: SourceRegistrationRetirementToken? = null,
	var providerRemovalComplete: Boolean = false,
	var durableCompletionComplete: Boolean = false,
)

private enum class PressureProviderRetirement { NOT_DURABLE, PENDING, TIMED_OUT, COMPLETE }

private fun SourceRegistration.samePressureRegistrationAs(other: SourceRegistration): Boolean =
	state.sourceKind == other.state.sourceKind &&
		state.sourceInstanceId == other.state.sourceInstanceId &&
		state.registrationGeneration == other.state.registrationGeneration

private val EMPTY_PRESSURE_ADMISSION_SNAPSHOT = RuntimeAdmissionSnapshot(
	lastDurablyAdmittedSequence = null,
	lastAdmissionOrdinal = null,
	failedAdmissionCount = 0L,
	unresolvedSequenceStart = null,
	unresolvedSequenceEndInclusive = null,
	gapClassifications = emptySet(),
)

internal data class PressureReception(
	val observedElapsedNanos: Long,
	val receivedElapsedNanos: Long,
	val receivedWallTimeMs: Long,
	val providerSequence: Long,
)

internal data class PendingPressureSample(
	val pressureHectopascals: Float,
	val sensorAccuracy: PressureSensorAccuracy,
	val reception: PressureReception,
)

internal enum class PressureRefreshLatchOffer { BUFFERED, OVERFLOW }

/** Bounded raw latch used only while the durable compatible authorization revision is unresolved. */
internal class PressureAuthorizationRefreshLatch(
	val effectiveElapsedRealtimeNanos: Long,
	private val maximumBufferedSamples: Int,
) {
	private val buffered = ArrayDeque<PendingPressureSample>()
	var boundaryClosed: Boolean = false
		private set
	val bufferedSampleCount: Int get() = buffered.size

	init {
		require(effectiveElapsedRealtimeNanos >= 0L)
		require(maximumBufferedSamples > 0)
	}

	fun shouldBuffer(observedElapsedRealtimeNanos: Long): Boolean =
		observedElapsedRealtimeNanos >= effectiveElapsedRealtimeNanos

	fun markBoundaryClosed(): Boolean {
		if (boundaryClosed) return false
		boundaryClosed = true
		return true
	}

	fun offer(sample: PendingPressureSample, safeBufferedSampleLimit: Int): PressureRefreshLatchOffer {
		require(shouldBuffer(sample.reception.observedElapsedNanos))
		require(safeBufferedSampleLimit >= 0)
		val effectiveLimit = minOf(maximumBufferedSamples, safeBufferedSampleLimit)
		if (buffered.size >= effectiveLimit) return PressureRefreshLatchOffer.OVERFLOW
		buffered.addLast(sample)
		return PressureRefreshLatchOffer.BUFFERED
	}

	fun drainBufferedSamples(): List<PendingPressureSample> = buildList(buffered.size) {
		while (buffered.isNotEmpty()) add(buffered.removeFirst())
	}
}

internal data class PressureCallbackAttribution(
	val registration: SourceRegistration,
	val sink: SourceEventSink,
	val effectiveElapsedRealtimeNanos: Long = registration.authorization.effectiveElapsedRealtimeNanos,
)

/** Compact observed-time authority history; monotonic accepted sensor times prune old entries. */
internal class PressureObservedAuthorizationTimeline(
	initialRegistration: SourceRegistration,
	initialSink: SourceEventSink,
) {
	private val entries = ArrayDeque<PressureCallbackAttribution>().apply {
		addLast(PressureCallbackAttribution(initialRegistration, initialSink))
	}

	fun refresh(
		registration: SourceRegistration,
		sink: SourceEventSink,
		effectiveElapsedRealtimeNanos: Long = registration.authorization.effectiveElapsedRealtimeNanos,
	) {
		val next = PressureCallbackAttribution(registration, sink, effectiveElapsedRealtimeNanos)
		val latest = entries.last()
		require(effectiveElapsedRealtimeNanos >= latest.effectiveElapsedRealtimeNanos)
		if (registration.pressureAccumulatorBoundary() ==
			latest.registration.pressureAccumulatorBoundary() &&
			effectiveElapsedRealtimeNanos == latest.effectiveElapsedRealtimeNanos
		) entries.removeLast()
		entries.addLast(next)
	}

	fun atObservedTime(observedElapsedRealtimeNanos: Long): PressureCallbackAttribution? {
		val selectedIndex = entries.indexOfLast { attribution ->
			attribution.effectiveElapsedRealtimeNanos <= observedElapsedRealtimeNanos
		}
		if (selectedIndex < 0) return null
		repeat(selectedIndex) { entries.removeFirst() }
		return entries.first()
	}
}

internal fun SourceRegistration.pressureAccumulatorBoundary() = PressureAccumulatorBoundary(
	registrationGeneration = state.registrationGeneration,
	eligibilityFingerprint = eligibilityFingerprint,
	appliedRevision = requireNotNull(state.appliedRevision) {
		"Pressure registration must retain its applied manifest revision"
	},
	authorizationRevision = authorization.authorizationRevision,
)

internal fun PressureCallbackAttribution.samePressureBoundary(
	other: PressureCallbackAttribution,
): Boolean = registration.pressureAccumulatorBoundary() == other.registration.pressureAccumulatorBoundary()

internal data class PressureWindowAtBoundary(
	val payload: PressureWindowPayload,
	val reception: PressureReception,
)

internal fun drainPressureWindowAtBoundary(
	accumulator: PressureWindowAccumulator?,
	lastReception: PressureReception?,
): PressureWindowAtBoundary? {
	if (accumulator?.snapshot() == null) return null
	val reception = checkNotNull(lastReception) {
		"Pressure accumulator had state without immutable reception metadata"
	}
	return PressureWindowAtBoundary(
		checkNotNull(accumulator.drain()),
		reception,
	)
}

internal fun isFreshPressureSampleTimestamp(
	providerElapsedNanos: Long,
	receivedElapsedNanos: Long,
	previousProviderElapsedNanos: Long?,
): Boolean = providerElapsedNanos > 0L &&
	providerElapsedNanos <= receivedElapsedNanos &&
	(previousProviderElapsedNanos == null || providerElapsedNanos > previousProviderElapsedNanos)

private fun Int.toPressureSensorAccuracy(): PressureSensorAccuracy = when (this) {
	SensorManager.SENSOR_STATUS_UNRELIABLE -> PressureSensorAccuracy.UNRELIABLE
	SensorManager.SENSOR_STATUS_ACCURACY_LOW -> PressureSensorAccuracy.LOW
	SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> PressureSensorAccuracy.MEDIUM
	SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> PressureSensorAccuracy.HIGH
	else -> PressureSensorAccuracy.UNKNOWN
}

private fun PressureWindowPayload.hasCompleteTargetCoverage(): Boolean {
	if (closureKind != PressureWindowClosureKind.TARGET_ELAPSED) return false
	val expectedCount = requireNotNull(expectedSampleCount)
	val samplePeriodNanos = requireNotNull(effectiveSamplePeriodMicros).toLong() * 1_000L
	// The rollover trigger belongs to the next window. A full half-open prior window therefore
	// covers at least (expected - 1) provider periods with its own first/last observations.
	val requiredObservedSpanNanos = (expectedCount.toLong() - 1L) * samplePeriodNanos
	val observedSpanNanos = windowEndElapsedRealtimeNanos - windowStartElapsedRealtimeNanos
	// A gap of two complete provider periods proves that at least one cadence slot is absent. Keep
	// ordinary sub-period jitter complete, but never let bunched samples at the ends hide a hole.
	val hasNoMissingCadenceSlot = requireNotNull(maximumInterSampleGapNanos) < 2L * samplePeriodNanos
	return sampleCount >= expectedCount && observedSpanNanos >= requiredObservedSpanNanos &&
		hasNoMissingCadenceSlot
}

internal data class PressureCompletedWindow(
	val payload: PressureWindowPayload,
	val reception: PressureReception,
	val registration: SourceRegistration,
	val sink: SourceEventSink,
	val lifecycle: RuntimeCheckpointLifecycle,
	val settlement: CompletableDeferred<Unit>? = null,
	val checkpointOrderElapsedRealtimeNanos: Long = reception.receivedElapsedNanos,
) {
	@Volatile
	var checkpointedCapacityGapSequence: Long? = null
}

internal data class PreparedPressureAdmission(
	val delivery: SourceDeliveryCandidate,
	val checkpoint: SensorAdmissionCheckpoint,
	val checkpointedCapacityGapSequence: Long?,
	/** Full callback-entry causal order retained for the shared checkpoint contract. */
	val checkpointOrderElapsedRealtimeNanos: Long,
)

/**
 * Intrinsic identity for one Pressure provider window. Callback-local sequence numbers remain in
 * the payload and checkpoint, but cannot define the provider window across runtime replacements.
 */
internal fun pressureProviderDeliveryIdentity(
	clockDomainId: String,
	payload: PressureWindowPayload,
): SourceDeliveryIdentity {
	require(clockDomainId.isNotBlank())
	require(payload.sampleCount > 0)
	require(payload.windowStartElapsedRealtimeNanos >= 0L)
	require(payload.windowEndElapsedRealtimeNanos >= payload.windowStartElapsedRealtimeNanos)
	val canonical = buildString {
		append("pressure-window-v1|")
		append(clockDomainId.length)
		append(':')
		append(clockDomainId)
		append("|start:")
		append(payload.windowStartElapsedRealtimeNanos)
		append("|end:")
		append(payload.windowEndElapsedRealtimeNanos)
		append("|count:")
		append(payload.sampleCount)
		append("|mean-bits:")
		append(payload.meanHectopascals.toRawBits())
		append("|m2-bits:")
		append(payload.sumSquaredDeviations.toRawBits())
		append("|min-bits:")
		append(payload.minimumHectopascals.toRawBits())
		append("|max-bits:")
		append(payload.maximumHectopascals.toRawBits())
	}
	return sourceDeliveryIdentity(canonical.toByteArray(Charsets.UTF_8))
}

internal fun SourceDeliveryAdmissionHandoff.toPressureWindowHandoff(): SourceAdmissionHandoff = when (this) {
	is SourceDeliveryAdmissionHandoff.Durable -> admissionOrdinals.singleOrNull()?.let { ordinal ->
		SourceAdmissionHandoff.Durable(ordinal)
	} ?: SourceAdmissionHandoff.TerminalFailure(SourceAdmissionFailureCode.INVALID_EVIDENCE)
	is SourceDeliveryAdmissionHandoff.Duplicate -> existingAdmissionOrdinals.singleOrNull()?.let { ordinal ->
		SourceAdmissionHandoff.Duplicate(ordinal)
	} ?: SourceAdmissionHandoff.TerminalFailure(SourceAdmissionFailureCode.INVALID_EVIDENCE)
	is SourceDeliveryAdmissionHandoff.TerminalFailure -> SourceAdmissionHandoff.TerminalFailure(code)
	is SourceDeliveryAdmissionHandoff.RetryableFailure -> SourceAdmissionHandoff.RetryableFailure(code)
	is SourceDeliveryAdmissionHandoff.SessionCutoff ->
		SourceAdmissionHandoff.TerminalFailure(SourceAdmissionFailureCode.SOURCE_POLICY_STALE)
}

internal enum class PressureWindowHeadResolution { SETTLED, DEADLINE_UNRESOLVED }

internal enum class PressureWindowLaneDrain { COMPLETE, DEADLINE_UNRESOLVED }

internal suspend fun consumePressureWindowLane(
	windows: ReceiveChannel<PressureCompletedWindow>,
	processHead: suspend (PressureCompletedWindow) -> PressureWindowHeadResolution,
	onSettled: (PressureCompletedWindow) -> Unit = {},
): PressureWindowLaneDrain {
	for (window in windows) {
		if (processHead(window) == PressureWindowHeadResolution.DEADLINE_UNRESOLVED) {
			return PressureWindowLaneDrain.DEADLINE_UNRESOLVED
		}
		onSettled(window)
	}
	return PressureWindowLaneDrain.COMPLETE
}

/**
 * Keeps one prepared Pressure candidate/checkpoint pair at the FIFO head through any number of
 * transient sequence or atomic-admission failures. Durable/duplicate facts already contain their
 * checkpoint; only a terminal no-fact outcome uses the standalone checkpoint path.
 */
internal suspend fun <Prepared> processPressureWindowHead(
	deadlineElapsedRealtimeNanos: () -> Long?,
	nowElapsedRealtimeNanos: () -> Long,
	prepare: suspend () -> Prepared?,
	admit: suspend (Prepared) -> SourceAdmissionHandoff,
	onAdmissionResolved: (Prepared, SourceAdmissionHandoff) -> Unit,
	persistTerminalOutcome: suspend () -> Boolean,
	retryDelaysMs: LongArray = PRESSURE_HEAD_RETRY_DELAYS_MS,
	waitBeforeRetry: suspend (Long) -> Unit = { delay(it) },
): PressureWindowHeadResolution {
	require(retryDelaysMs.isNotEmpty())
	var retryIndex = 0
	var prepared: Prepared? = null
	var terminalOutcomeRecorded = false
	while (true) {
		val deadline = deadlineElapsedRealtimeNanos()
		if (deadline != null && nowElapsedRealtimeNanos() >= deadline) {
			return PressureWindowHeadResolution.DEADLINE_UNRESOLVED
		}
		if (prepared == null) {
			prepared = prepare()
			if (prepared == null) {
				if (!waitForPressureRetry(
					deadline,
					nowElapsedRealtimeNanos,
					retryDelaysMs[retryIndex.coerceAtMost(retryDelaysMs.lastIndex)],
					waitBeforeRetry,
				)) return PressureWindowHeadResolution.DEADLINE_UNRESOLVED
				retryIndex++
				continue
			}
		}
		if (!terminalOutcomeRecorded) {
			val exactPreparedHead = checkNotNull(prepared)
			val handoff = admit(exactPreparedHead)
			if (handoff is SourceAdmissionHandoff.RetryableFailure) {
				if (!waitForPressureRetry(
					deadline,
					nowElapsedRealtimeNanos,
					retryDelaysMs[retryIndex.coerceAtMost(retryDelaysMs.lastIndex)],
					waitBeforeRetry,
				)) return PressureWindowHeadResolution.DEADLINE_UNRESOLVED
				retryIndex++
				continue
			}
			onAdmissionResolved(exactPreparedHead, handoff)
			when (handoff) {
				is SourceAdmissionHandoff.Durable,
				is SourceAdmissionHandoff.Duplicate,
				-> return PressureWindowHeadResolution.SETTLED
				is SourceAdmissionHandoff.TerminalFailure -> terminalOutcomeRecorded = true
				is SourceAdmissionHandoff.RetryableFailure -> error(
					"Retryable Pressure atomic handoff escaped the FIFO retry branch",
				)
			}
		}
		if (persistTerminalOutcome()) return PressureWindowHeadResolution.SETTLED
		if (!waitForPressureRetry(
			deadline,
			nowElapsedRealtimeNanos,
			retryDelaysMs[retryIndex.coerceAtMost(retryDelaysMs.lastIndex)],
			waitBeforeRetry,
		)) return PressureWindowHeadResolution.DEADLINE_UNRESOLVED
		retryIndex++
	}
}

private suspend fun waitForPressureRetry(
	deadlineElapsedRealtimeNanos: Long?,
	nowElapsedRealtimeNanos: () -> Long,
	retryDelayMs: Long,
	waitBeforeRetry: suspend (Long) -> Unit,
): Boolean {
	val waitMs = deadlineElapsedRealtimeNanos?.let { deadline ->
		val remainingNanos = deadline - nowElapsedRealtimeNanos()
		if (remainingNanos <= 0L) return false
		minOf(retryDelayMs, (remainingNanos + 999_999L) / 1_000_000L)
	} ?: retryDelayMs
	waitBeforeRetry(waitMs)
	return deadlineElapsedRealtimeNanos == null || nowElapsedRealtimeNanos() < deadlineElapsedRealtimeNanos
}

// Process-local delay only; the saturated tail avoids a storage outage becoming a power-hot loop.
internal val PRESSURE_HEAD_RETRY_DELAYS_MS = longArrayOf(100L, 1_000L, 5_000L, 30_000L, 60_000L)

internal enum class PressureResumeAttempt { RESUMED, RETRY, STALE }

internal suspend fun <T> resumePressureAfterPartialSettlement(
	partialSettlement: CompletableDeferred<Unit>?,
	resume: suspend () -> T,
): T {
	partialSettlement?.await()
	return resume()
}

internal suspend fun reconcilePressureResumeUntilSettled(
	attempt: suspend () -> PressureResumeAttempt,
	retryDelaysMs: LongArray = PRESSURE_RESUME_RETRY_DELAYS_MS,
	waitBeforeRetry: suspend (Long) -> Unit = { delay(it) },
): PressureResumeAttempt {
	require(retryDelaysMs.isNotEmpty())
	var retryIndex = 0
	while (true) {
		kotlin.coroutines.coroutineContext.ensureActive()
		when (val result = attempt()) {
			PressureResumeAttempt.RESUMED,
			PressureResumeAttempt.STALE,
			-> return result
			PressureResumeAttempt.RETRY -> {
				waitBeforeRetry(retryDelaysMs[retryIndex.coerceAtMost(retryDelaysMs.lastIndex)])
				retryIndex++
			}
		}
	}
}

// These are ordinary process-local coroutine delays: they do not claim wakeup or durable scheduling.
// The long saturated tail avoids a failed SensorManager registration becoming a power-hot loop.
internal val PRESSURE_RESUME_RETRY_DELAYS_MS = longArrayOf(100L, 1_000L, 5_000L, 30_000L, 60_000L)

internal fun pressureAtomicRuntimeCheckpoint(
	absoluteGapMetrics: RuntimeAdmissionSnapshot,
	causalOrderElapsedRealtimeNanos: Long,
): SensorRuntimeCheckpoint = SensorRuntimeCheckpoint(
	lifecycle = RuntimeCheckpointLifecycle.ACTIVE,
	metrics = absoluteGapMetrics,
	componentStateVersion = PRESSURE_RUNTIME_COMPONENT_VERSION,
	// A Pressure fact ends at providerSequenceThrough. The next in-process partial is future,
	// unadmitted input and must never be smuggled into this atomic fact checkpoint.
	componentPayload = ByteArray(0),
	causalOrderElapsedRealtimeNanos = causalOrderElapsedRealtimeNanos,
)

/** Same-boot monotonic checkpoint ordering; intentionally independent of wall-clock jumps. */
internal fun pressureCheckpointOrderMillis(elapsedRealtimeNanos: Long): Long {
	require(elapsedRealtimeNanos >= 0L)
	return elapsedRealtimeNanos / 1_000_000L
}

/** Compact exact coverage for sparse Pressure callback failures and synthetic capacity gaps. */
internal class PressureFailureRangeCoverage {
	private val lock = Any()
	private val ranges = mutableListOf<LongRange>()

	/** Records [range] and returns only portions that were not already covered. */
	fun recordAndReturnUnaccounted(range: LongRange): List<LongRange> {
		require(range.first > 0L && range.first <= range.last)
		return synchronized(lock) {
			val unaccounted = complementLocked(range)
			mergeLocked(range)
			unaccounted
		}
	}

	private fun complementLocked(target: LongRange): List<LongRange> = buildList {
		var cursor = target.first
		ranges.forEach { covered ->
			if (covered.last < cursor || covered.first > target.last) return@forEach
			if (covered.first > cursor) add(cursor..minOf(target.last, covered.first - 1L))
			if (covered.last == Long.MAX_VALUE) {
				cursor = Long.MAX_VALUE
				return@buildList
			}
			cursor = maxOf(cursor, covered.last + 1L)
		}
		if (cursor <= target.last) add(cursor..target.last)
	}

	private fun mergeLocked(next: LongRange) {
		val merged = mutableListOf<LongRange>()
		val candidates = ranges.toMutableList().apply { add(next) }.sortedBy { it.first }
		candidates.forEach { candidate ->
			val previous = merged.lastOrNull()
			val separated = previous == null ||
				(previous.last != Long.MAX_VALUE && candidate.first > previous.last + 1L)
			if (separated) {
				merged += candidate
			} else {
				merged[merged.lastIndex] = requireNotNull(previous).first..maxOf(previous.last, candidate.last)
			}
		}
		ranges.clear()
		ranges.addAll(merged)
	}
}

internal class PressureCallbackToken internal constructor(
	val registrationGeneration: Long,
	val eligibilityFingerprint: String,
)

/**
 * Fences callbacks by token identity so a listener retained by SensorManager cannot write into a
 * later registration's queue, even if persisted generation metadata were malformed or reused.
 */
internal class PressureCallbackGenerationGate {
	private var active: PressureCallbackToken? = null

	fun activate(registrationGeneration: Long, eligibilityFingerprint: String): PressureCallbackToken {
		require(registrationGeneration > 0L)
		require(eligibilityFingerprint.isNotBlank())
		return PressureCallbackToken(registrationGeneration, eligibilityFingerprint).also { active = it }
	}

	fun accepts(token: PressureCallbackToken): Boolean = active === token

	fun retire(token: PressureCallbackToken): Boolean {
		if (active !== token) return false
		active = null
		return true
	}
}

internal data class PressureRuntimeRecovery(
	val metrics: RuntimeAdmissionSnapshot?,
	val callbackEntrySequence: Long,
	val requiresPreAcquisitionCheckpoint: Boolean = false,
)

/** Restores only durable admission accounting. An open pressure accumulator is process-local. */
internal fun recoverPressureRuntimeState(
	saved: SourceRuntimeStateEntity?,
	currentRegistrationGeneration: Long,
	reusedActiveRegistration: Boolean,
): PressureRuntimeRecovery {
	require(currentRegistrationGeneration > 0L)
	val checkpoint = decodeSensorRuntimeCheckpoint(saved, PRESSURE_RUNTIME_COMPONENT_VERSION)
	if (checkpoint == null) {
		if (!reusedActiveRegistration) return PressureRuntimeRecovery(null, 0L)
		val restarted = RuntimeAdmissionMetrics().also {
			it.recordFailure(1L, classification = RuntimeGapClassification.PROCESS_RESTARTED)
		}
		return PressureRuntimeRecovery(restarted.snapshot(), 1L, requiresPreAcquisitionCheckpoint = true)
	}
	val sameGeneration = saved?.registrationGeneration == currentRegistrationGeneration
	val lastSequence = if (sameGeneration) {
		pressureRecoveryProviderHighWater(requireNotNull(saved).lastProviderSequence, checkpoint.metrics)
	} else {
		0L
	}
	if (checkpoint.lifecycle != RuntimeCheckpointLifecycle.ACTIVE) {
		if (!sameGeneration && reusedActiveRegistration) {
			val restarted = RuntimeAdmissionMetrics().also {
				it.recordFailure(1L, classification = RuntimeGapClassification.PROCESS_RESTARTED)
			}
			return PressureRuntimeRecovery(restarted.snapshot(), 1L, requiresPreAcquisitionCheckpoint = true)
		}
		return PressureRuntimeRecovery(
			metrics = checkpoint.metrics.takeIf { sameGeneration },
			callbackEntrySequence = lastSequence,
		)
	}
	val restartGapSequence = checkedNextPressureProviderSequence(lastSequence)
	val restarted = RuntimeAdmissionMetrics(
		checkpoint.metrics.lastDurablyAdmittedSequence,
		checkpoint.metrics.lastAdmissionOrdinal,
		checkpoint.metrics.failedAdmissionCount,
		checkpoint.metrics.unresolvedSequenceStart,
		checkpoint.metrics.unresolvedSequenceEndInclusive,
		checkpoint.metrics.gapClassifications,
	).also {
		it.recordFailure(restartGapSequence, classification = RuntimeGapClassification.PROCESS_RESTARTED)
	}
	return PressureRuntimeRecovery(
		restarted.snapshot(),
		restartGapSequence,
		requiresPreAcquisitionCheckpoint = true,
	)
}

internal fun pressureRecoveryProviderHighWater(
	rowLastProviderSequence: Long,
	metrics: RuntimeAdmissionSnapshot,
): Long {
	require(rowLastProviderSequence >= 0L)
	return maxOf(rowLastProviderSequence, metrics.unresolvedSequenceEndInclusive ?: 0L)
}

internal fun checkedNextPressureProviderSequence(highWater: Long): Long {
	require(highWater >= 0L)
	return Math.addExact(highWater, 1L)
}
