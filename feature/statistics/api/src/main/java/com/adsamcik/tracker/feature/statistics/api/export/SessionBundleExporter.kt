package com.adsamcik.tracker.feature.statistics.api.export

import android.net.Uri
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.OrganizationSessionId

/** Statistics owns the action; import/export owns file preparation, writing and cleanup. */
interface SessionBundleExporter {
    suspend fun export(destination: Uri, request: SessionBundleExportRequest): SessionBundleExportResult

    companion object {
        const val MIME_TYPE = "application/vnd.adsamcik.tracker.session+zip"
        const val EXTENSION = "trackersession"
    }
}

data class SessionBundleExportRequest(
    val sessionId: OrganizationSessionId,
    val expectedOrganizationRevision: Long,
    val expectedCollectedDataEpoch: Long,
    val sources: Set<HistorySource>,
) {
    init {
        require(expectedOrganizationRevision >= 0L && expectedCollectedDataEpoch >= 0L)
        require(sources.isNotEmpty())
    }
}

sealed interface SessionBundleExportResult {
    data class Success(val includesExtraTime: Boolean = false) : SessionBundleExportResult
    data class Failure(val reason: SessionBundleExportFailure) : SessionBundleExportResult
}

enum class SessionBundleExportFailure { EMPTY, STALE, UNAVAILABLE, STORAGE }
