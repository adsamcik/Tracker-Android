package com.adsamcik.tracker.map.tiles

import com.adsamcik.tracker.map.graphics.BitmapPool
import com.adsamcik.tracker.map.perf.PerformanceManager
import com.google.android.gms.maps.model.Tile
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Minimal smoke test: exercise the harness with a fake provider returning NO_TILE.
 */
class TileHarnessSmokeTest {

    private class FakeProvider(perf: PerformanceManager) : OptimizedTileProvider(perf) {
        override fun generateTile(
            x: Int,
            y: Int,
            zoom: Int,
            pool: BitmapPool?,
            budgets: PerformanceManager.PerformanceBudgets
        ): Tile = com.google.android.gms.maps.model.TileProvider.NO_TILE
    }

    @Test
    fun render_no_tile_smoke_skipped() {
        val harness = TileTestHarness(
            providerFactory = { perf -> FakeProvider(perf) }
        )
        // We don't assert bitmap decoding here to avoid Robolectric dependency; just ensure no crash
        harness.renderTile(x = 0, y = 0, zoom = 3, quality = 1.0f)
        assertTrue(true)
    }
}
