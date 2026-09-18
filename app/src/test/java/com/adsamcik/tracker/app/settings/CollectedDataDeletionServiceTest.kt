package com.adsamcik.tracker.app.settings

import android.app.Application
import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationArbiter
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationResult
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationSnapshot
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationStatus
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationFailureCode
import com.adsamcik.tracker.app.startup.TrackingStartupDeletionBarrier
import com.adsamcik.tracker.impexp.exporter.automation.ExportPlanStore
import com.adsamcik.tracker.impexp.exporter.proto.ExportPlansProto
import com.adsamcik.tracker.points.database.PointsAwardedDao
import com.adsamcik.tracker.shared.base.database.migration.DatabaseMigrationBackupException
import com.adsamcik.tracker.shared.base.database.legacy.LEGACY_DATABASE_NAME
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.CollectedDataDeletionOperationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleSnapshot
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.shared.preferences.retention.DefaultRetentionAuthorityProducer
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityProducer
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityResult
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityScope
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityState
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityUnavailableReason
import com.adsamcik.tracker.shared.preferences.retention.RetentionConfigurationApprovalResult
import com.adsamcik.tracker.shared.preferences.retention.RetentionConfigStore
import com.adsamcik.tracker.shared.preferences.retention.resetRetentionConfigForTests
import com.adsamcik.tracker.shared.preferences.tracking.RoomSourcePolicyRepository
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyAuthorityBootstrapCoordinator
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyEffectiveTime
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyEffectiveTimeProvider
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyRevisionReconciliationDebt
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyRevisionReconciliationFailure
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyRevisionReconciliationResult
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent
import com.adsamcik.tracker.tracker.api.AmbientStepsProviderCleanupFailure
import com.adsamcik.tracker.tracker.api.AmbientStepsProviderCleanupResult
import com.adsamcik.tracker.tracker.api.AmbientStepsProviderLifecycle
import com.adsamcik.tracker.tracker.api.AmbientTrackingSource
import com.adsamcik.tracker.tracker.api.TrackingPurposeDeletionFencer
import com.adsamcik.tracker.tracker.api.TrackingPurposeSettingsReconciliationDebt
import com.adsamcik.tracker.tracker.api.TrackingPurposeSettingsReconciliationFailure
import com.adsamcik.tracker.tracker.api.TrackingPurposeSettingsReconciliationFailureReason
import com.adsamcik.tracker.tracker.api.TrackingPurposeSettingsReconciliationResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.secondArg
import io.mockk.thirdArg
import io.mockk.verify
import java.io.File
import javax.inject.Provider
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class CollectedDataDeletionServiceTest {
	private lateinit var context: Application
	private lateinit var markerFile: File
	private lateinit var clearingMarkerFile: File
	private lateinit var preparedMarkerFile: File
	private val pointsAwardedDao: PointsAwardedDao = mockk()
	private val exportPlanStore: ExportPlanStore = mockk()
	private val writerQuiescer: CollectedDataWriterQuiescer = mockk()
	private val collectedDataLifecycleStore: CollectedDataLifecycleStore = mockk()
	private val automaticControlRestorer: PostDeletionAutomaticControlRestorer = mockk()
	private lateinit var startupDeletionBarrier: TrackingStartupDeletionBarrier
	private val databaseOperations =
		mutableMapOf<String, CollectedDataDeletionOperationEntity>()
	private var lifecycleSnapshot = CollectedDataLifecycleSnapshot(0L, null)

	@Before
	fun setUp() {
		context = ApplicationProvider.getApplicationContext()
		markerFile = File(context.cacheDir, "collected-data-deletion-test.pending")
		clearingMarkerFile = File(markerFile.parentFile, "${markerFile.name}.clearing")
		preparedMarkerFile = File(markerFile.parentFile, "${markerFile.name}.tmp")
		startupDeletionBarrier = TrackingStartupDeletionBarrier()
		markerFile.delete()
		clearingMarkerFile.delete()
		preparedMarkerFile.delete()
		databaseOperations.clear()
		lifecycleSnapshot = CollectedDataLifecycleSnapshot(0L, null)
		RETIRED_DATABASE_NAMES.forEach {
			context.deleteDatabase(it)
			context.openOrCreateDatabase(it, Application.MODE_PRIVATE, null).close()
		}
		every { pointsAwardedDao.deleteAll() } just Runs
		coEvery { exportPlanStore.resetAllWatermarks() } returns
			ExportPlansProto.getDefaultInstance()
		coEvery { writerQuiescer.quiesce() } just Runs
		every { writerQuiescer.resume() } just Runs
		every { automaticControlRestorer.schedule(any()) } just Runs
		coEvery { collectedDataLifecycleStore.snapshot() } answers { lifecycleSnapshot }
		coEvery {
			collectedDataLifecycleStore.beginFullDeletion(any(), any(), any())
		} answers {
			lifecycleSnapshot = CollectedDataLifecycleSnapshot(
				epoch = secondArg(),
				retainedFromMs = thirdArg(),
			)
			lifecycleSnapshot
		}
	}

	@After
	fun tearDown() {
		markerFile.delete()
		clearingMarkerFile.delete()
		preparedMarkerFile.delete()
		RETIRED_DATABASE_NAMES.forEach(context::deleteDatabase)
	}

	@Test
	fun `delete clears every database`() = runTest {
		var appDeletionCount = 0
		var capturedEpoch: Long? = null
		var capturedRetainedFromMs: Long? = null
		val service = createService { _, epoch, retainedFromMs, _ ->
			context.getDatabasePath(LEGACY_DATABASE_NAME).exists() shouldBe false
			appDeletionCount++
			capturedEpoch = epoch
			capturedRetainedFromMs = retainedFromMs
		}

		service.deleteAll()

		verify(exactly = 1) { pointsAwardedDao.deleteAll() }
		coVerify(exactly = 1) { exportPlanStore.resetAllWatermarks() }
		coVerify(exactly = 2) { writerQuiescer.quiesce() }
		coVerify(exactly = 1) {
			collectedDataLifecycleStore.beginFullDeletion(any(), 1L, 1L)
		}
		coVerifyOrder {
			writerQuiescer.quiesce()
			writerQuiescer.quiesce()
			collectedDataLifecycleStore.beginFullDeletion(any(), 1L, 1L)
		}
		verify(exactly = 0) { writerQuiescer.resume() }
		verify(exactly = 1) { automaticControlRestorer.schedule(1L) }
		appDeletionCount shouldBe 1
		capturedEpoch shouldBe 1L
		capturedRetainedFromMs shouldBe 1L
		markerFile.exists() shouldBe false
		RETIRED_DATABASE_NAMES.forEach {
			context.getDatabasePath(it).exists() shouldBe false
		}
	}

	@Test
	fun `pending deletion resumes`() = runTest {
		val firstAttempt = createService { _, _, _, _ ->
			throw SQLiteException("interrupted")
		}

		firstAttempt.deleteAll() shouldBe CollectedDataDeletionCompletion.Retryable(
			CollectedDataDeletionReconciliationFailure.DeletionExecution,
		)
		markerFile.exists() shouldBe true
		startupDeletionBarrier.isClosed shouldBe true
		verify(exactly = 0) { writerQuiescer.resume() }

		var resumedAppDeletionCount = 0
		val resumed = createService { _, _, _, _ ->
			resumedAppDeletionCount++
		}
		resumed.reconcilePendingDeletion()

		verify(exactly = 2) { pointsAwardedDao.deleteAll() }
		coVerify(exactly = 4) { writerQuiescer.quiesce() }
		coVerify(exactly = 2) {
			collectedDataLifecycleStore.beginFullDeletion(any(), 1L, 1L)
		}
		verify(exactly = 0) { writerQuiescer.resume() }
		verify(exactly = 1) { automaticControlRestorer.schedule(1L) }
		coVerify(exactly = 1) { exportPlanStore.resetAllWatermarks() }
		resumedAppDeletionCount shouldBe 1
		markerFile.exists() shouldBe false
		startupDeletionBarrier.isClosed shouldBe false
		RETIRED_DATABASE_NAMES.forEach {
			context.getDatabasePath(it).exists() shouldBe false
		}
	}

	@Test
	fun `cancellation before journal publication leaves startup admission open`() = runTest {
		val snapshotStarted = CompletableDeferred<Unit>()
		coEvery { collectedDataLifecycleStore.snapshot() } coAnswers {
			snapshotStarted.complete(Unit)
			awaitCancellation()
		}
		val service = createService { _, _, _, _ ->
			error("database deletion must not start")
		}

		val deletion = async { service.deleteAll() }
		snapshotStarted.await()
		deletion.cancel()
		runCatching { deletion.await() }

		markerFile.exists() shouldBe false
		preparedMarkerFile.exists() shouldBe false
		startupDeletionBarrier.isClosed shouldBe false
		coVerify(exactly = 0) { writerQuiescer.quiesce() }
	}

	@Test
	fun `recovery never reports complete with a markerless closed barrier`() = runTest {
		startupDeletionBarrier.close()
		val service = createService { _, _, _, _ ->
			error("database deletion must not start")
		}

		service.reconcilePendingDeletion() shouldBe CollectedDataDeletionCompletion.Complete

		startupDeletionBarrier.isClosed shouldBe false
	}

	@Test
	fun `writer quiescence failure keeps the durable marker and startup barrier closed`() = runTest {
		coEvery { writerQuiescer.quiesce() } throws DatabaseMigrationBackupException(
			"writer cancellation timed out",
		)
		var appDeletionCount = 0
		val service = createService { _, _, _, _ -> appDeletionCount++ }

		service.deleteAll().shouldBeInstanceOf<CollectedDataDeletionCompletion.Retryable>()

		markerFile.exists() shouldBe true
		startupDeletionBarrier.isClosed shouldBe true
		appDeletionCount shouldBe 0
		verify(exactly = 0) { pointsAwardedDao.deleteAll() }
		verify(exactly = 0) { writerQuiescer.resume() }
		verify(exactly = 0) { automaticControlRestorer.schedule(any()) }
	}

	@Test
	fun `hung provider fence is bounded without skipping independent fences`() = runTest {
		val arbiter = mockk<ActivityRegistrationArbiter>()
		val ambientSteps = mockk<AmbientStepsProviderLifecycle>()
		coEvery { arbiter.closeForCollectedDataDeletion() } coAnswers {
			awaitCancellation()
		}
		coEvery { ambientSteps.closeForCollectedDataDeletion() } returns
			AmbientStepsProviderCleanupResult(complete = true)
		val service = createService(
			activityRegistrationArbiterProvider = Provider { arbiter },
			ambientStepsProviderLifecycleProvider = Provider { ambientSteps },
			providerFenceTimeoutMs = 10L,
		) { _, _, _, _ -> error("database deletion must not start") }

		service.deleteAll().shouldBeInstanceOf<CollectedDataDeletionCompletion.Retryable>()

		coVerify(exactly = 2) { ambientSteps.closeForCollectedDataDeletion() }
		coVerify(exactly = 2) { writerQuiescer.quiesce() }
		markerFile.exists() shouldBe true
		startupDeletionBarrier.isClosed shouldBe true
	}

	@Test
	fun `deletion closes admission then cancels writers before awaiting startup quiescence`() = runTest {
		val operationStarted = CompletableDeferred<Unit>()
		val allowOperationToFinish = CompletableDeferred<Unit>()
		val operation = async {
			startupDeletionBarrier.withStartupRecovery(onClosed = { "closed" }) {
				operationStarted.complete(Unit)
				allowOperationToFinish.await()
				"finished"
			}
		}
		operationStarted.await()
		val writerQuiescenceReached = CompletableDeferred<Unit>()
		coEvery { writerQuiescer.quiesce() } coAnswers {
			writerQuiescenceReached.complete(Unit)
		}
		var appDeletionCount = 0
		val service = createService { _, _, _, _ -> appDeletionCount += 1 }

		val deletion = async { service.deleteAll() }
		writerQuiescenceReached.await()
		runCurrent()

		startupDeletionBarrier.isClosed shouldBe true
		deletion.isCompleted shouldBe false
		appDeletionCount shouldBe 0
		allowOperationToFinish.complete(Unit)
		operation.await() shouldBe "finished"
		deletion.await()
		appDeletionCount shouldBe 1
		startupDeletionBarrier.isClosed shouldBe false
	}

	@Test
	fun `deletion fences writers again after an admitted recovery finishes`() = runTest {
		val operations = mutableListOf<String>()
		val arbiter = mockk<ActivityRegistrationArbiter>()
		val ambientSteps = mockk<AmbientStepsProviderLifecycle>()
		coEvery { arbiter.closeForCollectedDataDeletion() } coAnswers {
			operations += "activity-close"
			appliedRegistrationResult()
		}
		coEvery { ambientSteps.closeForCollectedDataDeletion() } coAnswers {
			operations += "ambient-steps-close"
			completeAmbientStepsCleanup()
		}
		coEvery { arbiter.resumeAfterCollectedDataDeletion() } coAnswers {
			operations += "activity-resume"
			appliedRegistrationResult()
		}
		coEvery { writerQuiescer.quiesce() } coAnswers {
			operations += "writer-quiesce"
		}
		every { writerQuiescer.resume() } answers {
			operations += "writer-resume"
		}
		val operationStarted = CompletableDeferred<Unit>()
		val allowOperationToFinish = CompletableDeferred<Unit>()
		val admittedRecovery = async {
			startupDeletionBarrier.withStartupRecovery(onClosed = { error("already admitted") }) {
				operationStarted.complete(Unit)
				allowOperationToFinish.await()
				arbiter.resumeAfterCollectedDataDeletion()
				writerQuiescer.resume()
			}
		}
		operationStarted.await()
		val service = createService(
			activityRegistrationArbiterProvider = Provider { arbiter },
			ambientStepsProviderLifecycleProvider = Provider { ambientSteps },
		) { _, _, _, _ -> operations += "delete" }

		val deletion = async { service.deleteAll() }
		runCurrent()
		operations shouldBe listOf("activity-close", "ambient-steps-close", "writer-quiesce")

		allowOperationToFinish.complete(Unit)
		admittedRecovery.await()
		deletion.await()

		operations shouldBe listOf(
			"activity-close",
			"ambient-steps-close",
			"writer-quiesce",
			"activity-resume",
			"writer-resume",
			"activity-close",
			"ambient-steps-close",
			"writer-quiesce",
			"delete",
		)
		coVerify(exactly = 2) { arbiter.closeForCollectedDataDeletion() }
		coVerify(exactly = 2) { ambientSteps.closeForCollectedDataDeletion() }
		coVerify(exactly = 2) { writerQuiescer.quiesce() }
	}

	@Test
	fun `late pre quiescence fence completion cannot satisfy the final fence`() = runTest {
		val firstFenceStarted = CompletableDeferred<Unit>()
		val releaseFirstFence = CompletableDeferred<Unit>()
		var fenceCalls = 0
		var activeFences = 0
		var maximumActiveFences = 0
		val arbiter = mockk<ActivityRegistrationArbiter>()
		coEvery { arbiter.closeForCollectedDataDeletion() } coAnswers {
			fenceCalls += 1
			activeFences += 1
			maximumActiveFences = maxOf(maximumActiveFences, activeFences)
			try {
				if (fenceCalls == 1) {
					firstFenceStarted.complete(Unit)
					releaseFirstFence.await()
				}
				appliedRegistrationResult()
			} finally {
				activeFences -= 1
			}
		}
		val firstWriterFence = CompletableDeferred<Unit>()
		var writerFenceCalls = 0
		coEvery { writerQuiescer.quiesce() } coAnswers {
			writerFenceCalls += 1
			if (writerFenceCalls == 1) firstWriterFence.complete(Unit)
		}
		val service = createService(
			activityRegistrationArbiterProvider = Provider { arbiter },
			providerFenceTimeoutMs = 10L,
			providerFenceScope = backgroundScope,
		) { _, _, _, _ -> }

		val deletion = async { service.deleteAll() }
		firstFenceStarted.await()
		advanceTimeBy(11L)
		runCurrent()
		firstWriterFence.await()
		releaseFirstFence.complete(Unit)

		deletion.await() shouldBe CollectedDataDeletionCompletion.Complete
		fenceCalls shouldBe 2
		writerFenceCalls shouldBe 2
		maximumActiveFences shouldBe 1
	}

	@Test
	fun `cancelled UI await cannot cancel deletion after the durable marker`() = runTest {
		val fenceStarted = CompletableDeferred<Unit>()
		val allowFence = CompletableDeferred<Unit>()
		var writerFenceCalls = 0
		coEvery { writerQuiescer.quiesce() } coAnswers {
			writerFenceCalls += 1
			if (writerFenceCalls == 1) {
				fenceStarted.complete(Unit)
				allowFence.await()
			}
		}
		var physicalClearCount = 0
		val service = createService(
			providerFenceScope = backgroundScope,
		) { _, _, _, _ ->
			physicalClearCount += 1
		}

		val uiAwait = async { service.deleteAll() }
		fenceStarted.await()
		markerFile.exists() shouldBe true
		uiAwait.cancel()
		uiAwait.join()

		val serviceRejoin = async { service.reconcilePendingDeletion() }
		runCurrent()
		physicalClearCount shouldBe 0
		allowFence.complete(Unit)

		serviceRejoin.await() shouldBe CollectedDataDeletionCompletion.Complete
		physicalClearCount shouldBe 1
		writerFenceCalls shouldBe 2
		markerFile.exists() shouldBe false
		startupDeletionBarrier.isClosed shouldBe false
	}

	@Test
	fun `completed deletion is evicted after its only caller cancels`() = runTest {
		val firstClearStarted = CompletableDeferred<Unit>()
		val releaseFirstClear = CompletableDeferred<Unit>()
		var physicalClearCount = 0
		val service = createService(
			providerFenceScope = backgroundScope,
		) { _, _, _, _ ->
			physicalClearCount += 1
			if (physicalClearCount == 1) {
				firstClearStarted.complete(Unit)
				releaseFirstClear.await()
			}
		}

		val firstCaller = async { service.deleteAll() }
		firstClearStarted.await()
		firstCaller.cancel()
		firstCaller.join()
		releaseFirstClear.complete(Unit)
		runCurrent()

		markerFile.exists() shouldBe false
		service.deleteAll() shouldBe CollectedDataDeletionCompletion.Complete

		physicalClearCount shouldBe 2
		lifecycleSnapshot.epoch shouldBe 2L
		coVerify(exactly = 2) {
			collectedDataLifecycleStore.beginFullDeletion(any(), any(), any())
		}
	}

	@Test
	fun `failed completed deletion flight is evicted before durable retry`() = runTest {
		val firstClearStarted = CompletableDeferred<Unit>()
		val releaseFailure = CompletableDeferred<Unit>()
		var physicalClearAttempts = 0
		val service = createService(
			providerFenceScope = backgroundScope,
		) { _, _, _, _ ->
			physicalClearAttempts += 1
			if (physicalClearAttempts == 1) {
				firstClearStarted.complete(Unit)
				releaseFailure.await()
				error("first durable clear attempt failed")
			}
		}

		val abandonedCaller = async { service.deleteAll() }
		firstClearStarted.await()
		abandonedCaller.cancel()
		abandonedCaller.join()
		releaseFailure.complete(Unit)
		runCurrent()

		markerFile.exists() shouldBe true
		service.reconcilePendingDeletion() shouldBe CollectedDataDeletionCompletion.Complete

		physicalClearAttempts shouldBe 2
		markerFile.exists() shouldBe false
	}

	@Test
	fun `durable deletion callers deduplicate and rejoin one physical clear`() = runTest {
		val clearStarted = CompletableDeferred<Unit>()
		val allowClear = CompletableDeferred<Unit>()
		var physicalClearCount = 0
		val service = createService(
			providerFenceScope = backgroundScope,
		) { _, _, _, _ ->
			physicalClearCount += 1
			clearStarted.complete(Unit)
			allowClear.await()
		}

		val first = async { service.deleteAll() }
		clearStarted.await()
		val operationMarker = markerFile.readText()
		val second = async { service.deleteAll() }
		val startupRejoin = async { service.reconcilePendingDeletion() }
		runCurrent()

		physicalClearCount shouldBe 1
		markerFile.readText() shouldBe operationMarker
		allowClear.complete(Unit)
		listOf(first.await(), second.await(), startupRejoin.await()).forEach {
			it shouldBe CollectedDataDeletionCompletion.Complete
		}
		physicalClearCount shouldBe 1
		coVerify(exactly = 1) {
			collectedDataLifecycleStore.beginFullDeletion(any(), any(), any())
		}
	}

	@Test
	fun `durably journalled Ambient Steps removal debt blocks destructive deletion`() = runTest {
		val ambientSteps = mockk<AmbientStepsProviderLifecycle>()
		coEvery { ambientSteps.closeForCollectedDataDeletion() } returns
			AmbientStepsProviderCleanupResult(
				complete = false,
				failure = AmbientStepsProviderCleanupFailure.PROVIDER_REMOVAL_FAILED,
				retryable = true,
			)
		var appDeletionCount = 0
		val service = createService(
			ambientStepsProviderLifecycleProvider = Provider { ambientSteps },
		) { _, _, _, _ -> appDeletionCount += 1 }

		service.deleteAll().shouldBeInstanceOf<CollectedDataDeletionCompletion.Retryable>()

		appDeletionCount shouldBe 0
		coVerify(exactly = 2) { ambientSteps.closeForCollectedDataDeletion() }
		coVerify(exactly = 2) { writerQuiescer.quiesce() }
	}

	@Test
	fun `post deletion authority completes while provider reconciliation remains startup owned`() =
		runTest {
		val events = mutableListOf<String>()
		val retention = mockk<RetentionAuthorityProducer>()
		val ambientSteps = mockk<AmbientStepsProviderLifecycle>()
		coEvery { ambientSteps.closeForCollectedDataDeletion() } returns
			AmbientStepsProviderCleanupResult(complete = true)
		coEvery { retention.reconcileCurrentSettings() } answers {
			startupDeletionBarrier.isClosed shouldBe true
			markerFile.exists() shouldBe true
			events += "retention"
			disabledRetentionResults()
		}
		val service = createService(
			ambientStepsProviderLifecycleProvider = Provider { ambientSteps },
			retentionAuthorityProducer = retention,
		) { _, _, _, _ -> events += "delete" }

		service.deleteAll()

		events shouldBe listOf("delete", "retention", "retention")
		markerFile.exists() shouldBe false
		startupDeletionBarrier.isClosed shouldBe false
	}

	@Test
	fun `real Room epoch mismatch keeps marker gate and provider shutdown`() = runTest {
		resetRetentionTestState()
		val database = AppDatabase.testDatabase(context)
		try {
			val lifecycle = MutableDeletionLifecycleStore()
			val producer = roomRetentionProducer(
				database,
				lifecycle,
				ambientStepsEnabled = true,
				approvePolicy = true,
			)
			val ambientSteps = mockk<AmbientStepsProviderLifecycle>()
			coEvery { ambientSteps.closeForCollectedDataDeletion() } returns
				AmbientStepsProviderCleanupResult(complete = true)
			val service = createService(
				collectedDataLifecycleStore = lifecycle,
				ambientStepsProviderLifecycleProvider = Provider { ambientSteps },
				retentionAuthorityProducer = producer,
			) { _, epoch, retainedFromMs, updatedAtMs ->
				publishRoomDeletionEpoch(database, epoch + 1L, retainedFromMs, updatedAtMs)
			}

			val result = service.deleteAll()

			val retryable =
				result.shouldBeInstanceOf<CollectedDataDeletionCompletion.Retryable>()
			val failure = retryable.failure.shouldBeInstanceOf<
				CollectedDataDeletionReconciliationFailure.RetentionAuthority>()
			failure.failures.single().run {
				source shouldBe TrackingSourceComponent.STEPS
				reason shouldBe RetentionAuthorityUnavailableReason.COLLECTED_DATA_EPOCH_CHANGED
			}
			markerFile.exists() shouldBe true
			startupDeletionBarrier.isClosed shouldBe true
			coVerify(exactly = 3) { ambientSteps.closeForCollectedDataDeletion() }
			verify(exactly = 0) { automaticControlRestorer.schedule(any()) }
		} finally {
			database.close()
			resetRetentionTestState()
		}
	}

	@Test
	fun `real Room explicitly disabled sources complete without provider reconciliation`() = runTest {
		resetRetentionTestState()
		val database = AppDatabase.testDatabase(context)
		try {
			val lifecycle = MutableDeletionLifecycleStore()
			val producer = roomRetentionProducer(
				database,
				lifecycle,
				ambientStepsEnabled = false,
			)
			val ambientSteps = mockk<AmbientStepsProviderLifecycle>()
			coEvery { ambientSteps.closeForCollectedDataDeletion() } returns
				AmbientStepsProviderCleanupResult(complete = true)
			val service = createService(
				collectedDataLifecycleStore = lifecycle,
				ambientStepsProviderLifecycleProvider = Provider { ambientSteps },
				retentionAuthorityProducer = producer,
			) { _, epoch, retainedFromMs, updatedAtMs ->
				publishRoomDeletionEpoch(database, epoch, retainedFromMs, updatedAtMs)
			}

			service.deleteAll() shouldBe CollectedDataDeletionCompletion.Complete

			markerFile.exists() shouldBe false
			startupDeletionBarrier.isClosed shouldBe false
		} finally {
			database.close()
			resetRetentionTestState()
		}
	}

	@Test
	fun `real Room enabled source reissues new epoch authority without reopening provider`() =
		runTest {
			resetRetentionTestState()
			val database = AppDatabase.testDatabase(context)
			try {
				val lifecycle = MutableDeletionLifecycleStore()
				val producer = roomRetentionProducer(
					database,
					lifecycle,
					ambientStepsEnabled = true,
					approvePolicy = true,
				)
				val ambientSteps = mockk<AmbientStepsProviderLifecycle>()
				coEvery { ambientSteps.closeForCollectedDataDeletion() } returns
					AmbientStepsProviderCleanupResult(complete = true)
				val service = createService(
					collectedDataLifecycleStore = lifecycle,
					ambientStepsProviderLifecycleProvider = Provider { ambientSteps },
					retentionAuthorityProducer = producer,
				) { _, epoch, retainedFromMs, updatedAtMs ->
					publishRoomDeletionEpoch(database, epoch, retainedFromMs, updatedAtMs)
				}

				service.deleteAll() shouldBe CollectedDataDeletionCompletion.Complete

				database.ambientStepsFactRevisionDao().latestRetentionAuthority(
					com.adsamcik.tracker.shared.base.database.data
						.AmbientStepsRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
				)?.collectedDataEpoch shouldBe 1L
			} finally {
				database.close()
				resetRetentionTestState()
			}
		}

	@Test
	fun `startup retry bootstraps authority after DATABASE_CLEARED without provider activation`() =
		runTest {
			val events = mutableListOf<String>()
			val debt = SourcePolicyRevisionReconciliationDebt(
				policyRevision = null,
				failures = listOf(
					SourcePolicyRevisionReconciliationFailure.SourcePolicyUnavailable,
				),
			)
			var bootstrapAvailable = false
			val coordinator = SourcePolicyAuthorityBootstrapCoordinator {
				startupDeletionBarrier.isClosed shouldBe true
				markerFile.exists() shouldBe true
				events += "authority"
				if (bootstrapAvailable) {
					SourcePolicyRevisionReconciliationResult.Complete(mockk())
				} else {
					SourcePolicyRevisionReconciliationResult.Retryable(debt)
				}
			}
			val retention = mockk<RetentionAuthorityProducer>()
			coEvery { retention.reconcileCurrentSettings() } coAnswers {
				startupDeletionBarrier.isClosed shouldBe true
				markerFile.exists() shouldBe true
				events += "retention"
				disabledRetentionResults()
			}
			val ambientSteps = mockk<AmbientStepsProviderLifecycle>()
			coEvery { ambientSteps.closeForCollectedDataDeletion() } returns
				AmbientStepsProviderCleanupResult(complete = true)
			var physicalDeletionCount = 0
			var writerRearmCount = 0
			val service = createService(
				postDatabaseDeletion = {
					writerRearmCount += 1
					events += "writer-rearm"
				},
				ambientStepsProviderLifecycleProvider = Provider { ambientSteps },
				retentionAuthorityProducer = retention,
				sourcePolicyAuthorityBootstrapCoordinatorProvider = Provider { coordinator },
			) { _, _, _, _ ->
				physicalDeletionCount += 1
				events += "database-cleared"
			}

			val first = service.deleteAll()

			val retry = first.shouldBeInstanceOf<CollectedDataDeletionCompletion.Retryable>()
			retry.failure shouldBe
				CollectedDataDeletionReconciliationFailure.SourcePolicyAuthority(debt)
			databaseOperations.single().value.phase shouldBe
				CollectedDataDeletionOperationEntity.PHASE_WRITERS_REARMED
			markerFile.exists() shouldBe true
			startupDeletionBarrier.isClosed shouldBe true
			events shouldBe listOf("database-cleared", "writer-rearm", "authority")

			bootstrapAvailable = true
			service.reconcilePendingDeletion() shouldBe CollectedDataDeletionCompletion.Complete

			physicalDeletionCount shouldBe 1
			writerRearmCount shouldBe 1
			events shouldBe listOf(
				"database-cleared",
				"writer-rearm",
				"authority",
				"authority",
				"retention",
				"authority",
				"retention",
			)
			markerFile.exists() shouldBe false
			startupDeletionBarrier.isClosed shouldBe false
		}

	@Test
	fun `integrity failure returns unverifiable debt and keeps provider off`() = runTest {
		val retention = mockk<RetentionAuthorityProducer>()
		val ambientSteps = mockk<AmbientStepsProviderLifecycle>()
		coEvery { retention.reconcileCurrentSettings() } returns
			retentionFailureResults(RetentionAuthorityUnavailableReason.INTEGRITY_MISMATCH)
		coEvery { ambientSteps.closeForCollectedDataDeletion() } returns
			AmbientStepsProviderCleanupResult(complete = true)
		val service = createService(
			ambientStepsProviderLifecycleProvider = Provider { ambientSteps },
			retentionAuthorityProducer = retention,
		) { _, _, _, _ -> }

		val result = service.deleteAll()

		result.shouldBeInstanceOf<CollectedDataDeletionCompletion.Unverifiable>()
		markerFile.exists() shouldBe true
		startupDeletionBarrier.isClosed shouldBe true
		coVerify(exactly = 3) { ambientSteps.closeForCollectedDataDeletion() }
	}

	@Test
	fun `invalid Ambient Steps cleanup journal blocks Room deletion`() = runTest {
		val ambientSteps = mockk<AmbientStepsProviderLifecycle>()
		coEvery { ambientSteps.closeForCollectedDataDeletion() } returns
			AmbientStepsProviderCleanupResult(
				complete = false,
				failure = AmbientStepsProviderCleanupFailure.CLEANUP_JOURNAL_INVALID,
				retryable = false,
			)
		var appDeletionCount = 0
		val service = createService(
			ambientStepsProviderLifecycleProvider = Provider { ambientSteps },
		) { _, _, _, _ -> appDeletionCount += 1 }

		service.deleteAll().shouldBeInstanceOf<CollectedDataDeletionCompletion.Retryable>()

		appDeletionCount shouldBe 0
		markerFile.exists() shouldBe true
		startupDeletionBarrier.isClosed shouldBe true
		coVerify(exactly = 2) { ambientSteps.closeForCollectedDataDeletion() }
		coVerify(exactly = 2) { writerQuiescer.quiesce() }
	}

	@Test
	fun `cold pending deletion resolves provider fences before deleting retired vaults`() = runTest {
		val interrupted = createService { _, _, _, _ ->
			throw SQLiteException("interrupted before database commit")
		}
		interrupted.deleteAll() shouldBe CollectedDataDeletionCompletion.Retryable(
			CollectedDataDeletionReconciliationFailure.DeletionExecution,
		)
		RETIRED_DATABASE_NAMES.forEach {
			context.openOrCreateDatabase(it, Application.MODE_PRIVATE, null).close()
		}
		val arbiter = mockk<ActivityRegistrationArbiter>()
		val ambientSteps = mockk<AmbientStepsProviderLifecycle>()
		val result = appliedRegistrationResult()
		coEvery { arbiter.closeForCollectedDataDeletion() } returns result
		coEvery { ambientSteps.closeForCollectedDataDeletion() } returns
			completeAmbientStepsCleanup()
		var providerResolutions = 0
		val activityProvider = Provider {
			providerResolutions++
			arbiter
		}
		val ambientStepsProvider = Provider {
			providerResolutions++
			ambientSteps
		}
		val service = createService(
			activityRegistrationArbiterProvider = activityProvider,
			ambientStepsProviderLifecycleProvider = ambientStepsProvider,
		) { _, _, _, _ ->
			RETIRED_DATABASE_NAMES.forEach { databaseName ->
				context.getDatabasePath(databaseName).exists() shouldBe false
			}
		}

		service.reconcilePendingDeletion()

		providerResolutions shouldBe 2
		coVerify(exactly = 2) { arbiter.closeForCollectedDataDeletion() }
		coVerify(exactly = 2) { ambientSteps.closeForCollectedDataDeletion() }
		coVerify(exactly = 0) { arbiter.resumeAfterCollectedDataDeletion() }
		markerFile.exists() shouldBe false
	}

	@Test
	fun `durably deferred provider removal does not block local deletion or global writers`() = runTest {
		val arbiter = mockk<ActivityRegistrationArbiter>()
		val degraded = ActivityRegistrationResult(
			status = ActivityRegistrationStatus.DEGRADED,
			snapshot = appliedRegistrationResult().snapshot,
			failureCode = ActivityRegistrationFailureCode.PROVIDER_REMOVAL_FAILED,
			retryable = true,
		)
		coEvery { arbiter.closeForCollectedDataDeletion() } returns degraded
		var appDeletionCount = 0
		val service = createService(
			activityRegistrationArbiterProvider = Provider { arbiter },
		) { _, _, _, _ -> appDeletionCount++ }

		service.deleteAll()

		appDeletionCount shouldBe 1
		markerFile.exists() shouldBe false
		startupDeletionBarrier.isClosed shouldBe false
		coVerify(exactly = 2) { writerQuiescer.quiesce() }
		coVerify(exactly = 2) { arbiter.closeForCollectedDataDeletion() }
		verify(exactly = 0) { writerQuiescer.resume() }
		coVerify(exactly = 0) { arbiter.resumeAfterCollectedDataDeletion() }
		verify(exactly = 1) { automaticControlRestorer.schedule(1L) }
	}

	@Test
	fun `completed deletion schedules durable recovery by collected data epoch before reopening`() = runTest {
		val restorer = mockk<PostDeletionAutomaticControlRestorer>(relaxed = true)
		every { restorer.schedule(any()) } answers {
			markerFile.exists() shouldBe true
			startupDeletionBarrier.isClosed shouldBe true
		}
		val service = createService(
			automaticControlRestorer = restorer,
		) { _, _, _, _ -> }

		service.deleteAll()

		startupDeletionBarrier.currentGeneration shouldBe 1L
		startupDeletionBarrier.isClosed shouldBe false
		verify(exactly = 1) { restorer.schedule(1L) }
	}

	@Test
	fun `prepared marker durability failure keeps admission and providers closed`() = runTest {
		val service = createService(
			directorySync = { error("fsync failed") },
		) { _, _, _, _ -> error("database deletion must not start") }

		service.deleteAll() shouldBe CollectedDataDeletionCompletion.Retryable(
			CollectedDataDeletionReconciliationFailure.DeletionMarkerPublication,
		)

		preparedMarkerFile.exists() shouldBe true
		startupDeletionBarrier.isClosed shouldBe true
		coVerify(exactly = 0) {
			collectedDataLifecycleStore.beginFullDeletion(any(), any(), any())
		}
		coVerify(exactly = 2) { writerQuiescer.quiesce() }
	}

	@Test
	fun `rename fsync uncertainty preserves marker and closes admission before payload work`() =
		runTest {
			var syncCount = 0
			val ambientSteps = mockk<AmbientStepsProviderLifecycle>()
			coEvery { ambientSteps.closeForCollectedDataDeletion() } returns
				AmbientStepsProviderCleanupResult(complete = true)
			val service = createService(
				ambientStepsProviderLifecycleProvider = Provider { ambientSteps },
				directorySync = {
					syncCount += 1
					if (syncCount == 2) error("renamed entry fsync failed")
				},
			) { _, _, _, _ -> error("database deletion must not start") }

			service.deleteAll() shouldBe CollectedDataDeletionCompletion.Unverifiable(
				CollectedDataDeletionReconciliationFailure.DeletionMarkerDurability,
			)

			markerFile.exists() shouldBe true
			startupDeletionBarrier.isClosed shouldBe true
			coVerify(exactly = 0) {
				collectedDataLifecycleStore.beginFullDeletion(any(), any(), any())
			}
			coVerify(exactly = 2) { ambientSteps.closeForCollectedDataDeletion() }
			coVerify(exactly = 2) { writerQuiescer.quiesce() }
		}

	@Test
	fun `startup publishes a durable prepared marker before beginning deletion`() = runTest {
		var syncCount = 0
		val interrupted = createService(
			directorySync = {
				syncCount += 1
				if (syncCount == 1) error("prepared entry not yet durable")
			},
		) { _, _, _, _ -> error("database deletion must not start") }
		interrupted.deleteAll()
		preparedMarkerFile.exists() shouldBe true
		markerFile.exists() shouldBe false

		var appDeletionCount = 0
		val recovered = createService { _, _, _, _ -> appDeletionCount += 1 }
		recovered.reconcilePendingDeletion() shouldBe CollectedDataDeletionCompletion.Complete

		appDeletionCount shouldBe 1
		preparedMarkerFile.exists() shouldBe false
		markerFile.exists() shouldBe false
		startupDeletionBarrier.isClosed shouldBe false
	}

	@Test
	fun `marker removal failure returns retryable debt with barrier and provider closed`() = runTest {
		var appDeletionCount = 0
		val arbiter = mockk<ActivityRegistrationArbiter>()
		val ambientSteps = mockk<AmbientStepsProviderLifecycle>()
		coEvery { arbiter.closeForCollectedDataDeletion() } returns appliedRegistrationResult()
		coEvery { ambientSteps.closeForCollectedDataDeletion() } returns
			AmbientStepsProviderCleanupResult(complete = true)
		val first = createService(
			activityRegistrationArbiterProvider = Provider { arbiter },
			ambientStepsProviderLifecycleProvider = Provider { ambientSteps },
			markerDelete = { false },
		) { _, _, _, _ -> appDeletionCount += 1 }

		first.deleteAll() shouldBe CollectedDataDeletionCompletion.Retryable(
			CollectedDataDeletionReconciliationFailure.DeletionMarkerRemoval,
		)

		markerFile.exists() shouldBe false
		clearingMarkerFile.exists() shouldBe true
		startupDeletionBarrier.isClosed shouldBe true
		appDeletionCount shouldBe 1
		coVerify(exactly = 3) { ambientSteps.closeForCollectedDataDeletion() }

		val resumed = createService(
			activityRegistrationArbiterProvider = Provider { arbiter },
			ambientStepsProviderLifecycleProvider = Provider { ambientSteps },
			markerDelete = { it.delete() },
		) { _, _, _, _ -> appDeletionCount += 1 }

		resumed.reconcilePendingDeletion() shouldBe CollectedDataDeletionCompletion.Complete

		appDeletionCount shouldBe 1
		clearingMarkerFile.exists() shouldBe false
		startupDeletionBarrier.isClosed shouldBe false
		coVerify(exactly = 4) { arbiter.closeForCollectedDataDeletion() }
		coVerify(exactly = 5) { ambientSteps.closeForCollectedDataDeletion() }
		coVerify(exactly = 4) { writerQuiescer.quiesce() }
		coVerify(exactly = 1) {
			collectedDataLifecycleStore.beginFullDeletion(any(), any(), any())
		}
		verify(exactly = 1) { automaticControlRestorer.schedule(1L) }
	}

	@Test
	fun `final directory fsync failure is unverifiable until repeated recovery succeeds`() = runTest {
		var syncCount = 0
		var appDeletionCount = 0
		val ambientSteps = mockk<AmbientStepsProviderLifecycle>()
		coEvery { ambientSteps.closeForCollectedDataDeletion() } returns
			AmbientStepsProviderCleanupResult(complete = true)
		val service = createService(
			ambientStepsProviderLifecycleProvider = Provider { ambientSteps },
			directorySync = {
				syncCount += 1
				if (syncCount == 4) error("final directory fsync failed")
			},
		) { _, _, _, _ -> appDeletionCount += 1 }

		service.deleteAll() shouldBe CollectedDataDeletionCompletion.Unverifiable(
			CollectedDataDeletionReconciliationFailure.DeletionMarkerDurability,
		)

		markerFile.exists() shouldBe false
		clearingMarkerFile.exists() shouldBe false
		startupDeletionBarrier.isClosed shouldBe true
		appDeletionCount shouldBe 1
		coVerify(exactly = 3) { ambientSteps.closeForCollectedDataDeletion() }

		service.reconcilePendingDeletion() shouldBe CollectedDataDeletionCompletion.Complete

		appDeletionCount shouldBe 1
		startupDeletionBarrier.isClosed shouldBe false
		syncCount shouldBe 5
		coVerify(exactly = 5) { ambientSteps.closeForCollectedDataDeletion() }
		coVerify(exactly = 4) { writerQuiescer.quiesce() }
		coVerify(exactly = 1) {
			collectedDataLifecycleStore.beginFullDeletion(any(), any(), any())
		}
		verify(exactly = 1) { automaticControlRestorer.schedule(1L) }
	}

	@Test
	fun `new deletion attempt preserves an existing durable marker`() = runTest {
		val service = createService { _, _, _, _ ->
			throw SQLiteException("interrupted again")
		}

		service.deleteAll() shouldBe CollectedDataDeletionCompletion.Retryable(
			CollectedDataDeletionReconciliationFailure.DeletionExecution,
		)
		val originalMarker = markerFile.readText()
		service.deleteAll() shouldBe CollectedDataDeletionCompletion.Retryable(
			CollectedDataDeletionReconciliationFailure.DeletionExecution,
		)

		markerFile.exists() shouldBe true
		markerFile.readText() shouldBe originalMarker
		preparedMarkerFile.exists() shouldBe false
	}

	@Test
	fun `complete deletion orders Tracker data before local and Tracebox diagnostics`() = runTest {
		val operations = mutableListOf<String>()
		coEvery { exportPlanStore.resetAllWatermarks() } coAnswers {
			operations += "watermarks"
			ExportPlansProto.getDefaultInstance()
		}
		val service = createService(
			postDatabaseDeletion = { operations += "writer-rearm" },
			traceboxDataDeletion = {
				operations += "tracebox"
				true
			},
			trackingDiagnosticDataDeletion = {
				operations += "tracking-diagnostics"
				true
			},
		) { _, _, _, _ ->
			operations += "tracker"
		}

		service.deleteAll()

		operations shouldBe listOf(
			"tracker",
			"writer-rearm",
			"watermarks",
			"tracking-diagnostics",
			"tracebox",
		)
		markerFile.exists() shouldBe false
	}

	@Test
	fun `pending local diagnostic deletion retains marker and retries full deletion`() = runTest {
		val operations = mutableListOf<String>()
		var localComplete = false
		val service = createService(
			trackingDiagnosticDataDeletion = {
				operations += "tracking-diagnostics"
				localComplete
			},
			traceboxDataDeletion = {
				operations += "tracebox"
				true
			},
		) { _, _, _, _ ->
			operations += "tracker"
		}

		runCatching { service.deleteAll() }
			.exceptionOrNull()
			.shouldBeInstanceOf<DatabaseMigrationBackupException>()
		operations shouldBe listOf("tracker", "tracking-diagnostics", "tracebox")
		markerFile.exists() shouldBe true

		localComplete = true
		service.reconcilePendingDeletion()

		operations shouldBe listOf(
			"tracker",
			"tracking-diagnostics",
			"tracebox",
			"tracker",
			"tracking-diagnostics",
			"tracebox",
		)
		markerFile.exists() shouldBe false
	}

	@Test
	fun `writer rearm failure retains marker and closed gate until full retry succeeds`() = runTest {
		val operations = mutableListOf<String>()
		var rearmComplete = false
		val service = createService(
			postDatabaseDeletion = {
				operations += "writer-rearm"
				if (!rearmComplete) error("writer authority unavailable")
			},
		) { _, _, _, _ -> operations += "tracker" }

		service.deleteAll() shouldBe CollectedDataDeletionCompletion.Retryable(
			CollectedDataDeletionReconciliationFailure.DeletionExecution,
		)

		operations shouldBe listOf("tracker", "writer-rearm")
		markerFile.exists() shouldBe true
		startupDeletionBarrier.isClosed shouldBe true
		verify(exactly = 0) { automaticControlRestorer.schedule(any()) }

		rearmComplete = true
		service.reconcilePendingDeletion()

		operations shouldBe listOf(
			"tracker",
			"writer-rearm",
			"writer-rearm",
		)
		markerFile.exists() shouldBe false
		startupDeletionBarrier.isClosed shouldBe false
		verify(exactly = 1) { automaticControlRestorer.schedule(1L) }
	}

	@Test
	fun `crash after committed database clear resumes without another clear or epoch advance`() =
		runTest {
			var physicalClearCount = 0
			val first = createService(
				appDatabaseDeletionOperation = { _, operation ->
					physicalClearCount += 1
					databaseOperations[operation.operationId] =
						CollectedDataDeletionOperationEntity(
							operationId = operation.operationId,
							targetCollectedDataEpoch = operation.targetCollectedDataEpoch,
							retainedFromMs = operation.retainedFromMs,
							deletedAtMs = operation.deletedAtMs,
							phase =
								CollectedDataDeletionOperationEntity.PHASE_DATABASE_CLEARED,
							updatedAtMs = operation.deletedAtMs,
						)
					error("crash after committed clear")
				},
			) { _, _, _, _ -> error("legacy clear callback must not run") }

			first.deleteAll() shouldBe CollectedDataDeletionCompletion.Retryable(
				CollectedDataDeletionReconciliationFailure.DeletionExecution,
			)
			markerFile.exists() shouldBe true
			lifecycleSnapshot.epoch shouldBe 1L

			val resumed = createService { _, _, _, _ ->
				error("physical clear must not repeat after its durable receipt")
			}
			resumed.reconcilePendingDeletion() shouldBe CollectedDataDeletionCompletion.Complete

			physicalClearCount shouldBe 1
			lifecycleSnapshot.epoch shouldBe 1L
			coVerify(exactly = 1) {
				collectedDataLifecycleStore.beginFullDeletion(any(), any(), any())
			}
		}

	@Test
	fun `pending Tracebox deletion retains marker and retries the full transaction`() =
		runTest {
			val operations = mutableListOf<String>()
			var traceboxComplete = false
			val service = createService(
				traceboxDataDeletion = {
					operations += "tracebox"
					traceboxComplete
				},
			) { _, _, _, _ ->
				operations += "tracker"
			}

			service.deleteAll() shouldBe CollectedDataDeletionCompletion.Retryable(
				CollectedDataDeletionReconciliationFailure.DeletionExecution,
			)
			operations shouldBe listOf("tracker", "tracebox")
			markerFile.exists() shouldBe true

			traceboxComplete = true
			service.reconcilePendingDeletion()

			operations shouldBe listOf(
				"tracker",
				"tracebox",
				"tracebox",
			)
			markerFile.exists() shouldBe false
		}

	@Test
	fun `failure after writer rearm resumes without repeating deletion or authority generation`() =
		runTest {
		val operations = mutableListOf<String>()
		var rearmGeneration = 0
		var traceboxComplete = false
		coEvery { exportPlanStore.resetAllWatermarks() } coAnswers {
			operations += "watermarks"
			ExportPlansProto.getDefaultInstance()
		}
		val service = createService(
			postDatabaseDeletion = {
				rearmGeneration += 1
				operations += "writer-rearm-$rearmGeneration"
			},
			traceboxDataDeletion = {
				operations += "tracebox"
				traceboxComplete
			},
		) { _, _, _, _ -> operations += "tracker" }

		service.deleteAll() shouldBe CollectedDataDeletionCompletion.Retryable(
			CollectedDataDeletionReconciliationFailure.DeletionExecution,
		)
		markerFile.exists() shouldBe true
		startupDeletionBarrier.isClosed shouldBe true

		traceboxComplete = true
		service.reconcilePendingDeletion()

		operations shouldBe listOf(
			"tracker",
			"writer-rearm-1",
			"watermarks",
			"tracebox",
			"watermarks",
			"tracebox",
		)
		rearmGeneration shouldBe 1
		coVerify(exactly = 1) {
			collectedDataLifecycleStore.beginFullDeletion(any(), any(), any())
		}
		markerFile.exists() shouldBe false
		startupDeletionBarrier.isClosed shouldBe false
	}

	@Test
	fun `Tracebox exception retains marker and is retried`() = runTest {
		var deletionAttempts = 0
		var shouldFail = true
		val service = createService(
			traceboxDataDeletion = {
				deletionAttempts += 1
				if (shouldFail) error("Tracebox unavailable")
				true
			},
		) { _, _, _, _ -> }

		service.deleteAll() shouldBe CollectedDataDeletionCompletion.Retryable(
			CollectedDataDeletionReconciliationFailure.DeletionExecution,
		)

		deletionAttempts shouldBe 1
		markerFile.exists() shouldBe true

		shouldFail = false
		service.reconcilePendingDeletion()

		deletionAttempts shouldBe 2
		markerFile.exists() shouldBe false
	}

	private fun createService(
		traceboxDataDeletion: suspend () -> Boolean = { true },
		trackingDiagnosticDataDeletion: suspend () -> Boolean = { true },
		postDatabaseDeletion: suspend (Long) -> Unit = { },
		activityRegistrationArbiterProvider: Provider<ActivityRegistrationArbiter>? = null,
		ambientStepsProviderLifecycleProvider: Provider<AmbientStepsProviderLifecycle>? = null,
		automaticControlRestorer: PostDeletionAutomaticControlRestorer =
			this.automaticControlRestorer,
		collectedDataLifecycleStore: CollectedDataLifecycleStore =
			this.collectedDataLifecycleStore,
		retentionAuthorityProducer: RetentionAuthorityProducer =
			completeRetentionAuthorityProducer(),
		sourcePolicyAuthorityBootstrapCoordinatorProvider:
			Provider<SourcePolicyAuthorityBootstrapCoordinator>? = null,
		directorySync: (File) -> Unit = {},
		markerDelete: (File) -> Boolean = File::delete,
		providerFenceTimeoutMs: Long = 30_000L,
		providerFenceScope: CoroutineScope =
			CoroutineScope(SupervisorJob() + Dispatchers.Default),
		appDatabaseDeletionOperation: (suspend (
			android.content.Context,
			CollectedDataDeletionOperation,
		) -> CollectedDataDeletionOperationEntity)? = null,
		appDatabaseDeletion: suspend (android.content.Context, Long, Long?, Long) -> Unit,
	) = DefaultCollectedDataDeletionService(
		context = context,
		pointsAwardedDao = pointsAwardedDao,
		exportPlanStore = exportPlanStore,
		writerQuiescer = writerQuiescer,
		collectedDataLifecycleStore = collectedDataLifecycleStore,
		startupDeletionBarrier = startupDeletionBarrier,
		activityRegistrationArbiterProvider = activityRegistrationArbiterProvider,
		purposeDeletionFencerProvider = ambientStepsProviderLifecycleProvider?.let { lifecycle ->
			Provider {
				TrackingPurposeDeletionFencer {
					val cleanup = lifecycle.get().closeForCollectedDataDeletion()
					if (cleanup.complete) {
						TrackingPurposeSettingsReconciliationResult.Complete(
							setOf(AmbientTrackingSource.STEPS),
						)
					} else {
						TrackingPurposeSettingsReconciliationResult.Debt(
							TrackingPurposeSettingsReconciliationDebt(
								listOf(
									TrackingPurposeSettingsReconciliationFailure(
										AmbientTrackingSource.STEPS,
										TrackingPurposeSettingsReconciliationFailureReason
											.RETIREMENT_FAILED,
									),
								),
							),
						)
					}
				}
			}
		},
		automaticControlRestorer = automaticControlRestorer,
		retentionAuthorityProducer = retentionAuthorityProducer,
		sourcePolicyAuthorityBootstrapCoordinatorProvider =
			sourcePolicyAuthorityBootstrapCoordinatorProvider,
		traceboxDataDeletion = traceboxDataDeletion,
		trackingDiagnosticDataDeletion = trackingDiagnosticDataDeletion,
		appDatabaseDeletion = appDatabaseDeletionOperation ?: { deletionContext, operation ->
				appDatabaseDeletion(
					deletionContext,
					operation.targetCollectedDataEpoch,
					operation.retainedFromMs,
					operation.deletedAtMs,
				)
				CollectedDataDeletionOperationEntity(
					operationId = operation.operationId,
					targetCollectedDataEpoch = operation.targetCollectedDataEpoch,
					retainedFromMs = operation.retainedFromMs,
					deletedAtMs = operation.deletedAtMs,
					phase = CollectedDataDeletionOperationEntity.PHASE_DATABASE_CLEARED,
					updatedAtMs = operation.deletedAtMs,
				).also { databaseOperations[operation.operationId] = it }
			},
		readDatabaseDeletionOperation = { _, operationId ->
			databaseOperations[operationId]
		},
		postDatabaseDeletion = { operation ->
			postDatabaseDeletion(operation.deletedAtMs)
			val current = requireNotNull(databaseOperations[operation.operationId])
			databaseOperations[operation.operationId] = current.copy(
				phase = CollectedDataDeletionOperationEntity.PHASE_WRITERS_REARMED,
			)
		},
		markerFile = markerFile,
		directorySync = directorySync,
		markerDelete = markerDelete,
		currentTimeMillis = { 1L },
		providerFenceTimeoutMs = providerFenceTimeoutMs,
		providerFenceScope = providerFenceScope,
	)

	private suspend fun roomRetentionProducer(
		database: AppDatabase,
		lifecycle: CollectedDataLifecycleStore,
		ambientStepsEnabled: Boolean,
		approvePolicy: Boolean = false,
	): RetentionAuthorityProducer {
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState())
		var effectiveTime = 1L
		var wallTimeMs = System.currentTimeMillis()
		val timeProvider = SourcePolicyEffectiveTimeProvider {
			wallTimeMs = maxOf(wallTimeMs + 1L, System.currentTimeMillis())
			SourcePolicyEffectiveTime(
				bootId = "post-delete-test-boot",
				elapsedRealtimeNanos = effectiveTime++,
				wallTimeMs = wallTimeMs,
			)
		}
		val policyRepository = RoomSourcePolicyRepository(database, timeProvider)
		policyRepository.bootstrapFromLegacy(
			TrackingParamsState(
				ambientStepsEnabled = ambientStepsEnabled,
				legacySettingsMigrationCompleted = true,
			),
		)
		val configStore = RetentionConfigStore(context, Dispatchers.Unconfined)
		val producer = DefaultRetentionAuthorityProducer(
			database = database,
			sourcePolicyRepository = policyRepository,
			retentionConfigStore = configStore,
			collectedDataLifecycleStore = lifecycle,
			effectiveTimeProvider = timeProvider,
		)
		if (approvePolicy) {
			val applied = configStore.updateWithApproval(
				block = { this },
				prepare = { stage ->
					producer.preparePendingConfiguration(
						stage.policy.configurationGeneration,
					)
				},
				approve = { stage ->
					producer.reconcilePendingConfiguration(
						stage.policy.configurationGeneration,
					)
				},
			)
			check(applied.approval !is RetentionConfigurationApprovalResult.Unavailable)
		}
		return producer
	}

	private suspend fun resetRetentionTestState() {
		Preferences(context).editSuspend {
			remove("autoCleanupOldData")
			remove("dataRetentionYears")
		}
		resetRetentionConfigForTests(context)
	}

	private suspend fun publishRoomDeletionEpoch(
		database: AppDatabase,
		epoch: Long,
		retainedFromMs: Long?,
		updatedAtMs: Long,
	) {
		database.withTransaction {
			database.ambientStepsFactRevisionDao().deleteAllRetentionAuthorities()
			database.ambientWifiFactDao().deleteAllRetentionAuthorities()
			database.ambientCellFactDao().deleteAllRetentionAuthorities()
			check(
				database.sourceEvidenceStateDao().updateAfterFullDeletion(
					epoch = epoch,
					retainedFromMs = retainedFromMs,
					deletedSourceEventHighWaterOrdinal = 0L,
					updatedAtMs = updatedAtMs,
				) == 1,
			)
		}
	}

	private fun appliedRegistrationResult() = ActivityRegistrationResult(
		status = ActivityRegistrationStatus.APPLIED,
		snapshot = ActivityRegistrationSnapshot(
			active = false,
			identity = null,
			owners = emptySet(),
			continuousRecognitionIntervalSeconds = null,
			transitions = emptySet(),
		),
	)

	private fun completeAmbientStepsCleanup() = AmbientStepsProviderCleanupResult(
		complete = true,
	)

	private fun completeRetentionAuthorityProducer(): RetentionAuthorityProducer =
		mockk {
			coEvery { reconcileCurrentSettings() } returns disabledRetentionResults()
		}

	private fun disabledRetentionResults(): List<RetentionAuthorityResult> = listOf(
		TrackingSourceComponent.STEPS,
		TrackingSourceComponent.WIFI,
		TrackingSourceComponent.CELL,
	).map { source ->
		RetentionAuthorityResult.Unchanged(
			source = source,
			scope = RetentionAuthorityScope.LIVE_AMBIENT,
			state = RetentionAuthorityState.REVOKED,
			approvalRevision = null,
		)
	}

	private fun retentionFailureResults(
		reason: RetentionAuthorityUnavailableReason,
	): List<RetentionAuthorityResult> =
		listOf(
			RetentionAuthorityResult.Unavailable(
				source = TrackingSourceComponent.STEPS,
				scope = RetentionAuthorityScope.LIVE_AMBIENT,
				reason = reason,
			),
		) + disabledRetentionResults().filter {
			it.source != TrackingSourceComponent.STEPS
		}

	private class MutableDeletionLifecycleStore : CollectedDataLifecycleStore {
		private val state = MutableStateFlow(CollectedDataLifecycleSnapshot(0L, null))

		override val snapshots: Flow<CollectedDataLifecycleSnapshot> = state

		override suspend fun snapshot(): CollectedDataLifecycleSnapshot = state.value

		override suspend fun beginFullDeletion(
			deletedAtMs: Long,
		): CollectedDataLifecycleSnapshot = state.value.copy(
			epoch = state.value.epoch + 1L,
			retainedFromMs = deletedAtMs,
		).also { state.value = it }

		override suspend fun advanceRetainedFrom(
			retainedFromMs: Long,
		): CollectedDataLifecycleSnapshot = state.value.copy(
			retainedFromMs = maxOf(state.value.retainedFromMs ?: retainedFromMs, retainedFromMs),
		).also { state.value = it }
	}

	private companion object {
		val RETIRED_DATABASE_NAMES = listOf(
			LEGACY_DATABASE_NAME,
			"stats_database",
			"challenge_database",
		)
	}
}
