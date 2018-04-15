package com.adsamcik.signalcollector.data

import com.vimeo.stag.UseStag
import java.util.*

/**
 * Data class containing information about the name and boundaries.
 * Does not use [com.adsamcik.signalcollector.utility.CoordinateBounds] because Stag does not support this level of customization for TypeAdapters and custom type adapter is not a priority right now.
 */
//todo Update to use CoordinateBounds
@UseStag
data class MapLayer(var name: String, var top: Double = MAX_LATITUDE, var right: Double = MAX_LONGITUDE, var bottom: Double = MIN_LATITUDE, var left: Double = MIN_LONGITUDE) {
    /**
     * Contains information for the legend
     */
    var values: ArrayList<ValueColor>? = null

    inner class ValueColor(val name: String, val color: Int)

    constructor() : this("")

    companion object {

        const val MIN_LATITUDE = -90.0
        const val MAX_LATITUDE = 90.0
        const val MIN_LONGITUDE = -180.0
        const val MAX_LONGITUDE = 180.0

        /**
         * Converts array of MapLayers to string
         */
        fun toStringArray(layerArray: Array<MapLayer>): Array<String> = Array(layerArray.size) { layerArray[it].name }

        /**
         * Checks if MapLayer is in given array
         */
        fun contains(layerArray: Array<MapLayer>, name: String): Boolean =
                layerArray.any { it.name == name }

        fun mockArray(): Array<MapLayer> = arrayOf(
                MapLayer("Mock", 30.0, 30.0, -30.0, -30.0),
                MapLayer("Wifi", 0.0, 30.0, -20.0, -30.0),
                MapLayer("Cell", 30.0, 30.0, -30.0, -30.0),
                MapLayer("Cell", 60.0, 30.0, -30.0, -30.0),
                MapLayer("Cell", 30.0, 30.0, -30.0, -30.0)
        )
    }
}
