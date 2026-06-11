package com.adsamcik.tracker.stats.engine.processor

import com.adsamcik.tracker.stats.api.event.DomainEvent
import com.adsamcik.tracker.stats.api.processor.ProcessorContext
import com.adsamcik.tracker.stats.api.signal.LocationSignal
import com.adsamcik.tracker.stats.api.signal.TrackingSignal
import com.adsamcik.tracker.stats.api.value.CoordinateE7
import com.adsamcik.tracker.stats.api.value.DistanceM
import com.adsamcik.tracker.stats.api.value.EpochMs
import com.adsamcik.tracker.stats.api.value.LatE7
import com.adsamcik.tracker.stats.api.value.LonE7
import com.adsamcik.tracker.stats.api.value.SpeedMps
import com.adsamcik.tracker.stats.engine.exploration.CellDiscoveryEngine
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class ExplorationProcessorTest {

	private fun locationSignal(timestampMs: Long, lat: Double = 49.2, lon: Double = 16.6) =
		TrackingSignal(
			timestampMs = EpochMs(timestampMs),
			location = LocationSignal(
				coordinate = CoordinateE7(
					lat = LatE7.fromDegrees(lat),
					lon = LonE7.fromDegrees(lon),
				),
				horizontalAccuracyM = 5.0f,
				speed = SpeedMps.coerced(1.0f),
				altitudeM = 200f,
				distanceDelta = DistanceM.coerced(10f),
			),
		)

	private fun signalWithoutLocation(timestampMs: Long) = TrackingSignal(
		timestampMs = EpochMs(timestampMs),
	)

	@Test
	fun `location signal at new cell produces CellDiscovered on flush`() = runTest {
		val processor = ExplorationProcessor(CellDiscoveryEngine())
		processor.onStart(ProcessorContext(startTimestamp = EpochMs(1000L)))

		processor.onSignal(locationSignal(timestampMs = 2000L))

		val events = processor.onFlush()
		events shouldHaveSize 1
		events.first().shouldBeInstanceOf<DomainEvent.CellDiscovered>()
	}

	@Test
	fun `no-location signal is ignored - no events`() = runTest {
		val processor = ExplorationProcessor(CellDiscoveryEngine())
		processor.onStart(ProcessorContext(startTimestamp = EpochMs(1000L)))

		processor.onSignal(signalWithoutLocation(2000L))

		val events = processor.onFlush()
		events.shouldBeEmpty()
	}

	@Test
	fun `flush drains pending events - second flush returns empty`() = runTest {
		val processor = ExplorationProcessor(CellDiscoveryEngine())
		processor.onStart(ProcessorContext(startTimestamp = EpochMs(1000L)))

		processor.onSignal(locationSignal(timestampMs = 2000L))

		val firstFlush = processor.onFlush()
		firstFlush shouldHaveSize 1

		val secondFlush = processor.onFlush()
		secondFlush.shouldBeEmpty()
	}
}
