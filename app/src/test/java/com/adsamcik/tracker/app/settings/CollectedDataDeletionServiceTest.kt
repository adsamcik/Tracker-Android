package com.adsamcik.tracker.app.settings

import android.app.Application
import android.database.sqlite.SQLiteException
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
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleSnapshot
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import javax.inject.Provider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
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
	private val pointsAwardedDao: PointsAwardedDao = mockk()
	private val exportPlanStore: ExportPlanStore = mockk()
	private val writerQuiescer: CollectedDataWriterQuiescer = mockk()
	private val collectedDataLifecycleStore: CollectedDataLifecycleStore = mockk()
	private val automaticControlRestorer: PostDeletionAutomaticControlRestorer = mockk()
	private lateinit var startupDeletionBarrier: TrackingStartupDeletionBarrier

	@Before
	fun setUp() {
		context = ApplicationProvider.getApplicationContext()
		markerFile = File(context.cacheDir, "collected-data-deletion-test.pending")
		startupDeletionBarrier = TrackingStartupDeletionBarrier()
		markerFile.delete()
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
		coEvery { collectedDataLifecycleStore.beginFullDeletion(any()) } returns
			CollectedDataLifecycleSnapshot(epoch = 1L, retainedFromMs = 1L)
	}

	@After
	fun tearDown() {
		markerFile.delete()
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
		coVerify(exactly = 1) { collectedDataLifecycleStore.beginFullDeletion(any()) }
		coVerifyOrder {
			collectedDataLifecycleStore.beginFullDeletion(any())
			writerQuiescer.quiesce()
			writerQuiescer.quiesce()
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

		val failure = runCatching {
			firstAttempt.deleteAll()
		}.exceptionOrNull()
		failure.shouldBeInstanceOf<SQLiteException>()
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
		coVerify(exactly = 2) { collectedDataLifecycleStore.beginFullDeletion(any()) }
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
	fun `writer quiescence failure keeps the durable marker and startup barrier closed`() = runTest {
		coEvery { writerQuiescer.quiesce() } throws DatabaseMigrationBackupException(
			"writer cancellation timed out",
		)
		var appDeletionCount = 0
		val service = createService { _, _, _, _ -> appDeletionCount++ }

		runCatching { service.deleteAll() }
			.exceptionOrNull()
			.shouldBeInstanceOf<DatabaseMigrationBackupException>()

		markerFile.exists() shouldBe true
		startupDeletionBarrier.isClosed shouldBe true
		appDeletionCount shouldBe 0
		verify(exactly = 0) { pointsAwardedDao.deleteAll() }
		verify(exactly = 0) { writerQuiescer.resume() }
		verify(exactly = 0) { automaticControlRestorer.schedule(any()) }
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
		coEvery { arbiter.closeForCollectedDataDeletion() } coAnswers {
			operations += "activity-close"
			appliedRegistrationResult()
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
		) { _, _, _, _ -> operations += "delete" }

		val deletion = async { service.deleteAll() }
		runCurrent()
		operations shouldBe listOf("activity-close", "writer-quiesce")

		allowOperationToFinish.complete(Unit)
		admittedRecovery.await()
		deletion.await()

		operations shouldBe listOf(
			"activity-close",
			"writer-quiesce",
			"activity-resume",
			"writer-resume",
			"activity-close",
			"writer-quiesce",
			"delete",
		)
		coVerify(exactly = 2) { arbiter.closeForCollectedDataDeletion() }
		coVerify(exactly = 2) { writerQuiescer.quiesce() }
	}

	@Test
	fun `cold pending deletion removes retired vaults before resolving Room-backed arbiter`() = runTest {
		markerFile.writeText("pending")
		val arbiter = mockk<ActivityRegistrationArbiter>()
		val result = appliedRegistrationResult()
		coEvery { arbiter.closeForCollectedDataDeletion() } returns result
		var providerResolutions = 0
		val provider = Provider {
			providerResolutions++
			RETIRED_DATABASE_NAMES.forEach { databaseName ->
				context.getDatabasePath(databaseName).exists() shouldBe false
			}
			arbiter
		}
		val service = createService(
			activityRegistrationArbiterProvider = provider,
		) { _, _, _, _ -> }

		service.reconcilePendingDeletion()

		providerResolutions shouldBe 1
		coVerify(exactly = 2) { arbiter.closeForCollectedDataDeletion() }
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
	fun `marker durability failure never closes the process gate`() = runTest {
		val service = createService(
			directorySync = { error("fsync failed") },
		) { _, _, _, _ -> error("database deletion must not start") }

		runCatching { service.deleteAll() }.exceptionOrNull()
			.shouldBeInstanceOf<DatabaseMigrationBackupException>()

		startupDeletionBarrier.isClosed shouldBe false
		coVerify(exactly = 0) { collectedDataLifecycleStore.beginFullDeletion(any()) }
	}

	@Test
	fun `new deletion attempt preserves an existing durable marker`() = runTest {
		val originalMarker = "already-pending"
		markerFile.writeText(originalMarker)
		val service = createService { _, _, _, _ ->
			throw SQLiteException("interrupted again")
		}

		runCatching { service.deleteAll() }
			.exceptionOrNull()
			.shouldBeInstanceOf<SQLiteException>()

		markerFile.exists() shouldBe true
		markerFile.readText() shouldBe originalMarker
		File(markerFile.parentFile, "${markerFile.name}.tmp").exists() shouldBe false
	}

	@Test
	fun `complete deletion orders Tracker data before Tracebox diagnostics`() = runTest {
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
		) { _, _, _, _ ->
			operations += "tracker"
		}

		service.deleteAll()

		operations shouldBe listOf("tracker", "writer-rearm", "watermarks", "tracebox")
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

		runCatching { service.deleteAll() }.exceptionOrNull()
			.shouldBeInstanceOf<IllegalStateException>()

		operations shouldBe listOf("tracker", "writer-rearm")
		markerFile.exists() shouldBe true
		startupDeletionBarrier.isClosed shouldBe true
		verify(exactly = 0) { automaticControlRestorer.schedule(any()) }

		rearmComplete = true
		service.reconcilePendingDeletion()

		operations shouldBe listOf(
			"tracker",
			"writer-rearm",
			"tracker",
			"writer-rearm",
		)
		markerFile.exists() shouldBe false
		startupDeletionBarrier.isClosed shouldBe false
		verify(exactly = 1) { automaticControlRestorer.schedule(1L) }
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

			runCatching { service.deleteAll() }
				.exceptionOrNull()
				.shouldBeInstanceOf<DatabaseMigrationBackupException>()
			operations shouldBe listOf("tracker", "tracebox")
			markerFile.exists() shouldBe true

			traceboxComplete = true
			service.reconcilePendingDeletion()

			operations shouldBe listOf(
				"tracker",
				"tracebox",
				"tracker",
				"tracebox",
			)
			markerFile.exists() shouldBe false
		}

	@Test
	fun `failure after writer rearm repeats deletion and advances writer authority again`() = runTest {
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

		runCatching { service.deleteAll() }.exceptionOrNull()
			.shouldBeInstanceOf<DatabaseMigrationBackupException>()
		markerFile.exists() shouldBe true
		startupDeletionBarrier.isClosed shouldBe true

		traceboxComplete = true
		service.reconcilePendingDeletion()

		operations shouldBe listOf(
			"tracker",
			"writer-rearm-1",
			"watermarks",
			"tracebox",
			"tracker",
			"writer-rearm-2",
			"watermarks",
			"tracebox",
		)
		rearmGeneration shouldBe 2
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

		runCatching { service.deleteAll() }
			.exceptionOrNull()
			.shouldBeInstanceOf<DatabaseMigrationBackupException>()

		deletionAttempts shouldBe 1
		markerFile.exists() shouldBe true

		shouldFail = false
		service.reconcilePendingDeletion()

		deletionAttempts shouldBe 2
		markerFile.exists() shouldBe false
	}

	private fun createService(
		traceboxDataDeletion: suspend () -> Boolean = { true },
		postDatabaseDeletion: suspend (Long) -> Unit = { },
		activityRegistrationArbiterProvider: Provider<ActivityRegistrationArbiter>? = null,
		automaticControlRestorer: PostDeletionAutomaticControlRestorer =
			this.automaticControlRestorer,
		directorySync: (File) -> Unit = {},
		appDatabaseDeletion: suspend (android.content.Context, Long, Long?, Long) -> Unit,
	) = DefaultCollectedDataDeletionService(
		context = context,
		pointsAwardedDao = pointsAwardedDao,
		exportPlanStore = exportPlanStore,
		writerQuiescer = writerQuiescer,
		collectedDataLifecycleStore = collectedDataLifecycleStore,
		startupDeletionBarrier = startupDeletionBarrier,
		activityRegistrationArbiterProvider = activityRegistrationArbiterProvider,
		automaticControlRestorer = automaticControlRestorer,
		traceboxDataDeletion = traceboxDataDeletion,
		appDatabaseDeletion = appDatabaseDeletion,
		postDatabaseDeletion = postDatabaseDeletion,
		markerFile = markerFile,
		directorySync = directorySync,
	)

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

	private companion object {
		val RETIRED_DATABASE_NAMES = listOf(
			LEGACY_DATABASE_NAME,
			"stats_database",
			"challenge_database",
		)
	}
}
