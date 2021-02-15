package com.adsamcik.tracker.points.work

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.adsamcik.tracker.logger.LogData
import com.adsamcik.tracker.logger.Logger
import com.adsamcik.tracker.points.data.AwardSource
import com.adsamcik.tracker.points.data.Points
import com.adsamcik.tracker.points.data.PointsAwarded
import com.adsamcik.tracker.points.database.PointsDatabase
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.extension.toZonedDateTime
import com.adsamcik.tracker.shared.utils.extension.getPositiveLongReportNull

internal class PointsWorker(context: Context, workerParams: WorkerParameters) : Worker(
		context,
		workerParams
) {
	override fun doWork(): Result {
		val id = this.inputData.getPositiveLongReportNull(ARG_ID) ?: return Result.failure()
		val session = AppDatabase.database(applicationContext).sessionDao().get(id)
				?: return Result.failure()

		val points = POINTS_PER_METER * session.distanceOnFootInM

		val awardPoints = PointsAwarded(
				Time.now.toZonedDateTime(),
				Points(points),
				AwardSource.Session
		)

		Logger.log(LogData(message = "Awarded $awardPoints", source = "points"))

		PointsDatabase
				.database(applicationContext)
				.pointsAwardedDao()
				.insert(awardPoints)
		return Result.success()
	}

	companion object {
		private const val POINTS_PER_METER = 0.001
		private const val ARG_ID = TrackerSession.RECEIVER_SESSION_ID
	}
}
