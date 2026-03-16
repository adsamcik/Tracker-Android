package com.adsamcik.tracker.statistics.presenter

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.database.data.LocationSample
import com.adsamcik.tracker.shared.base.database.data.SkiRunSegment
import com.adsamcik.tracker.statistics.export.GpxShareHelper
import com.adsamcik.tracker.statistics.viewmodel.activityLabel
import com.adsamcik.tracker.stats.api.repository.LocationSampleRepository
import com.adsamcik.tracker.stats.api.repository.SkiRunSegmentRepository
import com.adsamcik.tracker.stats.api.repository.TripPresentationRepository
import com.adsamcik.tracker.stats.api.repository.TripRepository
import com.adsamcik.tracker.stats.api.repository.TripSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
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
		.stateIn(viewModelScope, SharingStarted.Lazily, TripDetailState.Loading)

	private val _skiSegments = MutableStateFlow<List<SkiRunSegment>>(emptyList())
	val skiSegments: StateFlow<List<SkiRunSegment>> = _skiSegments.asStateFlow()

	private val _insights = MutableStateFlow(TripDetailInsights())
	val insights: StateFlow<TripDetailInsights> = _insights.asStateFlow()

	init {
		events.tryEmit(TripDetailEvent.LoadTrip(tripId))
		loadSupplementalData()
	}

	private fun loadSupplementalData() {
		viewModelScope.launch {
			try {
				val loaded = state.filterIsInstance<TripDetailState.Loaded>().first()
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
		val samples = withContext(dispatchers.io) {
			locationSampleRepository.getSamplesBetween(tripStart, tripEnd)
		}
		val segments = withContext(dispatchers.io) {
			skiRunSegmentRepository.getSegmentsByTimeRange(tripStart, tripEnd)
		}

		_skiSegments.value = segments
		_insights.value = buildInsights(
			trip = trip,
			projection = projection,
			samples = samples,
		)
	}

	/**
	 * Retry loading the trip detail after an error.
	 */
	fun retry() {
		_insights.value = TripDetailInsights()
		_skiSegments.value = emptyList()
		events.tryEmit(TripDetailEvent.LoadTrip(tripId))
		loadSupplementalData()
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

	/**
	 * Delete the current trip and invoke [onDeleted] on completion.
	 */
	fun deleteTrip(onDeleted: () -> Unit) {
		viewModelScope.launch {
			tripPresentationRepository.deleteTrip(tripId)
			onDeleted()
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
	val routePoints: List<LocationSample> = emptyList(),
)

private fun buildInsights(
	trip: TripSummary,
	projection: com.adsamcik.tracker.shared.base.database.data.Trip?,
	samples: List<LocationSample>,
): TripDetailInsights {
	val altitudePoints = samples.mapNotNull { it.altitudeM?.toDouble() }
	var gain = 0.0
	var loss = 0.0
	altitudePoints.zipWithNext { previous, current ->
		val delta = current - previous
		if (abs(delta) >= 1.0) {
			if (delta > 0) gain += delta else loss += -delta
		}
	}

	val maxSpeed = samples.mapNotNull { it.speedMps?.toDouble() }
		.filter { it > 0.0 }
		.maxOrNull()
	val durationSeconds = (trip.duration.raw / 1000.0).takeIf { it > 0.0 }
	val averageSpeed = durationSeconds
		?.let { duration -> trip.distance.raw.toDouble().takeIf { it > 0.0 }?.div(duration) }

	val routePoints = samples.filter { it.latE7 != null && it.lonE7 != null }

	return TripDetailInsights(
		activityType = resolveActivityType(projection, trip),
		sourceLabel = resolveSourceLabel(samples, projection),
		averageSpeedMps = averageSpeed,
		maxSpeedMps = maxSpeed,
		elevationGainM = gain.takeIf { it > 0.0 },
		elevationLossM = loss.takeIf { it > 0.0 },
		maxAltitudeM = altitudePoints.maxOrNull(),
		routePoints = routePoints,
	)
}

private fun resolveActivityType(
	projection: com.adsamcik.tracker.shared.base.database.data.Trip?,
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
	projection: com.adsamcik.tracker.shared.base.database.data.Trip?,
): String {
	val provider = samples
		.groupingBy { normalizeProviderLabel(it.provider) }
		.eachCount()
		.maxByOrNull { it.value }
		?.key

	if (provider != null) {
		return provider
	}

	return when (projection?.source) {
		com.adsamcik.tracker.shared.base.database.data.SegmentSource.USER_CREATED -> "Manual"
		com.adsamcik.tracker.shared.base.database.data.SegmentSource.INFERRED_HIGH_CONFIDENCE -> "Inferred"
		com.adsamcik.tracker.shared.base.database.data.SegmentSource.INFERRED_MEDIUM_CONFIDENCE -> "Inferred"
		com.adsamcik.tracker.shared.base.database.data.SegmentSource.INFERRED_LOW_CONFIDENCE -> "Inferred"
		com.adsamcik.tracker.shared.base.database.data.SegmentSource.LEGACY_MIGRATION -> "Legacy"
		null -> "—"
	}
}

private fun normalizeProviderLabel(provider: String): String = when (provider.lowercase()) {
	"gps" -> "GPS"
	"network" -> "Network"
	"fused", "flp" -> "Fused"
	"passive" -> "Passive"
	else -> provider.replaceFirstChar { it.uppercase() }
}
