package com.adsamcik.tracker.tracker.source.runtime

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener2
import android.hardware.SensorManager
import android.os.SystemClock
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceRuntimeStateEntity
import com.adsamcik.tracker.shared.base.di.ApplicationScope
import com.adsamcik.tracker.tracker.source.runCatchingNonCancellation
import com.adsamcik.tracker.tracker.altitude.BarometricAltitudeFormula
import com.adsamcik.tracker.tracker.source.ingress.PRESSURE_QUALIFIED_WINDOW_PAYLOAD_VERSION
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.PressurePlan
import com.adsamcik.tracker.tracker.source.model.PressureSensorAccuracy
import com.adsamcik.tracker.tracker.source.model.PressureWindowPayload
import com.adsamcik.tracker.tracker.source.model.PressureWindowClosureKind
import com.adsamcik.tracker.tracker.source.model.PressureWindowQualification
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal sealed interface PressureProviderEraseSettlement {
	data object NoLocalProvider : PressureProviderEraseSettlement
	data class Settled(val registrationGeneration: Long) : PressureProviderEraseSettlement
	data object CaptureAuthorizationActive : PressureProviderEraseSettlement
	data object StaleLifecycle : PressureProviderEraseSettlement
	data object CallbackDrainTimedOut : PressureProviderEraseSettlement
	data object ProviderRemovalFailed : PressureProviderEraseSettlement
}

internal sealed interface PressureProviderEraseVerification {
	data object Verified : PressureProviderEraseVerification
	data object CaptureAuthorizationActive : PressureProviderEraseVerification
	data object StaleLifecycle : PressureProviderEraseVerification
	data object ProviderRemovalFailed : PressureProviderEraseVerification
}

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
	private val accumulatorSamples = ArrayList<PendingPressureSample>()
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
	private val stopDeadlineElapsedNanos = MutableStateFlow<Long?>(null)
	@Volatile private var laneDrainComplete = true
	private var cutoffElapsedNanos: Long? = null
	private var terminalSessionCutoff: SessionCutoff? = null
	private var terminalSessionCutoffOwner: SourceAuthorizationEntity? = null
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

	/** Stops session attribution before Room waits, without stopping the shared sensor. */
	internal fun fenceSessionCutoff(
		cutoff: SessionCutoff,
		serviceRunId: String,
		clockDomainId: String,
	): SessionCutoff? = synchronized(callbackLock) {
		val active = registration ?: return@synchronized null
		if (!active.matchesCaptureStop(cutoff, serviceRunId, clockDomainId)) {
			return@synchronized null
		}
		val capture = requireNotNull(active.capturePressureMembership())
		val earlier = terminalSessionCutoff?.takeIf {
			it.logicalTrackingId == cutoff.logicalTrackingId &&
				terminalSessionCutoffOwner.samePressureCaptureOwner(capture) &&
				it.elapsedRealtimeNanos <= cutoff.elapsedRealtimeNanos
		}
		(earlier ?: cutoff).also {
			terminalSessionCutoff = it
			terminalSessionCutoffOwner = capture
		}
	}

	private fun SourceAuthorizationEntity?.samePressureCaptureOwner(other: SourceAuthorizationEntity): Boolean =
		this != null && purpose == SourceBrokerPurpose.SESSION_CAPTURE && other.purpose == purpose &&
			logicalTrackingId == other.logicalTrackingId && serviceRunId == other.serviceRunId &&
			effectiveBootId == other.effectiveBootId

	private fun SourceRegistration.matchesCaptureStop(
		cutoff: SessionCutoff,
		serviceRunId: String,
		clockDomainId: String,
	): Boolean {
		val capture = capturePressureMembership() ?: return false
		return state.clockDomainId == clockDomainId &&
			capture.logicalTrackingId == cutoff.logicalTrackingId && capture.serviceRunId == serviceRunId &&
			runtimeClaim?.logicalTrackingId == cutoff.logicalTrackingId && runtimeClaim?.serviceRunId == serviceRunId
	}

	override suspend fun quiesce(cutoff: SessionCutoff): SourceStopAck {
		fenceCapacityResume()
		return lifecycleMutex.withLock {
			settledStopAcknowledgement(cutoff).also(::clearClaimIfReleased)
		}
	}

	/** Keep independently authorized all-day samples during the existing bounded provider flush. */
	internal suspend fun quiesceShared(cutoff: SessionCutoff): SourceStopAck {
		fenceCapacityResume()
		return lifecycleMutex.withLock {
			settledStopAcknowledgement(cutoff, preserveAmbient = true)
				.also(::clearClaimIfReleased)
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
			currentPlan != null || retirementIntent != null -> settledStopAcknowledgement(cutoff)
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
				clearClaimIfReleased(settledStopAcknowledgement(null))
			} else {
				runtimeClaim = null
			}
		}
	}

	/**
	 * Settles only an already-authorized local Pressure provider before source-wide payload erase.
	 *
	 * Revoking consent does not itself retire callbacks. A still-active capture authorization blocks
	 * this method unless an earlier stop already closed callback entry at an observed cutoff and left
	 * the exact provider retirement pending. No provider is started or replaced here.
	 */
	internal suspend fun establishSourceEraseBarrier(
		expectedCollectedDataEpoch: Long,
	): PressureProviderEraseSettlement {
		require(expectedCollectedDataEpoch >= 0L)
		fenceCapacityResume()
		return lifecycleMutex.withLock {
			val activeRegistration = registration ?: return@withLock PressureProviderEraseSettlement.NoLocalProvider
			if (activeRegistration.state.collectedDataEpoch != expectedCollectedDataEpoch) {
				return@withLock PressureProviderEraseSettlement.StaleLifecycle
			}
			val exactStoppingRetirement = hasExactObservedStoppingRetirement(activeRegistration)
			if (!exactStoppingRetirement && activeRegistration.hasSessionCaptureAuthorization()) {
				return@withLock PressureProviderEraseSettlement.CaptureAuthorizationActive
			}

			val acknowledgement = settledStopAcknowledgement(null)
			if (acknowledgement.source != SourceKind.PRESSURE ||
				acknowledgement.sourceInstanceId.value != activeRegistration.state.sourceInstanceId ||
				acknowledgement.registrationGeneration !=
				activeRegistration.state.registrationGeneration
			) {
				return@withLock PressureProviderEraseSettlement.StaleLifecycle
			}
			if (!acknowledgement.appDrainComplete) {
				return@withLock PressureProviderEraseSettlement.CallbackDrainTimedOut
			}
			if (acknowledgement.registrationRemovalOutcome != RegistrationRemovalOutcome.REMOVED) {
				return@withLock PressureProviderEraseSettlement.ProviderRemovalFailed
			}
			clearClaimIfReleased(acknowledgement)
			PressureProviderEraseSettlement.Settled(
				activeRegistration.state.registrationGeneration,
			)
		}
	}

	internal suspend fun verifySourceEraseProviderSettled(
		expectedCollectedDataEpoch: Long,
		expectedRegistrationGeneration: Long?,
	): PressureProviderEraseVerification = lifecycleMutex.withLock {
		require(expectedCollectedDataEpoch >= 0L)
		require(expectedRegistrationGeneration == null || expectedRegistrationGeneration > 0L)
		val active = registration
		if (expectedRegistrationGeneration != null && active != null &&
			(active.state.collectedDataEpoch != expectedCollectedDataEpoch ||
				active.state.registrationGeneration != expectedRegistrationGeneration)
		) return@withLock PressureProviderEraseVerification.StaleLifecycle
		if (active?.hasSessionCaptureAuthorization() == true) {
			return@withLock PressureProviderEraseVerification.CaptureAuthorizationActive
		}
		if (active != null || retirementIntent != null) {
			return@withLock PressureProviderEraseVerification.ProviderRemovalFailed
		}
		if (registrations.verifySourceEraseProviderSettled(
				source,
				expectedCollectedDataEpoch,
				expectedRegistrationGeneration,
			)
		) {
			PressureProviderEraseVerification.Verified
		} else {
			PressureProviderEraseVerification.StaleLifecycle
		}
	}

	private fun SourceRegistration.hasSessionCaptureAuthorization(): Boolean =
		authorization.authorizedMembers.any { member ->
			member.persistenceEligible && member.purpose == SourceBrokerPurpose.SESSION_CAPTURE
		}

	private fun hasExactObservedStoppingRetirement(
		activeRegistration: SourceRegistration,
	): Boolean = synchronized(callbackLock) {
		val intent = retirementIntent ?: return@synchronized false
		val authorizationEffectiveAt =
			activeRegistration.authorization.effectiveElapsedRealtimeNanos
		val requestedCutoff = cutoffElapsedNanos
		terminalSettlementInProgress &&
			!acceptingCallbacks &&
			intent.registration.samePressureRegistrationAs(activeRegistration) &&
			intent.listener === listener &&
			intent.callbackToken === callbackToken &&
			!callbackGate.accepts(intent.callbackToken) &&
			intent.retiredElapsedRealtimeNanos >= authorizationEffectiveAt &&
			(requestedCutoff == null || intent.retiredElapsedRealtimeNanos >= requestedCutoff)
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
		// A failed reservation must leave the retired predecessor available to End.
		// Only an installed successor replaces that exact provider's acknowledgement.
		terminalStopAck = null
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
		cutoffElapsedNanos = null
		terminalSessionCutoff = null
		terminalSessionCutoffOwner = null
		stopDeadlineElapsedNanos.value = null
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
			accumulatorSamples.clear()
			capacityResumeGate = PressureCapacityResumeGate(RESUME_LOW_WATER_WINDOWS)
			capacityResumeReconciliationAllowed = true
			accumulator = nextAccumulator
			accumulatorAttribution = PressureCallbackAttribution(nextRegistration, sink,
				capturePlanApplied = runtimeClaim != null)
			callbackAttributionTimeline = PressureObservedAuthorizationTimeline(nextRegistration, sink,
				initialCapturePlanApplied = runtimeClaim != null)
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
		val initialized = try {
			initializeRecoveryCheckpoint(nextRegistration, recovery)
			true
		} catch (cancelled: CancellationException) {
			cleanupFailedStart(nextRegistration, nextListener, nextToken, nextWindowLane,
				"RUNTIME_CHECKPOINT_CANCELLED")
			throw cancelled
		} catch (fatal: Error) {
			cleanupFailedStart(nextRegistration, nextListener, nextToken, nextWindowLane,
				"RUNTIME_CHECKPOINT_FATAL")
			throw fatal
		} catch (_: Exception) {
			false
		}
		if (!initialized) {
			cleanupFailedStart(nextRegistration, nextListener, nextToken, nextWindowLane,
				"RUNTIME_CHECKPOINT_FAILED")
			return SourceStartResult.Failed(
				appliedState(source, plan.revision, nextRegistration, SourceApplyStatus.FAILED,
					SystemClock.elapsedRealtimeNanos()),
				retryable = true,
			)
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

	/** The accepted replacement owns the checkpoint before its queued readings can be admitted. */
	private suspend fun initializeRecoveryCheckpoint(
		activeRegistration: SourceRegistration,
		recovery: PressureRuntimeRecovery,
	) {
		if (!recovery.requiresPreAcquisitionCheckpoint) {
			return
		}
		val checkpointElapsedNanos = SystemClock.elapsedRealtimeNanos()
		val restored = requireNotNull(recovery.metrics)
		check(registrations.saveSensorRuntimeCheckpoint(
			registration = activeRegistration,
			lastProviderSequence = recovery.callbackEntrySequence,
			checkpoint = SensorRuntimeCheckpoint(RuntimeCheckpointLifecycle.ACTIVE, restored,
				PRESSURE_RUNTIME_COMPONENT_VERSION, ByteArray(0),
				causalOrderElapsedRealtimeNanos = checkpointElapsedNanos),
			updatedAtMs = pressureCheckpointOrderMillis(checkpointElapsedNanos),
		) == SourceRuntimeStateSaveResult.Saved)
		lastCheckpointedProviderSequence = recovery.callbackEntrySequence
		lastCheckpointedAdmission = restored
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
		val previousCapturePlanApplied = runtimeClaim != null
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
					maximumBufferedSamples = MAX_PRESSURE_SAMPLES_PER_WINDOW,
					minimumWindowNanos = minOf(activePlan.aggregationWindowMs, plan.aggregationWindowMs)
						.coerceAtLeast(1L) * NANOS_PER_MILLISECOND,
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
					PressureCallbackAttribution(activeRegistration, activeSink,
						capturePlanApplied = previousCapturePlanApplied),
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
					PressureCallbackAttribution(activeRegistration, activeSink,
						capturePlanApplied = previousCapturePlanApplied),
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
					PressureCallbackAttribution(activeRegistration, activeSink,
						capturePlanApplied = previousCapturePlanApplied),
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
					PressureCallbackAttribution(activeRegistration, activeSink,
						capturePlanApplied = previousCapturePlanApplied),
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
					capturePlanApplied = runtimeClaim != null,
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
			terminalSettlementInProgress = true
			acceptingCallbacks = false
			callbackGate.retire(failedToken)
			capacityResumeReconciliationAllowed = false
			capacityResumeGate.fenceResume()
			if (actor == null) {
				rejectUnacceptedCallbacksLocked(failedLane)
			} else {
				enqueueTerminalWindowLocked()
			}
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

	/** Failed registration acceptance has no authority to admit its buffered callbacks. */
	private fun rejectUnacceptedCallbacksLocked(failedLane: Channel<PressureCompletedWindow>) {
		if (callbackEntrySequence > lastCheckpointedProviderSequence) {
			recordPressureFailureExactly(lastCheckpointedProviderSequence + 1L..callbackEntrySequence)
		}
		while (failedLane.tryReceive().isSuccess) {
			windowCapacity.windowSettled()
		}
		accumulator?.drain()
		accumulatorSamples.clear()
		lastAccumulatedReception = null
	}

	private fun enqueueTerminalWindowLocked() {
		val terminalWindow = if (exceptionalActorFailurePending) {
			null
		} else {
			drainPressureWindowAtBoundary(accumulator, lastAccumulatedReception)
		}
		if (terminalWindow != null) {
			val attribution = checkNotNull(accumulatorAttribution) {
				"Pressure terminal window had no observed-time authorization attribution"
			}
			enqueueWindowLocked(
				payload = terminalWindow.payload,
				reception = terminalWindow.reception,
				attribution = attribution,
				lifecycle = RuntimeCheckpointLifecycle.QUIESCED,
				terminal = true,
				checkpointOrderElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
			)
		}
		lastAccumulatedReception = null
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
			terminalStopAck?.let { previous ->
				val admission = metrics.snapshot()
				val completed = previous.copy(
					lastDurablyAdmittedSequence = admission.lastDurablyAdmittedSequence,
					lastAdmissionOrdinal = admission.lastAdmissionOrdinal,
					failedAdmissionCount = admission.failedAdmissionCount,
					unresolvedSequenceStart = admission.unresolvedSequenceStart,
					unresolvedSequenceEndInclusive = admission.unresolvedSequenceEndInclusive,
					registrationRemovalOutcome = RegistrationRemovalOutcome.REMOVED,
					appDrainComplete = true,
					status = SourceStopStatus.COMPLETE,
				)
				terminalStopAck = completed
				persistTerminalCheckpoint(
					intent.registration, lastCheckpointedProviderSequence, RuntimeCheckpointLifecycle.QUIESCED,
				)
				if (!persistPressureRetirement(intent.registration, completed)) return false
			}
			clearActiveState()
			true
		} else {
			retainProviderForRetirementRetry(retainActor = !actorSettled && actor != null)
			false
		}
	}

	private suspend fun settledStopAcknowledgement(
		cutoff: SessionCutoff?,
		preserveAmbient: Boolean = false,
	): SourceStopAck {
		if (terminalStopAck != null && !settleOwnedRetirementLocked()) {
			val previous = requireNotNull(terminalStopAck)
			return previous.copy(
				appDrainComplete = false,
				status = if (previous.appDrainComplete) SourceStopStatus.PROVIDER_FAILED else previous.status,
			)
		}
		return terminalStopAck ?: shutdownLocked(cutoff, preserveAmbient)
	}

	private suspend fun persistPressureRetirement(activeRegistration: SourceRegistration, ack: SourceStopAck): Boolean {
		val claim = runtimeClaim ?: return true
		return runCatchingNonCancellation {
			registrations.savePressureRetirementCompleteness(activeRegistration, claim, ack)
		}.getOrDefault(false)
	}

	/** Durable terminal fence -> exact listener removal -> exact token completion. */
	private suspend fun retireProviderRegistration(
		activeRegistration: SourceRegistration,
		activeListener: SensorEventListener2,
		activeToken: PressureCallbackToken,
		reason: String,
		retiredElapsedRealtimeNanos: Long,
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
			// Room admission is a durable journal operation, not a one-second provider flush.
			val token = runCatchingNonCancellation {
				registrations.beginRetirement(
					intent.registration,
					intent.reason,
					intent.retiredAtMs,
					intent.retiredElapsedRealtimeNanos,
				)
			}.getOrNull()
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
		val completed = runCatchingNonCancellation {
			registrations.completeRetirement(durableToken)
		}.getOrDefault(false)
		if (completed) {
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
		val retainedActor = actor ?: return pressureFifoDrainComplete(actorJoined = true)
		if (retainedActor.isCompleted) return pressureFifoDrainComplete(actorJoined = true)
		stopDeadlineElapsedNanos.value = deadlineElapsedRealtimeNanos
			?: SystemClock.elapsedRealtimeNanos() + DEFAULT_DRAIN_TIMEOUT_MS * NANOS_PER_MILLISECOND
		return withContext(NonCancellable) {
			withTimeoutOrNull(providerSettlementTimeoutMs(deadlineElapsedRealtimeNanos)) {
				retainedActor.start()
				retainedActor.join()
				pressureFifoDrainComplete(actorJoined = true)
			} == true
		}
	}

	private fun pressureFifoDrainComplete(actorJoined: Boolean): Boolean =
		actorJoined && laneDrainComplete && windowCapacity.pendingWindowCount == 0 &&
			failureCoverage.coversAfter(lastCheckpointedProviderSequence, callbackEntrySequence)

	private suspend fun shutdownLocked(cutoff: SessionCutoff?, preserveAmbient: Boolean = false): SourceStopAck {
		val acknowledgement = withContext(NonCancellable) { settleShutdownLocked(cutoff, preserveAmbient) }
		currentCoroutineContext().ensureActive()
		return acknowledgement
	}

	private suspend fun settleShutdownLocked(cutoff: SessionCutoff?, preserveAmbient: Boolean = false): SourceStopAck {
		val activeRegistration = registration ?: return unavailableAck(cutoff)
		val activeListener = listener ?: return unavailableAck(cutoff)
		val activeToken = callbackToken ?: return unavailableAck(cutoff)
		synchronized(callbackLock) {
			terminalSettlementInProgress = true
			if (cutoff != null) {
				val capture = activeRegistration.capturePressureMembership()
				val earlier = terminalSessionCutoff?.takeIf {
					capture != null && terminalSessionCutoffOwner.samePressureCaptureOwner(capture) &&
						it.elapsedRealtimeNanos <= cutoff.elapsedRealtimeNanos
				}
				terminalSessionCutoff = earlier ?: cutoff
				terminalSessionCutoffOwner = capture
			}
			val ambientAuthorized = activeRegistration.authorization.authorizedMembers.any {
				it.purpose == SourceBrokerPurpose.AMBIENT_PRODUCT && it.persistenceEligible
			}
			cutoffElapsedNanos = cutoff?.elapsedRealtimeNanos.takeUnless { preserveAmbient && ambientAuthorized }
		}
		stopDeadlineElapsedNanos.value = cutoff?.deadlineElapsedRealtimeNanos
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
			enqueueTerminalWindowLocked()
		}
		val retirement = retireProviderRegistration(
			activeRegistration,
			activeListener,
			activeToken,
			"ORDERLY_STOP",
			retirementBoundary,
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
		val drainComplete = pressureFifoDrainComplete(actorJoined)
		if (!drainComplete && activeActor?.isCompleted == true) {
			val firstAfterCheckpoint = lastCheckpointedProviderSequence + 1L
			if (firstAfterCheckpoint <= barrier) {
				recordPressureFailureExactly(
					firstAfterCheckpoint..barrier,
					RuntimeGapClassification.DRAIN_TIMED_OUT,
				)
			}
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
				!drainComplete -> SourceStopStatus.TIMED_OUT
				removal == RegistrationRemovalOutcome.FAILED -> SourceStopStatus.PROVIDER_FAILED
				else -> SourceStopStatus.COMPLETE
			},
		).withSessionMembership(runtimeClaim)
		terminalStopAck = ack
		// Keep the actual metrics before cancellation can leave retirement to another End attempt.
		if (drainComplete || activeActor?.isCompleted == true) {
			persistTerminalCheckpoint(
				activeRegistration,
				lastCheckpointedProviderSequence,
				if (drainComplete) RuntimeCheckpointLifecycle.QUIESCED else RuntimeCheckpointLifecycle.TIMED_OUT,
			)
		}
		return if (retirement == PressureProviderRetirement.COMPLETE && drainComplete) {
			if (persistPressureRetirement(activeRegistration, ack)) {
				clearActiveState()
				ack
			} else {
				retainProviderForRetirementRetry(retainActor = false)
				ack.copy(appDrainComplete = false, status = SourceStopStatus.PROVIDER_FAILED)
			}
		} else {
			retainProviderForRetirementRetry(retainActor = activeActor != null)
			ack
		}
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
				runtimeClaim != null,
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
						attribution = boundaryAttribution,
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
				accumulatorAttribution = PressureCallbackAttribution(context.registration, context.sink,
					capturePlanApplied = context.capturePlanApplied)
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
		stopDeadlineElapsedNanos.value == null &&
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
			if (currentPlan == null || cutoffElapsedNanos != null || stopDeadlineElapsedNanos.value != null) {
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
		val capturePlanApplied: Boolean,
	)

	private data class PreparedPressureCapacityResume(
		val context: PressureCapacityResumeContext,
		val partialSettlement: CompletableDeferred<Unit>?,
	)

	private suspend fun processWindowHead(window: PressureCompletedWindow): PressureWindowHeadResolution {
		var admissionWindow = window
		var ambientTail: PressureCompletedWindow? = null
		var sessionCutoff: Long? = null
		var observationsResolved = false
		val resolution = try {
			processPressureWindowHead(
				deadlineElapsedRealtimeNanos = { stopDeadlineElapsedNanos.value },
				nowElapsedRealtimeNanos = SystemClock::elapsedRealtimeNanos,
				prepare = { prepareWindowAdmission(admissionWindow) },
				admit = ::admitPressureWindowAfterLocalCutoff,
				rebuildAfterRejection = { _, rejected ->
					val cutoff = pressureRejectionCutoff(window, rejected)
					if (cutoff == null || sessionCutoff?.let { cutoff >= it } == true) {
						null
					} else {
						sessionCutoff = cutoff
						window.splitForCaptureCutoff(cutoff)?.let { (rebuilt, tail) ->
							ambientTail = tail
							admissionWindow = rebuilt
							prepareWindowAdmission(rebuilt) to cutoff
						}
					}
				},
				requiresTerminalCheckpoint = { prepared ->
					ambientTail == null &&
						prepared.window.payload.lastProviderSequence != window.payload.lastProviderSequence
				},
				onAdmissionResolved = { prepared, handoff ->
					observationsResolved = recordWindowResolution(prepared.window, prepared, handoff, sessionCutoff)
				},
				persistTerminalOutcome = { persistWindowCheckpoint(if (ambientTail == null) window else admissionWindow) },
				awaitNextDrainAttempt = ::awaitNextPressureDrainAttempt,
			)
		} catch (cancelled: CancellationException) {
			recordUnsettledPressurePieces(admissionWindow.takeUnless { observationsResolved }, ambientTail)
			throw cancelled
		} catch (fatal: Error) {
			recordUnsettledPressurePieces(admissionWindow.takeUnless { observationsResolved }, ambientTail)
			throw fatal
		}
		if (resolution == PressureWindowHeadResolution.DEADLINE_UNRESOLVED) {
			recordUnsettledPressurePieces(admissionWindow.takeUnless { observationsResolved }, ambientTail,
				RuntimeGapClassification.DRAIN_TIMED_OUT)
			return resolution
		}
		return settlePressureWindowTail(window, ambientTail)
	}

	private fun recordUnsettledPressurePieces(
		prefix: PressureCompletedWindow?,
		tail: PressureCompletedWindow?,
		classification: RuntimeGapClassification = RuntimeGapClassification.ADMISSION_FAILED,
	) {
		prefix?.let { recordPressureFailureExactly(it.providerSequences(), classification) }
		tail?.let { recordPressureFailureExactly(it.providerSequences(), classification) }
	}

	private suspend fun settlePressureWindowTail(
		window: PressureCompletedWindow,
		ambientTail: PressureCompletedWindow?,
	): PressureWindowHeadResolution {
		// Both pieces remain one FIFO/capacity head. The prefix checkpoints only its own samples;
		// the independently eligible tail must settle before the original head is released.
		val tail = ambientTail ?: return PressureWindowHeadResolution.SETTLED
		val tailResolution = processWindowHead(tail)
		if (tailResolution == PressureWindowHeadResolution.SETTLED) {
			window.checkpointedCapacityGapSequence = tail.checkpointedCapacityGapSequence
		}
		return tailResolution
	}

	private suspend fun pressureRejectionCutoff(
		window: PressureCompletedWindow,
		rejected: SourceDeliveryAdmissionHandoff,
	): Long? {
		if (window.registration.capturePressureMembership() == null) return null
		return when (rejected) {
			is SourceDeliveryAdmissionHandoff.SessionCutoff -> rejected.cutoffElapsedRealtimeNanos
			is SourceDeliveryAdmissionHandoff.TerminalFailure ->
				if (rejected.code == SourceAdmissionFailureCode.SOURCE_POLICY_STALE) endedCaptureCutoff(window) else null
			else -> null
		}
	}

	private suspend fun awaitNextPressureDrainAttempt(expiredDeadline: Long?): Boolean {
		if (expiredDeadline == null) return false
		stopDeadlineElapsedNanos.first { it == null || it > expiredDeadline }
		return true
	}

	private fun localCaptureCutoff(window: PressureCompletedWindow): SessionCutoff? = synchronized(callbackLock) {
		val capture = window.registration.capturePressureMembership() ?: return@synchronized null
		terminalSessionCutoff?.takeIf { cutoff ->
			cutoff.logicalTrackingId == capture.logicalTrackingId &&
				terminalSessionCutoffOwner.samePressureCaptureOwner(capture) &&
				window.registration.state.clockDomainId == capture.effectiveBootId
		}
	}

	private suspend fun admitPressureWindowAfterLocalCutoff(
		prepared: PreparedPressureAdmission,
	): SourceDeliveryAdmissionHandoff {
		val window = prepared.window
		val cutoff = localCaptureCutoff(window)?.elapsedRealtimeNanos
		if (cutoff == null || window.payload.windowEndElapsedRealtimeNanos <= cutoff) {
			return window.sink.admit(prepared.delivery, prepared.checkpoint)
		}
		if (window.payload.windowStartElapsedRealtimeNanos <= cutoff ||
			!window.registration.hasAmbientPressureMembership()) {
			return SourceDeliveryAdmissionHandoff.SessionCutoff(cutoff)
		}
		// Recheck every attempt, including a head prepared before this earlier Stop arrived.
		// This transient hint only denies the old session; ingress still verifies ambient authority.
		val delivery = prepared.delivery.copy(units = prepared.delivery.units.map { unit ->
			unit.copy(excludedPressureCapture = window.registration.capturePressureMembership())
		})
		return window.sink.admit(delivery, prepared.checkpoint)
	}

	private suspend fun prepareWindowAdmission(
		window: PressureCompletedWindow,
	): PreparedPressureAdmission {
		val payload = window.payload
		val reception = window.reception
		val activeRegistration = window.registration
		val delayNanos = (reception.receivedElapsedNanos - payload.windowEndElapsedRealtimeNanos).coerceAtLeast(0L)
		val acquiredAtMs = (reception.receivedWallTimeMs - delayNanos / NANOS_PER_MILLISECOND).coerceAtLeast(0L)
		val candidate = SourceEvidenceCandidate(
			providerDedupKey = null,
			logicalTrackingId = null, serviceRunId = null,
			source = source,
			sourceInstanceId = SourceInstanceId(activeRegistration.state.sourceInstanceId),
			registrationGeneration = activeRegistration.state.registrationGeneration,
			physicalConfigurationFingerprint = activeRegistration.physicalConfigurationFingerprint,
			authorizationRevision = activeRegistration.authorization.authorizationRevision,
			registrationPurposeEligibilityMask = activeRegistration.purposeEligibilityMask,
			registrationEligibilityFingerprint = activeRegistration.eligibilityFingerprint,
			// Room allocates the only durable sequence after replay detection succeeds.
			sourceSequence = 0L, configRevision = activeRegistration.state.appliedRevision,
			planAttribution = if (window.capturePlanApplied) {
				PlanAttribution.CAPTURED_REGISTRATION
			} else {
				PlanAttribution.RECEIVE_TIME_ONLY
			},
			clockDomainId = activeRegistration.state.clockDomainId,
			observedElapsedRealtimeNanos = payload.windowEndElapsedRealtimeNanos,
			receivedElapsedRealtimeNanos = window.checkpointOrderElapsedRealtimeNanos,
			receivedWallTimeMs = reception.receivedWallTimeMs,
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
					observedIntervalStartElapsedRealtimeNanos = payload.windowStartElapsedRealtimeNanos,
				),
			),
		)
		val (absoluteGapMetrics, checkpointedCapacityGapSequence) = pressureCheckpointMetricsSnapshot()
		return PreparedPressureAdmission(
			window = window,
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
			if (PressureWindowQualification.classify(payload) == PressureWindowQualification.PARTIAL) {
				add(SourceQualityFlag.INCOMPLETE_WINDOW)
			}
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

	private suspend fun recordWindowResolution(
		window: PressureCompletedWindow,
		prepared: PreparedPressureAdmission,
		handoff: SourceAdmissionHandoff,
		sessionCutoff: Long?,
	): Boolean = when (handoff) {
		is SourceAdmissionHandoff.Durable -> {
			metrics.recordDurable(window.payload.lastProviderSequence, handoff.admissionOrdinal)
			recordAtomicPressureCheckpointInMemory(window, prepared)
			true
		}
		is SourceAdmissionHandoff.Duplicate -> {
			metrics.recordDurable(window.payload.lastProviderSequence, handoff.existingAdmissionOrdinal)
			recordAtomicPressureCheckpointInMemory(window, prepared)
			true
		}
		is SourceAdmissionHandoff.TerminalFailure -> {
			val outside = (sessionCutoff ?: endedCaptureCutoff(window))?.let {
				window.payload.windowStartElapsedRealtimeNanos > it
			} == true
			if (!outside) recordPressureFailureExactly(window.providerSequences())
			outside
		}
		is SourceAdmissionHandoff.RetryableFailure ->
			error("Retryable Pressure handoff escaped the source-local FIFO loop")
	}

	private fun recordAtomicPressureCheckpointInMemory(
		window: PressureCompletedWindow,
		prepared: PreparedPressureAdmission,
	) {
		lastCheckpointedProviderSequence = window.payload.lastProviderSequence
		lastCheckpointedAdmission = metrics.snapshot()
		window.checkpointedCapacityGapSequence = prepared.checkpointedCapacityGapSequence
	}

	/** Exact original capture identity; ambient membership never borrows a session cutoff. */
	private suspend fun endedCaptureCutoff(window: PressureCompletedWindow): Long? {
		val capture = window.registration.captureOnlyPressureMembership() ?: return null
		val logicalId = requireNotNull(capture.logicalTrackingId)
		val runId = requireNotNull(capture.serviceRunId)
		val localCutoff = localCaptureCutoff(window)?.elapsedRealtimeNanos
		return localCutoff ?: runCatchingNonCancellation {
			registrations.endedCaptureCutoffElapsedNanos(window.registration, logicalId, runId)
		}.getOrNull()
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
				// Keep the lane's terminal reserve outside the buffered replay budget.
				val availableWindows =
					(MAX_PENDING_PRESSURE_WINDOWS - 1 - windowCapacity.pendingWindowCount).coerceAtLeast(0)
				when (refreshLatch.offer(sample, availableWindows)) {
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
					attribution = currentAttribution,
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
				attribution = attribution,
				lifecycle = RuntimeCheckpointLifecycle.ACTIVE,
				terminal = false,
				checkpointOrderElapsedRealtimeNanos = reception.receivedElapsedNanos,
			)
		}
		accumulatorSamples += sample
		lastAccumulatedReception = reception
		if (accumulatorSamples.size == MAX_PRESSURE_SAMPLES_PER_WINDOW) {
			val bounded = requireNotNull(drainPressureWindowAtBoundary(accumulator, reception))
			enqueueWindowLocked(bounded.payload, bounded.reception, attribution,
				RuntimeCheckpointLifecycle.ACTIVE, terminal = false,
				checkpointOrderElapsedRealtimeNanos = reception.receivedElapsedNanos)
			lastAccumulatedReception = null
		}
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
				attribution = attribution,
				lifecycle = RuntimeCheckpointLifecycle.ACTIVE,
				terminal = false,
				checkpointOrderElapsedRealtimeNanos = checkpointOrderElapsedRealtimeNanos,
			)
		}
		lastAccumulatedReception = null
	}

	private fun pausePressureForRefreshOverflowLocked(gapSequence: Long) {
		recordPressureFailureExactly(
			gapSequence..gapSequence,
			RuntimeGapClassification.CALLBACK_BUFFER_OVERFLOW,
		)
		// Closing the predecessor window may already have paused acquisition with a synthetic gap.
		// This callback entered before that pause and must still be accounted for independently.
		if (capacityResumeGate.capacityPaused) return
		acceptingCallbacks = false
		callbackToken?.let(callbackGate::retire)
		capacityPauseListener = listener
		capacityPauseRemovalComplete = false
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
				resolvedAttribution.capturePlanApplied,
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
					attribution = attribution,
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
		attribution: PressureCallbackAttribution,
		lifecycle: RuntimeCheckpointLifecycle,
		terminal: Boolean,
		settlement: CompletableDeferred<Unit>? = null,
		checkpointOrderElapsedRealtimeNanos: Long,
	) {
		val samples = accumulatorSamples.toList()
		check(samples.size == payload.sampleCount &&
			samples.first().reception.providerSequence == payload.firstProviderSequence &&
			samples.last().reception.providerSequence == payload.lastProviderSequence)
		accumulatorSamples.clear()
		val capacity = if (terminal) {
			windowCapacity.terminalWindowEnqueued()
		} else {
			windowCapacity.acquisitionWindowEnqueued()
		}
		check(requireNotNull(windowLane).trySend(
			PressureCompletedWindow(
				payload = payload,
				samples = samples,
				reception = reception,
				registration = attribution.registration,
				sink = attribution.sink,
				capturePlanApplied = attribution.capturePlanApplied,
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
			accumulatorSamples.clear()
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
		terminalSessionCutoff = null
		terminalSessionCutoffOwner = null
		stopDeadlineElapsedNanos.value = null
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
			accumulatorSamples.clear()
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
		const val MAX_PRESSURE_SAMPLES_PER_WINDOW = 1_024
		const val RESUME_LOW_WATER_WINDOWS = 16
	}
}

private fun SourceRegistration.capturePressureMembership(): SourceAuthorizationEntity? =
	authorization.authorizedMembers.singleOrNull { member ->
		member.purpose == SourceBrokerPurpose.SESSION_CAPTURE && member.persistenceEligible &&
			member.logicalTrackingId != null && member.serviceRunId != null
	}

private fun SourceRegistration.hasAmbientPressureMembership(): Boolean =
	authorization.authorizedMembers.any { it.purpose == SourceBrokerPurpose.AMBIENT_PRODUCT && it.persistenceEligible }

private fun SourceRegistration.captureOnlyPressureMembership(): SourceAuthorizationEntity? {
	val members = authorization.authorizedMembers
	val first = members.firstOrNull() ?: return null
	return first.takeIf { first.logicalTrackingId != null && first.serviceRunId != null && members.all { member ->
		val sameRun = member.logicalTrackingId == first.logicalTrackingId && member.serviceRunId == first.serviceRunId
		member.purpose == SourceBrokerPurpose.SESSION_CAPTURE && member.persistenceEligible && sameRun
	} }
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

private enum class PressureProviderRetirement { NOT_DURABLE, PENDING, COMPLETE }

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
	private val minimumWindowNanos: Long,
) {
	private val buffered = ArrayDeque<PendingPressureSample>()
	private var projectedWindowStart: Long? = null
	private var projectedWindowCount = 0
	var boundaryClosed: Boolean = false
		private set
	val bufferedSampleCount: Int get() = buffered.size

	init {
		require(effectiveElapsedRealtimeNanos >= 0L)
		require(maximumBufferedSamples > 0)
		require(minimumWindowNanos > 0L)
	}

	fun shouldBuffer(observedElapsedRealtimeNanos: Long): Boolean =
		observedElapsedRealtimeNanos >= effectiveElapsedRealtimeNanos

	fun markBoundaryClosed(): Boolean {
		if (boundaryClosed) return false
		boundaryClosed = true
		return true
	}

	fun offer(sample: PendingPressureSample, availableWindows: Int): PressureRefreshLatchOffer {
		val observed = sample.reception.observedElapsedNanos
		require(shouldBuffer(observed))
		require(availableWindows >= 0)
		val startsWindow = projectedWindowStart?.let { observed - it >= minimumWindowNanos } ?: true
		val requiredWindows = projectedWindowCount + if (startsWindow) { 1 } else { 0 }
		// The shorter old/new duration bounds replay on both commit and rollback, including its tail.
		if (buffered.size >= maximumBufferedSamples || requiredWindows > availableWindows) {
			return PressureRefreshLatchOffer.OVERFLOW
		}
		if (startsWindow) {
			projectedWindowStart = observed
		}
		projectedWindowCount = requiredWindows
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
	/** Snapshot the applied caller, not a later broker membership or a coinciding revision number. */
	val capturePlanApplied: Boolean = false,
)

/** Compact observed-time authority history; monotonic accepted sensor times prune old entries. */
internal class PressureObservedAuthorizationTimeline(
	initialRegistration: SourceRegistration,
	initialSink: SourceEventSink,
	initialCapturePlanApplied: Boolean = false,
) {
	private val entries = ArrayDeque<PressureCallbackAttribution>().apply {
		addLast(PressureCallbackAttribution(initialRegistration, initialSink,
			capturePlanApplied = initialCapturePlanApplied))
	}

	fun refresh(
		registration: SourceRegistration,
		sink: SourceEventSink,
		effectiveElapsedRealtimeNanos: Long = registration.authorization.effectiveElapsedRealtimeNanos,
		capturePlanApplied: Boolean = false,
	) {
		val next = PressureCallbackAttribution(registration, sink, effectiveElapsedRealtimeNanos, capturePlanApplied)
		val latest = entries.last()
		require(effectiveElapsedRealtimeNanos >= latest.effectiveElapsedRealtimeNanos)
		if (next.samePressureBoundary(latest) &&
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
): Boolean = capturePlanApplied == other.capturePlanApplied &&
	registration.pressureAccumulatorBoundary() == other.registration.pressureAccumulatorBoundary()

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

private fun Int.toPressureSensorAccuracy(): PressureSensorAccuracy = when (this) {
	SensorManager.SENSOR_STATUS_UNRELIABLE -> PressureSensorAccuracy.UNRELIABLE
	SensorManager.SENSOR_STATUS_ACCURACY_LOW -> PressureSensorAccuracy.LOW
	SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> PressureSensorAccuracy.MEDIUM
	SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> PressureSensorAccuracy.HIGH
	else -> PressureSensorAccuracy.UNKNOWN
}

internal data class PressureCompletedWindow(
	val payload: PressureWindowPayload,
	/** Transient originals retained only until this bounded FIFO window settles. */
	val samples: List<PendingPressureSample> = emptyList(),
	val reception: PressureReception,
	val registration: SourceRegistration,
	val sink: SourceEventSink,
	val lifecycle: RuntimeCheckpointLifecycle,
	val capturePlanApplied: Boolean = false,
	val settlement: CompletableDeferred<Unit>? = null,
	val checkpointOrderElapsedRealtimeNanos: Long = reception.receivedElapsedNanos,
) {
	@Volatile
	var checkpointedCapacityGapSequence: Long? = null

	fun providerSequences(): LongRange =
		payload.firstProviderSequence..payload.lastProviderSequence

	/** Rebuild only actual retained capture samples; target cadence stays descriptive, never invented. */
	fun prefixThrough(cutoffElapsedNanos: Long): PressureCompletedWindow? {
		if (registration.capturePressureMembership() == null || samples.size != payload.sampleCount ||
			payload.windowEndElapsedRealtimeNanos <= cutoffElapsedNanos) {
			return null
		}
		val prefix = samples.takeWhile { it.reception.observedElapsedNanos <= cutoffElapsedNanos }
		if (prefix.isEmpty() || prefix.size == samples.size) {
			return null
		}
		return rebuildPressureWindow(prefix)
	}

	fun suffixAfter(cutoffElapsedNanos: Long): PressureCompletedWindow? {
		if (samples.size != payload.sampleCount) {
			return null
		}
		val suffix = samples.dropWhile { it.reception.observedElapsedNanos <= cutoffElapsedNanos }
		return suffix.takeIf { it.isNotEmpty() && it.size < samples.size }?.let { rebuildPressureWindow(it) }
	}

	/** Rebuild both still-uncommitted pieces from this original window, including its ambient middle. */
	fun splitForCaptureCutoff(cutoffElapsedNanos: Long): Pair<PressureCompletedWindow, PressureCompletedWindow?>? {
		val prefix = prefixThrough(cutoffElapsedNanos)
		val ambient = registration.hasAmbientPressureMembership()
		if (prefix != null) {
			val tail = if (ambient) {
				suffixAfter(cutoffElapsedNanos)
			} else {
				null
			}
			return prefix to tail
		}
		return takeIf { payload.windowStartElapsedRealtimeNanos > cutoffElapsedNanos && ambient }
			?.let { it to null }
	}

	private fun rebuildPressureWindow(retained: List<PendingPressureSample>): PressureCompletedWindow {
		val rebuilt = PressureWindowAccumulator(
			windowNanos = requireNotNull(payload.targetWindowDurationNanos),
			effectiveSamplePeriodMicros = requireNotNull(payload.effectiveSamplePeriodMicros),
			effectiveMaximumReportLatencyMicros = requireNotNull(payload.effectiveMaximumReportLatencyMicros),
			boundary = registration.pressureAccumulatorBoundary(),
		)
		retained.forEach { sample ->
			check(rebuilt.add(sample.pressureHectopascals, sample.reception.observedElapsedNanos,
				sample.reception.providerSequence, sample.sensorAccuracy) == null)
		}
		return copy(payload = requireNotNull(rebuilt.drain(PressureWindowClosureKind.SOURCE_BOUNDARY)),
			samples = retained, reception = retained.last().reception)
	}
}

internal data class PreparedPressureAdmission(
	val window: PressureCompletedWindow,
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
 * checkpoint. Strictly earlier cutoffs may replace a still-uncommitted head; the existing standalone
 * checkpoint also acknowledges its intentionally omitted post-End tail without re-admission.
 */
internal suspend fun <Prepared> processPressureWindowHead(
	deadlineElapsedRealtimeNanos: () -> Long?,
	nowElapsedRealtimeNanos: () -> Long,
	prepare: suspend () -> Prepared?,
	admit: suspend (Prepared) -> SourceDeliveryAdmissionHandoff,
	onAdmissionResolved: suspend (Prepared, SourceAdmissionHandoff) -> Unit,
	persistTerminalOutcome: suspend () -> Boolean,
	retryDelaysMs: LongArray = PRESSURE_HEAD_RETRY_DELAYS_MS,
	waitBeforeRetry: suspend (Long) -> Unit = { delay(it) },
	awaitNextDrainAttempt: suspend (Long?) -> Boolean = { false },
	rebuildAfterRejection: suspend (Prepared, SourceDeliveryAdmissionHandoff) ->
		Pair<Prepared, Long>? = { _, _ -> null },
	requiresTerminalCheckpoint: (Prepared) -> Boolean = { false },
): PressureWindowHeadResolution {
	require(retryDelaysMs.isNotEmpty())
	var retryIndex = 0
	var prepared: Prepared? = null
	var terminalOutcomeRecorded = false
	var rebuiltCutoff: Long? = null
	suspend fun awaitRetry(deadline: Long?): Boolean {
		val retryDelay = retryDelaysMs[retryIndex.coerceAtMost(retryDelaysMs.lastIndex)]
		retryIndex++
		return waitForPressureRetry(deadline, nowElapsedRealtimeNanos, retryDelay, waitBeforeRetry) ||
			awaitNextDrainAttempt(deadline)
	}
	while (true) {
		val deadline = deadlineElapsedRealtimeNanos()
		if (deadline != null && nowElapsedRealtimeNanos() >= deadline) {
			if (awaitNextDrainAttempt(deadline)) continue
			return PressureWindowHeadResolution.DEADLINE_UNRESOLVED
		}
		if (prepared == null) {
			prepared = prepare()
			if (prepared == null) {
				if (!awaitRetry(deadline)) return PressureWindowHeadResolution.DEADLINE_UNRESOLVED
				continue
			}
		}
		if (!terminalOutcomeRecorded) {
			val exactPreparedHead = checkNotNull(prepared)
			val deliveryHandoff = admit(exactPreparedHead)
			val handoff = deliveryHandoff.toPressureWindowHandoff()
			if (handoff is SourceAdmissionHandoff.RetryableFailure) {
				if (!awaitRetry(deadline)) return PressureWindowHeadResolution.DEADLINE_UNRESOLVED
				continue
			}
			val rejectedCutoff = (deliveryHandoff as? SourceDeliveryAdmissionHandoff.SessionCutoff)
				?.cutoffElapsedRealtimeNanos
			val previousCutoff = rebuiltCutoff
			val mayRebuild = previousCutoff == null || rejectedCutoff == null || rejectedCutoff < previousCutoff
			if (handoff is SourceAdmissionHandoff.TerminalFailure && mayRebuild) {
				val rebuilt = rebuildAfterRejection(exactPreparedHead, deliveryHandoff)
				if (rebuilt != null && (previousCutoff == null || rebuilt.second < previousCutoff)) {
					prepared = rebuilt.first
					rebuiltCutoff = rebuilt.second
					continue
				}
			}
			onAdmissionResolved(exactPreparedHead, handoff)
			when (handoff) {
				is SourceAdmissionHandoff.Durable,
				is SourceAdmissionHandoff.Duplicate,
				-> if (!requiresTerminalCheckpoint(exactPreparedHead)) return PressureWindowHeadResolution.SETTLED
				is SourceAdmissionHandoff.TerminalFailure -> Unit
				is SourceAdmissionHandoff.RetryableFailure -> error("Retryable Pressure handoff escaped its retry branch")
			}
			terminalOutcomeRecorded = true
		}
		if (persistTerminalOutcome()) return PressureWindowHeadResolution.SETTLED
		if (!awaitRetry(deadline)) return PressureWindowHeadResolution.DEADLINE_UNRESOLVED
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

	/** Every callback after the admitted prefix must have an exact recorded disposition. */
	fun coversAfter(checkpointedSequence: Long, barrierSequence: Long): Boolean {
		require(checkpointedSequence >= 0L && barrierSequence >= 0L)
		return synchronized(lock) {
			checkpointedSequence >= barrierSequence ||
				complementLocked((checkpointedSequence + 1L)..barrierSequence).isEmpty()
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
		return pressureRecoveryWithoutCheckpoint(reusedActiveRegistration)
	}
	val sameGeneration = saved?.registrationGeneration == currentRegistrationGeneration
	// A replacement listener has a fresh sequence domain. Its predecessor's durable history and
	// retirement keep any real loss; it must not start this new generation with invented loss.
	if (!sameGeneration) {
		if (reusedActiveRegistration) {
			return pressureRecoveryWithoutCheckpoint(reusedActiveRegistration = true)
		}
		// Install this accepted generation's empty checkpoint before draining its buffered callbacks.
		return PressureRuntimeRecovery(RuntimeAdmissionMetrics().snapshot(), 0L,
			requiresPreAcquisitionCheckpoint = true)
	}
	val lastSequence = pressureRecoveryProviderHighWater(requireNotNull(saved).lastProviderSequence, checkpoint.metrics)
	if (checkpoint.lifecycle != RuntimeCheckpointLifecycle.ACTIVE) {
		return PressureRuntimeRecovery(
			metrics = checkpoint.metrics,
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
	)
	restarted.recordFailure(restartGapSequence, classification = RuntimeGapClassification.PROCESS_RESTARTED)
	return PressureRuntimeRecovery(
		restarted.snapshot(),
		restartGapSequence,
		requiresPreAcquisitionCheckpoint = true,
	)
}

private fun pressureRecoveryWithoutCheckpoint(reusedActiveRegistration: Boolean): PressureRuntimeRecovery {
	if (!reusedActiveRegistration) {
		return PressureRuntimeRecovery(null, 0L)
	}
	val restarted = RuntimeAdmissionMetrics().also {
		it.recordFailure(1L, classification = RuntimeGapClassification.PROCESS_RESTARTED)
	}
	return PressureRuntimeRecovery(restarted.snapshot(), 1L, requiresPreAcquisitionCheckpoint = true)
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
