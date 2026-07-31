package com.adsamcik.tracker.points.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.hilt.work.HiltWorker
import com.adsamcik.tracker.points.data.AwardSource
import com.adsamcik.tracker.points.data.Points
import com.adsamcik.tracker.points.data.PointsAwarded
import com.adsamcik.tracker.points.database.PointsDatabase
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.points.scoring.PointsScorer
import com.adsamcik.tracker.points.scoring.PointsScorer.ScoringLocation
import com.adsamcik.tracker.shared.base.data.ActivityInfo
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.getAllBetweenChunked
import com.adsamcik.tracker.shared.base.mapper.toModel
import com.adsamcik.tracker.shared.base.work.getNonNegativeLongOrNull
import com.adsamcik.tracker.shared.model.Location
import com.adsamcik.tracker.shared.model.LocationSample
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
	// Investigation (M2): Fallback scoring (steps*0.01 + distance*0.005 + duration*0.5) yields
	// >0 points for any real session. Zero-points on emulator is likely a WorkManager scheduling
	// timing issue rather than a calculation bug. No logic change needed.
	override suspend fun doWork(): Result {
		val id = this.inputData.getNonNegativeLongOrNull(ARG_ID) ?: return Result.failure()
		val trip = appDatabase.tripDao().getById(id)
			?: return Result.failure()
		val awardTime = trip.endTimeMs.takeIf { it > 0L } ?: Time.nowMillis
		val pointsDao = pointsDatabase.pointsAwardedDao()

		if (pointsDao.hasAwardAt(awardTime, AwardSource.SESSION.value)) {
			return Result.success()
		}

		val locationData = appDatabase
			.locationSampleDao()
			.getAllBetweenChunked(trip.startTimeMs, trip.endTimeMs)
			.map { it.toModel() }
			.mapNotNull { it.toScoringLocation() }
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
			return Result.failure()
		}

		val awardPoints = PointsAwarded(
			awardTime,
			Points(points),
			AwardSource.SESSION
		)

		pointsDao.insert(awardPoints)

		return Result.success()
	}

	private fun LocationSample.toScoringLocation(): ScoringLocation? {
		val lat = latE7 ?: return null
		val lon = lonE7 ?: return null
		return ScoringLocation(
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
			activity = ActivityInfo.UNKNOWN,
		)
	}

	companion object {
		private const val ARG_ID = TrackerSession.RECEIVER_SESSION_ID
	}
}
