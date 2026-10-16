package com.adsamcik.tracker.feature.statistics.api.export

import com.adsamcik.tracker.feature.statistics.api.navigation.SourceHistoryDetailSelection

/** Saves the exact source-issued selection as its existing source format. Never starts tracking. */
interface SourceHistoryFileExporter {
    /** Writes one exact source-issued history selection to the chosen destination in that source format. */
    suspend fun export(destination: String, selection: SourceHistoryDetailSelection): SourceHistoryFileExportResult
}

/**
 * Whether the selected source file was saved, changed before export, became unavailable or encountered a
 * storage failure.
 */
enum class SourceHistoryFileExportResult { SAVED, STALE, UNAVAILABLE, STORAGE }
