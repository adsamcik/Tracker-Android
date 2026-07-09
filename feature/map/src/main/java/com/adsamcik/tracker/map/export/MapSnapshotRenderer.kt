package com.adsamcik.tracker.map.export

import android.content.Context
import android.graphics.Bitmap
import com.adsamcik.tracker.map.data.GeoJsonConverter
import com.adsamcik.tracker.map.presentation.bridge.MapLibreLayerConfig
import com.adsamcik.tracker.map.presentation.udf.LatLngModel
import com.adsamcik.tracker.shared.base.concurrency.DefaultDispatchersProvider
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.maplibre.android.camera.CameraPosition as ClassicCameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.Style
import org.maplibre.android.snapshotter.MapSnapshot
import org.maplibre.android.snapshotter.MapSnapshotter
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.HeatmapLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.style.BaseStyle
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Thrown when MapLibre's native snapshotter fails to render (bad style, no network for online tiles, etc). */
class MapSnapshotException(message: String) : Exception(message)

/**
 * Renders the map (base style + the currently active data layer + the active-tracking path, if
 * any) to a static [Bitmap] at an arbitrary pixel resolution, using MapLibre's classic
 * `MapSnapshotter` — entirely off-screen, independent of whatever is on screen right now and of
 * MapLibre Compose (which has no snapshot API of its own; see [MapShareResolution] kdoc).
 *
 * Deliberately does NOT replicate live/personal overlays (user location dot, accuracy circle,
 * search marker): those are transient UI, not data worth sharing, and baking the user's live
 * location into a shared image would leak it.
 */
object MapSnapshotRenderer {

	/**
	 * @param context Application context is preferred; only used to read display density and
	 *   construct the snapshotter.
	 * @param baseStyle The same resolved style the on-screen map is using (JSON or URI).
	 * @param cameraPosition The on-screen camera position to reproduce (same center/zoom/tilt/bearing).
	 * @param layerConfig The currently active data layer (heatmap/line/fill/composite), if any.
	 * @param activeTrackingPath Points of an in-progress tracking session to draw as a line, if any.
	 * @param activeTrackingColorArgb Color for [activeTrackingPath]; resolved by the caller from the
	 *   current theme (this object has no Compose context to read `MaterialTheme` itself).
	 * @param widthPx / @param heightPx Target output size in raw pixels (see [MapShareResolution]).
	 */
	@Suppress("LongParameterList")
	suspend fun render(
		context: Context,
		baseStyle: BaseStyle,
		cameraPosition: CameraPosition,
		layerConfig: MapLibreLayerConfig?,
		activeTrackingPath: List<LatLngModel>,
		activeTrackingColorArgb: Int,
		widthPx: Int,
		heightPx: Int,
		dispatchers: DispatchersProvider = DefaultDispatchersProvider,
	): Bitmap {
		// MapSnapshotter is a native MapLibre object; like every other Map* entry point in this SDK
		// it must be constructed and driven from the main thread (see MapLibreInitializer kdoc).
		val mainImmediate = (dispatchers.main as? MainCoroutineDispatcher)?.immediate ?: dispatchers.main
		return withContext(mainImmediate) {
			renderOnMainThread(
				context = context.applicationContext,
				baseStyle = baseStyle,
				cameraPosition = cameraPosition,
				layerConfig = layerConfig,
				activeTrackingPath = activeTrackingPath,
				activeTrackingColorArgb = activeTrackingColorArgb,
				widthPx = widthPx,
				heightPx = heightPx,
			)
		}
	}

	@Suppress("LongParameterList")
	private suspend fun renderOnMainThread(
		context: Context,
		baseStyle: BaseStyle,
		cameraPosition: CameraPosition,
		layerConfig: MapLibreLayerConfig?,
		activeTrackingPath: List<LatLngModel>,
		activeTrackingColorArgb: Int,
		widthPx: Int,
		heightPx: Int,
	): Bitmap = suspendCancellableCoroutine { continuation ->
		val styleBuilder = Style.Builder().apply {
			when (baseStyle) {
				is BaseStyle.Json -> fromJson(baseStyle.json)
				is BaseStyle.Uri -> fromUri(baseStyle.uri)
			}
			layerConfig?.let { addLayerConfig(this, it, "share") }
			addActiveTrackingPath(this, activeTrackingPath, activeTrackingColorArgb)
		}

		val options = MapSnapshotter.Options(widthPx, heightPx)
			.withStyleBuilder(styleBuilder)
			.withCameraPosition(cameraPosition.toClassic())
			.withPixelRatio(context.resources.displayMetrics.density)
			.withLogo(true)
			.withAttribution(true)

		val snapshotter = MapSnapshotter(context, options)
		continuation.invokeOnCancellation { snapshotter.cancel() }

		snapshotter.start(
			object : MapSnapshotter.SnapshotReadyCallback {
				override fun onSnapshotReady(snapshot: MapSnapshot) {
					if (continuation.isActive) continuation.resume(snapshot.bitmap)
				}
			},
			object : MapSnapshotter.ErrorHandler {
				override fun onError(error: String) {
					if (continuation.isActive) {
						continuation.resumeWithException(MapSnapshotException(error))
					}
				}
			},
		)
	}

	/** Mirrors MapScreen's MapDataLayers, using the classic Layer/Source builder API instead of Compose. */
	private fun addLayerConfig(builder: Style.Builder, config: MapLibreLayerConfig, idPrefix: String) {
		when (config) {
			is MapLibreLayerConfig.Composite -> config.layers.forEachIndexed { index, child ->
				addLayerConfig(builder, child, "$idPrefix-$index")
			}
			is MapLibreLayerConfig.Heatmap -> {
				val sourceId = "$idPrefix-heatmap-source"
				builder.withSource(GeoJsonSource(sourceId, config.geoJson))
				builder.withLayer(
					HeatmapLayer("$idPrefix-heatmap-layer", sourceId).withProperties(
						PropertyFactory.heatmapRadius(config.radiusPx),
						PropertyFactory.heatmapIntensity(config.intensity),
						PropertyFactory.heatmapOpacity(config.opacity),
						PropertyFactory.heatmapWeight(Expression.get(config.weightProperty)),
						PropertyFactory.heatmapColor(buildColorStopExpression(config.colorStops, Expression.heatmapDensity())),
					),
				)
			}
			is MapLibreLayerConfig.Line -> {
				val sourceId = "$idPrefix-line-source"
				builder.withSource(GeoJsonSource(sourceId, config.geoJson))
				// Casing beneath (added first = drawn under) mirrors MapScreen's realistic styling.
				config.casingColorArgb?.let { casingArgb ->
					builder.withLayer(
						LineLayer("$idPrefix-line-casing", sourceId).withProperties(
							PropertyFactory.lineColor(casingArgb),
							PropertyFactory.lineWidth(config.widthDp + config.casingWidthDp * 2f),
							PropertyFactory.lineOpacity(config.opacity),
							PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
							PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
						),
					)
				}
				builder.withLayer(
					LineLayer("$idPrefix-line-layer", sourceId).withProperties(
						PropertyFactory.lineColor(config.colorArgb),
						PropertyFactory.lineWidth(config.widthDp),
						PropertyFactory.lineOpacity(config.opacity),
						PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
						PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
					),
				)
			}
			is MapLibreLayerConfig.Fill -> {
				val sourceId = "$idPrefix-fill-source"
				builder.withSource(GeoJsonSource(sourceId, config.geoJson))
				val properties = buildList {
					add(PropertyFactory.fillColor(buildColorStopExpression(config.colorStops, Expression.get(config.weightProperty))))
					add(PropertyFactory.fillOpacity(config.opacity))
					config.outlineColorArgb?.let { add(PropertyFactory.fillOutlineColor(it)) }
				}
				builder.withLayer(
					FillLayer("$idPrefix-fill-layer", sourceId).withProperties(*properties.toTypedArray()),
				)
			}
			is MapLibreLayerConfig.FillExtrusion -> {
				val sourceId = "$idPrefix-fill-extrusion-source"
				builder.withSource(GeoJsonSource(sourceId, config.geoJson))
				builder.withLayer(
					FillExtrusionLayer("$idPrefix-fill-extrusion-layer", sourceId).withProperties(
						PropertyFactory.fillExtrusionColor(
							buildColorStopExpression(config.colorStops, Expression.get(config.weightProperty)),
						),
						PropertyFactory.fillExtrusionHeight(
							Expression.product(Expression.get(config.weightProperty), Expression.literal(config.maxHeightMeters)),
						),
						PropertyFactory.fillExtrusionOpacity(config.opacity),
						PropertyFactory.fillExtrusionVerticalGradient(true),
					),
				)
			}
			is MapLibreLayerConfig.Circle -> {
				val sourceId = "$idPrefix-circle-source"
				builder.withSource(GeoJsonSource(sourceId, config.geoJson))
				builder.withLayer(
					CircleLayer("$idPrefix-circle-layer", sourceId).withProperties(
						PropertyFactory.circleColor(
							buildColorStopExpression(config.colorStops, Expression.get(config.weightProperty)),
						),
						PropertyFactory.circleRadius(
							Expression.interpolate(
								Expression.linear(),
								Expression.get(config.weightProperty),
								Expression.stop(0f, Expression.literal(config.minRadiusDp)),
								Expression.stop(1f, Expression.literal(config.maxRadiusDp)),
							),
						),
						PropertyFactory.circleOpacity(config.opacity),
						PropertyFactory.circleStrokeColor(config.strokeColorArgb),
						PropertyFactory.circleStrokeWidth(config.strokeWidthDp),
					),
				)
			}
			is MapLibreLayerConfig.GradientLine -> {
				val sourceId = "$idPrefix-gradient-line-source"
				// line-gradient needs line-distance metrics on the source (mirrors GRADIENT_GEOJSON_OPTIONS).
				builder.withSource(GeoJsonSource(sourceId, config.geoJson, GeoJsonOptions().withLineMetrics(true)))
				config.casingColorArgb?.let { casingArgb ->
					builder.withLayer(
						LineLayer("$idPrefix-gradient-line-casing", sourceId).withProperties(
							PropertyFactory.lineColor(casingArgb),
							PropertyFactory.lineWidth(config.widthDp + config.casingWidthDp * 2f),
							PropertyFactory.lineOpacity(config.opacity),
							PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
							PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
						),
					)
				}
				builder.withLayer(
					LineLayer("$idPrefix-gradient-line-layer", sourceId).withProperties(
						PropertyFactory.lineGradient(buildGradientStopExpression(config.gradientStops)),
						PropertyFactory.lineWidth(config.widthDp),
						PropertyFactory.lineOpacity(config.opacity),
						PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
						PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
					),
				)
			}
		}
	}

	/** Mirrors MapScreen's MapActiveTrackingLayer. */
	private fun addActiveTrackingPath(
		builder: Style.Builder,
		path: List<LatLngModel>,
		colorArgb: Int,
	) {
		if (path.size < 2) return
		val sourceId = "share-active-tracking-source"
		builder.withSource(GeoJsonSource(sourceId, GeoJsonConverter.lineToFeatureCollection(path)))
		builder.withLayer(
			LineLayer("share-active-tracking-layer", sourceId).withProperties(
				PropertyFactory.lineColor(colorArgb),
				PropertyFactory.lineWidth(5f),
				PropertyFactory.lineOpacity(0.95f),
				PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
				PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
			),
		)
	}

	private fun buildColorStopExpression(colorStops: List<Pair<Float, Int>>, input: Expression): Expression {
		val stops = colorStops.map { (stop, argb) -> Expression.stop(stop, Expression.color(argb)) }.toTypedArray()
		return Expression.interpolate(Expression.linear(), input, *stops)
	}

	/** Classic-API equivalent of MapScreen's buildLineGradientExpr: (lineProgress -> ARGB) stops. */
	private fun buildGradientStopExpression(gradientStops: List<Pair<Float, Int>>): Expression {
		val stops = gradientStops.map { (progress, argb) -> Expression.stop(progress, Expression.color(argb)) }.toTypedArray()
		return Expression.interpolate(Expression.linear(), Expression.lineProgress(), *stops)
	}

	private fun CameraPosition.toClassic(): ClassicCameraPosition =
		ClassicCameraPosition.Builder()
			.target(LatLng(target.latitude, target.longitude))
			.zoom(zoom)
			.tilt(tilt)
			.bearing(bearing)
			.build()
}
