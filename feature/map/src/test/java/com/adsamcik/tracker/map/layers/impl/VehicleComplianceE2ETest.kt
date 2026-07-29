package com.adsamcik.tracker.map.layers.impl

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.map.perf.PerformanceManager
import com.adsamcik.tracker.map.presentation.bridge.MapLibreLayerConfig
import com.adsamcik.tracker.map.layers.registry.VehicleComplianceProvider
import com.adsamcik.tracker.shared.base.data.DetectedActivity
import com.adsamcik.tracker.shared.base.concurrency.DefaultDispatchersProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.LocationSample
import com.adsamcik.tracker.shared.base.database.data.MotionState
import com.adsamcik.tracker.shared.base.database.data.SampleQuality
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.stats.api.roadmatch.MatchedEdge
import com.adsamcik.tracker.stats.api.roadmatch.RoadLimitProvenance
import com.adsamcik.tracker.stats.api.roadmatch.RoadMatchStatus
import com.adsamcik.tracker.stats.api.roadmatch.RoadMatcher
import com.adsamcik.tracker.stats.api.roadmatch.RoadObservation
import com.adsamcik.tracker.stats.api.roadmatch.RoadPoint
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * End-to-end test that exercises the full Vehicle Compliance pipeline:
 *
 *  1. Real Room samples + driving session segments seeded into [AppDatabase].
 *  2. The extracted production [VehicleComplianceProvider], which streams the
 *     `getDrivingChunkBetweenOrdered` projection, builds [RoadObservation]s,
 *     runs them through a (here, fake) [RoadMatcher], and returns typed
 *     unavailable/suppression diagnostics alongside drawable edges.
 *  3. The real [VehicleComplianceLayer] running `loadData → processData →
 *     produceConfig` end-to-end.
 *
 * The road matching itself is faked with a deterministic [RoadMatcher] (see
 * [buildLayer]); real HMM/Viterbi matching is covered by `OsmHmmMapMatcherTest`
 * in `:domain:osm`.
 *
 * The five compliance buckets are pinned to the production thresholds
 * defined in [ComplianceBucket] (WAY_UNDER < 0.5, SLOW [0.5, 0.9),
 * AT_LIMIT [0.9, 1.1), SLIGHTLY_OVER [1.1, 1.3), SPEEDING ≥ 1.3).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VehicleComplianceE2ETest {

	private lateinit var context: Application
	private lateinit var database: AppDatabase

	@Before
	fun setUp() {
		context = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
	}

	@After
	fun tearDown() {
		database.close()
	}

	// region ratio computation

	@Test
	fun `samples at limit baseline produce ratio 1_0`() = runTest {
		seedDrivingSegment(startMs = 0L, endMs = 10_000L)
		insertSample(timeMs = 1_000L, speedMps = BASELINE_50_KMH_MPS.toFloat())
		insertSample(timeMs = 2_000L, speedMps = BASELINE_50_KMH_MPS.toFloat())

		val layer = buildLayer(fixedLimitMps = BASELINE_50_KMH_MPS)
		val prepared = runPipeline(layer)

		val perBucket = prepared.perBucket
		perBucket shouldHaveSize 1
		perBucket.single().bucket shouldBe ComplianceBucket.AT_LIMIT
	}

	@Test
	fun `samples well under baseline classify WAY_UNDER`() = runTest {
		seedDrivingSegment(startMs = 0L, endMs = 10_000L)
		// 10 km/h vs 50 km/h baseline → ratio 0.2 → WAY_UNDER.
		insertSample(timeMs = 1_000L, speedMps = (10.0 / 3.6).toFloat())
		insertSample(timeMs = 2_000L, speedMps = (10.0 / 3.6).toFloat())

		val layer = buildLayer(fixedLimitMps = BASELINE_50_KMH_MPS)
		val prepared = runPipeline(layer)

		prepared.perBucket.single().bucket shouldBe ComplianceBucket.WAY_UNDER
	}

	@Test
	fun `samples well above baseline classify SPEEDING`() = runTest {
		seedDrivingSegment(startMs = 0L, endMs = 10_000L)
		// 100 km/h vs 50 km/h baseline → ratio 2.0 → SPEEDING.
		insertSample(timeMs = 1_000L, speedMps = (100.0 / 3.6).toFloat())
		insertSample(timeMs = 2_000L, speedMps = (100.0 / 3.6).toFloat())

		val layer = buildLayer(fixedLimitMps = BASELINE_50_KMH_MPS)
		val prepared = runPipeline(layer)

		prepared.perBucket.single().bucket shouldBe ComplianceBucket.SPEEDING
	}

	@Test
	fun `mixed run produces one bucket per contiguous segment in canonical order`() = runTest {
		seedDrivingSegment(startMs = 0L, endMs = 20_000L)
		// Sequence: WAY_UNDER, SLOW, AT_LIMIT, SLIGHTLY_OVER, SPEEDING.
		// Each bucket gets two contiguous samples so the FIRST bucket's run also
		// reaches size 2 (processData stitches new runs to the previous point, but
		// the *first* run has no previous point, so it must grow internally).
		val baseline = BASELINE_50_KMH_MPS.toFloat()
		insertSample(timeMs = 1_000L, speedMps = baseline * 0.2f) // WAY_UNDER (0.2)
		insertSample(timeMs = 2_000L, speedMps = baseline * 0.2f) // WAY_UNDER (0.2)
		insertSample(timeMs = 3_000L, speedMps = baseline * 0.7f) // SLOW (0.7)
		insertSample(timeMs = 4_000L, speedMps = baseline * 0.7f) // SLOW (0.7)
		insertSample(timeMs = 5_000L, speedMps = baseline * 1.0f) // AT_LIMIT (1.0)
		insertSample(timeMs = 6_000L, speedMps = baseline * 1.0f) // AT_LIMIT (1.0)
		insertSample(timeMs = 7_000L, speedMps = baseline * 1.2f) // SLIGHTLY_OVER (1.2)
		insertSample(timeMs = 8_000L, speedMps = baseline * 1.2f) // SLIGHTLY_OVER (1.2)
		insertSample(timeMs = 9_000L, speedMps = baseline * 1.5f) // SPEEDING (1.5)
		insertSample(timeMs = 10_000L, speedMps = baseline * 1.5f) // SPEEDING (1.5)

		val layer = buildLayer(fixedLimitMps = BASELINE_50_KMH_MPS)
		val prepared = runPipeline(layer)

		// produceConfig iterates ComplianceBucket.entries in declaration order, so the
		// output must contain all five buckets in canonical enum order.
		prepared.perBucket.map { it.bucket } shouldBe listOf(
			ComplianceBucket.WAY_UNDER,
			ComplianceBucket.SLOW,
			ComplianceBucket.AT_LIMIT,
			ComplianceBucket.SLIGHTLY_OVER,
			ComplianceBucket.SPEEDING,
		)
	}

	@Test
	fun `speed at 95 percent of limit classifies AT_LIMIT`() = runTest {
		seedDrivingSegment(startMs = 0L, endMs = 10_000L)
		val baseline = BASELINE_50_KMH_MPS
		// Two samples so the run survives processData's "size >= 2" gate. Exact
		// bucket boundaries are covered deterministically by the forRatio unit
		// tests — here we use an unambiguous in-bucket ratio because the matched
		// road's integer km/h limit makes exact-boundary floats non-deterministic.
		insertSample(timeMs = 1_000L, speedMps = (baseline * 0.95).toFloat())
		insertSample(timeMs = 2_000L, speedMps = (baseline * 0.95).toFloat())

		val layer = buildLayer(fixedLimitMps = baseline)
		val prepared = runPipeline(layer)

		prepared.perBucket.single().bucket shouldBe ComplianceBucket.AT_LIMIT
	}

	@Test
	fun `speed at 120 percent of limit classifies SLIGHTLY_OVER`() = runTest {
		seedDrivingSegment(startMs = 0L, endMs = 10_000L)
		val baseline = BASELINE_50_KMH_MPS
		insertSample(timeMs = 1_000L, speedMps = (baseline * 1.2).toFloat())
		insertSample(timeMs = 2_000L, speedMps = (baseline * 1.2).toFloat())

		val layer = buildLayer(fixedLimitMps = baseline)
		val prepared = runPipeline(layer)

		prepared.perBucket.single().bucket shouldBe ComplianceBucket.SLIGHTLY_OVER
	}

	@Test
	fun `zero explicit limit is withheld with an invalid-limit diagnostic`() = runTest {
		seedDrivingSegment(startMs = 0L, endMs = 10_000L)
		insertSample(timeMs = 1_000L, speedMps = 20f)
		insertSample(timeMs = 2_000L, speedMps = 20f)

		val layer = buildLayer(fixedLimitMps = 0.0)
		val prepared = runPipeline(layer)

		prepared.perBucket.shouldBeEmpty()
		prepared.diagnostics.map { it.reason } shouldBe listOf(
			VehicleComplianceLayer.SuppressionReason.INVALID_LIMIT,
		)
		prepared.diagnostics.single().limitProvenance shouldBe RoadLimitProvenance.EXPLICIT_OSM_TAG
		prepared.diagnostics.single().importId shouldBe 7L
		prepared.diagnostics.single().osmWayId shouldBe 11L
	}

	@Test
	fun `heuristic and unknown limits are withheld instead of colored`() = runTest {
		seedDrivingSegment(startMs = 0L, endMs = 10_000L)
		insertSample(timeMs = 1_000L, speedMps = BASELINE_50_KMH_MPS.toFloat())
		insertSample(timeMs = 2_000L, speedMps = BASELINE_50_KMH_MPS.toFloat())

		val heuristic = runPipeline(
			buildLayer(
				fixedLimitMps = BASELINE_50_KMH_MPS,
				limitProvenance = RoadLimitProvenance.ROAD_CLASS_HEURISTIC,
			),
		)
		heuristic.perBucket.shouldBeEmpty()
		heuristic.diagnostics.map { it.reason }.toSet() shouldBe setOf(
			VehicleComplianceLayer.SuppressionReason.HEURISTIC_LIMIT,
		)

		val unknown = runPipeline(
			buildLayer(
				fixedLimitMps = BASELINE_50_KMH_MPS,
				limitProvenance = RoadLimitProvenance.UNKNOWN,
			),
		)
		unknown.perBucket.shouldBeEmpty()
		unknown.diagnostics.map { it.reason }.toSet() shouldBe setOf(
			VehicleComplianceLayer.SuppressionReason.UNKNOWN_LIMIT,
		)
	}

	@Test
	fun `ambiguous and no-path matcher outputs are withheld with typed reasons`() = runTest {
		seedDrivingSegment(startMs = 0L, endMs = 10_000L)
		insertSample(timeMs = 1_000L, speedMps = BASELINE_50_KMH_MPS.toFloat())
		insertSample(timeMs = 2_000L, speedMps = BASELINE_50_KMH_MPS.toFloat())

		val ambiguous = runPipeline(
			buildLayer(
				fixedLimitMps = BASELINE_50_KMH_MPS,
				matchStatus = RoadMatchStatus.AMBIGUOUS,
			),
		)
		ambiguous.perBucket.shouldBeEmpty()
		ambiguous.diagnostics.map { it.reason }.toSet() shouldBe setOf(
			VehicleComplianceLayer.SuppressionReason.AMBIGUOUS_MATCH,
		)

		val noPath = runPipeline(
			buildLayer(
				fixedLimitMps = BASELINE_50_KMH_MPS,
				matchStatus = RoadMatchStatus.NO_PATH,
			),
		)
		noPath.perBucket.shouldBeEmpty()
		noPath.diagnostics.map { it.reason }.toSet() shouldBe setOf(
			VehicleComplianceLayer.SuppressionReason.NO_PATH,
		)
	}

	@Test
	fun `no match is unavailable rather than a normal bucket`() = runTest {
		seedDrivingSegment(startMs = 0L, endMs = 10_000L)
		insertSample(timeMs = 1_000L, speedMps = BASELINE_50_KMH_MPS.toFloat())
		insertSample(timeMs = 2_000L, speedMps = BASELINE_50_KMH_MPS.toFloat())

		val prepared = runPipeline(
			buildLayer(
				fixedLimitMps = BASELINE_50_KMH_MPS,
				emitEdges = false,
			),
		)

		prepared.perBucket.shouldBeEmpty()
		prepared.diagnostics.map { it.reason } shouldBe listOf(
			VehicleComplianceLayer.SuppressionReason.NO_MATCH,
		)
	}

	// endregion

	// region filtering

	@Test
	fun `empty session produces null config`() = runTest {
		// No samples, no segments.
		val layer = buildLayer(fixedLimitMps = BASELINE_50_KMH_MPS)
		val prepared = runPipeline(layer)

		prepared.perBucket.shouldBeEmpty()
		layer.testProduceConfig(prepared).shouldBe(null)
	}

	@Test
	fun `samples outside any driving segment are excluded`() = runTest {
		// Walking segment 0..5000, driving 5000..10000.
		seedSegment(startMs = 0L, endMs = 5_000L, primaryActivity = DetectedActivity.WALKING.value)
		seedDrivingSegment(startMs = 5_000L, endMs = 10_000L)
		insertSample(timeMs = 1_000L, speedMps = 5f)   // walking — must be excluded
		insertSample(timeMs = 2_000L, speedMps = 5f)   // walking — must be excluded
		insertSample(timeMs = 6_000L, speedMps = 15f)  // driving
		insertSample(timeMs = 7_000L, speedMps = 15f)  // driving

		val recorded = mutableListOf<Long>()
		val layer = buildLayer(
			fixedLimitMps = BASELINE_50_KMH_MPS,
			onObservations = { observations -> recorded.addAll(observations.map { it.timeMs }) },
		)
		val prepared = runPipeline(layer)

		// Only the two driving samples reach the road matcher.
		recorded.sorted() shouldBe listOf(6_000L, 7_000L)
		prepared.perBucket.shouldHaveSize(1)
	}

	@Test
	fun `road observations retain horizontal accuracy and default only when absent`() = runTest {
		seedDrivingSegment(startMs = 0L, endMs = 10_000L)
		insertSample(timeMs = 1_000L, hAccM = 12.5f)
		insertSample(timeMs = 2_000L, hAccM = null)
		val observedAccuracies = mutableListOf<Float>()

		val layer = buildLayer(
			fixedLimitMps = BASELINE_50_KMH_MPS,
			onObservations = { observations ->
				observedAccuracies.addAll(observations.map { it.accuracyM })
			},
		)
		runPipeline(layer)

		observedAccuracies shouldBe listOf(12.5f, DEFAULT_ACCURACY_M)
	}

	@Test
	fun `single sample is not drawable - line needs at least 2 points`() = runTest {
		seedDrivingSegment(startMs = 0L, endMs = 10_000L)
		insertSample(timeMs = 1_000L, speedMps = BASELINE_50_KMH_MPS.toFloat())

		val layer = buildLayer(fixedLimitMps = BASELINE_50_KMH_MPS)
		val prepared = runPipeline(layer)

		// processData filters runs by `size >= 2`, so a single sample produces
		// an empty perBucket list and produceConfig returns null.
		prepared.perBucket.shouldBeEmpty()
		layer.testProduceConfig(prepared).shouldBe(null)
	}

	// endregion

	// region config production

	@Test
	fun `produces composite line config with one Line per bucket`() = runTest {
		seedDrivingSegment(startMs = 0L, endMs = 20_000L)
		val baseline = BASELINE_50_KMH_MPS.toFloat()
		// Two samples per bucket so the first run (WAY_UNDER) survives the
		// `size >= 2` drawable filter — see processData() in VehicleComplianceLayer.
		insertSample(timeMs = 1_000L, speedMps = baseline * 0.2f) // WAY_UNDER
		insertSample(timeMs = 2_000L, speedMps = baseline * 0.2f) // WAY_UNDER
		insertSample(timeMs = 3_000L, speedMps = baseline * 1.0f) // AT_LIMIT
		insertSample(timeMs = 4_000L, speedMps = baseline * 1.0f) // AT_LIMIT
		insertSample(timeMs = 5_000L, speedMps = baseline * 1.5f) // SPEEDING
		insertSample(timeMs = 6_000L, speedMps = baseline * 1.5f) // SPEEDING

		val layer = buildLayer(fixedLimitMps = BASELINE_50_KMH_MPS)
		val prepared = runPipeline(layer)
		val config = layer.testProduceConfig(prepared)

		val composite = config.shouldNotBeNull().shouldBeInstanceOf<MapLibreLayerConfig.Composite>()
		// Three buckets visited → three Line configs. Each is one Line per bucket.
		composite.layers shouldHaveSize 3
		composite.layers.forEach { line ->
			line.shouldBeInstanceOf<MapLibreLayerConfig.Line>()
			(line as MapLibreLayerConfig.Line).geoJson.contains("\"type\":\"FeatureCollection\"") shouldBe true
		}
	}

	// endregion

	// --- helpers ----------------------------------------------------------------

	private fun buildLayer(
		fixedLimitMps: Double,
		onObservations: (List<RoadObservation>) -> Unit = {},
		limitProvenance: RoadLimitProvenance = RoadLimitProvenance.EXPLICIT_OSM_TAG,
		matchStatus: RoadMatchStatus = RoadMatchStatus.MATCHED,
		emitEdges: Boolean = true,
	): TestableLayer {
		val limitKmh = Math.round(fixedLimitMps * 3.6).toInt()
		// Deterministic matcher: one edge per consecutive observation pair, snapping
		// to the observation coordinates unchanged and using a fixed road limit.
		val matcher = RoadMatcher { observations ->
			onObservations(observations)
			if (observations.size < 2 || !emitEdges) {
				emptyList()
			} else {
				(1 until observations.size).map { i ->
					MatchedEdge(
						fromIndex = i - 1,
						toIndex = i,
						path = listOf(
							RoadPoint(observations[i - 1].latE7, observations[i - 1].lonE7),
							RoadPoint(observations[i].latE7, observations[i].lonE7),
						),
						maxspeedKmh = limitKmh,
						limitProvenance = limitProvenance,
						matchStatus = matchStatus,
						importId = 7L,
						osmWayId = 11L,
					)
				}
			}
		}
		val provider = VehicleComplianceProvider(
			dao = database.locationSampleDao(),
			roadMatcher = matcher,
			dispatchers = DefaultDispatchersProvider,
		)
		return TestableLayer(provider::load)
	}

	private suspend fun runPipeline(layer: TestableLayer): VehicleComplianceLayer.Prepared {
		val input = layer.testLoadData()
		return layer.testProcessData(input, PerformanceManager().budgets(rawQuality = 1.0f))
	}

	private suspend fun insertSample(
		timeMs: Long,
		speedMps: Float? = 15f,
		latE7: Int = 500_000_000,
		lonE7: Int = 140_000_000,
		hAccM: Float? = 5f,
	) {
		database.locationSampleDao().insert(
			LocationSample(
				timeMs = timeMs,
				elapsedRealtimeNanos = timeMs * 1_000_000L,
				latE7 = latE7,
				lonE7 = lonE7,
				altitudeM = 100f,
				rawGpsAltitudeM = 100f,
				hAccM = hAccM,
				vAccM = 10f,
				speedMps = speedMps,
				speedAccuracyMps = 0.5f,
				provider = "fused",
				quality = SampleQuality.HIGH,
				motionState = MotionState.MOVING,
				policy = null,
				bucketId = null,
				createdAt = timeMs,
			),
		)
	}

	private suspend fun seedDrivingSegment(startMs: Long, endMs: Long) {
		seedSegment(startMs, endMs, DetectedActivity.IN_VEHICLE.value)
	}

	private suspend fun seedSegment(startMs: Long, endMs: Long, primaryActivity: Int) {
		database.sessionSegmentDao().insert(
			SessionSegment(
				startTimeMs = startMs,
				endTimeMs = endMs,
				distanceM = 0f,
				steps = 0,
				primaryActivity = primaryActivity,
				activityConfidence = 90,
				sampleCount = 10,
				source = SegmentSource.USER_CREATED,
				inferenceVersion = "test",
				createdAt = startMs,
			),
		)
	}

	/** Re-exposes the protected lifecycle methods for tests. */
	private class TestableLayer(
		resultProvider: suspend (LongRange) -> ProviderResult,
	) : VehicleComplianceLayer(resultProvider, PerformanceManager()) {
		suspend fun testLoadData(): Input = loadData(context = mockk<Context>(relaxed = true), bounds = null)
		fun testProcessData(input: Input, budgets: PerformanceManager.PerformanceBudgets): Prepared =
			processData(input, budgets)
		fun testProduceConfig(prepared: Prepared): MapLibreLayerConfig? = produceConfig(prepared)
	}

	private companion object {
		const val BASELINE_50_KMH_MPS: Double = 50.0 / 3.6
		const val DEFAULT_ACCURACY_M: Float = 8f
	}
}
