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
import com.adsamcik.tracker.points.scoring.PointsScorer
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.base.work.getNonNegativeLongOrNull
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
		return trackingStartupGate.withReadyGenerationOperation(startupGeneration) {
			val appDatabase = appDatabaseProvider.get()
			val pointsDatabase = pointsDatabaseProvider.get()
			val trip = appDatabase.tripDao().getById(id)
				?: return@withReadyGenerationOperation Result.failure()
			val awardTime = trip.endTimeMs.takeIf { it > 0L } ?: Time.nowMillis
			val pointsDao = pointsDatabase.pointsAwardedDao()

			if (pointsDao.hasAwardAt(awardTime, AwardSource.SESSION.value)) {
				return@withReadyGenerationOperation Result.success()
			}

			val scorer = PointsScorer()
			val durationMinutes = ((trip.endTimeMs - trip.startTimeMs).coerceAtLeast(0L) / 60_000.0)
			// Location rows are not bound to this exact segment/run in the current read contract.
			// Wall-time overlap is not ownership, so slope scoring stays disabled until an exact
			// ownership query exists. Segment-local distance and duration remain independently useful.
			val points = scorer.calculateFallbackPoints(
				distanceMeters = trip.distanceM.toDouble(),
				durationMinutes = durationMinutes,
			)

			if (points <= 0.0) {
				return@withReadyGenerationOperation Result.failure()
			}

			val awardPoints = PointsAwarded(
				awardTime,
				Points(points),
				AwardSource.SESSION
			)

			pointsDatabase.withTransaction {
				// Re-check under the same fenced transaction as the insert so a concurrent
				// duplicate worker cannot race the earlier fast-path query.
				if (!pointsDao.hasAwardAt(awardTime, AwardSource.SESSION.value)) {
					pointsDao.insert(awardPoints)
				}
			}

			Result.success()
		} ?: Result.success()
	}

	companion object {
		private const val ARG_ID = TrackerSession.RECEIVER_SESSION_ID
	}
}
