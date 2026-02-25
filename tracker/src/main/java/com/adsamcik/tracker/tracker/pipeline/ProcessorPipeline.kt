package com.adsamcik.tracker.tracker.pipeline

import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.stats.api.event.DomainEvent
import com.adsamcik.tracker.stats.api.processor.ProcessorContext
import com.adsamcik.tracker.stats.api.processor.SignalProcessor
import com.adsamcik.tracker.stats.api.signal.TrackingSignal
import com.adsamcik.tracker.stats.api.value.EpochMs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
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
 * Thread safety:
 * - Signal delivery + state mutation protected by [mutex].
 * - Flush I/O runs **outside** the mutex to avoid blocking signal delivery.
 * - Domain event dispatch is fire-and-forget via [supervisorJob].
 *
 * Lifecycle: [start] → [onSignal]* → [stop]. Calling [start] twice
 * without [stop] throws [IllegalStateException].
 */
class ProcessorPipeline(
	private val processors: Set<SignalProcessor>,
	private val scope: CoroutineScope,
	private val onDomainEvents: suspend (List<DomainEvent>) -> Unit = {},
) {
	private val mutex = Mutex()
	private val sortedProcessors = processors.sortedBy { it.descriptor.priority }
	private var currentTier: PolicyTier = PolicyTier.OFF

	@Volatile
	private var isRunning = false
	private val lastFlushTime = mutableMapOf<String, Long>()
	private var supervisorJob = SupervisorJob()

	/** Cached active processors. Rebuilt on start/escalate — never recomputed in hot path. */
	private var cachedActiveProcessors: List<SignalProcessor> = emptyList()

	private fun rebuildActiveProcessors() {
		cachedActiveProcessors = sortedProcessors.filter {
			it.descriptor.requiredTier <= currentTier
		}
	}

	/**
	 * Start the pipeline for a new tracking session.
	 * @throws IllegalStateException if pipeline is already running.
	 */
	suspend fun start(
		tier: PolicyTier,
		startTimestamp: EpochMs,
		isResuming: Boolean = false,
		sessionId: Long = 0L,
	) = mutex.withLock {
		check(!isRunning) { "Pipeline already running — call stop() first" }
		supervisorJob = SupervisorJob()
		currentTier = tier
		isRunning = true
		lastFlushTime.clear()
		rebuildActiveProcessors()

		val context = ProcessorContext(
			startTimestamp = startTimestamp,
			isResuming = isResuming,
			sessionId = sessionId,
		)

		for (processor in cachedActiveProcessors) {
			try {
				processor.onStart(context)
				lastFlushTime[processor.descriptor.id] = startTimestamp.raw
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				System.err.println("Processor ${processor.descriptor.id} failed to start: ${e.message}")
			}
		}
	}

	/**
	 * Deliver a tracking signal to all active processors.
	 *
	 * Fast path (inside mutex): signal fan-out + identify flush candidates.
	 * Slow path (outside mutex): execute flushes + dispatch events.
	 */
	suspend fun onSignal(signal: TrackingSignal) {
		val flushCandidates: List<SignalProcessor>

		// Fast path: deliver signal + identify flush candidates under lock
		mutex.withLock {
			if (!isRunning) return

			for (processor in cachedActiveProcessors) {
				try {
					processor.onSignal(signal)
				} catch (e: Exception) {
					System.err.println("Processor ${processor.descriptor.id} signal error: ${e.message}")
				}
			}

			val now = signal.timestampMs.raw
			flushCandidates = cachedActiveProcessors.filter { processor ->
				val lastFlush = lastFlushTime[processor.descriptor.id] ?: 0L
				val shouldFlush = now - lastFlush >= processor.descriptor.flushIntervalMs
				if (shouldFlush) {
					lastFlushTime[processor.descriptor.id] = now
				}
				shouldFlush
			}
		}

		// Slow path: flush outside mutex so signal delivery isn't blocked by I/O
		if (flushCandidates.isEmpty()) return
		val events = mutableListOf<DomainEvent>()
		for (processor in flushCandidates) {
			try {
				events.addAll(processor.onFlush())
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				System.err.println("Processor ${processor.descriptor.id} flush error: ${e.message}")
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
	) {
		val toStop: Set<SignalProcessor>

		mutex.withLock {
			if (!isRunning) return

			val oldActive = cachedActiveProcessors.toSet()
			currentTier = newTier
			rebuildActiveProcessors()
			val newActive = cachedActiveProcessors.toSet()

			// Start newly active processors
			val toStart = newActive - oldActive
			val context = ProcessorContext(startTimestamp = timestamp)
			for (processor in toStart) {
				try {
					processor.onStart(context)
					lastFlushTime[processor.descriptor.id] = timestamp.raw
				} catch (e: CancellationException) {
					throw e
				} catch (e: Exception) {
					System.err.println("Processor ${processor.descriptor.id} escalation start error: ${e.message}")
				}
			}

			toStop = oldActive - newActive
		}

		// Stop de-escalated processors outside mutex
		if (toStop.isEmpty()) return
		val events = mutableListOf<DomainEvent>()
		for (processor in toStop) {
			try {
				events.addAll(processor.onStop())
				mutex.withLock { lastFlushTime.remove(processor.descriptor.id) }
			} catch (e: CancellationException) {
				throw e
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
	 * Awaits completion of in-flight domain event dispatches before returning.
	 */
	suspend fun stop() {
		val events = mutableListOf<DomainEvent>()

		mutex.withLock {
			if (!isRunning) return
			isRunning = false

			for (processor in cachedActiveProcessors) {
				try {
					events.addAll(processor.onStop())
				} catch (e: CancellationException) {
					throw e
				} catch (e: Exception) {
					System.err.println("Processor ${processor.descriptor.id} stop error: ${e.message}")
				}
			}

			lastFlushTime.clear()
			cachedActiveProcessors = emptyList()
		}

		// Deliver final events BEFORE cancelling the supervisor job
		if (events.isNotEmpty()) {
			try {
				onDomainEvents(events)
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				System.err.println("Final domain event dispatch failed: ${e.message}")
			}
		}

		// Wait for in-flight event dispatches to complete, then cancel
		supervisorJob.cancelAndJoin()
	}
}
