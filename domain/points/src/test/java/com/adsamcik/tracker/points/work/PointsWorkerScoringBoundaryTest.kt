package com.adsamcik.tracker.points.work

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.adsamcik.tracker.points.data.AwardSource
import com.adsamcik.tracker.points.database.PointsDatabase
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.LocationSample
import com.adsamcik.tracker.shared.base.database.data.MotionState
import com.adsamcik.tracker.shared.base.database.data.SampleQuality
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.model.SegmentSource
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import javax.inject.Provider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PointsWorkerScoringBoundaryTest {
	private lateinit var context: Application
	private lateinit var appDatabase: AppDatabase
	private lateinit var pointsDatabase: PointsDatabase

	@Before
	fun setUp() {
		context = ApplicationProvider.getApplicationContext()
		appDatabase = AppDatabase.testDatabase(context)
		pointsDatabase = PointsDatabase.testDatabase(context)
	}

	@After
	fun tearDown() {
		appDatabase.close()
		pointsDatabase.close()
	}

	@Test
	fun `wall-overlapping altitude cannot influence a segment without exact ownership`() = runTest {
		val segmentId = appDatabase.sessionSegmentDao().insert(
			segment(
				startTimeMs = 1_000L,
				endTimeMs = 601_000L,
				distanceM = 2_000f,
				steps = 500_000,
			),
		)
		appDatabase.locationSampleDao().insert(
			listOf(
				location(timeMs = 1_000L, latE7 = 500_000_000, altitudeM = 0f),
				location(timeMs = 601_000L, latE7 = 500_010_000, altitudeM = 10_000f),
			),
		)
		appDatabase.locationSampleDao().countAll() shouldBe 2L

		newWorker(segmentId).doWork() shouldBe ListenableWorker.Result.success()

		// Distance fallback wins: 2,000 m * 0.005 = 10. Neither raw Steps nor the
		// wall-overlapping altitude rows may change this irreversible ledger value.
		pointsDatabase.pointsAwardedDao().countBetween(Long.MIN_VALUE, Long.MAX_VALUE) shouldBe
			(10.0 plusOrMinus 1e-9)
	}

	@Test
	fun `high raw steps with no distance or duration cannot write the points ledger`() = runTest {
		val segmentId = appDatabase.sessionSegmentDao().insert(
			segment(
				startTimeMs = 1_000L,
				endTimeMs = 1_000L,
				distanceM = 0f,
				steps = 1_000_000,
			),
		)

		newWorker(segmentId).doWork() shouldBe ListenableWorker.Result.failure()

		pointsDatabase.pointsAwardedDao().countBetween(Long.MIN_VALUE, Long.MAX_VALUE) shouldBe 0.0
		pointsDatabase.pointsAwardedDao().hasAwardAt(1_000L, AwardSource.SESSION.value) shouldBe false
	}

	@Test
	fun `duration remains rewardable without location or qualified steps`() = runTest {
		val segmentId = appDatabase.sessionSegmentDao().insert(
			segment(
				startTimeMs = 1_000L,
				endTimeMs = 601_000L,
				distanceM = 0f,
				steps = null,
			),
		)

		newWorker(segmentId).doWork() shouldBe ListenableWorker.Result.success()

		// Duration fallback: 10 min * 0.5 = 5.
		pointsDatabase.pointsAwardedDao().countBetween(Long.MIN_VALUE, Long.MAX_VALUE) shouldBe
			(5.0 plusOrMinus 1e-9)
	}

	private fun newWorker(segmentId: Long): PointsWorker =
		TestListenableWorkerBuilder<PointsWorker>(context)
			.setInputData(workDataOf(TrackerSession.RECEIVER_SESSION_ID to segmentId))
			.setWorkerFactory(
				object : WorkerFactory() {
					override fun createWorker(
						appContext: Context,
						workerClassName: String,
						workerParameters: WorkerParameters,
					): ListenableWorker = PointsWorker(
						appContext,
						workerParameters,
						Provider { appDatabase },
						Provider { pointsDatabase },
						ReadyStartupGate,
					)
				},
			)
			.build()

	private fun segment(
		startTimeMs: Long,
		endTimeMs: Long,
		distanceM: Float,
		steps: Int?,
	): SessionSegment = SessionSegment(
		startTimeMs = startTimeMs,
		endTimeMs = endTimeMs,
		distanceM = distanceM,
		steps = steps,
		primaryActivity = null,
		activityConfidence = null,
		sampleCount = 1,
		source = SegmentSource.USER_CREATED,
		inferenceVersion = "test",
		createdAt = startTimeMs,
		logicalTrackingId = "logical-$startTimeMs-$endTimeMs",
		serviceRunId = "run-$startTimeMs-$endTimeMs",
	)

	private fun location(
		timeMs: Long,
		latE7: Int,
		altitudeM: Float,
	): LocationSample = LocationSample(
		timeMs = timeMs,
		elapsedRealtimeNanos = timeMs * 1_000_000L,
		latE7 = latE7,
		lonE7 = 140_000_000,
		altitudeM = altitudeM,
		rawGpsAltitudeM = altitudeM,
		hAccM = 1f,
		vAccM = 1f,
		speedMps = 1f,
		speedAccuracyMps = 1f,
		provider = "gps",
		quality = SampleQuality.HIGH,
		motionState = MotionState.MOVING,
		policy = "ACTIVE_ELEVATED",
		bucketId = null,
		createdAt = timeMs,
	)

	private object ReadyStartupGate : TrackingStartupGate {
		override val isReady: Boolean = true

		override suspend fun reconcile(retryFailedStorage: Boolean): TrackingStartupResult =
			TrackingStartupResult.Ready(
				legacyRecoveryPartial = false,
				liveCompletedThroughOrdinal = 0L,
			)
	}
}
