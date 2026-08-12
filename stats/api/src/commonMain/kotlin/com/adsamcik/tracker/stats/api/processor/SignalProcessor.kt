package com.adsamcik.tracker.stats.api.processor

import com.adsamcik.tracker.stats.api.event.DomainEvent
import com.adsamcik.tracker.stats.api.signal.TrackingSignal

/**
 * A processor that handles tracking signals in the pipeline.
 * Each processor is discovered via Hilt multibinding and runs
 * single-threaded behind a mutex in the ProcessorPipeline.
 *
 * Lifecycle: onStart → [onSignal]* → onFlush (periodic) → onStop
 */
interface SignalProcessor {
	/** Descriptor defining processor metadata. */
	val descriptor: ProcessorDescriptor

	/**
	 * Called once when tracking starts.
	 */
	suspend fun onStart(context: ProcessorContext)

	/**
	 * Called every sensor cycle (~1Hz).
	 * MUST be fast — no I/O, no suspending.
	 */
	fun onSignal(signal: TrackingSignal)

	/**
	 * Called periodically to flush state to storage.
	 * Returns domain events produced during this flush.
	 */
	suspend fun onFlush(): List<DomainEvent>

	/**
	 * Called when tracking stops.
	 * Final flush and cleanup.
	 * Returns domain events produced during stop.
	 */
	suspend fun onStop(): List<DomainEvent>
}
