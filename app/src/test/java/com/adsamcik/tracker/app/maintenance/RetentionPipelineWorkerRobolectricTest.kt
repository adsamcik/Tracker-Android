package com.adsamcik.tracker.app.maintenance

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.RoomTruncateImportedActivityRetention
import com.adsamcik.tracker.shared.base.database.TruncateImportedActivityRetentionResult

import com.adsamcik.tracker.shared.base.database.CellCapturedRetentionBlockedReason
import com.adsamcik.tracker.shared.base.database.CellCapturedRetentionResult
import com.adsamcik.tracker.shared.base.database.RetentionFloorDestructivePlan
import com.adsamcik.tracker.shared.base.database.RetentionFloorOperationLookupResult
import com.adsamcik.tracker.shared.base.database.RetentionWorkExecutionCompletionResult
import com.adsamcik.tracker.shared.base.database.RetentionWorkExecutionPlanResult
import com.adsamcik.tracker.shared.base.database.RetentionWorkExecutionReceipt
import com.adsamcik.tracker.shared.base.database.RetentionWorkExecutionStartResult
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
import com.adsamcik.tracker.shared.base.database.dao.PressureFactRevisionDao
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
import com.adsamcik.tracker.shared.preferences.retention.ApprovedRetentionPolicy
import com.adsamcik.tracker.shared.preferences.retention.ApprovedRetentionOperation
import com.adsamcik.tracker.shared.preferences.retention.ExactApprovedRetentionConfigInvalidReason
import com.adsamcik.tracker.shared.preferences.retention.ExactApprovedRetentionConfigRead
import com.adsamcik.tracker.shared.preferences.retention.ExactApprovedRetentionConfigUnavailableReason
import com.adsamcik.tracker.shared.preferences.retention.ExactApprovedRetentionOperationResult
import com.adsamcik.tracker.shared.preferences.retention.RetentionConfigState
import com.adsamcik.tracker.shared.preferences.retention.RetentionConfigStore
import com.adsamcik.tracker.tracker.api.AmbientTrackingSource
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
import io.mockk.firstArg
import io.mockk.mockk
import io.mockk.secondArg
import io.mockk.slot
import io.mockk.thirdArg
import io.mockk.verify
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CancellationException
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
import com.adsamcik.tracker.shared.base.database.data.CoordinateProvenance
import com.adsamcik.tracker.shared.base.database.data.WifiObservation
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedRetentionBlockedReason
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedRetentionResult
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedRetentionService
import io.kotest.matchers.shouldBe

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@Suppress("LargeClass")
class RetentionPipelineWorkerRobolectricTest {
	@Test
	fun `stale scheduled pipeline work retries every inexact retention authority`() = runTest {
		val context = ApplicationProvider.getApplicationContext<Context>()
		val enabled = autoPurgeConfig(rawDataRetentionDays = 1)
		val inexact = listOf<ExactApprovedRetentionConfigRead>(
			ExactApprovedRetentionConfigRead.Pending(enabled, pipelineApprovedPolicy()),
			ExactApprovedRetentionConfigRead.Invalid(
				ExactApprovedRetentionConfigInvalidReason.APPROVAL_INTEGRITY_MISMATCH,
			),
			ExactApprovedRetentionConfigRead.Unavailable(
				ExactApprovedRetentionConfigUnavailableReason.NOT_APPROVED,
			),
		)

		inexact.forEach { authority ->
			var databaseResolutions = 0
			val store = retentionStore(authority)

			assertEquals(
				ListenableWorker.Result.retry(),
				worker(
					context = context,
					store = store,
					db = mockk(relaxed = true),
					databaseProvider = Provider {
						databaseResolutions += 1
						mockk(relaxed = true)
					},
				).doWork(),
			)
			assertEquals(1, databaseResolutions)
		}
	}

	@Test
	fun `retryable startup persists execution generation before preflight`() = runTest {
		val context = ApplicationProvider.getApplicationContext<Context>()
		var resolutions = 0
		val events = mutableListOf<String>()
		val retryableGate = object : TrackingStartupGate {
			override val isReady: Boolean = false
			override val currentGeneration: Long = 7L
			override suspend fun reconcile(retryFailedStorage: Boolean):
				TrackingStartupResult {
				events += "startup-preflight"
				return TrackingStartupResult.RetryableFailure(
					TrackingStartupStage.STORAGE,
					"DB_BUSY",
				)
			}
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
			workExecutionCoordinator = workExecutionCoordinator {
				events += "execution-receipt"
			},
		).doWork()

		assertEquals(ListenableWorker.Result.retry(), result)
		assertEquals(1, resolutions)
		events shouldBe listOf("execution-receipt", "startup-preflight")
	}

	@Test
	fun `auto cleanup purges all supported retention tables`() = runTest {
		val context = ApplicationProvider.getApplicationContext<Context>()
		val store = retentionStore(
			RetentionConfigState(
				autoCleanupEnabled = true,
				dataRetentionYears = 1,
			),
		)
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
		val pressureFactRevisionDao: PressureFactRevisionDao = mockk(relaxed = true)
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
		coEvery { pressureFactRevisionDao.retentionServiceRunIdPage(null, any()) } returns emptyList()

		every { db.locationSampleDao() } returns locationDao
		every { db.locationObservationDao() } returns locationObservationDao
		every { db.stepFactRevisionDao() } returns stepFactRevisionDao
		every { db.stepIntervalDao() } returns stepDao
		every { db.activitySnapshotDao() } returns activityDao
		every { db.trackerRunDao() } returns runDao
		every { db.pressureFactRevisionDao() } returns pressureFactRevisionDao
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
		coVerify(exactly = 1) {
			collectedDataLifecycleStore.advanceRetainedFrom(any(), any(), any())
		}
		coVerify(exactly = 1) { stepsProjectionLane.drainAvailable() }
		coVerifyOrder {
			collectedDataLifecycleStore.advanceRetainedFrom(any(), any(), any())
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
		coVerify(exactly = 1) { pressureFactRevisionDao.retentionServiceRunIdPage(null, any()) }
		coVerifyOrder {
			pressureFactRevisionDao.retentionServiceRunIdPage(null, any())
			pressureDao.deleteOlderThan(any())
		}
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
	fun `trip retention removes imported physical member and fences its original Steps scope`() = runTest {
		val context = ApplicationProvider.getApplicationContext<Context>()
		val db = AppDatabase.testDatabase(context)
		try {
			val (entry, segmentId) = com.adsamcik.tracker.maintenance.seedExpiredImportedSteps(db, 1L)
			val config = autoPurgeConfig(rawDataRetentionDays = 0).copy(tripRetentionDays = 1)
			assertEquals(ListenableWorker.Result.success(), worker(context, retentionStore(config), db).doWork())
			com.adsamcik.tracker.maintenance.assertExpiredImportedStepsRemoved(db, entry, segmentId)
			assertEquals(0L, db.stepFactRevisionDao().countAll())
		} finally {
			db.close()
		}
	}

	@Test
	fun `Pressure retention audit failure retries before physical Pressure deletion`() = runTest {
		val context = ApplicationProvider.getApplicationContext<Context>()
		val pressureFactRevisionDao: PressureFactRevisionDao = mockk(relaxed = true)
		val pressureSampleDao: PressureSampleDao = mockk(relaxed = true)
		coEvery {
			pressureFactRevisionDao.retentionServiceRunIdPage(
				afterServiceRunId = null,
				limit = any(),
			)
		} throws IllegalStateException("Pressure retention authority is unverifiable")
		val db = retentionDatabase(
			pressureFactRevisionDao = pressureFactRevisionDao,
			pressureSampleDao = pressureSampleDao,
		)

		val result = worker(
			context = context,
			store = retentionStore(autoPurgeConfig(rawDataRetentionDays = 1)),
			db = db,
		).doWork()

		assertEquals(ListenableWorker.Result.retry(), result)
		coVerify(exactly = 1) {
			pressureFactRevisionDao.retentionServiceRunIdPage(
				afterServiceRunId = null,
				limit = any(),
			)
		}
		coVerify(exactly = 0) { pressureSampleDao.deleteOlderThan(any()) }
		verify(exactly = 0) { db.setTransactionSuccessful() }
		verify(atLeast = 1) { db.beginTransaction() }
		verify(atLeast = 1) { db.endTransaction() }
	}

	@Test
	fun `pending signal WAL defers raw retention after advancing privacy boundary`() = runTest {
		val context = ApplicationProvider.getApplicationContext<Context>()
		val db = AppDatabase.testDatabase(context)
		val collectedDataLifecycleStore = lifecycleStore(
			CollectedDataLifecycleSnapshot(epoch = 9L, retainedFromMs = 1_234L),
		)
		val migrationBackupRepository: DatabaseMigrationBackupRepository = mockk(relaxed = true)
		var cellWalCountAtRetention: Long? = null
		val cellRetentionService: CellCapturedRetentionService = mockk {
			coEvery { prune(db, any(), any()) } coAnswers {
				cellWalCountAtRetention = db.sourceEventWalDao().countAll()
				CellCapturedRetentionResult.NoChange
			}
		}
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
			db.sourceEventWalDao().insertIgnoringDuplicate(staleRawCellEvent())

			assertEquals(
				ListenableWorker.Result.retry(),
				worker(
					context,
					retentionStore(
						autoPurgeConfig(rawDataRetentionDays = 1).copy(wifiCellRetentionDays = 1),
					),
					db,
					migrationBackupRepository = migrationBackupRepository,
					collectedDataLifecycleStore = collectedDataLifecycleStore,
					cellCapturedRetentionService = cellRetentionService,
				).doWork(),
			)

			assertEquals(1L, cellWalCountAtRetention)
			assertEquals(0L, db.sourceEventWalDao().countAll())
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
			coVerify(exactly = 1) {
				collectedDataLifecycleStore.advanceRetainedFrom(any(), any(), any())
			}
			coVerify(exactly = 1) { cellRetentionService.prune(db, any(), any()) }
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
		val store = retentionStore(
			RetentionConfigState(
				autoCleanupEnabled = true,
				dataRetentionYears = 0,
			),
		)
		val db: AppDatabase = mockk(relaxed = true)

		assertEquals(ListenableWorker.Result.success(), worker(context, store, db).doWork())
		verify(exactly = 0) { db.locationSampleDao() }
		verify(exactly = 0) { db.wifiObservationDao() }
		verify(exactly = 0) { db.cellSampleDao() }
		verify(exactly = 0) { db.domainEventDao() }
		verify(exactly = 0) { db.exportLogDao() }
	}

	@Test
	fun `loosened Wi-Fi Cell setting still applies the settled monotonic floor to captured Cell`() =
		runTest {
		val context = ApplicationProvider.getApplicationContext<Context>()
		val service = cellRetentionService()
		val db = retentionDatabase()

		assertEquals(
			ListenableWorker.Result.success(),
			worker(
				context = context,
				store = retentionStore(autoPurgeConfig(rawDataRetentionDays = 0)),
				db = db,
				cellCapturedRetentionService = service,
			).doWork(),
		)

		coVerify(exactly = 1) { service.prune(db, 1L, any()) }
	}

	@Test
	fun `blocked captured Cell retention is one-shot and later retention stages remain ordered`() = runTest {
		val context = ApplicationProvider.getApplicationContext<Context>()
		val service = cellRetentionService(
			CellCapturedRetentionResult.Blocked(
				CellCapturedRetentionBlockedReason.SOURCE_EVIDENCE_AUTHORITY_CHANGED,
			),
		)
		val cellDao: CellSampleDao = mockk(relaxed = true)
		val wifiDao: WifiObservationDao = mockk(relaxed = true)
		val sessionDao: SessionSegmentDao = mockk(relaxed = true)
		val db = retentionDatabase(
			cellSampleDao = cellDao,
			wifiObservationDao = wifiDao,
			sessionSegmentDao = sessionDao,
		)
		val cutoff = slot<Long>()
		val markedAt = slot<Long>()
		val config = RetentionConfigState(
			autoPurgeEnabled = true,
			rawDataRetentionDays = 0,
			wifiCellRetentionDays = 3,
			tripRetentionDays = 1,
			dailySummaryRetentionDays = 0,
			explorationRetentionDays = 0,
		)

		assertEquals(
			ListenableWorker.Result.retry(),
			worker(
				context,
				retentionStore(config),
				db,
				cellCapturedRetentionService = service,
			).doWork(),
		)

		coVerify(exactly = 1) {
			service.prune(db, capture(cutoff), capture(markedAt))
		}
		assertEquals(
			RetentionPipelineWorker.computeWifiCellCutoffMillis(3, markedAt.captured),
			cutoff.captured,
		)
		coVerifyOrder {
			service.prune(db, any(), any())
			cellDao.deleteOlderThan(cutoff.captured)
			wifiDao.deleteOlderThan(cutoff.captured)
			sessionDao.deleteOlderThan(any())
		}
		verify(exactly = 0) { db.sourceBrokerDao() }
		verify(exactly = 0) { db.sourceRuntimeStateDao() }
	}

	@Test
	fun `ambient maintenance debt invokes captured sources and requests durable worker retry`() =
		runTest {
			val context = ApplicationProvider.getApplicationContext<Context>()
			val ambient = periodicAmbientRetentionMaintenance(
				PeriodicAmbientRetentionResult.Retryable(
					listOf(
						PeriodicAmbientRetentionFailure(
							PeriodicAmbientRetentionSource.IMPORTED_STEPS,
							PeriodicAmbientRetentionFailureReason.INCOMPLETE,
						),
					),
				),
			)
			val cell = cellRetentionService()
			val wifi = wifiRetentionService()
			val db = retentionDatabase()
			val config = autoPurgeConfig(rawDataRetentionDays = 0).copy(
				wifiCellRetentionDays = 3,
			)

			worker(
				context = context,
				store = retentionStore(config),
				db = db,
				cellCapturedRetentionService = cell,
				wifiCapturedRetentionService = wifi,
				periodicAmbientRetentionMaintenance = ambient,
			).doWork() shouldBe ListenableWorker.Result.retry()

			coVerify(exactly = 1) { ambient.run(db, any(), any()) }
			coVerify(exactly = 1) { cell.prune(db, any(), any()) }
			coVerify(exactly = 1) { wifi.prune(db, any(), any()) }
		}

	@Test
	fun `stricter radio setting becomes the exact captured floor when raw retention differs`() =
		runTest {
			val context = ApplicationProvider.getApplicationContext<Context>()
			val cell = cellRetentionService()
			val wifi = wifiRetentionService()
			val db = retentionDatabase()
			val cellFloor = slot<Long>()
			val wifiFloor = slot<Long>()
			val appliedAt = slot<Long>()
			val config = autoPurgeConfig(rawDataRetentionDays = 30).copy(
				wifiCellRetentionDays = 3,
			)

			worker(
				context = context,
				store = retentionStore(config),
				db = db,
				cellCapturedRetentionService = cell,
				wifiCapturedRetentionService = wifi,
			).doWork() shouldBe ListenableWorker.Result.success()

			coVerify(exactly = 1) {
				cell.prune(db, capture(cellFloor), capture(appliedAt))
			}
			coVerify(exactly = 1) {
				wifi.prune(db, capture(wifiFloor), any())
			}
			val expected = RetentionPipelineWorker.computeWifiCellCutoffMillis(
				3,
				appliedAt.captured,
			)
			cellFloor.captured shouldBe expected
			wifiFloor.captured shouldBe expected
		}

	@Test
	fun `captured Cell retention cancellation propagates without legacy deletion`() = runTest {
		val context = ApplicationProvider.getApplicationContext<Context>()
		val service = mockk<CellCapturedRetentionService>()
		coEvery { service.prune(any(), any(), any()) } throws
			CancellationException("cancel-cell-retention-worker")
		val cellDao: CellSampleDao = mockk(relaxed = true)
		val wifiDao: WifiObservationDao = mockk(relaxed = true)
		val db = retentionDatabase(cellSampleDao = cellDao, wifiObservationDao = wifiDao)
		val config = RetentionConfigState(
			autoPurgeEnabled = true,
			rawDataRetentionDays = 0,
			wifiCellRetentionDays = 1,
			tripRetentionDays = 0,
			dailySummaryRetentionDays = 0,
			explorationRetentionDays = 0,
		)

		assertFailsWith<CancellationException> {
			worker(
				context,
				retentionStore(config),
				db,
				cellCapturedRetentionService = service,
			).doWork()
		}

		coVerify(exactly = 1) { service.prune(db, any(), any()) }
		coVerify(exactly = 0) { cellDao.deleteOlderThan(any()) }
		coVerify(exactly = 0) { wifiDao.deleteOlderThan(any()) }
		verify(exactly = 0) { db.sourceBrokerDao() }
		verify(exactly = 0) { db.sourceRuntimeStateDao() }
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

	@Test
	@Suppress("LongMethod")
	fun `pending signal defers legacy radio deletion but Wi-Fi authenticates before WAL pruning`() = runTest {
		val context = ApplicationProvider.getApplicationContext<Context>()
		val db = AppDatabase.testDatabase(context)
		val collectedDataLifecycleStore = lifecycleStore(
			CollectedDataLifecycleSnapshot(epoch = 9L, retainedFromMs = 1_234L),
		)
		val migrationBackupRepository: DatabaseMigrationBackupRepository = mockk(relaxed = true)
		var wifiWalCountAtRetention: Long? = null
		val wifiRetentionService: WifiCapturedRetentionService = mockk {
			coEvery { prune(db, any(), any()) } coAnswers {
				wifiWalCountAtRetention = db.sourceEventWalDao().countAll()
				WifiCapturedRetentionResult.NoChange
			}
		}
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
			db.wifiObservationDao().insert(
				WifiObservation(
					timeMs = 1L,
					bssid = "retained-by-pending-signal",
					ssid = "",
					capabilities = "",
					frequency = 2_412,
					level = -50,
					latE7 = null,
					lonE7 = null,
					provenance = CoordinateProvenance.UNKNOWN,
					createdAt = 1L,
				),
			)
			db.sourceEventWalDao().insertIgnoringDuplicate(staleRawWifiEvent())

			assertEquals(
				ListenableWorker.Result.retry(),
				worker(
					context,
					retentionStore(
						autoPurgeConfig(rawDataRetentionDays = 1).copy(wifiCellRetentionDays = 1),
					),
					db,
					migrationBackupRepository = migrationBackupRepository,
					collectedDataLifecycleStore = collectedDataLifecycleStore,
					wifiCapturedRetentionService = wifiRetentionService,
				).doWork(),
			)

			assertEquals(1L, wifiWalCountAtRetention)
			assertEquals(0L, db.sourceEventWalDao().countAll())
			assertEquals(1L, db.locationSampleDao().countAll())
			assertEquals(1L, db.wifiObservationDao().getScanSummary().totalObservations)
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
			coVerify(exactly = 1) {
				collectedDataLifecycleStore.advanceRetainedFrom(any(), any(), any())
			}
			coVerify(exactly = 1) { wifiRetentionService.prune(db, any(), any()) }
			verify(exactly = 1) { migrationBackupRepository.deleteAll() }
		} finally {
			db.close()
		}
	}

	@Test
	fun `pending signal creates explicit radio-only debt and prevents final acknowledgement`() =
		runTest {
			val context = ApplicationProvider.getApplicationContext<Context>()
			val db = AppDatabase.testDatabase(context)
			val settlement = retentionFloorSettlement()
			try {
				db.pendingSignalDao().insertAll(
					listOf(
						PendingSignalEntity(
							signalId = "radio-only-retention-pending",
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
				db.wifiObservationDao().insert(
					WifiObservation(
						timeMs = 1L,
						bssid = "radio-only-retention",
						ssid = "",
						capabilities = "",
						frequency = 2_412,
						level = -50,
						latE7 = null,
						lonE7 = null,
						provenance = CoordinateProvenance.UNKNOWN,
						createdAt = 1L,
					),
				)

				worker(
					context = context,
					store = retentionStore(
						autoPurgeConfig(rawDataRetentionDays = 0).copy(
							wifiCellRetentionDays = 1,
						),
					),
					db = db,
					retentionFloorSettlement = settlement,
				).doWork() shouldBe ListenableWorker.Result.retry()

				db.wifiObservationDao().getScanSummary().totalObservations shouldBe 1L
				coVerify(exactly = 0) {
					settlement.complete(
						database = any(),
						startupGate = any(),
						expectedStartupGeneration = any(),
						settlement = any(),
						completedAtMs = any(),
						verifyApprovedOperation = any(),
					)
				}
			} finally {
				db.close()
			}
		}

	@Test
	fun `loosened Wi-Fi Cell setting cannot bypass the settled captured Wi-Fi floor`() = runTest {
		val context = ApplicationProvider.getApplicationContext<Context>()
		val service = wifiRetentionService()
		val db = retentionDatabase()

		assertEquals(
			ListenableWorker.Result.success(),
			worker(
				context = context,
				store = retentionStore(autoPurgeConfig(rawDataRetentionDays = 0)),
				db = db,
				wifiCapturedRetentionService = service,
			).doWork(),
		)

		coVerify(exactly = 1) { service.prune(db, 1L, any()) }
		verify(exactly = 0) { db.sourceBrokerDao() }
		verify(exactly = 0) { db.sourceRuntimeStateDao() }
		verify(exactly = 0) { db.trackingRolloutStateDao() }
	}

	@Test
	@Suppress("LongMethod")
	fun `captured Wi-Fi typed outcomes are one-shot and preserve later stage ordering`() = runTest {
		val context = ApplicationProvider.getApplicationContext<Context>()
		val expectedResults = listOf(
			WifiCapturedRetentionResult.NoChange,
			WifiCapturedRetentionResult.Pruned(logicalFactCount = 2, revisionCount = 3),
			WifiCapturedRetentionResult.Blocked(
				WifiCapturedRetentionBlockedReason.SOURCE_EVIDENCE_AUTHORITY_CHANGED,
			),
		)

		expectedResults.forEach { expected ->
			val service = wifiRetentionService(expected)
			val cellDao: CellSampleDao = mockk(relaxed = true)
			val wifiDao: WifiObservationDao = mockk(relaxed = true)
			val sessionDao: SessionSegmentDao = mockk(relaxed = true)
			val db = retentionDatabase(
				cellSampleDao = cellDao,
				wifiObservationDao = wifiDao,
				sessionSegmentDao = sessionDao,
			)
			val cutoff = slot<Long>()
			val markedAt = slot<Long>()
			val config = RetentionConfigState(
				autoPurgeEnabled = true,
				rawDataRetentionDays = 0,
				wifiCellRetentionDays = 3,
				tripRetentionDays = 1,
				dailySummaryRetentionDays = 0,
				explorationRetentionDays = 0,
			)

			assertEquals(
				if (expected is WifiCapturedRetentionResult.Blocked) {
					ListenableWorker.Result.retry()
				} else {
					ListenableWorker.Result.success()
				},
				worker(
					context = context,
					store = retentionStore(config),
					db = db,
					wifiCapturedRetentionService = service,
				).doWork(),
			)

			coVerify(exactly = 1) {
				service.prune(db, capture(cutoff), capture(markedAt))
			}
			assertEquals(
				RetentionPipelineWorker.computeWifiCellCutoffMillis(3, markedAt.captured),
				cutoff.captured,
			)
			coVerifyOrder {
				service.prune(db, any(), any())
				cellDao.deleteOlderThan(cutoff.captured)
				wifiDao.deleteOlderThan(cutoff.captured)
				sessionDao.deleteOlderThan(any())
			}
			verify(exactly = 0) { db.sourceBrokerDao() }
			verify(exactly = 0) { db.sourceRuntimeStateDao() }
			verify(exactly = 0) { db.trackingRolloutStateDao() }
		}
	}

	@Test
	fun `captured Wi-Fi storage failure requests one worker retry without legacy deletion`() = runTest {
		val context = ApplicationProvider.getApplicationContext<Context>()
		val service: WifiCapturedRetentionService = mockk()
		coEvery { service.prune(any(), any(), any()) } throws
			IllegalStateException("wifi-retention-storage")
		val cellDao: CellSampleDao = mockk(relaxed = true)
		val wifiDao: WifiObservationDao = mockk(relaxed = true)
		val db = retentionDatabase(cellSampleDao = cellDao, wifiObservationDao = wifiDao)
		val config = autoPurgeConfig(rawDataRetentionDays = 0).copy(wifiCellRetentionDays = 1)

		assertEquals(
			ListenableWorker.Result.retry(),
			worker(
				context = context,
				store = retentionStore(config),
				db = db,
				wifiCapturedRetentionService = service,
			).doWork(),
		)

		coVerify(exactly = 1) { service.prune(db, any(), any()) }
		coVerify(exactly = 0) { cellDao.deleteOlderThan(any()) }
		coVerify(exactly = 0) { wifiDao.deleteOlderThan(any()) }
		verify(exactly = 0) { db.sourceBrokerDao() }
		verify(exactly = 0) { db.sourceRuntimeStateDao() }
		verify(exactly = 0) { db.trackingRolloutStateDao() }
	}

	@Test
	fun `captured Wi-Fi cancellation propagates without legacy deletion`() = runTest {
		val context = ApplicationProvider.getApplicationContext<Context>()
		val service: WifiCapturedRetentionService = mockk()
		coEvery { service.prune(any(), any(), any()) } throws
			CancellationException("cancel-wifi-retention-worker")
		val cellDao: CellSampleDao = mockk(relaxed = true)
		val wifiDao: WifiObservationDao = mockk(relaxed = true)
		val db = retentionDatabase(cellSampleDao = cellDao, wifiObservationDao = wifiDao)
		val config = autoPurgeConfig(rawDataRetentionDays = 0).copy(wifiCellRetentionDays = 1)

		assertFailsWith<CancellationException> {
			worker(
				context = context,
				store = retentionStore(config),
				db = db,
				wifiCapturedRetentionService = service,
			).doWork()
		}

		coVerify(exactly = 1) { service.prune(db, any(), any()) }
		coVerify(exactly = 0) { cellDao.deleteOlderThan(any()) }
		coVerify(exactly = 0) { wifiDao.deleteOlderThan(any()) }
		verify(exactly = 0) { db.sourceBrokerDao() }
		verify(exactly = 0) { db.sourceRuntimeStateDao() }
		verify(exactly = 0) { db.trackingRolloutStateDao() }
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
		importedActivityRetentionProvider: Provider<RoomTruncateImportedActivityRetention> = Provider {
			mockk {
				coEvery { truncate(any()) } returns TruncateImportedActivityRetentionResult.NoChange
			}
		},

		cellCapturedRetentionService: CellCapturedRetentionService = cellRetentionService(),
		wifiCapturedRetentionService: WifiCapturedRetentionService = wifiRetentionService(),
		retentionFloorSettlement: RetentionFloorSettlement = retentionFloorSettlement(),
		periodicAmbientRetentionMaintenance: PeriodicAmbientRetentionMaintenance =
			periodicAmbientRetentionMaintenance(),
		workExecutionCoordinator: RetentionWorkExecutionCoordinator =
			workExecutionCoordinator(),
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
					importedActivityRetentionProvider,

					cellCapturedRetentionService,
					wifiCapturedRetentionService,
					retentionFloorSettlement,
					periodicAmbientRetentionMaintenance,
					workExecutionCoordinator,
				)
			})
			.build() as RetentionPipelineWorker

	private fun periodicAmbientRetentionMaintenance(
		result: PeriodicAmbientRetentionResult = PeriodicAmbientRetentionResult.Complete,
	): PeriodicAmbientRetentionMaintenance = mockk {
		coEvery { run(any(), any(), any()) } returns result
	}

	private fun retentionFloorSettlement(): RetentionFloorSettlement = mockk {
		coEvery { pendingOperation(any(), any()) } returns
			RetentionFloorOperationLookupResult.Available(null)
		coEvery {
			settle(
				database = any(),
				lifecycleStore = any(),
				startupGate = any(),
				expectedStartupGeneration = any(),
				requestedRetainedFromMs = any(),
				operationId = any(),
				updatedAtMs = any(),
				workExecutionId = any(),
				destructivePlan = any(),
				verifyApprovedOperation = any(),
			)
		} coAnswers {
			val requestedFloor = arg<Long>(4)
			val initial = arg<CollectedDataLifecycleStore>(1).snapshot()
			RetentionFloorSettlementResult.Settled(
				lifecycle = initial.copy(
					retainedFromMs = maxOf(initial.retainedFromMs ?: 0L, requestedFloor),
				),
				reconciledSources = setOf(
					AmbientTrackingSource.STEPS,
					AmbientTrackingSource.WIFI,
					AmbientTrackingSource.CELL,
				),
				operationId = arg(5),
				requestedRetainedFromMs = requestedFloor,
				requestedAtMs = arg(6),
				workExecutionId = arg(7),
				destructivePlan = arg(8),
			)
		}
		coEvery {
			complete(
				database = any(),
				startupGate = any(),
				expectedStartupGeneration = any(),
				settlement = any(),
				completedAtMs = any(),
				verifyApprovedOperation = any(),
			)
		} returns RetentionFloorSettlementCompletionResult.Completed
	}

	private fun workExecutionCoordinator(
		onBegin: () -> Unit = {},
	): RetentionWorkExecutionCoordinator = mockk {
		coEvery { begin(any(), any(), any(), any(), any()) } coAnswers {
			onBegin()
			val startedAtMs = invocation.args[4] as Long
			RetentionWorkExecutionStartResult.Open(
				RetentionWorkExecutionReceipt(
					executionId = "pipeline-execution:g1",
					workRequestId = invocation.args[1] as String,
					executionGeneration = 1L,
					workerKind = RetentionFloorDestructivePlan.WORKER_RETENTION_PIPELINE,
					startedAtMs = startedAtMs,
					state = "OPEN",
					destructivePlan = null,
					updatedAtMs = startedAtMs,
				),
			)
		}
		coEvery { attachPlan(any(), any(), any()) } coAnswers {
			val receipt = secondArg<RetentionWorkExecutionReceipt>()
			val plan = thirdArg<RetentionFloorDestructivePlan>()
			RetentionWorkExecutionPlanResult.Attached(
				receipt.copy(
					destructivePlan = plan,
					updatedAtMs = maxOf(receipt.updatedAtMs, plan.requestedAtMs),
				),
			)
		}
		coEvery { complete(any(), any(), any()) } returns
			RetentionWorkExecutionCompletionResult.Completed
	}

	private fun retentionStore(state: RetentionConfigState): RetentionConfigStore =
		retentionStore(
			ExactApprovedRetentionConfigRead.Approved(state, pipelineApprovedPolicy()),
		)

	private fun retentionStore(
		authority: ExactApprovedRetentionConfigRead,
	): RetentionConfigStore = mockk {
		coEvery {
			withExactApprovedOperation<ListenableWorker.Result>(any())
		} coAnswers {
			when (authority) {
				is ExactApprovedRetentionConfigRead.Approved -> {
					val admission = ApprovedRetentionOperation(
						authority.configuration,
						authority.policy,
					)
					ExactApprovedRetentionOperationResult.Completed(
						admission,
						firstArg<suspend (ApprovedRetentionOperation) -> ListenableWorker.Result>()
							.invoke(admission),
					)
				}
				is ExactApprovedRetentionConfigRead.Pending,
				is ExactApprovedRetentionConfigRead.Invalid,
				is ExactApprovedRetentionConfigRead.Unavailable,
				-> ExactApprovedRetentionOperationResult.Rejected(authority)
			}
		}
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
		pressureFactRevisionDao: PressureFactRevisionDao = mockk(relaxed = true),
		pressureSampleDao: PressureSampleDao = mockk(relaxed = true),

		cellSampleDao: CellSampleDao = mockk(relaxed = true),
		wifiObservationDao: WifiObservationDao = mockk(relaxed = true),
		sessionSegmentDao: SessionSegmentDao = mockk(relaxed = true),
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
		every { db.pressureFactRevisionDao() } returns pressureFactRevisionDao
		every { db.pressureSampleDao() } returns pressureSampleDao
		every { db.skiRunSegmentDao() } returns mockk(relaxed = true)
		every { db.domainEventDao() } returns domainEventDao
		every { db.exportLogDao() } returns exportLogDao
		every { db.pendingSignalDao() } returns pendingSignalDao
		every { db.locationObservationDecisionDao() } returns mockk(relaxed = true)
		every { db.trackerStateEventDao() } returns mockk(relaxed = true)
		every { db.sourceEvidenceStateDao() } returns sourceEvidenceStateDao
		every { db.cellSampleDao() } returns cellSampleDao
		every { db.wifiObservationDao() } returns wifiObservationDao
		every { db.sessionSegmentDao() } returns sessionSegmentDao
		return db
	}

	private fun pipelineApprovedPolicy(): ApprovedRetentionPolicy = ApprovedRetentionPolicy(
		configurationGeneration = 1L,
		revision = 1L,
		opaquePolicyId = "retention-pipeline-worker-test",
		configurationChecksum = "c".repeat(64),
		integrityChecksum = "d".repeat(64),
	)

	private fun cellRetentionService(
		result: CellCapturedRetentionResult = CellCapturedRetentionResult.NoChange,
	): CellCapturedRetentionService = mockk {
		coEvery { prune(any(), any(), any()) } returns result
	}

	private fun wifiRetentionService(
		result: WifiCapturedRetentionResult = WifiCapturedRetentionResult.NoChange,
	): WifiCapturedRetentionService = mockk {
		coEvery { prune(any(), any(), any()) } returns result
	}


	private fun staleRawWifiEvent() = staleRawStepsEvent().copy(
		eventId = "stale-wifi-event",
		providerDedupKey = "stale-wifi-dedup",
		sourceKind = SourceKind.WIFI.stableCode,
		sourceInstanceId = "wifi-provider",
	)


	private fun lifecycleStore(
		snapshot: CollectedDataLifecycleSnapshot =
			CollectedDataLifecycleSnapshot(epoch = 1L, retainedFromMs = 1L),
	): CollectedDataLifecycleStore = mockk {
		coEvery { snapshot() } returns snapshot
		coEvery { advanceRetainedFrom(any()) } answers {
			snapshot.copy(retainedFromMs = maxOf(snapshot.retainedFromMs ?: 0L, firstArg()))
		}
		coEvery { advanceRetainedFrom(any(), any(), any()) } answers {
			snapshot.copy(retainedFromMs = maxOf(snapshot.retainedFromMs ?: 0L, secondArg()))
		}
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

	private fun staleRawCellEvent() = staleRawStepsEvent().copy(
		eventId = "stale-cell-event",
		providerDedupKey = "stale-cell-dedup",
		sourceKind = SourceKind.CELL.stableCode,
		sourceInstanceId = "cell-provider",
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
