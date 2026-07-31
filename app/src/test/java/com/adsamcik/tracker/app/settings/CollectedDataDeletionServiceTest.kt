package com.adsamcik.tracker.app.settings

import android.app.Application
import android.database.sqlite.SQLiteException
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.impexp.exporter.automation.ExportPlanStore
import com.adsamcik.tracker.impexp.exporter.proto.ExportPlansProto
import com.adsamcik.tracker.points.database.PointsAwardedDao
import com.adsamcik.tracker.shared.base.database.migration.DatabaseMigrationBackupException
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
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CollectedDataDeletionServiceTest {
	private lateinit var context: Application
	private lateinit var markerFile: File
	private val pointsAwardedDao: PointsAwardedDao = mockk()
	private val exportPlanStore: ExportPlanStore = mockk()
	private val writerQuiescer: CollectedDataWriterQuiescer = mockk()
	private val collectedDataLifecycleStore: CollectedDataLifecycleStore = mockk()

	@Before
	fun setUp() {
		context = ApplicationProvider.getApplicationContext()
		markerFile = File(context.cacheDir, "collected-data-deletion-test.pending")
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
			appDeletionCount++
			capturedEpoch = epoch
			capturedRetainedFromMs = retainedFromMs
		}

		service.deleteAll()

		verify(exactly = 1) { pointsAwardedDao.deleteAll() }
		coVerify(exactly = 1) { exportPlanStore.resetAllWatermarks() }
		coVerify(exactly = 1) { writerQuiescer.quiesce() }
		coVerify(exactly = 1) { collectedDataLifecycleStore.beginFullDeletion(any()) }
		coVerifyOrder {
			collectedDataLifecycleStore.beginFullDeletion(any())
			writerQuiescer.quiesce()
		}
		verify(exactly = 1) { writerQuiescer.resume() }
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

		var resumedAppDeletionCount = 0
		val resumed = createService { _, _, _, _ ->
			resumedAppDeletionCount++
		}
		resumed.reconcilePendingDeletion()

		verify(exactly = 2) { pointsAwardedDao.deleteAll() }
		coVerify(exactly = 2) { writerQuiescer.quiesce() }
		coVerify(exactly = 2) { collectedDataLifecycleStore.beginFullDeletion(any()) }
		verify(exactly = 2) { writerQuiescer.resume() }
		coVerify(exactly = 1) { exportPlanStore.resetAllWatermarks() }
		resumedAppDeletionCount shouldBe 1
		markerFile.exists() shouldBe false
		RETIRED_DATABASE_NAMES.forEach {
			context.getDatabasePath(it).exists() shouldBe false
		}
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
			traceboxDataDeletion = {
				operations += "tracebox"
				true
			},
		) { _, _, _, _ ->
			operations += "tracker"
		}

		service.deleteAll()

		operations shouldBe listOf("tracker", "watermarks", "tracebox")
		markerFile.exists() shouldBe false
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
		appDatabaseDeletion: suspend (android.content.Context, Long, Long?, Long) -> Unit,
	) = DefaultCollectedDataDeletionService(
		context = context,
		pointsAwardedDao = pointsAwardedDao,
		exportPlanStore = exportPlanStore,
		writerQuiescer = writerQuiescer,
		collectedDataLifecycleStore = collectedDataLifecycleStore,
		traceboxDataDeletion = traceboxDataDeletion,
		appDatabaseDeletion = appDatabaseDeletion,
		markerFile = markerFile,
		directorySync = {},
	)

	private companion object {
		val RETIRED_DATABASE_NAMES = listOf(
			"stats_database",
			"challenge_database",
		)
	}
}
