package com.adsamcik.tracker.tracker.source.runtime

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener2
import android.hardware.SensorManager
import android.os.SystemClock
import com.adsamcik.tracker.shared.base.di.ApplicationScope
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

	override suspend fun reconfigure(plan: StepsPlan, sink: SourceEventSink): SourceApplyResult = lifecycleMutex.withLock {
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
		val nextRegistration = runCatchingNonCancellation {
			registrations.begin(source, plan.revision, plan.physicalConfigurationFingerprint(), System.currentTimeMillis())
		}.getOrElse {
			return SourceStartResult.Failed(
				appliedState(source, plan.revision, null, SourceApplyStatus.FAILED, SystemClock.elapsedRealtimeNanos()),
				retryable = true,
			)
		}
		val baselineBoundary = StepBaselineBoundary(
			nextRegistration.state.registrationGeneration,
			nextRegistration.eligibilityFingerprint,
		)
		val saved = registrations.loadRuntimeState(nextRegistration)
		val savedForBoundary = saved?.takeIf {
			it.registrationGeneration == nextRegistration.state.registrationGeneration
		}
		val restored = decodeSensorRuntimeCheckpoint(savedForBoundary, STEP_BASELINE_VERSION)
		val accumulator = StepWindowAccumulator(
			restored?.let {
				decodeStepBaseline(it.componentPayload, it.componentStateVersion, baselineBoundary)
			},
			baselineBoundary,
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
		val nextQueue = Channel<RawStep>(CALLBACK_BUFFER_CAPACITY)
		val nextToken = synchronized(callbackLock) {
			callbackGate.activate(
				nextRegistration.state.registrationGeneration,
				nextRegistration.eligibilityFingerprint,
			).also { callbackToken = it }
		}
		val nextListener = StepRegistrationListener(nextToken)
		registration = nextRegistration
		listener = nextListener
		currentPlan = plan
		currentSink = sink
		queue = nextQueue
		acceptingCallbacks = true
		val maximumLatencyUs = plan.maximumReportLatencyMs
			.coerceAtLeast(0L)
			.coerceAtMost((Int.MAX_VALUE / MICROS_PER_MILLISECOND).toLong())
			.times(MICROS_PER_MILLISECOND.toLong())
			.toInt()
		val registered = sensorManager.registerListener(
			nextListener,
			stepSensor,
			SensorManager.SENSOR_DELAY_NORMAL,
			maximumLatencyUs,
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
		batchingEnabled = maximumLatencyUs > 0 && stepSensor.fifoMaxEventCount > 0
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
			val sourceSequence = runCatchingNonCancellation {
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
				runCatchingNonCancellation {
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

	private fun onSensorChanged(callbackToken: StepCallbackToken, event: SensorEvent) {
		if (event.sensor.type != Sensor.TYPE_STEP_COUNTER) return
		val cumulative = event.values.firstOrNull()?.toLong() ?: return
		if (cumulative < 0L) return
		val receivedElapsed = SystemClock.elapsedRealtimeNanos()
		val receivedWall = System.currentTimeMillis()
		synchronized(callbackLock) {
			if (!acceptingCallbacks || !callbackGate.accepts(callbackToken)) return
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
		val boundary = StepBaselineBoundary(
			activeRegistration.state.registrationGeneration,
			activeRegistration.eligibilityFingerprint,
		)
		val component = saved
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
				metrics.snapshot(),
				component?.componentStateVersion ?: STEP_BASELINE_VERSION,
				component?.componentPayload ?: ByteArray(0),
			),
			System.currentTimeMillis(),
		)
	}

	private fun onFlushCompleted(callbackToken: StepCallbackToken, sensor: Sensor?) {
		if (sensor?.type == Sensor.TYPE_STEP_COUNTER) {
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
		listener = null
		actor = null
		batchingEnabled = false
		cutoffElapsedNanos = null
		currentPlan = null
	}

	private inner class StepRegistrationListener(
		private val token: StepCallbackToken,
	) : SensorEventListener2 {
		override fun onSensorChanged(event: SensorEvent) = this@StepSourceRuntime.onSensorChanged(token, event)

		override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

		override fun onFlushCompleted(sensor: Sensor?) = this@StepSourceRuntime.onFlushCompleted(token, sensor)
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
