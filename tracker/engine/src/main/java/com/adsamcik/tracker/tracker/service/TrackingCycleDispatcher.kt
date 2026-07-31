package com.adsamcik.tracker.tracker.service

import com.adsamcik.tracker.diagnostics.TrackerDiagnosticCode
import com.adsamcik.tracker.diagnostics.TrackerDiagnostics
import com.adsamcik.tracker.shared.base.data.LocationData
import com.adsamcik.tracker.tracker.data.collection.TrackingCycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.collections.immutable.toPersistentList

/**
 * Bounded, serialized dispatcher for timer callbacks.
 *
 * Provider observations and accepted GPS fixes are never dropped. When the queue is full, the
 * newest cycle is merged into the queued tail so all deliveries remain ordered while the number of
 * pending processing turns is bounded.
 */
internal class TrackingCycleDispatcher(
	scope: CoroutineScope,
	dispatcher: CoroutineDispatcher,
	private val capacity: Int = DEFAULT_CAPACITY,
	private val processCycle: suspend (TrackingCycle) -> Unit,
) {
	init {
		require(capacity > 0) { "Tracking cycle queue capacity must be positive" }
	}

	private data class QueuedCycle(
		val cycle: TrackingCycle,
		val completions: List<CompletableJob>,
	)

	private val signal = Channel<Unit>(Channel.CONFLATED)
	private val lock = Any()
	private val queue = ArrayDeque<QueuedCycle>()
	private var accepting = true
	private var inFlightCompletions: List<CompletableJob> = emptyList()

	@Volatile
	var maxBufferedCyclesObserved: Int = 0
		private set

	val pendingCycleCount: Int
		get() = synchronized(lock) { queue.size }

	private val worker = scope.launch(dispatcher) {
		while (true) {
			val queued = synchronized(lock) {
				queue.removeFirstOrNull()?.also { inFlightCompletions = it.completions }
			}
			if (queued == null) {
				signal.receive()
				continue
			}

			try {
				processQueued(queued)
			} finally {
				synchronized(lock) { inFlightCompletions = emptyList() }
			}
		}
	}

	fun enqueue(cycle: TrackingCycle): Job {
		val completion = Job()
		if (!worker.isActive) {
			completion.cancel()
			return completion
		}

		synchronized(lock) {
			if (!accepting) {
				completion.cancel()
				return completion
			}
			if (queue.size < capacity) {
				queue.addLast(QueuedCycle(cycle, listOf(completion)))
			} else {
				val tail = queue.removeLast()
				queue.addLast(
					QueuedCycle(
						cycle = mergeCycles(tail.cycle, cycle),
						completions = tail.completions + completion,
					)
				)
			}
			maxBufferedCyclesObserved = maxOf(maxBufferedCyclesObserved, queue.size)
		}
		signal.trySend(Unit)
		return completion
	}

	suspend fun closeAndDrain() {
		val completions = synchronized(lock) {
			accepting = false
			inFlightCompletions + queue.flatMap { it.completions }
		}
		completions.joinAll()
	}

	fun cancel() {
		worker.cancel()
		val pending = synchronized(lock) {
			accepting = false
			val completions = queue.flatMap { it.completions }
			queue.clear()
			completions
		}
		pending.forEach { it.cancel() }
		signal.close()
	}

	suspend fun cancelAndJoin() {
		cancel()
		worker.cancelAndJoin()
	}

	private suspend fun processQueued(queued: QueuedCycle) {
		try {
			splitLocationFixes(queued.cycle).forEach { processCycle(it) }
			queued.completions.forEach { it.complete() }
		} catch (e: CancellationException) {
			queued.completions.forEach { it.cancel(e) }
			throw e
		} catch (e: Exception) {
			TrackerDiagnostics.record(TrackerDiagnosticCode.TRACKING_CYCLE_FAILED)
			queued.completions.forEach { it.completeExceptionally(e) }
		}
	}

	private fun splitLocationFixes(cycle: TrackingCycle): List<TrackingCycle> {
		val locationData = cycle.location ?: return listOf(cycle)
		if (locationData.locations.size <= 1) return listOf(cycle)

		var previousLocation = locationData.previousLocation
		return locationData.locations.mapIndexed { index, location ->
			val isLast = index == locationData.locations.lastIndex
			val isFirst = index == 0
			val singleLocation = LocationData(
				locations = listOf(location),
				previousLocation = previousLocation,
				distance = previousLocation?.distanceTo(location),
				fixMetadata = listOf(locationData.fixMetadata[index]),
			)
			previousLocation = location

			cycle.copy(
				timestampMs = location.time.takeIf { it > 0L } ?: cycle.timestampMs,
				elapsedRealtimeNanos = location.elapsedRealtimeNanos
					.takeIf { it > 0L }
					?: cycle.elapsedRealtimeNanos,
				activityFresh = isLast && cycle.activityFresh,
				location = singleLocation,
				locationObservations = cycle.locationObservations.takeIf { isFirst }.orEmpty(),
				cellScan = cycle.cellScan.takeIf { isLast },
				cellScanFresh = isLast && cycle.cellScanFresh,
				wifiScan = cycle.wifiScan.takeIf { isLast },
				stepDelta = cycle.stepDelta.takeIf { isLast },
				totalStepsSinceBoot = cycle.totalStepsSinceBoot.takeIf { isLast },
				stepSensorValueStart = cycle.stepSensorValueStart.takeIf { isLast } ?: 0,
				stepSensorValueEnd = cycle.stepSensorValueEnd.takeIf { isLast } ?: 0,
				stepSensorReset = isLast && cycle.stepSensorReset,
				pressure = cycle.pressure.takeIf { isLast },
				rawGpsAltitude = cycle.rawGpsAltitude.takeIf { isLast },
				persistenceSignalId = "${cycle.persistenceSignalId}:location-$index",
			)
		}
	}

	private fun mergeCycles(first: TrackingCycle, second: TrackingCycle): TrackingCycle {
		return TrackingCycle(
			timestampMs = second.timestampMs,
			elapsedRealtimeNanos = second.elapsedRealtimeNanos,
			activity = second.activity ?: first.activity,
			activityFresh = first.activityFresh || second.activityFresh,
			location = mergeLocation(first.location, second.location),
			locationObservations = first.locationObservations + second.locationObservations,
			cellScan = second.cellScan ?: first.cellScan,
			cellScanFresh = first.cellScanFresh || second.cellScanFresh,
			wifiScan = second.wifiScan ?: first.wifiScan,
			stepDelta = mergeStepDelta(first.stepDelta, second.stepDelta),
			totalStepsSinceBoot = second.totalStepsSinceBoot ?: first.totalStepsSinceBoot,
			stepSensorValueStart = when {
				first.stepDelta != null -> first.stepSensorValueStart
				else -> second.stepSensorValueStart
			},
			stepSensorValueEnd = when {
				second.stepDelta != null -> second.stepSensorValueEnd
				else -> first.stepSensorValueEnd
			},
			stepSensorReset = first.stepSensorReset || second.stepSensorReset,
			pressure = second.pressure ?: first.pressure,
			rawGpsAltitude = second.rawGpsAltitude ?: first.rawGpsAltitude,
			// The tail cycle has not entered durable admission yet. Preserve its
			// identity while absorbing newer data so a retry of this queued work
			// resolves the same pending-signal key instead of minting a duplicate.
			persistenceSignalId = first.persistenceSignalId,
		)
	}

	private fun mergeLocation(first: LocationData?, second: LocationData?): LocationData? = when {
		first == null -> second
		second == null -> first
		else -> LocationData(
			locations = first.locations.toPersistentList().addingAll(second.locations),
			previousLocation = first.previousLocation ?: second.previousLocation,
			distance = mergeDistances(first.distance, second.distance),
			fixMetadata = first.fixMetadata.toPersistentList().addingAll(second.fixMetadata),
		)
	}

	private fun mergeDistances(first: Float?, second: Float?): Float? {
		val distances = listOfNotNull(first, second)
		return distances.takeIf { it.isNotEmpty() }?.sum()
	}

	private fun mergeStepDelta(first: Int?, second: Int?): Int? = when {
		first == null -> second
		second == null -> first
		else -> first + second
	}

	private companion object {
		const val DEFAULT_CAPACITY = 64
	}
}
