package com.adsamcik.tracker.tracker.source.wifi

import com.adsamcik.tracker.shared.base.database.AppDatabase
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

class WifiCapturedRetentionServiceTest {
	@Test
	fun `exact evidence snapshot and cutoff reach one authenticated operation`() = runTest {
		val database = databaseWithEvidence()
		var calls = 0
		val service = WifiCapturedRetentionService { actualDatabase, cutoff, epoch, highWater, markedAt ->
			calls += 1
			assertEquals(database, actualDatabase)
			assertEquals(CUTOFF_MS, cutoff)
			assertEquals(EPOCH, epoch)
			assertEquals(DELETION_HIGH_WATER, highWater)
			assertEquals(MARKED_AT_MS, markedAt)
			WifiCapturedRetentionResult.NoChange
		}

		assertEquals(
			WifiCapturedRetentionResult.NoChange,
			service.prune(database, CUTOFF_MS, MARKED_AT_MS),
		)
		assertEquals(1, calls)
	}

	@Test
	fun `pruned no-change and blocked results remain typed and unchanged`() = runTest {
		val expectedResults = listOf(
			WifiCapturedRetentionResult.Pruned(logicalFactCount = 2, revisionCount = 3),
			WifiCapturedRetentionResult.NoChange,
			WifiCapturedRetentionResult.Blocked(
				WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE,
			),
		)

		expectedResults.forEach { expected ->
			val database = databaseWithEvidence()
			var calls = 0
			val service = WifiCapturedRetentionService { _, _, _, _, _ ->
				calls += 1
				expected
			}

			assertEquals(expected, service.prune(database, CUTOFF_MS, MARKED_AT_MS))
			assertEquals(1, calls)
		}
	}

	@Test
	fun `missing source evidence blocks without invoking maintenance`() = runTest {
		val database: AppDatabase = mockk()
		val evidenceDao: SourceEvidenceStateDao = mockk()
		every { database.sourceEvidenceStateDao() } returns evidenceDao
		coEvery { evidenceDao.get() } returns null
		var calls = 0
		val service = WifiCapturedRetentionService { _, _, _, _, _ ->
			calls += 1
			WifiCapturedRetentionResult.NoChange
		}

		assertEquals(
			WifiCapturedRetentionResult.Blocked(
				WifiCapturedRetentionBlockedReason.SOURCE_EVIDENCE_AUTHORITY_CHANGED,
			),
			service.prune(database, CUTOFF_MS, MARKED_AT_MS),
		)
		assertEquals(0, calls)
	}

	@Test
	fun `storage and cancellation failures propagate to the worker boundary`() = runTest {
		val database = databaseWithEvidence()
		val storageFailure = IllegalStateException("wifi-retention-storage")
		val failingService = WifiCapturedRetentionService { _, _, _, _, _ ->
			throw storageFailure
		}
		assertEquals(
			storageFailure,
			assertFailsWith<IllegalStateException> {
				failingService.prune(database, CUTOFF_MS, MARKED_AT_MS)
			},
		)

		val cancelledService = WifiCapturedRetentionService { _, _, _, _, _ ->
			throw CancellationException("cancel-wifi-retention")
		}
		assertFailsWith<CancellationException> {
			cancelledService.prune(database, CUTOFF_MS, MARKED_AT_MS)
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
