package com.adsamcik.tracker.map.viz

import androidx.annotation.DrawableRes
import com.adsamcik.tracker.map.presentation.bridge.MapLibreLayerConfig
import com.adsamcik.tracker.map.shared.CoordinateBounds

/** Renderer-neutral point annotation emitted by a local visualization source. */
data class SymbolFeature(
	val lat: Double,
	val lon: Double,
	val time: Long,
	val label: String,
	val iconKey: String,
	val priority: Float = 0f,
)

/** Styling resolved by [SymbolEncoder] for one semantic [SymbolFeature.iconKey]. */
data class SymbolStyle(
	@DrawableRes val iconRes: Int,
	val iconColorArgb: Int,
	val iconHaloColorArgb: Int = 0xFFFFFFFF.toInt(),
	val textColorArgb: Int = 0xFFFFFFFF.toInt(),
	val textHaloColorArgb: Int = 0xCC000000.toInt(),
)

/** Keeps the highest-priority annotations within the renderer budget. */
class SymbolAggregator(
	private val maxSymbols: Int = 1_000,
) : Aggregator<SymbolFeature, SpatialData.Symbols> {
	override fun aggregate(features: List<SymbolFeature>, ctx: AggContext): SpatialData.Symbols {
		val budget = minOf(maxSymbols, ctx.maxPoints.coerceAtLeast(1))
		val points = if (features.size > budget) {
			features.sortedByDescending(SymbolFeature::priority).take(budget)
		} else {
			features
		}
		return SpatialData.Symbols(points)
	}
}

/**
 * Lowers semantic symbols to one native SymbolLayer config per icon family. Keeping each family in
 * its own config lets Compose and the snapshot renderer register one constant SDF image per layer.
 */
class SymbolEncoder(
	private val styles: Map<String, SymbolStyle>,
	private val iconSizeDp: Float = 24f,
	private val textSizeSp: Float = 12f,
	private val allowOverlap: Boolean = false,
) : Encoder<SpatialData.Symbols> {

	override fun encode(field: SpatialData.Symbols, ctx: RenderContext): MapLibreLayerConfig? {
		if (field.points.isEmpty()) return null
		val missingStyles = field.points.map(SymbolFeature::iconKey).distinct().filterNot(styles::containsKey)
		require(missingStyles.isEmpty()) { "Missing symbol styles for: ${missingStyles.joinToString()}" }

		val configs = field.points.groupBy(SymbolFeature::iconKey).map { (iconKey, points) ->
			val style = requireNotNull(styles[iconKey])
			MapLibreLayerConfig.Symbol(
				geoJson = points.toSymbolGeoJson(),
				iconRes = style.iconRes,
				iconColorArgb = style.iconColorArgb,
				iconHaloColorArgb = style.iconHaloColorArgb,
				iconSizeDp = iconSizeDp,
				textColorArgb = style.textColorArgb,
				textHaloColorArgb = style.textHaloColorArgb,
				textSizeSp = textSizeSp,
				allowOverlap = allowOverlap,
				bounds = points.coordinateBoundsOrNull(),
			)
		}
		return if (configs.size == 1) configs.single() else MapLibreLayerConfig.Composite(configs)
	}
}

/** Type-safe DSL terminal for [SpatialData.Symbols]. */
fun <Feature> EncodeStep<Feature, SpatialData.Symbols>.symbols(
	styles: Map<String, SymbolStyle>,
	iconSizeDp: Float = 24f,
	textSizeSp: Float = 12f,
	allowOverlap: Boolean = false,
): VizPipeline<Feature, SpatialData.Symbols> =
	encode(SymbolEncoder(styles, iconSizeDp, textSizeSp, allowOverlap))

private fun List<SymbolFeature>.toSymbolGeoJson(): String {
	val sb = StringBuilder(size * 120 + 100)
	sb.append("""{"type":"FeatureCollection","features":[""")
	forEachIndexed { index, point ->
		if (index > 0) sb.append(',')
		sb.append("""{"type":"Feature","geometry":{"type":"Point","coordinates":[""")
		sb.append(point.lon).append(',').append(point.lat)
		sb.append("""]},"properties":{"label":""")
		sb.appendJsonString(point.label)
		sb.append("""}}""")
	}
	sb.append("""]}""")
	return sb.toString()
}

private fun StringBuilder.appendJsonString(value: String) {
	append('"')
	value.forEach { char ->
		when (char) {
			'"' -> append("\\\"")
			'\\' -> append("\\\\")
			'\b' -> append("\\b")
			'\u000C' -> append("\\f")
			'\n' -> append("\\n")
			'\r' -> append("\\r")
			'\t' -> append("\\t")
			else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
		}
	}
	append('"')
}

private fun List<SymbolFeature>.coordinateBoundsOrNull(): CoordinateBounds? {
	if (isEmpty()) return null
	return CoordinateBounds(
		topBound = maxOf(SymbolFeature::lat),
		rightBound = maxOf(SymbolFeature::lon),
		bottomBound = minOf(SymbolFeature::lat),
		leftBound = minOf(SymbolFeature::lon),
	)
}
