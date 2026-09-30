package com.adsamcik.tracker.impexp.importer.file

import android.content.Context
import com.adsamcik.tracker.impexp.importer.FileImportStream
import com.adsamcik.tracker.impexp.portable.CellSourceFileV2
import com.adsamcik.tracker.impexp.portable.RadioSourceFileJsonV2Codec
import com.adsamcik.tracker.impexp.portable.WifiSourceFileV2
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.stats.api.repository.AmbientCellGap
import com.adsamcik.tracker.stats.api.repository.AmbientCellOrigin
import com.adsamcik.tracker.stats.api.repository.AmbientCellPortableIntegrity
import com.adsamcik.tracker.stats.api.repository.AmbientWifiGap
import com.adsamcik.tracker.stats.api.repository.AmbientWifiOrigin
import com.adsamcik.tracker.stats.api.repository.AmbientWifiPortableIntegrity
import com.adsamcik.tracker.stats.api.repository.ImportPortableAmbientCell
import com.adsamcik.tracker.stats.api.repository.ImportPortableAmbientCellResult
import com.adsamcik.tracker.stats.api.repository.ImportPortableAmbientWifi
import com.adsamcik.tracker.stats.api.repository.ImportPortableAmbientWifiResult
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PortableRadioFileImportEpochTest {
    @Test
    fun `Wi-Fi decoding and retry keep original epoch after data was cleared`() = runTest {
        val gap = AmbientWifiPortableIntegrity.createGap(AmbientWifiGap(
            identity = "a".repeat(64), origin = AmbientWifiOrigin.LOCAL_DEVICE,
            structuralEpochDay = 0L, startTimeMs = 1L, endTimeMs = 2L,
            storedZoneId = "UTC", reason = "provider unavailable"))
        val archive = AmbientWifiPortableIntegrity.createArchive("b".repeat(64), emptyList(), listOf(gap))
        val file = WifiSourceFileV2(selectedFromMs = null, selectedToExclusiveMs = null,
            includeSessionsAcrossDates = true, observationsOutsideDates = 0,
            sessions = emptyList(), background = archive, backgroundChildren = emptyList())
        val bytes = ByteArrayOutputStream().also { RadioSourceFileJsonV2Codec().writeWifi(it, file) }
            .toByteArray()
        val source = mockk<RadioSourceImportEntryPoint>()
        val writer = mockk<ImportPortableAmbientWifi>()
        every { source.backgroundWifi() } returns writer
        val epochs = mutableListOf<Long>()
        var currentEpoch = 1L
        val database = database { currentEpoch }
        coEvery { writer.importArchive(any()) } answers {
            val request = firstArg<com.adsamcik.tracker.stats.api.repository.ImportPortableAmbientWifiRequest>()
            epochs += request.expectedCollectedDataEpoch
            if (request.expectedCollectedDataEpoch != currentEpoch) {
                ImportPortableAmbientWifiResult.CollectedDataEpochChanged
            } else ImportPortableAmbientWifiResult.Applied(0, 1)
        }
        repeat(2) {
            val result = PortableWifiSourceFileImport(dependencies = { source }).import(mockk(), database,
                stream(bytes, "data.trackerwifi") { currentEpoch = 2L })
            result.failedCount shouldBe 1
            result.successCount shouldBe 0
        }
        epochs shouldBe listOf(1L, 1L)
    }

    @Test
    fun `Cell decoding cannot adopt a later epoch for its source or child attachment`() = runTest {
        val gap = AmbientCellPortableIntegrity.createGap(AmbientCellGap(
            identity = "c".repeat(64), origin = AmbientCellOrigin.LOCAL_DEVICE,
            structuralEpochDay = 0L, startTimeMs = 1L, endTimeMs = 2L,
            storedZoneId = "UTC", reason = "provider unavailable"))
        val archive = AmbientCellPortableIntegrity.createArchive("d".repeat(64), emptyList(), listOf(gap))
        val file = CellSourceFileV2(selectedFromMs = null, selectedToExclusiveMs = null,
            includeSessionsAcrossDates = true, observationsOutsideDates = 0,
            sessions = emptyList(), background = archive, backgroundChildren = emptyList())
        val bytes = ByteArrayOutputStream().also { RadioSourceFileJsonV2Codec().writeCell(it, file) }
            .toByteArray()
        val source = mockk<RadioSourceImportEntryPoint>()
        val writer = mockk<ImportPortableAmbientCell>()
        every { source.backgroundCell() } returns writer
        var currentEpoch = 1L
        val database = database { currentEpoch }
        val epochs = mutableListOf<Long>()
        coEvery { writer.importArchive(any()) } answers {
            val request = firstArg<com.adsamcik.tracker.stats.api.repository.ImportPortableAmbientCellRequest>()
            epochs += request.expectedCollectedDataEpoch
            if (request.expectedCollectedDataEpoch != currentEpoch) {
                ImportPortableAmbientCellResult.CollectedDataEpochChanged
            } else ImportPortableAmbientCellResult.Applied(0, 1)
        }
        val result = PortableCellSourceFileImport(dependencies = { source }).import(mockk(), database,
            stream(bytes, "data.trackercell") { currentEpoch = 2L })
        result.failedCount shouldBe 1
        result.successCount shouldBe 0
        epochs shouldBe listOf(1L)
    }

    private fun database(epoch: () -> Long): AppDatabase {
        val database = mockk<AppDatabase>()
        val evidence = mockk<com.adsamcik.tracker.shared.base.database.dao.SourceEvidenceStateDao>()
        every { database.sourceEvidenceStateDao() } returns evidence
        coEvery { evidence.get() } answers { SourceEvidenceState(collectedDataEpoch = epoch()) }
        return database
    }

    private fun stream(bytes: ByteArray, name: String, duringRead: () -> Unit): FileImportStream =
        FileImportStream(object : ByteArrayInputStream(bytes) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                duringRead()
                return super.read(buffer, offset, length)
            }
        }, name).withImportReceipt("original-job", 1_000L).withOriginalDataEpoch(1L)
}
