package com.adsamcik.tracker.map.viz.catalog

import com.adsamcik.tracker.map.viz.VizRequest
import com.adsamcik.tracker.shared.model.LocationSample
import com.adsamcik.tracker.shared.model.MotionState
import com.adsamcik.tracker.shared.model.SampleQuality
import com.adsamcik.tracker.shared.model.SkiRunSegment
import com.adsamcik.tracker.shared.model.SkiSegmentType
import com.adsamcik.tracker.stats.api.repository.LocationSampleRepository
import com.adsamcik.tracker.stats.api.repository.SkiRunSegmentRepository
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Ski X-ray")
class SkiXRayTest {

	@Test
	fun `anchors a segment to the sample nearest its midpoint`() = runTest {
		val segment = SkiRunSegment(
			sessionId = 7L,
			runIndex = 0,
			segmentType = SkiSegmentType.DOWNHILL_RUN,
			startTimeMs = 1_000L,
			endTimeMs = 3_000L,
			verticalM = 240f,
			distanceM = 1_500f,
			maxSpeedMps = 14f,
			avgSpeedMps = 10f,
			createdAt = 3_000L,
		)
		val locationRepository = FakeLocationRepository(
			listOf(
				sample(timeMs = 1_200L, latE7 = 500_000_000),
				sample(timeMs = 2_100L, latE7 = 510_000_000),
			),
		)
		val source = skiXRaySource(
			skiRepository = FakeSkiRepository(listOf(segment)),
			locationRepository = locationRepository,
		)

		val symbols = source.load(VizRequest(0L..Long.MAX_VALUE, bounds = null))

		symbols shouldHaveSize 1
		symbols.single().lat shouldBe 51.0
		symbols.single().iconKey shouldBe SKI_ICON_DOWNHILL
		symbols.single().label shouldContain "Run 1"
		symbols.single().label shouldContain "36 km/h"
		locationRepository.samplesBetweenQueries shouldHaveSize 1
		locationRepository.nearestQueries shouldHaveSize 0
	}

	@Test
	fun `skips a segment when no nearby coordinate exists`() = runTest {
		val segment = SkiRunSegment(
			sessionId = 7L,
			runIndex = 0,
			segmentType = SkiSegmentType.LIFT_UP,
			startTimeMs = 1_000L,
			endTimeMs = 3_000L,
			verticalM = 240f,
			distanceM = 1_500f,
			maxSpeedMps = 4f,
			avgSpeedMps = 2f,
			liftType = "Chairlift",
			createdAt = 3_000L,
		)
		val source = skiXRaySource(
			skiRepository = FakeSkiRepository(listOf(segment)),
			locationRepository = FakeLocationRepository(
				listOf(sample(timeMs = 60 * 60_000L, latE7 = 500_000_000)),
			),
		)

		source.load(VizRequest(0L..Long.MAX_VALUE, bounds = null)) shouldHaveSize 0
	}

	@Test
	fun `distant ski sessions use separate bounded sample windows`() = runTest {
		val yearMs = 365L * 24 * 60 * 60_000
		val segments = listOf(
			segment(startMs = 1_000L, endMs = 3_000L, runIndex = 0),
			segment(startMs = yearMs, endMs = yearMs + 2_000L, runIndex = 1),
		)
		val locationRepository = FakeLocationRepository(
			listOf(
				sample(timeMs = 2_000L, latE7 = 500_000_000),
				sample(timeMs = yearMs + 1_000L, latE7 = 510_000_000),
			),
		)

		val symbols = skiXRaySource(FakeSkiRepository(segments), locationRepository)
			.load(VizRequest(0L..Long.MAX_VALUE, bounds = null))

		symbols shouldHaveSize 2
		locationRepository.samplesBetweenQueries shouldHaveSize 2
		locationRepository.samplesBetweenQueries.forEach { (fromMs, toMs) ->
			(toMs - fromMs) shouldBeLessThan 1_000_000L
		}
	}

	private fun segment(startMs: Long, endMs: Long, runIndex: Int) = SkiRunSegment(
		sessionId = runIndex.toLong() + 1L,
		runIndex = runIndex,
		segmentType = SkiSegmentType.DOWNHILL_RUN,
		startTimeMs = startMs,
		endTimeMs = endMs,
		verticalM = 100f,
		distanceM = 1_000f,
		maxSpeedMps = 12f,
		avgSpeedMps = 8f,
		createdAt = endMs,
	)

	private fun sample(timeMs: Long, latE7: Int) = LocationSample(
		timeMs = timeMs,
		elapsedRealtimeNanos = 0L,
		latE7 = latE7,
		lonE7 = 140_000_000,
		altitudeM = null,
		rawGpsAltitudeM = null,
		hAccM = 5f,
		vAccM = null,
		speedMps = null,
		speedAccuracyMps = null,
		provider = "gps",
		quality = SampleQuality.HIGH,
		motionState = MotionState.MOVING,
		policy = null,
		bucketId = null,
		createdAt = timeMs,
	)

	private class FakeSkiRepository(
		private val segments: List<SkiRunSegment>,
	) : SkiRunSegmentRepository {
		override suspend fun getSegmentsByTimeRange(startMs: Long, endMs: Long): List<SkiRunSegment> = segments
	}

	private class FakeLocationRepository(
		private val samples: List<LocationSample>,
	) : LocationSampleRepository {
		val samplesBetweenQueries = mutableListOf<Pair<Long, Long>>()
		val nearestQueries = mutableListOf<Pair<Long, Long>>()

		override suspend fun getSamplesBetween(fromMs: Long, toMs: Long): List<LocationSample> {
			samplesBetweenQueries += fromMs to toMs
			return samples.filter { it.timeMs in fromMs..toMs }
		}

		override suspend fun getNearestWithCoordinates(
			timeMs: Long,
			toleranceMs: Long,
		): LocationSample? {
			nearestQueries += timeMs to toleranceMs
			return samples
				.filter { it.latE7 != null && it.lonE7 != null }
				.minByOrNull { kotlin.math.abs(it.timeMs - timeMs) }
				?.takeIf { kotlin.math.abs(it.timeMs - timeMs) <= toleranceMs }
		}

		override suspend fun getOrderedChunkBetween(
			fromMs: Long,
			toMs: Long,
			afterTimeMs: Long?,
			afterId: Long?,
			limit: Int,
		): List<LocationSample> = error("Not used")
	}
}
