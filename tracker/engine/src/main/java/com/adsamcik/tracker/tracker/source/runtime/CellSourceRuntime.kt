package com.adsamcik.tracker.tracker.source.runtime

import android.os.SystemClock
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.di.ApplicationScope
import com.adsamcik.tracker.tracker.source.runCatchingNonCancellation
import com.adsamcik.tracker.tracker.source.model.CellMode
import com.adsamcik.tracker.tracker.source.model.CellObservationEvidence
import com.adsamcik.tracker.tracker.source.model.CellPlan
import com.adsamcik.tracker.tracker.source.model.physicalConfigurationFingerprint
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
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
	private var callbackToken: CellCallbackToken? = null
	private var currentPlan: CellPlan? = null
	private var currentSink: SourceEventSink? = null
	private var queue: CellCallbackLane<CellRuntimeInput>? = null
	private var actor: Job? = null
	private var accepting = false
	private var callbackSequence = 0L
	private var cutoffElapsedNanos: Long? = null
	private var metrics = RuntimeAdmissionMetrics()
	private val processedCallbackSequence = MutableStateFlow(0L)
	private val prerequisiteGate = CellPrerequisiteGate(deviceStateProvider::cell)

	override suspend fun start(plan: CellPlan, sink: SourceEventSink): SourceStartResult = lifecycleMutex.withLock {
		require(currentPlan == null) { "Cell source is already started" }
		startLocked(plan, sink)
	}

	override suspend fun reconfigure(plan: CellPlan, sink: SourceEventSink): SourceApplyResult = lifecycleMutex.withLock {
		refreshCompatibleLocked(plan, sink)?.let { refreshed ->
			return@withLock refreshed
		}
		if (currentPlan != null) {
			val previous = shutdownLocked(null)
			if (!previous.appDrainComplete) {
				return@withLock SourceApplyResult.Failed(
					appliedState(source, plan.revision, null, SourceApplyStatus.FAILED,
						SystemClock.elapsedRealtimeNanos()),
					retryable = true,
				)
			}
		}
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
		callbackToken = null
		currentSink = null
	}

	/**
	 * Refreshes policy and immutable authorization attribution without replacing the compatible
	 * Telephony registration. Inputs already in the lane retain the context captured at callback
	 * entry; only later callbacks observe this revision.
	 */
	private suspend fun refreshCompatibleLocked(
		plan: CellPlan,
		sink: SourceEventSink,
	): SourceApplyResult? {
		val activePlan = currentPlan ?: return null
		val activeRegistration = registration ?: return null
		if (!cellPlansSharePhysicalRegistration(activePlan, plan)) return null
		val application = CellPrerequisiteEvaluator.evaluate(plan, deviceStateProvider.cell())
		_capabilities.value = capabilitiesNow()
		if (application.status == SourceApplyStatus.BLOCKED) return null
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
		if (refreshed.state.sourceInstanceId != activeRegistration.state.sourceInstanceId ||
			refreshed.state.registrationGeneration != activeRegistration.state.registrationGeneration ||
			refreshed.physicalConfigurationFingerprint != activeRegistration.physicalConfigurationFingerprint ||
			refreshed.requiresProviderAcceptance
		) return null
		val retained = synchronized(callbackLock) {
			if (!accepting || callbackToken == null) false else {
				registration = refreshed
				currentPlan = plan
				currentSink = sink
				queue?.offer(CellRuntimeInput.PlanChanged) == true
			}
		}
		if (!retained) return null
		val state = appliedState(
			source, plan.revision, refreshed, application.status, SystemClock.elapsedRealtimeNanos(),
		).copy(degradedReasons = application.reasons)
		return if (application.status == SourceApplyStatus.DEGRADED) {
			SourceApplyResult.Degraded(state)
		} else SourceApplyResult.Applied(state)
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
		val nextRegistration = runCatchingNonCancellation {
			registrations.begin(source, plan.revision, plan.physicalConfigurationFingerprint(), System.currentTimeMillis())
		}.getOrElse {
			return SourceStartResult.Failed(
				appliedState(source, plan.revision, null, SourceApplyStatus.FAILED, SystemClock.elapsedRealtimeNanos()), true,
			)
		}
		val nextQueue = CellCallbackLane<CellRuntimeInput>()
		val nextCallbackToken = CellCallbackToken()
		synchronized(callbackLock) {
			registration = nextRegistration
			callbackToken = nextCallbackToken
			currentPlan = plan
			currentSink = sink
			queue = nextQueue
			accepting = true
		}
		callbackSequence = 0L
		processedCallbackSequence.value = 0L
		cutoffElapsedNanos = null
		metrics = RuntimeAdmissionMetrics()
		if (!backend.start(plan.subscriptionIds) { snapshot ->
				onBackendSnapshot(nextCallbackToken, snapshot, CellRefreshOutcome.CALLBACK)
			}) {
			registrations.markFailed(nextRegistration, "PROVIDER_REGISTRATION_FAILED", System.currentTimeMillis())
			synchronized(callbackLock) {
				accepting = false
				nextQueue.close()
			}
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
		actor = applicationScope.launch { consume(nextQueue, nextCallbackToken) }
		enqueueControl(CellRuntimeInput.Started)
		val state = appliedState(
			source, plan.revision, nextRegistration, application.status, SystemClock.elapsedRealtimeNanos(),
		).copy(degradedReasons = application.reasons)
		return if (application.status == SourceApplyStatus.DEGRADED) {
			SourceStartResult.Degraded(state)
		} else SourceStartResult.Started(state)
	}

	private suspend fun shutdownLocked(cutoff: SessionCutoff?): SourceStopAck {
		val activeRegistration = registration ?: return unavailableAck(cutoff)
		synchronized(callbackLock) { cutoffElapsedNanos = cutoff?.elapsedRealtimeNanos }
		wakeups.cancel(REFRESH_WAKEUP_ID)
		wakeups.cancel(TIMEOUT_WAKEUP_ID)
		val barrier: Long
		synchronized(callbackLock) {
			accepting = false
			barrier = callbackSequence
		}
		val removed = if (backend.stop()) RegistrationRemovalOutcome.REMOVED else RegistrationRemovalOutcome.FAILED
		synchronized(callbackLock) { queue?.close() }
		val activeActor = actor
		val remainingMs = cutoff?.let {
			((it.deadlineElapsedRealtimeNanos - SystemClock.elapsedRealtimeNanos()) / NANOS_PER_MILLISECOND)
				.coerceAtLeast(1L)
		} ?: DEFAULT_DRAIN_TIMEOUT_MS
		val drained = activeActor == null || withTimeoutOrNull(remainingMs) { activeActor.join(); true } == true
		if (!drained) activeActor.cancelAndJoin()
		if (!drained) {
			unprocessedCellCallbackRange(processedCallbackSequence.value, barrier)?.let { unresolved ->
				metrics.recordFailure(
					unresolved.first,
					unresolved.last,
					RuntimeGapClassification.DRAIN_TIMED_OUT,
				)
			}
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
		)
		if (removed == RegistrationRemovalOutcome.FAILED) {
			registrations.markFailed(activeRegistration, "PROVIDER_REMOVAL_FAILED", System.currentTimeMillis())
		} else {
			registrations.markRetired(activeRegistration, System.currentTimeMillis())
		}
		clearActiveState()
		return ack
	}

	private fun onBackendSnapshot(
		token: CellCallbackToken,
		snapshot: CellBackendSnapshot,
		outcome: CellRefreshOutcome,
	) {
		synchronized(callbackLock) {
			if (!accepting || callbackToken !== token) return
			val context = currentCallbackContextLocked() ?: return
			// This lock is also the compatible-authorization boundary. The live prerequisite read and
			// immutable context capture therefore precede the FIFO offer as one callback-entry step.
			if (!prerequisiteGate.allows(context.plan)) return
			val receivedElapsedNanos = SystemClock.elapsedRealtimeNanos()
			val receivedWallTimeMs = System.currentTimeMillis()
			val sequence = ++callbackSequence
			val accepted = queue?.offer(
				CellRuntimeInput.Snapshot(
					snapshot = snapshot,
					outcome = outcome,
					callbackSequence = sequence,
					receivedElapsedNanos = receivedElapsedNanos,
					receivedWallTimeMs = receivedWallTimeMs,
					context = context,
				),
			) == true
			if (!accepted) {
				metrics.recordFailure(
					sequence,
					classification = RuntimeGapClassification.CALLBACK_BUFFER_OVERFLOW,
				)
			}
		}
	}

	private fun enqueueControl(input: CellRuntimeInput) {
		synchronized(callbackLock) {
			if (accepting) queue?.offer(input)
		}
	}

	private fun currentCallbackContext(token: CellCallbackToken): CellCallbackContext? =
		synchronized(callbackLock) {
			if (!accepting || callbackToken !== token) null else currentCallbackContextLocked()
		}

	private fun currentCallbackContextLocked(): CellCallbackContext? {
		val activeRegistration = registration ?: return null
		val activePlan = currentPlan ?: return null
		val activeSink = currentSink ?: return null
		return CellCallbackContext(activeRegistration, activePlan, activeSink)
	}

	private suspend fun consume(
		inputs: CellCallbackLane<CellRuntimeInput>,
		activeToken: CellCallbackToken,
	) {
		var lastRefreshAtMs = Long.MIN_VALUE
		var backoff = RuntimeBackoffState()
		var awaitingRefresh = false
		val replayGateBySubscription = mutableMapOf<Int?, BoundedReplayIdentityGate>()
		var refreshAttempts = 0
		var qualifiedEvidenceReceived = false

		fun canRequestRefresh(context: CellCallbackContext): Boolean =
			refreshAttempts < MAX_REFRESH_ATTEMPTS_PER_REGISTRATION &&
				!qualifiedEvidenceReceived &&
				context.registration.purposeEligibilityMask and
					SourceBrokerPurpose.MASK_SESSION_CAPTURE != 0L

		suspend fun scheduleRefresh() {
			val context = currentCallbackContext(activeToken) ?: return
			val state = deviceStateProvider.cell()
			if (!prerequisiteGate.allows(context.plan) ||
				!shouldScheduleCellRefresh(context.plan.mode, state.refreshApiAvailable) ||
				!canRequestRefresh(context)
			) {
				wakeups.cancel(REFRESH_WAKEUP_ID)
				return
			}
			val now = SystemClock.elapsedRealtime()
			val earliest = nextAttemptDeadlineMs(
				now,
				lastRefreshAtMs.takeUnless { it == Long.MIN_VALUE },
				context.plan.minimumRefreshAttemptIntervalMs,
				backoff.delayMs(context.plan.backoff),
			)
			wakeups.schedule(
				sourceWakeupRequest(
					REFRESH_WAKEUP_ID, source, earliest,
					(context.plan.minimumRefreshAttemptIntervalMs / 4L)
						.coerceIn(0L, MAX_COALESCE_WINDOW_MS),
				),
			) {
				enqueueControl(CellRuntimeInput.Refresh)
			}
		}

		suspend fun recordSnapshot(input: CellRuntimeInput.Snapshot): Boolean {
			val qualified = qualifyCellSnapshot(
				input.snapshot,
				input.outcome,
				input.receivedElapsedNanos,
				input.context.plan.maximumAcceptableCachedAgeMs,
			) ?: return false
			val replayGate = replayGateBySubscription.getOrPut(input.snapshot.subscriptionId) {
				BoundedReplayIdentityGate()
			}
			if (!replayGate.shouldAdmit(qualified.snapshotIdentity)) return false
			return if (admitSnapshot(
					input.context,
					qualified.observations,
					input.outcome,
					qualified.providerTimeNanos,
					input.receivedElapsedNanos,
					input.receivedWallTimeMs,
					PlanAttribution.CAPTURED_REGISTRATION,
					input.callbackSequence,
				)
			) {
				replayGate.record(qualified.snapshotIdentity)
				true
			} else false
		}

		inputs.consume inputLoop@{ input ->
			when (input) {
				CellRuntimeInput.Started,
				CellRuntimeInput.PlanChanged -> {
					scheduleRefresh()
				}
				is CellRuntimeInput.Snapshot -> {
					try {
						if (recordSnapshot(input)) {
							qualifiedEvidenceReceived = true
							wakeups.cancel(REFRESH_WAKEUP_ID)
						}
					} catch (cancelled: CancellationException) {
						metrics.recordFailure(input.callbackSequence)
						throw cancelled
					} catch (_: Throwable) {
						metrics.recordFailure(input.callbackSequence)
					} finally {
						processedCallbackSequence.value = maxOf(
							processedCallbackSequence.value,
							input.callbackSequence,
						)
					}
					if (awaitingRefresh) {
						awaitingRefresh = false
						wakeups.cancel(TIMEOUT_WAKEUP_ID)
						backoff = backoff.succeeded()
					}
					scheduleRefresh()
				}
				CellRuntimeInput.Refresh -> {
					val context = currentCallbackContext(activeToken) ?: return@inputLoop
					if (!canRequestRefresh(context) || !prerequisiteGate.allows(context.plan)) {
						scheduleRefresh()
						return@inputLoop
					}
					refreshAttempts++
					lastRefreshAtMs = SystemClock.elapsedRealtime()
					when (backend.requestRefresh { snapshot ->
						onBackendSnapshot(activeToken, snapshot, CellRefreshOutcome.CALLBACK)
					}) {
						CellRefreshRequestOutcome.REQUESTED -> {
							awaitingRefresh = true
							val timeoutAt = SystemClock.elapsedRealtime() + REFRESH_TIMEOUT_MS
							wakeups.schedule(
								sourceWakeupRequest(TIMEOUT_WAKEUP_ID, source, timeoutAt, 0L),
							) { enqueueControl(CellRuntimeInput.Timeout) }
						}
						CellRefreshRequestOutcome.NOT_SUPPORTED -> {
							backoff = backoff.succeeded()
						}
						CellRefreshRequestOutcome.PERMISSION_BLOCKED -> {
							backoff = backoff.failed()
						}
						CellRefreshRequestOutcome.PROVIDER_FAILED -> {
							backoff = backoff.failed()
						}
					}
					scheduleRefresh()
				}
				CellRuntimeInput.Timeout -> if (awaitingRefresh) {
					awaitingRefresh = false
					backoff = backoff.failed()
					scheduleRefresh()
				}
			}
		}
	}

	private suspend fun admitSnapshot(
		context: CellCallbackContext,
		observations: List<CellObservationEvidence>,
		outcome: CellRefreshOutcome,
		providerTimeNanos: Long?,
		receivedNanos: Long,
		receivedWallTimeMs: Long,
		attribution: PlanAttribution,
		callbackSequence: Long,
	): Boolean {
		val registration = context.registration
		val observed = providerTimeNanos ?: receivedNanos
		if (cutoffElapsedNanos?.let { observed > it } == true) return false
		if (!prerequisiteGate.allows(context.plan)) {
			metrics.recordFailure(callbackSequence)
			return false
		}
		val sourceSequence = runCatchingNonCancellation {
			registrations.allocateSequence(registration, receivedWallTimeMs)
		}.getOrElse {
			metrics.recordFailure(
				callbackSequence,
				classification = RuntimeGapClassification.SEQUENCE_ALLOCATION_FAILED,
			)
			return false
		}
		val delayMs = (receivedNanos - observed).coerceAtLeast(0L) / NANOS_PER_MILLISECOND
		val wallTime = (receivedWallTimeMs - delayMs).coerceAtLeast(0L)
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
				"${outcome.name}:$observed:$fingerprint",
			logicalTrackingId = null, serviceRunId = null, source = source,
			sourceInstanceId = SourceInstanceId(registration.state.sourceInstanceId),
			registrationGeneration = registration.state.registrationGeneration,
			physicalConfigurationFingerprint = registration.physicalConfigurationFingerprint,
			authorizationRevision = registration.authorization.authorizationRevision,
			registrationPurposeEligibilityMask = registration.purposeEligibilityMask,
			registrationEligibilityFingerprint = registration.eligibilityFingerprint,
			sourceSequence = sourceSequence,
			configRevision = registration.state.appliedRevision.takeUnless {
				attribution == PlanAttribution.RECEIVE_TIME_ONLY
			},
			planAttribution = attribution,
			clockDomainId = registration.state.clockDomainId,
			observedElapsedRealtimeNanos = observed.coerceAtLeast(0L),
			receivedElapsedRealtimeNanos = receivedNanos.coerceAtLeast(0L),
			wallTimeMs = wallTime, wallTimeUncertaintyMs = if (providerTimeNanos == null) delayMs else 1L,
			capturedCollectedDataEpoch = registration.state.collectedDataEpoch,
			acquiredAtMs = wallTime, quality = SourceQuality(flags = flags), payloadVersion = 1,
			payload = minimizedCellSnapshotPayload(observations, outcome),
		)
		when (val handoff = retryCellAdmissionWithinBudget(
			prerequisiteAllows = { prerequisiteGate.allows(context.plan) },
			admit = { context.sink.admit(candidate) },
		)) {
			is SourceAdmissionHandoff.Durable -> {
				metrics.recordDurable(callbackSequence, handoff.admissionOrdinal)
				return true
			}
			is SourceAdmissionHandoff.Duplicate -> {
				metrics.recordDurable(callbackSequence, handoff.existingAdmissionOrdinal)
				return true
			}
			is SourceAdmissionHandoff.TerminalFailure,
			is SourceAdmissionHandoff.RetryableFailure,
			-> {
				metrics.recordFailure(callbackSequence)
				return false
			}
			null -> {
				metrics.recordFailure(callbackSequence)
				return false
			}
		}
	}

	private fun capabilitiesNow(): SourceCapabilities = cellCapabilities(deviceStateProvider.cell())

	private fun clearActiveState() {
		synchronized(callbackLock) {
			accepting = false
			registration = null
			callbackToken = null
			currentSink = null
			queue = null
		}
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
		data object Started : CellRuntimeInput
		data object PlanChanged : CellRuntimeInput
		data object Refresh : CellRuntimeInput
		data object Timeout : CellRuntimeInput
		data class Snapshot(
			val snapshot: CellBackendSnapshot,
			val outcome: CellRefreshOutcome,
			val callbackSequence: Long,
			val receivedElapsedNanos: Long,
			val receivedWallTimeMs: Long,
			val context: CellCallbackContext,
		) : CellRuntimeInput
	}

	private companion object {
		const val REFRESH_WAKEUP_ID = "cell-sparse-refresh"
		const val TIMEOUT_WAKEUP_ID = "cell-refresh-timeout"
		const val DEFAULT_DRAIN_TIMEOUT_MS = 3_000L
		const val NANOS_PER_MILLISECOND = 1_000_000L
		const val MAX_COALESCE_WINDOW_MS = 5_000L
		const val REFRESH_TIMEOUT_MS = 20_000L
		const val MAX_REFRESH_ATTEMPTS_PER_REGISTRATION = 1
	}
}

private class CellCallbackToken

private data class CellCallbackContext(
	val registration: SourceRegistration,
	val plan: CellPlan,
	val sink: SourceEventSink,
)

/**
 * Fixed-memory Cell FIFO. A rejected provider callback is represented in process-local admission
 * metrics and, when shutdown is orderly, in [SourceStopAck]. Crash-durable handoff is separate.
 */
internal class CellCallbackLane<T>(capacity: Int = CELL_CALLBACK_BUFFER_CAPACITY) {
	private val channel: Channel<T>

	init {
		require(capacity > 0)
		channel = Channel(capacity)
	}

	fun offer(input: T): Boolean = channel.trySend(input).isSuccess

	fun close() = channel.close()

	suspend fun consume(action: suspend (T) -> Unit) {
		for (input in channel) action(input)
	}
}

/** Reads live Android state on every invocation; no reconciler or capability-flow cache is used. */
internal class CellPrerequisiteGate(
	private val stateProvider: () -> CellDeviceState,
) {
	fun allows(plan: CellPlan): Boolean = runCatching {
		plan.enabled && CellPrerequisiteEvaluator.evaluate(plan, stateProvider()).status !=
			SourceApplyStatus.BLOCKED
	}.getOrDefault(false)
}

internal data class QualifiedCellSnapshot(
	val observations: List<CellObservationEvidence>,
	val providerTimeNanos: Long,
	val snapshotIdentity: String,
)

internal fun qualifyCellSnapshot(
	snapshot: CellBackendSnapshot,
	outcome: CellRefreshOutcome,
	receivedElapsedNanos: Long,
	maximumAcceptableAgeMs: Long,
): QualifiedCellSnapshot? {
	val eligible = freshProviderObservations(
		snapshot.observations,
		receivedElapsedNanos,
		maximumAcceptableAgeMs,
		CellBackendObservation::providerTimestampNanos,
	)
	val emptyCoverage = shouldAdmitCoverageOnly(
		providerItemCount = snapshot.providerItemCount,
		eligibleItemCount = eligible.size,
		// An empty cache read has no observation or coverage value. Only a provider delivery can
		// establish a fresh empty boundary.
		providerDeliveryConfirmedFresh = outcome != CellRefreshOutcome.CACHED,
	)
	if (eligible.isEmpty() && !emptyCoverage) return null
	val providerTime = eligible.mapNotNull(CellBackendObservation::providerTimestampNanos)
		.maxOrNull() ?: receivedElapsedNanos
	val ordered = eligible.sortedWith(
		compareBy(
			CellBackendObservation::radioType,
			CellBackendObservation::identity,
			CellBackendObservation::registered,
			CellBackendObservation::signalLevelDbm,
			CellBackendObservation::providerTimestampNanos,
		),
	)
	val observations = ordered.map(CellBackendObservation::toMinimizedEvidence)
	val identity = if (emptyCoverage) {
		"coverage-empty:$receivedElapsedNanos"
	} else ordered.joinToString("|") {
		"${it.identity}:${it.radioType}:${it.registered}:${it.signalLevelDbm}:${it.providerTimestampNanos}"
	}
	return QualifiedCellSnapshot(observations, providerTime, identity)
}

/**
 * Retries the FIFO head inside a finite in-process budget. A retryable result after the final
 * delay is returned to the caller so the callback can be marked unresolved and the lane can keep
 * draining. A null means the live prerequisite disappeared before an admission attempt.
 */
internal suspend fun retryCellAdmissionWithinBudget(
	prerequisiteAllows: () -> Boolean,
	admit: suspend () -> SourceAdmissionHandoff,
	retryDelaysMs: LongArray = CELL_ADMISSION_RETRY_DELAYS_MS,
	waitBeforeRetry: suspend (Long) -> Unit = { delay(it) },
): SourceAdmissionHandoff? {
	var retryIndex = 0
	while (true) {
		if (!prerequisiteAllows()) return null
		val result = admit()
		if (result !is SourceAdmissionHandoff.RetryableFailure) return result
		val retryDelayMs = retryDelaysMs.getOrNull(retryIndex++) ?: return result
		waitBeforeRetry(retryDelayMs)
	}
}

internal fun unprocessedCellCallbackRange(processedSequence: Long, barrierSequence: Long): LongRange? {
	require(processedSequence >= 0L)
	require(barrierSequence >= 0L)
	val first = processedSequence + 1L
	return if (first <= barrierSequence) first..barrierSequence else null
}

internal fun cellPlansSharePhysicalRegistration(active: CellPlan, updated: CellPlan): Boolean =
	updated.enabled &&
		active.physicalConfigurationFingerprint() == updated.physicalConfigurationFingerprint()

internal fun cellCapabilities(state: CellDeviceState): SourceCapabilities {
	val available = state.radioFeatureAvailable && state.fineLocationPermission &&
		state.readPhoneStatePermission
	val reasons = buildSet {
		if (!state.radioFeatureAvailable) add(SourceDegradedReason.HARDWARE_UNAVAILABLE)
		if (!state.fineLocationPermission || !state.readPhoneStatePermission) {
			add(SourceDegradedReason.PERMISSION_MISSING)
		}
	}
	return SourceCapabilities(
		available = available,
		batchingSupported = false,
		flushSupported = false,
		maximumBatchSize = null,
		// requestCellInfoUpdate is a finite opportunistic probe, not a wake-reliable cadence.
		minimumDelayMs = null,
		degradedReasons = reasons,
	)
}

internal const val CELL_CALLBACK_BUFFER_CAPACITY = 64
private val CELL_ADMISSION_RETRY_DELAYS_MS = longArrayOf(10L, 50L, 250L)

internal fun CellBackendObservation.toMinimizedEvidence() = CellObservationEvidence(
	identifierToken = WITHHELD_RADIO_IDENTIFIER_TOKEN,
	radioType = radioType,
	registered = registered,
	signalLevelDbm = signalLevelDbm,
	providerTimestampNanos = providerTimestampNanos,
)

internal fun minimizedCellSnapshotPayload(
	observations: List<CellObservationEvidence>,
	outcome: CellRefreshOutcome,
) = CellSnapshotPayload(
	subscriptionId = null,
	observations = observations,
	refreshOutcome = outcome,
)
