package com.adsamcik.tracker.tracker.service

import android.content.Context
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.stats.engine.policy.DefaultPolicyEscalationEngine
import com.adsamcik.tracker.tracker.component.consumer.post.NotificationComponent
import com.adsamcik.tracker.tracker.controller.TrackerServiceController
import com.adsamcik.tracker.tracker.policy.TrackingPolicyManager
import kotlinx.coroutines.CoroutineScope

/**
 * Resolves the active component configuration for a given [PolicyTier].
 *
 * Delegates to [TrackerComponentFactory] to build the concrete component
 * instances, then wraps them in an immutable [TierConfiguration].
 *
 * **Current status (Phase 4b):** infrastructure only. The orchestrator
 * continues to use mutable component lists. Phase 4e will migrate
 * [TrackingOrchestrator] to hold a `var currentConfig: TierConfiguration`
 * and swap it atomically on tier change, eliminating the mutable-list
 * escalation pattern.
 */
internal class TierConfigurationResolver(
	private val componentFactory: TrackerComponentFactory,
) {

	/**
	 * Build a [TierConfiguration] from a pre-existing [ComponentSet].
	 *
	 * Useful when the factory has already been called (e.g. during initial
	 * setup in [TrackingOrchestrator.initialize]) and you want to wrap the
	 * result in the declarative model without re-constructing components.
	 */
	fun fromComponentSet(tier: PolicyTier, componentSet: ComponentSet): TierConfiguration {
		return TierConfiguration(
			tier = tier,
			preComponents = componentSet.preComponents,
			dataComponents = componentSet.dataComponents,
			producerTier = tier,
		)
	}

	/**
	 * Resolve a full [TierConfiguration] for the given [tier] by delegating
	 * to [TrackerComponentFactory.create].
	 *
	 * All components are constructed, enabled, and returned inside the
	 * immutable configuration. The caller is responsible for disabling
	 * components from the previous configuration when swapping.
	 */
	suspend fun resolve(
		context: Context,
		tier: PolicyTier,
		isSessionUserInitiated: Boolean,
		notificationComponent: NotificationComponent,
		trackingPolicyManager: TrackingPolicyManager?,
		escalationEngine: DefaultPolicyEscalationEngine,
		controller: TrackerServiceController,
		scope: CoroutineScope,
	): TierConfiguration {
		val componentSet = componentFactory.create(
			context = context,
			isSessionUserInitiated = isSessionUserInitiated,
			tier = tier,
			notificationComponent = notificationComponent,
			trackingPolicyManager = trackingPolicyManager,
			escalationEngine = escalationEngine,
			controller = controller,
			scope = scope,
		)
		return fromComponentSet(tier, componentSet)
	}
}
