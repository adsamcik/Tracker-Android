package com.adsamcik.tracker.tracker.pipeline

import com.adsamcik.tracker.diagnostics.TrackerDiagnosticCode
import com.adsamcik.tracker.diagnostics.TrackerDiagnostics
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.stats.api.event.DomainEvent
import com.adsamcik.tracker.stats.api.processor.ProcessorContext
import com.adsamcik.tracker.stats.api.processor.SignalProcessor
import com.adsamcik.tracker.stats.api.signal.TrackingSignal
import com.adsamcik.tracker.stats.api.value.EpochMs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext

/** Whether a durability checkpoint admitted a signal, intentionally discarded it, or failed. */
enum class DurableAdmissionStatus {
	ADMITTED,
	LIFECYCLE_REJECTED,
	FAILED,
}

internal interface DurableSignalProcessor : SignalProcessor {
	suspend fun checkpointStagedSignals(): Boolean

	/**
	 * Preserves the Boolean contract for existing implementations while allowing the persistence
	 * processor to distinguish a terminal lifecycle discard from a storage failure.
	 */
	suspend fun checkpointStagedSignalsStatus(): DurableAdmissionStatus =
		if (checkpointStagedSignals()) DurableAdmissionStatus.ADMITTED else DurableAdmissionStatus.FAILED

	/**
	 * Admission result for one just-staged signal. Implementations that keep per-signal admission
	 * metadata override this so a lifecycle-rejected older row does not suppress fan-out of a newer
	 * row checkpointed in the same batch.
	 */
	suspend fun checkpointStagedSignalStatus(signal: TrackingSignal): DurableAdmissionStatus =
		checkpointStagedSignalsStatus()
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
	private val onDomainEvents: suspend (List<DomainEvent>) -> Unit = {},
	/** Production pipelines make the pending-signal commit the acceptance boundary. */
	private val requireDurableAdmission: Boolean = false,
) {
	private companion object {
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
	// Admission is deliberately serialized separately from the normal state mutex.
	// A signal must be committed to pending_signal before downstream processors can
	// consume it; without this gate, two callers could checkpoint B before A and
	// advance producer state out of order.
	private val durableAdmissionSerializer = Mutex()
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
	private fun recordFailure(processorId: String) {
		val count = (failureCounts[processorId] ?: 0) + 1
		failureCounts[processorId] = count
		if (count >= MAX_CONSECUTIVE_FAILURES && processorId !in _disabledProcessors) {
			_disabledProcessors.add(processorId)
			cachedActiveProcessors = cachedActiveProcessors.filter { it.descriptor.id != processorId }
			TrackerDiagnostics.record(TrackerDiagnosticCode.TRACKING_PROCESSOR_DISABLED)
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
					recordFailure(id)
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
	): Boolean = durableAdmissionSerializer.withLock {
		slowPathSerializer.withLock slowPath@{
			var durabilityProcessor: DurableSignalProcessor? = null
			var durabilityDeliveryFailed = false
			var durabilityUnavailable = false

			// Stage only after owning the slow path. This gives every path the same
			// lock order (admission -> slow path -> state mutex), so stop/escalate
			// cannot checkpoint a staged signal and then make this call report that
			// it was rejected before downstream consumers see it.
			val pipelineActive = mutex.withLock {
				if (!isRunning) return@withLock false
				durabilityProcessor = cachedActiveProcessors
					.filterIsInstance<DurableSignalProcessor>()
					.firstOrNull()
				val durability = durabilityProcessor
				if (durability == null) {
					durabilityUnavailable = requireDurableAdmission
					return@withLock true
				}
				try {
					durability.onSignal(signal)
					resetFailureCount(durability.descriptor.id)
					true
				} catch (e: CancellationException) {
					throw e
				} catch (e: Exception) {
					recordFailure(durability.descriptor.id)
					durabilityDeliveryFailed = true
					false
				}
			}
			if (!pipelineActive || durabilityUnavailable || durabilityDeliveryFailed) {
				return@slowPath false
			}

			// The initial state-mutex section is the stop boundary. Once it has
			// staged this signal, finish its checkpoint and fan-out even if stop()
			// flips isRunning while waiting for the mutex: stop waits on this slow
			// path before it can tear any processor down.
			val durability = durabilityProcessor
			if (durability != null) {
					val admission = try {
						durability.checkpointStagedSignalStatus(signal)
				} catch (e: CancellationException) {
					throw e
				} catch (e: Exception) {
					mutex.withLock {
						recordFailure(durability.descriptor.id)
					}
					DurableAdmissionStatus.FAILED
				}
				if (admission != DurableAdmissionStatus.ADMITTED) return@slowPath false
			} else if (requireDurableAdmission) {
				return@slowPath false
			}

			val processorsToFlush = mutex.withLock {
				flushCandidates.clear()
				for (processor in cachedActiveProcessors) {
					if (processor === durability) continue
					val id = processor.descriptor.id
					try {
						processor.onSignal(signal)
						resetFailureCount(id)
					} catch (e: CancellationException) {
						throw e
					} catch (e: Exception) {
						recordFailure(id)
					}
				}

				onAccepted()
				val now = signal.timestampMs.raw
				for (processor in cachedActiveProcessors) {
					val lastFlush = lastFlushTime[processor.descriptor.id] ?: 0L
					if (now - lastFlush >= processor.descriptor.flushIntervalMs) {
						lastFlushTime[processor.descriptor.id] = now
						flushCandidates.add(processor)
					}
				}
				flushCandidates.toList()
			}

			persistPendingEvents(pendingRuntimeEvents, "runtime")
			if (processorsToFlush.isEmpty()) return@slowPath true

			val events = mutableListOf<DomainEvent>()
			for (processor in processorsToFlush) {
				val id = processor.descriptor.id
				try {
					events.addAll(processor.onFlush())
					mutex.withLock { resetFailureCount(id) }
				} catch (e: CancellationException) {
					throw e
				} catch (e: Exception) {
					mutex.withLock { recordFailure(id) }
				}
			}
			if (events.isNotEmpty()) {
				mutex.withLock { pendingRuntimeEvents.addAll(events) }
				persistPendingEvents(pendingRuntimeEvents, "runtime")
			}
			true
		}
	}

	/**
	 * Sends provider evidence only to the durability processor and checkpoints it immediately.
	 *
	 * Raw provider deliveries are captured before the curated pipeline and therefore must not fan
	 * out to analytical processors. They still need the same serialization as normal persistence:
	 * the slow-path lock excludes flush/stop while [mutex] excludes concurrent signal buffering.
	 */
	suspend fun checkpointDurableSignals(signals: List<TrackingSignal>): Boolean {
		if (signals.isEmpty()) return true
		return durableAdmissionSerializer.withLock {
			slowPathSerializer.withLock {
				mutex.withLock {
					if (!isRunning) return@withLock false
					val processor = cachedActiveProcessors
						.filterIsInstance<DurableSignalProcessor>()
						.firstOrNull()
						?: return@withLock false
					val id = processor.descriptor.id
					try {
						signals.forEach(processor::onSignal)
						val admission = processor.checkpointStagedSignalsStatus()
						if (admission == DurableAdmissionStatus.ADMITTED) resetFailureCount(id)
						admission == DurableAdmissionStatus.ADMITTED
					} catch (e: CancellationException) {
						throw e
					} catch (e: Exception) {
						recordFailure(id)
						false
					}
				}
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
						recordFailure(id)
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
						recordFailure(id)
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
				} catch (_: IllegalStateException) {
					// The events stay queued and will be retried by the next flush or stop.
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
			check(durabilityProcessor.checkpointStagedSignalsStatus() != DurableAdmissionStatus.FAILED) {
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
			mutex.withLock { recordFailure(id) }
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
				TrackerDiagnostics.record(TrackerDiagnosticCode.DOMAIN_EVENT_PERSIST_FAILED)
				throw IllegalStateException(
					"$phase domain event dispatch timed out after ${DOMAIN_EVENT_PERSIST_TIMEOUT_MILLIS}ms",
					e,
				)
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				TrackerDiagnostics.record(TrackerDiagnosticCode.DOMAIN_EVENT_PERSIST_FAILED)
				throw IllegalStateException("$phase domain event dispatch failed", e)
			}
			mutex.withLock {
				pendingEvents.subList(0, events.size).clear()
			}
		}
	}
}
