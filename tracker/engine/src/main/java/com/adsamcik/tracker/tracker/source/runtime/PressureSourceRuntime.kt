package com.adsamcik.tracker.tracker.source.runtime

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener2
import android.hardware.SensorManager
import android.os.SystemClock
import com.adsamcik.tracker.shared.base.di.ApplicationScope
import com.adsamcik.tracker.tracker.source.runCatchingNonCancellation
import com.adsamcik.tracker.tracker.altitude.BarometricAltitudeFormula
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.PressurePlan
import com.adsamcik.tracker.tracker.source.model.physicalConfigurationFingerprint
import com.adsamcik.tracker.tracker.source.model.PressureWindowPayload
import com.adsamcik.tracker.tracker.source.model.SourceApplyStatus
import com.adsamcik.tracker.tracker.source.model.SourceEvidenceCandidate
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourceQuality
import com.adsamcik.tracker.tracker.source.model.SourceQualityFlag
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

@Singleton
class PressureSourceRuntime @Inject constructor(
	@ApplicationContext context: Context,
	@ApplicationScope private val applicationScope: CoroutineScope,
	private val registrations: SourceRegistrationRepository,
) : SourceRuntime<PressurePlan> {
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
	private var currentSink: SourceEventSink? = null
	private var queue: Channel<RawPressure>? = null
	private var actor: Job? = null
	private var acceptingCallbacks = false
	private var callbackEntrySequence = 0L
	private var cutoffElapsedNanos: Long? = null
	private var flushCompletion: CompletableDeferred<Unit>? = null
	private var batchingEnabled = false
	private var metrics = RuntimeAdmissionMetrics()

	override suspend fun start(plan: PressurePlan, sink: SourceEventSink): SourceStartResult = lifecycleMutex.withLock {
		require(currentPlan == null) { "Pressure source is already started" }
		startLocked(plan, sink)
	}

	override suspend fun reconfigure(plan: PressurePlan, sink: SourceEventSink): SourceApplyResult = lifecycleMutex.withLock {
		if (currentPlan != null) {
			val previous = shutdownLocked(null)
			if (!previous.appDrainComplete) {
				return@withLock SourceApplyResult.Failed(
					appliedState(source, plan.revision, null, SourceApplyStatus.FAILED, SystemClock.elapsedRealtimeNanos()),
					retryable = true,
				)
			}
		}
		if (!plan.enabled) {
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
		shutdownLocked(cutoff)
	}

	override suspend fun close() = lifecycleMutex.withLock {
		if (currentPlan != null) shutdownLocked(null)
		registration = null
		currentPlan = null
		currentSink = null
	}

	private suspend fun startLocked(plan: PressurePlan, sink: SourceEventSink): SourceStartResult {
		if (!plan.enabled) {
			return SourceStartResult.Started(
				appliedState(source, plan.revision, null, SourceApplyStatus.APPLIED, SystemClock.elapsedRealtimeNanos()),
			)
		}
		val pressureSensor = sensor ?: return SourceStartResult.Blocked(
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
		val accumulatorBoundary = PressureAccumulatorBoundary(
			registrationGeneration = nextRegistration.state.registrationGeneration,
			eligibilityFingerprint = nextRegistration.eligibilityFingerprint,
			appliedRevision = requireNotNull(nextRegistration.state.appliedRevision) {
				"Pressure registration must retain its applied manifest revision"
			},
		)
		val saved = registrations.loadRuntimeState(nextRegistration)
		val savedForBoundary = saved?.takeIf {
			it.registrationGeneration == nextRegistration.state.registrationGeneration
		}
		val restored = decodeSensorRuntimeCheckpoint(savedForBoundary, PRESSURE_ACCUMULATOR_VERSION)
		val accumulator = PressureWindowAccumulator(
			windowNanos = plan.aggregationWindowMs.coerceAtLeast(1L) * NANOS_PER_MILLISECOND,
			boundary = accumulatorBoundary,
			state = restored?.let {
				decodePressureAccumulator(
					it.componentPayload,
					it.componentStateVersion,
					accumulatorBoundary,
				)
			},
		)
		metrics = RuntimeAdmissionMetrics(
			lastDurablyAdmittedSequence = restored?.metrics?.lastDurablyAdmittedSequence,
			lastAdmissionOrdinal = restored?.metrics?.lastAdmissionOrdinal,
			failedAdmissionCount = restored?.metrics?.failedAdmissionCount ?: 0L,
			unresolvedSequenceStart = restored?.metrics?.unresolvedSequenceStart,
			unresolvedSequenceEndInclusive = restored?.metrics?.unresolvedSequenceEndInclusive,
			gapClassifications = restored?.metrics?.gapClassifications.orEmpty(),
		)
		callbackEntrySequence = savedForBoundary?.lastProviderSequence ?: 0L
		cutoffElapsedNanos = null
		val nextQueue = Channel<RawPressure>(CALLBACK_BUFFER_CAPACITY)
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
		currentSink = sink
		queue = nextQueue
		acceptingCallbacks = true
		val registered = sensorManager.registerListener(
			nextListener,
			pressureSensor,
			plan.hardwareSamplePeriodMicros.coerceAtLeast(1),
			plan.maximumReportLatencyMicros.coerceAtLeast(0),
		)
		if (!registered) {
			registrations.markFailed(nextRegistration, "PROVIDER_REGISTRATION_FAILED", System.currentTimeMillis())
			synchronized(callbackLock) {
				acceptingCallbacks = false
				callbackGate.retire(nextToken)
				nextQueue.close()
			}
			actor?.join()
			clearActiveState()
			return SourceStartResult.Failed(
				appliedState(source, plan.revision, nextRegistration, SourceApplyStatus.FAILED, SystemClock.elapsedRealtimeNanos()),
				retryable = true,
			)
		}
		val accepted = runCatchingNonCancellation {
			registrations.markAccepted(nextRegistration, System.currentTimeMillis())
		}.isSuccess
		if (!accepted) {
			synchronized(callbackLock) {
				acceptingCallbacks = false
				callbackGate.retire(nextToken)
			}
			sensorManager.unregisterListener(nextListener)
			registrations.markFailed(nextRegistration, "REGISTRATION_ACCEPTANCE_STALE", System.currentTimeMillis())
			nextQueue.close()
			clearActiveState()
			return SourceStartResult.Failed(
				appliedState(source, plan.revision, nextRegistration, SourceApplyStatus.FAILED, SystemClock.elapsedRealtimeNanos()),
				retryable = true,
			)
		}
		actor = applicationScope.launch { consume(nextQueue, nextRegistration, sink, accumulator) }
		batchingEnabled = plan.maximumReportLatencyMicros > 0 && pressureSensor.fifoMaxEventCount > 0
		return SourceStartResult.Started(
			appliedState(source, plan.revision, nextRegistration, SourceApplyStatus.APPLIED, SystemClock.elapsedRealtimeNanos()),
		)
	}

	private suspend fun shutdownLocked(cutoff: SessionCutoff?): SourceStopAck {
		val activeRegistration = registration ?: return unavailableAck(cutoff)
		val activeListener = listener
		val activeToken = callbackToken
		cutoffElapsedNanos = cutoff?.elapsedRealtimeNanos
		val flushOutcome = if (activeListener != null && activeToken != null) {
			flushProvider(cutoff, activeListener, activeToken)
		} else {
			ProviderFlushOutcome.NOT_REQUESTED
		}
		val barrier: Long
		synchronized(callbackLock) {
			acceptingCallbacks = false
			activeToken?.let(callbackGate::retire)
			barrier = callbackEntrySequence
		}
		val removal = if (activeListener == null) {
			RegistrationRemovalOutcome.NOT_REGISTERED
		} else {
			runCatching {
				sensorManager.unregisterListener(activeListener)
				RegistrationRemovalOutcome.REMOVED
			}.getOrDefault(RegistrationRemovalOutcome.FAILED)
		}
		synchronized(callbackLock) { queue?.close() }
		val activeActor = actor
		val drainComplete = if (activeActor == null) true else {
			val remainingMs = cutoff?.let {
				((it.deadlineElapsedRealtimeNanos - SystemClock.elapsedRealtimeNanos()) / NANOS_PER_MILLISECOND)
					.coerceAtLeast(1L)
			} ?: DEFAULT_DRAIN_TIMEOUT_MS
			withTimeoutOrNull(remainingMs) { activeActor.join(); true } == true
		}
		if (!drainComplete) activeActor?.cancelAndJoin()
		if (!drainComplete) {
			val snapshot = metrics.snapshot()
			val firstUnresolved = (snapshot.lastDurablyAdmittedSequence ?: 0L) + 1L
			if (firstUnresolved <= barrier) {
				metrics.recordFailure(
					firstUnresolved,
					barrier,
					RuntimeGapClassification.DRAIN_TIMED_OUT,
				)
			}
		}
		persistTerminalCheckpoint(
			activeRegistration,
			barrier,
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
			status = if (drainComplete) SourceStopStatus.COMPLETE else SourceStopStatus.TIMED_OUT,
		)
		if (removal == RegistrationRemovalOutcome.FAILED) {
			registrations.markFailed(activeRegistration, "PROVIDER_REMOVAL_FAILED", System.currentTimeMillis())
		} else {
			registrations.markRetired(activeRegistration, System.currentTimeMillis())
		}
		clearActiveState()
		return ack
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

	private suspend fun consume(
		events: Channel<RawPressure>,
		activeRegistration: SourceRegistration,
		sink: SourceEventSink,
		accumulator: PressureWindowAccumulator,
	) {
		var lastReception: RawPressure? = null
		for (event in events) {
			if (cutoffElapsedNanos?.let { event.observedElapsedNanos > it } == true) continue
			lastReception = event
			val completed = accumulator.add(event.pressureHpa, event.observedElapsedNanos, event.providerSequence)
			if (completed != null) admitWindow(completed, event, activeRegistration, sink)
			persistAccumulator(activeRegistration, accumulator, event)
		}
		val finalWindow = accumulator.drain()
		if (finalWindow != null && lastReception != null) {
			admitWindow(finalWindow, lastReception, activeRegistration, sink)
			persistAccumulator(activeRegistration, accumulator, lastReception)
		}
	}

	private suspend fun admitWindow(
		payload: PressureWindowPayload,
		reception: RawPressure,
		activeRegistration: SourceRegistration,
		sink: SourceEventSink,
	) {
		val sourceSequence = runCatchingNonCancellation {
			registrations.allocateSequence(activeRegistration, reception.receivedWallTimeMs)
		}.getOrElse {
			metrics.recordFailure(
				payload.firstProviderSequence,
				payload.lastProviderSequence,
				RuntimeGapClassification.SEQUENCE_ALLOCATION_FAILED,
			)
			return
		}
		val delayNanos = (reception.receivedElapsedNanos - payload.windowEndElapsedRealtimeNanos).coerceAtLeast(0L)
		val acquiredAtMs = (reception.receivedWallTimeMs - delayNanos / NANOS_PER_MILLISECOND).coerceAtLeast(0L)
		val candidate = SourceEvidenceCandidate(
			providerDedupKey = "${activeRegistration.state.sourceInstanceId}:" +
				"${activeRegistration.state.registrationGeneration}:${payload.firstProviderSequence}-" +
				payload.lastProviderSequence,
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
			observedElapsedRealtimeNanos = payload.windowEndElapsedRealtimeNanos,
			receivedElapsedRealtimeNanos = reception.receivedElapsedNanos,
			wallTimeMs = acquiredAtMs,
			wallTimeUncertaintyMs = 1L,
			capturedCollectedDataEpoch = activeRegistration.state.collectedDataEpoch,
			acquiredAtMs = acquiredAtMs,
			quality = SourceQuality(
				flags = buildSet {
					if (delayNanos >= BATCHED_AFTER_NANOS) add(SourceQualityFlag.BATCHED)
					if (payload.sampleCount == 1) add(SourceQualityFlag.INCOMPLETE_WINDOW)
				},
			),
			payloadVersion = 1,
			payload = payload,
		)
		when (val handoff = sink.admitWithBoundedRetry(candidate)) {
			is SourceAdmissionHandoff.Durable ->
				metrics.recordDurable(payload.lastProviderSequence, handoff.admissionOrdinal)
			is SourceAdmissionHandoff.Duplicate ->
				metrics.recordDurable(payload.lastProviderSequence, handoff.existingAdmissionOrdinal)
			is SourceAdmissionHandoff.RetryableFailure,
			is SourceAdmissionHandoff.TerminalFailure -> metrics.recordFailure(
				payload.firstProviderSequence,
				payload.lastProviderSequence,
			)
		}
	}

	private suspend fun persistAccumulator(
		activeRegistration: SourceRegistration,
		accumulator: PressureWindowAccumulator,
		reception: RawPressure,
	) {
		val payload = accumulator.snapshot()?.encode() ?: ByteArray(0)
		runCatchingNonCancellation {
			val admission = metrics.snapshot()
			registrations.saveSensorRuntimeCheckpoint(
				registration = activeRegistration,
				lastProviderSequence = reception.providerSequence,
				checkpoint = SensorRuntimeCheckpoint(
					RuntimeCheckpointLifecycle.ACTIVE,
					admission,
					PRESSURE_ACCUMULATOR_VERSION,
					payload,
				),
				updatedAtMs = reception.receivedWallTimeMs,
			)
		}
	}

	private fun onSensorChanged(callbackToken: PressureCallbackToken, event: SensorEvent) {
		if (event.sensor.type != Sensor.TYPE_PRESSURE) return
		val pressure = event.values.firstOrNull() ?: return
		if (!BarometricAltitudeFormula.isValidPressure(pressure)) return
		val receivedElapsed = SystemClock.elapsedRealtimeNanos()
		val receivedWall = System.currentTimeMillis()
		synchronized(callbackLock) {
			if (!acceptingCallbacks || !callbackGate.accepts(callbackToken)) return
			val providerSequence = ++callbackEntrySequence
			val accepted = queue?.trySend(
				RawPressure(pressure, event.timestamp, receivedElapsed, receivedWall, providerSequence),
			)?.isSuccess == true
			if (!accepted) metrics.recordFailure(
				providerSequence,
				classification = RuntimeGapClassification.CALLBACK_BUFFER_OVERFLOW,
			)
		}
	}

	private suspend fun persistTerminalCheckpoint(
		activeRegistration: SourceRegistration,
		lastProviderSequence: Long,
		lifecycle: RuntimeCheckpointLifecycle,
	) {
		val saved = registrations.loadRuntimeState(activeRegistration)
		val boundary = PressureAccumulatorBoundary(
			registrationGeneration = activeRegistration.state.registrationGeneration,
			eligibilityFingerprint = activeRegistration.eligibilityFingerprint,
			appliedRevision = requireNotNull(activeRegistration.state.appliedRevision) {
				"Pressure registration must retain its applied manifest revision"
			},
		)
		val component = saved
			?.takeIf { it.registrationGeneration == activeRegistration.state.registrationGeneration }
			?.let { decodeSensorRuntimeCheckpoint(it, PRESSURE_ACCUMULATOR_VERSION) }
			?.takeIf {
				decodePressureAccumulator(it.componentPayload, it.componentStateVersion, boundary) != null
			}
		registrations.saveSensorRuntimeCheckpoint(
			activeRegistration,
			lastProviderSequence,
			SensorRuntimeCheckpoint(
				lifecycle,
				metrics.snapshot(),
				component?.componentStateVersion ?: PRESSURE_ACCUMULATOR_VERSION,
				component?.componentPayload ?: ByteArray(0),
			),
			System.currentTimeMillis(),
		)
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
			callbackToken?.let(callbackGate::retire)
			callbackToken = null
			queue = null
			flushCompletion = null
		}
		registration = null
		listener = null
		actor = null
		batchingEnabled = false
		cutoffElapsedNanos = null
		currentPlan = null
	}

	private inner class PressureRegistrationListener(
		private val token: PressureCallbackToken,
	) : SensorEventListener2 {
		override fun onSensorChanged(event: SensorEvent) = this@PressureSourceRuntime.onSensorChanged(token, event)

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
		)
	}

	private data class RawPressure(
		val pressureHpa: Float,
		val observedElapsedNanos: Long,
		val receivedElapsedNanos: Long,
		val receivedWallTimeMs: Long,
		val providerSequence: Long,
	)

	private fun Sensor?.toCapabilities() = SourceCapabilities(
		available = this != null,
		batchingSupported = this?.fifoMaxEventCount?.let { it > 0 } == true,
		flushSupported = this?.fifoMaxEventCount?.let { it > 0 } == true,
		maximumBatchSize = this?.fifoMaxEventCount,
		minimumDelayMs = this?.minDelay?.takeIf { it >= 0 }?.div(MICROS_PER_MILLISECOND)?.toLong(),
	)

	private companion object {
		const val CALLBACK_BUFFER_CAPACITY = 1_024
		const val MICROS_PER_MILLISECOND = 1_000
		const val NANOS_PER_MILLISECOND = 1_000_000L
		const val BATCHED_AFTER_NANOS = 5_000L * NANOS_PER_MILLISECOND
		const val PROVIDER_FLUSH_TIMEOUT_MS = 1_000L
		const val DEFAULT_DRAIN_TIMEOUT_MS = 2_000L
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
