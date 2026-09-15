package com.adsamcik.tracker.app.maintenance

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.CellCapturedRetentionBlockedReason
import com.adsamcik.tracker.shared.base.database.CellCapturedRetentionResult
import com.adsamcik.tracker.shared.base.database.dao.SourceEvidenceStateDao
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test

class CellCapturedRetentionServiceTest {
	@Test
	fun `exact source evidence authority and cutoff reach one bounded operation`() = runTest {
		val database = databaseWithEvidence()
		var calls = 0
		val service = CellCapturedRetentionService { actualDatabase, cutoff, epoch, highWater, markedAt ->
			calls += 1
			assertEquals(database, actualDatabase)
			assertEquals(CUTOFF_MS, cutoff)
			assertEquals(EPOCH, epoch)
			assertEquals(DELETION_HIGH_WATER, highWater)
			assertEquals(MARKED_AT_MS, markedAt)
			CellCapturedRetentionResult.NoChange
		}

		assertEquals(
			CellCapturedRetentionResult.NoChange,
			service.prune(database, CUTOFF_MS, MARKED_AT_MS),
		)
		assertEquals(1, calls)
	}

	@Test
	fun `pruned no-change and blocked results remain unchanged`() = runTest {
		val expectedResults = listOf(
			CellCapturedRetentionResult.Pruned(logicalFactCount = 2, revisionCount = 3),
			CellCapturedRetentionResult.NoChange,
			CellCapturedRetentionResult.Blocked(
				CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE,
			),
		)

		expectedResults.forEach { expected ->
			val database = databaseWithEvidence()
			val service = CellCapturedRetentionService { _, _, _, _, _ -> expected }

			assertEquals(expected, service.prune(database, CUTOFF_MS, MARKED_AT_MS))
		}
	}

	@Test
	fun `missing source evidence is blocked without invoking maintenance`() = runTest {
		val database: AppDatabase = mockk()
		val evidenceDao: SourceEvidenceStateDao = mockk()
		every { database.sourceEvidenceStateDao() } returns evidenceDao
		coEvery { evidenceDao.get() } returns null
		var calls = 0
		val service = CellCapturedRetentionService { _, _, _, _, _ ->
			calls += 1
			CellCapturedRetentionResult.NoChange
		}

		assertEquals(
			CellCapturedRetentionResult.Blocked(
				CellCapturedRetentionBlockedReason.SOURCE_EVIDENCE_AUTHORITY_CHANGED,
			),
			service.prune(database, CUTOFF_MS, MARKED_AT_MS),
		)
		assertEquals(0, calls)
	}

	@Test
	fun `maintenance cancellation propagates`() = runTest {
		val database = databaseWithEvidence()
		val service = CellCapturedRetentionService { _, _, _, _, _ ->
			throw CancellationException("cancel-cell-retention")
		}

		assertFailsWith<CancellationException> {
			service.prune(database, CUTOFF_MS, MARKED_AT_MS)
		}
	}

	private fun databaseWithEvidence(): AppDatabase {
		val database: AppDatabase = mockk()
		val evidenceDao: SourceEvidenceStateDao = mockk()
		every { database.sourceEvidenceStateDao() } returns evidenceDao
		coEvery { evidenceDao.get() } returns SourceEvidenceState(
			collectedDataEpoch = EPOCH,
			retainedFromMs = CUTOFF_MS,
			deletedSourceEventHighWaterOrdinal = DELETION_HIGH_WATER,
		)
		return database
	}

	private companion object {
		const val CUTOFF_MS = 1_000L
		const val MARKED_AT_MS = 2_000L
		const val EPOCH = 7L
		const val DELETION_HIGH_WATER = 11L
	}
}
