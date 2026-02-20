package com.adsamcik.tracker.tracker.pipeline

import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.stats.api.event.DomainEvent
import com.adsamcik.tracker.stats.api.processor.ProcessorContext
import com.adsamcik.tracker.stats.api.processor.SignalProcessor
import com.adsamcik.tracker.stats.api.signal.TrackingSignal
import com.adsamcik.tracker.stats.api.value.EpochMs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Central pipeline orchestrating all [SignalProcessor] instances.
 *
 * Responsibilities:
 * - Lifecycle management (start/stop)
 * - Signal fan-out to all active processors (filtered by tier)
 * - Periodic flush with domain event collection
 * - Failure isolation via SupervisorJob per processor
 *
 * Thread safety: All signal delivery is protected by [mutex].
 */
class ProcessorPipeline(
	private val processors: Set<SignalProcessor>,
	private val scope: CoroutineScope,
	private val onDomainEvents: suspend (List<DomainEvent>) -> Unit = {},
) {
	private val mutex = Mutex()
	private val sortedProcessors = processors.sortedBy { it.descriptor.priority }
	private var currentTier: PolicyTier = PolicyTier.OFF
	private var isRunning = false
	private val lastFlushTime = mutableMapOf<String, Long>()
	private val supervisorJob = SupervisorJob()

	/** Active processors for the current tier. */
	private val activeProcessors: List<SignalProcessor>
		get() = sortedProcessors.filter { it.descriptor.requiredTier <= currentTier }

	/**
	 * Start the pipeline for a new tracking session.
	 */
	suspend fun start(
		tier: PolicyTier,
		startTimestamp: EpochMs,
		isResuming: Boolean = false,
	) = mutex.withLock {
		currentTier = tier
		isRunning = true
		lastFlushTime.clear()

		val context = ProcessorContext(
			startTimestamp = startTimestamp,
			isResuming = isResuming,
		)

		for (processor in activeProcessors) {
			try {
				processor.onStart(context)
				lastFlushTime[processor.descriptor.id] = startTimestamp.raw
			} catch (e: Exception) {
				// Log but don't fail the whole pipeline
				System.err.println("Processor ${processor.descriptor.id} failed to start: ${e.message}")
			}
		}
	}

	/**
	 * Deliver a tracking signal to all active processors.
	 * MUST be fast — no I/O.
	 */
	suspend fun onSignal(signal: TrackingSignal) = mutex.withLock {
		if (!isRunning) return@withLock

		for (processor in activeProcessors) {
			try {
				processor.onSignal(signal)
			} catch (e: Exception) {
				System.err.println("Processor ${processor.descriptor.id} signal error: ${e.message}")
			}
		}

		// Check if any processor needs flushing
		val now = signal.timestampMs.raw
		val events = mutableListOf<DomainEvent>()
		for (processor in activeProcessors) {
			val lastFlush = lastFlushTime[processor.descriptor.id] ?: 0L
			if (now - lastFlush >= processor.descriptor.flushIntervalMs) {
				try {
					events.addAll(processor.onFlush())
					lastFlushTime[processor.descriptor.id] = now
				} catch (e: Exception) {
					System.err.println("Processor ${processor.descriptor.id} flush error: ${e.message}")
				}
			}
		}

		if (events.isNotEmpty()) {
			scope.launch(supervisorJob) {
				onDomainEvents(events)
			}
		}
	}

	/**
	 * Escalate or de-escalate the pipeline to a new tier.
	 * Starts processors that were inactive, stops those no longer needed.
	 */
	suspend fun escalate(
		newTier: PolicyTier,
		timestamp: EpochMs,
	) = mutex.withLock {
		val oldActive = sortedProcessors.filter { it.descriptor.requiredTier <= currentTier }.toSet()
		currentTier = newTier
		val newActive = sortedProcessors.filter { it.descriptor.requiredTier <= currentTier }.toSet()

		// Start newly active processors
		val toStart = newActive - oldActive
		val context = ProcessorContext(startTimestamp = timestamp)
		for (processor in toStart) {
			try {
				processor.onStart(context)
				lastFlushTime[processor.descriptor.id] = timestamp.raw
			} catch (e: Exception) {
				System.err.println("Processor ${processor.descriptor.id} escalation start error: ${e.message}")
			}
		}

		// Stop de-escalated processors
		val toStop = oldActive - newActive
		val events = mutableListOf<DomainEvent>()
		for (processor in toStop) {
			try {
				events.addAll(processor.onStop())
				lastFlushTime.remove(processor.descriptor.id)
			} catch (e: Exception) {
				System.err.println("Processor ${processor.descriptor.id} de-escalation stop error: ${e.message}")
			}
		}

		if (events.isNotEmpty()) {
			scope.launch(supervisorJob) {
				onDomainEvents(events)
			}
		}
	}

	/**
	 * Stop the pipeline. Final flush of all processors.
	 */
	suspend fun stop() = mutex.withLock {
		if (!isRunning) return@withLock
		isRunning = false

		val events = mutableListOf<DomainEvent>()
		for (processor in activeProcessors) {
			try {
				events.addAll(processor.onStop())
			} catch (e: Exception) {
				System.err.println("Processor ${processor.descriptor.id} stop error: ${e.message}")
			}
		}

		lastFlushTime.clear()

		if (events.isNotEmpty()) {
			onDomainEvents(events)
		}

		supervisorJob.cancel()
	}
}
