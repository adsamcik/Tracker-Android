package com.adsamcik.tracker.shared.base.database

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.data.CollectedDataDeletionOperationEntity
import com.adsamcik.tracker.shared.base.database.data.PendingSignalEntity
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertFailsWith

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CollectedDataDeletionOperationRoomTest {
	@Test
	fun `committed operation receipt prevents a repeated physical clear`() = runTest {
		val context = ApplicationProvider.getApplicationContext<Application>()
		val database = AppDatabase.testDatabase(context)
		try {
			val first = AppDatabase.deleteAllCollectedData(
				database = database,
				operationId = "delete-operation-1",
				collectedDataEpoch = 1L,
				retainedFromMs = 100L,
				updatedAtMs = 100L,
			)
			first.phase shouldBe
				CollectedDataDeletionOperationEntity.PHASE_DATABASE_CLEARED
			val revisionAfterClear = requireNotNull(database.sourceEvidenceStateDao().get()).revision
			database.pendingSignalDao().insertAll(
				listOf(
					PendingSignalEntity(
						signalId = "post-clear-sentinel",
						sessionId = 1L,
						envelopeVersion = 1,
						payloadChecksum =
							"1280fde14031e7b67bce77ff73860e29dbb51d1f3698679d28f2f93ed1beb128",
						signalJson = """{"type":"tracking_signal","payload":{"ts":1,"ern":0}}""",
						createdAt = 101L,
					),
				),
			)

			AppDatabase.deleteAllCollectedData(
				database = database,
				operationId = "delete-operation-1",
				collectedDataEpoch = 1L,
				retainedFromMs = 100L,
				updatedAtMs = 100L,
			) shouldBe first

			database.pendingSignalDao().countAll() shouldBe 1
			requireNotNull(database.sourceEvidenceStateDao().get()).revision shouldBe
				revisionAfterClear
		} finally {
			database.close()
		}
	}

	@Test
	fun `writer rearm phase advances only the exact deletion operation`() = runTest {
		val context = ApplicationProvider.getApplicationContext<Application>()
		val database = AppDatabase.testDatabase(context)
		try {
			AppDatabase.deleteAllCollectedData(
				database = database,
				operationId = "delete-operation-2",
				collectedDataEpoch = 1L,
				retainedFromMs = 100L,
				updatedAtMs = 100L,
			)

			database.collectedDataDeletionOperationDao().compareAndSetPhase(
				operationId = "delete-operation-2",
				targetCollectedDataEpoch = 1L,
				expectedPhase = CollectedDataDeletionOperationEntity.PHASE_DATABASE_CLEARED,
				newPhase = CollectedDataDeletionOperationEntity.PHASE_WRITERS_REARMED,
				updatedAtMs = 101L,
			) shouldBe 1
			database.collectedDataDeletionOperationDao().compareAndSetPhase(
				operationId = "delete-operation-2",
				targetCollectedDataEpoch = 1L,
				expectedPhase = CollectedDataDeletionOperationEntity.PHASE_DATABASE_CLEARED,
				newPhase = CollectedDataDeletionOperationEntity.PHASE_WRITERS_REARMED,
				updatedAtMs = 102L,
			) shouldBe 0
		} finally {
			database.close()
		}
	}

	@Test
	fun `retry resumes the first durable retention floor identity instead of recomputing it`() =
		runTest {
			val context = ApplicationProvider.getApplicationContext<Application>()
			val database = AppDatabase.testDatabase(context)
			try {
				val first = database.prepareOrResumeRetentionFloorSettlement(
					operationId = "retention-operation-original",
					requestedRetainedFromMs = 1_000L,
					collectedDataEpoch = 0L,
					requestedAtMs = 2_000L,
				)

				database.prepareOrResumeRetentionFloorSettlement(
					operationId = "retention-operation-recomputed",
					requestedRetainedFromMs = first.requestedRetainedFromMs,
					collectedDataEpoch = 0L,
					requestedAtMs = first.requestedAtMs,
					workExecutionId = first.workExecutionId,
					destructivePlan = first.destructivePlan,
				) shouldBe first
			} finally {
				database.close()
			}
		}

	@Test
	fun `retry delay cannot move any journaled destructive cutoff`() = runTest {
		val context = ApplicationProvider.getApplicationContext<Application>()
		val database = AppDatabase.testDatabase(context)
		try {
			val originalPlan = RetentionFloorDestructivePlan(
				workerKind = RetentionFloorDestructivePlan.WORKER_RETENTION_PIPELINE,
				requestedAtMs = 10_000L,
				requestedRetainedFromMs = 7_000L,
				rawRetentionCutoffMs = 7_000L,
				sourceEventRetentionCutoffMs = 7_000L,
				wifiCellRetentionCutoffMs = 6_000L,
				tripRetentionCutoffMs = 5_000L,
				dailySummaryRetentionCutoffDay = 4L,
				explorationRetentionCutoffMs = 3_000L,
				operationalRetentionCutoffMs = 7_000L,
			)
			val execution = (
				database.beginOrResumeRetentionWorkExecution(
					workRequestId = "work-request-1",
					workerKind = RetentionFloorDestructivePlan.WORKER_RETENTION_PIPELINE,
					runAttemptCount = 0,
					startedAtMs = 9_000L,
				) as RetentionWorkExecutionStartResult.Open
			).receipt
			val attached = (
				database.attachRetentionDestructivePlan(execution, originalPlan) as
					RetentionWorkExecutionPlanResult.Attached
			).receipt
			val first = database.prepareOrResumeRetentionFloorSettlement(
				operationId = "retention-plan-original",
				requestedRetainedFromMs = requireNotNull(originalPlan.requestedRetainedFromMs),
				collectedDataEpoch = 0L,
				requestedAtMs = originalPlan.requestedAtMs,
				workExecutionId = attached.executionId,
				destructivePlan = originalPlan,
			)

			val delayedRetry = (
				database.retentionFloorSettlementForExecution(attached) as
					RetentionFloorOperationLookupResult.Available
			).operation

			delayedRetry shouldBe first
			requireNotNull(delayedRetry).destructivePlan shouldBe originalPlan
			delayedRetry.requestedAtMs shouldBe 10_000L
		} finally {
			database.close()
		}
	}

	@Test
	fun `periodic execution generation survives preflight process death and retires final only on next period`() =
		runTest {
			val context = ApplicationProvider.getApplicationContext<Application>()
			val database = AppDatabase.testDatabase(context)
			try {
				val first = (
					database.beginOrResumeRetentionWorkExecution(
						"periodic-work-1",
						RetentionFloorDestructivePlan.WORKER_RETENTION_PIPELINE,
						runAttemptCount = 0,
						startedAtMs = 1_000L,
					) as RetentionWorkExecutionStartResult.Open
				).receipt
				database.beginOrResumeRetentionWorkExecution(
					"periodic-work-1",
					RetentionFloorDestructivePlan.WORKER_RETENTION_PIPELINE,
					runAttemptCount = 0,
					startedAtMs = 1_100L,
				) shouldBe RetentionWorkExecutionStartResult.Open(first)
				database.beginOrResumeRetentionWorkExecution(
					"periodic-work-1",
					RetentionFloorDestructivePlan.WORKER_RETENTION_PIPELINE,
					runAttemptCount = 1,
					startedAtMs = 1_200L,
				) shouldBe RetentionWorkExecutionStartResult.Open(first)
				database.retentionWorkExecutionReceiptDao().get(first.executionId)?.state shouldBe
					"OPEN"
				val firstPlan = RetentionFloorDestructivePlan(
					workerKind = RetentionFloorDestructivePlan.WORKER_RETENTION_PIPELINE,
					requestedAtMs = 1_000L,
					requestedRetainedFromMs = 500L,
					rawRetentionCutoffMs = 500L,
					sourceEventRetentionCutoffMs = 500L,
					wifiCellRetentionCutoffMs = 500L,
					tripRetentionCutoffMs = 500L,
					dailySummaryRetentionCutoffDay = 0L,
					explorationRetentionCutoffMs = 500L,
					operationalRetentionCutoffMs = 500L,
				)
				database.collectedDataDeletionOperationDao().insert(
					CollectedDataDeletionOperationEntity(
						operationId = "periodic-operation-1",
						targetCollectedDataEpoch = 0L,
						retainedFromMs = 500L,
						deletedAtMs = 1_000L,
						phase = CollectedDataDeletionOperationEntity.PHASE_RETENTION_FINAL,
						updatedAtMs = 1_300L,
						retentionWorkExecutionId = first.executionId,
						retentionDestructivePlan = firstPlan.encode(),
						settledRetainedFromMs = 500L,
						sourceMaintenanceAtMs = 1_000L,
					),
				)

				database.completeRetentionWorkExecution(first, 1_300L) shouldBe
					RetentionWorkExecutionCompletionResult.Completed
				database.beginOrResumeRetentionWorkExecution(
					"periodic-work-1",
					RetentionFloorDestructivePlan.WORKER_RETENTION_PIPELINE,
					runAttemptCount = 1,
					startedAtMs = 1_400L,
				) shouldBe RetentionWorkExecutionStartResult.AlreadyCompleted
				database.retentionWorkExecutionReceiptDao().latest("periodic-work-1") shouldBe
					database.retentionWorkExecutionReceiptDao().get(first.executionId)
				database.retentionWorkExecutionReceiptDao().get(first.executionId)?.state shouldBe
					"FINAL"
				database.collectedDataDeletionOperationDao()
					.get("periodic-operation-1")?.phase shouldBe
					CollectedDataDeletionOperationEntity.PHASE_RETENTION_FINAL

				val second = (
					database.beginOrResumeRetentionWorkExecution(
						"periodic-work-1",
						RetentionFloorDestructivePlan.WORKER_RETENTION_PIPELINE,
						runAttemptCount = 0,
						startedAtMs = 2_000L,
					) as RetentionWorkExecutionStartResult.Open
				).receipt
				second.executionGeneration shouldBe 2L
				database.retentionWorkExecutionReceiptDao().get(first.executionId)?.state shouldBe
					"ACKNOWLEDGED"
				database.collectedDataDeletionOperationDao()
					.get("periodic-operation-1")?.phase shouldBe
					CollectedDataDeletionOperationEntity.PHASE_RETENTION_ACKNOWLEDGED
			} finally {
				database.close()
			}
		}

	@Test
	fun `legacy final operation cannot be replayed by a retry and is acknowledged by a new period`() =
			runTest {
				val context = ApplicationProvider.getApplicationContext<Application>()
				val database = AppDatabase.testDatabase(context)
				try {
					val plan = RetentionFloorDestructivePlan.legacy(2_000L, 1_000L)
					database.collectedDataDeletionOperationDao().insert(
						CollectedDataDeletionOperationEntity(
							operationId = "legacy-final",
							targetCollectedDataEpoch = 0L,
							retainedFromMs = 1_000L,
							deletedAtMs = 2_000L,
							phase = CollectedDataDeletionOperationEntity.PHASE_RETENTION_FINAL,
							updatedAtMs = 2_100L,
							retentionWorkExecutionId = "legacy-periodic-id",
							retentionDestructivePlan = plan.encode(),
							settledRetainedFromMs = 1_000L,
							sourceMaintenanceAtMs = 2_000L,
						),
					)

					database.beginOrResumeRetentionWorkExecution(
						"legacy-periodic-id",
						RetentionFloorDestructivePlan.WORKER_DATA_RETENTION,
						runAttemptCount = 1,
						startedAtMs = 2_200L,
					) shouldBe RetentionWorkExecutionStartResult.AlreadyCompleted
					database.collectedDataDeletionOperationDao().get("legacy-final")?.phase shouldBe
						CollectedDataDeletionOperationEntity.PHASE_RETENTION_FINAL

					database.beginOrResumeRetentionWorkExecution(
						"legacy-periodic-id",
						RetentionFloorDestructivePlan.WORKER_DATA_RETENTION,
						runAttemptCount = 0,
						startedAtMs = 3_000L,
					).shouldBeInstanceOf<RetentionWorkExecutionStartResult.Open>()
					database.collectedDataDeletionOperationDao().get("legacy-final")?.phase shouldBe
						CollectedDataDeletionOperationEntity.PHASE_RETENTION_ACKNOWLEDGED
				} finally {
					database.close()
				}
			}

	@Test
	fun `cancellation request blocks owner and migration takeover until exact abandonment confirmation`() =
		runTest {
			val context = ApplicationProvider.getApplicationContext<Application>()
			val database = AppDatabase.testDatabase(context)
			try {
				val legacyOwner = (
					database.beginOrResumeRetentionWorkExecution(
						"legacy-cancelled-work",
						RetentionFloorDestructivePlan.WORKER_DATA_RETENTION,
						runAttemptCount = 0,
						startedAtMs = 5_000L,
					) as RetentionWorkExecutionStartResult.Open
				).receipt
				val currentOwner = (
					database.beginOrResumeRetentionWorkExecution(
						"unaffected-work",
						RetentionFloorDestructivePlan.WORKER_RETENTION_PIPELINE,
						runAttemptCount = 0,
						startedAtMs = 5_100L,
					) as RetentionWorkExecutionStartResult.Open
				).receipt
				val plan = RetentionFloorDestructivePlan(
					workerKind = RetentionFloorDestructivePlan.WORKER_DATA_RETENTION,
					requestedAtMs = 5_200L,
					requestedRetainedFromMs = 4_000L,
					rawRetentionCutoffMs = 4_000L,
					sourceEventRetentionCutoffMs = 4_000L,
					wifiCellRetentionCutoffMs = 4_000L,
					tripRetentionCutoffMs = 4_000L,
					dailySummaryRetentionCutoffDay = null,
					explorationRetentionCutoffMs = null,
					operationalRetentionCutoffMs = null,
				)
				val attached = (
					database.attachRetentionDestructivePlan(legacyOwner, plan) as
						RetentionWorkExecutionPlanResult.Attached
				).receipt
				val operation = database.prepareOrResumeRetentionFloorSettlement(
					operationId = "cancelled-owner-operation",
					requestedRetainedFromMs = requireNotNull(plan.requestedRetainedFromMs),
					collectedDataEpoch = 0L,
					requestedAtMs = plan.requestedAtMs,
					workExecutionId = attached.executionId,
					destructivePlan = plan,
				)

				val requested = database.requestRetentionWorkExecutionCancellations(
					targets = listOf(
						RetentionWorkCancellationTarget(
							legacyOwner.workRequestId,
							legacyOwner.workerKind,
						),
					),
					activeWorkRequestIds = listOf(legacyOwner.workRequestId),
					workerKinds = listOf(legacyOwner.workerKind),
					requestedAtMs = 5_300L,
				).single()
				requested.executionId shouldBe legacyOwner.executionId
				database.retentionWorkExecutionReceiptDao().get(legacyOwner.executionId)?.state shouldBe
					"CANCELLATION_REQUESTED"
				database.retentionWorkExecutionReceiptDao().get(currentOwner.executionId)?.state shouldBe
					"OPEN"
				database.retentionWorkExecutionContinuation(legacyOwner) shouldBe
					RetentionWorkExecutionContinuationResult.CancellationRequested
				database.retentionWorkExecutionContinuation(currentOwner) shouldBe
					RetentionWorkExecutionContinuationResult.Retryable(
						RetentionWorkExecutionFailure.CancellationHandoffOwned(
							ownerExecutionId = legacyOwner.executionId,
							ownerWorkerKind = legacyOwner.workerKind,
						),
					)
				database.beginOrResumeRetentionWorkExecution(
					legacyOwner.workRequestId,
					RetentionFloorDestructivePlan.WORKER_RETENTION_PIPELINE,
					runAttemptCount = 0,
					startedAtMs = 5_400L,
				) shouldBe RetentionWorkExecutionStartResult.CancellationRequested
				database.attachRetentionDestructivePlan(legacyOwner, plan) shouldBe
					RetentionWorkExecutionPlanResult.CancellationRequested
				database.completeRetentionWorkExecution(legacyOwner, 5_400L) shouldBe
					RetentionWorkExecutionCompletionResult.CancellationRequested

				database.beginOrResumeRetentionWorkExecution(
					"pipeline-takeover-work",
					RetentionFloorDestructivePlan.WORKER_RETENTION_PIPELINE,
					runAttemptCount = 0,
					startedAtMs = 5_500L,
				) shouldBe RetentionWorkExecutionStartResult.Retryable(
					RetentionWorkExecutionFailure.CancellationHandoffOwned(
						ownerExecutionId = legacyOwner.executionId,
						ownerWorkerKind = legacyOwner.workerKind,
					),
				)

				database.confirmRetentionWorkExecutionCancellations(
					executionIds = listOf(legacyOwner.executionId),
					confirmedAtMs = 5_600L,
				) shouldBe 1
				database.retentionWorkExecutionReceiptDao().get(legacyOwner.executionId)?.state shouldBe
					"ABANDONED"
				database.beginOrResumeRetentionWorkExecution(
					legacyOwner.workRequestId,
					legacyOwner.workerKind,
					runAttemptCount = 1,
					startedAtMs = 5_700L,
				) shouldBe RetentionWorkExecutionStartResult.AbandonedByCancellation
				database.retentionWorkExecutionContinuation(currentOwner) shouldBe
					RetentionWorkExecutionContinuationResult.Continue
				database.retentionFloorSettlementForExecution(currentOwner) shouldBe
					RetentionFloorOperationLookupResult.Available(
						operation.copy(workExecutionId = currentOwner.executionId),
					)
			} finally {
				database.close()
			}
		}

	@Test
	fun `category only retry keeps the first immutable plan without manufacturing a lifecycle floor`() =
		runTest {
			val context = ApplicationProvider.getApplicationContext<Application>()
			val database = AppDatabase.testDatabase(context)
			try {
				val execution = (
					database.beginOrResumeRetentionWorkExecution(
						"category-work",
						RetentionFloorDestructivePlan.WORKER_RETENTION_PIPELINE,
						runAttemptCount = 0,
						startedAtMs = 10_000L,
					) as RetentionWorkExecutionStartResult.Open
				).receipt
				val original = RetentionFloorDestructivePlan(
					workerKind = RetentionFloorDestructivePlan.WORKER_RETENTION_PIPELINE,
					requestedAtMs = 10_000L,
					requestedRetainedFromMs = null,
					rawRetentionCutoffMs = null,
					sourceEventRetentionCutoffMs = null,
					wifiCellRetentionCutoffMs = null,
					tripRetentionCutoffMs = 5_000L,
					dailySummaryRetentionCutoffDay = 4L,
					explorationRetentionCutoffMs = 3_000L,
					operationalRetentionCutoffMs = null,
				)
				val attached = (
					database.attachRetentionDestructivePlan(execution, original) as
						RetentionWorkExecutionPlanResult.Attached
				).receipt
				val restarted = (
					database.beginOrResumeRetentionWorkExecution(
						"category-work",
						RetentionFloorDestructivePlan.WORKER_RETENTION_PIPELINE,
						runAttemptCount = 1,
						startedAtMs = 20_000L,
					) as RetentionWorkExecutionStartResult.Open
				).receipt

				restarted shouldBe attached
				restarted.destructivePlan shouldBe original
				restarted.destructivePlan?.requestedRetainedFromMs shouldBe null
			} finally {
				database.close()
			}
		}

	@Test
	fun `data executor rejects a pipeline plan while pipeline safely claims legacy migration debt`() =
		runTest {
			val context = ApplicationProvider.getApplicationContext<Application>()
			val database = AppDatabase.testDatabase(context)
			try {
				val pipelinePlan = RetentionFloorDestructivePlan(
					workerKind = RetentionFloorDestructivePlan.WORKER_RETENTION_PIPELINE,
					requestedAtMs = 2_000L,
					requestedRetainedFromMs = 1_000L,
					rawRetentionCutoffMs = 1_000L,
					sourceEventRetentionCutoffMs = 1_000L,
					wifiCellRetentionCutoffMs = 1_000L,
					tripRetentionCutoffMs = 1_000L,
					dailySummaryRetentionCutoffDay = 1L,
					explorationRetentionCutoffMs = null,
					operationalRetentionCutoffMs = 1_000L,
				)
				database.prepareOrResumeRetentionFloorSettlement(
					operationId = "pipeline-operation",
					requestedRetainedFromMs = 1_000L,
					collectedDataEpoch = 0L,
					requestedAtMs = 2_000L,
					workExecutionId = "old-pipeline-execution",
					destructivePlan = pipelinePlan,
				)
				val dataExecution = (
					database.beginOrResumeRetentionWorkExecution(
						"legacy-data-work",
						RetentionFloorDestructivePlan.WORKER_DATA_RETENTION,
						0,
						2_100L,
					) as RetentionWorkExecutionStartResult.Open
				).receipt
				database.retentionFloorSettlementForExecution(dataExecution) shouldBe
					RetentionFloorOperationLookupResult.IncompatibleExecutor(
						RetentionFloorExecutorDebt(
							"pipeline-operation",
							RetentionFloorDestructivePlan.WORKER_RETENTION_PIPELINE,
							RetentionFloorDestructivePlan.WORKER_DATA_RETENTION,
						),
					)

				AppDatabase.deleteAllCollectedData(
					database = database,
					operationId = "migration-full-clear",
					collectedDataEpoch = 1L,
					retainedFromMs = 1_000L,
					updatedAtMs = 3_000L,
				)
				val dataPlan = pipelinePlan.copy(
					workerKind = RetentionFloorDestructivePlan.WORKER_DATA_RETENTION,
					requestedAtMs = 4_000L,
					requestedRetainedFromMs = 2_000L,
					rawRetentionCutoffMs = 2_000L,
					sourceEventRetentionCutoffMs = 2_000L,
					wifiCellRetentionCutoffMs = 2_000L,
					tripRetentionCutoffMs = 2_000L,
					dailySummaryRetentionCutoffDay = null,
					operationalRetentionCutoffMs = null,
				)
				val legacyOwner = (
					database.beginOrResumeRetentionWorkExecution(
						"old-data-work",
						RetentionFloorDestructivePlan.WORKER_DATA_RETENTION,
						0,
						3_500L,
					) as RetentionWorkExecutionStartResult.Open
				).receipt
				val legacyOperation = database.prepareOrResumeRetentionFloorSettlement(
					operationId = "legacy-data-operation",
					requestedRetainedFromMs = 2_000L,
					collectedDataEpoch = 1L,
					requestedAtMs = 4_000L,
					workExecutionId = legacyOwner.executionId,
					destructivePlan = dataPlan,
				)
				val pipelineExecution = (
					database.beginOrResumeRetentionWorkExecution(
						"pipeline-migration-work",
						RetentionFloorDestructivePlan.WORKER_RETENTION_PIPELINE,
						0,
						4_100L,
					) as RetentionWorkExecutionStartResult.Open
				).receipt
				database.retentionFloorSettlementForExecution(
					pipelineExecution,
					expectedOperationId = "another-operation",
				) shouldBe RetentionFloorOperationLookupResult.Available(null)
				database.activeRetentionFloorSettlement()?.workExecutionId shouldBe
					legacyOwner.executionId
				database.retentionFloorSettlementForExecution(pipelineExecution) shouldBe
					RetentionFloorOperationLookupResult.ExecutionOwned(
						RetentionFloorExecutionOwnerDebt(
							operationId = legacyOperation.operationId,
							ownerExecutionId = legacyOwner.executionId,
							requestedExecutionId = pipelineExecution.executionId,
						),
					)
				database.completeRetentionWorkExecution(legacyOwner, 4_200L) shouldBe
					RetentionWorkExecutionCompletionResult.Completed
				database.retentionFloorSettlementForExecution(pipelineExecution) shouldBe
					RetentionFloorOperationLookupResult.Available(
						legacyOperation.copy(workExecutionId = pipelineExecution.executionId),
					)
				assertFailsWith<IllegalStateException> {
					database.prepareOrResumeRetentionFloorSettlement(
						operationId = legacyOperation.operationId,
						requestedRetainedFromMs = legacyOperation.requestedRetainedFromMs,
						collectedDataEpoch = legacyOperation.collectedDataEpoch,
						requestedAtMs = legacyOperation.requestedAtMs,
						workExecutionId = legacyOwner.executionId,
						destructivePlan = legacyOperation.destructivePlan,
					)
				}
			} finally {
				database.close()
			}
		}

	@Test
	fun `full deletion supersedes an open periodic execution before its retry can delete`() = runTest {
			val context = ApplicationProvider.getApplicationContext<Application>()
			val database = AppDatabase.testDatabase(context)
			try {
				database.beginOrResumeRetentionWorkExecution(
					"superseded-period",
					RetentionFloorDestructivePlan.WORKER_RETENTION_PIPELINE,
					0,
					1_000L,
				).shouldBeInstanceOf<RetentionWorkExecutionStartResult.Open>()

				AppDatabase.deleteAllCollectedData(
					database = database,
					operationId = "full-delete",
					collectedDataEpoch = 1L,
					retainedFromMs = null,
					updatedAtMs = 2_000L,
				)

				database.beginOrResumeRetentionWorkExecution(
					"superseded-period",
					RetentionFloorDestructivePlan.WORKER_RETENTION_PIPELINE,
					1,
					3_000L,
				) shouldBe RetentionWorkExecutionStartResult.SupersededByFullDeletion
			} finally {
				database.close()
			}
		}

	@Test
	fun `exact Room guard replay does not increment source evidence twice`() = runTest {
		val context = ApplicationProvider.getApplicationContext<Application>()
		val database = AppDatabase.testDatabase(context)
		try {
			val prepared = database.prepareOrResumeRetentionFloorSettlement(
				operationId = "retention-operation-room",
				requestedRetainedFromMs = 1_000L,
				collectedDataEpoch = 0L,
				requestedAtMs = 2_000L,
			)
			val acknowledged = database.advanceRetentionFloorSettlementPhase(
				operation = prepared,
				expectedPhase =
					CollectedDataDeletionOperationEntity.PHASE_RETENTION_PREPARED,
				newPhase = CollectedDataDeletionOperationEntity
					.PHASE_RETENTION_DATASTORE_ACKNOWLEDGED,
				updatedAtMs = 2_001L,
			)

			val committed = database.commitRetentionFloorRoomGuard(
				operation = acknowledged,
				settledRetainedFromMs = 1_000L,
				updatedAtMs = 2_002L,
			)
			val revision = requireNotNull(database.sourceEvidenceStateDao().get()).revision

			database.commitRetentionFloorRoomGuard(
				operation = committed,
				settledRetainedFromMs = 1_000L,
				updatedAtMs = 2_003L,
			) shouldBe committed
			requireNotNull(database.sourceEvidenceStateDao().get()).revision shouldBe revision
		} finally {
			database.close()
		}
	}

	@Test
	fun `completed full deletion supersedes an unfinished retention settlement`() = runTest {
		val context = ApplicationProvider.getApplicationContext<Application>()
		val database = AppDatabase.testDatabase(context)
		try {
			database.prepareOrResumeRetentionFloorSettlement(
				operationId = "retention-operation-old-epoch",
				requestedRetainedFromMs = 1_000L,
				collectedDataEpoch = 0L,
				requestedAtMs = 2_000L,
			)

			AppDatabase.deleteAllCollectedData(
				database = database,
				operationId = "full-delete-after-retention",
				collectedDataEpoch = 1L,
				retainedFromMs = 1_500L,
				updatedAtMs = 3_000L,
			)

			database.activeRetentionFloorSettlement() shouldBe null
			database.collectedDataDeletionOperationDao().completedFullDeletionAfter(0L)
				?.operationId shouldBe "full-delete-after-retention"
			requireNotNull(database.sourceEvidenceStateDao().get()).run {
				collectedDataEpoch shouldBe 1L
				retainedFromMs shouldBe 1_500L
			}
		} finally {
			database.close()
		}
	}
}
