package com.adsamcik.tracker.tracker.service

import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.stats.api.value.EpochMs
import com.adsamcik.tracker.tracker.controller.TrackerServiceController
import com.adsamcik.tracker.tracker.pipeline.ProcessorPipeline
import com.adsamcik.tracker.tracker.policy.PolicyTierMapper
import com.adsamcik.tracker.tracker.policy.TrackingPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Propagates effective policy-tier changes to processing and service metadata.
 *
 * Phase 10 deliberately gives this class no platform-acquisition API. Source runtimes apply
 * semantic plans independently, so a policy transition cannot recreate the retired global timer
 * or compete for a location registration.
 */
internal class TrackerTierEscalationHandler(
	private val componentMutex: Mutex,
	private val controller: TrackerServiceController,
	private val tierAdjuster: (PolicyTier) -> PolicyTier = { it },
	private val onEffectiveTierChanged: (PolicyTier) -> Unit = {},
) {
	var currentTier: PolicyTier = PolicyTier.PRECISION
	var processorPipeline: ProcessorPipeline? = null

	fun onPolicyChanged(policy: TrackingPolicy, scope: CoroutineScope) {
		val newTier = tierAdjuster(PolicyTierMapper.toTier(policy))
		scope.launch {
			componentMutex.withLock {
				val oldTier = currentTier
				if (newTier == oldTier) return@withLock
				try {
					processorPipeline?.escalate(newTier, EpochMs(Time.nowMillis))
				} catch (e: CancellationException) {
					throw e
				} catch (e: Exception) {
					return@withLock
				}
				currentTier = newTier
				onEffectiveTierChanged(newTier)
				controller.updatePolicyTier(newTier)
			}
		}
	}
}
