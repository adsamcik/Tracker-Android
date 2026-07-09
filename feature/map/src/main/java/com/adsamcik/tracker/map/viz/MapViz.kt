package com.adsamcik.tracker.map.viz

import com.adsamcik.tracker.map.layers.base.BaseMapLayer

/**
 * Declarative, type-safe authoring DSL for the map-visualization engine.
 *
 * A visualization is expressed as a fluent, **typestate** chain:
 * ```
 * mapViz("location_heatmap")
 *     .source(locationDensitySource(repo))       // fixes -> features
 *     .aggregate(GridHeatmapAggregator(Density))  // features -> SpatialData.WeightedCells
 *     .heatmap(HeatmapColorRamps.LocationDensity, baseRadiusPx = 20f)  // field -> render config
 * ```
 * Each step advances the builder's field type. Field-specific terminals (e.g. `heatmap`) are
 * extension functions constrained to a concrete [SpatialData] variant, so pairing an aggregator with
 * an incompatible encoder simply does not compile — the type system is the resolver.
 */

/** A fully-wired visualization: an id plus the three type-linked stages. Runs as a [BaseMapLayer]. */
class VizPipeline<Feature, F : SpatialData> internal constructor(
	val id: String,
	internal val source: VizSource<Feature>,
	internal val aggregator: Aggregator<Feature, F>,
	internal val encoder: Encoder<F>,
) {
	/** Adapt this pipeline into the map runtime's layer lifecycle (bounds, zoom, quality, cache). */
	fun toLayer(): BaseMapLayer<List<Feature>, F> = PipelineLayer(this)
}

/** DSL entry point. */
fun mapViz(id: String): SourceStep = SourceStep(id)

/** Awaiting a [source]. */
class SourceStep internal constructor(private val id: String) {
	fun <Feature> source(source: VizSource<Feature>): AggregateStep<Feature> =
		AggregateStep(id, source)
}

/** Source chosen; awaiting an [aggregate] that fixes the field type. */
class AggregateStep<Feature> internal constructor(
	private val id: String,
	private val source: VizSource<Feature>,
) {
	fun <F : SpatialData> aggregate(aggregator: Aggregator<Feature, F>): EncodeStep<Feature, F> =
		EncodeStep(id, source, aggregator)
}

/**
 * Aggregator chosen; the field type [F] is now fixed. Terminate with a field-specific encoder
 * extension (e.g. [heatmap]) or the generic [encode].
 */
class EncodeStep<Feature, F : SpatialData> internal constructor(
	@PublishedApi internal val id: String,
	@PublishedApi internal val source: VizSource<Feature>,
	@PublishedApi internal val aggregator: Aggregator<Feature, F>,
) {
	/** Generic terminal; prefer a field-specific extension where one exists. */
	fun encode(encoder: Encoder<F>): VizPipeline<Feature, F> =
		VizPipeline(id, source, aggregator, encoder)
}
