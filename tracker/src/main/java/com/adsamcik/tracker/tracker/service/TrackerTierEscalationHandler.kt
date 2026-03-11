package com.adsamcik.tracker.tracker.service

import android.content.Context
import com.adsamcik.tracker.logger.Reporter
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.stats.api.value.EpochMs
import com.adsamcik.tracker.tracker.component.CollectionTriggerComponent
import com.adsamcik.tracker.tracker.component.DataProducerManager
import com.adsamcik.tracker.tracker.component.DataTrackerComponent
import com.adsamcik.tracker.tracker.component.DynamicIntervalCollectionTrigger
import com.adsamcik.tracker.tracker.component.TrackerTimerManager
import com.adsamcik.tracker.tracker.component.TrackerTimerReceiver
import com.adsamcik.tracker.tracker.controller.TrackerServiceController
import com.adsamcik.tracker.tracker.pipeline.ProcessorPipeline
import com.adsamcik.tracker.tracker.policy.PolicyIntervalMapper
import com.adsamcik.tracker.tracker.policy.PolicyTierMapper
import com.adsamcik.tracker.tracker.policy.TrackingPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Handles tier changes and escalation from AMBIENT to ACTIVE/PRECISION.
 *
 * Manages timer interval updates when the [TrackingPolicy] changes, and
 * performs a full timer swap + component addition when escalating from a
 * non-GPS tier to a GPS-enabled tier.
 *
 * Extracted from [TrackerService] to isolate tier-escalation concerns.
 */
internal class TrackerTierEscalationHandler(
	private val componentMutex: Mutex,
	private val controller: TrackerServiceController,
	private val componentFactory: TrackerComponentFactory,
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

	/**
	 * Mutable component list owned by the service — handler appends during escalation.
	 */
	lateinit var dataComponentList: MutableList<DataTrackerComponent>

	/**
	 * Callback invoked when escalation creates a new DataProducerManager.
	 * The service must update its own field to use the new manager.
	 */
	var onProducerManagerChanged: ((DataProducerManager) -> Unit)? = null

	var dataProducerManager: DataProducerManager? = null
	var processorPipeline: ProcessorPipeline? = null

	/**
	 * Called when the tracking policy changes. Updates the timer interval and
	 * triggers a full tier escalation if the tier boundary crosses from
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
				// Swap timer
				timerAccessor.get().onDisable(context)
				val gpsTimer = TrackerTimerManager.getSelected(context)
				timerAccessor.set(gpsTimer)

				// Recreate DataProducerManager with full producer set
				dataProducerManager?.onDisable()
				val newManager = DataProducerManager(context, newTier)
				newManager.onEnable()
				dataProducerManager = newManager
				onProducerManagerChanged?.invoke(newManager)

				// Add GPS-dependent data components
				val newDataComponents = componentFactory.buildEscalationDataComponents(context)
				dataComponentList.addAll(newDataComponents)

				// Escalate the stats ProcessorPipeline
				processorPipeline?.escalate(newTier, EpochMs(Time.nowMillis))

				// Enable GPS timer
				if (gpsTimer.hasRequiredPermissions(context)) {
					gpsTimer.onEnable(context, timerReceiver)
				} else {
					Reporter.report("Missing permissions for GPS timer during escalation")
				}
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
