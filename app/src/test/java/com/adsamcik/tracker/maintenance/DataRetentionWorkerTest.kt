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
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.QuarantinedSignalEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionIntegrity
import com.adsamcik.tracker.shared.base.database.migration.DatabaseMigrationBackupRepository
import com.adsamcik.tracker.impexp.exporter.automation.ExportPlanStore
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.preferences.retention.RetentionConfigStore
import com.adsamcik.tracker.shared.preferences.retention.RetentionConfigState
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleSnapshot
import com.adsamcik.tracker.tracker.source.projection.StepsSessionFactDrainResult
import com.adsamcik.tracker.tracker.source.projection.StepsSessionFactProjectionLane
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
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
					)
                }
            })
            .build() as DataRetentionWorker

        // Call doWork() directly to avoid blocking thread with startWork().get()
        val result = worker.doWork()
        assertEquals(ListenableWorker.Result.success(), result)
		coVerify(exactly = 0) { stepsProjectionLane.drainAvailable() }
    }

	@Test
	fun `enabled retention reconciles the Steps lane before source WAL pruning`() = runTest {
		val database = AppDatabase.testDatabase(context)
		val enabledStore: RetentionConfigStore = mockk {
			every { config } returns flowOf(
				RetentionConfigState(autoCleanupEnabled = true, dataRetentionYears = 1),
			)
		}
		val lifecycleStore: CollectedDataLifecycleStore = mockk()
		coEvery { lifecycleStore.advanceRetainedFrom(any()) } returns
			CollectedDataLifecycleSnapshot(epoch = 1L, retainedFromMs = 3L)
		val lane: StepsSessionFactProjectionLane = mockk()
		coEvery { lane.drainAvailable() } returns StepsSessionFactDrainResult.Inactive
		try {
			insertExpiredStepsHistory(database)
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
					)
				})
				.build() as DataRetentionWorker

			assertEquals(ListenableWorker.Result.success(), worker.doWork())
			assertEquals(0L, database.stepFactRevisionDao().countAll())
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
			coVerify(exactly = 1) { lane.drainAvailable() }
		} finally {
			database.close()
		}
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

	private suspend fun insertExpiredStepsHistory(database: AppDatabase) {
		database.sourceSessionDao().insertSession(expiredLogicalSession())
		database.sourceSessionDao().insertServiceRun(expiredServiceRun())
		database.stepFactRevisionDao().insert(expiredStepFactRevision())
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

}
