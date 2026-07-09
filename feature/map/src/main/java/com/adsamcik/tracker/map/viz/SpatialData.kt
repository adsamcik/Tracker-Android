package com.adsamcik.tracker.map.viz

import com.adsamcik.tracker.map.data.WeightedGeoFeature

/**
 * The engine's intermediate representation — a small, closed vocabulary of aggregated "data shapes"
 * that every visualization passes through between aggregation ([Aggregator]) and encoding
 * ([Encoder]). This is the stable waist of the map-visualization engine.
 *
 * Sealed for a discoverable, exhaustive vocabulary. Lowering a field to a render config is done by a
 * type-linked [Encoder] (`Encoder<in F>`), NOT via a central `when`, so adding a variant never
 * forces edits across existing encoders — it simply gains its own compatible encoders. New variants
 * are added only when a real visualization needs a shape that no existing variant can carry.
 */
sealed interface SpatialData {

	/**
	 * Aggregated weighted grid cells fed to MapLibre's native GPU heatmap. Each cell's [weight]
	 * (carried in [WeightedGeoFeature.weight], in `[0, 1]`) is the density (count) or value (mean)
	 * the colour ramp maps to a colour. Backs the location/speed/cell/wifi heatmap family.
	 */
	data class WeightedCells(val cells: List<WeightedGeoFeature>) : SpatialData

	/**
	 * Discrete tessellation tiles (rectangular grid cells) each carrying a normalised `[0, 1]` weight,
	 * rendered as coloured filled polygons ([MapLibreLayerConfig.Fill][com.adsamcik.tracker.map.presentation.bridge.MapLibreLayerConfig.Fill]).
	 * Backs the legacy square-tile heatmap; the second render shape proves the engine is not
	 * heatmap-only and that field/encoder pairing is enforced at compile time.
	 */
	data class FillCells(val tiles: List<com.adsamcik.tracker.map.data.GridTile>) : SpatialData

	/**
	 * Point markers rendered as circles ([MapLibreLayerConfig.Circle][com.adsamcik.tracker.map.presentation.bridge.MapLibreLayerConfig.Circle]),
	 * each carrying a normalised `[0, 1]` weight that can drive its colour and radius. Backs
	 * place-marker visualizations (e.g. frequent places sized by visit count).
	 */
	data class Markers(val points: List<WeightedGeoFeature>) : SpatialData

	/**
	 * An ordered polyline whose vertices each carry a normalised `[0, 1]` weight, rendered as a single
	 * gradient-coloured line ([MapLibreLayerConfig.GradientLine][com.adsamcik.tracker.map.presentation.bridge.MapLibreLayerConfig.GradientLine]).
	 * The weight varies *along* the path — e.g. speed, altitude or activity — so the colour flows
	 * smoothly from one end to the other. Backs "ribbon" visualizations (speed ribbon, altitude
	 * ribbon, …); the ordering is the path order (typically by time).
	 */
	data class Segments(val path: List<WeightedGeoFeature>) : SpatialData
}
