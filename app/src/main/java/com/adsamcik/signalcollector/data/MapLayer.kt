package com.adsamcik.signalcollector.data

import com.vimeo.stag.UseStag
import java.util.*

@UseStag
class MapLayer(name: String, top: Double = MAX_LATITUDE, right: Double = MAX_LONGITUDE, bottom: Double = MIN_LATITUDE, left: Double = MIN_LONGITUDE) {
    //internal SETTERS for STAG
    var name: String = name
        internal set
    var values: ArrayList<ValueColor>? = null
        internal set

    var top = top
        internal set
    var right = right
        internal set
    var bottom = bottom
        internal set
    var left = left
        internal set

    inner class ValueColor(val name: String, val color: Int)

    companion object {

        const val MIN_LATITUDE = -90.0
        const val MAX_LATITUDE = 90.0
        const val MIN_LONGITUDE = -180.0
        const val MAX_LONGITUDE = 180.0

        fun toStringArray(layerArray: Array<MapLayer>): Array<String> = Array(layerArray.size) { layerArray[it].name }

        fun indexOf(layerArray: Array<MapLayer>, name: String): Int {
            return layerArray.indices.firstOrNull { layerArray[it].name == name }
                    ?: -1
        }

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
