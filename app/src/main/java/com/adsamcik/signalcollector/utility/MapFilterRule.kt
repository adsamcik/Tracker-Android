package com.adsamcik.signalcollector.utility

import com.adsamcik.signalcollector.data.MapLayer
import com.adsamcik.signalcollector.interfaces.IFilterRule

class MapFilterRule : IFilterRule<MapLayer> {
    private var top = MapLayer.MIN_LATITUDE
    private var right = MapLayer.MIN_LONGITUDE
    private var bottom = MapLayer.MAX_LATITUDE
    private var left = MapLayer.MAX_LONGITUDE

    override fun filter(value: MapLayer, stringValue: String, constraint: CharSequence): Boolean =
            value.top > bottom && value.right > left && value.bottom < top && value.left < right

    fun updateBounds(top: Double, right: Double, bottom: Double, left: Double) {
        this.top = top
        this.right = right
        this.bottom = bottom
        this.left = left
    }
}
