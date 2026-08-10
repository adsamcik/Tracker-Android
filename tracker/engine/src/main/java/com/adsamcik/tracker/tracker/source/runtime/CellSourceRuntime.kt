package com.adsamcik.tracker.tracker.source.runtime

import android.os.SystemClock
import com.adsamcik.tracker.shared.base.di.ApplicationScope
import com.adsamcik.tracker.tracker.source.model.CellMode
import com.adsamcik.tracker.tracker.source.model.CellObservationEvidence
import com.adsamcik.tracker.tracker.source.model.CellPlan
import com.adsamcik.tracker.tracker.source.model.CellRefreshOutcome
import com.adsamcik.tracker.tracker.source.model.CellSnapshotPayload
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.SourceApplyStatus
import com.adsamcik.tracker.tracker.source.model.SourceDegradedReason
import com.adsamcik.tracker.tracker.source.model.SourceEvidenceCandidate
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourceQuality
import com.adsamcik.tracker.tracker.source.model.SourceQualityFlag
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
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CellSourceRuntime @Inject internal constructor(
	@ApplicationScope private val applicationScope: CoroutineScope,
	private val registrations: SourceRegistrationRepository,
	private val backend: AndroidCellSourceBackend,
	private val deviceStateProvider: AndroidConnectivityDeviceStateProvider,
	private val wakeups: CoalescingSourceWakeupScheduler,
) : SourceRuntime<CellPlan> {
	override val source = SourceKind.CELL
	private val _capabilities = MutableStateFlow(capabilitiesNow())
	override val capabilities: StateFlow<SourceCapabilities> = _capabilities
	private val lifecycleMutex = Mutex()
	private val callbackLock = Any()
	private var registration: SourceRegistration? = null
	private var currentPlan: CellPlan? = null
	private var currentSink: SourceEventSink? = null
	private var queue: Channel<CellRuntimeInput>? = null
	private var actor: Job? = null
	private var accepting = false
	private var callbackSequence = 0L
	private var cutoffElapsedNanos: Long? = null
	private var metrics = RuntimeAdmissionMetrics()

	override suspend fun start(plan: CellPlan, sink: SourceEventSink): SourceStartResult = lifecycleMutex.withLock {
		require(currentPlan == null) { "Cell source is already started" }
		startLocked(plan, sink)
	}

	override suspend fun reconfigure(plan: CellPlan): SourceApplyResult = lifecycleMutex.withLock {
		val sink = currentSink
		if (currentPlan != null) shutdownLocked(null)
		if (!plan.enabled) return@withLock SourceApplyResult.Applied(
			appliedState(source, plan.revision, null, SourceApplyStatus.APPLIED, SystemClock.elapsedRealtimeNanos()),
		)
		if (sink == null) return@withLock SourceApplyResult.Failed(
			appliedState(source, plan.revision, null, SourceApplyStatus.FAILED, SystemClock.elapsedRealtimeNanos()), false,
		)
		when (val result = startLocked(plan, sink)) {
			is SourceStartResult.Started -> SourceApplyResult.Applied(result.applied)
			is SourceStartResult.Degraded -> SourceApplyResult.Degraded(result.applied)
			is SourceStartResult.Blocked -> SourceApplyResult.Failed(result.applied, false)
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

	private suspend fun startLocked(plan: CellPlan, sink: SourceEventSink): SourceStartResult {
		if (!plan.enabled) return SourceStartResult.Started(
			appliedState(source, plan.revision, null, SourceApplyStatus.APPLIED, SystemClock.elapsedRealtimeNanos()),
		)
		val application = CellPrerequisiteEvaluator.evaluate(plan, deviceStateProvider.cell())
		_capabilities.value = capabilitiesNow()
		if (application.status == SourceApplyStatus.BLOCKED) return SourceStartResult.Blocked(
			appliedState(source, plan.revision, null, SourceApplyStatus.BLOCKED, SystemClock.elapsedRealtimeNanos())
				.copy(degradedReasons = application.reasons),
		)
		val nextRegistration = runCatching {
			registrations.begin(source, plan.revision, System.currentTimeMillis())
		}.getOrElse {
			return SourceStartResult.Failed(
				appliedState(source, plan.revision, null, SourceApplyStatus.FAILED, SystemClock.elapsedRealtimeNanos()), true,
			)
		}
		val nextQueue = Channel<CellRuntimeInput>(CALLBACK_BUFFER_CAPACITY)
		registration = nextRegistration
		currentPlan = plan
		currentSink = sink
		queue = nextQueue
		callbackSequence = 0L
		cutoffElapsedNanos = null
		metrics = RuntimeAdmissionMetrics()
		accepting = true
		actor = applicationScope.launch { consume(nextQueue, nextRegistration, sink) }
		if (!backend.start(plan.subscriptionIds, ::onBackendSnapshot)) {
			shutdownLocked(null)
			return SourceStartResult.Failed(
				appliedState(source, plan.revision, nextRegistration, SourceApplyStatus.FAILED, SystemClock.elapsedRealtimeNanos()), true,
			)
		}
		nextCallbackSequence()?.let { enqueue(CellRuntimeInput.Started(it)) }
		val state = appliedState(
			source, plan.revision, nextRegistration, application.status, SystemClock.elapsedRealtimeNanos(),
		).copy(degradedReasons = application.reasons)
		return if (application.status == SourceApplyStatus.DEGRADED) {
			SourceStartResult.Degraded(state)
		} else SourceStartResult.Started(state)
	}

	private suspend fun shutdownLocked(cutoff: SessionCutoff?): SourceStopAck {
		val activeRegistration = registration ?: return unavailableAck(cutoff)
		cutoffElapsedNanos = cutoff?.elapsedRealtimeNanos
		wakeups.cancel(REFRESH_WAKEUP_ID)
		wakeups.cancel(TIMEOUT_WAKEUP_ID)
		val barrier: Long
		synchronized(callbackLock) { accepting = false; barrier = callbackSequence }
		val removed = if (backend.stop()) RegistrationRemovalOutcome.REMOVED else RegistrationRemovalOutcome.FAILED
		synchronized(callbackLock) { queue?.close() }
		val activeActor = actor
		val remainingMs = cutoff?.let {
			((it.deadlineElapsedRealtimeNanos - SystemClock.elapsedRealtimeNanos()) / NANOS_PER_MILLISECOND)
				.coerceAtLeast(1L)
		} ?: DEFAULT_DRAIN_TIMEOUT_MS
		val drained = activeActor == null || withTimeoutOrNull(remainingMs) { activeActor.join(); true } == true
		if (!drained) activeActor?.cancelAndJoin()
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
		)
		clearActiveState()
		return ack
	}

	private fun onBackendSnapshot(snapshot: CellBackendSnapshot) {
		val sequence = nextCallbackSequence() ?: return
		enqueue(CellRuntimeInput.Snapshot(snapshot, CellRefreshOutcome.CALLBACK, sequence))
	}

	private fun nextCallbackSequence(): Long? = synchronized(callbackLock) {
		if (!accepting) null else ++callbackSequence
	}

	private fun enqueue(input: CellRuntimeInput) {
		val accepted = synchronized(callbackLock) {
			accepting && queue?.trySend(input)?.isSuccess == true
		}
		if (!accepted) metrics.recordFailure(input.callbackSequence)
	}

	private suspend fun consume(
		inputs: Channel<CellRuntimeInput>,
		activeRegistration: SourceRegistration,
		sink: SourceEventSink,
	) {
		var lastRefreshAtMs = Long.MIN_VALUE
		var backoff = RuntimeBackoffState()
		var awaitingRefresh = false
		val lastSignatureBySubscription = mutableMapOf<Int?, String>()
		val lastProviderTimeBySubscription = mutableMapOf<Int?, Long?>()

		suspend fun scheduleRefresh() {
			val plan = currentPlan ?: return
			if (plan.mode != CellMode.OBSERVE_AND_SPARSE_REFRESH) return
			val now = SystemClock.elapsedRealtime()
			val earliest = nextAttemptDeadlineMs(
				now,
				lastRefreshAtMs.takeUnless { it == Long.MIN_VALUE },
				plan.minimumRefreshAttemptIntervalMs,
				backoff.delayMs(plan.backoff),
			)
			wakeups.schedule(
				sourceWakeupRequest(
					REFRESH_WAKEUP_ID, source, earliest,
					(plan.minimumRefreshAttemptIntervalMs / 4L).coerceIn(0L, MAX_COALESCE_WINDOW_MS),
				),
			) {
				nextCallbackSequence()?.let { enqueue(CellRuntimeInput.Refresh(it)) }
			}
		}

		suspend fun recordSnapshot(snapshot: CellBackendSnapshot, outcome: CellRefreshOutcome) {
			val plan = currentPlan ?: return
			val providerTime = snapshot.observations.mapNotNull(CellBackendObservation::providerTimestampNanos).maxOrNull()
			val nowNanos = SystemClock.elapsedRealtimeNanos()
			val ageMs = providerTime?.let { (nowNanos - it).coerceAtLeast(0L) / NANOS_PER_MILLISECOND }
			if (outcome == CellRefreshOutcome.CACHED && ageMs != null && ageMs > plan.maximumAcceptableCachedAgeMs) return
			val observations = snapshot.observations.map { observation ->
				CellObservationEvidence(
					identifierToken = stableIdentifierToken("cell", observation.identity),
					radioType = observation.radioType,
					registered = observation.registered,
					signalLevelDbm = observation.signalLevelDbm,
					providerTimestampNanos = observation.providerTimestampNanos,
				)
			}.sortedWith(compareBy(CellObservationEvidence::radioType, CellObservationEvidence::identifierToken))
			val signature = observations.joinToString("|") {
				"${it.identifierToken}:${it.radioType}:${it.registered}:${it.signalLevelDbm}"
			}
			if (!ConnectivitySnapshotGate.shouldAdmitCell(
					signature,
					providerTime,
					lastSignatureBySubscription[snapshot.subscriptionId],
					lastProviderTimeBySubscription[snapshot.subscriptionId],
				)
			) return
			lastSignatureBySubscription[snapshot.subscriptionId] = signature
			lastProviderTimeBySubscription[snapshot.subscriptionId] = providerTime
			admitSnapshot(activeRegistration, sink, snapshot.subscriptionId, observations, outcome, providerTime, nowNanos)
		}

		for (input in inputs) {
			when (input) {
				is CellRuntimeInput.Started -> {
					backend.readCached().forEach { recordSnapshot(it, CellRefreshOutcome.CACHED) }
					scheduleRefresh()
				}
				is CellRuntimeInput.Snapshot -> {
					recordSnapshot(input.snapshot, input.outcome)
					if (awaitingRefresh) {
						awaitingRefresh = false
						wakeups.cancel(TIMEOUT_WAKEUP_ID)
						backoff = backoff.succeeded()
					}
					scheduleRefresh()
				}
				is CellRuntimeInput.Refresh -> {
					lastRefreshAtMs = SystemClock.elapsedRealtime()
					when (backend.requestRefresh(::onBackendSnapshot)) {
						CellRefreshRequestOutcome.REQUESTED -> {
							awaitingRefresh = true
							admitOutcome(activeRegistration, sink, CellRefreshOutcome.REFRESH_REQUESTED)
							val timeoutAt = SystemClock.elapsedRealtime() + REFRESH_TIMEOUT_MS
							wakeups.schedule(
								sourceWakeupRequest(TIMEOUT_WAKEUP_ID, source, timeoutAt, 0L),
							) { nextCallbackSequence()?.let { enqueue(CellRuntimeInput.Timeout(it)) } }
						}
						CellRefreshRequestOutcome.NOT_SUPPORTED -> {
							backend.readCached().forEach { recordSnapshot(it, CellRefreshOutcome.CACHED) }
							backoff = backoff.succeeded()
						}
						CellRefreshRequestOutcome.PERMISSION_BLOCKED -> {
							backoff = backoff.failed()
							admitOutcome(activeRegistration, sink, CellRefreshOutcome.PERMISSION_BLOCKED)
						}
						CellRefreshRequestOutcome.PROVIDER_FAILED -> {
							backoff = backoff.failed()
							admitOutcome(activeRegistration, sink, CellRefreshOutcome.PROVIDER_FAILED)
						}
					}
					scheduleRefresh()
				}
				is CellRuntimeInput.Timeout -> if (awaitingRefresh) {
					awaitingRefresh = false
					backoff = backoff.failed()
					admitOutcome(activeRegistration, sink, CellRefreshOutcome.REFRESH_TIMEOUT)
					scheduleRefresh()
				}
			}
		}
	}

	private suspend fun admitOutcome(
		registration: SourceRegistration,
		sink: SourceEventSink,
		outcome: CellRefreshOutcome,
	) = admitSnapshot(
		registration, sink, null, emptyList(), outcome, null, SystemClock.elapsedRealtimeNanos(),
	)

	private suspend fun admitSnapshot(
		registration: SourceRegistration,
		sink: SourceEventSink,
		subscriptionId: Int?,
		observations: List<CellObservationEvidence>,
		outcome: CellRefreshOutcome,
		providerTimeNanos: Long?,
		receivedNanos: Long,
	) {
		val observed = providerTimeNanos ?: receivedNanos
		if (cutoffElapsedNanos?.let { observed > it } == true) return
		val sourceSequence = runCatching {
			registrations.allocateSequence(registration, System.currentTimeMillis())
		}.getOrElse { metrics.recordFailure(callbackSequence); return }
		val delayMs = (receivedNanos - observed).coerceAtLeast(0L) / NANOS_PER_MILLISECOND
		val wallTime = (System.currentTimeMillis() - delayMs).coerceAtLeast(0L)
		val flags = buildSet {
			if (outcome == CellRefreshOutcome.CACHED) add(SourceQualityFlag.CACHED)
			if (outcome == CellRefreshOutcome.THROTTLED) add(SourceQualityFlag.THROTTLED)
			if (outcome == CellRefreshOutcome.PERMISSION_BLOCKED) add(SourceQualityFlag.PERMISSION_DEGRADED)
			if (outcome in setOf(CellRefreshOutcome.RADIO_UNAVAILABLE, CellRefreshOutcome.PROVIDER_FAILED)) {
				add(SourceQualityFlag.PROVIDER_DEGRADED)
			}
			if (providerTimeNanos == null) add(SourceQualityFlag.CLOCK_UNCERTAIN)
		}
		val fingerprint = observations.joinToString("|") {
			"${it.identifierToken}:${it.radioType}:${it.registered}:${it.signalLevelDbm}:${it.providerTimestampNanos}"
		}
		val candidate = SourceEvidenceCandidate(
			providerDedupKey = "${registration.state.sourceInstanceId}:${registration.state.registrationGeneration}:" +
				"$subscriptionId:${outcome.name}:$observed:$fingerprint",
			logicalTrackingId = null, serviceRunId = null, source = source,
			sourceInstanceId = SourceInstanceId(registration.state.sourceInstanceId),
			registrationGeneration = registration.state.registrationGeneration,
			sourceSequence = sourceSequence, configRevision = registration.state.appliedRevision,
			planAttribution = PlanAttribution.CAPTURED_REGISTRATION,
			clockDomainId = registration.state.clockDomainId,
			observedElapsedRealtimeNanos = observed.coerceAtLeast(0L),
			receivedElapsedRealtimeNanos = receivedNanos.coerceAtLeast(0L),
			wallTimeMs = wallTime, wallTimeUncertaintyMs = if (providerTimeNanos == null) delayMs else 1L,
			capturedCollectedDataEpoch = registration.state.collectedDataEpoch,
			acquiredAtMs = wallTime, quality = SourceQuality(flags = flags), payloadVersion = 1,
			payload = CellSnapshotPayload(subscriptionId, observations, outcome),
		)
		when (val handoff = sink.admitWithBoundedRetry(candidate)) {
			is SourceAdmissionHandoff.Durable -> metrics.recordDurable(sourceSequence, handoff.admissionOrdinal)
			is SourceAdmissionHandoff.Duplicate -> metrics.recordDurable(sourceSequence, handoff.existingAdmissionOrdinal)
			is SourceAdmissionHandoff.RetryableFailure,
			is SourceAdmissionHandoff.TerminalFailure -> metrics.recordFailure(sourceSequence)
		}
	}

	private fun capabilitiesNow(): SourceCapabilities {
		val state = deviceStateProvider.cell()
		val available = state.radioFeatureAvailable && state.fineLocationPermission && state.readPhoneStatePermission
		val reasons = buildSet {
			if (!state.radioFeatureAvailable) add(SourceDegradedReason.HARDWARE_UNAVAILABLE)
			if (!state.fineLocationPermission || !state.readPhoneStatePermission) {
				add(SourceDegradedReason.PERMISSION_MISSING)
			}
		}
		return SourceCapabilities(
			available, false, false, null,
			if (state.refreshApiAvailable) MINIMUM_SPARSE_REFRESH_MS else null,
			reasons,
		)
	}

	private fun clearActiveState() {
		synchronized(callbackLock) { accepting = false; queue = null }
		actor = null
		cutoffElapsedNanos = null
		currentPlan = null
	}

	private fun unavailableAck(cutoff: SessionCutoff?) = SourceStopAck(
		source, SourceInstanceId("unavailable-cell"), 0L, currentPlan?.revision,
		callbackSequence, null, null, 0L, null, null,
		RegistrationRemovalOutcome.NOT_REGISTERED, ProviderFlushOutcome.NOT_REQUESTED,
		ProviderCoverage.PROVIDER_COMPLETENESS_UNOBSERVABLE, true,
		if (cutoff == null) SourceStopStatus.COMPLETE else SourceStopStatus.PROVIDER_FAILED,
	)

	private sealed interface CellRuntimeInput {
		val callbackSequence: Long
		data class Started(override val callbackSequence: Long) : CellRuntimeInput
		data class Refresh(override val callbackSequence: Long) : CellRuntimeInput
		data class Timeout(override val callbackSequence: Long) : CellRuntimeInput
		data class Snapshot(
			val snapshot: CellBackendSnapshot,
			val outcome: CellRefreshOutcome,
			override val callbackSequence: Long,
		) : CellRuntimeInput
	}

	private companion object {
		const val REFRESH_WAKEUP_ID = "cell-sparse-refresh"
		const val TIMEOUT_WAKEUP_ID = "cell-refresh-timeout"
		const val CALLBACK_BUFFER_CAPACITY = 64
		const val DEFAULT_DRAIN_TIMEOUT_MS = 3_000L
		const val NANOS_PER_MILLISECOND = 1_000_000L
		const val MAX_COALESCE_WINDOW_MS = 5_000L
		const val REFRESH_TIMEOUT_MS = 20_000L
		const val MINIMUM_SPARSE_REFRESH_MS = 60_000L
	}
}
