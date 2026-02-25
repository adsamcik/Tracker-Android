package com.adsamcik.tracker.shared.utils.style.compose

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** Standard dialog shape — Material 3 default (28dp). Avoids clipping from asymmetric AppShapes. */
val DialogShape = RoundedCornerShape(28.dp)

// Custom Shapes
val WaypointShape = RoundedCornerShape(
    topStartPercent = 50,
    topEndPercent = 50,
    bottomEndPercent = 10,
    bottomStartPercent = 50
)

val MomentumPillShape = RoundedCornerShape(
    topStartPercent = 20,
    topEndPercent = 50,
    bottomEndPercent = 50,
    bottomStartPercent = 20
)

val TerrainCardShape = RoundedCornerShape(
    topStartPercent = 15,
    topEndPercent = 4,
    bottomEndPercent = 15,
    bottomStartPercent = 4
)

val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = TerrainCardShape,
    large = MomentumPillShape,
    extraLarge = DialogShape
)
