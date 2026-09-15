package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.ImportedStepsDao
import com.adsamcik.tracker.shared.base.database.steps.imported.ImportedStepsAdmissionRows
import com.adsamcik.tracker.shared.base.database.steps.imported.ImportedStepsReadFailure
import com.adsamcik.tracker.shared.base.database.steps.imported.ImportedStepsRetainedRead
import com.adsamcik.tracker.shared.base.database.steps.imported.ImportedStepsRetainedReader
import com.adsamcik.tracker.shared.base.database.steps.imported.RetainedImportedStepsEntry
import com.adsamcik.tracker.stats.api.repository.ExportPortableStepsRequest
import com.adsamcik.tracker.stats.api.repository.ExportPortableStepsResult
import com.adsamcik.tracker.stats.api.repository.PortableStepsCaptureCoverage
import com.adsamcik.tracker.stats.api.repository.PortableStepsCompletenessV1
import com.adsamcik.tracker.stats.api.repository.PortableStepsDeletionScopeDigest
import com.adsamcik.tracker.stats.api.repository.PortableStepsEntryV1
import com.adsamcik.tracker.stats.api.repository.PortableStepsExportUnverifiableReason
import com.adsamcik.tracker.stats.api.repository.PortableStepsFactCoverage
import com.adsamcik.tracker.stats.api.repository.PortableStepsFactV1
import com.adsamcik.tracker.stats.api.repository.PortableStepsManifestV1
import com.adsamcik.tracker.stats.api.repository.PortableStepsOpaqueIdentity
import com.adsamcik.tracker.stats.api.repository.PortableStepsProviderCoverage
import com.adsamcik.tracker.stats.api.repository.PortableStepsRunV1
import com.adsamcik.tracker.stats.api.repository.PortableStepsSessionMode
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** Shared-reader authentication has its own Room tests; this seam verifies consumer read budgeting. */
class ImportedStepsProductReaderTest {
	@Test
	fun `typed recent read exposes entry failure while legacy list preserves omission`() = runTest {
		val database = mockk<AppDatabase>()
		val dao = mockk<ImportedStepsDao>()
		val retained = mockk<ImportedStepsRetainedReader>()
		val wire = entry(1, large = false)
		val metadata = ImportedStepsAdmissionRows.entry(wire, epoch = 0L, ownerGeneration = 7L)
		every { database.importedStepsDao() } returns dao
		coEvery { dao.recentEntries(1) } returns listOf(metadata)
		coEvery { retained.readEntriesInTransaction(listOf(metadata.identity)) } returnsMany
			listOf(
				ImportedStepsRetainedRead.Ready(
					entries = emptyList(),
					unverifiableEntries = mapOf(
						metadata.identity to ImportedStepsReadFailure.INTEGRITY,
					),
				),
				ImportedStepsRetainedRead.Unverifiable(ImportedStepsReadFailure.INTEGRITY),
			)
		val reader = ImportedStepsProductReader(database, retained)

		reader.recentSourceAwareInTransaction(1) shouldBe
			ImportedStepsRecentRead.Unavailable(ImportedStepsReadFailure.INTEGRITY)
		reader.recentInTransaction(1) shouldBe emptyList()
		coVerify(exactly = 2) {
			retained.readEntriesInTransaction(listOf(metadata.identity))
		}
	}

	@Test
	fun `typed recent read rejects an incomplete successful batch`() = runTest {
		val database = mockk<AppDatabase>()
		val dao = mockk<ImportedStepsDao>()
		val retained = mockk<ImportedStepsRetainedReader>()
		val wire = entry(1, large = false)
		val metadata = ImportedStepsAdmissionRows.entry(wire, epoch = 0L, ownerGeneration = 7L)
		every { database.importedStepsDao() } returns dao
		coEvery { dao.recentEntries(1) } returns listOf(metadata)
		coEvery { retained.readEntriesInTransaction(listOf(metadata.identity)) } returns
			ImportedStepsRetainedRead.Ready(emptyList())

		ImportedStepsProductReader(database, retained).recentSourceAwareInTransaction(1) shouldBe
			ImportedStepsRecentRead.Unavailable(ImportedStepsReadFailure.MISSING)
	}

	@Test
	fun `export rejects an exhausted batch before requesting the next authenticated entry batch`() = runTest {
		val database = mockk<AppDatabase>()
		val dao = mockk<ImportedStepsDao>()
		val retained = mockk<ImportedStepsRetainedReader>()
		val entries = (1..33).map { entry(it, large = it == 1) }
		val metadata = entries.map { ImportedStepsAdmissionRows.entry(it, epoch = 0L, ownerGeneration = 7L) }
		val firstIds = metadata.take(32).map { it.identity }
		val verified = entries.take(32).map { wire ->
			mockk<RetainedImportedStepsEntry> { every { portable } returns wire }
		}
		every { database.importedStepsDao() } returns dao
		coEvery { dao.entriesOverlapping(any(), any(), any()) } returns metadata
		coEvery { retained.readEntriesInTransaction(firstIds) } returns ImportedStepsRetainedRead.Ready(verified)

		ImportedStepsProductReader(database, retained).exportInTransaction(ExportPortableStepsRequest(0L, 1_000_000L)) shouldBe
			PortableStepsSnapshot.Outcome(ExportPortableStepsResult.Unverifiable(
				PortableStepsExportUnverifiableReason.DEPENDENCY_OVERFLOW,
			))
		coVerify(exactly = 1) { retained.readEntriesInTransaction(firstIds) }
		confirmVerified(retained)
	}

	private fun entry(index: Int, large: Boolean): PortableStepsEntryV1 {
		val runs = (0 until if (large) { 8 } else { 1 }).map { runIndex ->
			val start = index * 10_000L + runIndex * 2_048L
			val id = index * 100 + runIndex
			PortableStepsRunV1(
				identity('b', id), PortableStepsDeletionScopeDigest(identity('d', id).value.removePrefix("sha256:")),
				start, start + 2_048L, "UTC", listOf(PortableStepsManifestV1(1L, start, 1L, 0L)),
				PortableStepsCompletenessV1(
					PortableStepsCaptureCoverage.WHOLE_RUN, PortableStepsProviderCoverage.COMPLETE, true, true, false,
				),
				if (large) {
					(0 until 2_048).map { ordinal ->
						PortableStepsFactV1.create(
							identity('c', id * 10_000 + ordinal), 1L, start + ordinal, start + ordinal + 1L,
							0L, PortableStepsFactCoverage.COVERED, 1L,
						)
					}
				} else { emptyList() },
			)
		}
		return PortableStepsEntryV1.create(
			identity('a', index), PortableStepsSessionMode.MANUAL, runs.first().startTimeMs, runs.last().endTimeMs, runs,
		)
	}

	private fun identity(prefix: Char, value: Int) = PortableStepsOpaqueIdentity(
		"sha256:$prefix${value.toString(16).padStart(63, '0')}",
	)
}
