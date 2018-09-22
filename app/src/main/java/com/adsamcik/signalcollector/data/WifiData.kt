package com.adsamcik.signalcollector.data

import java.util.*

data class WifiData(
        /**
         * Time of collection of wifi data
         */
        val wifiTime: Long,
        /**
         * Array of collected wifi networks
         */
        val inRange: Array<WifiInfo>) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as WifiData

        if (wifiTime != other.wifiTime) return false
        if (!Arrays.equals(inRange, other.inRange)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = wifiTime.hashCode()
        result = 31 * result + (inRange?.let { Arrays.hashCode(it) } ?: 0)
        return result
    }
}