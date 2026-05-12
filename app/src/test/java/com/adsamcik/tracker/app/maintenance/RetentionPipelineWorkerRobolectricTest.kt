package com.adsamcik.tracker.app.maintenance

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.ActivitySnapshotDao
import com.adsamcik.tracker.shared.base.database.dao.CellSampleDao
import com.adsamcik.tracker.shared.base.database.dao.DailySummaryDao
import com.adsamcik.tracker.shared.base.database.dao.DomainEventDao
import com.adsamcik.tracker.shared.base.database.dao.ExportLogDao
import com.adsamcik.tracker.shared.base.database.dao.FrequentPlaceDao
import com.adsamcik.tracker.shared.base.database.dao.InferredTripDao
import com.adsamcik.tracker.shared.base.database.dao.LocationSampleDao
import com.adsamcik.tracker.shared.base.database.dao.PressureSampleDao
import com.adsamcik.tracker.shared.base.database.dao.RouteCacheDao
import com.adsamcik.tracker.shared.base.database.dao.SessionSegmentDao
import com.adsamcik.tracker.shared.base.database.dao.StepIntervalDao
import com.adsamcik.tracker.shared.base.database.dao.TrackerRunDao
import com.adsamcik.tracker.shared.base.database.dao.TripLegDao
import com.adsamcik.tracker.shared.base.database.dao.WifiObservationDao
import com.adsamcik.tracker.shared.preferences.retention.RetentionConfigState
import com.adsamcik.tracker.shared.preferences.retention.RetentionConfigStore
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RetentionPipelineWorkerRobolectricTest {

	@Test
	fun `auto cleanup purges all supported retention tables`() = runTest {
		val context = ApplicationProvider.getApplicationContext<Context>()
		val store: RetentionConfigStore = mockk {
			every { config } returns flowOf(
				RetentionConfigState(
					autoCleanupEnabled = true,
					dataRetentionYears = 1,
				)
			)
		}
		val db: AppDatabase = mockk()
		val locationDao: LocationSampleDao = mockk(relaxed = true)
		val stepDao: StepIntervalDao = mockk(relaxed = true)
		val activityDao: ActivitySnapshotDao = mockk(relaxed = true)
		val runDao: TrackerRunDao = mockk(relaxed = true)
		val pressureDao: PressureSampleDao = mockk(relaxed = true)
		val cellDao: CellSampleDao = mockk(relaxed = true)
		val wifiDao: WifiObservationDao = mockk(relaxed = true)
		val sessionDao: SessionSegmentDao = mockk(relaxed = true)
		val inferredTripDao: InferredTripDao = mockk(relaxed = true)
		val tripLegDao: TripLegDao = mockk(relaxed = true)
		val frequentPlaceDao: FrequentPlaceDao = mockk(relaxed = true)
		val routeCacheDao: RouteCacheDao = mockk(relaxed = true)
		val dailySummaryDao: DailySummaryDao = mockk(relaxed = true)
		val domainEventDao: DomainEventDao = mockk(relaxed = true)
		val exportLogDao: ExportLogDao = mockk(relaxed = true)

		every { db.locationSampleDao() } returns locationDao
		every { db.stepIntervalDao() } returns stepDao
		every { db.activitySnapshotDao() } returns activityDao
		every { db.trackerRunDao() } returns runDao
		every { db.pressureSampleDao() } returns pressureDao
		every { db.cellSampleDao() } returns cellDao
		every { db.wifiObservationDao() } returns wifiDao
		every { db.sessionSegmentDao() } returns sessionDao
		every { db.inferredTripDao() } returns inferredTripDao
		every { db.tripLegDao() } returns tripLegDao
		every { db.frequentPlaceDao() } returns frequentPlaceDao
		every { db.routeCacheDao() } returns routeCacheDao
		every { db.dailySummaryDao() } returns dailySummaryDao
		every { db.explorationCellDao() } returns mockk(relaxed = true)
		every { db.explorationStreakDao() } returns mockk(relaxed = true)
		every { db.achievementProgressDao() } returns mockk(relaxed = true)
		every { db.personalRecordDao() } returns mockk(relaxed = true)
		every { db.domainEventDao() } returns domainEventDao
		every { db.exportLogDao() } returns exportLogDao

		val worker = worker(context, store, db)

		assertEquals(ListenableWorker.Result.success(), worker.doWork())

		coVerify(exactly = 1) { locationDao.deleteOlderThan(any()) }
		coVerify(exactly = 1) { stepDao.deleteOlderThan(any()) }
		coVerify(exactly = 1) { activityDao.deleteOlderThan(any()) }
		coVerify(exactly = 1) { runDao.deleteOlderThan(any()) }
		coVerify(exactly = 1) { pressureDao.deleteOlderThan(any()) }
		coVerify(exactly = 1) { cellDao.deleteOlderThan(any()) }
		coVerify(exactly = 1) { wifiDao.deleteOlderThan(any()) }
		coVerify(exactly = 1) { sessionDao.deleteOlderThan(any()) }
		coVerify(exactly = 1) { inferredTripDao.deleteOlderThan(any()) }
		coVerify(exactly = 1) { tripLegDao.deleteOlderThan(any()) }
		coVerify(exactly = 1) { frequentPlaceDao.deleteOlderThan(any()) }
		coVerify(exactly = 1) { routeCacheDao.deleteOlderThan(any()) }
		coVerify(exactly = 1) { dailySummaryDao.deleteOlderThan(any()) }
		coVerify(exactly = 1) { domainEventDao.deleteOlderThan(any()) }
		coVerify(exactly = 1) { exportLogDao.deleteOlderThan(any()) }
	}

	@Test
	fun `auto cleanup zero years keeps data forever`() = runTest {
		val context = ApplicationProvider.getApplicationContext<Context>()
		val store: RetentionConfigStore = mockk {
			every { config } returns flowOf(
				RetentionConfigState(
					autoCleanupEnabled = true,
					dataRetentionYears = 0,
				)
			)
		}
		val db: AppDatabase = mockk(relaxed = true)

		assertEquals(ListenableWorker.Result.success(), worker(context, store, db).doWork())
		verify(exactly = 0) { db.locationSampleDao() }
		verify(exactly = 0) { db.wifiObservationDao() }
		verify(exactly = 0) { db.cellSampleDao() }
		verify(exactly = 0) { db.domainEventDao() }
		verify(exactly = 0) { db.exportLogDao() }
	}

	private fun worker(
		context: Context,
		store: RetentionConfigStore,
		db: AppDatabase,
	): RetentionPipelineWorker =
		TestListenableWorkerBuilder<RetentionPipelineWorker>(context)
			.setWorkerFactory(object : WorkerFactory() {
				override fun createWorker(
					appContext: Context,
					workerClassName: String,
					workerParameters: WorkerParameters,
				): ListenableWorker = RetentionPipelineWorker(
					appContext,
					workerParameters,
					store,
					db,
				)
			})
			.build() as RetentionPipelineWorker
}
