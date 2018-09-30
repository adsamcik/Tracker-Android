package com.adsamcik.signalcollector.data

import com.squareup.moshi.JsonClass

/**
 * Instance that holds information about local statistics today
 */
@JsonClass(generateAdapter = true)
data class StatDay(var minutes: Int, var locations: Int, var wifi: Int, var cell: Int, var age: Int, var uploaded: Long) {


    /**
     * Adds given cell count and wifi count to this instance
     *
     * @param cellCount Number of cells
     * @param wifiCount Number of Wi-Fi networks
     */
    fun add(cellCount: Int, wifiCount: Int) {
        bumpLocation().addCell(cellCount).addWifi(wifiCount)
    }

    /**
     * Adds [StatDay] instance to this instance
     */
    operator fun plusAssign(day: StatDay) {
        locations += day.locations
        wifi += day.wifi
        cell += day.cell
        minutes += day.minutes
        uploaded += day.uploaded
    }

    fun addCell(value: Int): StatDay {
        cell += value
        return this
    }

    fun addWifi(value: Int): StatDay {
        wifi += value
        return this
    }

    fun addMinutes(value: Int): StatDay {
        minutes += value
        return this
    }

    fun bumpLocation(): StatDay {
        locations++
        return this
    }

    fun age(value: Int): Int {
        age += value
        return age
    }

    operator fun plusAssign(days: MutableList<StatDay>) {
        days.forEach { this += it }
    }
}
