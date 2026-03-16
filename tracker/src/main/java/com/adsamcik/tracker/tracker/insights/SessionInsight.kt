package com.adsamcik.tracker.tracker.insights

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable

/**
 * Category of a session insight for grouping and iconography.
 */
enum class InsightCategory {
    DURATION,
    DISTANCE,
    STEPS,
    ACTIVITY,
    GOAL,
}

/**
 * A single insight derived from post-session data.
 * All user-visible text is resolved to strings before construction
 * so the data class is UI-ready and testable.
 */
@Immutable
data class SessionInsight(
    val category: InsightCategory,
    val title: String,
    val description: String,
    @DrawableRes val iconRes: Int,
)
