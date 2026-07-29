package com.adsamcik.tracker.map.layers.impl

import android.content.Context
import com.adsamcik.tracker.map.perf.PerformanceManager
import com.adsamcik.tracker.map.presentation.bridge.MapLibreLayerConfig
import com.adsamcik.tracker.map.presentation.udf.LatLngModel
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.mockk
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
		edgeProvider: suspend (LongRange) -> List<VehicleComplianceLayer.ComplianceEdge>,
	) : VehicleComplianceLayer(edgeProvider, PerformanceManager()) {
		suspend fun testLoadData() = loadData(context = stubContext(), bounds = null)
		fun testProcessData(input: Input, budgets: PerformanceManager.PerformanceBudgets) =
			processData(input, budgets)

		fun testProduceConfig(prepared: Prepared) = produceConfig(prepared)
	}

	private fun edge(
		ratio: Float,
		vararg points: Pair<Double, Double>,
		gapBefore: Boolean = false,
	) = VehicleComplianceLayer.ComplianceEdge(
		ratio = ratio,
		path = points.map { LatLngModel(it.first, it.second) },
		gapBefore = gapBefore,
	)

	private fun layerFor(
		edges: List<VehicleComplianceLayer.ComplianceEdge>,
	): TestableVehicleComplianceLayer = TestableVehicleComplianceLayer(edgeProvider = { edges })

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
		fun `0_9 is AT_LIMIT (lower boundary inclusive)`() {
			ComplianceBucket.forRatio(0.9f) shouldBe ComplianceBucket.AT_LIMIT
		}

		@Test
		fun `1_1 is SLIGHTLY_OVER (lower boundary inclusive)`() {
			ComplianceBucket.forRatio(1.1f) shouldBe ComplianceBucket.SLIGHTLY_OVER
		}

		@Test
		fun `1_3 is SPEEDING (lower boundary inclusive)`() {
			ComplianceBucket.forRatio(1.3f) shouldBe ComplianceBucket.SPEEDING
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
		fun `NaN is unavailable rather than at limit`() {
			ComplianceBucket.forRatio(Float.NaN).shouldBeNull()
		}

		@Test
		fun `negative ratio is unavailable`() {
			ComplianceBucket.forRatio(-0.1f).shouldBeNull()
		}

		@Test
		fun `infinite ratio is unavailable`() {
			ComplianceBucket.forRatio(Float.POSITIVE_INFINITY).shouldBeNull()
		}
	}

	@Nested
	@DisplayName("loadData + processData")
	inner class Loading {
		@Test
		fun `empty edge list produces no config`() = runTest {
			val layer = layerFor(emptyList())
			val input = layer.testLoadData()
			val prepared = layer.testProcessData(input, PerformanceManager().acquireBudgets())
			prepared.perBucket.shouldHaveSize(0)
			prepared.bounds.shouldBeNull()
			layer.testProduceConfig(prepared).shouldBeNull()
		}

		@Test
		fun `contiguous same-bucket edges stitch into a single Line`() = runTest {
			val edges = listOf(
				edge(1.0f, 50.0 to 14.0, 50.001 to 14.001),
				edge(1.05f, 50.001 to 14.001, 50.002 to 14.002),
				edge(0.95f, 50.002 to 14.002, 50.003 to 14.003),
			)
			val layer = layerFor(edges)
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
		fun `each distinct bucket yields its own Line with a distinct colour`() = runTest {
			val edges = listOf(
				edge(0.2f, 50.0 to 14.0, 50.001 to 14.001),     // WAY_UNDER
				edge(0.7f, 50.001 to 14.001, 50.002 to 14.002), // SLOW
				edge(1.0f, 50.002 to 14.002, 50.003 to 14.003), // AT_LIMIT
				edge(1.2f, 50.003 to 14.003, 50.004 to 14.004), // SLIGHTLY_OVER
				edge(2.0f, 50.004 to 14.004, 50.005 to 14.005), // SPEEDING
			)
			val layer = layerFor(edges)
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
		fun `gapBefore keeps the same bucket but breaks the stroke`() = runTest {
			// Two AT_LIMIT spans separated by an unmatched stretch: one bucket, but the
			// second span is not contiguous with the first.
			val edges = listOf(
				edge(1.0f, 50.0 to 14.0, 50.001 to 14.001),
				edge(1.0f, 50.010 to 14.010, 50.011 to 14.011, gapBefore = true),
			)
			val layer = layerFor(edges)
			val input = layer.testLoadData()
			val prepared = layer.testProcessData(input, PerformanceManager().acquireBudgets())

			prepared.perBucket.shouldHaveSize(1)
			prepared.perBucket[0].bucket shouldBe ComplianceBucket.AT_LIMIT
			val composite = layer.testProduceConfig(prepared)
				.shouldNotBeNull()
				.shouldBeInstanceOf<MapLibreLayerConfig.Composite>()
			composite.layers.shouldHaveSize(1)
		}

		@Test
		fun `bounds reflect path coordinate range`() = runTest {
			val edges = listOf(
				edge(1.0f, 50.0 to 14.0, 50.5 to 14.5),
				edge(1.0f, 50.5 to 14.5, 50.2 to 14.3),
			)
			val layer = layerFor(edges)
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

/** VehicleComplianceLayer.loadData takes a Context but never reads it. */
private fun stubContext(): Context = mockk(relaxed = true)

/** Test budgets matching the high bucket. */
private fun PerformanceManager.acquireBudgets(): PerformanceManager.PerformanceBudgets =
	budgets(rawQuality = 1.0f)
