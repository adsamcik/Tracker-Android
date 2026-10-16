package com.adsamcik.tracker.impexp.exporter

import androidx.room.withTransaction
import com.adsamcik.tracker.impexp.portable.CellSessionFileV2
import com.adsamcik.tracker.impexp.portable.RadioSourceFileFormatException
import com.adsamcik.tracker.impexp.portable.RadioSourceFileJsonV2Codec
import com.adsamcik.tracker.impexp.portable.RadioSourceFileV2
import com.adsamcik.tracker.impexp.portable.WifiSessionFileV2
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.ExportPortableCapturedCellRequest
import com.adsamcik.tracker.shared.base.database.ExportPortableCapturedCellResult
import com.adsamcik.tracker.shared.base.database.ImportedCellProductEvaluation
import com.adsamcik.tracker.shared.base.database.ImportedCellProductReader
import com.adsamcik.tracker.shared.base.database.PortableCapturedCellEntryV1
import com.adsamcik.tracker.shared.base.database.PortableCellIdentityKind
import com.adsamcik.tracker.shared.base.database.PortableCellOpaqueIdentity
import com.adsamcik.tracker.shared.base.database.dao.ImportedWifiDao
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.stats.api.repository.CellHistoryEntrySelection
import com.adsamcik.tracker.stats.api.repository.ExportPortableCapturedWifiRequest
import com.adsamcik.tracker.stats.api.repository.ExportPortableCapturedWifiResult
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.ImportedCellHistorySelection
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductEvaluation
import com.adsamcik.tracker.stats.api.repository.LocalCellHistorySelection
import com.adsamcik.tracker.stats.api.repository.PortableCapturedWifiEntryV1
import com.adsamcik.tracker.stats.api.repository.PortableWifiIdentityKind
import com.adsamcik.tracker.stats.api.repository.PortableWifiOpaqueIdentity
import com.adsamcik.tracker.stats.api.repository.WifiHistorySelection
import java.io.OutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Source-issued opaque selections resolve within the same qualified typed-file snapshot. */
internal suspend fun writeSelectedWifiFile(source: WifiSourceExportEntryPoint, selection: WifiHistorySelection,
    output: OutputStream): ExportResult = source.database().withTransaction {
    val database = source.database()
    val value = when (selection) {
        is WifiHistorySelection.Local -> {
            val logical = selectedNativeRadioLogicalId(database, HistorySource.WIFI, selection.key.value)
            var entry: PortableCapturedWifiEntryV1? = null
            val result = source.capturedWifi().export(ExportPortableCapturedWifiRequest(logical)) { entry = it }
            check(result is ExportPortableCapturedWifiResult.Exported)
            val saved = requireNotNull(entry)
            WifiSessionFileV2(saved, PortableWifiSourceExporter().localChildren(database, logical, saved))
        }
        is WifiHistorySelection.Imported -> {
            val selected = selection.selected
            val value =
                source.importedWifiEvaluator()
                    .selectIdentityInTransaction(selected.key) as? ImportedWifiProductEvaluation.Readable
                ?: throw RadioSourceFileFormatException("Selected Wi-Fi data is unavailable")
            check(value.isReExportable && value.candidate.importRevision == selected.importRevision &&
                value.entry.contentChecksum.value == selected.contentChecksum)
            val children = database.importedRadioChildDao().wifiSessionChildren(selected.key.value,
                selected.importRevision, RadioSourceFileV2.MAX_CHILDREN + 1)
            check(children.size <= RadioSourceFileV2.MAX_CHILDREN)
            WifiSessionFileV2(value.entry, mapImportedWifiSessionChildren(value.entry, children))
        }
    }
    RadioSourceFileJsonV2Codec().wifiStreamWriter(output, null, null, true).apply {
        session(value); finish(null, emptyList(), 0)
    }
    ExportResult.Success(value.entry.runs.sumOf { it.observations.size })
}

internal suspend fun writeSelectedCellFile(source: CellSourceExportEntryPoint, selection: CellHistoryEntrySelection,
    output: OutputStream): ExportResult = source.database().withTransaction {
    val database = source.database()
    val value = when (selection) {
        is LocalCellHistorySelection -> {
            val logical = selectedNativeRadioLogicalId(database, HistorySource.CELL, selection.identity.value)
            var entry: PortableCapturedCellEntryV1? = null
            val result = source.capturedCell().export(ExportPortableCapturedCellRequest(logical)) { entry = it }
            check(result is ExportPortableCapturedCellResult.Exported)
            val saved = requireNotNull(entry)
            CellSessionFileV2(saved, PortableCellSourceExporter().localChildren(database, logical, saved))
        }
        is ImportedCellHistorySelection -> {
            val value =
                ImportedCellProductReader(database).selectIdentityInTransaction(
                    PortableCellOpaqueIdentity(selection.identity.value)
                )
                as? ImportedCellProductEvaluation.Readable ?:
                    throw RadioSourceFileFormatException("Selected Cell data is unavailable")
            check(value.isReExportable && value.candidate.importRevision == selection.importRevision &&
                value.entry.contentChecksum.value == selection.contentChecksum.value)
            val children = database.importedRadioChildDao().cellSessionChildren(selection.identity.value,
                selection.importRevision, RadioSourceFileV2.MAX_CHILDREN + 1)
            check(children.size <= RadioSourceFileV2.MAX_CHILDREN)
            CellSessionFileV2(value.entry, mapImportedCellSessionChildren(value.entry, children))
        }
        else -> throw RadioSourceFileFormatException("Selected Cell data is unavailable")
    }
    RadioSourceFileJsonV2Codec().cellStreamWriter(output, null, null, true).apply {
        session(value); finish(null, emptyList(), 0)
    }
    ExportResult.Success(value.entry.runs.sumOf { it.observations.size })
}

private suspend fun selectedNativeRadioLogicalId(database: AppDatabase, source: HistorySource,
    identity: String): String {
    var after: String? = null
    var count = 0L
    while (true) {
        currentCoroutineContext().ensureActive()
        val page = if (source == HistorySource.WIFI) {
            database.wifiCapturedFactDao().completedExportCandidateIdsPage(
            SourceDestinationOwnerEntity.SOURCE_WIFI, null, null, after, RADIO_SELECTION_PAGE_SIZE)
        }
        else {
            database.cellCapturedFactDao().completedExportCandidateIdsPage(
                SourceDestinationOwnerEntity.SOURCE_CELL, null, null, after, RADIO_SELECTION_PAGE_SIZE
            )
        }
        check(page.size <= RADIO_SELECTION_PAGE_SIZE && page.zipWithNext().all { (a, b) -> a < b } &&
            (after == null || page.all { it > requireNotNull(after) }))
        count = Math.addExact(count, page.size.toLong())
        check(count <= ImportedWifiDao.MAX_GLOBAL_AUTHORITY_ROWS)
        page.firstOrNull { logical ->
            val actual = if (source == HistorySource.WIFI) {
                PortableWifiOpaqueIdentity.derive(PortableWifiIdentityKind.LOGICAL_ENTRY, logical).value
            }
            else {
                PortableCellOpaqueIdentity.derive(PortableCellIdentityKind.LOGICAL_ENTRY, logical).value
            }
            actual == identity
        }?.let { return it }
        if (page.size < RADIO_SELECTION_PAGE_SIZE) {
            throw RadioSourceFileFormatException("Selected recording is unavailable")
        }
        after = page.last()
    }
}

private const val RADIO_SELECTION_PAGE_SIZE = 100
