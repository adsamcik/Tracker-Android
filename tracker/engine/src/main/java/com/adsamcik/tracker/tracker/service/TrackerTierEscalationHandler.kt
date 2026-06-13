package com.adsamcik.tracker.tracker.service

import android.content.Context
import com.adsamcik.tracker.logger.Reporter
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsRepository
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.stats.api.value.EpochMs
import com.adsamcik.tracker.tracker.component.CollectionTriggerComponent
import com.adsamcik.tracker.tracker.component.DynamicIntervalCollectionTrigger
import com.adsamcik.tracker.tracker.component.TrackerTimerManager
import com.adsamcik.tracker.tracker.component.TrackerTimerReceiver
import com.adsamcik.tracker.tracker.controller.TrackerServiceController
import com.adsamcik.tracker.tracker.pipeline.ProcessorPipeline
import com.adsamcik.tracker.tracker.policy.PolicyIntervalMapper
import com.adsamcik.tracker.tracker.policy.PolicyTierMapper
import com.adsamcik.tracker.tracker.policy.TrackingPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Handles tier changes and escalation from AMBIENT to ACTIVE/PRECISION.
 *
 * Manages timer interval updates when the [TrackingPolicy] changes, and performs a timer swap
 * (ambient → GPS) when escalating from a non-GPS tier to a GPS-enabled tier — but only when the
 * user actually enabled location. Producers and data components for every enabled source are built
 * up-front by toggle (see [TrackerComponentFactory]), so escalation no longer mutates component or
 * producer lists; it only changes the collection trigger and escalates the stats pipeline.
 *
 * Extracted from [TrackerService] to isolate tier-escalation concerns.
 */
internal class TrackerTierEscalationHandler(
	private val componentMutex: Mutex,
	private val controller: TrackerServiceController,
	private val trackingParamsRepository: TrackingParamsRepository,
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
	 * Called when the tracking policy changes. Updates the timer interval and
	 * triggers a tier escalation if the tier boundary crosses from
	 * non-GPS to GPS-enabled.
	 */
	fun onPolicyChanged(
		policy: TrackingPolicy,
		context: Context,
		timerReceiver: TrackerTimerReceiver,
		scope: CoroutineScope,
	) {
		val newTier = PolicyTierMapper.toTier(policy)
		val oldTier = currentTier

		if (newTier != oldTier) {
			currentTier = newTier
			controller.updatePolicyTier(newTier)

			if (!oldTier.isGpsEnabled && newTier.isGpsEnabled) {
				onTierEscalation(newTier, context, timerReceiver, scope)
			}
		}

		val timer = timerAccessor.get()
		if (timer !is DynamicIntervalCollectionTrigger) {
			return
		}

		val intervalSeconds = PolicyIntervalMapper.getIntervalSeconds(policy)
		val minDistanceMeters = PolicyIntervalMapper.getMinDistanceMeters(policy)
		timer.updateInterval(context, intervalSeconds, minDistanceMeters)
	}

	private fun onTierEscalation(
		newTier: PolicyTier,
		context: Context,
		timerReceiver: TrackerTimerReceiver,
		scope: CoroutineScope,
	) {
		scope.launch {
			componentMutex.withLock {
				// Only escalate to a GPS trigger when the user wants location. Without location we
				// keep the lightweight non-GPS trigger so Wi-Fi/cell/activity/step cycles keep
				// firing at the ambient cadence. Producers/components for every enabled source were
				// already created up-front, so escalation just begins delivering real GPS fixes.
				val locationEnabled = trackingParamsRepository.data.first().locationEnabled
				if (locationEnabled) {
					timerAccessor.get().onDisable(context)
					val gpsTimer = TrackerTimerManager.getSelected(context)
					timerAccessor.set(gpsTimer)
					if (gpsTimer.hasRequiredPermissions(context)) {
						gpsTimer.onEnable(context, timerReceiver)
					} else {
						Reporter.report("Missing permissions for GPS timer during escalation")
					}
				}

				// Escalate the stats ProcessorPipeline regardless of location so tier-aware
				// metrics stay correct.
				processorPipeline?.escalate(newTier, EpochMs(Time.nowMillis))
			}
		}
	}

	/**
	 * Provides get/set access to the timer component owned by the service.
	 */
	internal interface TimerAccessor {
		fun get(): CollectionTriggerComponent
		fun set(timer: CollectionTriggerComponent)
	}
}
