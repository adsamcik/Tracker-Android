package com.adsamcik.tracker.stats.engine.processor

import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.stats.api.SegmentEvent
import com.adsamcik.tracker.stats.api.SegmentSignal
import com.adsamcik.tracker.stats.api.event.DomainEvent
import com.adsamcik.tracker.stats.api.processor.ProcessorContext
import com.adsamcik.tracker.stats.api.processor.ProcessorDescriptor
import com.adsamcik.tracker.stats.api.processor.SignalProcessor
import com.adsamcik.tracker.stats.api.signal.TrackingSignal
import com.adsamcik.tracker.stats.api.value.DistanceM
import com.adsamcik.tracker.stats.api.value.DurationMs
import com.adsamcik.tracker.stats.api.value.EpochMs
import com.adsamcik.tracker.stats.api.value.StepCount
import com.adsamcik.tracker.stats.engine.segment.SessionSegmentDetector

/**
 * SignalProcessor wrapping [SessionSegmentDetector].
 * Detects trip start/end and emits TripStarted/TripCompleted domain events.
 */
class SegmentDetectorProcessor(
	private val detector: SessionSegmentDetector = SessionSegmentDetector(),
	private val onTripCompleted: (() -> Unit)? = null,
) : SignalProcessor {

	override val descriptor = ProcessorDescriptor(
		id = "segment-detector",
		requiredTier = PolicyTier.ACTIVE,
		flushIntervalMs = 30_000L,
		priority = 20,
	)

	private val pendingEvents = mutableListOf<DomainEvent>()
	private var lastLocationTimestamp: EpochMs? = null

	override suspend fun onStart(context: ProcessorContext) {
		pendingEvents.clear()
		lastLocationTimestamp = null
	}

	override fun onSignal(signal: TrackingSignal) {
		val location = signal.location ?: return
		lastLocationTimestamp = signal.timestampMs

		val segSignal = SegmentSignal(
			timestampMs = signal.timestampMs.raw,
			latE7 = location.coordinate.lat.raw,
			lonE7 = location.coordinate.lon.raw,
			speedMps = location.speed?.raw,
			horizontalAccuracyM = location.horizontalAccuracyM,
			activityType = signal.activity?.type,
			activityConfidence = signal.activity?.confidence?.raw,
			stepDelta = signal.steps?.stepDelta?.raw ?: 0,
			distanceDeltaM = location.distanceDelta?.raw,
		)

		queueEvent(detector.onSignal(segSignal), signal.timestampMs)
	}

	private fun queueEvent(event: SegmentEvent?, timestamp: EpochMs) {
		when (event) {
			null -> Unit
			is SegmentEvent.TripStarted -> {
				pendingEvents.add(
					DomainEvent.TripStarted(
						timestampMs = timestamp,
						processorId = descriptor.id,
						triggerActivity = event.triggerActivity,
					),
				)
			}
			is SegmentEvent.TripEnded -> {
				onTripCompleted?.invoke()
				pendingEvents.add(
					DomainEvent.TripCompleted(
						timestampMs = timestamp,
						processorId = descriptor.id,
						tripStartMs = EpochMs(event.startTimeMs),
						distance = DistanceM.coerced(event.totalDistanceM),
						steps = StepCount.coerced(event.totalSteps),
						duration = DurationMs((event.endTimeMs - event.startTimeMs).coerceAtLeast(0L)),
						primaryMode = event.inferredTransportMode,
					),
				)
			}
			is SegmentEvent.TripUpdated,
			is SegmentEvent.DepartureCancelled -> {
				// No domain events for these
			}
		}
	}

	override suspend fun onFlush(): List<DomainEvent> {
		val events = pendingEvents.toList()
		pendingEvents.clear()
		return events
	}

	override suspend fun onStop(): List<DomainEvent> {
		val finalTimestamp = lastLocationTimestamp
		if (finalTimestamp == null) {
			detector.reset()
		} else {
			queueEvent(detector.forceEnd(finalTimestamp.raw), finalTimestamp)
		}
		lastLocationTimestamp = null
		return onFlush()
	}
}
