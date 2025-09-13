package com.adsamcik.tracker.map.layers.base

import android.content.Context
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.TileOverlay
import com.google.android.gms.maps.model.TileOverlayOptions
import com.adsamcik.tracker.map.tiles.HeatmapTileProviderBase

/**
 * Base class for heatmap-like layers using Google Maps TileOverlay.
 * Specializes BaseMapLayer with convenient overlay lifecycle handling.
 */
abstract class HeatmapLayer<I, P> : BaseMapLayer<I, P>() {

    private var overlay: TileOverlay? = null
    protected var lastTileProvider: com.google.android.gms.maps.model.TileProvider? = null

    /** Provide the TileOverlayOptions to add to the map in render(). */
    protected abstract fun buildTileOverlay(processed: P): TileOverlayOptions

    override fun render(map: GoogleMap, processed: P) {
        // Remove previous overlay if any
        overlay?.remove()
        val opts = buildTileOverlay(processed)
        lastTileProvider = opts.tileProvider
        overlay = map.addTileOverlay(opts)

        // Wire provider invalidation callback -> clearTileCache (debounced upstream)
        (lastTileProvider as? HeatmapTileProviderBase)?.setInvalidateTilesCallback {
            overlay?.clearTileCache()
        }
    }

    override fun onDisable(map: GoogleMap) {
        overlay?.remove()
        overlay = null
        lastTileProvider = null
    }

    fun currentTileProvider(): com.google.android.gms.maps.model.TileProvider? = lastTileProvider
}
