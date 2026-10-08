package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.dao.MeasuredHistoryCandidate
import com.adsamcik.tracker.stats.api.repository.HistoryPosition
import java.util.Locale

/** Same exact order for raw key discovery, qualification and the public continuation. */
internal fun measuredHistoryPosition(candidate: MeasuredHistoryCandidate): HistoryPosition =
    HistoryPosition(candidate.startTimeMs,
        candidate.origin.lowercase(Locale.ROOT) + ":" + when {
            candidate.physicalIdentity == null -> candidate.kind.lowercase(Locale.ROOT)
            candidate.kind in setOf("captured_wifi", "ambient_wifi") -> "native_wifi"
            candidate.kind in setOf("captured_cell", "ambient_cell") -> "native_cell"
            else -> candidate.kind.lowercase(Locale.ROOT)
        }, candidate.physicalIdentity ?: candidate.identity)
