package com.adsamcik.tracker.app.startup

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.adsamcik.tracker.app.settings.CollectedDataDeletionService
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.legacy.LegacyDatabaseInfo
import com.adsamcik.tracker.shared.base.database.legacy.LegacyDatabaseRepository
import com.adsamcik.tracker.shared.base.database.legacy.LegacyDatabaseState
import com.adsamcik.tracker.shared.base.database.legacy.LegacyImportStatus
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.verify
import java.io.File
import javax.inject.Provider
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
	private val collectedDataDeletionService = mockk<CollectedDataDeletionService> {
		coEvery { reconcilePendingDeletion() } just Runs
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
		coordinator.isReady() shouldBe false
	}

	@Test
	fun `inspection failure is reported durably instead of escaping startup`() = runTest {
		val failure = IllegalStateException("broken vault")
		every { repository.inspect() } throws failure
		val coordinator = coordinator()

		val result = coordinator.ensureReady()

		result.shouldBeInstanceOf<LegacyDatabaseStartupResult.Failed>().message shouldBe "broken vault"
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
	fun `pending full deletion failure blocks inspection and active database open`() = runTest {
		val failure = IllegalStateException("deletion remains pending")
		coEvery { collectedDataDeletionService.reconcilePendingDeletion() } throws failure
		val coordinator = coordinator()

		val result = coordinator.ensureReady()

		result.shouldBeInstanceOf<LegacyDatabaseStartupResult.Failed>().message shouldBe
			"deletion remains pending"
		coVerify(exactly = 1) { collectedDataDeletionService.reconcilePendingDeletion() }
		verify(exactly = 0) { repository.inspect() }
		verify(exactly = 0) { openHelper.writableDatabase }
	}

	private fun coordinator() = LegacyDatabaseUpgradeCoordinator(
		databaseProvider,
		repository,
		collectedDataDeletionService,
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
