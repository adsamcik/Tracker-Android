package com.adsamcik.tracker.stats.engine.processor

import com.adsamcik.tracker.stats.api.DetectedActivityType
import com.adsamcik.tracker.stats.api.processor.ProcessorContext
import com.adsamcik.tracker.stats.api.signal.ActivitySignal
import com.adsamcik.tracker.stats.api.signal.TrackingSignal
import com.adsamcik.tracker.stats.api.value.ActivityConfidence
import com.adsamcik.tracker.stats.api.value.EpochMs
import com.adsamcik.tracker.stats.engine.segment.SessionSegmentDetector
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class SegmentDetectorProcessorTest {

	private fun signalWithoutLocation(timestampMs: Long = 1000L) = TrackingSignal(
		timestampMs = EpochMs(timestampMs),
		activity = ActivitySignal(
			type = DetectedActivityType.WALKING,
			confidence = ActivityConfidence(80),
		),
	)

	@Test
	fun `signal without location produces no events`() = runTest {
		val processor = SegmentDetectorProcessor(SessionSegmentDetector())
		processor.onStart(ProcessorContext(startTimestamp = EpochMs(1000L)))

		processor.onSignal(signalWithoutLocation(2000L))

		val events = processor.onFlush()
		events.shouldBeEmpty()
	}

	@Test
	fun `flush returns pending events and clears buffer`() = runTest {
		val processor = SegmentDetectorProcessor(SessionSegmentDetector())
		processor.onStart(ProcessorContext(startTimestamp = EpochMs(1000L)))

		// No signals means no events
		val firstFlush = processor.onFlush()
		firstFlush.shouldBeEmpty()

		// Second flush also empty since buffer was cleared
		val secondFlush = processor.onFlush()
		secondFlush.shouldBeEmpty()
	}

	@Test
	fun `stop calls reset and returns remaining events via onFlush`() = runTest {
		val processor = SegmentDetectorProcessor(SessionSegmentDetector())
		processor.onStart(ProcessorContext(startTimestamp = EpochMs(1000L)))

		// Feed signals without location — no events generated
		processor.onSignal(signalWithoutLocation(2000L))

		val stopEvents = processor.onStop()
		stopEvents.shouldBeEmpty()

		// After stop, flush should also be empty (buffer cleared)
		val postStopFlush = processor.onFlush()
		postStopFlush.shouldBeEmpty()
	}
}
