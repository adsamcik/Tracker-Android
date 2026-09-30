package com.adsamcik.tracker.stats.data.repository

import android.app.Application
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.*
import com.adsamcik.tracker.shared.base.database.data.*
import com.adsamcik.tracker.stats.api.repository.*
import kotlin.test.*
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real import transactions/receipts consumed by the retained reader; no native authority exists. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RetainedImportedAmbientRadioReaderTest {
    private lateinit var database: AppDatabase
    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setup() = runTest {
        database = AppDatabase.testDatabase(ApplicationProvider.getApplicationContext<Application>())
        database.sourceEvidenceStateDao().ensure(SourceEvidenceState(collectedDataEpoch = 4))
        database.applyAmbientWifiRetentionDecision(AmbientRadioRetentionDecision.GrantPortableImport("wifi-retention", 4, "boot", 0, 0))
        database.applyAmbientCellRetentionDecision(AmbientRadioRetentionDecision.GrantPortableImport("cell-retention", 4, "boot", 0, 0))
    }
    @After fun cleanup() = database.close()

    @Test fun `imported Wi-Fi and Cell remain readable without active recording or sensor permission`() = runTest {
        installImports()
        val wifi = readWifi()
        val cell = readCell()
        assertEquals(1, wifi.facts.size)
        assertEquals(1, wifi.gaps.size)
        assertEquals(1, cell.facts.size)
        assertEquals(1, cell.gaps.size)
        assertTrue(wifi.readGaps.isEmpty())
        assertTrue(cell.readGaps.isEmpty())
        assertNull(database.sourcePolicyDao().authority())
    }

    @Test fun `missing import receipt withholds that source without claiming another source is invalid`() = runTest {
        installImports()
        database.ambientWifiFactDao().deleteImportReceipts(listOf(wifiArchive().archiveId))
        val wifi = readWifi()
        assertTrue(wifi.facts.isEmpty())
        assertTrue(wifi.gaps.isEmpty())
        assertEquals(setOf(RadioContextReadGapReason.SOURCE_PROVENANCE_UNVERIFIABLE), wifi.readGaps)
        assertEquals(1, readCell().facts.size)
    }

    @Test fun `changed data epoch or retention floor hides stale imported rows even if they remain stored`() = runTest {
        installImports()
        database.sourceEvidenceStateDao().updateLifecycle(4, 1_500, 10_001)
        assertTrue(readWifi().facts.isEmpty())
        assertTrue(readCell().facts.isEmpty())
        database.sourceEvidenceStateDao().updateLifecycle(5, null, 10_002)
        assertTrue(readWifi().facts.isEmpty())
        assertTrue(readCell().facts.isEmpty())
        assertEquals(1L, database.ambientWifiFactDao().importedFactCount())
        assertEquals(1L, database.ambientCellFactDao().importedFactCount())
    }

    @Test fun `archive tombstone and identity footprint withhold saved data rather than reviving it`() = runTest {
        installImports()
        database.ambientWifiFactDao().insertImportTombstones(listOf(AmbientWifiFactIntegrity.createImportTombstone(
            wifiArchive().archiveId, 4, 1, 10_001)))
        val cell = cellArchive()
        database.ambientCellFactDao().insertReplayFootprints(listOf(AmbientCellFactIntegrity.createReplayFootprint(
            AmbientCellReplayFootprintEntity.KIND_FACT_IDENTITY, cell.facts.single().identity, 1, 4, 1, 10_001)))
        assertTrue(readWifi().facts.isEmpty())
        assertTrue(readWifi().gaps.isEmpty())
        assertTrue(readCell().facts.isEmpty())
        assertEquals(1, readCell().gaps.size)
    }

    @Test fun `ordinary range day and portable export share imported receipt authority`() = runTest {
        installImports()
        val wifi = RoomAmbientWifiRepository(database, dispatcher)
        val cell = RoomAmbientCellRepository(database, dispatcher)
        val wifiRange = AmbientWifiReadRequest(0, 20_000, setOf(AmbientWifiOrigin.PORTABLE_IMPORT), 10)
        val cellRange = AmbientCellReadRequest(0, 20_000, setOf(AmbientCellOrigin.PORTABLE_IMPORT), 10)
        assertEquals(1, assertIs<AmbientWifiReadResult.Snapshot>(wifi.read(wifiRange)).facts.size)
        assertEquals(1, assertIs<AmbientCellReadResult.Snapshot>(cell.read(cellRange)).facts.size)
        assertEquals(1, assertIs<AmbientWifiReadResult.Snapshot>(wifi.readDay(
            AmbientWifiDayReadRequest(0, "UTC", setOf(AmbientWifiOrigin.PORTABLE_IMPORT), 10))).facts.size)
        database.ambientWifiFactDao().deleteImportReceipts(listOf(wifiArchive().archiveId))
        assertEquals(AmbientWifiReadResult.StorageUnavailable, wifi.read(wifiRange))
        assertEquals(AmbientWifiReadResult.StorageUnavailable, wifi.readDay(
            AmbientWifiDayReadRequest(0, "UTC", setOf(AmbientWifiOrigin.PORTABLE_IMPORT), 10)))
        var emitted = false
        assertEquals(ExportPortableAmbientWifiResult.StorageUnavailable,
            RoomAmbientWifiPortableTransfer(database, wifi, dispatcher).export(ExportPortableAmbientWifiRequest(
                0, 20_000, setOf(AmbientWifiOrigin.PORTABLE_IMPORT))) { emitted = true })
        assertFalse(emitted)
        assertIs<ExportPortableAmbientCellResult.Exported>(RoomAmbientCellPortableTransfer(database, cell, dispatcher)
            .export(ExportPortableAmbientCellRequest(0, 20_000, setOf(AmbientCellOrigin.PORTABLE_IMPORT))) {
                assertEquals(1, it.facts.size)
            })
    }

    @Test fun `ordinary reader and exporter do not revive stored records after epoch or floor changes`() = runTest {
        installImports()
        val wifi = RoomAmbientWifiRepository(database, dispatcher)
        val cell = RoomAmbientCellRepository(database, dispatcher)
        database.sourceEvidenceStateDao().updateLifecycle(4, 3_000, 10_001)
        assertTrue(assertIs<AmbientWifiReadResult.Snapshot>(wifi.read(AmbientWifiReadRequest(
            0, 20_000, setOf(AmbientWifiOrigin.PORTABLE_IMPORT), 10))).facts.isEmpty())
        assertEquals(ExportPortableAmbientWifiResult.NoData, RoomAmbientWifiPortableTransfer(database, wifi, dispatcher)
            .export(ExportPortableAmbientWifiRequest(0, 20_000, setOf(AmbientWifiOrigin.PORTABLE_IMPORT))) {
                error("Expired records must not be emitted")
            })
        database.sourceEvidenceStateDao().updateLifecycle(5, null, 10_002)
        assertTrue(assertIs<AmbientCellReadResult.Snapshot>(cell.read(AmbientCellReadRequest(
            0, 20_000, setOf(AmbientCellOrigin.PORTABLE_IMPORT), 10))).facts.isEmpty())
        assertEquals(ExportPortableAmbientCellResult.NoData, RoomAmbientCellPortableTransfer(database, cell, dispatcher)
            .export(ExportPortableAmbientCellRequest(0, 20_000, setOf(AmbientCellOrigin.PORTABLE_IMPORT))) {
                error("A previous data epoch must not be emitted")
            })
    }

    private suspend fun installImports() {
        assertIs<ImportPortableAmbientWifiResult.Applied>(RoomAmbientWifiPortableTransfer(database,
            RoomAmbientWifiRepository(database, dispatcher), dispatcher).importArchive(ImportPortableAmbientWifiRequest(
                wifiArchive(), PortableAmbientWifiImportReceipt("wifi-job", "wifi-entry", "wifi", 10_000), 4)))
        assertIs<ImportPortableAmbientCellResult.Applied>(RoomAmbientCellPortableTransfer(database,
            RoomAmbientCellRepository(database, dispatcher), dispatcher).importArchive(ImportPortableAmbientCellRequest(
                cellArchive(), PortableAmbientCellImportReceipt("cell-job", "cell-entry", "cell", 10_000), 4)))
    }

    private suspend fun readWifi() = database.withTransaction {
        val dao = database.ambientWifiFactDao()
        val facts = dao.importedOverWindow(0, 20_000, 10)
        val history = dao.importedFactRevisions(facts.map { it.factId }, 10)
        RetainedImportedAmbientRadioReader(database).wifiInTransaction(facts, history, dao.importedGapsOverWindow(0, 20_000, 10))
    }
    private suspend fun readCell() = database.withTransaction {
        val dao = database.ambientCellFactDao()
        val facts = dao.importedOverWindow(0, 20_000, 10)
        val history = dao.importedFactRevisions(facts.map { it.factId }, 10)
        RetainedImportedAmbientRadioReader(database).cellInTransaction(facts, history, dao.importedGapsOverWindow(0, 20_000, 10))
    }

    private fun wifiArchive() = AmbientWifiPortableIntegrity.createArchive(
        AmbientWifiPortableIntegrity.opaqueIdentity("archive", "reader-wifi"), listOf(AmbientWifiPortableIntegrity.createFact(
            AmbientWifiFact(AmbientWifiPortableIntegrity.opaqueIdentity("fact", "reader-wifi"), AmbientWifiOrigin.PORTABLE_IMPORT,
                1_000, 2_000, 2_001, 0, "UTC", AmbientWifiCoverage.UNVERIFIABLE, 1, 1, 0, 0, 0, -40, -40, -40.0))),
        listOf(AmbientWifiPortableIntegrity.createGap(AmbientWifiGap(AmbientWifiPortableIntegrity.opaqueIdentity("gap", "reader-wifi"),
            AmbientWifiOrigin.PORTABLE_IMPORT, 0, 2_100, 2_200, "UTC", "PROVIDER_COMPLETENESS_UNVERIFIABLE"))))
    private fun cellArchive() = AmbientCellPortableIntegrity.createArchive(
        AmbientCellPortableIntegrity.opaqueIdentity("archive", "reader-cell"), listOf(AmbientCellPortableIntegrity.createFact(
            AmbientCellFact(AmbientCellPortableIntegrity.opaqueIdentity("fact", "reader-cell"), AmbientCellOrigin.PORTABLE_IMPORT,
                1_000, 2_000, 2_001, 0, "UTC", AmbientCellCoverage.UNVERIFIABLE, AmbientCellSubscriptionCompleteness.UNKNOWN,
                1, 1, AmbientCellTechnologyMix(0, 0, 0, 0, 1, 0), AmbientCellQualityDistribution(0, 0, 0, 0, 1, 0)))),
        listOf(AmbientCellPortableIntegrity.createGap(AmbientCellGap(AmbientCellPortableIntegrity.opaqueIdentity("gap", "reader-cell"),
            AmbientCellOrigin.PORTABLE_IMPORT, 0, 2_100, 2_200, "UTC", "PROVIDER_COMPLETENESS_UNVERIFIABLE"))))
}
