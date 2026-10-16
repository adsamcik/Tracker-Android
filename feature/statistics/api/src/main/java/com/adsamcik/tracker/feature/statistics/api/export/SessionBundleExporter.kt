package com.adsamcik.tracker.feature.statistics.api.export

import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.OrganizationSessionId

/** Statistics owns the action; import/export owns file preparation, writing and cleanup. */
interface SessionBundleExporter {
    /**
     * Writes the selected saved sources and latest session annotation to the destination, rejecting stale
     * revision or epoch inputs.
     */
    suspend fun export(destination: String, request: SessionBundleExportRequest): SessionBundleExportResult

    /** File type used by the destination picker for a single session bundle. */
    companion object {
        const val MIME_TYPE = "application/vnd.adsamcik.tracker.session+zip"
        const val EXTENSION = "trackersession"
    }
}

/** Exact session, annotation revision, data epoch and nonempty source selection used to prevent export scope drift. */
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

/** Whether the selected bundle was saved, including any whole-window time expansion, or could not be produced. */
sealed interface SessionBundleExportResult {
    /**
     * The bundle was saved; includesExtraTime reports complete measured windows extending beyond the requested
     * session.
     */
    data class Success(val includesExtraTime: Boolean = false) : SessionBundleExportResult
    /** The bundle was not saved; the reason distinguishes empty, stale, unavailable and storage outcomes. */
    data class Failure(val reason: SessionBundleExportFailure) : SessionBundleExportResult
}

/** Reasons the exact saved-session export could not complete. */
enum class SessionBundleExportFailure { EMPTY, STALE, UNAVAILABLE, STORAGE }
