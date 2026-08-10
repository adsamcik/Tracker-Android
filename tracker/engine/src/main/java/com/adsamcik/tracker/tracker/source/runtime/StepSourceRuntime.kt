package com.adsamcik.tracker.tracker.source.runtime

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener2
import android.hardware.SensorManager
import android.os.SystemClock
import com.adsamcik.tracker.shared.base.di.ApplicationScope
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.SourceApplyStatus
import com.adsamcik.tracker.tracker.source.model.SourceEvidenceCandidate
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourceQuality
import com.adsamcik.tracker.tracker.source.model.SourceQualityFlag
import com.adsamcik.tracker.tracker.source.model.StepsPlan
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
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

@Singleton
class StepSourceRuntime @Inject constructor(
	@ApplicationContext private val context: Context,
	@ApplicationScope private val applicationScope: CoroutineScope,
	private val registrations: SourceRegistrationRepository,
) : SourceRuntime<StepsPlan>, SensorEventListener2 {
	override val source: SourceKind = SourceKind.STEPS
	private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
	private val sensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
	private val _capabilities = MutableStateFlow(sensor.toCapabilities())
	override val capabilities: StateFlow<SourceCapabilities> = _capabilities

	private val lifecycleMutex = Mutex()
	private val callbackLock = Any()
	private var registration: SourceRegistration? = null
	private var currentPlan: StepsPlan? = null
	private var currentSink: SourceEventSink? = null
	private var queue: Channel<RawStep>? = null
	private var actor: Job? = null
	private var acceptingCallbacks = false
	private var callbackEntrySequence = 0L
	private var cutoffElapsedNanos: Long? = null
	private var flushCompletion: CompletableDeferred<Unit>? = null
	private var batchingEnabled = false
	private var metrics = RuntimeAdmissionMetrics()

	override suspend fun start(plan: StepsPlan, sink: SourceEventSink): SourceStartResult = lifecycleMutex.withLock {
		require(currentPlan == null) { "Step source is already started" }
		startLocked(plan, sink)
	}

	override suspend fun reconfigure(plan: StepsPlan): SourceApplyResult = lifecycleMutex.withLock {
		val sink = currentSink
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
			currentPlan = null
			currentSink = sink
			return@withLock SourceApplyResult.Applied(
				appliedState(source, plan.revision, null, SourceApplyStatus.APPLIED, SystemClock.elapsedRealtimeNanos()),
			)
		}
		if (sink == null) {
			return@withLock SourceApplyResult.Failed(
				appliedState(source, plan.revision, null, SourceApplyStatus.FAILED, SystemClock.elapsedRealtimeNanos()),
				retryable = false,
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

	private suspend fun startLocked(plan: StepsPlan, sink: SourceEventSink): SourceStartResult {
		if (!plan.enabled) {
			return SourceStartResult.Started(
				appliedState(source, plan.revision, null, SourceApplyStatus.APPLIED, SystemClock.elapsedRealtimeNanos()),
			)
		}
		val stepSensor = sensor ?: return SourceStartResult.Blocked(
			appliedState(source, plan.revision, null, SourceApplyStatus.BLOCKED, SystemClock.elapsedRealtimeNanos()),
		)
		val nextRegistration = runCatching {
			registrations.begin(source, plan.revision, System.currentTimeMillis())
		}.getOrElse {
			return SourceStartResult.Failed(
				appliedState(source, plan.revision, null, SourceApplyStatus.FAILED, SystemClock.elapsedRealtimeNanos()),
				retryable = true,
			)
		}
		val saved = registrations.loadRuntimeState(nextRegistration)
		val restored = (decodeSensorRuntimeCheckpoint(saved, STEP_BASELINE_VERSION)
			?: missingSensorCheckpointAfterRegistration(
				nextRegistration.state.registrationGeneration,
				STEP_BASELINE_VERSION,
			))
			?.withProcessRestartIfNeeded(
				priorRegistrationGeneration = saved?.registrationGeneration ?: nextRegistration.state.registrationGeneration,
				currentRegistrationGeneration = nextRegistration.state.registrationGeneration,
				lastProviderSequence = saved?.lastProviderSequence ?: 0L,
			)
		val accumulator = StepWindowAccumulator(
			restored?.let { decodeStepBaseline(it.componentPayload, it.componentStateVersion) },
		)
		metrics = RuntimeAdmissionMetrics(
			lastDurablyAdmittedSequence = restored?.metrics?.lastDurablyAdmittedSequence,
			lastAdmissionOrdinal = restored?.metrics?.lastAdmissionOrdinal,
			failedAdmissionCount = restored?.metrics?.failedAdmissionCount ?: 0L,
			unresolvedSequenceStart = restored?.metrics?.unresolvedSequenceStart,
			unresolvedSequenceEndInclusive = restored?.metrics?.unresolvedSequenceEndInclusive,
			gapClassifications = restored?.metrics?.gapClassifications.orEmpty(),
		)
		callbackEntrySequence = saved?.lastProviderSequence ?: 0L
		cutoffElapsedNanos = null
		val nextQueue = Channel<RawStep>(CALLBACK_BUFFER_CAPACITY)
		registration = nextRegistration
		currentPlan = plan
		currentSink = sink
		queue = nextQueue
		acceptingCallbacks = true
		if (restored?.metrics?.gapClassifications?.contains(RuntimeGapClassification.PROCESS_RESTARTED) == true) {
			registrations.saveSensorRuntimeCheckpoint(
				nextRegistration,
				callbackEntrySequence,
				restored.copy(lifecycle = RuntimeCheckpointLifecycle.ACTIVE),
				System.currentTimeMillis(),
			)
		}
		actor = applicationScope.launch { consume(nextQueue, nextRegistration, sink, accumulator) }
		val maximumLatencyUs = plan.maximumReportLatencyMs
			.coerceAtLeast(0L)
			.coerceAtMost((Int.MAX_VALUE / MICROS_PER_MILLISECOND).toLong())
			.times(MICROS_PER_MILLISECOND.toLong())
			.toInt()
		val registered = sensorManager.registerListener(
			this,
			stepSensor,
			SensorManager.SENSOR_DELAY_NORMAL,
			maximumLatencyUs,
		)
		if (!registered) {
			synchronized(callbackLock) {
				acceptingCallbacks = false
				nextQueue.close()
			}
			actor?.join()
			clearActiveState()
			return SourceStartResult.Failed(
				appliedState(source, plan.revision, nextRegistration, SourceApplyStatus.FAILED, SystemClock.elapsedRealtimeNanos()),
				retryable = true,
			)
		}
		batchingEnabled = maximumLatencyUs > 0 && stepSensor.fifoMaxEventCount > 0
		return SourceStartResult.Started(
			appliedState(source, plan.revision, nextRegistration, SourceApplyStatus.APPLIED, SystemClock.elapsedRealtimeNanos()),
		)
	}

	private suspend fun shutdownLocked(cutoff: SessionCutoff?): SourceStopAck {
		val activeRegistration = registration ?: return unavailableAck(cutoff)
		cutoffElapsedNanos = cutoff?.elapsedRealtimeNanos
		val flushOutcome = flushProvider(cutoff)
		val barrier: Long
		synchronized(callbackLock) {
			acceptingCallbacks = false
			barrier = callbackEntrySequence
		}
		val removal = runCatching {
			sensorManager.unregisterListener(this)
			RegistrationRemovalOutcome.REMOVED
		}.getOrDefault(RegistrationRemovalOutcome.FAILED)
		val activeQueue = synchronized(callbackLock) {
			queue.also { it?.close() }
		}
		val activeActor = actor
		val drainComplete = if (activeActor == null) true else {
			val remainingMs = cutoff?.let {
				((it.deadlineElapsedRealtimeNanos - SystemClock.elapsedRealtimeNanos()) / NANOS_PER_MILLISECOND)
					.coerceAtLeast(1L)
			} ?: DEFAULT_DRAIN_TIMEOUT_MS
			withTimeoutOrNull(remainingMs) { joinAll(activeActor); true } == true
		}
		if (!drainComplete) activeActor?.cancelAndJoin()
		@Suppress("UNUSED_VARIABLE") val keepReferenceUntilDrain = activeQueue
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
		clearActiveState()
		return ack
	}

	private suspend fun flushProvider(cutoff: SessionCutoff?): ProviderFlushOutcome {
		if (!batchingEnabled) return ProviderFlushOutcome.NOT_SUPPORTED
		val completion = CompletableDeferred<Unit>()
		synchronized(callbackLock) { flushCompletion = completion }
		if (!sensorManager.flush(this)) {
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
		events: Channel<RawStep>,
		activeRegistration: SourceRegistration,
		sink: SourceEventSink,
		accumulator: StepWindowAccumulator,
	) {
		for (event in events) {
			if (cutoffElapsedNanos?.let { event.observedElapsedNanos > it } == true) continue
			val payload = accumulator.accept(
				activeRegistration.state.clockDomainId,
				event.cumulativeCount,
				event.observedElapsedNanos,
				event.providerSequence,
			)
			val sourceSequence = runCatching {
				registrations.allocateSequence(activeRegistration, event.receivedWallTimeMs)
			}.getOrElse {
				metrics.recordFailure(
					event.providerSequence,
					classification = RuntimeGapClassification.SEQUENCE_ALLOCATION_FAILED,
				)
				continue
			}
			val delayNanos = (event.receivedElapsedNanos - event.observedElapsedNanos).coerceAtLeast(0L)
			val acquiredAtMs = (event.receivedWallTimeMs - delayNanos / NANOS_PER_MILLISECOND).coerceAtLeast(0L)
			val candidate = SourceEvidenceCandidate(
				providerDedupKey = "${activeRegistration.state.sourceInstanceId}:" +
					"${activeRegistration.state.registrationGeneration}:${event.providerSequence}",
				logicalTrackingId = null,
				serviceRunId = null,
				source = source,
				sourceInstanceId = SourceInstanceId(activeRegistration.state.sourceInstanceId),
				registrationGeneration = activeRegistration.state.registrationGeneration,
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
					flags = if (delayNanos >= BATCHED_AFTER_NANOS) setOf(SourceQualityFlag.BATCHED) else emptySet(),
				),
				payloadVersion = 1,
				payload = payload,
			)
			when (val handoff = sink.admitWithBoundedRetry(candidate)) {
				is SourceAdmissionHandoff.Durable -> metrics.recordDurable(event.providerSequence, handoff.admissionOrdinal)
				is SourceAdmissionHandoff.Duplicate ->
					metrics.recordDurable(event.providerSequence, handoff.existingAdmissionOrdinal)
				is SourceAdmissionHandoff.RetryableFailure,
				is SourceAdmissionHandoff.TerminalFailure -> metrics.recordFailure(event.providerSequence)
			}
			accumulator.snapshot()?.let { baseline ->
				runCatching {
					val admission = metrics.snapshot()
					registrations.saveSensorRuntimeCheckpoint(
						registration = activeRegistration,
						lastProviderSequence = event.providerSequence,
						checkpoint = SensorRuntimeCheckpoint(
							RuntimeCheckpointLifecycle.ACTIVE,
							admission,
							STEP_BASELINE_VERSION,
							baseline.encode(),
						),
						updatedAtMs = event.receivedWallTimeMs,
					)
				}
			}
		}
	}

	override fun onSensorChanged(event: SensorEvent) {
		if (event.sensor.type != Sensor.TYPE_STEP_COUNTER) return
		val cumulative = event.values.firstOrNull()?.toLong() ?: return
		if (cumulative < 0L) return
		val receivedElapsed = SystemClock.elapsedRealtimeNanos()
		val receivedWall = System.currentTimeMillis()
		synchronized(callbackLock) {
			if (!acceptingCallbacks) return
			val providerSequence = ++callbackEntrySequence
			val accepted = queue?.trySend(
				RawStep(cumulative, event.timestamp, receivedElapsed, receivedWall, providerSequence),
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
		val component = decodeSensorRuntimeCheckpoint(saved, STEP_BASELINE_VERSION)
		registrations.saveSensorRuntimeCheckpoint(
			activeRegistration,
			lastProviderSequence,
			SensorRuntimeCheckpoint(
				lifecycle,
				metrics.snapshot(),
				component?.componentStateVersion ?: STEP_BASELINE_VERSION,
				component?.componentPayload ?: ByteArray(0),
			),
			System.currentTimeMillis(),
		)
	}

	override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

	override fun onFlushCompleted(sensor: Sensor?) {
		if (sensor?.type == Sensor.TYPE_STEP_COUNTER) {
			synchronized(callbackLock) { flushCompletion?.complete(Unit) }
		}
	}

	private fun clearActiveState() {
		synchronized(callbackLock) {
			acceptingCallbacks = false
			queue = null
			flushCompletion = null
		}
		actor = null
		batchingEnabled = false
		cutoffElapsedNanos = null
		currentPlan = null
	}

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

	private data class RawStep(
		val cumulativeCount: Long,
		val observedElapsedNanos: Long,
		val receivedElapsedNanos: Long,
		val receivedWallTimeMs: Long,
		val providerSequence: Long,
	)

	private fun Sensor?.toCapabilities(): SourceCapabilities = SourceCapabilities(
		available = this != null && context.packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_STEP_COUNTER),
		batchingSupported = this?.fifoMaxEventCount?.let { it > 0 } == true,
		flushSupported = this?.fifoMaxEventCount?.let { it > 0 } == true,
		maximumBatchSize = this?.fifoMaxEventCount,
		minimumDelayMs = this?.minDelay?.takeIf { it >= 0 }?.div(MICROS_PER_MILLISECOND)?.toLong(),
	)

	private companion object {
		const val CALLBACK_BUFFER_CAPACITY = 256
		const val MICROS_PER_MILLISECOND = 1_000
		const val NANOS_PER_MILLISECOND = 1_000_000L
		const val BATCHED_AFTER_NANOS = 5_000L * NANOS_PER_MILLISECOND
		const val PROVIDER_FLUSH_TIMEOUT_MS = 1_000L
		const val DEFAULT_DRAIN_TIMEOUT_MS = 2_000L
	}
}
