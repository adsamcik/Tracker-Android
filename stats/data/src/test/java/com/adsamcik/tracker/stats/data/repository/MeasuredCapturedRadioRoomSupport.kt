package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SourceProductLaneExecutionAuthority
import com.adsamcik.tracker.shared.base.time.BootClockDomainProvider
import com.adsamcik.tracker.stats.api.repository.*
import kotlinx.coroutines.Dispatchers

/** Real source readers/Room; the optional qualified Wi-Fi evaluation is an explicit import boundary fixture. */
internal fun measuredCapturedRadioFixture(database: AppDatabase,
    wifi: DefaultWifiHistoryRepository? = null, cell: DefaultCellHistoryRepository? = null,
    importedWifi: ImportedWifiProductEvaluation? = null,
    onWifiSelection: suspend () -> Unit = {}): MeasuredCapturedRadioHistoryReader {
    val authority = SourceProductLaneExecutionAuthority { database.inTransaction() }
    val wifiReader = wifi ?: DefaultWifiHistoryRepository(database, authority,
        object : ImportedWifiProductEvaluator {
            override suspend fun selectIdentityInTransaction(selection: WifiImportedHistorySelectionKey): ImportedWifiProductEvaluation? {
                onWifiSelection()
                return importedWifi?.takeIf { it.candidate.identity.value == selection.value }
            }
            override suspend fun selectRecentInTransaction(limit: Int) = emptyList<ImportedWifiProductEvaluation>()
            override suspend fun selectRangeInTransaction(request: ImportedWifiProductRangeRequest) = ImportedWifiProductRangePage(emptyList(), false)
            override suspend fun selectExportRangeInTransaction(request: ImportedWifiProductRangeRequest) = error("No export in measured read")
        }, object : ReadLocalPortableCapturedWifi {
            override suspend fun readInTransaction(request: ExportPortableCapturedWifiRequest) = error("No portable counterpart reader in measured read")
        }, WifiDeletedHistoryReader { WifiDeletedHistoryResult.NotDeleted },
        BootClockDomainProvider { "boot-measured-fixture" }, Dispatchers.Unconfined)
    val cellReader = cell ?: DefaultCellHistoryRepository(database, authority, Dispatchers.Unconfined, { _ -> })
    return MeasuredCapturedRadioHistoryReader(database, wifiReader, cellReader)
}

internal fun measuredCaptureRange(from: Long, to: Long, source: HistorySource,
    origins: Set<HistoryOrigin> = setOf(HistoryOrigin.LOCAL)) = HistoryRange(from, to, "UTC",
    setOf(source), origins, setOf(HistoryPurpose.CAPTURE), HistoryBrowseMode.OBSERVATIONS)
