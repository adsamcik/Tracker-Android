package com.adsamcik.tracker.tracker.ui.compose

import com.adsamcik.tracker.shared.model.Location
import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.ints.shouldBeExactly
import org.junit.Test

class PathMetricsAccumulatorTest {

	@Test
	fun `processes only newly appended path segments`() {
		var distanceCalls = 0
		val accumulator = PathMetricsAccumulator { _, _ ->
			distanceCalls++
			10.0
		}
		val firstPath = listOf(location(time = 0L), location(time = 1_000L))

		val first = accumulator.update(firstPath)
		val second = accumulator.update(firstPath + location(time = 2_000L))

		first.distanceMeters.shouldBeExactly(10.0)
		second.distanceMeters.shouldBeExactly(20.0)
		second.movingAverageSpeedMps.shouldBeExactly(10.0)
		distanceCalls.shouldBeExactly(2)
	}

	@Test
	fun `recomputes metrics when path is replaced`() {
		var distanceCalls = 0
		val accumulator = PathMetricsAccumulator { _, _ ->
			distanceCalls++
			5.0
		}
		accumulator.update(listOf(location(0L), location(1_000L), location(2_000L)))

		val replacement = accumulator.update(listOf(location(10_000L), location(11_000L)))

		replacement.distanceMeters.shouldBeExactly(5.0)
		replacement.movingAverageSpeedMps.shouldBeExactly(5.0)
		distanceCalls.shouldBeExactly(3)
	}

	private fun location(time: Long) = Location(
		time = time,
		latitude = 50.0,
		longitude = 14.0,
		altitude = null,
		horizontalAccuracy = null,
		verticalAccuracy = null,
		speed = null,
		speedAccuracy = null,
	)
}
