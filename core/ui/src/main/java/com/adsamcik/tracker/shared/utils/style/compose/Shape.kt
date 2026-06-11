package com.adsamcik.tracker.shared.utils.style.compose

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Ridgeline Design System — Shape Tokens
 *
 * 5-level diagonal asymmetry scale (3:1 major:minor ratio).
 * Major corners: topStart + bottomEnd. Minor: topEnd + bottomStart.
 * Creates the "ridgeline" opposing-corner signature.
 */

/** Standard dialog shape — 28dp uniform. Exempt from asymmetry. */
val DialogShape = RoundedCornerShape(28.dp)

/** Bottom sheet shape — asymmetric top only, flat bottom. */
val BottomSheetShape = RoundedCornerShape(
    topStart = 20.dp,
    topEnd = 6.dp,
    bottomEnd = 0.dp,
    bottomStart = 0.dp,
)

// --- Identity Shapes (percentage-based, NOT part of 5-level scale) ---

/** Asymmetric pin-drop for TrackingFAB only. */
val WaypointShape = RoundedCornerShape(
    topStartPercent = 50,
    topEndPercent = 50,
    bottomEndPercent = 10,
    bottomStartPercent = 50,
)

/** Forward-motion pill for primary action buttons and nav pill indicator. */
val MomentumPillShape = RoundedCornerShape(
    topStartPercent = 20,
    topEndPercent = 50,
    bottomEndPercent = 50,
    bottomStartPercent = 20,
)

/** Legacy card shape. Prefer MaterialTheme.shapes.medium (L3) for new code. */
val TerrainCardShape = RoundedCornerShape(
    topStartPercent = 15,
    topEndPercent = 4,
    bottomEndPercent = 15,
    bottomStartPercent = 4,
)

/**
 * Ridgeline 5-level diagonal shape scale.
 *
 * L1 (extraSmall): 6/2dp — chips, badges, inline tags
 * L2 (small):     10/3dp — small cards, list items, toggles
 * L3 (medium):    14/4dp — standard cards, dialog body, sheets
 * L4 (large):     20/6dp — feature cards, expanded panels
 * L5 (extraLarge): 24/8dp — hero cards, full-width banners
 */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(
        topStart = 6.dp,
        topEnd = 2.dp,
        bottomEnd = 6.dp,
        bottomStart = 2.dp,
    ),
    small = RoundedCornerShape(
        topStart = 10.dp,
        topEnd = 3.dp,
        bottomEnd = 10.dp,
        bottomStart = 3.dp,
    ),
    medium = RoundedCornerShape(
        topStart = 14.dp,
        topEnd = 4.dp,
        bottomEnd = 14.dp,
        bottomStart = 4.dp,
    ),
    large = RoundedCornerShape(
        topStart = 20.dp,
        topEnd = 6.dp,
        bottomEnd = 20.dp,
        bottomStart = 6.dp,
    ),
    extraLarge = RoundedCornerShape(
        topStart = 24.dp,
        topEnd = 8.dp,
        bottomEnd = 24.dp,
        bottomStart = 8.dp,
    ),
)
