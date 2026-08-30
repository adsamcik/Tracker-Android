package com.adsamcik.tracker.statistics.presenter

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import com.adsamcik.tracker.feature.map.api.preview.RoutePoint
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.model.LocationSample
import com.adsamcik.tracker.shared.model.SampleQuality
import com.adsamcik.tracker.shared.model.SkiRunSegment
import com.adsamcik.tracker.shared.model.androidModelMslAltitudeOrNull
import com.adsamcik.tracker.shared.model.hasIdentifiedAltitude
import com.adsamcik.tracker.shared.model.isAltitudeContinuousAfter
import com.adsamcik.tracker.statistics.export.GpxShareHelper
import com.adsamcik.tracker.statistics.viewmodel.activityLabel
import com.adsamcik.tracker.stats.api.repository.LocationSampleRepository
import com.adsamcik.tracker.stats.api.repository.SkiRunSegmentRepository
import com.adsamcik.tracker.stats.api.repository.TripPresentationRepository
import com.adsamcik.tracker.stats.api.repository.TripRepository
import com.adsamcik.tracker.stats.api.repository.TripSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.math.abs

/**
 * Hilt-compatible ViewModel wrapper for [TripDetailPresenter].
 * Bridges the Molecule-style Presenter pattern with Compose's [hiltViewModel] lifecycle.
 *
 * - Extracts `tripId` from [SavedStateHandle] and emits [TripDetailEvent.LoadTrip] on init.
 * - Exposes reactive [state] for the Compose UI to collect.
 */
@HiltViewModel
class TripDetailPresenterViewModel @Inject constructor(
	presenter: TripDetailPresenter,
	private val tripPresentationRepository: TripPresentationRepository,
	private val skiRunSegmentRepository: SkiRunSegmentRepository,
	private val locationSampleRepository: LocationSampleRepository,
	private val gpxShareHelper: GpxShareHelper,
	private val dispatchers: DispatchersProvider,
	savedStateHandle: SavedStateHandle,
) : ViewModel() {

	private val tripId: Long = requireNotNull(savedStateHandle["tripId"])

	private val events = MutableSharedFlow<TripDetailEvent>(replay = 1)

	val state: StateFlow<TripDetailState> = presenter.present(events)
		.stateIn(
			scope = viewModelScope,
			started = SharingStarted.WhileSubscribed(
				stopTimeoutMillis = HISTORY_STOP_TIMEOUT_MS,
				replayExpirationMillis = 0L,
			),
			initialValue = TripDetailState.Loading,
		)

	private val _skiSegments = MutableStateFlow<List<SkiRunSegment>>(emptyList())
	val skiSegments: StateFlow<List<SkiRunSegment>> = _skiSegments.asStateFlow()

	private val _insights = MutableStateFlow(TripDetailInsights())
	val insights: StateFlow<TripDetailInsights> = _insights.asStateFlow()
	private var supplementalDataJob: Job? = null

	init {
		events.tryEmit(TripDetailEvent.LoadTrip(tripId))
	}

	/** Load optional Location/Ski presentation data only while Trip Detail is composed. */
	fun loadSupplementalData() {
		val loaded = state.value as? TripDetailState.Loaded ?: return
		supplementalDataJob?.cancel()
		supplementalDataJob = viewModelScope.launch {
			try {
				loadSupplementalDataForTrip(loaded)
			} catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
				// Supplemental data is optional — don't break trip detail on failure
			}
		}
	}

	private suspend fun loadSupplementalDataForTrip(loaded: TripDetailState.Loaded) {
		val trip = loaded.trip
		val tripStart = trip.startTimeMs.raw
		val tripEnd = trip.endTimeMs.raw
		val projection = withContext(dispatchers.io) {
			tripPresentationRepository.getTripProjection(tripId)
		}
		val sampleInsights = withContext(dispatchers.io) {
			loadSampleInsights(locationSampleRepository, tripStart, tripEnd)
		}
		val segments = withContext(dispatchers.io) {
			skiRunSegmentRepository.getSegmentsByTimeRange(tripStart, tripEnd)
		}

		_skiSegments.value = segments
		_insights.value = buildInsights(
			trip = trip,
			projection = projection,
			sampleInsights = sampleInsights,
		)
	}

	/**
	 * Retry loading the trip detail after an error.
	 */
	fun retry() {
		events.tryEmit(TripDetailEvent.LoadTrip(tripId))
	}

	/**
	 * Export the current trip as a GPX file and share via system share sheet.
	 */
	fun exportTripGpx(context: Context) {
		viewModelScope.launch {
			val loaded = state.value as? TripDetailState.Loaded ?: return@launch
			val trip = loaded.trip
			gpxShareHelper.exportAndShare(
				context = context,
				tripId = tripId,
				startTimeMs = trip.startTimeMs.raw,
				endTimeMs = trip.endTimeMs.raw,
			)
		}
	}
}

data class TripDetailInsights(
	val activityType: String = "Trip",
	val sourceLabel: String = "—",
	val averageSpeedMps: Double? = null,
	val maxSpeedMps: Double? = null,
	val elevationGainM: Double? = null,
	val elevationLossM: Double? = null,
	val maxAltitudeM: Double? = null,
	val routePoints: List<RoutePoint> = emptyList(),
)

private data class SampleInsights(
	val providerCounts: Map<String, Int> = emptyMap(),
	val maxSpeedMps: Double? = null,
	val elevationGainM: Double = 0.0,
	val elevationLossM: Double = 0.0,
	val maxAltitudeM: Double? = null,
	val routePoints: List<RoutePoint> = emptyList(),
)

private suspend fun loadSampleInsights(
	locationSampleRepository: LocationSampleRepository,
	fromMs: Long,
	toMs: Long,
): SampleInsights {
	val providerCounts = linkedMapOf<String, Int>()
	val routePoints = ArrayList<RoutePoint>(ROUTE_PREVIEW_MAX_POINTS)
	var maxSpeed: Double? = null
	var elevationGain = 0.0
	var elevationLoss = 0.0
	var maxAltitude: Double? = null
	var previousAltitudeSample: LocationSample? = null
	var afterTimeMs: Long? = null
	var afterId: Long? = null

	while (true) {
		val chunk = locationSampleRepository.getOrderedChunkBetween(
			fromMs = fromMs,
			toMs = toMs,
			afterTimeMs = afterTimeMs,
			afterId = afterId,
			limit = SAMPLE_CHUNK_SIZE,
		)
		if (chunk.isEmpty()) break

		for (sample in chunk) {
			providerCounts[normalizeProviderLabel(sample.provider)] =
				(providerCounts[normalizeProviderLabel(sample.provider)] ?: 0) + 1

			if (sample.hasIdentifiedAltitude()) {
				val altitude = requireNotNull(sample.altitudeM).toDouble()
				if (sample.isAltitudeContinuousAfter(previousAltitudeSample)) {
					val previous = requireNotNull(previousAltitudeSample?.altitudeM).toDouble()
					val delta = altitude - previous
					if (abs(delta) >= 1.0) {
						if (delta > 0) elevationGain += delta else elevationLoss += -delta
						previousAltitudeSample = sample
					}
				} else {
					// Reset at every incompatible/unknown/clock-domain boundary rather than bridging it.
					previousAltitudeSample = sample
				}
			} else {
				previousAltitudeSample = null
			}
			sample.androidModelMslAltitudeOrNull()?.let { altitude ->
				maxAltitude = maxOf(maxAltitude ?: altitude, altitude)
			}

			val speed = sample.speedMps?.toDouble()?.takeIf { it > 0.0 }
			if (speed != null && sample.hasTrustworthySpeed()) {
				maxSpeed = maxOf(maxSpeed ?: speed, speed)
			}

			val lat = sample.latE7
			val lon = sample.lonE7
			if (lat != null && lon != null) {
				routePoints.add(RoutePoint(lat / E7_DIVISOR, lon / E7_DIVISOR))
			}
		}

		if (routePoints.size > ROUTE_PREVIEW_MAX_POINTS * ROUTE_COMPACTION_FACTOR) {
			val compacted = simplifyRoutePoints(routePoints)
			routePoints.clear()
			routePoints.addAll(compacted)
		}

		val lastSample = chunk.last()
		afterTimeMs = lastSample.timeMs
		afterId = lastSample.id
		if (chunk.size < SAMPLE_CHUNK_SIZE) {
			break
		}
	}

	return SampleInsights(
		providerCounts = providerCounts,
		maxSpeedMps = maxSpeed,
		elevationGainM = elevationGain,
		elevationLossM = elevationLoss,
		maxAltitudeM = maxAltitude,
		routePoints = simplifyRoutePoints(routePoints),
	)
}

private fun buildInsights(
	trip: TripSummary,
	projection: com.adsamcik.tracker.shared.model.Trip?,
	samples: List<LocationSample>,
): TripDetailInsights {
	var gain = 0.0
	var loss = 0.0
	var previousAltitudeSample: LocationSample? = null
	val mslAltitudes = mutableListOf<Double>()
	samples.forEach { sample ->
		if (sample.hasIdentifiedAltitude()) {
			val altitude = requireNotNull(sample.altitudeM).toDouble()
			if (sample.isAltitudeContinuousAfter(previousAltitudeSample)) {
				val previous = requireNotNull(previousAltitudeSample?.altitudeM).toDouble()
				val delta = altitude - previous
				if (abs(delta) >= 1.0) {
					if (delta > 0) gain += delta else loss += -delta
					previousAltitudeSample = sample
				}
			} else {
				previousAltitudeSample = sample
			}
		} else {
			previousAltitudeSample = null
		}
		sample.androidModelMslAltitudeOrNull()?.let(mslAltitudes::add)
	}

	val maxSpeed = samples
		.filter { it.hasTrustworthySpeed() }
		.mapNotNull { it.speedMps?.toDouble() }
		.filter { it > 0.0 }
		.maxOrNull()
	val durationSeconds = (trip.duration.raw / 1000.0).takeIf { it > 0.0 }
	val averageSpeed = durationSeconds
		?.let { duration -> trip.distance.raw.toDouble().takeIf { it > 0.0 }?.div(duration) }

	val routePoints = simplifyRoutePoints(
		samples.mapNotNull { sample ->
			val lat = sample.latE7 ?: return@mapNotNull null
			val lon = sample.lonE7 ?: return@mapNotNull null
			RoutePoint(lat / E7_DIVISOR, lon / E7_DIVISOR)
		}
	)

	return TripDetailInsights(
		activityType = resolveActivityType(projection, trip),
		sourceLabel = resolveSourceLabel(samples, projection),
		averageSpeedMps = averageSpeed,
		maxSpeedMps = maxSpeed,
		elevationGainM = gain.takeIf { it > 0.0 },
		elevationLossM = loss.takeIf { it > 0.0 },
		maxAltitudeM = mslAltitudes.maxOrNull(),
		routePoints = routePoints,
	)
}

private fun buildInsights(
	trip: TripSummary,
	projection: com.adsamcik.tracker.shared.model.Trip?,
	sampleInsights: SampleInsights,
): TripDetailInsights {
	val durationSeconds = (trip.duration.raw / 1000.0).takeIf { it > 0.0 }
	val averageSpeed = durationSeconds
		?.let { duration -> trip.distance.raw.toDouble().takeIf { it > 0.0 }?.div(duration) }

	return TripDetailInsights(
		activityType = resolveActivityType(projection, trip),
		sourceLabel = resolveSourceLabel(sampleInsights.providerCounts, projection),
		averageSpeedMps = averageSpeed,
		maxSpeedMps = sampleInsights.maxSpeedMps,
		elevationGainM = sampleInsights.elevationGainM.takeIf { it > 0.0 },
		elevationLossM = sampleInsights.elevationLossM.takeIf { it > 0.0 },
		maxAltitudeM = sampleInsights.maxAltitudeM,
		routePoints = sampleInsights.routePoints,
	)
}

internal fun simplifyRoutePoints(points: List<RoutePoint>): List<RoutePoint> =
	if (points.size <= ROUTE_PREVIEW_MAX_POINTS) {
		points
	} else {
		RoutePreviewSimplifier.simplify(
			points = points,
			toleranceMeters = ROUTE_PREVIEW_TOLERANCE_METERS,
			maxPoints = ROUTE_PREVIEW_MAX_POINTS,
		)
	}

private fun resolveActivityType(
	projection: com.adsamcik.tracker.shared.model.Trip?,
	trip: TripSummary,
): String {
	projection?.primaryActivity?.let { return activityLabel(it) }
	return trip.primaryMode.name
		.replace('_', ' ')
		.lowercase()
		.replaceFirstChar { it.uppercase() }
		.takeIf { it != "Unknown" }
		?: "Trip"
}

private fun resolveSourceLabel(
	samples: List<LocationSample>,
	projection: com.adsamcik.tracker.shared.model.Trip?,
): String {
	val provider = samples
		.groupingBy { normalizeProviderLabel(it.provider) }
		.eachCount()
		.maxByOrNull { it.value }
		?.key

	return resolveSourceLabel(provider, projection)
}

private fun resolveSourceLabel(
	providerCounts: Map<String, Int>,
	projection: com.adsamcik.tracker.shared.model.Trip?,
): String {
	val provider = providerCounts.maxByOrNull { it.value }?.key

	return resolveSourceLabel(provider, projection)
}

private fun resolveSourceLabel(
	provider: String?,
	projection: com.adsamcik.tracker.shared.model.Trip?,
): String {

	if (provider != null) {
		return provider
	}

	return when (projection?.source) {
		com.adsamcik.tracker.shared.model.SegmentSource.USER_CREATED -> "Manual"
		com.adsamcik.tracker.shared.model.SegmentSource.INFERRED_HIGH_CONFIDENCE -> "Inferred"
		com.adsamcik.tracker.shared.model.SegmentSource.INFERRED_MEDIUM_CONFIDENCE -> "Inferred"
		com.adsamcik.tracker.shared.model.SegmentSource.INFERRED_LOW_CONFIDENCE -> "Inferred"
		com.adsamcik.tracker.shared.model.SegmentSource.LEGACY_MIGRATION -> "Legacy"
		null -> "—"
	}
}

private const val SAMPLE_CHUNK_SIZE = 2_000
private const val ROUTE_PREVIEW_MAX_POINTS = 1_500
private const val ROUTE_COMPACTION_FACTOR = 4
private const val ROUTE_PREVIEW_TOLERANCE_METERS = 8.0
private const val E7_DIVISOR = 1e7
private const val HISTORY_STOP_TIMEOUT_MS = 5_000L

/**
 * Maximum trusted uncertainty for a device-reported speed reading. Readings with a larger
 * reported [LocationSample.speedAccuracyMps] are too uncertain to be trusted for a "record"
 * statistic like max speed, even if the raw value itself looks plausible.
 */
private const val MAX_TRUSTED_SPEED_ACCURACY_MPS = 3.0

/**
 * Whether this sample's speed reading is reliable enough to contribute to speed statistics
 * (currently max speed). A single low-accuracy or coarse fix can report an implausible
 * instantaneous speed even though the numeric value itself is unremarkable, which would let one
 * bad fix set a trip's "max speed" record. [SampleQuality] is derived from horizontal accuracy at
 * capture time (see PersistenceProcessor.classifyQuality); LOW/COARSE fixes are excluded here.
 * When available, [LocationSample.speedAccuracyMps] is checked too, since it can flag an
 * unreliable speed independently of position accuracy.
 */
private fun LocationSample.hasTrustworthySpeed(): Boolean {
	if (quality == SampleQuality.LOW || quality == SampleQuality.COARSE) return false
	val speedAccuracy = speedAccuracyMps
	if (speedAccuracy != null && speedAccuracy > MAX_TRUSTED_SPEED_ACCURACY_MPS) return false
	return true
}

private fun normalizeProviderLabel(provider: String): String = when (provider.lowercase()) {
	"gps" -> "GPS"
	"network" -> "Network"
	"fused", "flp" -> "Fused"
	"passive" -> "Passive"
	else -> provider.replaceFirstChar { it.uppercase() }
}
