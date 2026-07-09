package com.adsamcik.tracker.map.viz

import android.content.Context
import com.adsamcik.tracker.map.data.Bounds
import com.adsamcik.tracker.map.layers.base.BaseMapLayer
import com.adsamcik.tracker.map.layers.base.SupportsDateRange
import com.adsamcik.tracker.map.perf.PerformanceManager
import com.adsamcik.tracker.map.presentation.bridge.MapLibreLayerConfig

/**
 * The single adapter that runs any [VizPipeline]'s three stages through the existing map-layer
 * lifecycle — viewport bounds, zoom, quality, per-refresh render budget, the `reloadGeneration`
 * race guard and the viewport cache all come for free from [BaseMapLayer]. Every DSL-authored
 * visualization shares this one runtime; there is no per-visualization layer subclass.
 */
internal class PipelineLayer<Feature, F : SpatialData>(
	private val pipeline: VizPipeline<Feature, F>,
) : BaseMapLayer<List<Feature>, F>(), SupportsDateRange {

	override var dateRange: LongRange = 0L..Long.MAX_VALUE

	override suspend fun loadData(context: Context, bounds: Bounds?): List<Feature> =
		pipeline.source.load(VizRequest(dateRange, bounds))

	override fun processData(
		input: List<Feature>,
		budgets: PerformanceManager.PerformanceBudgets,
	): F = pipeline.aggregator.aggregate(input, AggContext(zoom = zoom, quality = quality, maxPoints = budgets.maxPoints))

	override fun produceConfig(processed: F): MapLibreLayerConfig? =
		pipeline.encoder.encode(processed, RenderContext(quality = quality))
}
