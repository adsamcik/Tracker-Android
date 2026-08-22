package com.adsamcik.tracker.tracker.source.runtime

import android.os.SystemClock
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.di.ApplicationScope
import com.adsamcik.tracker.tracker.source.runCatchingNonCancellation
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.SourceApplyStatus
import com.adsamcik.tracker.tracker.source.model.SourceDegradedReason
import com.adsamcik.tracker.tracker.source.model.SourceEvidenceCandidate
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourceQuality
import com.adsamcik.tracker.tracker.source.model.WifiAccessPointEvidence
import com.adsamcik.tracker.tracker.source.model.WifiMode
import com.adsamcik.tracker.tracker.source.model.WifiPlan
import com.adsamcik.tracker.tracker.source.model.physicalConfigurationFingerprint
import com.adsamcik.tracker.tracker.source.model.WifiResultSnapshotPayload
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

	override suspend fun reconfigure(plan: WifiPlan, sink: SourceEventSink): SourceApplyResult = lifecycleMutex.withLock {
		if (currentPlan != null) shutdownLocked(null)
		if (!plan.enabled) return@withLock SourceApplyResult.Applied(
			appliedState(source, plan.revision, null, SourceApplyStatus.APPLIED, SystemClock.elapsedRealtimeNanos()),
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
		val nextRegistration = runCatchingNonCancellation {
			registrations.begin(source, plan.revision, plan.physicalConfigurationFingerprint(), System.currentTimeMillis())
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
		val started = backend.start { event -> onBackendEvent(nextRegistration, event) }
		if (!started) {
			registrations.markFailed(nextRegistration, "PROVIDER_REGISTRATION_FAILED", System.currentTimeMillis())
			synchronized(callbackLock) { accepting = false; nextQueue.close() }
			clearActiveState()
			return SourceStartResult.Failed(
				appliedState(source, plan.revision, nextRegistration, SourceApplyStatus.FAILED, SystemClock.elapsedRealtimeNanos()), true,
			)
		}
		val accepted = runCatchingNonCancellation {
			registrations.markAccepted(nextRegistration, System.currentTimeMillis())
		}.isSuccess
		if (!accepted) {
			runCatching { backend.stop() }
			registrations.markFailed(nextRegistration, "REGISTRATION_ACCEPTANCE_STALE", System.currentTimeMillis())
			synchronized(callbackLock) { accepting = false; nextQueue.close() }
			clearActiveState()
			return SourceStartResult.Failed(
				appliedState(source, plan.revision, nextRegistration, SourceApplyStatus.FAILED, SystemClock.elapsedRealtimeNanos()), true,
			)
		}
		actor = applicationScope.launch { consume(nextQueue, nextRegistration, sink) }
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
		if (!drained) activeActor.cancelAndJoin()
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
		if (removed == RegistrationRemovalOutcome.FAILED) {
			registrations.markFailed(activeRegistration, "PROVIDER_REMOVAL_FAILED", System.currentTimeMillis())
		} else {
			registrations.markRetired(activeRegistration, System.currentTimeMillis())
		}
		clearActiveState()
		return ack
	}

	private fun onBackendEvent(callbackRegistration: SourceRegistration, event: WifiBackendEvent) {
		val sequence = nextCallbackSequence(callbackRegistration) ?: return
		enqueue(WifiRuntimeInput.Backend(event, sequence))
	}

	private fun nextCallbackSequence(callbackRegistration: SourceRegistration? = registration): Long? =
		synchronized(callbackLock) {
		if (!accepting || registration !== callbackRegistration) null else ++callbackSequence
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
		val replayGate = BoundedReplayIdentityGate()
		val acquisitionBudget = DirectAcquisitionBudget(
			maximumAttempts = MAX_ACTIVE_ATTEMPTS_PER_REGISTRATION,
			directCaptureRequested = activeRegistration.purposeEligibilityMask and
				SourceBrokerPurpose.MASK_SESSION_CAPTURE != 0L,
		)

		suspend fun scheduleAttempt() {
			val plan = currentPlan ?: return
			if (plan.mode != WifiMode.ACTIVE_ATTEMPTS || !acquisitionBudget.canRequest) {
				wakeups.cancel(WAKEUP_ID)
				return
			}
			val application = WifiPrerequisiteEvaluator.evaluate(plan, deviceStateProvider.wifi())
			if (application.activeAttemptsDeferred) {
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
				val callback = nextCallbackSequence() ?: return@schedule
				enqueue(WifiRuntimeInput.Attempt(callback))
			}
		}

		for (input in inputs) {
			when (input) {
				is WifiRuntimeInput.Started -> {
					scheduleAttempt()
				}
				is WifiRuntimeInput.Attempt -> {
					if (currentPlan == null || !acquisitionBudget.consumeRequest()) continue
					val attemptId = UUID.randomUUID().toString()
					val now = SystemClock.elapsedRealtime()
					lastAttemptAtMs = now
					when (backend.requestScan()) {
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
				is WifiRuntimeInput.Backend -> when (val event = input.event) {
					WifiBackendEvent.IdleStateChanged -> scheduleAttempt()
					is WifiBackendEvent.Results -> {
						val linkedAttempt = pendingAttemptId
						if (event.resultsUpdated != false && event.snapshot != null) {
							val admitted = admitSnapshot(
								event.snapshot, activeRegistration, sink, linkedAttempt,
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
		}
	}

	private suspend fun admitSnapshot(
		snapshot: WifiBackendSnapshot,
		registration: SourceRegistration,
		sink: SourceEventSink,
		linkedAttemptId: String?,
		replayGate: BoundedReplayIdentityGate,
		confirmedFreshEmptyCallback: Boolean,
		receivedElapsedNanos: Long = SystemClock.elapsedRealtimeNanos(),
		receivedWallTimeMs: Long = System.currentTimeMillis(),
	): String? {
		val plan = currentPlan ?: return null
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
		val ageMs = ((receivedElapsedNanos - observed).coerceAtLeast(0L) / NANOS_PER_MILLISECOND)
		val snapshotIdentity = if (emptyCoverage) {
			"coverage-empty:$receivedElapsedNanos"
		} else normalized.joinToString("|") { item ->
			"${item.frequencyMhz}:${item.signalLevelDbm}:${item.providerTimestampNanos}"
		}
		if (!replayGate.shouldAdmit(snapshotIdentity)) return null
		val attribution = when {
			linkedAttemptId != null -> PlanAttribution.LINKED_ATTEMPT
			emptyCoverage -> PlanAttribution.CAPTURED_REGISTRATION
			else -> PlanAttribution.RECEIVE_TIME_ONLY
		}
		val wallTime = receivedWallTimeMs - ageMs
		admit(
			registration, sink, observed, receivedElapsedNanos, wallTime,
			if (linkedAttemptId != null) registration.state.appliedRevision else null,
			attribution,
			SourceQuality(),
			WifiResultSnapshotPayload(
				accessPoints = normalized,
				platformTimestampMs = if (emptyCoverage) null else observed / NANOS_PER_MILLISECOND,
				resultAgeMs = ageMs,
			),
			"snapshot:$snapshotIdentity",
		).takeIf { it } ?: return null
		return snapshotIdentity
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
	): Boolean {
		if (cutoffElapsedNanos?.let { observedNanos > it } == true) return false
		val sourceSequence = runCatchingNonCancellation {
			registrations.allocateSequence(registration, System.currentTimeMillis())
		}.getOrElse { metrics.recordFailure(callbackSequence); return false }
		val candidate = SourceEvidenceCandidate(
			providerDedupKey = "${registration.state.sourceInstanceId}:${registration.state.registrationGeneration}:$dedupKey",
			logicalTrackingId = null, serviceRunId = null, source = source,
			sourceInstanceId = SourceInstanceId(registration.state.sourceInstanceId),
			registrationGeneration = registration.state.registrationGeneration,
			physicalConfigurationFingerprint = registration.physicalConfigurationFingerprint,
			authorizationRevision = registration.authorization.authorizationRevision,
			registrationPurposeEligibilityMask = registration.purposeEligibilityMask,
			registrationEligibilityFingerprint = registration.eligibilityFingerprint,
			sourceSequence = sourceSequence, configRevision = configRevision,
			planAttribution = attribution, clockDomainId = registration.state.clockDomainId,
			observedElapsedRealtimeNanos = observedNanos.coerceAtLeast(0L),
			receivedElapsedRealtimeNanos = receivedNanos.coerceAtLeast(0L),
			wallTimeMs = wallTimeMs.coerceAtLeast(0L), wallTimeUncertaintyMs = 1L,
			capturedCollectedDataEpoch = registration.state.collectedDataEpoch,
			acquiredAtMs = wallTimeMs.coerceAtLeast(0L), quality = quality,
			payloadVersion = WIFI_PAYLOAD_VERSION, payload = payload,
		)
		when (val handoff = sink.admitWithBoundedRetry(candidate)) {
			is SourceAdmissionHandoff.Durable -> {
				metrics.recordDurable(sourceSequence, handoff.admissionOrdinal)
				return true
			}
			is SourceAdmissionHandoff.Duplicate -> {
				metrics.recordDurable(sourceSequence, handoff.existingAdmissionOrdinal)
				return true
			}
			is SourceAdmissionHandoff.RetryableFailure,
			is SourceAdmissionHandoff.TerminalFailure -> {
				metrics.recordFailure(sourceSequence)
				return false
			}
		}
	}

	private fun capabilitiesNow(): SourceCapabilities {
		val state = deviceStateProvider.wifi()
		val available = state.wifiFeatureAvailable && state.fineLocationPermission &&
			state.locationServicesEnabled
		val reasons = buildSet {
			if (!state.wifiFeatureAvailable) add(SourceDegradedReason.HARDWARE_UNAVAILABLE)
			if (!state.fineLocationPermission) add(SourceDegradedReason.PERMISSION_MISSING)
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
		const val WIFI_PAYLOAD_VERSION = 2
		const val WAKEUP_ID = "wifi-active-attempt"
		const val CALLBACK_BUFFER_CAPACITY = 64
		const val DEFAULT_DRAIN_TIMEOUT_MS = 3_000L
		const val NANOS_PER_MILLISECOND = 1_000_000L
		const val MAX_COALESCE_WINDOW_MS = 5_000L
		const val MINIMUM_PLATFORM_SCAN_INTERVAL_MS = 30_000L
		const val MAX_ACTIVE_ATTEMPTS_PER_REGISTRATION = 1
	}
}

internal fun WifiBackendAccessPoint.toMinimizedEvidence() = WifiAccessPointEvidence(
	identifierToken = WITHHELD_RADIO_IDENTIFIER_TOKEN,
	frequencyMhz = frequencyMhz,
	signalLevelDbm = signalLevelDbm,
	providerTimestampNanos = requireNotNull(providerTimestampNanos),
)
