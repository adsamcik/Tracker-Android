package com.adsamcik.tracker.points.work

import android.content.Context
import androidx.room.withTransaction
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
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.model.Location
import com.adsamcik.tracker.shared.model.LocationSample
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import javax.inject.Provider

@HiltWorker
internal class PointsWorker @AssistedInject constructor(
	@Assisted context: Context,
	@Assisted workerParams: WorkerParameters,
	private val appDatabaseProvider: Provider<AppDatabase>,
	private val pointsDatabaseProvider: Provider<PointsDatabase>,
	private val trackingStartupGate: TrackingStartupGate,
) : CoroutineWorker(
	context,
	workerParams
) {
	override suspend fun doWork(): Result {
		val id = this.inputData.getNonNegativeLongOrNull(ARG_ID) ?: return Result.failure()
		val startupGeneration = trackingStartupGate.currentGeneration
		when (trackingStartupGate.reconcile()) {
			is TrackingStartupResult.Ready -> Unit
			is TrackingStartupResult.RetryableFailure -> return Result.retry()
			is TrackingStartupResult.Blocked -> return Result.success()
		}
		return try {
			if (!isReadyGeneration(startupGeneration)) return Result.success()
			val appDatabase = appDatabaseProvider.get()
			if (!isReadyGeneration(startupGeneration)) return Result.success()
			val pointsDatabase = pointsDatabaseProvider.get()
			if (!isReadyGeneration(startupGeneration)) return Result.success()
			val trip = appDatabase.tripDao().getById(id)
				?: return Result.failure()
			val awardTime = trip.endTimeMs.takeIf { it > 0L } ?: Time.nowMillis
			if (!isReadyGeneration(startupGeneration)) return Result.success()
			val pointsDao = pointsDatabase.pointsAwardedDao()

			if (!isReadyGeneration(startupGeneration)) return Result.success()
			if (pointsDao.hasAwardAt(awardTime, AwardSource.SESSION.value)) {
				return Result.success()
			}

			if (!isReadyGeneration(startupGeneration)) return Result.success()
			val locationData = appDatabase
				.locationSampleDao()
				.getAllBetweenChunked(
					fromMs = trip.startTimeMs,
					toMs = trip.endTimeMs,
					verifyCollectedDataAccess = { requireReadyGeneration(startupGeneration) },
				)
				.map { it.toModel() }
				.mapNotNull { it.toScoringLocation() }
				.filter { it.altitude != null }

			val scorer = PointsScorer()
			val points = if (locationData.size > 1) {
				scorer.calculateSlopePoints(locationData)
			} else {
				val durationMinutes = ((trip.endTimeMs - trip.startTimeMs).coerceAtLeast(0L) / 60_000.0)
				scorer.calculateFallbackPoints(
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

			pointsDatabase.withTransaction {
				requireReadyGeneration(startupGeneration)
				try {
					// Re-check under the same fenced transaction as the insert so a concurrent
					// duplicate worker cannot race the earlier fast-path query.
					if (!pointsDao.hasAwardAt(awardTime, AwardSource.SESSION.value)) {
						pointsDao.insert(awardPoints)
					}
				} finally {
					requireReadyGeneration(startupGeneration)
				}
			}

			Result.success()
		} catch (_: StartupGenerationChangedException) {
			Result.success()
		}
	}

	private fun isReadyGeneration(startupGeneration: Long): Boolean =
		trackingStartupGate.isReady && trackingStartupGate.currentGeneration == startupGeneration

	private fun requireReadyGeneration(startupGeneration: Long) {
		if (!isReadyGeneration(startupGeneration)) throw StartupGenerationChangedException
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

	private object StartupGenerationChangedException : RuntimeException()
}
