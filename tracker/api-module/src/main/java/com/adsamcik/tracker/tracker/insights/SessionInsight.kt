package com.adsamcik.tracker.tracker.insights

import androidx.annotation.DrawableRes

/**
 * Category of a session insight for grouping and iconography.
 */
enum class InsightCategory {
    ACHIEVEMENT,
    FUN_FACT,
    COMPARISON,
    EXPLORATION,
}

/**
 * A single insight derived from post-session data.
 * All user-visible text is resolved to strings before construction
 * so the data class is UI-ready and testable.
 */
data class SessionInsight(
    val category: InsightCategory,
    val title: String,
    val description: String,
    @DrawableRes val iconRes: Int,
)
