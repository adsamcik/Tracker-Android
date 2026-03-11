package com.adsamcik.tracker.points.work

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.adsamcik.tracker.logger.LogData
import com.adsamcik.tracker.logger.Logger
import com.adsamcik.tracker.points.POINTS_LOG_SOURCE
import com.adsamcik.tracker.points.data.AwardSource
import com.adsamcik.tracker.points.data.Points
import com.adsamcik.tracker.points.data.PointsAwarded
import com.adsamcik.tracker.points.database.PointsDatabase
import com.adsamcik.tracker.shared.base.Time
import androidx.annotation.VisibleForTesting
import com.adsamcik.tracker.shared.base.data.ActivityInfo
import com.adsamcik.tracker.shared.base.data.LengthUnit
import com.adsamcik.tracker.shared.base.data.Location
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.DatabaseLocation
import com.adsamcik.tracker.shared.base.database.data.LocationSample
import com.adsamcik.tracker.shared.base.database.data.Trip
import com.adsamcik.tracker.shared.base.extension.format
import com.adsamcik.tracker.shared.utils.extension.getPositiveLongReportNull
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.runBlocking

internal class PointsWorker(context: Context, workerParams: WorkerParameters) : Worker(
	context,
	workerParams
) {
	private fun logResult(
		message: String,
		result: Result
	): Result {
		Logger.log(LogData(message = message, source = POINTS_LOG_SOURCE))
		return result
	}

	override fun doWork(): Result {
		val id = this.inputData.getPositiveLongReportNull(ARG_ID) ?: return Result.failure()
		val trip = runBlocking {
			AppDatabase.database(applicationContext).tripDao().getById(id)
		} ?: return logResult("Found no session for point calculation.", Result.failure())
		val awardTime = trip.endTimeMs.takeIf { it > 0L } ?: Time.nowMillis
		val pointsDao = PointsDatabase
			.database(applicationContext)
			.pointsAwardedDao()

		if (pointsDao.hasAwardAt(awardTime, AwardSource.SESSION.value)) {
			return logResult("Points already awarded for session $id at $awardTime, skipping duplicate.", Result.success())
		}

		val locationData = runBlocking {
			AppDatabase.database(applicationContext)
				.locationSampleDao()
				.getAllBetween(trip.startTimeMs, trip.endTimeMs)
		}
			.mapNotNull { it.toDatabaseLocation() }
			.filter { it.altitude != null }

		val points = if (locationData.size > 1) {
			val slopeList = calculateSlope(locationData)
			slopeList.sumOf {
				@Suppress("MagicNumber")
				val slopePositive = max(it.slope, 0.0)

				@Suppress("MagicNumber")
				val slopeBonus = kotlin.math.sqrt(slopePositive / HALF_SLOPE) * SLOPE_MULTIPLIER

				it.distance * POINTS_PER_METER_MPS * it.speedMPS * (1.0 + slopeBonus)
			}
		} else {
			calculateFallbackPoints(trip)
		}

		if (points <= 0.0) {
			return logResult("No qualifying movement found for point calculation.", Result.failure())
		}

		val awardPoints = PointsAwarded(
			awardTime,
			Points(points),
			AwardSource.SESSION
		)

		pointsDao.insert(awardPoints)

		return logResult(
			"Awarded ${awardPoints.value.value.format(2)} points from ${awardPoints.source.value}",
			Result.success()
		)
	}

	private fun calculateSlope(locationData: Collection<DatabaseLocation>): Collection<SlopeData> =
		Companion.calculateSlope(locationData)

	private fun calculateFallbackPoints(trip: Trip): Double {
		val durationMinutes = ((trip.endTimeMs - trip.startTimeMs).coerceAtLeast(0L) / 60_000.0)
		val stepPoints = (trip.steps ?: 0).coerceAtLeast(0) * FALLBACK_POINTS_PER_STEP
		val distancePoints = trip.distanceM.coerceAtLeast(0f) * FALLBACK_POINTS_PER_METER
		val durationPoints = durationMinutes * FALLBACK_POINTS_PER_MINUTE
		return max(stepPoints, max(distancePoints, durationPoints))
	}

	private fun LocationSample.toDatabaseLocation(): DatabaseLocation? {
		val lat = latE7 ?: return null
		val lon = lonE7 ?: return null
		return DatabaseLocation(
			location = Location(
				time = timeMs,
				latitude = lat / 1e7,
				longitude = lon / 1e7,
				altitude = altitudeM?.toDouble(),
				horizontalAccuracy = hAccM,
				verticalAccuracy = null,
				speed = speedMps,
				speedAccuracy = null,
			),
			activityInfo = ActivityInfo.UNKNOWN,
		)
	}

	data class SlopeData(
		val location: Location,
		val activity: ActivityInfo,
		val change: Double,
		val slope: Double,
		val distance: Double,
		val speedMPS: Double
	)

	companion object {
		private const val HALF_SLOPE = kotlin.math.PI / 4
		private const val POINTS_PER_METER_MPS = 0.01
		private const val SLOPE_MULTIPLIER = 12
		private const val ARG_ID = TrackerSession.RECEIVER_SESSION_ID
		private const val ALTITUDE_THRESHOLD = 10.0
		private const val FALLBACK_POINTS_PER_STEP = 0.01
		private const val FALLBACK_POINTS_PER_METER = 0.005
		private const val FALLBACK_POINTS_PER_MINUTE = 0.5

		@VisibleForTesting
		internal fun calculateSlope(locationData: Collection<DatabaseLocation>): Collection<SlopeData> {
			val firstLocation = locationData.first()
			var lastAltitude = requireNotNull(firstLocation.altitude)
			val slopeList = mutableListOf(
				SlopeData(
					firstLocation.location,
					firstLocation.activityInfo,
					0.0,
					0.0,
					0.0,
					0.0
				)
			)
			var prevLocation = firstLocation.location
			locationData.forEachIndexed { index, dbLocation ->
				val location = dbLocation.location
				val altitude = requireNotNull(location.altitude)
				val diff = abs(lastAltitude - altitude)
				if (index + 1 == locationData.size || diff > ALTITUDE_THRESHOLD) {
					val distance = prevLocation.distanceFlat(location, LengthUnit.Meter)
					val timeDelta = location.time - prevLocation.time
					if (timeDelta <= 0 || distance <= 0.0) return@forEachIndexed
					val speed = distance / timeDelta
					val slope = kotlin.math.atan(diff / distance)
					slopeList.add(
						SlopeData(
							location,
							dbLocation.activityInfo,
							diff,
							slope,
							distance,
							speed
						)
					)

					prevLocation = location
					lastAltitude = altitude
				}
			}

			return slopeList
		}
	}
}
