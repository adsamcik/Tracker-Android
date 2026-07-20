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
import com.adsamcik.tracker.tracker.controller.TrackerServiceController
import com.adsamcik.tracker.tracker.pipeline.ProcessorPipeline
import com.adsamcik.tracker.tracker.policy.PolicyIntervalMapper
import com.adsamcik.tracker.tracker.policy.PolicyTierMapper
import com.adsamcik.tracker.tracker.policy.TrackingPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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
			if (gpsTimer.hasRequiredPermissions(context)) {
				configureLocationRequestFidelity(gpsTimer, requestFidelity)
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
