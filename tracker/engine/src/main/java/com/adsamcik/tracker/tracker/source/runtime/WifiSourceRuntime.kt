package com.adsamcik.tracker.tracker.source.runtime

import android.os.SystemClock
import com.adsamcik.tracker.shared.base.di.ApplicationScope
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.SourceApplyStatus
import com.adsamcik.tracker.tracker.source.model.SourceDegradedReason
import com.adsamcik.tracker.tracker.source.model.SourceEvidenceCandidate
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourceQuality
import com.adsamcik.tracker.tracker.source.model.SourceQualityFlag
import com.adsamcik.tracker.tracker.source.model.WifiAccessPointEvidence
import com.adsamcik.tracker.tracker.source.model.WifiMode
import com.adsamcik.tracker.tracker.source.model.WifiPlan
import com.adsamcik.tracker.tracker.source.model.WifiResultSnapshotPayload
import com.adsamcik.tracker.tracker.source.model.WifiScanAttemptOutcome
import com.adsamcik.tracker.tracker.source.model.WifiScanAttemptPayload
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
) : SourceRuntime<WifiPlan> {
	override val source = SourceKind.WIFI
	private val _capabilities = MutableStateFlow(capabilitiesNow())
	override val capabilities: StateFlow<SourceCapabilities> = _capabilities
	private val lifecycleMutex = Mutex()
	private val callbackLock = Any()
	private var registration: SourceRegistration? = null
	private var currentPlan: WifiPlan? = null
	private var currentSink: SourceEventSink? = null
	private var queue: Channel<WifiRuntimeInput>? = null
	private var actor: Job? = null
	private var accepting = false
	private var callbackSequence = 0L
	private var cutoffElapsedNanos: Long? = null
	private var metrics = RuntimeAdmissionMetrics()

	override suspend fun start(plan: WifiPlan, sink: SourceEventSink): SourceStartResult = lifecycleMutex.withLock {
		require(currentPlan == null) { "Wi-Fi source is already started" }
		startLocked(plan, sink)
	}

	override suspend fun reconfigure(plan: WifiPlan): SourceApplyResult = lifecycleMutex.withLock {
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

	private suspend fun startLocked(plan: WifiPlan, sink: SourceEventSink): SourceStartResult {
		if (!plan.enabled) return SourceStartResult.Started(
			appliedState(source, plan.revision, null, SourceApplyStatus.APPLIED, SystemClock.elapsedRealtimeNanos()),
		)
		val application = WifiPrerequisiteEvaluator.evaluate(plan, deviceStateProvider.wifi())
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
		val nextQueue = Channel<WifiRuntimeInput>(CALLBACK_BUFFER_CAPACITY)
		registration = nextRegistration
		currentPlan = plan
		currentSink = sink
		queue = nextQueue
		callbackSequence = 0L
		cutoffElapsedNanos = null
		metrics = RuntimeAdmissionMetrics()
		accepting = true
		actor = applicationScope.launch { consume(nextQueue, nextRegistration, sink) }
		val started = backend.start(::onBackendEvent)
		if (!started) {
			shutdownLocked(null)
			return SourceStartResult.Failed(
				appliedState(source, plan.revision, nextRegistration, SourceApplyStatus.FAILED, SystemClock.elapsedRealtimeNanos()), true,
			)
		}
		nextCallbackSequence()?.let { enqueue(WifiRuntimeInput.Started(it)) }
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
		wakeups.cancel(WAKEUP_ID)
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

	private fun onBackendEvent(event: WifiBackendEvent) {
		val sequence = nextCallbackSequence() ?: return
		enqueue(WifiRuntimeInput.Backend(event, sequence))
	}

	private fun nextCallbackSequence(): Long? = synchronized(callbackLock) {
		if (!accepting) null else ++callbackSequence
	}

	private fun enqueue(input: WifiRuntimeInput) {
		val accepted = synchronized(callbackLock) {
			accepting && queue?.trySend(input)?.isSuccess == true
		}
		if (!accepted) metrics.recordFailure(input.callbackSequence)
	}

	private suspend fun consume(
		inputs: Channel<WifiRuntimeInput>,
		activeRegistration: SourceRegistration,
		sink: SourceEventSink,
	) {
		var pendingAttemptId: String? = null
		var lastAttemptAtMs = Long.MIN_VALUE
		var backoff = RuntimeBackoffState()
		var lastFingerprint: String? = null
		var lastSnapshotAtNanos = Long.MIN_VALUE
		var deferredRecorded = false

		suspend fun scheduleAttempt() {
			val plan = currentPlan ?: return
			if (plan.mode != WifiMode.ACTIVE_ATTEMPTS) return
			val application = WifiPrerequisiteEvaluator.evaluate(plan, deviceStateProvider.wifi())
			if (application.activeAttemptsDeferred) {
				wakeups.cancel(WAKEUP_ID)
				if (!deferredRecorded) {
					deferredRecorded = true
					admitAttempt(activeRegistration, sink, "idle", WifiScanAttemptOutcome.DEFERRED_IDLE, null, null)
				}
				return
			}
			deferredRecorded = false
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
				val callback = nextCallbackSequence() ?: return@schedule
				enqueue(WifiRuntimeInput.Attempt(callback))
			}
		}

		for (input in inputs) {
			when (input) {
				is WifiRuntimeInput.Started -> {
					if (currentPlan?.mode != WifiMode.OFF) {
						backend.readSnapshot()?.let { snapshot ->
							val admitted = admitSnapshot(
								snapshot, activeRegistration, sink, true, null,
								lastFingerprint, lastSnapshotAtNanos,
							)
							if (admitted != null) {
								lastFingerprint = admitted.first
								lastSnapshotAtNanos = admitted.second
							}
						}
					}
					scheduleAttempt()
				}
				is WifiRuntimeInput.Attempt -> {
					val plan = currentPlan ?: continue
					val attemptId = UUID.randomUUID().toString()
					val now = SystemClock.elapsedRealtime()
					lastAttemptAtMs = now
					admitAttempt(activeRegistration, sink, attemptId, WifiScanAttemptOutcome.REQUESTED, null, null)
					when (backend.requestScan()) {
						WifiRequestOutcome.ACCEPTED -> {
							pendingAttemptId = attemptId
							backoff = backoff.succeeded()
							admitAttempt(activeRegistration, sink, attemptId, WifiScanAttemptOutcome.ACCEPTED, null, null)
						}
						WifiRequestOutcome.THROTTLED -> {
							backoff = backoff.failed()
							admitAttempt(activeRegistration, sink, attemptId, WifiScanAttemptOutcome.THROTTLED, null, null)
						}
						WifiRequestOutcome.PERMISSION_BLOCKED -> {
							backoff = backoff.failed()
							admitAttempt(activeRegistration, sink, attemptId, WifiScanAttemptOutcome.PERMISSION_BLOCKED, null, null)
						}
						WifiRequestOutcome.PROVIDER_FAILED -> {
							backoff = backoff.failed()
							admitAttempt(activeRegistration, sink, attemptId, WifiScanAttemptOutcome.PROVIDER_FAILED, null, null)
						}
					}
					scheduleAttempt()
				}
				is WifiRuntimeInput.Backend -> when (val event = input.event) {
					WifiBackendEvent.IdleStateChanged -> scheduleAttempt()
					is WifiBackendEvent.Results -> {
						val linkedAttempt = pendingAttemptId
						val age = event.snapshot?.freshestTimestampNanos?.let {
							((event.receivedElapsedRealtimeNanos - it).coerceAtLeast(0L) / NANOS_PER_MILLISECOND)
						}
						admitAttempt(
							activeRegistration, sink,
							linkedAttempt ?: "provider:${event.receivedElapsedRealtimeNanos}",
							WifiScanAttemptOutcome.RESULTS_AVAILABLE,
							event.snapshot?.accessPoints?.size, age,
							linked = linkedAttempt != null,
						)
						if (event.resultsUpdated != false && event.snapshot != null) {
							val admitted = admitSnapshot(
								event.snapshot, activeRegistration, sink, false, linkedAttempt,
								lastFingerprint, lastSnapshotAtNanos,
								event.receivedElapsedRealtimeNanos, event.receivedWallTimeMs,
							)
							if (admitted != null) {
								lastFingerprint = admitted.first
								lastSnapshotAtNanos = admitted.second
							}
						}
						pendingAttemptId = null
					}
				}
			}
		}
	}

	private suspend fun admitAttempt(
		registration: SourceRegistration,
		sink: SourceEventSink,
		attemptId: String,
		outcome: WifiScanAttemptOutcome,
		resultCount: Int?,
		resultAgeMs: Long?,
		linked: Boolean = true,
	) {
		val nowElapsed = SystemClock.elapsedRealtimeNanos()
		val flags = buildSet {
			if (outcome == WifiScanAttemptOutcome.THROTTLED) add(SourceQualityFlag.THROTTLED)
			if (outcome == WifiScanAttemptOutcome.PERMISSION_BLOCKED) add(SourceQualityFlag.PERMISSION_DEGRADED)
			if (outcome in setOf(WifiScanAttemptOutcome.PROVIDER_FAILED, WifiScanAttemptOutcome.LOCATION_SERVICES_DISABLED)) {
				add(SourceQualityFlag.PROVIDER_DEGRADED)
			}
		}
		admit(
			registration, sink, nowElapsed, nowElapsed, System.currentTimeMillis(),
			if (linked) registration.state.appliedRevision else null,
			if (linked) PlanAttribution.LINKED_ATTEMPT else PlanAttribution.RECEIVE_TIME_ONLY,
			SourceQuality(flags = flags),
			WifiScanAttemptPayload(attemptId, outcome, resultCount, resultAgeMs),
			"attempt:$attemptId:${outcome.name}",
		)
	}

	private suspend fun admitSnapshot(
		snapshot: WifiBackendSnapshot,
		registration: SourceRegistration,
		sink: SourceEventSink,
		cached: Boolean,
		linkedAttemptId: String?,
		lastFingerprint: String?,
		lastSnapshotAtNanos: Long,
		receivedElapsedNanos: Long = SystemClock.elapsedRealtimeNanos(),
		receivedWallTimeMs: Long = System.currentTimeMillis(),
	): Pair<String, Long>? {
		val plan = currentPlan ?: return null
		val observed = snapshot.freshestTimestampNanos ?: receivedElapsedNanos
		val ageMs = ((receivedElapsedNanos - observed).coerceAtLeast(0L) / NANOS_PER_MILLISECOND)
		if (ageMs > plan.maximumAcceptableResultAgeMs) return null
		val evidence = snapshot.accessPoints.map { accessPoint ->
			WifiAccessPointEvidence(
				stableIdentifierToken("wifi-bssid", accessPoint.bssid),
				accessPoint.frequencyMhz,
				accessPoint.signalLevelDbm,
			)
		}.sortedWith(compareBy(WifiAccessPointEvidence::identifierToken, WifiAccessPointEvidence::frequencyMhz))
		val fingerprint = evidence.joinToString("|") {
			"${it.identifierToken}:${it.frequencyMhz}:${it.signalLevelDbm}"
		}
		if (!ConnectivitySnapshotGate.shouldAdmitWifi(
				fingerprint,
				lastFingerprint,
				receivedElapsedNanos,
				lastSnapshotAtNanos,
				plan.unchangedResultDedupeWindowMs,
			)
		) return null
		val attribution = if (linkedAttemptId != null) PlanAttribution.LINKED_ATTEMPT else PlanAttribution.RECEIVE_TIME_ONLY
		val wallTime = receivedWallTimeMs - ageMs
		admit(
			registration, sink, observed, receivedElapsedNanos, wallTime,
			if (linkedAttemptId != null) registration.state.appliedRevision else null,
			attribution,
			SourceQuality(flags = buildSet {
				if (cached) add(SourceQualityFlag.CACHED)
				if (snapshot.freshestTimestampNanos == null) add(SourceQualityFlag.CLOCK_UNCERTAIN)
			}),
			WifiResultSnapshotPayload(evidence, observed / NANOS_PER_MILLISECOND, ageMs),
			"snapshot:$observed:$fingerprint",
		)
		return fingerprint to receivedElapsedNanos
	}

	private suspend fun admit(
		registration: SourceRegistration,
		sink: SourceEventSink,
		observedNanos: Long,
		receivedNanos: Long,
		wallTimeMs: Long,
		configRevision: Long?,
		attribution: PlanAttribution,
		quality: SourceQuality,
		payload: com.adsamcik.tracker.tracker.source.model.SourcePayload,
		dedupKey: String,
	) {
		if (cutoffElapsedNanos?.let { observedNanos > it } == true) return
		val sourceSequence = runCatching {
			registrations.allocateSequence(registration, System.currentTimeMillis())
		}.getOrElse { metrics.recordFailure(callbackSequence); return }
		val candidate = SourceEvidenceCandidate(
			providerDedupKey = "${registration.state.sourceInstanceId}:${registration.state.registrationGeneration}:$dedupKey",
			logicalTrackingId = null, serviceRunId = null, source = source,
			sourceInstanceId = SourceInstanceId(registration.state.sourceInstanceId),
			registrationGeneration = registration.state.registrationGeneration,
			sourceSequence = sourceSequence, configRevision = configRevision,
			planAttribution = attribution, clockDomainId = registration.state.clockDomainId,
			observedElapsedRealtimeNanos = observedNanos.coerceAtLeast(0L),
			receivedElapsedRealtimeNanos = receivedNanos.coerceAtLeast(0L),
			wallTimeMs = wallTimeMs.coerceAtLeast(0L), wallTimeUncertaintyMs = 1L,
			capturedCollectedDataEpoch = registration.state.collectedDataEpoch,
			acquiredAtMs = wallTimeMs.coerceAtLeast(0L), quality = quality,
			payloadVersion = 1, payload = payload,
		)
		when (val handoff = sink.admitWithBoundedRetry(candidate)) {
			is SourceAdmissionHandoff.Durable -> metrics.recordDurable(sourceSequence, handoff.admissionOrdinal)
			is SourceAdmissionHandoff.Duplicate -> metrics.recordDurable(sourceSequence, handoff.existingAdmissionOrdinal)
			is SourceAdmissionHandoff.RetryableFailure,
			is SourceAdmissionHandoff.TerminalFailure -> metrics.recordFailure(sourceSequence)
		}
	}

	private fun capabilitiesNow(): SourceCapabilities {
		val state = deviceStateProvider.wifi()
		val available = state.wifiFeatureAvailable && state.fineLocationPermission &&
			(state.apiLevel < 33 || state.nearbyWifiPermission) && state.locationServicesEnabled
		val reasons = buildSet {
			if (!state.wifiFeatureAvailable) add(SourceDegradedReason.HARDWARE_UNAVAILABLE)
			if (!state.fineLocationPermission || (state.apiLevel >= 33 && !state.nearbyWifiPermission)) {
				add(SourceDegradedReason.PERMISSION_MISSING)
			}
			if (!state.locationServicesEnabled) add(SourceDegradedReason.PROVIDER_UNAVAILABLE)
		}
		return SourceCapabilities(
			available, false, false, null, MINIMUM_PLATFORM_SCAN_INTERVAL_MS,
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
		source, SourceInstanceId("unavailable-wifi"), 0L, currentPlan?.revision,
		callbackSequence, null, null, 0L, null, null,
		RegistrationRemovalOutcome.NOT_REGISTERED, ProviderFlushOutcome.NOT_REQUESTED,
		ProviderCoverage.PROVIDER_COMPLETENESS_UNOBSERVABLE, true,
		if (cutoff == null) SourceStopStatus.COMPLETE else SourceStopStatus.PROVIDER_FAILED,
	)

	private fun attemptWindow(intervalMs: Long) = (intervalMs / 4L).coerceIn(0L, MAX_COALESCE_WINDOW_MS)

	private sealed interface WifiRuntimeInput {
		val callbackSequence: Long
		data class Started(override val callbackSequence: Long) : WifiRuntimeInput
		data class Attempt(override val callbackSequence: Long) : WifiRuntimeInput
		data class Backend(val event: WifiBackendEvent, override val callbackSequence: Long) : WifiRuntimeInput
	}

	private companion object {
		const val WAKEUP_ID = "wifi-active-attempt"
		const val CALLBACK_BUFFER_CAPACITY = 64
		const val DEFAULT_DRAIN_TIMEOUT_MS = 3_000L
		const val NANOS_PER_MILLISECOND = 1_000_000L
		const val MAX_COALESCE_WINDOW_MS = 5_000L
		const val MINIMUM_PLATFORM_SCAN_INTERVAL_MS = 30_000L
	}
}
