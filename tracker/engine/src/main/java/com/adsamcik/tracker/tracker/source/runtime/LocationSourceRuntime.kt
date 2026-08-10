package com.adsamcik.tracker.tracker.source.runtime

import android.content.Context
import android.location.Location
import android.os.SystemClock
import com.adsamcik.tracker.shared.base.di.ApplicationScope
import com.adsamcik.tracker.tracker.source.model.LocationBackend
import com.adsamcik.tracker.tracker.source.model.LocationFixPayload
import com.adsamcik.tracker.tracker.source.model.LocationMode
import com.adsamcik.tracker.tracker.source.model.LocationPlan
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.SourceApplyStatus
import com.adsamcik.tracker.tracker.source.model.SourceDegradedReason
import com.adsamcik.tracker.tracker.source.model.SourceEvidenceCandidate
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourceQuality
import com.adsamcik.tracker.tracker.source.model.SourceQualityFlag
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
) : SourceRuntime<LocationPlan> {
	override val source = SourceKind.LOCATION
	private val _capabilities = MutableStateFlow(currentCapabilities())
	override val capabilities: StateFlow<SourceCapabilities> = _capabilities
	private val lifecycleMutex = Mutex()
	private val callbackLock = Any()
	private var registration: SourceRegistration? = null
	private var currentPlan: LocationPlan? = null
	private var currentSink: SourceEventSink? = null
	private var activeBackend: LocationSourceBackendController? = null
	private var queue: Channel<RawLocationBatch>? = null
	private var actor: Job? = null
	private var acceptingCallbacks = false
	private var callbackEntrySequence = 0L
	private var cutoffElapsedNanos: Long? = null
	private var metrics = RuntimeAdmissionMetrics()

	override suspend fun start(plan: LocationPlan, sink: SourceEventSink): SourceStartResult = lifecycleMutex.withLock {
		require(currentPlan == null) { "Location source is already started" }
		startLocked(plan, sink)
	}

	override suspend fun reconfigure(plan: LocationPlan): SourceApplyResult = lifecycleMutex.withLock {
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
		currentSink = null
	}

	private suspend fun startLocked(plan: LocationPlan, sink: SourceEventSink): SourceStartResult {
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
		val nextRegistration = runCatching {
			registrations.begin(source, plan.revision, System.currentTimeMillis())
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
		val nextQueue = Channel<RawLocationBatch>(CALLBACK_BUFFER_CAPACITY)
		registration = nextRegistration
		currentPlan = effectivePlan
		currentSink = sink
		activeBackend = backend
		queue = nextQueue
		callbackEntrySequence = 0L
		cutoffElapsedNanos = null
		metrics = RuntimeAdmissionMetrics()
		acceptingCallbacks = true
		actor = applicationScope.launch { consume(nextQueue, nextRegistration, sink) }
		val started = backend.start(effectivePlan) { locations -> onLocationBatch(locations) }
		if (!started) {
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

	private suspend fun shutdownLocked(cutoff: SessionCutoff?): SourceStopAck {
		val activeRegistration = registration ?: return unavailableAck(cutoff)
		val backend = activeBackend
		cutoffElapsedNanos = cutoff?.elapsedRealtimeNanos
		val flushOutcome = if (backend == null) ProviderFlushOutcome.NOT_REQUESTED else {
			withTimeoutOrNull(PROVIDER_OPERATION_TIMEOUT_MS) { backend.flush() }
				?: ProviderFlushOutcome.TIMED_OUT
		}
		val barrier: Long
		synchronized(callbackLock) {
			acceptingCallbacks = false
			barrier = callbackEntrySequence
		}
		val removal = if (backend == null) RegistrationRemovalOutcome.NOT_REGISTERED else {
			withTimeoutOrNull(PROVIDER_OPERATION_TIMEOUT_MS) { backend.stop() }
				?: RegistrationRemovalOutcome.FAILED
		}
		synchronized(callbackLock) { queue?.close() }
		val activeActor = actor
		val remainingMs = cutoff?.let {
			((it.deadlineElapsedRealtimeNanos - SystemClock.elapsedRealtimeNanos()) / NANOS_PER_MILLISECOND)
				.coerceAtLeast(1L)
		} ?: DEFAULT_DRAIN_TIMEOUT_MS
		val drainComplete = activeActor == null ||
			withTimeoutOrNull(remainingMs) { activeActor.join(); true } == true
		if (!drainComplete) activeActor.cancelAndJoin()
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
			// Neither FLP flushLocations nor LocationManager removal proves provider-wide completeness.
			providerCoverage = ProviderCoverage.PROVIDER_COMPLETENESS_UNOBSERVABLE,
			appDrainComplete = drainComplete,
			status = when {
				!drainComplete -> SourceStopStatus.TIMED_OUT
				removal == RegistrationRemovalOutcome.FAILED -> SourceStopStatus.PROVIDER_FAILED
				else -> SourceStopStatus.COMPLETE
			},
		)
		clearActiveState()
		return ack
	}

	private fun onLocationBatch(locations: List<Location>) {
		if (locations.isEmpty()) return
		val receivedElapsed = SystemClock.elapsedRealtimeNanos()
		val receivedWall = System.currentTimeMillis()
		synchronized(callbackLock) {
			if (!acceptingCallbacks) return
			val callbackSequence = ++callbackEntrySequence
			val accepted = queue?.trySend(
				RawLocationBatch(locations.map(::Location), receivedElapsed, receivedWall, callbackSequence),
			)?.isSuccess == true
			if (!accepted) metrics.recordFailure(callbackSequence)
		}
	}

	private suspend fun consume(
		batches: Channel<RawLocationBatch>,
		activeRegistration: SourceRegistration,
		sink: SourceEventSink,
	) {
		for (batch in batches) {
			val sorted = normalizeLocationBatch(batch.locations)
			for (location in sorted) {
				admitLocation(
					location = location,
					batch = batch,
					batchSize = sorted.size,
					activeRegistration = activeRegistration,
					sink = sink,
				)
			}
		}
	}

	private suspend fun admitLocation(
		location: Location,
		batch: RawLocationBatch,
		batchSize: Int,
		activeRegistration: SourceRegistration,
		sink: SourceEventSink,
	) {
		val observedNanos = location.elapsedRealtimeNanos.takeIf { it > 0L } ?: batch.receivedElapsedNanos
		if (cutoffElapsedNanos?.let { observedNanos > it } == true) return
		if (!location.isValidLocationEvidence()) {
			metrics.recordFailure(batch.callbackSequence)
			return
		}
		val sourceSequence = runCatching {
			registrations.allocateSequence(activeRegistration, batch.receivedWallTimeMs)
		}.getOrElse {
			metrics.recordFailure(batch.callbackSequence)
			return
		}
		val delayNanos = (batch.receivedElapsedNanos - observedNanos).coerceAtLeast(0L)
		val acquiredAtMs = location.time.takeIf { it >= 0L }
			?: (batch.receivedWallTimeMs - delayNanos / NANOS_PER_MILLISECOND).coerceAtLeast(0L)
		val approximate = currentPlan?.preciseLocationAvailable == false
		val candidate = SourceEvidenceCandidate(
			providerDedupKey = "${activeRegistration.state.sourceInstanceId}:" +
				"${activeRegistration.state.registrationGeneration}:${location.provider}:" +
				"$observedNanos:${location.latitude}:${location.longitude}",
			logicalTrackingId = null,
			serviceRunId = null,
			source = source,
			sourceInstanceId = SourceInstanceId(activeRegistration.state.sourceInstanceId),
			registrationGeneration = activeRegistration.state.registrationGeneration,
			sourceSequence = sourceSequence,
			configRevision = activeRegistration.state.appliedRevision,
			planAttribution = PlanAttribution.CAPTURED_REGISTRATION,
			clockDomainId = activeRegistration.state.clockDomainId,
			observedElapsedRealtimeNanos = observedNanos,
			receivedElapsedRealtimeNanos = batch.receivedElapsedNanos,
			wallTimeMs = acquiredAtMs,
			wallTimeUncertaintyMs = if (location.time >= 0L) 0L else 1L,
			capturedCollectedDataEpoch = activeRegistration.state.collectedDataEpoch,
			acquiredAtMs = acquiredAtMs,
			quality = SourceQuality(
				flags = buildSet {
					if (approximate) add(SourceQualityFlag.APPROXIMATE)
					if (batchSize > 1 || delayNanos >= BATCHED_AFTER_NANOS) add(SourceQualityFlag.BATCHED)
					if (location.elapsedRealtimeNanos <= 0L) add(SourceQualityFlag.CLOCK_UNCERTAIN)
				},
			),
			payloadVersion = 1,
			payload = LocationFixPayload(
				latitudeDegrees = location.latitude,
				longitudeDegrees = location.longitude,
				horizontalAccuracyMeters = location.accuracy,
				altitudeMeters = location.altitude.takeIf { location.hasAltitude() },
				verticalAccuracyMeters = location.verticalAccuracyMeters.takeIf { location.hasVerticalAccuracy() },
				speedMetersPerSecond = location.speed.takeIf { location.hasSpeed() },
				bearingDegrees = location.bearing.takeIf { location.hasBearing() },
				provider = location.provider ?: "unknown",
			),
		)
		when (val handoff = sink.admitWithBoundedRetry(candidate)) {
			is SourceAdmissionHandoff.Durable -> metrics.recordDurable(sourceSequence, handoff.admissionOrdinal)
			is SourceAdmissionHandoff.Duplicate -> metrics.recordDurable(sourceSequence, handoff.existingAdmissionOrdinal)
			is SourceAdmissionHandoff.RetryableFailure,
			is SourceAdmissionHandoff.TerminalFailure -> metrics.recordFailure(sourceSequence)
		}
	}

	private fun currentCapabilities(): SourceCapabilities {
		val device = deviceStateProvider.snapshot()
		val permission = device.coarsePermission || device.finePermission
		return SourceCapabilities(
		available = device.locationFeatureAvailable && permission,
		batchingSupported = true,
		flushSupported = true,
		maximumBatchSize = null,
		minimumDelayMs = null,
		degradedReasons = if (permission) emptySet() else setOf(SourceDegradedReason.PERMISSION_MISSING),
		)
	}

	private fun clearActiveState() {
		synchronized(callbackLock) {
			acceptingCallbacks = false
			queue = null
		}
		actor = null
		activeBackend = null
		cutoffElapsedNanos = null
		currentPlan = null
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
		)
	}

	private data class RawLocationBatch(
		val locations: List<Location>,
		val receivedElapsedNanos: Long,
		val receivedWallTimeMs: Long,
		val callbackSequence: Long,
	)

	private companion object {
		const val CALLBACK_BUFFER_CAPACITY = 64
		const val NANOS_PER_MILLISECOND = 1_000_000L
		const val BATCHED_AFTER_NANOS = 5_000L * NANOS_PER_MILLISECOND
		const val PROVIDER_OPERATION_TIMEOUT_MS = 2_000L
		const val DEFAULT_DRAIN_TIMEOUT_MS = 3_000L
	}
}
