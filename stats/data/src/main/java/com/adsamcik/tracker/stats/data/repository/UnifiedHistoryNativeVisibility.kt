package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.stats.api.repository.HistoryOrigin
import com.adsamcik.tracker.stats.api.repository.HistoryPurpose
import com.adsamcik.tracker.stats.api.repository.HistoryRange

/** Payload authority must already come from the calling source's exact duplicate comparison. */
internal suspend fun AppDatabase.nativeLogicalIdsVisibleInRange(
    logicalIds: Set<String>,
    range: HistoryRange,
): Set<String> {
    if (logicalIds.isEmpty() || HistoryOrigin.LOCAL !in range.origins ||
        HistoryPurpose.CAPTURE !in range.purposes) return emptySet()
    require(logicalIds.size <= 100)
    return trackingHistoryReadDao().unifiedHistoryCandidates(
        UnifiedHistoryCandidateQueries.nativeVisibility(range, logicalIds.toList()))
        .mapTo(linkedSetOf()) { it.identity.removePrefix("logical:") }
}
