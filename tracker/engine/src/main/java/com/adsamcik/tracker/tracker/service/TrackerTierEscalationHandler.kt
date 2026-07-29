package com.adsamcik.tracker.tracker.service

import android.content.Context
import com.adsamcik.tracker.logger.Reporter
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsRepository
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.stats.api.value.EpochMs
import com.adsamcik.tracker.tracker.component.AdaptiveLocationCollectionTrigger
import com.adsamcik.tracker.tracker.component.CollectionTriggerComponent
import com.adsamcik.tracker.tracker.component.DynamicIntervalCollectionTrigger
import com.adsamcik.tracker.tracker.component.LocationRequestFidelity
import com.adsamcik.tracker.tracker.component.TrackerTimerReceiver
import com.adsamcik.tracker.tracker.control.AcquisitionRequest
import com.adsamcik.tracker.tracker.control.ControlAcquisitionApplyOutcome
import com.adsamcik.tracker.tracker.control.ControlAcquisitionApplyResult
import com.adsamcik.tracker.tracker.control.ControlAcquisitionCommand
import com.adsamcik.tracker.tracker.control.ControlAcquisitionCommandOrigin
import com.adsamcik.tracker.tracker.control.LocationAcquisitionMode as DecisionAcquisitionMode
import com.adsamcik.tracker.tracker.control.NoOpTrackingControlAcquisitionOutcomeSink
import com.adsamcik.tracker.tracker.control.TrackingControlAcquisitionOutcomeSink
import com.adsamcik.tracker.tracker.controller.TrackerServiceController
import com.adsamcik.tracker.tracker.data.TrackingClockDomain
import com.adsamcik.tracker.tracker.pipeline.ProcessorPipeline
import com.adsamcik.tracker.tracker.policy.PolicyIntervalMapper
import com.adsamcik.tracker.tracker.policy.PolicyTierMapper
import com.adsamcik.tracker.tracker.policy.TrackingPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Handles effective-tier changes between ambient and GPS-capable collection.
 *
 * Manages timer interval updates when the [TrackingPolicy] changes, and performs a timer swap
 * timer swap when crossing the GPS boundary in either direction. GPS is used only when the
 * effective tier is GPS-capable and the user enabled location. Producers and data components for
 * every enabled source are built up-front by toggle (see [TrackerComponentFactory]), so tier changes
 * no longer mutate component or producer lists.
 *
 * Extracted from [TrackerService] to isolate tier-escalation concerns.
 */
internal class TrackerTierEscalationHandler(
	private val componentMutex: Mutex,
	private val controller: TrackerServiceController,
	private val trackingParamsRepository: TrackingParamsRepository,
	private val tierAdjuster: (PolicyTier) -> PolicyTier = { it },
	private val gpsTriggerFactory: suspend (Context) -> CollectionTriggerComponent,
	private val ambientTriggerFactory: () -> CollectionTriggerComponent,
	private val foregroundServiceTypeUpdater: (Boolean, Boolean, Boolean) -> Boolean =
		{ _, _, _ -> true },
	private val onEffectiveTierChanged: (PolicyTier) -> Unit = {},
) {

	/**
	 * Mutable references managed by this handler.
	 * These are backed by the service's own fields and updated via callbacks.
	 */
	var currentTier: PolicyTier = PolicyTier.PRECISION

	/**
	 * Callback to swap the timer component in the service.
	 * Set by the service after construction.
	 */
	lateinit var timerAccessor: TimerAccessor

	var processorPipeline: ProcessorPipeline? = null

	/** Last feature-gated control request applied to a platform trigger, separate from legacy tier. */
	private var appliedControlRequest: AcquisitionRequest? = null
	private var lastCompletedControlRequestId: String? = null
	private var controlProbeExpiryJob: Job? = null

	/**
	 * Called when the tracking policy changes. Applies runtime caps, updates the timer interval,
	 * and swaps collection triggers when the effective tier crosses the GPS boundary.
	 */
	fun onPolicyChanged(
		policy: TrackingPolicy,
		context: Context,
		timerReceiver: TrackerTimerReceiver,
		scope: CoroutineScope,
	) {
		val requestedTier = PolicyTierMapper.toTier(policy)
		val newTier = tierAdjuster(requestedTier)
		val intervalPolicy = if (newTier < requestedTier) {
			PolicyTierMapper.toTrackingPolicy(newTier)
		} else {
			policy
		}
		val requestFidelity = intervalPolicy.toLocationRequestFidelity()

		scope.launch {
			componentMutex.withLock {
				val oldTier = currentTier
				val params = trackingParamsRepository.data.first()
				val shouldUseGps = newTier.isGpsEnabled && params.locationEnabled
				val requiresHealth = params.activityEnabled || params.stepsEnabled
				val currentlyUsesGps = timerAccessor.get().isLocationTrigger
				val triggerTransition = prepareTriggerTransition(
					shouldUseGps = shouldUseGps,
					requiresHealth = requiresHealth,
					context = context,
					timerReceiver = timerReceiver,
					requestFidelity = requestFidelity,
				)
				if (triggerTransition == TriggerTransition.Failed) {
					return@withLock
				}
				if (
					triggerTransition == TriggerTransition.NotNeeded &&
					!foregroundServiceTypeUpdater(
						shouldUseGps,
						requiresHealth,
						true,
					)
				) {
					return@withLock
				}

				try {
					if (newTier != oldTier) {
						processorPipeline?.escalate(newTier, EpochMs(Time.nowMillis))
					}
					if (triggerTransition == TriggerTransition.NotNeeded) {
						configureLocationRequestFidelity(timerAccessor.get(), requestFidelity)
					}
					triggerTransition.commit(context, timerAccessor)
					if (
						!shouldUseGps &&
						currentlyUsesGps &&
						!foregroundServiceTypeUpdater(false, requiresHealth, true)
					) {
						return@withLock
					}
				} catch (e: CancellationException) {
					triggerTransition.rollback(context)
					throw e
				} catch (e: Exception) {
					triggerTransition.rollback(context)
					Reporter.report(e)
					return@withLock
				}

				if (newTier != oldTier) {
					currentTier = newTier
					onEffectiveTierChanged(newTier)
					controller.updatePolicyTier(newTier)
				}
				updateTimerInterval(context, intervalPolicy)
			}
		}
	}

	/**
	 * Configures a trigger created before the policy observer starts.
	 *
	 * The service calls this during initialization, before enabling the trigger, so even the first
	 * fused request follows the balanced-first policy.
	 */
	fun configureCurrentLocationRequest(policy: TrackingPolicy) {
		val requestedTier = PolicyTierMapper.toTier(policy)
		val effectiveTier = tierAdjuster(requestedTier)
		val effectivePolicy = if (effectiveTier < requestedTier) {
			PolicyTierMapper.toTrackingPolicy(effectiveTier)
		} else {
			policy
		}
		configureLocationRequestFidelity(
			timerAccessor.get(),
			effectivePolicy.toLocationRequestFidelity(),
		)
	}

	/**
	 * Retains the legacy policy-tier effects while an experimental acquisition controller owns the
	 * physical request. Unlike [onPolicyChanged], this deliberately does not touch the trigger,
	 * its fidelity/cadence, or foreground-service type.
	 *
	 * Keeping this separate avoids a transient legacy location request racing the controller while
	 * still keeping processor activation, UI state, and raw-signal tier attribution correct.
	 */
	fun onPolicyTierChangedWithoutAcquisition(
		policy: TrackingPolicy,
		scope: CoroutineScope,
	) {
		val requestedTier = PolicyTierMapper.toTier(policy)
		val newTier = tierAdjuster(requestedTier)
		scope.launch {
			componentMutex.withLock {
				val oldTier = currentTier
				if (newTier == oldTier) return@withLock
				try {
					processorPipeline?.escalate(newTier, EpochMs(Time.nowMillis))
				} catch (e: CancellationException) {
					throw e
				} catch (e: Exception) {
					Reporter.report(e)
					return@withLock
				}
				currentTier = newTier
				onEffectiveTierChanged(newTier)
				controller.updatePolicyTier(newTier)
			}
		}
	}

	/**
	 * Applies a request produced by the independent acquisition reducer.
	 *
	 * This is called only when [TrackingDecisionFeatureFlags.applyAcquisitionRequests] is enabled;
	 * legacy policy/tier handling remains untouched otherwise. It deliberately does not mutate the
	 * legacy [currentTier] or processor pipeline tier, allowing a fast rollback to the old request
	 * owner while retaining complete shadow evidence.
	 */
	fun onControlAcquisitionRequest(
		command: ControlAcquisitionCommand,
		context: Context,
		timerReceiver: TrackerTimerReceiver,
		scope: CoroutineScope,
		outcomeSink: TrackingControlAcquisitionOutcomeSink = NoOpTrackingControlAcquisitionOutcomeSink,
	) {
		val request = command.request
		scope.launch {
			componentMutex.withLock {
				if (lastCompletedControlRequestId == command.requestId) return@withLock
				appliedControlRequest?.takeIf { it.samePlatformShapeAs(request) }?.let { applied ->
					completeControlRequest(
						command = command,
						outcome = ControlAcquisitionApplyOutcome.NO_CHANGE,
						applied = applied,
						reason = "PLATFORM_SHAPE_ALREADY_APPLIED",
						outcomeSink = outcomeSink,
					)
					return@withLock
				}
				val params = trackingParamsRepository.data.first()
				val shouldUseLocation = request.mode != DecisionAcquisitionMode.DISABLED
				val requiresHealth = params.activityEnabled || params.stepsEnabled
				val currentlyUsesLocation = timerAccessor.get().isLocationTrigger
				val transition = prepareTriggerTransition(
					shouldUseGps = shouldUseLocation,
					requiresHealth = requiresHealth,
					context = context,
					timerReceiver = timerReceiver,
					requestFidelity = request.toLocationRequestFidelity(),
				)
				if (transition == TriggerTransition.Failed) {
					completeControlRequest(
						command = command,
						outcome = ControlAcquisitionApplyOutcome.FAILED,
						reason = "TRIGGER_PREPARATION_FAILED",
						outcomeSink = outcomeSink,
					)
					return@withLock
				}
				if (
					transition == TriggerTransition.NotNeeded &&
					!foregroundServiceTypeUpdater(shouldUseLocation, requiresHealth, true)
				) {
					completeControlRequest(
						command = command,
						outcome = ControlAcquisitionApplyOutcome.FAILED,
						reason = "FOREGROUND_SERVICE_TYPE_UPDATE_FAILED",
						outcomeSink = outcomeSink,
					)
					return@withLock
				}

				try {
					if (transition == TriggerTransition.NotNeeded) {
						configureLocationRequestFidelity(timerAccessor.get(), request.toLocationRequestFidelity())
					}
					transition.commit(context, timerAccessor)
					if (!shouldUseLocation && currentlyUsesLocation &&
						!foregroundServiceTypeUpdater(false, requiresHealth, true)
					) {
						// The trigger transition has already committed, so rolling back here would leave
						// neither timer reliably enabled. Record the actual applied request with an
						// explicit degraded FGS outcome instead of claiming the platform request failed.
						Reporter.report("Foreground-service type removal failed after control de-escalation")
						appliedControlRequest = request
						completeControlRequest(
							command = command,
							outcome = ControlAcquisitionApplyOutcome.APPLIED,
							applied = request,
							reason = "PLATFORM_REQUEST_APPLIED_FGS_TYPE_REMOVAL_FAILED",
							outcomeSink = outcomeSink,
						)
						scheduleProbeExpiryIfNeeded(command, context, timerReceiver, scope, outcomeSink)
						return@withLock
					}
					updateTimerInterval(
						context = context,
						intervalMs = request.intervalMs,
						minDistanceMeters = request.minDistanceMeters,
					)
				} catch (e: CancellationException) {
					transition.rollback(context)
					throw e
				} catch (e: Exception) {
					transition.rollback(context)
					Reporter.report(e)
					completeControlRequest(
						command = command,
						outcome = ControlAcquisitionApplyOutcome.FAILED,
						reason = "PLATFORM_APPLY_EXCEPTION:${e.javaClass.simpleName}",
						outcomeSink = outcomeSink,
					)
					return@withLock
				}

				appliedControlRequest = request
				completeControlRequest(
					command = command,
					outcome = ControlAcquisitionApplyOutcome.APPLIED,
					applied = request,
					reason = "PLATFORM_REQUEST_APPLIED",
					outcomeSink = outcomeSink,
				)
				scheduleProbeExpiryIfNeeded(command, context, timerReceiver, scope, outcomeSink)
			}
		}
	}

	private fun completeControlRequest(
		command: ControlAcquisitionCommand,
		outcome: ControlAcquisitionApplyOutcome,
		applied: AcquisitionRequest? = null,
		reason: String,
		outcomeSink: TrackingControlAcquisitionOutcomeSink,
	) {
		lastCompletedControlRequestId = command.requestId
		try {
			outcomeSink.onAcquisitionOutcome(
				ControlAcquisitionApplyResult(
					command = command,
					outcome = outcome,
					applied = applied,
					reason = reason,
					eventEpochMs = Time.nowMillis.coerceAtLeast(0L),
					eventElapsedNanos = Time.elapsedRealtimeNanos.coerceAtLeast(0L),
					clockDomainId = TrackingClockDomain.currentId(),
				),
			)
		} catch (e: Exception) {
			// Research instrumentation must never change whether the physical request succeeds.
			Reporter.report(e)
		}
	}

	private suspend fun prepareTriggerTransition(
		shouldUseGps: Boolean,
		requiresHealth: Boolean,
		context: Context,
		timerReceiver: TrackerTimerReceiver,
		requestFidelity: LocationRequestFidelity,
	): TriggerTransition {
		val currentlyUsesGps = timerAccessor.get().isLocationTrigger

		return when {
			shouldUseGps && !currentlyUsesGps ->
				prepareGpsTrigger(context, timerReceiver, requiresHealth, requestFidelity)
			!shouldUseGps && currentlyUsesGps -> prepareAmbientTrigger(context, timerReceiver)
			else -> TriggerTransition.NotNeeded
		}
	}

	private suspend fun prepareGpsTrigger(
		context: Context,
		timerReceiver: TrackerTimerReceiver,
		requiresHealth: Boolean,
		requestFidelity: LocationRequestFidelity,
	): TriggerTransition {
		if (!foregroundServiceTypeUpdater(true, requiresHealth, false)) {
			return TriggerTransition.Failed
		}
		val oldTimer = timerAccessor.get()
		var prepared = false
		return try {
			val gpsTimer = gpsTriggerFactory(context)
			// Native passive/low-power modes accept coarse permission, whereas its default high
			// fidelity requires fine permission. Configure before the permission check.
			configureLocationRequestFidelity(gpsTimer, requestFidelity)
			if (gpsTimer.hasRequiredPermissions(context)) {
				gpsTimer.onEnable(context, timerReceiver)
				prepared = true
				TriggerTransition.Ready(
					oldTimer = oldTimer,
					newTimer = gpsTimer,
					onRollback = {
						foregroundServiceTypeUpdater(false, requiresHealth, true)
					},
				)
			} else {
				Reporter.report("Missing permissions for GPS timer during escalation")
				TriggerTransition.Failed
			}
		} finally {
			if (!prepared) {
				foregroundServiceTypeUpdater(false, requiresHealth, true)
			}
		}
	}

	private fun prepareAmbientTrigger(
		context: Context,
		timerReceiver: TrackerTimerReceiver,
	): TriggerTransition {
		val oldTimer = timerAccessor.get()
		val ambientTimer = ambientTriggerFactory()
		return if (ambientTimer.hasRequiredPermissions(context)) {
			ambientTimer.onEnable(context, timerReceiver)
			TriggerTransition.Ready(oldTimer, ambientTimer)
		} else {
			Reporter.report("Missing permissions for ambient timer during de-escalation")
			TriggerTransition.Failed
		}
	}

	private suspend fun updateTimerInterval(context: Context, policy: TrackingPolicy) {
		val timer = timerAccessor.get()
		if (timer !is DynamicIntervalCollectionTrigger) return

		val params = trackingParamsRepository.data.first()
		val intervalSeconds = PolicyIntervalMapper.getIntervalSeconds(policy, params)
		val minDistanceMeters = PolicyIntervalMapper.getMinDistanceMeters(policy, params)
		timer.updateInterval(context, intervalSeconds, minDistanceMeters)
	}

	private fun updateTimerInterval(
		context: Context,
		intervalMs: Long,
		minDistanceMeters: Int,
	) {
		val timer = timerAccessor.get()
		if (timer !is DynamicIntervalCollectionTrigger) return
		val seconds = ((intervalMs.coerceAtLeast(1L) + 999L) / 1_000L)
			.coerceAtMost(Int.MAX_VALUE.toLong())
			.toInt()
		timer.updateInterval(context, seconds, minDistanceMeters.coerceAtLeast(0))
	}

	private fun configureLocationRequestFidelity(
		trigger: CollectionTriggerComponent,
		fidelity: LocationRequestFidelity,
	) {
		(trigger as? AdaptiveLocationCollectionTrigger)?.updateRequestFidelity(fidelity)
	}

	private fun TrackingPolicy.toLocationRequestFidelity(): LocationRequestFidelity = when (this) {
		TrackingPolicy.ACTIVE_ELEVATED,
		TrackingPolicy.USER_INITIATED,
		-> LocationRequestFidelity.HIGH_ACCURACY
		TrackingPolicy.PASSIVE_LOW,
		TrackingPolicy.MOVEMENT_SUSPECTED,
		TrackingPolicy.ACTIVE_MODERATE,
		-> LocationRequestFidelity.BALANCED
	}

	private fun AcquisitionRequest.toLocationRequestFidelity(): LocationRequestFidelity = when (mode) {
		DecisionAcquisitionMode.DISABLED -> LocationRequestFidelity.DISABLED
		DecisionAcquisitionMode.PASSIVE -> LocationRequestFidelity.PASSIVE
		DecisionAcquisitionMode.LOW_POWER -> LocationRequestFidelity.LOW_POWER
		DecisionAcquisitionMode.BALANCED -> LocationRequestFidelity.BALANCED
		DecisionAcquisitionMode.HIGH_ACCURACY -> LocationRequestFidelity.HIGH_ACCURACY
		DecisionAcquisitionMode.PROBE -> LocationRequestFidelity.PROBE
	}

	private fun AcquisitionRequest.samePlatformShapeAs(other: AcquisitionRequest): Boolean =
		mode == other.mode && intervalMs == other.intervalMs &&
			minDistanceMeters == other.minDistanceMeters && probeDurationMs == other.probeDurationMs

	private fun scheduleProbeExpiryIfNeeded(
		command: ControlAcquisitionCommand,
		context: Context,
		timerReceiver: TrackerTimerReceiver,
		scope: CoroutineScope,
		outcomeSink: TrackingControlAcquisitionOutcomeSink,
	) {
		val request = command.request
		if (request.mode != DecisionAcquisitionMode.PROBE) {
			controlProbeExpiryJob?.cancel()
			controlProbeExpiryJob = null
			return
		}
		controlProbeExpiryJob?.cancel()
		val durationMs = requireNotNull(request.probeDurationMs)
		controlProbeExpiryJob = scope.launch {
			delay(durationMs)
			// Clear our own reference before applying the fallback so the fallback does not cancel
			// the currently executing coroutine at its deadline boundary.
			controlProbeExpiryJob = null
			onControlAcquisitionRequest(
				command = ControlAcquisitionCommand(
					requestId = "${command.requestId}:adapter-probe-deadline",
					logicalTrackingId = command.logicalTrackingId,
					decisionLedgerSequence = command.decisionLedgerSequence,
					request = AcquisitionRequest(
						mode = DecisionAcquisitionMode.PASSIVE,
						intervalMs = 60_000L,
						minDistanceMeters = 0,
						reason = "ADAPTER_PROBE_DEADLINE",
					),
					decisionEpochMs = Time.nowMillis.coerceAtLeast(0L),
					decisionElapsedNanos = Time.elapsedRealtimeNanos.coerceAtLeast(0L),
					clockDomainId = TrackingClockDomain.currentId(),
					origin = ControlAcquisitionCommandOrigin.ADAPTER_PROBE_DEADLINE,
				),
				context = context,
				timerReceiver = timerReceiver,
				scope = scope,
				outcomeSink = outcomeSink,
			)
		}
	}

	/**
	 * Provides get/set access to the timer component owned by the service.
	 */
	internal interface TimerAccessor {
		fun get(): CollectionTriggerComponent
		fun set(timer: CollectionTriggerComponent)
	}

	private sealed interface TriggerTransition {
		fun commit(context: Context, timerAccessor: TimerAccessor) = Unit
		fun rollback(context: Context) = Unit

		data object NotNeeded : TriggerTransition
		data object Failed : TriggerTransition

		data class Ready(
			private val oldTimer: CollectionTriggerComponent,
			private val newTimer: CollectionTriggerComponent,
			private val onRollback: () -> Unit = {},
		) : TriggerTransition {
			override fun commit(context: Context, timerAccessor: TimerAccessor) {
				oldTimer.onDisable(context)
				timerAccessor.set(newTimer)
			}

			override fun rollback(context: Context) {
				newTimer.onDisable(context)
				onRollback()
			}
		}
	}
}
