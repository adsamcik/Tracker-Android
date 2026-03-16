package com.adsamcik.tracker.points.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.hilt.work.HiltWorker
import com.adsamcik.tracker.logger.LogData
import com.adsamcik.tracker.logger.Logger
import com.adsamcik.tracker.points.POINTS_LOG_SOURCE
import com.adsamcik.tracker.points.data.AwardSource
import com.adsamcik.tracker.points.data.Points
import com.adsamcik.tracker.points.data.PointsAwarded
import com.adsamcik.tracker.points.database.PointsDatabase
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.points.scoring.PointsScorer
import com.adsamcik.tracker.shared.base.data.ActivityInfo
import com.adsamcik.tracker.shared.base.data.Location
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.DatabaseLocation
import com.adsamcik.tracker.shared.base.database.data.LocationSample
import com.adsamcik.tracker.shared.base.extension.format
import com.adsamcik.tracker.shared.utils.extension.getPositiveLongReportNull
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
internal class PointsWorker @AssistedInject constructor(
	@Assisted context: Context,
	@Assisted workerParams: WorkerParameters,
	private val appDatabase: AppDatabase,
	private val pointsDatabase: PointsDatabase,
) : CoroutineWorker(
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

	override suspend fun doWork(): Result {
		val id = this.inputData.getPositiveLongReportNull(ARG_ID) ?: return Result.failure()
		val trip = appDatabase.tripDao().getById(id)
			?: return logResult("Found no session for point calculation.", Result.failure())
		val awardTime = trip.endTimeMs.takeIf { it > 0L } ?: Time.nowMillis
		val pointsDao = pointsDatabase.pointsAwardedDao()

		if (pointsDao.hasAwardAt(awardTime, AwardSource.SESSION.value)) {
			return logResult("Points already awarded for session $id at $awardTime, skipping duplicate.", Result.success())
		}

		val locationData = appDatabase
			.locationSampleDao()
			.getAllBetween(trip.startTimeMs, trip.endTimeMs)
			.mapNotNull { it.toDatabaseLocation() }
			.filter { it.altitude != null }

		val scorer = PointsScorer()
		val points = if (locationData.size > 1) {
			scorer.calculateSlopePoints(locationData)
		} else {
			val durationMinutes = ((trip.endTimeMs - trip.startTimeMs).coerceAtLeast(0L) / 60_000.0)
			scorer.calculateFallbackPoints(
				steps = (trip.steps ?: 0),
				distanceMeters = trip.distanceM.toDouble(),
				durationMinutes = durationMinutes,
			)
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

	companion object {
		private const val ARG_ID = TrackerSession.RECEIVER_SESSION_ID
	}
}
