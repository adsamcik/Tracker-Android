package com.adsamcik.signalcollector.data

/**
 * Instance that holds information about local statistics today
 */
class StatDay {
    var age: Int = 0
    var wifi: Int = 0
    var cell: Int = 0
    var locations: Int = 0
    var minutes: Int = 0
    var uploaded: Long = 0

    constructor(minutes: Int, locations: Int, wifi: Int, cell: Int, age: Int, upload: Long) {
        this.minutes = minutes
        this.wifi = wifi
        this.cell = cell
        this.locations = locations
        this.age = age
        this.uploaded = upload
    }

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

    //STAG CONSTRUCTOR AND GETTERS AND SETTERS//

    internal constructor()

    internal fun getUpload(): Long = uploaded

    internal fun setUpload(upload: Long) {
        this.uploaded = upload
    }
}
