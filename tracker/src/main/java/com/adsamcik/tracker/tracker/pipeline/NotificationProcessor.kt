package com.adsamcik.tracker.tracker.pipeline

import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.stats.api.event.DomainEvent
import com.adsamcik.tracker.stats.api.processor.ProcessorContext
import com.adsamcik.tracker.stats.api.processor.ProcessorDescriptor
import com.adsamcik.tracker.stats.api.processor.SignalProcessor
import com.adsamcik.tracker.stats.api.signal.TrackingSignal
import javax.inject.Inject

/**
 * Bridge processor that registers the notification concern in the new
 * signal pipeline. Caches the latest [TrackingSignal] each cycle.
 *
 * The actual foreground notification update continues to be driven by
 * [com.adsamcik.tracker.tracker.component.consumer.post.NotificationComponent]
 * via TrackerService.updateData() until the old PostTrackerComponent
 * pipeline is removed (Phase 4). This processor exists solely to
 * ensure the notification concern has a home in the new pipeline and
 * will not be orphaned when the old pipeline is deleted.
 */
class NotificationProcessor @Inject constructor() : SignalProcessor {

	override val descriptor = ProcessorDescriptor(
		id = PROCESSOR_ID,
		requiredTier = PolicyTier.AMBIENT,
		flushIntervalMs = Long.MAX_VALUE,
		priority = PRIORITY,
	)

	@Volatile
	private var latestSignal: TrackingSignal? = null

	override suspend fun onStart(context: ProcessorContext) {
		latestSignal = null
	}

	override fun onSignal(signal: TrackingSignal) {
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
		const val PROCESSOR_ID = "notification"
		const val PRIORITY = 100
	}
}
