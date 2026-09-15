package com.adsamcik.tracker.stats.data.repository

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiFactIntegrity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
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
import com.adsamcik.tracker.stats.api.repository.AmbientWifiFact
import com.adsamcik.tracker.stats.api.repository.AmbientWifiOrigin
import com.adsamcik.tracker.stats.api.repository.AmbientWifiPortableIntegrity
import com.adsamcik.tracker.stats.api.repository.AmbientWifiReadRequest
import com.adsamcik.tracker.stats.api.repository.AmbientWifiReadResult
import com.adsamcik.tracker.stats.api.repository.ExportPortableAmbientWifiRequest
import com.adsamcik.tracker.stats.api.repository.ExportPortableAmbientWifiResult
import com.adsamcik.tracker.stats.api.repository.ImportPortableAmbientCellRequest
import com.adsamcik.tracker.stats.api.repository.ImportPortableAmbientCellResult
import com.adsamcik.tracker.stats.api.repository.ImportPortableAmbientWifiRequest
import com.adsamcik.tracker.stats.api.repository.ImportPortableAmbientWifiResult
import com.adsamcik.tracker.stats.api.repository.PortableAmbientCellImportReceipt
import com.adsamcik.tracker.stats.api.repository.PortableAmbientWifiArchiveV1
import com.adsamcik.tracker.stats.api.repository.PortableAmbientWifiImportReceipt
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Authored Room/transfer scenarios; execution is deferred with the tracking convergence batch. */
@RunWith(RobolectricTestRunner::class)
class RoomAmbientRadioPortableTransferTest {
	private lateinit var database: AppDatabase
	private val dispatcher = UnconfinedTestDispatcher()

	@BeforeTest
	fun setUp() = runTest {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		database.sourceEvidenceStateDao().ensure(
			SourceEvidenceState(collectedDataEpoch = 4L, updatedAtMs = 1L),
		)
	}

	@AfterTest
	fun tearDown() {
		database.close()
	}

	@Test
	fun `ambient Wi-Fi import read export round trip remains portable origin`() = runTest {
		val repository = RoomAmbientWifiRepository(database, dispatcher)
		val transfer = RoomAmbientWifiPortableTransfer(database, repository, dispatcher)
		val archive = wifiArchive()
		val request = ImportPortableAmbientWifiRequest(
			archive,
			PortableAmbientWifiImportReceipt("job-wifi", "entry-wifi", "backup", 10_000L),
			"privacy:wifi:ambient:v1",
			4L,
		)

		assertIs<ImportPortableAmbientWifiResult.Applied>(transfer.importArchive(request))
		assertEquals(
			ImportPortableAmbientWifiResult.Duplicate,
			transfer.importArchive(request),
		)
		val correction = wifiArchive(
			semanticRevision = 2L,
			supersedesSemanticRevision = 1L,
			meanSignalDbm = -55.0,
			includeGap = false,
		)
		assertIs<ImportPortableAmbientWifiResult.Applied>(
			transfer.importArchive(
				ImportPortableAmbientWifiRequest(
					correction,
					PortableAmbientWifiImportReceipt(
						"job-wifi-correction",
						"entry-wifi-correction",
						"backup",
						10_100L,
					),
					"privacy:wifi:ambient:v1",
					4L,
				),
			),
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
		assertEquals(AmbientWifiOrigin.PORTABLE_IMPORT, read.facts.single().origin)
		assertEquals(2L, read.facts.single().semanticRevision)
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
		assertEquals(archive.facts.single().observationCount,
			exported?.facts?.single()?.observationCount)
		assertEquals(archive.gaps.single().reason, exported?.gaps?.single()?.reason)

		database.ambientWifiFactDao().insertImportTombstone(
			AmbientWifiFactIntegrity.createImportTombstone(
				archive.archiveId,
				4L,
				1L,
				11_000L,
			),
		)
		assertEquals(
			ImportPortableAmbientWifiResult.DeletedArchive,
			transfer.importArchive(
				request.copy(
					receipt = PortableAmbientWifiImportReceipt(
						"job-wifi-2",
						"entry-wifi-2",
						"backup",
						12_000L,
					),
				),
			),
		)
	}

	@Test
	fun `ambient Cell imported partial fact is read without granting local origin`() = runTest {
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
					"privacy:cell:ambient:v1",
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
		assertEquals(
			AmbientCellSubscriptionCompleteness.PARTIAL,
			read.facts.single().subscriptionCompleteness,
		)
	}

	@Test
	fun `wrong epoch is typed and export cancellation is never swallowed`() = runTest {
		val wifiRepository = RoomAmbientWifiRepository(database, dispatcher)
		val wifiTransfer = RoomAmbientWifiPortableTransfer(database, wifiRepository, dispatcher)
		assertEquals(
			ImportPortableAmbientWifiResult.CollectedDataEpochChanged,
			wifiTransfer.importArchive(
				ImportPortableAmbientWifiRequest(
					wifiArchive(),
					PortableAmbientWifiImportReceipt("job", "entry", "backup", 10_000L),
					"privacy:wifi:ambient:v1",
					5L,
				),
			),
		)

		wifiTransfer.importArchive(
			ImportPortableAmbientWifiRequest(
				wifiArchive(),
				PortableAmbientWifiImportReceipt("job-2", "entry-2", "backup", 10_000L),
				"privacy:wifi:ambient:v1",
				4L,
			),
		)
		var cancelled = false
		try {
			wifiTransfer.export(
				ExportPortableAmbientWifiRequest(
					0L,
					20_000L,
					setOf(AmbientWifiOrigin.PORTABLE_IMPORT),
				),
			) {
				throw CancellationException("cancel sink")
			}
		} catch (_: CancellationException) {
			cancelled = true
		}
		assertTrue(cancelled)
	}

	private fun wifiArchive(
		semanticRevision: Long = 1L,
		supersedesSemanticRevision: Long? = null,
		meanSignalDbm: Double = -60.0,
		includeGap: Boolean = true,
	): PortableAmbientWifiArchiveV1 {
		val fact = AmbientWifiFact(
			AmbientWifiPortableIntegrity.opaqueIdentity("fact", "wifi-import"),
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
			meanSignalDbm,
			semanticRevision,
			supersedesSemanticRevision,
		)
		return AmbientWifiPortableIntegrity.createArchive(
			AmbientWifiPortableIntegrity.opaqueIdentity(
				"archive",
				"wifi-import-$semanticRevision",
			),
			listOf(AmbientWifiPortableIntegrity.createFact(fact)),
			if (includeGap) listOf(
				AmbientWifiPortableIntegrity.createGap(com.adsamcik.tracker.stats.api.repository.AmbientWifiGap(
					AmbientWifiPortableIntegrity.opaqueIdentity("gap", "wifi-import"),
					AmbientWifiOrigin.PORTABLE_IMPORT,
					0L,
					2_100L,
					2_200L,
					"UTC",
					"PROVIDER_COMPLETENESS_UNVERIFIABLE",
				)),
			) else emptyList(),
		)
	}
}
