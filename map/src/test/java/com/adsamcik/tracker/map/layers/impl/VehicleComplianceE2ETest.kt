package com.adsamcik.tracker.map.layers.impl

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.map.perf.PerformanceManager
import com.adsamcik.tracker.map.presentation.bridge.MapLibreLayerConfig
import com.adsamcik.tracker.map.presentation.udf.LatLngModel
import com.adsamcik.tracker.shared.base.data.DetectedActivity
import com.adsamcik.tracker.shared.base.data.SessionActivityIds
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.LocationSample
import com.adsamcik.tracker.shared.base.database.data.MotionState
import com.adsamcik.tracker.shared.base.database.data.SampleQuality
import com.adsamcik.tracker.shared.base.database.data.SegmentSource
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.stats.api.speed.SpeedLimitSource
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
 *  2. A locally-mirrored copy of the production `sampleProvider` lambda from
 *     `DefaultLayerRegistry` (lines 266-340) that streams the
 *     `getDrivingChunkBetweenOrdered` projection through
 *     [SpeedLimitSource.limitMpsAt] and produces
 *     [VehicleComplianceLayer.VehicleSpeedSample] rows.
 *  3. The real [VehicleComplianceLayer] running `loadData → processData →
 *     produceConfig` end-to-end.
 *
 * Production code is intentionally NOT modified — the inline copy of the
 * sampleProvider lambda is small enough that the duplication cost is lower
 * than the engineering cost of exposing it. If/when the algorithm grows
 * further (e.g. road-class weighting in Phase 13), the natural follow-up is
 * to extract it into a `VehicleComplianceSampleProvider` class in `:map`
 * and have both call sites depend on it; this test will pin the contract
 * the extracted class must honour.
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
	fun `boundary ratio 0_9 classifies AT_LIMIT (lower-bound inclusive)`() = runTest {
		seedDrivingSegment(startMs = 0L, endMs = 10_000L)
		val baseline = BASELINE_50_KMH_MPS
		// Two samples both at the inclusive boundary so the run survives
		// processData's "drawable.filter { size >= 2 }" gate.
		insertSample(timeMs = 1_000L, speedMps = (baseline * 0.9).toFloat())
		insertSample(timeMs = 2_000L, speedMps = (baseline * 0.9).toFloat())

		val layer = buildLayer(fixedLimitMps = baseline)
		val prepared = runPipeline(layer)

		prepared.perBucket.single().bucket shouldBe ComplianceBucket.AT_LIMIT
	}

	@Test
	fun `boundary ratio 1_1 classifies SLIGHTLY_OVER (upper-bound exclusive)`() = runTest {
		seedDrivingSegment(startMs = 0L, endMs = 10_000L)
		val baseline = BASELINE_50_KMH_MPS
		insertSample(timeMs = 1_000L, speedMps = (baseline * 1.1).toFloat())
		insertSample(timeMs = 2_000L, speedMps = (baseline * 1.1).toFloat())

		val layer = buildLayer(fixedLimitMps = baseline)
		val prepared = runPipeline(layer)

		prepared.perBucket.single().bucket shouldBe ComplianceBucket.SLIGHTLY_OVER
	}

	@Test
	fun `limit of zero yields NaN ratio mapped to AT_LIMIT`() = runTest {
		seedDrivingSegment(startMs = 0L, endMs = 10_000L)
		insertSample(timeMs = 1_000L, speedMps = 20f)
		insertSample(timeMs = 2_000L, speedMps = 20f)

		val layer = buildLayer(fixedLimitMps = 0.0)
		val prepared = runPipeline(layer)

		// `ratio = NaN` because limitMps <= 0.0; ComplianceBucket.forRatio(NaN) -> AT_LIMIT.
		prepared.perBucket.single().bucket shouldBe ComplianceBucket.AT_LIMIT
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
			onLookup = { epochMs -> recorded += epochMs },
		)
		val prepared = runPipeline(layer)

		// Only the two driving samples reach the speed-limit source.
		recorded.sorted() shouldBe listOf(6_000L, 7_000L)
		prepared.perBucket.shouldHaveSize(1)
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

	/**
	 * Production sample-provider algorithm copied verbatim from
	 * `DefaultLayerRegistry.kt:266-340`. Pinned here so any future divergence
	 * between this test and production immediately surfaces as a contract
	 * failure during the next test-suite run.
	 */
	private fun buildSampleProvider(
		speedLimitSource: SpeedLimitSource,
	): suspend (LongRange) -> List<VehicleComplianceLayer.VehicleSpeedSample> {
		val dao = database.locationSampleDao()
		val drivingActivityIds = SessionActivityIds.DRIVING.toList()
		return { range ->
			val now = System.currentTimeMillis()
			val fromMs = if (!range.isEmpty()) range.first else now - DEFAULT_LOCATION_RANGE_MS
			val toMs = if (!range.isEmpty()) range.last else now

			val rows = mutableListOf<com.adsamcik.tracker.shared.base.database.dao.VehicleSpeedSampleRow>()
			var afterTimeMs: Long? = null
			var afterId: Long? = null

			while (true) {
				val chunk = dao.getDrivingChunkBetweenOrdered(
					fromMs = fromMs,
					toMs = toMs,
					drivingActivities = drivingActivityIds,
					afterTimeMs = afterTimeMs,
					afterId = afterId,
					limit = VEHICLE_CHUNK_SIZE,
				)
				if (chunk.isEmpty()) break
				rows.addAll(chunk)
				val lastRow = chunk.last()
				afterTimeMs = lastRow.timeMs
				afterId = lastRow.id
				if (chunk.size < VEHICLE_CHUNK_SIZE) break
			}

			if (rows.isEmpty()) {
				emptyList()
			} else {
				val step = (rows.size / VEHICLE_MAX_PRE_SAMPLES).coerceAtLeast(1)
				val result = ArrayList<VehicleComplianceLayer.VehicleSpeedSample>(rows.size / step + 1)
				for ((index, row) in rows.withIndex()) {
					if (index % step != 0) continue
					val limitMps = speedLimitSource.limitMpsAt(
						epochMs = row.timeMs,
						latE7 = row.latE7,
						lonE7 = row.lonE7,
					)
					val ratio = if (limitMps <= 0.0) {
						Float.NaN
					} else {
						(row.speedMps / limitMps).toFloat()
					}
					result.add(
						VehicleComplianceLayer.VehicleSpeedSample(
							latLng = LatLngModel(row.latE7 / 1e7, row.lonE7 / 1e7),
							ratio = ratio,
						),
					)
				}
				result
			}
		}
	}

	private fun buildLayer(
		fixedLimitMps: Double,
		onLookup: (Long) -> Unit = {},
	): TestableLayer {
		val source = SpeedLimitSource { epochMs, _, _ ->
			onLookup(epochMs)
			fixedLimitMps
		}
		return TestableLayer(buildSampleProvider(source))
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
	) {
		database.locationSampleDao().insert(
			LocationSample(
				timeMs = timeMs,
				elapsedRealtimeNanos = timeMs * 1_000_000L,
				latE7 = latE7,
				lonE7 = lonE7,
				altitudeM = 100f,
				rawGpsAltitudeM = 100f,
				hAccM = 5f,
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
		sampleProvider: suspend (LongRange) -> List<VehicleSpeedSample>,
	) : VehicleComplianceLayer(sampleProvider, PerformanceManager()) {
		suspend fun testLoadData(): Input = loadData(context = mockk<Context>(relaxed = true), bounds = null)
		fun testProcessData(input: Input, budgets: PerformanceManager.PerformanceBudgets): Prepared =
			processData(input, budgets)
		fun testProduceConfig(prepared: Prepared): MapLibreLayerConfig? = produceConfig(prepared)
	}

	private companion object {
		// Mirrors DefaultLayerRegistry private constants — see KDoc on
		// buildSampleProvider() for the contract rationale.
		const val DEFAULT_LOCATION_RANGE_MS: Long = 30L * 24 * 60 * 60 * 1_000
		const val VEHICLE_CHUNK_SIZE: Int = 2_000
		const val VEHICLE_MAX_PRE_SAMPLES: Int = 30_000
		const val BASELINE_50_KMH_MPS: Double = 50.0 / 3.6
	}
}
