package com.adsamcik.tracker.app.maintenance

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.prepareOrResumeRetentionFloorSettlement
import com.adsamcik.tracker.shared.base.database.data.CollectedDataDeletionOperationEntity
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleSnapshot
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityOperationLease
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityProducer
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityResult
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityScope
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityState
import com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent
import com.adsamcik.tracker.tracker.api.AmbientTrackingSource
import com.adsamcik.tracker.tracker.api.TrackingRetentionFloorReconciler
import com.adsamcik.tracker.tracker.api.TrackingRetentionFloorReconciliationDebt
import com.adsamcik.tracker.tracker.api.TrackingRetentionFloorReconciliationFailure
import com.adsamcik.tracker.tracker.api.TrackingRetentionFloorReconciliationFailureReason
import com.adsamcik.tracker.tracker.api.TrackingRetentionFloorReconciliationResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RetentionFloorSettlementTest {
	private lateinit var database: AppDatabase

	@Before
	fun setUp() {
		val context = ApplicationProvider.getApplicationContext<Application>()
		database = AppDatabase.testDatabase(context)
	}

	@After
	fun tearDown() {
		database.close()
	}

	@Test
	fun `exact floor purpose publication is the only provider reconciliation before settlement`() =
		runTest {
			val events = mutableListOf<String>()
			val lifecycle = fixedLifecycleStore(
				CollectedDataLifecycleSnapshot(epoch = 4L, retainedFromMs = FLOOR),
				onAdvance = { events += "lifecycle" },
			)
			val producer = mockk<RetentionAuthorityProducer> {
				coEvery { reconcileCurrentSettings() } coAnswers {
					database.sourceEvidenceStateDao().get()?.retainedFromMs shouldBe FLOOR
					events += "authority"
					retentionResults(active = setOf(TrackingSourceComponent.STEPS))
				}
			}
			val reconciler = TrackingRetentionFloorReconciler { generation, floor, sources ->
				generation shouldBe GENERATION
				floor shouldBe FLOOR
				sources shouldBe setOf(AmbientTrackingSource.STEPS)
				events += "purpose"
				TrackingRetentionFloorReconciliationResult.Complete(floor, sources)
			}
			val result = RetentionFloorSettlement(
				RetentionAuthorityOperationLease(),
				producer,
				reconciler,
			).settle(
				database = database,
				lifecycleStore = lifecycle,
				startupGate = readyGate(),
				expectedStartupGeneration = GENERATION,
				requestedRetainedFromMs = FLOOR,
				updatedAtMs = FLOOR,
				verifyApprovedOperation = { events += "approved" },
			).shouldBeInstanceOf<RetentionFloorSettlementResult.Settled>()

			result.lifecycle.retainedFromMs shouldBe FLOOR
			result.reconciledSources shouldBe setOf(AmbientTrackingSource.STEPS)
			(events.indexOf("lifecycle") < events.indexOf("authority")) shouldBe true
			(events.indexOf("authority") < events.indexOf("purpose")) shouldBe true
			events.filterNot { it == "approved" } shouldBe
				listOf("lifecycle", "authority", "purpose")
		}

	@Test
	fun `disabled retention sources cannot enter provider reconciliation`() = runTest {
		var providerStarts = 0
		val producer = mockk<RetentionAuthorityProducer> {
			coEvery { reconcileCurrentSettings() } returns retentionResults(active = emptySet())
		}
		val result = RetentionFloorSettlement(
			RetentionAuthorityOperationLease(),
			producer,
			TrackingRetentionFloorReconciler { _, floor, sources ->
				providerStarts += sources.size
				TrackingRetentionFloorReconciliationResult.Complete(floor, sources)
			},
		).settle(
			database = database,
			lifecycleStore = fixedLifecycleStore(
				CollectedDataLifecycleSnapshot(epoch = 4L, retainedFromMs = FLOOR),
			),
			startupGate = readyGate(),
			expectedStartupGeneration = GENERATION,
			requestedRetainedFromMs = FLOOR,
			updatedAtMs = FLOOR,
			verifyApprovedOperation = { },
		)

		result.shouldBeInstanceOf<RetentionFloorSettlementResult.Settled>()
			.reconciledSources shouldBe emptySet()
		providerStarts shouldBe 0
	}

	@Test
	fun `provider reissue failure remains typed retry debt after floor commit`() = runTest {
		val producer = mockk<RetentionAuthorityProducer> {
			coEvery { reconcileCurrentSettings() } returns retentionResults(
				active = setOf(TrackingSourceComponent.WIFI),
			)
		}
		val result = RetentionFloorSettlement(
			RetentionAuthorityOperationLease(),
			producer,
			TrackingRetentionFloorReconciler { _, floor, _ ->
				TrackingRetentionFloorReconciliationResult.Retryable(
					TrackingRetentionFloorReconciliationDebt(
						floor,
						listOf(
							TrackingRetentionFloorReconciliationFailure(
								AmbientTrackingSource.WIFI,
								TrackingRetentionFloorReconciliationFailureReason
									.OWNER_RECONCILIATION_FAILED,
							),
						),
					),
				)
			},
		).settle(
			database = database,
			lifecycleStore = fixedLifecycleStore(
				CollectedDataLifecycleSnapshot(epoch = 4L, retainedFromMs = FLOOR),
			),
			startupGate = readyGate(),
			expectedStartupGeneration = GENERATION,
			requestedRetainedFromMs = FLOOR,
			updatedAtMs = FLOOR,
			verifyApprovedOperation = { },
		).shouldBeInstanceOf<RetentionFloorSettlementResult.Retryable>()

		database.sourceEvidenceStateDao().get()?.retainedFromMs shouldBe FLOOR
		result.debt.failures.single()
			.shouldBeInstanceOf<RetentionFloorSettlementFailure.ProviderLifecycle>()
	}

	@Test
	fun `portable import authority failure prevents settlement completion`() = runTest {
		val results = retentionResults(active = emptySet()).map { result ->
			if (
				result.source == TrackingSourceComponent.WIFI &&
				result.scope == RetentionAuthorityScope.PORTABLE_IMPORT
			) {
				RetentionAuthorityResult.Unavailable(
					source = result.source,
					scope = result.scope,
					reason = com.adsamcik.tracker.shared.preferences.retention
						.RetentionAuthorityUnavailableReason.STORAGE_UNAVAILABLE,
				)
			} else {
				result
			}
		}
		val producer = mockk<RetentionAuthorityProducer> {
			coEvery { reconcileCurrentSettings() } returns results
		}

		val result = RetentionFloorSettlement(
			RetentionAuthorityOperationLease(),
			producer,
			TrackingRetentionFloorReconciler { _, _, _ ->
				error("provider reconciliation must wait for every authority scope")
			},
		).settle(
			database = database,
			lifecycleStore = fixedLifecycleStore(
				CollectedDataLifecycleSnapshot(epoch = 4L, retainedFromMs = FLOOR),
			),
			startupGate = readyGate(),
			expectedStartupGeneration = GENERATION,
			requestedRetainedFromMs = FLOOR,
			updatedAtMs = FLOOR,
			verifyApprovedOperation = { },
		).shouldBeInstanceOf<RetentionFloorSettlementResult.Retryable>()

		result.debt.failures.single()
			.shouldBeInstanceOf<RetentionFloorSettlementFailure.AuthorityUnavailable>()
			.result.scope shouldBe RetentionAuthorityScope.PORTABLE_IMPORT
	}

	@Test
	fun `Room guard failure after durable floor is retry debt without authority reissue`() = runTest {
		val producer = mockk<RetentionAuthorityProducer>()
		val lifecycle = fixedLifecycleStore(
			CollectedDataLifecycleSnapshot(epoch = 4L, retainedFromMs = FLOOR),
			onAdvance = database::close,
		)

		val result = RetentionFloorSettlement(
			RetentionAuthorityOperationLease(),
			producer,
			TrackingRetentionFloorReconciler { _, _, _ ->
				error("provider publication must wait for the Room guard")
			},
		).settle(
			database = database,
			lifecycleStore = lifecycle,
			startupGate = readyGate(),
			expectedStartupGeneration = GENERATION,
			requestedRetainedFromMs = FLOOR,
			updatedAtMs = FLOOR,
			verifyApprovedOperation = { },
		).shouldBeInstanceOf<RetentionFloorSettlementResult.Retryable>()

		result.debt.failures.single()
			.shouldBeInstanceOf<RetentionFloorSettlementFailure.CommitBoundary>()
			.phase shouldBe RetentionFloorSettlementPhase.DATASTORE_FLOOR_ACKNOWLEDGED
		coVerify(exactly = 0) { producer.reconcileCurrentSettings() }
	}

	@Test
	fun `late DataStore floor commit remains typed debt until Room guard and authority recovery`() =
		runTest {
			val commitEntered = CompletableDeferred<Unit>()
			val releaseCommit = CompletableDeferred<Unit>()
			var lifecycle = CollectedDataLifecycleSnapshot(epoch = 4L, retainedFromMs = null)
			var advances = 0
			val lifecycleStore = object : CollectedDataLifecycleStore {
				override val snapshots: Flow<CollectedDataLifecycleSnapshot> =
					MutableStateFlow(lifecycle)

				override suspend fun snapshot(): CollectedDataLifecycleSnapshot = lifecycle

				override suspend fun beginFullDeletion(
					deletedAtMs: Long,
				): CollectedDataLifecycleSnapshot = error("Not used")

				override suspend fun advanceRetainedFrom(
					retainedFromMs: Long,
				): CollectedDataLifecycleSnapshot {
					advances += 1
					if (advances == 1) {
						commitEntered.complete(Unit)
						withContext(NonCancellable) {
							releaseCommit.await()
						}
					}
					lifecycle = lifecycle.copy(retainedFromMs = retainedFromMs)
					return lifecycle
				}
			}
			val producer = mockk<RetentionAuthorityProducer> {
				coEvery { reconcileCurrentSettings() } returns
					retentionResults(active = setOf(TrackingSourceComponent.STEPS))
			}
			val lease = RetentionAuthorityOperationLease(
				ownedSuspensionTimeoutMs = 1L,
				completionScope = backgroundScope,
			)
			val settlement = RetentionFloorSettlement(
				lease,
				producer,
				TrackingRetentionFloorReconciler { _, floor, sources ->
					TrackingRetentionFloorReconciliationResult.Complete(floor, sources)
				},
			)
			val operationId = "retention-floor:test-policy:$FLOOR"
			val initialEvidence = database.sourceEvidenceStateDao().get()
			val first = async {
				settlement.settle(
					database = database,
					lifecycleStore = lifecycleStore,
					startupGate = readyGate(),
					expectedStartupGeneration = GENERATION,
					requestedRetainedFromMs = FLOOR,
					operationId = operationId,
					updatedAtMs = FLOOR,
					verifyApprovedOperation = { },
				)
			}

			commitEntered.await()
			advanceTimeBy(2L)
			val debt = first.await().shouldBeInstanceOf<RetentionFloorSettlementResult.Retryable>()
			debt.debt.failures.single()
				.shouldBeInstanceOf<RetentionFloorSettlementFailure.DataStoreCommitUnknown>()
				.operationId shouldBe operationId
			settlement.pendingOperation(database)?.operationId shouldBe operationId
			database.sourceEvidenceStateDao().get() shouldBe initialEvidence
			coVerify(exactly = 0) { producer.reconcileCurrentSettings() }

			releaseCommit.complete(Unit)
			runCurrent()
			lifecycle.retainedFromMs shouldBe FLOOR
			database.sourceEvidenceStateDao().get() shouldBe initialEvidence

			settlement.settle(
				database = database,
				lifecycleStore = lifecycleStore,
				startupGate = readyGate(),
				expectedStartupGeneration = GENERATION,
				requestedRetainedFromMs = FLOOR,
				operationId = operationId,
				updatedAtMs = FLOOR + 1L,
				verifyApprovedOperation = { },
			).shouldBeInstanceOf<RetentionFloorSettlementResult.Settled>()
			database.sourceEvidenceStateDao().get()?.retainedFromMs shouldBe FLOOR
			coVerify(exactly = 1) { producer.reconcileCurrentSettings() }
		}

	@Test
	fun `authority reissue debt retries the same fenced floor`() = runTest {
		var authorityAvailable = false
		val producer = mockk<RetentionAuthorityProducer> {
			coEvery { reconcileCurrentSettings() } coAnswers {
				if (!authorityAvailable) error("authority storage unavailable")
				retentionResults(active = setOf(TrackingSourceComponent.STEPS))
			}
		}
		val settlement = RetentionFloorSettlement(
			RetentionAuthorityOperationLease(),
			producer,
			TrackingRetentionFloorReconciler { _, floor, sources ->
				TrackingRetentionFloorReconciliationResult.Complete(floor, sources)
			},
		)
		val lifecycle = fixedLifecycleStore(
			CollectedDataLifecycleSnapshot(epoch = 4L, retainedFromMs = FLOOR),
		)

		settlement.settle(
			database = database,
			lifecycleStore = lifecycle,
			startupGate = readyGate(),
			expectedStartupGeneration = GENERATION,
			requestedRetainedFromMs = FLOOR,
			updatedAtMs = FLOOR,
			verifyApprovedOperation = { },
		).shouldBeInstanceOf<RetentionFloorSettlementResult.Retryable>()

		authorityAvailable = true
		settlement.settle(
			database = database,
			lifecycleStore = lifecycle,
			startupGate = readyGate(),
			expectedStartupGeneration = GENERATION,
			requestedRetainedFromMs = FLOOR,
			updatedAtMs = FLOOR + 1L,
			verifyApprovedOperation = { },
		).shouldBeInstanceOf<RetentionFloorSettlementResult.Settled>()
			.reconciledSources shouldBe setOf(AmbientTrackingSource.STEPS)
	}

	@Test
	fun `a new process resumes every durable settlement phase with the original identity`() =
		runTest {
			val resumablePhases = listOf(
				CollectedDataDeletionOperationEntity.PHASE_RETENTION_PREPARED,
				CollectedDataDeletionOperationEntity.PHASE_RETENTION_DATASTORE_ACKNOWLEDGED,
				CollectedDataDeletionOperationEntity.PHASE_RETENTION_ROOM_GUARD_COMMITTED,
				CollectedDataDeletionOperationEntity.PHASE_RETENTION_AUTHORITY_REISSUED,
				CollectedDataDeletionOperationEntity.PHASE_RETENTION_PROVIDER_RECONCILED,
				CollectedDataDeletionOperationEntity
					.PHASE_RETENTION_SOURCE_MAINTENANCE_COMPLETED,
			)
			resumablePhases.forEachIndexed { index, phase ->
				database.close()
				val context = ApplicationProvider.getApplicationContext<Application>()
				database = AppDatabase.testDatabase(context)
				val operationId = "retention-restart-$index"
				database.collectedDataDeletionOperationDao().insert(
					CollectedDataDeletionOperationEntity(
						operationId = operationId,
						targetCollectedDataEpoch = 4L,
						retainedFromMs = FLOOR,
						deletedAtMs = FLOOR,
						phase = phase,
						updatedAtMs = FLOOR,
					),
				)
				if (
					phase != CollectedDataDeletionOperationEntity.PHASE_RETENTION_PREPARED &&
					phase != CollectedDataDeletionOperationEntity
						.PHASE_RETENTION_DATASTORE_ACKNOWLEDGED
				) {
					database.sourceEvidenceStateDao().updateLifecycle(4L, FLOOR, FLOOR)
				}
				val settlement = RetentionFloorSettlement(
					RetentionAuthorityOperationLease(),
					mockk {
						coEvery { reconcileCurrentSettings() } returns
							retentionResults(active = setOf(TrackingSourceComponent.STEPS))
					},
					TrackingRetentionFloorReconciler { _, floor, sources ->
						TrackingRetentionFloorReconciliationResult.Complete(floor, sources)
					},
				).settle(
					database = database,
					lifecycleStore = fixedLifecycleStore(
						CollectedDataLifecycleSnapshot(epoch = 4L, retainedFromMs = FLOOR),
					),
					startupGate = readyGate(),
					expectedStartupGeneration = GENERATION,
					requestedRetainedFromMs = FLOOR + index + 1L,
					operationId = "recomputed-$index",
					updatedAtMs = FLOOR + index + 1L,
					verifyApprovedOperation = { },
				).shouldBeInstanceOf<RetentionFloorSettlementResult.Settled>()

				settlement.operationId shouldBe operationId
				settlement.requestedRetainedFromMs shouldBe FLOOR
				settlement.sourceMaintenanceCompleted shouldBe (
					phase == CollectedDataDeletionOperationEntity
						.PHASE_RETENTION_SOURCE_MAINTENANCE_COMPLETED
					)
			}
		}

	@Test
	fun `source maintenance completion and final acknowledgement are durable phases`() = runTest {
		val settlementCoordinator = RetentionFloorSettlement(
			RetentionAuthorityOperationLease(),
			mockk {
				coEvery { reconcileCurrentSettings() } returns
					retentionResults(active = emptySet())
			},
			TrackingRetentionFloorReconciler { _, floor, sources ->
				TrackingRetentionFloorReconciliationResult.Complete(floor, sources)
			},
		)
		val settled = settlementCoordinator.settle(
			database = database,
			lifecycleStore = fixedLifecycleStore(
				CollectedDataLifecycleSnapshot(epoch = 4L, retainedFromMs = FLOOR),
			),
			startupGate = readyGate(),
			expectedStartupGeneration = GENERATION,
			requestedRetainedFromMs = FLOOR,
			operationId = "retention-completion",
			updatedAtMs = FLOOR,
			verifyApprovedOperation = { },
		).shouldBeInstanceOf<RetentionFloorSettlementResult.Settled>()

		settlementCoordinator.complete(
			database = database,
			startupGate = readyGate(),
			expectedStartupGeneration = GENERATION,
			settlement = settled,
			completedAtMs = FLOOR + 1L,
			verifyApprovedOperation = { },
		) shouldBe RetentionFloorSettlementCompletionResult.Completed

		database.collectedDataDeletionOperationDao().get("retention-completion")?.phase shouldBe
			CollectedDataDeletionOperationEntity.PHASE_RETENTION_FINAL
		settlementCoordinator.pendingOperation(database) shouldBe null
		settlementCoordinator.pendingOperation(
			database,
			workExecutionId = "retention-completion",
			resumeCompletedExecution = true,
		)?.phase shouldBe CollectedDataDeletionOperationEntity.PHASE_RETENTION_FINAL
	}

	@Test
	fun `only a committed newer full deletion can supersede settlement completion debt`() =
		runTest {
			val settlementCoordinator = RetentionFloorSettlement(
				RetentionAuthorityOperationLease(),
				mockk(),
				TrackingRetentionFloorReconciler { _, floor, sources ->
					TrackingRetentionFloorReconciliationResult.Complete(floor, sources)
				},
			)
			database.prepareOrResumeRetentionFloorSettlement(
				operationId = "superseded-retention",
				requestedRetainedFromMs = FLOOR,
				collectedDataEpoch = 0L,
				requestedAtMs = FLOOR,
			)
			AppDatabase.deleteAllCollectedData(
				database = database,
				operationId = "newer-full-deletion",
				collectedDataEpoch = 1L,
				retainedFromMs = FLOOR,
				updatedAtMs = FLOOR + 1L,
			)
			val settled = RetentionFloorSettlementResult.Settled(
				lifecycle = CollectedDataLifecycleSnapshot(0L, FLOOR),
				reconciledSources = emptySet(),
				operationId = "superseded-retention",
				requestedRetainedFromMs = FLOOR,
				requestedAtMs = FLOOR,
			)

			settlementCoordinator.complete(
				database = database,
				startupGate = readyGate(),
				expectedStartupGeneration = GENERATION,
				settlement = settled,
				completedAtMs = FLOOR + 2L,
				verifyApprovedOperation = { },
			) shouldBe RetentionFloorSettlementCompletionResult.SupersededByFullDeletion(1L)
		}

	@Test
	fun `settlement cannot publish complete after its ready generation closes`() = runTest {
		var ready = true
		val gate = object : TrackingStartupGate {
			override val isReady: Boolean
				get() = ready
			override val currentGeneration: Long = GENERATION
			override suspend fun reconcile(retryFailedStorage: Boolean): TrackingStartupResult =
				TrackingStartupResult.Ready(false, 0L)
		}
		val producer = mockk<RetentionAuthorityProducer> {
			coEvery { reconcileCurrentSettings() } returns retentionResults(active = emptySet())
		}
		val result = RetentionFloorSettlement(
			RetentionAuthorityOperationLease(),
			producer,
			TrackingRetentionFloorReconciler { _, floor, sources ->
				ready = false
				TrackingRetentionFloorReconciliationResult.Complete(floor, sources)
			},
		).settle(
			database = database,
			lifecycleStore = fixedLifecycleStore(
				CollectedDataLifecycleSnapshot(epoch = 4L, retainedFromMs = FLOOR),
			),
			startupGate = gate,
			expectedStartupGeneration = GENERATION,
			requestedRetainedFromMs = FLOOR,
			updatedAtMs = FLOOR,
			verifyApprovedOperation = { },
		).shouldBeInstanceOf<RetentionFloorSettlementResult.Retryable>()

		result.debt.failures.single()
			.shouldBeInstanceOf<RetentionFloorSettlementFailure.ProviderLifecycle>()
			.debt.failures.single().reason shouldBe
			TrackingRetentionFloorReconciliationFailureReason.STARTUP_GENERATION_CHANGED
	}

	private fun retentionResults(
		active: Set<TrackingSourceComponent>,
	): List<RetentionAuthorityResult> = listOf(
		TrackingSourceComponent.STEPS,
		TrackingSourceComponent.WIFI,
		TrackingSourceComponent.CELL,
	).flatMap { source ->
		RetentionAuthorityScope.entries.map { scope ->
			RetentionAuthorityResult.Unchanged(
				source = source,
				scope = scope,
				state = if (
					scope == RetentionAuthorityScope.LIVE_AMBIENT &&
					source in active
				) {
					RetentionAuthorityState.ACTIVE
				} else {
					RetentionAuthorityState.REVOKED
				},
				approvalRevision = 1L,
			)
		}
	}

	private fun fixedLifecycleStore(
		snapshot: CollectedDataLifecycleSnapshot,
		onAdvance: () -> Unit = { },
	): CollectedDataLifecycleStore = object : CollectedDataLifecycleStore {
		override val snapshots: Flow<CollectedDataLifecycleSnapshot> = MutableStateFlow(snapshot)

		override suspend fun snapshot(): CollectedDataLifecycleSnapshot = snapshot

		override suspend fun beginFullDeletion(
			deletedAtMs: Long,
		): CollectedDataLifecycleSnapshot = error("Not used")

		override suspend fun advanceRetainedFrom(
			retainedFromMs: Long,
		): CollectedDataLifecycleSnapshot {
			onAdvance()
			return snapshot
		}
	}

	private fun readyGate(): TrackingStartupGate = object : TrackingStartupGate {
		override val isReady: Boolean = true
		override val currentGeneration: Long = GENERATION
		override suspend fun reconcile(retryFailedStorage: Boolean): TrackingStartupResult =
			TrackingStartupResult.Ready(false, 0L)
	}

	private companion object {
		const val GENERATION = 7L
		const val FLOOR = 5_000L
	}
}
