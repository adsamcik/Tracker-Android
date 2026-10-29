package com.adsamcik.tracker.app.startup

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.adsamcik.tracker.shared.base.database.ActiveDatabaseBlockReason
import com.adsamcik.tracker.shared.base.database.ActiveDatabaseOpenBlockedException
import com.adsamcik.tracker.shared.base.database.ActiveDatabaseOpenRetryableException
import com.adsamcik.tracker.shared.base.database.ActiveDatabaseRetryableReason
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.legacy.LegacyDatabaseInfo
import com.adsamcik.tracker.shared.base.database.legacy.LegacyDatabaseRepository
import com.adsamcik.tracker.shared.base.database.legacy.LegacyDatabaseState
import com.adsamcik.tracker.shared.base.database.legacy.LegacyImportStatus
import com.adsamcik.tracker.shared.base.database.legacy.LegacyV26Importer
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleSnapshot
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityOperationLease
import io.mockk.coEvery
import io.mockk.coVerify
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import javax.inject.Provider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class LegacyDatabaseUpgradeCoordinatorTest {
	private val rawDatabase = mockk<SupportSQLiteDatabase>()
	private val openHelper = mockk<SupportSQLiteOpenHelper> {
		every { writableDatabase } returns rawDatabase
	}
	private val database = mockk<AppDatabase> {
		every { openHelper } returns this@LegacyDatabaseUpgradeCoordinatorTest.openHelper
	}
	private val databaseProvider = Provider { database }
	private val repository = mockk<LegacyDatabaseRepository>(relaxed = true)
	private val importer = mockk<LegacyV26Importer>(relaxed = true)
	private val lifecycle = mockk<CollectedDataLifecycleStore> {
		coEvery { snapshot() } returns CollectedDataLifecycleSnapshot(4L, 100L)
	}

	@Test
	fun `clean start still opens and validates the active target`() = runTest {
		every { repository.inspect() } returns null
		val coordinator = coordinator()

		coordinator.ensureReady() shouldBe LegacyDatabaseStartupResult.Ready
		coordinator.ensureReady() shouldBe LegacyDatabaseStartupResult.Ready

		verify(exactly = 1) { openHelper.writableDatabase }
		coordinator.isReady() shouldBe true
	}

	@Test
	fun `legacy start requires atomic completion marker before becoming ready`() = runTest {
		val source = legacyInfo()
		every { repository.hasSourceDatabase() } returns true
		every { repository.inspect() } returns source
		every { repository.currentState() } returns state(source, LegacyImportStatus.RUNNING)
		val cursor = mockk<Cursor>(relaxed = true) {
			every { moveToFirst() } returns true
		}
		every { rawDatabase.query(any<String>(), any<Array<out Any?>>()) } returns cursor
		val coordinator = coordinator()

		coordinator.ensureReady() shouldBe LegacyDatabaseStartupResult.Ready

		verify { repository.markComplete() }
		coordinator.isReady() shouldBe true
	}

	@Test
	fun `legacy start rejects a target without the atomic completion marker`() = runTest {
		val source = legacyInfo()
		every { repository.hasSourceDatabase() } returns true
		every { repository.inspect() } returns source
		every { repository.currentState() } returns state(source, LegacyImportStatus.RUNNING)
		val cursor = mockk<Cursor>(relaxed = true) {
			every { moveToFirst() } returns false
		}
		every { rawDatabase.query(any<String>(), any<Array<out Any?>>()) } returns cursor
		val coordinator = coordinator()

		val result = coordinator.ensureReady()

		result.shouldBeInstanceOf<LegacyDatabaseStartupResult.Failed>().message shouldBe
			"The v27 database opened without a completed legacy import marker"
		verify(exactly = 0) { repository.markComplete() }
		coVerify(exactly = 1) { importer.importWithLifecycle(database, 4L, 100L, any()) }
		coordinator.isReady() shouldBe false
	}

	@Test
	fun `inspection failure is reported durably instead of escaping startup`() = runTest {
		val failure = IllegalStateException("broken vault")
		every { repository.hasSourceDatabase() } returns true
		every { repository.inspect() } throws failure
		val coordinator = coordinator()

		val result = coordinator.ensureReady()

		result.shouldBeInstanceOf<LegacyDatabaseStartupResult.Failed>().apply {
			message shouldBe "broken vault"
			requiresExplicitRetry shouldBe true
		}
		verify { repository.markFailed(failure) }
		verify(exactly = 0) { openHelper.writableDatabase }
		coordinator.isReady() shouldBe false
	}

	@Test
	fun `retry after source removal reopens the same Hilt database`() = runTest {
		every { repository.inspect() } returns null
		val coordinator = coordinator()

		coordinator.ensureReady(retry = true) shouldBe LegacyDatabaseStartupResult.Ready

		verify { repository.resetForRetry() }
		verify { openHelper.writableDatabase }
	}

	@Test
	fun `stale development v28 is typed and does not mutate legacy recovery state`() = runTest {
		every { repository.inspect() } returns null
		every { openHelper.writableDatabase } throws ActiveDatabaseOpenBlockedException(
			ActiveDatabaseBlockReason.STALE_DEVELOPMENT_V28,
		)
		val coordinator = coordinator()

		coordinator.ensureReady() shouldBe LegacyDatabaseStartupResult.ActiveDatabaseBlocked(
			ActiveDatabaseBlockReason.STALE_DEVELOPMENT_V28,
		)

		verify(exactly = 0) { repository.markFailed(any()) }
		verify(exactly = 0) { repository.markComplete() }
		coordinator.isReady() shouldBe false
	}

	@Test
	fun `database contention is retryable and does not mutate legacy recovery state`() = runTest {
		every { repository.inspect() } returns null
		every { openHelper.writableDatabase } throws ActiveDatabaseOpenRetryableException(
			ActiveDatabaseRetryableReason.CONTENDED,
		)
		val coordinator = coordinator()

		coordinator.ensureReady() shouldBe LegacyDatabaseStartupResult.ActiveDatabaseRetryable(
			ActiveDatabaseRetryableReason.CONTENDED,
		)

		verify(exactly = 0) { repository.markFailed(any()) }
		verify(exactly = 0) { repository.markComplete() }
		coordinator.isReady() shouldBe false
	}

	@Test
	@OptIn(ExperimentalCoroutinesApi::class)
	fun `large import retains its lifecycle lease beyond short authority mutation deadlines`() = runTest {
		val lease = RetentionAuthorityOperationLease()
		val entered = CompletableDeferred<Unit>()
		val finishImport = CompletableDeferred<Unit>()
		var committed = false
		var nextLifecycleEntered = false
		val source = legacyInfo()
		every { repository.inspect() } returns source
		every { repository.currentState() } returns state(source, LegacyImportStatus.RUNNING)
		val marker = mockk<Cursor>(relaxed = true) { every { moveToFirst() } answers { committed } }
		every { rawDatabase.query(any<String>(), any<Array<out Any?>>()) } returns marker
		coEvery { importer.importWithLifecycle(database, 4L, 100L, any()) } coAnswers {
			entered.complete(Unit)
			finishImport.await()
			committed = true
		}
		val startup = async { coordinator(lease).ensureReady() }
		entered.await()
		val nextLifecycle = async { lease.withPermit { nextLifecycleEntered = true } }

		advanceTimeBy(31_000L)
		runCurrent()
		startup.isCompleted shouldBe false
		nextLifecycle.isCompleted shouldBe false
		nextLifecycleEntered shouldBe false
		verify(exactly = 0) { repository.markComplete() }

		finishImport.complete(Unit)
		startup.await() shouldBe LegacyDatabaseStartupResult.Ready
		nextLifecycle.await()
		nextLifecycleEntered shouldBe true
		verify(exactly = 1) { repository.markComplete() }
		coVerify(exactly = 1) { importer.importWithLifecycle(database, 4L, 100L, any()) }
	}

	private fun coordinator(lease: RetentionAuthorityOperationLease = RetentionAuthorityOperationLease()) =
		LegacyDatabaseUpgradeCoordinator(
		databaseProvider,
		repository,
		importer,
		lifecycle,
		lease,
	)

	private fun legacyInfo() = LegacyDatabaseInfo(File("legacy.db"), 26, 100L)

	private fun state(
		database: LegacyDatabaseInfo,
		status: LegacyImportStatus,
	) = LegacyDatabaseState(
		database = database,
		importStatus = status,
		report = null,
		lastError = null,
		externallyExported = false,
	)
}
