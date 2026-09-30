package com.adsamcik.tracker.stats.data.repository

import android.app.Application
import java.io.IOException
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AmbientRadioRetentionDecision
import com.adsamcik.tracker.shared.base.database.AmbientWifiRetentionCommand
import com.adsamcik.tracker.shared.base.database.AmbientWifiRetentionResult
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.applyAmbientCellRetentionDecision
import com.adsamcik.tracker.shared.base.database.applyAmbientWifiRetentionDecision
import com.adsamcik.tracker.shared.base.database.clearAmbientWifiProductPreservingReplayFootprints
import com.adsamcik.tracker.shared.base.database.data.AmbientCellFactIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiFactIntegrity
import com.adsamcik.tracker.shared.base.database.pruneAmbientWifi
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientWifiChildEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientCellChildEntity
import com.adsamcik.tracker.shared.base.database.data.PortableRadioChildIntegrity
import com.adsamcik.tracker.stats.api.repository.AmbientCellCoverage
import com.adsamcik.tracker.stats.api.repository.AmbientCellFact
import com.adsamcik.tracker.stats.api.repository.AmbientCellOrigin
import com.adsamcik.tracker.stats.api.repository.AmbientCellPortableIntegrity
import com.adsamcik.tracker.stats.api.repository.AmbientCellQualityDistribution
import com.adsamcik.tracker.stats.api.repository.AmbientCellReadRequest
import com.adsamcik.tracker.stats.api.repository.AmbientCellReadResult
import com.adsamcik.tracker.stats.api.repository.AmbientCellSubscriptionCompleteness
import com.adsamcik.tracker.stats.api.repository.AmbientCellTechnologyMix
import com.adsamcik.tracker.stats.api.repository.AmbientWifiCoverage
import com.adsamcik.tracker.stats.api.repository.AmbientWifiDayReadRequest
import com.adsamcik.tracker.stats.api.repository.AmbientWifiFact
import com.adsamcik.tracker.stats.api.repository.AmbientWifiGap
import com.adsamcik.tracker.stats.api.repository.AmbientWifiOrigin
import com.adsamcik.tracker.stats.api.repository.AmbientWifiPortableIntegrity
import com.adsamcik.tracker.stats.api.repository.AmbientWifiReadRequest
import com.adsamcik.tracker.stats.api.repository.AmbientWifiReadResult
import com.adsamcik.tracker.stats.api.repository.ExportPortableAmbientWifiRequest
import com.adsamcik.tracker.stats.api.repository.ExportPortableAmbientWifiResult
import com.adsamcik.tracker.stats.api.repository.ExportPortableAmbientCellRequest
import com.adsamcik.tracker.stats.api.repository.ExportPortableAmbientCellResult
import com.adsamcik.tracker.stats.api.repository.ImportPortableAmbientCellRequest
import com.adsamcik.tracker.stats.api.repository.ImportPortableAmbientCellResult
import com.adsamcik.tracker.stats.api.repository.ImportPortableAmbientWifiRequest
import com.adsamcik.tracker.stats.api.repository.ImportPortableAmbientWifiResult
import com.adsamcik.tracker.stats.api.repository.PortableAmbientCellImportReceipt
import com.adsamcik.tracker.stats.api.repository.PortableAmbientWifiArchiveV1
import com.adsamcik.tracker.stats.api.repository.PortableAmbientWifiImportReceipt
import io.kotest.matchers.shouldBe
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Room-backed portable lineage scenarios; execution remains deferred to convergence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomAmbientRadioPortableTransferTest {
	private lateinit var database: AppDatabase
	private val dispatcher = UnconfinedTestDispatcher()

	@Before
	fun setUp() = runTest {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		installImportAuthority(database)
	}

	@After
	fun tearDown() {
		database.close()
	}

	@Test
	fun `complete Wi-Fi correction lineage imports reads and reexports intact`() = runTest {
		val repository = RoomAmbientWifiRepository(database, dispatcher)
		val transfer = RoomAmbientWifiPortableTransfer(database, repository, dispatcher)
		val first = wifiArchive(latestRevision = 1L, includeGap = true)
		assertIs<ImportPortableAmbientWifiResult.Applied>(
			transfer.importArchive(wifiRequest(first, "first", 10_000L)),
		)
		val corrected = wifiArchive(latestRevision = 2L, includeGap = true)
		assertIs<ImportPortableAmbientWifiResult.Applied>(
			transfer.importArchive(wifiRequest(corrected, "corrected", 10_100L)),
		)

		val read = assertIs<AmbientWifiReadResult.Snapshot>(
			repository.read(
				AmbientWifiReadRequest(
					0L,
					20_000L,
					setOf(AmbientWifiOrigin.PORTABLE_IMPORT),
					10,
				),
			),
		)
		assertEquals(2L, read.facts.single().semanticRevision)
		var exported: PortableAmbientWifiArchiveV1? = null
		assertIs<ExportPortableAmbientWifiResult.Exported>(
			transfer.export(
				ExportPortableAmbientWifiRequest(
					0L,
					20_000L,
					setOf(AmbientWifiOrigin.PORTABLE_IMPORT),
				),
			) {
				assertEquals(false, database.inTransaction())
				exported = it
			},
		)
		assertEquals(listOf(1L, 2L), exported?.facts?.map { it.semanticRevision })
		assertEquals(1, exported?.gaps?.size)
		assertFailsWith<IOException> {
			transfer.export(
				ExportPortableAmbientWifiRequest(
					0L, 20_000L, setOf(AmbientWifiOrigin.PORTABLE_IMPORT),
				),
			) { throw IOException("file write failed") }
		}
		assertEquals(
			true,
			database.ambientWifiFactDao().importedForArchive(corrected.archiveId, 10)
				.all(AmbientWifiFactIntegrity::isAuthentic),
		)
	}

	@Test
	fun `portable import stops before storage when retention producer rejects`() = runTest {
		val repository = RoomAmbientWifiRepository(database, dispatcher)
		val transfer = RoomAmbientWifiPortableTransfer(
			database,
			repository,
			dispatcher,
			ensurePortableRetention = { false },
		)

		transfer.importArchive(
			wifiRequest(wifiArchive(latestRevision = 1L, includeGap = false), "denied", 10_000L),
		) shouldBe ImportPortableAmbientWifiResult.RetentionAuthorityUnavailable
		database.ambientWifiFactDao().importedFactCount() shouldBe 0L
	}

	@Test
	fun `Wi-Fi history reads only current correction details and rejects incomplete details`() = runTest {
		val repository = RoomAmbientWifiRepository(database, dispatcher)
		val transfer = RoomAmbientWifiPortableTransfer(database, repository, dispatcher)
		val original = wifiArchive(latestRevision = 1L, includeGap = false)
		assertIs<ImportPortableAmbientWifiResult.Applied>(
			transfer.importArchive(wifiRequest(original, "details-original", 10_000L)),
		)
		val corrected = wifiArchive(latestRevision = 2L, includeGap = false)
		assertIs<ImportPortableAmbientWifiResult.Applied>(
			transfer.importArchive(wifiRequest(corrected, "details-corrected", 10_100L)),
		)
		val request = AmbientWifiReadRequest(0L, 20_000L,
			setOf(AmbientWifiOrigin.PORTABLE_IMPORT), 10)
		// Older summary-only data stays usable when it contains no detail rows.
		assertEquals(emptyList(), assertIs<AmbientWifiReadResult.Snapshot>(
			repository.read(request)).facts.single().networks)
		fun child(archiveId: String, revision: Long, index: Int, name: String) =
			ImportedAmbientWifiChildEntity(
				archiveId, corrected.facts.last().identity, revision, index,
				7_000L + index, 2_000L, 1L, "aa:bb:cc:dd:ee:f$index", name,
				2_412, -55,
				PortableRadioChildIntegrity.digest("wifi", listOf(corrected.facts.last().identity),
					listOf(revision, index, 7_000L + index, 2_000L, 1L,
						"aa:bb:cc:dd:ee:f$index", name, 2_412, -55)),
			)
		database.importedRadioChildDao().insertWifiBackground(listOf(
			child(original.archiveId, 1L, 0, "Old"),
			child(original.archiveId, 1L, 1, "Old second"),
			child(corrected.archiveId, 2L, 0, "Current"),
		))
		assertEquals(AmbientWifiReadResult.StorageUnavailable, repository.read(request))
		database.importedRadioChildDao().insertWifiBackground(listOf(
			child(corrected.archiveId, 2L, 1, "Current second"),
		))
		val read = assertIs<AmbientWifiReadResult.Snapshot>(repository.read(request))
		assertEquals(2L, read.facts.single().semanticRevision)
		assertEquals(listOf("Current", "Current second"), read.facts.single().networks.map { it.ssid })
		database.openHelper.writableDatabase.execSQL(
			"UPDATE imported_ambient_wifi_child SET ssid = 'Changed' WHERE semantic_revision = 2 AND item_index = 0",
		)
		assertEquals(AmbientWifiReadResult.StorageUnavailable, repository.read(request))
		database.clearAmbientWifiProductPreservingReplayFootprints(4L, 11_000L)
		assertEquals(emptyList(), database.importedRadioChildDao()
			.wifiBackgroundChildrenForRevision(corrected.facts.last().identity, 2L, 10))
	}

	@Test
	fun `fresh database accepts a complete corrected archive`() =
		runTest {
			val context: Application = ApplicationProvider.getApplicationContext()
			val fresh = AppDatabase.testDatabase(context)
			try {
				installImportAuthority(fresh)
				val transfer = RoomAmbientWifiPortableTransfer(
					fresh,
					RoomAmbientWifiRepository(fresh, dispatcher),
					dispatcher,
				)
				assertIs<ImportPortableAmbientWifiResult.Applied>(
					transfer.importArchive(wifiRequest(
						wifiArchive(latestRevision = 2L, includeGap = false),
						"fresh-correction",
						10_100L,
					)),
				)
			} finally {
				fresh.close()
			}
		}

	@Test
	fun `portable import is default deny without durable import retention authority`() = runTest {
			val context: Application = ApplicationProvider.getApplicationContext()
			val fresh = AppDatabase.testDatabase(context)
			try {
				fresh.sourceEvidenceStateDao().ensure(
					SourceEvidenceState(collectedDataEpoch = 4L, updatedAtMs = 1L),
				)
				val transfer = RoomAmbientWifiPortableTransfer(
					fresh,
					RoomAmbientWifiRepository(fresh, dispatcher),
					dispatcher,
				)

				assertEquals(
					ImportPortableAmbientWifiResult.RetentionAuthorityUnavailable,
					transfer.importArchive(
						wifiRequest(
							wifiArchive(latestRevision = 1L, includeGap = false),
							"no-import-authority",
							10_000L,
						),
					),
				)
			} finally {
				fresh.close()
			}
	}

	@Test
	fun `gap-only Wi-Fi archive imports reads and reexports without fabricated facts`() = runTest {
		val repository = RoomAmbientWifiRepository(database, dispatcher)
		val transfer = RoomAmbientWifiPortableTransfer(database, repository, dispatcher)
		val gap = AmbientWifiPortableIntegrity.createGap(
			AmbientWifiGap(
				AmbientWifiPortableIntegrity.opaqueIdentity("gap", "only-gap"),
				AmbientWifiOrigin.PORTABLE_IMPORT,
				0L,
				2_100L,
				2_200L,
				"UTC",
				"PROVIDER_COMPLETENESS_UNVERIFIABLE",
			),
		)
		val archive = AmbientWifiPortableIntegrity.createArchive(
			AmbientWifiPortableIntegrity.opaqueIdentity("archive", "only-gap"),
			emptyList(),
			listOf(gap),
		)
		assertIs<ImportPortableAmbientWifiResult.Applied>(
			transfer.importArchive(wifiRequest(archive, "gap-only", 10_000L)),
		)
		val read = assertIs<AmbientWifiReadResult.Snapshot>(
			repository.read(
				AmbientWifiReadRequest(
					0L,
					20_000L,
					setOf(AmbientWifiOrigin.PORTABLE_IMPORT),
					10,
				),
			),
		)
		assertEquals(emptyList(), read.facts)
		assertEquals(1, read.gaps.size)
		val day = assertIs<AmbientWifiReadResult.Snapshot>(
			repository.readDay(
				AmbientWifiDayReadRequest(
					0L,
					"UTC",
					setOf(AmbientWifiOrigin.PORTABLE_IMPORT),
					10,
				),
			),
		)
		assertEquals(1, day.gaps.size)
		var exported: PortableAmbientWifiArchiveV1? = null
		assertIs<ExportPortableAmbientWifiResult.Exported>(
			transfer.export(
				ExportPortableAmbientWifiRequest(
					0L,
					20_000L,
					setOf(AmbientWifiOrigin.PORTABLE_IMPORT),
				),
			) { exported = it },
		)
		assertEquals(emptyList(), exported?.facts)
		assertEquals(1, exported?.gaps?.size)
	}

	@Test
	fun `Cell import uses portable retention authority without local ambient consent`() = runTest {
		val repository = RoomAmbientCellRepository(database, dispatcher)
		val transfer = RoomAmbientCellPortableTransfer(database, repository, dispatcher)
		val fact = AmbientCellFact(
			AmbientCellPortableIntegrity.opaqueIdentity("fact", "cell-import"),
			AmbientCellOrigin.PORTABLE_IMPORT,
			1_000L,
			2_000L,
			2_001L,
			0L,
			"UTC",
			AmbientCellCoverage.UNVERIFIABLE,
			AmbientCellSubscriptionCompleteness.PARTIAL,
			2,
			1,
			AmbientCellTechnologyMix(0, 0, 0, 0, 1, 1),
			AmbientCellQualityDistribution(0, 0, 1, 0, 1, 0),
		)
		val archive = AmbientCellPortableIntegrity.createArchive(
			AmbientCellPortableIntegrity.opaqueIdentity("archive", "cell-import"),
			listOf(AmbientCellPortableIntegrity.createFact(fact)),
			emptyList(),
		)

		assertIs<ImportPortableAmbientCellResult.Applied>(
			transfer.importArchive(
				ImportPortableAmbientCellRequest(
					archive,
					PortableAmbientCellImportReceipt("job-cell", "entry-cell", "backup", 10_000L),
					4L,
				),
			),
		)
		val read = assertIs<AmbientCellReadResult.Snapshot>(
			repository.read(
				AmbientCellReadRequest(
					0L,
					20_000L,
					setOf(AmbientCellOrigin.PORTABLE_IMPORT),
					10,
				),
			),
		)
		assertEquals(AmbientCellOrigin.PORTABLE_IMPORT, read.facts.single().origin)
		assertEquals(emptyList(), read.facts.single().towers)
		val children = listOf("LTE:123" to "LTE", "NR:456" to "NR")
			.mapIndexed { index, (identity, radioType) -> ImportedAmbientCellChildEntity(
				archive.archiveId, fact.identity, 1L, index, 7_000L + index,
				2_000L, 1L, identity, radioType, index == 0, -72,
				PortableRadioChildIntegrity.digest("cell", listOf(fact.identity),
					listOf(1L, index, 7_000L + index, 2_000L, 1L,
						identity, radioType, index == 0, -72)),
			) }
		database.importedRadioChildDao().insertCellBackground(children.take(1))
		val detailRequest = AmbientCellReadRequest(0L, 20_000L,
			setOf(AmbientCellOrigin.PORTABLE_IMPORT), 10)
		assertEquals(AmbientCellReadResult.StorageUnavailable, repository.read(detailRequest))
		database.importedRadioChildDao().insertCellBackground(children.drop(1))
		val details = assertIs<AmbientCellReadResult.Snapshot>(repository.read(detailRequest))
		assertEquals(listOf("LTE:123", "NR:456"), details.facts.single().towers.map { it.identity })
		var exportedChecksum: String? = null
		assertIs<ExportPortableAmbientCellResult.Exported>(
			transfer.export(
				ExportPortableAmbientCellRequest(
					0L,
					20_000L,
					setOf(AmbientCellOrigin.PORTABLE_IMPORT),
				),
			) { output ->
				assertEquals(false, database.inTransaction())
				exportedChecksum = output.facts.single().contentChecksum
			},
		)
		assertEquals(archive.facts.single().contentChecksum, exportedChecksum)
		assertFailsWith<IOException> {
			transfer.export(
				ExportPortableAmbientCellRequest(
					0L, 20_000L, setOf(AmbientCellOrigin.PORTABLE_IMPORT),
				),
			) { throw IOException("file write failed") }
		}
		assertEquals(
			true,
			database.ambientCellFactDao().importedForArchive(archive.archiveId, 10)
				.all(AmbientCellFactIntegrity::isAuthentic),
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE imported_ambient_cell_child SET tower_identity = 'Changed' WHERE item_index = 0",
		)
		assertEquals(AmbientCellReadResult.StorageUnavailable, repository.read(detailRequest))
	}

	@Test
	fun `retention uses effective correction time and preserves its complete lineage`() =
		runTest {
			val repository = RoomAmbientWifiRepository(database, dispatcher)
			val transfer = RoomAmbientWifiPortableTransfer(database, repository, dispatcher)
			val oldEffective = portableWifiFact("old-effective", 1_000L)
			val correctedIdentity =
				AmbientWifiPortableIntegrity.opaqueIdentity("fact", "mixed-correction")
			val correctionV1 = portableWifiRevision(
				correctedIdentity,
				1L,
				null,
				1_000L,
				-60.0,
			)
			val correctionV2 = portableWifiRevision(
				correctedIdentity,
				2L,
				1L,
				2_000L,
				-55.0,
			)
			val archive = AmbientWifiPortableIntegrity.createArchive(
				AmbientWifiPortableIntegrity.opaqueIdentity("archive", "cutoff-straddling"),
				listOf(oldEffective, correctionV1, correctionV2),
				emptyList(),
			)
			val request = wifiRequest(archive, "straddling", 10_000L)
			assertIs<ImportPortableAmbientWifiResult.Applied>(transfer.importArchive(request))
			assertEquals(
				1,
				database.sourceEvidenceStateDao().updateLifecycle(4L, 1_500L, 11_000L),
			)
			assertIs<com.adsamcik.tracker.shared.base.database.AmbientRadioRetentionAuthorityResult.Applied>(
				database.applyAmbientWifiRetentionDecision(
					AmbientRadioRetentionDecision.GrantPortableImport(
						"privacy:wifi:import:v1",
						4L,
						"boot-1",
						2L,
						11_001L,
						expectedPreviousApprovalRevision = 1L,
						expectedRetainedFromMs = 1_500L,
					),
				),
			)

			val result = database.pruneAmbientWifi(
				AmbientWifiRetentionCommand(
					beforeMs = 1_500L,
					expectedCollectedDataEpoch = 4L,
					appliedAtMs = 12_000L,
				),
			)
			check(result is AmbientWifiRetentionResult.Pruned) { "Expected Wi-Fi prune, got $result" }

			assertEquals(0, result.importedArchives)
			assertEquals(
				listOf(
					correctedIdentity to 1L,
					correctedIdentity to 2L,
				),
				database.ambientWifiFactDao().importedForArchive(archive.archiveId, 10)
					.map { it.factId to it.semanticRevision },
			)
			assertEquals(
				archive.archiveId,
				database.ambientWifiFactDao().importReceipt(
					request.receipt.jobId,
					request.receipt.entryKey,
				)?.archiveId,
			)
		}

	@Test
	fun `source clear blocks a rehashed fact with the same portable effect`() = runTest {
		val repository = RoomAmbientWifiRepository(database, dispatcher)
		val transfer = RoomAmbientWifiPortableTransfer(database, repository, dispatcher)
		val original = wifiArchive(latestRevision = 1L, includeGap = false)
		assertIs<ImportPortableAmbientWifiResult.Applied>(
			transfer.importArchive(wifiRequest(original, "original", 10_000L)),
		)
		database.clearAmbientWifiProductPreservingReplayFootprints(4L, 11_000L)
		database.applyAmbientWifiRetentionDecision(
			AmbientRadioRetentionDecision.GrantPortableImport(
				"privacy:wifi:import:v2",
				4L,
				"boot-1",
				12L,
				12_000L,
			),
		)
		val originalFact = original.facts.single()
		val rehashedFact = AmbientWifiPortableIntegrity.createFact(
			AmbientWifiFact(
				AmbientWifiPortableIntegrity.opaqueIdentity("fact", "rehashed"),
				originalFact.origin,
				originalFact.coverageStartTimeMs,
				originalFact.observedTimeMs,
				originalFact.latestPossibleTimeMs,
				originalFact.structuralEpochDay,
				originalFact.storedZoneId,
				originalFact.coverage,
				originalFact.observationCount,
				originalFact.twoPointFourGhzCount,
				originalFact.fiveGhzCount,
				originalFact.sixGhzCount,
				originalFact.otherBandCount,
				originalFact.strongestSignalDbm,
				originalFact.weakestSignalDbm,
				originalFact.meanSignalDbm,
			),
		)
		val rehashedArchive = AmbientWifiPortableIntegrity.createArchive(
			AmbientWifiPortableIntegrity.opaqueIdentity("archive", "rehashed"),
			listOf(rehashedFact),
			emptyList(),
		)

		assertEquals(
			ImportPortableAmbientWifiResult.DeletedArchive,
			transfer.importArchive(wifiRequest(rehashedArchive, "rehashed", 13_000L)),
		)
	}

	private suspend fun installImportAuthority(target: AppDatabase) {
		target.sourceEvidenceStateDao().ensure(
			SourceEvidenceState(collectedDataEpoch = 4L, updatedAtMs = 1L),
		)
		target.applyAmbientWifiRetentionDecision(
			AmbientRadioRetentionDecision.GrantPortableImport(
				"privacy:wifi:import:v1",
				4L,
				"boot-1",
				1L,
				1L,
			),
		)
		target.applyAmbientCellRetentionDecision(
			AmbientRadioRetentionDecision.GrantPortableImport(
				"privacy:cell:import:v1",
				4L,
				"boot-1",
				1L,
				1L,
			),
		)
	}

	private fun wifiRequest(
		archive: PortableAmbientWifiArchiveV1,
		key: String,
		receivedAtMs: Long,
	) = ImportPortableAmbientWifiRequest(
		archive,
		PortableAmbientWifiImportReceipt("job-$key", "entry-$key", "backup", receivedAtMs),
		4L,
	)

	private fun wifiArchive(
		latestRevision: Long,
		includeGap: Boolean,
	): PortableAmbientWifiArchiveV1 {
		val identity = AmbientWifiPortableIntegrity.opaqueIdentity("fact", "wifi-import")
		val facts = (1L..latestRevision).map { revision ->
			AmbientWifiPortableIntegrity.createFact(
				AmbientWifiFact(
					identity,
					AmbientWifiOrigin.PORTABLE_IMPORT,
					1_000L,
					2_000L,
					2_001L,
					0L,
					"UTC",
					AmbientWifiCoverage.UNVERIFIABLE,
					2,
					1,
					1,
					0,
					0,
					-40,
					-80,
					if (revision == 1L) -60.0 else -55.0,
					revision,
					revision.takeIf { it > 1L }?.minus(1L),
				),
			)
		}
		val gaps = if (includeGap) {
			listOf(
				AmbientWifiPortableIntegrity.createGap(
					AmbientWifiGap(
						AmbientWifiPortableIntegrity.opaqueIdentity("gap", "wifi-import"),
						AmbientWifiOrigin.PORTABLE_IMPORT,
						0L,
						2_100L,
						2_200L,
						"UTC",
						"PROVIDER_COMPLETENESS_UNVERIFIABLE",
					),
				),
			)
		} else {
			emptyList()
		}
		return AmbientWifiPortableIntegrity.createArchive(
			AmbientWifiPortableIntegrity.opaqueIdentity(
				"archive",
				"wifi-import-$latestRevision-$includeGap",
			),
			facts,
			gaps,
		)
	}

	private fun portableWifiFact(key: String, observedTimeMs: Long) =
		portableWifiRevision(
			AmbientWifiPortableIntegrity.opaqueIdentity("fact", key),
			1L,
			null,
			observedTimeMs,
			-50.0,
		)

	private fun portableWifiRevision(
		identity: String,
		semanticRevision: Long,
		supersedesSemanticRevision: Long?,
		observedTimeMs: Long,
		meanSignalDbm: Double,
	) =
		AmbientWifiPortableIntegrity.createFact(
			AmbientWifiFact(
				identity,
				AmbientWifiOrigin.PORTABLE_IMPORT,
				observedTimeMs - 100L,
				observedTimeMs,
				observedTimeMs + 1L,
				0L,
				"UTC",
				AmbientWifiCoverage.UNVERIFIABLE,
				1,
				1,
				0,
				0,
				0,
				-40,
				-80,
				meanSignalDbm,
				semanticRevision,
				supersedesSemanticRevision,
			),
		)
}
