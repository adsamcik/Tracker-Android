package com.adsamcik.tracker.statistics.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.database.dao.LocationDataDao
import com.adsamcik.tracker.shared.base.database.dao.TripDao
import com.adsamcik.tracker.shared.base.database.data.DatabaseLocation
import com.adsamcik.tracker.shared.base.database.data.Trip
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.math.max

/**
 * Computed metrics derived from location points for a trip.
 */
data class TripMetrics(
	val avgSpeedKmh: Double,
	val maxSpeedKmh: Double,
	val elevationGainM: Double,
	val maxAltitudeM: Double?
)

/**
 * UI state for Trip detail screen.
 */
sealed interface TripDetailState {
	data object Loading : TripDetailState
	data class Loaded(
		val trip: Trip,
		val locationPoints: List<DatabaseLocation>,
		val metrics: TripMetrics?
	) : TripDetailState

	data object NotFound : TripDetailState
	data class Error(val message: String) : TripDetailState
}

/**
 * ViewModel for the Trip detail screen.
 * Loads a single [Trip] by ID from [TripDao] and location data for metrics/map.
 */
@HiltViewModel
class TripDetailViewModel @Inject constructor(
	private val tripDao: TripDao,
	private val locationDataDao: LocationDataDao,
	private val dispatchersProvider: DispatchersProvider,
	savedStateHandle: SavedStateHandle
) : ViewModel() {

	private val tripId: Long = requireNotNull(savedStateHandle["tripId"])

	private val _state = MutableStateFlow<TripDetailState>(TripDetailState.Loading)
	val state: StateFlow<TripDetailState> = _state.asStateFlow()

	init {
		loadTrip()
	}

	/** Retry loading the trip after an error. */
	fun retry() {
		_state.value = TripDetailState.Loading
		loadTrip()
	}

	/** Delete the current trip and signal completion via state. */
	fun deleteTrip(onDeleted: () -> Unit) {
		viewModelScope.launch {
			withContext(dispatchersProvider.io) {
				tripDao.deleteById(tripId)
			}
			onDeleted()
		}
	}

	private fun loadTrip() {
		viewModelScope.launch {
			try {
				val trip = tripDao.getById(tripId)
				if (trip == null) {
					_state.value = TripDetailState.NotFound
					return@launch
				}

				val points = withContext(dispatchersProvider.io) {
					locationDataDao.getAllBetweenOrdered(trip.startTimeMs, trip.endTimeMs)
				}

				val metrics = if (points.size >= 2) computeMetrics(points, trip) else null
				_state.value = TripDetailState.Loaded(
					trip = trip,
					locationPoints = points,
					metrics = metrics
				)
			} catch (e: Exception) {
				_state.value = TripDetailState.Error(e.message ?: "Failed to load trip")
			}
		}
	}

	private fun computeMetrics(points: List<DatabaseLocation>, trip: Trip): TripMetrics {
		val maxSpeedMs = points.mapNotNull { it.location.speed }.maxOrNull() ?: 0f

		var elevationGain = 0.0
		var maxAlt: Double? = null
		for (i in 1 until points.size) {
			val prevAlt = points[i - 1].altitude
			val currAlt = points[i].altitude
			if (prevAlt != null && currAlt != null) {
				val diff = currAlt - prevAlt
				if (diff > 0) elevationGain += diff
			}
			if (currAlt != null) {
				maxAlt = if (maxAlt != null) max(maxAlt, currAlt) else currAlt
			}
		}
		// Include first point altitude
		points.firstOrNull()?.altitude?.let { first ->
			maxAlt = if (maxAlt != null) max(maxAlt!!, first) else first
		}

		val durationSec = trip.durationMs / MS_PER_SECOND
		val avgSpeedMs = if (durationSec > 0) trip.distanceM / durationSec else 0.0

		return TripMetrics(
			avgSpeedKmh = avgSpeedMs * MS_TO_KMH,
			maxSpeedKmh = maxSpeedMs.toDouble() * MS_TO_KMH,
			elevationGainM = elevationGain,
			maxAltitudeM = maxAlt
		)
	}

	companion object {
		private const val MS_PER_SECOND = 1000.0
		private const val MS_TO_KMH = 3.6
	}
}
