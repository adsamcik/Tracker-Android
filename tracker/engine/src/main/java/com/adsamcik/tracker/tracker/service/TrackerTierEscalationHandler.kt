package com.adsamcik.tracker.tracker.service

import android.content.Context
import com.adsamcik.tracker.logger.Reporter
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsRepository
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.stats.api.value.EpochMs
import com.adsamcik.tracker.tracker.component.CollectionTriggerComponent
import com.adsamcik.tracker.tracker.component.DynamicIntervalCollectionTrigger
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

		scope.launch {
			componentMutex.withLock {
				val oldTier = currentTier
				val triggerTransition = prepareTriggerTransition(
					newTier = newTier,
					context = context,
					timerReceiver = timerReceiver,
				)
				if (triggerTransition == TriggerTransition.Failed) {
					return@withLock
				}

				try {
					if (newTier != oldTier) {
						processorPipeline?.escalate(newTier, EpochMs(Time.nowMillis))
					}
					triggerTransition.commit(context, timerAccessor)
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

	private suspend fun prepareTriggerTransition(
		newTier: PolicyTier,
		context: Context,
		timerReceiver: TrackerTimerReceiver,
	): TriggerTransition {
		val locationEnabled = trackingParamsRepository.data.first().locationEnabled
		val shouldUseGps = newTier.isGpsEnabled && locationEnabled
		val currentlyUsesGps = timerAccessor.get().isLocationTrigger

		return when {
			shouldUseGps && !currentlyUsesGps -> prepareGpsTrigger(context, timerReceiver)
			!shouldUseGps && currentlyUsesGps -> prepareAmbientTrigger(context, timerReceiver)
			else -> TriggerTransition.NotNeeded
		}
	}

	private suspend fun prepareGpsTrigger(
		context: Context,
		timerReceiver: TrackerTimerReceiver,
	): TriggerTransition {
		val oldTimer = timerAccessor.get()
		val gpsTimer = gpsTriggerFactory(context)
		return if (gpsTimer.hasRequiredPermissions(context)) {
			gpsTimer.onEnable(context, timerReceiver)
			TriggerTransition.Ready(oldTimer, gpsTimer)
		} else {
			Reporter.report("Missing permissions for GPS timer during escalation")
			TriggerTransition.Failed
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
		) : TriggerTransition {
			override fun commit(context: Context, timerAccessor: TimerAccessor) {
				oldTimer.onDisable(context)
				timerAccessor.set(newTimer)
			}

			override fun rollback(context: Context) {
				newTimer.onDisable(context)
			}
		}
	}
}
