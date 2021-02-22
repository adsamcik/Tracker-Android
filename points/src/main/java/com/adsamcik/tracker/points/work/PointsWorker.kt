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
import com.adsamcik.tracker.shared.base.data.ActivityInfo
import com.adsamcik.tracker.shared.base.data.GroupedActivity
import com.adsamcik.tracker.shared.base.data.LengthUnit
import com.adsamcik.tracker.shared.base.data.Location
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.DatabaseLocation
import com.adsamcik.tracker.shared.base.extension.format
import com.adsamcik.tracker.shared.utils.extension.getPositiveLongReportNull
import kotlin.math.abs
import kotlin.math.max

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
		val session = AppDatabase.database(applicationContext).sessionDao().get(id)
				?: return logResult("Found no session for point calculation.", Result.failure())

		val locationData = AppDatabase.database(applicationContext)
				.locationDao()
				.getAllBetweenOrdered(session.start, session.end)
				.filter { it.altitude != null && it.activityInfo.groupedActivity == GroupedActivity.ON_FOOT }

		if (locationData.size <= 1) {
			return logResult(
					"Not enough locations with altitude and on foot for point calculation.",
					Result.failure()
			)
		}

		val slopeList = calculateSlope(locationData)

		val points = slopeList.sumByDouble {
			@Suppress("MagicNumber")
			val slopePositive = max(it.slope, 0.0)

			@Suppress("MagicNumber")
			val slopeBonus = kotlin.math.sqrt(slopePositive / HALF_SLOPE) * SLOPE_MULTIPLIER

			it.distance * POINTS_PER_METER_MPS * it.speedMPS * (1.0 + slopeBonus)
		}

		val awardPoints = PointsAwarded(
				Time.nowMillis,
				Points(points),
				AwardSource.SESSION
		)

		PointsDatabase
				.database(applicationContext)
				.pointsAwardedDao()
				.insert(awardPoints)

		return logResult(
				"Awarded ${awardPoints.value.value.format(2)} points from ${awardPoints.source.value}",
				Result.success()
		)
	}

	private fun calculateSlope(locationData: Collection<DatabaseLocation>): Collection<SlopeData> {
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
				val speed = distance / (location.time - prevLocation.time)
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
	}
}
