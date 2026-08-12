package com.adsamcik.tracker.stats.api.processor

import com.adsamcik.tracker.stats.api.PolicyTier

/**
 * Metadata describing a SignalProcessor.
 *
 * @property id Unique identifier for this processor
 * @property requiredTier Minimum policy tier needed for this processor to run
 * @property flushIntervalMs How often onFlush() should be called (default 30s)
 * @property priority Execution order within the pipeline (lower = first)
 */
data class ProcessorDescriptor(
	val id: String,
	val requiredTier: PolicyTier = PolicyTier.AMBIENT,
	val flushIntervalMs: Long = 30_000L,
	val priority: Int = 0,
)
