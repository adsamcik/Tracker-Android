package com.adsamcik.tracker.feature.map.api.preview

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Presentation contract for embedding a non-interactive route preview.
 *
 * The map feature owns the renderer while consumers only depend on this API.
 */
interface RoutePreviewRenderer {
    @Composable
    fun Content(
        points: List<RoutePoint>,
        modifier: Modifier = Modifier,
    )
}
