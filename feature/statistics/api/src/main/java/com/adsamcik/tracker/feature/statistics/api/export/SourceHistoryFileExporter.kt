package com.adsamcik.tracker.feature.statistics.api.export

import android.net.Uri
import com.adsamcik.tracker.feature.statistics.api.navigation.SourceHistoryDetailSelection

/** Saves the exact source-issued selection as its existing source format. Never starts tracking. */
interface SourceHistoryFileExporter {
    suspend fun export(destination: Uri, selection: SourceHistoryDetailSelection): SourceHistoryFileExportResult
}

enum class SourceHistoryFileExportResult { SAVED, STALE, UNAVAILABLE, STORAGE }
