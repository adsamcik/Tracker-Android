package com.adsamcik.tracker.tracker.pipeline

import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.stats.api.event.DomainEvent
import com.adsamcik.tracker.stats.api.processor.ProcessorContext
import com.adsamcik.tracker.stats.api.processor.ProcessorDescriptor
import com.adsamcik.tracker.stats.api.processor.SignalProcessor
import com.adsamcik.tracker.stats.api.signal.TrackingSignal
import javax.inject.Inject

/**
 * Bridge processor that registers the ski-tracking concern in the signal
 * pipeline. Caches the latest [TrackingSignal] each cycle.
 *
 * The actual real-time ski detection (pressure + GPS analysis, lift
 * detection, segment writing) is driven by
 * [com.adsamcik.tracker.tracker.component.consumer.post.SkiTrackingComponent]
 * and [com.adsamcik.tracker.tracker.component.consumer.post.SkiSegmentWriter].
 * This processor ensures the ski-tracking concern has a home in the pipeline
 * so it is not orphaned when old components are removed.
 */
class SkiTrackingProcessor @Inject constructor() : SignalProcessor {

	override val descriptor = ProcessorDescriptor(
		id = PROCESSOR_ID,
		requiredTier = PolicyTier.ACTIVE,
		flushIntervalMs = Long.MAX_VALUE,
		priority = PRIORITY,
	)

	@Volatile
	private var latestSignal: TrackingSignal? = null

	private var enabled: Boolean = true

	override suspend fun onStart(context: ProcessorContext) {
		latestSignal = null
		enabled = true
	}

	override fun onSignal(signal: TrackingSignal) {
		if (!enabled) return
		latestSignal = signal
	}

	override suspend fun onFlush(): List<DomainEvent> = emptyList()

	override suspend fun onStop(): List<DomainEvent> {
		latestSignal = null
		return emptyList()
	}

	override fun checkpoint(): ByteArray? = null

	override fun restore(state: ByteArray) {
		// No checkpoint state for bridge processor
	}

	companion object {
		const val PROCESSOR_ID = "ski-tracking"
		const val PRIORITY = 50
	}
}
