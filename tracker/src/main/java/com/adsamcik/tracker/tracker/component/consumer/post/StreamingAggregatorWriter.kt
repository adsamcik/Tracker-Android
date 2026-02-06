package com.adsamcik.tracker.tracker.component.consumer.post

import android.content.Context
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.data.CollectionData
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.stats.api.AggregatorSignal
import com.adsamcik.tracker.stats.api.DetectedActivityType
import com.adsamcik.tracker.stats.engine.aggregator.StreamingAggregator
import com.adsamcik.tracker.tracker.component.PostTrackerComponent
import com.adsamcik.tracker.tracker.component.TrackerComponentRequirement
import com.adsamcik.tracker.tracker.component.producer.StepDataProducer
import com.adsamcik.tracker.tracker.data.collection.CollectionTempData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Post-tracker component that feeds sensor data into [StreamingAggregator]
 * and periodically flushes real-time stats to the database.
 *
 * Bridges the tracker pipeline with the stats-engine aggregation:
 * - Builds [AggregatorSignal] from per-cycle collection data
 * - Flushes snapshot to `live_stats` table every ~30 seconds
 * - On session end: materializes `daily_summary` and clears `live_stats`
 *
 * Contract:
 * - No required data: operates on whatever sensors are available
 * - Thread safety: called from TrackerService's componentMutex (single-threaded)
 */
internal class StreamingAggregatorWriter : PostTrackerComponent {
	override val requiredData: Collection<TrackerComponentRequirement> = emptyList()

	private lateinit var database: AppDatabase
	private var scope: CoroutineScope? = null
	private var aggregator: StreamingAggregator? = null

	// Previous location for distance delta calculation
	private var prevLatitude: Double? = null
	private var prevLongitude: Double? = null

	// Flush interval tracking
	private var lastFlushMs: Long = 0L

	override suspend fun onEnable(context: Context) {
		database = AppDatabase.database(context)
		scope = CoroutineScope(Job() + Dispatchers.Default)

		// Create aggregator and seed with today's existing data
		val now = Time.nowMillis
		val todayEpochDay = now / Time.DAY_IN_MILLISECONDS

		aggregator = StreamingAggregator(clock = { Time.nowMillis }).apply {
			start(sessionStartMs = now)

			// Seed with existing daily summary if available
			val existing = database.dailySummaryDao().getByDay(todayEpochDay)
			if (existing != null) {
				seedDayTotals(
					distanceM = existing.totalDistanceM,
					steps = existing.totalSteps,
					durationMs = existing.totalDurationMs,
					trips = existing.tripCount
				)
			}
		}

		prevLatitude = null
		prevLongitude = null
		lastFlushMs = now
	}

	override suspend fun onDisable(context: Context) {
		val agg = aggregator ?: return
		val now = Time.nowMillis
		val todayEpochDay = now / Time.DAY_IN_MILLISECONDS

		// Final flush to live_stats
		flushSnapshot()

		// Get final snapshot
		val finalSnapshot = agg.stop()

		// Materialize to daily_summary directly (onDisable is suspend, no need to launch)
		database.dailySummaryDao().upsert(
			dateEpochDay = todayEpochDay,
			totalDistanceM = finalSnapshot.dayTotalDistanceM,
			totalSteps = finalSnapshot.dayTotalSteps,
			totalDurationMs = finalSnapshot.dayTotalDurationMs,
			tripCount = finalSnapshot.tripCount,
			activeTrackingMs = finalSnapshot.sessionDurationMs,
			lastUpdatedMs = now
		)

		// Clear live_stats
		database.liveStatsDao().clear()

		aggregator = null
		prevLatitude = null
		prevLongitude = null
		scope?.cancel()
		scope = null
	}

	override fun onNewData(
		context: Context,
		session: com.adsamcik.tracker.shared.base.data.TrackerSession,
		collectionData: CollectionData,
		tempData: CollectionTempData,
	) {
		val agg = aggregator ?: return
		val signal = buildSignal(collectionData, tempData)
		agg.onSignal(signal)

		// Periodic flush to database
		val now = Time.nowMillis
		if (now - lastFlushMs >= FLUSH_INTERVAL_MS) {
			scope?.launch(Dispatchers.IO) {
				flushSnapshot()
			}
			lastFlushMs = now
		}
	}

	private fun buildSignal(
		collectionData: CollectionData,
		tempData: CollectionTempData,
	): AggregatorSignal {
		val location = collectionData.location
		val activity = collectionData.activity

		// Calculate distance delta from previous position
		val distanceDeltaM = if (location != null && prevLatitude != null && prevLongitude != null) {
			computeDistance(prevLatitude!!, prevLongitude!!, location.latitude, location.longitude)
		} else {
			null
		}

		// Update previous position
		if (location != null) {
			prevLatitude = location.latitude
			prevLongitude = location.longitude
		}

		// Map activity type
		val activityType = activity?.let { mapActivityType(it.activityType) }

		// Get step delta
		val stepDelta = tempData.tryGet<Int>(StepDataProducer.NEW_STEPS_ARG) ?: 0

		return AggregatorSignal(
			timestampMs = tempData.timeMillis,
			distanceDeltaM = distanceDeltaM,
			speedMps = location?.speed,
			stepDelta = stepDelta,
			activityType = activityType,
			activityConfidence = activity?.confidence,
		)
	}

	private suspend fun flushSnapshot() {
		val agg = aggregator ?: return
		val snapshot = agg.snapshot()
		val now = Time.nowMillis
		val todayEpochDay = now / Time.DAY_IN_MILLISECONDS

		database.liveStatsDao().upsert(
			dateEpochDay = todayEpochDay,
			sessionDistanceM = snapshot.sessionDistanceM,
			sessionSteps = snapshot.sessionSteps,
			sessionDurationMs = snapshot.sessionDurationMs,
			dayTotalDistanceM = snapshot.dayTotalDistanceM,
			dayTotalSteps = snapshot.dayTotalSteps,
			dayTotalDurationMs = snapshot.dayTotalDurationMs,
			lastUpdatedMs = now
		)
	}

	companion object {
		private const val FLUSH_INTERVAL_MS = 30_000L // 30 seconds

		/**
		 * Compute distance between two lat/lon coordinates using Android's Location.distanceBetween.
		 */
		private fun computeDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
			val results = FloatArray(1)
			android.location.Location.distanceBetween(lat1, lon1, lat2, lon2, results)
			return results[0]
		}

		/**
		 * Map Google Play Services activity type int to [DetectedActivityType].
		 *
		 * Values from com.google.android.gms.location.DetectedActivity:
		 * IN_VEHICLE=0, ON_BICYCLE=1, ON_FOOT=2, STILL=3, UNKNOWN=4, TILTING=5, WALKING=7, RUNNING=8
		 */
		private fun mapActivityType(activityType: Int): DetectedActivityType? = when (activityType) {
			0 -> DetectedActivityType.IN_VEHICLE
			1 -> DetectedActivityType.ON_BICYCLE
			2 -> DetectedActivityType.ON_FOOT
			3 -> DetectedActivityType.STILL
			5 -> DetectedActivityType.TILTING
			7 -> DetectedActivityType.WALKING
			8 -> DetectedActivityType.RUNNING
			else -> null
		}
	}
}
