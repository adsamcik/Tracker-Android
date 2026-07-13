package com.adsamcik.tracker.map.viz

import com.adsamcik.tracker.map.data.Bounds
import com.adsamcik.tracker.map.presentation.bridge.MapLibreLayerConfig

/**
 * The three stage contracts of the map-visualization engine: `Source -> Aggregator -> Encoder`.
 *
 * The stages are type-linked through [SpatialData]: an `Aggregator<*, F>` can only feed an
 * `Encoder<in F>`, so an incompatible pairing is a **compile error** rather than a runtime failure —
 * this is what replaces a runtime resolver/capability-negotiation step. Cross-cutting guarantees
 * (local-only sources, stable/perceptual colour, viewport+generation caching) are provided by the
 * runtime and shared services, not re-implemented per stage.
 */

/** Viewport + time window handed to a [VizSource] on each load. */
data class VizRequest(
	val dateRange: LongRange,
	val bounds: Bounds?,
	val maxFeatures: Int? = null,
)

/** Zoom, render quality and the per-refresh point budget available to an [Aggregator]. */
data class AggContext(
	val zoom: Float,
	val quality: Float,
	val maxPoints: Int,
)

/** Render-time inputs (e.g. quality-scaled radius) available to an [Encoder]. */
data class RenderContext(
	val quality: Float,
)

/**
 * **Stage 1 — Source.** Loads local features for the [request]. Local-only by contract: a source
 * queries on-device storage only (never the network).
 */
fun interface VizSource<out Feature> {
	suspend fun load(request: VizRequest): List<Feature>
}

/** **Stage 2 — Aggregator.** Reduces raw features into a typed [SpatialData] field. */
fun interface Aggregator<in Feature, out F : SpatialData> {
	fun aggregate(features: List<Feature>, ctx: AggContext): F
}

/**
 * **Stage 3 — Encoder.** Lowers a typed field + style into a MapLibre render config. `in F` makes
 * the field type contravariant so only encoders that accept the aggregator's output type compile.
 */
fun interface Encoder<in F : SpatialData> {
	fun encode(field: F, ctx: RenderContext): MapLibreLayerConfig?
}
