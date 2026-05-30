package com.adsamcik.tracker.map.layers.impl

import com.adsamcik.tracker.map.data.Bounds
import com.adsamcik.tracker.map.perf.PerformanceManager
import com.adsamcik.tracker.map.presentation.bridge.MapLibreLayerConfig
import com.adsamcik.tracker.map.presentation.udf.LatLngModel
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for [VehicleComplianceLayer].
 *
 * The layer is constructed with a deterministic sample provider lambda so we can
 * exercise classification, run stitching, and config production without Room.
 *
 * No Android stubs are required because [HeatmapColorRamps.VehicleCompliance]
 * uses raw hex literals — `Color.rgb()` is never called in production code paths
 * exercised here.
 */
@DisplayName("VehicleComplianceLayer")
class VehicleComplianceLayerTest {

	/** Subclass that re-exposes the protected lifecycle methods for tests. */
	private class TestableVehicleComplianceLayer(
		sampleProvider: suspend (LongRange) -> List<VehicleComplianceLayer.VehicleSpeedSample>,
	) : VehicleComplianceLayer(sampleProvider, PerformanceManager()) {
		suspend fun testLoadData() = loadData(context = stubContext(), bounds = null)
		fun testProcessData(input: Input, budgets: PerformanceManager.PerformanceBudgets) =
			processData(input, budgets)

		fun testProduceConfig(prepared: Prepared) = produceConfig(prepared)
	}

	private fun sample(latLng: Pair<Double, Double>, ratio: Float) =
		VehicleComplianceLayer.VehicleSpeedSample(
			latLng = LatLngModel(latLng.first, latLng.second),
			ratio = ratio,
		)

	private fun layerFor(
		samples: List<VehicleComplianceLayer.VehicleSpeedSample>,
	): TestableVehicleComplianceLayer = TestableVehicleComplianceLayer(
		sampleProvider = { samples },
	)

	@Nested
	@DisplayName("ComplianceBucket.forRatio")
	inner class ForRatio {
		@Test
		fun `0_0 is WAY_UNDER`() {
			ComplianceBucket.forRatio(0f) shouldBe ComplianceBucket.WAY_UNDER
		}

		@Test
		fun `0_49 is WAY_UNDER (upper boundary exclusive)`() {
			ComplianceBucket.forRatio(0.49f) shouldBe ComplianceBucket.WAY_UNDER
		}

		@Test
		fun `0_5 is SLOW (lower boundary inclusive)`() {
			ComplianceBucket.forRatio(0.5f) shouldBe ComplianceBucket.SLOW
		}

		@Test
		fun `1_0 is AT_LIMIT`() {
			ComplianceBucket.forRatio(1.0f) shouldBe ComplianceBucket.AT_LIMIT
		}

		@Test
		fun `1_2 is SLIGHTLY_OVER`() {
			ComplianceBucket.forRatio(1.2f) shouldBe ComplianceBucket.SLIGHTLY_OVER
		}

		@Test
		fun `2_0 is SPEEDING`() {
			ComplianceBucket.forRatio(2.0f) shouldBe ComplianceBucket.SPEEDING
		}

		@Test
		fun `NaN defaults to AT_LIMIT`() {
			ComplianceBucket.forRatio(Float.NaN) shouldBe ComplianceBucket.AT_LIMIT
		}

		@Test
		fun `negative ratio is WAY_UNDER`() {
			ComplianceBucket.forRatio(-0.1f) shouldBe ComplianceBucket.WAY_UNDER
		}
	}

	@Nested
	@DisplayName("loadData + processData")
	inner class Loading {
		@Test
		fun `empty sample list produces no config`() = runTest {
			val layer = layerFor(emptyList())
			val input = layer.testLoadData()
			val prepared = layer.testProcessData(input, PerformanceManager().acquireBudgets())
			prepared.perBucket.shouldHaveSize(0)
			prepared.bounds.shouldBeNull()
			layer.testProduceConfig(prepared).shouldBeNull()
		}

		@Test
		fun `single bucket samples produce single Line in Composite`() = runTest {
			val samples = listOf(
				sample(50.0 to 14.0, 1.0f),
				sample(50.001 to 14.001, 1.05f),
				sample(50.002 to 14.002, 0.95f),
			)
			val layer = layerFor(samples)
			val input = layer.testLoadData()
			val prepared = layer.testProcessData(input, PerformanceManager().acquireBudgets())

			prepared.perBucket.shouldHaveSize(1)
			prepared.perBucket[0].bucket shouldBe ComplianceBucket.AT_LIMIT

			val config = layer.testProduceConfig(prepared)
			config.shouldNotBeNull()
			val composite = config.shouldBeInstanceOf<MapLibreLayerConfig.Composite>()
			composite.layers.shouldHaveSize(1)
			composite.layers[0].shouldBeInstanceOf<MapLibreLayerConfig.Line>()
		}

		@Test
		fun `multiple buckets produce one Line per bucket with distinct colours`() = runTest {
			val samples = listOf(
				sample(50.0 to 14.0, 0.2f),   // WAY_UNDER
				sample(50.001 to 14.001, 0.7f), // SLOW
				sample(50.002 to 14.002, 1.0f), // AT_LIMIT
				sample(50.003 to 14.003, 1.2f), // SLIGHTLY_OVER
				sample(50.004 to 14.004, 2.0f), // SPEEDING
			)
			val layer = layerFor(samples)
			val input = layer.testLoadData()
			val prepared = layer.testProcessData(input, PerformanceManager().acquireBudgets())

			val config = layer.testProduceConfig(prepared)
			config.shouldNotBeNull()
			val composite = config.shouldBeInstanceOf<MapLibreLayerConfig.Composite>()
			composite.layers.shouldHaveSize(5)
			val colours = composite.layers.map { (it as MapLibreLayerConfig.Line).colorArgb }
			colours.toSet().size shouldBe 5
		}

		@Test
		fun `single sample per bucket yields no Line for that bucket when alone`() = runTest {
			val samples = listOf(
				sample(50.0 to 14.0, 1.0f),
				sample(50.5 to 14.5, 2.0f),
			)
			val layer = layerFor(samples)
			val input = layer.testLoadData()
			val prepared = layer.testProcessData(input, PerformanceManager().acquireBudgets())

			val config = layer.testProduceConfig(prepared)
			config.shouldNotBeNull()
			val composite = config.shouldBeInstanceOf<MapLibreLayerConfig.Composite>()
			// AT_LIMIT run has 1 point (dropped), SPEEDING run has 2 (stitched from prev AT_LIMIT).
			composite.layers.shouldHaveSize(1)
		}

		@Test
		fun `bounds reflect sample coordinate range`() = runTest {
			val samples = listOf(
				sample(50.0 to 14.0, 1.0f),
				sample(50.5 to 14.5, 1.0f),
				sample(50.2 to 14.3, 1.0f),
			)
			val layer = layerFor(samples)
			val input = layer.testLoadData()
			val prepared = layer.testProcessData(input, PerformanceManager().acquireBudgets())

			val bounds = prepared.bounds
			bounds.shouldNotBeNull()
			bounds.top shouldBe 50.5
			bounds.bottom shouldBe 50.0
			bounds.right shouldBe 14.5
			bounds.left shouldBe 14.0
		}
	}
}

/** Hack: VehicleComplianceLayer.loadData takes a Context but never reads it. */
@Suppress("UNCHECKED_CAST")
private fun stubContext(): android.content.Context =
	java.lang.reflect.Proxy.newProxyInstance(
		android.content.Context::class.java.classLoader,
		arrayOf(android.content.Context::class.java),
	) { _, _, _ -> null } as android.content.Context

/** Test budgets matching the high bucket. */
private fun PerformanceManager.acquireBudgets(): PerformanceManager.PerformanceBudgets =
	budgets(rawQuality = 1.0f)
