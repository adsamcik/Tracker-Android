package com.adsamcik.tracker.map.viz

import com.adsamcik.tracker.map.data.CellRadioGeoFeature
import com.adsamcik.tracker.map.data.GeoJsonConverter
import com.adsamcik.tracker.map.data.WeightedGeoFeature
import com.adsamcik.tracker.map.data.WifiRadioGeoFeature
import com.adsamcik.tracker.map.data.haversineMeters
import com.adsamcik.tracker.map.graphics.GridAggregator
import com.adsamcik.tracker.map.presentation.bridge.LayerAnimation
import com.adsamcik.tracker.map.presentation.bridge.MapLibreLayerConfig
import com.adsamcik.tracker.shared.base.data.CellType
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

enum class RadioVisualGroup {
	WIFI_24,
	WIFI_5,
	WIFI_6,
	WIFI_OTHER,
	CELL_GSM,
	CELL_WCDMA,
	CELL_LTE,
	CELL_NR,
	CELL_OTHER,
}

data class RadioCoverage(
	val group: RadioVisualGroup,
	val cells: List<WeightedGeoFeature>,
)

data class RadioEstimate(
	val group: RadioVisualGroup,
	val center: WeightedGeoFeature,
	val confidence: Double,
	val uncertaintyMeters: Double,
)

class WifiRadioAggregator : Aggregator<WifiRadioGeoFeature, SpatialData.RadioField> {
	override fun aggregate(
		features: List<WifiRadioGeoFeature>,
		ctx: AggContext,
	): SpatialData.RadioField {
		if (features.isEmpty()) return SpatialData.RadioField(emptyList(), emptyList())
		val cellSize = radioCellSize(ctx, WIFI_COVERAGE_CELL_DEGREES)
		val coverage = aggregateCoverage(
			features = features,
			cellSizeDegrees = cellSize,
			maxPoints = ctx.maxPoints,
			group = WifiRadioGeoFeature::visualGroup,
			identity = { it.normalizedBssid().ifEmpty { UNKNOWN_WIFI_IDENTITY } },
			signalWeight = { wifiSignalWeight(it.levelDbm) },
			coordinates = { ObservationPoint(it.lat, it.lon, it.time) },
		)
		val estimates = features
			.asSequence()
			.filter { it.normalizedBssid().isNotEmpty() }
			.groupBy { it.normalizedBssid() }
			.values
			.flatMap { identityObservations ->
				identityObservations.spatialClusters(WIFI_IDENTITY_CLUSTER_METERS).mapNotNull { observations ->
					estimateRadio(
						observations = observations.map {
							EstimateObservation(
								lat = it.lat,
								lon = it.lon,
								time = it.time,
								signal = wifiSignalWeight(it.levelDbm),
								rawSignal = it.levelDbm.toDouble(),
							)
						},
						group = observations.groupingBy { it.visualGroup() }.eachCount().maxBy { it.value }.key,
						config = WIFI_ESTIMATE_CONFIG,
						confidenceMultiplier = if (observations.first().hasLocallyAdministeredBssid()) {
							LOCALLY_ADMINISTERED_CONFIDENCE_MULTIPLIER
						} else {
							1.0
						},
					)
				}
			}
			.sortedByDescending { it.confidence }
			.take(ctx.maxPoints.coerceAtMost(MAX_ESTIMATES))

		return SpatialData.RadioField(coverage, estimates)
	}
}

class WifiOverlapAggregator : Aggregator<WifiRadioGeoFeature, SpatialData.WeightedCells> {
	override fun aggregate(
		features: List<WifiRadioGeoFeature>,
		ctx: AggContext,
	): SpatialData.WeightedCells {
		if (features.isEmpty()) return SpatialData.WeightedCells(emptyList())
		val cellSize = radioCellSize(ctx, WIFI_OVERLAP_CELL_DEGREES).toDouble()
		val cells = LinkedHashMap<GridKey, OverlapCell>()
		features.forEach { feature ->
			val identity = feature.normalizedBssid()
			if (identity.isEmpty()) return@forEach
			val key = GridKey(
				floor(feature.lat / cellSize).toLong(),
				floor(feature.lon / cellSize).toLong(),
			)
			val cell = cells.getOrPut(key) { OverlapCell() }
			cell.latSum += feature.lat
			cell.lonSum += feature.lon
			cell.count++
			cell.time = max(cell.time, feature.time)
			cell.identities += identity
		}
		return SpatialData.WeightedCells(
			cells.values
				.map { cell ->
					WeightedGeoFeature(
						lat = cell.latSum / cell.count,
						lon = cell.lonSum / cell.count,
						time = cell.time,
						weight = (ln(1.0 + cell.identities.size) / ln(1.0 + WIFI_OVERLAP_SATURATION))
							.coerceIn(0.0, 1.0),
					)
				}
				.sortedByDescending { it.weight }
				.take(ctx.maxPoints),
		)
	}
}

class CellRadioAggregator : Aggregator<CellRadioGeoFeature, SpatialData.RadioField> {
	override fun aggregate(
		features: List<CellRadioGeoFeature>,
		ctx: AggContext,
	): SpatialData.RadioField {
		if (features.isEmpty()) return SpatialData.RadioField(emptyList(), emptyList())
		val signalFeatures = features.filter { feature ->
			feature.asu?.let { normalizedCellSignalWeight(it.toDouble(), feature.networkType) } != null
		}
		if (signalFeatures.isEmpty()) return SpatialData.RadioField(emptyList(), emptyList())
		val cellSize = radioCellSize(ctx, CELL_COVERAGE_CELL_DEGREES)
		val coverage = aggregateCoverage(
			features = signalFeatures,
			cellSizeDegrees = cellSize,
			maxPoints = ctx.maxPoints,
			group = CellRadioGeoFeature::visualGroup,
			identity = CellRadioGeoFeature::coverageIdentity,
			signalWeight = {
				normalizedCellSignalWeight(requireNotNull(it.asu).toDouble(), it.networkType)
					?: error("Signal validity was checked before aggregation")
			},
			coordinates = { ObservationPoint(it.lat, it.lon, it.time) },
		)
		val estimates = signalFeatures
			.asSequence()
			.filter { it.hasStableCellIdentity() }
			.groupBy(CellRadioGeoFeature::siteIdentity)
			.values
			.mapNotNull { observations ->
				estimateRadio(
					observations = observations.map {
						val asu = requireNotNull(it.asu)
						EstimateObservation(
							lat = it.lat,
							lon = it.lon,
							time = it.time,
							signal = normalizedCellSignalWeight(asu.toDouble(), it.networkType)
								?: error("Signal validity was checked before estimation"),
							rawSignal = asu.toDouble(),
						)
					},
					group = observations.first().visualGroup(),
					config = CELL_ESTIMATE_CONFIG,
				)
			}
			.sortedByDescending { it.confidence }
			.take(ctx.maxPoints.coerceAtMost(MAX_ESTIMATES))

		return SpatialData.RadioField(coverage, estimates)
	}
}

class RadioFieldEncoder private constructor(
	private val colors: Map<RadioVisualGroup, Int>,
	private val coverageRadiusPx: Float,
) : Encoder<SpatialData.RadioField> {

	override fun encode(field: SpatialData.RadioField, ctx: RenderContext): MapLibreLayerConfig? {
		val layers = buildList {
			field.coverage.forEach { coverage ->
				val color = colors[coverage.group] ?: return@forEach
				if (coverage.cells.isNotEmpty()) {
					add(
						MapLibreLayerConfig.Heatmap(
							geoJson = GeoJsonConverter.pointsToFeatureCollection(coverage.cells),
							colorStops = radioRamp(color),
							radiusPx = GridAggregator.radiusForQuality(coverageRadiusPx, ctx.quality),
							intensity = 1.05f,
							opacity = COVERAGE_OPACITY,
						),
					)
				}
			}

			field.estimates.groupBy { it.group }.forEach { (group, estimates) ->
				val color = colors[group] ?: return@forEach
				estimates.groupBy(RadioEstimate::confidenceTier).forEach { (tier, tierEstimates) ->
					add(
						MapLibreLayerConfig.Heatmap(
							geoJson = GeoJsonConverter.pointsToFeatureCollection(tierEstimates.map { it.center }),
							colorStops = uncertaintyRamp(color),
							radiusPx = GridAggregator.radiusForQuality(tier.haloRadiusPx, ctx.quality),
							intensity = 0.8f,
							opacity = tier.opacity,
						),
					)
				}
				val confident = estimates.filter {
					it.confidence >= ESTIMATE_CORE_MIN_CONFIDENCE && it.confidenceTier() != ConfidenceTier.LOW
				}
				if (confident.isNotEmpty()) {
					add(
						MapLibreLayerConfig.Circle(
							geoJson = GeoJsonConverter.pointsToFeatureCollection(confident.map { it.center }),
							colorStops = solidRamp(color),
							minRadiusDp = 3.5f,
							maxRadiusDp = 8.5f,
							opacity = 0.95f,
							strokeColorArgb = 0xE6FFFFFF.toInt(),
							strokeWidthDp = 1.4f,
							animation = LayerAnimation.Pulse(
								periodMs = 3_200,
								minScale = 0.96f,
								maxScale = 1.08f,
							),
						),
					)
				}
			}
		}

		return when (layers.size) {
			0 -> null
			1 -> layers.single()
			else -> MapLibreLayerConfig.Composite(layers)
		}
	}

	companion object {
		fun wifi(): RadioFieldEncoder = RadioFieldEncoder(WIFI_RADIO_COLORS, WIFI_COVERAGE_RADIUS_PX)
		fun cell(): RadioFieldEncoder = RadioFieldEncoder(CELL_RADIO_COLORS, CELL_COVERAGE_RADIUS_PX)
	}
}

fun <Feature> EncodeStep<Feature, SpatialData.RadioField>.radioField(
	encoder: RadioFieldEncoder,
): VizPipeline<Feature, SpatialData.RadioField> = encode(encoder)

internal val WIFI_RADIO_COLORS = linkedMapOf(
	RadioVisualGroup.WIFI_24 to 0xFF00C2C7.toInt(),
	RadioVisualGroup.WIFI_5 to 0xFF8B5CF6.toInt(),
	RadioVisualGroup.WIFI_6 to 0xFFFFB300.toInt(),
	RadioVisualGroup.WIFI_OTHER to 0xFF78909C.toInt(),
)

internal val CELL_RADIO_COLORS = linkedMapOf(
	RadioVisualGroup.CELL_GSM to 0xFFFFA726.toInt(),
	RadioVisualGroup.CELL_WCDMA to 0xFF43A047.toInt(),
	RadioVisualGroup.CELL_LTE to 0xFF039BE5.toInt(),
	RadioVisualGroup.CELL_NR to 0xFFD81B60.toInt(),
	RadioVisualGroup.CELL_OTHER to 0xFF78909C.toInt(),
)

private data class ObservationPoint(val lat: Double, val lon: Double, val time: Long)
private data class GridKey(val lat: Long, val lon: Long)
private data class ClusterGridKey(val x: Long, val y: Long, val z: Long)

private class CoverageCell {
	var latSum = 0.0
	var lonSum = 0.0
	var count = 0
	var time = Long.MIN_VALUE
	val identitySignals = HashMap<String, Double>()
}

private class OverlapCell {
	var latSum = 0.0
	var lonSum = 0.0
	var count = 0
	var time = Long.MIN_VALUE
	val identities = HashSet<String>()
}

private data class CoverageCandidate(
	val group: RadioVisualGroup,
	val feature: WeightedGeoFeature,
)

private inline fun <T> aggregateCoverage(
	features: List<T>,
	cellSizeDegrees: Float,
	maxPoints: Int,
	group: (T) -> RadioVisualGroup,
	identity: (T) -> String,
	signalWeight: (T) -> Double,
	coordinates: (T) -> ObservationPoint,
): List<RadioCoverage> {
	val cellSize = cellSizeDegrees.toDouble()
	val grouped = LinkedHashMap<Pair<RadioVisualGroup, GridKey>, CoverageCell>()
	features.forEach { feature ->
		val point = coordinates(feature)
		val visualGroup = group(feature)
		val gridKey = GridKey(
			floor(point.lat / cellSize).toLong(),
			floor(point.lon / cellSize).toLong(),
		)
		val cell = grouped.getOrPut(visualGroup to gridKey) { CoverageCell() }
		cell.latSum += point.lat
		cell.lonSum += point.lon
		cell.count++
		cell.time = max(cell.time, point.time)
		val radioIdentity = identity(feature)
		val weight = signalWeight(feature)
		cell.identitySignals[radioIdentity] = max(cell.identitySignals[radioIdentity] ?: 0.0, weight)
	}

	return grouped
		.map { (key, cell) ->
			val combined = 1.0 - cell.identitySignals.values.fold(1.0) { remaining, signal ->
				remaining * (1.0 - signal * RADIO_UNION_CONTRIBUTION)
			}
			CoverageCandidate(
				group = key.first,
				feature = WeightedGeoFeature(
					lat = cell.latSum / cell.count,
					lon = cell.lonSum / cell.count,
					time = cell.time,
					weight = combined.coerceIn(0.0, 1.0),
				),
			)
		}
		.sortedByDescending { it.feature.weight }
		.take(maxPoints)
		.groupBy(CoverageCandidate::group)
		.map { (visualGroup, candidates) ->
			RadioCoverage(visualGroup, candidates.map(CoverageCandidate::feature))
		}
}

private data class EstimateConfig(
	val binMeters: Double,
	val minBins: Int,
	val targetBins: Int,
	val minSpanMeters: Double,
	val targetSpanMeters: Double,
	val targetSignalRange: Double,
	val minimumSignalRange: Double,
	val uncertaintyFloorMeters: Double,
	val minConfidence: Double,
)

private data class EstimateObservation(
	val lat: Double,
	val lon: Double,
	val time: Long,
	val signal: Double,
	val rawSignal: Double,
)

private data class LocalObservation(
	val x: Double,
	val y: Double,
	val lat: Double,
	val lon: Double,
	val time: Long,
	val signal: Double,
	val rawSignal: Double,
) {
	val localizationWeight: Double = MIN_LOCALIZATION_WEIGHT + signal.coerceIn(0.0, 1.0).pow(3)
}

private fun estimateRadio(
	observations: List<EstimateObservation>,
	group: RadioVisualGroup,
	config: EstimateConfig,
	confidenceMultiplier: Double = 1.0,
): RadioEstimate? {
	if (observations.size < config.minBins) return null
	val referenceLat = observations.map { it.lat }.average()
	val referenceLon = circularMeanLongitude(observations.map(EstimateObservation::lon))
	val lonMeters = METERS_PER_DEGREE * cos(Math.toRadians(referenceLat)).coerceAtLeast(MIN_COS_LATITUDE)
	val bins = LinkedHashMap<GridKey, LocalObservation>()
	observations.forEach { observation ->
		val local = LocalObservation(
			x = shortestLongitudeDelta(observation.lon, referenceLon) * lonMeters,
			y = (observation.lat - referenceLat) * METERS_PER_DEGREE,
			lat = observation.lat,
			lon = observation.lon,
			time = observation.time,
			signal = observation.signal,
			rawSignal = observation.rawSignal,
		)
		val key = GridKey(
			floor(local.y / config.binMeters).toLong(),
			floor(local.x / config.binMeters).toLong(),
		)
		val previous = bins[key]
		if (previous == null || local.signal > previous.signal ||
			(local.signal == previous.signal && local.time > previous.time)
		) {
			bins[key] = local
		}
	}
	val samples = bins.values.sortedByDescending { it.signal }.take(MAX_ESTIMATE_BINS)
	if (samples.size < config.minBins) return null

	val span = hypot(
		samples.maxOf { it.x } - samples.minOf { it.x },
		samples.maxOf { it.y } - samples.minOf { it.y },
	)
	if (span < config.minSpanMeters) return null

	val totalWeight = samples.sumOf(LocalObservation::localizationWeight)
	if (totalWeight <= 0.0) return null
	val centerX = samples.sumOf { it.x * it.localizationWeight } / totalWeight
	val centerY = samples.sumOf { it.y * it.localizationWeight } / totalWeight
	val medoid = samples.minBy { candidate ->
		samples.sumOf { other ->
			hypot(candidate.x - other.x, candidate.y - other.y) * other.localizationWeight
		}
	}
	val medoidDistance = hypot(centerX - medoid.x, centerY - medoid.y)

	val covarianceX = samples.sumOf {
		(it.x - centerX).pow(2) * it.localizationWeight
	} / totalWeight
	val covarianceY = samples.sumOf {
		(it.y - centerY).pow(2) * it.localizationWeight
	} / totalWeight
	val covarianceXY = samples.sumOf {
		(it.x - centerX) * (it.y - centerY) * it.localizationWeight
	} / totalWeight
	val halfDifference = (covarianceX - covarianceY) / 2.0
	val eigenOffset = sqrt((halfDifference.pow(2) + covarianceXY.pow(2)).coerceAtLeast(0.0))
	val halfTrace = (covarianceX + covarianceY) / 2.0
	val majorStd = sqrt((halfTrace + eigenOffset).coerceAtLeast(0.0))
	val minorStd = sqrt((halfTrace - eigenOffset).coerceAtLeast(0.0))
	val geometryScore = if (majorStd <= 0.0) 0.0 else (minorStd / majorStd).coerceIn(0.0, 1.0)
	val signalRange = samples.maxOf { it.rawSignal } - samples.minOf { it.rawSignal }
	if (signalRange < config.minimumSignalRange) return null

	val sampleScore = (samples.size.toDouble() / config.targetBins).coerceIn(0.0, 1.0)
	val spanScore = (span / config.targetSpanMeters).coerceIn(0.0, 1.0)
	val dynamicScore = (signalRange / config.targetSignalRange).coerceIn(0.0, 1.0)
	val agreementScale = max(config.uncertaintyFloorMeters, majorStd * 2.0)
	val agreementScore = (1.0 - medoidDistance / agreementScale).coerceIn(0.0, 1.0)
	val confidence = (
		sampleScore * SAMPLE_CONFIDENCE_WEIGHT +
			spanScore * SPAN_CONFIDENCE_WEIGHT +
			geometryScore * GEOMETRY_CONFIDENCE_WEIGHT +
			dynamicScore * DYNAMIC_CONFIDENCE_WEIGHT +
			agreementScore * AGREEMENT_CONFIDENCE_WEIGHT
		) * confidenceMultiplier
	if (confidence < config.minConfidence) return null

	var uncertainty = max(config.uncertaintyFloorMeters, majorStd * UNCERTAINTY_STD_MULTIPLIER + medoidDistance)
	if (geometryScore < LOW_GEOMETRY_THRESHOLD) {
		uncertainty *= 1.0 + (LOW_GEOMETRY_THRESHOLD - geometryScore) * LOW_GEOMETRY_PENALTY
	}
	val center = WeightedGeoFeature(
		lat = referenceLat + centerY / METERS_PER_DEGREE,
		lon = normalizeLongitude(referenceLon + centerX / lonMeters),
		time = samples.maxOf { it.time },
		weight = confidence.coerceIn(0.0, 1.0),
	)
	return RadioEstimate(group, center, confidence.coerceIn(0.0, 1.0), uncertainty)
}

private fun WifiRadioGeoFeature.visualGroup(): RadioVisualGroup = when (frequencyMhz) {
	in 2_400..2_500 -> RadioVisualGroup.WIFI_24
	in 4_900..5_900 -> RadioVisualGroup.WIFI_5
	in 5_925..7_125 -> RadioVisualGroup.WIFI_6
	else -> RadioVisualGroup.WIFI_OTHER
}

private fun String.validBssidOrEmpty(): String {
	val parts = split(':')
	if (parts.size != BSSID_OCTETS || parts.any { part ->
			part.length != BSSID_OCTET_LENGTH || part.toIntOrNull(16) == null
		}
	) {
		return ""
	}
	return parts.joinToString(":") { it.lowercase() }
}

private fun WifiRadioGeoFeature.normalizedBssid(): String = bssid.trim().validBssidOrEmpty()

private fun WifiRadioGeoFeature.hasLocallyAdministeredBssid(): Boolean {
	val firstOctet = normalizedBssid().substringBefore(':').toIntOrNull(16) ?: return true
	return firstOctet and LOCALLY_ADMINISTERED_BIT != 0
}

private fun CellRadioGeoFeature.visualGroup(): RadioVisualGroup = when (
	CellType.values().getOrNull(networkType)
) {
	CellType.GSM -> RadioVisualGroup.CELL_GSM
	CellType.WCDMA -> RadioVisualGroup.CELL_WCDMA
	CellType.LTE -> RadioVisualGroup.CELL_LTE
	CellType.NR -> RadioVisualGroup.CELL_NR
	else -> RadioVisualGroup.CELL_OTHER
}

private fun CellRadioGeoFeature.hasStableCellIdentity(): Boolean =
	mcc > 0 && mnc >= 0 && when (CellType.values().getOrNull(networkType)) {
		CellType.GSM, CellType.CDMA -> cellId in 0L..MAX_16_BIT_CELL_ID
		CellType.WCDMA, CellType.LTE -> cellId in 0L..MAX_28_BIT_CELL_ID
		CellType.NR -> cellId in 0L..MAX_36_BIT_CELL_ID
		CellType.Unknown, CellType.None, null -> false
	}

private fun CellRadioGeoFeature.coverageIdentity(): String =
	"$mcc:$mnc:$networkType:$areaCode:${cellId.takeIf { it > 0L } ?: 0L}"

private fun CellRadioGeoFeature.siteIdentity(): String {
	val type = CellType.values().getOrNull(networkType)
	return if (type == CellType.LTE && cellId > MAX_LTE_SECTOR_ID) {
		"$mcc:$mnc:$networkType:$areaCode:${cellId ushr LTE_SECTOR_BITS}"
	} else {
		coverageIdentity()
	}
}

private fun wifiSignalWeight(levelDbm: Int): Double =
	((levelDbm.coerceIn(MIN_WIFI_DBM, MAX_WIFI_DBM) - MIN_WIFI_DBM).toDouble() /
		(MAX_WIFI_DBM - MIN_WIFI_DBM)).coerceIn(0.0, 1.0)

internal fun normalizedCellSignalWeight(asu: Double, networkType: Int): Double? {
	if (!asu.isFinite() || asu < 0.0 || asu == UNKNOWN_ASU || asu >= INVALID_ASU) return null
	val maxAsu = when (CellType.values().getOrNull(networkType)) {
		CellType.GSM -> 31.0
		CellType.WCDMA -> 96.0
		CellType.CDMA -> 16.0
		CellType.LTE, CellType.NR -> 97.0
		CellType.Unknown, CellType.None, null -> return null
	}
	if (asu > maxAsu) return null
	return (asu / maxAsu).coerceIn(0.0, 1.0)
}

private fun radioCellSize(ctx: AggContext, minimumCellDegrees: Double): Float {
	val zoomCell = GridAggregator.cellSizeForZoom(ctx.zoom, ctx.quality)
	val qualityFloor = minimumCellDegrees / ctx.quality.coerceAtLeast(MIN_QUALITY)
	return max(zoomCell.toDouble(), qualityFloor).toFloat()
}

private enum class ConfidenceTier(val haloRadiusPx: Float, val opacity: Float) {
	LOW(52f, 0.24f),
	MEDIUM(36f, 0.32f),
	HIGH(24f, 0.42f),
}

private fun RadioEstimate.confidenceTier(): ConfidenceTier {
	val wifi = group in WIFI_VISUAL_GROUPS
	val highUncertaintyLimit = if (wifi) WIFI_HIGH_UNCERTAINTY_METERS else CELL_HIGH_UNCERTAINTY_METERS
	val mediumUncertaintyLimit = if (wifi) WIFI_MEDIUM_UNCERTAINTY_METERS else CELL_MEDIUM_UNCERTAINTY_METERS
	return when {
		confidence >= HIGH_CONFIDENCE && uncertaintyMeters <= highUncertaintyLimit -> ConfidenceTier.HIGH
		confidence >= MEDIUM_CONFIDENCE && uncertaintyMeters <= mediumUncertaintyLimit -> ConfidenceTier.MEDIUM
		else -> ConfidenceTier.LOW
	}
}

private class WifiSpatialCluster(
	val observations: MutableList<WifiRadioGeoFeature> = mutableListOf(),
	var latSum: Double = 0.0,
	var lonSineSum: Double = 0.0,
	var lonCosineSum: Double = 0.0,
) {
	val centerLat: Double get() = latSum / observations.size
	val centerLon: Double
		get() = Math.toDegrees(atan2(lonSineSum, lonCosineSum))

	fun add(observation: WifiRadioGeoFeature) {
		observations += observation
		latSum += observation.lat
		val longitudeRadians = Math.toRadians(observation.lon)
		lonSineSum += sin(longitudeRadians)
		lonCosineSum += cos(longitudeRadians)
	}

	fun gridKey(bucketMeters: Double): ClusterGridKey = radioClusterGridKey(centerLat, centerLon, bucketMeters)
}

private fun List<WifiRadioGeoFeature>.spatialClusters(maxDistanceMeters: Double): List<List<WifiRadioGeoFeature>> {
	val clusters = mutableListOf<WifiSpatialCluster>()
	val buckets = HashMap<ClusterGridKey, MutableSet<WifiSpatialCluster>>()
	sortedBy(WifiRadioGeoFeature::time).forEach { observation ->
		val observationKey = radioClusterGridKey(observation.lat, observation.lon, maxDistanceMeters)
		val nearest = observationKey.neighbors()
			.asSequence()
			.flatMap { buckets[it].orEmpty().asSequence() }
			.distinct()
			.map { cluster ->
				cluster to haversineMeters(observation.lat, observation.lon, cluster.centerLat, cluster.centerLon)
			}
			.filter { (_, distance) -> distance <= maxDistanceMeters }
			.minByOrNull { it.second }
			?.first
		if (nearest == null) {
			val cluster = WifiSpatialCluster()
			cluster.add(observation)
			clusters += cluster
			buckets.getOrPut(cluster.gridKey(maxDistanceMeters)) { linkedSetOf() } += cluster
		} else {
			val oldKey = nearest.gridKey(maxDistanceMeters)
			nearest.add(observation)
			val newKey = nearest.gridKey(maxDistanceMeters)
			if (newKey != oldKey) {
				buckets[oldKey]?.let { bucket ->
					bucket -= nearest
					if (bucket.isEmpty()) buckets.remove(oldKey)
				}
				buckets.getOrPut(newKey) { linkedSetOf() } += nearest
			}
		}
	}
	return clusters.map(WifiSpatialCluster::observations)
}

private fun radioClusterGridKey(lat: Double, lon: Double, bucketMeters: Double): ClusterGridKey {
	val latitudeRadians = Math.toRadians(lat)
	val longitudeRadians = Math.toRadians(lon)
	val latitudeCosine = cos(latitudeRadians)
	return ClusterGridKey(
		x = floor(EARTH_RADIUS_METERS * latitudeCosine * cos(longitudeRadians) / bucketMeters).toLong(),
		y = floor(EARTH_RADIUS_METERS * latitudeCosine * sin(longitudeRadians) / bucketMeters).toLong(),
		z = floor(EARTH_RADIUS_METERS * sin(latitudeRadians) / bucketMeters).toLong(),
	)
}

private fun ClusterGridKey.neighbors(): List<ClusterGridKey> = buildList(27) {
	for (xOffset in -1L..1L) {
		for (yOffset in -1L..1L) {
			for (zOffset in -1L..1L) {
				add(ClusterGridKey(x + xOffset, y + yOffset, z + zOffset))
			}
		}
	}
}

private fun circularMeanLongitude(longitudes: List<Double>): Double {
	val sine = longitudes.sumOf { sin(Math.toRadians(it)) }
	val cosine = longitudes.sumOf { cos(Math.toRadians(it)) }
	if (abs(sine) < CIRCULAR_MEAN_EPSILON && abs(cosine) < CIRCULAR_MEAN_EPSILON) {
		return normalizeLongitude(longitudes.firstOrNull() ?: 0.0)
	}
	return normalizeLongitude(Math.toDegrees(atan2(sine, cosine)))
}

private fun shortestLongitudeDelta(longitude: Double, referenceLongitude: Double): Double =
	((longitude - referenceLongitude + LONGITUDE_WRAP_OFFSET) % FULL_LONGITUDE_DEGREES) -
		HALF_LONGITUDE_DEGREES

private fun normalizeLongitude(longitude: Double): Double =
	((longitude + LONGITUDE_WRAP_OFFSET) % FULL_LONGITUDE_DEGREES) - HALF_LONGITUDE_DEGREES

private fun radioRamp(color: Int): List<Pair<Float, Int>> = listOf(
	0.0f to 0x00000000,
	0.25f to color.withAlpha(0x44),
	0.65f to color.withAlpha(0xB8),
	1.0f to color,
)

private fun uncertaintyRamp(color: Int): List<Pair<Float, Int>> = listOf(
	0.0f to 0x00000000,
	0.45f to color.withAlpha(0x38),
	1.0f to color.withAlpha(0xA8),
)

private fun solidRamp(color: Int): List<Pair<Float, Int>> = listOf(
	0.0f to color.withAlpha(0x99),
	1.0f to color,
)

private fun Int.withAlpha(alpha: Int): Int = (this and 0x00FFFFFF) or (alpha shl 24)

private val WIFI_ESTIMATE_CONFIG = EstimateConfig(
	binMeters = 12.0,
	minBins = 4,
	targetBins = 8,
	minSpanMeters = 20.0,
	targetSpanMeters = 70.0,
	targetSignalRange = 14.0,
	minimumSignalRange = 6.0,
	uncertaintyFloorMeters = 20.0,
	minConfidence = 0.35,
)

private val CELL_ESTIMATE_CONFIG = EstimateConfig(
	binMeters = 40.0,
	minBins = 4,
	targetBins = 10,
	minSpanMeters = 120.0,
	targetSpanMeters = 700.0,
	targetSignalRange = 25.0,
	minimumSignalRange = 8.0,
	uncertaintyFloorMeters = 100.0,
	minConfidence = 0.35,
)

private const val METERS_PER_DEGREE = 111_320.0
private const val MIN_COS_LATITUDE = 0.01
private const val MIN_QUALITY = 0.6f
private const val WIFI_COVERAGE_CELL_DEGREES = 0.00022
private const val WIFI_OVERLAP_CELL_DEGREES = 0.00032
private const val CELL_COVERAGE_CELL_DEGREES = 0.0007
private const val WIFI_OVERLAP_SATURATION = 8.0
private const val RADIO_UNION_CONTRIBUTION = 0.72
private const val UNKNOWN_WIFI_IDENTITY = "<unknown>"
private const val BSSID_OCTETS = 6
private const val BSSID_OCTET_LENGTH = 2
private const val LOCALLY_ADMINISTERED_BIT = 0x02
private const val LOCALLY_ADMINISTERED_CONFIDENCE_MULTIPLIER = 0.72
private const val WIFI_IDENTITY_CLUSTER_METERS = 350.0
private const val EARTH_RADIUS_METERS = 6_378_137.0
private const val CIRCULAR_MEAN_EPSILON = 1e-12
private const val FULL_LONGITUDE_DEGREES = 360.0
private const val HALF_LONGITUDE_DEGREES = 180.0
private const val LONGITUDE_WRAP_OFFSET = 540.0
private const val MIN_WIFI_DBM = -100
private const val MAX_WIFI_DBM = -30
private const val UNKNOWN_ASU = 99.0
private const val INVALID_ASU = 255.0
private const val MAX_16_BIT_CELL_ID = 0xFFFFL
private const val MAX_28_BIT_CELL_ID = 0x0FFFFFFFL
private const val MAX_36_BIT_CELL_ID = 0x0FFFFFFFFFL
private const val LTE_SECTOR_BITS = 8
private const val MAX_LTE_SECTOR_ID = 0xFF
private const val MAX_ESTIMATE_BINS = 64
private const val MAX_ESTIMATES = 600
private const val MIN_LOCALIZATION_WEIGHT = 0.04
private const val SAMPLE_CONFIDENCE_WEIGHT = 0.28
private const val SPAN_CONFIDENCE_WEIGHT = 0.22
private const val GEOMETRY_CONFIDENCE_WEIGHT = 0.24
private const val DYNAMIC_CONFIDENCE_WEIGHT = 0.16
private const val AGREEMENT_CONFIDENCE_WEIGHT = 0.10
private const val UNCERTAINTY_STD_MULTIPLIER = 2.2
private const val LOW_GEOMETRY_THRESHOLD = 0.35
private const val LOW_GEOMETRY_PENALTY = 2.0
private const val HIGH_CONFIDENCE = 0.72
private const val MEDIUM_CONFIDENCE = 0.50
private const val ESTIMATE_CORE_MIN_CONFIDENCE = 0.60
private const val WIFI_HIGH_UNCERTAINTY_METERS = 45.0
private const val WIFI_MEDIUM_UNCERTAINTY_METERS = 110.0
private const val CELL_HIGH_UNCERTAINTY_METERS = 350.0
private const val CELL_MEDIUM_UNCERTAINTY_METERS = 1_200.0
private const val WIFI_COVERAGE_RADIUS_PX = 25f
private const val CELL_COVERAGE_RADIUS_PX = 31f
private const val COVERAGE_OPACITY = 0.62f

private val WIFI_VISUAL_GROUPS = setOf(
	RadioVisualGroup.WIFI_24,
	RadioVisualGroup.WIFI_5,
	RadioVisualGroup.WIFI_6,
	RadioVisualGroup.WIFI_OTHER,
)
