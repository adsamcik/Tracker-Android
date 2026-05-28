package com.adsamcik.tracker.stats.engine.processor

import com.adsamcik.tracker.stats.api.AggregatorSignal
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.stats.api.event.DomainEvent
import com.adsamcik.tracker.stats.api.metric.MetricDirtyTracker
import com.adsamcik.tracker.stats.api.metric.MetricKeys
import com.adsamcik.tracker.stats.api.processor.ProcessorContext
import com.adsamcik.tracker.stats.api.processor.ProcessorDescriptor
import com.adsamcik.tracker.stats.api.processor.SignalProcessor
import com.adsamcik.tracker.stats.api.signal.TrackingSignal
import com.adsamcik.tracker.stats.api.value.DistanceM
import com.adsamcik.tracker.stats.api.value.DurationMs
import com.adsamcik.tracker.stats.api.value.EpochMs
import com.adsamcik.tracker.stats.api.value.StepCount
import com.adsamcik.tracker.stats.engine.aggregator.StreamingAggregator

/**
 * SignalProcessor wrapping [StreamingAggregator].
 * Accumulates session/day statistics and emits DailySummaryUpdated events on flush.
 *
 * When a [dirtyTracker] is provided, every mutation to the in-memory accumulators
 * marks [MetricKeys.TABLE_AGGREGATOR_STATE] dirty. Achievement evaluation reads
 * the live totals via [snapshotMetrics] (NOT a pre-aggregated table), so without
 * full coverage the achievement processor's dirty-aware short-circuit would
 * suppress real progress events for steps / distance / trip count. The mutating
 * surface is exactly:
 *  - [onSignal] when a non-zero distance/step delta or activity sample arrives.
 *  - [notifyTripCompleted] when a trip is closed (mutates `total_trips`).
 *  - [seedDayTotals] when prior-session totals are restored on session start.
 *
 * Heartbeat signals with zero deltas leave the aggregator clean and the
 * short-circuit can correctly fire.
 */
class AggregatorProcessor(
	private val aggregator: StreamingAggregator = StreamingAggregator(),
	private val dirtyTracker: MetricDirtyTracker? = null,
) : SignalProcessor {

	override val descriptor = ProcessorDescriptor(
		id = "aggregator",
		requiredTier = PolicyTier.AMBIENT,
		flushIntervalMs = 30_000L,
		priority = 10,
	)

	private var sessionId: Long = 0L

	override suspend fun onStart(context: ProcessorContext) {
		sessionId = context.sessionId
		if (context.checkpoint != null) {
			restore(context.checkpoint!!)
		}
		if (!aggregator.isActive) {
			aggregator.start(context.startTimestamp.raw)
		}
	}

	override fun onSignal(signal: TrackingSignal) {
		val distanceDelta = signal.location?.distanceDelta?.raw
		val stepDelta = signal.steps?.stepDelta?.raw ?: 0
		aggregator.onSignal(
			AggregatorSignal(
				timestampMs = signal.timestampMs.raw,
				distanceDeltaM = distanceDelta,
				speedMps = signal.location?.speed?.raw,
				stepDelta = stepDelta,
				activityType = signal.activity?.type,
			),
		)
		// Mark the aggregator's in-memory state dirty when its accumulators actually
		// moved. Pure-heartbeat signals (no distance or step deltas, no activity)
		// don't mark dirty, which is what lets the downstream AchievementProcessor
		// short-circuit during AMBIENT idle.
		val moved = (distanceDelta != null && distanceDelta != 0f) ||
			stepDelta != 0 ||
			signal.activity != null
		if (moved) {
			markStateDirty()
		}
	}

	override suspend fun onFlush(): List<DomainEvent> {
		val snap = aggregator.snapshot()
		return listOf(
			DomainEvent.DailySummaryUpdated(
				timestampMs = EpochMs(snap.lastUpdateMs),
				processorId = descriptor.id,
				dayEpoch = snap.sessionStartMs / 86_400_000L,
				totalDistance = DistanceM.coerced(snap.dayTotalDistanceM),
				totalSteps = StepCount.coerced(snap.dayTotalSteps),
				totalDuration = DurationMs(snap.dayTotalDurationMs.coerceAtLeast(0L)),
				tripCount = snap.tripCount,
			),
		)
	}

	override suspend fun onStop(): List<DomainEvent> {
		val snap = aggregator.stop()
		return listOf(
			DomainEvent.SessionEnded(
				timestampMs = EpochMs(snap.lastUpdateMs),
				processorId = descriptor.id,
				sessionId = sessionId,
				totalDistance = DistanceM.coerced(snap.sessionDistanceM),
				totalSteps = StepCount.coerced(snap.sessionSteps),
				duration = DurationMs(snap.sessionDurationMs.coerceAtLeast(0L)),
			),
		)
	}

	fun notifyTripCompleted() {
		aggregator.onTripCompleted()
		// `tripCount` is part of snapshotMetrics() ("total_trips"), so closing a trip
		// must invalidate the achievement short-circuit even when no signal accompanies
		// the trip-end (e.g. trip closed by inactivity timeout, not movement).
		markStateDirty()
	}

	fun seedDayTotals(distanceM: Float, steps: Int, durationMs: Long, trips: Int) {
		aggregator.seedDayTotals(distanceM, steps, durationMs, trips)
		// Pre-session totals feed `total_distance_km`, `total_steps`, `total_trips`,
		// and `best_daily_steps` in snapshotMetrics(). If any of them are non-zero,
		// the in-memory snapshot changed without any sensor signal — mark dirty so
		// the first achievement flush after session start re-evaluates them.
		if (distanceM != 0f || steps != 0 || durationMs != 0L || trips != 0) {
			markStateDirty()
		}
	}

	private fun markStateDirty() {
		dirtyTracker?.markDirty(MetricKeys.TABLE_AGGREGATOR_STATE)
	}

	/**
	 * Returns the current aggregator state as a flat metrics map for achievement evaluation.
	 * Safe to call at any time; returns an empty map when the aggregator is inactive.
	 *
	 * Keys match the [com.adsamcik.tracker.stats.api.achievement.AchievementCatalog] metric names where applicable.
	 */
	fun snapshotMetrics(): Map<String, Long> {
		if (!aggregator.isActive) return emptyMap()
		val snap = aggregator.snapshot()
		return mapOf(
			"total_distance_km" to (snap.dayTotalDistanceM / 1000f).toLong(),
			"total_steps" to snap.dayTotalSteps.toLong(),
			"total_trips" to snap.tripCount.toLong(),
			"session_distance_m" to snap.sessionDistanceM.toLong(),
			"session_steps" to snap.sessionSteps.toLong(),
			"session_duration_ms" to snap.sessionDurationMs,
			"longest_trip_km" to (snap.sessionDistanceM / 1000f).toLong(),
			"best_daily_steps" to snap.dayTotalSteps.toLong(),
		)
	}

	override fun checkpoint(): ByteArray = aggregator.serialize()

	override fun restore(state: ByteArray) {
		aggregator.deserialize(state)
	}
}
