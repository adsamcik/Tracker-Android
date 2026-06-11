package com.adsamcik.tracker.tracker.pipeline

import android.util.Log
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.stats.api.event.DomainEvent
import com.adsamcik.tracker.stats.api.processor.ProcessorContext
import com.adsamcik.tracker.stats.api.processor.SignalProcessor
import com.adsamcik.tracker.stats.api.signal.TrackingSignal
import com.adsamcik.tracker.stats.api.value.EpochMs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Central pipeline orchestrating all [SignalProcessor] instances.
 *
 * Responsibilities:
 * - Lifecycle management (start/stop)
 * - Signal fan-out to all active processors (filtered by tier)
 * - Periodic flush with domain event collection
 * - Failure isolation via SupervisorJob per processor
 * - Health tracking: processors with [MAX_CONSECUTIVE_FAILURES] consecutive
 *   errors are automatically disabled for the remainder of the session.
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
	private companion object {
		const val TAG = "ProcessorPipeline"
		const val MAX_CONSECUTIVE_FAILURES = 5
	}

	private val mutex = Mutex()
	// Slow-path flush + dispatch serializer: stop() acquires this AFTER setting
	// isRunning=false but BEFORE calling processor.onStop(), so an in-flight slow
	// flush always finishes before stop tears down processor state. Without it,
	// processor.onStop() could mutate aggregator state mid-flush, causing torn
	// snapshots or final-event loss. Acquiring this also serializes concurrent
	// slow-path flushes from multiple onSignal callers — a small cost in exchange
	// for guaranteed correctness against the now-thread-safe StreamingAggregator.
	private val slowPathSerializer = Mutex()
	private val sortedProcessors = processors.sortedBy { it.descriptor.priority }
	private var currentTier: PolicyTier = PolicyTier.OFF

	@Volatile
	private var isRunning = false
	private val lastFlushTime = mutableMapOf<String, Long>()
	private var supervisorJob = SupervisorJob()
	private val inFlightDispatches = mutableListOf<Job>()

	/** Cached active processors. Rebuilt on start/escalate — never recomputed in hot path. */
	private var cachedActiveProcessors: List<SignalProcessor> = emptyList()
	private val flushCandidates = mutableListOf<SignalProcessor>()

	// --- Health tracking ---
	private val failureCounts = mutableMapOf<String, Int>()
	private val _disabledProcessors = mutableSetOf<String>()

	/** Processors disabled due to repeated failures in this session. */
	val disabledProcessors: Set<String> get() = _disabledProcessors.toSet()

	private fun rebuildActiveProcessors() {
		cachedActiveProcessors = sortedProcessors.filter {
			it.descriptor.requiredTier <= currentTier && it.descriptor.id !in _disabledProcessors
		}
	}

	/**
	 * Record a processor failure and disable if threshold exceeded.
	 *
	 * Caller **must** ensure exclusive access to mutable state
	 * (i.e. call under [mutex] or wrap in `mutex.withLock`).
	 */
	private fun recordFailure(processorId: String, operation: String, e: Exception) {
		val count = (failureCounts[processorId] ?: 0) + 1
		failureCounts[processorId] = count
		Log.w(TAG, "Processor $processorId $operation failed (consecutive: $count): ${e.message}")

		if (count >= MAX_CONSECUTIVE_FAILURES && processorId !in _disabledProcessors) {
			_disabledProcessors.add(processorId)
			cachedActiveProcessors = cachedActiveProcessors.filter { it.descriptor.id != processorId }
			Log.w(TAG, "Processor $processorId disabled after $count consecutive failures")
		}
	}

	/** Reset failure count on successful operation. Hot-path safe (no-op when map empty). */
	private fun resetFailureCount(processorId: String) {
		if (failureCounts.isNotEmpty()) {
			failureCounts.remove(processorId)
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
		failureCounts.clear()
		_disabledProcessors.clear()
		rebuildActiveProcessors()

		val context = ProcessorContext(
			startTimestamp = startTimestamp,
			isResuming = isResuming,
			sessionId = sessionId,
		)

		for (processor in cachedActiveProcessors) {
			val id = processor.descriptor.id
			try {
				processor.onStart(context)
				lastFlushTime[id] = startTimestamp.raw
				resetFailureCount(id)
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				recordFailure(id, "start", e)
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
		val processorsToFlush: List<SignalProcessor>

		// Fast path: deliver signal + identify flush candidates under lock
		mutex.withLock {
			if (!isRunning) return
			flushCandidates.clear()

			for (processor in cachedActiveProcessors) {
				val id = processor.descriptor.id
				try {
					processor.onSignal(signal)
					resetFailureCount(id)
				} catch (e: CancellationException) {
					throw e
				} catch (e: Exception) {
					recordFailure(id, "signal", e)
				}
			}

			val now = signal.timestampMs.raw
			for (processor in cachedActiveProcessors) {
				val lastFlush = lastFlushTime[processor.descriptor.id] ?: 0L
				val shouldFlush = now - lastFlush >= processor.descriptor.flushIntervalMs
				if (shouldFlush) {
					lastFlushTime[processor.descriptor.id] = now
					flushCandidates.add(processor)
				}
			}
			processorsToFlush = flushCandidates.toList()
		}

		// Slow path: flush outside the signal-delivery mutex so signal delivery isn't
		// blocked by I/O. Serialized via slowPathSerializer so stop() can guarantee no
		// flush is in flight when it tears down processor state.
		if (processorsToFlush.isEmpty()) return
		val events = mutableListOf<DomainEvent>()
		slowPathSerializer.withLock {
			if (!isRunning) return  // stop() set the flag before we acquired the lock
			for (processor in processorsToFlush) {
				val id = processor.descriptor.id
				try {
					events.addAll(processor.onFlush())
					mutex.withLock { resetFailureCount(id) }
				} catch (e: CancellationException) {
					throw e
				} catch (e: Exception) {
					mutex.withLock { recordFailure(id, "flush", e) }
				}
			}
		}

		if (events.isNotEmpty() && isRunning) {
			val job = scope.launch(supervisorJob) {
				if (withTimeoutOrNull(5000) { onDomainEvents(events) } == null) {
					Log.e(TAG, "Timed out dispatching domain events")
				}
			}
			synchronized(inFlightDispatches) {
				inFlightDispatches.add(job)
				job.invokeOnCompletion { synchronized(inFlightDispatches) { inFlightDispatches.remove(job) } }
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
				val id = processor.descriptor.id
				try {
					processor.onStart(context)
					lastFlushTime[id] = timestamp.raw
					resetFailureCount(id)
				} catch (e: CancellationException) {
					throw e
				} catch (e: Exception) {
					recordFailure(id, "escalation-start", e)
				}
			}

			toStop = oldActive - newActive
		}

		// Stop de-escalated processors outside mutex but inside slow-path serializer
		// so a concurrent slow flush (or stop()) doesn't race the processor.onStop()
		// state mutation.
		if (toStop.isEmpty()) return
		val events = mutableListOf<DomainEvent>()
		slowPathSerializer.withLock {
			if (!isRunning) return
			for (processor in toStop) {
				val id = processor.descriptor.id
				try {
					events.addAll(processor.onStop())
					mutex.withLock {
						lastFlushTime.remove(id)
						resetFailureCount(id)
					}
				} catch (e: CancellationException) {
					throw e
				} catch (e: Exception) {
					mutex.withLock { recordFailure(id, "de-escalation-stop", e) }
				}
			}
		}

		if (events.isNotEmpty() && isRunning) {
			val job = scope.launch(supervisorJob) {
				if (withTimeoutOrNull(5000) { onDomainEvents(events) } == null) {
					Log.e(TAG, "Timed out dispatching domain events")
				}
			}
			synchronized(inFlightDispatches) {
				inFlightDispatches.add(job)
				job.invokeOnCompletion { synchronized(inFlightDispatches) { inFlightDispatches.remove(job) } }
			}
		}
	}

	/**
	 * Stop the pipeline. Final flush of all processors.
	 *
	 * Drain order (carefully sequenced to prevent torn-snapshot/lost-event races):
	 * 1. Set `isRunning=false` under [mutex] so no new onSignal-driven slow flush
	 *    enters the slow path (it checks the flag again after acquiring its mutex).
	 * 2. Acquire [slowPathSerializer] — waits for any in-flight slow flush to finish
	 *    so processor state is stable when we call onStop on each one.
	 * 3. Call `processor.onStop()` on each active processor and collect final events.
	 * 4. Dispatch the final event batch.
	 * 5. Join in-flight event-dispatch jobs.
	 * 6. Cancel the supervisor.
	 */
	suspend fun stop() {
		val events = mutableListOf<DomainEvent>()

		// Step 1: stop accepting new flushes.
		mutex.withLock {
			if (!isRunning) return
			isRunning = false
		}

		// Step 2: wait for any in-flight slow flush to drain before mutating processors.
		// Step 3: call onStop sequentially while holding the serializer so no slow flush
		// can sneak in. We acquire mutex again only to keep failure-tracking access safe.
		slowPathSerializer.withLock {
			mutex.withLock {
				for (processor in cachedActiveProcessors) {
					val id = processor.descriptor.id
					try {
						events.addAll(processor.onStop())
					} catch (e: CancellationException) {
						throw e
					} catch (e: Exception) {
						recordFailure(id, "stop", e)
					}
				}

				lastFlushTime.clear()
				cachedActiveProcessors = emptyList()
			}
		}

		// Step 4: deliver final events BEFORE cancelling the supervisor job
		if (events.isNotEmpty()) {
			try {
				if (withTimeoutOrNull(5000) { onDomainEvents(events) } == null) {
					Log.e(TAG, "Timed out dispatching final domain events")
				}
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				Log.w(TAG, "Final domain event dispatch failed: ${e.message}")
			}
		}

		// Step 5: join in-flight event dispatches, then step 6: cancel the supervisor.
		val pendingJobs: List<Job>
		synchronized(inFlightDispatches) {
			pendingJobs = inFlightDispatches.toList()
		}
		pendingJobs.forEach { it.join() }
		supervisorJob.cancelAndJoin()
	}
}
