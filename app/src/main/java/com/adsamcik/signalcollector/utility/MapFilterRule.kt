package com.adsamcik.signalcollector.utility

import com.adsamcik.signalcollector.data.MapLayer

class MapFilterRule {
    var top = MapLayer.MIN_LATITUDE
        private set
    var right = MapLayer.MIN_LONGITUDE
        private set
    var bottom = MapLayer.MAX_LATITUDE
        private set
    var left = MapLayer.MAX_LONGITUDE
        private set

    fun updateBounds(top: Double, right: Double, bottom: Double, left: Double) {
        this.top = top
        this.right = right
        this.bottom = bottom
        this.left = left
    }
}
