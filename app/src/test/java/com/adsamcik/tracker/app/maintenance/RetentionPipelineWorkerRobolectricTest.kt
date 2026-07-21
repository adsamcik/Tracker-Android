package com.adsamcik.tracker.app.maintenance

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.migration.DatabaseMigrationBackupRepository
import com.adsamcik.tracker.shared.base.database.dao.ActivitySnapshotDao
import com.adsamcik.tracker.shared.base.database.dao.CellSampleDao
import com.adsamcik.tracker.shared.base.database.dao.DailySummaryDao
import com.adsamcik.tracker.shared.base.database.dao.DomainEventDao
import com.adsamcik.tracker.shared.base.database.dao.ExportLogDao
import com.adsamcik.tracker.shared.base.database.dao.FrequentPlaceDao
import com.adsamcik.tracker.shared.base.database.dao.InferredTripDao
import com.adsamcik.tracker.shared.base.database.dao.LocationObservationDao
import com.adsamcik.tracker.shared.base.database.dao.LocationObservationDecisionDao
import com.adsamcik.tracker.shared.base.database.dao.LocationSampleDao
import com.adsamcik.tracker.shared.base.database.dao.PendingSignalDao
import com.adsamcik.tracker.shared.base.database.dao.PressureSampleDao
import com.adsamcik.tracker.shared.base.database.dao.RouteCacheDao
import com.adsamcik.tracker.shared.base.database.dao.SessionSegmentDao
import com.adsamcik.tracker.shared.base.database.dao.SkiRunSegmentDao
import com.adsamcik.tracker.shared.base.database.dao.SourceEvidenceStateDao
import com.adsamcik.tracker.shared.base.database.dao.StepIntervalDao
import com.adsamcik.tracker.shared.base.database.dao.TrackerStateEventDao
import com.adsamcik.tracker.shared.base.database.dao.TrackerRunDao
import com.adsamcik.tracker.shared.base.database.dao.TripLegDao
import com.adsamcik.tracker.shared.base.database.dao.WifiObservationDao
import com.adsamcik.tracker.shared.base.database.data.LocationSample
import com.adsamcik.tracker.shared.base.database.data.PendingSignalEntity
import com.adsamcik.tracker.shared.base.database.data.SampleQuality
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleSnapshot
import com.adsamcik.tracker.shared.preferences.retention.RetentionConfigState
import com.adsamcik.tracker.shared.preferences.retention.RetentionConfigStore
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor
import kotlin.coroutines.EmptyCoroutineContext

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
		val db: AppDatabase = mockk(relaxed = true)
		every { db.transactionExecutor } returns DIRECT_EXECUTOR
		every { db.suspendingTransactionContext } returns
			ThreadLocal.withInitial { EmptyCoroutineContext }
		every { db.beginTransaction() } returns Unit
		every { db.setTransactionSuccessful() } returns Unit
		every { db.endTransaction() } returns Unit
		val locationDao: LocationSampleDao = mockk(relaxed = true)
		val locationObservationDao: LocationObservationDao = mockk(relaxed = true)
		val stepDao: StepIntervalDao = mockk(relaxed = true)
		val activityDao: ActivitySnapshotDao = mockk(relaxed = true)
		val runDao: TrackerRunDao = mockk(relaxed = true)
		val pressureDao: PressureSampleDao = mockk(relaxed = true)
		val skiRunSegmentDao: SkiRunSegmentDao = mockk(relaxed = true)
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
		val pendingSignalDao: PendingSignalDao = mockk(relaxed = true)
		val locationObservationDecisionDao: LocationObservationDecisionDao = mockk(relaxed = true)
		val trackerStateEventDao: TrackerStateEventDao = mockk(relaxed = true)
		val sourceEvidenceStateDao = sourceEvidenceStateDao()
		val migrationBackupRepository: DatabaseMigrationBackupRepository = mockk(relaxed = true)
		val collectedDataLifecycleStore = lifecycleStore()
		coEvery { runDao.minStartTimeMs() } returns null
		coEvery { locationObservationDao.minFixTimeMs() } returns null

		every { db.locationSampleDao() } returns locationDao
		every { db.locationObservationDao() } returns locationObservationDao
		every { db.stepIntervalDao() } returns stepDao
		every { db.activitySnapshotDao() } returns activityDao
		every { db.trackerRunDao() } returns runDao
		every { db.pressureSampleDao() } returns pressureDao
		every { db.skiRunSegmentDao() } returns skiRunSegmentDao
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
		every { db.pendingSignalDao() } returns pendingSignalDao
		every { db.locationObservationDecisionDao() } returns locationObservationDecisionDao
		every { db.trackerStateEventDao() } returns trackerStateEventDao
		every { db.sourceEvidenceStateDao() } returns sourceEvidenceStateDao

		val worker = worker(
			context,
			store,
			db,
			migrationBackupRepository,
			collectedDataLifecycleStore,
		)

		assertEquals(ListenableWorker.Result.success(), worker.doWork())

		coVerify(exactly = 1) { locationDao.deleteOlderThan(any()) }
		coVerify(exactly = 1) { collectedDataLifecycleStore.advanceRetainedFrom(any()) }
		coVerifyOrder {
			collectedDataLifecycleStore.advanceRetainedFrom(any())
			locationDao.deleteOlderThan(any())
		}
		coVerify(exactly = 1) { locationObservationDao.deleteOlderThan(any()) }
		coVerify(exactly = 0) { locationObservationDao.deleteOlderThanThroughId(any(), any()) }
		coVerify(exactly = 1) { locationObservationDecisionDao.deleteOlderThan(any()) }
		coVerify(exactly = 1) { locationObservationDecisionDao.deleteWithoutObservation() }
		coVerify(exactly = 1) { trackerStateEventDao.deleteOlderThan(any()) }
		coVerify(exactly = 1) { sourceEvidenceStateDao.updateLifecycle(any(), any(), any()) }
		coVerify(exactly = 1) { stepDao.deleteOlderThan(any()) }
		coVerify(exactly = 1) { activityDao.deleteOlderThan(any()) }
		coVerify(exactly = 1) { runDao.deleteOlderThan(any()) }
		coVerify(exactly = 1) { pressureDao.deleteOlderThan(any()) }
		coVerify(exactly = 1) { skiRunSegmentDao.deleteOlderThan(any()) }
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
		verify(exactly = 1) { migrationBackupRepository.deleteAll() }
	}

	@Test
	fun `pending signal WAL defers raw retention after advancing privacy boundary`() = runTest {
		val context = ApplicationProvider.getApplicationContext<Context>()
		val db = AppDatabase.testDatabase(context)
		val collectedDataLifecycleStore = lifecycleStore(
			CollectedDataLifecycleSnapshot(epoch = 9L, retainedFromMs = 1_234L),
		)
		val migrationBackupRepository: DatabaseMigrationBackupRepository = mockk(relaxed = true)
		try {
			db.locationSampleDao().insert(
				LocationSample(
					timeMs = 1L,
					elapsedRealtimeNanos = 1L,
					latE7 = 500_000_000,
					lonE7 = 140_000_000,
					altitudeM = null,
					rawGpsAltitudeM = null,
					hAccM = 5f,
					vAccM = null,
					speedMps = null,
					speedAccuracyMps = null,
					provider = "fused",
					quality = SampleQuality.HIGH,
					motionState = null,
					policy = null,
					bucketId = null,
					createdAt = 1L,
				),
			)
			db.pendingSignalDao().insertAll(
				listOf(PendingSignalEntity(sessionId = 7L, signalJson = "{}", createdAt = 1L)),
			)

			assertEquals(
				ListenableWorker.Result.retry(),
				worker(
					context,
					retentionStore(autoPurgeConfig(rawDataRetentionDays = 1)),
					db,
					migrationBackupRepository = migrationBackupRepository,
					collectedDataLifecycleStore = collectedDataLifecycleStore,
				).doWork(),
			)

			assertEquals(1L, db.locationSampleDao().countAll())
			assertEquals(1, db.pendingSignalDao().countAll())
			assertEquals(1L, db.sourceEvidenceStateDao().get()?.revision)
			assertEquals(9L, db.sourceEvidenceStateDao().get()?.collectedDataEpoch)
			assertEquals(1_234L, db.sourceEvidenceStateDao().get()?.retainedFromMs)
			coVerify(exactly = 1) { collectedDataLifecycleStore.advanceRetainedFrom(any()) }
			verify(exactly = 1) { migrationBackupRepository.deleteAll() }
		} finally {
			db.close()
		}
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

	@Test
	fun `domain event purge clamps cutoff to slowest consumer cursor`() = runTest {
		val context = ApplicationProvider.getApplicationContext<Context>()
		val slowestCursorMs = 1_234L
		val domainEventDao: DomainEventDao = mockk(relaxed = true)
		coEvery { domainEventDao.getMinimumCursorTimestampMs() } returns slowestCursorMs
		val db = retentionDatabase(domainEventDao = domainEventDao)

		assertEquals(
			ListenableWorker.Result.success(),
			worker(context, retentionStore(autoPurgeConfig(rawDataRetentionDays = 1)), db).doWork()
		)

		coVerify(exactly = 1) { domainEventDao.deleteOlderThan(slowestCursorMs) }
	}

	@Test
	fun `domain event purge uses raw cutoff when every consumer is past retention cutoff`() = runTest {
		val context = ApplicationProvider.getApplicationContext<Context>()
		val domainEventDao: DomainEventDao = mockk(relaxed = true)
		coEvery { domainEventDao.getMinimumCursorTimestampMs() } returns Long.MAX_VALUE
		val cutoffSlot = slot<Long>()
		val db = retentionDatabase(domainEventDao = domainEventDao)
		val before = System.currentTimeMillis()

		assertEquals(
			ListenableWorker.Result.success(),
			worker(context, retentionStore(autoPurgeConfig(rawDataRetentionDays = 1)), db).doWork()
		)

		val after = System.currentTimeMillis()
		coVerify(exactly = 1) { domainEventDao.deleteOlderThan(capture(cutoffSlot)) }
		assertTrue(
			cutoffSlot.captured in
				(before - Time.DAY_IN_MILLISECONDS)..(after - Time.DAY_IN_MILLISECONDS)
		)
	}

	@Test
	fun `domain event purge uses raw cutoff when no consumer cursors exist`() = runTest {
		val context = ApplicationProvider.getApplicationContext<Context>()
		val domainEventDao: DomainEventDao = mockk(relaxed = true)
		coEvery { domainEventDao.getMinimumCursorTimestampMs() } returns null
		val cutoffSlot = slot<Long>()
		val db = retentionDatabase(domainEventDao = domainEventDao)
		val before = System.currentTimeMillis()

		assertEquals(
			ListenableWorker.Result.success(),
			worker(context, retentionStore(autoPurgeConfig(rawDataRetentionDays = 1)), db).doWork()
		)

		val after = System.currentTimeMillis()
		coVerify(exactly = 1) { domainEventDao.deleteOlderThan(capture(cutoffSlot)) }
		assertTrue(
			cutoffSlot.captured in
				(before - Time.DAY_IN_MILLISECONDS)..(after - Time.DAY_IN_MILLISECONDS)
		)
	}

	private fun worker(
		context: Context,
		store: RetentionConfigStore,
		db: AppDatabase,
		migrationBackupRepository: DatabaseMigrationBackupRepository = mockk(relaxed = true),
		collectedDataLifecycleStore: CollectedDataLifecycleStore = lifecycleStore(),
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
					collectedDataLifecycleStore,
					db,
					migrationBackupRepository,
				)
			})
			.build() as RetentionPipelineWorker

	private fun retentionStore(state: RetentionConfigState): RetentionConfigStore = mockk {
		every { config } returns flowOf(state)
	}

	private fun autoPurgeConfig(rawDataRetentionDays: Int): RetentionConfigState =
		RetentionConfigState(
			autoPurgeEnabled = true,
			rawDataRetentionDays = rawDataRetentionDays,
			wifiCellRetentionDays = 0,
			tripRetentionDays = 0,
			dailySummaryRetentionDays = 0,
			explorationRetentionDays = 0,
		)

	private fun retentionDatabase(
		domainEventDao: DomainEventDao = mockk(relaxed = true),
		exportLogDao: ExportLogDao = mockk(relaxed = true),
		pendingSignalDao: PendingSignalDao = mockk(relaxed = true),
	): AppDatabase {
		val db: AppDatabase = mockk(relaxed = true)
		every { db.transactionExecutor } returns DIRECT_EXECUTOR
		every { db.suspendingTransactionContext } returns
			ThreadLocal.withInitial { EmptyCoroutineContext }
		every { db.beginTransaction() } returns Unit
		every { db.setTransactionSuccessful() } returns Unit
		every { db.endTransaction() } returns Unit
		val locationObservationDao: LocationObservationDao = mockk(relaxed = true)
		val sourceEvidenceStateDao = sourceEvidenceStateDao()
		val trackerRunDao: TrackerRunDao = mockk(relaxed = true)
		coEvery { trackerRunDao.minStartTimeMs() } returns null
		coEvery { locationObservationDao.minFixTimeMs() } returns null
		every { db.locationSampleDao() } returns mockk(relaxed = true)
		every { db.locationObservationDao() } returns locationObservationDao
		every { db.stepIntervalDao() } returns mockk(relaxed = true)
		every { db.activitySnapshotDao() } returns mockk(relaxed = true)
		every { db.trackerRunDao() } returns trackerRunDao
		every { db.pressureSampleDao() } returns mockk(relaxed = true)
		every { db.skiRunSegmentDao() } returns mockk(relaxed = true)
		every { db.domainEventDao() } returns domainEventDao
		every { db.exportLogDao() } returns exportLogDao
		every { db.pendingSignalDao() } returns pendingSignalDao
		every { db.locationObservationDecisionDao() } returns mockk(relaxed = true)
		every { db.trackerStateEventDao() } returns mockk(relaxed = true)
		every { db.sourceEvidenceStateDao() } returns sourceEvidenceStateDao
		return db
	}

	private fun lifecycleStore(
		snapshot: CollectedDataLifecycleSnapshot =
			CollectedDataLifecycleSnapshot(epoch = 1L, retainedFromMs = 1L),
	): CollectedDataLifecycleStore = mockk {
		coEvery { advanceRetainedFrom(any()) } returns snapshot
	}

	private fun sourceEvidenceStateDao(): SourceEvidenceStateDao = mockk {
		coEvery { ensure(any()) } just Runs
		coEvery { get() } returns SourceEvidenceState()
		coEvery { updateLifecycle(any(), any(), any()) } returns 1
		coEvery { incrementRevision(any()) } returns 1
	}

	private companion object {
		val DIRECT_EXECUTOR = Executor(Runnable::run)
	}
}
