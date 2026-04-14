package com.adsamcik.tracker.stats.engine.processor

import com.adsamcik.tracker.stats.api.AggregatorSignal
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.stats.api.event.DomainEvent
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
 */
class AggregatorProcessor(
	private val aggregator: StreamingAggregator = StreamingAggregator(),
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
		aggregator.onSignal(
			AggregatorSignal(
				timestampMs = signal.timestampMs.raw,
				distanceDeltaM = signal.location?.distanceDelta?.raw,
				speedMps = signal.location?.speed?.raw,
				stepDelta = signal.steps?.stepDelta?.raw ?: 0,
				activityType = signal.activity?.type,
			),
		)
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
	}

	fun seedDayTotals(distanceM: Float, steps: Int, durationMs: Long, trips: Int) {
		aggregator.seedDayTotals(distanceM, steps, durationMs, trips)
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
