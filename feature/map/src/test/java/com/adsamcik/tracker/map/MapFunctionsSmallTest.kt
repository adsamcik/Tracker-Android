package com.adsamcik.tracker.map

import io.kotest.matchers.comparables.shouldBeLessThan
import org.junit.jupiter.api.Test

class MapFunctionsSmallTest {

    @Test
    fun lon_lat_to_tile_and_back_consistency() {
        val zoom = 12
        val tileCount = MapFunctions.getTileCount(zoom)
        val lon = 14.42076
        val lat = 50.08804
        val tx = MapFunctions.toTileX(lon, tileCount)
        val ty = MapFunctions.toTileY(lat, tileCount)
        val lon2 = MapFunctions.toLon(tx, zoom)
        val lat2 = MapFunctions.toLat(ty, zoom)
        kotlin.math.abs(lon - lon2).shouldBeLessThan(1e-6)
        kotlin.math.abs(lat - lat2).shouldBeLessThan(1e-6)
    }

    @Test
    fun pixel_size_decreases_with_zoom() {
        val lat = 0.0
        val pz10 = MapFunctions.countPixelSize(lat, 10)
        val pz15 = MapFunctions.countPixelSize(lat, 15)
        pz15.shouldBeLessThan(pz10)
    }
}
