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
import com.adsamcik.tracker.shared.base.database.dao.LocationObservationDao
import com.adsamcik.tracker.shared.base.database.dao.LocationObservationDecisionDao
import com.adsamcik.tracker.shared.base.database.dao.LocationSampleDao
import com.adsamcik.tracker.shared.base.database.dao.PendingSignalDao
import com.adsamcik.tracker.shared.base.database.dao.PressureSampleDao
import com.adsamcik.tracker.shared.base.database.dao.QuarantinedSignalDao
import com.adsamcik.tracker.shared.base.database.dao.SessionSegmentDao
import com.adsamcik.tracker.shared.base.database.dao.SkiRunSegmentDao
import com.adsamcik.tracker.shared.base.database.dao.SourceEvidenceStateDao
import com.adsamcik.tracker.shared.base.database.dao.StepFactRevisionDao
import com.adsamcik.tracker.shared.base.database.dao.StepIntervalDao
import com.adsamcik.tracker.shared.base.database.dao.TrackerStateEventDao
import com.adsamcik.tracker.shared.base.database.dao.TrackerRunDao
import com.adsamcik.tracker.shared.base.database.dao.WifiObservationDao
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.LocationSample
import com.adsamcik.tracker.shared.base.database.data.PendingSignalEntity
import com.adsamcik.tracker.shared.base.database.data.SampleQuality
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionFailureEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionIntegrity
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.base.startup.TrackingStartupStage
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleSnapshot
import com.adsamcik.tracker.shared.preferences.retention.RetentionConfigState
import com.adsamcik.tracker.shared.preferences.retention.RetentionConfigStore
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.tracker.source.ingress.CorruptSourceEventException
import com.adsamcik.tracker.tracker.source.ingress.DurableSourceIngress
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.projection.StepsSessionFactDrainResult
import com.adsamcik.tracker.tracker.source.projection.StepsSessionFactProjectionLane
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
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
import javax.inject.Provider
import kotlin.coroutines.EmptyCoroutineContext

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@Suppress("LargeClass")
class RetentionPipelineWorkerRobolectricTest {
	@Test
	fun `retryable startup does not resolve the collected database`() = runTest {
		val context = ApplicationProvider.getApplicationContext<Context>()
		var resolutions = 0
		val retryableGate = object : TrackingStartupGate {
			override val isReady: Boolean = false
			override val currentGeneration: Long = 7L
			override suspend fun reconcile(retryFailedStorage: Boolean) =
				TrackingStartupResult.RetryableFailure(TrackingStartupStage.STORAGE, "DB_BUSY")
		}

		val result = worker(
			context = context,
			store = retentionStore(autoPurgeConfig(rawDataRetentionDays = 1)),
			db = mockk(relaxed = true),
			databaseProvider = Provider {
				resolutions += 1
				mockk(relaxed = true)
			},
			trackingStartupGate = retryableGate,
		).doWork()

		assertEquals(ListenableWorker.Result.retry(), result)
		assertEquals(0, resolutions)
	}

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
		val stepFactRevisionDao: StepFactRevisionDao = mockk(relaxed = true)
		val stepDao: StepIntervalDao = mockk(relaxed = true)
		val activityDao: ActivitySnapshotDao = mockk(relaxed = true)
		val runDao: TrackerRunDao = mockk(relaxed = true)
		val pressureDao: PressureSampleDao = mockk(relaxed = true)
		val skiRunSegmentDao: SkiRunSegmentDao = mockk(relaxed = true)
		val cellDao: CellSampleDao = mockk(relaxed = true)
		val wifiDao: WifiObservationDao = mockk(relaxed = true)
		val sessionDao: SessionSegmentDao = mockk(relaxed = true)
		val dailySummaryDao: DailySummaryDao = mockk(relaxed = true)
		val domainEventDao: DomainEventDao = mockk(relaxed = true)
		val exportLogDao: ExportLogDao = mockk(relaxed = true)
		val quarantinedSignalDao: QuarantinedSignalDao = mockk(relaxed = true)
		val pendingSignalDao: PendingSignalDao = mockk(relaxed = true)
		val locationObservationDecisionDao: LocationObservationDecisionDao = mockk(relaxed = true)
		val trackerStateEventDao: TrackerStateEventDao = mockk(relaxed = true)
		val sourceEvidenceStateDao = sourceEvidenceStateDao()
		val migrationBackupRepository: DatabaseMigrationBackupRepository = mockk(relaxed = true)
		val collectedDataLifecycleStore = lifecycleStore()
		val stepsProjectionLane = mockk<StepsSessionFactProjectionLane>()
		coEvery { stepsProjectionLane.drainAvailable() } returns StepsSessionFactDrainResult.Inactive
		coEvery { runDao.minStartTimeMs() } returns null
		coEvery { locationObservationDao.minFixTimeMs() } returns null

		every { db.locationSampleDao() } returns locationDao
		every { db.locationObservationDao() } returns locationObservationDao
		every { db.stepFactRevisionDao() } returns stepFactRevisionDao
		every { db.stepIntervalDao() } returns stepDao
		every { db.activitySnapshotDao() } returns activityDao
		every { db.trackerRunDao() } returns runDao
		every { db.pressureSampleDao() } returns pressureDao
		every { db.skiRunSegmentDao() } returns skiRunSegmentDao
		every { db.cellSampleDao() } returns cellDao
		every { db.wifiObservationDao() } returns wifiDao
		every { db.sessionSegmentDao() } returns sessionDao
		every { db.dailySummaryDao() } returns dailySummaryDao
		every { db.explorationCellDao() } returns mockk(relaxed = true)
		every { db.explorationStreakDao() } returns mockk(relaxed = true)
		every { db.achievementProgressDao() } returns mockk(relaxed = true)
		every { db.domainEventDao() } returns domainEventDao
		every { db.exportLogDao() } returns exportLogDao
		every { db.quarantinedSignalDao() } returns quarantinedSignalDao
		every { db.pendingSignalDao() } returns pendingSignalDao
		every { db.locationObservationDecisionDao() } returns locationObservationDecisionDao
		every { db.trackerStateEventDao() } returns trackerStateEventDao
		every { db.sourceEvidenceStateDao() } returns sourceEvidenceStateDao

		val worker = worker(
			context = context,
			store = store,
			db = db,
			migrationBackupRepository = migrationBackupRepository,
			collectedDataLifecycleStore = collectedDataLifecycleStore,
			stepsProjectionLaneProvider = Provider { stepsProjectionLane },
		)

		assertEquals(ListenableWorker.Result.success(), worker.doWork())

		coVerify(exactly = 1) { locationDao.deleteOlderThan(any()) }
		coVerify(exactly = 1) { collectedDataLifecycleStore.advanceRetainedFrom(any()) }
		coVerify(exactly = 1) { stepsProjectionLane.drainAvailable() }
		coVerifyOrder {
			collectedDataLifecycleStore.advanceRetainedFrom(any())
			locationDao.deleteOlderThan(any())
		}
		coVerify(exactly = 1) { locationObservationDao.deleteOlderThan(any()) }
		coVerify(exactly = 0) { locationObservationDao.deleteOlderThanThroughId(any(), any()) }
		coVerify(exactly = 1) { locationObservationDecisionDao.deleteOlderThan(any()) }
		coVerify(exactly = 1) { locationObservationDecisionDao.deleteWithoutObservation() }
		coVerify(exactly = 1) { trackerStateEventDao.deleteOlderThan(any()) }
		assertEquals(1L, sourceEvidenceStateDao.get()?.collectedDataEpoch)
		assertEquals(1L, sourceEvidenceStateDao.get()?.retainedFromMs)
		coVerify(exactly = 2) {
			stepFactRevisionDao.retentionCandidateServiceRunIds(any(), any(), any())
		}
		coVerify(exactly = 1) { stepDao.deleteOlderThan(any()) }
		coVerify(exactly = 1) { activityDao.deleteOlderThan(any()) }
		coVerify(exactly = 1) { runDao.deleteOlderThan(any()) }
		coVerify(exactly = 1) { pressureDao.deleteOlderThan(any()) }
		coVerify(exactly = 1) { skiRunSegmentDao.deleteOlderThan(any()) }
		coVerify(exactly = 1) { cellDao.deleteOlderThan(any()) }
		coVerify(exactly = 1) { wifiDao.deleteOlderThan(any()) }
		coVerify(exactly = 1) { sessionDao.deleteOlderThan(any()) }
		coVerify(exactly = 1) { dailySummaryDao.deleteOlderThan(any()) }
		coVerify(exactly = 1) { domainEventDao.deleteOlderThan(any()) }
		coVerify(exactly = 1) { exportLogDao.deleteOlderThan(any()) }
		verify(exactly = 1) { quarantinedSignalDao.deleteAcquiredBefore(any()) }
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
			insertExpiredStepsHistory(db, collectedDataEpoch = 9L)
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
				listOf(
					PendingSignalEntity(
						signalId = "retention-fixture-1",
						sessionId = 7L,
						envelopeVersion = 1,
						payloadChecksum =
							"1280fde14031e7b67bce77ff73860e29dbb51d1f3698679d28f2f93ed1beb128",
						signalJson =
							"""{"type":"tracking_signal","payload":{"ts":1,"ern":0}}""",
						createdAt = 1L,
					),
				),
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
			assertEquals(1L, db.stepFactRevisionDao().countAll())
			assertEquals(1, db.pendingSignalDao().countAll())
			assertEquals(1L, db.sourceEvidenceStateDao().get()?.revision)
			assertEquals(9L, db.sourceEvidenceStateDao().get()?.collectedDataEpoch)
			assertEquals(1_234L, db.sourceEvidenceStateDao().get()?.retainedFromMs)
			val marker = requireNotNull(db.sourceDeletionFenceDao().get(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
				purpose = StepFactRevisionIntegrity.RETENTION_TRUNCATION_PURPOSE,
				scopeKind = SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
				scopeIdentityDigest = StepFactRevisionIntegrity.retentionTruncationIdentity(
					"expired-session",
					"expired-run",
				),
			))
			assertTrue(StepFactRevisionIntegrity.isRetentionTruncationFence(
				marker,
				"expired-session",
				"expired-run",
				9L,
			))
			coVerify(exactly = 1) { collectedDataLifecycleStore.advanceRetainedFrom(any()) }
			verify(exactly = 1) { migrationBackupRepository.deleteAll() }
		} finally {
			db.close()
		}
	}

	@Test
	@Suppress("LongMethod")
	fun `raw retention cannot trust a stale Steps integrity failure to prune its WAL`() = runTest {
		val context = ApplicationProvider.getApplicationContext<Context>()
		val db = AppDatabase.testDatabase(context)
		val lifecycle = lifecycleStore(
			CollectedDataLifecycleSnapshot(epoch = 2L, retainedFromMs = 1_234L),
		)
		val ingress = mockk<DurableSourceIngress>()
		coEvery {
			ingress.committedSourceBatch(SourceKind.STEPS, 0L, 1L, 64)
		} throws CorruptSourceEventException(
			admissionOrdinal = 1L,
			sourceKind = SourceKind.STEPS.stableCode,
			failureCode = "RAW_PAYLOAD_INTEGRITY",
		)
		val lane = StepsSessionFactProjectionLane(db, ingress, backgroundScope)
		try {
			db.sourceEvidenceStateDao().ensure(SourceEvidenceState(collectedDataEpoch = 2L))
			db.sourceDestinationOwnerDao().insertIfAbsent(
				SourceDestinationOwnerEntity(
					sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
					destination = SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS,
					owner = SourceDestinationOwnerEntity.OWNER_LEGACY_STEP_INTERVAL,
					ownerGeneration = SourceDestinationOwnerEntity.INITIAL_LEGACY_GENERATION,
					updatedAtMs = 0L,
				),
			)
			assertEquals(
				1,
				db.sourceDestinationOwnerDao().compareAndSetOwner(
					sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
					destination = SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS,
					expectedOwner = SourceDestinationOwnerEntity.OWNER_LEGACY_STEP_INTERVAL,
					expectedOwnerGeneration =
						SourceDestinationOwnerEntity.INITIAL_LEGACY_GENERATION,
					newOwner = SourceDestinationOwnerEntity.OWNER_STEPS_SESSION_FACTS,
					newOwnerGeneration = SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
					updatedAtMs = 1L,
				),
			)
			insertExpiredStepsHistory(db, collectedDataEpoch = 2L)
			db.sourceEventWalDao().insertIgnoringDuplicate(staleRawStepsEvent())
			db.sourceProjectionStateDao().installProductLane(
				SourceProductProjectionLaneEntity(
					sourceKind = SourceKind.STEPS.stableCode,
					bindingGeneration = StepsSessionFactProjectionLane.BINDING_GENERATION,
					projectionId = StepsSessionFactProjectionLane.WRITER_ID,
					projectionVersion = StepsSessionFactProjectionLane.WRITER_VERSION,
					captureModeMask = StepsSessionFactProjectionLane.MANUAL_CAPTURE_MODE_MASK,
					productStage = SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL,
					activatedRolloutRevision = 2L,
					activationOrdinal = 1L,
					contiguousAdmissionOrdinal = 0L,
					captureAdmissionCutoffOrdinal = null,
					retentionRequired = true,
					status = SourceProductProjectionLaneEntity.STATUS_ACTIVE,
					installedAtMs = 1L,
					updatedAtMs = 1L,
				),
			)
			db.sourceProjectionStateDao().saveFailure(
				SourceProjectionFailureEntity(
					projectionId = StepsSessionFactProjectionLane.WRITER_ID,
					projectionVersion = StepsSessionFactProjectionLane.WRITER_VERSION,
					admissionOrdinal = 1L,
					attemptCount = 1,
					failureCode = "RAW_PAYLOAD_INTEGRITY",
					terminal = true,
					lastAttemptAtMs = 1L,
				),
			)

			assertEquals(
				ListenableWorker.Result.success(),
				worker(
					context,
					retentionStore(autoPurgeConfig(rawDataRetentionDays = 1)),
					db,
					collectedDataLifecycleStore = lifecycle,
					stepsProjectionLaneProvider = Provider { lane },
				).doWork(),
			)

			assertEquals(0L, db.stepFactRevisionDao().countAll())
			assertEquals(1L, db.sourceEventWalDao().countAll())
			assertEquals(
				0L,
				db.sourceProjectionStateDao()
					.activeProductLane(SourceKind.STEPS.stableCode)?.contiguousAdmissionOrdinal,
			)
			val retainedFailure = requireNotNull(
				db.sourceProjectionStateDao().failure(
					StepsSessionFactProjectionLane.WRITER_ID,
					StepsSessionFactProjectionLane.WRITER_VERSION,
					1L,
				),
			)
			assertEquals("RAW_PAYLOAD_INTEGRITY", retainedFailure.failureCode)
			assertEquals(1, retainedFailure.attemptCount)
			val factMarker = requireNotNull(db.sourceDeletionFenceDao().get(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
				purpose = StepFactRevisionIntegrity.RETENTION_TRUNCATION_PURPOSE,
				scopeKind = SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
				scopeIdentityDigest = StepFactRevisionIntegrity.retentionTruncationIdentity(
					"expired-session",
					"expired-run",
				),
			))
			assertTrue(StepFactRevisionIntegrity.isRetentionTruncationFence(
				factMarker,
				"expired-session",
				"expired-run",
				2L,
			))
			assertEquals(
				null,
				db.sourceDeletionFenceDao().get(
					sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
					purpose = StepFactRevisionIntegrity.RETENTION_TRUNCATION_PURPOSE,
					scopeKind = SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
					scopeIdentityDigest = StepFactRevisionIntegrity.retentionTruncationIdentity(
						"session-1",
						"run-1",
					),
				),
			)
			coVerify(exactly = 0) {
				ingress.committedSourceBatch(SourceKind.STEPS, 0L, 1L, 64)
			}
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
		databaseProvider: Provider<AppDatabase> = Provider { db },
		migrationBackupRepository: DatabaseMigrationBackupRepository = mockk(relaxed = true),
		collectedDataLifecycleStore: CollectedDataLifecycleStore = lifecycleStore(),
		trackingStartupGate: TrackingStartupGate = READY_STARTUP_GATE,
		stepsProjectionLaneProvider: Provider<StepsSessionFactProjectionLane> =
			Provider { mockk(relaxed = true) },
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
					databaseProvider,
					migrationBackupRepository,
					trackingStartupGate,
					stepsProjectionLaneProvider,
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
		every { db.stepFactRevisionDao() } returns mockk(relaxed = true)
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

	private fun sourceEvidenceStateDao(): SourceEvidenceStateDao {
		var storedState: SourceEvidenceState? = null
		return object : SourceEvidenceStateDao {
			override suspend fun ensure(state: SourceEvidenceState) {
				if (storedState == null) {
					storedState = state
				}
			}

			override suspend fun get(): SourceEvidenceState? = storedState

			override suspend fun updateLifecycle(
				epoch: Long,
				retainedFromMs: Long?,
				updatedAtMs: Long,
			): Int {
				val current = requireNotNull(storedState)
				storedState = current.copy(
					revision = current.revision + 1L,
					collectedDataEpoch = epoch,
					retainedFromMs = retainedFromMs,
					updatedAtMs = updatedAtMs,
				)
				return 1
			}

			override suspend fun incrementRevision(updatedAtMs: Long): Int {
				val current = requireNotNull(storedState)
				storedState = current.copy(
					revision = current.revision + 1L,
					updatedAtMs = updatedAtMs,
				)
				return 1
			}

			override suspend fun updateAfterFullDeletion(
				epoch: Long,
				retainedFromMs: Long?,
				deletedSourceEventHighWaterOrdinal: Long,
				updatedAtMs: Long,
			): Int {
				val current = requireNotNull(storedState)
				storedState = current.copy(
					revision = current.revision + 1L,
					collectedDataEpoch = epoch,
					retainedFromMs = retainedFromMs,
					deletedSourceEventHighWaterOrdinal = deletedSourceEventHighWaterOrdinal,
					updatedAtMs = updatedAtMs,
				)
				return 1
			}
		}
	}

	private fun staleRawStepsEvent() = SourceEventWalEntity(
		admissionOrdinal = 1L,
		eventId = "stale-steps-event",
		providerDedupKey = "stale-steps-dedup",
		logicalTrackingId = "session-1",
		serviceRunId = "run-1",
		sourceKind = SourceKind.STEPS.stableCode,
		sourceInstanceId = "steps-provider",
		registrationGeneration = 1L,
		physicalConfigurationFingerprint = "steps-config",
		authorizationRevision = 1L,
		authorizationPurposeEligibilityMask = 1L,
		authorizationFingerprint = "steps-capture",
		sourceSequence = 1L,
		configRevision = 1L,
		planAttribution = 0,
		clockDomainId = "boot-1",
		observedElapsedNanos = 2_000_000L,
		receivedElapsedNanos = 2_001_000L,
		wallTimeMs = 1L,
		wallTimeUncertaintyMs = 1L,
		capturedCollectedDataEpoch = 2L,
		sourcePolicyRevision = 3L,
		captureConsentEpoch = 4L,
		sessionManifestRevision = 5L,
		lifecycleLeaseGeneration = 6L,
		acquiredAtMs = 1L,
		qualityFlags = 0L,
		qualityConfidence = null,
		payloadVersion = 3,
		payload = byteArrayOf(1),
		payloadChecksum = "corrupt",
		integrityIdentity = "corrupt",
		createdAtMs = 1L,
	)

	private suspend fun insertExpiredStepsHistory(
		database: AppDatabase,
		collectedDataEpoch: Long,
	) {
		database.sourceSessionDao().insertSession(expiredLogicalSession())
		database.sourceSessionDao().insertServiceRun(expiredServiceRun())
		database.stepFactRevisionDao().insert(expiredStepFactRevision(collectedDataEpoch))
	}

	private fun expiredLogicalSession() = LogicalTrackingSessionEntity(
		logicalTrackingId = "expired-session",
		state = "FINALIZED",
		lifecycleRevision = 2L,
		desiredPlanRevision = 1L,
		rolloutRevision = 1L,
		startOrigin = "MANUAL_FOREGROUND_START",
		clockDomainId = "boot-1",
		startedAtMs = 1L,
		startedElapsedNanos = 1_000_000L,
		cutoffAtMs = 2L,
		cutoffElapsedNanos = 2_000_000L,
		completedAtMs = 2L,
		finalAdmissionOrdinal = 1L,
		failureCode = null,
		sessionMode = "MANUAL",
		currentManifestRevision = 1L,
		currentIntentRevision = 1L,
		currentServiceRunId = null,
		lifecycleLeaseGeneration = 1L,
		lifecycleBootId = "boot-1",
	)

	private fun expiredServiceRun() = SourceServiceRunEntity(
		serviceRunId = "expired-run",
		logicalTrackingId = "expired-session",
		state = "FINALIZED",
		desiredPlanRevision = 1L,
		rolloutRevision = 1L,
		foregroundCapabilityFlags = 0L,
		startedAtMs = 1L,
		startedElapsedNanos = 1_000_000L,
		completedAtMs = 2L,
		completionReason = "USER_STOP",
		bootId = "boot-1",
		leaseGeneration = 1L,
		startOrigin = "MANUAL_FOREGROUND_START",
		desiredForegroundCapabilityFlags = 0L,
		appliedForegroundCapabilityFlags = 0L,
		runtimeAcknowledgement = "STOP_ACCEPTED",
		runRevision = 2L,
		startDeliveryToken = "expired-delivery",
		startCommandGeneration = 1L,
		preparedManifestRevision = 1L,
		preparedIntentRevision = 1L,
		androidDeliveryState = "FOREGROUND_ACCEPTED",
		androidDeliveryUpdatedAtMs = 2L,
		startIsUserInitiated = true,
		startIsAmbient = false,
	)

	private fun expiredStepFactRevision(collectedDataEpoch: Long): StepFactRevisionEntity {
		val sourceEventId = "expired-source-event"
		val logicalFactId = "${StepsSessionFactProjectionLane.WRITER_ID}:$sourceEventId"
		val unsigned = StepFactRevisionEntity(
			logicalFactId = logicalFactId,
			semanticRevision = 1L,
			mutationId = "$logicalFactId:1:${StepFactRevisionEntity.OPERATION_UPSERT}",
			stepIntervalId = null,
			sourceEventId = sourceEventId,
			sourceAdmissionOrdinal = 1L,
			originKind = StepFactRevisionEntity.ORIGIN_LIVE_WAL,
			originIdentity = sourceEventId,
			writerProjectionId = StepsSessionFactProjectionLane.WRITER_ID,
			writerProjectionVersion = StepsSessionFactProjectionLane.WRITER_VERSION,
			writerBindingGeneration = StepsSessionFactProjectionLane.BINDING_GENERATION,
			operation = StepFactRevisionEntity.OPERATION_UPSERT,
			intervalStartTimeMs = 1L,
			intervalEndTimeMs = 2L,
			intervalStartElapsedRealtimeNanos = 1_000_000L,
			intervalEndElapsedRealtimeNanos = 2_000_000L,
			clockDomainId = "boot-1",
			bootClockDomainId = "boot-1",
			cumulativeStepCountStart = 100L,
			cumulativeStepCountEnd = 101L,
			wallTimeUncertaintyMs = 0L,
			coverageKind = StepFactRevisionEntity.COVERAGE_COVERED,
			effectiveStepCount = 1L,
			logicalTrackingId = "expired-session",
			serviceRunId = "expired-run",
			purpose = StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
			manifestRevision = 1L,
			sourcePolicyRevision = 1L,
			captureConsentEpoch = 1L,
			collectedDataEpoch = collectedDataEpoch,
			scopeDeletionGeneration = 0L,
			effectChecksum = "unsigned",
			appliedAtMs = 2L,
		)
		return unsigned.copy(
			effectChecksum = StepFactRevisionIntegrity.liveWalEffectChecksum(unsigned),
		)
	}

	private companion object {
		val DIRECT_EXECUTOR = Executor(Runnable::run)
		val READY_STARTUP_GATE = object : TrackingStartupGate {
			override val isReady: Boolean = true
			override suspend fun reconcile(retryFailedStorage: Boolean) =
				TrackingStartupResult.Ready(legacyRecoveryPartial = false, liveCompletedThroughOrdinal = 0L)
		}
	}
}
