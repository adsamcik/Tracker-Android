package com.adsamcik.tracker.tracker.service

import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.tracker.component.DataTrackerComponent
import com.adsamcik.tracker.tracker.component.PostTrackerComponent
import com.adsamcik.tracker.tracker.component.PreTrackerComponent

/**
 * Immutable configuration for a tracking tier.
 *
 * Pre-computed at initialization time — tier changes just swap configs
 * instead of mutating mutable component lists. This eliminates the
 * error-prone mutable-list escalation pattern in [TrackerTierEscalationHandler].
 *
 * **Phase 4e migration:** [TrackingOrchestrator] should replace its
 * `mutableListOf<*TrackerComponent>()` fields with a single
 * `var currentConfig: TierConfiguration` that is swapped atomically
 * on tier change.
 *
 * @property tier           the [PolicyTier] this configuration represents.
 * @property preComponents  pre-processing components (location filtering, etc.).
 * @property dataComponents data-collection components active at this tier.
 * @property postComponents post-processing components (notification, ski, etc.).
 * @property producerTier   the [PolicyTier] used for [DataProducerManager] construction.
 */
internal data class TierConfiguration(
	val tier: PolicyTier,
	val preComponents: List<PreTrackerComponent>,
	val dataComponents: List<DataTrackerComponent>,
	val postComponents: List<PostTrackerComponent>,
	val producerTier: PolicyTier,
)
