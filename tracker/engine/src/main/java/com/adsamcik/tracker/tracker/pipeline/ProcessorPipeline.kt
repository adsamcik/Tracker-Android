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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext

internal interface DurableSignalProcessor : SignalProcessor {
	suspend fun checkpointStagedSignals(): Boolean
}

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
 * - Domain events are persisted in producer order before later batches can overtake them.
 *
 * Lifecycle: [start] → [onSignal]* → [stop]. Calling [start] twice
 * without [stop] throws [IllegalStateException].
 */
class ProcessorPipeline(
	private val processors: Set<SignalProcessor>,
	@Suppress("UNUSED_PARAMETER") scope: CoroutineScope,
	private val onDomainEvents: suspend (List<DomainEvent>) -> Unit = {},
) {
	private companion object {
		const val TAG = "ProcessorPipeline"
		const val MAX_CONSECUTIVE_FAILURES = 5
		const val DOMAIN_EVENT_PERSIST_TIMEOUT_MILLIS = 3_000L
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
	private val pendingRuntimeEvents = mutableListOf<DomainEvent>()
	private val pendingStopProcessors = ArrayDeque<SignalProcessor>()
	private val pendingStopEvents = mutableListOf<DomainEvent>()
	private val startedProcessors = mutableSetOf<SignalProcessor>()
	private val quarantinedProcessors = mutableSetOf<SignalProcessor>()

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
			it.descriptor.requiredTier <= currentTier &&
				it.descriptor.id !in _disabledProcessors &&
				it !in quarantinedProcessors
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
	) {
		var fatalStartupFailure: IllegalStateException? = null
		mutex.withLock {
			check(!isRunning) { "Pipeline already running — call stop() first" }
			check(
				pendingRuntimeEvents.isEmpty() &&
					pendingStopProcessors.isEmpty() &&
					pendingStopEvents.isEmpty() &&
					startedProcessors.isEmpty() &&
					quarantinedProcessors.isEmpty()
			) {
				"Pipeline shutdown is incomplete — retry stop() before starting a new session"
			}
			currentTier = tier
			isRunning = true
			lastFlushTime.clear()
			failureCounts.clear()
			_disabledProcessors.clear()
			pendingRuntimeEvents.clear()
			pendingStopProcessors.clear()
			pendingStopEvents.clear()
			startedProcessors.clear()
			quarantinedProcessors.clear()
			rebuildActiveProcessors()

			val context = ProcessorContext(
				startTimestamp = startTimestamp,
				isResuming = isResuming,
				sessionId = sessionId,
			)

			for (processor in cachedActiveProcessors.toList()) {
				val id = processor.descriptor.id
				startedProcessors.add(processor)
				try {
					processor.onStart(context)
					lastFlushTime[id] = startTimestamp.raw
					resetFailureCount(id)
				} catch (e: CancellationException) {
					throw e
				} catch (e: Exception) {
					recordFailure(id, "start", e)
					quarantinedProcessors.add(processor)
					cachedActiveProcessors = cachedActiveProcessors.filterNot { it === processor }
					lastFlushTime.remove(id)
					if (processor is DurableSignalProcessor) {
						isRunning = false
						pendingStopProcessors.clear()
						pendingStopProcessors.addAll(startedProcessors.sortedBy { it.descriptor.priority })
						fatalStartupFailure = IllegalStateException(
							"Durability processor failed during pipeline startup",
							e,
						)
						break
					}
				}
			}
		}

		fatalStartupFailure?.let { failure ->
			try {
				stop()
			} catch (cleanupFailure: Exception) {
				failure.addSuppressed(cleanupFailure)
			}
			throw failure
		}
	}

	/**
	 * Deliver a tracking signal to all active processors.
	 *
	 * Fast path (inside mutex): signal fan-out + identify flush candidates.
	 * Slow path (outside mutex): execute flushes + dispatch events.
	 */
	suspend fun onSignal(
		signal: TrackingSignal,
		onAccepted: () -> Unit = {},
	) {
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
			onAccepted()

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

		// Slow path: flush outside the signal-delivery mutex. Event persistence shares the
		// same serializer so batches cannot overtake one another or the final SessionEnded.
		slowPathSerializer.withLock {
			if (!isRunning) return  // stop() set the flag before we acquired the lock
			checkpointSignalsBeforePendingEvents(pendingRuntimeEvents)
			persistPendingEvents(pendingRuntimeEvents, "runtime")
			if (processorsToFlush.isEmpty()) return

			val events = mutableListOf<DomainEvent>()
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
			if (events.isNotEmpty()) {
				mutex.withLock { pendingRuntimeEvents.addAll(events) }
				checkpointSignalsBeforePendingEvents(pendingRuntimeEvents)
				persistPendingEvents(pendingRuntimeEvents, "runtime")
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
		slowPathSerializer.withLock {
			if (!isRunning) return

			var failedPendingEventCount: Int? = null
			try {
				checkpointSignalsBeforePendingEvents(pendingRuntimeEvents)
				persistPendingEvents(pendingRuntimeEvents, "runtime")
			} catch (e: IllegalStateException) {
				failedPendingEventCount = mutex.withLock { pendingRuntimeEvents.size }
				Log.w(TAG, "Deferring pending tier-transition event persistence", e)
			}

			mutex.withLock {
				val desiredActive = sortedProcessors.filter {
					it.descriptor.requiredTier <= newTier &&
						it.descriptor.id !in _disabledProcessors &&
						it !in quarantinedProcessors
				}.toSet()
				val active = cachedActiveProcessors.toMutableSet()
				val context = ProcessorContext(startTimestamp = timestamp)

				for (processor in desiredActive - active) {
					val id = processor.descriptor.id
					startedProcessors.add(processor)
					try {
						processor.onStart(context)
						active.add(processor)
						cachedActiveProcessors = active.sortedBy { it.descriptor.priority }
						lastFlushTime[id] = timestamp.raw
						quarantinedProcessors.remove(processor)
						resetFailureCount(id)
					} catch (e: CancellationException) {
						throw e
					} catch (e: Exception) {
						recordFailure(id, "escalation-start", e)
						quarantinedProcessors.add(processor)
					}
				}

				for (processor in active.toSet() - desiredActive) {
					val id = processor.descriptor.id
					try {
						pendingRuntimeEvents.addAll(processor.onStop())
						active.remove(processor)
						startedProcessors.remove(processor)
						cachedActiveProcessors = active.sortedBy { it.descriptor.priority }
						lastFlushTime.remove(id)
						resetFailureCount(id)
					} catch (e: CancellationException) {
						throw e
					} catch (e: Exception) {
						recordFailure(id, "de-escalation-stop", e)
					}
				}
				currentTier = newTier
			}

			val shouldPersistTransitionEvents = mutex.withLock {
				pendingRuntimeEvents.isNotEmpty() &&
					(failedPendingEventCount == null || pendingRuntimeEvents.size > failedPendingEventCount)
			}
			if (shouldPersistTransitionEvents) {
				try {
					checkpointSignalsBeforePendingEvents(pendingRuntimeEvents)
					persistPendingEvents(pendingRuntimeEvents, "runtime")
				} catch (e: IllegalStateException) {
					Log.w(TAG, "Deferring tier-transition event persistence", e)
				}
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
	 * 3. Persist any earlier runtime event batches.
	 * 4. Call `processor.onStop()` on each active processor and collect final events.
	 * 5. Persist the final event batch before releasing the serializer.
	 */
	suspend fun stop() {
		// Step 1: stop accepting new flushes.
		mutex.withLock {
			if (isRunning) {
				isRunning = false
				pendingStopProcessors.clear()
				pendingStopProcessors.addAll(startedProcessors.sortedBy { it.descriptor.priority })
				pendingStopEvents.clear()
			} else if (
				pendingRuntimeEvents.isEmpty() &&
				pendingStopProcessors.isEmpty() &&
				pendingStopEvents.isEmpty()
			) {
				return
			}
		}

		// Step 2: wait for any in-flight slow flush to drain before mutating processors.
		// Step 3: stop processors sequentially while retaining progress. If one fails,
		// later processors are not stopped and no final events are emitted. A retry
		// resumes at the failed processor, preventing SessionEnded from being published
		// before earlier durability-critical processors finish successfully.
		slowPathSerializer.withLock {
			val durabilityProcessor = mutex.withLock {
				pendingStopProcessors.firstOrNull { it is DurableSignalProcessor }
			}
			if (durabilityProcessor != null) {
				stopProcessor(durabilityProcessor)
			}
			persistPendingEvents(pendingRuntimeEvents, "runtime")
			while (true) {
				val processor = mutex.withLock { pendingStopProcessors.firstOrNull() } ?: break
				stopProcessor(processor)
			}
			withContext(NonCancellable) {
				mutex.withLock {
					lastFlushTime.clear()
					cachedActiveProcessors = emptyList()
					quarantinedProcessors.clear()
				}
			}
			persistPendingEvents(pendingStopEvents, "final")
		}
	}

	private suspend fun checkpointSignalsBeforePendingEvents(
		pendingEvents: MutableList<DomainEvent>,
	) {
		val durabilityProcessor = mutex.withLock {
			if (pendingEvents.isEmpty()) {
				null
			} else {
				cachedActiveProcessors.filterIsInstance<DurableSignalProcessor>().firstOrNull()
			}
		}
		if (durabilityProcessor != null) {
			check(durabilityProcessor.checkpointStagedSignals()) {
				"Unable to checkpoint tracking signals before domain event dispatch"
			}
		}
	}

	private suspend fun stopProcessor(processor: SignalProcessor) {
		val id = processor.descriptor.id
		val events = try {
			processor.onStop()
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			mutex.withLock { recordFailure(id, "stop", e) }
			throw IllegalStateException("Processor $id failed during final shutdown", e)
		}

		withContext(NonCancellable) {
			mutex.withLock {
				pendingStopEvents.addAll(events)
				pendingStopProcessors.remove(processor)
				startedProcessors.remove(processor)
				lastFlushTime.remove(id)
				resetFailureCount(id)
			}
		}
	}

	private suspend fun persistPendingEvents(
		pendingEvents: MutableList<DomainEvent>,
		phase: String,
	) {
		val events = mutex.withLock { pendingEvents.toList() }
		if (events.isEmpty()) return

		// Keep successful persistence and the in-memory acknowledgement atomic against
		// cancellation so a shutdown timeout cannot cause duplicate event retries.
		withContext(NonCancellable) {
			try {
				withTimeout(DOMAIN_EVENT_PERSIST_TIMEOUT_MILLIS) {
					onDomainEvents(events)
				}
			} catch (e: TimeoutCancellationException) {
				throw IllegalStateException(
					"$phase domain event dispatch timed out after ${DOMAIN_EVENT_PERSIST_TIMEOUT_MILLIS}ms",
					e,
				)
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				throw IllegalStateException("$phase domain event dispatch failed", e)
			}
			mutex.withLock {
				pendingEvents.subList(0, events.size).clear()
			}
		}
	}
}
