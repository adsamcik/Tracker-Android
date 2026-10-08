package com.adsamcik.tracker.shared.base.database.dao

/** Key-only measured discovery. Source-owned readers authenticate exact parent/item dependencies. */
data class MeasuredHistoryCandidate(
    val kind: String,
    val origin: String,
    val purpose: String,
    val identity: String,
    val startTimeMs: Long,
    val endTimeMs: Long?,
    val rowId: Long?,
    val writerId: String?,
    val writerVersion: Int?,
    val factId: String?,
    val factRevision: Long?,
    val itemIndex: Int?,
    /** Query-only physical radio grouping; original purpose identity remains in identity. */
    val physicalEventId: String? = null,
    val physicalIdentity: String? = null,
)
