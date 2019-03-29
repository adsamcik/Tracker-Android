package com.adsamcik.signalcollector.tracker.data

import com.squareup.moshi.JsonClass
import java.util.*

@JsonClass(generateAdapter = false)
data class WifiData(
        /**
         * Time of collection of wifi data
         */
        val time: Long,
        /**
         * Array of collected wifi networks
         */
        val inRange: Array<WifiInfo>) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as WifiData

        if (time != other.time) return false
        if (!Arrays.equals(inRange, other.inRange)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = time.hashCode()
        result = 31 * result + Arrays.hashCode(inRange)
        return result
    }
}