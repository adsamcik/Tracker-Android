package com.adsamcik.tracker.maintenance

import android.content.Context
import androidx.work.Configuration
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.SynchronousExecutor
import com.adsamcik.tracker.app.maintenance.CellCapturedRetentionService
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.CellCapturedRetentionBlockedReason
import com.adsamcik.tracker.shared.base.database.CellCapturedRetentionResult
import com.adsamcik.tracker.shared.base.database.RoomTruncateImportedActivityRetention
import com.adsamcik.tracker.shared.base.database.TruncateImportedActivityRetentionResult
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.PendingSignalEntity
import com.adsamcik.tracker.shared.base.database.data.QuarantinedSignalEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionIntegrity
import com.adsamcik.tracker.shared.base.database.migration.DatabaseMigrationBackupRepository
import com.adsamcik.tracker.impexp.exporter.automation.ExportPlanStore
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.shared.preferences.retention.RetentionConfigStore
import com.adsamcik.tracker.shared.preferences.retention.RetentionConfigState
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleSnapshot
import com.adsamcik.tracker.tracker.source.projection.StepsSessionFactDrainResult
import com.adsamcik.tracker.tracker.source.projection.StepsSessionFactProjectionLane
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedRetentionBlockedReason
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedRetentionResult
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedRetentionService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.inject.Provider
import kotlin.test.assertFailsWith

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@Suppress("LargeClass")
class DataRetentionWorkerTest {
    private companion object {
        const val UNIQUE_WORK_NAME = "APP.DATA_RETENTION_PIPELINE_WEEKLY"
		val READY_STARTUP_GATE = object : TrackingStartupGate {
			override val isReady: Boolean = true
			override suspend fun reconcile(retryFailedStorage: Boolean) =
				TrackingStartupResult.Ready(legacyRecoveryPartial = false, liveCompletedThroughOrdinal = 0L)
		}
    }

    private lateinit var context: Context
    private val retentionStore: RetentionConfigStore = mockk {
        every { config } returns flowOf(RetentionConfigState(autoCleanupEnabled = false))
    }
    private val testDispatcher = StandardTestDispatcher()
    private val mockDatabase: AppDatabase = mockk(relaxed = true)
    private val exportPlanStore: ExportPlanStore = mockk(relaxed = true)
    private val migrationBackupRepository: DatabaseMigrationBackupRepository = mockk(relaxed = true)
	private val collectedDataLifecycleStore: CollectedDataLifecycleStore = mockk(relaxed = true)
	private val stepsProjectionLane: StepsSessionFactProjectionLane = mockk(relaxed = true)
	private val importedActivityRetention: RoomTruncateImportedActivityRetention = mockk {
		coEvery { truncate(any()) } returns TruncateImportedActivityRetentionResult.NoChange
	}
	private val cellCapturedRetentionService: CellCapturedRetentionService = mockk {
		coEvery { prune(any(), any(), any()) } returns CellCapturedRetentionResult.NoChange
	}
	private val wifiCapturedRetentionService: WifiCapturedRetentionService = mockk {
		coEvery { prune(any(), any(), any()) } returns WifiCapturedRetentionResult.NoChange
	}

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.DEBUG)
            .setExecutor(SynchronousExecutor())
            .setTaskExecutor(SynchronousExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    }

    @Test
    fun `doWork returns success and does nothing when disabled`() = runTest(testDispatcher) {
        val worker = TestListenableWorkerBuilder<DataRetentionWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters
                ): ListenableWorker {
                    return DataRetentionWorker(
                        appContext,
                        workerParameters,
                        retentionStore,
                        Provider { mockDatabase },
                        exportPlanStore,
                        migrationBackupRepository,
						collectedDataLifecycleStore,
						READY_STARTUP_GATE,
						Provider { stepsProjectionLane },
						Provider { importedActivityRetention },
						cellCapturedRetentionService,
						wifiCapturedRetentionService,
					)
                }
            })
            .build() as DataRetentionWorker

        // Call doWork() directly to avoid blocking thread with startWork().get()
        val result = worker.doWork()
        assertEquals(ListenableWorker.Result.success(), result)
		coVerify(exactly = 0) { stepsProjectionLane.drainAvailable() }
		coVerify(exactly = 0) { importedActivityRetention.truncate(any()) }
		coVerify(exactly = 0) { cellCapturedRetentionService.prune(any(), any(), any()) }
		coVerify(exactly = 0) { wifiCapturedRetentionService.prune(any(), any(), any()) }
    }

	@Test
	fun `enabled retention preserves attributed segment through radio before segment and WAL pruning`() = runTest {
		val database = AppDatabase.testDatabase(context)
		val operations = mutableListOf<String>()
		val walCountsAtRadioRetention = mutableListOf<Long>()
		val attributedSegment = expiredAttributedSegment()
		var attributedSegmentId = 0L
		val enabledStore: RetentionConfigStore = mockk {
			every { config } returns flowOf(
				RetentionConfigState(autoCleanupEnabled = true, dataRetentionYears = 1),
			)
		}
		val lifecycleStore: CollectedDataLifecycleStore = mockk()
		coEvery { lifecycleStore.advanceRetainedFrom(any()) } returns
			CollectedDataLifecycleSnapshot(epoch = 1L, retainedFromMs = 3L)
		val lane: StepsSessionFactProjectionLane = mockk()
		coEvery { lane.drainAvailable() } coAnswers {
			operations += "steps"
			StepsSessionFactDrainResult.Inactive
		}
		val cellRetention: CellCapturedRetentionService = mockk {
			coEvery { prune(database, 3L, any()) } coAnswers {
				operations += "cell"
				assertEquals(
					attributedSegment.copy(id = attributedSegmentId),
					database.sessionSegmentDao().getById(attributedSegmentId),
				)
				walCountsAtRadioRetention += database.sourceEventWalDao().countAll()
				CellCapturedRetentionResult.NoChange
			}
		}
		val wifiRetention: WifiCapturedRetentionService = mockk {
			coEvery { prune(database, 3L, any()) } coAnswers {
				operations += "wifi"
				assertEquals(
					attributedSegment.copy(id = attributedSegmentId),
					database.sessionSegmentDao().getById(attributedSegmentId),
				)
				walCountsAtRadioRetention += database.sourceEventWalDao().countAll()
				WifiCapturedRetentionResult.NoChange
			}
		}
		try {
			attributedSegmentId = database.sessionSegmentDao().insert(attributedSegment)
			insertExpiredStepsHistory(database, attributedSegmentId)
			val (imported, importedSegmentId) = seedExpiredImportedSteps(database, 1L)
			database.sourceEventWalDao().insertIgnoringDuplicate(staleRawCellEvent())
			database.sourceEventWalDao().insertIgnoringDuplicate(staleRawWifiEvent())
			database.quarantinedSignalDao().insert(
				QuarantinedSignalEntity(
					sourcePendingId = 1L,
					signalId = "expired-quarantine",
					sessionId = 1L,
					envelopeVersion = 1,
					payloadChecksum = null,
					signalJson = "{}",
					createdAt = System.currentTimeMillis(),
					acquiredAtMs = 1L,
					deliveryAttemptCount = 1,
					failureReason = "test",
					quarantinedAt = System.currentTimeMillis(),
				),
			)
			val worker = TestListenableWorkerBuilder<DataRetentionWorker>(context)
				.setWorkerFactory(object : WorkerFactory() {
					override fun createWorker(
						appContext: Context,
						workerClassName: String,
						workerParameters: WorkerParameters,
					): ListenableWorker = DataRetentionWorker(
						appContext,
						workerParameters,
						enabledStore,
						Provider { database },
						exportPlanStore,
						migrationBackupRepository,
						lifecycleStore,
						READY_STARTUP_GATE,
						Provider { lane },
						Provider { importedActivityRetention },
						cellRetention,
						wifiRetention,
					)
				})
				.build() as DataRetentionWorker

			assertEquals(ListenableWorker.Result.success(), worker.doWork())
			assertEquals(0L, database.stepFactRevisionDao().countAll())
			assertExpiredImportedStepsRemoved(database, imported, importedSegmentId)
			val retentionMarker = database.sourceDeletionFenceDao().get(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
				purpose = StepFactRevisionIntegrity.RETENTION_TRUNCATION_PURPOSE,
				scopeKind = SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
				scopeIdentityDigest = StepFactRevisionIntegrity.retentionTruncationIdentity(
					"expired-session",
					"expired-run",
				),
			)
			assertTrue(StepFactRevisionIntegrity.isRetentionTruncationFence(
				requireNotNull(retentionMarker),
				"expired-session",
				"expired-run",
				1L,
			))
			assertEquals(0, database.quarantinedSignalDao().countAll())
			assertEquals(listOf("steps", "cell", "wifi"), operations)
			assertEquals(listOf(2L, 2L), walCountsAtRadioRetention)
			assertEquals(null, database.sessionSegmentDao().getById(attributedSegmentId))
			assertEquals(0L, database.sourceEventWalDao().countAll())
			coVerify(exactly = 1) { lane.drainAvailable() }
			coVerify(exactly = 1) { cellRetention.prune(database, 3L, any()) }
			coVerify(exactly = 1) { wifiRetention.prune(database, 3L, any()) }
		} finally {
			database.close()
		}
	}

	@Test
	fun `pending signals still run both captured radio retention services without pruning WAL`() = runTest {
		val database = AppDatabase.testDatabase(context)
		val operations = mutableListOf<String>()
		val attributedSegment = expiredAttributedSegment()
		var attributedSegmentId = 0L
		val cellRetention: CellCapturedRetentionService = mockk {
			coEvery { prune(database, 3L, any()) } coAnswers {
				operations += "cell"
				assertEquals(
					attributedSegment.copy(id = attributedSegmentId),
					database.sessionSegmentDao().getById(attributedSegmentId),
				)
				assertEquals(2L, database.sourceEventWalDao().countAll())
				CellCapturedRetentionResult.NoChange
			}
		}
		val wifiRetention: WifiCapturedRetentionService = mockk {
			coEvery { prune(database, 3L, any()) } coAnswers {
				operations += "wifi"
				assertEquals(
					attributedSegment.copy(id = attributedSegmentId),
					database.sessionSegmentDao().getById(attributedSegmentId),
				)
				assertEquals(2L, database.sourceEventWalDao().countAll())
				WifiCapturedRetentionResult.NoChange
			}
		}
		val lane = inactiveLane()
		try {
			attributedSegmentId = database.sessionSegmentDao().insert(attributedSegment)
			insertExpiredStepsHistory(database, attributedSegmentId)
			seedPendingRetention(database)
			database.sourceEventWalDao().insertIgnoringDuplicate(staleRawWifiEvent())

			assertEquals(
				ListenableWorker.Result.retry(),
				worker(
					store = enabledRetentionStore(),
					database = database,
					lifecycleStore = lifecycleStore(),
					lane = lane,
					cellRetention = cellRetention,
					wifiRetention = wifiRetention,
				).doWork(),
			)

			assertEquals(listOf("cell", "wifi"), operations)
			assertEquals(
				attributedSegment.copy(id = attributedSegmentId),
				database.sessionSegmentDao().getById(attributedSegmentId),
			)
			assertEquals(2L, database.sourceEventWalDao().countAll())
			coVerify(exactly = 0) { lane.drainAvailable() }
			coVerify(exactly = 1) { cellRetention.prune(database, 3L, any()) }
			coVerify(exactly = 1) { wifiRetention.prune(database, 3L, any()) }
		} finally {
			database.close()
		}
	}

	@Test
	fun `each captured radio rejection keeps attributed segment and WAL after both services run once`() = runTest {
		for (rejectedSource in RadioSource.entries) {
			val database = AppDatabase.testDatabase(context)
			val attributedSegment = expiredAttributedSegment()
			var attributedSegmentId = 0L
			val cellResult = if (rejectedSource == RadioSource.CELL) {
				CellCapturedRetentionResult.Blocked(
					CellCapturedRetentionBlockedReason.SOURCE_EVIDENCE_AUTHORITY_CHANGED,
				)
			} else {
				CellCapturedRetentionResult.NoChange
			}
			val wifiResult = if (rejectedSource == RadioSource.WIFI) {
				WifiCapturedRetentionResult.Blocked(
					WifiCapturedRetentionBlockedReason.SOURCE_EVIDENCE_AUTHORITY_CHANGED,
				)
			} else {
				WifiCapturedRetentionResult.NoChange
			}
			val cellRetention: CellCapturedRetentionService = mockk {
				coEvery { prune(database, 3L, any()) } coAnswers {
					assertEquals(
						attributedSegment.copy(id = attributedSegmentId),
						database.sessionSegmentDao().getById(attributedSegmentId),
					)
					cellResult
				}
			}
			val wifiRetention: WifiCapturedRetentionService = mockk {
				coEvery { prune(database, 3L, any()) } coAnswers {
					assertEquals(
						attributedSegment.copy(id = attributedSegmentId),
						database.sessionSegmentDao().getById(attributedSegmentId),
					)
					wifiResult
				}
			}
			try {
				attributedSegmentId = database.sessionSegmentDao().insert(attributedSegment)
				insertExpiredStepsHistory(database, attributedSegmentId)
				database.sourceEventWalDao().insertIgnoringDuplicate(staleRawCellEvent())

				assertEquals(
					ListenableWorker.Result.retry(),
					worker(
						store = enabledRetentionStore(),
						database = database,
						lifecycleStore = lifecycleStore(),
						cellRetention = cellRetention,
						wifiRetention = wifiRetention,
					).doWork(),
				)

				assertEquals(
					attributedSegment.copy(id = attributedSegmentId),
					database.sessionSegmentDao().getById(attributedSegmentId),
				)
				assertEquals(1L, database.sourceEventWalDao().countAll())
				coVerify(exactly = 1) { cellRetention.prune(database, 3L, any()) }
				coVerify(exactly = 1) { wifiRetention.prune(database, 3L, any()) }
			} finally {
				database.close()
			}
		}
	}

	@Test
	fun `each captured radio storage failure keeps attributed segment and WAL for retry`() = runTest {
		for (failedSource in RadioSource.entries) {
			val database = AppDatabase.testDatabase(context)
			val attributedSegment = expiredAttributedSegment()
			var attributedSegmentId = 0L
			val cellRetention: CellCapturedRetentionService = mockk()
			val wifiRetention: WifiCapturedRetentionService = mockk()
			if (failedSource == RadioSource.CELL) {
				coEvery { cellRetention.prune(database, 3L, any()) } coAnswers {
					assertEquals(
						attributedSegment.copy(id = attributedSegmentId),
						database.sessionSegmentDao().getById(attributedSegmentId),
					)
					throw IllegalStateException("cell-retention-storage")
				}
				coEvery { wifiRetention.prune(database, 3L, any()) } returns WifiCapturedRetentionResult.NoChange
			} else {
				coEvery { cellRetention.prune(database, 3L, any()) } coAnswers {
					assertEquals(
						attributedSegment.copy(id = attributedSegmentId),
						database.sessionSegmentDao().getById(attributedSegmentId),
					)
					CellCapturedRetentionResult.NoChange
				}
				coEvery { wifiRetention.prune(database, 3L, any()) } coAnswers {
					assertEquals(
						attributedSegment.copy(id = attributedSegmentId),
						database.sessionSegmentDao().getById(attributedSegmentId),
					)
					throw IllegalStateException("wifi-retention-storage")
				}
			}
			try {
				attributedSegmentId = database.sessionSegmentDao().insert(attributedSegment)
				insertExpiredStepsHistory(database, attributedSegmentId)
				database.sourceEventWalDao().insertIgnoringDuplicate(staleRawCellEvent())

				assertEquals(
					ListenableWorker.Result.retry(),
					worker(
						store = enabledRetentionStore(),
						database = database,
						lifecycleStore = lifecycleStore(),
						cellRetention = cellRetention,
						wifiRetention = wifiRetention,
					).doWork(),
				)

				assertEquals(
					attributedSegment.copy(id = attributedSegmentId),
					database.sessionSegmentDao().getById(attributedSegmentId),
				)
				assertEquals(1L, database.sourceEventWalDao().countAll())
				coVerify(exactly = 1) { cellRetention.prune(database, 3L, any()) }
				coVerify(exactly = if (failedSource == RadioSource.CELL) 0 else 1) {
					wifiRetention.prune(database, 3L, any())
				}
			} finally {
				database.close()
			}
		}
	}

	@Test
	fun `each captured radio cancellation propagates with attributed segment and WAL intact`() = runTest {
		for (cancelledSource in RadioSource.entries) {
			val database = AppDatabase.testDatabase(context)
			val attributedSegment = expiredAttributedSegment()
			var attributedSegmentId = 0L
			val cellRetention: CellCapturedRetentionService = mockk()
			val wifiRetention: WifiCapturedRetentionService = mockk()
			if (cancelledSource == RadioSource.CELL) {
				coEvery { cellRetention.prune(database, 3L, any()) } coAnswers {
					assertEquals(
						attributedSegment.copy(id = attributedSegmentId),
						database.sessionSegmentDao().getById(attributedSegmentId),
					)
					throw CancellationException("cancel-cell-retention")
				}
				coEvery { wifiRetention.prune(database, 3L, any()) } returns WifiCapturedRetentionResult.NoChange
			} else {
				coEvery { cellRetention.prune(database, 3L, any()) } coAnswers {
					assertEquals(
						attributedSegment.copy(id = attributedSegmentId),
						database.sessionSegmentDao().getById(attributedSegmentId),
					)
					CellCapturedRetentionResult.NoChange
				}
				coEvery { wifiRetention.prune(database, 3L, any()) } coAnswers {
					assertEquals(
						attributedSegment.copy(id = attributedSegmentId),
						database.sessionSegmentDao().getById(attributedSegmentId),
					)
					throw CancellationException("cancel-wifi-retention")
				}
			}
			try {
				attributedSegmentId = database.sessionSegmentDao().insert(attributedSegment)
				insertExpiredStepsHistory(database, attributedSegmentId)
				database.sourceEventWalDao().insertIgnoringDuplicate(staleRawCellEvent())

				assertFailsWith<CancellationException> {
					worker(
						store = enabledRetentionStore(),
						database = database,
						lifecycleStore = lifecycleStore(),
						cellRetention = cellRetention,
						wifiRetention = wifiRetention,
					).doWork()
				}

				assertEquals(
					attributedSegment.copy(id = attributedSegmentId),
					database.sessionSegmentDao().getById(attributedSegmentId),
				)
				assertEquals(1L, database.sourceEventWalDao().countAll())
				coVerify(exactly = 1) { cellRetention.prune(database, 3L, any()) }
				coVerify(exactly = if (cancelledSource == RadioSource.CELL) 0 else 1) {
					wifiRetention.prune(database, 3L, any())
				}
			} finally {
				database.close()
			}
		}
	}

	@Test
	fun `startup generation changes during captured radio retention fence later mutations`() = runTest {
		for (changedSource in RadioSource.entries) {
			val database = AppDatabase.testDatabase(context)
			val gate = MutableStartupGate()
			val attributedSegment = expiredAttributedSegment()
			var attributedSegmentId = 0L
			val cellRetention: CellCapturedRetentionService = mockk {
				coEvery { prune(database, 3L, any()) } coAnswers {
					assertEquals(
						attributedSegment.copy(id = attributedSegmentId),
						database.sessionSegmentDao().getById(attributedSegmentId),
					)
					if (changedSource == RadioSource.CELL) gate.generation += 1L
					CellCapturedRetentionResult.NoChange
				}
			}
			val wifiRetention: WifiCapturedRetentionService = mockk {
				coEvery { prune(database, 3L, any()) } coAnswers {
					assertEquals(
						attributedSegment.copy(id = attributedSegmentId),
						database.sessionSegmentDao().getById(attributedSegmentId),
					)
					if (changedSource == RadioSource.WIFI) gate.generation += 1L
					WifiCapturedRetentionResult.NoChange
				}
			}
			try {
				attributedSegmentId = database.sessionSegmentDao().insert(attributedSegment)
				insertExpiredStepsHistory(database, attributedSegmentId)
				database.sourceEventWalDao().insertIgnoringDuplicate(staleRawCellEvent())

				assertEquals(
					ListenableWorker.Result.success(),
					worker(
						store = enabledRetentionStore(),
						database = database,
						lifecycleStore = lifecycleStore(),
						trackingStartupGate = gate,
						cellRetention = cellRetention,
						wifiRetention = wifiRetention,
					).doWork(),
				)

				assertEquals(
					attributedSegment.copy(id = attributedSegmentId),
					database.sessionSegmentDao().getById(attributedSegmentId),
				)
				assertEquals(1L, database.sourceEventWalDao().countAll())
				coVerify(exactly = 1) { cellRetention.prune(database, 3L, any()) }
				coVerify(exactly = if (changedSource == RadioSource.CELL) 0 else 1) {
					wifiRetention.prune(database, 3L, any())
				}
			} finally {
				database.close()
			}
		}
	}

	@Test
	fun `zero year retention creates no captured radio work`() = runTest {
		val cellRetention = cellRetentionService()
		val wifiRetention = wifiRetentionService()
		val store: RetentionConfigStore = mockk {
			every { config } returns flowOf(
				RetentionConfigState(autoCleanupEnabled = true, dataRetentionYears = 0),
			)
		}

		assertEquals(
			ListenableWorker.Result.success(),
			worker(
				store = store,
				database = mockDatabase,
				cellRetention = cellRetention,
				wifiRetention = wifiRetention,
			).doWork(),
		)

		coVerify(exactly = 0) { cellRetention.prune(any(), any(), any()) }
		coVerify(exactly = 0) { wifiRetention.prune(any(), any(), any()) }
	}

    @Test
    fun `ensureScheduled enqueues work and cancel removes active work`() {
        val workManager = WorkManager.getInstance(context)

        DataRetentionWorker.cancel(context)
        var works = workManager.getWorkInfosForUniqueWork(UNIQUE_WORK_NAME).get()
        assertTrue("expected no active work, found ${works.map { it.state }}", works.none { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING })

        DataRetentionWorker.ensureScheduled(context)
        works = workManager.getWorkInfosForUniqueWork(UNIQUE_WORK_NAME).get()
        assertTrue("expected scheduled work, found ${works.map { it.state }}", works.isNotEmpty())

        DataRetentionWorker.cancel(context)
        works = workManager.getWorkInfosForUniqueWork(UNIQUE_WORK_NAME).get()
        assertTrue("expected cancellation, found ${works.map { it.state }}", works.none { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING })
    }

	private fun worker(
		store: RetentionConfigStore,
		database: AppDatabase,
		lifecycleStore: CollectedDataLifecycleStore = collectedDataLifecycleStore,
		trackingStartupGate: TrackingStartupGate = READY_STARTUP_GATE,
		lane: StepsSessionFactProjectionLane = inactiveLane(),
		cellRetention: CellCapturedRetentionService = cellCapturedRetentionService,
		wifiRetention: WifiCapturedRetentionService = wifiCapturedRetentionService,
	): DataRetentionWorker =
		TestListenableWorkerBuilder<DataRetentionWorker>(context)
			.setWorkerFactory(object : WorkerFactory() {
				override fun createWorker(
					appContext: Context,
					workerClassName: String,
					workerParameters: WorkerParameters,
				): ListenableWorker = DataRetentionWorker(
					appContext,
					workerParameters,
					store,
					Provider { database },
					exportPlanStore,
					migrationBackupRepository,
					lifecycleStore,
					trackingStartupGate,
					Provider { lane },
					Provider { importedActivityRetention },
					cellRetention,
					wifiRetention,
				)
			})
			.build() as DataRetentionWorker

	private fun enabledRetentionStore(): RetentionConfigStore = mockk {
		every { config } returns flowOf(
			RetentionConfigState(autoCleanupEnabled = true, dataRetentionYears = 1),
		)
	}

	private fun lifecycleStore(): CollectedDataLifecycleStore = mockk {
		coEvery { advanceRetainedFrom(any()) } returns
			CollectedDataLifecycleSnapshot(epoch = 1L, retainedFromMs = 3L)
	}

	private fun inactiveLane(): StepsSessionFactProjectionLane = mockk {
		coEvery { drainAvailable() } returns StepsSessionFactDrainResult.Inactive
	}

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

	private suspend fun seedPendingRetention(database: AppDatabase) {
		database.pendingSignalDao().insertAll(
			listOf(
				PendingSignalEntity(
					signalId = "pending-radio-retention",
					sessionId = 7L,
					envelopeVersion = 1,
					payloadChecksum =
						"1280fde14031e7b67bce77ff73860e29dbb51d1f3698679d28f2f93ed1beb128",
					signalJson = """{"type":"tracking_signal","payload":{"ts":1,"ern":0}}""",
					createdAt = 1L,
				),
			),
		)
		database.sourceEventWalDao().insertIgnoringDuplicate(staleRawCellEvent())
	}

	private fun staleRawCellEvent() = SourceEventWalEntity(
		admissionOrdinal = 1L,
		eventId = "stale-cell-event",
		providerDedupKey = "stale-cell-dedup",
		logicalTrackingId = "expired-session",
		serviceRunId = "expired-run",
		sourceKind = SourceKind.CELL.stableCode,
		sourceInstanceId = "cell-provider",
		registrationGeneration = 1L,
		physicalConfigurationFingerprint = "cell-config",
		authorizationRevision = 1L,
		authorizationPurposeEligibilityMask = 1L,
		authorizationFingerprint = "cell-capture",
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

	private fun staleRawWifiEvent() = staleRawCellEvent().copy(
		admissionOrdinal = 2L,
		eventId = "stale-wifi-event",
		providerDedupKey = "stale-wifi-dedup",
		sourceKind = SourceKind.WIFI.stableCode,
		sourceInstanceId = "wifi-provider",
		physicalConfigurationFingerprint = "wifi-config",
		authorizationFingerprint = "wifi-capture",
		sourceSequence = 2L,
	)

	private suspend fun insertExpiredStepsHistory(
		database: AppDatabase,
		sessionSegmentId: Long? = null,
	) {
		database.sourceSessionDao().insertSession(expiredLogicalSession())
		database.sourceSessionDao().insertServiceRun(expiredServiceRun(sessionSegmentId))
		database.stepFactRevisionDao().insert(expiredStepFactRevision())
	}

	private fun expiredAttributedSegment() = SessionSegment(
		startTimeMs = 1L,
		endTimeMs = 2L,
		distanceM = 0f,
		steps = null,
		primaryActivity = null,
		activityConfidence = null,
		sampleCount = 0,
		source = SegmentSource.INFERRED_HIGH_CONFIDENCE,
		inferenceVersion = "radio-authority-fixture",
		createdAt = 2L,
		logicalTrackingId = "expired-session",
		serviceRunId = "expired-run",
	)

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

	private fun expiredServiceRun(sessionSegmentId: Long? = null) = SourceServiceRunEntity(
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
		sessionSegmentId = sessionSegmentId,
	)

	private fun expiredStepFactRevision(): StepFactRevisionEntity {
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
			collectedDataEpoch = 1L,
			scopeDeletionGeneration = 0L,
			effectChecksum = "unsigned",
			appliedAtMs = 2L,
		)
		return unsigned.copy(
			effectChecksum = StepFactRevisionIntegrity.liveWalEffectChecksum(unsigned),
		)
	}

	private enum class RadioSource {
		CELL,
		WIFI,
	}

	private class MutableStartupGate : TrackingStartupGate {
		var generation = 1L

		override val isReady: Boolean = true
		override val currentGeneration: Long
			get() = generation

		override suspend fun reconcile(retryFailedStorage: Boolean) =
			TrackingStartupResult.Ready(legacyRecoveryPartial = false, liveCompletedThroughOrdinal = 0L)
	}

}
