package com.adsamcik.tracker.statistics.model

import com.adsamcik.tracker.shared.base.data.TrackerSession

// Session UI models previously nested inside legacy RecyclerView adapter. Kept while
// migrating to pure Compose paging list. Retained structure so existing separator logic in
// StatsViewModel remains unchanged.
sealed class SessionUiModel {
    class SessionModel(val session: TrackerSession) : SessionUiModel()
    open class SessionHeader(val date: Long) : SessionUiModel()
    class ListHeader(date: Long) : SessionHeader(date)
}
